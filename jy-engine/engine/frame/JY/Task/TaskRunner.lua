
--[[
Copyright (c) 2024 成都林深见鹿网络科技有限公司
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

本软件仅供个人学习研究使用。如需商业授权，请联系版权方.
--]]

local JY = require("JY.Utils.JY")
local jy_task = require("JY.Utils.TaskUtils")
local TaskConfig = require("JY/Task/TaskConfig")
local TaskValidator = require("JY/Task/TaskValidator")
local TaskScheduler = require("JY.Task.TaskScheduler")
local jy_param = require("JY.Utils.ParamUtils")
local condUtil = require("JY.Utils.ConditionUtils")
local evaluateCondition = condUtil.evaluateCondition
require("Funs")
local TaskRunner = {}
TaskRunner.__index = TaskRunner

-- helper to identify tasks that should not be added to the chain
local function isChainExcluded(name)
    return name == "通用任务退出" or name == "检测线程" or name == "通用弹窗" or name == "弹窗检测"
end

function TaskRunner:new(taskConfig, actions)
    local self = setmetatable({}, TaskRunner)

    if type(taskConfig) == "table" and taskConfig.outTime == nil then
        self.config = TaskConfig:new(taskConfig)
    else
        self.config = taskConfig
    end

    self.actions = actions or {}
    self.validator = TaskValidator:new()

    self.state = {
        code = -1,
        whileCount = 1,
        outCount = 0,
        errorCount = 0,
        currentTime = os.time()
    }

    self.actionRecords = {}
    self.startTime = os.time()
    self.isRunning = false
    self.returnCode = 0
    self.actionCode = -1

    self.callbacks = {
        onTimeout = nil,
        onStuck = nil,
        onExit = nil,
        onError = nil
    }

    self:initializeActionRecords()
    self:initializeFilePaths()
    self:initializeActions()
    self.checkCounter = 0

    return self
end

function TaskRunner:initializeActionRecords()
    self.actionRecords = {
        time = nil,
        count = 0
    }
end

function TaskRunner:initializeFilePaths()
    local name = self.config.taskName
    local timestamp = os.date("%Y-%m-%d %H_%M_%S")
    local currentTask = G.config("当前任务") or "未知任务"
    local screenshotPath = G.config("截图路径") or ""
    self.outTimeFileName = screenshotPath .. "【" .. currentTask .. "】" .. name .. "__超时_" .. timestamp .. ".png"
    self.stuckFileName = screenshotPath .. "【" .. currentTask .. "】【" .. name .. "】__卡屏_" .. timestamp .. ".png"
end

function TaskRunner:setCallback(event, callback)
    if self.callbacks[event] ~= nil then
        self.callbacks[event] = callback
    end
end

function TaskRunner:getCallback(event)
    return self.callbacks[event]
end

function TaskRunner:resetState()
    self.state = {
        code = -1,
        whileCount = 1,
        outCount = 0,
        errorCount = 0,
        currentTime = os.time()
    }
    self.actionCode = -1
    self.returnCode = 0
    self:initializeActionRecords()
end

function TaskRunner:getState()
    return self.state
end

function TaskRunner:setState(key, value)
    self.state[key] = value
end

function TaskRunner:initializeActionRecord(actionName)
    if self.actionRecords[actionName] == nil then
        self.actionRecords[actionName] = {
            count = 0,
            time = 0
        }
    end
end

function TaskRunner:initializeActions()
    self.sortedActions = {}
    if type(self.actions) ~= "table" then
        return
    end
    for k, v in pairs(self.actions) do
        if type(k) == "number" then
            table.insert(self.sortedActions, {index = k, action = v})
        end
    end
    table.sort(self.sortedActions, function(a, b) return a.index < b.index end)
end

function TaskRunner:getActionRecord(actionName)
    return self.actionRecords[actionName]
end

function TaskRunner:updateActionRecord(actionName, updateTime)
    self:initializeActionRecord(actionName)
    self.actionRecords[actionName].count = self.actionRecords[actionName].count + 1
    if updateTime then
        self.actionRecords[actionName].time = os.time()
    end
end

function TaskRunner:run()
    if not isChainExcluded(self.config.taskName) and G.config("当前任务链") ~= nil then
        G.setConfig("当前任务链", G.config("当前任务链") .. ">" .. self.config.taskName)
    end
    self.isRunning = true
    self.startTime = os.time()
    self.state.currentTime = os.time()

    local result = self:executeLoop()

    self.isRunning = false
    frameSdk.releaseCapture()

    if not isChainExcluded(self.config.taskName) and G.config("当前任务链") ~= nil then
        G.setConfig("当前任务链", string.gsub(G.config("当前任务链"), ">" .. self.config.taskName,""))
    end
    return result
end

function TaskRunner:executeLoop()
    while self.isRunning do
        frameSdk.releaseCapture()

        -- 轻量级检查（每次循环）
        local returnCode = self:checkTimingTask()
        if returnCode ~= 0 then
            return returnCode
        end

        -- 重型检查（每10次循环检查一次）
        self.checkCounter = (self.checkCounter or 0) + 1
        if (self.checkCounter % 10) == 0 then
            returnCode = self:checkTaskConditions()
            if returnCode ~= 0 then
                return returnCode
            end
        end

        if self.config["截图至内存"] == true then
            frameSdk.keepCapture()
        end

        local checkCode = self:executeActions()
        if checkCode ~= true then
            return checkCode
        end

        frameSdk.releaseCapture()

        checkCode = self:checkLoopConditions()
        if checkCode ~= true then
            return checkCode
        end
        if self.config.wait ~= 10 then
            JY.jy_sys.mlog(string.format("强制休息==============================【%s】 设置强制休息%d", self.config.taskName, self.config.wait))
            frameSdk.sleep(self.config.wait)
        end

        self.actionCode = -1
        self.state.currentTime = os.time()
    end

    return self.returnCode
end

function TaskRunner:checkTaskConditions()
    if self.config.tastByTime ~= nil and self.state.currentTime - self.startTime > self.config.tastByTime[1] then
        JY.jy_sys.mlog(string.format("简游调试：【%s】 计时任务方法<%s>", self.config.taskName, tostring(self.config.tastByTime[1])))
        TaskScheduler.new():runWait(self.config.tastByTime[2], nil)
        return 14
    end

    if self.config.outTime ~= 0 and self.state.currentTime - self.startTime > self.config.outTime and self.config.ForCheck == true then
        if self.config["超时方法"] ~= nil then
            jy_task.otherRun(self.config["超时方法"], self.config:toTable())
        end

        if G.config("是否截取卡死界面") then
            JY.jy_sys.mlog(string.format("任务***************【检测到任务< %s >超时,截取超时图片】***************", self.config.taskName))
            frameSdk.snapShot(self.outTimeFileName)
        end

        JY.jy_sys.mlog(string.format("▲【退出任务】【%s】===================== 超时退出， 超时时间 【%s】", self.config.taskName, os.date("%M 分 %S 秒", self.config.outTime)))

        if self.callbacks.onTimeout then
            self.callbacks.onTimeout(self)
        end

        return 6
    end

    if (G.runtime("timingTask.gameState") == -1 or G.runtime("gameCheck.gameState") > (G.config("卡屏次数") or 3)) and self.config.taskName ~= "检测线程" then
        if G.config("是否截取卡死界面") and G.config("当前任务") ~= "通用任务退出" then
            JY.jy_sys.mlog(string.format("任务***************【检测到任务< %s >卡屏,截取卡屏图片】***************", self.config.taskName))
            frameSdk.snapShot(self.stuckFileName)
        end

        if G.config("卡屏重启") == true then
            jy_task.FunRegistry.runSetFun("restart")
        end

        if self.config["卡屏不退"] ~= true and G.config("当前任务") ~= "通用任务退出" then
            JY.jy_sys.mlog(string.format("任务***************【检测到任务< %s >卡屏,退出整个任务】***************", self.config.taskName))

            if self.callbacks.onStuck then
                self.callbacks.onStuck(self)
            end

            return 13
        end

        G.setRuntime("gameCheck.gameState", 0)
    end

    local xtime = G.runtime("netParam.st")
    if G.runtime("netParam.t") ~= nil and (xtime == nil or (frameSdk.systemTime() - xtime > 1000 * 60 * 60 * 3)) then
        JY.jy_sys.mlog(frameSdk.systemTime().. " - ".. xtime.." = "..systemTime() - xtime)
        frameSdk.exitScript()
    end

    return 0
end

function TaskRunner:checkTimingTask()
    if G.runtime("timingTask.startCrontab") == 1 and self.config.taskName ~= "检测线程" and G.runtime("gameCheck.gameState") > 0 then
        G.setConfig("定时任务.执行状态", true)
        G.setRuntime("timingTask.Crontab", 0)
        G.setRuntime("gameCheck.gameState", -1)
        frameSdk.toast("检测到定时任务开始,退出流程！")
        JY.jy_sys.mlog("***************【检测到定时任务开始,退出整个任】务***************")
        return 14
    end

    if G.runtime("gameCheck.gameState") == -2 then
        frameSdk.toast("检测到定时任务开始,退出流程！")
        JY.jy_sys.mlog("***************【检测到定时任务开始,退出整个任务】***************")
        return 14
    end

    if G.runtime("gameCheck.gameState") == -3 then
        frameSdk.toast("检测到游戏不在前台退出流程！")
        JY.jy_sys.mlog("***************【检测到游戏不在前台退出整个任务】***************")
        return 14
    end

    return 0
end

function TaskRunner:executeActions()
    local checkCode = -10

    -- 使用预排序动作列表，避免每次循环判断 key 类型
    for _, item in ipairs(self.sortedActions or {}) do
        local v = item.action

        if self.config["执行条件"] ~= nil then
            if not evaluateCondition(self.config:toTable()) then
                JY.jy_sys.mlog(string.format("▲【退出任务】【%s】===================== 不满足执行条件，退出任务", self.config.taskName))
                return 2
            end
        end

        if self:validateAndExecuteAction(v, item.index) then
            v["taskName"] = self.config.taskName
            local ActionFramework = require("JY.Actions.ActionFramework")
            self.actionCode =  ActionFramework.actions(v)
            if self.actionCode == false then
                -- 未命中行级日志：扫描式调度表调试关键——无此日志时未命中行全程静默，
                -- 只能靠退出码反推。degree 为行阈值，供"改阈值 or 重采样"判定。
                JY.jy_sys.acLog("未命中特征 -- 阈值degree=" .. tostring(v["degree"] or "默认") .. " 行#" .. tostring(item.index), v)
            end
            checkCode = self:processActionResult(v, self.actionCode)
            if checkCode ~= true then
                return checkCode
            end
        end

        JY.jy_sys.sleep(10)
    end

    return true
end

function TaskRunner:validateAndExecuteAction(action, index)
    self:initializeActionRecord(action[1])
    local record = self:getActionRecord(action[1])

    local valid, msg = self.validator:validateAll(action, record, vars)
    if not valid then
        JY.jy_sys.acLog(msg, action)
        return false
    end

    return true
end

function TaskRunner:processActionResult(action, actionCode)
    if (action["outTask"] == actionCode and action["outTaskSure"] ~= false) or (action["outTaskSure"] ~= false and actionCode == 12) then
        JY.jy_sys.mlog(string.format("▲【退出任务】【%s】===================== 检测到[%s]执行后，退出流程！", self.config.taskName, action[1]))
        return 12
    end

    if action["outCode"] ~= nil then
        if type(action["outCode"]) ~= "table" and action["outCode"] == actionCode then
            JY.jy_sys.mlog(string.format("▲【退出任务】【%s】===================== 检测到[%s]指定退出的ActionCode，退出！", self.config.taskName, action[1]))
            return 3
        elseif type(action["outCode"]) == "table" then
            for k2, v2 in pairs(action["outCode"]) do
                if v2 == actionCode then
                    JY.jy_sys.mlog(string.format("▲【退出任务】【%s】===================== 检测到[%s]指定退出的ActionCode，退出！", self.config.taskName, action[1]))
                    return 3
                end
            end
        end
    end

    if actionCode == 13 then
        JY.jy_sys.mlog(string.format("▲【退出任务】【%s】===================== 游戏异常,退出流程！", self.config.taskName))
        return 13
    end

    if actionCode == 14 then
        JY.jy_sys.mlog(string.format("▲【退出任务】【%s】===================== 定时任务开始,退出流程！", self.config.taskName))
        return 14
    end

    if actionCode == 11 then
        JY.jy_sys.mlog(string.format("▲【退出任务】【%s】===================== 检测到[%s]执行后变量变化，退出当前任务", self.config.taskName, action[1]))
        return 10
    end

    if actionCode == true or actionCode == 1 or actionCode == 4 or actionCode == 5 or actionCode == 11 or actionCode == 3 then
        self.state.code = actionCode
        local record = self:getActionRecord(action[1])

        if (action["执行次数"] ~= nil and type(action["执行次数"]) == "number") or action["执行一次"] == true then
            local count = record.count or 0
            self.actionRecords[action[1]].count = count + 1
        end

        if action["timeLag"] ~= nil then
            self.actionRecords[action[1]].time = os.time()
        end

        if action["out"] == true then
            JY.jy_sys.mlog(string.format("▲【退出任务】【%s】===================== 检测到out，退出！", self.config.taskName))
            return 1
        end
    end

    if actionCode == 8 then
        JY.jy_sys.mlog(string.format("▲【退出任务】【%s】===================== 检测到[%s]指定ActionCode，退出！", self.config.taskName, action[1]))
        return 8
    end

    if action["notOutCode"] ~= nil and action["notOutCode"] ~= actionCode then
        JY.jy_sys.mlog(string.format("▲【退出任务】【%s】===================== 未检测到[%s]指定ActionCode，退出！", self.config.taskName, action[1]))
        return 9
    end

    if action["notOut"] == true and actionCode == false then
        JY.jy_sys.mlog(string.format("▲【退出任务】【%s】===================== 未检测到[%s] 退出！", self.config.taskName, action[1]))
        return 3
    end

    if self.config["顺序执行"] == true and actionCode == false then
        JY.jy_sys.mlog(string.format("▲【退出任务】【%s】===================== 未检测到[%s] 开启顺序执行退出", self.config.taskName, action[1]))
        return 7
    end

    if actionCode == true and self.config.mOutCount ~= 0 and action["mathchCheck"] == true then
        self.state.outCount = self.state.outCount + 1
        if self.state.outCount >= self.config.mOutCount then
            JY.jy_sys.mlog(string.format("▲【退出任务】【%s】===================== 连续检测到[%s] %d次，退出！", self.config.taskName, action[1], self.state.outCount))
            if self.config.mathchCheckSuccess then
                jy_param.taskRecord()
            end
            return 4
        end
    end

    return true
end

function TaskRunner:checkLoopConditions()
    if self.state.code == -1 then
        if self.config["无可执行事件"] ~= nil then
            jy_task.otherRun(self.config["无可执行事件"], self.config:toTable())
        end
        self.state.errorCount = self.state.errorCount + 1
        if self.state.errorCount == self.config.noPageCount and self.config.ForCheck then
            JY.jy_sys.mlog(string.format("▲【退出任务】【%s】===================== 无可执行，退出！", self.config.taskName))
            return 15
        end

        if self.config["其他事件延时"] ~= nil then
            JY.jy_sys.acLog(self.config["其他事件延时"])
        end
    else
        self.state.errorCount = 0
    end

    if self.config.forCount ~= -1 then
        JY.jy_sys.mlog(string.format("【%s】 循环执行第%d次", self.config.taskName, self.state.whileCount))
        self.state.whileCount = self.state.whileCount + 1
        if self.state.whileCount > self.config.forCount then
            JY.jy_sys.mlog(string.format("▲【退出任务】【%s】===================== 指定循环次数退出", self.config.taskName))
            return 5
        end
    end

    return true
end

function TaskRunner:stop()
    self.isRunning = false
end

function TaskRunner:isTaskRunning()
    return self.isRunning
end

function TaskRunner:getElapsedTime()
    return os.time() - self.startTime
end

function TaskRunner:getConfig()
    return self.config
end

function TaskRunner:setConfig(config)
    if type(config) == "table" then
        if config.outTime ~= nil then
            self.config = config
        else
            self.config:merge(config)
        end
    end
end

function TaskRunner:getActionRecords()
    return self.actionRecords
end

function TaskRunner:clearActionRecords()
    self.actionRecords = {}
end

function TaskRunner:getActions()
    return self.actions
end

function TaskRunner:setActions(actions)
    self.actions = actions or {}
end

function TaskRunner:addAction(action)
    table.insert(self.actions, action)
end

function TaskRunner.parseConfig(configTable)
    local taskConfig = {}
    local actions = {}

    for k, v in pairs(configTable) do
        if type(k) == "string" then
            taskConfig[k] = v
        elseif type(k) == "number" then
            actions[k] = v
        end
    end

    return taskConfig, actions
end

function TaskRunner.quickRun(configTable)
    local taskConfig, actions = TaskRunner.parseConfig(configTable)
    local runner = TaskRunner:new(taskConfig, actions)
    return runner:run()
end

return TaskRunner