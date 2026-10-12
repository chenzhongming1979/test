--[[ frameSdk-autolua2/touch.lua
触控域：触控原语/按键/输入（ime）/ Path 与 Gesture 无障碍手势兼容对象（D 级降级）。
版权与许可见本包 contract.lua 文件头。
框架传入坐标已是真实屏幕坐标（PageUtils 已换算），全部透传。
底层映射：引擎直注全局 tap/touchDown/touchMove/touchUp/swipe/keyPress + KeyCode 表；
输入走引擎全局 inputText（ASCII→input text，中文→剪贴板+粘贴）与 ime 服务。
--]]

local engine = require("frameSdk-autolua2.engine")
local device = require("frameSdk-autolua2.device")

local touch = {}

local function g(name)
	return rawget(_G, name)
end

-- ==================== 触控原语（引擎直注全局，root 注入通道） ====================

function touch.tap(x, y)
	local fn = g("tap")
	if fn ~= nil then return fn(x, y) end
	engine.log("tap 不可用（触控环境未就绪）")
	return false
end

function touch.touchDown(id, x, y)
	local fn = g("touchDown")
	if fn ~= nil then return fn(id, x, y) end
	return false
end

function touch.touchMove(id, x, y)
	local fn = g("touchMove")
	if fn ~= nil then return fn(id, x, y) end
	return false
end

-- 带延时移动：懒人语义 = 移动后停顿 delay 毫秒
function touch.touchMoveEx(id, x, y, delay)
	touch.touchMove(id, x, y)
	if delay and delay > 0 then device.sleep(delay) end
end

function touch.touchUp(id)
	local fn = g("touchUp")
	if fn ~= nil then return fn(id) end
	return false
end

-- 原版滑动：swipe(x1,y1,x2,y2,time)
function touch.swipe(x1, y1, x2, y2, time)
	local fn = g("swipe")
	if fn ~= nil then return fn(x1, y1, x2, y2, tonumber(time) or 300) end
	return false
end

-- ==================== 按键（keyPress 键名映射 → KeyCode 表 / 键值） ====================

-- 引擎 KeyCode 表键名（懒人键名 → KeyCode.X / Android 键值）
local KEY_MAP = {
	Home = "HOME", BACK = "BACK", Back = "BACK", Recent = "RECENT", RecentApps = "RECENT",
	Menu = "MENU", Power = "POWER", VolumeUp = "VOLUME_UP", VolumeDown = "VOLUME_DOWN",
}
-- Android 键值兜底（3=HOME 4=BACK 187=RECENT 26=POWER 24/25=音量 66=回车 67=退格 61=TAB）
local KEY_CODES = { ENTER = 66, DEL = 67, BACKSPACE = 67, TAB = 61, SPACE = 62,
	ESCAPE = 111, HOME = 3, BACK = 4, MENU = 82, POWER = 26,
	VOLUME_UP = 24, VOLUME_DOWN = 25, RECENT = 187, SEARCH = 84, CAMERA = 27 }

function touch.keyPress(name)
	local fn = g("keyPress")
	if fn == nil then
		engine.log("keyPress 不可用（触控环境未就绪）")
		return false
	end
	local codeName = KEY_MAP[tostring(name)]
	if codeName ~= nil then
		local kc = g("KeyCode")
		if kc ~= nil and kc[codeName] ~= nil then
			local ok = pcall(fn, kc[codeName])
			if ok then return true end
		end
	end
	local code = KEY_CODES[string.upper(tostring(name))]
	if code == nil then code = tonumber(name) end
	if code ~= nil then
		local ok2 = pcall(fn, code)
		if ok2 then return true end
	end
	engine.log("keyPress 不支持的键名：", tostring(name))
	return false
end

-- ==================== Path / Gesture 无障碍手势兼容对象（D 级降级） ====================
-- autolua2 Lua 层无 dispatchGesture 手势 API（触控走 root 注入通道）：
-- 保留 Path/Gesture 构建形态，dispatch 降级为"首尾点 swipe 近似"（单路径），
-- 多路径/带停留时序的复杂手势不支持并告警。

local PathC = {}
PathC.__index = PathC
function PathC:new()
	return setmetatable({ points = {}, sTime = 0, dTime = 0 }, PathC)
end
function PathC:setStartTime(t) self.sTime = tonumber(t) or 0 return self end
function PathC:setDurTime(t) self.dTime = tonumber(t) or 0 return self end
function PathC:addPoint(x, y)
	self.points[#self.points + 1] = { x = x, y = y }
	return self
end

local GestureC = {}
GestureC.__index = GestureC
function GestureC:new()
	return setmetatable({ paths = {} }, GestureC)
end
function GestureC:addPath(path)
	self.paths[#self.paths + 1] = path
	return self
end
function GestureC:dispatch()
	local path = self.paths[1]
	if path == nil or #path.points < 2 then
		engine.log("Gesture:dispatch 无有效路径（至少 2 个点）")
		return false
	end
	if #self.paths > 1 then
		engine.log("Gesture:dispatch 多路径手势不支持（D 级降级），仅执行首路径")
	end
	local first, last = path.points[1], path.points[#path.points]
	local dur = path.dTime and path.dTime > 0 and path.dTime or 300
	return touch.swipe(first.x, first.y, last.x, last.y, dur)
end

-- 工厂函数（懒人形态：Path()/Gesture() 返回类对象）
function touch.Path() return PathC end
function touch.Gesture() return GestureC end

-- ==================== 输入（inputText / imeLib → 引擎全局 inputText / ime 服务） ====================

function touch.inputText(t)
	local fn = g("inputText")
	if fn == nil then
		engine.log("inputText 不可用")
		return false
	end
	local ok, r = pcall(fn, tostring(t or ""))
	return ok and r ~= false
end

local imeLib = {}
function imeLib.deleteChar()
	-- 懒人语义：循环删除输入框字符；引擎 ime.clearField 一次清空等效
	local ime = engine.M.ime
	if ime ~= nil and ime.clearField ~= nil then pcall(ime.clearField) end
end
function imeLib.setText(t)
	local ime = engine.M.ime
	if ime ~= nil and ime.setText ~= nil then
		pcall(ime.setText, tostring(t or ""))
		return
	end
	touch.inputText(t)
end
function imeLib.finishInput()
	local ime = engine.M.ime
	if ime ~= nil and ime.hideKeyboard ~= nil then
		pcall(ime.hideKeyboard)
	end
end
touch.imeLib = imeLib

return touch
