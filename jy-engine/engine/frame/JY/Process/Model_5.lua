-- 统一的流程驱动模块，依据 conf 中的选项调度逻辑
local Common = require("JY.Process.ProcessUtils")
local JY = require("JY.Utils.JY")
local jy_frame = require("JY.Utils.FrameUtils")
local TaskScheduler = require("JY.Task.TaskScheduler")
local jy_ui = require("JY.Utils.UIUtils")
local script_task = require("JY.Process.TaskProcessControl")


local function run()
    G.setRuntime("memoryParam.memoryState", 1)

	-- 状态初始化
	local whileCount , startNum = 0 , 1
	local restartCount = G.config("重启次数")
	local nowUser = G.config("当前账号")
    local code = true
	-- 主循环流程
	while true do
		-- 序号管理
		startNum = jy_ui.getUISet("当前序号" , 1)
		G.setConfig("当前序号", startNum)
		G.setRuntime("appid", G.config("appid")[startNum])

		if not frameSdk.appIsFront(G.runtime("appid")) then
			frameSdk.runApp(G.runtime("appid"))
		end
		-- 日志管理
		if G.config("日志记录") then frameSdk.setLogOff(false) end

		-- 账号获取
		G.setConfig("当前账号", jy_ui.getUISet("账号"..startNum,nil) or nowUser)
		-- 任务初始化
		G.GSM:resetVars()
		G.setRuntime("gameCheck.gameState", 1)
		jy_frame.setHUDTextWrapper(G.runtime("HUDID") , "进入游戏")
		TaskScheduler.new():runWait("进入游戏" , nil)

        --执行定时任务
        code = Common.handleCrontabLogic()

        --章节任务
        if not code then code = Common.processGameModes() end

        -- 主流程
        if not code then script_task.taskLoading() end

        G.setRuntime("gameCheck.gameState", 0)

		JY.jy_time.countDown(G.config("轮号休息"))
	end
end

return {run = run }