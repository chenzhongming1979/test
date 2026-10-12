--[[
Copyright (c) 2026 成都林深见鹿网络科技有限公司 All Rights Reserved.

本代码基于 Apache License 2.0 协议开源：
1. 个人学习、非商业用途：可免费使用、修改、分发本代码；
2. 商业用途、企业使用：必须联系作者获取书面授权后方可使用；
3. 任何使用场景下，禁止删除本版权声明及相关协议说明；
4. 若进行二次开发/衍生，需在衍生作品的显著位置保留本版权声明，并标注衍生说明。

你可以获取 Apache License 2.0 完整协议文本：
http://www.apache.org/licenses/LICENSE-2.0
--]]

local JY = require("JY.Utils.JY")
local jy_ui = require("JY.Utils.UIUtils")
--[[ ================== jy_frame 框架特有方法 ==================]]


local jy_frame = {}
-- 运算符处理映射表
local opHandlers = {
	["=="] = function(a, b) return a == b end,
	[">"] = function(a, b) return a > b end,
	["<"] = function(a, b) return a < b end,
	["~="] = function(a, b) return a ~= b end,
	[">="] = function(a, b) return a >= b end,
	["<="] = function(a, b) return a <= b end,
	["inStr"] = function(a, b) return string.find(tostring(a), tostring(b), 1) ~= nil end,
	["ms"] = function(a, b)
		local currentMs = frameSdk.systemTime() -- frameSdk 迁移：systemTime 经 frameSdk 命令表调用
		local targetMs = tonumber(a) or 0
		return (currentMs - targetMs) > (tonumber(b) or 0)
	end
}


-- 是否是框架比较运算
local function isCompar(t, i)
	local VALID_COMPARATORS = {
		["=="] = true,
		[">"] = true,
		["<"] = true,
		["~="] = true,
		[">="] = true,
		["<="] = true,
		["inStr"] = true,
		["ms"] = true
	}
	-- 快速失败：检查表有效性
	if type(t) ~= "table" then
		return false
	end

	-- 获取目标值并验证类型
	local value = t[i]
	if type(value) ~= "string" or value == "" or value == "null" then
		return false
	end

	-- 使用哈希表快速查询（O(1)时间复杂度）
	return VALID_COMPARATORS[value] or false
end
jy_frame.isCompar = isCompar

-- 点击坐标是否在排除范围检查
local function excheck(v2, p)
	local ret = false
	if v2["ex"] ~= nil then
		local ex = v2["ex"]
		local x_b = (p["x"] > ex(0) and p["x"] < ex(2)) or (p["x"] < ex(0) and p["x"] > ex(2))
		local y_b = (p["y"] > ex(1) and p["y"] < ex(3)) or (p["y"] < ex(1) and p["y"] > ex(3))
		if x_b and y_b then
			ret = true
			JY.jy_sys.acLog("在指定范围内不点击", v2)
		end
	end
	return ret
end
jy_frame.excheck = excheck

--[[
NJOcr
	{"status":200,"data":[
			{"text":"色技能书*1=五分钟通用加速*2","x":194,"y":17},
			{"text":"色技能书*1=五分钟通用加速*5","x":194,"y":64}
		]
	}
NcnnOcr
	{"lines":[
		{"box":[15,42,378,82],"text":"-紫色技能书*1=五分钟通用加速*5"},
		{"box":[16,0,378,35],"text":"蓝色技能书*1=五分钟通用加速*2"}
		],
	"time":"1142.2ms"}
PDOcr
	[
		{"x":0,"y":4,"w":62,"h":41,"label":"-蓝色","prob":0.923976719379425},
		{"x":0,"y":52,"w":59,"h":40,"label":"一紫色","prob":0.9662986993789673}
	]
LBTOcr
	[
		{"label":"-紫色技能书*1=五分钟通用加速*5","prob":0.9719,"box":{"cx":193.0,"cy":62.2,"w":21.0,"h":358.3,"angle":90.21}},
		{"label":"-蓝色技能书*1=五分钟通用加速*2","prob":0.9957,"box":{"cx":192.0,"cy":15.5,"w":21.5,"h":362.5,"angle":90.00}]}
	]
]]

local ocrPoint = {
	["PDOcr"] = function(ocrJson, range)
			return jy_frame.clickRange({range[1] + ocrJson.x, range[2] + ocrJson.y, range[1] + ocrJson.w, range[2] + ocrJson.h})
		end,
	["NJOcr"] = function(ocrJson, range)
			return Point(range[1] + ocrJson.x,range[2] + ocrJson.y)
		end,
	["NCNNOcr"] = function(ocrJson, range)
			local x1 = range[1] + ocrJson.box[1]
			local y1 = range[2] + ocrJson.box[2]
			local x2 = range[1] + ocrJson.box[3]
			local y2 = range[2] + ocrJson.box[4]
			return jy_frame.clickRange({x1, y1, x2, y2})
		end,
	["LBTOcr"] = function(ocrJson, range)
			local x1 = range[1] + JY.jy_num.toFixed(ocrJson.bounds[1][1])
			local y1 = range[2] + JY.jy_num.toFixed(ocrJson.bounds[2][2])
			local x2 = range[1] + JY.jy_num.toFixed(ocrJson.bounds[3][1])
			local y2 = range[2] + JY.jy_num.toFixed(ocrJson.bounds[3][2])
			return jy_frame.clickRange({x1, y1, x2, y2})
		end,

}
local function decodeOcrPoint(jsonStr, ocrModel, ocrParams,str)
	local data = nil
	local label = "text"
	if ocrModel == "NJOcr" then
		data = "data"
	elseif ocrModel == "NCNNOcr" then
		data = "lines"
	elseif ocrModel == "PDOcr" or ocrModel == "LBTOcr" then
		label = "label"
	end
    -- 5. 解析JSON结果
    local success, resultData = pcall(frameSdk.jsonLib.decode, jsonStr)
    if not success or type(resultData) ~= "table" then
        return nil
    end
	local result = ""
	local retData = Iif(data == nil, resultData, resultData[data])
	local points = {}
	local strs = frameSdk.splitStr(str, "|")
	if retData== nil or #retData == 0 then
		return nil
	else
		for key, value in pairs(retData) do
			for _, s in pairs(strs) do
				if string.find(value[label],s) then
					table.insert(points,ocrPoint[ocrModel](value, ocrParams))
				end
			end

		end
	end
    return points
end
jy_frame.decodeOcrPoint = decodeOcrPoint
local function decodeOcrStr(jsonStr, ocrModel)
	local data = nil
	local label = "text"
	if ocrModel == "NJOcr" then
		data = "data"
	elseif ocrModel == "NCNNOcr" then
		data = "lines"
	elseif ocrModel == "PDOcr" or ocrModel == "LBTOcr" then
		label = "label"
	end
    -- 5. 解析JSON结果
    local success, resultData = pcall(frameSdk.jsonLib.decode, jsonStr)
    if not success or type(resultData) ~= "table" then
        return nil
    end
	local result = ""
	local retData = Iif(data == nil, resultData, resultData[data])
	if retData== nil or #retData == 0 then
		return nil
	else
		for key, value in pairs(retData) do
			result = result .. value[label]
		end
	end
    return result
end
--[[文字识别
参数1： v2 特征
参数2： isNumber 是否转换为数字
 * @return 识别结果
]]

local ocrModel_Handlers = {
	-- 低版本懒人注释
	["PDOcr"] = function(v2)
			return JY.jy_ocr.performOcrWithSnapshot(v2["ocrParams"][1], v2["ocrParams"][2],v2["ocrParams"][3],v2["ocrParams"][4],v2["rgb"],v2["padding"] ,v2["ocrEx"])
		end,

	["NJOcr"] = function(v2)
			return JY.jy_ocr.NJOcr(v2["ocrParams"][1], v2["ocrParams"][2],v2["ocrParams"][3],v2["ocrParams"][4],v2["type"])
		end,
	["NCNNOcr"] = function(v2)
			return JY.jy_ocr.NCNNOcr(v2["ocrParams"][1], v2["ocrParams"][2],v2["ocrParams"][3],v2["ocrParams"][4],v2["ocrParams"][5],v2["ocrParams"][6],v2["ocrParams"][7])
		end,
	["LBTOcr"] = function(v2)
			return JY.jy_ocr.LBTOcr(v2["ocrParams"][1], v2["ocrParams"][2],v2["ocrParams"][3],v2["ocrParams"][4])
		end,

}

local function findOcrStr(v2)
	local ocrModel = v2.ocrModel or "PDOcr"
	local ret = nil
	local ocrHandler = ocrModel_Handlers[ocrModel]
	if ocrHandler then
		ret = ocrHandler(v2)
	else
		JY.jy_sys.acLog("OCR ocrModel参数错误，无该模型" .. ocrModel, v2)
	end
	local retPoint = decodeOcrPoint(ret,ocrModel, v2.ocrParams,v2.findStr)
	-- 添加空值检查，防止 retPoint 为 nil 时访问索引报错
	if IsEmpty(retPoint) or #retPoint == 0 then
		return Point(-1, -1)
	end
	return retPoint[1]
end
jy_frame.findOcrStr = findOcrStr

local function findOcrStrAll(v2)
	local ocrModel = v2.ocrModel or "PDOcr"
	local ret = nil
	local ocrHandler = ocrModel_Handlers[ocrModel]
	if ocrHandler then
		ret = ocrHandler(v2)
	else
		JY.jy_sys.acLog("OCR ocrModel参数错误，无该模型" .. ocrModel, v2)
	end
	return decodeOcrPoint(ret, ocrModel, v2.ocrParams,v2.findStr)

end
jy_frame.findOcrStrAll = findOcrStrAll

local function smOcr(v2, isNumber)
	local ocrModel = v2.ocrModel or "PDOcr"
	local ret = nil
	local ocrHandler = ocrModel_Handlers[ocrModel]
	if ocrHandler then
		ret = ocrHandler(v2)
	else
		JY.jy_sys.acLog("OCR ocrModel参数错误，无该模型" .. ocrModel, v2)
	end
	ret = decodeOcrStr(ret, v2.ocrModel)
	JY.jy_sys.acLog("文字识别结果：" .. tostring(ret), v2)
	if isNumber and not IsEmpty(ret) then
		ret = JY.jy_num.extractNumbers(ret)
	end
	if IsEmpty(ret) then
		ret = v2["defult"]
	end
	return ret
end
jy_frame.smOcr = smOcr

--[[识别数字
参数 v2 特征
 * @return 识别结果
]]
local function smartOcr(v2) return smOcr(v2, true) end
jy_frame.ocr_num = smartOcr

--[[识别所有文字
参数 v2 特征
 * @return 识别结果
]]
local function jyOcrText(v2) return smOcr(v2, false) end
jy_frame.ocr_str = jyOcrText

--[[在指定范围内获取随机坐标
参数 range[table] : 范围{左上x,左上y,右下x,右下y}
返回值：返回坐标
]]
local function clickRange(range)
	local x, y
	if range[1] > range[3] then
		x = math.random(JY.jy_num.toNumber(range[3]), JY.jy_num.toNumber(range[1]))
	else
		x = math.random(JY.jy_num.toNumber(range[1]), JY.jy_num.toNumber(range[3]))
	end

	if range[2] > range[4] then
		y = math.random(JY.jy_num.toNumber(range[4]), JY.jy_num.toNumber(range[2]))
	else
		y = math.random(JY.jy_num.toNumber(range[2]), JY.jy_num.toNumber(range[4]))
	end
	return Point(x, y,false, range[5])
end
jy_frame.clickRange = clickRange

--特征命令格式检查
local function paramAda(...)
	local argCnt = { ... }
	local v
	if type(argCnt[1]) == "number" then
		return argCnt
	elseif type(argCnt[1]) == "table" then
		v = argCnt[1]
	end
	if frameSdk.utf8.inStr(0, v["ff"], "fc") > 0 then
		if v["color"] == nil or type(v["color"]) ~= "string" then
			JY.jy_sys.acLog("命令格式错误，缺少 color 参数或格式错误", v)
			frameSdk.exitScript()
		end
		--return {v.r[1], v.r[2], v.r[3], v.r[4], v["color"],v["orient"] or 0,v["degree"] or 0.9}
		return v
	end

	if frameSdk.utf8.inStr(0, v["ff"], "ps") > 0 then
		if v["p"] == nil or type(v["p"]) ~= "string" then
			JY.jy_sys.acLog("命令格式错误，缺少 p 参数或格式错误", v)
			frameSdk.exitScript()
		end
		return { v["p"], v["degree"] }
		--return v
	end

	if frameSdk.utf8.inStr(0, v["ff"], "ft") > 0 then
		if v["one"] == nil or v["ct"] == nil or type(v["one"]) ~= "string" or type(v["ct"]) ~= "string" then
			JY.jy_sys.acLog("命令格式错误，缺少 one 或者 ct 参数或格式错误", v)
			frameSdk.exitScript()
		end
		--return {v["r"][1], v["r"][2], v["r"][3], v["r"][4], v["one"], v["ct"], v["orient"] or 0, v["degree"] or 0.9}
		return v
	end

	if frameSdk.utf8.inStr(0, v["ff"], "pic") > 0 then
		if v["picName"] == nil or type(v["picName"]) ~= "string" then
			JY.jy_sys.acLog("命令格式错误，缺少 picName 参数或格式错误", v)
			frameSdk.exitScript()
		end
		--return {v["r"][1], v["r"][2], v["r"][3], v["r"][4], v["picName"], v["delta_color"], v["orient"] or 0, v["degree"] or 0.9}
		return v
	end
	if frameSdk.utf8.inStr(0, v["ff"], "fs") > 0 then
		if v["ocrParams"] == nil or type(v["ocrParams"]) ~= "table" then
			JY.jy_sys.acLog("命令格式错误，缺少 ocrParams 参数或格式错误", v)
			frameSdk.exitScript()
		end
		--return {v["r"][1], v["r"][2], v["r"][3], v["r"][4], v["picName"], v["delta_color"], v["orient"] or 0, v["degree"] or 0.9}
		return v
	end
	JY.jy_sys.acLog("[ff]命令错误，无指定命令", v)
	frameSdk.exitScript()
end
jy_frame.paramAda = paramAda
-- 框架比较
local function compar(var1, op, var2, varType)
	local function safeCompare()
		-- 统一类型处理
		local function convertValue(val, targetType)
			if targetType == "num" then
				return (val == nil or val == "") and -1 or tonumber(val) or -1
			elseif targetType == "str" then
				return tostring(val or "")
			end
			return val
		end


		-- 特殊运算符类型强制转换
		if op == ">" or op == "<" or op == ">=" or op == "<=" or op == "ms" then
			varType = "num"
		end

		-- 统一值转换
		local v1 = convertValue(var1, varType)
		local v2 = convertValue(var2, varType)

		-- 执行运算符逻辑
		local handler = opHandlers[op]
		if not handler then
			error("Unsupported operator: " .. tostring(op))
		end

		return handler(v1, v2)
	end

	-- 错误处理
	local success, result = pcall(safeCompare)
	if success then
		return result
	else
		print(string.format("COMPAR  ERROR | %s %s %s | %s",
			tostring(var1), op, tostring(var2), result))
		return false
	end
end
jy_frame.compar = compar
--找色
local function MFindColor(...)
	local v = paramAda(...)
	local x, y = frameSdk.findColorT(({ v["r"][1], v["r"][2], v["r"][3], v["r"][4], v["color"], v["orient"] or 0, v["degree"] or 0.9 }))
	return Point(x, y)
end
jy_frame.MFindColor = MFindColor
--单点多点
local function mCmpColor(...)
	local v = paramAda(...)
	local code = frameSdk.cmpColorExT(v)
	if code == 1 then
		return true;
	end

	return false;
end
jy_frame.mCmpColor = mCmpColor


local function mCmpColorEx(v2)
	if string.find(v2["ff"], "ps_b") ~= nil then
		return jy_frame.mCmpColorB(v2)
	else
		local ret = jy_frame.mCmpColor(v2)
		if ret then
			local tapStrs = frameSdk.splitStr(v2["p"], "|")
			return Point(tonumber(tapStrs[1]), tonumber(tapStrs[2]), v2[2])
		else
			return Point(-1, -1)
		end
	end
end
jy_frame.mCmpColorEx = mCmpColorEx

--多个单点多点
local function mCmpColorB(v2)
	if type(v2["ps"]) ~= "table" then
		return Point(-1, -1)
	end
	local point = Point(-1, -1)
	local taps = G.taps()
	for key, value in pairs(v2["ps"]) do
		if string.find(value,"|") then
			if mCmpColor({ v2[1], v2[2], ff = "ps", taskName = v2.taskName, name = v2.name, p = value, degree = v2["degree"] or 0.9 }) then
				local tapStrs = frameSdk.splitStr(value, "|")
				point = Point(tonumber(tapStrs[1]), tonumber(tapStrs[2]), v2[2])
				return point
			end
		else
			local p = taps["通用"][value]
			if mCmpColor(p) then
				local tapStrs = frameSdk.splitStr(p["p"], "|")
				point = Point(tonumber(tapStrs[1]), tonumber(tapStrs[2]), v2[2])
				return point
			end
		end

	end
	return point
end
jy_frame.mCmpColorB = mCmpColorB
--多点范围
local function myFind(...)

	local v = paramAda(...)

	local x, y = frameSdk.findMultiColorT({ v["r"][1], v["r"][2], v["r"][3], v["r"][4], v["one"], v["ct"], v["orient"] or 0, v
	["degree"] or 0.9 })

	return Point(x, y)
end
jy_frame.myFind = myFind
--多点范围全部
local function myFind_all(...)
	local v = paramAda(...)
	return frameSdk.findMultiColorAll(v["r"][1], v["r"][2], v["r"][3], v["r"][4], v["one"], v["ct"], v["orient"] or 0, v["degree"] or 0.9)
end
jy_frame.myFind_all = myFind_all
--找图
local function mFindPic(...)
    local v = paramAda(...)
    local ret,x,y = frameSdk.findPic(v["r"][1], v["r"][2], v["r"][3], v["r"][4], v["picName"], v["delta_color"], v["orient"] or 0, v["degree"] or 0.9)
    return Point(x,y)
end
jy_frame.mFindPic = mFindPic
--找图全部
local function mFindPic_all(...)
	local v = paramAda(...)
	local retList = frameSdk.findPicFast(v[1], v[2], v[3], v[4], v[5], v[6], v[7] or 0, v[8] or 0.9)
	return retList
end
jy_frame.mFindPic_all = mFindPic_all
--找字
local function MFindStr(...)
	local v = paramAda(...)
	local ret,x,y = frameSdk.findStrEx(v.ocrParams[1], v.ocrParams[2], v.ocrParams[3], v.ocrParams[4], v.ocrParams[5], v.ocrParams[6],
		v.degree or 0.9)
	return Point(x,y)
end
jy_frame.MFindStr = MFindStr
local function MFindStrAll(...)
	local v = paramAda(...)
	return frameSdk.findStrExNew(v.ocrParams[1], v.ocrParams[2], v.ocrParams[3], v.ocrParams[4], v.ocrParams[5], v.ocrParams[6],
		v.degree or 0.9)
end
jy_frame.MFindStr = MFindStr
-- D8 修复（FireFly 移植版）：原版此处误写为 jy_frame.MFindStr = MFindStr 覆盖了全找导出
jy_frame.MFindStrAll = MFindStrAll

local function findStr(v2)
	if v2.ocrModel==nil then
		return MFindStr(v2)
	else
		return jy_frame.findOcrStr(v2)
	end

end
jy_frame.findStr = findStr

local function findStr_all(v2)
	if v2.ocrModel==nil then
		return MFindStrAll(v2)
	else
		return jy_frame.findOcrStrAll(v2)
	end

end
jy_frame.findStr_all = findStr_all
--颜色数量
local function findColorNum(v2)
	local ret = v2["r"]
	local degree = v2["degree"] or 1.0
	return frameSdk.getColorNum(ret[1], ret[2], ret[3], ret[4], v2["color"], degree)
end
jy_frame.findColorNum = findColorNum

-- 功能: 根据配置设置 HUD 文本（若未启用浮窗则为幂等空操作）
local function setHUDTextWrapper(hudId, text)
	if G.config("显示浮窗") then
		jy_frame.setHUDText(hudId, text)
	end
end
jy_frame.setHUDTextWrapper = setHUDTextWrapper

--[[
函数名: comClick
功能描述: 框架通用点击 - 根据特征配置执行点击操作的统一入口

参数说明:
    p (table/Point): 点击坐标对象，包含x、y属性
    v (table): 特征配置对象，包含以下可选字段
        - count (number): 点击次数，默认1次
        - isClick (boolean): 是否执行点击，默认true；false时仅记录坐标不点击
        - wait (number): 点击后延迟时间(毫秒)，默认使用conf["comSleep"]
        - countSleep (number): 多次点击间隔时间(毫秒)
        - tape (table): 指定点击坐标，优先于p参数
        - clickRange (table): 随机点击范围 {左上x, 左上y, 右下x, 右下y}
        - offset (table): 坐标偏移 {x, y}
        - lwait/longWait (number): 长按时间(毫秒)

返回值:
    无

内部处理:
    1. 优先使用clickRange生成随机点击坐标
    2. 其次使用tape指定坐标
    3. 最后使用p坐标（可叠加offset偏移）
    4. 根据count执行多次点击
    5. 记录点击日志

使用示例:
    -- 基础点击
    comClick(Point(100, 200), {wait = 500})

    -- 多次点击
    comClick(Point(100, 200), {count = 3, countSleep = 200})

    -- 范围内随机点击
    comClick(Point(100, 200), {clickRange = {50, 150, 150, 250}})

    -- 仅记录不点击
    comClick(Point(100, 200), {isClick = false})
]]
local function comClick(p, v)
	local point = p
	local count = v["count"] or 1
	local isClick = Iif(v["isClick"] ~= nil, v["isClick"], true)
	local mstime = v["wait"] or G.config("comSleep")
	local countSleep = v["countSleep"] or v["cw "] or G.config("countSleep")
	local mPoint = v["tape"]
	local lwait = v["lwait"] or v["longWait"]
	local tape
	if count == 1 then
		countSleep = 0
	end

	local function mclick(t)
		for i = 1, count, 1 do
			JY.jy_touch.click(t, lwait);
			JY.jy_sys.sleep(countSleep);
		end
		if count > 1 then
			JY.jy_sys.acLog("点击坐标：" .. t["x"] .. "," .. t["y"] .. " " .. count .. "次", v);
		else
			JY.jy_sys.acLog("点击坐标：" .. t["x"] .. "," .. t["y"], v);
		end
	end
	if isClick then
		if v.clickRange ~= nil then
			tape = clickRange(v.clickRange)
			mclick(tape)
		else
			if mPoint ~= nil then
				tape = mPoint
			else
				if v.offset ~= nil then
					point.x = point.x + v.offset.x
					point.y = point.y + v.offset.y
				end
				tape = point
			end
			mclick(tape)
		end

	else
		JY.jy_sys.acLog("找到坐标：" .. point["x"] .. "," .. point["y"] .. " ----- 不点击", v);
	end
	JY.jy_sys.sleep(mstime)
end
jy_frame.comClick = comClick

-- Point 函数已统一至 JY.lua 中定义，此处不再重复导出

--[[设置浮窗显示文字
说明：
	更改浮窗显示的内容
参数：
	HUDID： 浮窗ID
	text： 显示内容
返回：
	无
]]
local function setHUDText(HUDID, text)
	if G.config("显示浮窗") ~= true then
		return
	end
	local hudCoords = G.config("浮窗坐标")
	frameSdk.showHUD(
		HUDID , text ,
		G.config("浮窗文字大小") , G.config("字体颜色") or "0xffff0000" , G.config("背景颜色") or "0xffffffff" , 0 ,
		hudCoords[1] , hudCoords[2] , hudCoords[3] - hudCoords[1] ,
		hudCoords[4] - hudCoords[2] , 0 , 0 , 8
	)
end
jy_frame.setHUDText = setHUDText


local function taskParamSet(startMode, execFun, execMode, execInterval, enterTimeout, hasSetting, execCount, UIID, preAction)

    local hasSettingVar = Iif(hasSetting == nil, true, hasSetting )
	hasSettingVar = Iif(UIID ~= nil, false, hasSettingVar)
    local set = {}
    set["启动方法"] = startMode or 0			--0主界面启动（找色），1指定方法启动，2主界面启动（直接点击）
    set["执行方法"] = execFun				--执行指定方法
    set["执行模式"] = execMode or 0			--0:不限制 1、每天一次 2、每周一次 3、每月一次
    set["执行间隔时间"] = execInterval or -1		-- -1:不限制 自定义间隔时间
    set["进入任务超时时间"] = enterTimeout or 3000	--弃用
    set["UIID"] = UIID						--对应设置UI的ID，不填默认为参数1
    set["是否有对应设置"] = hasSettingVar
    set["执行次数"] = execCount or -1
    set["任务执行前操作"] = preAction
    return set
end
-- FireFly 移植版：函数名改 ASCII（taskParamSet），导出键同步更名；语义不变
jy_frame.taskParamSet = taskParamSet


local function createUserFile()
    local accFile = JY.jy_file.fileGet("账号文件")
    local acc,pwd
    local accCount = G.config("账号数量") or 10
    local acc_1 = jy_ui.getUISet("账号1",nil)
    if not IsEmpty(acc_1) then
        if not IsEmpty(accFile) then
			JY.jy_file.mWriteFile(accFile ,"",false,true) --清空文件
			local accList = {}
			for i = 1, accCount, 1 do
                acc = JY.jy_str.trim(jy_ui.getUISet("账号" .. i,""))
                pwd = JY.jy_str.trim(jy_ui.getUISet("密码" .. i,""))
                roleCount = JY.jy_str.trim(jy_ui.getUISet("角色数量" .. i,nil))
                if not IsEmpty(acc) then
					if IsEmpty(roleCount) then
                    	table.insert(accList, acc .. "--" .. pwd)
					else
						table.insert(accList, acc .. "--" .. pwd .. "--" .. roleCount)
					end
                end
            end
			JY.jy_file.writeFileLines(accFile, accList)
        end
	else
		JY.jy_sys.mlog("未在UI界面填写账号信息，且未指定账号文件，无法创建账号文件！", "账号初始化", "账号文件检查")
		frameSdk.exitScript()
    end
end



local function getDefulUserFile(defulFile)
    local defaultPath = G.config("默认账号路径")
    local picturesPath = defaultPath .. defulFile .. ".txt"
	if frameSdk.fileExist(picturesPath) then
		for i = 1, 500, 1 do
			picturesPath = defaultPath .. defulFile .. "_" .. i .. ".txt"
			if not frameSdk.fileExist(picturesPath) then
				break
			end
		end
	end

	JY.jy_file.filePut("账号文件", picturesPath)
    return picturesPath
end
-- 账号初始化函数
local function InitAccountInfo()

	-- 初始化默认路径
	local defaultPath = G.config("默认账号路径")
	if IsEmpty(defaultPath) then
		defaultPath = frameSdk.getSdPath() .. "/Pictures/"
		G.setConfig("默认账号路径", defaultPath)
	end

	-- 辅助函数：构建文件路径
	local function build_file_path(filename , suffix)
		return defaultPath .. "/" .. filename .. (suffix or "") .. ".txt"
	end

	-- 步骤1: 查看是否有用户设置的账号文件
	local user_filename = jy_ui.getUISet("账号文件", "")
	local account_filepath = ""

	if not IsEmpty(user_filename) then
		-- 用户指定了文件名，使用用户指定的路径
		account_filepath = build_file_path(user_filename)
		JY.jy_sys.mlog("用户指定账号文件：" .. account_filepath, "init", "步骤1")

		-- 尝试读取文件
		if not frameSdk.fileExist(account_filepath) then
			JY.jy_msg.showMsg("账号文件不存在：" .. user_filename)
			JY.jy_sys.mlog("游戏启动 ***************** 账号文件不存在：" .. account_filepath, "init", "账号初始化")
			frameSdk.exitScript()
		end

		-- 读取文件内容
		local user_table = JY.jy_file.readFileLines(account_filepath)

		-- 检查文件是否为空
		if #user_table == 0 then
			JY.jy_msg.showMsg("账号文件为空：" .. user_filename)
			JY.jy_sys.mlog("游戏启动 ***************** 账号文件为空：" .. account_filepath, "init", "账号初始化")
			frameSdk.exitScript()
		end

		-- 将路径保存到配置
		JY.jy_file.filePut("账号文件", account_filepath)

	else
		-- 步骤2: 用户未指定文件，检查配置中的路径
		account_filepath = JY.jy_file.fileGet("账号文件", "")

		if IsEmpty(account_filepath) then
			-- 配置中没有路径，使用默认路径
			account_filepath = getDefulUserFile(G.config("默认账号文件名"))
		end

		-- 读取现有账号数据（如果文件存在）
		local existing_user_table = nil
		if frameSdk.fileExist(account_filepath) then
			existing_user_table = JY.jy_file.readFileLines(account_filepath)
		end

		-- 步骤3: 检查用户是否有输入的账号密码
		local acc_1 = jy_ui.getUISet("账号1", nil)

		if not IsEmpty(acc_1) then
			-- 用户有输入账号信息，收集所有输入
			local accCount = G.config("账号数量") or 10
			local newAccList = {}

			for i = 1, accCount, 1 do
				local acc = JY.jy_str.trim(jy_ui.getUISet("账号" .. i, ""))
				local pwd = JY.jy_str.trim(jy_ui.getUISet("密码" .. i, ""))
				local roleCount = JY.jy_str.trim(jy_ui.getUISet("角色数量" .. i, 1))

				if not IsEmpty(acc) then
					table.insert(newAccList, acc .. "--" .. pwd .. "--" .. roleCount)
				end
			end

			-- 检查数据是否发生变化
			local dataDiff = false

			if existing_user_table == nil then
				-- 原文件不存在，需要新建
				dataDiff = true
			elseif #newAccList ~= #existing_user_table then
				-- 数量不同，数据肯定不同
				dataDiff = true
			else
				-- 对比每一条数据
				for i = 1, #newAccList do
					if newAccList[i] ~= existing_user_table[i] then
						dataDiff = true
						break
					end
				end
			end

			-- 根据对比结果进行处理
			if dataDiff then
				JY.jy_file.mWriteFile(account_filepath, "", false, true) -- 清空文件
				JY.jy_file.writeFileLines(account_filepath, newAccList)
			end

			-- 确保路径被保存
			JY.jy_file.filePut("账号文件", account_filepath)
		else
			-- 用户没有输入账号信息，依赖现有文件
			if not frameSdk.fileExist(account_filepath) or (#existing_user_table or 0) == 0 then
				JY.jy_msg.showMsg("无账号信息，请在UI中填写账号或选择账号文件")
				JY.jy_sys.mlog("游戏启动 ***************** 无账号信息，路径：" .. account_filepath, "init", "账号初始化")
				frameSdk.exitScript()
			end
		end
	end

	-- 最终读取账号文件内容
	local user_table = JY.jy_file.readFileLines(account_filepath)

	-- 再次确认文件有效性
	if not frameSdk.fileExist(account_filepath) or #user_table == 0 then
		JY.jy_msg.showMsg("无有效的账号信息")
		JY.jy_sys.mlog("游戏启动 ***************** 最后检查失败，账号路径：" .. account_filepath, "init", "账号初始化")
		frameSdk.exitScript()
	end


	JY.jy_sys.mlog("游戏启动 ***************** 账号文件：" .. account_filepath .. "，共 " .. #user_table .. " 个账号", "init", "账号初始化")

	-- 自动建号处理
	local acc_num = G.config("账号数量") or 0
	if jy_ui.getUISet("自动建号" , false) then
		local input_num = tonumber(jy_ui.getUISet("账号数量" , 5)) or 5
		acc_num = math.min(input_num , G.config("账号数量") or input_num)

		while #user_table < acc_num do
			table.insert(user_table , "11--11")
		end
	end

	-- 账号数量限制
	local accountCount = G.config("账号数量")
	if accountCount then
		local limited = {}
		for i = 1 , math.min(#user_table , accountCount) do
			table.insert(limited , user_table[i])
		end
		user_table = limited
	end

	-- 启动序号处理
	local start_num = tonumber(jy_ui.getUISet("当前序号" , 1))
	if G.config("主号模式") or jy_ui.getUISet("辅助功能" , false) then
		start_num = start_num or 1
	else
		start_num = start_num or JY.jy_file.fileGet("当前序号",1)
	end

	-- 返回完整账号信息表
	return {
			userTap = user_table ,
			startNum = start_num ,
			accountFilePath = account_filepath
		}

end
jy_frame.InitAccountInfo = InitAccountInfo


--[[重设动态范围
说明：
	如果r 范围数据类型为string，则再vars["临时变量"] 中查找指定的范围
参数：
	HUDID： 浮窗ID
	text： 显示内容
返回：
	无
]]
local function reSetR(v2)
	local v = JY.jy_tab.deepCopy(v2)
	if type(v2["r"]) == "string" then
		v["r"] = G.vars("临时变量." .. v2["r"])
	end
	return v
end
jy_frame.reSetR = reSetR

local function getAppId(nowUser)
	local appId = G.config("appid")
	local setVersion = jy_ui.getUISet("游戏版本" .. nowUser , 0)
	if setVersion ~= 0 and setVersion ~= nil then
		return appId[setVersion - 1]
	end
	if type(appId) == "table" then
		return appId[nowUser] or appId[1]
	end

	return appId[jy_ui.getUISet("游戏版本", 0)]
end
jy_frame.getAppId = getAppId

--通用特征替换
local function tyReload(v2, fun , view)
	local ty
	local pageName = v2[1]
	local taps = G.taps()

	if v2["ff"] == "ty" then
		ty = taps["通用"][pageName]
		if ty == nil then
			JY.jy_sys.mlog("错误：【" .. pageName .. "】 未找到通用参数配置" , fun , view)
			frameSdk.exitScript()
		end
		for ty_k , ty_v in pairs(ty) do
			if ty_k == "wait" then
				if v2["wait"] ~= nil then
					ty_v = v2["wait"]
				end
			end
			v2[ty_k] = ty_v
		end
		if v2["ty_ff"] ~= nil then
			v2["ff"] = v2["ty_ff"]
		end
	end

	if v2["ty_n"] ~= nil then
		ty = taps["通用"][v2["ty_n"]]
		if ty == nil then
			JY.jy_sys.mlog("错误：【" .. v2["ty_n"] .. "】 未找到通用参数配置" , fun , view)
			frameSdk.exitScript()
		end
		for ty_k , ty_v in pairs(ty) do
			if ty_k == "wait" then
				if v2["wait"] ~= nil then
					ty_v = v2["wait"]
				end
			end
			if (ty_k ~= "ff" and ty_k ~= 1) or v2[ty_k] == nil then
				v2[ty_k] = ty_v
			end
		end
	end
	return v2
end
jy_frame.tyReload = tyReload


local function getTask(task)
	local taskName = nil
	if type(task) == "table" then
		return task
	elseif type(task) == "string" then
		taskName = task
		local taps = G.taps()
		for _, taskTab in ipairs(taps["任务列表模块"]) do
			-- 判断指定的任务列表模块是否存在
			if not IsEmpty(taps[taskTab]) then
				-- 指定模块是否标记为任务列表
				if taps[taskTab]["任务列表"] == true then
					-- 遍历任务列表模块
					for key, value in pairs(taps[taskTab]) do
						-- 判断是否是任务特征
						if type(key) == "number" and type(value) == "table" and value[1] == taskName then
							return value
						end
					end
				elseif taskTab == "任务列表" then
					-- 遍历任务列表模块
					for key, value in pairs(taps[taskTab]) do
						-- 判断是否是任务特征
						if type(key) == "number" and type(value) == "table" and value[1] == taskName then
							return value
						end
					end
				end
			end
		end
	end
	JY.jy_sys.mlog("未查找到指定任务 【" .. taskName .. "】 检测任务名是否正确，或任务模块是否指定为任务列表，或是否在taps['任务列表模块']中定义")
	return nil
end
jy_frame.getTask = getTask



return jy_frame
