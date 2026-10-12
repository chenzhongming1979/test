-- ==================== 防检测模块 ====================
-- 随机延迟 + 坐标抖动 + 操作频率限制 + 人类行为模拟

local M = {}

local CONFIG = {}

--- 初始化防检测配置
-- @param config 可选, 覆盖默认配置
function M.init(config)
    config = config or {}

    CONFIG.minDelay = config.minDelay or 1000
    CONFIG.maxDelay = config.maxDelay or 3000
    CONFIG.clickJitterX = config.clickJitterX or {3, 8}
    CONFIG.clickJitterY = config.clickJitterY or {3, 8}
    CONFIG.battleMinDelay = config.battleMinDelay or 500
    CONFIG.battleMaxDelay = config.battleMaxDelay or 1500
    CONFIG.longDelay = config.longDelay or 5000
    CONFIG.jitterFactor = config.jitterFactor or 0.2
    CONFIG.maxActionsPerMinute = config.maxActionsPerMinute or 60
    CONFIG.useAntiDetect = config.useAntiDetect ~= false

    -- 操作频率追踪
    CONFIG.actionTimestamps = {}
    CONFIG.lastActionTime = 0

    print("[AntiDetect] 初始化完成: delay=" .. CONFIG.minDelay .. "-" .. CONFIG.maxDelay .. "ms")
end

--- 获取随机延迟（带波动）
-- @param minDelay 可选, 最小延迟
-- @param maxDelay 可选, 最大延迟
-- @return 实际延迟毫秒数
function M.getRandomDelay(minDelay, maxDelay)
    minDelay = minDelay or CONFIG.minDelay
    maxDelay = maxDelay or CONFIG.maxDelay

    local base = math.random(minDelay, maxDelay)
    local jitter = math.floor(base * (CONFIG.jitterFactor or 0.2))
    local actual = base + math.random(-jitter, jitter)

    return math.max(100, actual)
end

--- 随机延迟（执行并等待）
function M.sleepRandom(minDelay, maxDelay)
    local delay = M.getRandomDelay(minDelay, maxDelay)
    sleep(delay)
    return delay
end

--- 随机偏移后点击
-- @param x 横坐标
-- @param y 纵坐标
-- @param jitterRange 可选, 抖动范围 {minX, maxX, minY, maxY}
-- @param delayAfter 可选, 点击后延迟(毫秒)
function M.randomClick(x, y, jitterRange, delayAfter)
    jitterRange = jitterRange or CONFIG.clickJitterX

    local minX, maxX, minY, maxY
    if type(jitterRange) == "table" and #jitterRange == 4 then
        minX, maxX, minY, maxY = jitterRange[1], jitterRange[2], jitterRange[3], jitterRange[4]
    else
        minX, maxX = jitterRange[1], jitterRange[2]
        minY, maxY = jitterRange[1], jitterRange[2]
    end

    local dx = math.random(minX, maxX)
    local dy = math.random(minY, maxY)

    -- 随机选择正负
    if math.random(1, 2) == 1 then dx = -dx end
    if math.random(1, 2) == 1 then dy = -dy end

    tap(x + dx, y + dy)

    if delayAfter then
        sleep(delayAfter)
    else
        M.sleepRandom(CONFIG.battleMinDelay, CONFIG.battleMaxDelay)
    end
end

--- 检查操作频率是否超限
-- @return true 可以执行, false 需要等待
function M.checkRateLimit()
    if not CONFIG.useAntiDetect then return true end

    local now = os.clock()
    local minuteAgo = now - 60

    -- 清理超过60秒的记录
    local active = {}
    for _, ts in ipairs(CONFIG.actionTimestamps) do
        if ts > minuteAgo then
            table.insert(active, ts)
        end
    end
    CONFIG.actionTimestamps = active

    if #active >= (CONFIG.maxActionsPerMinute or 60) then
        local oldest = active[1]
        local waitTime = math.ceil((oldest + 60 - now) * 1000)
        if waitTime > 0 then
            sleep(waitTime)
        end
        return false
    end

    CONFIG.actionTimestamps[#CONFIG.actionTimestamps + 1] = now
    return true
end

--- 包装一个操作（自动加防检测）
-- @param action 函数, 要执行的操作
-- @param actionName 可选, 操作名称（用于日志）
-- @return action的返回值
function M.wrapAction(action, actionName)
    if not CONFIG.useAntiDetect then
        return action()
    end

    -- 检查频率限制
    if not M.checkRateLimit() then
        return nil
    end

    if actionName then
        print(string.format("[Action] 执行: %s", actionName))
    end

    local startTime = os.clock()
    local result = action()
    local elapsed = math.floor((os.clock() - startTime) * 1000)

    if actionName then
        print(string.format("[Action] %s 完成, 耗时 %dms", actionName, elapsed))
    end

    return result
end

--- 模拟人类思考延迟（操作间随机等待）
-- @param minThink 可选, 最小思考时间(毫秒)
-- @param maxThink 可选, 最大思考时间(毫秒)
function M.think(minThink, maxThink)
    minThink = minThink or 500
    maxThink = maxThink or 2000

    -- 人类思考时间通常是 0.5~3 秒，带长尾分布
    local base = math.random(minThink, maxThink)
    -- 10% 概率出现较长思考（模拟走神）
    if math.random(1, 10) == 1 then
        base = base + math.random(2000, 5000)
    end

    sleep(base)
end

--- 模拟人类滑动（带随机速度和路径）
-- @param startX 起始X
-- @param startY 起始Y
-- @param endX 结束X
-- @param endY 结束Y
-- @param duration 可选, 滑动时长(毫秒)
function M.humanSwipe(startX, startY, endX, endY, duration)
    duration = duration or math.random(300, 500)

    -- 添加随机路径偏移（模拟手指不直）
    local midX = (startX + endX) / 2 + math.random(-20, 20)
    local midY = (startY + endY) / 2 + math.random(-20, 20)

    -- 分两段滑动，模拟人类弯曲手指
    swipe(startX, startY, midX, midY, math.floor(duration * 0.5))
    swipe(midX, midY, endX, endY, math.ceil(duration * 0.5))
end

--- 获取当前防检测配置摘要
function M.getConfigSummary()
    return {
        useAntiDetect = CONFIG.useAntiDetect,
        delayRange = CONFIG.minDelay .. "-" .. CONFIG.maxDelay,
        jitterRange = table.concat({CONFIG.clickJitterX[1], CONFIG.clickJitterX[2]}, "~"),
        maxActionsPerMinute = CONFIG.maxActionsPerMinute,
    }
end

return M
