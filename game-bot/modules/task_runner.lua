-- ==================== 任务执行器 ====================
-- 流程引擎：支持顺序/条件/循环执行任务流程

local M = {}

local logger = import("utils/logger")
local pageDetector = import("modules/page_detector")

--- 任务类型常量
M.TYPE_CLICK = "click"
M.TYPE_WAIT = "wait"
M.TYPE_WAIT_PAGE = "wait_page"
M.TYPE_CONDITION = "condition"
M.TYPE_LOOP = "loop"
M.TYPE_BREAK = "break"
M.TYPE_CONTINUE = "continue"

--- 初始化任务执行器
function M.init()
    logger.info("任务执行器初始化完成")
end

--- 执行任务流程
-- @param flow 任务流程表，每个元素是一个动作
-- @param context 上下文表，传递给动作函数
-- @return success 是否成功执行完整个流程
function M.runFlow(flow, context)
    context = context or {}
    context.flowIndex = 0

    logger.info(string.format("开始执行任务流程: %d 个步骤", #flow))

    local success = true

    while context.flowIndex <= #flow do
        local step = flow[context.flowIndex]

        if not step then
            break
        end

        local actionType = step.type or step.action

        logger.debug(string.format("执行步骤 [%d/%d]: %s",
                                  context.flowIndex, #flow, actionType or "unknown"))

        local stepResult, newFlowIndex = M.executeStep(step, context)

        if stepResult == false then
            logger.warn(string.format("步骤 [%d] 失败: %s", context.flowIndex, actionType or "unknown"))
            success = false
            break
        end

        -- 更新流程索引
        if newFlowIndex then
            context.flowIndex = newFlowIndex
        else
            context.flowIndex = context.flowIndex + 1
        end
    end

    if success then
        logger.info("任务流程执行完成")
    else
        logger.warn("任务流程执行中断")
    end

    return success
end

--- 执行单个步骤
-- @param step 步骤配置
-- @param context 上下文
-- @return success, newFlowIndex
function M.executeStep(step, context)
    local actionType = step.type or step.action

    if actionType == M.TYPE_CLICK then
        return M.executeClick(step, context)
    elseif actionType == M.TYPE_WAIT then
        return M.executeWait(step, context)
    elseif actionType == M.TYPE_WAIT_PAGE then
        return M.executeWaitPage(step, context)
    elseif actionType == M.TYPE_CONDITION then
        return M.executeCondition(step, context)
    elseif actionType == M.TYPE_LOOP then
        return M.executeLoop(step, context)
    elseif actionType == M.TYPE_BREAK then
        return false, nil  -- 中断流程
    elseif actionType == M.TYPE_CONTINUE then
        context.flowIndex = context.flowIndex + 1
        return true, context.flowIndex
    else
        logger.warn(string.format("未知步骤类型: %s", actionType))
        return false, nil
    end
end

--- 点击操作
function M.executeClick(step, context)
    local target = step.target or step.point
    local delay = step.delay or 0

    if not target then
        logger.error("点击步骤缺少 target")
        return false
    end

    local success = false

    -- 支持多种 target 格式:
    -- 1. {name="按钮名"} - 从坐标表查找
    -- 2. {x=100, y=200} - 直接坐标
    -- 3. "page_detector_name" - 页面识别器返回的坐标

    if type(target) == "table" then
        if target.x and target.y then
            -- 直接坐标
            success = pcall(function()
                import("utils/anti_detect").randomClick(target.x, target.y, nil, delay)
            end)
        elseif target.name and context.coordTbl then
            -- 从坐标表查找
            local pos = context.coordTbl[target.name]
            if pos then
                success = pcall(function()
                    import("utils/anti_detect").randomClick(pos[1], pos[2], nil, delay)
                end)
            else
                logger.warn(string.format("坐标表中无 [%s]", target.name))
            end
        end
    elseif type(target) == "string" then
        -- 页面识别器名称
        if context.pageResult and context.pageResult.details then
            local detail = context.pageResult.details[target]
            if detail then
                success = pcall(function()
                    import("utils/anti_detect").randomClick(detail[1], detail[2], nil, delay)
                end)
            end
        end
    end

    return success, nil
end

--- 等待操作
function M.executeWait(step, context)
    local duration = step.duration or step.timeout or 1000
    logger.debug(string.format("等待 %dms", duration))
    sleep(duration)
    return true, nil
end

--- 等待指定页面
function M.executeWaitPage(step, context)
    local targetPage = step.page or step.target_page
    local timeout = step.timeout or 10000
    local interval = step.interval or 500

    if not targetPage then
        logger.error("等待页面步骤缺少 page")
        return false
    end

    logger.info(string.format("等待页面: %s (超时: %dms)", targetPage, timeout))

    local result = pageDetector.waitForPage(targetPage, timeout, interval)

    if result.matched then
        context.pageResult = result
        return true, nil
    else
        logger.warn(string.format("等待页面超时: %s", targetPage))
        return false, nil
    end
end

--- 条件判断
function M.executeCondition(step, context)
    local condition = step.condition
    local thenIndex = step.then  -- 条件满足时跳到的步骤索引
    local elseIndex = step.else   -- 条件不满足时跳到的步骤索引

    if not condition then
        logger.error("条件步骤缺少 condition")
        return false
    end

    -- 执行条件函数
    local result = false
    if type(condition) == "function" then
        result = condition(context)
    elseif type(condition) == "table" then
        -- 表格形式: {type="page_match", page="battle"}
        if condition.type == "page_match" then
            result = (context.pageResult and context.pageResult.page) == condition.page
        elseif condition.type == "hp_below" then
            local battle = import("modules/battle")
            result = battle.getHpPercent() < (condition.threshold or 0.3)
        end
    end

    if result then
        if thenIndex then
            context.flowIndex = thenIndex - 1  -- -1 因为 executeStep 会 +1
            return true, thenIndex
        end
    else
        if elseIndex then
            context.flowIndex = elseIndex - 1
            return true, elseIndex
        end
    end

    return true, nil  -- 条件不匹配但无 else，继续下一步
end

--- 循环
function M.executeLoop(step, context)
    local loopCount = step.count or step.iterations or 0
    local minDelay = step.minDelay or 0
    local maxDelay = step.maxDelay or 0

    logger.info(string.format("循环开始: 次数=%d", loopCount))

    for i = 1, loopCount do
        context.loopIndex = i
        context.loopTotal = loopCount

        -- 执行循环体（从当前步骤的下一个开始）
        local subFlow = step.body or {}
        if #subFlow > 0 then
            local subContext = table.copy(context)
            subContext.flowIndex = 0

            local subSuccess = M.runFlow(subFlow, subContext)

            if not subSuccess then
                logger.warn(string.format("循环第 %d/%d 次失败", i, loopCount))
                return false, nil
            end
        end

        -- 循环间延迟
        if minDelay > 0 or maxDelay > 0 then
            import("utils/anti_detect").sleepRandom(minDelay, maxDelay)
        end
    end

    logger.info(string.format("循环结束: 完成 %d/%d 次", loopCount, loopCount))
    return true, nil
end

--- 创建标准主界面流程
-- 返回一个可被 runFlow 执行的任务流程表
function M.createMainFlow()
    return {
        {type = M.TYPE_WAIT_PAGE, page = "main_menu", timeout = 10000},
        {type = M.TYPE_CLICK, target = {name = "开始战斗"}, delay = 2000},
        {type = M.TYPE_WAIT, duration = 3000},
    }
end

--- 创建标准战斗流程
function M.createBattleFlow()
    return {
        {type = M.TYPE_WAIT_PAGE, page = "battle", timeout = 15000},
        {type = M.TYPE_LOOP, count = 20, minDelay = 500, maxDelay = 1500, body = {
            {type = M.TYPE_CONDITION, condition = {type = "hp_below", threshold = 0.3},
             then = 3, else = 4},
            {type = M.TYPE_CLICK, target = {name = "使用药水"}, delay = 1500},
            {type = M.TYPE_CLICK, target = {name = "攻击"}, delay = 200},
        }},
        {type = M.TYPE_WAIT_PAGE, page = "settlement", timeout = 30000},
    }
end

return M
