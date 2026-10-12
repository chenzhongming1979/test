-- 统一的流程驱动模块，依据 conf 中的选项调度逻辑
local Common = require("JY.Process.ProcessUtils")
local JY = require("JY.Utils.JY")
local jy_frame = require("JY.Utils.FrameUtils")
local TaskScheduler = require("JY.Task.TaskScheduler")
local jy_ui = require("JY.Utils.UIUtils")
local function processAccount(i, runConf)
    --获取游戏包名
    G.setRuntime("appid", jy_frame.getAppId(runConf.roundNum))
    --账号准备
    local runAcc = Common.prepareAccount(i, runConf)
    --关闭游戏
    Common.stopIfRunning()
    --启动游戏前执行
    Common.runBefor()
    --启动并登录游戏
    if not Common.loginGame() then return false end

    local roles = JY.jy_file.fileGetUser("角色数量",1, G.config("当前账号"))
    -- 支持多角色
    for role_i = 1, roles do
        --多角色设置
        Common.moreRole(runAcc,role_i,roles)

        --执行定时任务
        if Common.handleCrontabLogic() then break end

        --手动模式和辅助功能执行
        if Common.handleManualOrAux() then break end

        --章节任务
        if Common.processGameModes() then break end

        --执行主任务列表
        if Common.executeScriptTasks() then break end

        --切换角色
        if Common.changeRoles() then break end
    end

    --启动后执行
    Common.afterRun()

    -- 重置状态
    Common.resetStatus(i)
end

local function run()
    local accInfo = jy_frame.InitAccountInfo()
    local runConf = {
        userTap = accInfo["userTap"],
        roundNum = accInfo["startNum"] or 1,
        roleNum = accInfo["roleNum"] or 1,
        initVarSet = JY.jy_tab.deepCopy(G.vars()),
        runAcc = nil,
        runStatus = true,
        newAcc = "",
        currentRound = 0,
    }

    if G.config("日志记录") then
        frameSdk.setLogOff(false)
    end

    G.setRuntime("timingTask.Crontab", Common.CONST.CRONTAB_RUN)

    while true do
        runConf.currentRound = runConf.currentRound + 1
        if runConf.roundNum > #runConf.userTap then
            runConf.roundNum = 1
        end

        for i = runConf.roundNum, #runConf.userTap, 1 do
            runConf.runStatus = true
            G.setRuntime("gameStatus", 0)
            JY.jy_sys.mlog(string.format("游戏启动 ***************** 第%d轮", runConf.currentRound))

            -- 启动游戏流程
            if not processAccount(i, runConf) then break end

            if G.runtime("timingTask.Crontab") == Common.CONST.CRONTAB_STOP then break end
        end

        -- 重置其他状态
        Common.resetOtherStatus()
    end
end

return { run = run }
