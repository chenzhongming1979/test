package com.autolua.autolua2.keepalive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * AlarmManager 保活兜底。
 *
 * - 紧急模式：setExactAndAllowWhileIdle 以 +1s 梯度连续触发五次；
 * - 常规模式：setRepeating 每 60 秒触发一次。
 *
 * 所有 PendingIntent 一律 FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE（数值 201326592）；
 * API 31+ 需 [AlarmManager.canScheduleExactAlarms] 已授权才能排精确闹钟，
 * 未授权或抛 SecurityException 时降级为非精确 [AlarmManager.set]。
 */
class KeepAliveAlarmSender private constructor(private val context: Context) {

  companion object {
    private const val TAG = "KeepAliveAlarmSender"

    /** 常规重复闹钟的 requestCode */
    private const val REPEAT_REQUEST_CODE = 8886

    /** 紧急梯度闹钟起始 requestCode（五连发依次 +1，避免 FLAG_UPDATE_CURRENT 相互覆盖） */
    private const val EMERGENCY_REQUEST_CODE_BASE = 8888

    /** 紧急梯度触发次数 */
    private const val EMERGENCY_COUNT = 5

    /** 梯度间隔：1 秒 */
    private const val EMERGENCY_STEP_MS = 1000L

    /** 常规重复周期：60 秒 */
    private const val REPEAT_INTERVAL_MS = 60 * 1000L

    @Volatile
    private var instance: KeepAliveAlarmSender? = null

    fun getInstance(context: Context): KeepAliveAlarmSender =
      instance ?: synchronized(this) {
        instance ?: KeepAliveAlarmSender(context.applicationContext).also { instance = it }
      }
  }

  /**
   * 构建指向保活服务的 PendingIntent。
   * API 26+ 用 getForegroundService（闹钟触发时系统将应用列入临时白名单，允许后台启动 FGS），
   * 低版本用 getService。
   */
  private fun buildPendingIntent(requestCode: Int): PendingIntent {
    val intent = Intent(context, KeepAliveService::class.java).apply {
      action = KeepAliveManager.ACTION_ALARM_KEEP_ALIVE
    }
    // FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE = 201326592
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      PendingIntent.getForegroundService(context, requestCode, intent, flags)
    } else {
      PendingIntent.getService(context, requestCode, intent, flags)
    }
  }

  /**
   * 排精确闹钟；API 31+ 未授权精确闹钟时降级为非精确 set()
   */
  private fun setExactAlarm(alarmManager: AlarmManager, triggerAtMillis: Long, pi: PendingIntent) {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
        // 未授予 SCHEDULE_EXACT_ALARM，降级为非精确 set()
        alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pi)
      } else {
        alarmManager.setExactAndAllowWhileIdle(
          AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pi)
      }
    } catch (e: SecurityException) {
      Log.e(TAG, "setExactAlarm denied, fallback to inexact set()", e)
      try {
        alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pi)
      } catch (e2: Exception) {
        Log.e(TAG, "inexact set failed", e2)
      }
    }
  }

  /**
   * 紧急保活：从当前时刻起 +1s 梯度五连发
   */
  fun startEmergencyAlarm() {
    try {
      val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
      val now = SystemClock.elapsedRealtime()
      for (i in 0 until EMERGENCY_COUNT) {
        setExactAlarm(
          alarmManager,
          now + (i + 1) * EMERGENCY_STEP_MS,
          buildPendingIntent(EMERGENCY_REQUEST_CODE_BASE + i))
      }
      Log.d(TAG, "Emergency alarms scheduled x$EMERGENCY_COUNT")
    } catch (e: Exception) {
      Log.e(TAG, "startEmergencyAlarm failed", e)
    }
  }

  /**
   * 常规兜底：每 60 秒重复触发
   */
  fun scheduleRepeatingAlarm() {
    try {
      val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
      alarmManager.setRepeating(
        AlarmManager.ELAPSED_REALTIME_WAKEUP,
        SystemClock.elapsedRealtime() + REPEAT_INTERVAL_MS,
        REPEAT_INTERVAL_MS,
        buildPendingIntent(REPEAT_REQUEST_CODE))
      Log.d(TAG, "Repeating alarm scheduled every ${REPEAT_INTERVAL_MS}ms")
    } catch (e: Exception) {
      Log.e(TAG, "scheduleRepeatingAlarm failed", e)
    }
  }
}
