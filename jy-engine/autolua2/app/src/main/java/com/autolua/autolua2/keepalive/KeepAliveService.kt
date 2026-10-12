package com.autolua.autolua2.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.autolua.autolua2.R
import com.autolua.autolua2.activity.MainActivity

/**
 * 保活前台服务（foregroundServiceType="specialUse"）。
 *
 * - API 34+ 使用 startForeground(id, notification, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
 *   三参重载（FOREGROUND_SERVICE_TYPE_SPECIAL_USE 数值 1073741824）；
 *   低版本使用 startForeground(id, notification)；
 * - 常驻 PARTIAL_WAKE_LOCK 防止 CPU 休眠；
 * - onStartCommand 返回 START_STICKY，被杀后系统尽量重建；
 * - onDestroy / onTaskRemoved 时调用 [KeepAliveManager.emergencyKeepAlive] 自愈；
 * - 通知渠道固定为 "keepalive"。
 */
class KeepAliveService : Service() {

  companion object {
    private const val TAG = "KeepAliveService"

    /**
     * 通知 ID：避免与 Constants 中 ENGINE_SERVICE(10087) / DEBUG_SERVICE(10088) 冲突
     */
    private const val NOTIFICATION_ID = 10089

    /** 通知渠道 ID */
    private const val CHANNEL_ID = "keepalive"
  }

  private var wakeLock: PowerManager.WakeLock? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Log.d(TAG, "Keep-alive service onStartCommand action=${intent?.action}")
    startForegroundCompat()
    acquireWakeLock()
    // 服务每次被拉起都重排 Job 与 Alarm，形成自愈闭环
    KeepAliveJobService.schedulePersistentJob(this)
    KeepAliveAlarmSender.getInstance(this).scheduleRepeatingAlarm()
    // 无障碍存活守护（P0.2）：复用 60s Alarm 心跳周期检查无障碍开关状态
    AccessibilityWatchdog.onHeartbeat(this)
    return START_STICKY
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    super.onTaskRemoved(rootIntent)
    Log.d(TAG, "onTaskRemoved, trigger emergency keep alive")
    KeepAliveManager.emergencyKeepAlive(applicationContext)
  }

  override fun onDestroy() {
    Log.d(TAG, "onDestroy, trigger emergency keep alive")
    // 先紧急保活再释放资源，尽量保证拉活动作能够执行
    KeepAliveManager.emergencyKeepAlive(applicationContext)
    releaseWakeLock()
    super.onDestroy()
  }

  /**
   * 启动前台：API 34+ 携带 specialUse 类型，低版本走两参重载
   */
  private fun startForegroundCompat() {
    val notification = buildNotification()
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        // API 34+：必须携带 FGS 类型，FOREGROUND_SERVICE_TYPE_SPECIAL_USE = 1073741824
        startForeground(
          NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
      } else {
        startForeground(NOTIFICATION_ID, notification)
      }
    } catch (e: Exception) {
      Log.e(TAG, "startForeground failed", e)
    }
  }

  private fun buildNotification(): Notification {
    val contentIntent = PendingIntent.getActivity(
      this, 0,
      Intent(this, MainActivity::class.java),
      // FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE = 201326592
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.logo)
      .setContentTitle("autolua2 保活服务")
      .setContentText("脚本引擎保活运行中")
      .setOngoing(true)
      .setShowWhen(true)
      .setContentIntent(contentIntent)
      .build()
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID, "保活服务", NotificationManager.IMPORTANCE_LOW)
      channel.description = "进程保活前台服务通知"
      channel.setShowBadge(false)
      channel.enableLights(false)
      channel.enableVibration(false)
      val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      manager.createNotificationChannel(channel)
    }
  }

  private fun acquireWakeLock() {
    try {
      if (wakeLock == null) {
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
          .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoLua2:KeepAliveWakeLock")
      }
      wakeLock?.takeIf { !it.isHeld }?.acquire()
      Log.d(TAG, "Wake lock acquired")
    } catch (e: Exception) {
      Log.e(TAG, "Acquire wake lock failed", e)
    }
  }

  private fun releaseWakeLock() {
    try {
      wakeLock?.takeIf { it.isHeld }?.release()
    } catch (e: Exception) {
      Log.e(TAG, "Release wake lock failed", e)
    }
    wakeLock = null
  }
}
