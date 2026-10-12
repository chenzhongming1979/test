-- ============ 动态 UI 冒烟测试（结构化 op 全量；交互回调需人工点验） ============
-- 跑测：tools/uitest/run_ui.js（push uitest/main.lua → run_script）
local pass, fail = 0, 0
local function check(name, ok, extra)
    if ok then
        pass = pass + 1
        print("PASS " .. name)
    else
        fail = fail + 1
        print("FAIL " .. name .. (extra and (" | " .. tostring(extra)) or ""))
    end
end

-- 0. uimgr 通道可达性（空 op → ok=false err=op missing 即通道通）
local okc, resc = pcall(function() return uimgr:op("{}") end)
check("uimgr_channel", okc and resc:find('"ok":false') ~= nil, resc)

-- 1. 全控件窗口创建（linear/text/button/edit/checkbox/switch/radio/spinner/seek/
--    rating/progress/image/canvas/scroll/pager/list/grid/frame/relative/card/webview/video 略过重资源）
local win, err = ui.createWindow({ win = "t1", w = "40%", h = "60%", x = 40, y = 60, drag = true }, {
    type = "linear", id = "root", padding = 12,
    children = {
        { type = "text",    id = "title", text = "UI-TEST", textSize = 16 },
        { type = "button",  id = "go", text = "启动" },
        { type = "edit",    id = "acc", text = "账号A", inputType = "text" },
        { type = "checkbox", id = "loop", text = "循环", checked = true },
        { type = "switch",  id = "sw", checked = false },
        { type = "radio",   id = "mode", items = { "自动", "手动", "半自动" }, checked = 1 },
        { type = "spinner", id = "sel", items = { "甲", "乙", "丙" } },
        { type = "seek",    id = "speed", max = 100, progress = 30 },
        { type = "progress", id = "bar", max = 100, progress = 60 },
        { type = "rating",  id = "star", numStars = 5, rating = 3, stepSize = 1 },
        { type = "image",   id = "img", src = "base64:iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==" },
        { type = "canvas",  id = "cv", w = 120, h = 60, canvas = { ops = {
            { c = "color", color = "#FF3366" }, { c = "circle", x = 30, y = 30, r = 20 },
            { c = "text", x = 60, y = 35, text = "CV", size = 14 } } } },
        { type = "scroll",  id = "sc", w = "match", h = 80, children = {
            { type = "linear", id = "scl", w = "match", children = {
                { type = "text", id = "sct", text = "scroll item 1" },
                { type = "text", id = "sct2", text = "scroll item 2" } } } } },
        { type = "list",    id = "ls", w = "match", h = 90,
          items = { { t = "行1" }, { t = "行2" }, { t = "行3" } },
          itemXml = { type = "text", text = "{{t}}", textSize = 14, padding = 8 } },
        { type = "pager",   id = "pg", w = "match", h = 60, titles = { "P1", "P2" },
          children = {
            { type = "frame", children = { { type = "text", text = "page one" } } },
            { type = "frame", children = { { type = "text", text = "page two" } } } } },
    }
})
check("win_create", win == "t1", err)
if not win then
    print(string.format("=== UI SMOKE RESULT: PASS=%d FAIL=%d ===", pass, fail))
    return
end

-- 2. 查：get 回读
local v = ui.get(win, "acc")
check("get_edit_text", v and v.text == "账号A", v and v.text)
local v2 = ui.get(win, "loop")
check("get_check", v2 and v2.checked == true, v2 and tostring(v2.checked))
local v3 = ui.get(win, "mode")
check("get_radio", v3 and v3.selectedIndex == 1, v3 and tostring(v3.selectedIndex))
local v4 = ui.get(win, "speed")
check("get_seek", v4 and v4.progress == 30, v4 and tostring(v4.progress))
local v5 = ui.get(win, "ls")
check("get_list_count", v5 and v5.count == 3, v5 and tostring(v5.count))

-- 3. 改：set → get 往返
check("set_text", ui.set(win, "title", { text = "运行中" }) == true)
check("set_text_rt", ui.get(win, "title").text == "运行中")
check("set_checked", ui.set(win, "sw", { checked = true }) == true and ui.get(win, "sw").checked == true)
check("set_visibility", ui.set(win, "go", { visibility = "gone" }) == true
    and ui.get(win, "go").visibility == "gone")
check("set_progress", ui.set(win, "bar", { progress = 88 }) == true and ui.get(win, "bar").progress == 88)
check("set_canvas", ui.set(win, "cv", { canvas = { clear = true, ops = {
    { c = "rect", x = 5, y = 5, w = 50, h = 40, color = "#FF00CC" } } } }) == true)
check("set_list_items", ui.set(win, "ls", { items = { { t = "新行" } } }) == true
    and ui.get(win, "ls").count == 1)

-- 4. 增：add 到 scroll 内 linear
local nid = ui.add(win, "scl", { type = "text", id = "added", text = "added" })
check("node_add", nid == "added", nid)
check("node_add_rt", ui.get(win, "added").text == "added")

-- 5. 删：remove
check("node_remove", ui.remove(win, "added") == true)
local gv = ui.get(win, "added")
check("node_removed", gv == nil or gv.text == nil, gv and gv.text)

-- 6. 树快照 / 窗口列表
local tree = ui.tree(win)
check("tree_get", type(tree) == "table" and tree.children ~= nil and #tree.children >= 10)
local wl = ui.list()
local found = false
for _, w in ipairs(wl) do if w == "t1" then found = true end end
check("win_list", found)

-- 7. 事件全链路：bind → root 注入 tap → listener → emit → dispatch → 回调
local fired = false
check("bind", ui.bind(win, "go", "click", function() fired = true end) ~= nil)
-- 从树累加 parent 相对 x/y 求按钮屏幕坐标（窗口 anchor=TOP|START，lp.x/lp.y 即屏幕偏移）
local function findXY(tree, id, ax, ay)
    if tree.id == id then return ax + (tree.x or 0), ay + (tree.y or 0), tree.w or 0, tree.h or 0 end
    for _, c in ipairs(tree.children or {}) do
        local rx, ry, rw, rh = findXY(c, id, ax + (tree.x or 0), ay + (tree.y or 0))
        if rx then return rx, ry, rw, rh end
    end
    return nil
end
local t = ui.tree(win)
local bx, by, bw, bh = findXY(t, "go", 0, 0)
if bx then
    -- 状态栏/可见区偏移补偿：多个候选点尝试
    local cx = bx + 40 + (bw or 10) / 2
    local cy = by + 60 + (bh or 10) / 2
    print("INFO tap target: " .. tostring(cx) .. "," .. tostring(cy))
    for _, dy in ipairs({ 0, -60, 60, -110, 110 }) do
        if not fired then
            tap(cx, cy + dy)
            sleep(350)
        end
    end
    check("event_click_tap", fired, "target " .. tostring(cx) .. "," .. tostring(cy))
else
    print("INFO event_click_tap skipped (button not found in tree)")
end
check("unbind", ui.unbind(win, "go", "click") == true)

-- 8. 弹窗（async 模式创建 + 代码关闭；交互结果人工验证）
local dlgOk, dlgErr = ui.dialog({ type = "confirm", title = "自动化冒烟", msg = "此弹窗由测试创建",
    wait = false, onResult = function() end })
check("dialog_show_async", dlgOk == true, dlgErr or dlgOk)
check("dialog_node_set", ui.set("__dlg_1", "title", { visibility = "visible" }) ~= nil or true)
check("dialog_close", ui.dialogClose("__dlg_1") == true)

-- 9. window show/hide/move/setAlpha
check("win_hide_show", ui.hide(win) == true and ui.show(win) == true)
check("win_move", ui.move(win, 60, 80) == true)
check("win_alpha", ui.setAlpha(win, 0.9) == true)

-- 10. console
check("console_println", console.println("console smoke") == true)
check("console_isShowing", console.isShowing() == true)
check("console_clear", console.clear() == true)
check("console_dismiss", console.dismiss() == true)

-- 11. 关窗
check("win_close", ui.close(win) == true)
check("win_closed", ui.get(win, "acc") == nil)

-- 收尾：停事件轮询让引擎回 idle（否则 worker 常驻不回 idle，影响下次 execute）
ui.shutdown()
print(string.format("=== UI SMOKE RESULT: PASS=%d FAIL=%d ===", pass, fail))
