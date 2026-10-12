--[[ frameSdk-autolua2/node.lua
节点域：UiObject 包装（W）、链式选择器工厂（sel）、表式 nodeLib（层级查询 + 操作）。
版权与许可见本包 contract.lua 文件头。

底层映射（相对 frameSdk-firefly 的差异）：
- 引擎直注 UiSelector（链式条件 + findOnce/findAll/findOne 全套）→ 工厂直接原生链式构建，
  不再延迟收集 conds
- UiObject 属性为字段式访问（text/desc/res/clazz/bounds{数组}/parent/...），动作经方法调用
- keepNode/releaseNode/getNodeXml/openAccessibility/isAccServiceOk/findByIndex 为引擎
  NodeLib 直注全局（真实现，非降级）
--]]

local engine = require("frameSdk-autolua2.engine")
local compat = require("frameSdk-autolua2.compat")

local node = {}
local splitStr, trim = compat.splitStr, compat.trim

local function g(name)
	return rawget(_G, name)
end

-- 节点能力守卫：UiSelector/getRootInActiveWindow 未注入时给中文引导错误
local function needNode(where)
	if g("UiSelector") == nil then
		error("节点功能不可用：" .. tostring(where) .. " 需要无障碍节点能力，请先开启无障碍服务（或使用 root 模式）", 3)
	end
end

-- ==================== 节点包装对象（UiObject → W） ====================
-- 懒人 nd 体系期望：node:bounds() 返回 {l,t,r,b} 数组；node:text()/click()/setText/
-- parent()/getChild(i)/scrollXxx()。autolua2 UiObject 属性为字段式（bounds 为 {l,t,r,b}
-- 数组字段），统一由 W 包装为懒人形态。

local W = {}
W.__index = W

local function wrapNode(obj)
	if obj == nil then return nil end
	if type(obj) == "table" and getmetatable(obj) == W then return obj end
	-- userdata（UiObject 为 LuaObjectAdapter userdata）或 table 均可包装
	return setmetatable({ _obj = obj }, W)
end

local function rawObj(nd)
	if nd == nil then return nil end
	if type(nd) == "table" and nd._obj ~= nil then return nd._obj end
	return nd
end

function W:bounds()
	local o = self._obj
	-- autolua2：bounds 为 {l,t,r,b} 数组字段（__index 分发）
	local ok, b = pcall(function() return o.bounds end)
	if ok and type(b) == "table" then
		local l, t, r, bt = b[1], b[2], b[3], b[4]
		if l ~= nil and t ~= nil and r ~= nil and bt ~= nil then
			return { tonumber(l), tonumber(t), tonumber(r), tonumber(bt) }
		end
		-- 兼容 {left=,top=,right=,bottom=} 字段表
		l, t, r, bt = b.left or b[1], b.top or b[2], b.right or b[3], b.bottom or b[4]
		if l ~= nil and t ~= nil and r ~= nil and bt ~= nil then
			return { tonumber(l), tonumber(t), tonumber(r), tonumber(bt) }
		end
	end
	return nil
end

function W:text()
	local o = self._obj
	local ok, r = pcall(function() return o.text end)
	if ok and r ~= nil and type(r) ~= "table" then return tostring(r) end
	return nil
end

function W:desc()
	local o = self._obj
	local ok, r = pcall(function() return o.desc end)
	if ok and r ~= nil and type(r) ~= "table" then return tostring(r) end
	return nil
end

function W:click()
	local ok, r = pcall(function() return self._obj:click() end)
	if ok and r == false then return false end
	return ok
end

function W:longClick()
	local ok, r = pcall(function() return self._obj:longClick() end)
	if ok and r == false then return false end
	return ok
end

function W:setText(t)
	local ok, r = pcall(function() return self._obj:setText(tostring(t or "")) end)
	if ok and r == false then return false end
	return ok
end

function W:parent()
	local ok, p = pcall(function() return self._obj.parent end)
	if not ok or p == nil then return nil end
	return wrapNode(p)
end

function W:getChild(i)
	local ok, c = pcall(function() return self._obj:child(i) end)
	if not ok or c == nil then return nil end
	return wrapNode(c)
end

-- 滚动：autolua2 UiObject 无 scrollXxx 方法 → performAction 无障碍滚动动作。
-- 常量经 luajava bindClass 懒加载（androidx AccessibilityNodeInfoCompat），
-- 绑定失败时退回 Android 框架硬编码值。
local SCROLL_ACTIONS = nil
local function scrollActions()
	if SCROLL_ACTIONS ~= nil then return SCROLL_ACTIONS end
	SCROLL_ACTIONS = { up = 32768, down = 65536, left = 131072, right = 262144, forward = 4096, backward = 8192 }
	local ok, cls = pcall(function()
		return luajava.bindClass("androidx.core.view.accessibility.AccessibilityNodeInfoCompat")
	end)
	if ok and cls ~= nil then
		pcall(function() SCROLL_ACTIONS.up = cls.ACTION_SCROLL_UP end)
		pcall(function() SCROLL_ACTIONS.down = cls.ACTION_SCROLL_DOWN end)
		pcall(function() SCROLL_ACTIONS.left = cls.ACTION_SCROLL_LEFT end)
		pcall(function() SCROLL_ACTIONS.right = cls.ACTION_SCROLL_RIGHT end)
		pcall(function() SCROLL_ACTIONS.forward = cls.ACTION_SCROLL_FORWARD end)
		pcall(function() SCROLL_ACTIONS.backward = cls.ACTION_SCROLL_BACKWARD end)
	end
	return SCROLL_ACTIONS
end

local function scrollByAction(self, action)
	local ok, r = pcall(function() return self._obj:performAction(action) end)
	if ok then return r == true end
	return false
end

function W:scrollUp() return scrollByAction(self, scrollActions().up) end
function W:scrollDown() return scrollByAction(self, scrollActions().down) end
function W:scrollLeft() return scrollByAction(self, scrollActions().left) end
function W:scrollRight() return scrollByAction(self, scrollActions().right) end
function W:scrollForward() return scrollByAction(self, scrollActions().forward) end
function W:scrollBackward() return scrollByAction(self, scrollActions().backward) end

function W:dump()
	-- autolua2 无单节点 dump：getNodeXml 为整树 XML（ndVerify 快照够用）
	local gx = g("getNodeXml")
	if gx ~= nil then
		local ok, s = pcall(gx)
		if ok and type(s) == "string" then return s end
	end
	return ""
end

-- 任意未定义方法转发：W:xxx(...) → UiObject:xxx(...)（indexInParent/sibling/performAction/...）
function W.__index(t, k)
	-- 先走预定义方法/字段
	local v = rawget(W, k)
	if v ~= nil then return v end
	return function(self, ...)
		local o = rawObj(self)
		if o == nil then return nil end
		local ok, r = pcall(o[k], o, ...)
		if ok then
			return wrapNode(r)
		end
		-- 字段式兜底（UiObject __index 属性）
		local ok2, r2 = pcall(function() return o[k] end)
		if ok2 then return r2 end
		return nil
	end
end

-- ==================== UiObjectCollection → Lua 数组 ====================

local function collectionToArr(col)
	local arr = {}
	if col == nil then return arr end
	-- autolua2 root:find / sel:findAll 返回 Lua 数组（UiObject...）
	if type(col) == "table" then
		for _, o in ipairs(col) do arr[#arr + 1] = wrapNode(o) end
		return arr
	end
	-- userdata 集合兜底：size()/get(i)（autoCollection 形态经 toList 展开）
	local ok, list = pcall(function() return col:toList() end)
	if ok and type(list) == "table" then
		for _, o in ipairs(list) do arr[#arr + 1] = wrapNode(o) end
		return arr
	end
	return arr
end

-- ==================== 链式 selector（原生 UiSelector 直转发 + 代理包装） ====================
-- 懒人调用形态：sel = factory(v)；sel[k](sel, v) 链式追加；sel:findOnce(idx)/sel:findAll(idx)
-- 实现策略：首条件经 UiSelector() 原生构建，后续键经 SEL_KEYMAP 映射后链式调用原生，
-- 查询方法包装 W 返回。

local SEL_KEYMAP = {
	id = "id",                    -- autolua2 UiSelector 原生支持 id（→res Pattern）
	className = "clazz",
	packageName = "pkg",
	-- bounds/boundsInside 传表展开 4 参（特殊处理见 applyCond）
}
node.SEL_KEYMAP = SEL_KEYMAP

-- 单条件应用到原生选择器（返回新选择器）
local function applyCond(sel, k, v)
	local m = SEL_KEYMAP[k] or k
	if (m == "bounds" or m == "boundsInside") and type(v) == "table" then
		return sel[m](sel, v[1] or 0, v[2] or 0, v[3] or 0, v[4] or 0)
	end
	if m == "depth" and type(v) == "table" then
		return sel.depth(sel, v[1] or 0, v[2] or v[1] or 0)
	end
	return sel[m](sel, v)
end

local function selectorFindAll(s)
	needNode("findAll")
	local ok, arr = pcall(function() return s._sel:findAll() end)
	if not ok then
		engine.log("findAll 失败（含平台不支持的条件键时属预期）：", tostring(arr))
		return {}
	end
	return collectionToArr(arr)
end

local function selectorFindOnce(s, idx)
	needNode("findOnce")
	local ok, n = pcall(function() return s._sel:findOnce(tonumber(idx) or 0) end)
	if not ok then
		engine.log("findOnce 失败：", tostring(n))
		return nil
	end
	return wrapNode(n)
end

local function newSelector(k, v)
	needNode("选择器工厂")
	local s = {}
	s._sel = applyCond(g("UiSelector")(), k, v)
	function s:findOnce(idx) return selectorFindOnce(self, idx) end
	function s:findAll(idx) return selectorFindAll(self) end
	-- 其余任意键：链式追加条件（sel[k](sel, v)）
	setmetatable(s, {
		__index = function(t, key)
			return function(self, val)
				self._sel = applyCond(self._sel, key, val)
				return self
			end
		end,
	})
	return s
end
node.newSelector = newSelector

-- 链式全局工厂（与 ActionsUtils ND_KEYS 白名单同名，首条件入口）
local SEL_KEYS = {
	"id", "idContains", "idStartsWith", "idEndsWith", "idMatches",
	"text", "textContains", "textStartsWith", "textEndsWith", "textMatches",
	"desc", "descContains", "descStartsWith", "descEndsWith", "descMatches",
	"className", "classNameContains", "classNameStartsWith", "classNameEndsWith", "classNameMatches",
	"packageName", "packageNameContains", "packageNameStartsWith", "packageNameEndsWith", "packageNameMatches",
	"drawingOrder", "depth", "index",
	"visibleToUser", "selected", "clickable", "longClickable", "enabled",
	"password", "scrollable", "checked", "checkable", "focusable", "focused",
	"bounds", "boundsInside",
}
node.SEL_KEYS = SEL_KEYS

-- ==================== 表式 nodeLib（层级查询 + 操作） ====================

local nodeLib = {}

local TAB_KEYMAP = {
	class = "className", id = "id", package = "packageName",
	text = "text", desc = "desc",
	checkable = "checkable", checked = "checked", clickable = "clickable",
	scrollable = "scrollable", longClickable = "longClickable",
	enabled = "enabled", selected = "selected", focusable = "focusable",
}
local STRING_KEYS = { className = true, id = true, packageName = true, text = true, desc = true }
local CONTAINS_MAP = {
	className = "classNameContains", id = "idContains", packageName = "packageNameContains",
	text = "textContains", desc = "descContains",
}

-- 表式条件 → conds（fuzzy=true 时字符串键转 Contains 模糊匹配）
local function tabToConds(tab, fuzzy)
	local conds = {}
	if type(tab) ~= "table" then return conds end
	for k, v in pairs(tab) do
		local m = TAB_KEYMAP[k]
		if m ~= nil then
			if type(v) == "boolean" then
				conds[#conds + 1] = { k = m, v = v }
			elseif STRING_KEYS[m] then
				local key = m
				if fuzzy and CONTAINS_MAP[m] ~= nil then key = CONTAINS_MAP[m] end
				conds[#conds + 1] = { k = key, v = tostring(v) }
			else
				conds[#conds + 1] = { k = m, v = v }
			end
		end
	end
	return conds
end

local function buildSelector(conds)
	local us = g("UiSelector")
	if us == nil then return nil, "UiSelector 不可用（无障碍未开启）" end
	local sel = us()
	local ok, err = pcall(function()
		for _, c in ipairs(conds) do
			sel = applyCond(sel, c.k, c.v)
		end
	end)
	if not ok then return nil, err end
	return sel
end

function nodeLib.isAccServiceOk()
	local fn = g("isAccServiceOk")
	if fn ~= nil then
		local ok, r = pcall(fn)
		if ok then return r == true end
	end
	return false
end

function nodeLib.openAccessibility()
	local fn = g("openAccessibility")
	if fn ~= nil then
		pcall(fn)
		return true
	end
	return false
end

-- 表式单查：nodeLib.findOne(条件表, fuzzy)
function nodeLib.findOne(tab, fuzzy)
	local conds = tabToConds(tab, fuzzy ~= false)
	local sel, err = buildSelector(conds)
	if sel == nil then
		engine.log("nodeLib.findOne 构建选择器失败：", tostring(err))
		return nil
	end
	local ok, n = pcall(function() return sel:findOne() end)
	if ok and n ~= nil then return wrapNode(n) end
	return nil
end

-- 表式全查：nodeLib.findAll(条件表, fuzzy)
function nodeLib.findAll(tab, fuzzy)
	local conds = tabToConds(tab, fuzzy ~= false)
	local sel, err = buildSelector(conds)
	if sel == nil then
		engine.log("nodeLib.findAll 构建选择器失败：", tostring(err))
		return {}
	end
	local ok, arr = pcall(function() return sel:findAll() end)
	if ok then return collectionToArr(arr) end
	return {}
end

-- 容器内查找：nodeLib.findInNode(容器, 条件表, wantAll, fuzzy)
function nodeLib.findInNode(container, subTab, wantAll, fuzzy)
	local obj = rawObj(container)
	if obj == nil then return wantAll and {} or nil end
	local conds = tabToConds(subTab, fuzzy ~= false)
	local sel, err = buildSelector(conds)
	if sel == nil then
		engine.log("nodeLib.findInNode 构建选择器失败：", tostring(err))
		return wantAll and {} or nil
	end
	if wantAll then
		local ok, arr = pcall(function() return obj:find(sel) end)
		if ok then return collectionToArr(arr) end
		return {}
	end
	local ok2, n = pcall(function() return obj:findOne(sel) end)
	if ok2 and n ~= nil then return wrapNode(n) end
	return nil
end

-- 位置路径直达：nodeLib.findByIndex("0|0")（层级|序号，逐层 getChild）
-- 引擎 NodeLib 直注全局 findByIndex 优先（容忍 / 分隔、越界 nil）；降级手写 getChild 链
function nodeLib.findByIndex(path)
	local fn = g("findByIndex")
	if fn ~= nil then
		local ok, n = pcall(fn, tostring(path))
		if ok then return wrapNode(n) end
	end
	local rootFn = g("getRootInActiveWindow")
	if rootFn == nil then return nil end
	local okR, root = pcall(rootFn)
	if not okR or root == nil then return nil end
	local cur = wrapNode(root)
	for _, seg in ipairs(splitStr(tostring(path), "|")) do
		local idx = tonumber(trim(seg))
		if idx == nil or cur == nil then return nil end
		cur = cur:getChild(idx)
	end
	return cur
end

-- 界面快照：引擎 NodeLib 直注真实现（快照缓存，图色/节点同帧），非降级
function nodeLib.keepNode()
	local fn = g("keepNode")
	if fn ~= nil then pcall(fn) end
	return true
end

function nodeLib.releaseNode()
	local fn = g("releaseNode")
	if fn ~= nil then pcall(fn) end
	return true
end

-- 原生点击 / 文本写入（兼容 W 包装对象与原生 UiObject）
function nodeLib.click(nd)
	local obj = rawObj(nd)
	if obj == nil then return false end
	local ok, r = pcall(function() return obj:click() end)
	return ok and r == true
end

function nodeLib.setText(nd, text)
	local obj = rawObj(nd)
	if obj == nil then return false end
	local ok, r = pcall(function() return obj:setText(tostring(text or "")) end)
	return ok and r == true
end

-- 节点树 dump（ndVerify V11 用；引擎直注 getNodeXml([includeInvisible])）
function nodeLib.getNodeXml()
	local fn = g("getNodeXml")
	if fn ~= nil then
		local ok, s = pcall(fn)
		if ok and type(s) == "string" then return s end
	end
	return ""
end

node.nodeLib = nodeLib

return node
