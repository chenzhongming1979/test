package com.autolua.autolua2.extension.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * 动态 UI 服务实现（单例，app 主进程）。
 *
 * - op(json)：JSON op 协议同步执行（主线程落点 + 8s 超时），响应 {"req","ok","data|err"}
 * - pollEvents()：事件队列排空 → JSON 数组（ui 控件事件 + dialog 结果）
 * - 窗口注册表 wins：FloatWin（2038 悬浮窗 + DragFrameLayout 拖动）
 * - 弹窗 dialogs：DialogHost.Session（win="__dlg_<req>"，可 node.set 寻址）
 */
class UiMgrImp private constructor() : IUiMgr {

  companion object {
    val instance: UiMgrImp by lazy { UiMgrImp() }
    private const val MAX_EVENTS = 256
  }

  private lateinit var context: Context
  /** AppCompat 主题包装 context：Material/SwitchCompat/TabLayout 等控件要求 AppCompat 系主题 */
  private lateinit var uiContext: Context
  private lateinit var wm: WindowManager
  private val mainHandler = Handler(Looper.getMainLooper())
  private val wins = ConcurrentHashMap<String, FloatWin>()
  private val dialogs = ConcurrentHashMap<String, DialogHost.Session>()
  private val events = ConcurrentLinkedQueue<String>()
  private var winSeq = 0

  @SuppressLint("StaticFieldLeak")
  fun init(context: Context) {
    this.context = context.applicationContext
    this.uiContext = android.view.ContextThemeWrapper(this.context,
      androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
    this.wm = this.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
  }

  // ---------------- 事件 ----------------

  private fun emitOf(win: String): (String?, String, JSONObject?) -> Unit = { id, ev, data ->
    val o = JSONObject()
    o.put("ev", "ui")
    o.put("win", win)
    if (id != null) o.put("id", id)
    o.put("event", ev)
    if (data != null) o.put("data", data)
    pushEvent(o)
  }

  private fun pushEvent(o: JSONObject) {
    if (events.size >= MAX_EVENTS) events.poll()
    events.add(o.toString())
  }

  override fun pollEvents(): String {
    if (events.isEmpty()) return "[]"
    val arr = JSONArray()
    while (true) {
      val e = events.poll() ?: break
      try { arr.put(JSONObject(e)) } catch (t: Throwable) {}
    }
    return arr.toString()
  }

  // ---------------- op 分发 ----------------

  override fun op(json: String): String {
    val cmd = try { JSONObject(json) } catch (t: Throwable) {
      return resp(null, false, null, "bad json: ${t.message}")
    }
    val req: Any? = if (cmd.has("req")) cmd.opt("req") else null
    val name = cmd.optString("op")
    if (name.isEmpty()) return resp(req, false, null, "op missing")
    var data: JSONObject? = null
    var error: String? = null
    val latch = CountDownLatch(1)
    mainHandler.post {
      try {
        data = dispatch(cmd, name)
      } catch (t: Throwable) {
        error = t.toString()
      } finally {
        latch.countDown()
      }
    }
    try {
      if (!latch.await(8, TimeUnit.SECONDS)) error = "ui op timeout: $name"
    } catch (t: InterruptedException) {
      error = "interrupted"
    }
    return resp(req, error == null, data, error)
  }

  private fun resp(req: Any?, ok: Boolean, data: JSONObject?, err: String?): String {
    val o = JSONObject()
    if (req != null) o.put("req", req)
    o.put("ok", ok)
    if (data != null) o.put("data", data)
    if (err != null) o.put("err", err)
    return o.toString()
  }

  private fun dispatch(cmd: JSONObject, name: String): JSONObject? {
    return when (name) {
      "win.create" -> createWin(cmd)
      "win.close" -> { closeWin(cmd.optString("win")); null }
      "win.show" -> { wins[cmd.optString("win")]?.show(); null }
      "win.hide" -> { wins[cmd.optString("win")]?.hide(); null }
      "win.move" -> { wins[cmd.optString("win")]?.moveTo(cmd.optInt("x", 0), cmd.optInt("y", 0)); null }
      "win.setSize" -> { wins[cmd.optString("win")]?.setSize(cmd.optInt("w", -2), cmd.optInt("h", -2)); null }
      "win.setAlpha" -> { wins[cmd.optString("win")]?.setAlpha(cmd.optDouble("a", 1.0).toFloat()); null }
      "win.list" -> JSONObject().put("wins", JSONArray(wins.keys.toList()))
      "node.add" -> nodeAdd(cmd)
      "node.set" -> nodeSet(cmd)
      "node.setMulti" -> nodeSetMulti(cmd)
      "node.remove" -> nodeRemove(cmd)
      "node.get" -> nodeGet(cmd)
      "node.listen" -> nodeListen(cmd)
      "tree.get" -> treeGet(cmd)
      "dialog.show" -> dialogShow(cmd)
      "dialog.close" -> dialogClose(cmd)
      "toast" -> {
        val dur = if (cmd.optInt("dur", 0) == 1) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        Toast.makeText(context, cmd.optString("msg", ""), dur).show()
        null
      }
      else -> throw IllegalArgumentException("unknown op: $name")
    }
  }

  // ---------------- 窗口 ----------------

  private fun createWin(cmd: JSONObject): JSONObject? {
    val cfg = cmd.optJSONObject("cfg") ?: cmd
    var winId = cmd.optString("win", "")
    if (winId.isEmpty()) {
      winSeq++
      winId = "w$winSeq"
    }
    val exist = wins[winId]
    if (exist != null) closeWin(winId)
    val fw = FloatWin(winId, uiContext, wm, cfg, emitOf(winId))
    val rootSpec = cmd.optJSONObject("root")
    fw.create(rootSpec)
    wins[winId] = fw
    if (cfg.optBoolean("autoShow", true)) fw.show()
    return JSONObject().put("win", winId)
  }

  private fun closeWin(win: String) {
    wins.remove(win)?.close()
  }

  // ---------------- 节点操作 ----------------

  private fun findNode(win: String, id: String): UiNode? {
    wins[win]?.root?.let { return it.findById(id) }
    dialogs[win]?.root?.let { return it.findById(id) }
    return null
  }

  private fun nodeAdd(cmd: JSONObject): JSONObject? {
    val win = cmd.optString("win")
    val parentId = cmd.optString("parent", "")
    val nodeSpec = cmd.optJSONObject("node") ?: throw IllegalArgumentException("node missing")
    val parent: UiNode = if (parentId.isEmpty()) {
      wins[win]?.root ?: dialogs[win]?.root
        ?: throw IllegalArgumentException("window not found: $win")
    } else {
      findNode(win, parentId)
        ?: throw IllegalArgumentException("parent not found: $parentId")
    }
    val index = if (cmd.has("index")) cmd.optInt("index") else -1
    val child = JsonViewFactory.addChild(uiContext, parent, nodeSpec, emitOf(win), index)
      ?: throw IllegalArgumentException("cannot add child to ${parent.type}")
    return JSONObject().put("id", child.id ?: "")
  }

  private fun nodeSet(cmd: JSONObject): JSONObject? {
    val win = cmd.optString("win")
    val id = cmd.optString("id")
    val attrs = cmd.optJSONObject("attrs") ?: cmd.optJSONObject("node") ?: JSONObject()
    val node = findNode(win, id) ?: throw IllegalArgumentException("node not found: $id")
    JsonViewFactory.applyCommon(uiContext, node, attrs)
    JsonViewFactory.applyAttrs(uiContext, node, attrs, emitOf(win))
    return null
  }

  private fun nodeRemove(cmd: JSONObject): JSONObject? {
    val win = cmd.optString("win")
    val id = cmd.optString("id")
    val node = findNode(win, id) ?: return null
    destroyViews(node)
    node.removeFromParent()
    return null
  }

  /** node.listen：登记节点关心的事件集合（目前 touch 依赖此白名单，默认不上报——
   *  touch 一次 tap 产生 DOWN+MOVE×N+UP，拖动/滑动时洪流占满事件队列会挤掉 click） */
  private fun nodeListen(cmd: JSONObject): JSONObject? {
    val node = findNode(cmd.optString("win"), cmd.optString("id"))
      ?: throw IllegalArgumentException("node not found: ${cmd.optString("id")}")
    val arr = cmd.optJSONArray("events")
    val set = linkedSetOf<String>()
    for (i in 0 until (arr?.length() ?: 0)) set.add(arr!!.optString(i))
    node.extra["listen"] = set
    return null
  }

  /** node.setMulti：一次 op 批量回填多控件（profile 加载用）。
   *  每次 UI op 都要在引擎侧持有 LuaState 锁阻塞等主线程（latch）——逐控件 set 时
   *  40 次锁持有把 dispatch 轮询/interrupt 全部饿住（"点击无反应几秒/停止卡几秒"的
   *  根因），合并为单次 op 后锁占用只剩一份。cmd.sets：[{id,attrs},...]，单条失败
   *  不中断整批（找不到的 id 记入 data.missing 返回） */
  private fun nodeSetMulti(cmd: JSONObject): JSONObject? {
    val win = cmd.optString("win")
    val sets = cmd.optJSONArray("sets") ?: throw IllegalArgumentException("sets missing")
    val missing = JSONArray()
    val emit = emitOf(win)
    for (i in 0 until sets.length()) {
      val one = sets.optJSONObject(i) ?: continue
      val node = findNode(win, one.optString("id"))
      if (node == null) {
        missing.put(one.optString("id"))
        continue
      }
      val attrs = one.optJSONObject("attrs") ?: continue
      JsonViewFactory.applyCommon(uiContext, node, attrs)
      JsonViewFactory.applyAttrs(uiContext, node, attrs, emit)
    }
    return if (missing.length() > 0) JSONObject().put("missing", missing) else null
  }

  private fun nodeGet(cmd: JSONObject): JSONObject {
    val node = findNode(cmd.optString("win"), cmd.optString("id"))
      ?: throw IllegalArgumentException("node not found: ${cmd.optString("id")}")
    return JSONObject().put("values", JsonViewFactory.collectValues(uiContext, node))
  }

  private fun treeGet(cmd: JSONObject): JSONObject {
    val win = cmd.optString("win")
    val root = wins[win]?.root ?: dialogs[win]?.root
      ?: throw IllegalArgumentException("window not found: $win")
    return JSONObject().put("tree", treeJson(uiContext, root))
  }

  private fun treeJson(ctx: Context, n: UiNode): JSONObject {
    val o = JsonViewFactory.collectValues(ctx, n)
    if (n.children.isNotEmpty()) {
      val arr = JSONArray()
      for (c in n.children) arr.put(treeJson(ctx, c))
      o.put("children", arr)
    }
    return o
  }

  private fun destroyViews(n: UiNode) {
    when (n.view) {
      is WebView -> {
        val wv = n.view as WebView
        try { wv.stopLoading(); wv.loadUrl("about:blank"); wv.destroy() } catch (t: Throwable) {}
      }
      is android.widget.VideoView -> {
        try { (n.view as android.widget.VideoView).stopPlayback() } catch (t: Throwable) {}
      }
    }
    for (c in n.children) destroyViews(c)
  }

  // ---------------- 弹窗 ----------------

  private fun dialogShow(cmd: JSONObject): JSONObject? {
    val req = cmd.optLong("req", 0L)
    val dlg = cmd.optJSONObject("dlg") ?: throw IllegalArgumentException("dlg missing")
    val win = "__dlg_$req"
    // 已存在同 req 弹窗：先关
    dialogs[win]?.let { old ->
      try {
        DialogHost.dismiss(uiContext, wm, old, "cancel", ::onDialogResult, ::onDialogClosed)
      } catch (t: Throwable) {}
    }
    val s = DialogHost.show(uiContext, wm, req, dlg, emitOf(win), ::onDialogResult, ::onDialogClosed)
    dialogs[win] = s
    return JSONObject().put("win", win)
  }

  private fun dialogClose(cmd: JSONObject): JSONObject? {
    val win = cmd.optString("win")
    val s = dialogs[win] ?: return null
    DialogHost.dismiss(uiContext, wm, s, cmd.optString("action", "cancel"),
      ::onDialogResult, ::onDialogClosed)
    return null
  }

  private fun onDialogResult(s: DialogHost.Session, action: String, values: JSONObject, index: Int) {
    val o = JSONObject()
    o.put("ev", "dialog")
    o.put("req", s.req)
    o.put("action", action)
    o.put("values", values)
    if (index >= 0) o.put("index", index)
    pushEvent(o)
  }

  private fun onDialogClosed(win: String) {
    dialogs.remove(win)
  }

  // ---------------- 释放 ----------------

  override fun onRelease() {
    // 快照在 post 前采集：容器销毁（worker IDLE）触发点与 post 执行之间若新容器已建窗，
    // 只回收快照时刻已存在的窗口/弹窗，避免误杀新容器的 UI
    val winSnap = wins.values.toList()
    val dlgSnap = dialogs.values.toList()
    mainHandler.post {
      for (w in winSnap) {
        w.close()
        if (wins[w.id] === w) wins.remove(w.id)
      }
      for (s in dlgSnap) {
        try {
          DialogHost.dismiss(uiContext, wm, s, "cancel", ::onDialogResult, ::onDialogClosed)
        } catch (t: Throwable) {}
      }
      events.clear()
    }
  }

  /** 容器销毁兜底（app 侧 IDLE 观察者调用）：只回收弹窗+清事件队列。
   *  窗口不在本方法关闭——IDLE 状态通知经跨进程分发存在延迟，新容器的窗口可能
   *  已在通知到达前创建（快照采到新窗导致误杀，实测面板消失）；窗口由脚本 Lua
   *  OnStop 链（ui.list→ui.close，引擎 loop 线程序列化保证时序）主动关闭 */
  override fun releaseDialogs() {
    val dlgSnap = dialogs.values.toList()
    mainHandler.post {
      for (s in dlgSnap) {
        try {
          DialogHost.dismiss(uiContext, wm, s, "cancel", ::onDialogResult, ::onDialogClosed)
        } catch (t: Throwable) {}
      }
      events.clear()
    }
  }

  // ---------------- 悬浮窗 ----------------

  inner class FloatWin(
    val id: String,
    private val context: Context,
    private val wm: WindowManager,
    cfg: JSONObject,
    private val emit: (String?, String, JSONObject?) -> Unit
  ) {
    private val lp = WindowManager.LayoutParams()
    private val container = DragFrameLayout(context)
    var root: UiNode? = null
      private set
    @Volatile
    private var showing = false
    private var movePending = false

    init {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      } else {
        @Suppress("DEPRECATION") lp.type = WindowManager.LayoutParams.TYPE_PHONE
      }
      lp.format = PixelFormat.TRANSLUCENT
      lp.gravity = when (cfg.optString("gravity", "")) {
        "center" -> Gravity.CENTER
        else -> Gravity.TOP or Gravity.START
      }
      val fullscreen = cfg.optBoolean("fullscreen", false)
      lp.width = if (fullscreen) ViewGroup.LayoutParams.MATCH_PARENT
      else JsonViewFactory.size(context, cfg.opt("w"), true)
      lp.height = if (fullscreen) ViewGroup.LayoutParams.MATCH_PARENT
      else JsonViewFactory.size(context, cfg.opt("h"), false)
      lp.x = cfg.optInt("x", 0)
      lp.y = cfg.optInt("y", 0)
      lp.flags = 0
      if (!cfg.optBoolean("focus", false)) {
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
      }
      if (cfg.optBoolean("notTouchable", false)) {
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
      }
      if (cfg.optBoolean("fullscreen", false)) {
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
      }
      container.dragEnabled = cfg.optBoolean("drag", false)
      container.posProvider = { lp.x to lp.y }
      container.onDrag = { x, y -> moveTo(x, y) }
      if (cfg.has("alpha")) lp.alpha = cfg.optDouble("alpha", 1.0).toFloat()
    }

    fun create(rootSpec: JSONObject?) {
      val rootLp = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      if (rootSpec != null) {
        val built = JsonViewFactory.buildNode(context, rootSpec, emit)
        root = built
        val w = JsonViewFactory.size(context, rootSpec.opt("w"), true)
        val h = JsonViewFactory.size(context, rootSpec.opt("h"), false)
        if (w > 0) rootLp.width = w
        if (h > 0) rootLp.height = h
        container.addView(built.view, rootLp)
      }
    }

    fun show() {
      if (!showing) {
        try { wm.addView(container, lp); showing = true } catch (t: Throwable) {}
      }
    }

    fun hide() {
      if (showing) {
        try { wm.removeView(container) } catch (t: Throwable) {}
        showing = false
      }
    }

    fun moveTo(x: Int, y: Int) {
      lp.x = x; lp.y = y
      if (showing && !movePending) {
        movePending = true
        mainHandler.post {
          movePending = false
          try { wm.updateViewLayout(container, lp) } catch (t: Throwable) {}
        }
      }
    }

    fun setSize(w: Int, h: Int) {
      if (w > 0) lp.width = w
      if (h > 0) lp.height = h
      if (showing) {
        try { wm.updateViewLayout(container, lp) } catch (t: Throwable) {}
      }
    }

    fun setAlpha(a: Float) {
      lp.alpha = a
      if (showing) {
        try { wm.updateViewLayout(container, lp) } catch (t: Throwable) {}
      }
    }

    fun close() {
      root?.let { destroyAll(it) }
      hide()
    }

    private fun destroyAll(n: UiNode) {
      val v = n.view
      if (v is WebView) {
        try { v.stopLoading(); v.loadUrl("about:blank"); v.destroy() } catch (t: Throwable) {}
      } else if (v is android.widget.VideoView) {
        try { v.stopPlayback() } catch (t: Throwable) {}
      }
      for (c in n.children) destroyAll(c)
    }
  }

  // ---------------- 可拖动容器（12px 阈值拖动/点击互斥，抄派大星思路） ----------------

  class DragFrameLayout(ctx: Context) : FrameLayout(ctx) {
    var dragEnabled = false
    var posProvider: (() -> Pair<Int, Int>)? = null
    var onDrag: ((Int, Int) -> Unit)? = null

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var skipDrag = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private fun skippable(v: View?): Boolean {
      var cur: View? = v
      while (cur != null && cur !== this) {
        if (cur is EditText || cur is ScrollView || cur is HorizontalScrollView ||
          cur is RecyclerView || cur is JsonViewFactory.SimplePagerView) return true
        cur = cur.parent as? View
      }
      return false
    }

    /** 触点是否落在子树内的可滚动/输入控件上（2026-09-23 修复"内容超界不可滚动"）：
     *  skippable 只从窗口直接子节点向上遍历，深层 ScrollView（pager 页内）永远探不到，
     *  拖动层拦截全部竖向拖动 → ScrollView 滚不动。向下递归命中即跳过拖动 */
    private fun hitScrollableIn(v: View?, x: Float, y: Float): Boolean {
      if (v == null || !v.isShown) return false
      if (x < v.left || x > v.right || y < v.top || y > v.bottom) return false
      if (v is ScrollView || v is HorizontalScrollView || v is RecyclerView || v is EditText) return true
      if (v is ViewGroup) {
        val cx = x - v.left
        val cy = y - v.top
        for (i in v.childCount - 1 downTo 0) {
          if (hitScrollableIn(v.getChildAt(i), cx, cy)) return true
        }
      }
      return false
    }

    private fun childUnder(x: Float, y: Float): View? {
      for (i in childCount - 1 downTo 0) {
        val c = getChildAt(i)
        if (c.isShown && x >= c.left && x <= c.right && y >= c.top && y <= c.bottom) return c
      }
      return null
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
      if (!dragEnabled) return super.onInterceptTouchEvent(ev)
      when (ev.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          downRawX = ev.rawX; downRawY = ev.rawY
          val pos = posProvider?.invoke() ?: (0 to 0)
          startX = pos.first; startY = pos.second
          dragging = false
          val target = childUnder(ev.x, ev.y)
          skipDrag = skippable(target) || hitScrollableIn(target, ev.x, ev.y)
        }
        MotionEvent.ACTION_MOVE -> {
          if (!skipDrag && !dragging &&
            (abs(ev.rawX - downRawX) > touchSlop || abs(ev.rawY - downRawY) > touchSlop)) {
            dragging = true
            return true
          }
        }
      }
      return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
      if (dragging) {
        when (ev.actionMasked) {
          MotionEvent.ACTION_MOVE -> onDrag?.invoke(
            startX + (ev.rawX - downRawX).toInt(), startY + (ev.rawY - downRawY).toInt())
          MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
      }
      return super.onTouchEvent(ev)
    }
  }
}
