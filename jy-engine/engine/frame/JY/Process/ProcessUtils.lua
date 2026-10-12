local JY = require("JY.Utils.JY")
local jy_acc = require("JY.Utils.AccountUtils")
local jy_ui = require("JY.Utils.UIUtils")
local jy_frame = require("JY.Utils.FrameUtils")
local TaskScheduler = require("JY.Task.TaskScheduler")
local jy_task = require("JY.Utils.TaskUtils")
local script_task = require("JY.Process.TaskProcessControl")
local CONST = {
    APP_STOP_DELAY = 1500,       -- 应用停止延迟
    APP_START_DELAY = 3000,      -- 应用启动默认延迟
    POPUP_CHECK_HUD_DELAY = 600, -- 弹窗检测浮窗显示时长
    CRONTAB_STOP = 0,            -- 定时任务停止标识
    CRONTAB_RUN = 1,             -- 定时任务运行标识
    EXIT_CODE_POPUP = 6,         -- 弹窗退出码
    EXIT_CODE_CRONTAB = 14,      -- 定时任务退出码
    GAME_MODE_CHAPTER = 1,       -- 游戏模式：章节任务
    GAME_MODE_AUX = 2,           -- 游戏模式：辅助功能
    GAME_MODE_OTHER = 3,         -- 游戏模式：其他
    LOGIN_MODE_SAVE = 1,         -- 登录模式：保存文件
}

local process = {
    CONST = CONST
}

local function stopIfRunning()
    if (G.config("轮号关闭游戏") == nil or G.config("轮号关闭游戏") == 1) and frameSdk.appIsRunning(G.runtime("appid")) then
        frameSdk.stopApp(G.runtime("appid"))
        JY.jy_sys.sleep(G.config("启动延时") or CONST.APP_START_DELAY)
    end
end
process.stopIfRunning = stopIfRunning

local function prepareAccount(i, runConf)
    -- 账号准备
    runConf.runAcc = jy_acc.accSet(i, runConf.userTap[i])
    local accLogText = string.format("%d/%d %s ---- %s", i, #runConf.userTap, runConf.runAcc[1], runConf.runAcc[2])
    frameSdk.toast(accLogText)
    JY.jy_sys.mlog("游戏启动 ***************** 当前账号：" .. accLogText)
end
process.prepareAccount = prepareAccount



local function loginGame()
    if not JY.jy_sys.havaApp(G.runtime("appid")) then
        JY.jy_sys.mlog("游戏启动 ***************** 未安装游戏")
        frameSdk.exitScript()
    end
    --初始化循环次数7
    local login_count = 1
    if G.config("二次登录次数") ~= nil and G.config("二次登录次数") > 0 then
        login_count = G.config("二次登录次数")
    end
    if G.config("显示浮窗") == true then
        if jy_ui.getUISet("自动建号", false) == true and G.config("是否建号") == 1 then
            jy_frame.setHUDTextWrapper(G.runtime("HUDID"), "创建账号")
        else
            jy_frame.setHUDTextWrapper(G.runtime("HUDID"), "登录游戏")
        end
    end

    local loginCheck = false
    for i = 1, login_count, 1 do
        JY.jy_sys.mlog("启动游戏 ***************** 第 " .. i .. " 次登录")

        frameSdk.runApp(G.runtime("appid"))
        JY.jy_sys.sleep(G.config("启动延时") or CONST.APP_START_DELAY)
        G.setRuntime("gameCheck.gameState", 1)
        if jy_ui.getUISet("自动建号", false) == true and G.config("是否建号") == 1 then
            loginCheck = TaskScheduler.new():runWait("账号注册界面", { timeout = 60 })
        else
            loginCheck = TaskScheduler.new():runWait("登录游戏", { timeout = 60 })
        end
        if jy_ui.getUISet("自动建号", false) == true and G.config("是否建号") == 1 then
            if G.vars("任务变量.建号状态") == "1" then
                JY.jy_sys.mlog("创建账号 ***************** 写入账号:" ..
                G.config("当前账号") .. "--" .. G.config("当前密码") .. "--" .. 1)
                jy_acc.accountReord() --写入内部存储
                return true
            end
        else
            if loginCheck == 14 then
                JY.jy_sys.mlog("启动游戏 ***************** 登录失败 强制退出整个任务链")
                return false
            elseif loginCheck == 6 then
                JY.jy_sys.mlog("启动游戏 ***************** 登录失败 超时")
                jy_acc.loginErr()
            elseif G.vars("状态变量.登录状态") == -1 then
                JY.jy_sys.mlog("启动游戏 ***************** 登录失败 其他原因，登录状态 = -1")
                return false
            elseif G.vars("状态变量.登录状态") == 1 then
                return true
            else
                JY.jy_sys.mlog("启动游戏 ***************** 登录失败 其他原因，退出码：" .. loginCheck)
            end
        end

        frameSdk.stopApp(G.runtime("appid"))
        frameSdk.sleep(1000)
    end
    if jy_ui.getUISet("自动建号", false) ~= true then
        jy_acc.loginErr()
    end

    return false
end
process.loginGame = loginGame

local function handleCrontabLogic()
    -- 1. 定时任务逻辑（语义化常量）
    if G.runtime("timingTask.Crontab") == CONST.CRONTAB_STOP then
        G.setRuntime("timingTask.startCrontab", 0)
        G.setConfig("定时任务.启动状态", 1)
        if G.config("游戏模式") == CONST.GAME_MODE_AUX or G.config("游戏模式") == CONST.GAME_MODE_CHAPTER then
            JY.jy_sys.mlog("定时任务触发 ***************** 定时任务触发，执行特征：" .. G.runtime("timingTask.执行特征"))
            jy_frame.setHUDTextWrapperWrapper("弹窗检测", CONST.POPUP_CHECK_HUD_DELAY)
            G.setRuntime("memoryParam.memoryState", 1)
            TaskScheduler.new():runWait(G.runtime("timingTask.执行特征"))
            return true
        end
    end
    return false
end
process.handleCrontabLogic = handleCrontabLogic


local function handleManualOrAux()
    -- 2. 手动模式逻辑
    if jy_ui.getUISet("手动模式", false) then
        TaskScheduler.new():runWait("手动模式退出")
        JY.jy_sys.mlog("手动模式退出 ***************** 退出当前账号流程")
        return true
    end

    -- 3. 辅助功能逻辑
    if jy_ui.getUISet("辅助功能", false) then
        local code = TaskScheduler.new():runWait("弹窗检测")
        if code == CONST.EXIT_CODE_CRONTAB or code == CONST.EXIT_CODE_POPUP then
            G.setRuntime("timingTask.Crontab", CONST.CRONTAB_STOP)
            JY.jy_sys.mlog("辅助功能 > 检测到定时任务 ***************** 退出当前账号流程")
            return true
        end
        JY.jy_sys.sleep(CONST.APP_START_DELAY)

        G.setConfig("当前任务", "辅助功能")
        TaskScheduler.new():runWait("辅助功能")

        TaskScheduler.new():runWait("退出游戏")
        JY.jy_sys.mlog("辅助功能完成 ***************** 退出当前账号流程")
        return true
    end
    return false
end
process.handleManualOrAux = handleManualOrAux


local function processGameModes()
    if G.config("游戏模式") == CONST.GAME_MODE_CHAPTER or G.config("游戏模式") == CONST.GAME_MODE_OTHER then
        local trainAccountDetail = JY.jy_file.fileGetUser("练号详情", "-1", G.config("当前账号"))
        if jy_ui.getUISet("练号", false) and trainAccountDetail ~= "1" then
            G.setConfig("当前任务", "章节任务")
            jy_frame.setHUDTextWrapperWrapper(G.runtime("HUDID"), "章节任务")

            local code = TaskScheduler.new():runWait("章节任务")
            if code == CONST.EXIT_CODE_POPUP then
                JY.jy_sys.mlog("章节任务超时 ***************** 退出当前账号流程")
                return true
            end
        end
    end

    -- 练号检查
    if jy_ui.getUISet("练号", false) then
        local trainAccountDetail = JY.jy_file.fileGetUser("练号详情", "-1", G.config("当前账号"))
        if trainAccountDetail ~= "1" then
            JY.jy_sys.mlog("练号检查 ***************** 练号状态未完成，退出当前账号流程")
            return true
        end
    end
    return false
end
process.processGameModes = processGameModes

local function moreRole(runAcc, role_i, roles)
    if roles > 1 then
        G.setConfig("当前账号", runAcc[1] .. "_" .. role_i)
        JY.jy_file.filePut("当前角色", role_i)
        JY.jy_sys.mlog("游戏启动 ***************** 当前角色：" .. role_i)
    end
end
process.moreRole = moreRole

local function executeScriptTasks()
    local code = script_task.taskLoading()
    if code == CONST.EXIT_CODE_CRONTAB then
        G.setRuntime("timingTask.Crontab", CONST.CRONTAB_STOP)
        return true
    end
    return false
end
process.executeScriptTasks = executeScriptTasks

local function afterRun()
    -- 运行后置方法
    if G.config("afterFun") and #G.config("afterFun") > 0 then
        for _, value in ipairs(G.config("afterFun")) do
            jy_task.otherRun(value, nil)
        end
    end

    -- 登录模式：保存游戏文件
    if G.config("登录模式") == CONST.LOGIN_MODE_SAVE then
        jy_acc.copyGameFile(G.runtime("appid"), G.config("gameFile"), G.config("gameSavePath"), G.config("当前账号"))
    end
end
process.afterRun = afterRun

local function changeRoles(role_i, roles)
    if role_i ~= roles then
        local roleCode = TaskScheduler.new():runWait("切换角色")
        if roleCode == CONST.EXIT_CODE_POPUP then
            JY.jy_sys.mlog("切换角色 ***************** 切换角色超时，退出当前账号流程")
            return true
        end
        if roleCode == CONST.EXIT_CODE_CRONTAB then
            G.setRuntime("timingTask.Crontab", CONST.CRONTAB_STOP);
            JY.jy_sys.mlog("切换角色 ***************** 切换角色触发定时任务，退出当前账号流程")
            return true
        end
    end
    return false
end
process.changeRoles = changeRoles

local function resetStatus(i)
    G.setRuntime("memoryParam.memoryState", 0)
    G.setRuntime("gameCheck.gameState", 0)
    JY.jy_file.filePut("当前序号", i + 1)

    jy_frame.setHUDTextWrapperWrapper(G.runtime("HUDID"), "切换账号")
    JY.jy_sys.mlog("游戏启动 ***************** 切换账号")
end
process.resetStatus = resetStatus

local function resetOtherStatus()
    if G.config("定时任务") and G.config("定时任务.启动状态") == 1 then
        G.setConfig("定时任务.执行状态", false)
        G.setConfig("定时任务.启动状态", 0)
        G.setRuntime("timingTask.Crontab", CONST.CRONTAB_RUN)
        G.setRuntime("timingTask.startCrontab", 0)
    end

    -- 轮次休息（增加非空判断）
    if G.config("轮号休息") and G.config("轮号休息") > 0 then
        jy_acc.lhSleep(G.config("轮号休息"))
    end

    -- 主号模式计数
    if G.config("主号模式") then
        G.setConfig("主号状态", (G.config("主号状态") or 0) + 1)
    end
end
process.resetOtherStatus = resetOtherStatus

local function runBefor(appid)
    G.setRuntime("ipStatus", true)

    -- 清理游戏文件流程
    if G.config("cleanGameFile") == true then
        JY.jy_sys.mlog("游戏启动 ***************** 清除游戏账号信息")
        jy_acc.cleanGameFile(appid)
        frameSdk.sleep(1000)
    end

    -- 设备改机流程
    if jy_ui.getUISet("改机", false) == true then
        JY.jy_sys.mlog("游戏启动 ***************** 改机")
        --改机(0, G.config("当前账号"), "/sdcard/Pictures/" .. JY["account_filename"] .. "_drive.txt",  appid, conf)
    end

    -- 运行后置方法
    if G.config("beforFun") and #G.config("beforFun") > 0 then
        for _, value in ipairs(G.config("beforFun")) do
            jy_task.otherRun(value, nil)
        end
    end
    -- 游戏存档处理
    if G.config("登录模式") == 1 then
        jy_acc.saveGameFile(appid, G.config("gameFile"), G.config("gameSavePath"), G.config("当前账号"))
    end
end
process.runBefor = runBefor
return process
