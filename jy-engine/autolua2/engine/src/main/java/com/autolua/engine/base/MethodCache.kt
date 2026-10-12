package com.autolua.engine.base

import java.lang.reflect.Method

class MethodCache {
  private val methodMap: MutableMap<String, Method?> = HashMap()
  private val methodListCache: MutableMap<String, List<Method>> = HashMap()

  fun findMethod(clazz: Class<*>, name: String): Method? {
    val key = clazz.name + ":" + name
    var m = methodMap[key]
    if (null == m) {
      for (method in clazz.methods) {
        if (method.name == name) {
          m = method
          methodMap[key] = m
          break
        }
      }
    }
    return m
  }

  /** 同名方法全集（重载解析用；首次查询后缓存） */
  fun findMethods(clazz: Class<*>, name: String): List<Method> {
    val key = clazz.name + ":" + name
    var list = methodListCache[key]
    if (list == null) {
      list = clazz.methods.filter { it.name == name }
      methodListCache[key] = list
    }
    return list
  }
}
