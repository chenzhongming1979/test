-- ==================== 异常处理模块 ====================
-- 广告弹窗、充值活动弹窗、网络断开、卡死检测与恢复

local M = {}

local logger = import("utils/logger")
local CF = import("color_finder")
local antiDetect = import("utils/anti_detect")

-- 异常类型
M.TYPE_POPUP = "popup"
M.TYPE_NETWORK_ERROR = "network_error"
M.TYPE_DEADLOCK = "deadlock"
M.TYPE_UNKNOWN_ERROR = "unknown"

-- 异常处理状态
M.STATUS_OK = "ok"
M.STATUS_HANDLING = "handling"
M.STATUS_RECOVERED = "recovered"
M.STATUS_FAILED = "failed"

-- 当前状态
local currentStatus = M.STATUS_OK
local lastNormalTime = os.clock()
local errorCount = 0
local maxErrorCount = 10

-- 弹窗颜色模板（需要实际采集）
local popupTemplates = {}

--- 初始化异常处理
-- @param config 可选, 配置覆盖
function M.init(config)
    config = config or {}

    M.adColor = config.adColor or "FFFFFF"
    M.adRegion = config.adRegion or {left = 0, top = 0, right = 720, bottom = 1280}
    M.maxRetry = config.maxRetry or 3
    M.retryDelay = config.retryDelay or 2000
    M.deadlockThreshold = config.deadlockThreshold or 30000
    M.autoRecover = config.autoRecover ~= false

    M.popupCallbacks = {}

    logger.info(string.format("异常处理初始化: 最大错误=%d, 卡死阈值=%dms",
                              M.maxErrorCount, M.deadlockThreshold))
end

--- 注册异常处理回调
function M.onException(callback)
    table.insert(M.popupCallbacks, callback)
end

--- 检测并处理异常
-- @return status: ok / handling / recovered / failed
function M.detectAndHandle()
    if currentStatus == M.STATUS_OK then
        lastNormalTime = os.clock()
    end

    -- 1. 检测卡死
    local deadlock = M.detectDeadlock()
    if deadlock then
        return M.handleDeadlock()
    end

    -- 2. 检测网络错误
    local networkError = M.detectNetworkError()
    if networkError then
        return M.handleNetworkError()
    end

    -- 3. 检测弹窗/广告
    local popup = M.detectPopup()
    if popup then
        return M.handlePopup(popup)
    end

    -- 4. 检测未知异常
    local unknown = M.detectUnknown()
    if unknown then
        return M.handleUnknown(unknown)
    end

    return M.STATUS_OK
end

--- 检测卡死（长时间无操作）
function M.detectDeadlock()
    local elapsed = (os.clock() - lastNormalTime) * 1000

    if elapsed > M.deadlockThreshold then
        logger.error(string.format("检测到卡死: 已 %dms 无操作", elapsed))
        return true
    end

    return false
end

--- 处理卡死
function M.handleDeadlock()
    currentStatus = M.STATUS_HANDLING
    logger.warn("开始处理卡死...")

    -- 尝试返回主界面
    local success = antiDetect.wrapAction(function()
        local backSuccess = CF.findAndClickByTable(
            M.backColorTbl or {},
            M.backCoordTbl or {},
            "返回",
            500
        )
        return backSuccess or false
    end, "返回主界面")

    if success then
        currentStatus = M.STATUS_RECOVERED
        logger.info("卡死恢复成功: 已返回主界面")
        return M.STATUS_RECOVERED
    else
        currentStatus = M.STATUS_FAILED
        logger.error("卡死恢复失败")
        return M.STATUS_FAILED
    end
end

--- 检测网络错误
function M.detectNetworkError()
    -- 通过颜色检测网络错误弹窗
    -- 需要实际采集网络错误页面的特征颜色
    if not M.networkErrorColorTbl or #M.networkErrorColorTbl == 0 then
        return false
    end

    local foundName = CF.findTemplateToTable(M.networkErrorColorTbl, {})
    return foundName ~= nil
end

--- 处理网络错误
function M.handleNetworkError()
    currentStatus = M.STATUS_HANDLING
    logger.warn("检测到网络错误，尝试重连...")

    local retryCount = 0

    while retryCount < M.maxRetry do
        retryCount = retryCount + 1
        logger.info(string.format("网络重连尝试: %d/%d", retryCount, M.maxRetry))

        local success = antiDetect.wrapAction(function()
            -- 点击重连按钮
            local reconnected = CF.findAndClickByTable(
                M.reconnectColorTbl or {},
                M.reconnectCoordTbl or {},
                "重连",
                1000
            )
            return reconnected or false
        end, "网络重连")

        if success then
            sleep(3000)  -- 等待重连成功

            -- 验证是否恢复
            local pageDetector = import("modules/page_detector")
            local page = pageDetector.identifyPage()

            if page.page ~= "network_error" then
                currentStatus = M.STATUS_RECOVERED
                logger.info(string.format("网络重连成功 (第%d次)", retryCount))
                return M.STATUS_RECOVERED
            end
        end

        sleep(M.retryDelay)
    end

    currentStatus = M.STATUS_FAILED
    logger.error("网络重连失败，已尝试 " .. M.maxRetry .. " 次")
    return M.STATUS_FAILED
end

--- 检测弹窗/广告
function M.detectPopup()
    if not M.popupColorTbl or #M.popupColorTbl == 0 then
        return false
    end

    local foundName = CF.findTemplateToTable(M.popupColorTbl, {})
    return foundName ~= nil
end

--- 处理弹窗/广告
function M.handlePopup(popupName)
    currentStatus = M.STATUS_HANDLING
    logger.warn(string.format("检测到弹窗: %s", popupName or "未知"))

    -- 通知回调
    for _, cb in ipairs(M.popupCallbacks) do
        pcall(cb, popupName)
    end

    local success = antiDetect.wrapAction(function()
        -- 尝试关闭弹窗
        local closed = CF.findAndClickByTable(
            M.closeBtnColorTbl or {},
            M.closeBtnCoordTbl or {},
            "关闭",
            300
        )
        return closed or false
    end, "关闭弹窗")

    if success then
        sleep(1000)  -- 等待关闭动画

        local pageDetector = import("modules/page_detector")
        local page = pageDetector.identifyPage()

        if page.page ~= "popup" then
            currentStatus = M.STATUS_RECOVERED
            logger.info("弹窗已关闭")
            return M.STATUS_RECOVERED
        end
    end

    currentStatus = M.STATUS_FAILED
    logger.error("弹窗关闭失败")
    return M.STATUS_FAILED
end

--- 检测未知异常
function M.detectUnknown()
    -- 检查是否连续错误过多
    errorCount = errorCount + 1

    if errorCount > M.maxErrorCount then
        logger.error(string.format("连续错误超过阈值 (%d次)，触发紧急处理", errorCount))
        return true
    end

    return false
end

--- 处理未知异常
function M.handleUnknown(reason)
    currentStatus = M.STATUS_HANDLING
    logger.error("未知异常: " .. tostring(reason))

    -- 尝试重启游戏
    local success = antiDetect.wrapAction(function()
        -- 强制停止应用
        local app = import("android.app.Application").getCurrentApplication()
        if app then
            app.forceStop()
        end
        return true
    end, "重启应用")

    if success then
        sleep(5000)  -- 等待应用重启
        currentStatus = M.STATUS_RECOVERED
        logger.info("应用已重启")
        return M.STATUS_RECOVERED
    end

    currentStatus = M.STATUS_FAILED
    return M.STATUS_FAILED
end

--- 重置异常状态
function M.reset()
    currentStatus = M.STATUS_OK
    lastNormalTime = os.clock()
    errorCount = 0
    logger.info("异常状态已重置")
end

--- 获取当前异常处理状态
function M.getStatus()
    return {
        status = currentStatus,
        errorCount = errorCount,
        lastNormalTime = lastNormalTime,
    }
end

return M
