package com.autolua.autolua2.debug

import android.content.Context
import android.util.Log
import com.autolua.autolua2.activity.PreferencesHelper
import com.autolua.engine.ide.IdeServerManager
import io.netty.channel.nio.NioEventLoopGroup

/**
 * UDP 设备发现管理器（9.11 调试通道整合：老通道 BroadcastLocationServer 迁移挂到新 IDE 通道）。
 * <p>
 * 包装 {@link BroadcastLocationServer}（应答 "AutoLuaEngine,Where are you?" →
 * "AutoLuaKit,I am here!"），随 IdeServerManager 启停：
 * <ul>
 *     <li>UDP 监听端口与 WebSocket IDE 端口一致（IdeServerManager.getPort()，默认 8080）；</li>
 *     <li>开关状态持久化到 Preferences（key "udp_discovery"，默认开启）；</li>
 *     <li>自建独立 NioEventLoopGroup(1)，不复用老 DebugServiceImp 的 group（该服务已退役）。</li>
 * </ul>
 * UI 开关：DebugFragment 的"UDP 设备发现"Switch → {@link #setEnable}。
 */
object DiscoveryManager {

  private const val TAG = "DiscoveryManager"
  /** Preferences 持久化 key（默认 true：设备发现开箱即用） */
  private const val PREF_KEY = "udp_discovery"

  /** 独立网络线程组（App 进程生命周期，懒加载；不随单次启停关闭） */
  private var group: NioEventLoopGroup? = null
  private var broadcastServer: BroadcastLocationServer? = null

  /** 读取开关状态（默认 true） */
  fun isEnabled(context: Context): Boolean {
    return PreferencesHelper(context.applicationContext).getBoolean(PREF_KEY, true)
  }

  /** 写开关状态并即时生效：开=随当前 IDE 端口启动；关=停止监听 */
  fun setEnable(context: Context, enabled: Boolean) {
    PreferencesHelper(context.applicationContext).saveBoolean(PREF_KEY, enabled)
    if (enabled) {
      start(context.applicationContext)
    } else {
      stop()
    }
  }

  /**
   * 按 Preferences 状态应用（随 IdeServerManager.start() 之后调用；
   * IDE 服务停止场景由 MainApp 侧控制——当前无 stop 接线，仅启动时 apply）。
   */
  fun apply(context: Context) {
    if (isEnabled(context)) {
      start(context.applicationContext)
    } else {
      stop()
    }
  }

  /** 启动 UDP 发现（端口取 IdeServerManager 当前监听端口；未运行时跳过） */
  @Synchronized
  private fun start(context: Context) {
    val port = IdeServerManager.getInstance().getPort()
    if (port <= 0) {
      Log.w(TAG, "ide server not running, skip udp discovery start")
      return
    }
    var server = broadcastServer
    when (server?.getState()) {
      BroadcastLocationServer.State.RUNNING, BroadcastLocationServer.State.STARTING -> return
      // STOPPING 状态卡在异步关端口：弃用旧实例换新实例，避免下次 start 被状态机挡住
      BroadcastLocationServer.State.STOPPING -> server = null
      else -> {}
    }
    if (server == null) {
      server = BroadcastLocationServer(group())
      broadcastServer = server
    }
    Log.d(TAG, "udp discovery start at port $port")
    server.start(port)
  }

  /** 停止 UDP 发现（幂等） */
  @Synchronized
  private fun stop() {
    broadcastServer?.stop()
  }

  /** 懒加载独立网络线程组（单线程足够：UDP 应答为轻量收发） */
  private fun group(): NioEventLoopGroup {
    return group ?: NioEventLoopGroup(1).also { group = it }
  }
}
