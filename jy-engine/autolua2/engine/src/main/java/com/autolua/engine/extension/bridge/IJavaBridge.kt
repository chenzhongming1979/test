package com.autolua.engine.extension.bridge

/**
 * luajava 反射桥服务接口（注入为 Lua 全局 `luajava`）。
 * 必须注册在 Lua 所在进程本地（root 进程 Server.kt / IDE 沙箱 LuaEngineExecutor）。
 */
interface IJavaBridge {
  /** 类名 → Class 包装（LuaClassWrapper）：支持 :new()、静态方法、静态字段 */
  fun bindClass(name: String): Any?

  /** 类名 + 构造参数 → 实例包装 */
  fun newInstance(name: String, vararg args: Any?): Any?

  /** Class 包装 + 构造参数 → 实例包装（等价 cls:new(...)） */
  fun new(classObj: Any?, vararg args: Any?): Any?
}
