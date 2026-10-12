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
local condUtil = require("JY.Utils.ConditionUtils")
local evaluateCondition = condUtil.evaluateCondition

local TaskValidator = {}
TaskValidator.__index = TaskValidator

function TaskValidator:new()
    local self = setmetatable({}, TaskValidator)
    self.conditionCache = {}
    self.lastCheckTime = {}
    return self
end

function TaskValidator:validateActionStructure(action)
    if type(action) ~= "table" then
        return false, "动作必须是表类型"
    end
    if type(action[1]) ~= "string" then
        return false, "动作名称必须是字符串"
    end
    local numParam = action[2] or action[3]
    if type(numParam) ~= "number" and numParam ~= nil then
        return false, "动作参数必须是数字"
    end
    return true, "动作结构有效"
end

function TaskValidator:validateExecutionCount(action, record)
    if action["执行一次"] == nil and action["执行次数"] == nil then
        return true
    end
    local count = record and record.count or 0
    if action["执行一次"] == true and count >= 1 then
        return false, "已达到执行一次限制"
    end
    if action["执行次数"] ~= nil then
        local maxCount = type(action["执行次数"]) == "table" and JY.jy_num.toNumber(action["执行次数"], -1) or action["执行次数"]
        if maxCount > 0 and count >= maxCount then
            return false, "已达到执行次数限制"
        end
    end
    return true
end

function TaskValidator:validateTimeLag(action, record)
    if action["timeLag"] == nil then
        return true
    end
    local lastTime = record and record.time or 0
    local currentTime = os.time()
    if currentTime - action["timeLag"] < lastTime then
        return false, "未到指定时间间隔,剩余：" ..  action["timeLag"]  - (currentTime - lastTime) .."秒"
    end
    return true
end

function TaskValidator:validateCondition(action, vars)
    if action["执行条件"] == nil then
        return true
    end
    -- 缓存
    -- local cacheKey = action[1] .. "_condition"
    -- if self.conditionCache[cacheKey] ~= nil then
    --     print("result = " , self.conditionCache[cacheKey])
    --     return self.conditionCache[cacheKey]
    -- end
    local result = evaluateCondition(action)

    --self.conditionCache[cacheKey] = result
    return result
end

function TaskValidator:validateAll(action, record, vars)
    local valid, msg = self:validateActionStructure(action)
    if not valid then
        return false, msg
    end
    valid, msg = self:validateExecutionCount(action, record)
    if not valid then
        return false, msg
    end
    valid, msg = self:validateTimeLag(action, record)
    if not valid then
        return false, msg
    end
    valid = self:validateCondition(action, vars)
    if not valid then
        return false, "不满足执行条件"
    end
    return true, "验证通过"
end

function TaskValidator:clearCache()
    self.conditionCache = {}
    self.lastCheckTime = {}
end

function TaskValidator:validateTaskConfig(config)
    if not config or type(config) ~= "table" then
        return false, "配置无效"
    end
    if not config.taskName or config.taskName == "" then
        return false, "任务名称不能为空"
    end
    if config.forCount ~= nil and type(config.forCount) ~= "number" then
        return false, "循环次数必须是数字"
    end
    if config.outTime ~= nil and type(config.outTime) ~= "number" then
        return false, "超时时间必须是数字"
    end
    return true, "配置验证通过"
end

function TaskValidator:validateActionList(actions)
    if not actions or type(actions) ~= "table" then
        return false, "动作列表无效"
    end
    for k, v in pairs(actions) do
        if type(k) == "number" then
            local valid, msg = self:validateActionStructure(v)
            if not valid then
                return false, "动作[" .. k .. "] " .. msg
            end
        end
    end
    return true, "动作列表验证通过"
end

return TaskValidator