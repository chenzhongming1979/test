--[[ frameSdk-autolua2/ui.lua
UI 域：动态 UI 兼容层（ui 表全部）+ createHUD/showHUD/toast/getUIConfig。
版权与许可见本包 contract.lua 文件头。
两种调用约定兼容（首参为已注册布局名时按 UIUtils 约定，否则按 JUI 直调约定）。

底层映射（相对 frameSdk-firefly 的差异）：
- 引擎动态 UI 为 JSON 控件树 + op 协议（UiLib 注入的 ui 全局表）：
  ui.createWindow(cfg, rootTree) / ui.add / ui.set / ui.get / ui.bind(win,id,event,fn)
- 动态 UI 兼容层将 JUI DSL（newLayout/newRow/addXxx/show/getValue）编译为 JSON 树一次性建窗
- HUD 用动态 UI 常驻小窗（首次创建后续 ui.set 更新文本）；uimgr 不可用时降级 toast
--]]

local engine = require("frameSdk-autolua2.engine")
local compat = require("frameSdk-autolua2.compat")
local device = require("frameSdk-autolua2.device")

local ui = {}
local trim = compat.trim

-- ==================== HUD / toast / getUIConfig ====================

local hud = { nextId = 1, win = nil }

function ui.createHUD()
	local id = hud.nextId
	hud.nextId = id + 1
	return id
end

-- showHUD(id,text,size,color,bg,o,x,y,w,h,a,b,c)：样式参数忽略，单一文本悬浮窗
function ui.showHUD(id, text, size, color, bg, o, x, y, w, h, a, b, c)
	local uim = engine.M.ui
	if uim == nil or uim.createWindow == nil then
		-- uimgr 服务不可用：降级 toast（每 5 次提示一次避免刷屏）
		hud._miss = (hud._miss or 0) + 1
		if hud._miss % 5 == 1 then
			pcall(function()
				local t = rawget(_G, "toast")
				if t ~= nil then t(tostring(text or ""), 0) end
			end)
		end
		return
	end
	if hud.win == nil then
		local ok, win = pcall(uim.createWindow, {
			win = "frameSdk_hud", drag = true, x = 24, y = 60, w = "60%", h = "wrap",
		}, {
			type = "linear", id = "root", bg = { color = "#CC000000", corner = 6 },
			padTop = 6, padBottom = 6, padLeft = 10, padRight = 10,
			children = {
				{ type = "text", id = "hud_text", text = "", textColor = "#FFFFFFFF", textSize = 13, w = "match" },
			},
		})
		if ok and win ~= nil then hud.win = win end
	end
	if hud.win ~= nil then
		pcall(uim.set, hud.win, "hud_text", { text = tostring(text or "") })
	end
end

function ui.toast(t)
	-- 引擎全局 toast（compat 三级降级实现）优先；ui 表 toast 次之
	local gToast = rawget(_G, "toast")
	if gToast ~= nil then
		pcall(gToast, tostring(t or ""), 0)
		return
	end
	local uim = engine.M.ui
	if uim ~= nil and uim.toast ~= nil then
		pcall(uim.toast, tostring(t or ""), 0)
	end
end

-- getUIConfig("main.config")：静态 UI 保存值（从工作目录同名文件读取）
function ui.getUIConfig(key)
	local path = device.getWorkPath() .. "/" .. tostring(key or "")
	local f = io.open(path, "r")
	if f == nil then return "" end
	local s = f:read("*a")
	f:close()
	return s or ""
end

-- ==================== 动态 UI 兼容层（JUI DSL → ui 表 JSON 控件树） ====================
-- Spinner v1 以"选项序号输入框"呈现（取值为序号），M3 升级原生 Spinner。
-- 渲染/取值封装在 buildTree/readValue，M3 可整体替换实现而不动 JY/JUI 代码。

local uiState = { layouts = {}, values = {}, spinners = {}, _cur = nil, _active = nil, _activeLayout = nil, _winIds = {} }

local function callNamedHandler(handlerStr)
	-- UI 事件处理器为 JUI 层定义的全局函数（按名字动态调用，属框架约定，非包内自引用）
	local fname = string.match(tostring(handlerStr or ""), "([%w_]+)")
	local f = fname and rawget(_G, fname)
	if type(f) == "function" then
		local ok, err = pcall(f)
		if not ok then engine.log("UI 事件执行失败：", tostring(err)) end
	else
		engine.log("UI 事件函数不存在：", tostring(handlerStr))
	end
end

local function uiCurrentRow(lay)
	if lay.currentRow == nil then
		lay.currentRow = { views = {} }
		lay.rows[#lay.rows + 1] = lay.currentRow
	end
	return lay.currentRow
end

local function uiAddView(lay, vtype, name, props)
	if lay == nil then return end
	local row = uiCurrentRow(lay)
	row.views[#row.views + 1] = { type = vtype, name = tostring(name), props = props or {} }
end

-- 首参为已注册布局名 → 返回布局与参数偏移 1；否则当前布局、偏移 0
local function uiResolve(args)
	local lay = uiState.layouts[args[1]]
	if lay ~= nil then return lay, 1 end
	return uiState.layouts[uiState._cur], 0
end

local uitab = {}

function uitab.newLayout(name, w, h)
	local lay = { name = tostring(name), w = w, h = h, rows = {}, currentRow = nil, handlers = { click = {} }, onClose = nil }
	uiState.layouts[lay.name] = lay
	uiState._cur = lay.name
	return lay
end

function uitab.newRow(a, b, c, d)
	local lay, off = uiResolve({ a, b, c, d })
	if lay == nil then return end
	local rowName = (off == 1) and b or a
	lay.currentRow = { views = {}, name = rowName }
	lay.rows[#lay.rows + 1] = lay.currentRow
end

function uitab.addTextView(a, b, c, d, e)
	local lay, off = uiResolve({ a, b, c, d, e })
	if lay == nil then return end
	if off == 1 then
		uiAddView(lay, "text", b, { text = c, w = d, h = e })
	else
		uiAddView(lay, "text", a, { text = b, w = c, h = d })
	end
end

function uitab.addLine(a, b, c)
	local lay, off = uiResolve({ a, b, c })
	if lay == nil then return end
	uiAddView(lay, "text", (off == 1) and b or a, { text = "----------------" })
end

function uitab.addButton(a, b, c, d, e)
	local lay, off = uiResolve({ a, b, c, d, e })
	if lay == nil then return end
	if off == 1 then
		-- (layout, name, text, w, h)
		uiAddView(lay, "button", b, { text = c, w = d, h = e })
	else
		-- (name, text, handler, w, h)
		uiAddView(lay, "button", a, { text = b, w = d, h = e })
		if c ~= nil then lay.handlers.click[tostring(a)] = c end
	end
end

function uitab.addCheckBox(a, b, c, d, e, f)
	local lay, off = uiResolve({ a, b, c, d, e, f })
	if lay == nil then return end
	if off == 1 then
		-- (layout, name, tooltip, sel, w, h)
		uiAddView(lay, "check", b, { text = c, checked = (d == true or d == "1" or d == 1), w = e, h = f })
	else
		-- (name, tooltip, sel, w, h)
		uiAddView(lay, "check", a, { text = b, checked = (c == true or c == "1" or c == 1), w = d, h = e })
	end
end

function uitab.addEditText(a, b, c, d, e)
	local lay, off = uiResolve({ a, b, c, d, e })
	if lay == nil then return end
	if off == 1 then
		-- (layout, name, tip, w, h)
		uiAddView(lay, "edit", b, { hint = c, text = "", w = d, h = e })
	else
		-- (name, tip, defText, w, h)
		uiAddView(lay, "edit", a, { hint = b, text = c, w = d, h = e })
	end
end

function uitab.addSpinner(a, b, c, d, e, f)
	local lay, off = uiResolve({ a, b, c, d, e, f })
	if lay == nil then return end
	if off == 1 then
		-- (layout, name, list, sel, w, h)
		uiAddView(lay, "spinner", b, { list = c, sel = d, w = e, h = f })
		uiState.spinners[tostring(b)] = c
	else
		-- (name, list, sel, w, h)
		uiAddView(lay, "spinner", a, { list = b, sel = c, w = d, h = e })
		uiState.spinners[tostring(a)] = b
	end
end

function uitab.setOnClick(name, handlerStr)
	local lay = uiState.layouts[uiState._cur]
	if lay ~= nil then lay.handlers.click[tostring(name)] = handlerStr end
end

function uitab.setOnChange(name, handlerStr)
	-- v1：记录不生效（M3 经 ui.bind textChange/check 事件接入）
	local lay = uiState.layouts[uiState._cur]
	if lay ~= nil then lay.handlers.change = lay.handlers.change or {} end
end

function uitab.setOnClose(name, handlerStr)
	local lay = uiState.layouts[tostring(name)]
	if lay ~= nil then lay.onClose = handlerStr end
end

function uitab.addTabView(name, tabName)
	-- v1：标签页扁平化（页头文本分隔），M3 升级原生 Tab
	uitab.addTextView(tostring(tabName or "tab"), { text = "══ " .. tostring(tabName or "") .. " ══" })
end

function uitab.addTab(parentId, tabId, title)
	-- 扁平化实现，无需处理
end

-- JUI DSL 结构 → JSON 控件树
local function uiBuildTree(lay)
	local rows = {}
	for _, row in ipairs(lay.rows) do
		local kids = {}
		for _, v in ipairs(row.views) do
			local p = v.props
			local idn = tostring(v.name)
			local textSize = 14
			if v.type == "text" then
				kids[#kids + 1] = {
					type = "text", id = idn, text = tostring(p.text or ""),
					textColor = "#FFEEEEEE", textSize = textSize,
				}
			elseif v.type == "button" then
				kids[#kids + 1] = {
					type = "button", id = idn, text = tostring(p.text or ""), textSize = textSize,
				}
			elseif v.type == "edit" then
				kids[#kids + 1] = {
					type = "edit", id = idn, hint = tostring(p.hint or ""),
					text = tostring(p.text or ""), textSize = textSize, w = "match",
				}
			elseif v.type == "check" then
				kids[#kids + 1] = {
					type = "check", id = idn, text = tostring(p.text or ""),
					checked = (p.checked == true), textSize = textSize,
				}
			elseif v.type == "spinner" then
				local n = 0
				if type(p.list) == "table" then n = #p.list end
				kids[#kids + 1] = {
					type = "edit", id = idn, hint = "选项序号(0-" .. tostring(math.max(n - 1, 0)) .. ")",
					text = tostring(p.sel or 0), textSize = textSize, w = "match",
				}
			end
		end
		rows[#rows + 1] = { type = "linear", orientation = "h", w = "match", children = kids }
	end
	-- 自动附加【关闭并保存】按钮（触发 OnClose → SaveUISet → 解锁主流程）
	if engine.UI_ADD_CLOSE_BUTTON and lay.onClose ~= nil then
		lay.handlers.click["frameSdk_close_btn"] = lay.onClose
		rows[#rows + 1] = { type = "linear", orientation = "h", w = "match",
			children = { { type = "button", id = "frameSdk_close_btn", text = "【关闭并保存】", textSize = 14 } } }
	end
	return {
		type = "linear", id = "root", orientation = "v",
		bg = { color = "#F0151515", corner = 8 },
		padTop = 10, padBottom = 10, padLeft = 12, padRight = 12,
		children = {
			{ type = "scroll", id = "scroller", w = "match", weight = 1, children = {
				{ type = "linear", orientation = "v", w = "match", children = rows } } },
		},
	}
end

function uitab.show(name, flag)
	local uim = engine.M.ui
	local lay = uiState.layouts[tostring(name)]
	if uim == nil or uim.createWindow == nil then
		engine.log("ui.show 失败：uimgr 服务不可用（root 引擎未启动或沙箱未注入）")
		return
	end
	if lay == nil then
		engine.log("ui.show 失败：布局未注册（", tostring(name), "）")
		return
	end
	local winName = "jy_" .. tostring(name)
	-- 同名旧窗口先关闭（重复 show 场景）
	if uiState._winIds[winName] ~= nil then
		pcall(uim.close, uiState._winIds[winName])
		uiState._winIds[winName] = nil
	end
	local ok, win = pcall(uim.createWindow, {
		win = winName, drag = true, focus = false, x = 40, y = 80, w = "72%", h = "70%",
	}, uiBuildTree(lay))
	if not ok or win == nil then
		engine.log("ui.createWindow 失败：", tostring(win))
		return
	end
	uiState._active = win
	uiState._activeLayout = lay
	uiState._winIds[winName] = win
	-- 绑定点击事件（handler 为 JUI 全局函数名）
	for vname, handler in pairs(lay.handlers.click) do
		pcall(uim.bind, win, vname, "click", function() callNamedHandler(handler) end)
	end
	-- 应用已保存值（loadProfile 预载）
	for vname, val in pairs(uiState.values) do
		if type(val) == "boolean" then
			pcall(uim.set, win, vname, { checked = val })
		else
			pcall(uim.set, win, vname, { text = tostring(val) })
		end
	end
end

function uitab.dismiss(name)
	local uim = engine.M.ui
	local winName = "jy_" .. tostring(name)
	local win = uiState._winIds[winName] or uiState._active
	if uim ~= nil and win ~= nil then pcall(uim.close, win) end
	if uiState._active == win then
		uiState._active = nil
		uiState._activeLayout = nil
	end
	uiState._winIds[winName] = nil
end

function uitab.getValue(name)
	local uim = engine.M.ui
	local win = uiState._active
	name = tostring(name)
	if uim ~= nil and win ~= nil then
		local ok, vals = pcall(uim.get, win, name)
		if ok and type(vals) == "table" then
			if uiState.spinners[name] ~= nil then
				-- spinner 以序号输入框呈现
				return tonumber(trim(tostring(vals.text or "0"))) or 0
			end
			if vals.checked ~= nil and (vals.checked == true or vals.checked == false) then
				return vals.checked
			end
			if vals.text ~= nil then return tostring(vals.text) end
		end
	end
	return uiState.values[name]
end

function uitab.saveProfile(path)
	local lay = uiState._activeLayout
	local out = {}
	if lay ~= nil then
		for _, row in ipairs(lay.rows) do
			for _, v in ipairs(row.views) do
				if v.type == "edit" or v.type == "check" or v.type == "spinner" then
					out[v.name] = uitab.getValue(v.name)
				end
			end
		end
	end
	local ok, s = pcall(engine.jsonEncode, out)
	if ok and type(s) == "string" then
		local f = io.open(tostring(path), "w")
		if f ~= nil then
			f:write(s)
			f:close()
		end
	end
end

function uitab.loadProfile(path)
	local f = io.open(tostring(path or ""), "r")
	if f == nil then return end
	local s = f:read("*a")
	f:close()
	local ok, map = pcall(engine.jsonDecode, s)
	if ok and type(map) == "table" then
		uiState.values = map
		local uim = engine.M.ui
		local win = uiState._active
		if uim ~= nil and win ~= nil then
			for vname, val in pairs(map) do
				if type(val) == "boolean" then
					pcall(uim.set, win, vname, { checked = val })
				else
					pcall(uim.set, win, vname, { text = tostring(val) })
				end
			end
		end
	end
end

ui.ui = uitab
ui.uiState = uiState

return ui
