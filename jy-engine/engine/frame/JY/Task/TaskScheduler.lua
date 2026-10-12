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

--- TaskScheduler - 任务调度器
--- 职责：任务入口判断、页面等待、任务分类调度
--- 与 TaskRunner（任务执行层）形成分层架构
---
--- 架构关系：
--- TaskScheduler (调度层) → TaskRunner (执行层)
---        ↓
---   TaskProcessControl (业务流程层，注入 runList 回调)

local JY = require("JY.Utils.JY")
local jy_task = require("JY.Utils.TaskUtils")
local TaskScheduler = {}
TaskScheduler.__index = TaskScheduler



--- 创建任务调度器实例
--- @param options table|nil 可选配置
---   - defaultTimeout: 默认超时时间
---   - onRunList: 任务列表执行回调 function(pageObject) -> result
---   - onTapSource: taps 数据源 function() -> tapsTable (默认使用全局 taps)
--- @return TaskScheduler 任务调度器实例
function TaskScheduler.new(options)
    local instance = setmetatable({}, TaskScheduler)
    instance.options = options or {}
    instance.defaultTimeout = instance.options.defaultTimeout or 2000

    -- 回调函数：任务列表执行（由 TaskProcessControl 注入）
    instance.onRunList = instance.options.onRunList

    -- 数据源：taps 获取函数（支持依赖注入，便于测试）
    instance.getTaps = instance.options.onTapSource or function()
        return G.taps()
    end

    return instance
end

--- 解析页面对象（内部方法，支持传入 taps）
--- @param page string|table 页面名称或页面对象
--- @param tapsSource table|nil taps 数据源
--- @return string pageName 页面名称
--- @return table|nil pageObject 页面对象
function TaskScheduler._parsePageObject(page, tapsSource)
    local pageName, pageObject = "", nil
    local source = tapsSource or G.taps()

    if type(page) == "string" then
        pageName = page
        pageObject = source and source[page] or nil
    elseif type(page) == "table" and page.name then
        pageObject = page
        pageName = page.name
    elseif type(page) == "table" then
        pageObject = page
        pageName = page[1] or "未知页面"
    end
    return pageName, pageObject
end

--- 解析页面对象（静态方法，使用全局 taps）
--- @param page string|table 页面名称或页面对象
--- @return string pageName 页面名称
--- @return table|nil pageObject 页面对象
function TaskScheduler.parsePageObject(page)
    return TaskScheduler._parsePageObject(page, G.taps())
end

--- 生成日志前缀
--- @param pageName string 页面名称
--- @return string 日志前缀字符串
function TaskScheduler.getLogPrefix(pageName)
    return "▼【进入任务】【" .. pageName .. "】====================="
end

--- 执行页面逻辑（内部方法）
--- 区分任务列表模式和单任务模式
--- @param pageObject table 页面对象
--- @return number|boolean 执行结果
function TaskScheduler:executePageLogic(pageObject)
    if pageObject["任务列表"] == true then
        -- 任务列表模式：调用注入的 onRunList 回调
        if self.onRunList then
            local result = self.onRunList(pageObject)
            return result or false
        else
            JY.jy_sys.mlog("【TaskScheduler】警告：未注入 onRunList 回调，无法执行任务列表")
            return false
        end
    else
        -- 单任务模式：使用 TaskRunner 执行
        local TaskRunner = require("JY.Task.TaskRunner")
        return TaskRunner.quickRun(pageObject)
    end
end

--- 处理退出页面
--- @param result number|boolean 任务执行结果
--- @param pageName string 页面名称
--- @param pageObject table 页面对象
function TaskScheduler:handleExitPage(result, pageName, pageObject)
    -- 只有当返回码不是13或14时才处理退出页面
    if result ~= 13 and result ~= 14 and not string.find(pageName .. "退出", "退出退出") then
        local exitPage = pageObject["退出方法"] or (pageName .. "退出")
        local currentTaps = self.getTaps()
        if currentTaps and currentTaps[exitPage] then
            local logPrefix = "█【进入任务】【" .. pageName .. "】====================="
            JY.jy_sys.mlog(logPrefix .. " 执行退出页面：" .. exitPage)
            self:runWait(exitPage)
        end
    end
end

--- 设置回调函数
--- @param event string 事件名称 (onRunList, onTapSource)
--- @param callback function 回调函数
function TaskScheduler:setCallback(event, callback)
    if event == "onRunList" then
        self.onRunList = callback
    elseif event == "onTapSource" then
        self.getTaps = callback
    end
end

--- 任务入口判断和分类处理（主方法）
--- @param page string|table 页面名称或页面对象
--- @param options table|nil 可选配置 {timeout=超时时间}
--- @return number|boolean 执行结果
function TaskScheduler:runWait(page, options)
    -- 释放内存截图（仅需一次）
    frameSdk.releaseCapture()
    if IsEmpty(options) then
        options = {}
        options.timeout = G.config("timeout") or self.defaultTimeout
    end
    -- 1. 初始化核心变量
    local returnCode = false
    local timeout = options.timeout

    -- 2. 获取 taps 数据源
    local currentTaps = self.getTaps()

    -- 3. 校验核心依赖（taps 为空直接终止）
    if not currentTaps then
        local logPrefix = TaskScheduler.getLogPrefix("未知页面")
        JY.jy_sys.mlog(logPrefix .. "无法获取界面特征数据（taps 为空）")
        return false
    end

    -- 4. 解析页面对象（使用静态方法解析时需要传入 taps）
    local pageName, pageObject = TaskScheduler._parsePageObject(page, currentTaps)

    -- 更新当前任务块
    G.setConfig("当前任务块", pageName)

    -- 5. 校验页面对象是否存在
    if not pageObject then
        -- 仅非"退出"页面输出日志
        if not string.find(pageName or "", "退出") then
            local logPrefix = TaskScheduler.getLogPrefix(pageName or "未知页面")
            JY.jy_sys.mlog(logPrefix .. "未找到任务")
        end
        return false
    end

    -- 6. 处理等待参数
    local waitParams = pageObject["任务界面"]
    if type(waitParams) == "string" then
        waitParams = currentTaps["通用界面"] and currentTaps["通用界面"][waitParams] or {}
    end

    -- 7. 扁平化主执行逻辑
    local logPrefix = TaskScheduler.getLogPrefix(pageName)
    if waitParams and #waitParams > 0 then
        local waitTimeout = pageObject.waitOut or timeout
        local waitResult = jy_task.pageWaitOut(waitParams, waitTimeout)

        if waitResult then
            JY.jy_sys.mlog(logPrefix .. "【成功】 等待时间：" .. waitTimeout)
            returnCode = self:executePageLogic(pageObject)
        else
            JY.jy_sys.mlog(logPrefix .. "【失败】 等待时间：" .. waitTimeout)
            returnCode = false
        end
    else
        JY.jy_sys.mlog(logPrefix .. " 无等待参数，直接执行")
        returnCode = self:executePageLogic(pageObject)
    end

    -- 8. 处理退出页面
    self:handleExitPage(returnCode, pageName, pageObject)

    return returnCode
end

--- 快速调度入口（静态便捷方法）
--- @param page string|table 页面名称或页面对象
--- @param options table|nil 可选配置 {timeout=超时时间}
--- @return number|boolean 执行结果
function TaskScheduler.quickRun(page, options)
    local scheduler = TaskScheduler.new(options)
    return scheduler:runWait(page, options)
end

--- 批量执行任务列表
--- @param taskList table 任务列表
--- @param options table|nil 可选配置
--- @return number|boolean 最后一个任务的返回码
function TaskScheduler:runTaskList(taskList, options)
    local lastResult = 0

    for _, task in ipairs(taskList) do
        if type(task) == "string" then
            lastResult = self:runWait(task, options)
        elseif type(task) == "table" then
            lastResult = self:runWait(task, options)
        end

        -- 特殊返回码处理（13: 游戏异常, 14: 定时任务）
        if lastResult == 13 or lastResult == 14 then
            return lastResult
        end
    end

    return lastResult
end

return TaskScheduler
