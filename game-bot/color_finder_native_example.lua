-- ==================== 懒人精灵原生模式示例 ====================
-- 参考图片中的代码结构: Default_t (颜色表) + Default_d (坐标表)

local CF = import("color_finder")

print("==== 懒人精灵原生模式示例 ====")
print()

-- ==========================================
-- 1. 定义颜色表 (Default_t)
-- ==========================================
print("--- 1. 定义颜色表 ---")

-- 颜色表格式: {名称} = {{x, y, 0x颜色值}, {dx, dy, 0x颜色值}, ...}
-- 第一个点是锚点 (绝对坐标)，后续点是偏移 (相对坐标)
Default_t = {
    ["创建角色"] = {
        {961, 576, 0xccbb66},    -- 锚点: 坐标 (961, 576), 颜色 0xccbb66
        {961, 595, 0x7e7e7},     -- 偏移: (961-961, 595-576) = (0, 19), 颜色 0x7e7e7
        {961, 598, 0x838383},    -- 偏移: (0, 22), 颜色 0x838383
        {961, 599, 0x382314},    -- 偏移: (0, 23), 颜色 0x382314
        {961, 602, 0xdbdbdb},    -- 偏移: (0, 26), 颜色 0xdbdbdb
    },
    
    ["更新"] = {
        {446, 375, 0xd2a970},
        {446, 376, 0x350b00},
        {446, 379, 0xdcb765},
        {445, 388, 0x7b967},
        {446, 391, 0x3b666},
    },
    
    ["选择武器"] = {
        {503, 66, 0xffd678},
        {506, 72, 0xffd678},
        {517, 45, 0xa7712a},
        {517, 66, 0xffd678},
        {517, 78, 0x391306},
    },
}

print("颜色表定义完成:")
for name, points in pairs(Default_t) do
    print(string.format("  [%s]: %d 个点", name, #points))
end
print()

-- ==========================================
-- 2. 定义坐标表 (Default_d) - 初始为空
-- ==========================================
print("--- 2. 定义坐标表 ---")

Default_d = {}
print("坐标表初始为空:", Default_d)
print()

-- ==========================================
-- 3. 表找色到表 (找色并填充坐标表)
-- ==========================================
print("--- 3. 表找色到表 ---")

-- 调用 findTemplateToTable，自动填充 Default_d
local foundName = CF.findTemplateToTable(Default_t, Default_d)

if foundName then
    print(string.format("找到模板: %s", foundName))
    print("坐标表内容:")
    for name, pos in pairs(Default_d) do
        print(string.format("  [%s] = (%d, %d)", name, pos[1], pos[2]))
    end
else
    print("未找到任何模板")
end
print()

-- ==========================================
-- 4. 通过坐标表点击 (懒人精灵原生调用方式)
-- ==========================================
print("--- 4. 通过坐标表点击 ---")

-- 像图片中那样直接调用: _点击(Default_d.创建角色)
-- 我们用 clickByCoordTable 实现相同效果

-- 先找色
CF.findTemplateToTable(Default_t, Default_d)

-- 然后点击
local success = CF.clickByCoordTable(Default_d, "创建角色", 200)

if success then
    print("点击成功: 创建角色")
else
    print("点击失败 (可能未找到该模板)")
end
print()

-- ==========================================
-- 5. 一步完成: 找色 + 点击
-- ==========================================
print("--- 5. 一步完成: 找色 + 点击 ---")

-- 使用 findAndClickByTable 一步到位
local ok = CF.findAndClickByTable(Default_t, Default_d, "更新", 300)

if ok then
    print("找到 [更新] 模板并点击成功")
else
    print("未找到 [更新] 模板")
end
print()

-- ==========================================
-- 6. 等待找色 (带超时)
-- ==========================================
print("--- 6. 等待找色 ---")

-- 等待某个模板出现，超时 5 秒
local waitedName = CF.waitForTemplateToTable(Default_t, Default_d, 5000, 200)

if waitedName then
    print(string.format("等待成功，找到: %s", waitedName))
    print("坐标:", Default_d[waitedName][1], Default_d[waitedName][2])
else
    print("等待超时，未找到任何模板")
end
print()

-- ==========================================
-- 7. 实战: 征途创建角色流程
-- ==========================================
print("--- 7. 实战: 征途创建角色 ---")

-- 定义颜色表 (参考图片中的 Point_t)
Point_t = {
    ["随机名字"] = {
        {680, 603, 0xffffff},
    },
    ["输入名字"] = {
        {631, 603, 0xffffff},
    },
    ["输入完成"] = {
        {1023, 600, 0xffd678},
    },
    ["创建角色"] = {
        {1011, 601, 0xccbb66},
        {1011, 620, 0x7e7e7},
    },
}

-- 定义坐标表
Point_d = {}

-- 流程: 依次找色并点击
local steps = {
    {template = "随机名字", click = "随机名字", delay = 500},
    {template = "输入名字", click = "输入名字", delay = 300},
    {template = "输入完成", click = "输入完成", delay = 500},
    {template = "创建角色", click = "创建角色", delay = 1000},
}

for _, step in ipairs(steps) do
    print(string.format("等待 [%s]...", step.template))
    
    -- 找色
    local found = CF.waitForTemplateToTable({[step.template] = Point_t[step.template]}, 
                                             Point_d, 3000, 200)
    
    if found then
        print(string.format("  找到 [%s]: (%d, %d)", 
                           step.template, 
                           Point_d[step.template][1], 
                           Point_d[step.template][2]))
        
        -- 点击
        CF.clickByCoordTable(Point_d, step.click, step.delay)
        print(string.format("  点击 [%s] 成功", step.click))
    else
        print(string.format("  [%s] 未找到，跳过", step.template))
    end
end

print()

-- ==========================================
-- 结束
-- ==========================================
print("=== 懒人精灵原生模式示例执行完毕 ===")
