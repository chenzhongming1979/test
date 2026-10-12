package com.autolua.engine.base

import android.util.Log
import com.autolua.engine.common.Utils

import java.lang.reflect.Method


class JavaObjectWrapper(
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
  override fun index(L:LuaContext): Int {
    val name = L.toString(2)
    return if(methodCache.findMethod(aClass, name!!) != null) 2 else 0
  }

  override fun newIndex(L: LuaContext): Int {
    val name = L.toString(2)
    val field = aClass.getField(name!!)
    val value = L.toValue(3, field.type)
    field.set(obj, value)
    return 0
  }

  companion object {
    private val nullArray = arrayOfNulls<Any>(0)
  }

  @Throws(Throwable::class)
  override fun call(L: LuaContext, name: String): Int {
    // 参数起点自适应：obj:m(a) 冒号调用栈首为 self（跳过），obj.m(a) 点调用从 index 1 起；
    // index 超出栈顶视为未传参（null，对应 Lua 缺省可选参数）
    val self = L.toLuaObjectAdapter(1)
    val argStart = if (self === this) 2 else 1
    val luaArgCount = L.top - argStart + 1
    val method = pickOverload(L, name, argStart, luaArgCount)
      ?: throw NoSuchMethodException("Method not found:$name")
    var args = nullArray
    val types = method.parameterTypes
    if (types.isNotEmpty()) {
      args = arrayOfNulls(types.size)
      // vararg 方法（Java .../ Kotlin vararg）：尾参是数组，把剩余所有 Lua 实参收集成该数组
      val varArgIdx = if (method.isVarArgs) types.size - 1 else -1
      for (i in types.indices) {
        if (i == varArgIdx) {
          val compType = types[i].componentType
          val remain = if (L.top >= argStart + i) L.top - (argStart + i) + 1 else 0
          val arr = java.lang.reflect.Array.newInstance(compType, remain) as Array<Any?>
          for (j in 0 until remain) {
            arr[j] = L.toValue(argStart + i + j, compType)
          }
          args[i] = arr
          break
        }
        val idx = argStart + i
        args[i] = if (idx <= L.top) L.toValue(idx, types[i]) else defaultValue(types[i])
      }
    }
    val result = method.invoke(obj, *args)
    val resultType = method.returnType
    // Object[] 返回值展开为 Lua 多返回值（如 http.request 的 body, status, headers）。
    // 运行时 component==Object 判定同时覆盖接口泛型擦除：INetLib.get 声明 Object（擦除），
    // 实际返回 Object[]，按声明类型不会展开、整体被包成单 table（自测 11 章复现）
    if ((resultType.isArray && resultType.componentType == java.lang.Object::class.java)
      || (result != null && result.javaClass.isArray && result.javaClass.componentType == java.lang.Object::class.java)
    ) {
      val arr = result as Array<*>
      for (o in arr) {
        L.pushValue(o, java.lang.Object::class.java)
      }
      return arr.size
    }
    L.pushValue(result, resultType)
    return 1
  }

  /** 重载解析：按实参数量与 Lua 值类型对候选方法打分，取最高分；全部不适配则回退首个同名方法 */
  private fun pickOverload(L: LuaContext, name: String, argStart: Int, luaArgCount: Int): Method? {
    val candidates = methodCache.findMethods(aClass, name)
    if (candidates.isEmpty()) return null
    if (candidates.size == 1) return candidates[0]
    var best: Method? = null
    var bestScore = Int.MIN_VALUE
    for (m in candidates) {
      val types = m.parameterTypes
      val isVarArg = m.isVarArgs
      // vararg 方法接受 [types.size-1, +∞) 个实参；普通方法实参不得多于形参
      if (isVarArg) {
        if (luaArgCount < types.size - 1) continue
      } else {
        if (luaArgCount > types.size) continue
      }
      var score = 100 - (types.size - luaArgCount) * 10
      var ok = true
      // vararg 尾参不定长，不参与逐参打分
      val scoreLimit = if (isVarArg) types.size - 1 else luaArgCount
      for (i in 0 until minOf(luaArgCount, scoreLimit)) {
        val s = scoreArg(L, argStart + i, types[i])
        if (s < 0) {
          ok = false
          break
        }
        score += s
      }
      if (ok && score > bestScore) {
        bestScore = score
        best = m
      }
    }
    return best ?: candidates[0]
  }

  /** 单个实参与形参类型兼容性打分；<0 表示确定不兼容 */
  private fun scoreArg(L: LuaContext, idx: Int, param: Class<*>): Int = when (L.type(idx)) {
    LuaContext.ValueType.NIL, LuaContext.ValueType.NONE ->
      if (param.isPrimitive) -100 else 1
    LuaContext.ValueType.BOOLEAN -> when (param) {
      java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> 4
      java.lang.Object::class.java -> 1
      else -> -100
    }
    LuaContext.ValueType.NUMBER -> when (param) {
      java.lang.Integer.TYPE, java.lang.Integer::class.java -> 5
      java.lang.Long.TYPE, java.lang.Long::class.java -> 4
      java.lang.Double.TYPE, java.lang.Double::class.java -> 4
      java.lang.Float.TYPE, java.lang.Float::class.java -> 3
      java.lang.Short.TYPE, java.lang.Short::class.java -> 3
      java.lang.Byte.TYPE, java.lang.Byte::class.java -> 3
      java.lang.Number::class.java, java.lang.Object::class.java -> 1
      else -> -100
    }
    LuaContext.ValueType.STRING -> when (param) {
      String::class.java -> 6
      java.lang.CharSequence::class.java -> 4
      java.lang.Character.TYPE, java.lang.Character::class.java -> 2
      ByteArray::class.java -> 1
      java.lang.Object::class.java -> 1
      else -> -100
    }
    LuaContext.ValueType.TABLE -> when {
      param == java.util.Map::class.java || param == java.util.List::class.java -> 4
      param.isArray -> 3
      param == java.lang.Object::class.java -> 1
      else -> 0
    }
    LuaContext.ValueType.USERDATA -> if (param.isPrimitive) -100 else 1
    else -> if (param.isPrimitive) -100 else 0
  }

  override fun invoke(L: LuaContext): Int {
    TODO("Not yet implemented")
  }


  @Throws(Throwable::class)
  override fun release(L: LuaContext) {
    if (Releasable::class.java.isInstance(obj)) {
      (obj as Releasable).onRelease()
    }
  }
}
