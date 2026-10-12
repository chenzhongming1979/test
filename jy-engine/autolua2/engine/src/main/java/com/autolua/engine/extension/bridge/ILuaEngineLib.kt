package com.autolua.engine.extension.bridge

/**
 * LuaEngine java 桥服务接口（注入为 Lua 全局 `LuaEngine`，对齐懒人 LuaEngine.* 系列）。
 * 必须注册在 Lua 所在进程本地。
 */
interface ILuaEngineLib {
  /**
   * 获取 Android Context（懒人 LuaEngine.getContext 对应）：
   * app 进程返回构造注入的 Context；root 进程反射 ActivityThread.currentApplication()（可能为 null）
   */
  fun getContext(): Any?

  /**
   * 加载 apk/dex 插件并返回类对象（LuaClassWrapper，可 :new(...) 实例化）
   * = 懒人 LuaEngine.loadApk 的轻量形态；完整插件生命周期走 PluginManager（app 层）
   */
  fun loadApk(path: String, className: String): Any?

  /** 引擎版本号 */
  fun getVersion(): String
}
