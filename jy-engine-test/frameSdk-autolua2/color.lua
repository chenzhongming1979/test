--[[ frameSdk-autolua2/color.lua
图色域：找色/找图/比色/取色 + 找字（OCR 仿真四格式）+ yolo（YoloV5 对象，D 级降级）。
版权与许可见本包 contract.lua 文件头。全部为纯透传实现，无坐标缩放。

底层映射（相对 frameSdk-firefly 的差异）：
- 找色走引擎直注全局 findFeature/findFeatureAll（特征串与懒人 one+ct 串同构直拼）
- 单色找色走 findColor（签名与懒人同构）
- 比色走 isColor 逐点判定（cmpColorExT 的多点 p 串）
- 找图走 findImage/findImageAll（模板为文件路径，免 base64）
- OCR 走 ocr 插件运行时 ocr.recognizeRaw（getScreenFrame 取帧）
--]]

local engine = require("frameSdk-autolua2.engine")
local compat = require("frameSdk-autolua2.compat")

local color = {}
local splitStr, trim = compat.splitStr, compat.trim
local colorToRGB = compat.colorToRGB

local function g(name)
	return rawget(_G, name)
end

-- OCR 不可用一次性告警
local ocrWarned = false
local function ocrWarnOnce()
	if not ocrWarned then
		ocrWarned = true
		engine.log("警告：ocr 插件运行时不可用（ocr.recognizeRaw 缺失），找字/OCR 功能返回空结果")
	end
end

-- ==================== 像素与颜色 ====================

-- 取单点颜色（引擎帧链路自动复用锁定帧；懒人像素 1-based → Android 0-based -1 偏移）
function color.getPixelColor(x, y)
	local gc = g("getColor")
	if gc == nil then return "0x000000" end
	local dx, dy = x, y
	if engine.PIXEL_ZERO_BASED then dx, dy = x - 1, y - 1 end
	local ok, r = pcall(gc, dx, dy)
	if not ok or r == nil then return "0x000000" end
	r = tostring(r)
	local hex = string.gsub(r, "0[xX]", "")
	hex = string.gsub(hex, "#", "")
	if #hex >= 6 then
		hex = string.sub(hex, 1, 6)
		return "0x" .. string.upper(hex)
	end
	return "0x000000"
end

function color.colorToRGB(c)
	return colorToRGB(c)
end

-- ==================== 找图模板路径缓存 ====================

local picCache = {}

-- picName → 模板文件绝对路径（约定置于 工作目录/images/ 下）
local function picPath(picName)
	local key = tostring(picName)
	if picCache[key] ~= nil then return picCache[key] end
	local work = engine.workPath or "."
	local fileio = require("frameSdk-autolua2.file_io")
	local candidates = {
		work .. "/images/" .. key .. ".png",
		work .. "/images/" .. key,
		key .. ".png",
		key,
	}
	for _, path in ipairs(candidates) do
		if fileio.fileExist(path) then
			picCache[key] = path
			return path
		end
	end
	error("frameSdk: 找不到找图模板[" .. key .. "]，请将模板图片置于 工作目录/images/", 0)
end

-- ==================== 图色兼容函数（纯透传，无坐标缩放） ====================

-- one="A5F2F9-101010" + ct="-2|-42|2E217B-101010,5|18|FFFFFF"
-- → findFeature 特征串："0|0|A5F2F9-101010,-2|-42|2E217B-101010,5|18|FFFFFF"（与引擎同构直拼）
local function buildFeature(one, ct)
	local s = "0|0|" .. tostring(one or "")
	if ct ~= nil and ct ~= "" then
		s = s .. "," .. tostring(ct)
	end
	return s
end

-- ft 多点找色（单点）：入参 {x1,y1,x2,y2,one,ct,orient,degree} → x,y（未命中 -1,-1）
function color.findMultiColorT(t)
	local x1, y1, x2, y2 = t[1], t[2], t[3], t[4]
	local one, ct = t[5], t[6]
	local degree = tonumber(t[8]) or 0.9
	local ff = g("findFeature")
	if ff == nil then return -1, -1 end
	local ok, x, y = pcall(ff, x1, y1, x2, y2, buildFeature(one, ct), degree, 1)
	if ok and type(x) == "number" and x >= 0 then return x, y end
	return -1, -1
end

-- ft 全找：8 个展开参数 → 坐标表（{x=,y=} 数组，供 tableSizer 去重）
function color.findMultiColorAll(x1, y1, x2, y2, one, ct, orient, degree)
	local out = {}
	local ffa = g("findFeatureAll")
	if ffa == nil then return out end
	local ok, pts = pcall(ffa, x1, y1, x2, y2, buildFeature(one, ct), tonumber(degree) or 0.9, 1)
	if ok and type(pts) == "table" then
		for _, p in ipairs(pts) do
			if type(p) == "table" then
				out[#out + 1] = { x = tonumber(p[1]) or tonumber(p.x) or 0, y = tonumber(p[2]) or tonumber(p.y) or 0 }
			end
		end
	end
	return out
end

-- fc 单色找色：入参 {x1,y1,x2,y2,color,orient,degree} → x,y（引擎 findColor 签名同构，未命中 -1,-1）
function color.findColorT(t)
	local x1, y1, x2, y2 = t[1], t[2], t[3], t[4]
	local c = tostring(t[5] or "")
	local degree = tonumber(t[7]) or 0.9
	if c == "" then return -1, -1 end
	local fc = g("findColor")
	if fc == nil then return -1, -1 end
	local ok, x, y = pcall(fc, x1, y1, x2, y2, c, degree, 1)
	if ok and type(x) == "number" and x >= 0 then return x, y end
	return -1, -1
end

-- ps 比色：paramAda 输出 {p串, degree} → 1/0
-- p 串为多点绝对坐标比色串 "x|y|色-偏色,..."：逐点 isColor 判定，全过返回 1
function color.cmpColorExT(t)
	local p = t[1]
	local sim = tonumber(t[2]) or 1.0
	if type(p) ~= "string" or p == "" then return 0 end
	local isCol = g("isColor")
	if isCol == nil then return 0 end
	for _, seg in ipairs(splitStr(p, ",")) do
		local parts = splitStr(seg, "|")
		if #parts < 3 then return 0 end
		local x = tonumber(trim(parts[1]))
		local y = tonumber(trim(parts[2]))
		local c = trim(parts[3])
		if x == nil or y == nil or c == "" then return 0 end
		if engine.PIXEL_ZERO_BASED then x, y = x - 1, y - 1 end
		local ok, hit = pcall(isCol, x, y, c, sim)
		if not ok or hit ~= true then return 0 end
	end
	return 1
end

-- pic 找图：8 参数 → ret,x,y（模板路径缓存；引擎 findImage 返回 x,y,idx）
function color.findPic(x1, y1, x2, y2, picName, delta_color, orient, degree)
	local path = picPath(picName)
	local fi = g("findImage")
	if fi == nil then return 0, -1, -1 end
	local ok, x, y, idx = pcall(fi, x1, y1, x2, y2, path, tonumber(degree) or 0.9, orient or 1)
	if ok and type(x) == "number" and x >= 0 then
		return 1, x, y
	end
	return 0, -1, -1
end

-- pic 全找：8 参数 → {x=,y=} 列表（findImageAll 多模板全匹配；降级 findImage 单点）
function color.findPicFast(x1, y1, x2, y2, picName, delta_color, orient, degree)
	local out = {}
	local path = picPath(picName)
	local fia = g("findImageAll")
	if fia ~= nil then
		local ok, pts = pcall(fia, x1, y1, x2, y2, path, tonumber(degree) or 0.9, orient or 1)
		if ok and type(pts) == "table" then
			for _, p in ipairs(pts) do
				if type(p) == "table" then
					out[#out + 1] = { x = tonumber(p[1]) or tonumber(p.x) or 0, y = tonumber(p[2]) or tonumber(p.y) or 0 }
				end
			end
			return out
		end
	end
	local ret, x, y = color.findPic(x1, y1, x2, y2, picName, delta_color, orient, degree)
	if ret == 1 then out[1] = { x = x, y = y } end
	return out
end

-- gcn 颜色数量：懒人参数序 (x1,y1,x2,y2,color,degree) → 引擎 getColorCount 同构
function color.getColorNum(x1, y1, x2, y2, c, degree)
	local gcc = g("getColorCount")
	if gcc == nil then return 0 end
	local ok, n = pcall(gcc, x1, y1, x2, y2, tostring(c or ""), tonumber(degree) or 1.0)
	if ok and type(n) == "number" then return n end
	return 0
end

-- ==================== 找字（fs 底层，经 OCR 统一引擎） ====================

-- 白名单过滤：空 → 任一文本块命中；否则 text 包含任一 token（懒人 | 分隔语义）
local function matchWhitelist(text, whitelist)
	if whitelist == nil or whitelist == "" then return true end
	text = tostring(text or "")
	for _, tk in ipairs(splitStr(tostring(whitelist), "|")) do
		tk = trim(tk)
		if tk ~= "" and string.find(text, tk, 1, true) then return true end
	end
	return false
end

local function strCenterAbs(baseX, baseY, it)
	return math.floor(baseX + (it.x or 0) + (it.width or 0) / 2 + 0.5),
		math.floor(baseY + (it.y or 0) + (it.height or 0) / 2 + 0.5)
end

-- fs 找字：findStrEx(x1,y1,x2,y2,偏色,白名单,degree) → ret,x,y
-- （偏色忽略：OCR 免二值化；返回屏幕绝对坐标中心）
function color.findStrEx(x1, y1, x2, y2, castColor, whitelist, degree)
	local results = color.ocr.recognizeRegion(x1, y1, x2, y2)
	for _, it in ipairs(results) do
		if (tonumber(it.prob) or 1) >= (tonumber(degree) or 0.9) and matchWhitelist(it.text, whitelist) then
			local cx, cy = strCenterAbs(x1, y1, it)
			return 1, cx, cy
		end
	end
	return 0, -1, -1
end

-- fs 全找：findStrExNew(...) → {x=,y=,text=} 列表（屏幕绝对坐标）
function color.findStrExNew(x1, y1, x2, y2, castColor, whitelist, degree)
	local out = {}
	local results = color.ocr.recognizeRegion(x1, y1, x2, y2)
	for _, it in ipairs(results) do
		if (tonumber(it.prob) or 1) >= (tonumber(degree) or 0.9) and matchWhitelist(it.text, whitelist) then
			local cx, cy = strCenterAbs(x1, y1, it)
			out[#out + 1] = { x = cx, y = cy, text = it.text }
		end
	end
	return out
end

-- ==================== OCR 统一引擎（ocr 插件运行时） ====================
-- 懒人 4 条 OCR 引擎路径（PaddleOcr/NJOcr/NCNN插件/PPv5）统一收敛为 ocr.recognizeRaw
-- （getScreenFrame 取帧），输出严格仿真框架期待的懒人 JSON 格式。
-- 坐标基准注记：recognizeRaw 返回的块坐标相对整帧（引擎 compat 的 ocrExNew 对结果
-- 减去区域偏移可证），此处按"帧绝对 → 减区域原点 = 相对区域坐标"归一化，
-- 与 firefly 包 recognizeRegion 的"坐标相对传入区域"契约对齐。

-- 从原始帧数据直接识别：data, fw, fh, stride, x, y, w, h → 归一化块数组（坐标相对帧）
local function recognizeFrame(data, fw, fh, stride, x, y, w, h)
	local ocr = engine.M.ocr
	if ocr == nil or ocr.recognizeRaw == nil then
		ocrWarnOnce()
		return {}
	end
	local ok, blocks = pcall(ocr.recognizeRaw, data, fw, fh, stride, x, y, w, h)
	if not ok or type(blocks) ~= "table" then
		engine.log("ocr.recognizeRaw 失败：", tostring(blocks))
		return {}
	end
	local out = {}
	for _, b in ipairs(blocks) do
		if type(b) == "table" and b.text ~= nil and b.text ~= "" then
			out[#out + 1] = {
				x = tonumber(b.x) or 0,
				y = tonumber(b.y) or 0,
				width = tonumber(b.w) or tonumber(b.width) or 0,
				height = tonumber(b.h) or tonumber(b.height) or 0,
				text = tostring(b.text),
				prob = tonumber(b.confidence) or tonumber(b.prob) or tonumber(b.score) or 1,
			}
		end
	end
	return out
end

-- 提取 bmp 兼容对象 / 原始图片对象 → (data, fw, fh, stride)
local function unwrapFrame(o)
	if type(o) == "table" and o._data ~= nil then
		return o._data, o._fw, o._fh, o._stride
	end
	return nil
end

color.ocr = {}

-- 区域识别：返回 { {x=,y=,width=,height=,text=,prob=}, ... }（坐标相对传入区域）
function color.ocr.recognizeRegion(x1, y1, x2, y2)
	local data, fw, fh, stride = engine.frame.getFrame()
	x2 = x2 or (fw - 1)
	y2 = y2 or (fh - 1)
	local bx = (x1 ~= nil) and x1 or 0
	local by = (y1 ~= nil) and y1 or 0
	local blocks = recognizeFrame(data, fw, fh, stride, bx, by, x2 - bx + 1, y2 - by + 1)
	for _, b in ipairs(blocks) do
		b.x = b.x - bx
		b.y = b.y - by
	end
	return blocks
end

-- 全图识别（bmp 兼容对象 / 图片路径）：返回块数组（坐标相对整图）
function color.ocr.recognizeImg(imgOrBmp)
	local data, fw, fh, stride = unwrapFrame(imgOrBmp)
	if data ~= nil then
		return recognizeFrame(data, fw, fh, stride, 0, 0, fw, fh)
	end
	-- 图片路径：loadImage → images.getData/getWidth/getHeight → 帧数据识别
	local path = tostring(imgOrBmp or "")
	local li = g("loadImage")
	if li == nil then
		engine.log("OCR recognizeImg：loadImage 不可用，无法识别图片 ", path)
		return {}
	end
	local ok, img = pcall(li, path)
	if not ok or img == nil then
		engine.log("OCR recognizeImg：读取图片失败 ", path, tostring(img))
		return {}
	end
	local images = engine.M.images
	local w, h = 0, 0
	if images ~= nil then
		local okW, wv = pcall(images.getWidth, img)
		local okH, hv = pcall(images.getHeight, img)
		w = (okW and tonumber(wv)) or 0
		h = (okH and tonumber(hv)) or 0
	end
	local dataStr
	if images ~= nil and images.getData ~= nil then
		local okD, dv = pcall(images.getData, img)
		dataStr = okD and dv or nil
	end
	if dataStr == nil or w <= 0 or h <= 0 then
		engine.log("OCR recognizeImg：images.getData/getWidth/getHeight 不可用")
		return {}
	end
	local blocks = recognizeFrame(dataStr, w, h, w * 4, 0, 0, w, h)
	pcall(function() if img.release ~= nil then img:release() end end)
	return blocks
end

-- ==================== PaddleOcr 兼容表（PDOcr 格式，包内导出、不进 api 命令面） ====================
-- PDOcr JSON 格式：[{"x":0,"y":4,"w":62,"h":41,"label":"-蓝色","prob":0.92}, ...]
-- 坐标相对传入区域（回填 ocrParams[1]/[2] 偏移）

local PaddleOcr = {}
color.PaddleOcr = PaddleOcr

function PaddleOcr.loadModel(flag)
	-- ocr 插件运行时自管理模型加载，此函数保持兼容返回 true
	if engine.M.ocr == nil or engine.M.ocr.recognizeRaw == nil then
		ocrWarnOnce()
		return false
	end
	return true
end

local function pdoJson(results)
	local arr = {}
	for _, it in ipairs(results) do
		arr[#arr + 1] = {
			x = it.x, y = it.y, w = it.width, h = it.height,
			label = it.text, prob = it.prob,
		}
	end
	local ok, s = pcall(engine.jsonEncode, arr)
	if ok and type(s) == "string" then return s end
	return "[]"
end

-- detect(left,top,right,bottom) → PDOcr JSON 串（坐标相对区域）
function PaddleOcr.detect(left, top, right, bottom)
	return pdoJson(color.ocr.recognizeRegion(left, top, right, bottom))
end

-- detectWithPadding(bitmap,padding,r,g,b) → PDOcr JSON 串
-- v1：padding/RGB 过滤忽略（OCR 免二值化；M3 可加二值化预过滤提精度）
function PaddleOcr.detectWithPadding(bitmap, padding, r, g, b)
	return pdoJson(color.ocr.recognizeImg(bitmap))
end

-- ==================== PPv5_OCR 兼容表（LBTOcr 格式，包内导出、不进 api 命令面） ====================
-- LBTOcr JSON 格式：[{"label":"...","prob":0.97,"bounds":[[x,y],[x+w,y],[x+w,y+h],[x,y+h]]}, ...]

local PPv5 = {}
color.PPv5_OCR = PPv5

function PPv5.loadModelFromPath(path, ocrType, size, gpu)
	-- autolua2 用 ocr 插件内置模型，此函数保持兼容返回 true
	return true
end

function PPv5.detect(bmp)
	local arr = {}
	for _, it in ipairs(color.ocr.recognizeImg(bmp)) do
		local bx1, by1 = it.x, it.y
		local bx2, by2 = it.x + it.width, it.y + it.height
		arr[#arr + 1] = {
			label = it.text, prob = it.prob,
			bounds = { { bx1, by1 }, { bx2, by1 }, { bx2, by2 }, { bx1, by2 } },
		}
	end
	local ok, s = pcall(engine.jsonEncode, arr)
	if ok and type(s) == "string" then return s end
	return "[]"
end

-- ==================== NCNN OCR 兼容（ocrServer.detectBitmap → NCNNOcr 格式，不进 api 命令面） ====================
-- NCNNOcr JSON 格式：{"lines":[{"box":[15,42,378,82],"text":"..."}, ...]}
-- box 相对裁剪位图（回填 ocrParams[1]/[2] 偏移）

local ncnnCompat = {}
color.ncnnCompat = ncnnCompat

function ncnnCompat.detectBitmap(bmp, white, box, c)
	local lines = {}
	for _, it in ipairs(color.ocr.recognizeImg(bmp)) do
		lines[#lines + 1] = {
			box = { it.x, it.y, it.x + it.width, it.y + it.height },
			text = it.text,
		}
	end
	local ok, s = pcall(engine.jsonEncode, { lines = lines })
	if ok and type(s) == "string" then return s end
	return "{\"lines\":[]}"
end

-- ==================== NJOcr 兼容（本机 1666 服务 → 本地仿真） ====================
-- NJOcr JSON 格式：{"data":[{"x":12,"y":34,"text":"..."}, ...]}
-- 懒人流程：区域截图落盘 → httpPost(path=...&type=...) → 坐标相对截图

function color.njocrSimulate(body)
	-- 解析 body 中 path= 参数（表单串 "path=...&type=..."）
	local path = string.match(tostring(body or ""), "path=([^&]+)")
	if path == nil then return "{\"data\":[]}" end
	path = trim(path)
	local li = g("loadImage")
	if li == nil then
		engine.log("NJOcr 仿真：loadImage 不可用")
		return "{\"data\":[]}"
	end
	local ok, img = pcall(li, path)
	if not ok or img == nil then
		engine.log("NJOcr 仿真：读取截图失败 ", path)
		return "{\"data\":[]}"
	end
	local arr = {}
	for _, it in ipairs(color.ocr.recognizeImg(path)) do
		arr[#arr + 1] = { x = it.x, y = it.y, text = it.text }
	end
	pcall(function() if img.release ~= nil then img:release() end end)
	local ok2, s = pcall(engine.jsonEncode, { data = arr })
	if ok2 and type(s) == "string" then return s end
	return "{\"data\":[]}"
end

-- ==================== yolo 兼容（YoloV5 对象，D 级降级） ====================
-- autolua2 引擎当前无 yolo26/yolo 推理模块：init 记录路径并校验文件（模型随包迁移到位），
-- detect 返回空结果 + 一次性告警。待引擎接入 yolo 推理后仅替换本节实现，契约不变。

local yoloWarned = false
local function yoloWarnOnce()
	if not yoloWarned then
		yoloWarned = true
		engine.log("警告：autolua2 无 yolo 推理模块，YoloV5 检测返回空结果（yolo_* 指令不可用）")
	end
end

local yoloState = { paths = nil }

local YoloV5 = {}

function YoloV5.init(labelPath, paramPath, binPath)
	local paths = { labelPath, paramPath, binPath }
	local fileio = require("frameSdk-autolua2.file_io")
	for _, p in ipairs(paths) do
		if p ~= nil and not fileio.fileExist(p) then
			engine.log("YoloV5.init 模型文件不存在：", tostring(p))
			return false
		end
	end
	yoloState.paths = paths
	return true
end

-- detect(bmp, gpu) → JSON 串 []（D 级降级：保持返回格式，内容为空）
function YoloV5.detect(bmp, gpu)
	yoloWarnOnce()
	return "[]"
end

color.YoloV5 = YoloV5

return color
