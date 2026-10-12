--[[ frameSdk-autolua2/compat.lua
懒人扩展库仿真：纯文本 splitStr / utf8 兜底 / table.contains / string.split / DateTime / iconv / trim / colorToRGB。
版权与许可见本包 contract.lua 文件头。
顶层副作用仅限增强 Lua 标准库表（与原实现 require 时机一致），不调用任何引擎接口。

差异注记（相对 frameSdk-firefly）：
- autolua2 引擎 compat 层已注入全局 utf8 全套，但其 inStr 签名为 (s, sub, from)，
  与懒人 inStr(pos, str, sub) 不同——此处构造独立 utf8c 表（懒人签名），其余方法经
  元表转发引擎原生 utf8，不覆盖全局实现（避免影响引擎内部调用者）。
--]]

local compat = {}

-- ==================== splitStr（分隔符按【纯文本】处理，懒人语义，非 Lua pattern） ====================
-- 引擎 compat 层 splitStr 用 gmatch pattern 拼接，对多字符/含特殊字符分隔符语义有偏差；
-- 此处与 firefly 包一致使用纯文本查找版本，api.splitStr 走本实现。
local function splitStr(str, sep)
	local out = {}
	if str == nil then return out end
	str = tostring(str)
	sep = tostring(sep or ",")
	if sep == "" then
		out[1] = str
		return out
	end
	local pos = 1
	while true do
		local s, e = string.find(str, sep, pos, true)
		if s == nil then
			out[#out + 1] = string.sub(str, pos)
			break
		end
		out[#out + 1] = string.sub(str, pos, s - 1)
		pos = e + 1
	end
	return out
end
compat.splitStr = splitStr

-- ==================== utf8 兼容（懒人签名） ====================
-- 构造独立表：inStr(pos, str, sub)（懒人签名）包内实现；
-- 其余方法（len/sub/left/right/mid/char/codes/...）经 __index 转发引擎原生 utf8。
local utf8c = {}
utf8c.charpattern = (utf8 and utf8.charpattern) or "[%z\1-\127\194-\244][\128-\191]*"

function utf8c.inStr(pos, str, sub)
	if type(str) ~= "string" or type(sub) ~= "string" then return 0 end
	local s = string.find(str, sub, (tonumber(pos) or 0) + 1, true)
	return s or 0
end

setmetatable(utf8c, {
	__index = function(_, k)
		local g = rawget(_G, "utf8")
		if g ~= nil then return g[k] end
		return nil
	end,
})
compat.utf8c = utf8c

-- ==================== table.contains / string.split / table.unpack ====================

if table.contains == nil then
	function table.contains(t, v)
		if type(t) ~= "table" then return false end
		for _, x in pairs(t) do
			if x == v then return true end
		end
		return false
	end
end

if string.split == nil then
	function string.split(s, sep)
		return splitStr(s, sep)
	end
end

-- table.unpack 兼容（Lua 5.1 系无 table.unpack）
if table.unpack == nil then
	table.unpack = unpack
end

-- ==================== DateTime.Format（懒人内置时间对象仿真） ====================
-- 支持 %H %M %S（由总秒数推导），其余字符原样输出
local DateTime = {}
function DateTime.Format(fmt, sec)
	sec = tonumber(sec) or 0
	local h = math.floor(sec / 3600)
	local m = math.floor((sec % 3600) / 60)
	local s = math.floor(sec % 60)
	fmt = tostring(fmt or "")
	fmt = string.gsub(fmt, "%%H", string.format("%02d", h))
	fmt = string.gsub(fmt, "%%M", string.format("%02d", m))
	fmt = string.gsub(fmt, "%%S", string.format("%02d", s))
	return fmt
end
compat.DateTime = DateTime

-- ==================== iconv 兼容 ====================
-- 懒人内置 GBK 转码；autolua2 无字符集转换模块（crypt 仅含密码学能力），
-- v1 降级为原文透传——约定：数据文件统一 UTF-8，此路径即不会被触发。
local iconvWarned = false
local function iconvWarnOnce()
	if not iconvWarned then
		iconvWarned = true
		print("[frameSdk]", "iconv 转码不可用/失败，按原文处理（请确保数据文件为 UTF-8 编码）")
	end
end
local iconv = { _autolua2 = true }
-- v1 语义：透传原文 + 一次性告警（账号文件统一 UTF-8 后此路径不会被触发）
function iconv.decode(str, from, to)
	if type(str) ~= "string" then return str end
	iconvWarnOnce()
	return str
end
function iconv.new(to, from)
	local decoder = {}
	function decoder:wrap(fileIter)
		-- 流式包装降级：直接透传行迭代器
		return fileIter
	end
	return decoder
end
compat.iconv = iconv

-- ==================== trim ====================

local function trim(s)
	if type(s) ~= "string" then return s end
	return (string.gsub(s, "^%s*(.-)%s*$", "%1"))
end
compat.trim = trim

-- ==================== colorToRGB（纯 Lua 解析，兼容字符串 hex 与数字） ====================

local function colorToRGB(color)
	if type(color) == "string" then
		local hex = string.gsub(color, "0[xX]", "")
		hex = string.gsub(hex, "#", "")
		if #hex < 6 then return nil, nil, nil end
		local r = tonumber(string.sub(hex, 1, 2), 16) or 0
		local g = tonumber(string.sub(hex, 3, 4), 16) or 0
		local b = tonumber(string.sub(hex, 5, 6), 16) or 0
		return r, g, b
	end
	local n = tonumber(color) or 0
	return math.floor(n / 0x10000) % 0x100, math.floor(n / 0x100) % 0x100, n % 0x100
end
compat.colorToRGB = colorToRGB

return compat
