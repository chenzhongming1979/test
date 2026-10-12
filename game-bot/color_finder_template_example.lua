-- ==================== 表找色 + 点击使用示例 ====================

local CF = import("color_finder")

print("==== 表找色 & 点击示例 ====")
print()

-- ==========================================
-- 1. 表找色 findTemplate — 按表格配置多点匹配
-- ==========================================
print("--- 1. 表找色 ---")

-- 定义一个模板: 第一个点是锚点, 后面是偏移特征点
local x, y = CF.findTemplate({
    region = {left = 0, top = 0, right = 720, bottom = 1280},
    
    points = {
        -- 第1个点: 锚点(基准坐标), offset={0,0} 表示就是找到的实际位置
        {offset = {0,   0},    color = "FF0000", tolerance = "101010"},
        
        -- 第2个点: 在锚点右方30px、下方5px处
        {offset = {30, -5},     color = "00FF00", tolerance = "000000"},
        
        -- 第3个点: 在锚点右方60px、上方20px处
        {offset = {-60, 20},    color = "FFFFFF", tolerance = "202020"},
    },
    
    sim = 0.9,    -- 相似度
    dir = 0,      -- 查找方向
})

if x ~= -1 and y ~= -1 then
    print(string.format("表找色成功! 锚点坐标: (%d, %d)", x, y))
else
    print("未找到该模板")
end
print()

-- ==========================================
-- 2. 表找色带超时 waitForTemplate
-- ==========================================
print("--- 2. 表找色等待 ---")

local wx, wy = CF.waitForTemplate({
    region = {left = 0, top = 0, right = 500, bottom = 800},
    points = {
        {offset = {0, 0},    color = "0000FF-101010|1F0F0F"},
        {offset = {15, 20},   color = "FFFF00",         tolerance = "000000"},
    },
    sim = 0.85,
}, 5000, 200)  -- 超时5秒, 每200ms轮询一次

if wx then
    print(string.format("等待找到模板: (%d, %d)", wx, wy))
else
    print("等待超时")
end
print()

-- ==========================================
-- 3. 表找色点击 templateClick
-- ==========================================
print("--- 3. 表找色点击 ---")

-- 找到模板后，点击第2个点（即锚点偏移 {30, -5} 的位置）
local success = CF.templateClick({
    region = {left = 0, top = 0, right = 720, bottom = 1280},
    
    -- 定义模板特征点
    points = {
        {offset = {0, 0},    color = "FF0000", tolerance = "101010"},  -- 锚点
        {offset = {30, -5},   color = "00FF00", tolerance = "000000"},  -- 特征点1
    },
    
    clickPointIdx = 2,          -- 点击第2个颜色点
    tapDelay = 200,             -- 点击后延迟 200ms
    sim = 0.9,
})

if success then
    print("点击成功!")
else
    print("未找到模板，点击失败")
end
print()

-- ==========================================
-- 4. 表找色点击重试 templateClickRetry
-- ==========================================
print("--- 4. 点击重试 ---")

-- 最多尝试 3 次，每次间隔 500ms
local clickedIdx = CF.templateClickRetry({
    region = {left = 0, top = 0, right = 720, bottom = 1280},
    points = {
        {offset = {0, 0},    color = "FF0000", tolerance = "101010"},
        {offset = {50, 30},   color = "FFFFFF", tolerance = "101010"},
    },
    clickPointIdx = 2,
    tapDelay = 150,
    sim = 0.85,
}, 3, 500)

if clickedIdx > 0 then
    print(string.format("重试%d次后成功点击到第%d个点", clickedIdx == 1 and "1" or "...", clickedIdx))
else
    print("3次重试均失败")
end
print()

-- ==========================================
-- 5. 多模板轮流点击 multiTemplateClick
-- ==========================================
print("--- 5. 多模板轮流点击 ---")

-- 当一个界面可能有多种布局时，依次尝试多个模板
local result = CF.multiTemplateClick({
    -- 模板1: 红色锚点 + 绿色特征点
    {
        region = {left = 0, top = 0, right = 720, bottom = 1280},
        points = {
            {offset = {0, 0},     color = "FF0000", tolerance = "101010"},
            {offset = {30, -5},    color = "00FF00", tolerance = "000000"},
        },
        clickPointIdx = 2,
        tapDelay = 100,
        sim = 0.9,
    },
    
    -- 模板2: 蓝色锚点 + 白色特征点
    {
        region = {left = 0, top = 0, right = 720, bottom = 1280},
        points = {
            {offset = {0, 0},     color = "0000FF", tolerance = "101010"},
            {offset = {50, 40},    color = "FFFFFF", tolerance = "202020"},
        },
        clickPointIdx = 2,
        tapDelay = 100,
        sim = 0.9,
    },
    
    -- 模板3: 黄色锚点 + 紫色特征点
    {
        region = {left = 0, top = 0, right = 720, bottom = 1280},
        points = {
            {offset = {0, 0},     color = "FFFF00", tolerance = "101010"},
            {offset = {20, 60},    color = "FF00FF", tolerance = "101010"},
        },
        clickPointIdx = 2,
        tapDelay = 100,
        sim = 0.9,
    },
}, 3000, 200)  -- 每个模板超时3秒

if result then
    print(string.format("模板%d的第%d个点被点击: (%d, %d)", 
                        result[1], result[2], result[3], result[4]))
else
    print("所有模板均未匹配")
end
print()

-- ==========================================
-- 6. 实战场景示例: 战斗界面自动点击攻击按钮
-- ==========================================
print("--- 6. 实战: 战斗界面攻击按钮 ---")

-- 思路: 用血条颜色作为锚点, 以攻击按钮的颜色作为偏移特征点
CF.templateClick({
    region = {left = 0, top = 0, right = 720, bottom = 1280},
    
    points = {
        -- 锚点: 左上角有特定颜色的区域 (比如血条起始位置)
        {offset = {0, 0},       color = "FF0000", tolerance = "202020"},
        -- 特征点: 攻击按钮的相对位置 (相对于锚点的偏移)
        {offset = {500, 800},    color = "00AAFF", tolerance = "101010"},
    },
    
    clickPointIdx = 2,          -- 点击攻击按钮
    tapDelay = 300,             -- 攻击间隔 300ms
    sim = 0.85,
})
print("已执行攻击按钮点击逻辑")
print()

-- ==========================================
-- 结束
-- ==========================================
print("=== 示例执行完毕 ===")
