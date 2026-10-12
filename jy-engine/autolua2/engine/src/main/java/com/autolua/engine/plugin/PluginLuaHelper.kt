package com.autolua.engine.plugin

import com.autolua.engine.base.JavaObjectWrapper
import com.autolua.engine.base.LuaContext
import com.autolua.engine.base.LuaObjectAdapter
import com.autolua.engine.base.MethodCache

/**
 * 插件贡献 Lua API 的便捷工具（配合 {@link LuaEnvContributor} 使用）。
 *
 * 插件实现 `LuaEnvContributor.contribute(ctx)` 后，引擎在建 LuaContext 时
 * 会经 {@link PluginManager#contributeLua} 调用；本工具把插件内的普通 Java/Kotlin
 * 对象包装为 Lua 全局表（方法经 JavaObjectWrapper 反射桥接，与 http/file 等内置
 * 服务同一条调用链）。
 *
 * 插件侧示例（Java，object 经 INSTANCE 访问）：
 * <pre>
 *   public void contribute(LuaContext lua) {
 *       PluginLuaHelper.INSTANCE.putGlobal(lua, "plugindemo", new Api(ctx), Api.class);
 *   }
 * </pre>
 * Lua 侧：`plugindemo.hello("x")`（冒号/点号均可，无 self 语义参数）。
 *
 * 注意：
 * - 贡献时机 = LuaContext 创建时（已加载的插件）；插件安装晚于 context 创建的，
 *   需重建 context（重跑脚本/重启引擎）后生效；
 * - root 主引擎与 IDE 沙箱各有一份插件实例，各自向所在进程的 Lua 贡献；
 * - 注册同名全局会覆盖内置服务（勿用 http/file/ocr 等保留名）。
 */
object PluginLuaHelper {

  private val methodCache = MethodCache()

  /**
   * 把对象注册为 Lua 全局。
   *
   * @param ctx   目标 Lua 环境（contribute 回调入参）
   * @param name  Lua 全局名（建议带插件语义前缀，如 "ocrx"、"yolo"）
   * @param api   暴露给 Lua 的对象实例
   * @param iface 暴露给 Lua 的方法接口；传 null 时按对象具体类反射（非公开方法不可见）
   */
  fun putGlobal(ctx: LuaContext, name: String, api: Any, iface: Class<*>? = null) {
    val wrapper: LuaObjectAdapter = JavaObjectWrapper(iface ?: api.javaClass, api, methodCache)
    ctx.push(wrapper)
    ctx.setGlobal(name)
  }
}
