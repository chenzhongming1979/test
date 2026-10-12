package com.autolua.engine.base

import android.util.Log
import com.autolua.engine.base.LuaContext.CodeMode
import com.autolua.engine.base.LuaContext.ValueType
import com.autolua.engine.base.LuaContext.ValueType.Companion.valueOf
import com.autolua.engine.common.Utils
import kotlin.reflect.full.memberProperties
import kotlin.reflect.javaType


class LuaContextImplement(private val objectCache:ObjectCache,
                          private val methodCache: MethodCache = MethodCache())
  : LuaContext {
  private var nativeLua: Long = 0

  fun getNativeLua(): Long {
    return nativeLua
  }


  private external fun createLuaContext(): Long

  init {
    nativeLua = createLuaContext()
  }


  //适配器相关的操作

  fun indexMethod(id: Long): Int {
    val adapter = objectCache.get(id) as LuaObjectAdapter
    return adapter.index(this)
  }

  fun invokeMethod(id: Long): Int {
    val adapter = objectCache.get(id) as LuaObjectAdapter
    return adapter.invoke(this)
  }

  fun newIndexMethod(id: Long): Int {
    val adapter = objectCache.get(id) as LuaObjectAdapter
    return adapter.newIndex(this)
  }

  fun callMethod(id: Long, name: String): Int {
    val adapter = objectCache.get(id) as LuaObjectAdapter
    return adapter.call(this, name)
  }



  fun release(id: Long) {
    val adapter = objectCache.remove(id)
    if (adapter != null && adapter is LuaObjectAdapter)
        adapter.release(this)
    val str = adapter?.javaClass?.name ?: "null"
    Utils.log(TAG, "release lua object adapter failed $str")
  }

  override fun destroy() {
    if(nativeLua>0){
      destroy(nativeLua)
      nativeLua = 0
    }
  }


  override fun toPointer(index: Int): Long {
    return toPointer(nativeLua, index)
  }

  override fun toLong(index: Int): Long {
    return toInteger(nativeLua, index)
  }

  override fun toDouble(index: Int): Double {
    return toNumber(nativeLua, index)
  }

  override fun toString(index: Int): String? {
    return toString(nativeLua, index)
  }


  override fun toBytes(index: Int): ByteArray? {
    return toBytes(nativeLua, index)
  }


  override fun toBoolean(index: Int): Boolean {
    return toBoolean(nativeLua, index)
  }

  override fun toValue(index: Int, valueClass: Class<*>): Any? {
    return when(valueClass){
      Long::class.javaPrimitiveType -> toLong(index)
      Double::class.javaPrimitiveType -> toDouble(index)
      String::class.java -> toString(index)
      ByteArray::class.java ->  toBytes(index)
      Boolean::class.javaPrimitiveType -> toBoolean(index)
      Int::class.javaPrimitiveType ->  toLong(index).toInt()
      Float::class.javaPrimitiveType ->  toDouble(index).toFloat()
      Char::class.javaPrimitiveType, java.lang.Character::class.java -> {
        val s = toString(index) ?: throw LuaTypeError("can not convert to char")
        if (s.length != 1) throw LuaTypeError("expect single char, got len ${s.length}")
        s[0]
      }
      Byte::class.javaPrimitiveType -> toLong(index).toByte()
      Short::class.javaPrimitiveType -> toLong(index).toShort()
      Unit::class.java -> null
      else -> {
        val indexType = type(index)
        if(valueClass.isEnum){
          val n = toLong(index)
          val values = valueClass.enumConstants as Array<Enum<*>>
          if(n<0 || n>=values.size) throw LuaTypeError("can not convert to ${valueClass.name}")
          values[n.toInt()]
        }else if (indexType == ValueType.NONE || indexType == ValueType.NIL) null
        else if(indexType == ValueType.USERDATA){
          val adapter = toLuaObjectAdapter(index)
            ?: throw LuaTypeError("can not convert to ${valueClass.name}")
          val obj = when(adapter){
            is JavaObjectWrapper -> adapter.obj
            is com.autolua.engine.extension.bridge.LuaClassWrapper -> adapter.obj
            else -> adapter
          }
          if(valueClass.isInstance(obj))
            return obj
          else
            throw LuaTypeError("can not convert to ${valueClass.name}")
        }
        else if(indexType == ValueType.TABLE) {
          table2JavaObj(index,valueClass)
        }
        else if(valueClass == java.lang.Object::class.java){
          // Object 参数兜底装箱：Map.get(Object)/equals(Object) 等常见签名
          // 传 Lua 字符串/布尔/数字时按对应 Java 包装类型转换
          when(indexType){
            ValueType.STRING -> toString(index)
            ValueType.BOOLEAN -> toBoolean(index)
            ValueType.NUMBER -> if(isInteger(index)) toLong(index) else toDouble(index)
            else -> throw LuaTypeError("can not convert to ${valueClass.name}")
          }
        }
        else throw LuaTypeError("can not convert to ${valueClass.name}")
      }
    }
  }

  private fun table2JavaObj(tableIndex:Int, type: Class<*>):Any{
    if(type == java.util.Map::class.java){
      return table2Map(tableIndex)
    }
    if(type == java.util.List::class.java){
      return table2List(tableIndex)
    }
    if(type.isArray){
      val childType = type.componentType!!
      val size = len(nativeLua, tableIndex)
      val array = java.lang.reflect.Array.newInstance(childType,size.toInt())
      for(i in 1..size){
        push(i)
        getTable(tableIndex)
        val value = toValue(getTop(nativeLua),childType)
        java.lang.reflect.Array.set(array,i.toInt()-1,value)
        pop(1)
      }
      return array
    }else{
      val constructor = type.getDeclaredConstructor()
      constructor.isAccessible = true
      val obj = constructor.newInstance()
      pushNil()
      while (next(nativeLua,tableIndex)){
        if(type(-2) == ValueType.STRING){
          val key = toString(-2)!!
          val field = type.getField(key)
          field.isAccessible = true
          val value = toValue(getTop(nativeLua),field.type)
          field.set(obj,value)
        }
        pop(1)
      }
      return obj
    }
  }

  /**
   * Lua table → Java 通用容器转换（递归）：
   * 连续数字键 1..n 视为数组（ArrayList），否则视为字典（LinkedHashMap）。
   * 服务方法参数声明为 Map<*,*> / List<*> 即可接收 Lua 表（含 http.request 的 options 表）。
   */
  /** 相对索引 → 绝对索引（等价 lua_absindex 非 registry 分支）；
   *  table2Map 遍历中 pushNil/pop 会移动栈顶，相对索引必须先归一化，
   *  否则 next(rawGet) 对错误栈位操作（嵌套表场景曾致 SIGSEGV） */
  private fun absIdx(index: Int): Int {
    return if (index <= 0) getTop(nativeLua) + index + 1 else index
  }

  private fun table2Java(tableIndex: Int): Any {
    val idx = absIdx(tableIndex)
    push(1L)
    val firstType = rawGet(idx)
    pop(1)
    return if (firstType != ValueType.NIL.code && firstType != ValueType.NONE.code) {
      table2List(idx)
    } else {
      table2Map(idx)
    }
  }

  private fun table2Map(tableIndex: Int): java.util.LinkedHashMap<String, Any?> {
    val idx = absIdx(tableIndex)
    val map = java.util.LinkedHashMap<String, Any?>()
    pushNil()
    while (next(nativeLua, idx)) {
      if (type(-2) == ValueType.STRING) {
        map[toString(-2)!!] = toJavaValue(-1)
      }
      pop(1)
    }
    return map
  }

  private fun table2List(tableIndex: Int): java.util.ArrayList<Any?> {
    val idx = absIdx(tableIndex)
    val list = java.util.ArrayList<Any?>()
    var i = 1L
    while (true) {
      push(i)
      val t = rawGet(idx)
      if (t == ValueType.NIL.code || t == ValueType.NONE.code) {
        pop(1)
        break
      }
      list.add(toJavaValue(-1))
      pop(1)
      i++
    }
    return list
  }

  /** 栈上任意 Lua 值 → Java 对象（table 递归为 Map/List，userdata 取包装的原生对象） */
  private fun toJavaValue(index:Int):Any?{
    return when(type(index)){
      ValueType.NIL, ValueType.NONE -> null
      ValueType.BOOLEAN -> toBoolean(index)
      ValueType.NUMBER -> if(isInteger(index)) toLong(index) else toDouble(index)
      ValueType.STRING -> toString(index)
      ValueType.TABLE -> table2Java(index)
      ValueType.USERDATA -> toLuaObjectAdapter(index)?.let {
        if(it is JavaObjectWrapper) it.obj else it
      }
      else -> null
    }
  }

  override fun toLuaObjectAdapter(index: Int): LuaObjectAdapter? {
    val id = toLuaObjectAdapter(nativeLua, index)
    if (id>0)
      return objectCache.get(id) as LuaObjectAdapter
    return null
  }


  override fun push(v: Long) {
    push(nativeLua, v)
  }


  override fun push(v: Double) {
    push(nativeLua, v)
  }


  override fun push(v: Boolean) {
    push(nativeLua, v)
  }

  override fun push(v: LuaObjectAdapter?) {
    val id = objectCache.put(v!!)
    if(id<=0){
      objectCache.remove(id)
      throw LuaError("push lua object adapter failed")
    }
    pushLuaObjectAdapter(
      nativeLua, id)
  }

  override fun push(v: Function<*>) {
    push(KotlinFunctionWrapper(v))
  }


  override fun push(v: ByteArray?) {
    push(nativeLua, v)
  }


  override fun push(v: String?) {
    if (v != null) push(nativeLua, v.toByteArray())
    else pushNil()
  }


  override fun pushNil() {
    pushNil(nativeLua)
  }

  override fun pushValue(index: Int) {
    pushValue(nativeLua, index)
  }


  private fun pushArray(array:Any,arrayClass:Class<*>){
    val size = java.lang.reflect.Array.getLength(array)
    createTable(size,0)
    val childType = arrayClass.componentType!!
    for(i in 0 until size){
      push(i+1L)
      pushValue(java.lang.reflect.Array.get(array,i),childType)
      setTable(-3)
    }
  }

  private fun pushData(data:Any,dataClass:Class<*>) {
    if(dataClass.isArray){
      pushArray(data,dataClass)
    }else{
      val fields = dataClass.declaredFields
      createTable(0,fields.size)
      for(field in fields){
        field.isAccessible = true
        push(field.name)
        pushValue(field.get(data),field.type)
        setTable(-3)
      }
    }
  }

  private fun pushEnumClass(enumClass:Class<*>){
    val values = enumClass.enumConstants as Array<Enum<*>>
    createTable(0,values.size)
    for(value in values){
      push(value.name)
      push(value.ordinal.toLong())
      setTable(-3)
    }
  }

  override fun pushValue(value: Any?, valueClass: Class<*>) {
    if (value == null) {
      pushNil()
      return
    }
    var valueType = valueClass
    if (valueClass == java.lang.Object::class.java){
      valueType = value.javaClass
    }
    if(valueType.isPrimitive){
      when(valueType){
        Long::class.javaPrimitiveType -> push(value as Long)
        Double::class.javaPrimitiveType -> push(value as Double)
        Boolean::class.javaPrimitiveType -> push(value as Boolean)
        Int::class.javaPrimitiveType -> push((value as Int).toLong())
        Float::class.javaPrimitiveType -> push((value as Float).toDouble())
        Short::class.javaPrimitiveType -> push((value as Short).toLong())
        else -> throw LuaTypeError("can not convert to ${valueType.name}")
      }
      Log.d(TAG, "pushPrimitiveValue: ${value.javaClass.name}  , ${valueType.name}  ")
      return
    }
    when (valueType) {
      java.lang.Integer::class.java -> push((value as Int).toLong())
      java.lang.Long::class.java -> push(value as Long)
      java.lang.Double::class.java -> push(value as Double)
      java.lang.Boolean::class.java -> push(value as Boolean)
      java.lang.Float::class.java -> push((value as Float).toDouble())
      java.lang.Short::class.java -> push((value as Short).toLong())
      String::class.java -> push(value as String)
      ByteArray::class.java -> push(value as ByteArray)
      Class::class.java ->{
        val nValue = value as Class<*>
        if(nValue.isEnum){
          pushEnumClass(nValue)
        }else{
          push(com.autolua.engine.extension.bridge.LuaClassWrapper(nValue, value, methodCache))
        }
      }
      else -> {
        if(LuaObjectAdapter::class.java.isInstance(value)){
          push(value as LuaObjectAdapter)
        }else if(value is Map<*, *>){
          // Map → Lua table（http headers、rsaGenerateKey 等，对齐商业引擎 table 契约）
          createTable(value.size, 0)
          for((k, v) in value){
            push(k?.toString())
            pushValue(v, java.lang.Object::class.java)
            setTable(-3)
          }
        }else if(value is List<*>){
          // List → Lua 数组表（index 从 1 起）
          createTable(0, value.size)
          var i = 1L
          for(v in value){
            push(i)
            pushValue(v, java.lang.Object::class.java)
            setTable(-3)
            i++
          }
        }else if(valueType.isInterface){
          push(JavaObjectWrapper(valueType, value, methodCache))
        }else if(valueType.methods.isEmpty()){
          pushData(value,valueType)
        }else if(valueType.isArray) {
          pushArray(value, valueType)
        }else{
          push(JavaObjectWrapper(valueType, value, methodCache))
        }
      }
    }
  }

  @OptIn(ExperimentalStdlibApi::class)
  override fun pushTable(value: Any) {
    val clazz = value::class
    createTable(0, clazz.memberProperties.size)
    for(property in clazz.memberProperties){
      val name = property.name
      val tValue = property.getter.call(value)
      push(name)
      pushValue(tValue,property.returnType.javaType as Class<*>)
      setTable(-3)
    }
  }


  override fun loadBuffer(code: ByteArray?, chunkName: String?, mode: CodeMode?) {
    loadBuffer(nativeLua, code, chunkName, mode!!.value)
  }

  override fun pcall(nArgs: Int, nResults: Int, errFunc: Int): Int {
    return pcall(nativeLua, nArgs, nResults, errFunc)
  }


  override fun loadFile(filePath: String?, mode: CodeMode?) {
    loadFile(nativeLua, filePath, mode!!.value)
  }


  override fun type(index: Int): ValueType {
    return valueOf(type(nativeLua, index))
  }


  override fun isInteger(index: Int): Boolean {
    return isInteger(nativeLua, index)
  }


  override fun createTable(arraySize: Int, dictionarySize: Int) {
    createTable(nativeLua, arraySize, dictionarySize)
  }


  override fun pop(n: Int) {
    pop(nativeLua, n)
  }


  override var top: Int
    get() = getTop(nativeLua)
    set(index) {
      setTop(nativeLua, index)
    }


  override fun setTable(tableIndex: Int) {
    setTable(nativeLua, tableIndex)
  }

  override fun getGlobal(key: String?): Int {
    return getGlobal(nativeLua, key)
  }

  override fun setGlobal(key: String?) {
    setGlobal(nativeLua, key)
  }

  override fun rawGet(tableIndex: Int): Int {
    return rawGet(nativeLua, tableIndex)
  }

  override fun rawSet(tableIndex: Int) {
    rawSet(nativeLua, tableIndex)
  }

  override fun getTable(tableIndex: Int): Int {
    return getTable(nativeLua, tableIndex)
  }

  companion object {
    private const val TAG = "LuaContext"

    init {
      System.loadLibrary("engine_base")
    }


    external fun toPointer(nativeLua: Long, index: Int): Long
    @Throws(LuaTypeError::class)
    external fun toInteger(nativeLua: Long, index: Int): Long
    @Throws(LuaTypeError::class)
    external fun toNumber(nativeLua: Long, index: Int): Double
    @Throws(LuaTypeError::class)
    external fun toBytes(nativeLua: Long, index: Int): ByteArray?
    @Throws(LuaTypeError::class)
    external fun toString(nativeLua: Long, index: Int): String?
    external fun toBoolean(nativeLua: Long, index: Int): Boolean
    external fun push(nativeLua: Long, v: Long)
    external fun push(nativeLua: Long, v: Double)
    external fun push(nativeLua: Long, v: ByteArray?)
    external fun push(nativeLua: Long, v: Boolean)
    external fun pushNil(nativeLua: Long)


    external fun pushValue(nativeLua: Long, index: Int)
    @JvmStatic
    external fun pushLuaObjectAdapter(
      nativeLua: Long,
      adapterId: Long)

    external fun toLuaObjectAdapter(nativeLua: Long, index: Int): Long


    external fun loadBuffer(nativeLua: Long, code: ByteArray?, chunkName: String?, codeType: Int)
    external fun loadFile(nativeLua: Long, fileName: String?, codeType: Int)
    external fun pcall(nativeLua: Long, nArgs: Int, nResults: Int, errFunc: Int): Int

    external fun type(nativeLua: Long, index: Int): Int
    external fun isInteger(nativeLua: Long, index: Int): Boolean

    external fun getTop(nativeLua: Long): Int
    external fun setTop(nativeLua: Long, index: Int)
    external fun setTable(nativeLua: Long, tableIndex: Int)
    external fun getTable(nativeLua: Long, tableIndex: Int): Int
    external fun rawSet(nativeLua: Long, tableIndex: Int)
    external fun rawGet(nativeLua: Long, tableIndex: Int): Int
    @JvmStatic
    external fun setGlobal(nativeLua: Long, key: String?)
    external fun getGlobal(nativeLua: Long, key: String?): Int
    external fun pop(nativeLua: Long, n: Int)
    external fun createTable(nativeLua: Long, arraySize: Int, dictionarySize: Int)

    external fun destroy(nativeLua: Long)

    external fun next(nativeLua: Long, index: Int): Boolean
    external fun len(nativeLua: Long, index: Int): Long

  }
}
