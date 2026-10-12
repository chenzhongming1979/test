-- ==================== 每日副本模块 ====================
-- 每个动作 = 找色 + 点击，找不到就重试，超时/超次数报错退出

local M = {}

local logger = import("utils/logger")
local CF = import("color_finder")
local antiDetect = import("utils/anti_detect")

-- 状态
M.STATUS_IDLE = "idle"         -- 空闲状态
M.STATUS_RUNNING = "running"   -- 运行中
M.STATUS_DONE = "done"         -- 完成
M.STATUS_FAILED = "failed"     -- 失败

local currentStatus = M.STATUS_IDLE  -- 当前状态，初始为空闲
local dungeonStartTime = 0           -- 副本开始时间戳
local dungeonDuration = 3600000      -- 副本时长(毫秒)，默认1小时 = 3600000ms

--- 通用：带重试的动作
-- 核心逻辑：找色→点击→成功；找不到→重试；超时/超次数→返回false
-- @param actionName  动作名称（日志用）
-- @param colorTbl    颜色表
-- @param coordTbl    坐标表
-- @param timeout     最大等待时间(毫秒), 默认30秒
-- @param maxRetry    最大重试次数, 默认30
-- @param retryDelay  每次重试间隔(毫秒), 默认500
-- @param delayAfter  成功后点击后延迟(毫秒), 默认500
-- @return true=成功, false=失败
function M.doAction(actionName, colorTbl, coordTbl, timeout, maxRetry, retryDelay, delayAfter)
    timeout = timeout or 30000           -- 最大等待时间(毫秒)，默认30秒
    maxRetry = maxRetry or 30           -- 最大重试次数，默认30次
    retryDelay = retryDelay or 500      -- 每次重试间隔(毫秒)，默认500ms
    delayAfter = delayAfter or 500      -- 成功后点击后延迟(毫秒)，默认500ms

    logger.debug(string.format("[副本] %s: 开始等待 (超时%dms, 最大重试%d次)",
                              actionName, timeout, maxRetry))

    local startTime = os.clock()
    local retryCount = 0

    while true do
        local elapsed = (os.clock() - startTime) * 1000

        -- 超时检查
        if elapsed >= timeout then
            logger.error(string.format("[副本] %s: 超时%dms, 重试%d次, 放弃",
                                      actionName, timeout, retryCount))
            return false
        end

        -- 找色+点击
        local success = antiDetect.wrapAction(function()
            return CF.findAndClickByTable(colorTbl, coordTbl, actionName, delayAfter)
        end, actionName)

        if success then
            logger.info(string.format("[副本] %s: 成功 (耗时%.1fs, 重试%d次)",
                                      actionName, elapsed / 1000, retryCount))
            return true
        end

        retryCount = retryCount + 1

        -- 超过最大重试次数
        if retryCount >= maxRetry then
            logger.error(string.format("[副本] %s: 超过最大重试次数%d, 放弃",
                                      actionName, maxRetry))
            return false
        end

        -- 随机延迟后重试
        antiDetect.sleepRandom(retryDelay, retryDelay * 2)
    end
end

--- 初始化
function M.init(config)
    config = config or {}
    dungeonDuration = config.dungeonDuration or dungeonDuration
    logger.info(string.format("[副本] 初始化完成, 时长=%d秒", dungeonDuration / 1000))
end

--- 执行每日副本
-- @param colorTbl  颜色表 (zhengtuColors.daily_dungeon)
-- @param coordTbl  坐标表
-- @param floor     层数
-- @return true=完成, false=失败
function M.run(colorTbl, coordTbl, floor)
    floor = floor or 1
    currentStatus = M.STATUS_RUNNING
    dungeonStartTime = os.clock()

    logger.info(string.format("[副本] ===== 开始每日副本 (层数: %d) =====", floor))

    -- 定义动作序列: 每个动作 = {名称, 颜色表key, 超时, 最大重试, 点击后延迟}
    local actions = {
        {name = "汉堡菜单",   key = "hamburger_menu",   timeout = 15000, maxRetry = 15, delayAfter = 1500},  -- 超时15秒，最多重试15次，成功后延迟1.5秒
        {name = "每日副本",   key = "daily_dungeon_btn", timeout = 15000, maxRetry = 15, delayAfter = 2000},  -- 超时15秒，最多重试15次，成功后延迟2秒
        {name = "冰副本",     key = "ice_dungeon_btn",  timeout = 15000, maxRetry = 15, delayAfter = 1500},  -- 超时15秒，最多重试15次，成功后延迟1.5秒
        {name = "层数" .. floor, key = "floor_" .. floor, timeout = 15000, maxRetry = 15, delayAfter = 1000},  -- 超时15秒，最多重试15次，成功后延迟1秒
        {name = "确认进入",   key = "confirm_enter",    timeout = 15000, maxRetry = 15, delayAfter = 3000},  -- 超时15秒，最多重试15次，成功后延迟3秒
        {name = "自动挂机",   key = "auto_hang_scroll", timeout = 30000, maxRetry = 20, delayAfter = 1500},  -- 超时30秒，最多重试20次，成功后延迟1.5秒
    }

    -- 逐个执行动作
    for i, action in ipairs(actions) do
        local colorTblEntry = colorTbl[action.key] or {}
        if #colorTblEntry == 0 then
            logger.error(string.format("[副本] 颜色表 [%s] 为空，无法执行", action.key))
            currentStatus = M.STATUS_FAILED
            return false
        end

        local ok = M.doAction(action.name, colorTblEntry, coordTbl,
                              action.timeout, action.maxRetry, 500, action.delayAfter)

        if not ok then
            logger.error(string.format("[副本] 动作 [%s] 失败，副本流程终止", action.name))
            currentStatus = M.STATUS_FAILED
            return false
        end
    end

    -- 进入副本后的挂机循环
    logger.info("[副本] 已进入副本，开始挂机")
    M.runDungeonLoop()

    currentStatus = M.STATUS_DONE
    logger.info("[副本] ===== 每日副本完成 =====")
    return true
end

--- 副本内挂机循环（超时自动退出）
function M.runDungeonLoop()
    local round = 0              -- 当前轮次计数

    while currentStatus == M.STATUS_RUNNING do
        round = round + 1

        local elapsed = (os.clock() - dungeonStartTime) * 1000  -- 已运行时间(毫秒)
        if elapsed >= dungeonDuration then
            logger.info(string.format("[副本] 挂机超时: %.0f秒，退出副本", elapsed / 1000))
            M.exitDungeon()
            break
        end

        if round % 30 == 0 then  -- 每30轮输出一次日志
            local remaining = math.max(0, (dungeonDuration - elapsed) / 1000)
            logger.info(string.format("[副本] 挂机中... 剩余 %.0f秒, 轮次: %d", remaining, round))
        end

        sleep(1000)              -- 每轮间隔1秒
    end
end

--- 退出副本
function M.exitDungeon()
    logger.info("[副本] 退出副本")
    local colorTbl = zhengtuColors.daily_dungeon or {}  -- 颜色表
    local coordTbl = {}                                 -- 坐标表（空，退出时不需要）
    M.doAction("退出副本", colorTbl["dungeon_exit"] or {}, coordTbl, 10000, 5, 500, 2000)  -- 超时10秒，最多重试5次，间隔500ms，成功后延迟2秒
    currentStatus = M.STATUS_IDLE
end

--- 获取状态
function M.getStatus()
    return { status = currentStatus }
end

--- 重置
function M.reset()
    currentStatus = M.STATUS_IDLE  -- 重置为空闲状态
    dungeonStartTime = 0           -- 重置开始时间戳为0
end

return M
