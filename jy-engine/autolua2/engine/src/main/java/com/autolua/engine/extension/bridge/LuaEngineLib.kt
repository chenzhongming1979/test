package com.autolua.engine.extension.bridge

import android.content.Context
import dalvik.system.DexClassLoader
import java.io.File

/**
 * LuaEngine java 桥实现（懒人 LuaEngine.* 轻量对齐）。
 *
 * - app 进程（IDE 沙箱）：构造注入 Context
 * - root 进程（app_process）：getContext 反射 ActivityThread.currentApplication()，
 *   失败返回 null（懒人 root 模式同此行为）；DexClassLoader 的 optimizedDir 回退 /data/local/tmp
 */
open class LuaEngineLib(private val appContext: Context?) : ILuaEngineLib {

  /** 无参构造：root 进程本地实例化路径（AutoLuaEngineOnLocal.newLocalServiceObj） */
  constructor() : this(null)

  companion object {
    private const val VERSION = "2.0"

    /** root 进程 optimizedDir 兜底目录（uid 0 可写） */
    private const val FALLBACK_OPT_DIR = "/data/local/tmp"
  }

  override fun getContext(): Any? {
    if (appContext != null) return appContext
    return try {
      val activityThread = Class.forName("android.app.ActivityThread")
      val currentApplication = activityThread.getMethod("currentApplication")
      currentApplication.invoke(null) as? Context
    } catch (t: Throwable) {
      null
    }
  }

  override fun loadApk(path: String, className: String): Any? {
    val apkFile = File(path)
    if (!apkFile.exists()) {
      throw IllegalArgumentException("LuaEngine.loadApk: file not found: $path")
    }
    val optDir = appContext?.let { File(it.codeCacheDir, "luaplugin").absolutePath }
      ?: FALLBACK_OPT_DIR
    val optDirFile = File(optDir)
    if (!optDirFile.exists()) optDirFile.mkdirs()
    val loader = DexClassLoader(
      apkFile.absolutePath,
      optDir,
      null,
      LuaEngineLib::class.java.classLoader
    )
    val clazz = loader.loadClass(className)
    return clazz
  }

  override fun getVersion(): String = VERSION
}
