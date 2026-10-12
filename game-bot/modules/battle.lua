-- ==================== 战斗逻辑模块 ====================
-- 自动攻击、用药、技能管理、战斗结束检测

local M = {}

local logger = import("utils/logger")
local CF = import("color_finder")
local antiDetect = import("utils/anti_detect")

-- 战斗状态
M.STATUS_IDLE = "idle"
M.STATUS_ATTACKING = "attacking"
M.STATUS_USE_POTION = "use_potion"
M.STATUS_SKILL = "skill"
M.STATUS_ENDING = "ending"

-- 当前战斗状态
local currentStatus = M.STATUS_IDLE
local battleStartTime = 0
local lastActionTime = 0
local totalAttacks = 0
local totalPotions = 0
local lastHpPercent = 100

--- 初始化战斗配置
-- @param config 可选, 战斗配置覆盖
function M.init(config)
    config = config or {}

    M.autoPotionThreshold = config.autoPotionThreshold or 0.3
    M.attackMinDelay = config.attackMinDelay or 500
    M.attackMaxDelay = config.attackMaxDelay or 1500
    M.skillCheckInterval = config.skillCheckInterval or 1000
    M.battleEndTimeout = config.battleEndTimeout or 5000
    M.maxBattleTime = config.maxBattleTime or 120000  -- 2分钟

    M.statusChangeCallbacks = {}

    logger.info(string.format("战斗模块初始化: 用药阈值=%.0f%%, 攻击间隔=%d-%dms",
                              M.autoPotionThreshold * 100,
                              M.attackMinDelay, M.attackMaxDelay))
end

--- 注册状态变化回调
function M.onStatusChange(callback)
    table.insert(M.statusChangeCallbacks, callback)
end

--- 切换战斗状态
local function setStatus(newStatus)
    if currentStatus == newStatus then return end
    local oldStatus = currentStatus
    currentStatus = newStatus

    for _, cb in ipairs(M.statusChangeCallbacks) do
        pcall(cb, oldStatus, newStatus)
    end

    logger.info(string.format("战斗状态: %s -> %s", oldStatus, newStatus))
end

--- 获取当前HP百分比（通过颜色检测）
-- @return hpPercent 0~1, nil 表示无法检测
function M.getHpPercent()
    -- 默认血条位置（屏幕左上角）
    local hpX = math.floor(device.width * 0.05)
    local hpY = math.floor(device.height * 0.05)

    local color = getPixelColor(hpX, hpY)
    if not color or color == "000000" then
        return nil
    end

    -- 简单判断: 红色分量高 = 血量低
    local r, g, b = colorToRGB(color)
    local total = r + g + b
    if total == 0 then return nil end

    -- 红色占比越高，血量越低
    local hpPercent = 1 - (r / total)
    return math.max(0, math.min(1, hpPercent))
end

--- 检查是否需要用药
function M.needPotion()
    local hp = M.getHpPercent()
    if not hp then return false end

    local need = hp < M.autoPotionThreshold
    if need and lastHpPercent ~= nil then
        local dropPercent = (lastHpPercent - hp) * 100
        if dropPercent > 5 then
            logger.warn(string.format("血量骤降: %.0f%% -> %.0f%% (-%.1f%%)",
                                      lastHpPercent * 100, hp * 100, dropPercent))
        end
    end

    lastHpPercent = hp
    return need
end

--- 使用药水
function M.usePotion()
    if not M.needPotion() then return false end

    setStatus(M.STATUS_USE_POTION)
    logger.info("血量不足，使用药水")

    -- 查找药水按钮（通过颜色模板）
    local success = antiDetect.wrapAction(function()
        -- 这里需要实际的颜色模板，示例用坐标
        local potionPos = CF.findAndClickByTable(
            M.potionColorTbl or {},
            M.potionCoordTbl or {},
            "使用药水",
            300
        )
        return success or false
    end, "使用药水")

    totalPotions = totalPotions + 1
    setStatus(M.STATUS_ATTACKING)
    return success
end

--- 执行一次攻击
function M.attack()
    setStatus(M.STATUS_ATTACKING)

    local success = antiDetect.wrapAction(function()
        local attackPos = CF.findAndClickByTable(
            M.attackColorTbl or {},
            M.attackCoordTbl or {},
            "攻击",
            100
        )
        return success or false
    end, "攻击")

    if success then
        totalAttacks = totalAttacks + 1
        antiDetect.sleepRandom(M.attackMinDelay, M.attackMaxDelay)
    end

    return success
end

--- 检查并使用技能
function M.useSkill()
    -- 技能检测逻辑需要根据实际游戏实现
    -- 这里作为框架占位
    setStatus(M.STATUS_SKILL)
    logger.debug("技能冷却检查")
    setStatus(M.STATUS_ATTACKING)
    return false  -- 框架占位，需实际实现
end

--- 检测战斗是否结束
-- @return true 表示战斗已结束
function M.isBattleEnded()
    local elapsed = os.clock() - battleStartTime

    if elapsed > M.maxBattleTime then
        logger.warn(string.format("战斗超时: %.0f秒 > %.0f秒", elapsed / 1000, M.maxBattleTime / 1000))
        return true
    end

    -- 检查是否回到非战斗页面
    local pageDetector = import("modules/page_detector")
    local pageResult = pageDetector.identifyPage()

    if pageResult.page ~= M.PAGE_BATTLE and pageResult.page ~= M.PAGE_UNKNOWN then
        logger.info(string.format("战斗结束: 页面变为 %s", pageResult.page))
        return true
    end

    -- 检查 idle 时间（连续多次未检测到变化）
    if os.clock() - lastActionTime > M.battleEndTimeout / 1000 then
        logger.info("战斗疑似结束: 长时间无操作")
        return true
    end

    return false
end

--- 开始战斗循环
-- @param config 战斗配置 {potionColorTbl, attackColorTbl, ...}
function M.startBattle(config)
    config = config or {}
    M.potionColorTbl = config.potionColorTbl or {}
    M.attackColorTbl = config.attackColorTbl or {}
    M.potionCoordTbl = config.potionCoordTbl or {}
    M.attackCoordTbl = config.attackCoordTbl or {}

    battleStartTime = os.clock()
    totalAttacks = 0
    totalPotions = 0
    lastHpPercent = 100
    lastActionTime = os.clock()

    logger.info("===== 战斗开始 =====")
    setStatus(M.STATUS_ATTACKING)

    local round = 0

    while not M.isBattleEnded() do
        round = round + 1

        -- 1. 检查用药
        if M.needPotion() then
            M.usePotion()
            lastActionTime = os.clock()
        end

        -- 2. 检查技能
        M.useSkill()

        -- 3. 执行攻击
        M.attack()
        lastActionTime = os.clock()

        -- 4. 检查战斗结束
        if M.isBattleEnded() then
            break
        end

        -- 日志输出（每10轮一次）
        if round % 10 == 0 then
            logger.info(string.format("战斗轮次: %d, 攻击: %d, 药水: %d", round, totalAttacks, totalPotions))
        end
    end

    logger.info(string.format("===== 战斗结束 ===== 总攻击: %d, 总药水: %d, 耗时: %.1f秒",
                              totalAttacks, totalPotions,
                              (os.clock() - battleStartTime) / 1000))

    setStatus(M.STATUS_IDLE)
end

--- 重置战斗统计
function M.resetStats()
    totalAttacks = 0
    totalPotions = 0
    battleStartTime = 0
    lastHpPercent = 100
    currentStatus = M.STATUS_IDLE
end

return M
