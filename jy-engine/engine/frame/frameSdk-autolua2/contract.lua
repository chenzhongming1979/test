--[[
Copyright (c) 2026 成都林深见鹿网络科技有限公司 All Rights Reserved.

本代码基于 Apache License 2.0 协议开源：
1. 个人学习、非商业用途：可免费使用、修改、分发本代码；
2. 商业用途、企业使用：必须联系作者获取书面授权后方可使用；
3. 任何使用场景下，禁止删除本版权声明及相关协议说明；
4. 若进行二次开发/衍生，需在衍生作品的显著位置保留本版权声明，并标注衍生说明。

完整协议文本：http://www.apache.org/licenses/LICENSE-2.0

说明：本包为引擎适配层衍生作品（参照 frameSdk-firefly / frameSdk-lrjl 包结构），
本文件为契约清单——命令面与 frameSdk-firefly 注入的全局面完全同构。
三包（frameSdk-lrjl / frameSdk-firefly / frameSdk-autolua2）各自独立分发，各带一份本清单。
--]]

local contract = {}

contract.name = "frameSdk-autolua2"
contract.platform = "autolua2"
contract.version = "1.0.0"

-- core 函数型命令（50 个，与 frameSdk-firefly 契约逐字一致）
contract.coreFunctions = {
	"keepCapture", "releaseCapture", "snapShot", "getPixelColor", "colorToRGB",
	"findMultiColorT", "findMultiColorAll", "findColorT", "cmpColorExT",
	"findPic", "findPicFast", "getColorNum", "findStrEx", "findStrExNew",
	"httpPost",
	"tap", "touchDown", "touchMove", "touchMoveEx", "touchUp", "swipe", "keyPress", "inputText",
	"runApp", "stopApp", "appIsRunning", "appIsFront", "getInstalledApk", "getPackageName",
	"getDisplaySize", "getDisplayRotate", "getRunEnvType",
	"getWorkPath", "getSdPath", "setLogOff", "systemTime", "exitScript",
	"fileExist", "fileSize", "writeFile", "mkdir", "delfile", "unZip",
	"createHUD", "showHUD", "toast", "getUIConfig",
	"extractAssets", "splitStr", "sleep",
}

-- core 对象型命令（12 个，含手势族与 PaddleOcr 兼容对象）
contract.coreObjects = {
	"nodeLib", "LuaEngine", "YoloV5", "Thread", "jsonLib", "imeLib", "ui", "lfs", "import",
	"Path", "Gesture", "PaddleOcr",
}

-- core 子表：表名(或 点路径) -> 需存在的方法键
contract.coreSubTables = {
	["utf8"] = "inStr",
	["table.contains"] = "contains",
	["string.split"] = "split",
}

-- core 选择器工厂键（与 frameSdk-firefly 工厂键表逐字一致）
contract.selectorKeys = {
	"id", "idContains", "idStartsWith", "idEndsWith", "idMatches",
	"text", "textContains", "textStartsWith", "textEndsWith", "textMatches",
	"desc", "descContains", "descStartsWith", "descEndsWith", "descMatches",
	"className", "classNameContains", "classNameStartsWith", "classNameEndsWith", "classNameMatches",
	"packageName", "packageNameContains", "packageNameStartsWith", "packageNameEndsWith", "packageNameMatches",
	"drawingOrder", "depth", "index",
	"visibleToUser", "selected", "clickable", "longClickable", "enabled",
	"password", "scrollable", "checked", "checkable", "focusable", "focused",
	"bounds", "boundsInside",
}
-- 运行期注入命令（FrameInit 插件链写入 frameSdk.PPv5_OCR；不参与加载期自检）
contract.coreDynamic = { "PPv5_OCR" }

-- ext：懒人引擎全局函数全集中 core 未覆盖部分（源自 doc-lrword/AGENT索引.md api/android 节）。
-- 本包不实现 ext，自检时仅告警"autolua2 暂不覆盖"。
contract.ext = {
	-- color 节
	"setScreenScale", "getScreenPixel", "colorDiff", "cmpColor", "cmpColorEx",
	"findMultiColor", "findMultiColorAllT", "findColor", "findStr",
	"createOcr", "setWhiteList", "ocrText", "ocrTextEx", "ocrTextExJ", "releaseOcr",
	"ocr", "ocrj", "ocrjNew", "ocrNew", "ocrEx", "ocrBinaryEx", "setDict", "useDict",
	"findCircle", "findPicEx", "findImage", "findPicAllPoint", "isDisplayDead",
	-- device 节
	"getCpuArch", "setControlBarPosNew", "showControlBar", "restartScript",
	"getApkVerInt", "getScriptVersion", "vibrate", "getSubscriberId", "getSimSerialNumber",
	"getDisplayDpi", "getBatteryLevel", "playAudio", "stopAudio", "installLrPkg",
	"getInstalledApps", "getLrApi", "getOaid", "getInsallAppInfos", "getDisplayInfo",
	"getSensorsInfo", "installApk", "checkIsDebug", "getDeviceId", "getBrand",
	"getBootLoader", "getBoard", "getManufacturer", "getProduct", "getDevice",
	"getModel", "getHardware", "getId", "getFingerprint", "getSdkVersion",
	"getOsVersionName", "getWifiMac", "scanImage", "getCurrentActivity", "frontAppName",
	"rnd", "exec", "runIntent", "sendSms", "phoneCall", "setDisplayPowerOff",
	"setTimer", "setUserEventCallBack", "setNotifyEventCallBack", "setStopCallBack",
	-- extension 节
	"connectSQL", "closeSQL", "executeQuerySQL", "executeSQL", "beginThread", "newThread",
	"qrDecode", "MD5", "fileMD5", "getFileBase64", "encodeBase64", "decodeBase64",
	"decodeUrl", "encodeUrl", "bdOcr", "getImage", "rotateImage", "scaleImage",
	"binaryImage", "binaryRect", "readFile",
	-- network 节
	"httpGet", "asynHttpPost", "asynHttpGet", "downloadFile", "uploadFile",
	"startWebSocket", "closeWebSocket", "sendWebSocket",
	-- time 节
	"getNetWorkTime", "tickCount",
	-- touch / ui 节
	"longTap", "keyDown", "keyUp", "setOnTouchListener", "closeWindow",
	"getUIWebViewUrl", "setUIWebViewUrl", "getUISelected", "getUISelectText",
	"getUIText", "setUIText", "setUISelect", "setUICheck", "getUICheck",
	"getUIEnable", "setUIEnable", "getUIVisible", "setUIVisible",
	"setUITextColor", "setUIBackground", "setUIConfig", "hideToast",
	"showUI", "showUIEx", "hideHUD", "createDragView", "getDragViewPos",
	"removeDragView", "setDragViewOnClick",
	-- 家族对象/设备与系统补充（与 frameSdk-firefly ext 对齐）
	"console", "cryptLib", "ffi", "gobridge", "imgui", "cv", "virtualDisplay", "http",
	"setRootEnvMode", "setHandleEnvMode", "setAccessibilityEnvMode",
	"setMainThreadPause", "setMainThreadResume", "readPasteboard", "writePasteboard",
	"setBTEnable", "setWifiEnable", "setAirplaneMode", "printEx", "lockScreen", "unLockScreen",
	"setPluginEventCallBack", "sendPluginEvent",
}

contract.extNote = "autolua2 暂不覆盖"

return contract
