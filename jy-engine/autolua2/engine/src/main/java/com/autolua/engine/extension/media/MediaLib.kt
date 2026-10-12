package com.autolua.engine.extension.media

import com.autolua.engine.base.LuaContext
import com.autolua.engine.base.LuaEnvContributor
import com.autolua.engine.base.LuaObjectAdapter

/**
 * 音频播放域（注入为 Lua 全局 `media` 表，对齐派大星 media），
 * 并注册懒人风格全局函数 playAudio / stopAudio。
 *
 * 单实例 MediaPlayer（本地文件路径）；播放结束不自回收，脚本可 isPlaying 轮询或 stopAudio。
 * 所有方法必须在非主线程调用（Lua worker 满足）。
 */
open class MediaLib : LuaEnvContributor {

  private var player: android.media.MediaPlayer? = null

  /** 播放本地音频文件（loop=true 循环）；开始新播放前自动释放旧实例 */
  fun playAudio(path: String, loop: Boolean = false): Boolean = try {
    stopAudio()
    val p = android.media.MediaPlayer()
    p.setDataSource(path)
    p.isLooping = loop
    p.prepare()
    p.start()
    player = p
    true
  } catch (t: Throwable) {
    false
  }

  fun stopAudio() {
    try { player?.stop() } catch (t: Throwable) {}
    try { player?.release() } catch (t: Throwable) {}
    player = null
  }

  fun pauseAudio() { try { player?.pause() } catch (t: Throwable) {} }

  fun resumeAudio() { try { player?.start() } catch (t: Throwable) {} }

  fun seekTo(ms: Long) { try { player?.seekTo(ms.toInt()) } catch (t: Throwable) {} }

  fun isPlaying(): Boolean = try { player?.isPlaying == true } catch (t: Throwable) { false }

  fun getDuration(): Long = try { player?.duration?.toLong() ?: 0L } catch (t: Throwable) { 0L }

  fun getCurrentPosition(): Long = try { player?.currentPosition?.toLong() ?: 0L } catch (t: Throwable) { 0L }

  fun setVolume(v: Double) {
    try {
      val f = v.toFloat().coerceIn(0f, 1f)
      player?.setVolume(f, f)
    } catch (t: Throwable) {}
  }

  fun release() = stopAudio()

  override fun contribute(ctx: LuaContext) {
    ctx.createTable(0, 10)
    put(ctx, "playAudio") { L -> ctx.push(playAudio(L.toString(2) ?: "", L.toBoolean(3))); 1 }
    put(ctx, "stopAudio") { L -> stopAudio(); ctx.push(true); 1 }
    put(ctx, "pauseAudio") { L -> pauseAudio(); ctx.push(true); 1 }
    put(ctx, "resumeAudio") { L -> resumeAudio(); ctx.push(true); 1 }
    put(ctx, "seekTo") { L -> seekTo(L.toLong(2)); ctx.push(true); 1 }
    put(ctx, "isPlaying") { L -> ctx.push(isPlaying()); 1 }
    put(ctx, "getDuration") { L -> ctx.push(getDuration()); 1 }
    put(ctx, "getCurrentPosition") { L -> ctx.push(getCurrentPosition()); 1 }
    put(ctx, "getPosition") { L -> ctx.push(getCurrentPosition()); 1 }  // 派大星别名
    put(ctx, "setVolume") { L ->
      val v = if (L.type(2) == LuaContext.ValueType.NUMBER) L.toDouble(2) else 1.0
      setVolume(v); ctx.push(true); 1
    }
    put(ctx, "release") { L -> stopAudio(); ctx.push(true); 1 }
    ctx.setGlobal("media")
    // 懒人全局函数
    registerGlobal(ctx, "playAudio") { L -> ctx.push(playAudio(L.toString(2) ?: "")); 1 }
    registerGlobal(ctx, "stopAudio") { L -> stopAudio(); ctx.push(true); 1 }
  }

  private fun put(ctx: LuaContext, name: String, body: (LuaContext) -> Int) {
    ctx.push(name)
    ctx.push(adapter(body))
    ctx.setTable(-3)
  }

  private fun registerGlobal(ctx: LuaContext, name: String, body: (LuaContext) -> Int) {
    ctx.push(adapter(body))
    ctx.setGlobal(name)
  }

  private fun adapter(body: (LuaContext) -> Int): LuaObjectAdapter = object : LuaObjectAdapter {
    override fun invoke(L: LuaContext): Int = body(L)
    override fun index(L: LuaContext): Int = 0
    override fun newIndex(L: LuaContext): Int = 0
    override fun call(L: LuaContext, name: String): Int = 0
    override fun release(L: LuaContext) {}
  }
}
