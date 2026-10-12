package com.autolua.autolua2.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启广播：BOOT_COMPLETED 后按用户开关拉起保活前台服务。
 *
 * 开关持久化于 SharedPreferences（boot_receiver_prefs / auto_start_on_boot），
 * 默认 false，需用户在设置中显式开启。
 */
class BootReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
    Log.d(TAG, "Boot completed received")
    if (!isAutoStartOnBoot(context)) {
      Log.d(TAG, "Auto start on boot is disabled, skip")
      return
    }
    Log.d(TAG, "Auto start on boot is enabled, starting keep alive")
    try {
      // 开机广播属于系统白名单广播，允许后台启动 FGS
      KeepAliveManager.startForegroundService(context)
      KeepAliveJobService.schedulePersistentJob(context)
      KeepAliveJobService.scheduleImmediateJob(context)
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start keep alive after boot", e)
    }
  }

  companion object {
    private const val TAG = "BootReceiver"
    private const val PREFS_NAME = "boot_receiver_prefs"
    private const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"

    /** 是否开机自启（默认 false） */
    fun isAutoStartOnBoot(context: Context): Boolean {
      return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_AUTO_START_ON_BOOT, false)
    }

    /** 设置开机自启开关 */
    fun setAutoStartOnBoot(context: Context, enabled: Boolean) {
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_AUTO_START_ON_BOOT, enabled).apply()
    }
  }
}
