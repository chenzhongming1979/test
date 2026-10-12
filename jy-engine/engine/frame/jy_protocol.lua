--[[ jy_protocol.lua —— jy-frame 云控协议参考实现（挂载于引擎内置 cloud 纯通道）
装载即注册 cloud.onMessage(dispatch)；main.lua 用 cloud.run{onIdle=jy.onIdle} 驱动泵。
协议契约详见 jy-frame-engine/docs/features/cloud/contracts.md。

协议（每条消息带可选 req 回执关联；响应 {op="result",req,ok,ret,msg,state?,echoOp?,ts}）：
  {"op":"ping"}                               → pong
  {"op":"state"}                              → 框架状态摘要
  {"op":"sync","taps":{..},"conf":{..},"vars":{..}}  → 合并下发（未 init 合并入本地骨架；已 init 写 G 注册表）
  {"op":"init"}                               → 显式 EnvInit（幂等，sync 后自动标记需重 init）
  {"op":"pageTest","page":"模块名"}            → 层1：TaskScheduler:runWait(taps[page])，回退出码
  {"op":"taskTest","task":"任务名"}            → 层2：任务列表任务（参数预处理→前置→actions→taskRun→通用退出）
  {"op":"action","row":{...}}                 → 层3：单特征行执行（任务参数预处理），回退出码
  {"op":"scriptRun","mode":5}                 → 层4：完整脚本（独立协程，阻塞主流程）
  {"op":"stop"}                               → 停 scriptRun 协程
  {"op":"auth","card":"卡密"}                 → 授权（Provider 契约：builtin 白名单 / custom dofile 适配器）
  {"op":"get","key":"taps|conf|vars"?}        → 读回框架数据（inited→G 注册表；未 init→骨架+pending 预览；无 key=全量）
  {"op":"panel","uiConf":{...}}               → 弹 JUI 设置面板（阻塞至确定/退出），确定后回传 UI 生成 JSON {uiset}
  {"op":"tree"}                               → 回传面板控件树 ui.tree("jy_set")（面板开着才有效）
  {"op":"uiset"}                              → 读最后保存的 UI 配置（UISet最后配置.txt → UISet<名>.txt）
内联下发兼容（旧 modle 协议接收方案并 op 协议）：pageTest/taskTest/action/scriptRun 可随消息
直接携带 "taps"/"conf"/"vars" 字段——携带即独占替换环境并强制重建（原 modle 1/3/4/5 的
"消息即完整环境"语义），且【原样透传 EnvInit】：本工程设备端零预置数据，全靠 WS 下发，
不补骨架默认/不归一化（缺参报错回包，来方责任）；不携带则维持 sync 增量语义。
scriptRun 另可带 "uiConf"（先弹设置面板阻塞至确定/退出，确定后接收内联数据再启动脚本，
对齐旧 modle 5）。
执行模型：cloud.run 主线程串行分发（G 全局状态不串）；scriptRun 独立协程。
授权：jy.conf.auth 配置后启用拦截矩阵（未授权时执行类/sync/init 拒绝，ping/state/debug/stop 放行）
      + 心跳保活（onIdle 内计时，连续失败 fail_limit 次 → expired）；
      conf.auth 可经 sync 下发覆盖（存 jy.conf.auth，不写 G 注册表）；
      auth 未配置时完全不拦截（向后兼容，本地调试态）。
日志：装载时包装 print → {"op":"log","line","ts"} 广播（引擎统一采集之外的云控实时推送）。
依赖：引擎内置 cloud 全局（纯 WS 通道，引擎版本需含 LuaEnvContributor 注入）、JY 核心（jyframe.lua 已装载）。
--]]

local jy = {}

-- ==================== 状态 ====================
jy.conf = {
    dir = rawget(_G, "__SCRIPT_DIR__"),  -- custom Provider dofile 根（默认工程根）
    auth = nil,                          -- 授权配置 {provider="builtin"|"custom", module=?, cards=?, heartbeat_interval=?, fail_limit=?}
                                         -- （sync 可下发覆盖；nil=未配置→完全不拦截，向后兼容）
}
jy.state = {
    inited = false,       -- EnvInit 是否已执行
    dirty = false,        -- sync 后需要重新 EnvInit
    scriptThread = nil,   -- scriptRun 协程句柄
    lastOp = nil,
    connCount = 0,
    auth = {              -- 授权状态机：unauth →(login 成功)→ authorized →(心跳超限/到期)→ expired；login 失败 → denied（可重新 auth）
        status = "unauth",
        token = nil,
        expire = 0,
        provider = nil,
        card = nil,
    },
}

-- 待 init 的本地骨架（sync 在 init 前到达时先合并到这里）
local pending = { taps = nil, conf = nil, vars = nil }

-- 日志（print 直出；云控广播由本文件装载时的 print 包装统一转发，勿在此重复推送）
local function LOG(...)
	print("[cloud]", ...)
end

-- ==================== 基础骨架（EnvInit 的最小前置） ====================
-- conf 必备键：appid(数组,initStatus 索引)/开发比例(freemInit)/显示浮窗(false)/卡屏检测(false)
-- taps 必备键：任务列表/任务列表模块（initTasks 遍历）
local function baseSkeleton()
    local w, h = frameSdk.getDisplaySize()
    if (tonumber(w) or 0) <= 0 then w, h = 720, 1280 end
    return {
        conf = {
            appid = { "" },
            ["开发比例"] = { w, h },
            ["显示浮窗"] = false,
            ["卡屏检测"] = false,
            ["是否打印输出"] = true,
            ["日志记录"] = false,
            ["游戏模式"] = 5,
            ["循环次数"] = 1,
            ["无特征次数"] = 0,
            outTime = 60,
            timeout = 4000,
            ms = 80,
            comWait = 500,
            comSleep = 700,
            ["任务循环延迟"] = 80,
            ["启动线程"] = false,
            ["二次登录"] = false,
        },
        taps = {
            ["任务列表"] = {},
            ["任务列表模块"] = {},
        },
        vars = {
            ["任务变量"] = {},
        },
    }
end

local function deepMerge(dst, src)
    if type(src) ~= "table" then return dst end
    for k, v in pairs(src) do
        if type(v) == "table" and type(dst[k]) == "table" then
            deepMerge(dst[k], v)
        else
            dst[k] = v
        end
    end
    return dst
end

-- JSON → Lua 键归一化：cjson 解码 JSON 对象产生的数字键是字符串（"1"≠1），
-- JY 消费 taps 行用 ipairs 数字索引——递归把纯数字字符串键转为 number 键
local function normalizeKeys(t)
    if type(t) ~= "table" then return t end
    local out = {}
    for k, v in pairs(t) do
        local nk = k
        if type(k) == "string" and k:match("^%d+$") then nk = tonumber(k) end
        out[nk] = normalizeKeys(v)
    end
    return out
end

-- ==================== EnvInit（懒执行 + sync 后重建） ====================
local inlineFresh = false   -- 内联数据待 EnvInit 直灌标志（ensureInit 据此绕过骨架合并，见 applyInline）
local function ensureInit()
    if jy.state.inited and not jy.state.dirty then return true end
    local mTaps, mConf, mVars
    if inlineFresh then
        -- 内联下发：WS 数据即完整环境，原样透传（用户契约：设备端零预置数据，
        -- 不补骨架默认/不归一化，缺参 → EnvInit 报错经 pcall 回 result ok:false，来方责任）
        mTaps, mConf, mVars = pending.taps, pending.conf, pending.vars
        inlineFresh = false
    else
        local skel = baseSkeleton()
        mTaps, mConf, mVars = skel.taps, skel.conf, skel.vars
        if pending.taps ~= nil then deepMerge(mTaps, pending.taps) end
        if pending.conf ~= nil then deepMerge(mConf, pending.conf) end
        if pending.vars ~= nil then deepMerge(mVars, pending.vars) end
    end
    LOG("[cloud] EnvInit 开始")
    EnvInit(mTaps, mConf, mVars)
    jy.state.inited = true
    jy.state.dirty = false
    LOG("[cloud] EnvInit 完成")
    return true
end

-- ==================== 旧协议内联下发兼容（原 modle 1/3/4/5 接收方案） ====================
-- 数据源契约（用户拍板）：本工程设备端零预置数据，taps/conf/vars/uiConf 全靠 WS 下发。
-- 内联携带时【原样透传】EnvInit（不补骨架默认/不归一化，缺参 → EnvInit 报错回
-- result ok:false，来方责任，对齐 2026-09-22 main.lua 透传契约）；独占替换 pending
-- + inlineFresh 直灌（绕过骨架合并）；不携带则维持 sync 增量语义。
local function applyInline(msg)
    if type(msg.taps) ~= "table" and type(msg.conf) ~= "table" and type(msg.vars) ~= "table" then
        return false
    end
    if type(msg.conf) == "table" and type(msg.conf.auth) == "table" then
        jy.conf.auth = jy.conf.auth or {}
        deepMerge(jy.conf.auth, msg.conf.auth)
        msg.conf.auth = nil
        LOG("[cloud] conf.auth 已更新 provider=", tostring(jy.conf.auth.provider))
    end
    pending.taps = msg.taps
    pending.conf = msg.conf
    pending.vars = msg.vars
    inlineFresh = true
    jy.state.dirty = true
    LOG("[cloud] 内联下发已接收（原样透传） taps=", tostring(msg.taps ~= nil),
        " conf=", tostring(msg.conf ~= nil), " vars=", tostring(msg.vars ~= nil))
    return true
end

-- ==================== 4 层执行实现（内联，回退出码） ====================

-- 层1：pageTest —— TaskScheduler:runWait(taps[page])
local function doPageTest(page)
    if not ensureInit() then return false, "init failed" end
    local taps = G.taps()
    if taps == nil or taps[page] == nil then
        return false, "无此任务模块: " .. tostring(page)
    end
    local TaskScheduler = require("JY.Task.TaskScheduler")
    LOG("[cloud] pageTest →", page)
    local ret = TaskScheduler.new():runWait(taps[page])
    return true, "退出码=" .. tostring(ret), ret
end

-- 层2：taskTest —— 任务列表任务（复刻 FrameRun.taskTest，取退出码）
local function doTaskTest(taskName)
    if not ensureInit() then return false, "init failed" end
    local taps = G.taps()
    local task = nil
    for _, value in pairs(taps["任务列表"] or {}) do
        if type(value) == "table" and value[1] == taskName then task = value end
    end
    if task == nil then
        return false, "任务列表中无此任务: " .. tostring(taskName)
    end
    local TaskScheduler = require("JY.Task.TaskScheduler")
    local ActionFramework = require("JY.Actions.ActionFramework")
    local jy_frame = require("JY.Utils.FrameUtils")
    local script_task = require("JY.Process.TaskProcessControl")
    -- 任务参数预处理（与 FrameRun.taskTest 一致）
    if task["任务参数"] ~= nil and type(task["任务参数"]) == "table" and task["任务参数"]["执行模式"] == nil then
        local p = task["任务参数"]
        task["任务参数"] = jy_frame.taskParamSet(p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8], p[9])
    end
    -- 前置操作
    if task["任务参数"] ~= nil and task["任务参数"]["任务执行前操作"] ~= nil then
        TaskScheduler.new():runWait(task["任务参数"]["任务执行前操作"])
    end
    LOG("[cloud] taskTest →", taskName)
    local ret = ActionFramework.actions(task)
    if ret and ret ~= 2 then
        script_task.taskRun(task)
    end
    TaskScheduler.new():runWait("通用任务退出")
    return true, "退出码=" .. tostring(ret), ret
end

-- 层3：action —— 单特征行（JSON 对象形态 {"1":名,"2":超时,"ff":..} → normalize → actions）
local function doAction(row)
    if not ensureInit() then return false, "init failed" end
    if type(row) ~= "table" then
        return false, "row 格式错误：需 {\"1\":名,\"2\":超时,\"ff\":...,参数...}"
    end
    row = normalizeKeys(row)
    if row[1] == nil then
        return false, "row 格式错误：需 {\"1\":名,\"2\":超时,\"ff\":...,参数...}"
    end
    local ActionFramework = require("JY.Actions.ActionFramework")
    local jy_frame = require("JY.Utils.FrameUtils")
    if row["任务参数"] ~= nil and type(row["任务参数"]) == "table" and row["任务参数"]["执行模式"] == nil then
        local p = row["任务参数"]
        row["任务参数"] = jy_frame.taskParamSet(p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8], p[9])
    end
    LOG("[cloud] action →", tostring(row[1]))
    local ret = ActionFramework.actions(row)
    return true, "退出码=" .. tostring(ret), ret
end

-- 层4：scriptRun —— EnvInit + Model/Model_5（独立协程）
local function doScriptRun(mode)
    if not ensureInit() then return false, "init failed" end
    if jy.state.scriptThread ~= nil then
        return false, "scriptRun 已在运行（先 stop）"
    end
    local th = frameSdk.Thread
    local body = function()
        if mode == 5 then
            local model = require("JY/Process/Model_5")
            model.run()
        else
            local model = require("JY.Process.Model")
            model.run()
        end
    end
    local ok, tid = pcall(th.newThread, body)
    if not ok or tid == nil then
        return false, "scriptRun 协程启动失败: " .. tostring(tid)
    end
    jy.state.scriptThread = tid
    return true, "scriptRun 已启动（mode=" .. tostring(mode) .. "）"
end

-- ==================== auth 授权（Provider 契约 + 心跳调度） ====================
-- Provider 契约（contracts.md §4.4，作者实现 auth_provider.lua）：
--   M.login(card, device)  → true, token, expireTs | false, 原因
--   M.heartbeat(token)     → true, remainSeconds | false, 原因
--   M.logout(token)        → 可选
-- 心跳归本文件：onIdle 内计时（os.time 差 ≥ heartbeat_interval 触发），
-- 连续失败 fail_limit 次 → status="expired"，成功即清零计数并按 remainSeconds 续期 expire。

-- 心跳调度状态
local hbLast = 0      -- 上次心跳尝试时间（os.time 秒）
local hbFails = 0     -- 连续失败计数（成功清零）
-- custom Provider 缓存（按 module 名；auth 重复调用/心跳复用，不重复 dofile）
local providerCache = { module = nil, mod = nil }

-- 设备指纹（框架自动补；autolua2 无 getDeviceId 时降级 "unknown"）
local function deviceFingerprint()
    local fs = rawget(_G, "frameSdk")
    if type(fs) == "table" and fs.getDeviceId ~= nil then
        local ok, id = pcall(fs.getDeviceId)
        if ok and type(id) == "string" and id ~= "" then return id end
    end
    return "unknown"
end

-- builtin 测试 Provider：白名单卡密（jy.conf.auth.cards），命中即授权，heartbeat 恒真
local function builtinProvider()
    return {
        login = function(card, device)
            local cards = (jy.conf.auth or {}).cards
            if type(cards) == "table" then
                for _, c in ipairs(cards) do
                    if tostring(c) == card then
                        return true, "builtin-" .. card, os.time() + 3600
                    end
                end
            end
            return false, "卡密不在白名单"
        end,
        heartbeat = function(token)
            local auth = jy.state.auth or {}
            return true, math.max(0, (auth.expire or os.time()) - os.time())
        end,
        logout = function(token) return true end,
    }
end

-- Provider 装载：builtin 内置；custom dofile <dir>/<module>.lua（失败返回 nil, 原因）
local function loadProvider()
    local conf = jy.conf.auth
    if type(conf) ~= "table" or conf.provider == nil then
        return nil, "auth 未配置（jy.conf.auth 缺失或无 provider）"
    end
    if conf.provider == "builtin" then return builtinProvider() end
    if conf.provider == "custom" then
        local module = tostring(conf.module or "auth_provider")
        if providerCache.module == module and providerCache.mod ~= nil then
            return providerCache.mod
        end
        local dir = jy.conf.dir or rawget(_G, "__SCRIPT_DIR__") or "."
        local path = dir .. "/" .. module .. ".lua"
        local okF, mod = pcall(dofile, path)
        if not okF or type(mod) ~= "table" then
            return nil, "Provider 装载失败(" .. path .. "): " .. tostring(mod)
        end
        providerCache.module = module
        providerCache.mod = mod
        return mod
    end
    return nil, "未知 provider: " .. tostring(conf.provider)
end

-- auth 是否已配置（未配置 → dispatch 完全不拦截，向后兼容）
local function authConfigured()
    local conf = jy.conf.auth
    return type(conf) == "table" and conf.provider ~= nil
end

-- auth 是否处于有效授权（expire 到期就地翻转为 expired）
local function authAuthorized()
    local a = jy.state.auth
    if type(a) ~= "table" or a.status ~= "authorized" then return false end
    if a.expire ~= nil and a.expire > 0 and os.time() > a.expire then
        a.status = "expired"
        LOG("[cloud] 授权 expire 到期 → expired")
        return false
    end
    return true
end

-- auth 对外摘要（不回传 token）
local function authPublic(a)
    return {
        status = a.status,
        provider = a.provider,
        card = a.card,
        expire = a.expire or 0,
        remain = math.max(0, (a.expire or 0) - os.time()),
    }
end

-- 心跳调度（onIdle 调用）：authorized 时按 heartbeat_interval 计时保活
local function pollHeartbeat()
    local auth = jy.state.auth
    if type(auth) ~= "table" or auth.status ~= "authorized" then return end
    local conf = jy.conf.auth
    if type(conf) ~= "table" then return end
    local now = os.time()
    if auth.expire ~= nil and auth.expire > 0 and now > auth.expire then
        auth.status = "expired"
        LOG("[cloud] 授权 expire 到期 → expired")
        return
    end
    local interval = tonumber(conf.heartbeat_interval) or 300
    if now - hbLast < interval then return end
    hbLast = now
    local okH, remain, reason = false, nil, nil
    local prov, perr = loadProvider()
    if prov == nil then
        reason = perr
    else
        local okC, e1, e2 = pcall(prov.heartbeat, auth.token)
        if okC and e1 == true then
            okH, remain = true, tonumber(e2)
        else
            reason = okC and tostring(e2) or ("Provider heartbeat 异常: " .. tostring(e1))
        end
    end
    if okH then
        hbFails = 0
        if remain ~= nil and remain > 0 then auth.expire = os.time() + remain end
        LOG("[cloud] heartbeat ok remain=", tostring(remain))
    else
        hbFails = hbFails + 1
        local limit = tonumber(conf.fail_limit) or 3
        LOG("[cloud] heartbeat 失败(", tostring(hbFails), "/", tostring(limit), "): ", tostring(reason))
        if hbFails >= limit then
            auth.status = "expired"
            LOG("[cloud] 连续心跳失败达 fail_limit → expired")
        end
    end
end

-- ==================== 协议分发 ====================

local handlers = {}

handlers["ping"] = function(msg)
    return true, "pong", nil
end

handlers["state"] = function(msg)
    local ok, t = pcall(function()
        local a = jy.state.auth
        return {
            inited = jy.state.inited,
            dirty = jy.state.dirty,
            enc = cloud.isEncrypted and cloud.isEncrypted() or false,
            scriptRunning = jy.state.scriptThread ~= nil,
            lastOp = jy.state.lastOp,
            auth = type(a) == "table" and authPublic(a) or nil,
            tapsModules = jy.state.inited and (function()
                local n = 0
                for _ in pairs(G.taps() or {}) do n = n + 1 end
                return n
            end)() or 0,
        }
    end)
    if ok then return true, t, nil, t end
    return true, "state 获取失败: " .. tostring(t)
end

handlers["sync"] = function(msg)
    -- conf.auth 特殊透传：Provider 配置存 jy.conf.auth（不写 G 注册表/骨架，不参与 normalizeKeys）
    local n = 0
    if type(msg.conf) == "table" and type(msg.conf.auth) == "table" then
        jy.conf.auth = jy.conf.auth or {}
        deepMerge(jy.conf.auth, msg.conf.auth)
        msg.conf.auth = nil
        n = n + 1
        LOG("[cloud] conf.auth 已更新 provider=", tostring(jy.conf.auth.provider))
    end
    if type(msg.taps) == "table" then msg.taps = normalizeKeys(msg.taps) end
    if type(msg.conf) == "table" then msg.conf = normalizeKeys(msg.conf) end
    if type(msg.vars) == "table" then msg.vars = normalizeKeys(msg.vars) end
    -- 未 init：合并入 pending（下次 ensureInit 生效）；已 init：直接写 G 注册表
    if jy.state.inited then
        if type(msg.taps) == "table" then
            for k, v in pairs(msg.taps) do G.setTaps(k, v) n = n + 1 end
        end
        if type(msg.conf) == "table" then
            for k, v in pairs(msg.conf) do G.setConfig(k, v) n = n + 1 end
        end
        if type(msg.vars) == "table" then
            for k, v in pairs(msg.vars) do G.setVars(k, v) n = n + 1 end
        end
        jy.state.dirty = true  -- taps 坐标换算等需重建，下次执行前重 EnvInit
    else
        pending.taps = pending.taps or {}
        pending.conf = pending.conf or {}
        pending.vars = pending.vars or {}
        if type(msg.taps) == "table" then deepMerge(pending.taps, msg.taps) n = n + 1 end
        if type(msg.conf) == "table" then deepMerge(pending.conf, msg.conf) n = n + 1 end
        if type(msg.vars) == "table" then deepMerge(pending.vars, msg.vars) n = n + 1 end
    end
    return true, "sync ok（" .. tostring(n) .. " 项）"
end

handlers["init"] = function(msg)
    if not ensureInit() then return false, "init failed" end
    return true, "EnvInit 完成"
end

handlers["debug"] = function(msg)
    -- 诊断：回传 G 注册表实际结构（taps 全量 / 指定键）
    if not jy.state.inited then return false, "not inited（先 init）" end
    local taps = G.taps()
    if msg.key ~= nil then
        return true, nil, nil, { value = taps[msg.key] }
    end
    local keys = {}
    for k in pairs(taps or {}) do keys[#keys + 1] = tostring(k) end
    return true, nil, nil, { keys = keys, taps = taps }
end

-- UI 生成配置读取：UISet最后配置.txt（最后配置名）→ UISet<名>.txt（JUI fastSave 的控件值 JSON）
-- 路径与 JUI.UIConf.config_path 同源（__SCRIPT_DIR__），读写恒同目录
local function readUISet()
    local j = rawget(_G, "json")
    if type(j) ~= "table" or type(j.decode) ~= "function" then return nil, "json 不可用" end
    local dir = rawget(_G, "__SCRIPT_DIR__") or "."
    local f = io.open(dir .. "/UISet最后配置.txt", "r")
    if not f then return nil, "无已保存 UI 配置（先 panel 确定）" end
    local name = f:read("*l") or ""
    f:close()
    local pf = io.open(dir .. "/UISet" .. name .. ".txt", "r")
    if not pf then return nil, "配置文件不存在: UISet" .. name .. ".txt" end
    local content = pf:read("*a")
    pf:close()
    local okD, data = pcall(j.decode, content)
    if not okD or type(data) ~= "table" then return nil, "配置 JSON 损坏: UISet" .. name .. ".txt" end
    return { name = name, values = data }
end

-- 读回框架数据：conf/vars/taps（inited→G 注册表；未 init→骨架+pending 预览，与 ensureInit 输入一致）
handlers["get"] = function(msg)
    local taps, conf, vars
    if jy.state.inited then
        taps, conf, vars = G.taps(), G.config(), G.vars()
    else
        local skel = baseSkeleton()
        if pending.taps ~= nil then deepMerge(skel.taps, pending.taps) end
        if pending.conf ~= nil then deepMerge(skel.conf, pending.conf) end
        if pending.vars ~= nil then deepMerge(skel.vars, pending.vars) end
        taps, conf, vars = skel.taps, skel.conf, skel.vars
    end
    local key = msg.key
    if key == nil then
        return true, nil, nil, { data = { taps = taps, conf = conf, vars = vars } }
    end
    if key == "taps" then return true, nil, nil, { data = taps } end
    if key == "conf" then return true, nil, nil, { data = conf } end
    if key == "vars" then return true, nil, nil, { data = vars } end
    return false, "未知 key: " .. tostring(key) .. "（taps/conf/vars）"
end

-- 弹 JUI 设置面板（阻塞至确定/退出，期间 pump 暂停——与旧 modle 5 同语义）；
-- 确定后 JUI 已 fastSave，读回 UI 生成的 JSON 一起回传（{uiset={name,values}}）
handlers["panel"] = function(msg)
    if type(msg.uiConf) ~= "table" then return false, "缺少 uiConf 字段" end
    local JUI = require("JUI")
    local okS, err = pcall(JUI.ShowJUI, msg.uiConf)
    if not okS then
        if err == "interrupt" or err == "exitScript" then
            return false, "面板退出（" .. tostring(err) .. "）"
        end
        return false, "panel failed: " .. tostring(err)
    end
    local data, rerr = readUISet()
    if data == nil then return false, rerr end
    return true, nil, nil, { uiset = data }
end

-- 面板控件树（含各控件当前值）：面板开着才有效（对齐旧 modle 6）
handlers["tree"] = function(msg)
    if type(ui) ~= "table" and type(ui) ~= "userdata" then return false, "ui 通道不可用" end
    local okT, t = pcall(ui.tree, "jy_set")
    if not okT or t == nil then
        return false, "tree 获取失败（面板未开？）: " .. tostring(t)
    end
    return true, nil, nil, { tree = t }
end

-- 读最后保存的 UI 配置 JSON（不弹面板，等价 panel 确定后的回传部分）
handlers["uiset"] = function(msg)
    local data, rerr = readUISet()
    if data == nil then return false, rerr end
    return true, nil, nil, { uiset = data }
end

handlers["pageTest"] = function(msg)
    applyInline(msg)
    if msg.page == nil then return false, "缺少 page 字段" end
    return doPageTest(msg.page)
end

handlers["taskTest"] = function(msg)
    applyInline(msg)
    if msg.task == nil then return false, "缺少 task 字段" end
    return doTaskTest(msg.task)
end

handlers["action"] = function(msg)
    applyInline(msg)
    return doAction(msg.row)
end

-- 层4：scriptRun —— uiConf 随消息携带时先弹面板（阻塞至确定/退出，旧 modle 5 语义），
-- 确定后 applyInline 接收 taps/conf/vars 再启动脚本协程
handlers["scriptRun"] = function(msg)
    if type(msg.uiConf) == "table" then
        local JUI = require("JUI")
        local okS, err = pcall(JUI.ShowJUI, msg.uiConf)
        if not okS then
            if err == "interrupt" or err == "exitScript" then
                return false, "面板退出（" .. tostring(err) .. "）"
            end
            return false, "panel failed: " .. tostring(err)
        end
    end
    applyInline(msg)
    return doScriptRun(msg.mode or 5)
end

handlers["stop"] = function(msg)
    if jy.state.scriptThread == nil then return true, "scriptRun 未在运行" end
    local thread = rawget(_G, "thread")
    if thread ~= nil and thread.stop ~= nil then
        pcall(thread.stop, jy.state.scriptThread)
    end
    jy.state.scriptThread = nil
    return true, "scriptRun 已停止"
end

handlers["auth"] = function(msg)
    local card = msg.card
    if type(card) ~= "string" or card == "" then
        return false, "缺少 card 字段（卡密）"
    end
    local prov, perr = loadProvider()
    if prov == nil then
        jy.state.auth.status = "denied"
        jy.state.auth.reason = perr
        return false, perr
    end
    local device = msg.device
    if type(device) ~= "string" or device == "" then device = deviceFingerprint() end
    local auth = jy.state.auth
    auth.status = "authing"  -- 过渡态（login 同步执行，外部不可观测，仅对齐状态机）
    auth.card = card
    auth.provider = jy.conf.auth.provider
    local okL, r1, r2, r3 = pcall(prov.login, card, device)
    if okL and r1 == true then
        auth.status = "authorized"
        auth.token = r2
        auth.expire = tonumber(r3) or (os.time() + 3600)
        auth.reason = nil
        hbLast = os.time()   -- 授权即起算心跳间隔
        hbFails = 0
        LOG("[cloud] auth 成功 card=", card, " provider=", tostring(auth.provider),
            " expire=", tostring(auth.expire))
        return true, nil, nil, { auth = authPublic(auth) }
    end
    local reason = okL and tostring(r2) or ("Provider login 异常: " .. tostring(r2))
    auth.status = "denied"
    auth.token = nil
    auth.expire = 0
    auth.reason = reason
    LOG("[cloud] auth 拒绝 card=", card, " 原因=", reason)
    return false, reason
end

-- 授权拦截矩阵：auth 已配置且未授权时，执行类/sync/init 一律拒绝
-- （ping/state/debug/get/uiset/tree/stop/auth 放行；jy.conf.auth 未配置时完全不拦截，向后兼容）
local AUTH_GUARDED = {
    sync = true, init = true, pageTest = true, taskTest = true,
    action = true, scriptRun = true, panel = true,
}

-- 分发入口：msg(table) → 回执表
local function dispatch(msg)
    if type(msg) ~= "table" or msg.op == nil then
        return { op = "result", ok = false, msg = "JSON 需含 op 字段" }
    end
    jy.state.lastOp = tostring(msg.op)
    local h = handlers[msg.op]
    if h == nil then
        return { op = "result", req = msg.req, ok = false, msg = "未知 op: " .. tostring(msg.op) }
    end
    if AUTH_GUARDED[msg.op] and authConfigured() and not authAuthorized() then
        return { op = "result", req = msg.req, ok = false, msg = "未授权(先 auth)" }
    end
    local ok, a, b, c = pcall(h, msg)
    if not ok then
        return { op = "result", req = msg.req, ok = false, msg = tostring(a) }
    end
    -- handlers 返回 (ok, msgOrState, ret, extraState)
    local resp = { op = "result", req = msg.req, ok = (a == true) }
    if type(b) == "string" then
        resp.msg = b
        if b:sub(1, #"退出码=") == "退出码=" then
            resp.ret = tonumber(b:sub(#"退出码=" + 1))
        end
    elseif type(b) == "table" then
        resp.state = b
    end
    if type(c) == "table" then
        resp.state = c
        resp.msg = nil
    end
    return resp
end

-- ==================== 装载即挂载（通道 → 协议） ====================

if type(cloud) ~= "table" or cloud.onMessage == nil or cloud.send == nil then
    error("jy_protocol 需引擎内置 cloud 通道（引擎版本过旧，无 LuaEnvContributor 注入）", 0)
end

-- 注册分发回调：每条 WS 消息 → dispatch → cloud.send 回包（广播语义）
cloud.onMessage(function(msg)
    local resp = dispatch(msg)
    resp.op = resp.op or "result"
    if type(msg) == "table" and msg.op ~= nil then resp.echoOp = msg.op end
    resp.ts = os.time()
    cloud.send(resp)
end)

-- print 桥：脚本日志实时推云控客户端（{"op":"log"} 广播；透传原 print，
-- IDE 通道/落盘不受影响；server 未启动时 cloud.send 静默失败，不丢本地日志）。
-- 幂等标志：主引擎 LuaState 跨 execute 保留，重复装载（同一 state 再运行）不得叠加包装
do
  if rawget(_G, "__jyf_cloud_print_bridge") ~= true then
    local origPrint = print
    _G.print = function(...)
        local n = select("#", ...)
        local parts = {}
        for i = 1, n do parts[i] = tostring(select(i, ...)) end
        local line = table.concat(parts, "\t")
        origPrint(line)
        cloud.send({ op = "log", line = line, ts = os.time() })
    end
    rawset(_G, "__jyf_cloud_print_bridge", true)
  end
end

-- ==================== 作者 API ====================

-- 注册/覆盖指令：op → function(msg) return ok, msgOrState, ret, extraState end
-- 装载后调用生效；同名覆盖内置 4 层执行，新名即自定义指令
function jy.register(op, fn)
    if type(op) ~= "string" or type(fn) ~= "function" then
        return false, "register 需 (op:string, fn:function)"
    end
    handlers[op] = fn
    return true
end

-- 空闲钩子（main.lua 传给 cloud.run{onIdle=}）：心跳保活 + 连接数同步
function jy.onIdle()
    pollHeartbeat()
    local srv = rawget(_G, "cloudServer")
    if srv ~= nil and srv.connections ~= nil then
        local ok, n = pcall(srv.connections)
        if ok and type(n) == "number" then jy.state.connCount = n end
    end
end

return jy

