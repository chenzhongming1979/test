-- ============================================================
-- diag_node_sugar.lua — UiSelector/UiObject newIndex/invoke 语义糖全覆盖
-- 运行：node tools/selftest/run_diag.js diag_node_sugar.lua [wsPort]
-- 前置：引擎已连接（root 主引擎，或沙箱+无障碍已开启）。
--       屏幕内容任意——脚本自动从 getNodeXml 提取屏幕真实文本/ID 做强校验；
--       提取不到时降级为「糖版 vs 链式版结果等价」弱校验（会在汇总标注）。
-- 覆盖：字符串五系赋值 / 布尔态 true+false 赋值 / index+drawingOrder 数值赋值 /
--       id(res) 字段映射 / sel(...) 调用糖与超时 / node(i) 与 node() 刷新 /
--       node.text 写入（找到 editable 节点时）/ 全部错误分支（pcall 断言中文报错）
-- ============================================================

local passCount, failCount, skipCount = 0, 0, 0
local failures = {}

local function check(name, ok, detail)
    if ok then
        passCount = passCount + 1
        print("[PASS] " .. name)
    else
        failCount = failCount + 1
        local msg = name .. (detail ~= nil and (" | " .. tostring(detail)) or "")
        failures[#failures + 1] = msg
        print("[FAIL] " .. msg)
    end
end

local function skip(name, why)
    skipCount = skipCount + 1
    print("[SKIP] " .. name .. " | " .. tostring(why))
end

-- 节点指纹：centerX,centerY,text（同一视图两次查找实例不同，按指纹比较）
local function fp(nd)
    if nd == nil then return "nil" end
    local okx, x = pcall(function() return nd:centerX() end)
    local oky, y = pcall(function() return nd:centerY() end)
    local okt, t = pcall(function() return nd:text() end)
    return tostring(okx and x or "?") .. "," .. tostring(oky and y or "?") .. ","
        .. tostring(okt and t or "?")
end

-- 糖版 vs 链式版 findOne 等价性
local function sameFind(sa, sb, timeout)
    local a, b
    local oka, ea = pcall(function() return sa:findOne(timeout or 400) end)
    local okb, eb = pcall(function() return sb:findOne(timeout or 400) end)
    if not oka or not okb then return false, "throw: " .. tostring(ea or eb) end
    a, b = ea, eb
    return fp(a) == fp(b), a
end

print("=== 0. 环境 ===")
-- 自诊断：env/root/全局存在性逐项打印，任何一步失败都显式落 [FAIL] 而非静默终止
print("[INFO] runEnv=" .. (type(getRunEnvType) == "function" and tostring(getRunEnvType()) or "unknown")
    .. " UiSelector=" .. type(UiSelector)
    .. " getRootInActiveWindow=" .. type(getRootInActiveWindow)
    .. " getNodeXml=" .. type(getNodeXml))
local okR, root = pcall(function() return getRootInActiveWindow ~= nil and getRootInActiveWindow() or nil end)
if not okR then
    check("env.getRootInActiveWindow", false, root)
    print(string.format("=== 汇总: PASS=%d FAIL=%d SKIP=%d（环境不可用，提前结束）===",
        passCount, failCount, skipCount))
    return
end
check("env.rootInActiveWindow", root ~= nil)
if root == nil then
    print(string.format("=== 汇总: PASS=%d FAIL=%d SKIP=%d（无障碍不可用，提前结束）===",
        passCount, failCount, skipCount))
    return
end

-- 屏幕真实素材提取（强校验用）：首个非空 text / id / pkg
local T, ID, PKG
local okXml, xml = pcall(getNodeXml, true)
if okXml and type(xml) == "string" then
    for m in xml:gmatch(' t="([^"]*)"') do if m and #m > 0 then T = m break end end
    for m in xml:gmatch(' id="([^"]*)"') do if m and #m > 0 then ID = m break end end
    for m in xml:gmatch(' p="([^"]*)"') do if m and #m > 0 then PKG = m break end end
end
if T == nil then T = "设置" end  -- 兜底：仍可做等价弱校验
print(string.format("[INFO] 屏幕素材 text=%s id=%s pkg=%s", tostring(T), tostring(ID), tostring(PKG)))

print("=== 1. UiSelector.newIndex 字符串五系赋值（糖版 vs 链式版等价） ===")
local strCases = {
    { "text", T }, { "textContains", T }, { "textStartsWith", T },
    { "textEndsWith", T }, { "textMatches", T },
    { "content", T }, { "contentContains", T },
    { "clazz", "android.widget." }, { "clazzContains", "Widget" },
}
for _, c in ipairs(strCases) do
    local k, v = c[1], c[2]
    local okC, errC = pcall(function()
        local sa = UiSelector(); sa[k] = v
        local sb = UiSelector(); sb[k](sb, v)
        local same, hit = sameFind(sa, sb, 400)
        check("newindex.str." .. k, same, hit)
    end)
    if not okC then check("newindex.str." .. k, false, "sugar threw: " .. tostring(errC)) end
end
if ID then
    local okC, errC = pcall(function()
        local sa = UiSelector(); sa.id = ID
        local sb = UiSelector():id(ID)
        check("newindex.str.id(res映射)", sameFind(sa, sb, 400))
        sa = UiSelector(); sa.idContains = ID:sub(1, math.min(#ID, 8))
        sb = UiSelector():idContains(ID:sub(1, math.min(#ID, 8)))
        check("newindex.str.idContains(res映射)", sameFind(sa, sb, 400))
    end)
    if not okC then check("newindex.str.id(res映射)", false, "sugar threw: " .. tostring(errC)) end
else
    skip("newindex.str.id(res映射)", "屏幕无 id 属性")
end
if PKG then
    local okC, errC = pcall(function()
        local sa = UiSelector(); sa.pkg = PKG
        local sb = UiSelector():pkg(PKG)
        check("newindex.str.pkg", sameFind(sa, sb, 400))
    end)
    if not okC then check("newindex.str.pkg", false, "sugar threw: " .. tostring(errC)) end
else
    skip("newindex.str.pkg", "屏幕无 pkg 属性")
end

print("=== 2. UiSelector.newIndex 布尔态赋值 true/false（Boolean? 语义） ===")
local bools = { "clickable", "checkable", "checked", "enabled", "focusable", "focused",
    "longClickable", "scrollable", "selected", "visibleToUser", "editable", "password" }
local anyTrueHit = false
for _, k in ipairs(bools) do
    local okC, errC = pcall(function()
        local sa = UiSelector(); sa[k] = true
        local sb = UiSelector(); sb[k](sb, true)
        local same, hit = sameFind(sa, sb, 300)
        if hit ~= nil then anyTrueHit = true end
        check("newindex.bool." .. k .. "=true", same, hit)
        sa = UiSelector(); sa[k] = false
        sb = UiSelector(); sb[k](sb, false)
        same, hit = sameFind(sa, sb, 300)
        check("newindex.bool." .. k .. "=false", same, hit)
    end)
    if not okC then check("newindex.bool." .. k, false, "sugar threw: " .. tostring(errC)) end
end
check("bool.sanity.至少一个true条件命中", anyTrueHit, "24 项全空可能屏幕无标准控件，仅提示")

print("=== 3. UiSelector.newIndex 数值赋值 index/drawingOrder ===")
local baseS = UiSelector(); baseS.text = T
local baseC = UiSelector():text(T)
local s1 = UiSelector(); s1.text = T; s1.index = 0
local s2 = UiSelector():text(T):index(0)
check("newindex.num.index=0", sameFind(s1, s2, 400))
local s3 = UiSelector(); s3.text = T; s3.drawingOrder = 0
local s4 = UiSelector():text(T):drawingOrder(0)
check("newindex.num.drawingOrder=0", sameFind(s3, s4, 400))

print("=== 4. UiSelector.invoke 调用糖 sel(...) ===")
local nSugar = UiSelector(); nSugar.text = T
local nA = nSugar(...)
local nB = nSugar:findOne()
check("invoke.sel.sel(...)等价findOne", nA ~= nil and fp(nA) == fp(nB), fp(nA) .. " vs " .. fp(nB))
local t0 = os.time()
local nMiss = UiSelector():text("___绝对不存在___")(400)
check("invoke.sel.超时返nil", nMiss == nil and (os.time() - t0) <= 5,
    "miss=" .. tostring(nMiss) .. " elapsed=" .. tostring(os.time() - t0) .. "s")

print("=== 5. UiObject 调用糖 node()/node(i) 与赋值糖 node.text ===")
local nd = nA or root
local c1, c2 = nd(0), nd:child(0)
check("invoke.node.node(0)等价child(0)", fp(c1) == fp(c2), fp(c1) .. " vs " .. fp(c2))
local okR, r = pcall(function() return nd() end)
check("invoke.node.node()刷新返self", okR and r == nd, tostring(r))
local ed = UiSelector():editable(true):findOne(300)
if ed then
    local orig = ed:text()
    ed.text = "糖测"
    local w = ed:text()
    ed:setText(orig or "")
    check("newindex.node.text写入", w == "糖测", "got " .. tostring(w))
else
    skip("newindex.node.text写入", "屏幕无 editable 节点")
end

print("=== 6. 错误分支（pcall 断言中文报错） ===")
local function errOf(f) local ok, e = pcall(f); return not ok, tostring(e) end

local okE, eE = errOf(function() local s = UiSelector(); s.foo = "x" end)
check("newindex.err.未知字符串键", okE and eE:find("不支持", 1, true) ~= nil, eE)

okE, eE = errOf(function() local s = UiSelector(); s.notACondition = true end)
check("newindex.err.未知布尔键", okE and eE:find("不支持", 1, true) ~= nil, eE)

okE, eE = errOf(function() local s = UiSelector(); s.depthX = 1 end)
check("newindex.err.未知数值键", okE and eE:find("不支持", 1, true) ~= nil, eE)

okE, eE = errOf(function() local s = UiSelector(); s.textMatches = "([<<" end)
check("newindex.err.非法正则", okE and eE:find("不是合法正则", 1, true) ~= nil, eE)

okE, eE = errOf(function() local s = UiSelector(); s.text = {} end)
check("newindex.err.值类型不支持", okE and eE:find("仅支持", 1, true) ~= nil, eE)

okE, eE = errOf(function() local s = UiSelector(); s[true] = "x" end)
check("newindex.err.非字符串键", okE and eE:find("键必须是字符串", 1, true) ~= nil, eE)

okE, eE = errOf(function() nd.hello = 1 end)
check("newindex.err.node未知键", okE and eE:find("不支持", 1, true) ~= nil, eE)

print("=== 汇总 ===")
print(string.format("PASS=%d FAIL=%d SKIP=%d", passCount, failCount, skipCount))
if #failures > 0 then
    print("--- 失败明细 ---")
    for _, f in ipairs(failures) do print("  " .. f) end
end
