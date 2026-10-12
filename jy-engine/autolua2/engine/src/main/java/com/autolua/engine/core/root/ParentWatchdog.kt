package com.autolua.engine.core.root

import android.os.SystemClock
import android.util.Log
import java.io.File
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * 父死自退出 watchdog（root 引擎僵尸进程根治）：
 *
 * 引擎由 su 拉起（app_process），su/sh 守护链持有 stdin/stdout 管道副本——
 * app 退出（force-stop/崩溃/重装）后管道 EOF 永不到来，引擎进程永不退出，
 * 并占住系统全局唯一的 shell UiTestAutomationService 注册位（UiAutomation 全机仅一连接），
 * 导致新引擎 "UiAutomationService already registered" 恒失败。
 *
 * 解法：握手时调用方（app）把自己的 pid 传给引擎（协议见 Client.build 追加 writeInt
 * 与 Server.initEngine 的 readInt），本线程周期探测 /proc/<callerPid>；
 * 调用方死亡即 cleanup + exit，不再依赖管道 EOF。
 *
 * 误判方向分析（只允许「漏退」不允许「误杀」）：
 * - app 存活期间 /proc/<pid> 必然存在且 cmdline 非空 → 不会误杀；
 * - pid 被系统复用 → cmdline 非空仍判活 → 漏退（退化为旧行为，由
 *   RootProcessStarter.killStaleServers 清场兜底）。
 */
object ParentWatchdog {
  private const val TAG = "ParentWatchdog"

  /** 探测间隔（ms），可按需调整 */
  @JvmField var intervalMs: Long = 5000L

  /**
   * @param callerPid 调用方（app 主进程）pid；<=0 时不启用（协议降级）
   * @param cleanup 退出前的优雅清理（断开 UiAutomation，防注册位泄漏）
   */
  fun start(callerPid: Int, cleanup: (() -> Unit)? = null) {
    if (callerPid <= 0) {
      Log.w(TAG, "callerPid invalid ($callerPid), watchdog disabled")
      return
    }
    thread(name = "parent-watchdog", isDaemon = true) {
      while (true) {
        SystemClock.sleep(intervalMs)
        val alive = try {
          // /proc/<pid>/cmdline：存在且非空 = 进程存活（NUL 结尾需 trim）
          val bytes = File("/proc/$callerPid/cmdline").readBytes()
          String(bytes, Charsets.UTF_8).trim('\u0000').isNotEmpty()
        } catch (t: Throwable) {
          // /proc/<pid> 不存在或不可读：调用方已死
          false
        }
        if (alive) continue
        System.err.println("[$TAG] caller(pid=$callerPid) gone, engine self-exit")
        Log.w(TAG, "caller(pid=$callerPid) gone, engine self-exit")
        try {
          cleanup?.invoke()
        } catch (t: Throwable) {
          Log.w(TAG, "cleanup before exit failed (ignore)", t)
        }
        exitProcess(0)
      }
    }
  }
}
