--[[ frameSdk-autolua2/engine.lua
引擎接入层：平台模块发现、帧管理器、LuaEngine 兼容表、JSON 绑定、清理与权限引导。
版权与许可见本包 contract.lua 文件头。
底层引擎：autolua2（jy-frame-engine）——图色/触控/节点为引擎直注全局，
服务型模块（file/http/thread/json/crypt/ocr/ime/ui）为全局表注入。
顶层只定义不执行引擎调用（可在 PC 无引擎环境加载）；模块句柄在 init 时填充。
--]]

local engine = {}

-- ==================== 平台开关（真机实测后可调整） ====================
engine.PIXEL_ZERO_BASED = true      -- autolua2 getColor 为 Android 0-based 坐标，懒人像素按 1-based 处理
engine.YOLO_THRESHOLD  = 0.1        -- yolo 内部低阈值（autolua2 无 yolo26 模块，当前 D 级降级保留）
engine.UI_ADD_CLOSE_BUTTON = true   -- 动态 UI 自动附加【关闭】按钮（触发 OnClose，保证脚本继续）
engine.PKG_SELF = "com.autolua.autolua2"  -- 引擎自身包名（getPackageName 兜底）

-- autolua2 引擎注册名（全局表注入为主，require 兜底探测）
local MODULE_NAMES = {
	json     = { "json", "cjson" },
	filem    = { "file" },
	http     = { "http" },
	thread   = { "thread" },
	crypt    = { "crypt" },
	ocr      = { "ocr" },
	ime      = { "ime" },
	cv       = { "cv" },
	images   = { "images" },
	event    = { "event" },
	path     = { "path" },
	project  = { "project" },
	fhttp    = { "fhttp" },
	coordinate = { "coordinate", "coord" },
}
engine.MODULE_NAMES = MODULE_NAMES

engine.M = {}   -- 模块句柄表（init 时填充）

-- 安全 require：依次尝试候选名 require，再尝试同名全局表（兼容预注入全局型引擎），全部失败返回 nil
function engine.reqAny(names)
	for _, n in ipairs(names) do
		local ok, m = pcall(require, n)
		if ok and m ~= nil then return m, n end
	end
	for _, n in ipairs(names) do
		local g = rawget(_G, n)
		if type(g) == "table" or type(g) == "userdata" then return g, n end
	end
	return nil, nil
end

-- 包内日志（print 直出，JY 层日志走 jy_sys）
function engine.log(...)
	print("[frameSdk]", ...)
end

-- ==================== 帧管理器（keepCapture/releaseCapture 语义） ====================
-- autolua2 引擎自动管理截图生命周期：keepScreen() 锁定当前帧（图色/取色/OCR 复用同帧），
-- updateScreen() 解锁并刷新。与懒人 keepCapture/releaseCapture 语义一一对应。
engine.frame = { locked = false }

function engine.frame.release()
	engine.frame.locked = false
end

-- 取当前帧原始数据（OCR recognizeRaw 用）：data, w, h, stride
function engine.frame.getFrame()
	local gf = rawget(_G, "getScreenFrame")
	if gf == nil then error("frameSdk: getScreenFrame 不可用（图色环境未注入）", 0) end
	local data, w, h, stride = gf()
	if not data then error("frameSdk: 屏幕帧不可用（请确认已授予截图能力/无障碍在线）", 0) end
	return data, w, h, stride
end

function engine.keepCapture()
	local ks = rawget(_G, "keepScreen")
	if ks ~= nil then pcall(ks) end
	engine.frame.locked = true
	return true
end

function engine.releaseCapture()
	local us = rawget(_G, "updateScreen")
	if us ~= nil then pcall(us) end
	engine.frame.release()
end

-- ==================== 截图族（snapShot / LuaEngine.snapShot / releaseBmp） ====================

-- 区域截图保存文件：懒人签名 snapShot(path [,x1,y1,x2,y2])
-- autolua2 saveScreen(x1,y1,x2,y2,path) 为 exclusive 端点（右/下边界不含），含端点语义 +1
function engine.snapShot(path, x1, y1, x2, y2)
	local ss = rawget(_G, "saveScreen")
	if ss == nil then error("frameSdk: saveScreen 不可用", 0) end
	if x1 ~= nil and y1 ~= nil and x2 ~= nil and y2 ~= nil then
		local ok, r = pcall(ss, x1, y1, x2 + 1, y2 + 1, tostring(path))
		if ok then return true end
		engine.log("snapShot 区域保存失败：", tostring(r))
		return false
	end
	local ok, r = pcall(ss, -1, -1, -1, -1, tostring(path))
	return ok
end

-- LuaEngine 兼容表（snapShot 返回 bmp 兼容对象：帧数据 + 区域，供 OCR detectWithPadding/
-- YoloUtils.getMat 使用；isRecycled/recycle 为字段式函数）
engine.LuaEngine = {}

function engine.LuaEngine.snapShot(x1, y1, x2, y2)
	local data, fw, fh, stride = engine.frame.getFrame()
	local bmp = {}
	bmp._data = data
	bmp._fw, bmp._fh, bmp._stride = fw, fh, stride
	if x1 ~= nil and y1 ~= nil and x2 ~= nil and y2 ~= nil then
		bmp._x, bmp._y = x1, y1
		bmp._rw, bmp._rh = (x2 - x1 + 1), (y2 - y1 + 1)
	else
		bmp._x, bmp._y, bmp._rw, bmp._rh = 0, 0, fw, fh
	end
	bmp.isRecycled = function() return bmp._data == nil end
	bmp.recycle = function() bmp._data = nil end
	bmp.getMat = function()
		-- cv.captureMat(x, y, w, h)（cv_lib 组合层可用时）；无 cv 返回 nil
		local cv = engine.M.cv
		if cv ~= nil and cv.captureMat ~= nil then
			local ok, m = pcall(cv.captureMat, bmp._x or 0, bmp._y or 0, bmp._rw or fw, bmp._rh or fh)
			if ok then return m end
		end
		return nil
	end
	return bmp
end

function engine.LuaEngine.releaseBmp(bmp)
	if type(bmp) == "table" and bmp.recycle ~= nil then pcall(bmp.recycle) end
end

function engine.LuaEngine.getResPath()
	local project = engine.M.project
	if project ~= nil and project.getProjectDir ~= nil then
		local ok, p = pcall(project.getProjectDir)
		if ok and type(p) == "string" then return p end
	end
	local pathm = engine.M.path
	if pathm ~= nil and pathm.getProjectDir ~= nil then
		local ok2, p2 = pcall(pathm.getProjectDir)
		if ok2 and type(p2) == "string" then return p2 end
	end
	return "."
end

function engine.LuaEngine.loadApk(path)
	engine.log("loadApk：autolua2 不支持懒人 APK 插件链（NJOcr/NCNN/LBT 路径不可用，请使用 PPDOcr/ocr 插件路径）")
	return nil
end

-- ==================== JSON 编解码（init 时绑定平台 json 模块） ====================

engine.jsonEncode = function(t) error("frameSdk: json 模块未初始化（init 未执行）", 0) end
engine.jsonDecode = function(s) error("frameSdk: json 模块未初始化（init 未执行）", 0) end

-- ==================== 平台权限引导（FrameInit 前置） ====================

function engine.bootstrapPermissions()
	-- 无障碍（nd_*/节点域依赖；NodeLib 直注全局 openAccessibility，内部 root 追加式拉起/失败跳设置页）
	local oa = rawget(_G, "openAccessibility")
	if oa ~= nil then
		local ok, r = pcall(oa)
		if not ok then engine.log("openAccessibility 引导失败：", tostring(r)) end
	end
	-- 权限状态探测（尽力告警，不阻塞）
	local event = engine.M.event
	if event ~= nil and event.getPermissionStatus ~= nil then
		local ok, st = pcall(event.getPermissionStatus)
		if ok and type(st) == "table" then
			if not st.touch then engine.log("警告：触控权限未就绪（root 或无障碍）") end
			if not st.accessibility then engine.log("警告：无障碍服务未在线，节点指令不可用") end
		end
	end
end

-- ==================== 资源清理 ====================

function engine.cleanup()
	pcall(engine.frame.release)
	local ui = engine.M.ui
	if ui ~= nil and ui.shutdown ~= nil then pcall(ui.shutdown) end
	local ocr = engine.M.ocr
	if ocr ~= nil and ocr.destroy ~= nil then pcall(ocr.destroy) end
end

-- ==================== init：加载平台模块与绑定（保持原时序语义） ====================
-- 返回 true 表示本次执行了模块装载；由 init.lua 编排调用
function engine.loadModules()
	-- 1) 加载平台模块（全局表注入为主）
	for key, names in pairs(MODULE_NAMES) do
		local m = engine.reqAny(names)
		if m ~= nil then
			engine.M[key] = m
		else
			engine.log("警告：平台模块不可用 [" .. tostring(names[1]) .. "]，对应功能将不可用")
		end
	end
	-- 1.1) 引擎直注全局探测（记录可用性，便于自检告警）
	local globals = {
		"keepScreen", "updateScreen", "getScreenFrame", "saveScreen",
		"findFeature", "findFeatureAll", "findColor", "isColor", "isFeature",
		"getColor", "getColorCount", "findImage", "findImageAll", "isImage", "loadImage",
		"UiSelector", "getRootInActiveWindow", "getBaseSize",
		"tap", "swipe", "touchDown", "touchMove", "touchUp", "keyPress", "exec", "execOut",
	}
	engine.hasGlobal = {}
	for _, g in ipairs(globals) do
		engine.hasGlobal[g] = (rawget(_G, g) ~= nil)
	end
	-- 2) JSON 编解码绑定
	local j = engine.M.json
	if j ~= nil and j.encode ~= nil and j.decode ~= nil then
		engine.jsonEncode = j.encode
		engine.jsonDecode = j.decode
	else
		engine.log("警告：json 模块不可用，jsonLib 将无法工作")
	end
	return true
end

return engine
