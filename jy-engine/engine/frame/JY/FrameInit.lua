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

-- FireFly 版：移除懒人引擎 import（LuaEngine 兼容表由 lrsdk 提供）
local JY = require("JY.Utils.JY")

local jy_page = require("JY.Utils.PageUtils")
local jy_ui = require("JY.Utils.UIUtils")
local TaskScheduler = require("JY.Task.TaskScheduler")

local function keyProt()
	local scriptNo = G.runtime("netParam.scriptNo")
	local key = G.runtime("netParam.cdkey")
	local err = 0

	while true do
		JY.jy_sys.sleep(G.runtime("netParam.keyPortDelay") or 30000) -- 延迟30秒

		local ret = 0 -- JY_SFUN.JY_KeyProt(key , scriptNo)

		if ret == 2 then
			err = err + 1
			if err > (G.runtime("netParam.errorCount") or 5) then
				break -- 退出循环
			end
		elseif ret == 1 then
			break -- 退出循环
		else
			err = 0 -- 重置错误计数
		end
	end
end
--卡屏检查
local function gameCheck()
	JY.jy_sys.sleep(3000) -- 初始延迟

	-- 获取共享变量
	local gameAppid = G.runtime("appid")
	-- 配置参数处理（带默认值）
	local range = G.config("卡屏检测范围") or {0 , 0 , 100 , 100}
	local threadWait = G.vars("卡屏检测间隔") or 20000
	local isDead = false
	while true do
		-- 仅在开启卡屏检测时执行
		if G.config("卡屏检测") then
			local gameState = G.runtime("gameCheck.gameState")

			-- 游戏状态检测
			if gameState > 0 then
				-- 卡屏检测核心逻辑
				isDead = JY.jy_sys.isScreenStable({range[1] , range[2] , range[3] , range[4]} ,	G.config("卡屏检测时长") or 2000 ,0.9 ,G.config("卡屏检测频率") or 200 ,G.config("卡屏检测点数") or 100)
				if not isDead then
					-- 卡屏处理逻辑
					JY.jy_sys.mlog("检测线程 ***************** 卡屏【 ".. gameState .." 】次" , "卡屏检测" , "卡屏检测")

					if G.config("启动线程") and gameState > 0 then
						TaskScheduler.new():runWait("检测线程" , nil)
					end

					G.setRuntime("gameCheck.gameState", gameState + 1)
				else
					-- 重置卡屏计数
					G.setRuntime("gameCheck.gameState", 1)
				end
			else
				-- 游戏未运行状态处理
				G.setRuntime("gameCheck.gameState", 0)
			end
		end

		-- 等待下次检测
		JY.jy_sys.sleep(threadWait)
	end
end
--初始化conf 和 vars
local function initConf(mConf, mVars)
	local openLog = jy_ui.getUISet("记录日志" , false)
	mConf["日志记录"] = openLog
	local lhTime = jy_ui.getUISet("轮号休息" , 0)
	local setLh = JY.jy_num.toNumber(lhTime,0)

	if setLh < 7 then
		setLh = setLh * 15 * 1000 * 60
	end
	mConf["轮号休息"] = setLh

	mConf["账号文件"] = jy_ui.getUISet("账号文件" , mConf["默认账号文件名"])

	mConf["改机"] = jy_ui.getUISet("改机" , false)
	if jy_ui.getUISet("单号模式" , false) then
		mConf["游戏模式"] = 5
	end

	JY.jy_sys.mlog("游戏模式：" .. mConf["游戏模式"] , "init" , "初始化")
	G.setRuntime("copyVars", JY.jy_tab.deepCopy(mVars))

	G.GSM:initConfig(mConf)
	G.GSM:initVars(mVars)
end

--初始化浮窗
local function initHUD()
	if G.config("显示浮窗") == true then
		local wz = G.config("浮窗坐标")
		G.setRuntime("HUDID", frameSdk.createHUD())
	end
end

--初始化taps
local function initTaps(mTaps)
	mTaps = jy_page.freemInit(mTaps)
	G.GSM:initTaps(mTaps)
end


local function initStatus()
	G.setRuntime("gameCheck.gameState", 0)
	--内存参数初始化
	G.setRuntime("memoryParam.memoryState", 0)
	G.setRuntime("memoryParam.nowUser", "0")
	G.setRuntime("memoryParam.nowPwd", "0")

	local appids = G.config("appid")
	local appid = appids[jy_ui.getUISet("游戏版本" , 1)]
	G.setRuntime("appid", appid)

	--网络参数初始化
	if G.config("scriptNo") ~= nil then
		G.setRuntime("netParam.scriptNo", G.config("scriptNo"))
		G.setRuntime("netParam.scriptId", G.config("scriptId"))
		G.setRuntime("netParam.cdkey", jy_ui.getUISet("激活码" , nil))
		G.setRuntime("netParam.setKeyPortState", true)
	end

end


local function initTasks()
	local taps = G.taps()
	for key, value in pairs(taps["任务列表模块"]) do
		-- PageUtils.freemInit(w) 会给每个模块注入 name 标记（值=key 本身字符串），
		-- pairs 遍历到 name 键时 value 是字符串，其 taps[value][i] 行也是字符串——跳过
		if key ~= "name" and type(value) == "string" and type(taps[value]) == "table" then
			for i = 1 , #taps[value] , 1 do
			local taskParam = taps["任务列表"][i]["任务参数"] or {}
			local settingID

			-- 确定UIID来源
			if taskParam["UIID"] ~= nil then
				settingID = taskParam["UIID"]
			else
				settingID = taps["任务列表"][i][1] -- 兼容两种索引方式
			end

			-- 处理设置值逻辑
			local setValue = false
			if taskParam["是否有对应设置"] == true then
				if jy_ui.getUISet(settingID) then -- 调用外部函数
					setValue = true
				end
			else
				setValue = true
			end

			-- 更新任务参数
			taps[value][i]["是否启用"] = setValue
		end
	end
end

end

local function initNcnnOcr()
    frameSdk.import("org.opencv.android.Utils")
    frameSdk.import("org.opencv.core.*")
    frameSdk.import("org.opencv.imgcodecs.Imgcodecs")
    frameSdk.import("org.opencv.imgproc.Imgproc")

	local pluginPath = frameSdk.LuaEngine.getResPath() .. "/OcrServerNCNN-1.1.1-Plugin.apk"
    local loader = frameSdk.LuaEngine.loadApk(pluginPath)
    local OcrPlugin = loader.loadClass("com.roiff.ocr.ncnn.OcrPlugin")
    local ocrServer = OcrPlugin.init(pluginPath,"2")
    local mat = Mat()

    G.setConfig("NccnOcr_ocrSever", ocrServer)
    G.setConfig("NccnOcr_mat", mat)
end

local function initLBTOcr()
	local apkname = "boloto_test.apk"
	local pluginWorkPath = frameSdk.LuaEngine.getResPath()
	local list = {
		"PP_OCRv5_mobile_det.ncnn.bin" , "PP_OCRv5_mobile_det.ncnn.param" ,
		"PP_OCRv5_mobile_rec.ncnn.bin" , "PP_OCRv5_mobile_rec.ncnn.param"
	}
	local p = {path = pluginWorkPath , type = 0 , size = 3 , gpu = 0 , }
	for i = 1 , 2 do
		local isNeedDownLoad = false
		for s = 1 , #list do
			if not frameSdk.fileExist(p.path.."/"..list[s]) then
				isNeedDownLoad = true

				break
			end
		end
		if isNeedDownLoad then
			local modelLocalPath = pluginWorkPath.."/model.zip"
			if not frameSdk.fileExist(modelLocalPath) or i == 2 then
				frameSdk.toast("菠萝头模型不存在,加载失败")
				print("菠萝头模型不存在,加载失败")
				frameSdk.sleep(1000)
				frameSdk.exitScript()
			end
			print("解压模型")
			frameSdk.unZip(modelLocalPath , pluginWorkPath)
		else
			break
		end
	end

	local loader = frameSdk.LuaEngine.loadApk(apkname)

	local success , err = pcall(function ()
		-- 迁移说明：插件对象注入 frameSdk 命令总线（替代原全局 PPv5_OCR）
		frameSdk.PPv5_OCR = loader.loadClass("com.rain.qq934741802.PPOCRv5Ncnn")
		frameSdk.PPv5_OCR = frameSdk.PPv5_OCR()
	end)
	if err then
		print("菠萝头插件加载错误" , err)
		frameSdk.exitScript()
	end
	if not frameSdk.PPv5_OCR.loadModelFromPath(p.path , p.type , p.size , p.gpu) then
		print("菠萝头模型初始化失败!")
		frameSdk.exitScript()
	end
	print("菠萝头加载成功")
end

local function cleanLogFile()
    local logFilePaht = "/sdcard/Download/" .. frameSdk.getPackageName() .. "/logset"
    local success, err = pcall(JY.jy_file.deleteFilesByModifyTime, logFilePaht, G.config("日志过期时间") or 12)
    if not success then
        print("日志清理异常: " .. tostring(err))
    end
end
local function initOther(mConf)
	if mConf.NCNNOcr then
		initNcnnOcr()
	end

	if mConf.LBTOcr then
		initLBTOcr()
	end
end

--框架环境初始化
function EnvInit(mTaps , mConf , mVars)
	--setLogOff(true)
	--FireFly 版：平台权限引导（无障碍/截图/悬浮窗/IME；重复调用安全）
	-- 权限引导已收口于入口：main.lua 中 require("frameSdk-firefly").init({ bootstrapPermissions = true })
	--（框架不再直接依赖具体 SDK 包，lrsdk 引用已随架构迁移移除）
	--初始化参数
	initConf(mConf,mVars)
	--初始化特征
	initTaps(mTaps)
	--初始化框架状态
	initStatus()
	--初始化任务
	initTasks()
	--初始化浮窗
	initHUD()
	--初始化其他插件或其他自定义初始化
	initOther(mConf)
	--删除过期日志文件
	cleanLogFile()
	if G.config("卡屏检测") then
		local checkThread = frameSdk.Thread.newThread(gameCheck)
		JY.jy_sys.mlog("启动卡屏检测功能" , "init" , "初始服务")
		JY.jy_sys.sleep(1000)
	end
	if G.runtime("netParam.cdkey") ~= nil then
		local keyPortThread = frameSdk.Thread.newThread(keyProt)
		JY.jy_sys.sleep(1000)
	end

end