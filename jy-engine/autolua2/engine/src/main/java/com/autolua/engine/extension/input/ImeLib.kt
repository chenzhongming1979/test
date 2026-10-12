package com.autolua.engine.extension.input

import com.autolua.engine.base.LuaContext
import com.autolua.engine.base.LuaEnvContributor
import com.autolua.engine.base.LuaObjectAdapter
import com.autolua.engine.inputmethod.AutoLuaInputMethodService

/**
 * 输入法域（注入为 Lua 全局 `ime` 表，对齐派大星 ime 14 项）。
 *
 * 纯 shell/broadcast 实现 —— root 引擎进程（app_process）内没有任何 Application/systemContext
 * 可用（currentApplication/currentActivityThread 均为 null），故本服务不依赖 Context：
 *
 * - 注入动作：`am broadcast -a <action> -p <pkg> [--es/--ei extras]` 经 shell 通道发往
 *   宿主 app 进程的 [AutoLuaInputMethodService] 动态 receiver（已声明 RECEIVER_EXPORTED）；
 * - 默认输入法读写：`settings get/put secure default_input_method`；
 * - 键盘显隐状态：`dumpsys input_method` 解析 mInputShown；
 * - 拉起输入法设置页：`am start -a android.settings.INPUT_METHOD_SETTINGS`。
 *
 * 注入依赖本 IME 为系统默认输入法：首次调用会自动切换（root uid 0 直接写 settings）。
 * 所有方法可能阻塞（exec），必须在非主线程调用（Lua worker 满足）。
 */
open class ImeLib : LuaEnvContributor {

  companion object {
    private const val PKG = "com.autolua.autolua2"
    private val IME_ID = "$PKG/${AutoLuaInputMethodService::class.java.name}"

    /** 执行命令返回 (exitCode, 合并输出)；异常返回 (-1, 错误串) */
    private fun exec(vararg cmd: String): Pair<Int, String> {
      return try {
        val p = Runtime.getRuntime().exec(cmd)
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
        val rc = p.waitFor()
        rc to (out + err)
      } catch (t: Throwable) {
        -1 to t.toString()
      }
    }

    private fun settingsGet(): String =
      exec("settings", "get", "secure", "default_input_method").second.trim()

    private fun isDefaultIme(): Boolean = settingsGet().contains(PKG)

    /** 切换到本 IME：写 settings 后回读验证 */
    private fun switchToThisIme(): Boolean {
      if (isDefaultIme()) return true
      val (rc, _) = exec("settings", "put", "secure", "default_input_method", IME_ID)
      return rc == 0 && isDefaultIme()
    }

    /** 写回指定输入法 id 并回读验证（空/未知 id 返回 false） */
    private fun setCurrentIme(imeId: String?): Boolean {
      if (imeId.isNullOrBlank()) return false
      val (rc, _) = exec("settings", "put", "secure", "default_input_method", imeId)
      return rc == 0 && settingsGet() == imeId
    }

    /** 确保 IME 通道就绪：已切换直接通过；刚切换后短暂等待系统拉起服务 */
    private fun ensureChannel(): Boolean {
      if (isDefaultIme()) return true
      if (!switchToThisIme()) return false
      if (AutoLuaInputMethodService.getInstance() == null) {
        try { Thread.sleep(600) } catch (t: Throwable) {}
      }
      return true
    }

    /** 经 `am broadcast` 发动作（uid 0 shell 通道，receiver 已 EXPORTED） */
    private fun broadcast(action: String, extras: List<Array<String>> = emptyList()): Boolean {
      val cmd = mutableListOf("am", "broadcast", "-a", action, "-p", PKG)
      for (e in extras) cmd.addAll(e)
      val (rc, _) = exec(*cmd.toTypedArray())
      return rc == 0
    }

    private fun textExtra(v: String) = listOf(arrayOf("--es", "text", v))
    private fun intExtra(k: String, v: Int) = listOf(arrayOf("--ei", k, v.toString()))
  }

  override fun contribute(ctx: LuaContext) {
    ctx.createTable(0, 16)
    put(ctx, "setText") { L ->
      if (!ensureChannel()) return@put fail(ctx, "ime channel unavailable")
      ctx.push(broadcast(AutoLuaInputMethodService.ACTION_INPUT_TEXT,
        textExtra(L.toString(2) ?: "")))
      1
    }
    put(ctx, "commitText") { L ->
      if (!ensureChannel()) return@put fail(ctx, "ime channel unavailable")
      ctx.push(broadcast(AutoLuaInputMethodService.ACTION_INPUT_TEXT,
        textExtra(L.toString(2) ?: "")))
      1
    }
    put(ctx, "inputLText") { L ->
      if (!ensureChannel()) return@put fail(ctx, "ime channel unavailable")
      ctx.push(broadcast(AutoLuaInputMethodService.ACTION_INPUT_TEXT,
        textExtra(L.toString(2) ?: "")))
      1
    }
    put(ctx, "clearField") { L ->
      if (!ensureChannel()) return@put fail(ctx, "ime channel unavailable")
      val n = if (L.type(2) == LuaContext.ValueType.NUMBER) L.toLong(2).toInt() else 0
      ctx.push(broadcast(AutoLuaInputMethodService.ACTION_CLEAR_TEXT,
        if (n > 0) intExtra("count", n) else emptyList()))
      1
    }
    put(ctx, "deleteChar") { L ->
      if (!ensureChannel()) return@put fail(ctx, "ime channel unavailable")
      val n = if (L.type(2) == LuaContext.ValueType.NUMBER) L.toLong(2).toInt() else 1
      ctx.push(broadcast(AutoLuaInputMethodService.ACTION_CLEAR_TEXT,
        if (n > 0) intExtra("count", n) else emptyList()))
      1
    }
    put(ctx, "showKeyboard") { L ->
      if (!ensureChannel()) return@put fail(ctx, "ime channel unavailable")
      ctx.push(broadcast(AutoLuaInputMethodService.ACTION_SHOW_IME))
      1
    }
    put(ctx, "hideKeyboard") { L ->
      ctx.push(broadcast(AutoLuaInputMethodService.ACTION_HIDE_IME))
      1
    }
    put(ctx, "finishInput") { L ->
      ctx.push(broadcast(AutoLuaInputMethodService.ACTION_HIDE_IME))
      1
    }
    put(ctx, "keyEvent") { L ->
      if (!ensureChannel()) return@put fail(ctx, "ime channel unavailable")
      val code = if (L.type(2) == LuaContext.ValueType.NUMBER) L.toLong(2).toInt() else -1
      ctx.push(broadcast(AutoLuaInputMethodService.ACTION_INPUT_KEYCODE,
        intExtra("code", code)))
      1
    }
    put(ctx, "isActive") { L ->
      // root 进程查不到宿主服务单例，降级为"本 IME 已是默认输入法且键盘显示"
      var active = false
      if (isDefaultIme()) {
        val (_, out) = exec("dumpsys", "input_method")
        active = out.contains("mInputShown=true")
      }
      ctx.push(active)
      1
    }
    put(ctx, "isOurIMEActive") { L ->
      ctx.push(isDefaultIme())
      1
    }
    put(ctx, "setAsDefaultIME") { L ->
      ctx.push(switchToThisIme())
      1
    }
    put(ctx, "setCurrentIME") { L ->
      ctx.push(setCurrentIme(L.toString(2)))
      1
    }
    put(ctx, "showInputMethodPicker") { L ->
      // 宿主进程的 IME 服务在线时由其调 IMM.showInputMethodPicker
      ctx.push(broadcast(AutoLuaInputMethodService.ACTION_SHOW_PICKER))
      1
    }
    put(ctx, "getCurrentIMEName") { L ->
      ctx.push(settingsGet().ifEmpty { null })
      1
    }
    put(ctx, "openIMESettings") { L ->
      val (rc, _) = exec("am", "start", "-a", "android.settings.INPUT_METHOD_SETTINGS")
      ctx.push(rc == 0)
      1
    }
    ctx.setGlobal("ime")
  }

  /** 通道不可用统一出口：push false + 错误串 */
  private fun fail(ctx: LuaContext, err: String? = null): Int {
    ctx.push(false)
    if (err != null) ctx.push(err)
    return if (err != null) 2 else 1
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
