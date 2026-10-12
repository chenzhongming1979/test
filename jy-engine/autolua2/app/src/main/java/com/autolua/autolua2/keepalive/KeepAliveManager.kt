package com.autolua.autolua2.keepalive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * 进程保活对外门面（单例）。
 *
 * ===================== VERSION 适配说明 =====================
 * 1. API 26 (Android 8.0) 及以上：后台启动服务必须使用 [Context.startForegroundService]，
 *    且服务须在 5 秒内调用 [android.app.Service.startForeground]；26 以下退化为
 *    [Context.startService]。
 * 2. API 34 (Android 14) 及以上：前台服务强制声明 foregroundServiceType，
 *    本模块 [KeepAliveService] 使用 "specialUse" 类型，startForeground 时须传入
 *    FOREGROUND_SERVICE_TYPE_SPECIAL_USE（数值 1073741824），
 *    并在 Manifest 声明 FOREGROUND_SERVICE_SPECIAL_USE 权限。
 * 3. API 31 (Android 12) 及以上：setExactAndAllowWhileIdle 需要 SCHEDULE_EXACT_ALARM
 *    授权，须先用 [AlarmManager.canScheduleExactAlarms] 判断，未授权时降级为
 *    非精确的 [AlarmManager.set]。
 * 4. 所有 PendingIntent 一律 FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE（数值 201326592）。
 * ============================================================
 */
object KeepAliveManager {

  private const val TAG = "KeepAliveManager"

  /** Alarm 拉活时使用的 action */
  const val ACTION_ALARM_KEEP_ALIVE = "com.autolua.autolua2.keepalive.ALARM_KEEP_ALIVE"

  /**
   * 常规启动保活体系：启动前台服务 + 调度持久周期 Job + 调度 60s 重复 Alarm
   */
  fun start(context: Context) {
    val appContext = context.applicationContext
    Log.d(TAG, "start keep alive")
    startForegroundService(appContext)
    KeepAliveJobService.schedulePersistentJob(appContext)
    KeepAliveAlarmSender.getInstance(appContext).scheduleRepeatingAlarm()
  }

  /**
   * 紧急保活：onDestroy / onTaskRemoved 时调用，
   * Alarm +1s 梯度五连发 + 即时 Job 全部拉满
   */
  fun emergencyKeepAlive(context: Context) {
    val appContext = context.applicationContext
    Log.d(TAG, "emergency keep alive")
    startForegroundService(appContext)
    KeepAliveJobService.scheduleImmediateJob(appContext)
    KeepAliveAlarmSender.getInstance(appContext).startEmergencyAlarm()
  }

  /**
   * 拉起保活前台服务。
   * API 26+ 用 startForegroundService，低版本退化为 startService；
   * 后台启动 FGS 在 Android 12+ 受限时可能抛异常，静默失败由 Job/Alarm 兜底。
   */
  internal fun startForegroundService(context: Context) {
    try {
      val intent = Intent(context, KeepAliveService::class.java)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        // API 26+：后台启动服务必须走 startForegroundService
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    } catch (e: Exception) {
      Log.e(TAG, "start foreground service failed", e)
    }
  }

  /**
   * 是否已被用户忽略电池优化（进入电池优化白名单）
   */
  fun isBatteryIgnored(context: Context): Boolean {
    return try {
      val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
      pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (e: Exception) {
      Log.e(TAG, "isBatteryIgnored failed", e)
      false
    }
  }

  /** 厂商引导矩阵条目：keywords 命中 manufacturer/brand 任一 → buildCandidates 生成候选页 */
  private class VendorGuideEntry(
    val keywords: List<String>,
    val buildCandidates: (Context) -> List<Intent>
  )

  /**
   * 厂商引导矩阵（P1.5）：华为/鸿蒙清后台复位无障碍、vivo 官方省电自动关无障碍、
   * OPPO 耗电异常杀服务——均需引导用户到厂商后台/自启动管理页放行。
   * 每条目支持多候选（不同 OS 版本页面不同），resolveActivity 解析不到自动试下一个。
   */
  private val VENDOR_GUIDE_MATRIX: List<VendorGuideEntry> = listOf(
    // 小米系：MIUI 自启动管理（保留原有行为）
    VendorGuideEntry(listOf("xiaomi", "redmi", "poco")) { context ->
      listOf(
        Intent("miui.intent.action.OP_AUTO_START").apply {
          addCategory(Intent.CATEGORY_DEFAULT)
          putExtra("package_name", context.packageName)
        })
    },
    // 华为/荣耀：自启动管理
    VendorGuideEntry(listOf("huawei", "honor")) { _ ->
      listOf(componentIntent(
        "com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
    },
    // OPPO/一加/realme：ColorOS 自启动管理（旧版包名 com.oppo.safe 作备选）
    VendorGuideEntry(listOf("oppo", "oneplus", "realme")) { _ ->
      listOf(
        componentIntent("com.coloros.safecenter",
          "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        componentIntent("com.oppo.safe",
          "com.oppo.safe.permission.startup.StartupAppListActivity"))
    },
    // vivo/iQOO：后台启动管理（旧版 i管家包名 com.iqoo.secure 作备选）
    VendorGuideEntry(listOf("vivo", "iqoo")) { _ ->
      listOf(
        componentIntent("com.vivo.permissionmanager",
          "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        componentIntent("com.iqoo.secure",
          "com.iqoo.secure.ui.phoneoptimize.BgStartUpManagerActivity"))
    },
    // 三星：无统一自启动管理页，引导电池优化白名单
    VendorGuideEntry(listOf("samsung")) { _ ->
      listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    },
    // 魅族：智能后台管理
    VendorGuideEntry(listOf("meizu")) { _ ->
      listOf(componentIntent("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"))
    })

  /** 按包名+全类名构建显式组件 Intent */
  private fun componentIntent(packageName: String, className: String): Intent =
      Intent().setClassName(packageName, className)

  /**
   * 获取厂商自启动/后台管理设置页 Intent（数据驱动厂商矩阵）。
   * 按 Build.MANUFACTURER + Build.BRAND 多关键词命中厂商条目，依序解析该厂商的
   * 候选页面，全部解析失败或异常时回退到应用详情设置页；未命中已知厂商返回 null。
   */
  fun getManufacturerGuideIntent(context: Context): Intent? {
    val manufacturer = Build.MANUFACTURER.lowercase()
    val brand = Build.BRAND.lowercase()
    for (entry in VENDOR_GUIDE_MATRIX) {
      if (entry.keywords.none { manufacturer.contains(it) || brand.contains(it) }) {
        continue
      }
      return resolveFirstAvailable(context, entry.buildCandidates(context))
          ?: buildAppDetailsIntent(context)
    }
    return null
  }

  /** 依序解析候选 Intent，返回第一个可解析的；resolveActivity 异常按不可用处理 */
  private fun resolveFirstAvailable(context: Context, candidates: List<Intent>): Intent? {
    for (intent in candidates) {
      try {
        if (intent.resolveActivity(context.packageManager) != null) {
          return intent
        }
      } catch (e: Exception) {
        Log.w(TAG, "resolve vendor guide intent failed: $intent", e)
      }
    }
    return null
  }

private fun buildAppDetailsIntent(context: Context): Intent {
    return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:" + context.packageName))
  }
}
