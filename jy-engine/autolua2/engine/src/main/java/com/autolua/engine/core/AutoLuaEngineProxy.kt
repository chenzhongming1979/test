package com.autolua.engine.core

import android.util.Log
import com.autolua.engine.base.LuaContext
import com.autolua.engine.common.Observable
import com.autolua.engine.common.ObservableImpOnMainThread
import kotlinx.coroutines.*
import java.util.concurrent.Executors

class AutoLuaEngineProxy:AutoLuaEngine, Observable<AutoLuaEngine.State> by ObservableImpOnMainThread() {
  @Volatile
  private var engine:AutoLuaEngine? = null
  @Volatile
  private var engineCreator:(()->AutoLuaEngine)? = null

  private val executor = Executors.newSingleThreadExecutor()
  private val customDispatcher = executor.asCoroutineDispatcher()
  private val customScope = CoroutineScope(customDispatcher)

  companion object {
    val instance by lazy { AutoLuaEngineProxy() }
  }

  @Volatile
  private var rootDir: String? = null

  override fun setRootDir(rootDir: String) {
    this.rootDir = rootDir
    customScope.launch {
      ensureEngine().setRootDir(rootDir)
    }
  }

  fun startTask(){
    customScope.launch {
      // __SCRIPT_DIR__ 注入（与 IDE run_script 同契约）：云控壳工程 jyframe.lua 以此定位工程根。
      // init.lua 可选；引擎内置 loadfile 对缺失文件直接抛错（非标准 nil 返回），必须 pcall 包裹。
      val dir = rootDir ?: ""
      val script = """
        rawset(_G, '__SCRIPT_DIR__', "$dir")
        local ok, f = pcall(loadfile, "init.lua")
        if ok and type(f) == "function" then f() end
        local okm, m = pcall(loadfile, "main.lua")
        if okm and type(m) == "function" then
          m()
        else
          error(okm and "main.lua not found in engine rootDir" or tostring(m), 0)
        end
      """.trimIndent()
      ensureEngine().execute(script)
    }
  }


  fun setEngineCreator(creator:()->AutoLuaEngine){
    engineCreator = creator
  }

  private fun ensureEngine(): AutoLuaEngine {
    if(engine == null){
      engine = engineCreator?.invoke()
      engine?.addObserver(listener)
    }
    if(engine == null){
      throw IllegalStateException("engine is null")
    }
    return engine!!
  }



  override fun start(callback: Callback?) {
    customScope.launch {
      val engine = ensureEngine()
      engine.start(callback)
    }
  }

  override fun stop() {
    customScope.launch {
      engine?.stop()
    }
  }

  override fun getState(target: AutoLuaEngine.Target): AutoLuaEngine.State {
    return engine?.getState(target) ?: AutoLuaEngine.State.IDLE
  }

  override fun destroy() {
    customScope.launch {
      engine?.destroy()
      engine = null
    }
  }


  override fun execute(script: String, flags: Int) {
    customScope.launch {
      ensureEngine().execute(script, flags)
    }
  }

  override fun execute(script: ByteArray, codeMode: LuaContext.CodeMode, flags: Int) {
    customScope.launch {
      ensureEngine().execute(script, codeMode, flags)
    }
  }

  override fun interrupt() {
    customScope.launch {
      ensureEngine().interrupt()
    }
  }

  // 三期：暂停/恢复脚本，透传到引擎。engine 未创建时忽略——
  // 不走 ensureEngine()（避免为一次 pause 拉起引擎/root 进程）
  override fun pause() {
    engine?.let { e ->
      customScope.launch { e.pause() }
    }
  }

  override fun resume() {
    engine?.let { e ->
      customScope.launch { e.resume() }
    }
  }

  override fun startDebugger(debuggerConfigure: AutoLuaEngine.DebuggerConfigure) {
    customScope.launch {
      val engine = ensureEngine()
      if(engine.getState() == AutoLuaEngine.State.IDLE){
        engine.start()
      }
      engine.startDebugger(debuggerConfigure)
    }
  }

  override fun stopDebugger() {
    customScope.launch {
      ensureEngine().stopDebugger()
    }
  }

}