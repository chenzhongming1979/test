package com.autolua.engine.extension.bridge

import com.autolua.engine.base.JavaObjectWrapper
import com.autolua.engine.base.LuaContext
import com.autolua.engine.base.LuaObjectAdapter
import com.autolua.engine.base.MethodCache

/**
 * Java 类包装器（luajava 反射桥核心）：把 [Class] 对象暴露给 Lua。
 *
 * Lua 侧能力：
 * - `cls:new(...)` → 匹配构造器实例化（返回实例包装）
 * - `cls.静态方法(...)` → 静态方法反射调用
 * - `cls.FIELD` 读 / `cls.FIELD = v` 写 → 静态字段读写
 * - `cls.obj` → 原生 Class 对象（JavaObjectWrapper 约定，toValue 转换用）
 *
 * 实例对象经 pushValue 包装为 JavaObjectWrapper（实例方法反射），与类包装器配合构成完整桥。
 */
class LuaClassWrapper(
  private val aClass: Class<*>,
  val obj: Any,
  private val methodCache: MethodCache
) : LuaObjectAdapter {

  /** 未传参（超出栈顶）时的缺省值：基本类型给零值，引用类型 null */
  private fun defaultValue(type: Class<*>): Any? = when (type) {
    java.lang.Integer.TYPE -> 0
    java.lang.Long.TYPE -> 0L
    java.lang.Double.TYPE -> 0.0
    java.lang.Float.TYPE -> 0f
    java.lang.Boolean.TYPE -> false
    java.lang.Short.TYPE -> 0.toShort()
    java.lang.Byte.TYPE -> 0.toByte()
    java.lang.Character.TYPE -> ' '
    else -> null
  }

  override fun index(L: LuaContext): Int {
    val name = L.toString(2) ?: return 0
    // cls:new(...) 构造语法糖
    if (name == "new") return 2
    // 静态字段优先（含枚举常量）；失败回退静态方法（返回 2 = 存在，走 callMethod）
    try {
      val field = aClass.getField(name)
      L.pushValue(field.get(null), field.type)
      return 1
    } catch (e: NoSuchFieldException) {
      // fallthrough to method lookup
    } catch (e: Throwable) {
      throw e
    }
    return if (methodCache.findMethod(aClass, name) != null) 2 else 0
  }

  override fun newIndex(L: LuaContext): Int {
    val name = L.toString(2)
    val field = aClass.getField(name!!)
    val value = L.toValue(3, field.type)
    field.set(null, value)
    return 0
  }

  @Throws(Throwable::class)
  override fun call(L: LuaContext, name: String): Int {
    if (name == "new") {
      return callNew(L)
    }
    val method = methodCache.findMethod(aClass, name)
      ?: throw NoSuchMethodException("Method not found:$aClass.$name")
    var args: Array<Any?> = arrayOfNulls(0)
    val types = method.parameterTypes
    if (types.isNotEmpty()) {
      args = arrayOfNulls(types.size)
      // 参数起点自适应：cls:m(a) 冒号调用跳过 self，cls.m(a) 点调用从 index 1 起；
      // index 超出栈顶视为未传参（null，对应 Java 侧基本类型默认值）
      val self = L.toLuaObjectAdapter(1)
      val argStart = if (self === this) 2 else 1
      for (i in types.indices) {
        val idx = argStart + i
        args[i] = if (idx <= L.top) L.toValue(idx, types[i]) else defaultValue(types[i])
      }
    }
    val result = method.invoke(null, *args)
    val resultType = method.returnType
    if (resultType.isArray && resultType.componentType == java.lang.Object::class.java) {
      val arr = result as Array<*>
      for (o in arr) L.pushValue(o, java.lang.Object::class.java)
      return arr.size
    }
    L.pushValue(result, resultType)
    return 1
  }

  /** 构造器匹配：先按参数个数精确匹配，逐个尝试参数转换（失败换下一个候选） */
  private fun callNew(L: LuaContext): Int {
    // 参数起点自适应：cls:new(a) 冒号调用栈首为 cls（跳过），cls.new(a) 点调用从 index 1 起
    val self = L.toLuaObjectAdapter(1)
    val argStart = if (self === this) 2 else 1
    val argCount = L.top - argStart + 1
    val constructors = aClass.declaredConstructors
    // 精确参数个数优先
    for (c in constructors) {
      if (c.parameterTypes.size != argCount) continue
      c.isAccessible = true
      try {
        val args = arrayOfNulls<Any?>(argCount)
        for (i in 0 until argCount) {
          args[i] = L.toValue(argStart + i, c.parameterTypes[i])
        }
        val instance = c.newInstance(*args)
        L.pushValue(instance, instance.javaClass)
        return 1
      } catch (ignored: Throwable) {
        // 类型不匹配，尝试下一个候选
      }
    }
    // 可变参数构造器（最后一个参数为数组）兜底
    for (c in constructors) {
      val types = c.parameterTypes
      if (types.isEmpty() || !types[types.size - 1].isArray) continue
      if (argCount < types.size - 1) continue
      c.isAccessible = true
      try {
        val fixed = types.size - 1
        val args = arrayOfNulls<Any?>(types.size)
        for (i in 0 until fixed) {
          args[i] = L.toValue(argStart + i, types[i])
        }
        val varType = types[types.size - 1].componentType!!
        val varArgs = java.lang.reflect.Array.newInstance(varType, argCount - fixed)
        for (i in 0 until argCount - fixed) {
          java.lang.reflect.Array.set(varArgs, i, L.toValue(argStart + fixed + i, varType))
        }
        args[fixed] = varArgs
        val instance = c.newInstance(*args)
        L.pushValue(instance, instance.javaClass)
        return 1
      } catch (ignored: Throwable) {
      }
    }
    throw NoSuchMethodException("No suitable constructor of $aClass with $argCount args")
  }

  override fun invoke(L: LuaContext): Int {
    // 类对象不可直接调用
    return 0
  }

  override fun release(L: LuaContext) {
  }
}
