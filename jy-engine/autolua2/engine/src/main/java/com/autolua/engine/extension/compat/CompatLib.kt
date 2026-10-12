package com.autolua.engine.extension.compat

import com.autolua.engine.base.LuaContext
import com.autolua.engine.base.LuaEnvContributor

/**
 * Lua 兼容层：向 Lua 环境注入商业引擎（懒人精灵/派大星）风格的 API 薄封装。
 *
 * 内容（compat.lua 内嵌字符串，contribute 时 loadBuffer + pcall 执行）：
 * - 字符串：splitStr、utf8 扩展（length/sub/left/right/mid/inStr/inStrRev/strReverse/strCut/gsub）
 * - 数组：table.contains / table.indexOf
 * - 数学：math 扩展（clamp/round/lerp/sign/isEven/isOdd/inRange/isInteger/init）
 * - 随机：frandom 模块（uuid/hex/string/shuffle/weighted/...）
 * - 时间：systemTime/tickCount/getNetWorkTime 全局 + time 模块（timestamp/format/parse/…）
 * - 图色兼容：findMultiColor/findMultiColorEx（→findFeature 特征串拼接）、findPic/findPicFast/findPicEx
 *   （→findImage）、cmpColor/getPixelColor/getColorNum/keepCapture/releaseCapture/snapShot 别名、
 *   colorToRGB/colorDiff 纯 Lua 实现
 * - 定时/线程：setTimer（thread 协程驱动）、beginThread/newThread（→thread.start）
 * - 应用/网络全局别名：httpGet/httpPost/downloadFile
 * - 派大星 auto.* 全局工厂（选择器条件 + 查找 + 按键）
 *
 * 依赖顺序：必须在 http/file/crypt/device/app/luajava 服务之后注册（compat 引用它们）。
 */
open class CompatLib : LuaEnvContributor {

  override fun contribute(ctx: LuaContext) {
    try {
      ctx.loadBuffer(COMPAT_LUA.toByteArray(Charsets.UTF_8), "=compat", com.autolua.engine.base.LuaContext.CodeMode.TEXT)
      val r = ctx.pcall(0, 0, 0)
      if (r != 0) {
        val msg = try { ctx.toString(-1) } catch (t: Throwable) { "pcall code=$r" }
        android.util.Log.w("CompatLib", "compat.lua exec failed: $msg")
        ctx.pop(1)
      }
    } catch (t: Throwable) {
      android.util.Log.w("CompatLib", "compat.lua load failed: $t")
    }
  }

  companion object {
    private val COMPAT_LUA = """
-- ============ JY-Frame 商业引擎兼容层（懒人/派大星 API 风格） ============
-- 由 CompatLib 注入；依赖全局：UiSelector getRootInActiveWindow tap swipe keyPress
-- sleep thread http file crypt luajava

-- ---------------- 字符串 ----------------
-- splitStr(str [, sep])：按分隔符分割（默认逗号），返回数组
function splitStr(str, sep)
    if str == nil then return {} end
    sep = sep or ","
    local result = {}
    local pattern = "([^" .. sep .. "]*)" .. sep .. "?"
    for token in string.gmatch(str .. "", pattern) do
        result[#result + 1] = token
    end
    return result
end

-- utf8 扩展（懒人风格；基于标准 utf8.offset/charpattern 组合）
utf8 = utf8 or {}
local u8p = utf8.charpattern

function utf8.length(s)
    return utf8.len(s)
end

-- 按字符位置取子串（1 起始，含两端；负数从尾部计）
local function charPos(s, i)
    if i < 0 then i = utf8.len(s) + i + 1 end
    if i < 1 then return 1 end
    local p = utf8.offset(s, i)
    return p or (#s + 1)
end

function utf8.sub(s, i, j)
    s = tostring(s)
    local len = utf8.len(s) or 0
    if i < 0 then i = len + i + 1 end
    if j == nil then j = len end
    if j < 0 then j = len + j + 1 end
    if i < 1 then i = 1 end
    if i > len or j < i then return "" end
    local si = charPos(s, i)
    local sj = charPos(s, j + 1)
    return string.sub(s, si, sj - 1)
end

function utf8.left(s, n)
    return utf8.sub(s, 1, n)
end

function utf8.right(s, n)
    local len = utf8.len(s) or 0
    return utf8.sub(s, len - n + 1, len)
end

function utf8.mid(s, i, j)
    return utf8.sub(s, i, j)
end

function utf8.inStr(s, sub, from)
    s = tostring(s)
    local startByte = charPos(s, from or 1)
    local byteIdx = string.find(s, sub, startByte, true)
    if not byteIdx then return 0 end
    -- 目标字符之前的完整字符数 + 1（byteIdx-1 保证不含目标字符的首字节）
    local before = s:sub(1, byteIdx - 1)
    return (utf8.len(before) or 0) + 1
end

function utf8.inStrRev(s, sub)
    s = tostring(s)
    local pos = 0
    local idx = 0
    while true do
        local p = string.find(s, sub, pos + 1, true)
        if not p then break end
        idx = p
        pos = p
    end
    if idx == 0 then return 0 end
    return (utf8.len(s:sub(1, idx - 1)) or 0) + 1
end

function utf8.strReverse(s)
    s = tostring(s)
    local t = {}
    for ch in s:gmatch(u8p) do t[#t + 1] = ch end
    for i = 1, math.floor(#t / 2) do
        t[i], t[#t - i + 1] = t[#t - i + 1], t[i]
    end
    return table.concat(t)
end

function utf8.strCut(s, pos)
    return utf8.sub(s, pos, pos)
end

-- 按字符 gsub（对每个字符应用回调，返回替换后的串）
function utf8.gsub(s, repl)
    s = tostring(s)
    local out = {}
    for ch in s:gmatch(u8p) do
        out[#out + 1] = repl(ch) or ch
    end
    -- 残余非 UTF8 字节
    local consumed = table.concat(out)
    return consumed
end

-- ---------------- 数组工具 ----------------
function table.contains(t, v)
    for _, x in ipairs(t) do if x == v then return true end end
    return false
end

function table.indexOf(t, v)
    for i, x in ipairs(t) do if x == v then return i end end
    return -1
end

-- ---------------- 数学扩展 ----------------
math.clamp = function(v, lo, hi) if v < lo then return lo elseif v > hi then return hi end return v end
math.round = function(v) return math.floor(v + 0.5) end
math.lerp = function(a, b, t) return a + (b - a) * t end
math.sign = function(v) if v > 0 then return 1 elseif v < 0 then return -1 end return 0 end
math.isEven = function(v) return v % 2 == 0 end
math.isOdd = function(v) return v % 2 ~= 0 end
math.inRange = function(v, lo, hi) return v >= lo and v <= hi end
math.isInteger = function(v) return math.tointeger(v) ~= nil end

-- ---------------- 随机扩展 frandom ----------------
frandom = {}
function frandom.seed(s) math.randomseed(s or os.time()) end
function frandom.int(lo, hi) return math.random(lo, hi) end
function frandom.float(lo, hi) return lo + math.random() * (hi - lo) end
function frandom.boolean(p) return math.random() < (p or 0.5) end
function frandom.pick(list) return list[math.random(#list)] end
function frandom.chance(p) return math.random() < (p or 0.5) end
function frandom.floatRange(lo, hi) return lo + math.random() * (hi - lo) end
function frandom.hex(n)
    n = n or 8
    local out = {}
    local chars = "0123456789abcdef"
    for _ = 1, n do
        local i = math.random(1, 16)
        out[#out + 1] = chars:sub(i, i)
    end
    return table.concat(out)
end
function frandom.string(n, charset)
    n = n or 8
    charset = charset or "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    local out = {}
    for _ = 1, n do
        local i = math.random(1, #charset)
        out[#out + 1] = charset:sub(i, i)
    end
    return table.concat(out)
end
function frandom.uuid()
    local tmpl = "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx"
    return tmpl:gsub("x", function() return frandom.hex(1) end)
               :gsub("y", function() return string.char(("89ab"):byte(math.random(1, 4))) end)
end
function frandom.shuffle(list)
    for i = #list, 2, -1 do
        local j = math.random(i)
        list[i], list[j] = list[j], list[i]
    end
    return list
end
function frandom.weighted(items)
    -- items = { {value=..., weight=...}, ... } 或 { {v,w}, ... }
    local total = 0
    for _, it in ipairs(items) do
        local w = it.weight or it[2] or 1
        total = total + w
    end
    local r = math.random() * total
    for _, it in ipairs(items) do
        local w = it.weight or it[2] or 1
        r = r - w
        if r <= 0 then return it.value or it[1] end
    end
    return items[#items] and (items[#items].value or items[#items][1])
end
rnd = frandom.int  -- 懒人 rnd(begin,end) 别名

-- ---------------- 图色增强补齐（懒人 color 周边 / 派大星 images 对齐） ----------------
-- 多点找色全部位置（懒人 findMultiColorAll 签名）；C++ findFeatureAll 可用时走原生，
-- 否则（沙箱远程图色）降级为单点结果
if findFeatureAll then
function findMultiColorAll(x1, y1, x2, y2, firstColor, offsetColorStr, sim, order)
    local feature = "0|0|" .. firstColor
    if offsetColorStr and offsetColorStr ~= "" then
        feature = feature .. "," .. offsetColorStr
    end
    return findFeatureAll(x1, y1, x2, y2, feature, sim, order or 1)
end
else
function findMultiColorAll(x1, y1, x2, y2, firstColor, offsetColorStr, sim, order)
    local x, y = findMultiColor(x1, y1, x2, y2, firstColor, offsetColorStr, sim, order)
    if x and x >= 0 then return { { x, y } } end
    return {}
end
end

findMultiColorAllT = findMultiColorAll
findColorAllPointT = findColorAllPoint

-- 区域截图（懒人 snapShot(path,x,y,w,h)；无区域参数时整屏）
-- saveScreen(x1,y1) 为 exclusive 端点（右/下边界不含），w,h 直接偏移
function snapShot(path, x, y, w, h)
    if x and y and w and h and w > 0 and h > 0 then
        return saveScreen(x, y, x + w, y + h, path)
    end
    return saveScreen(-1, -1, -1, -1, path)
end

-- 屏幕缩放适配：脚本按虚拟分辨率坐标编写，引擎换算实际坐标（触控 + 图色，懒人 setScreenScale）
_screenScale = 1
function setScreenScale(scale)
    if type(scale) ~= "number" or scale <= 0 then return false end
    _screenScale = scale
    return true
end

function getScreenScale() return _screenScale end

local function _sx(v) return math.floor(v * _screenScale + 0.5) end

-- 触控坐标缩放
local _oTap, _oSwipe, _oTouchDown, _oTouchMove, _oLongTap =
    tap, swipe, touchDown, touchMove, longTap
tap = function(x, y) return _oTap(_sx(x), _sx(y)) end
swipe = function(x1, y1, x2, y2, dur)
    return _oSwipe(_sx(x1), _sx(y1), _sx(x2), _sx(y2), dur)
end
touchDown = function(id, x, y) return _oTouchDown(id, _sx(x), _sx(y)) end
touchMove = function(id, x, y, steps)
    return _oTouchMove(id, _sx(x), _sx(y), steps)
end
longTap = function(x, y, dur) return _oLongTap(_sx(x), _sx(y), dur) end

-- 图色坐标缩放（返回坐标的函数结果反算回虚拟坐标）
local _oGetColor, _oIsColor, _oFindColor, _oFindFeature, _oIsFeature, _oGetColorCount, _oFindImage, _oIsImage =
    getColor, isColor, findColor, findFeature, isFeature, getColorCount, findImage, isImage

getColor = function(x, y) return _oGetColor(_sx(x), _sx(y)) end

isColor = function(x, y, color, sim)
    return _oIsColor(_sx(x), _sx(y), color, sim)
end

-- cmpColor 经全局 isColor 转发：isColor 由图色域注册，捕获时机随注入顺序可能为空，
-- 走全局符号永远有效（isColor 原生签名与 cmpColor 一致，仅返回布尔）
cmpColor = function(x, y, color, sim)
    return isColor(_sx(x), _sx(y), color, sim)
end

findColor = function(x1, y1, x2, y2, color, sim, order)
    local rx, ry = _oFindColor(_sx(x1), _sx(y1), _sx(x2), _sx(y2), color, sim, order)
    if rx and rx >= 0 then
        return math.floor(rx / _screenScale + 0.5), math.floor(ry / _screenScale + 0.5)
    end
    return rx, ry
end

findFeature = function(x1, y1, x2, y2, feature, sim, order)
    local rx, ry = _oFindFeature(_sx(x1), _sx(y1), _sx(x2), _sx(y2), feature, sim, order)
    if rx and rx >= 0 then
        return math.floor(rx / _screenScale + 0.5), math.floor(ry / _screenScale + 0.5)
    end
    return rx, ry
end

-- 懒人语义 isFeature(x,y,feature,sim)：特征坐标为相对基点 (x,y) 的偏移；
-- 原生实现按屏幕绝对坐标比对（签名 isFeature(feature,sim)），
-- 这里把各点坐标平移到基点后交给原生。点格式 dx|dy|color（颜色部分原样保留）
local function _shiftFeature(feature, bx, by)
    local out = {}
    for pt in string.gmatch(feature or "", "[^,]+") do
        local dx, dy, rest = string.match(pt, "^(-?%d+)|(-?%d+)|(.+)$")
        if dx then
            out[#out + 1] = (bx + dx) .. "|" .. (by + dy) .. "|" .. rest
        else
            out[#out + 1] = pt
        end
    end
    return table.concat(out, ",")
end

isFeature = function(x, y, feature, sim)
    return _oIsFeature(_shiftFeature(feature, _sx(x), _sx(y)), sim)
end

getColorCount = function(x1, y1, x2, y2, color, sim)
    return _oGetColorCount(_sx(x1), _sx(y1), _sx(x2), _sx(y2), color, sim)
end

findImage = function(x1, y1, x2, y2, image, sim, order)
    local rx, ry, idx = _oFindImage(_sx(x1), _sx(y1), _sx(x2), _sx(y2), image, sim, order)
    if rx and rx >= 0 then
        return math.floor(rx / _screenScale + 0.5), math.floor(ry / _screenScale + 0.5), idx
    end
    return rx, ry, idx
end

isImage = function(x, y, image, sim)
    return _oIsImage(_sx(x), _sx(y), image, sim)
end

-- 全部匹配查找同样纳入缩放（入参区域 ×scale，出参点反算回虚拟坐标）
if findColorAllPoint then
local _oFindColorAllPoint = findColorAllPoint
findColorAllPoint = function(x1, y1, x2, y2, color, sim, order)
    local pts = _oFindColorAllPoint(_sx(x1), _sx(y1), _sx(x2), _sx(y2), color, sim, order)
    for _, p in ipairs(pts) do
        p[1] = math.floor(p[1] / _screenScale + 0.5)
        p[2] = math.floor(p[2] / _screenScale + 0.5)
    end
    return pts
end
findColorAllPointT = findColorAllPoint
end

if findFeatureAll then
local _oFindFeatureAll = findFeatureAll
findFeatureAll = function(x1, y1, x2, y2, feature, sim, order)
    local pts = _oFindFeatureAll(_sx(x1), _sx(y1), _sx(x2), _sx(y2), feature, sim, order)
    for _, p in ipairs(pts) do
        p[1] = math.floor(p[1] / _screenScale + 0.5)
        p[2] = math.floor(p[2] / _screenScale + 0.5)
    end
    return pts
end
end

-- captureScreen 模块（派大星风格薄包装；引擎自动管理截图生命周期）
captureScreen = {}
function captureScreen.init() updateScreen() return true end
function captureScreen.captureScreen(path)
    updateScreen()
    if path then return snapShot(path) end
    return true
end
function captureScreen.stop() return true end
function captureScreen.isInitialized() return getBaseSize ~= nil end
function captureScreen.getScreenWidth() local w = getBaseSize() return w end
function captureScreen.getScreenHeight() local _, h = getBaseSize() return h end
function captureScreen.getStatus() return "running" end
function captureScreen.keepScreen() keepScreen() end
function captureScreen.releaseScreen() updateScreen() end

-- COMPAT_PART2

-- ---------------- 时间 ----------------
-- systemTime：系统墙钟毫秒（经 luajava 反射 System.currentTimeMillis）
_systemClass = luajava and luajava.bindClass and luajava.bindClass("java.lang.System")
_systemClockClass = luajava and luajava.bindClass and luajava.bindClass("android.os.SystemClock")

function systemTime()
    if _systemClass then return math.floor(_systemClass:currentTimeMillis()) end
    return os.time() * 1000
end

function tickCount()
    if _systemClockClass then return math.floor(_systemClockClass:elapsedRealtime()) end
    return math.floor(os.clock() * 1000)
end

-- 网络时间（HTTP Date 头解析，返回 "YYYY-MM-DD HH:MM:SS"）
function getNetWorkTime()
    local _, _, headers = http.request{ url = "http://www.baidu.com", method = "GET", timeout = 5000 }
    local date = headers and (headers["Date"] or headers["date"])
    if not date then return nil end
    local months = { Jan=1, Feb=2, Mar=3, Apr=4, May=5, Jun=6, Jul=7, Aug=8, Sep=9, Oct=10, Nov=11, Dec=12 }
    local _, _, d, mon, y, h, mi, s =
        string.find(date, "(%d+)%s+(%a+)%s+(%d+)%s+(%d+):(%d+):(%d+)")
    if not y then return nil end
    local m = months[mon] or 1
    return string.format("%04d-%02d-%02d %02d:%02d:%02d", tonumber(y), m, tonumber(d), tonumber(h), tonumber(mi), tonumber(s))
end

-- ---------------- 剪贴板别名（派大星 clipboard.get/set；懒人 readPasteboard/writePasteboard 由 DeviceLib 注册） ----------------
clipboard = { get = readPasteboard, set = writePasteboard }

-- ---------------- 脚本控制流 ----------------
-- 运行环境：root 引擎进程 uid=0 / 沙箱（探测一次缓存）
_rootEnv = nil
function getRunEnvType()
    if _rootEnv == nil then
        local out = execOut and execOut("id -u") or ""
        _rootEnv = (out:find("^0") ~= nil) and "root" or "sandbox"
    end
    return _rootEnv
end

function checkIsDebug() return false end

-- 脚本停止回调：引擎 loop 在脚本结束（含报错）后调用全局 OnStop
-- setStopCallBack 单播（懒人兼容）；event.onStopEvent 多播（派大星）；OnStop 桥同时转发两者
_onStopUser = nil
function setStopCallBack(cb) _onStopUser = cb end
function getStopCallBack() return _onStopUser end
function OnStop()
    if _onStopUser then pcall(_onStopUser) end
    if event and event._emit then pcall(event._emit, "stop") end
end

-- time 模块（派大星风格）
time = {}
function time.unix() return os.time() end
function time.timestamp() return systemTime() end
function time.millisecond() return systemTime() % 1000 end
function time.now() return os.date("*t") end
function time.year() return os.date("*t").year end
function time.month() return os.date("*t").month end
function time.day() return os.date("*t").day end
function time.hour() return os.date("*t").hour end
function time.minute() return os.date("*t").min end
function time.second() return os.date("*t").sec end
function time.weekday() return os.date("*t").wday end
function time.utc() return os.date("!*t") end
function time.format(t, fmt)
    fmt = fmt or "%Y-%m-%d %H:%M:%S"
    if type(t) == "table" then return os.date(fmt, os.time(t)) end
    return os.date(fmt, t or os.time())
end
function time.parse(str, fmt)
    -- 支持 "YYYY-MM-DD HH:MM:SS" / "YYYY-MM-DD"
    local y, m, d, h, mi, s = string.match(str or "", "(%d+)[-%/](%d+)[-%/](%d+)%s*(%d*):?(%d*):?(%d*)")
    if not y then return nil end
    return os.time({ year = tonumber(y), month = tonumber(m), day = tonumber(d),
        hour = tonumber(h) or 0, min = tonumber(mi) or 0, sec = tonumber(s) or 0 })
end
function time.diff(t1, t2) return t1 - t2 end
function time.addDays(t, n) return t + n * 86400 end
function time.addHours(t, n) return t + n * 3600 end
function time.addMinutes(t, n) return t + n * 60 end
function time.addSeconds(t, n) return t + n end
function time.isLeapYear(y)
    return (y % 4 == 0 and y % 100 ~= 0) or (y % 400 == 0)
end
function time.daysInMonth(y, m)
    local days = { 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31 }
    if m == 2 and time.isLeapYear(y) then return 29 end
    return days[m]
end

-- ---------------- 定时与线程别名 ----------------
-- setTimer(func, ms, ...)：后台延时执行（thread 协程驱动，不阻塞主脚本）
function setTimer(func, ms, ...)
    local args = { ... }
    return thread.start(function()
        thread.sleep(ms or 0)
        func(table.unpack(args))
    end)
end

beginThread = thread.start
newThread = thread.start
th = th or {}
th.stopThread = thread.stop

-- ---------------- 图色兼容（懒人签名适配） ----------------
-- 原生全局图色签名与懒人同构：findColor(x1,y1,x2,y2,color,sim[,order]) / findImage(...)
-- findImage 的图路径参数支持逗号分隔多路径（findPicFast 多图轮询语义）

-- findMultiColor(x1,y1,x2,y2, firstColor, offsetColorStr, sim, order)
-- → findFeature 特征串拼接：首点 0|0 偏移 + 偏移点列表 "x|y|色串,..."
function findMultiColor(x1, y1, x2, y2, firstColor, offsetColorStr, sim, order)
    local feature = "0|0|" .. firstColor
    if offsetColorStr and offsetColorStr ~= "" then
        feature = feature .. "," .. offsetColorStr
    end
    return findFeature(x1, y1, x2, y2, feature, sim, order or 1)
end

findMultiColorEx = findMultiColor
findMultiColorT = findMultiColor

-- findPic / findPicEx / findPicFast / findImageEx → findImage（路径参数）
findPic = findImage
findPicEx = findImage
findPicFast = findImage
findImageEx = findImage

-- findPicAllPoint / findPicAll(x1,y1,x2,y2, pngNames, sim, order) → findImageAll（模板全匹配，
-- 返回 {{x,y},...}）；无原生 findImageAll 时降级 findImage 单点包 table
if findImageAll then
    function findPicAllPoint(x1, y1, x2, y2, pngNames, sim, order)
        return findImageAll(x1, y1, x2, y2, pngNames, sim, order or 1)
    end
else
    function findPicAllPoint(x1, y1, x2, y2, pngNames, sim, order)
        local x, y = findImage(x1, y1, x2, y2, pngNames, sim, order)
        if x and x >= 0 then return { { x, y } } end
        return {}
    end
end
findPicAll = findPicAllPoint

-- cmpColor(x, y, colorStr, sim) → isColor（懒人坐标在前，签名同构）
cmpColor = isColor
cmpColorEx = isFeature  -- 多点比色：featureStr 全相对一点，以 isFeature 判定
getPixelColor = getColor
getColorNum = getColorCount

-- keepCapture / releaseCapture（snapShot 区域版见上文图色增强段）
keepCapture = keepScreen
releaseCapture = updateScreen

-- colorToRGB(color) -> r, g, b
function colorToRGB(color)
    color = tonumber(color) or 0
    return math.floor(color / 0x10000) % 0x100, math.floor(color / 0x100) % 0x100, color % 0x100
end

-- colorDiff(c1, c2)：最大分量差（0~255）
function colorDiff(c1, c2)
    local r1, g1, b1 = colorToRGB(c1)
    local r2, g2, b2 = colorToRGB(c2)
    return math.max(math.abs(r1 - r2), math.abs(g1 - g2), math.abs(b1 - b2))
end

-- ---------------- 网络全局别名（懒人） ----------------
function httpGet(url)
    local body, code = http.get(url)
    return body, code
end

function httpPost(url, bodyData)
    local b, code = http.post(url, bodyData or "")
    return b, code
end

function downloadFile(url, path)
    local data, code, _ = http.request{ url = url, method = "GET", binary = true, timeout = 60000 }
    if code ~= 200 or not data then return false, code end
    local f = io.open(path, "wb")
    if not f then return false, code end
    f:write(data)
    f:close()
    return true, code
end

-- ---------------- 节点全局工厂（懒人/派大星风格） ----------------
-- text("x") / idContains("y") / ... → UiSelector 链式条件；查找用 getRootInActiveWindow():findOne(sel)

-- 节点能力守卫（P1.8）：沙箱模式无障碍服务未连接时，节点全局（UiSelector/getRootInActiveWindow）
-- 未注册，直接调用只会得到 "attempt to call a nil value" 裸错；此处统一转中文提示。
-- level 3：穿透工厂函数自身，错误位置指向脚本实际调用行
local function _needNode(where)
    if UiSelector == nil or getRootInActiveWindow == nil then
        error("节点功能不可用：" .. tostring(where) .. " 需要无障碍节点能力，请先在系统设置中开启无障碍服务（或使用 root 模式）", 3)
    end
end

local function _sel()
    _needNode("选择器工厂")
    return UiSelector()
end

-- 懒人全局工厂
function id(v) return _sel():res(v) end
function idContains(v) return _sel():idContains(v) end
function idStartsWith(v) return _sel():idStartsWith(v) end
function idEndsWith(v) return _sel():idEndsWith(v) end
function idMatches(v) return _sel():resMatches(v) end
function text(v) return _sel():text(v) end
function textContains(v) return _sel():textContains(v) end
function textStartsWith(v) return _sel():textStartsWith(v) end
function textEndsWith(v) return _sel():textEndsWith(v) end
function textMatches(v) return _sel():textMatches(v) end
function desc(v) return _sel():desc(v) end
function descContains(v) return _sel():descContains(v) end
function descStartsWith(v) return _sel():descStartsWith(v) end
function descEndsWith(v) return _sel():descEndsWith(v) end
function descMatches(v) return _sel():descMatches(v) end
function className(v) return _sel():clazz(v) end
function classNameContains(v) return _sel():clazzContains(v) end
function classNameStartsWith(v) return _sel():clazzStartsWith(v) end
function classNameEndsWith(v) return _sel():clazzEndsWith(v) end
function classNameMatches(v) return _sel():clazzMatches(v) end
function packageName(v) return _sel():pkg(v) end
function packageNameMatches(v) return _sel():pkgMatches(v) end
function clickable(v) return _sel():clickable(v) end
function scrollable(v) return _sel():scrollable(v) end
function enabled(v) return _sel():enabled(v) end
function depthSel(minD, maxD) return _sel():depth(minD, maxD) end

-- 条件工厂补齐（对齐懒人全局工厂全集）
function checkable(v) return _sel():checkable(v) end
function checked(v) return _sel():checked(v) end
function focusable(v) return _sel():focusable(v) end
function focused(v) return _sel():focused(v) end
function longClickable(v) return _sel():longClickable(v) end
function selected(v) return _sel():selected(v) end
function editable(v) return _sel():editable(v) end
function visibleToUser(v) return _sel():visibleToUser(v) end
function password(v) return _sel():password(v) end
function drawingOrderSel(i) return _sel():drawingOrder(i) end

-- ---------------- 文本输入（懒人 inputText） ----------------
-- 全 ASCII：root `input text`（无需焦点）；含非 ASCII：写剪贴板后对焦点/可见可编辑节点粘贴
-- （粘贴路径需要无障碍在线；懒人 imeLib 体系此处以剪贴板+paste 近似）
local function _inputPasteTarget()
    local root = getRootInActiveWindow()
    if not root then return nil end
    local n = root:findOne(_sel():focused(true):editable(true))
    if n ~= nil then return n end
    return root:findOne(_sel():editable(true):visibleToUser(true))
end

function inputText(str)
    if str == nil then return false end
    str = tostring(str)
    if str:find("[^\32-\126]") == nil then
        -- ASCII：单引号包裹（shell 转义内嵌单引号），exitCode 判定成败
        local _, code = exec("input text '" .. str:gsub("'", "'\\''") .. "'")
        return code == 0
    end
    if writePasteboard == nil or getRootInActiveWindow == nil then return false end
    if not writePasteboard(str) then return false end
    local n = _inputPasteTarget()
    if n == nil then return false end
    local ok, res = pcall(function() return n:paste() end)
    return ok and res == true
end

-- ---------------- Toast（懒人 toast/hideToast） ----------------
-- 三级降级：沙箱 UI 服务 → app Context（LuaEngine:getContext）→ root system Context 反射；
-- 前线程无 Looper 时先 Looper.prepare（Toast.show 需要）
_lastToast = nil
function toast(msg, dur)
    msg = tostring(msg or "")
    if UI ~= nil then
        local ok = pcall(function() UI:showToast(msg, dur or 0) end)
        if not ok then ok = pcall(function() UI.showToast(msg, dur or 0) end) end
        if ok then return true end
    end
    local ok, t = pcall(function()
        local Toast = luajava.bindClass("android.widget.Toast")
        local Looper = luajava.bindClass("android.os.Looper")
        if Looper:myLooper() == nil then
            pcall(function() Looper:prepare() end)
        end
        local ctx = (LuaEngine ~= nil) and LuaEngine:getContext() or nil
        if ctx == nil then
            local AT = luajava.bindClass("android.app.ActivityThread")
            local sysMain = AT:systemMain()
            ctx = sysMain and sysMain:getSystemContext() or nil
        end
        if ctx == nil then error("no context for toast") end
        local style = Toast.LENGTH_SHORT
        if type(dur) == "number" and dur > 2000 then style = Toast.LENGTH_LONG end
        local t = Toast.makeText(ctx, msg, style)
        t:show()
        return t
    end)
    if ok and t ~= nil then
        _lastToast = t
        return true
    end
    return false
end

function hideToast()
    local t = _lastToast
    _lastToast = nil
    if t == nil then return true end
    return pcall(function() t:cancel() end)
end

-- ---------------- 派大星 auto.* 工厂 ----------------
-- 注：keepNode/releaseNode/updateNode/getNodeXml/openAccessibility/isAccServiceOk
--     为 Java 全局函数（引擎侧 NodeLib 直注，不在 compat 内）
auto = {}

auto.selector = function()
    _needNode("auto.selector")
    return UiSelector()
end
auto.getRoot = function()
    _needNode("auto.getRoot")
    return getRootInActiveWindow()
end
auto.getScreenSize = function()
    local w, h = getBaseSize()
    return w, h
end

-- 条件工厂（返回 UiSelector，可继续链式）
auto.text = function(v) return _sel():text(v) end
auto.textContains = function(v) return _sel():textContains(v) end
auto.textStartsWith = function(v) return _sel():textStartsWith(v) end
auto.textEndsWith = function(v) return _sel():textEndsWith(v) end
auto.textMatches = function(v) return _sel():textMatches(v) end
auto.id = function(v) return _sel():res(v) end
auto.idContains = function(v) return _sel():idContains(v) end
auto.idStartsWith = function(v) return _sel():idStartsWith(v) end
auto.idEndsWith = function(v) return _sel():idEndsWith(v) end
auto.idMatches = function(v) return _sel():resMatches(v) end
auto.desc = function(v) return _sel():desc(v) end
auto.descContains = function(v) return _sel():descContains(v) end
auto.descStartsWith = function(v) return _sel():descStartsWith(v) end
auto.descEndsWith = function(v) return _sel():descEndsWith(v) end
auto.descMatches = function(v) return _sel():descMatches(v) end
auto.className = function(v) return _sel():clazz(v) end
auto.classNameContains = function(v) return _sel():clazzContains(v) end
auto.packageName = function(v) return _sel():pkg(v) end
auto.clickable = function(v) return _sel():clickable(v) end
auto.enabled = function(v) return _sel():enabled(v) end
auto.scrollable = function(v) return _sel():scrollable(v) end
auto.depth = function(a, b) return _sel():depth(a, b) end
auto.visibleToUser = function(v) return _sel():visibleToUser(v) end
auto.editable = function(v) return _sel():editable(v) end
auto.bounds = function(l, t, r, b) return _sel():bounds(l, t, r, b) end
auto.boundsInside = function(l, t, r, b) return _sel():boundsInside(l, t, r, b) end

-- 查找（在当前活动窗口上执行）
auto.find = function(sel) return auto.getRoot():find(sel) end
auto.findOne = function(sel) return auto.getRoot():findOne(sel) end
-- auto.waitFor(sel, timeout)：等待出现，超时返回 nil（默认 10s；走 sel:findOne(timeout) 引擎侧轮询）
auto.waitFor = function(sel, timeout)
    if sel == nil then return nil end
    return sel:findOne(timeout or 10000)
end
auto.exists = function(sel) return auto.getRoot():findOne(sel) ~= nil end

-- 懒人语义全局 waitFor(sel, timeout)：等待选择器匹配节点出现，超时返回 nil（默认 10s）
function waitFor(sel, timeout)
    if sel == nil then return nil end
    return sel:findOne(timeout or 10000)
end

-- 全局动作
auto.click = function(x, y) return tap(x, y) end
auto.swipe = function(x1, y1, x2, y2, dur) return swipe(x1, y1, x2, y2, dur or 500) end
auto.longClick = function(x, y, dur) return longTap(x, y, dur or 800) end
auto.back = function() return keyPress(KeyCode.BACK) end
auto.home = function() return keyPress(KeyCode.HOME) end
auto.recents = function() return keyPress(KeyCode.RECENT) end
auto.touchDown = function(id, x, y) return touchDown(id, x, y) end
auto.touchMove = function(id, x, y, steps) return touchMove(id, x, y, steps) end
auto.touchUp = function(id) return touchUp(id) end
auto.press = function(key, ms) return keyPress(key, ms) end

-- 等待轮询（阻塞直到非空，无限等待，慎用）：getRoot 可能 nil（无障碍未就绪），判空持续等待；
-- find 用 pcall 隔离节点树失效异常（树刷新瞬间 find 可能抛错，不应中断轮询）
auto.untilFind = function(sel)
    while true do
        local root = auto.getRoot()
        if root then
            local ok, list = pcall(root.find, root, sel)
            if ok and type(list) == "table" and #list > 0 then return list end
        end
        sleep(50)
    end
end

-- auto.untilFindOne(sel)：同 untilFind 轮询，返回首个节点（无限等待，慎用）
auto.untilFindOne = function(sel)
    local list = auto.untilFind(sel)
    return list and list[1] or nil
end

-- ---------------- 派大星 autoCollection（UiObjectCollection 批量操作，33 项） ----------------
-- autoCollection.create(nodeArray) 由节点数组构建集合；autoCollection.find(selector) 全量查找
autoCollection = {}
local _colMeta = {}

-- 容器：isEmpty/nonEmpty/size/count/get/first/last/toList/toArray/each/contains
function _colMeta.isEmpty(c) return #c.__nodes == 0 end
function _colMeta.nonEmpty(c) return #c.__nodes > 0 end
function _colMeta.size(c) return #c.__nodes end
_colMeta.count = _colMeta.size
function _colMeta.get(c, i) return c.__nodes[i] end
function _colMeta.first(c) return c.__nodes[1] end
function _colMeta.last(c) return c.__nodes[#c.__nodes] end
function _colMeta.toList(c) return c.__nodes end
_colMeta.toArray = _colMeta.toList
function _colMeta.each(c, fn)
    for i = 1, #c.__nodes do fn(c.__nodes[i], i) end
end
-- contains(target)：UiObject 指纹比对（text/desc/res/bounds）
local function _fp(n)
    local ok, b = pcall(function() return n.bounds end)
    local bkey = ""
    if ok and type(b) == "table" then
        bkey = string.format("%s,%s,%s,%s", tostring(b[1]), tostring(b[2]), tostring(b[3]), tostring(b[4]))
    end
    local t, d, r = "", "", ""
    pcall(function() t = tostring(n.text) end)
    pcall(function() d = tostring(n.desc) end)
    pcall(function() r = tostring(n.res) end)
    return t .. "|" .. d .. "|" .. r .. "|" .. bkey
end
function _colMeta.contains(c, target)
    if target == nil then return false end
    local key = _fp(target)
    for _, n in ipairs(c.__nodes) do
        if _fp(n) == key then return true end
    end
    return false
end

-- 二次筛选：find/findOne/setText
function _colMeta.find(c, selector)
    local out = {}
    for _, n in ipairs(c.__nodes) do
        local ok, sub = pcall(n.find, n, selector)
        if ok and type(sub) == "table" then
            for _, x in ipairs(sub) do out[#out + 1] = x end
        end
    end
    return autoCollection.create(out)
end
function _colMeta.findOne(c, selector)
    for _, n in ipairs(c.__nodes) do
        local ok, one = pcall(n.findOne, n, selector)
        if ok and one then return one end
    end
    return nil
end
function _colMeta.setText(c, text)
    local n = 0
    for _, nd in ipairs(c.__nodes) do
        local ok, r = pcall(nd.setText, nd, text)
        if ok and r then n = n + 1 end
    end
    return n
end

-- 批量动作：遍历执行 node:m()，返回成功数
local function _batch(method)
    return function(c)
        local n = 0
        for _, nd in ipairs(c.__nodes) do
            local ok, r = pcall(nd[method], nd)
            if ok and r == true then n = n + 1 end
        end
        return n
    end
end
for _, m in ipairs({ "click", "longClick", "select", "copy", "cut", "paste", "focus", "clearFocus",
    "scrollForward", "scrollBackward", "scrollUp", "scrollDown", "scrollLeft", "scrollRight",
    "expand", "collapse", "dismiss", "show" }) do
    _colMeta[m] = _batch(m)
end
function _colMeta.performAction(c, action)
    local n = 0
    for _, nd in ipairs(c.__nodes) do
        local ok, r = pcall(nd.performAction, nd, action)
        if ok and r == true then n = n + 1 end
    end
    return n
end
_colMeta.__index = _colMeta

function autoCollection.create(list)
    local col = { __nodes = list or {} }
    return setmetatable(col, _colMeta)
end
function autoCollection.find(selector)
    local root = auto.getRoot()
    if not root then return autoCollection.create({}) end
    local ok, list = pcall(root.find, root, selector)
    if not ok or type(list) ~= "table" then return autoCollection.create({}) end
    return autoCollection.create(list)
end
autoCollection.findAll = autoCollection.find
function autoCollection.findOne(selector)
    return auto.getRoot():findOne(selector)
end

-- ---------------- 懒人图片处理别名（getImage/scaleImage/rotateImage/binaryImage/binaryRect） ----------------
getImage = loadImage
scaleImage = function(img, w, h) return images.resize(img, w, h) end
rotateImage = function(img, angle)
    if angle == 90 then return images.rotate90(img)
    elseif angle == 180 then return images.rotate180(img)
    elseif angle == 270 then return images.rotate270(img)
    else return images.rotate(img, angle) end
end
-- binaryImage 双序自适应：binaryImage(img, threshold) 或 binaryImage(threshold, img)
function binaryImage(a, b)
    if type(a) == "number" then return images.binarize(b, a) end
    return images.binarize(a, b)
end
-- binaryRect 双序自适应：binaryRect(img, threshold, x,y,w,h) 或 binaryRect(threshold, img, x,y,w,h)
function binaryRect(a, ...)
    local args = {...}
    if type(a) == "number" then
        return images.binarize(args[1], a, args[2], args[3], args[4], args[5])
    end
    return images.binarize(a, args[1], args[2], args[3], args[4], args[5])
end

-- ---------------- path 路径处理（派大星 path 19 项） ----------------
path = {}
function path.getProjectDir() return ROOT_DIR end
function path.setProjectDir(p) ROOT_DIR = p end
_appFilesDir = nil
function path.getAppFilesDir()
    if _appFilesDir then return _appFilesDir end
    local ok, dir = pcall(function()
        return LuaEngine:getContext():getFilesDir():getAbsolutePath()
    end)
    if ok and dir then _appFilesDir = dir end
    return _appFilesDir
end
function path.setAppFilesDir(p) _appFilesDir = p end
_SCRIPT_PATH = nil
function path.getCurrentScriptPath() return _SCRIPT_PATH or ROOT_DIR end
function path.setCurrentScriptPath(p) _SCRIPT_PATH = p end
function path.getWorkingDir() return ROOT_DIR end
function path.join(...)
    local out = ""
    for _, p in ipairs({...}) do
        if p ~= nil and p ~= "" then
            p = tostring(p)
            if out == "" then out = p
            else
                if out:sub(-1) == "/" and p:sub(1, 1) == "/" then
                    p = p:sub(2)
                end
                if out:sub(-1) ~= "/" and p:sub(1, 1) ~= "/" then
                    out = out .. "/"
                end
                out = out .. p
            end
        end
    end
    return out
end
function path.dirname(p)
    return tostring(p):match("^(.*)/[^/]*$") or ""
end
function path.basename(p)
    return tostring(p):match("([^/]+)/?$") or tostring(p)
end
function path.getName(p) return path.basename(p) end
function path.getParent(p) return path.dirname(p) end
function path.extname(p)
    local ext = path.basename(p):match("%.([^%.]*)$")
    return ext and ("." .. ext) or ""
end
function path.normalize(p)
    p = tostring(p):gsub("\\", "/")
    local abs = p:sub(1, 1) == "/"
    local parts = {}
    for seg in p:gmatch("[^/]+") do
        if seg == ".." and #parts > 0 and parts[#parts] ~= ".." then parts[#parts] = nil
        elseif seg ~= "." then parts[#parts + 1] = seg end
    end
    return (abs and "/" or "") .. table.concat(parts, "/")
end
function path.isAbsolutePath(p) return tostring(p):sub(1, 1) == "/" end
function path.resolve(base, rel)
    if rel == nil then return path.normalize(base or "") end
    if path.isAbsolutePath(rel) then return path.normalize(rel) end
    return path.normalize(path.join(base or ROOT_DIR or "", rel))
end
function path.getAbsolutePath(p) return path.resolve(ROOT_DIR, p) end
function path.exists(p) return fileExist(p) end
function path.isReadable(p)
    local f = io.open(tostring(p), "r")
    if f then f:close() return true end
    return false
end
function path.isAssetPath(p)
    p = tostring(p)
    return p:sub(1, 7) == "assets/" or p:sub(1, 6) == "asset/"
end
function path.stripAssetPrefix(p)
    return tostring(p):gsub("^assets/", ""):gsub("^asset/", "")
end
function path.resolveCandidates(...)
    for _, c in ipairs({...}) do
        if c and fileExist(c) then return c end
    end
    return nil
end

-- ---------------- attach 附件（懒人式）：约定目录 ROOT_DIR/attach/ ----------------
-- 附件随工程 zip / Fire 热更自然分发（同步打包不筛文件类型）；
-- 依赖 ROOT_DIR（工程启动后有效，ROOT_DIR 为 nil 时路径解析失败属预期）。
attach = {}
function attach.dir()
    return path.join(ROOT_DIR or "", "attach")
end
-- 附件相对路径 → 绝对路径（不校验存在性，存在性用 attach.exists）
function attach.path(name)
    if type(name) ~= "string" or name == "" then return nil end
    return path.join(attach.dir(), name)
end
function attach.exists(name)
    local p = attach.path(name)
    if not p then return false end
    return fileExist(p)
end
-- 读附件文本（二进制用 file.read(p, true) 自行拼路径）
function attach.read(name)
    local p = attach.path(name)
    if not p then error("attach.read: bad name", 2) end
    if not fileExist(p) then error("attach.read: not found " .. p, 2) end
    return file.read(p)
end
-- 读附件图片（等价 loadImage(attach.path(name))；需图色环境已注入 loadImage）
function attach.loadImage(name)
    local p = attach.path(name)
    if not p then error("attach.loadImage: bad name", 2) end
    if not fileExist(p) then error("attach.loadImage: not found " .. p, 2) end
    if not loadImage then error("attach.loadImage: loadImage unavailable in this lua env", 2) end
    return loadImage(p)
end
-- 动态加载附件 lua 模块（attach/<name>.lua 的返回值即模块），带缓存
local _attach_cache = {}
function attach.require(name)
    if type(name) ~= "string" or name == "" then error("attach.require: bad name", 2) end
    local hit = _attach_cache[name]
    if hit ~= nil then return hit end
    local p = attach.path(name .. ".lua")
    if not p or not fileExist(p) then
        error("attach.require: not found " .. tostring(p), 2)
    end
    local chunk, err = loadfile(p)
    if not chunk then error("attach.require: load failed " .. tostring(err), 2) end
    local mod = chunk()
    _attach_cache[name] = mod
    return mod
end

-- ---------------- coordinate 坐标转换（派大星 12 项，百分比 ↔ 像素） ----------------
coordinate = {}
function coordinate.getScreenSize()
    if getDisplaySize then return getDisplaySize() end
    return getBaseSize()
end
-- 设定虚拟分辨率基准（脚本按 vw/vh 编写，复用 setScreenScale 机制）
function coordinate.setScreenSize(vw, vh)
    local w = select(1, coordinate.getScreenSize())
    if w and w > 0 and vw and vw > 0 then
        setScreenScale(vw / w)
        return true
    end
    return false
end
function coordinate.p2rX(px) local w = select(1, coordinate.getScreenSize()) return math.floor(w * px / 100 + 0.5) end
function coordinate.p2rY(py) local _, h = coordinate.getScreenSize() return math.floor(h * py / 100 + 0.5) end
function coordinate.r2pX(x) local w = select(1, coordinate.getScreenSize()) return x * 100 / w end
function coordinate.r2pY(y) local _, h = coordinate.getScreenSize() return y * 100 / h end
function coordinate.p2r(px, py) return coordinate.p2rX(px), coordinate.p2rY(py) end
function coordinate.r2p(x, y) return coordinate.r2pX(x), coordinate.r2pY(y) end
function coordinate.p2rRect(px, py, pw, ph)
    local x, y = coordinate.p2r(px, py)
    return x, y, coordinate.p2rX(px + pw) - x, coordinate.p2rY(py + ph) - y
end
function coordinate.r2pRect(x, y, w, h)
    local px, py = coordinate.r2p(x, y)
    return px, py, coordinate.r2pX(x + w) - px, coordinate.r2pY(y + h) - py
end
function coordinate.getCenter()
    local w, h = coordinate.getScreenSize()
    return math.floor(w / 2), math.floor(h / 2)
end
function coordinate.getCenterPercent() return 50, 50 end

-- ---------------- timer 定时器（派大星同名；thread 协程驱动，可 clear） ----------------
timer = {}
_timerSeq = 0
_timerMap = {}
function timer.set(func, ms, repeatFlag)
    local id
    local function schedule()
        id = setTimer(function()
            if not _timerMap[id] then return end
            local ok, err = pcall(func)
            if not ok then print("[timer error] " .. tostring(err)) end
            if repeatFlag and _timerMap[id] then schedule() else _timerMap[id] = nil end
        end, ms)
        _timerMap[id] = true
    end
    schedule()
    return id
end
function timer.clear(id)
    if _timerMap[id] then
        _timerMap[id] = nil
        pcall(thread.stop, id)
    end
end
function timer.clearAll() _timerMap = {} end
setTimeout = function(func, ms) return timer.set(func, ms, false) end
setInterval = function(func, ms) return timer.set(func, ms, true) end
clearTimeout = timer.clear
clearInterval = timer.clear

-- ---------------- 事件回调（懒人 setXxxEventCallBack 系；进程内总线） ----------------
_eventCallbacks = {}
function setUserEventCallBack(cb) _eventCallbacks.user = cb end
function setPluginEventCallBack(cb) _eventCallbacks.plugin = cb end
function setNotifyEventCallBack(cb) _eventCallbacks.notify = cb end
local function _dispatch(kind, name, data)
    local cb = _eventCallbacks[kind]
    if cb then
        local ok, err = pcall(cb, name, data)
        if not ok then print("[" .. kind .. " event error] " .. tostring(err)) end
        return true
    end
    return false
end
function sendPluginEvent(name, data) return _dispatch("plugin", name, data) end
function sendUserEvent(name, data) return _dispatch("user", name, data) end
function sendNotifyEvent(name, data) return _dispatch("notify", name, data) end

-- ---------------- event 事件总线（派大星对齐：多播注册 + 系统事件源） ----------------
event = {}
_eventListeners = {}   -- name -> { {id, fn}, ... }
_eventIdName = {}      -- id -> name（off 递减系统事件源计数）
_eventSeq = 0
_sysSourceCount = {}   -- 系统事件源监听计数（toast/notification/click/touch）
_sysPolling = false
_lastMirrorLine = nil

function event.on(name, cb)
    if type(name) ~= "string" or type(cb) ~= "function" then return nil end
    _eventSeq = _eventSeq + 1
    local lst = _eventListeners[name]
    if not lst then lst = {} _eventListeners[name] = lst end
    lst[#lst + 1] = { id = _eventSeq, fn = cb }
    _eventIdName[_eventSeq] = name
    return _eventSeq
end
event.addListener = event.on

function event.off(id)
    local name = _eventIdName[id]
    if not name then
        -- id 也可以直接传事件名（按名清空）
        if type(id) == "string" and _eventListeners[id] then
            _eventListeners[id] = nil
            return true
        end
        return false
    end
    local lst = _eventListeners[name]
    if lst then
        for i, e in ipairs(lst) do
            if e.id == id then table.remove(lst, i) break end
        end
        if #lst == 0 then _eventListeners[name] = nil end
    end
    _eventIdName[id] = nil
    _sysSourceOff(name)
    return true
end
event.removeListener = event.off

function event.removeListeners(name)
    if name then
        for id, n in pairs(_eventIdName) do
            if n == name then _eventIdName[id] = nil end
        end
        _eventListeners[name] = nil
        _sysSourceOff(name)
    else
        _eventListeners = {}
        _eventIdName = {}
        for n in pairs(_sysSourceCount) do _sysSourceOff(n) end
        _sysSourceCount = {}
    end
    return true
end

-- 内部触发（回调异常隔离，返回送达数）
function event._emit(name, ...)
    local lst = _eventListeners[name]
    if not lst then return 0 end
    local n = 0
    for _, e in ipairs(lst) do
        local ok = pcall(e.fn, ...)
        if ok then n = n + 1 end
    end
    return n
end

-- 系统事件源开关与轮询（EventBridge 镜像文件 → thread 轮询协程，60ms 尽力而为）
function _sysSourceOn(name)
    _sysSourceCount[name] = (_sysSourceCount[name] or 0) + 1
    if _sysSourceCount[name] == 1 and __event_enable then
        __event_enable(true)
        if not _sysPolling then
            _sysPolling = true
            thread.start(function()
                while _sysPolling do
                    local t, pk, cl, tx, kc, ac
                    if __event_read then t, pk, cl, tx, kc, ac = __event_read() end
                    if t then
                        -- key 事件跳过去重：队列读端本就每条只投递一次，
                        -- 连续两次相同按键（如快速双击音量键）折叠会丢事件；回调参数改传 (keyCode, action)
                        if t == "key" then
                            event._emit("key", kc, ac, tx)
                        else
                            local key = t .. "|" .. tostring(pk) .. "|" .. tostring(cl) .. "|" .. tostring(tx)
                            if key ~= _lastMirrorLine then
                                _lastMirrorLine = key
                                if t == "notify" then t = "notification" end
                                event._emit(t, pk, cl, tx)
                            end
                        end
                    end
                    thread.sleep(60)
                end
            end)
        end
    end
end

function _sysSourceOff(name)
    if _sysSourceCount[name] and _sysSourceCount[name] > 0 then
        _sysSourceCount[name] = _sysSourceCount[name] - 1
        if _sysSourceCount[name] == 0 and __event_enable then
            local any = false
            for _, v in pairs(_sysSourceCount) do
                if v > 0 then any = true break end
            end
            if not any then
                _sysPolling = false
                __event_enable(false)
            end
        end
    end
end

-- 派大星 event 模块 API（toast/notification/click/touch/key 挂接系统事件源计数）
function event.onStopEvent(cb) return event.on("stop", cb) end
-- 语音/手势事件源缺失：注册即拒绝（返回 false 表示不可用）
function event.onVoiceDown(cb) return false end
function event.onVoiceUp(cb) return false end
function event.onGesture(cb) return false end

local function _onSystem(name, cb)
    local id = event.on(name, cb)
    if id then _sysSourceOn(name) end
    return id
end
function event.onToast(cb) return _onSystem("toast", cb) end
function event.onNotification(cb) return _onSystem("notification", cb) end
function event.onTouch(cb) return _onSystem("touch", cb) end
function event.onClick(cb) return _onSystem("click", cb) end
-- 音量键监听（无障碍 flagRequestFilterKeyEvents + 转发开启时，服务端智能拦截音量键）：
-- 回调 cb(keyCode, action)，仅拦截 VOLUME_UP/DOWN/MUTE；无脚本消费心跳 3 秒后自动放行
function event.onKey(cb) return _onSystem("key", cb) end

-- 权限状态（尽力而为）
function event.getPermissionStatus()
    local st = { root = false, accessibility = false, overlay = false, touch = false }
    st.root = (getRunEnvType() == "root")
    if execOut then
        local out = execOut("settings", "get", "secure", "enabled_accessibility_services") or ""
        st.accessibility = out:find("autolua", 1, true) ~= nil
    end
    if st.root then
        st.touch = true
        st.overlay = true  -- root 可注入悬浮窗，无法直接查 overlay 授权，按 root 放行
    else
        st.touch = st.accessibility
    end
    return st
end

function event.hasTouchPermission()
    return getRunEnvType() == "root" or (event.getPermissionStatus().accessibility)
end

-- ---------------- project 工程与 Fire 热更（派大星对齐；纯 Lua 实现） ----------------
project = {}
local _projectBase = ROOT_DIR or "/data/local/tmp"
local _HF = _projectBase .. "/hotfix"
local _HF_PENDING = _HF .. "/pending"
local _HF_BACKUP = _HF .. "/backup"

project.getProjectDir = function() return _projectBase end
project.getHotfixDir = function() return _HF end

function project.setHotFixCallback(cb) return event.on("hotfix_finish", cb) end

function project.hasPendingHotfix()
    local lst = file.listDir and file.listDir(_HF_PENDING)
    return type(lst) == "table" and #lst > 0
end

local function _ensureDir(d)
    if not fileExist(d) then file.mkdir(d) end
end

-- 应用 pending（或指定目录）：被覆盖文件先备份 → 覆盖到项目根 → 触发 hotfix_finish
function project.hotfixFire(dir)
    dir = dir or _HF_PENDING
    if not fileExist(dir) then return false, "hotfix dir not found: " .. tostring(dir) end
    local lst = file.listDir(dir) or {}
    local n = 0
    for _, name in ipairs(lst) do
        local src = dir .. "/" .. name
        local dst = _projectBase .. "/" .. name
        if file.isDirectory(src) then
            file.copy(src, dst)  -- 子目录整体复制
        else
            local parent = dst:match("^(.*)/[^/]+$")
            if parent then _ensureDir(parent) end
            if fileExist(dst) then
                _ensureDir(_HF_BACKUP)
                file.copy(dst, _HF_BACKUP .. "/" .. name)  -- 备份被覆盖文件
            end
            file.copy(src, dst)
        end
        n = n + 1
    end
    event._emit("hotfix_finish", true, n)
    return true, n
end

function project.hotfixFireFromDir(dir)
    if not fileExist(dir) then return false, "dir not found: " .. tostring(dir) end
    file.deleteDir(_HF_PENDING)
    _ensureDir(_HF_PENDING)
    local lst = file.listDir(dir) or {}
    for _, name in ipairs(lst) do
        file.copy(dir .. "/" .. name, _HF_PENDING .. "/" .. name)
    end
    return project.hotfixFire()
end

function project.hotfixFireFromUrl(url, timeout)
    local zipPath = _HF .. "/pending.zip"
    _ensureDir(_HF)
    if not (http and http.download) then return false, "http.download unavailable" end
    pcall(http.download, url, zipPath)
    if not fileExist(zipPath) then return false, "download failed" end
    file.deleteDir(_HF_PENDING)
    _ensureDir(_HF_PENDING)
    if not file.unZip(zipPath, _HF_PENDING) then return false, "unzip failed" end
    delfile(zipPath)
    return project.hotfixFire()
end

function project.rollback()
    if not fileExist(_HF_BACKUP) then return false, "no backup" end
    local lst = file.listDir(_HF_BACKUP) or {}
    for _, name in ipairs(lst) do
        file.copy(_HF_BACKUP .. "/" .. name, _projectBase .. "/" .. name)
    end
    file.deleteDir(_HF_BACKUP)
    event._emit("hotfix_finish", false, "rollback")
    return true
end

function project.clearCustomFire()
    file.deleteDir(_HF_PENDING)
    file.deleteDir(_HF_BACKUP)
    return true
end

-- ---------------- cv.Mat 组合层（cv_lib.cpp 之上；images 互通 + captureMat） ----------------
if cv ~= nil and cv.createMat ~= nil then
    -- images.getMat/getGrayMat：image → Mat（RGBA 4ch / 灰度 1ch）
    images.getMat = function(img)
        local data, w, h = images.getData(img), images.getWidth(img), images.getHeight(img)
        return cv.createMatFromData(h, w, data, 4)
    end
    images.getGrayMat = function(img)
        local data, w, h = images.getData(img), images.getWidth(img), images.getHeight(img)
        return cv.createGrayMatFromRgba(h, w, data)
    end
    images.toJavaMat = images.getMat  -- Java OpenCV 桥不存在，等同 getMat

    -- cv.captureMat(x, y, w, h) → Mat（整帧 RGBA 取区域）
    cv.captureMat = function(x, y, w, h)
        local data, fw, fh, stride = getScreenFrame()
        if not data then return nil, "frame unavailable" end
        x, y = x or 0, y or 0
        w, h = w or fw, h or fh
        local out = {}
        for r = 0, h - 1 do
            local base = (y + r) * stride + x * 4 + 1
            out[#out + 1] = data:sub(base, base + w * 4 - 1)
        end
        return cv.createMatFromData(h, w, table.concat(out), 4)
    end

    -- cv.matToImage(m) → image（mat → dataStr → images.fromData；1ch 先转 RGBA）
    cv.matToImage = function(m)
        local data, rows, cols, ch = cv.matToData(m)
        if ch == 1 then
            local g = cv.createMat(rows, cols, 4)
            for r = 0, rows - 1 do
                for c = 0, cols - 1 do
                    local v = m:get(r, c)
                    g:put(r, c, v, v, v, 255)
                end
            end
            data = g:getData()
        end
        return images.fromData(rows, cols, data)  -- stride 默认 4（RGBA）
    end

    -- cv.encodePng(m) → pngStr（经 images.toPng）
    cv.encodePng = function(m) return images.toPng(cv.matToImage(m)) end
end

-- ---------------- OCR 脚本调用（懒人 ocrText / 派大星 dotocr 形态；OCR 插件运行时） ----------------
-- 取帧走引擎 Display 帧链路（getScreenFrame），识别走 ocr 插件（OcrLib 转发）
if ocr ~= nil and ocr.recognizeRaw ~= nil then
    function ocr.ocrText(x1, y1, x2, y2)
        if not getScreenFrame then return nil, "display unavailable" end
        updateScreen()
        local data, fw, fh, stride = getScreenFrame()
        if not data then return nil, "frame unavailable" end
        x2 = x2 or (fw - 1)
        y2 = y2 or (fh - 1)
        local blocks = ocr.recognizeRaw(data, fw, fh, stride, x1, y1, x2 - x1 + 1, y2 - y1 + 1)
        local out = {}
        for _, b in ipairs(blocks) do out[#out + 1] = b.text end
        return table.concat(out, "\n")
    end

    function ocr.ocrTextEx(x1, y1, x2, y2)
        return ocr.ocrText(x1, y1, x2, y2)
    end

    -- 返回块数组 {{text=,x=,y=,w=,h=,confidence=},...}（相对识别区域坐标）
    function ocr.ocrEx(x1, y1, x2, y2)
        if not getScreenFrame then return nil, "display unavailable" end
        updateScreen()
        local data, fw, fh, stride = getScreenFrame()
        if not data then return nil, "frame unavailable" end
        x2 = x2 or (fw - 1)
        y2 = y2 or (fh - 1)
        return ocr.recognizeRaw(data, fw, fh, stride, x1, y1, x2 - x1 + 1, y2 - y1 + 1)
    end

    -- 基点相对坐标块（懒人 ocrExNew 形态：各块坐标相对基点）
    function ocr.ocrExNew(x1, y1, x2, y2)
        local blocks = ocr.ocrEx(x1, y1, x2, y2)
        if type(blocks) ~= "table" then return blocks end
        for _, b in ipairs(blocks) do
            b.x = b.x - x1
            b.y = b.y - y1
        end
        return blocks
    end

    ocr.ocrResult = ocr.ocrText
end

-- ---------------- captureScreen 高性能帧访问补齐（派大星 🟦 项） ----------------
-- captureScreen 基础包装见上文；此处补帧直访与状态
if captureScreen ~= nil and getScreenFrame ~= nil then
    -- captureScreen.getFrame() -> data, w, h, stride, format（整帧 RGBA，一次拷贝）
    captureScreen.getFrame = getScreenFrame
    -- 共享内存地址/尺寸无用户态等价物（C++ SharedFrame），给出帧大小等价信息
    captureScreen.getSharedMemorySize = function()
        local _, w, h, stride = getScreenFrame()
        return stride * h
    end
    captureScreen.getSharedMemoryAddress = function() return nil, "not exposed (use getFrame)" end
    captureScreen.getScreenshotFromSharedMemory = function(path)
        if path then return snapShot(path) end
        updateScreen()
        return true
    end
end

-- ---------------- fhttp（派大星网络请求模块；底层 http 服务） ----------------
fhttp = {}
if http ~= nil then
    function fhttp.get(url, headers)
        if headers then return http.request{ url = url, method = "GET", headers = headers } end
        return http.get(url)
    end

    function fhttp.post(url, body, headers)
        return http.request{ url = url, method = "POST", body = body or "", headers = headers }
    end

    function fhttp.postJson(url, jsonStr, headers)
        headers = headers or {}
        headers["Content-Type"] = "application/json"
        return http.request{ url = url, method = "POST", body = jsonStr or "", headers = headers }
    end

    function fhttp.postForm(url, formTable, headers)
        local parts = {}
        if formTable then
            for k, v in pairs(formTable) do
                parts[#parts + 1] = tostring(k) .. "=" .. tostring(v)
            end
        end
        local body = table.concat(parts, "&")
        headers = headers or {}
        if headers["Content-Type"] == nil then
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        end
        return http.request{ url = url, method = "POST", body = body, headers = headers }
    end

    function fhttp.put(url, body, headers)
        return http.request{ url = url, method = "PUT", body = body, headers = headers }
    end

    function fhttp.delete(url, headers)
        return http.request{ url = url, method = "DELETE", headers = headers }
    end

    function fhttp.patch(url, body, headers)
        return http.request{ url = url, method = "PATCH", body = body, headers = headers }
    end

    function fhttp.head(url, headers)
        return http.request{ url = url, method = "HEAD", headers = headers }
    end

    function fhttp.options(url, headers)
        return http.request{ url = url, method = "OPTIONS", headers = headers }
    end

    function fhttp.download(url, path, headers)
        return http.download(url, path, headers)
    end

    function fhttp.upload(url, path, fieldName, fileName, form)
        return http.upload(url, path, fieldName, fileName, form)
    end

    function fhttp.setTimeout(ms) return http.setTimeout(ms) end
    function fhttp.setProxy(host, port) return http.setProxy(host, port or 0) end

    -- 异步族：网络 IO 在独立 Java 线程（http.requestAsync/downloadAsync/uploadAsync），
    -- Lua 侧轮询句柄 isDone（协程内 thread.sleep 挂起解锁；主线程退化为普通 sleep，
    -- 两者均不阻塞其他协程），完成后 get 取结果（Object[] 展开多值），
    -- 回调签名 (body, code, headers) / (bool, code)；请求异常时回调 (nil, -1, err)
    local function _poll(ms)
        if thread and thread.isMain and not thread.isMain() then
            thread.sleep(ms)
        else
            sleep(ms)
        end
    end

    local function _waitHandle(h, cb)
        -- h/cb 经 vararg 传入（跨 thread.start 的闭包捕获不可靠，参照 setTimer 模式）
        thread.start(function(handle, callback)
            if type(callback) ~= "function" then return end
            while not handle:isDone() do thread.sleep(15) end
            local ok, a, b, c = pcall(function() return handle:get() end)
            if ok then
                pcall(callback, a, b, c)
            else
                pcall(callback, nil, -1, tostring(b))
            end
        end, h, cb)
    end

    local function _areq(opts, cb)
        if type(cb) ~= "function" then
            local res = { pcall(http.requestAsync, opts) }
            if not res[1] then error(res[2], 0) end
            local h = res[2]
            while not h:isDone() do _poll(15) end
            return h:get()
        end
        _waitHandle(http.requestAsync(opts), cb)
    end

    fhttp.getAsync = function(url, cb)
        _areq({ url = url, method = "GET" }, cb)
    end
    fhttp.postAsync = function(url, body, cb)
        _areq({ url = url, method = "POST", body = body or "" }, cb)
    end
    fhttp.postJsonAsync = function(url, jsonStr, cb)
        _areq({ url = url, method = "POST", body = jsonStr or "",
                headers = { ["Content-Type"] = "application/json" } }, cb)
    end
    fhttp.postFormAsync = function(url, formTable, cb)
        local parts = {}
        if formTable then
            for k, v in pairs(formTable) do
                parts[#parts + 1] = tostring(k) .. "=" .. tostring(v)
            end
        end
        _areq({ url = url, method = "POST", body = table.concat(parts, "&"),
                headers = { ["Content-Type"] = "application/x-www-form-urlencoded" } }, cb)
    end
    fhttp.putAsync = function(url, body, cb)
        _areq({ url = url, method = "PUT", body = body }, cb)
    end
    fhttp.deleteAsync = function(url, cb)
        _areq({ url = url, method = "DELETE" }, cb)
    end
    fhttp.patchAsync = function(url, body, cb)
        _areq({ url = url, method = "PATCH", body = body }, cb)
    end
    fhttp.headAsync = function(url, cb)
        _areq({ url = url, method = "HEAD" }, cb)
    end
    fhttp.optionsAsync = function(url, cb)
        _areq({ url = url, method = "OPTIONS" }, cb)
    end
    fhttp.downloadAsync = function(url, path, cb)
        if type(cb) ~= "function" then
            local h = http.downloadAsync(url, path)
            while not h:isDone() do _poll(15) end
            return h:get()
        end
        _waitHandle(http.downloadAsync(url, path), cb)
    end
    fhttp.uploadAsync = function(url, path, fieldName, fileName, form, cb)
        if type(cb) ~= "function" then
            local h = http.uploadAsync(url, path, fieldName, fileName, form)
            while not h:isDone() do _poll(15) end
            return h:get()
        end
        _waitHandle(http.uploadAsync(url, path, fieldName, fileName, form), cb)
    end
    -- 句柄直通（高级用法）：自行轮询
    fhttp.requestAsync = function(opts) return http.requestAsync(opts) end
end

-- ---------------- ime 补充（节点侧组合 + 懒人别名） ----------------
-- ime 基础表由 ImeLib 服务注入；此处补 getCurrentText（无障碍焦点输入框文本）
if ime ~= nil then
    function ime.getCurrentText()
        local ok, text = pcall(function()
            local sel = UiSelector():focused(true):editable(true)
            local node = getRootInActiveWindow():findOne(sel)
            if node == nil then return nil end
            return node.text
        end)
        if not ok then return nil end
        return text
    end
    imeLib = ime  -- 懒人 imeLib 别名
end

jsonLib = json  -- 懒人 jsonLib 别名

print("=compat loaded=")
"""
  }
}
