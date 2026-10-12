-- =====================================================================
-- JUI.lua —— 复刻自 jy-frame/脚本/JUI.lua（参数 1:1），渲染走引擎动态 UI
-- 链路：JY UIUtils 描述器(addCheckBox/addEditText/addSpinner/...) → 本文件
--       strategy → 引擎 ui.* JSON 控件树（pager 多标签页 + scroll + 底部按钮）
-- 内置能力：多标签页 = 引擎 pager 控件；多配置 = 引擎 ui.saveProfile/loadProfile
-- 交互：阻塞直至【确定】(保存+继续运行) 或【退出】(保存+error("exitScript")终止)
-- =====================================================================
local jy_ui = require("JY.Utils.UIUtils")

-- UI基础配置（参数对齐 jy-frame/脚本/JUI.lua 的 UIConf）
-- config_path 用 __SCRIPT_DIR__（脚本所在目录，读写恒同源）：
-- 不用 getWorkPath()——它走 frameSdk Java 缓存，ide_push / framesdk_test 双推送根下
-- 会漂移，出现"保存 A 目录、读取 B 目录"的 no such profile（实测踩坑）
local UIConf = {
	UIJson            = {},                           -- UI界面数据
	script_name       = "HJG_LYWZ_WJ",                -- 脚本名称
	config_list       = {                             -- 配置列表
		"配置1",
		"配置2",
		"配置3",
		"配置4"
	},
	config_path       = rawget(_G, "__SCRIPT_DIR__") or ".",  -- 配置文件路径（脚本目录）
	ui_height   = 1800,       -- 界面高度
	ui_width    = 700,        -- 界面宽度
}

local UISet = { win = nil, showStatus = true, exitFlag = false }

--[[
	生成账号设置界面（复刻 createUIAcc，参数 1:1）
参数
	num： 账号数量
]]
local function createUIAcc(num)
	if type(num) ~= "number" then
		error("参数：账号数量 应该为数字")
	end
	local result = {}
	table.insert(result, jy_ui.addRow())
	table.insert(result, jy_ui.addTextView("文字框312", "  账号******密码*****角色数量****版本"))
	for i = 1, num, 1 do
		table.insert(result, jy_ui.addRow())
		table.insert(result, jy_ui.addTextView("zhmm" .. i, "" .. i))
		table.insert(result, jy_ui.addEditText("账号" .. i, "", "", 180))
		table.insert(result, jy_ui.addEditText("密码" .. i, "", "", 180))
		table.insert(result, jy_ui.addEditText("角色数量" .. i, "1", "1", 50))
		table.insert(result, jy_ui.addSpinner("游戏版本" .. i, {"默认", "开炮1", "开炮2"}, 0))
	end

	table.insert(result, jy_ui.addRow())
	table.insert(result, jy_ui.addTextView("账号文件10", "账号文件"))
	table.insert(result, jy_ui.addEditText("账号文件", "", ""))

	table.insert(result, jy_ui.addRow())
	table.insert(result, jy_ui.addTextView("账号文件11", "(账号文件放入内部存储下的Pictures文件夹)"))

	table.insert(result, jy_ui.addRow())
	table.insert(result, jy_ui.addTextView("文字框5", "启动序号"))
	table.insert(result, jy_ui.addEditText("序号", "", "",false,60))
	table.insert(result, jy_ui.addTextView("游戏版本12", "默认游戏版本"))
	table.insert(result, jy_ui.addSpinner("游戏版本", {"开炮1", "开炮2"}, 0))

	table.insert(result, jy_ui.addRow())
	table.insert(result, jy_ui.addCheckBox("单号模式", "单号模式", false))
	table.insert(result, jy_ui.addCheckBox("记录日志", "记录日志", false))

	table.insert(result, jy_ui.addRow())
	table.insert(result, jy_ui.addTextView("文字框13", "轮号休息时间："))
	table.insert(result, jy_ui.addSpinner("轮号休息", {"0", "15", "30", "45", "60", "75", "90"}, 0))
	table.insert(result, jy_ui.addTextView("文框14", "分钟"))

	return result
end

-- 功能标签页配置（参数对齐 jy-frame/脚本/JUI.lua 的 UI）
local UI = {}
-- local UI = {
-- 	interface_name = "设置",
-- 	{			--功能
-- 		tab_name = "功能",
-- 		idx = 1,
-- 		controls = {
-- 			jy_ui.addRow(),
-- 			jy_ui.addSpinner("配置", UIConf["config_list"], 0),
-- 			jy_ui.addButton("读取配置", "读取配置", "readUISet()"),
-- 			jy_ui.addButton("保存配置", "保存配置", "saveUISet()"),

-- 			jy_ui.addRow(),
-- 			jy_ui.addCheckBox("多选框", "多选框", true),

-- 			jy_ui.addRow(),
-- 			jy_ui.addTextView("选择提示", "下拉框 --->"),
-- 			jy_ui.addSpinner("下拉框", {"默认", "选项1", "战斗", "斗争", "推荐"}, 0),

-- 			jy_ui.addTextView("文本框1", "文本框"),
-- 			jy_ui.addEditText("文本框", "", ""),
-- 		}
-- 	},
-- 	{			--野外
-- 		tab_name = "野外",
-- 		idx = 2,
-- 		controls = {
-- 		}
-- 	},
-- 	{			--账号设置
-- 		tab_name = "账号设置",
-- 		idx = 4,
-- 		controls = createUIAcc(10)
-- 	},
-- }

-- ============ 描述表 → 引擎控件节点（JY UIUtils reqName/properties → ui.* JSON） ============
-- 宽度语义：p.w=-2(未指定) → 弹性(w=0+weight=1)；数值 → 固定像素（对齐 JUI 账号行 180/50）
local function toNode(v)
	local p = v.properties
	local req = v.reqName
	if req == "多选框" then
		return { type = "checkbox", id = p.name, text = tostring(p.tip or p.name), checked = p.checked == true }
	elseif req == "下拉框" then
		return { type = "spinner", id = p.name, items = p.list or {},
		         selection = p.selIdx or 0, w = 0, weight = 1 }
	elseif req == "输入框" then
		local node = { type = "edit", id = p.name,
		               hint = tostring(p.tip or ""), text = tostring(p.defText or "") }
		if p.w and p.w ~= -2 then node.w = p.w else node.w = 0; node.weight = 1 end
		return node
	elseif req == "文字框" then
		return { type = "text", id = p.name, text = tostring(p.text or ""), textSize = 14 }
	elseif req == "按钮" then
		return { type = "button", id = p.name, text = tostring(p.text or p.name) }
	end
	return nil
end

-- ============ 面板组装：pager 多标签页 + 底部【退出/确定】 ============
local function buildPanel(winH)
	local pages, titles = {}, {}
	for _, tab in ipairs(UI) do
		local rows, cur = {}, nil
		local function newRow()
			cur = { type = "linear", orientation = "h", w = "match", children = {} }
			rows[#rows + 1] = cur
		end
		for _, v in ipairs(tab.controls) do
			if v.reqName == "换行" then
				newRow()
			else
				local node = toNode(v)
				if node then
					if cur == nil then newRow() end
					cur.children[#cur.children + 1] = node
				end
			end
		end
		pages[#pages + 1] = { type = "scroll", w = "match", h = 0, weight = 1,
			title = tostring(tab.tab_name),   -- TabLayout 页签文字（JsonViewFactory 读子页 title）
			children = { { type = "linear", orientation = "v", w = "match", children = rows } } }
		titles[#titles + 1] = tostring(tab.tab_name)
	end
	return {
		type = "linear", id = "__root", orientation = "v", padding = 12,
		-- 注意：root 树 h 不能用 "match"——引擎 create() 里 `h > 0` 会把 MATCH_PARENT(-1)
		-- 过滤掉回退 WRAP_CONTENT，pager 的 weight 随之失效；用与窗口同款百分比（解析为正像素）
		w = "match", h = winH,
		bg = { color = "#FFFFFFFF", corner = 6, stroke = { color = "#FF000000", width = 1.5 } },
		children = {
			{ type = "text", id = "__title", text = tostring(UI["interface_name"] or ""),
			  textSize = 17, bold = true, gravity = "center", w = "match",
			  padTop = 8, padBottom = 8 },   -- 标题区加高
			{ type = "linear", id = "__titleLine", w = "match", h = "dp:1",
			  bg = { color = "#FFCCCCCC" } },   -- 标题下分割线（1dp 淡灰）
			{ type = "pager", id = "__pager", w = "match", h = 0, weight = 1,
			  titles = titles, children = pages },
			{ type = "linear", orientation = "h", w = "match", children = {
				{ type = "button", id = "__btnExit", text = "退出", w = 0, weight = 1 },
				{ type = "button", id = "__btnOk", text = "确定", w = 0, weight = 1 } } },
		},
	}
end

-- ============ 配置读写（复刻 SaveUISet/ReadUISet/OnClose；多配置=引擎内置 profile） ============
-- 注意：必须 local 定义（引擎 _G 的 __newindex 代理会吞普通全局赋值），尾部再 rawset 补全局
local SaveUISet, ReadUISet, OnClose

-- ===== 最后配置记录：直接 io 读写，不走 JY.jy_file =====
-- 原因：jy_file.filePut 默认写 getWorkPath()/JYConf.txt，其内部 mWriteFile 对路径
-- 逐级 frameSdk.fileExist/mkdir 探测创建（/data 系统目录必失败抛错），上层 pcall
-- 吞掉 → JYConf 永远写不出 → "打开面板不回载上次配置"（实测踩坑，UISet*.txt 用
-- io.open 直写则正常）。文件名复用 UISet 前缀：命中插件同步 DEVICE_GENERATED 保护
-- 清单，避免"本地无 → 删设备端"
local REC_FILE = "UISet最后配置.txt"

local function cfgPath(name)
	return UIConf.config_path .. "/UISet" .. tostring(name) .. ".txt"
end

local function recPut(value)
	local f = io.open(UIConf.config_path .. "/" .. REC_FILE, "w")
	if not f then return nil, "cannot write " .. REC_FILE end
	f:write(tostring(value or ""))
	f:close()
	-- root 引擎进程写的文件放权给 app 进程通道（IDE 同步/覆盖/删除）
	pcall(os.execute, "chmod 666 '" .. UIConf.config_path .. "/" .. REC_FILE .. "'")
	return true
end

local function recGet()
	local f = io.open(UIConf.config_path .. "/" .. REC_FILE, "r")
	if not f then return nil end
	local s = f:read("*l")
	f:close()
	if s and s ~= "" then return s end
	return nil
end

local function curCfgName()
	local sel = UISet.win and ui.get(UISet.win, "配置")
	local idx = (type(sel) == "table" and sel.selectedIndex or 0) + 1
	return UIConf.config_list[idx] or "配置1"
end

-- ===== 快速存取：一次 tree.get 收集全树值（替代引擎逐控件 op）=====
-- 引擎 ui.saveProfile 内部对每个值控件各发一次 node.get（40+ 控件 = 40+ 次跨线程
-- 往返，实测单次保存 ~1s，主线程忙时更久，且拉大事件队列洪流窗口挤掉 click）；
-- tree.get 的 JSON 已含每个控件的 type+值字段（text/checked/selectedIndex/...），
-- 一次拿全。回填仍需逐控件 ui.set（Java 无批量 op），但省掉每控件先 get 取 type。
local _SAVABLE = { edit = true, checkbox = true, switch = true, spinner = true,
                   seek = true, progress = true, rating = true }

local function fastCollect(win)
	local data = {}
	local function walk(n)
		if type(n) ~= "table" then return end
		local id, t = n.id, n.type
		if type(id) == "string" and id ~= "" and t and _SAVABLE[t]
			and string.sub(id, 1, 2) ~= "__" then
			local v
			if t == "edit" then v = n.text
			elseif t == "checkbox" or t == "switch" then v = n.checked == true
			elseif t == "spinner" then v = n.selectedIndex or 0
			elseif t == "seek" or t == "progress" then v = n.progress
			elseif t == "rating" then v = n.rating or n.progress end
			if v ~= nil then data[id] = v end
		end
		if type(n.children) == "table" then
			for _, c in ipairs(n.children) do walk(c) end
		end
	end
	walk(ui.tree(win))
	return data
end

local function fastSave(path, win)
	local j = rawget(_G, "json")
	if type(j) ~= "table" or type(j.encode) ~= "function" then return nil, "json unavailable" end
	local okE, enc = pcall(j.encode, fastCollect(win))
	if not okE or type(enc) ~= "string" then return nil, "encode failed: " .. tostring(enc) end
	local f = io.open(path, "w")
	if not f then return nil, "cannot write " .. tostring(path) end
	f:write(enc)
	f:close()
	return true
end

local function fastLoad(path, win)
	local j = rawget(_G, "json")
	if type(j) ~= "table" or type(j.decode) ~= "function" then return nil, "json unavailable" end
	local f = io.open(path, "r")
	if not f then return nil, "no such profile: " .. tostring(path) end
	local content = f:read("*a")
	f:close()
	local okD, data = pcall(j.decode, content)
	if not okD or type(data) ~= "table" then return nil, "bad profile json: " .. tostring(path) end
	-- 与当前树对比，只收集值有变化的控件，一次 node.setMulti 批量回填：
	-- 每次 UI op 都在引擎侧持 LuaState 锁阻塞等主线程，op 越多其他协程（dispatch
	-- 轮询/interrupt）被饿越久——"点击无反应几秒/停止卡几秒"的根因，op 数必须压到最少
	local sets = {}
	local function walk(n)
		if type(n) ~= "table" then return end
		local id, t = n.id, n.type
		if type(id) == "string" and id ~= "" and t and _SAVABLE[t]
			and string.sub(id, 1, 2) ~= "__" and data[id] ~= nil then
			local v = data[id]
			local attrs
			if t == "edit" then
				if n.text ~= tostring(v) then attrs = { text = tostring(v) } end
			elseif t == "checkbox" or t == "switch" then
				if (n.checked == true) ~= (v == true) then attrs = { checked = v == true } end
			elseif t == "spinner" then
				if (n.selectedIndex or 0) ~= (tonumber(v) or 0) then attrs = { selection = tonumber(v) or 0 } end
			elseif t == "seek" or t == "progress" then
				if (n.progress or 0) ~= (tonumber(v) or 0) then attrs = { progress = tonumber(v) or 0 } end
			elseif t == "rating" then
				if (n.rating or n.progress or 0) ~= (tonumber(v) or 0) then attrs = { rating = tonumber(v) or 0 } end
			end
			if attrs ~= nil then sets[#sets + 1] = { id = id, attrs = attrs } end
		end
		if type(n.children) == "table" then
			for _, c in ipairs(n.children) do walk(c) end
		end
	end
	walk(ui.tree(win))
	if #sets == 0 then return true end
	local s = rawget(_G, "uimgr")
	if type(s) ~= "table" and type(s) ~= "userdata" then return nil, "uimgr unavailable" end
	local okE, cmd = pcall(j.encode, { op = "node.setMulti", win = tostring(win), sets = sets })
	if not okE or type(cmd) ~= "string" then return nil, "encode failed" end
	local okO, res = pcall(function() return s:op(cmd) end)
	if not okO then return nil, tostring(res) end
	local r = j.decode(res or "{}")
	if type(r) ~= "table" or not r.ok then
		return nil, (type(r) == "table" and tostring(r.err or "setMulti failed")) or "setMulti failed"
	end
	local miss = r.data and r.data.missing
	if type(miss) == "table" and #miss > 0 then
		print("[JUI] setMulti missing: " .. tostring(table.concat(miss, ",")))
	end
	return true
end

--保存设置
SaveUISet = function()
	if not UISet.win then return end
	local st = rawget(_G, "systemTime")
	local t0 = st and st() or 0
	local name = curCfgName()
	local t1 = st and st() or 0
	local path = cfgPath(name)
	local ok, err = fastSave(path, UISet.win)
	local t2 = st and st() or 0
	-- root 进程写入放权：后台协程执行——os.execute fork sh 可达秒级，不阻塞点击回调
	thread.start(function() pcall(os.execute, "chmod 666 '" .. path .. "'") end)
	print(("[JUI] saveProfile → %s : %s (取名%dms 存档%dms)"):format(path, tostring(ok and "ok" or err), t1 - t0, t2 - t1))
	if ok then ui.toast("已保存 " .. name) else ui.toast("保存失败：" .. tostring(err)) end
end

--加载设置
ReadUISet = function()
	if not UISet.win then return end
	local st = rawget(_G, "systemTime")
	local t0 = st and st() or 0
	local name = curCfgName()
	local t1 = st and st() or 0
	local path = cfgPath(name)
	local ok, err = fastLoad(path, UISet.win)
	local t2 = st and st() or 0
	print(("[JUI] loadProfile ← %s : %s (取名%dms 回填%dms)"):format(path, tostring(ok and "ok" or err), t1 - t0, t2 - t1))
	if ok then ui.toast("已读取 " .. name) else ui.toast("读取失败：" .. tostring(err)) end
end

-- 关闭保存（复刻 OnClose）：存当前配置 + 记录最后配置名（下次启动预载）
OnClose = function()
	print("[JUI] OnClose enter")   -- 卡顿定位：此行时间戳晚=事件派发被堵；晚于此的耗时=op 链路被堵
	SaveUISet()
	recPut(curCfgName())
	UISet.showStatus = false
end

-- 卡顿看门狗：面板存续期每 2s 一拍。thread.sleep 超差 → LuaState 锁被长 op 占持；
-- ui.get 探针 op >500ms → app 主线程（或传输通道）堵。平时零输出，不刷屏
local function startStallProbe()
	thread.start(function()
		while UISet.showStatus do
			local t0 = systemTime()
			if not pcall(thread.sleep, 2000) then break end
			local schedLag = systemTime() - t0 - 2000
			if schedLag > 500 then
				print(("[probe] 调度卡 %dms（LuaState 锁被占持）"):format(schedLag))
			end
			local t1 = systemTime()
			pcall(ui.get, UISet.win, "__title")
			local opLag = systemTime() - t1
			if opLag > 500 then
				print(("[probe] op 延迟 %dms（app 主线程/通道堵）"):format(opLag))
			end
		end
	end)
end

local function callNamedHandler(handlerStr)
	local fname = string.match(tostring(handlerStr or ""), "([%w_]+)")
	local f = fname and rawget(_G, fname)
	if type(f) == "function" then
		pcall(f)
	else
		print("[JUI] UI 事件函数不存在：", tostring(handlerStr))
	end
end

-- ============ 显示面板 + 阻塞主循环（复刻 createUI 的 show + while showStatus） ============
local function ShowJUI(uiConf)
	if uiConf == nil and type(uiConf) ~= "table" then
		print("[JUI] Invalid UI configuration")
		return nil, "Invalid UI configuration"
	end
	UI = uiConf
	-- h 超屏降级：原框架 ui_height=1800 在竖屏(1280)上底部按钮会落到屏外，
	-- 超过 1200 时改用屏幕比例（保留 UIConf 原值以对齐参数）
	local winH = UIConf.ui_height
	if type(winH) == "number" and winH > 1200 then winH = "72%" end
	local win, err = ui.createWindow({
		win = "jy_set", x = 20, y = 80,
		w = UIConf.ui_width, h = winH, drag = true, focus = true -- focusable：EditText 才能获焦输入（阻塞模态面板期不干扰脚本）
	}, buildPanel(winH))
	if not win then
		print("[JUI] createWindow failed: " .. tostring(err))
		return nil, err
	end
	UISet.win = win
	UISet.showStatus = true
	UISet.exitFlag = false
	startStallProbe()   -- 卡顿看门狗（见定义；面板关后自动退出）

	-- 绑定：描述按钮 handler 字符串 + 内置【退出/确定】
	for _, tab in ipairs(UI) do
		for _, v in ipairs(tab.controls) do
			if v.reqName == "按钮" and v.properties.onClick then
				ui.bind(win, v.properties.name, "click",
					function() callNamedHandler(v.properties.onClick) end)
			end
		end
	end
	ui.bind(win, "__btnOk", "click", function()
		OnClose()            -- 保存 + 记录最后配置
		ui.close(win)
	end)
	ui.bind(win, "__btnExit", "click", function()
		OnClose()
		UISet.exitFlag = true
		ui.close(win)
	end)

	-- 启动预载：上次退出时的配置（复刻 createUI 尾部 loadProfile；recGet 见上方说明）
	local last = recGet()
	if last then
		local okl, errl = fastLoad(cfgPath(last), win)
		print(("[JUI] 预载 %s : %s"):format(tostring(last), tostring(okl and "ok" or errl)))
	end

	-- 阻塞直至【确定】(继续) /【退出】(结束脚本)——
	-- 主栈用全局 sleep（lSleep 放锁让事件派发并行、interrupt 可杀）；协程内 thread.sleep
	while UISet.showStatus do
		if not pcall(thread.sleep, 200) then sleep(200) end
	end
	UISet.win = nil
	if UISet.exitFlag then
		error("exitScript", 0)   -- 在调用方上下文终止（引擎识别 → OnStop → 容器回收）
	end
	return true
end

-- JY 按钮 handler 为字符串约定（"readUISet()" 取函数名调全局）——
-- 引擎 _G 带 __newindex 语义糖代理：普通全局赋值静默失效（__SCRIPT_DIR__ 同款坑），
-- 供 callNamedHandler（rawget(_G,...)）与外部调用的函数必须 rawset 显式写入；
-- 补齐原版大小写别名（readUISet/saveUISet）
rawset(_G, "SaveUISet", SaveUISet)
rawset(_G, "ReadUISet", ReadUISet)
rawset(_G, "OnClose", OnClose)
rawset(_G, "readUISet", ReadUISet)
rawset(_G, "saveUISet", SaveUISet)
rawset(_G, "ShowJUI", ShowJUI)

return { ShowJUI = ShowJUI, UIConf = UIConf, UISet = UISet }

