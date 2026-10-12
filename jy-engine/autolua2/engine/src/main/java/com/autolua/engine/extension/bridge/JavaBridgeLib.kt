package com.autolua.engine.extension.bridge

import com.autolua.engine.base.JavaObjectWrapper
import com.autolua.engine.base.LuaContext
import com.autolua.engine.base.LuaEnvContributor
import com.autolua.engine.base.LuaObjectAdapter
import com.autolua.engine.base.MethodCache

/**
 * luajava 反射桥实现。
 *
 * 注入后 Lua 可用：
 * ```lua
 * local JString = import("java.lang.String")          -- 全局函数（contribute 注册）
 * local cls = luajava.bindClass("java.lang.Integer")  -- 对象式
 * local n = cls:new(42)                                -- 构造实例
 * local max = java.lang.Math.max(1, 2)                -- 静态调用（import 返回的类支持链式静态）
 * ```
 *
 * 构造器形态（AutoLuaEngineOnLocal.newLocalServiceObj）：
 * - (LuaContext) 构造器：contribute 注册全局 import/importClass 需要 LuaContext
 * - 无参构造器：沙箱路径由 contribute(sandbox) 补持上下文
 */
open class JavaBridgeLib : IJavaBridge, LuaEnvContributor {

  private val methodCache = MethodCache()
  private var envContext: LuaContext? = null

  constructor()

  constructor(ctx: LuaContext) {
    envContext = ctx
  }

  override fun bindClass(name: String): Any? {
    val clazz = Class.forName(name)
    return LuaClassWrapper(clazz, clazz, methodCache)
  }

  override fun newInstance(name: String, vararg args: Any?): Any? {
    return new(bindClass(name), *args)
  }

  override fun new(classObj: Any?, vararg args: Any?): Any? {
    val wrapper = classObj as? LuaClassWrapper
      ?: throw IllegalArgumentException("luajava.new: expect a class object (bindClass/import result)")
    val clazz = wrapper.obj as Class<*>
    val constructors = clazz.declaredConstructors
    for (c in constructors) {
      if (c.parameterTypes.size != args.size) continue
      c.isAccessible = true
      try {
        val instance = c.newInstance(*args)
        return instance
      } catch (ignored: Throwable) {
        // 类型不匹配尝试下一候选
      }
    }
    throw NoSuchMethodException("No suitable constructor of $clazz with ${args.size} args")
  }

  override fun contribute(ctx: LuaContext) {
    envContext = ctx
    // 全局 import(name) / importClass(name)：返回 LuaClassWrapper（service 方法返回
    // LuaObjectAdapter 类型在 JavaObjectWrapper.call 中经 pushValue 的 LuaObjectAdapter 分支直推）
    registerGlobalFunction(ctx, "import") { L -> pushBindClassResult(L) }
    registerGlobalFunction(ctx, "importClass") { L -> pushBindClassResult(L) }
  }

  /** 单返回值 push：bindClass 结果直接以 userdata 形态压栈 */
  private fun pushBindClassResult(L: LuaContext): Int {
    val name = L.toString(2)
      ?: throw IllegalArgumentException("import: class name expected")
    val clazz = Class.forName(name)
    L.push(LuaClassWrapper(clazz, clazz, methodCache) as LuaObjectAdapter)
    return 1
  }

  /**
   * 注册单参数全局函数：函数体由 [body] 实现（栈布局 index 1 = adapter 本体，参数从 index 2 起），
   * 返回值为压栈结果个数（对齐 LuaObjectAdapter.invoke 约定）。
   */
  private fun registerGlobalFunction(
    ctx: LuaContext,
    name: String,
    body: (LuaContext) -> Int
  ) {
    ctx.push(object : LuaObjectAdapter {
      override fun invoke(L: LuaContext): Int = body(L)
      override fun index(L: LuaContext): Int = 0
      override fun newIndex(L: LuaContext): Int = 0
      override fun call(L: LuaContext, name: String): Int = 0
      override fun release(L: LuaContext) {}
    })
    ctx.setGlobal(name)
  }
}
