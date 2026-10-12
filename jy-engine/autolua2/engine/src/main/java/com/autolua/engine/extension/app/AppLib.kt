package com.autolua.engine.extension.app

import com.autolua.engine.base.LuaContext
import com.autolua.engine.base.LuaEnvContributor
import java.io.File

/**
 * 应用控制域服务（注入为 Lua 全局 `app`，对齐派大星 app.* / 懒人 device 应用控制），
 * 并经 contribute 注册懒人风格全局函数：runApp / stopApp / appIsFront / appIsRunning /
 * frontAppName / exec / getPackageName / getInstalledApps
 *
 * 实现方式：Runtime.exec（root 进程 uid 0 → am/pm/dumpsys 均可用）。
 * 必须注册在 Lua 所在进程本地；root 主引擎（Server.kt）注入时全部能力可用，
 * 沙箱（app 进程）注入时 exec 降级为 shell 权限（am/ps 可用，force-stop 需 root 失败返回 false）。
 */
open class AppLib : LuaEnvContributor {

  // ---------------- shell 执行 ----------------

  /** 执行命令，返回 (output, exitCode) */
  fun exec(cmd: String): Array<Any> = try {
    val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
    val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
    val err = proc.errorStream.readBytes().toString(Charsets.UTF_8)
    proc.waitFor()
    arrayOf(out.ifEmpty { err }, proc.exitValue())
  } catch (t: Throwable) {
    arrayOf("exec failed: ${t.message}", -1)
  }

  /** 执行命令，仅返回输出（懒人 exec(cmd, isRet) 的 isRet=true 语义） */
  fun execOut(cmd: String): String = exec(cmd)[0] as String

  // ---------------- 应用控制 ----------------

  fun launchPackage(packageName: String): Boolean {
    val (_, code) = exec("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
    return code == 0
  }

  /** 支持包名或 sUrl 前缀链接（懒人 runApp 语义） */
  fun runApp(target: String): Boolean {
    return if (target.startsWith("http://") || target.startsWith("https://")) {
      val (_, code) = exec("am start -a android.intent.action.VIEW -d \"${target}\"")
      code == 0
    } else {
      launchPackage(target)
    }
  }

  fun killApp(packageName: String): Boolean {
    val (_, code) = exec("am force-stop $packageName")
    return code == 0
  }

  fun stopApp(packageName: String): Boolean = killApp(packageName)

  fun isAppRunning(packageName: String): Boolean {
    val out = exec("pidof $packageName")[0] as String
    return out.isNotBlank() && !out.contains("not found")
  }

  fun isAppInstalled(packageName: String): Boolean {
    val (_, code) = exec("pm path $packageName")
    return code == 0
  }

  /** 当前前台应用包名（dumpsys window 解析 mCurrentFocus） */
  fun getTopPackageName(): String {
    val out = exec("dumpsys window windows 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp'")[0] as String
    // 形如：mCurrentFocus=Window{8b3d2f u0 com.android.launcher/com.android.launcher2.Launcher}
    val regex = Regex("u0 ([A-Za-z0-9_.]+)/")
    return regex.find(out)?.groupValues?.get(1) ?: ""
  }

  fun frontAppName(): String = getTopPackageName()

  /** 判断指定包名是否为前台应用（懒人 appIsFront） */
  fun appIsFront(packageName: String): Boolean = getTopPackageName() == packageName

  fun getCurrentActivity(): String {
    val out = exec("dumpsys activity activities 2>/dev/null | grep -E 'topResumedActivity|mResumedActivity'")[0] as String
    return out.trim().lineSequence().firstOrNull { it.isNotBlank() } ?: ""
  }

  /** 第三方包名列表 */
  fun getInstalledApps(): Array<String> {
    val out = exec("pm list packages -3")[0] as String
    return out.lineSequence()
      .map { it.removePrefix("package:").trim() }
      .filter { it.isNotEmpty() }
      .toList()
      .toTypedArray()
  }

  /** 应用版本号（dumpsys package 解析 versionName） */
  fun getAppVersionName(packageName: String): String {
    val out = exec("dumpsys package $packageName 2>/dev/null | grep versionName")[0] as String
    return Regex("versionName=([^\\s]+)").find(out)?.groupValues?.get(1) ?: ""
  }

  /** 打开链接（openUrl） */
  fun openUrl(url: String): Boolean = runApp(url)

  // ---------------- 系统操作（root shell 封装；懒人 device 系统操作对齐） ----------------

  private fun shellOk(cmd: String): Boolean {
    val (_, code) = exec(cmd)
    return code == 0
  }

  /** 熄屏并锁屏（KEYCODE_POWER） */
  fun lockScreen(): Boolean = shellOk("input keyevent 26")

  /** 点亮屏幕（KEYCODE_WAKEUP + MENU 上滑解锁） */
  fun unLockScreen(): Boolean =
    shellOk("input keyevent 224") && shellOk("input keyevent 82")

  /** 熄屏（同 lockScreen，懒人独立命名） */
  fun setDisplayPowerOff(): Boolean = lockScreen()

  /** Wi-Fi 开关（svc） */
  fun setWifiEnable(enable: Boolean): Boolean =
    shellOk("svc wifi ${if (enable) "enable" else "disable"}")

  /** 蓝牙开关（cmd bluetooth_manager，Android 8+） */
  fun setBTEnable(enable: Boolean): Boolean =
    shellOk("cmd bluetooth_manager ${if (enable) "enable" else "disable"}")

  /** 飞行模式（settings + 广播） */
  fun setAirplaneMode(enable: Boolean): Boolean {
    val v = if (enable) 1 else 0
    val (_, c1) = exec("settings put global airplane_mode_on $v")
    val (_, c2) = exec("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state ${if (enable) "true" else "false"}")
    return c1 == 0 && c2 == 0
  }

  /** 设置显示密度（wm density，虚拟 DPI） */
  fun setDpiToVir(dpi: Int): Boolean = shellOk("wm density $dpi")

  /** 恢复默认显示密度 */
  fun setDpiToRealy(): Boolean = shellOk("wm density reset")

  /** 发送短信：直接发送需 SMS 权限，root shell 走 SENDTO 打开短信编辑（懒人 sendSms 降级语义） */
  fun sendSms(number: String, message: String): Boolean =
    shellOk("am start -a android.intent.action.SENDTO -d \"smsto:$number\" --es sms_body \"${message.replace("\"", "\\\"")}\"")

  /** 打电话（ACTION_CALL） */
  fun phoneCall(number: String): Boolean =
    shellOk("am start -a android.intent.action.CALL -d \"tel:$number\"")

  /** 通用 Intent 启动：runIntent(action [, dataUri]) */
  fun runIntent(action: String, dataUri: String? = null): Boolean {
    val cmd = buildString {
      append("am start -a ").append(action)
      if (!dataUri.isNullOrBlank()) append(" -d \"").append(dataUri).append("\"")
    }
    return shellOk(cmd)
  }

  /** 静默安装 APK（root） */
  fun installApk(apkPath: String): Boolean = shellOk("pm install -r \"$apkPath\"")

  /** 扫描媒体文件入库 */
  fun scanImage(filePath: String): Boolean =
    shellOk("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d \"file://$filePath\"")

  /**
   * 解压 assets 目录到目标路径（懒人 extractAssets）。
   * 沙箱（app 进程）反射 currentApplication 取 AssetManager 递归拷贝；
   * root 进程无 AssetManager 返回 false（脚本可改用文件路径方案）。
   */
  fun extractAssets(assetDir: String, destPath: String): Boolean {
    return try {
      val app = Class.forName("android.app.ActivityThread")
        .getMethod("currentApplication").invoke(null) as? android.content.Context
        ?: return false
      val assets = app.assets
      fun copyRec(dir: String, dst: File) {
        dst.mkdirs()
        for (name in assets.list(dir) ?: emptyArray()) {
          val childSrc = if (dir.isEmpty()) name else "$dir/$name"
          val childDst = File(dst, name)
          // 先按文件拷贝，失败则视为子目录递归
          val copied = try {
            assets.open(childSrc).use { input ->
              childDst.outputStream().use { input.copyTo(it) }
            }
            true
          } catch (t: Throwable) {
            false
          }
          if (!copied) copyRec(childSrc, childDst)
        }
      }
      copyRec(assetDir.trimStart('/'), File(destPath))
      true
    } catch (t: Throwable) {
      false
    }
  }

  // ---------------- 懒人风格全局函数 ----------------

  override fun contribute(ctx: LuaContext) {
    reg(ctx, "runApp") { L -> ctx.push(runApp(L.toString(2) ?: "")); 1 }
    reg(ctx, "stopApp") { L -> ctx.push(stopApp(L.toString(2) ?: "")); 1 }
    reg(ctx, "appIsRunning") { L -> ctx.push(isAppRunning(L.toString(2) ?: "")); 1 }
    reg(ctx, "appIsFront") { L -> ctx.push(appIsFront(L.toString(2) ?: "")); 1 }
    reg(ctx, "frontAppName") { ctx.push(frontAppName()); 1 }
    reg(ctx, "getCurrentActivity") { ctx.push(getCurrentActivity()); 1 }
    reg(ctx, "getInstalledApps") {
      val apps = getInstalledApps()
      ctx.createTable(apps.size, 0)
      for ((i, name) in apps.withIndex()) {
        ctx.push((i + 1).toLong())
        ctx.push(name)
        ctx.setTable(-3)
      }
      1
    }
    // exec(cmd [, isRet])：isRet=true 返回输出字符串，否则返回 (output, exitCode)
    reg(ctx, "exec") { L ->
      val cmd = L.toString(2) ?: ""
      val wantOut = if (L.type(3) == com.autolua.engine.base.LuaContext.ValueType.NIL) true
      else L.toBoolean(3)
      if (wantOut) {
        ctx.push(execOut(cmd))
        1
      } else {
        val r = exec(cmd)
        ctx.push(r[0] as String)
        ctx.push((r[1] as Int).toLong())
        2
      }
    }
    reg(ctx, "execOut") { L -> ctx.push(execOut(L.toString(2) ?: "")); 1 }
    // 系统操作（懒人 device 系统操作对齐；root 环境下可用）
    reg(ctx, "lockScreen") { ctx.push(lockScreen()); 1 }
    reg(ctx, "unLockScreen") { ctx.push(unLockScreen()); 1 }
    reg(ctx, "setDisplayPowerOff") { ctx.push(setDisplayPowerOff()); 1 }
    reg(ctx, "setWifiEnable") { L -> ctx.push(setWifiEnable(L.toBoolean(2))); 1 }
    reg(ctx, "setBTEnable") { L -> ctx.push(setBTEnable(L.toBoolean(2))); 1 }
    reg(ctx, "setAirplaneMode") { L -> ctx.push(setAirplaneMode(L.toBoolean(2))); 1 }
    reg(ctx, "setDpiToVir") { L ->
      val dpi = if (L.type(2) == com.autolua.engine.base.LuaContext.ValueType.NUMBER) L.toLong(2).toInt() else 0
      ctx.push(setDpiToVir(dpi)); 1
    }
    reg(ctx, "setDpiToRealy") { ctx.push(setDpiToRealy()); 1 }
    reg(ctx, "sendSms") { L -> ctx.push(sendSms(L.toString(2) ?: "", L.toString(3) ?: "")); 1 }
    reg(ctx, "phoneCall") { L -> ctx.push(phoneCall(L.toString(2) ?: "")); 1 }
    reg(ctx, "runIntent") { L -> ctx.push(runIntent(L.toString(2) ?: "", L.toString(3))); 1 }
    reg(ctx, "installApk") { L -> ctx.push(installApk(L.toString(2) ?: "")); 1 }
    reg(ctx, "scanImage") { L -> ctx.push(scanImage(L.toString(2) ?: "")); 1 }
    reg(ctx, "extractAssets") { L -> ctx.push(extractAssets(L.toString(2) ?: "", L.toString(3) ?: "")); 1 }
  }

  private fun reg(ctx: LuaContext, name: String, body: (LuaContext) -> Int) {
    ctx.push(object : com.autolua.engine.base.LuaObjectAdapter {
      override fun invoke(L: LuaContext): Int = body(L)
      override fun index(L: LuaContext): Int = 0
      override fun newIndex(L: LuaContext): Int = 0
      override fun call(L: LuaContext, name: String): Int = 0
      override fun release(L: LuaContext) {}
    })
    ctx.setGlobal(name)
  }
}
