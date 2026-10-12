package com.autolua.autolua2.keepalive

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.autolua.autolua2.R
import com.autolua.autolua2.guide.RestrictedSettingsGuide
import com.autolua.engine.accessibility.AutoLuaAccessibilityService
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

/**
 * 无障碍存活守护（P0.2 watchdog）。
 *
 * 挂接保活心跳（KeepAliveService 60s Alarm / KeepAliveJobService 周期 Job），
 * 周期检查系统无障碍开关列表是否仍包含本应用服务组件（服务死后
 * onServiceConnected 不会再回调，只能主动检测+引导）。断开处置：
 * 1. root 设备（主路径）：经 su 读写 settings secure 追加启用本服务
 *    （先读旧值合并，不覆盖别家服务；再置 accessibility_enabled=1），
 *    成功后 5s 复查验证；每会话最多尝试 3 次、间隔 10s，防循环风暴；
 *    服务恢复健康后重置计数（下次断开可再次自愈）。
 * 2. 非 root / root 自愈失败：发高优先级通知（渠道 accessibility_watchdog）
 *    引导用户手动重开，点击跳系统无障碍设置；5 分钟内不重复发；
 *    API 33+ 检查 POST_NOTIFICATIONS 运行时权限与 areNotificationsEnabled，
 *    无权限时降级为仅记录日志。
 * 3. 服务关闭且非 root 且为 Android 13+ 侧载受限候选时，通知改为跳
 *    应用详情页，引导「允许受限设置」（见 [RestrictedSettingsGuide]）。
 */
object AccessibilityWatchdog {

  private const val TAG = "AccessibilityWatchdog"

  /** 通知渠道 ID（高优先级，独立于保活渠道） */
  const val CHANNEL_ID = "accessibility_watchdog"

  /** engine 模块无障碍服务类名（engine 的 manifest 即以此类声明，app 内声明同值常量） */
  private const val SERVICE_CLASS = "com.autolua.engine.accessibility.AutoLuaAccessibilityService"

  /** 通知 ID：避开 Constants 10087/10088 与保活 10089 */
  private const val NOTIFICATION_ID = 10090

  /** 通知点击 PendingIntent requestCode */
  private const val NOTIFY_REQUEST_CODE = 10091

  /** root 重开每会话最大尝试次数 */
  private const val MAX_ROOT_ATTEMPTS = 3

  /** root 重开两次尝试最小间隔 */
  private const val ROOT_RETRY_INTERVAL_MS = 10 * 1000L

  /** root 重开下发命令后的复查验证延迟 */
  private const val ROOT_VERIFY_DELAY_MS = 5 * 1000L

  /** 单条 su 命令执行超时 */
  private const val SU_TIMEOUT_MS = 5 * 1000L

  /** 通知节流：5 分钟内不重复发 */
  private const val NOTIFY_THROTTLE_MS = 5 * 60 * 1000L

  private enum class RootState { UNKNOWN, SUPPORTED, UNSUPPORTED }

  @Volatile private var rootState = RootState.UNKNOWN
  @Volatile private var rootAttempts = 0
  @Volatile private var lastRootAttemptAt = 0L
  @Volatile private var rootAttemptInFlight = false
  @Volatile private var lastNotifyAt = 0L

  private val handler = Handler(Looper.getMainLooper())

  /**
   * 心跳入口：由保活体系的 60s Alarm / 周期 Job 触发（≥60s 一次）。
   * 服务健康时为 O(1) 快速路径（同进程静态标记），开销可忽略。
   */
  fun onHeartbeat(context: Context) {
    try {
      val appContext = context.applicationContext
      if (isServiceEnabled(appContext)) {
        // 恢复健康：重置 root 尝试预算（防风暴前提下允许下次断开继续自愈）
        rootAttempts = 0
        lastRootAttemptAt = 0L
        return
      }
      Log.w(TAG, "accessibility service disconnected, handling (rootAttempts=$rootAttempts)")
      if (canAttemptRootNow()) {
        lastRootAttemptAt = SystemClock.elapsedRealtime()
        rootAttempts++
        rootAttemptInFlight = true
        // su 命令必须离开主线程执行
        Thread {
          val ok = performRootReenable(appContext)
          if (ok) {
            Log.i(TAG, "root re-enable commands sent, verify in ${ROOT_VERIFY_DELAY_MS}ms")
            handler.postDelayed({ verifyRootReenable(appContext) }, ROOT_VERIFY_DELAY_MS)
          } else {
            Log.w(TAG, "root re-enable failed (state=$rootState)")
            handler.post { notifyDisconnected(appContext) }
          }
          rootAttemptInFlight = false
        }.start()
      } else {
        notifyDisconnected(appContext)
      }
    } catch (e: Throwable) {
      Log.e(TAG, "onHeartbeat failed", e)
    }
  }

  /** 本应用无障碍服务是否处于系统已启用状态 */
  fun isServiceEnabled(context: Context): Boolean {
    try {
      // 同进程辅助：服务已连接必然可用（免跨进程查询）
      if (AutoLuaAccessibilityService.isRunning()) {
        return true
      }
      val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
      // getId() 返回扁平组件串（pkg/cls），与 ComponentName.flattenToString() 同构
      val expectedFlat = ComponentName(context.packageName, SERVICE_CLASS).flattenToString()
      val enabled = am.getEnabledAccessibilityServiceList(
        AccessibilityServiceInfo.FEEDBACK_ALL_MASK) ?: return false
      for (info in enabled) {
        if (info.id.equals(expectedFlat, ignoreCase = true)) {
          return true
        }
      }
      return false
    } catch (e: Throwable) {
      Log.e(TAG, "isServiceEnabled failed", e)
      return false
    }
  }

  private fun canAttemptRootNow(): Boolean {
    if (rootState == RootState.UNSUPPORTED) return false
    if (rootAttemptInFlight) return false
    if (rootAttempts >= MAX_ROOT_ATTEMPTS) return false
    return SystemClock.elapsedRealtime() - lastRootAttemptAt >= ROOT_RETRY_INTERVAL_MS
  }

  /**
   * root 自动重开：读旧值 → 合并追加本服务 → 写回 → 置总开关。
   * 任一步命令失败返回 false；命令成功返回 true（由 5s 复查验证最终效果）。
   */
  private fun performRootReenable(context: Context): Boolean {
    val component = ComponentName(context.packageName, SERVICE_CLASS).flattenToString()
    // 先读旧值，避免覆盖其他无障碍服务（多服务以冒号分隔）
    val old = execSu("settings get secure enabled_accessibility_services") ?: return false
    val trimmed = old.trim()
    val alreadyListed = trimmed.split(":").any { it.equals(component, ignoreCase = true) }
    val merged = if (alreadyListed || trimmed.isEmpty() || trimmed == "null") {
      if (alreadyListed) trimmed else component
    } else {
      "$trimmed:$component"
    }
    if (!alreadyListed &&
      execSu("settings put secure enabled_accessibility_services $merged") == null) {
      return false
    }
    if (execSu("settings put secure accessibility_enabled 1") == null) {
      return false
    }
    rootState = RootState.SUPPORTED
    return true
  }

  /** root 重开 5s 复查：仍未生效则走通知兜底（root 可用，通知仍是常规入口） */
  private fun verifyRootReenable(context: Context) {
    try {
      if (isServiceEnabled(context)) {
        Log.i(TAG, "root re-enable verified: accessibility is back")
        return
      }
      Log.w(TAG, "root re-enable verify failed, fallback to notification")
      notifyDisconnected(context)
    } catch (e: Throwable) {
      Log.e(TAG, "verifyRootReenable failed", e)
    }
  }

  /**
   * 断开兜底通知（5 分钟节流）：受限候选 → 跳应用详情页引导「允许受限设置」；
   * 否则跳系统无障碍设置。无通知权限时降级为仅记录日志。
   */
  private fun notifyDisconnected(context: Context) {
    try {
      val now = SystemClock.elapsedRealtime()
      if (lastNotifyAt != 0L && now - lastNotifyAt < NOTIFY_THROTTLE_MS) {
        Log.d(TAG, "notification throttled (5min)")
        return
      }
      if (!areNotificationsEnabled(context)) {
        // 无 POST_NOTIFICATIONS 权限 / 通知总开关关闭：降级为仅记录日志
        Log.w(TAG, "notification disabled by user/permission, skip notify (accessibility is off)")
        return
      }
      val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      ensureChannel(context, nm)
      // 侧载受限候选且 root 未能自愈：点击改为跳应用详情页
      val restricted = rootState != RootState.SUPPORTED &&
          RestrictedSettingsGuide.isRestrictedSettingsCandidate(context)
      val openIntent = if (restricted) {
        RestrictedSettingsGuide.buildAppDetailsIntent(context)
      } else {
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
      }
      openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      val pi = PendingIntent.getActivity(context, NOTIFY_REQUEST_CODE, openIntent,
        // FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE = 201326592
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
      val text = if (restricted) context.getString(R.string.watchdog_notify_restricted)
                 else context.getString(R.string.watchdog_notify_normal)
      val bigText = if (restricted) text + "\n" + RestrictedSettingsGuide.GUIDE_TEXT else text
      val notification = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.mipmap.logo)
        .setContentTitle(context.getString(R.string.watchdog_notify_title))
        .setContentText(text)
        .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(bigText))
        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(pi)
        .build()
      nm.notify(NOTIFICATION_ID, notification)
      lastNotifyAt = now
      Log.w(TAG, "accessibility watchdog notified (restricted=$restricted)")
    } catch (e: Throwable) {
      Log.e(TAG, "notifyDisconnected failed", e)
    }
  }

  /** 通知可用性：API 33+ 检查 POST_NOTIFICATIONS 运行时权限，再查总开关 */
  private fun areNotificationsEnabled(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= 33) {
      if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
          != PackageManager.PERMISSION_GRANTED) {
        return false
      }
    }
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      nm.areNotificationsEnabled()
    } else {
      true
    }
  }

  /** 创建高优先级通知渠道（API 26+） */
  private fun ensureChannel(context: Context, nm: NotificationManager) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(CHANNEL_ID,
        context.getString(R.string.watchdog_channel_name), NotificationManager.IMPORTANCE_HIGH)
      channel.description = context.getString(R.string.watchdog_channel_desc)
      channel.enableVibration(true)
      nm.createNotificationChannel(channel)
    }
  }

  /**
   * 经 su 执行单条命令（加超时读流）。
   * 返回 stdout（trim 后）；失败/超时/非零退出返回 null。
   * su 不存在或无执行权限时标记本会话无 root。
   */
  private fun execSu(command: String): String? {
    val proc: Process
    try {
      proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
    } catch (e: IOException) {
      rootState = RootState.UNSUPPORTED
      Log.w(TAG, "su unavailable (no root?): $e")
      return null
    } catch (e: Throwable) {
      Log.w(TAG, "execSu start failed: $e")
      return null
    }
    try {
      val stdout = StringBuilder()
      val stderr = StringBuilder()
      // 输出在独立线程读取，避免缓冲区塞满导致 waitFor 死锁
      Thread { readStream(proc.inputStream, stdout) }.apply { isDaemon = true; start() }
      Thread { readStream(proc.errorStream, stderr) }.apply { isDaemon = true; start() }
      val waiter = Thread { try { proc.waitFor() } catch (_: InterruptedException) {} }
      waiter.start()
      waiter.join(SU_TIMEOUT_MS)
      if (waiter.isAlive) {
        Log.w(TAG, "execSu timeout: $command")
        proc.destroy()
        return null
      }
      val exit = proc.exitValue()
      if (exit != 0) {
        Log.w(TAG, "execSu exit=$exit cmd=$command err=${stderr.toString().trim()}")
        return null
      }
      return stdout.toString().trim()
    } catch (e: Throwable) {
      Log.w(TAG, "execSu failed: $e")
      try { proc.destroy() } catch (_: Throwable) {}
      return null
    }
  }

  private fun readStream(stream: InputStream, sink: StringBuilder) {
    try {
      val reader = BufferedReader(InputStreamReader(stream))
      while (true) {
        val line = reader.readLine() ?: break
        sink.append(line).append('\n')
      }
    } catch (_: Throwable) {
    }
  }
}