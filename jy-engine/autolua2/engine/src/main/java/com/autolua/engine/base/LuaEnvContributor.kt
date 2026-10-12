package com.autolua.engine.base

/**
 * 本地服务环境贡献者：注入为 Lua 全局对象后回调 [contribute]，
 * 允许服务自行向 Lua 环境注册全局函数/常量（如 luajava 的全局 import）。
 *
 * 生效点：
 * - 主引擎：AutoLuaEngineOnLocal.pushLocalServices（wrapLocalService 之后）
 * - IDE 沙箱：LuaEngineExecutor.injectService
 */
interface LuaEnvContributor {
  /** 向 Lua 环境注册全局函数/常量（调用时持有对应 lua_State 的访问权） */
  fun contribute(ctx: LuaContext)
}
