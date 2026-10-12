-- ==================== 页面识别器 ====================
-- 通过颜色模板匹配判断当前游戏界面
-- 支持多模板优先级匹配，返回页面类型和匹配信息

local M = {}

local logger = import("utils/logger")
local CF = import("color_finder")

-- 页面类型常量
M.PAGE_UNKNOWN = "unknown"
M.PAGE_MAIN_MENU = "main_menu"
M.PAGE_BATTLE = "battle"
M.PAGE_INVENTORY = "inventory"
M.PAGE_EQUIPMENT = "equipment"
M.PAGE_TASK = "task"
M.PAGE_POPUP = "popup"
M.PAGE_LOADING = "loading"
M.PAGE_SETTLEMENT = "settlement"
M.PAGE_NETWORK_ERROR = "network_error"

-- 页面识别模板（按优先级排列，先匹配到的优先）
-- 每个模板: {name, colorTbl, region, priority}
local pageTemplates = {}

--- 初始化页面识别器
-- @param colorTemplates 颜色模板表（从 templates/zhengtu_colors.lua 加载）
function M.init(colorTemplates)
    colorTemplates = colorTemplates or {}

    -- 注册默认页面模板
    pageTemplates = {
        -- 网络错误（最高优先级，避免在断网时误判）
        {
            name = M.PAGE_NETWORK_ERROR,
            priority = 100,
            colorTbl = colorTemplates.network_error or {},
            region = {left = 0, top = 0, right = 720, bottom = 1280},
        },

        -- 结算界面
        {
            name = M.PAGE_SETTLEMENT,
            priority = 90,
            colorTbl = colorTemplates.settlement or {},
            region = {left = 0, top = 0, right = 720, bottom = 1280},
        },

        -- 弹窗/广告
        {
            name = M.PAGE_POPUP,
            priority = 80,
            colorTbl = colorTemplates.popup or {},
            region = {left = 0, top = 0, right = 720, bottom = 1280},
        },

        -- 加载界面
        {
            name = M.PAGE_LOADING,
            priority = 70,
            colorTbl = colorTemplates.loading or {},
            region = {left = 0, top = 0, right = 720, bottom = 1280},
        },

        -- 背包
        {
            name = M.PAGE_INVENTORY,
            priority = 60,
            colorTbl = colorTemplates.inventory or {},
            region = {left = 0, top = 0, right = 720, bottom = 1280},
        },

        -- 装备
        {
            name = M.PAGE_EQUIPMENT,
            priority = 55,
            colorTbl = colorTemplates.equipment or {},
            region = {left = 0, top = 0, right = 720, bottom = 1280},
        },

        -- 任务
        {
            name = M.PAGE_TASK,
            priority = 50,
            colorTbl = colorTemplates.task or {},
            region = {left = 0, top = 0, right = 720, bottom = 1280},
        },

        -- 战斗（较高优先级，因为战斗是最常见的状态）
        {
            name = M.PAGE_BATTLE,
            priority = 40,
            colorTbl = colorTemplates.battle or {},
            region = {left = 0, top = 0, right = 720, bottom = 1280},
        },

        -- 主界面（最低优先级，兜底）
        {
            name = M.PAGE_MAIN_MENU,
            priority = 10,
            colorTbl = colorTemplates.main_menu or {},
            region = {left = 0, top = 0, right = 720, bottom = 1280},
        },
    }

    -- 按优先级排序
    table.sort(pageTemplates, function(a, b)
        return a.priority < b.priority
    end)

    logger.info("页面识别器初始化完成, 模板数: " .. #pageTemplates)
end

--- 注册新的页面模板
-- @param name 页面名称
-- @param colorTbl 颜色表
-- @param region 可选, 查找区域
-- @param priority 可选, 优先级（数字越小越优先）
function M.registerTemplate(name, colorTbl, region, priority)
    priority = priority or 50

    table.insert(pageTemplates, {
        name = name,
        colorTbl = colorTbl,
        region = region or {left = 0, top = 0, right = 720, bottom = 1280},
        priority = priority,
    })

    table.sort(pageTemplates, function(a, b)
        return a.priority < b.priority
    end)

    logger.info(string.format("注册页面模板: %s (priority=%d)", name, priority))
end

--- 识别当前页面
-- @return {page=页面名, matched=true/false, details=匹配详情}
function M.identifyPage()
    local result = {
        page = M.PAGE_UNKNOWN,
        matched = false,
        details = {},
    }

    for _, tmpl in ipairs(pageTemplates) do
        if not tmpl.colorTbl or #tmpl.colorTbl == 0 then
            continue
        end

        local foundName = CF.findTemplateToTable(tmpl.colorTbl, {}, tmpl.region)

        if foundName then
            result.page = tmpl.name
            result.matched = true
            result.details = {
                template = tmpl.name,
                found = foundName,
                region = tmpl.region,
            }
            logger.debug(string.format("页面识别: %s (模板: %s)", tmpl.name, foundName))
            return result
        end
    end

    logger.debug("页面识别: 未匹配到任何模板")
    return result
end

--- 等待指定页面出现
-- @param targetPage 目标页面名
-- @param timeout 超时(毫秒), 默认 10000
-- @param interval 轮询间隔(毫秒), 默认 500
-- @return {page=页面名, matched=true/false}
function M.waitForPage(targetPage, timeout, interval)
    timeout = timeout or 10000
    interval = interval or 500

    local startTime = os.clock()
    while (os.clock() - startTime) * 1000 < timeout do
        local result = M.identifyPage()

        if result.page == targetPage then
            logger.info(string.format("等待页面到达: %s", targetPage))
            return result
        end

        sleep(interval)
    end

    logger.warn(string.format("等待页面超时: 目标=%s, 当前=%s", targetPage, M.identifyPage().page))
    return M.identifyPage()
end

--- 获取当前页面摘要（用于日志）
function M.getPageSummary()
    local result = M.identifyPage()
    if result.matched then
        return string.format("页面[%s] (模板:%s)", result.page, result.details.found)
    end
    return "页面[未知]"
end

return M
