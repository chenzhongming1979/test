--[[
Copyright (c) 2026 成都林深见鹿网络科技有限公司 All Rights Reserved.

本代码基于 Apache License 2.0 协议开源：
1. 个人学习、非商业用途：可免费使用、修改、分发本代码；
2. 商业用途、企业使用：必须联系作者获取书面授权后方可使用；
3. 任何使用场景下，禁止删除本版权声明及相关协议说明；
4. 若进行二次开发/衍生，需在衍生作品的显著位置保留本版权声明，并标注衍生说明。

完整协议文本：http://www.apache.org/licenses/LICENSE-2.0

================================================================================
frameSdk-autolua2 包入口：组装 + 自检 + 调试钩子 + 注册 _G.frameSdk

定位（参照 frameSdk-firefly 包结构）：
  在 autolua2 引擎（jy-frame-engine）上 1:1 复刻懒人精灵引擎全局 API，
  使 JY 框架核心（TaskRunner/ActionFramework/ActionsUtils 等）零改动运行。

使用约束：
  1. 必须先于一切 JY 模块加载：main.lua 中 require("frameSdk-autolua2").init(opts)
  2. 坐标契约：JY 框架已在 PageUtils.freemInit 完成开发基准→真实分辨率换算，
     本层图色/触控/OCR 全部纯透传，不做任何缩放
  3. 返回格式仿真：OCR 输出 PDOcr/NJOcr/NCNNOcr/LBTOcr 四种懒人 JSON 格式；
     yolo 为 D 级降级（引擎暂无 yolo 推理模块）
  4. 不再注入引擎全局，统一经 _G.frameSdk 命名空间访问
================================================================================
--]]

local contract = require("frameSdk-autolua2.contract")
local engine = require("frameSdk-autolua2.engine")
local compat = require("frameSdk-autolua2.compat")
local fileio = require("frameSdk-autolua2.file_io")
local color = require("frameSdk-autolua2.color")
local node = require("frameSdk-autolua2.node")
local device = require("frameSdk-autolua2.device")
local touch = require("frameSdk-autolua2.touch")
local uimod = require("frameSdk-autolua2.ui")

local api = {}
api.PLATFORM = contract.platform
api.VERSION = contract.version

-- ==================== 组装：命令面与原全局面同构 ====================

-- engine（帧管理/截图）
api.keepCapture = engine.keepCapture
api.releaseCapture = engine.releaseCapture
api.snapShot = engine.snapShot
api.LuaEngine = engine.LuaEngine

-- color（图色/找字/yolo）
api.getPixelColor = color.getPixelColor
api.colorToRGB = color.colorToRGB
api.findMultiColorT = color.findMultiColorT
api.findMultiColorAll = color.findMultiColorAll
api.findColorT = color.findColorT
api.cmpColorExT = color.cmpColorExT
api.findPic = color.findPic
api.findPicFast = color.findPicFast
api.getColorNum = color.getColorNum
api.findStrEx = color.findStrEx
api.findStrExNew = color.findStrExNew
api.YoloV5 = color.YoloV5
api.PaddleOcr = color.PaddleOcr
api.PPv5_OCR = color.PPv5_OCR  -- LBTOcr 兼容对象；运行期可被 FrameInit 插件链覆盖注入

-- node（节点体系 + 选择器工厂）
api.nodeLib = node.nodeLib
for _, k in ipairs(node.SEL_KEYS) do
	api[k] = function(v) return node.newSelector(node.SEL_KEYMAP[k] or k, v) end
end
api.sel = {}
for _, k in ipairs(node.SEL_KEYS) do
	api.sel[k] = api[k]  -- 框架经 frameSdk.sel[k] 动态分发（与 lrjl 命令面对齐）
end

-- touch（触控/按键/输入/手势）
api.tap = touch.tap
api.touchDown = touch.touchDown
api.touchMove = touch.touchMove
api.touchMoveEx = touch.touchMoveEx
api.touchUp = touch.touchUp
api.swipe = touch.swipe
api.keyPress = touch.keyPress
api.inputText = touch.inputText
api.Path = touch.Path
api.Gesture = touch.Gesture
api.imeLib = touch.imeLib

-- device（应用/设备/路径/线程/系统）
api.runApp = device.runApp
api.stopApp = device.stopApp
api.appIsRunning = device.appIsRunning
api.appIsFront = device.appIsFront
api.getInstalledApk = device.getInstalledApk
api.getPackageName = device.getPackageName
api.getDisplaySize = device.getDisplaySize
api.getDisplayRotate = device.getDisplayRotate
api.getRunEnvType = device.getRunEnvType
api.getWorkPath = device.getWorkPath
api.getSdPath = device.getSdPath
api.setLogOff = device.setLogOff
api.systemTime = device.systemTime
api.exitScript = device.exitScript
api.Thread = device.Thread
api.extractAssets = device.extractAssets
api.sleep = device.sleep

-- file_io（文件/网络/JSON）
api.fileExist = fileio.fileExist
api.fileSize = fileio.fileSize
api.writeFile = fileio.writeFile
api.mkdir = fileio.mkdir
api.delfile = fileio.delfile
api.unZip = fileio.unZip
api.httpPost = fileio.httpPost
api.jsonLib = fileio.jsonLib

-- ui（HUD/吐司/静态 UI 配置 + 动态 UI 兼容层）
api.createHUD = uimod.createHUD
api.showHUD = uimod.showHUD
api.toast = uimod.toast
api.getUIConfig = uimod.getUIConfig
api.ui = uimod.ui

-- compat（懒人扩展）
api.splitStr = compat.splitStr
api.utf8 = compat.utf8c
api.table = { contains = table.contains }  -- 与 lrjl 命令面对齐（框架经 frameSdk.table.contains 调用）
api.string = { split = string.split }      -- 与 lrjl 命令面对齐

-- lfs：兼容表（autolua2 无原生 lfs，走 file 服务映射）
api.lfs = fileio.lfsCompat

-- import：require 时按原判定顺序解析（importClass/luajava.bindClass 优先于空操作）
api.import = device.resolveImport()
-- 工作目录显式校正（getWorkPath 缓存固化；ide_push 等非默认工程根场景入口脚本调用）
api.setWorkPath = device.setWorkPath

-- LuaEngine.httpGet 挂载（归 file_io 域实现，挂回同一张 LuaEngine 表）
fileio.bindLuaEngineHttpGet()

-- ==================== 自检（core 全量存在 → 通过；缺项 → error） ====================

local function selfCheck()
	local missing = {}
	for _, name in ipairs(contract.coreFunctions) do
		if type(api[name]) ~= "function" then missing[#missing + 1] = name end
	end
	for _, name in ipairs(contract.coreObjects) do
		if api[name] == nil then missing[#missing + 1] = name end
	end
	for _, name in ipairs(contract.coreDynamic or {}) do
		if api[name] == nil then missing[#missing + 1] = name end
	end
	-- 子表检查：utf8 经 api，标准表增强经全局 table/string
	if api.utf8 == nil or api.utf8.inStr == nil then missing[#missing + 1] = "utf8.inStr" end
	if type(table.contains) ~= "function" then missing[#missing + 1] = "table.contains" end
	if type(string.split) ~= "function" then missing[#missing + 1] = "string.split" end
	for _, k in ipairs(contract.selectorKeys) do
		if type(api[k]) ~= "function" then missing[#missing + 1] = "sel:" .. k end
	end
	if #missing > 0 then
		error("frameSdk 自检失败，缺失命令: " .. table.concat(missing, ", "), 0)
	end
	-- ext 仅告警（autolua2 暂不覆盖）
	engine.log("ext 契约 " .. #contract.ext .. " 项 " .. tostring(contract.extNote) .. "（名单见 contract.ext）")
	-- 引擎直注全局可用性摘要（缺项仅告警，功能级降级已在各域实现）
	if engine.hasGlobal ~= nil then
		local missG = {}
		for name, ok in pairs(engine.hasGlobal) do
			if not ok then missG[#missG + 1] = name end
		end
		if #missG > 0 then
			engine.log("引擎全局缺失（对应功能降级）：", table.concat(missG, ", "))
		end
	end
end

-- ==================== 调试钩子（opts.debugMode == true 时启用，默认零开销） ====================
-- 注意：debug 模式下包装壳统一返回至多 4 个返回值（pcall 收集后固定展开），
-- 仅影响依赖 select('#') 计数的调用方；异常经 log 后原样重抛，控制流（含
-- exitScript 的 @EXIT_SCRIPT@ 受控退出）保持不变。

local function wrapTrace(name, fn)
	return function(...)
		local n = select("#", ...)
		local args = { ... }
		local t0 = os.clock()
		local res = { pcall(fn, unpack(args, 1, n)) }
		local ms = (os.clock() - t0) * 1000
		if not res[1] then
			engine.log("[trace]", name, "异常 耗时", string.format("%.2f", ms), "ms:", tostring(res[2]))
			error(res[2], 0)
		end
		engine.log("[trace]", name, "耗时", string.format("%.2f", ms), "ms")
		return res[2], res[3], res[4], res[5]
	end
end

local function applyDebugHooks()
	for k, v in pairs(api) do
		if type(v) == "function" then
			api[k] = wrapTrace(k, v)
		end
	end
	engine.log("调试钩子已启用：api 命令追踪开启（命令名/耗时/异常）")
end

-- ==================== init（签名与原实现完全兼容） ====================

function api.init(opts)
	opts = opts or {}
	-- 0) iconv 注册为可 require 模块（JY.lua:1494 require("iconv")，懒人内置、autolua2 无）
	if package ~= nil and package.preload ~= nil then
		package.preload["iconv"] = function() return compat.iconv end
	end
	-- 1) 加载平台模块 + JSON 编解码绑定
	engine.loadModules()
	-- 2) 屏幕信息缓存
	local w, h = device.getDisplaySize()
	engine.screen = { w = tonumber(w) or 0, h = tonumber(h) or 0 }
	engine.workPath = nil
	device.getWorkPath()  -- 触发缓存
	-- 3) 可选权限引导
	if opts.bootstrapPermissions == true then
		engine.bootstrapPermissions()
	end
	-- 4) 注册脚本停止清理（引擎 event 总线多播；OnStop 桥由引擎 compat 提供）
	local event = engine.M.event
	if event ~= nil and event.onStopEvent ~= nil then
		pcall(event.onStopEvent, function() engine.cleanup() end)
	end
	-- 5) 契约自检
	selfCheck()
	-- 6) 调试钩子（默认关闭，零开销）
	if opts.debugMode == true then
		applyDebugHooks()
	end
	engine.log("v" .. tostring(api.VERSION) .. " 初始化完成，屏幕 "
		.. tostring(engine.screen.w) .. "x" .. tostring(engine.screen.h)
		.. "，工作目录 " .. tostring(engine.workPath))
	return api
end

-- 注册命名空间（不注入其余引擎全局）
_G.frameSdk = api

return api
