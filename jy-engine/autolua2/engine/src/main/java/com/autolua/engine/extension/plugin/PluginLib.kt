package com.autolua.engine.extension.plugin

import com.autolua.engine.base.LuaContext
import com.autolua.engine.base.LuaEnvContributor
import com.autolua.engine.base.LuaObjectAdapter
import com.autolua.engine.plugin.PluginManager

/**
 * 插件管理 Lua 口（注入为 Lua 全局 `plugin` 表，对齐派大星 plugin 生命周期）：
 *
 * - plugin.list() → {{id=, name=, version=, provides={...}, loaded=bool}, ...}
 * - plugin.getLoaded() → { [id] = true, ... }
 * - plugin.load(id) / plugin.unload(id) → bool（unload 保留目录，回到"已安装未加载"态）
 * - plugin.loadAll() → 重载全部已安装插件
 * - plugin.install(zipPath) → bool, err（本地 zip 直装，sha256 校验同 WS 通道）
 * - plugin.uninstall(id) → bool
 * - plugin.getPluginPath(id) / plugin.getSoPath(id) → 路径（未知 id 返回 nil）
 *
 * 必须注册在 Lua 所在进程本地：root 引擎进程由 Server.kt 预先注入
 * PluginManager 目录 override 并 loadAll，本服务零依赖。
 */
open class PluginLib : LuaEnvContributor {

  private fun mgr(): PluginManager = PluginManager.getInstance()

  override fun contribute(ctx: LuaContext) {
    ctx.createTable(0, 11)
    put(ctx, "list") { L ->
      val installed = mgr().getInstalled()
      ctx.createTable(installed.size, 0)
      for ((i, p) in installed.withIndex()) {
        ctx.createTable(0, 5)
        putStr(ctx, "id", p.meta.id)
        putStr(ctx, "name", p.meta.name)
        putStr(ctx, "version", p.meta.version)
        ctx.push("provides")
        val tags = p.meta.provides ?: emptyArray<String>()
        ctx.createTable(tags.size, 0)
        for ((ti, t) in tags.withIndex()) {
          ctx.push((ti + 1).toLong())
          ctx.push(t)
          ctx.setTable(-3)
        }
        ctx.setTable(-3)
        ctx.push("loaded")
        ctx.push(p.instance != null)
        ctx.setTable(-3)
        ctx.push((i + 1).toLong())
        ctx.setTable(-3)
      }
      1
    }
    put(ctx, "getLoaded") { L ->
      val installed = mgr().getInstalled()
      ctx.createTable(0, installed.size)
      for (p in installed) {
        if (p.instance != null) {
          ctx.push(p.meta.id)
          ctx.push(true)
          ctx.setTable(-3)
        }
      }
      1
    }
    put(ctx, "load") { L -> ctx.push(mgr().loadOne(L.toString(2) ?: "")); 1 }
    put(ctx, "unload") { L -> ctx.push(mgr().unload(L.toString(2) ?: "")); 1 }
    put(ctx, "loadAll") { L -> mgr().loadAll(); ctx.push(true); 1 }
    put(ctx, "install") { L ->
      val path = L.toString(2) ?: ""
      try {
        mgr().install(java.io.File(path))
        ctx.push(true)
        1
      } catch (t: Throwable) {
        ctx.push(false)
        ctx.push(t.message ?: t.toString())
        2
      }
    }
    put(ctx, "uninstall") { L -> ctx.push(mgr().uninstall(L.toString(2) ?: "")); 1 }
    put(ctx, "getPluginPath") { L ->
      val p = mgr().getById(L.toString(2) ?: "")
      ctx.push(p?.versionDir?.absolutePath)
      1
    }
    put(ctx, "getSoPath") { L ->
      val root = mgr().getSoRoot(L.toString(2) ?: "")
      ctx.push(root?.absolutePath)
      1
    }
    ctx.setGlobal("plugin")
  }

  private fun putStr(ctx: LuaContext, key: String, value: String?) {
    ctx.push(key)
    ctx.push(value)
    ctx.setTable(-3)
  }

  private fun put(ctx: LuaContext, name: String, body: (LuaContext) -> Int) {
    ctx.push(name)
    ctx.push(adapter(body))
    ctx.setTable(-3)
  }

  private fun adapter(body: (LuaContext) -> Int): LuaObjectAdapter = object : LuaObjectAdapter {
    override fun invoke(L: LuaContext): Int = body(L)
    override fun index(L: LuaContext): Int = 0
    override fun newIndex(L: LuaContext): Int = 0
    override fun call(L: LuaContext, name: String): Int = 0
    override fun release(L: LuaContext) {}
  }
}
