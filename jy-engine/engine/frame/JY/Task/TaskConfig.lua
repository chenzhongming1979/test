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


local TaskConfig = {}
TaskConfig.__index = TaskConfig

function TaskConfig:new(configParams)
    local self = setmetatable({}, TaskConfig)
    self:loadFromTable(configParams or {})
    return self
end

function TaskConfig:loadFromTable(tab)
    self.outTime = tab["超时时间"] or tab.outTime or G.config("outTime") or 0
    self.noPageCount = tab["无特征次数"] or tab.noPageCount or 0
    self.wait = tab["强制休息"] or 10
    self.forCount = tab["循环次数"] or tab.forCount or -1
    self.ForCheck = tab["ForCheck"] ~= nil and tab["ForCheck"] or true
    self.mOutCount = tab["outCount"] or tab.mOutCount or 0
    self.taskName = tab["name"] or tab.taskName or ""
    self.tastByTime = tab["计时任务"] or tab.tastByTime
    self["其他事件延时"] = tab["其他事件延时"]
    self["无可执行事件"] = tab["无可执行事件"]
    self["顺序执行"] = tab["顺序执行"]
    self["超时方法"] = tab["超时方法"]
    self["卡屏不退"] = tab["卡屏不退"]
    self.mathchCheck = tab["mathchCheck"]
    self.mathchCheckSuccess = tab["mathchCheckSuccess"]
    self["截图至内存"] = tab["截图至内存"]
    self["执行条件"] = tab["执行条件"]
    self._dirty = true
    return self
end

function TaskConfig:validate()
    if type(self.taskName) ~= "string" or self.taskName == "" then
        return false, "任务名称不能为空"
    end
    if type(self.forCount) ~= "number" then
        return false, "循环次数必须是数字"
    end
    if type(self.outTime) ~= "number" then
        return false, "超时时间必须是数字"
    end
    return true, "配置验证通过"
end

function TaskConfig:get(key)
    return self[key]
end

function TaskConfig:set(key, value)
    self[key] = value
    self._dirty = true
end

function TaskConfig:toTable()
    if not self._cachedTable or self._dirty then
        self._cachedTable = {
            ["超时时间"] = self.outTime,
            ["无特征次数"] = self.noPageCount,
            wait = self.wait,
            ["循环次数"] = self.forCount,
            ForCheck = self.ForCheck,
            outCount = self.mOutCount,
            name = self.taskName,
            ["计时任务"] = self.tastByTime,
            ["其他事件延时"] = self["其他事件延时"],
            ["无可执行事件"] = self["无可执行事件"],
            ["顺序执行"] = self["顺序执行"],
            ["超时方法"] = self["超时方法"],
            ["卡屏不退"] = self["卡屏不退"],
            mathchCheck = self.mathchCheck,
            mathchCheckSuccess = self.mathchCheckSuccess,
            ["截图至内存"] = self["截图至内存"],
            ["执行条件"] = self["执行条件"]
        }
        self._dirty = false
    end
    return self._cachedTable
end

function TaskConfig:clone()
    return TaskConfig:new(self:toTable())
end

function TaskConfig:merge(otherConfig)
    if type(otherConfig) == "table" then
        for k, v in pairs(otherConfig) do
            if v ~= nil then
                self[k] = v
            end
        end
    end
end

function TaskConfig:toString()
    local str = "TaskConfig:\n"
    str = str .. "  任务名称: " .. self.taskName .. "\n"
    str = str .. "  超时时间: " .. self.outTime .. "\n"
    str = str .. "  循环次数: " .. self.forCount .. "\n"
    str = str .. "  等待时间: " .. self.wait .. "\n"
    str = str .. "  无特征次数: " .. self.noPageCount .. "\n"
    return str
end

return TaskConfig