-- ==================== 找色模块使用示例 ====================

-- 导入模块
local CF = import("color_finder")

print("==== 懒人精灵找色模块 使用示例 ====")
print()

-- ==========================================
-- 1. 基础单点找色
-- ==========================================
print("--- 1. 单点找色 ---")

-- 在屏幕中间区域找红色 (FF0000, BBGGRR格式即 "0000FF"，加容差 "-101010")
local ret, x, y = CF.find("0000FF-101010")
if ret == 1 and x ~= -1 then
    print(string.format("找到颜色! x=%d, y=%d", x, y))
else
    print("未找到该颜色")
end
print()

-- 自定义查找区域（只找左上角 500x500 区域）
local region = { left = 0, top = 0, right = 500, bottom = 500 }
ret, x, y = CF.find("0000FF-101010", region)
print(string.format("指定区域查找: x=%s, y=%s", tostring(x), tostring(y)))
print()

-- ==========================================
-- 2. 等待颜色出现（带超时）
-- ==========================================
print("--- 2. 等待颜色出现 ---")

local wx, wy = CF.waitForColor("00FF00-101010", 5000, 200)
if wx then
    print(string.format("颜色出现在: (%d, %d)", wx, wy))
else
    print("超时未找到颜色")
end
print()

-- ==========================================
-- 3. 多点找色（首点 + 偏移点）
-- ==========================================
print("--- 3. 多点找色 ---")

-- 首点在 (0, 0) 偏移处是红色，(10, 10) 偏移处是绿色，(30, -5) 偏移处是白色
local mret, mx, my = CF.findMulti("0|0-101010|0000FF-101010", 
                                    "10|10-000000|00FF00|30|-5-101010|FFFFFF")
if mret == 1 and mx ~= -1 then
    print(string.format("多点匹配成功! x=%d, y=%d", mx, my))
end
print()

-- ==========================================
-- 4. 结构化参数多点找色（推荐）
-- ==========================================
print("--- 4. 结构化参数多点找色 ---")

local pos = CF.findMultiTable({
    firstColor = "0|0-101010|0000FF",       -- 首点: 坐标偏移 0|0, 容差 -101010, 目标颜色 0000FF
    offsets = {                              -- 偏移颜色点列表
        "10|10-000000|00FF00",              -- 偏移 (10,10), 颜色 00FF00, 无容差
        "30|-5-101010|FFFFFF",              -- 偏移 (30,-5), 颜色 FFFFFF, 容差 -101010
    },
    sim = 0.9,                              -- 相似度
    dir = 0,                                -- 查找方向
})

if pos then
    print(string.format("结构化多点找色: (%d, %d)", pos.x, pos.y))
else
    print("未匹配到")
end
print()

-- ==========================================
-- 5. 通过偏移量模式查找（最简洁的写法）
-- ==========================================
print("--- 5. locateByOffset — 便捷偏移查找 ---")

local loc = CF.locateByOffset({
    firstColor = "0000FF-101010",           -- 首点颜色: 蓝色+容差
    -- 注意: firstTolerance 不需要额外传, 已融入 firstColor
    
    offsets = {                             -- 偏移特征点
        {"10", "10", "00FF00-000000"},      -- 偏移 (10, 10), 绿色
        {"30", "-5", "FFFFFF-101010"},      -- 偏移 (30, -5), 白色
    },
    
    -- region = nil,                          -- 默认使用屏幕缩放区域
    sim = 0.85,                             -- 降低阈值提高灵敏度
})

if loc then
    print(string.format("便捷偏移定位: x=%d, y=%d", loc.x, loc.y))
end
print()

-- ==========================================
-- 6. 颜色数量统计
-- ==========================================
print("--- 6. 统计颜色数量 ---")

-- 统计红色像素数量
local count = CF.countColor("FF0000-101010", nil, 0.9)
print(string.format("区域内红色像素数: %d", count))

-- 判断红色像素是否足够多（比如血条满了的情况）
if CF.hasEnoughColor("FF0000-101010", 1000, nil, 0.8) then
    print("红色面积充足!")
else
    print("红色面积不足")
end
print()

-- ==========================================
-- 7. 坐标取色与比对
-- ==========================================
print("--- 7. 坐标取色 ---")

-- 获取某点的颜色
local pixelColor = CF.getColorAt(100, 100)
print(string.format("点 (100,100) 的颜色: %s", pixelColor))

-- 判断某点颜色是否匹配
local isMatch = CF.matchAtPoint(100, 100, "FF0000-101010", 0.9)
print(string.format("点 (100,100) 是否为红色? %s", isMatch == 1 and "是" or "否"))
print()

-- ==========================================
-- 8. 批量找色（找所有出现的位置）
-- ==========================================
print("--- 8. 批量找色 ---")

local allPos = CF.findAll("FF0000-101010", "", nil, 0.9)
if allPos and #allPos > 0 then
    print(string.format("共找到 %d 个红色位置:", #allPos))
    for i, p in ipairs(allPos) do
        print(string.format("  [%d] (%d, %d)", i, p.x, p.y))
    end
else
    print("未找到多个匹配项")
end
print()

-- ==========================================
-- 9. 工具函数演示
-- ==========================================
print("--- 9. RGB 互转 ---")

-- RGB整数 → BBGGRR字符串
local hex = CF.rgbToHex(0xFF00AA)  -- R=255, G=0, B=170
print(string.format("RGB(255,0,170) → HEX: %s", hex))

-- BBGGRR字符串 → RGB整数
local rgbVal = CF.hexToRgb(hex)
print(string.format("HEX '%s' → RGB整数: %d", hex, rgbVal))
print()

-- ==========================================
-- 结束
-- ==========================================
print("=== 示例执行完毕 ===")
