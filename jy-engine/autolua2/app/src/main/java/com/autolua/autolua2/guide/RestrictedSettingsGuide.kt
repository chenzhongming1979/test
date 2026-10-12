package com.autolua.autolua2.guide

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Android 13+ 受限设置（Restricted Settings）引导（P0.3）。
 *
 * 背景：Android 13 起通过浏览器/IM 等「非商店渠道」侧载安装的 APK，
 * 系统会禁止其直接开启无障碍开关（开关置灰），需引导用户到
 * 「应用详情 → 右上三点 → 允许受限设置」解除限制后才能开启无障碍。
 *
 * 判定：API 33+ 且安装发起方（initiatingPackageName，API 30+ 接口）不在
 * 可信来源白名单（Play 商店 / adb / 主流厂商系统商店，可按需扩展）时，
 * 视为侧载受限候选。
 */
object RestrictedSettingsGuide {

  private const val TAG = "RestrictedSettingsGuide"

  /** 无障碍开启操作引导文案（设置→应用→右上三点→允许受限设置→返回开启无障碍） */
  const val GUIDE_TEXT = "设置 → 应用 → 右上三点 → 允许受限设置 → 返回开启无障碍"

  /** 可信安装来源白名单（initiatingPackageName）：商店/adb 之外视为侧载受限候选 */
  private val TRUSTED_INSTALLER_PACKAGES = setOf(
    "com.android.vending",             // Google Play
    "com.android.shell",               // adb 安装
    "com.huawei.appmarket",            // 华为应用市场
    "com.heytap.market",               // OPPO 软件商店
    "com.vivo.appstore",               // vivo 应用商店
    "com.xiaomi.market",               // 小米应用商店
    "com.meizu.mstore",                // 魅族应用商店
    "com.sec.android.app.samsungapps"  // 三星应用商店
    // 第三方下载器/应用宝等不在白名单：其分发的 APK 同样会触发受限设置
  )

  /**
   * 是否为「侧载受限候选」：API 33+ 且安装发起方不在可信白名单。
   * 接口异常（包名不存在等）按不受限处理，避免误引导。
   */
  fun isRestrictedSettingsCandidate(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      return false
    }
    return try {
      // getInstallSourceInfo 为 API 30+ 接口，已由上方 33 闸门保证可用
      val source = context.packageManager.getInstallSourceInfo(context.packageName)
      val initiating = source.initiatingPackageName
      // initiating 为 null（无法判定/系统直装）视为可信
      initiating != null && initiating !in TRUSTED_INSTALLER_PACKAGES
    } catch (e: Throwable) {
      Log.w(TAG, "getInstallSourceInfo failed: $e")
      false
    }
  }

  /** 应用详情页 Intent（「允许受限设置」入口在其右上三点菜单） */
  fun buildAppDetailsIntent(context: Context): Intent {
    return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:" + context.packageName))
  }
}