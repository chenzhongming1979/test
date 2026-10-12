--[[ frameSdk-autolua2/device.lua
设备域：应用管理/设备信息/路径基准/线程 Thread/systemTime/exitScript/setLogOff/extractAssets/sleep。
版权与许可见本包 contract.lua 文件头。

底层映射（相对 frameSdk-firefly 的差异）：
- autolua2 无 app 模块：应用管理走 exec/execOut shell 路径（root 引擎进程内确定可用）
  —— runApp=monkey 拉起 / stopApp=am force-stop / appIsFront=dumpsys 解析
- 显示尺寸走 getBaseSize()（引擎图色帧基准）
- 路径基准走 project/path 模块（ROOT_DIR）
--]]

local engine = require("frameSdk-autolua2.engine")
local fileio = require("frameSdk-autolua2.file_io")

local device = {}

local function g(name)
	return rawget(_G, name)
end

-- ==================== 应用管理（appid 传入包名，与懒人用法一致） ====================

function device.runApp(pkg)
	if pkg == nil or pkg == "" then return false end
	pkg = tostring(pkg)
	-- 优先引擎 app 模块（若后续引擎接入）；否则 monkey 拉起（root/沙箱 sh 均内置）
	local app = engine.M.app
	if app ~= nil and app.launchPackage ~= nil then
		local ok, r = pcall(app.launchPackage, pkg)
		if ok then return true end
	end
	local eo = g("execOut")
	if eo ~= nil then
		local out = eo("monkey -p " .. pkg .. " -c android.intent.category.LAUNCHER 1")
		if out ~= nil and tostring(out):find("Events injected: 1", 1, true) then return true end
		-- monkey 失败兜底：am start 需要入口 Activity，dumpsys 解析
		local e2 = g("execOut")
		if e2 ~= nil then
			local info = e2("cmd package resolve-activity --brief " .. pkg)
			local act = tostring(info or ""):match("(%S+/%S+)")
			if act ~= nil and act:find("/", 1, true) then
				local e3 = g("exec")
				if e3 ~= nil then
					pcall(e3, "am start -n " .. act)
					return true
				end
			end
		end
	end
	engine.log("runApp 失败（包未安装或 shell 不可用）：", pkg)
	return false
end

function device.stopApp(pkg)
	if pkg == nil or pkg == "" then return false end
	pkg = tostring(pkg)
	local app = engine.M.app
	if app ~= nil and app.killApp ~= nil then
		local ok = pcall(app.killApp, pkg)
		if ok then return true end
	end
	local e = g("exec")
	if e ~= nil then
		pcall(e, "am force-stop " .. pkg)
		return true
	end
	return false
end

function device.appIsRunning(pkg)
	if pkg == nil or pkg == "" then return false end
	local app = engine.M.app
	if app ~= nil and app.isAppRunning ~= nil then
		local ok, r = pcall(app.isAppRunning, pkg)
		if ok then return r == true end
	end
	local eo = g("execOut")
	if eo ~= nil then
		local out = eo("pidof " .. tostring(pkg))
		return out ~= nil and tostring(out) ~= "" and tostring(out):find("%d") ~= nil
	end
	return false
end

function device.appIsFront(pkg)
	if pkg == nil or pkg == "" then return false end
	local app = engine.M.app
	if app ~= nil and app.getTopPackageName ~= nil then
		local ok, top = pcall(app.getTopPackageName)
		if ok and top ~= nil then return tostring(top) == tostring(pkg) end
	end
	local eo = g("execOut")
	if eo ~= nil then
		-- dumpsys activity 顶栏解析：mResumedActivity / topResumedActivity 行的 "pkg/act"
		local out = eo("dumpsys activity activities")
		out = tostring(out or "")
		local line = out:match("([^\r\n]*mResumedActivity[^\r\n]*)")
			or out:match("([^\r\n]*topResumedActivity[^\r\n]*)")
		if line ~= nil then
			local top = line:match("([%w%.]+)/")
			if top ~= nil then return top == tostring(pkg) end
		end
	end
	return false
end

function device.getInstalledApk()
	local out = {}
	local app = engine.M.app
	if app ~= nil and app.getInstalledApps ~= nil then
		local ok, list = pcall(app.getInstalledApps)
		if ok and type(list) == "table" then
			for _, it in ipairs(list) do
				if type(it) == "string" then
					out[#out + 1] = it
				elseif type(it) == "table" then
					local pkg = it.packageName or it.pkg or it["包名"]
					if pkg ~= nil then out[#out + 1] = tostring(pkg) end
				end
			end
			return out
		end
	end
	local eo = g("execOut")
	if eo ~= nil then
		local list = eo("pm list packages")
		for pkg in tostring(list or ""):gmatch("package:([%w%.]+)") do
			out[#out + 1] = pkg
		end
	end
	return out
end

function device.getPackageName()
	local app = engine.M.app
	if app ~= nil then
		local ok, ctx = pcall(function() return app.getContext() end)
		if ok and ctx ~= nil then
			local ok2, name = pcall(function() return ctx:getPackageName() end)
			if ok2 and name ~= nil then return tostring(name) end
		end
	end
	local le = rawget(_G, "LuaEngine")
	if le ~= nil and le.getContext ~= nil then
		local ok3, ctx3 = pcall(le.getContext)
		if ok3 and ctx3 ~= nil then
			local ok4, name4 = pcall(function() return ctx3:getPackageName() end)
			if ok4 and name4 ~= nil then return tostring(name4) end
		end
	end
	return engine.PKG_SELF
end

-- ==================== 设备信息 ====================

function device.getDisplaySize()
	local bs = g("getBaseSize")
	if bs ~= nil then
		local ok, w, h = pcall(bs)
		if ok and tonumber(w) then return tonumber(w), tonumber(h) end
	end
	local coord = engine.M.coordinate
	if coord ~= nil and coord.getScreenSize ~= nil then
		local ok2, w2, h2 = pcall(coord.getScreenSize)
		if ok2 and tonumber(w2) then return tonumber(w2), tonumber(h2) end
	end
	return 0, 0
end

-- 屏幕方向：横屏 1/竖屏 0（懒人取值语义 C1 联调核对）
function device.getDisplayRotate()
	local w, h = device.getDisplaySize()
	if w ~= nil and h ~= nil and w > h then return 1 end
	return 0
end

function device.getRunEnvType()
	-- 与 frameSdk-firefly 同语义：统一 0（JY 框架仅做存在性判断）
	return 0
end

-- ==================== 路径基准（缓存字段在 engine 上，init 触发） ====================

function device.getWorkPath()
	if engine.workPath ~= nil then return engine.workPath end
	local project = engine.M.project
	if project ~= nil and project.getProjectDir ~= nil then
		local ok, p = pcall(project.getProjectDir)
		if ok and type(p) == "string" and p ~= "" then
			engine.workPath = p
			return p
		end
	end
	local pathm = engine.M.path
	if pathm ~= nil and pathm.getProjectDir ~= nil then
		local ok2, p2 = pcall(pathm.getProjectDir)
		if ok2 and type(p2) == "string" and p2 ~= "" then
			engine.workPath = p2
			return p2
		end
	end
	engine.workPath = "."
	return "."
end

-- 显式校正工作目录（getWorkPath 首次调用后缓存固化；ide_push 等非默认工程根场景，
-- 入口脚本在 init 后调用 setWorkPath(工程根)，getUIConfig/找图相对路径才落对位置）
function device.setWorkPath(p)
	if type(p) == "string" and p ~= "" then
		engine.workPath = p
	end
end

function device.getSdPath()
	if engine.sdPath ~= nil then return engine.sdPath end
	-- 真机实测 /sdcard 可写性；不可写时改 return getWorkPath()
	engine.sdPath = "/sdcard"
	return engine.sdPath
end

-- ==================== 线程（卡屏检测线程 / 心跳线程） ====================

local Thread = {}
function Thread.newThread(fn)
	local thread = engine.M.thread
	if thread == nil or thread.start == nil then
		engine.log("thread 模块不可用，函数将同步执行（卡屏检测功能退化）")
		return nil
	end
	local ok, tid = pcall(thread.start, fn)
	if not ok then engine.log("thread.start 失败：", tostring(tid)) end
	return tid
end
device.Thread = Thread

-- ==================== 日志开关 / 系统时间 / 退出 ====================

-- 日志开关（懒人引擎函数，v1 记录标志位）
device.logOff = false
function device.setLogOff(flag)
	device.logOff = (flag == true)
end

-- 毫秒时间戳（引擎全局 systemTime = System.currentTimeMillis）
function device.systemTime()
	local st = g("systemTime")
	if st ~= nil then
		local ok, ts = pcall(st)
		if ok and type(ts) == "number" then return ts end
	end
	return math.floor(os.clock() * 1000)
end

-- 脚本终止：受控异常方案（R5）。入口脚本捕获 "@EXIT_SCRIPT@" 后正常收尾；
-- 若后续查证到平台正式停止 API，仅需替换此函数实现
function device.exitScript(msg)
	engine.cleanup()
	error("@EXIT_SCRIPT@:" .. tostring(msg or "脚本退出"), 0)
end

-- ==================== sleep（毫秒） ====================
-- 引擎原生全局 sleep（coroutine 友好挂起）优先；缺失时忙等兜底（自检需要该命令存在）。
local nativeSleep = rawget(_G, "sleep")
local function sleep(ms)
	if type(nativeSleep) == "function" then
		return nativeSleep(ms)
	end
	ms = tonumber(ms) or 0
	if ms <= 0 then return end
	local t0 = os.clock()
	local target = ms / 1000
	while os.clock() - t0 < target do end
end
device.sleep = sleep

-- ==================== assets 释放兼容 ====================
-- autolua2 资源随包直读（模型平铺到 工作目录/yolo/），此函数仅确保目录
function device.extractAssets(resName, targetDir, pattern)
	if targetDir ~= nil and not fileio.fileExist(targetDir) then
		fileio.mkdir(targetDir)
	end
	engine.log("extractAssets（autolua2 免释放）：", tostring(resName), "→", tostring(targetDir))
	return true
end

-- ==================== import 别名解析（init 时调用，与原实现判定顺序一致） ====================
-- 原语义：importClass 存在且全局 import 不存在时，import = importClass；
-- autolua2 luajava.bindClass 单类导入："com.xx.Cls" → 类对象，"pkg.*" 导包降级空操作。
-- 引擎原生 import/importClass 对 "java.lang.*" 通配直接抛 Invalid name（实测），
-- 故无论命中哪个实现都包 pcall 安全壳 + 通配降级，业务脚本导入失败不炸装载链。
function device.resolveImport()
	local importClass = rawget(_G, "importClass")
	local importG = rawget(_G, "import")
	local raw = importG or importClass
	if raw ~= nil then
		return function(spec)
			spec = tostring(spec or "")
			if spec:sub(-1) == "*" then
				engine.log("import 通配导包不支持（luajava 全类名直引）：", spec)
				return nil
			end
			local ok, cls = pcall(raw, spec)
			if not ok then engine.log("import 失败：", tostring(cls)) end
			return ok and cls or nil
		end
	end
	local lj = rawget(_G, "luajava")
	if lj ~= nil and lj.bindClass ~= nil then
		return function(spec)
			spec = tostring(spec or "")
			if spec:sub(-1) == "*" then
				engine.log("import 通配导包不支持（luajava 全类名直引）：", spec)
				return nil
			end
			local ok, cls = pcall(lj.bindClass, spec)
			if not ok then engine.log("import bindClass 失败：", tostring(cls)) end
			return ok and cls or nil
		end
	end
	engine.log("警告：import/importClass/luajava 均不可用，import 命令降级为空操作")
	return function() end
end

return device
