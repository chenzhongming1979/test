--[[ frameSdk-autolua2/file_io.lua
文件/网络域：文件原语（引擎直注全局 + file 服务映射）/ lfs 兼容 / unZip /
httpPost（含 NJOcr 本地仿真）/ LuaEngine.httpGet / jsonLib / os.execute 桥。
版权与许可见本包 contract.lua 文件头。
--]]

local engine = require("frameSdk-autolua2.engine")
local compat = require("frameSdk-autolua2.compat")

local fileio = {}

-- ==================== 文件原语（引擎直注全局优先，file 服务兜底） ====================

function fileio.fileExist(p)
	local g = rawget(_G, "fileExist")
	if g ~= nil then
		local ok, r = pcall(g, tostring(p))
		if ok then return r == true end
	end
	local f = engine.M.filem
	if f ~= nil and f.exists ~= nil then
		local ok2, r2 = pcall(f.exists, tostring(p))
		return ok2 and r2 == true
	end
	-- 最终兜底：io.open 可读即存在
	local fh = io.open(tostring(p), "rb")
	if fh ~= nil then fh:close() return true end
	return false
end

function fileio.fileSize(p)
	local g = rawget(_G, "fileSize")
	if g ~= nil then
		local ok, r = pcall(g, tostring(p))
		if ok and tonumber(r) then return tonumber(r) end
	end
	local f = engine.M.filem
	if f ~= nil and f.size ~= nil then
		local ok2, r2 = pcall(f.size, tostring(p))
		return (ok2 and tonumber(r2)) or 0
	end
	return 0
end

-- writeFile(path, content [,overwrite])：覆盖写（懒人第3参兼容；
-- 引擎 writeFile(path, content, append)——append=false 即覆盖）
function fileio.writeFile(p, c, ov)
	local g = rawget(_G, "writeFile")
	if g ~= nil then
		local ok, r = pcall(g, tostring(p), tostring(c or ""), ov == false)
		return ok and r ~= false
	end
	local f = engine.M.filem
	if f ~= nil and f.write ~= nil then
		local ok2, r2 = pcall(f.write, tostring(p), tostring(c or ""), ov == false)
		return ok2 and r2 ~= false
	end
	return false
end

function fileio.mkdir(p)
	local f = engine.M.filem
	if f == nil then return false end
	if f.mkdirs ~= nil then
		local ok, _ = pcall(f.mkdirs, tostring(p))
		return ok
	end
	if f.mkdir ~= nil then
		local ok2, _ = pcall(f.mkdir, tostring(p))
		return ok2
	end
	return false
end

function fileio.delfile(p)
	p = tostring(p)
	local g = rawget(_G, "delfile")
	-- 目录优先递归删除（file.deleteDir），文件走 delfile/file.remove
	local f = engine.M.filem
	if f ~= nil and f.isDirectory ~= nil then
		local ok, isDir = pcall(f.isDirectory, p)
		if ok and isDir == true then
			if f.deleteDir ~= nil then
				local ok2, _ = pcall(f.deleteDir, p)
				return ok2
			end
			return false
		end
	end
	if g ~= nil then
		local ok3, r = pcall(g, p)
		if ok3 then return r == true or r == nil end
	end
	if f ~= nil and f.remove ~= nil then
		local ok4, _ = pcall(f.remove, p)
		return ok4
	end
	return false
end

function fileio.unZip(zipPath, destDir)
	local f = engine.M.filem
	if f ~= nil and f.unZip ~= nil then
		local ok, r = pcall(f.unZip, tostring(zipPath), tostring(destDir))
		return ok and r ~= false
	end
	engine.log("file.unZip 不可用，unZip 无法执行")
	return false
end

-- ==================== lfs 兼容（目录遍历/属性） ====================

local lfsCompat = {}
function lfsCompat.mkdir(p) return fileio.mkdir(p) end

function lfsCompat.dir(path)
	local f = engine.M.filem
	local arr = {}
	if f ~= nil and f.listDir ~= nil then
		local ok, list = pcall(f.listDir, tostring(path or "."))
		if ok and type(list) == "table" then
			for _, name in ipairs(list) do
				arr[#arr + 1] = (type(name) == "string") and name
					or tostring(type(name) == "table" and (name.name or name.fileName) or name)
			end
		end
	end
	local i = 0
	return function()
		i = i + 1
		return arr[i]
	end
end

function lfsCompat.attributes(path, attrib)
	local f = engine.M.filem
	local t = { mode = "file", size = 0, modification = 0 }
	if f == nil then
		if attrib ~= nil then return nil end
		return t
	end
	local isDir = false
	if f.isDirectory ~= nil then
		local ok, r = pcall(f.isDirectory, tostring(path))
		isDir = (ok and r == true)
	end
	if isDir then t.mode = "directory" end
	if f.size ~= nil then
		local ok2, s = pcall(f.size, tostring(path))
		if ok2 then t.size = tonumber(s) or 0 end
	end
	if f.getMTime ~= nil then
		local ok3, m = pcall(f.getMTime, tostring(path))
		if ok3 then t.modification = tonumber(m) or 0 end
	end
	if attrib ~= nil then return t[attrib] end
	return t
end
fileio.lfsCompat = lfsCompat

-- ==================== httpPost（NJOcr 本机地址本地仿真，其余走引擎 httpPost/http 服务） ====================

function fileio.httpPost(url, body)
	-- NJOcr 仿真依赖 color 域（运行时延迟加载，避免顶层环）
	local color = require("frameSdk-autolua2.color")
	if type(url) == "string" and string.find(url, "127.0.0.1:1666", 1, true) then
		return color.njocrSimulate(body)
	end
	local g = rawget(_G, "httpPost")
	if g ~= nil then
		local ok, b = pcall(g, url, tostring(body or ""))
		if ok then return b end
	end
	local httpm = engine.M.http
	if httpm == nil then error("frameSdk: http 服务未加载", 0) end
	local ok2, r = pcall(httpm.post, url, tostring(body or ""))
	if ok2 then return r end
	return ""
end

-- LuaEngine.httpGet(url, headers, timeout)（懒人 Java 桥）→ http 服务
function fileio.bindLuaEngineHttpGet()
	engine.LuaEngine.httpGet = function(url, headers, timeout)
		local httpm = engine.M.http
		if httpm == nil then error("frameSdk: http 服务未加载", 0) end
		if httpm.setTimeout ~= nil and tonumber(timeout) then
			pcall(httpm.setTimeout, tonumber(timeout))
		end
		if headers ~= nil and httpm.request ~= nil then
			local ok, body, code, _ = pcall(httpm.request, { url = url, method = "GET", headers = headers })
			if ok then return body end
		end
		local ok2, r = pcall(httpm.get, url)
		if ok2 then return r end
		return ""
	end
end

-- ==================== jsonLib ====================

local jsonLib = {
	encode = function(t)
		return engine.jsonEncode(t)
	end,
	decode = function(s)
		return engine.jsonDecode(s)
	end,
}
fileio.jsonLib = jsonLib

-- ==================== os.execute 桥（exec 全局，root shell） ====================
-- 引擎 os.execute 可能被禁用/缺失；jy_sys.execute/ping 走 os.execute 时桥到引擎 exec。
-- 引擎原生 os.execute 可用时保持原生（与 firefly 包"保留形态"策略不同：
-- autolua2 的 exec 在 root 引擎进程内确定可用，桥接有效）。
if os ~= nil then
	local nativeExec = rawget(_G, "exec")
	if nativeExec ~= nil then
		os.execute = function(cmd)
			local ok, r = pcall(nativeExec, tostring(cmd or ""))
			if ok then return true end
			return false
		end
	end
end

return fileio
