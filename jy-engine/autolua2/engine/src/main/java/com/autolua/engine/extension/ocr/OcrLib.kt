package com.autolua.engine.extension.ocr

import android.graphics.BitmapFactory
import com.autolua.engine.base.LuaContext
import com.autolua.engine.base.LuaEnvContributor
import com.autolua.engine.base.LuaObjectAdapter
import com.autolua.engine.plugin.IOcrPlugin
import com.autolua.engine.plugin.OcrTextBlock
import com.autolua.engine.plugin.PluginManager

/**
 * OCR Lua 调用口（注入为 Lua 全局 `ocr` 表；OCR 插件运行时）。
 *
 * 契约：安装了 provides=["ocr"] 且实现 {@link IOcrPlugin} 的插件后可用。
 * 取帧由 Lua 侧完成（getScreenFrame() 走引擎 Display 同一条帧链路），本服务只负责：
 * 行 stride 重排（IOcrPlugin 契约要求紧凑 frameW*4/行）→ 插件 recognize → 文本块转表。
 *
 * - ocr.isSupported() / ocr.init() → bool
 * - ocr.list() → 已安装 OCR 插件 id 数组
 * - ocr.use(id?) → bool（切换激活插件；缺省/nil 恢复默认）
 * - ocr.active() → 当前生效插件 id（无插件为 nil）
 * - ocr.recognizeRaw(frameStr, frameW, frameH, stride, x, y, w, h) → 块数组
 *   （块结构 {text=, x=, y=, w=, h=, confidence=}；坐标为帧内坐标）
 * - ocr.recognizeFile(pngPath) → 块数组（插件开发调试用）
 * - ocr.ocrText(x1,y1,x2,y2) / ocr.ocrEx(...)：由 compat 组装（Lua 侧取帧 + 拼接文本）
 *
 * 多 OCR 共存：门面 + 切换（路线A）。识别类接口统一走"当前生效"插件
 * （显式 ocr.use 优先，未切换取第一个注册的）；切换按进程内存态生效，
 * 各插件 native 推理会话独立、互不干扰，无需重启。
 *
 * 必须注册在 Lua 所在进程本地；root 进程由 Server.kt 注入插件目录 override 后
 * loadAll 加载（帧数据 ~3.7MB 超 Binder 1MB 限制，不走跨进程）。
 */
open class OcrLib : LuaEnvContributor {

  private fun plugin(): IOcrPlugin? = PluginManager.getInstance().getOcrPlugin()

  fun isSupported(): Boolean = plugin() != null

  /** 已安装 OCR 插件 id 列表（安装序） */
  fun listOcr(): List<String> = PluginManager.getInstance().getOcrPluginIds()

  /** 切换激活 OCR 插件；id 缺省/null 恢复默认（第一个注册的） */
  fun useOcr(id: String?): Boolean = PluginManager.getInstance().setActiveOcr(id)

  /** 当前生效的 OCR 插件 id；无 OCR 插件为 null */
  fun activeOcr(): String? = PluginManager.getInstance().getEffectiveOcrId()

  /**
   * 帧数据识别。
   *
   * @param frame   RGBA 像素字节串（长度 >= stride*fh）
   * @param fw/fh   帧宽高（像素）
   * @param stride  帧行字节数（>= fw*4，含行尾 padding）
   * @param x/y/w/h 识别区域（帧内坐标，越界自动钳制）
   */
  fun recognizeRaw(
    frame: ByteArray, fw: Int, fh: Int, stride: Int,
    x: Int, y: Int, w: Int, h: Int
  ): List<OcrTextBlock> {
    val plugin = plugin() ?: throw IllegalStateException("no ocr plugin installed")
    if (fw <= 0 || fh <= 0 || stride < fw * 4) {
      throw IllegalArgumentException("bad frame size $fw x $fh stride $stride")
    }
    if (frame.size < stride.toLong() * fh) {
      throw IllegalArgumentException("frame truncated: ${frame.size} < ${stride.toLong() * fh}")
    }
    // 区域钳制到帧内
    val rx = x.coerceIn(0, fw - 1)
    val ry = y.coerceIn(0, fh - 1)
    val rw = w.coerceIn(1, fw - rx)
    val rh = h.coerceIn(1, fh - ry)
    // stride 带行尾 padding 时重排为紧凑 RGBA（契约要求 frameW*4/行）
    val compact: ByteArray =
      if (stride == fw * 4) frame
      else {
        val out = ByteArray(fw * 4 * fh)
        for (row in 0 until fh) {
          System.arraycopy(frame, row * stride, out, row * fw * 4, fw * 4)
        }
        out
      }
    val blocks = plugin.recognize(compact, fw, fh, rx, ry, rw, rh)
    return blocks ?: emptyList()
  }

  /**
   * 识别 PNG 图片文件（插件开发调试用）：解码 → recognize。
   * framework 类无 Context 依赖，root 进程可用。
   */
  fun recognizeFile(pngPath: String): List<OcrTextBlock> {
    val plugin = plugin() ?: throw IllegalStateException("no ocr plugin installed")
    val bmp = BitmapFactory.decodeFile(pngPath)
      ?: throw IllegalArgumentException("decode failed: $pngPath")
    val w = bmp.width
    val h = bmp.height
    val frame = ByteArray(w * 4 * h)
    bmp.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(frame))
    val blocks = plugin.recognize(frame, w, h, 0, 0, w, h)
    bmp.recycle()
    return blocks ?: emptyList()
  }

  override fun contribute(ctx: LuaContext) {
    ctx.createTable(0, 8)
    put(ctx, "isSupported") { L -> ctx.push(isSupported()); 1 }
    put(ctx, "init") { L -> ctx.push(isSupported()); 1 }
    put(ctx, "list") { L ->
      val ids = listOcr()
      ctx.createTable(ids.size, 0)
      for ((i, id) in ids.withIndex()) {
        ctx.push((i + 1).toLong())
        ctx.push(id)
        ctx.setTable(-3)
      }
      1
    }
    put(ctx, "use") { L ->
      // 缺省/nil → null → 恢复默认（第一个注册的）；非字符串参数直接报错
      val t = L.type(2)
      val id = when (t) {
        null, LuaContext.ValueType.NONE, LuaContext.ValueType.NIL -> null
        LuaContext.ValueType.STRING -> L.toString(2)
        else -> throw IllegalArgumentException("ocr.use expects string plugin id or nil")
      }
      ctx.push(useOcr(id))
      1
    }
    put(ctx, "active") { L ->
      val id = activeOcr()
      if (id == null) ctx.pushNil() else ctx.push(id)
      1
    }
    put(ctx, "recognizeRaw") { L ->
      val frame = L.toBytes(2) ?: throw IllegalArgumentException("frame byte string expected")
      val fw = L.toLong(3).toInt()
      val fh = L.toLong(4).toInt()
      val stride = L.toLong(5).toInt()
      val x = L.toLong(6).toInt()
      val y = L.toLong(7).toInt()
      val w = L.toLong(8).toInt()
      val h = L.toLong(9).toInt()
      pushBlocks(ctx, recognizeRaw(frame, fw, fh, stride, x, y, w, h))
      1
    }
    put(ctx, "recognizeFile") { L ->
      pushBlocks(ctx, recognizeFile(L.toString(2) ?: ""))
      1
    }
    ctx.setGlobal("ocr")
  }

  /** OcrTextBlock 列表 → Lua 表 {{text=,x=,y=,w=,h=,confidence=},...} */
  private fun pushBlocks(ctx: LuaContext, blocks: List<OcrTextBlock>) {
    ctx.createTable(blocks.size, 0)
    for ((i, b) in blocks.withIndex()) {
      if (b == null) continue
      ctx.push((i + 1).toLong())
      ctx.createTable(0, 6)
      ctx.push("text")
      ctx.push(b.text ?: "")
      ctx.setTable(-3)
      ctx.push("x")
      ctx.push(b.x.toLong())
      ctx.setTable(-3)
      ctx.push("y")
      ctx.push(b.y.toLong())
      ctx.setTable(-3)
      ctx.push("w")
      ctx.push(b.w.toLong())
      ctx.setTable(-3)
      ctx.push("h")
      ctx.push(b.h.toLong())
      ctx.setTable(-3)
      ctx.push("confidence")
      ctx.push(b.confidence.toDouble())
      ctx.setTable(-3)
      ctx.setTable(-3)
    }
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
