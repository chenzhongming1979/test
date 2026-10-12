package com.autolua.engine.extension.device

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import com.autolua.engine.base.LuaContext
import com.autolua.engine.base.LuaEnvContributor
import java.net.NetworkInterface
import java.util.Enumeration

/**
 * 设备信息服务（注入为 Lua 全局 `device`），并经 contribute 注册懒人风格全局函数：
 * getBrand getModel getManufacturer getSdkVersion getOsVersionName getDisplayDpi
 * getBatteryLevel getDeviceId getAndroidId getCpuAbi getBoard getFingerprint getHardware
 * getProduct getDevice getBootLoader getId getWifiMac getIpAddress getMacAddress getInstalledApps
 *
 * 说明：
 * - app 进程（沙箱）传入 Context；root 进程无 Context，系统服务类返回 nil/空串
 * - IMEI 等强权限标识在 Android 10+ 均不可取，getDeviceId 返回 ANDROID_ID 或空
 */
open class DeviceLib(private val appContext: Context? = null) : LuaEnvContributor {

  /** 无参构造：root 进程本地实例化 */
  constructor() : this(null)

  private fun ctx(): Context? = appContext

  // ---------------- Build 信息（无需 Context） ----------------

  fun getBrand(): String = Build.BRAND ?: ""
  fun getModel(): String = Build.MODEL ?: ""
  fun getManufacturer(): String = Build.MANUFACTURER ?: ""
  fun getBoard(): String = Build.BOARD ?: ""
  fun getHardware(): String = Build.HARDWARE ?: ""
  fun getProduct(): String = Build.PRODUCT ?: ""
  fun getDevice(): String = Build.DEVICE ?: ""
  fun getFingerprint(): String = Build.FINGERPRINT ?: ""
  fun getId(): String = Build.ID ?: ""
  fun getBootLoader(): String = Build.BOOTLOADER ?: ""
  fun getCpuAbi(): String = Build.SUPPORTED_ABIS?.firstOrNull() ?: ""
  fun getCpuAbi2(): String = if ((Build.SUPPORTED_ABIS?.size ?: 0) > 1) Build.SUPPORTED_ABIS[1] else ""
  fun getSdkVersion(): Int = Build.VERSION.SDK_INT
  fun getOsVersionName(): String = Build.VERSION.RELEASE ?: ""
  fun getAndroidVersion(): String = Build.VERSION.RELEASE ?: ""

  // ---------------- 需要系统服务/Context ----------------

  fun getAndroidId(): String = try {
    android.provider.Settings.Secure.getString(ctx()?.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: ""
  } catch (t: Throwable) { "" }

  fun getDeviceId(): String = getAndroidId()

  fun getBatteryLevel(): Int = try {
    val bm = ctx()?.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
  } catch (t: Throwable) { -1 }

  fun isCharging(): Boolean = try {
    val bm = ctx()?.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    bm?.isCharging ?: false
  } catch (t: Throwable) { false }

  fun getDisplayDpi(): Float = try {
    ctx()?.resources?.displayMetrics?.densityDpi?.toFloat() ?: 0f
  } catch (t: Throwable) { 0f }

  fun getDisplaySize(): IntArray = try {
    val dm = ctx()?.resources?.displayMetrics
    if (dm != null) {
      intArrayOf(dm.widthPixels, dm.heightPixels)
    } else {
      // root 进程无 Context：回退 IWindowManager binder 查询
      val p = android.graphics.Point()
      com.autolua.engine.conceal.IWindowManager.getBaseDisplaySize(0, p)
      intArrayOf(p.x, p.y)
    }
  } catch (t: Throwable) { intArrayOf(0, 0) }

  // ---------------- 网络 ----------------

  fun getMacAddress(): String {
    return try {
      val nis: Enumeration<NetworkInterface> = NetworkInterface.getNetworkInterfaces()
      while (nis.hasMoreElements()) {
        val mac = nis.nextElement()?.hardwareAddress
        if (mac != null && mac.isNotEmpty()) {
          return mac.joinToString(":") { String.format("%02x", it) }
        }
      }
      ""
    } catch (t: Throwable) { "" }
  }

  fun getIpAddress(): String {
    return try {
      val nis: Enumeration<NetworkInterface> = NetworkInterface.getNetworkInterfaces()
      while (nis.hasMoreElements()) {
        val nis2 = nis.nextElement()?.inetAddresses ?: continue
        while (nis2.hasMoreElements()) {
          val addr = nis2.nextElement()
          if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
            return addr.hostAddress ?: ""
          }
        }
      }
      ""
    } catch (t: Throwable) { "" }
  }

  fun getWifiMac(): String = getMacAddress()

  fun isWifiEnabled(): Boolean = try {
    val wifi = ctx()?.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
    wifi?.isWifiEnabled ?: false
  } catch (t: Throwable) { false }

  // ---------------- 剪贴板（沙箱 ClipboardManager / root IClipboard binder 反射） ----------------

  private fun clipboardManager(): android.content.ClipboardManager? =
    ctx()?.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager

  /** root 进程无 Context：直接 binder 调 IClipboard（uid 0 有权） */
  private fun <T> withIClipboard(body: (Any) -> T): T? = try {
    val binder = Class.forName("android.os.ServiceManager")
      .getMethod("getService", String::class.java).invoke(null, "clipboard") as android.os.IBinder
    val clip = Class.forName("android.content.IClipboard\$Stub")
      .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
    body(clip)
  } catch (t: Throwable) { null }

  /**
   * IClipboard 各 Android 版本签名不一（getPrimaryClip(String[,int[,int]]) 等），
   * 按方法名取候选，实参按形参类型自适应填充（String→包名 / Int→userId / ClipData→给定值）
   */
  private fun invokeClipboard(clip: Any, name: String, clipData: android.content.ClipData?): Any? {
    val m = clip.javaClass.methods.firstOrNull {
      it.name == name && it.parameterTypes.isNotEmpty() &&
        it.parameterTypes[0] == android.content.ClipData::class.java
    } ?: clip.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isNotEmpty() }
      ?: throw NoSuchMethodException(name)
    val pkg = "com.autolua.autolua2"
    val args = m.parameterTypes.map { t ->
      when {
        t == android.content.ClipData::class.java -> clipData
        t == String::class.java -> pkg
        t == Int::class.javaPrimitiveType -> 0
        t == java.lang.Integer::class.java -> 0
        else -> null
      }
    }.toTypedArray()
    return m.invoke(clip, *args)
  }

  fun readPasteboard(): String = try {
    val cm = clipboardManager()
    if (cm != null) {
      cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
    } else {
      withIClipboard { clip ->
        val cd = invokeClipboard(clip, "getPrimaryClip", null) as? android.content.ClipData
        cd?.getItemAt(0)?.text?.toString() ?: ""
      } ?: ""
    }
  } catch (t: Throwable) { "" }

  fun writePasteboard(text: String): Boolean = try {
    val cd = android.content.ClipData.newPlainText("text", text)
    val cm = clipboardManager()
    if (cm != null) {
      cm.setPrimaryClip(cd); true
    } else {
      withIClipboard { clip ->
        invokeClipboard(clip, "setPrimaryClip", cd)
        true
      } ?: false
    }
  } catch (t: Throwable) { false }

  // ---------------- 振动（需 Context；root 进程无 Vibrator 访问，返回 false） ----------------

  fun vibrate(ms: Long): Boolean = try {
    val vib = ctx()?.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
    if (vib != null) {
      if (Build.VERSION.SDK_INT >= 26) vib.vibrate(android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
      else @Suppress("DEPRECATION") vib.vibrate(ms)
      true
    } else false
  } catch (t: Throwable) { false }

  // ---------------- 懒人风格全局函数 ----------------

  override fun contribute(ctx: LuaContext) {
    reg(ctx, "getBrand") { pushS(ctx, getBrand()) }
    reg(ctx, "getModel") { pushS(ctx, getModel()) }
    reg(ctx, "getManufacturer") { pushS(ctx, getManufacturer()) }
    reg(ctx, "getBoard") { pushS(ctx, getBoard()) }
    reg(ctx, "getHardware") { pushS(ctx, getHardware()) }
    reg(ctx, "getProduct") { pushS(ctx, getProduct()) }
    reg(ctx, "getDevice") { pushS(ctx, getDevice()) }
    reg(ctx, "getFingerprint") { pushS(ctx, getFingerprint()) }
    reg(ctx, "getId") { pushS(ctx, getId()) }
    reg(ctx, "getBootLoader") { pushS(ctx, getBootLoader()) }
    reg(ctx, "getCpuAbi") { pushS(ctx, getCpuAbi()) }
    reg(ctx, "getSdkVersion") { ctx.push(getSdkVersion().toLong()); 1 }
    reg(ctx, "getOsVersionName") { pushS(ctx, getOsVersionName()) }
    reg(ctx, "getDeviceId") { pushS(ctx, getDeviceId()) }
    reg(ctx, "getAndroidId") { pushS(ctx, getAndroidId()) }
    reg(ctx, "getBatteryLevel") { ctx.push(getBatteryLevel().toLong()); 1 }
    reg(ctx, "getDisplayDpi") { ctx.push(getDisplayDpi().toDouble()); 1 }
    reg(ctx, "getWifiMac") { pushS(ctx, getWifiMac()) }
    reg(ctx, "getMacAddress") { pushS(ctx, getMacAddress()) }
    reg(ctx, "getIpAddress") { pushS(ctx, getIpAddress()) }
    // getDisplaySize 返回 width, height 两值
    reg(ctx, "getDisplaySize") {
      val size = getDisplaySize()
      ctx.push(size[0].toLong())
      ctx.push(size[1].toLong())
      2
    }
    // 剪贴板（懒人 readPasteboard/writePasteboard；派大星 clipboard.get/set）
    reg(ctx, "readPasteboard") { pushS(ctx, readPasteboard()) }
    reg(ctx, "writePasteboard") { L -> ctx.push(writePasteboard(L.toString(2) ?: "")); 1 }
    reg(ctx, "vibrate") { L ->
      val ms = if (L.type(2) == com.autolua.engine.base.LuaContext.ValueType.NUMBER) L.toLong(2) else 0L
      ctx.push(vibrate(ms)); 1
    }
  }

  private fun pushS(ctx: LuaContext, s: String): Int {
    ctx.push(s)
    return 1
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
