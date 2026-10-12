-- tap 链路最小诊断：bind click/touch → tap → 看回调与事件帧
local win, err = ui.createWindow({ win = "d1", w = 220, h = 140, x = 40, y = 60 }, {
    type = "linear", padding = 8, children = {
        { type = "button", id = "b", text = "TAPME", w = 160, h = 80 } } })
if not win then print("ERR create: " .. tostring(err)) return end
ui.bind("d1", "b", "click", function(ev) print("CALLBACK click") end)
ui.bind("d1", "b", "touch", function(ev)
    print("CALLBACK touch act=" .. tostring(ev.data and ev.data.action))
end)
sleep(300)
local t = ui.tree("d1")
print("tree=" .. json.encode(t))
-- 按钮中心：树坐标 + 窗口 (40,60)
local b = t.children and t.children[1]
if b then
    local cx = 40 + (b.x or 0) + (b.w or 20) / 2
    local cy = 60 + (b.y or 0) + (b.h or 20) / 2
    print("tap center: " .. cx .. "," .. cy)
    tap(cx, cy); sleep(600)
    tap(cx, cy + 5); sleep(600)
end
print("END")
ui.shutdown()
