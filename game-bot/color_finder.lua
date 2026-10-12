-- ==================== 懒人精灵找色模块封装 ====================
-- 基于懒人精灵原生图色 API 封装，提供更简洁的使用方式
-- 用法: import("color_finder") 或直接 require "color_finder"

local M = {}

-- ==================== 配置区 ====================

M.defaultConfig = {
    -- 默认相似度阈值 (0~1)
    defaultSim = 0.9,
    -- 默认查找方向 (0:左上→右下 | 1:中心→四周 | 2:右下→左上 | 3:左下→右上 | 4:右上→左下)
    defaultDir = 0,
    -- 全屏查找时的缩放区域 (避免全屏扫描过慢)，nil 表示不限制
    scanRegionScale = 0.8,   -- 屏幕中间 80% 区域
}

-- ==================== 基础工具函数 ====================

--- 将 RGB 整数转为 BBGGRR 格式字符串（懒人精灵格式）
function M.rgbToHex(rgbValue)
    local r = rgbValue % 256
    local g = (rgbValue // 256) % 256
    local b = (rgbValue // 65536) % 256
    return string.format("%02X%02X%02X", b, g, r)
end

--- 将 BBGGRR 十六进制字符串解析为 RGB 整数
function M.hexToRgb(hexStr)
    hexStr = hexStr:gsub("#", ""):gsub("0x", "")
    if #hexStr == 6 then
        local b = tonumber(hexStr:sub(1, 2), 16)
        local g = tonumber(hexStr:sub(3, 4), 16)
        local r = tonumber(hexStr:sub(5, 6), 16)
        return bit.bor(r, bit.shift(g, 8), bit.shift(b, 16))
    end
    return nil
end

--- 颜色容差格式化：将容差值加入颜色串
-- 支持输入 "AABBCC" 或 "AABBCC-101010" 格式
function M.addTolerance(colorStr, tolerance)
    tolerance = tolerance or "000000"
    if not colorStr:find("-") then
        return colorStr .. "-" .. tolerance
    end
    return colorStr
end

--- 获取屏幕分辨率
function M.getScreenSize()
    local w, h = getDisplaySize()
    return w, h
end

--- 根据缩放比例计算扫描区域
-- 返回 {left, top, right, bottom}
function M.getScanRegion(w, h, scale)
    scale = scale or M.defaultConfig.scanRegionScale
    local marginW = (w * (1 - scale)) / 2
    local marginH = (h * (1 - scale)) / 2
    return {
        left = math.floor(marginW),
        top = math.floor(marginH),
        right = math.ceil(w - marginW),
        bottom = math.ceil(h - marginH)
    }
end

-- ==================== 单点找色 findColor 封装 ====================

--- 在指定区域找一个颜色
-- @param color       目标颜色, BBGGRR格式 如 "FF0000" 或 "FF0000-101010"
-- @param region      可选, 查找区域 {left, top, right, bottom}, 默认全屏
-- @param sim         可选, 相似度 0~1, 默认 0.9
-- @param dir         可选, 查找方向, 默认 0 (左上→右下)
-- @return            ret, x, y (没找到时 ret=-1, x=-1, y=-1)
function M.find(color, region, sim, dir)
    sim = sim or M.defaultConfig.defaultSim
    dir = dir or M.defaultConfig.defaultDir
    
    local w, h = M.getScreenSize()
    local r = region or M.getScanRegion(w, h)
    
    color = M.addTolerance(color, nil)
    
    local ret, x, y = findColor(r.left, r.top, r.right, r.bottom, color, dir, sim)
    return ret, x, y
end

--- 等待颜色出现（带超时）
-- @param color       目标颜色
-- @param timeout     超时时间(毫秒), 默认 5000
-- @param interval    轮询间隔(毫秒), 默认 200
-- @param region      可选, 查找区域
-- @return            x, y (没找到返回 nil, nil)
function M.waitForColor(color, timeout, interval, region)
    timeout = timeout or 5000
    interval = interval or 200
    
    local startTime = os.clock()
    while (os.clock() - startTime) * 1000 < timeout do
        local ret, x, y = M.find(color, region)
        if ret == 1 and x ~= -1 and y ~= -1 then
            return x, y
        end
        sleep(interval)
    end
    return nil, nil
end

-- ==================== 多点找色 findMultiColor 封装 ====================

--- 多点找色（首点+偏移点组合）
-- @param firstColor  首点颜色, 格式 "X|Y-容差|BBGGBB" 如 "0|0-101010|FF0000"
-- @param offsets     偏移颜色列表, 多个用 "|" 连接, 格式 "dx|dy-容差|BBGGRB"
--                   例: "10|11|FF0000-000000|23|57|00FF00-101010"
-- @param region      可选, 查找区域
-- @param sim         可选, 相似度
-- @param dir         可选, 查找方向
-- @return            x, y (没找到返回 -1, -1)
function M.findMulti(firstColor, offsets, region, sim, dir)
    sim = sim or M.defaultConfig.defaultSim
    dir = dir or M.defaultConfig.defaultDir
    
    local w, h = M.getScreenSize()
    local r = region or M.getScanRegion(w, h)
    
    -- firstColor 需要转换为懒人的格式
    local fcFormatted = firstColor
    if not firstColor:find("|") then
        -- 如果只传了颜色和容差, 需要补充首点偏移
        fcFormatted = "0|0-" .. (firstColor:match("^(.-)-") or "000000") .. "|" .. firstColor:match("|(.*)$") or firstColor
    end
    
    local ret, x, y = findMultiColor(r.left, r.top, r.right, r.bottom, fcFormatted, offsets, dir, sim)
    return ret, x, y
end

--- 使用表格形式传入的多点找色（更清晰的结构化参数）
-- @param tb          表: {region={left,top,right,bottom}, firstColor="...", offsets={"dx|dy-容差|颜色", ...}, sim=0.9, dir=0}
-- @return            x, y (没找到返回 -1, -1)
function M.findMultiTable(tb)
    local r = tb.region or M.getScanRegion(M.getScreenSize())
    local ret, x, y = findMultiColorT({
        r.left, r.top, r.right, r.bottom,
        tb.firstColor,
        table.concat(tb.offsets, ","),
        tb.dir or M.defaultConfig.defaultDir,
        tb.sim or M.defaultConfig.defaultSim
    })
    return ret, x, y
end

--- 等待多点颜色出现（带超时）
-- @param firstColor  首点颜色/偏移串
-- @param offsets     偏移颜色串
-- @param timeout     超时(毫秒), 默认 5000
-- @param interval    轮询间隔(毫秒), 默认 200
-- @return            x, y (没找到返回 nil, nil)
function M.waitForMultiColor(firstColor, offsets, timeout, interval, region)
    timeout = timeout or 5000
    interval = interval or 200
    
    local startTime = os.clock()
    while (os.clock() - startTime) * 1000 < timeout do
        local ret, x, y = M.findMulti(firstColor, offsets, region)
        if ret == 1 and x ~= -1 and y ~= -1 then
            return x, y
        end
        sleep(interval)
    end
    return nil, nil
end

-- ==================== 颜色数量统计 getColorNum ====================

--- 统计指定区域内匹配颜色的像素数量
-- @param color       目标颜色 BBGGRR 格式
-- @param region      查找区域 {left, top, right, bottom}
-- @param sim         相似度
-- @return            匹配的像素个数
function M.countColor(color, region, sim)
    local w, h = M.getScreenSize()
    local r = region or M.getScanRegion(w, h)
    
    return getColorNum(r.left, r.top, r.right, r.bottom, color, sim or M.defaultConfig.defaultSim)
end

--- 当某种颜色数量超过阈值时返回 true
-- @param color       目标颜色
-- @param threshold   最低像素数量
-- @param region      可选, 查找区域
-- @return            isEnough 是否达到数量要求
function M.hasEnoughColor(color, threshold, region, sim)
    local count = M.countColor(color, region, sim)
    return count >= threshold
end

-- ==================== 坐标取色与比对 cmpColor ====================

--- 获取某点的颜色并判断是否匹配
-- @param x           横坐标
-- @param y           纵坐标
-- @param color       目标颜色 BBGGRR 格式
-- @param sim         相似度
-- @return            1 匹配 / 0 不匹配
function M.matchAtPoint(x, y, color, sim)
    return cmpColor(x, y, color, sim or M.defaultConfig.defaultSim)
end

--- 获取某点实际颜色（原始值）
-- @param x           横坐标
-- @param y           纵坐标
-- @param type        可选, 1 返回整数, 否则返回 BBGGRR 字符串
-- @return            颜色值
function M.getColorAt(x, y, type)
    return getPixelColor(x, y, type)
end

-- ==================== 多点比色 cmpColorEx 封装 ====================

--- 区域多点比色（用于验证某个布局是否存在）
-- @param points      点描述字符串, 格式 "x|y|颜色|-容差|x2|y2|颜色2|-容差2"
--                    例如: "100|200|FF0000-101010|300|400|00FF00-000000"
-- @param sim         相似度
-- @return            1 完全匹配 / 0 未完全匹配
function M.compareMultiPoints(points, sim)
    return cmpColorEx(points, sim or M.defaultConfig.defaultSim)
end

-- ==================== 批量找色 findMultiColorAll 封装 ====================

--- 找出所有匹配位置
-- @param firstColor  首点颜色串
-- @param offsets     偏移颜色串
-- @param region      可选, 查找区域
-- @param sim         可选, 相似度
-- @param dir         可选, 查找方向
-- @return            table 数组 [{x=..., y=...}, ...], 失败返回 nil
function M.findAll(firstColor, offsets, region, sim, dir)
    sim = sim or M.defaultConfig.defaultSim
    dir = dir or M.defaultConfig.defaultDir
    
    local w, h = M.getScreenSize()
    local r = region or M.getScanRegion(w, h)
    
    local fcFormatted = firstColor
    if not firstColor:find("|") then
        fcFormatted = "0|0-" .. (firstColor:match("^(.-)-") or "000000") .. "|" .. firstColor:match("|(.*)$") or firstColor
    end
    
    return findMultiColorAll(r.left, r.top, r.right, r.bottom, fcFormatted, offsets, dir, sim)
end

-- ==================== 便捷查找模式（推荐常用场景） ====================

--- 通过首点 + 偏移量找色（最常用的模式）
-- 示例: M.locateByOffset({
--   firstColor = "FF0000",         -- 红色首点
--   offsets = {{"10", "10", "00FF00"}, {"30", "-5", "FFFFFF"}},
--   region = {left=0, top=0, right=800, bottom=1200}
-- })
-- 返回: {x, y} 或 nil
function M.locateByOffset(opts)
    opts = opts or {}
    
    -- 构建首点偏移串
    local fcStr = "0|0-" .. (opts.firstTolerance or "000000") .. "|" .. M.addTolerance(opts.firstColor or "", opts.firstTolerance)
    
    -- 构建偏移颜色串
    if opts.offsets and #opts.offsets > 0 then
        local offsetParts = {}
        for _, o in ipairs(opts.offsets) do
            local dx, dy, col, tol = o[1], o[2], o[3], o[4]
            table.insert(offsetParts, dx .. "|" .. dy .. "-" .. (tol or "000000") .. "|" .. col)
        end
        return M.findMulti(fcStr, table.concat(offsetParts, "|"), opts.region, opts.sim, opts.dir)
    end
    
    -- 无偏移, 退化为单点找色
    return M.find(opts.firstColor, opts.region, opts.sim, opts.dir)
end

-- ==================== 表找色模板封装 ====================
-- 提供结构化的表格方式定义多点找色模板
-- 用法:
--   local x, y = CF.findTemplate({
--       region = {left=0, top=0, right=720, bottom=1280},
--       points = {
--           {offset={0, 0},     color="FF0000", tolerance="101010"},
--           {offset={30, -5},   color="00FF00", tolerance="000000"},
--           {offset={60, 20},   color="FFFFFF", tolerance="202020"},
--       },
--       sim = 0.9,
--       dir = 0,
--   })

-- ==================== 懒人精灵原生模式封装 ====================
-- 支持 Default_t (颜色表) + Default_d (坐标表) 模式
-- 用法:
--   Default_t = {
--       ["创建角色"] = {
--           {961, 576, 0xccbb66},
--           {961, 595, 0x7e7e7},
--           ...
--       },
--   }
--   Default_d = {}
--   CF.findTemplateToTable(Default_t, Default_d)
--   _点击(Default_d.创建角色)

--- 将表结构的颜色点转换为 findMultiColor 所需的字符串格式
-- @param points   颜色点表 [{offset={x,y}, color="BBGGRR", tolerance="101010"}, ...]
-- @param firstIdx 首点在表中的索引（默认第1个）
-- @return         {firstColorStr, offsetColorStr}
local function buildMultiColorStrings(points, firstIdx)
    firstIdx = firstIdx or 1
    
    local firstColorStr = ""
    local offsetParts = {}
    
    for i, p in ipairs(points) do
        if i == 1 then
            -- 第一个点是首点: 偏移X|偏移Y-容差|颜色
            firstColorStr = (p.offset and p.offset[1] or 0) .. "|" .. 
                           (p.offset and p.offset[2] or 0) .. "-" .. 
                           (p.tolerance or "000000") .. "|" .. 
                           (p.color or "000000")
        else
            -- 后续点是偏移颜色: dx|dy-容差|颜色
            table.insert(offsetParts, 
                (p.offset[1]) .. "|" .. 
                (p.offset[2]) .. "-" .. 
                (p.tolerance or "000000") .. "|" .. 
                (p.color or "000000"))
        end
    end
    
    return firstColorStr, table.concat(offsetParts, "|")
end

--- 表找色 — 通过结构化表定义多个颜色点进行搜索
-- @param tb 表结构配置:
--     region      — 可选, 查找区域 {left,top,right,bottom}，默认屏幕缩放区域
--     points      — 必选, 颜色点列表 [{offset={dx,dy}, color="BBGGRR", tolerance="101010"}, ...]
--                   offset 是相对于首个点的偏移, color 为 BBGGRR 十六进制, tolerance 为容差
--     sim         — 可选, 相似度, 默认 0.9
--     dir         — 可选, 查找方向, 默认 0
-- @return          x, y (找不到返回 -1, -1)
function M.findTemplate(tb)
    if not tb or not tb.points or #tb.points == 0 then
        print("findTemplate: 需要至少提供一个颜色点")
        return -1, -1
    end
    
    local sim = tb.sim or M.defaultConfig.defaultSim
    local dir = tb.dir or M.defaultConfig.defaultDir
    
    local w, h = M.getScreenSize()
    local r = tb.region or M.getScanRegion(w, h)
    
    local fcStr, ocStr = buildMultiColorStrings(tb.points)
    
    local ret, x, y = findMultiColor(r.left, r.top, r.right, r.bottom, fcStr, ocStr, dir, sim)
    return ret, x, y
end

--- 表找色带超时等待
-- @param tb 与 findTemplate 相同的表结构
-- @param timeout     超时(毫秒), 默认 5000
-- @param interval    轮询间隔(毫秒), 默认 200
-- @return             x, y (失败返回 nil, nil)
function M.waitForTemplate(tb, timeout, interval)
    timeout = timeout or 5000
    interval = interval or 200
    
    local startTime = os.clock()
    while (os.clock() - startTime) * 1000 < timeout do
        local ret, x, y = M.findTemplate(tb)
        if ret == 1 and x ~= -1 and y ~= -1 then
            return x, y
        end
        sleep(interval)
    end
    return nil, nil
end

--- 表找色点击 — 找到多点模板后，点击指定的颜色点
-- 点击原理: 找到的是首个锚点的坐标，然后根据配置的偏移量计算出要点击的实际位置
-- @param tb 表结构配置，额外字段:
--     clickPointIdx   — 必选, 点击哪个点的索引（points 表中的序号，从1开始）
--     tapDelay        — 可选, 点击后的延迟(毫秒)，默认 100
-- @return             成功返回 true，失败返回 false
function M.templateClick(tb)
    if not tb then
        print("templateClick: 参数不能为空")
        return false
    end
    
    -- 必须有点击目标索引
    local clickIdx = tb.clickPointIdx or 1
    if clickIdx < 1 or clickIdx > (#tb.points or 0) then
        print(string.format("templateClick: clickPointIdx=%d 超出范围 [1, %d]", 
                          clickIdx, #tb.points))
        return false
    end
    
    -- 先执行表找色
    local ret, baseX, baseY = M.findTemplate(tb)
    if ret ~= 1 or baseX == -1 or baseY == -1 then
        return false
    end
    
    -- 找到首点坐标后，计算点击目标的实际屏幕坐标
    -- 点击目标 = 首点(baseX, baseY) + 该点相对于首点的偏移
    local clickPt = tb.points[clickIdx]
    local offsetX = (clickPt.offset and clickPt.offset[1]) or 0
    local offsetY = (clickPt.offset and clickPt.offset[2]) or 0
    
    local clickX = baseX + offsetX
    local clickY = baseY + offsetY
    
    -- 执行点击
    tap(clickX, clickY)
    
    -- 点击后延迟
    local delay = tb.tapDelay or 100
    sleep(delay)
    
    return true
end

--- 表找色点击带多次重试
-- @param tb          与 templateClick 相同的表结构
-- @param retry       最大重试次数, 默认 3
-- @param retryDelay  每次重试间隔(毫秒), 默认 500
-- @return             成功返回 clicked point index, 失败返回 0
function M.templateClickRetry(tb, retry, retryDelay)
    retry = retry or 3
    retryDelay = retryDelay or 500
    
    for i = 1, retry do
        if M.templateClick(tb) then
            return tb.clickPointIdx
        end
        if i < retry then
            sleep(retryDelay)
        end
    end
    return 0
end

--- 表找色点击 — 多模板轮流尝试（按优先级顺序）
-- 适用于一个界面可能有多种布局的情况
-- @param templates  模板表数组 [{...}, {...}, ...]
-- @param timeout    每个模板的超时时间(毫秒), 默认 3000
-- @param interval   轮询间隔(毫秒), 默认 200
-- @return            成功返回 {templateIndex, pointIndex, x, y}, 失败返回 nil
function M.multiTemplateClick(templates, timeout, interval)
    timeout = timeout or 3000
    interval = interval or 200
    
    for tIdx, tb in ipairs(templates) do
        local startTime = os.clock()
        while (os.clock() - startTime) * 1000 < timeout do
            local ret, baseX, baseY = M.findTemplate(tb)
            if ret == 1 and baseX ~= -1 and baseY ~= -1 then
                -- 找到当前模板，计算点击坐标
                local clickIdx = tb.clickPointIdx or 1
                local clickPt = tb.points[clickIdx]
                local cx = baseX + ((clickPt.offset or {0, 0})[1])
                local cy = baseY + ((clickPt.offset or {0, 0})[2])
                tap(cx, cy)
                sleep(tb.tapDelay or 100)
                return {tIdx, clickIdx, cx, cy}
            end
            sleep(interval)
        end
    end
    return nil
end

-- ==================== 懒人精灵原生模式封装 ====================
-- 支持 Default_t (颜色表) + Default_d (坐标表) 模式
-- 颜色表格式: {名称} = {{x, y, 0x颜色值}, {dx, dy, 0x颜色值}, ...}
-- 坐标表格式: {名称} = {x, y}  (找色后填充)

--- 将懒人精灵颜色表转换为内部格式
-- @param tbl  颜色表 {名称 = {{x,y,color}, ...}, ...}
-- @return     {名称 = {{offset={dx,dy}, color="BBGGRR", tolerance="101010"}, ...}, ...}
local function convertTouchSpriteTable(tbl)
    local result = {}
    
    for name, points in pairs(tbl) do
        local converted = {}
        
        for i, pt in ipairs(points) do
            -- 第一个点是锚点 (x, y, color)
            -- 后续点是偏移 (dx, dy, color)
            local offsetX, offsetY, colorVal
            
            if i == 1 then
                offsetX = pt[1]
                offsetY = pt[2]
                colorVal = pt[3]
            else
                offsetX = pt[1]
                offsetY = pt[2]
                colorVal = pt[3]
            end
            
            -- 颜色值转 BBGGRR 格式
            local colorHex = ""
            if type(colorVal) == "number" then
                colorHex = string.format("%06X", colorVal)
            elseif type(colorVal) == "string" then
                colorHex = colorVal:gsub("^0x", ""):upper()
            end
            
            table.insert(converted, {
                offset = {offsetX, offsetY},
                color = colorHex,
                tolerance = "000000"
            })
        end
        
        result[name] = converted
    end
    
    return result
end

--- 将找色结果写入坐标表
-- @param resultTable  坐标表，找色后填充 {名称} = {x, y}
-- @param foundName    找到的模板名称
-- @param x, y         找到的坐标
function M.writeResultToTable(resultTable, foundName, x, y)
    if resultTable and foundName and x ~= -1 and y ~= -1 then
        resultTable[foundName] = {x, y}
    end
end

--- 表找色到表 (懒人精灵原生模式)
-- 从颜色表找色，结果写入坐标表
-- @param colorTbl     颜色表 {名称 = {{x,y,color}, {dx,dy,color}, ...}, ...}
-- @param resultTbl    坐标表 {名称 = {x, y}}，找色后填充
-- @param region       可选, 查找区域
-- @param sim          可选, 相似度
-- @param dir          可选, 查找方向
-- @return             找到的名称，失败返回 nil
function M.findTemplateToTable(colorTbl, resultTbl, region, sim, dir)
    sim = sim or M.defaultConfig.defaultSim
    dir = dir or M.defaultConfig.defaultDir
    
    -- 转换颜色表格式
    local convertedTbl = convertTouchSpriteTable(colorTbl)
    
    -- 依次尝试每个模板
    for name, points in pairs(convertedTbl) do
        local ret, x, y = M.findTemplate({
            region = region,
            points = points,
            sim = sim,
            dir = dir,
        })
        
        if ret == 1 and x ~= -1 and y ~= -1 then
            -- 找到，写入坐标表
            resultTbl[name] = {x, y}
            return name
        end
    end
    
    return nil
end

--- 等待表找色到表 (懒人精灵原生模式)
-- @param colorTbl     颜色表
-- @param resultTbl    坐标表
-- @param timeout      超时(毫秒), 默认 5000
-- @param interval     轮询间隔(毫秒), 默认 200
-- @return             找到的名称，失败返回 nil
function M.waitForTemplateToTable(colorTbl, resultTbl, timeout, interval)
    timeout = timeout or 5000
    interval = interval or 200
    
    local startTime = os.clock()
    while (os.clock() - startTime) * 1000 < timeout do
        local foundName = M.findTemplateToTable(colorTbl, resultTbl)
        if foundName then
            return foundName
        end
        sleep(interval)
    end
    
    return nil
end

--- 通过坐标表点击 (懒人精灵原生模式)
-- 直接调用 _点击(Default_d.创建角色) 的方式
-- @param coordTbl     坐标表 {名称 = {x, y}}
-- @param pointName    要点击的名称
-- @param delay        点击后延迟(毫秒), 默认 100
-- @return             成功返回 true，失败返回 false
function M.clickByCoordTable(coordTbl, pointName, delay)
    delay = delay or 100
    
    if not coordTbl or not coordTbl[pointName] then
        print(string.format("clickByCoordTable: 坐标表 %s 中无 %s 记录", 
                          type(coordTbl) == "table" and "存在" or "nil", pointName))
        return false
    end
    
    local x, y = coordTbl[pointName][1], coordTbl[pointName][2]
    tap(x, y)
    sleep(delay)
    
    return true
end

--- 表找色 + 自动点击 (懒人精灵原生模式)
-- 一步完成: 找色 → 写入坐标表 → 点击指定点
-- @param colorTbl     颜色表
-- @param resultTbl    坐标表
-- @param clickName    要点击的名称
-- @param delay        点击后延迟(毫秒), 默认 100
-- @return             成功返回 true，失败返回 false
function M.findAndClickByTable(colorTbl, resultTbl, clickName, delay)
    delay = delay or 100
    
    -- 先找色
    local foundName = M.findTemplateToTable(colorTbl, resultTbl)
    if not foundName then
        return false
    end
    
    -- 再点击
    return M.clickByCoordTable(resultTbl, clickName, delay)
end

-- ==================== 初始化/导出 ====================

return M
