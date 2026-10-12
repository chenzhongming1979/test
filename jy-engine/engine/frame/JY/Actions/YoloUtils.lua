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

--[[ ================== YoloUtils - YoloV5 目标检测 ==================
为框架提供 ff="yolo*" 系列指令的查找实现（由 ActionsUtils 的
FIND_POINT_KEYS/FIND_ALL_KEYS 登记，经 findClick 标准管线调用）。

模型约定（首次调用自动完成）：
  1. 脚本 assets 内打包 yolo.rc（内含 result.txt + yolov5s.param + yolov5s.bin）
  2. 运行时 extractAssets 释放到 工作目录/yolo/ 下
  3. YoloV5.init(result.txt, yolov5s.param, yolov5s.bin) 懒加载，进程内只初始化一次

特征参数（v2）：
  p      (string, 必填) 标签名，支持逗号分隔多标签，如 "怪物,BOSS"
  degree (number, 可选) 置信度阈值，默认 0.5
  r      (table,  可选) 检测区域 {x1,y1,x2,y2}（已换算的真实坐标），缺省全屏
  gpu    (boolean,可选) 是否使用 GPU 推理，默认 false

返回坐标：检测框中心点 Point(x,y)，为真实屏幕坐标（同节点 bounds，无需锚点换算）
--]]

local JY = require("JY.Utils.JY")

local yolo = {}

-- 模型文件约定
local MODEL_DIR = "yolo"
local LABEL_FILE = "result.txt"
local PARAM_FILE = "yolov5s.param"
local BIN_FILE = "yolov5s.bin"
local DEFAULT_DEGREE = 0.5

-- 模块状态（进程内缓存，模型只初始化一次）
local state = {
    inited = false,      -- 模型是否已就绪
    failed = false,      -- 初始化失败后不再重试，避免每轮循环反复加载
    labels = nil,        -- result.txt 标签表（类别索引 -> 标签名，用于结果只回传索引时映射）
    rawLogged = false,   -- 原始检测结果只打印一次（用于真机确认 JSON 字段）
}

-- 确保 frameSdk 命令表存在 YoloV5（新版自带，旧版尝试 import）
local function ensureYoloGlobal()
    if frameSdk["YoloV5"] ~= nil then return true end
    if type(frameSdk.import) == "function" then
        pcall(frameSdk.import, "com.nx.assist.lua.YoloV5")
    end
    return frameSdk["YoloV5"] ~= nil
end

-- 读取标签文件（类别索引映射用，读取失败不影响主流程）
local function loadLabels(labelPath)
    local ok, list = pcall(function()
        local out = {}
        for line in io.lines(labelPath) do
            local name = string.match(line, "%s*(.-)%s*$")
            if name ~= nil and name ~= "" then
                table.insert(out, name)
            end
        end
        return out
    end)
    if ok and #list > 0 then return list end
    return nil
end

-- 初始化模型（幂等；失败返回 false 并写日志）
function yolo.init(v2)
    if state.inited then return true end
    if state.failed then return false end

    local ok, err = pcall(function()
        if not ensureYoloGlobal() then
            error("当前懒人精灵环境不支持 YoloV5", 0)
        end
        local work = frameSdk.getWorkPath() .. "/" .. MODEL_DIR
        local paramPath = work .. "/" .. PARAM_FILE
        if not frameSdk.fileExist(paramPath) then
            frameSdk.mkdir(work)
            frameSdk.extractAssets(MODEL_DIR .. ".rc", work, "*.*")
        end
        local labelPath = work .. "/" .. LABEL_FILE
        state.labels = loadLabels(labelPath)
        local ret = frameSdk.YoloV5.init(labelPath, paramPath, work .. "/" .. BIN_FILE)
        if ret == nil or ret == false then
            error("YoloV5.init 失败，检查 " .. work .. " 下模型文件是否存在", 0)
        end
    end)

    if not ok then
        state.failed = true
        JY.jy_sys.acLog("yolo模型初始化失败：" .. tostring(err), v2)
        return false
    end
    state.inited = true
    JY.jy_sys.acLog("yolo模型初始化成功", v2)
    return true
end

-- 匹配标签：v2["p"] 支持逗号分隔多标签
local function matchLabel(v2, label)
    local targets = v2["p"]
    if type(targets) ~= "string" or targets == "" or label == nil then
        return false
    end
    label = tostring(label)
    for token in (targets .. ","):gmatch("([^,]*)") do
        if token ~= "" and token == label then
            return true
        end
    end
    return false
end

-- 解析单条检测结果为 {label, score, x1, y1, x2, y2}（真实屏幕坐标）
-- 检测框字段名官方文档未定义，按常见命名顺序兜底，字段结构以首次运行日志为准
local function parseItem(item, offsetX, offsetY, v2)
    if type(item) ~= "table" then return nil end

    -- 标签：字符串直取；纯数字按类别索引映射 result.txt（索引从0开始）
    local label = item.label or item.name or item.tag or item.class
    if type(label) == "number" and state.labels ~= nil then
        label = state.labels[label + 1]
    end

    -- 置信度
    local score = tonumber(item.score or item.conf or item.confidence or item.prob)
    if score == nil then return nil end

    -- 检测框：优先命名键 x/y/w/h，其次 x1/y1/x2/y2，再次 left/top/right/bottom，最后 box 子表
    local x1, y1, x2, y2
    if item.w ~= nil or item.width ~= nil then
        local w = tonumber(item.w or item.width) or 0
        local h = tonumber(item.h or item.height) or 0
        x1 = tonumber(item.x) or 0
        y1 = tonumber(item.y) or 0
        x2, y2 = x1 + w, y1 + h
    elseif item.x2 ~= nil then
        x1, y1 = tonumber(item.x) or 0, tonumber(item.y) or 0
        x2, y2 = tonumber(item.x2), tonumber(item.y2)
    elseif item.right ~= nil then
        x1, y1 = tonumber(item.left) or 0, tonumber(item.top) or 0
        x2, y2 = tonumber(item.right), tonumber(item.bottom)
    elseif type(item.box) == "table" and #item.box >= 4 then
        local b1, b2, b3, b4 = tonumber(item.box[1]), tonumber(item.box[2]), tonumber(item.box[3]), tonumber(item.box[4])
        if b1 ~= nil and b2 ~= nil and b3 ~= nil and b4 ~= nil then
            -- box 子表按 {x1,y1,x2,y2} 解释（若为 {x,y,w,h} 结构请依据首检日志调整此处）
            x1, y1, x2, y2 = b1, b2, b3, b4
        end
    else
        x1 = tonumber(item.x)
        y1 = tonumber(item.y)
        if x1 == nil or y1 == nil then return nil end
        x2, y2 = tonumber(item.w), tonumber(item.h)
        if x2 ~= nil and y2 ~= nil then
            x2, y2 = x1 + x2, y1 + y2
        else
            return nil
        end
    end
    if x1 == nil or y1 == nil or x2 == nil or y2 == nil then return nil end

    return {
        label = label,
        score = score,
        x1 = x1 + offsetX,
        y1 = y1 + offsetY,
        x2 = x2 + offsetX,
        y2 = y2 + offsetY,
    }
end

-- 执行一次检测并返回标准结果列表
-- 返回: table | nil（模型/截图失败），元素 {label, score, x1, y1, x2, y2}
function yolo.detectList(v2)
    if not yolo.init(v2) then return nil end

    -- 检测区域：v2["r"] 为框架换算后的真实坐标，缺省全屏
    local l, t, rt, b = 0, 0, 0, 0
    local hasRegion = false
    if type(v2["r"]) == "table" and #v2["r"] >= 4 then
        l = tonumber(v2["r"][1]) or 0
        t = tonumber(v2["r"][2]) or 0
        rt = tonumber(v2["r"][3]) or 0
        b = tonumber(v2["r"][4]) or 0
        hasRegion = true
    end

    -- 截图（区域截图时检测结果为框内坐标，需偏移回屏幕坐标）
    local okBmp, bmp = pcall(frameSdk.LuaEngine.snapShot, l, t, rt, b)
    if not okBmp or bmp == nil then
        JY.jy_sys.acLog("yolo截图失败", v2)
        return nil
    end

    local okDe, ret = pcall(frameSdk.YoloV5.detect, bmp, v2["gpu"] == true)
    -- Bitmap 必须释放，避免内存泄漏
    pcall(function()
        if bmp.isRecycled() == false then bmp.recycle() end
    end)
    if not okDe then
        JY.jy_sys.acLog("yolo检测失败：" .. tostring(ret), v2)
        return nil
    end

    local items = {}
    if ret == nil or ret == "" then return items end

    local okJs, arr = pcall(frameSdk.jsonLib.decode, ret)
    if not okJs or type(arr) ~= "table" then
        if not state.rawLogged then
            state.rawLogged = true
            JY.jy_sys.acLog("yolo结果JSON解析失败，原始返回：" .. tostring(ret), v2)
        end
        return items
    end

    -- 首次成功解析时打印原始结果，供真机确认字段结构
    if not state.rawLogged then
        state.rawLogged = true
        JY.jy_sys.acLog("yolo首检原始结果：" .. tostring(ret), v2)
    end

    local offsetX = hasRegion and l or 0
    local offsetY = hasRegion and t or 0
    for _, item in ipairs(arr) do
        local parsed = parseItem(item, offsetX, offsetY, v2)
        if parsed ~= nil then
            table.insert(items, parsed)
        end
    end
    return items
end

-- 单点查找：返回置信度最高且满足标签/阈值的目标中心点，未命中返回 Point(-1,-1)
function yolo.find(v2)
    local items = yolo.detectList(v2)
    if items == nil then return Point(-1, -1) end

    local degree = tonumber(v2["degree"]) or DEFAULT_DEGREE
    local best, bestScore
    for _, it in ipairs(items) do
        if it.score >= degree and matchLabel(v2, it.label) then
            if bestScore == nil or it.score > bestScore then
                best, bestScore = it, it.score
            end
        end
    end
    if best == nil then
        return Point(-1, -1)
    end

    local cx = math.floor((best.x1 + best.x2) / 2 + 0.5)
    local cy = math.floor((best.y1 + best.y2) / 2 + 0.5)
    JY.jy_sys.acLog("yolo命中[" .. tostring(best.label) .. "] " .. best.score .. " 坐标：" .. cx .. "," .. cy, v2)
    return Point(cx, cy)
end

-- 全找：返回所有满足标签/阈值的目标中心点列表（按置信度降序）
function yolo.findAll(v2)
    local items = yolo.detectList(v2)
    local points = {}
    if items == nil then return points end

    local degree = tonumber(v2["degree"]) or DEFAULT_DEGREE
    local matched = {}
    for _, it in ipairs(items) do
        if it.score >= degree and matchLabel(v2, it.label) then
            table.insert(matched, it)
        end
    end
    table.sort(matched, function(a, b) return a.score > b.score end)

    for _, it in ipairs(matched) do
        local cx = math.floor((it.x1 + it.x2) / 2 + 0.5)
        local cy = math.floor((it.y1 + it.y2) / 2 + 0.5)
        table.insert(points, Point(cx, cy))
    end
    return points
end

return yolo
