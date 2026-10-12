package com.autolua.engine.extension.ui

import com.autolua.engine.base.LuaContext
import com.autolua.engine.base.LuaEnvContributor

/**
 * 动态 UI Lua 层（ui_* API 全集 + console 日志悬浮窗）。
 *
 * - 全局 `ui` 表：win.create/close/show/hide/move/setSize/setAlpha/list +
 *   node.add/set/remove/get/tree + bind/unbind + dialog/alert/confirm/input/sheet + toast
 * - 全局 `console` 表：show/dismiss/println/clear（拖动日志悬浮窗）
 *
 * 依赖（惰性寻址，调用时解析）：跨进程/同进程 `uimgr` 服务（JSON op 协议）、
 * `thread` 协程（每事件独立协程派发回调，回调内可阻塞弹窗/继续操作 UI）、`json`（cjson）。
 *
 * 事件模型：Java 侧控件 listener 全量常挂 → 事件帧入 uimgr 队列 → 本层 50ms 轮询协程
 * 唯一消费 pollEvents → 按(win,id,event)路由到 bind 注册的 Lua 回调 / 弹窗等待者。
 * 未绑定的事件直接丢弃，不回压。
 */
open class UiLib : LuaEnvContributor {

  override fun contribute(ctx: LuaContext) {
    try {
      ctx.loadBuffer(UI_LUA.toByteArray(Charsets.UTF_8), "=uilib", LuaContext.CodeMode.TEXT)
      val r = ctx.pcall(0, 0, 0)
      if (r != 0) {
        val msg = try { ctx.toString(-1) } catch (t: Throwable) { "pcall code=$r" }
        android.util.Log.w("UiLib", "ui.lua exec failed: $msg")
        ctx.pop(1)
      }
    } catch (t: Throwable) {
      android.util.Log.w("UiLib", "ui.lua load failed: $t")
    }
  }

  companion object {
    private val UI_LUA = """
-- ============ JY-Frame 动态 UI（JSON 控件树 + op 协议 + 事件回调） ============
-- 由 UiLib 注入；依赖：uimgr 服务、thread、json
ui = ui or {}
console = console or {}

local _cbs = {}       -- (win\1id\1event) → {fn=...}
local _waiters = {}   -- tostring(req) → {done=false, result=nil}
local _dlgCb = {}     -- tostring(req) → onResult 回调（异步弹窗）
local _req = 0
local _disp = false

local function _svc()
    local s = rawget(_G, "uimgr")
    if not s then error("uimgr 服务不可用（root 引擎未启动或沙箱未注入）") end
    return s
end

-- 同步执行 op：成功返回响应表，失败返回 nil, err
local function _call(cmd)
    local s = _svc()
    local ok, res = pcall(function() return s:op(json.encode(cmd)) end)
    if not ok then return nil, tostring(res) end
    local r = json.decode(res or "{}")
    if type(r) ~= "table" then return nil, "bad ui response" end
    if not r.ok then return nil, tostring(r.err or "ui op failed") end
    return r
end

-- 事件帧路由（dispatch 协程唯一消费 pollEvents）
local function _route(ev)
    if type(ev) ~= "table" then return end
    if ev.ev == "dialog" then
        local key = tostring(ev.req)
        local w = _waiters[key]
        if w then
            w.done = true
            w.result = { action = ev.action, values = ev.values, index = ev.index, req = ev.req }
            return
        end
        local cb = _dlgCb[key]
        if cb then
            _dlgCb[key] = nil
            local r = { action = ev.action, values = ev.values, index = ev.index, req = ev.req }
            thread.start(function(f, e) pcall(f, e) end, cb, r)
        end
    elseif ev.ev == "ui" then
        local key = tostring(ev.win or "") .. "\1" .. tostring(ev.id or "") .. "\1" ..
            tostring(ev.event or "")
        local reg = _cbs[key]
        if reg then
            -- 每事件独立协程：回调内可阻塞（弹窗 wait）/再调 ui_*，互不饿死
            thread.start(function(fn, e)
                local okr, errr = pcall(fn, e)
                if not okr then print("ui callback error: " .. tostring(errr)) end
            end, reg.fn, ev)
        end
    end
end

local _stop = false
local _quiet = 0
local _gen = 0         -- 派发循环代际：_ensureDispatch 每次调用都换代启动新循环，
                       -- 旧代发现代际不符自动退出。 interrupt 强杀协程时清理代码被
                       -- longjmp 跳过、_disp 残留导致的"下次面板点击全无效"由此根治
                       -- （VSCode 运行按钮的自愈路径 stop→run 紧邻，任何"判活再启动"都有尸体窗口）

local function _dispatchLoop(myGen)
    pcall(print, "[disp] loop gen=" .. tostring(myGen))
    while not _stop do
        if myGen ~= _gen then
            pcall(print, "[disp] gen " .. tostring(myGen) .. " stale, exit")
            return
        end
        local hadEvent = false
        local okp, frame = pcall(function()
            local s = rawget(_G, "uimgr")
            if not s then return "[]" end
            return s:pollEvents()
        end)
        if okp and type(frame) == "string" and #frame > 1 then
            local arr = json.decode(frame)
            if type(arr) == "table" and #arr > 0 then
                hadEvent = true
                -- [disp] events=N 逐轮打印已移除（2026-09-23）：交互期每 50ms 一条 print
                -- → IdeLog 管道洪水 → 传输阻塞 + LuaState 锁占持（print 洪水实测根源之一）
                for _, ev in ipairs(arr) do _route(ev) end
            end
        end
        -- 自动退出：无绑定回调、无等待者、连续 3s 无事件 → 停止轮询（让引擎回 idle）
        if not hadEvent and next(_cbs) == nil and next(_waiters) == nil then
            _quiet = _quiet + 50
            if _quiet >= 3000 then break end
        else
            _quiet = 0
        end
        thread.sleep(50)
    end
    if myGen == _gen then
        _disp = false
        _stop = false
    end
end

function ui._ensureDispatch()
    -- 无条件换代启动：新循环立即接管，旧循环（含僵死残留）看到代际不符自行消亡；
    -- stop→run 紧邻时不会有"误判存活导致永不派发"的窗口
    _gen = _gen + 1
    _disp = true
    _stop = false
    _quiet = 0
    pcall(print, "[disp] ensureDispatch gen=" .. tostring(_gen))
    thread.start(_dispatchLoop, _gen)
end

-- ui.shutdown()：停事件轮询（脚本收尾调用；窗口/绑定保留，引擎回 idle）
function ui.shutdown()
    _cbs = {}
    _waiters = {}
    _dlgCb = {}
    if _disp then _stop = true end
    return true
end

-- ---------------- 窗口 ----------------
-- ui.createWindow(cfg, rootTree) → winId；cfg:{win?,x,y,w,h,drag,focus,alpha,fullscreen,gravity,notTouchable}
function ui.createWindow(cfg, root)
    cfg = cfg or {}
    if type(cfg) == "string" then cfg = { win = cfg } end
    local r, err = _call({ op = "win.create", win = cfg.win, cfg = cfg, root = root })
    if not r then return nil, err end
    ui._ensureDispatch()
    return (r.data and r.data.win) or cfg.win
end
ui.win = ui.createWindow

function ui.close(win) local r, e = _call({ op = "win.close", win = win }); if not r then return nil, e end return true end
function ui.show(win) local r, e = _call({ op = "win.show", win = win }); if not r then return nil, e end return true end
function ui.hide(win) local r, e = _call({ op = "win.hide", win = win }); if not r then return nil, e end return true end
function ui.move(win, x, y) local r, e = _call({ op = "win.move", win = win, x = x, y = y }); if not r then return nil, e end return true end
function ui.setSize(win, w, h) local r, e = _call({ op = "win.setSize", win = win, w = w, h = h }); if not r then return nil, e end return true end
function ui.setAlpha(win, a) local r, e = _call({ op = "win.setAlpha", win = win, a = a }); if not r then return nil, e end return true end
function ui.list() local r, e = _call({ op = "win.list" }); if not r then return nil, e end return (r.data and r.data.wins) or {} end

-- ---------------- 控件增删改查 ----------------
-- ui.add(win, parent, node[, index]) → 控件 id
function ui.add(win, parent, node, index)
    local r, err = _call({ op = "node.add", win = win, parent = parent or "", node = node, index = index })
    if not r then return nil, err end
    return (r.data and r.data.id) or nil
end

-- ui.set(win, id, attrs)：批量改属性（同 build 属性面：text/checked/bg/visibility/items/canvas ops/...）
function ui.set(win, id, attrs)
    local r, e = _call({ op = "node.set", win = win, id = id, attrs = attrs or {} })
    if not r then return nil, e end
    return true
end

function ui.remove(win, id) local r, e = _call({ op = "node.remove", win = win, id = id }); if not r then return nil, e end return true end

-- ui.get(win, id) → 值表 {type,text,checked,selectedIndex,progress,url,x,y,w,h,...}
function ui.get(win, id)
    local r, e = _call({ op = "node.get", win = win, id = id })
    if not r then return nil, e end
    return (r.data and r.data.values) or {}
end

-- ui.tree(win) → 整树快照（含 children 递归）
function ui.tree(win)
    local r, e = _call({ op = "tree.get", win = win })
    if not r then return nil, e end
    return (r.data and r.data.tree) or {}
end

-- ---------------- 事件绑定 ----------------
-- ui.bind(win, id, event, fn)：fn(ev) — ev={ev,win,id,event,data}
-- 事件：click longClick touch check select progressChange textChange editorAction
--       itemClick itemLongClick scrollEnd(itemSelect pager) urlLoad pageDone
function ui.bind(win, id, event, fn)
    if type(fn) ~= "function" then return nil, "callback must be function" end
    local key = tostring(win or "") .. "\1" .. tostring(id or "") .. "\1" .. tostring(event or "")
    _cbs[key] = { fn = fn }
    -- touch 高频事件白名单注册：Java 侧 touch 默认不上报（DOWN+MOVE×N+UP 洪流会
    -- 占满事件队列挤掉 click），显式 bind 才通过 node.listen 开启
    if event == "touch" then
        pcall(function()
            local s = rawget(_G, "uimgr")
            s:op(json.encode({ op = "node.listen", win = tostring(win or ""),
                               id = tostring(id or ""), events = { "touch" } }))
        end)
    end
    ui._ensureDispatch()
    return key
end

function ui.unbind(win, id, event)
    local key = tostring(win or "") .. "\1" .. tostring(id or "") .. "\1" .. tostring(event or "")
    _cbs[key] = nil
    return true
end

-- ---------------- 弹窗 ----------------
-- ui.dialog(opts)：
--   opts.type = alert|confirm|input|sheet|custom
--   opts.title/msg/buttons({text,action}或字符串)/fields({id,label,text,inputType})/items/content(控件树)
--   opts.wait=true 同步等结果（必须在 thread 协程内调用）；opts.timeout 毫秒（0=不限）
--   opts.onResult=fn 异步回调
-- 返回：wait 时 {action="ok|cancel|select|b1..", values={...}, index=n}；异步 true
function ui.dialog(opts)
    opts = opts or {}
    local t = tostring(opts.type or "alert"):lower()
    _req = _req + 1
    local req = _req
    local key = tostring(req)
    local d = {
        type = t, title = opts.title, msg = opts.msg,
        buttons = opts.buttons, fields = opts.fields, items = opts.items,
        content = opts.content, w = opts.w, cancelable = opts.cancelable
    }
    if t == "alert" and not d.buttons then d.buttons = { { text = "确定", action = "ok" } } end
    if t == "confirm" and not d.buttons then
        d.buttons = { { text = "确定", action = "ok" }, { text = "取消", action = "cancel" } }
    end
    if t == "input" then
        if not d.buttons then d.buttons = { { text = "确定", action = "ok" }, { text = "取消", action = "cancel" } } end
        if not d.fields then
            d.fields = { { id = "input", label = opts.label, text = opts.text or "", inputType = opts.inputType } }
        end
    end
    if t == "sheet" and not d.items then d.items = {} end
    local w = { done = false, result = nil }
    _waiters[key] = w
    if type(opts.onResult) == "function" then _dlgCb[key] = opts.onResult end
    local r, err = _call({ op = "dialog.show", req = req, dlg = d })
    if not r then
        _waiters[key] = nil
        _dlgCb[key] = nil
        return nil, err
    end
    ui._ensureDispatch()
    if opts.wait then
        local now = function()
            if tickCount then return tickCount() end
            return os.clock() * 1000
        end
        local t0 = now()
        local timeout = opts.timeout or 0
        while not w.done do
            if timeout > 0 and now() - t0 > timeout then
                _waiters[key] = nil
                return nil, "dialog timeout"
            end
            thread.sleep(40)
        end
        _waiters[key] = nil
        return w.result
    end
    return true
end

function ui.dialogClose(win, action)
    local r, e = _call({ op = "dialog.close", win = win, action = action or "cancel" })
    if not r then return nil, e end
    return true
end

-- ui.alert(title, msg[, onOk])：默认同步等待，返回结果表；传回调则异步
function ui.alert(title, msg, onOk)
    local opts = { type = "alert", title = title, msg = msg, wait = true }
    if type(onOk) == "function" then opts.onResult = onOk; opts.wait = false end
    return ui.dialog(opts)
end

-- ui.confirm(title, msg[, cb])：默认同步等待返回 bool；传回调则异步
function ui.confirm(title, msg, cb)
    local opts = { type = "confirm", title = title, msg = msg, wait = true }
    if type(cb) == "function" then opts.onResult = cb; opts.wait = false end
    local r, err = ui.dialog(opts)
    if not r then return nil, err end
    if opts.wait then return r.action == "ok" end
    return true
end

-- ui.input(opts[, cb])：opts={title,label,text,inputType,fields}，返回结果表（values 按字段 id）
function ui.input(opts, cb)
    opts = opts or {}
    opts.type = "input"
    if type(cb) == "function" then opts.onResult = cb; opts.wait = false end
    return ui.dialog(opts)
end

-- ui.sheet(title, items[, cb])：返回 {action="select", index=n}；取消 index=-1
function ui.sheet(title, items, cb)
    local opts = { type = "sheet", title = title, items = items or {}, wait = true }
    if type(cb) == "function" then opts.onResult = cb; opts.wait = false end
    local r, err = ui.dialog(opts)
    if not r then return nil, err end
    if opts.wait then
        if r.action ~= "select" then r.index = -1 end
        return r
    end
    return true
end

function ui.toast(msg, dur)
    local r, e = _call({ op = "toast", msg = tostring(msg or ""), dur = dur or 0 })
    if not r then return nil, e end
    return true
end

-- ---------------- 内置设置面板（底部【退出/确定】按钮行 + 行为全内置 + 阻塞语义） ----------------
-- ui.settings(opts) → 阻塞直至用户点击，返回 { action="ok", values={...} }；
--   点【退出】则保存后在本调用上下文抛 error("exitScript") 终止脚本（被 pcall 包裹时自行处理）
--   以后脚本建设置面板无需再写按钮/绑定/退出逻辑：
--   opts 窗口键透传 createWindow：win/x/y/w/h/drag/focus/alpha/gravity/bg...
--   opts.title：标题文本（可缺省）
--   opts.fields：值控件数组（不含按钮行），每项需带 id：edit/checkbox/spinner/seek...
--   opts.config：设置结果 JSON 保存路径（确定/退出都保存）；未填则报错落盘到
--     app 工作目录（ROOT_DIR→__SCRIPT_DIR__ 兜底）的 ui_settings_error.log
--   opts.confirmText/exitText：按钮文案（缺省 确定/退出）
--   opts.confirmExit=true：退出弹二次确认（缺省直接退出）
--   opts.exitTitle/exitMsg：退出确认弹窗文案
--   opts.onConfirm(values,onDone)：可选，默认自动 cloud.send{op="settings"}；
--     onDone() 由内部调用以解除阻塞（自定义回调务必调用，否则永久阻塞）
--   opts.onExit(values,onDone)：可选，默认 自动保存+关窗+结束脚本
--   values 收集：fields 逐项 ui.get 取主值（text/checked/selectedIndex/progress/rating/url）
--   保留 id 前缀 __（__root/__title/__btnExit/__btnOk），fields 勿用
local function _pickValue(v)
    if type(v) ~= "table" then return v end
    -- checked/selectedIndex 等状态值优先于 text（checkbox/switch 的 text 是标签非值）
    for _, k in ipairs({ "checked", "selectedIndex", "progress", "rating", "url", "text" }) do
        if v[k] ~= nil then return v[k] end
    end
    return v
end

local function _cloudSendSettings(values)
    local c = rawget(_G, "cloud")
    if type(c) == "table" and type(c.send) == "function" then
        pcall(c.send, { op = "settings", values = values })
    end
end

-- 阻塞节拍：主线程用全局 sleep（OS 阻塞+lSleep 挂起点，interrupt 可杀，
-- 且 lSleep 释放 stateMutex 让 dispatch 协程并行取事件）；协程内用 thread.sleep
local function _yieldTick(ms)
    if not pcall(thread.sleep, ms) then sleep(ms) end
end

-- 设置结果保存：config 填了写 JSON；失败/未填则错误 append 到 app 工作目录
local function _saveConfig(opts, values)
    local path = opts.config
    if type(path) ~= "string" or path == "" then
        local dir = rawget(_G, "ROOT_DIR") or rawget(_G, "__SCRIPT_DIR__") or "/sdcard"
        local f = io.open(dir .. "/ui_settings_error.log", "a")
        if f then
            f:write(os.date("%Y-%m-%d %H:%M:%S") .. " ui.settings: config 未配置，设置结果未保存\n")
            f:close()
        end
        print("[ui.settings] config 未配置，设置结果未保存（错误已写入工作目录 ui_settings_error.log）")
        return false
    end
    local j = rawget(_G, "json")
    local enc = "{}"
    if type(j) == "table" and type(j.encode) == "function" then
        local ok, e = pcall(j.encode, values)
        if ok and type(e) == "string" then enc = e end
    end
    local f = io.open(path, "w")
    if f then
        f:write(enc)
        f:close()
        -- root 引擎进程写入的文件放权给 app 进程通道（对齐 main.config 先例）
        pcall(os.execute, "chmod 666 '" .. tostring(path) .. "'")
        return true
    end
    local dir = rawget(_G, "ROOT_DIR") or rawget(_G, "__SCRIPT_DIR__") or "/sdcard"
    local ef = io.open(dir .. "/ui_settings_error.log", "a")
    if ef then
        ef:write(os.date("%Y-%m-%d %H:%M:%S") .. " ui.settings: config 写入失败 " .. tostring(path) .. "\n")
        ef:close()
    end
    print("[ui.settings] config 写入失败: " .. tostring(path))
    return false
end

function ui.settings(opts)
    opts = opts or {}
    local fields = opts.fields or {}
    local children = {}
    if opts.title then
        children[#children + 1] = { type = "text", id = "__title", text = tostring(opts.title), textSize = 16 }
    end
    for _, f in ipairs(fields) do children[#children + 1] = f end
    children[#children + 1] = { type = "linear", orientation = "h", w = "match", children = {
        { type = "button", id = "__btnExit", text = tostring(opts.exitText or "退出"), w = 0, weight = 1 },
        { type = "button", id = "__btnOk", text = tostring(opts.confirmText or "确定"), w = 0, weight = 1 } } }
    local win, err = ui.createWindow(opts, {
        type = "linear", id = "__root", padding = 12,
        bg = opts.bg or { color = "#204B1F", corner = 8 },
        children = children })
    if not win then return nil, err end

    -- 会合标志：点击回调（dispatch 协程）置位，调用方阻塞循环消费
    local wait = { done = false, action = nil, values = nil }
    local function onDone(action, values)
        wait.done, wait.action, wait.values = true, action, values
    end

    local function collect()
        local values = {}
        for _, f in ipairs(fields) do
            if type(f) == "table" and f.id then values[f.id] = _pickValue(ui.get(win, f.id)) end
        end
        return values
    end

    ui.bind(win, "__btnOk", "click", function()
        local values = collect()
        _saveConfig(opts, values)
        if type(opts.onConfirm) == "function" then
            opts.onConfirm(values, function() onDone("ok", values) end)
        else
            _cloudSendSettings(values)
            onDone("ok", values)
        end
        ui.close(win)
    end)

    ui.bind(win, "__btnExit", "click", function()
        -- 缺省直接退出（confirmExit=true 才弹二次确认）
        local go = true
        if opts.confirmExit == true then
            go = ui.confirm(opts.exitTitle or "退出", opts.exitMsg or "确定退出并结束脚本？")
        end
        if not go then return end
        local values = collect()
        _saveConfig(opts, values)
        if type(opts.onExit) == "function" then
            opts.onExit(values, function() onDone("exit", values) end)
        else
            _cloudSendSettings(values)
            ui.close(win)
            local c = rawget(_G, "cloud")
            if type(c) == "table" and type(c.exit) == "function" then c.exit() end
            onDone("exit", values)
        end
    end)

    -- 阻塞：点确定/退出前调用方不继续（UI 事件由 dispatch 协程并行派发）
    while not wait.done do
        _yieldTick(30)
    end
    local result = { action = wait.action, values = wait.values }
    if result.action == "exit" and type(opts.onExit) ~= "function" then
        error("exitScript", 0)   -- 在调用方上下文终止脚本（引擎识别后走 OnStop→容器回收）
    end
    return result
end

-- ---------------- 多配置 profile（按窗口树自动收集/回填可保存控件值） ----------------
-- 可保存控件类型（值控件；text/button 等展示控件不存）
local _SAVABLE = { edit = true, checkbox = true, switch = true, spinner = true,
                   seek = true, progress = true, rating = true }

-- 递归收集窗口树中可保存控件 { {win=,id=,type=} }
local function _collectSavable(win, node, out)
    if type(node) ~= "table" then return end
    local id, t = node.id, node.type
    if type(id) == "string" and id ~= "" and t and _SAVABLE[t] then
        out[#out + 1] = { win = win, id = id, type = t }
    end
    local ch = node.children
    if type(ch) == "table" then
        for _, c in ipairs(ch) do _collectSavable(win, c, out) end
    end
end

local function _profileTargets(win)
    local list = {}
    local wins = win ~= nil and { win } or (ui.list() or {})
    for _, w in ipairs(wins) do _collectSavable(w, ui.tree(w), list) end
    return list
end

-- ui.saveProfile(path[, win])：收集可保存控件值存 JSON（{id=值}）；win 缺省=全部窗口；
-- __ 前缀为引擎内部控件（__root/__btnOk...）自动跳过
function ui.saveProfile(path, win)
    local data = {}
    for _, n in ipairs(_profileTargets(win)) do
        if string.sub(n.id, 1, 2) ~= "__" then
            local v = _pickValue(ui.get(n.win, n.id))
            if v ~= nil then data[n.id] = v end
        end
    end
    local j = rawget(_G, "json")
    local enc = "{}"
    if type(j) == "table" and type(j.encode) == "function" then
        local ok, s = pcall(j.encode, data)
        if ok and type(s) == "string" then enc = s end
    end
    local f = io.open(path, "w")
    if not f then return nil, "cannot write " .. tostring(path) end
    f:write(enc)
    f:close()
    -- root 引擎进程写入的文件放权给 app 进程通道（IDE 同步/覆盖/删除），对齐 main.config 先例
    pcall(os.execute, "chmod 666 '" .. tostring(path) .. "'")
    return true
end

-- 按控件类型回填一个值（load 配置用）
local function _applyProfileValue(win, id, v)
    local info = ui.get(win, id)
    if type(info) ~= "table" or v == nil then return end
    local t = info.type
    if t == "edit" then ui.set(win, id, { text = tostring(v) })
    elseif t == "checkbox" or t == "switch" then ui.set(win, id, { checked = v == true })
    elseif t == "spinner" then ui.set(win, id, { selection = tonumber(v) or 0 })
    elseif t == "seek" or t == "progress" then ui.set(win, id, { progress = tonumber(v) or 0 })
    elseif t == "rating" then ui.set(win, id, { rating = tonumber(v) or 0 })
    end
end

-- ui.loadProfile(path[, win])：读 JSON 回填窗口控件（按 id 匹配）；win 缺省=全部窗口
function ui.loadProfile(path, win)
    local f = io.open(path, "r")
    if not f then return nil, "no such profile: " .. tostring(path) end
    local content = f:read("*a")
    f:close()
    local j = rawget(_G, "json")
    local data
    if type(j) == "table" and type(j.decode) == "function" then
        local ok, d = pcall(j.decode, content)
        if ok and type(d) == "table" then data = d end
    end
    if not data then return nil, "bad profile json: " .. tostring(path) end
    for _, n in ipairs(_profileTargets(win)) do
        if string.sub(n.id, 1, 2) ~= "__" then _applyProfileValue(n.win, n.id, data[n.id]) end
    end
    return true
end

-- ---------------- console 日志悬浮窗 ----------------
local _consoleWin = nil
local _logBuf = {}

function console.show()
    if _consoleWin then return _consoleWin end
    local win, err = ui.createWindow({
        drag = true, focus = true, x = 24, y = 200, w = "72%", h = "40%"
    }, {
        type = "linear", id = "root", bg = { color = "#E6111111", corner = 8 },
        padTop = 8, padBottom = 8, padLeft = 12, padRight = 12,
        children = {
            { type = "text", id = "title", text = "console", textColor = "#FF9CDCFE", textSize = 12 },
            { type = "scroll", id = "scroller", w = "match", weight = 1, children = {
                { type = "text", id = "log", text = "", textColor = "#FFEEEEEE", textSize = 12 } } },
            { type = "linear", orientation = "h", children = {
                { type = "edit", id = "filter", w = "match", weight = 1, textSize = 12,
                  hint = "filter", textColor = "#FFEEEEEE" },
                { type = "button", id = "clear", text = "清空", textSize = 12 },
                { type = "button", id = "close", text = "x", textSize = 12 } } }
        }
    })
    if not win then return nil, err end
    ui.bind(win, "close", "click", function() console.dismiss() end)
    ui.bind(win, "clear", "click", function() console.clear() end)
    _consoleWin = win
    return win
end

function console.println(s)
    local win = _consoleWin or console.show()
    if not win then return nil, "console create failed" end
    _logBuf[#_logBuf + 1] = tostring(s)
    if #_logBuf > 200 then table.remove(_logBuf, 1) end
    return ui.set(win, "log", { text = table.concat(_logBuf, "\n") })
end

function console.clear()
    _logBuf = {}
    if _consoleWin then ui.set(_consoleWin, "log", { text = "" }) end
    return true
end

function console.dismiss()
    if _consoleWin then
        ui.close(_consoleWin)
        _consoleWin = nil
    end
    return true
end

function console.isShowing() return _consoleWin ~= nil end
"""
  }
}
