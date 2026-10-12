package com.autolua.engine.cloud

import android.util.Log
import com.autolua.engine.base.LuaContext
import com.autolua.engine.base.LuaEnvContributor
import com.autolua.engine.base.ScriptContextLifecycleListener

/**
 * 云控通道（Java 下沉版）：WS server + PSK 帧加解密 + 消息队列在 CloudCore /
 * CloudWebSocketServer / CloudFrameCodec；Lua 侧只见明文 JSON 收发。
 *
 * 双 Lua 面：
 * 1. 全局 `cloud`（本类经 LuaEnvContributor.contribute 注入，三环境生效）——纯 WS 通道，
 *    无任何协议语义（不认识 op、不路由、不回执）：
 * ```lua
 * cloud.open{ port = 9300, secret = "" }  -- 启动（secret 非空=加密帧）；不阻塞
 * cloud.onMessage(function(msg) ... end)  -- 每条已解码 JSON table 回调（可多个）
 * cloud.send(msg)                         -- 广播（table 自动 JSON 编码 / string 原样）
 * cloud.recv(ms)                          -- 手动拉一条：table / 原始JSON串 / nil
 * cloud.run{ pollMs = 30, onIdle = fn }   -- 阻塞消息泵（永不返回）；onIdle=空闲钩子
 * cloud.close() / cloud.connections() / cloud.isEncrypted()
 * ```
 * 2. `cloudServer`（LocalService 原生面）：start/stop/poll/send/connections/isRunning，
 *    供需要直连底层的高级用法（jy_protocol 等上层协议实现复用）。
 *
 * 泵模型约束：消息处理必须在 Lua 主线程（Java 无法回调 Lua），cloud.run 在主线程轮询；
 * 每轮 sleep(1) 提供挂起点（pause/interrupt 信号响应）。
 */
interface ICloudService {
    /** 启动 WS server：secret 非空启用 PSK 加密；端口占用返回 false */
    fun start(port: Int, secret: String): Boolean

    /** 停止 server（幂等） */
    fun stop(): Boolean

    /** 取一条已解密消息（明文 JSON 串，毫秒阻塞）；无消息返回 null */
    fun poll(timeoutMs: Int): String?

    /** 广播明文 JSON（加密模式自动组帧）；编帧失败返回 false */
    fun send(text: String): Boolean

    /** 当前在线连接数 */
    fun connections(): Int

    /** 是否运行中 */
    fun isRunning(): Boolean

    /** 是否加密模式（start 时 secret 非空） */
    fun isEncrypted(): Boolean
}

/**
 * 内置 Lua 绑定（纯通道）。经 contribute 注入为全局 `cloud`：
 * chunk return 表 → pcall 后 setGlobal，避开引擎 _G __newindex 代理对新键的静默吞赋值。
 * 依赖全部运行时 rawget（cloudServer/json/sleep），与服务注入顺序无关。
 */
private const val CLOUD_LUA = """
-- 引擎内置云控通道（纯 WS 通道，无协议语义；协议/路由/回执由上层脚本自行实现）
local cloud = {}
local observers = {}      -- 当前生效回调集合（run/recv 启动时与 pendingObs 整体交换）
local pendingObs = {}     -- 新注册暂存：主引擎 LuaState 跨 execute 保留，直接写 observers
                          -- 会跨运行累积（运行 N 次 = 每条消息 dispatch N 次）；改为
                          -- "注册进 pending → run/recv 时整体替换 observers" 的会话语义
local running = false

local function srv()
  local s = rawget(_G, "cloudServer")
  if type(s) == "table" or type(s) == "userdata" then return s end
  return nil
end

local function jsonLib()
  return rawget(_G, "jsonLib") or rawget(_G, "json")
end

-- 启动 WS server（不阻塞）：{port=9300, secret=""}；secret 非空启用 PSK 加密帧
function cloud.open(opts)
  opts = opts or {}
  local s = srv()
  if s == nil or s.start == nil then return false, "cloudServer 未注入" end
  if jsonLib() == nil then return false, "json 库不可用" end
  local ok = s.start(tonumber(opts.port) or 9300, tostring(opts.secret or ""))
  if not ok then return false, "cloudServer.start 失败（端口占用或密钥派生失败）" end
  running = true
  return true
end

-- 注册消息回调（可多个）：进入 pending，run/recv 时整体生效（见 absorbPending）
function cloud.onMessage(fn)
  if type(fn) ~= "function" then return false, "onMessage 需传 function" end
  pendingObs[#pendingObs + 1] = fn
  print("[cloud] onMessage pending, n=" .. #pendingObs)
  return true
end

-- 生效注册：observers 整体替换为 pending（"本次运行会话的回调集合"语义）——
-- 跨运行残留天然免疫：每次 run/recv 都以上一次生效后新注册的集合为准
local function absorbPending()
  if #pendingObs > 0 then
    observers = pendingObs
    pendingObs = {}
    print("[cloud] pending absorbed, observers=" .. #observers)
  end
end

-- 广播：table 自动 JSON 编码；string 原样（调用方自证合法 JSON）
function cloud.send(msg)
  local s = srv()
  if s == nil or s.send == nil then return false, "cloudServer 未注入" end
  local wire = msg
  if type(msg) == "table" then
    local j = jsonLib()
    if j == nil or j.encode == nil then return false, "json 库不可用" end
    local okE, enc = pcall(j.encode, msg)
    if not okE or type(enc) ~= "string" then return false, "JSON 编码失败: " .. tostring(enc) end
    wire = enc
  end
  local okS = pcall(s.send, wire)
  return okS == true
end

-- 手动拉一条：table=已解码；string=原始 JSON（解码失败透传）；nil=无消息
function cloud.recv(timeoutMs)
  absorbPending()
  local s = srv()
  if s == nil or s.poll == nil then return nil end
  local okP, raw = pcall(s.poll, tonumber(timeoutMs) or 30)
  if not okP or type(raw) ~= "string" then return nil end
  local j = jsonLib()
  if j ~= nil and j.decode ~= nil then
    local okJ, m = pcall(j.decode, raw)
    if okJ and type(m) == "table" then return m end
  end
  return raw
end

-- 阻塞消息泵（cloud.exit()/cloud.close() 置 running=false 后下一轮退出）：poll 节拍 + sleep(1) 挂起点（pause/interrupt 响应）
-- opts：{pollMs=30, onIdle=fn}——每轮无消息时回调 onIdle（业务心跳/周期任务挂钩）
-- 终止语义（重要）：引擎 interrupt 在 sleep 挂起点抛 error("interrupt")——sleep 必须裸调，
-- pcall 会吞掉终止信号导致"停止后再运行被忙态拒绝"；回调 pcall 后必须检查并重抛
function cloud.run(opts)
  opts = opts or {}
  if not running then return false, "先 cloud.open{}" end
  local pollMs = tonumber(opts.pollMs) or 30
  local onIdle = type(opts.onIdle) == "function" and opts.onIdle or nil
  local sl = rawget(_G, "sleep")
  if type(sl) ~= "function" then
    local fs = rawget(_G, "frameSdk")
    if type(fs) == "table" and type(fs.sleep) == "function" then sl = fs.sleep end
  end
  if type(sl) ~= "function" then return false, "run 需要全局 sleep 或 frameSdk.sleep（挂起点）" end
  absorbPending()
  while running do
    local msg = cloud.recv(pollMs)
    if type(msg) == "table" then
      for i = 1, #observers do
        local ok, err = pcall(observers[i], msg)
        if not ok then
          if err == "interrupt" or err == "exitScript" then error(err, 0) end
          pcall(print, "[cloud] onMessage handler error: " .. tostring(err))  -- 业务异常透出（logcat/IDE），不吞成黑箱
        end
      end
    elseif onIdle ~= nil then
      local ok, err = pcall(onIdle)
      if not ok and (err == "interrupt" or err == "exitScript") then error(err, 0) end
    end
    sl(1)  -- 裸调：interrupt/exitScript 在挂起点抛 error 终止泵，不得 pcall 包裹
  end
end

-- 请求泵退出（不关服务器）：run 循环检测 running，下一轮退出（≤pollMs+1ms）；
-- 退出后脚本可继续收尾（发回执/ui.shutdown），服务器由容器销毁钩子（OnStop→cloud.close）统一关停。
-- 与 OnStop→running=false 的差异：exit 供脚本在运行期（如 UI 回调协程）主动请求结束
function cloud.exit()
  running = false
  return true
end

function cloud.close()
  running = false
  local s = srv()
  if s ~= nil and s.stop ~= nil then pcall(s.stop) end
  return true
end

function cloud.connections()
  local s = srv()
  if s == nil or s.connections == nil then return 0 end
  local ok, n = pcall(s.connections)
  if ok and type(n) == "number" then return n end
  return 0
end

-- 是否加密模式（open 时传了 secret）
function cloud.isEncrypted()
  local s = srv()
  if s == nil or s.isEncrypted == nil then return false end
  local ok, e = pcall(s.isEncrypted)
  return ok and e == true
end

-- 【诊断】当前注册的回调数量（定位重复 dispatch）
function cloud._n()
  return #observers
end

-- 脚本终止钩子：停止/exitScript/异常退出后引擎调用全局 OnStop——
-- 在此关掉 cloud server，防止脚本已死而 9300 端口残留（close 幂等，重复调用无害）；
-- 并重置观察者：主引擎 LuaState 跨 execute 保留（非每次重建），onMessage 注册的回调
-- 会跨运行累积（运行 N 次 = 每条消息 dispatch N 次/回包 N 份）——终止时清空，下次运行从零开始
do
  local prev = rawget(_G, "OnStop")
  rawset(_G, "OnStop", function()
    -- 清理步骤各自独立 pcall：任何一步抛错（如 print 经 IdeLog 在 error 传播后失败）
    -- 都不得中断后续清理（曾因 print 在首行抛错 → pcall 吞掉 → 清理整体失效）
    local pf = io.open("/sdcard/onstop_proof.txt", "a")
    if pf then pf:write("OnStop at " .. os.time() .. "\n"); pf:close() end
    running = false
    pcall(cloud.close)
    pcall(print, "[cloud] OnStop pending=" .. #pendingObs .. " observers=" .. #observers)
    -- pendingObs 跨运行累积（LuaState 保留）：未开泵的运行里 onMessage 注册只攒不吸收，
    -- 攒 N 次后开泵 → absorb 把 N 个旧回调全部生效 → 每条消息 dispatch N 遍。脚本终止
    -- 即本轮会话结束，pending 注册作废，下次运行从零注册
    pendingObs = {}
    observers = {}
    if type(prev) == "function" then pcall(prev) end
  end)
end

return cloud
"""

/** 进程级单例核心（CloudService 反射实例化的 CLASS 型注册多实例安全：状态全在这里） */
internal object CloudCore {
    private const val TAG = "AutoLuaCloud"

    @Volatile
    var server: CloudWebSocketServer? = null

    @Volatile
    private var codecRef: CloudFrameCodec? = null

    /** 上一次异步 stop 的完成门闩（start 前等待，防端口未释放竞争） */
    @Volatile
    private var stopDone: java.util.concurrent.CountDownLatch? = null

    @JvmStatic
    fun start(port: Int, secret: String): Boolean {
        val s = server
        if (s != null) {
            return true // 幂等（对齐原 cloud.start 已在运行语义）
        }
        // 上一次 stop 是异步的：等它完成（≤2s）再绑定，防端口未释放导致空壳
        try {
            stopDone?.await(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        val codec = try {
            CloudFrameCodec.create(secret)
        } catch (t: Throwable) {
            Log.w(TAG, "psk derive failed: ${t.message}")
            return false
        }
        return try {
            val srv = CloudWebSocketServer(port, codec)
            srv.start() // Java-WebSocket 异步起 selector 线程
            if (!srv.awaitStarted(1500)) {
                // 绑定失败（端口占用/密钥派生）：不留未监听的空壳
                Log.w(TAG, "cloud server bind failed on port $port")
                try {
                    srv.stop()
                } catch (t: Throwable) {
                    Log.w(TAG, "dead server cleanup failed: ${t.message}")
                }
                return false
            }
            server = srv
            codecRef = codec
            Log.i(TAG, "cloud channel started on port $port (encrypted=${codec.isEncrypted})")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "cloud server start failed: ${t.message}")
            false
        }
    }

    @JvmStatic
    fun stop(): Boolean {
        val s = server ?: return true
        server = null
        codecRef = null
        // 异步停：脚本终止钩子（OnStop→close）在引擎 loop 主线程上执行，
        // 同步 stop 会等 selector 线程退出，可能卡住主线程导致 worker 状态无法回 Idle
        val latch = java.util.concurrent.CountDownLatch(1)
        stopDone = latch
        Thread {
            try {
                s.stop()
                Log.i(TAG, "cloud websocket server stopped (async)")
            } catch (t: Throwable) {
                Log.w(TAG, "cloud server stop failed: ${t.message}")
            } finally {
                latch.countDown()
            }
        }.start()
        return true
    }

    @JvmStatic
    fun isEncrypted(): Boolean = codecRef?.isEncrypted ?: false

    @JvmStatic
    fun poll(timeoutMs: Int): String? {
        val s = server ?: return null
        return try {
            s.poll(timeoutMs.toLong())
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    @JvmStatic
    fun send(text: String): Boolean {
        val s = server ?: return false
        return s.broadcastPlain(text) >= 0
    }

    @JvmStatic
    fun connections(): Int {
        val s = server ?: return 0
        return s.clientCount
    }
}

/**
 * LocalService 实现类：无状态委托 {@link CloudCore}；并经 LuaEnvContributor 向每个
 * 新建 LuaContext 注入全局 `cloud`（纯通道绑定）。
 * CLASS 型注册（root Server.kt / 沙箱 Builder 反射实例化）与 OBJECT 型注册（IDE 沙箱）
 * 两种路径下多实例行为一致。
 */
class CloudService : ICloudService, LuaEnvContributor, ScriptContextLifecycleListener {
    override fun start(port: Int, secret: String): Boolean = CloudCore.start(port, secret)
    override fun stop(): Boolean = CloudCore.stop()
    override fun poll(timeoutMs: Int): String? = CloudCore.poll(timeoutMs)
    override fun send(text: String): Boolean = CloudCore.send(text)
    override fun connections(): Int = CloudCore.connections()
    override fun isRunning(): Boolean = CloudCore.server != null
    override fun isEncrypted(): Boolean = CloudCore.isEncrypted()

    /** 脚本容器销毁兜底：关 cloud server（幂等；与 OnStop Lua 钩子双通道，重复调用安全） */
    override fun onScriptContextDestroyed() {
        CloudCore.stop()  // 异步 stop（后台线程 + stopDone 门闩），回调内不阻塞
    }

    /** 注入全局 cloud：chunk return 表 → pcall → setGlobal（避开 _G 代理吞赋值） */
    override fun contribute(ctx: LuaContext) {
        try {
            ctx.loadBuffer(CLOUD_LUA.toByteArray(), "=cloud", LuaContext.CodeMode.TEXT)
            if (ctx.pcall(0, 1, 0) == 0) {
                ctx.setGlobal("cloud")
            } else {
                val err = ctx.toString(-1)
                ctx.pop(1)
                Log.w("AutoLuaCloud", "cloud lua bind failed: $err")
            }
        } catch (t: Throwable) {
            Log.w("AutoLuaCloud", "cloud contribute failed: $t")
        }
    }
}
