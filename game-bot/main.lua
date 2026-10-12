-- ==================== 征途手游挂机脚本 - 主入口 ====================
-- 基于懒人精灵的游戏自动化框架
-- 支持: 挂机 / 自动打本 / 自动刷资源 / 每日副本

-- 导入模块
local config = import("config/settings")        -- 全局配置（分辨率/延迟/战斗参数等）
local logger = import("utils/logger")           -- 日志系统（分级输出+文件轮转）
local antiDetect = import("utils/anti_detect")  -- 防检测（随机延迟+坐标抖动）
local CF = import("color_finder")               -- 找色模块（单点/多点/表找色）
local pageDetector = import("modules/page_detector")  -- 页面识别器（颜色模板匹配）
local battle = import("modules/battle")         -- 战斗逻辑（自动攻击/用药/技能）
local taskRunner = import("modules/task_runner") -- 任务执行器（流程引擎）
local exceptionHandler = import("modules/exception_handler")  -- 异常处理（弹窗/断线/卡死）
local dailyDungeon = import("modules/daily_dungeon")  -- 每日副本模块（找色+点击+重试）
local zhengtuColors = import("templates/zhengtu_colors")  -- 征途颜色模板（各界面特征色）

-- ==================== 全局状态 ====================

local STATE = {
    running = false,              -- 脚本是否运行中
    paused = false,               -- 是否暂停
    roundCount = 0,               -- 当前轮次计数
    startTime = 0,                -- 脚本启动时间戳
    lastActionTime = 0,           -- 最后一次操作时间戳
    totalActions = 0,             -- 总操作次数
    currentPage = "unknown",      -- 当前页面类型
    errorCount = 0,               -- 错误次数
    dailyDungeonCompleted = false, -- 每日副本是否已完成
    dailyDungeonFailed = false,   -- 每日副本是否失败（失败后本轮不再尝试）
}

-- 坐标表（找色结果存储，格式: {名称} = {x, y}）
local coordTbl = {}

-- 任务流程配置
local currentFlow = nil

-- ==================== 初始化 ====================

function init()
    print("========================================")
    print("  征途手游挂机脚本 v1.0")
    print("  基于懒人精灵框架")
    print("========================================")
    print()

    -- 1. 初始化日志系统
    -- 输出到控制台 + 文件，支持日志轮转（按大小/数量）
    logger.init({
        dir = config.log.dir,                  -- 日志目录
        level = config.log.level,              -- 日志级别 (debug/info/warn/error)
        console = config.log.console,          -- 是否输出到控制台
        maxFileSize = config.log.maxFileSize,  -- 日志文件最大大小(MB)
        maxFiles = config.log.maxFiles,        -- 保留日志文件数量
    })

    logger.info("屏幕分辨率: " .. device.width .. "x" .. device.height)
    logger.info("脚本版本: " .. config.app.version)

    -- 2. 初始化防检测模块
    -- 随机延迟 + 坐标抖动 + 操作频率限制，模拟人类行为
    antiDetect.init({
        minDelay = config.action.minDelay,           -- 最小延迟(毫秒)，默认1000ms
        maxDelay = config.action.maxDelay,           -- 最大延迟(毫秒)，默认3000ms
        clickJitterX = config.action.clickJitter,    -- 点击X轴抖动范围(像素)
        clickJitterY = config.action.clickJitter,    -- 点击Y轴抖动范围(像素)
        battleMinDelay = config.battle.attackInterval[1],  -- 战斗最小攻击间隔(毫秒)
        battleMaxDelay = config.battle.attackInterval[2],  -- 战斗最大攻击间隔(毫秒)
        longDelay = config.loop.restInterval,        -- 长时间等待(毫秒)
        jitterFactor = 0.2,                          -- 随机波动系数(±20%)
        maxActionsPerMinute = 60,                    -- 每分钟最大操作次数
    })

    -- 3. 初始化找色模块
    -- 设置默认相似度、查找方向、扫描区域比例
    CF.defaultConfig.defaultSim = config.color.defaultSim           -- 默认相似度(0~1)，默认0.9
    CF.defaultConfig.defaultDir = config.color.defaultDir           -- 默认查找方向(0~4)，默认0
    CF.defaultConfig.scanRegionScale = config.color.scanRegionScale -- 扫描区域比例(0~1)，默认0.85

    -- 4. 初始化页面识别器
    -- 注册各界面颜色模板，按优先级匹配当前页面
    pageDetector.init(zhengtuColors)

    -- 5. 初始化战斗模块
    -- 配置自动用药阈值、攻击间隔、战斗超时等
    battle.init({
        autoPotionThreshold = config.battle.autoPotionThreshold,  -- 血量低于此值(0~1)时自动用药，默认0.3
        attackMinDelay = config.battle.attackInterval[1],         -- 最小攻击间隔(毫秒)
        attackMaxDelay = config.battle.attackInterval[2],         -- 最大攻击间隔(毫秒)
        battleEndTimeout = config.battle.idleTimeout,             -- 战斗结束检测超时(毫秒)
        maxBattleTime = config.battle.timeout,                    -- 单场战斗最大时长(毫秒)
    })

    -- 6. 初始化任务执行器
    -- 支持顺序/条件/循环执行任务流程
    taskRunner.init()

    -- 7. 初始化异常处理模块
    -- 检测并处理广告弹窗、网络断开、卡死等异常
    exceptionHandler.init({
        adColor = config.exception.adColor,              -- 广告弹窗检测颜色
        adRegion = config.exception.adRegion,            -- 广告检测区域
        maxRetry = config.exception.maxRetry,            -- 最大重试次数
        retryDelay = config.exception.retryDelay,        -- 重试间隔(毫秒)
        deadlockThreshold = config.exception.deadlockThreshold,  -- 卡死检测阈值(毫秒)
    })

    -- 8. 初始化每日副本模块
    -- 配置副本时长、挂机阈值、退出延迟等
    dailyDungeon.init({
        dungeonDuration = config.dungeon.dungeonDuration or 3600000,  -- 副本时长(毫秒)，默认1小时
        autoHangThreshold = config.dungeon.autoHangThreshold or 0.3,  -- 挂机血量阈值(0~1)
        exitDelay = config.dungeon.exitDelay or 2000,                 -- 退出后延迟(毫秒)
    })

    -- 9. 注册异常处理回调
    -- 当检测到弹窗时触发此回调
    exceptionHandler.onException(function(popupName)
        logger.warn(string.format("弹窗回调: %s", popupName))
    end)

    logger.info("所有模块初始化完成")
    print()
end

-- ==================== 主循环 ====================

function mainLoop()
    STATE.running = true           -- 标记脚本运行中
    STATE.startTime = os.clock()   -- 记录启动时间
    STATE.roundCount = 0           -- 轮次计数归零
    STATE.errorCount = 0           -- 错误次数归零

    logger.info("===== 主循环开始 =====")
    logger.info(string.format("循环次数: %d, 休息间隔: %dms",
                              config.loop.maxCount, config.loop.restInterval))

    local roundStartTime = os.clock()  -- 当前轮次开始时间

    while STATE.running do
        -- 检查暂停状态
        if STATE.paused then
            sleep(1000)              -- 暂停时每秒检查一次
            continue
        end

        -- 检查单轮超时（防止单轮卡死）
        local roundElapsed = (os.clock() - roundStartTime) * 1000
        if config.loop.maxRoundTime > 0 and roundElapsed > config.loop.maxRoundTime then
            logger.warn(string.format("单轮超时: %.0f秒 > %.0f秒，退出",
                                      roundElapsed / 1000, config.loop.maxRoundTime / 1000))
            break
        end

        STATE.roundCount = STATE.roundCount + 1  -- 轮次计数+1

        logger.info(string.format("\n===== 第 %d 轮 =====", STATE.roundCount))

        -- 1. 异常检测与处理
        -- 检测广告弹窗、网络错误、卡死等，自动恢复或退出
        local exceptionStatus = exceptionHandler.detectAndHandle()

        if exceptionStatus == exceptionHandler.STATUS_FAILED then
            logger.error("异常处理失败，退出脚本")
            break
        end

        if exceptionStatus == exceptionHandler.STATUS_RECOVERED then
            logger.info("已从异常中恢复，继续执行")
            exceptionHandler.reset()  -- 重置异常状态
        end

        -- 2. 页面识别
        -- 通过颜色模板匹配判断当前界面类型
        local pageResult = pageDetector.identifyPage()
        STATE.currentPage = pageResult.page

        logger.info(string.format("当前页面: %s", pageResult.page))

        -- 3. 根据页面分发处理
        local handled = false

        if pageResult.page == pageDetector.PAGE_MAIN_MENU then
            -- 主界面：执行主界面流程 + 检查每日副本
            handled = handleMainMenu()

            -- 主界面处理完后，检查是否需要执行每日副本
            -- 条件：启用副本 + 未完成 + 未失败
            if handled and config.dungeon.enabled and not STATE.dailyDungeonCompleted
               and not STATE.dailyDungeonFailed then
                local floor = config.dungeon.defaultFloor or 1  -- 默认层数
                local ok = dailyDungeon.run(
                    zhengtuColors.daily_dungeon,  -- 每日副本颜色表
                    coordTbl,                     -- 坐标表
                    floor                         -- 层数
                )
                if ok then
                    STATE.dailyDungeonCompleted = true  -- 标记已完成
                    logger.info("每日副本已完成")
                else
                    STATE.dailyDungeonFailed = true     -- 标记失败，本轮不再尝试
                    logger.warn(string.format("每日副本失败 (层数:%d)，本轮不再执行", floor))
                end
            end

        elseif pageResult.page == pageDetector.PAGE_BATTLE then
            -- 战斗界面：执行自动战斗逻辑
            handled = handleBattle()

        elseif pageResult.page == pageDetector.PAGE_INVENTORY then
            -- 背包界面：自动关闭
            handled = handleInventory()

        elseif pageResult.page == pageDetector.PAGE_POPUP then
            -- 弹窗/广告：由异常处理模块处理
            handled = false

        elseif pageResult.page == pageDetector.PAGE_SETTLEMENT then
            -- 结算界面：点击继续
            handled = handleSettlement()

        elseif pageResult.page == pageDetector.PAGE_NETWORK_ERROR then
            -- 网络错误：由异常处理模块处理
            handled = false

        elseif pageResult.page == pageDetector.PAGE_LOADING then
            -- 加载界面：等待
            handled = handleLoading()

        else
            -- 未知页面：尝试返回主界面
            handled = handleUnknownPage(pageResult)
        end

        -- 如果当前页面未处理，等待后继续
        if not handled then
            logger.warn("当前页面未处理，等待...")
            sleep(2000)
        end

        -- 4. 轮间休息
        -- 每轮结束后休息一段时间，模拟人类操作间隔
        if config.loop.restInterval > 0 then
            logger.info(string.format("轮间休息 %dms", config.loop.restInterval))
            sleep(config.loop.restInterval)
        end

        -- 检查循环次数是否达到上限
        if config.loop.maxCount > 0 and STATE.roundCount >= config.loop.maxCount then
            logger.info(string.format("达到循环次数上限: %d", config.loop.maxCount))
            break
        end
    end

    -- 循环结束，执行结束处理
    endMainLoop()
end

-- ==================== 页面处理器 ====================

function handleMainMenu()
    logger.info("在主界面")

    -- 使用任务流程执行主界面操作（如点击开始战斗等）
    local flow = taskRunner.createMainFlow()
    local success = taskRunner.runFlow(flow, {
        coordTbl = coordTbl,                    -- 坐标表
        pageResult = pageDetector.identifyPage(), -- 当前页面结果
    })

    if success then
        STATE.lastActionTime = os.clock()  -- 更新最后操作时间
        STATE.totalActions = STATE.totalActions + 1  -- 操作计数+1
        return true
    end

    return false
end

--- 每日副本完成检查
-- 检查每日副本是否已完成或失败
function checkDailyDungeonCompleted()
    if not config.dungeon.enabled then return true end  -- 未启用副本则直接返回完成
    local status = dailyDungeon.getStatus()
    return status.status == dailyDungeon.STATUS_DONE  -- 状态为done表示完成
end

function handleBattle()
    logger.info("进入战斗")

    -- 执行战斗逻辑（自动攻击/用药/技能）
    battle.startBattle({
        potionColorTbl = zhengtuColors.battle.btn_potion or {},  -- 药水颜色表
        attackColorTbl = zhengtuColors.battle.btn_attack or {},  -- 攻击按钮颜色表
        potionCoordTbl = coordTbl,                               -- 药水坐标表
        attackCoordTbl = coordTbl,                               -- 攻击坐标表
    })

    STATE.lastActionTime = os.clock()
    STATE.totalActions = STATE.totalActions + 1
    return true
end

function handleInventory()
    logger.info("在背包界面，自动关闭")

    -- 找色点击关闭背包按钮
    local success = antiDetect.wrapAction(function()
        return CF.findAndClickByTable(
            zhengtuColors.inventory.btn_close_bag or {},  -- 关闭背包颜色表
            coordTbl,
            "关闭背包",
            500
        )
    end, "关闭背包")

    if success then
        STATE.lastActionTime = os.clock()
        return true
    end

    return false
end

function handleSettlement()
    logger.info("在结算界面")

    -- 找色点击继续按钮
    local success = antiDetect.wrapAction(function()
        return CF.findAndClickByTable(
            zhengtuColors.settlement.btn_settlement_continue or {},  -- 继续按钮颜色表
            coordTbl,
            "继续",
            2000
        )
    end, "点击继续")

    if success then
        STATE.lastActionTime = os.clock()
        return true
    end

    return false
end

function handleLoading()
    logger.info("加载中，等待...")
    sleep(3000)  -- 等待加载完成
    return true
end

function handleUnknownPage(pageResult)
    logger.warn(string.format("未知页面: %s", pageResult.page))

    -- 尝试返回主界面
    local success = antiDetect.wrapAction(function()
        return CF.findAndClickByTable(
            zhengtuColors.back.btn_back or {},  -- 返回按钮颜色表
            coordTbl,
            "返回",
            1000
        )
    end, "返回主界面")

    if success then
        sleep(2000)  -- 返回后等待
        return true
    end

    -- 如果返回失败，等待一下再试
    sleep(3000)
    return false
end

-- ==================== 结束处理 ====================

function endMainLoop()
    STATE.running = false  -- 标记脚本停止

    local totalElapsed = (os.clock() - STATE.startTime) * 1000  -- 总耗时(毫秒)
    local totalMinutes = totalElapsed / 60000                   -- 总耗时(分钟)

    logger.info("========================================")
    logger.info("===== 脚本执行结束 =====")
    logger.info(string.format("总轮次: %d", STATE.roundCount))           -- 总轮次数
    logger.info(string.format("总操作数: %d", STATE.totalActions))     -- 总操作次数
    logger.info(string.format("总耗时: %.1f 分钟", totalMinutes))      -- 总耗时
    logger.info(string.format("错误次数: %d", STATE.errorCount))       -- 错误次数
    logger.info("========================================")

    logger.close()  -- 关闭日志系统
end

-- ==================== 入口 ====================

-- 前台服务启动
-- 监听系统通知和Toast消息
events.observeNotification()
events.onToast(function(toast)
    logger.info(string.format("[Toast] %s", toast.getText()))  -- 记录Toast消息到日志
end)

-- 初始化所有模块
init()

-- 启动主循环
mainLoop()
