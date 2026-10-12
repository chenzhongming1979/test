package com.autolua.autolua2.extension.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import android.widget.EditText
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * UI 节点树记录：id 寻址（node.set/get/remove）与 tree.get 序列化的基础。
 */
class UiNode(val type: String, view: View) {
  var view: View = view
  var id: String? = null
  var parent: UiNode? = null
  val children = mutableListOf<UiNode>()
  /** 运行时可变数据（canvas 指令队列 / list items / pager titles 等） */
  val extra = ConcurrentHashMap<String, Any?>()

  fun findById(target: String): UiNode? {
    id?.let { if (it == target) return this }
    for (c in children) c.findById(target)?.let { return it }
    return null
  }

  fun removeFromParent() {
    parent?.let { p ->
      p.children.remove(this)
      (p.view as? ViewGroup)?.let { vg ->
        if (view.parent === vg) vg.removeView(view)
      }
      parent = null
    }
  }
}

/**
 * JSON 控件描述 → Android View 工厂（全量控件面）。
 *
 * 控件 type：linear frame relative scroll grid card pager | text button edit |
 * checkbox switch radio spinner seek rating progress | image webview video list canvas
 *
 * 通用属性：id w h margin/padding(l/r/t/b/all) gravity layout_gravity bg visibility
 * enabled alpha clickable rotation elevation tag weight
 *
 * 事件声明：events:["click","longClick",...]（build 时挂 listener，node.bind 动态追加）。
 * 事件统一经 emit(id, event, data) 上抛（UiMgrImp 入队 → pollEvents → Lua 回调）。
 *
 * 所有 build 必须在主线程执行（UiMgrImp op 分发保证）。
 */
object JsonViewFactory {

  private val mainHandler = Handler(Looper.getMainLooper())

  // ---------------- 解析工具 ----------------

  fun color(s: String?, def: Int = Color.TRANSPARENT): Int {
    if (s == null) return def
    return try {
      when {
        s.startsWith("#") && s.length == 4 -> Color.parseColor("#" + s[1] + s[1] + s[2] + s[2] + s[3] + s[3])
        else -> Color.parseColor(s)
      }
    } catch (t: Throwable) {
      def
    }
  }

  fun sp(ctx: Context, v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, ctx.resources.displayMetrics)

  fun dp(ctx: Context, v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

  fun screenW(ctx: Context): Int = ctx.resources.displayMetrics.widthPixels
  fun screenH(ctx: Context): Int = ctx.resources.displayMetrics.heightPixels

  /**
   * 尺寸解析：-1=match  -2=wrap  数字=px  "50%"=屏幕百分比  "dp:12"/"sp:14" 带单位
   */
  fun size(ctx: Context, v: Any?, horizontal: Boolean): Int {
    if (v == null) return ViewGroup.LayoutParams.WRAP_CONTENT
    if (v is Number) return v.toInt()
    val s = v.toString().trim()
    return when {
      s.equals("match", true) || s.equals("fill", true) || s.equals("match_parent", true) -> ViewGroup.LayoutParams.MATCH_PARENT
      s.equals("wrap", true) || s.equals("wrap_content", true) -> ViewGroup.LayoutParams.WRAP_CONTENT
      s.endsWith("%") -> {
        val p = s.removeSuffix("%").toFloatOrNull() ?: return ViewGroup.LayoutParams.WRAP_CONTENT
        ((if (horizontal) screenW(ctx) else screenH(ctx)) * p / 100f).toInt()
      }
      s.startsWith("dp:") -> dp(ctx, s.removePrefix("dp:").toFloatOrNull() ?: 0f).toInt()
      s.startsWith("sp:") -> sp(ctx, s.removePrefix("sp:").toFloatOrNull() ?: 0f).toInt()
      else -> s.toFloatOrNull()?.toInt() ?: ViewGroup.LayoutParams.WRAP_CONTENT
    }
  }

  /** bg："#RRGGBB" 纯色 | {color,corner,stroke:{color,width},shape:round/rect/oval} */
  fun background(ctx: Context, v: Any?): android.graphics.drawable.Drawable? {
    if (v == null) return null
    if (v is String || v is Number) {
      val c = color(v.toString())
      return GradientDrawable().apply { setColor(c) }
    }
    if (v is JSONObject) {
      val d = GradientDrawable()
      d.setColor(color(v.optString("color", "#00000000")))
      when (v.optString("shape", "round")) {
        "rect" -> d.shape = GradientDrawable.RECTANGLE
        "oval" -> d.shape = GradientDrawable.OVAL
        "line" -> d.shape = GradientDrawable.LINE
        else -> d.shape = GradientDrawable.RECTANGLE
      }
      val corner = v.opt("corner")
      if (corner != null) {
        val r = dp(ctx, corner.toString().toFloatOrNull() ?: 0f)
        val arr = v.optJSONArray("corner")
        if (arr != null && arr.length() == 4) {
          val f = FloatArray(8)
          for (i in 0 until 4) f[i * 2] = dp(ctx, arr.optDouble(i, 0.0).toFloat())
          for (i in 0 until 4) f[i * 2 + 1] = f[i * 2]
          d.cornerRadii = f
        } else d.cornerRadius = r
      }
      val stroke = v.optJSONObject("stroke")
      if (stroke != null) {
        d.setStroke(dp(ctx, stroke.optDouble("width", 1.0).toFloat()).toInt(),
          color(stroke.optString("color", "#FFFFFFFF")))
      }
      return d
    }
    return null
  }

  private fun edge(obj: JSONObject, allKey: String, lKey: String): IntArray {
    // 返回 [l, t, r, b]
    val all = obj.opt(allKey)
    val l = obj.opt(lKey) ?: all
    val t = obj.opt("${lKey}Top").let { if (it != null) it else all }
    val r = obj.opt("${lKey}Right").let { if (it != null) it else all }
    val b = obj.opt("${lKey}Bottom").let { if (it != null) it else all }
    fun n(x: Any?): Int = when (x) {
      null -> 0
      is Number -> x.toInt()
      else -> x.toString().toFloatOrNull()?.toInt() ?: 0
    }
    return intArrayOf(n(l), n(t), n(r), n(b))
  }

  private fun gravityOf(s: String?): Int {
    if (s == null) return Gravity.NO_GRAVITY
    var g = Gravity.NO_GRAVITY
    for (p in s.split("|")) {
      g = g or when (p.trim().lowercase()) {
        "left" -> Gravity.LEFT
        "right" -> Gravity.RIGHT
        "top" -> Gravity.TOP
        "bottom" -> Gravity.BOTTOM
        "center" -> Gravity.CENTER
        "center_horizontal" -> Gravity.CENTER_HORIZONTAL
        "center_vertical" -> Gravity.CENTER_VERTICAL
        "start" -> Gravity.START
        "end" -> Gravity.END
        else -> Gravity.NO_GRAVITY
      }
    }
    return g
  }

  // ---------------- 通用属性 ----------------

  fun applyCommon(ctx: Context, n: UiNode, spec: JSONObject) {
    val v = n.view
    val tag = spec.opt("tag")
    if (tag != null) v.tag = tag.toString()
    if (spec.has("enabled")) v.isEnabled = spec.optBoolean("enabled", true)
    if (spec.has("alpha")) v.alpha = spec.optDouble("alpha", 1.0).toFloat()
    if (spec.has("clickable")) v.isClickable = spec.optBoolean("clickable", false)
    if (spec.has("rotation")) v.rotation = spec.optDouble("rotation", 0.0).toFloat()
    if (spec.has("elevation")) v.elevation = dp(ctx, spec.optDouble("elevation", 0.0).toFloat())
    if (spec.has("bg")) v.background = background(ctx, spec.opt("bg"))
    when (spec.optString("visibility", "visible")) {
      "gone" -> v.visibility = View.GONE
      "invisible" -> v.visibility = View.INVISIBLE
      else -> v.visibility = View.VISIBLE
    }
    if (spec.has("padding") || spec.has("pad") || spec.has("padTop") ||
      spec.has("padLeft") || spec.has("padRight") || spec.has("padBottom")) {
      val p = edge(spec, "padding", "pad")
      v.setPadding(p[0], p[1], p[2], p[3])
    }
  }

  /** 容器子 View LayoutParams（linear/frame/relative/grid 分别处理） */
  fun childParams(ctx: Context, n: UiNode, spec: JSONObject, child: View): ViewGroup.LayoutParams {
    val p = n.view
    val w = size(ctx, spec.opt("w"), true)
    val h = size(ctx, spec.opt("h"), false)
    return when (p) {
      is LinearLayout -> {
        val lp = LinearLayout.LayoutParams(w, h)
        val g = spec.opt("layout_gravity")
        if (g != null) lp.gravity = gravityOf(g.toString())
        val weight = spec.optDouble("weight", Double.NaN)
        if (!weight.isNaN()) lp.weight = weight.toFloat()
        applyMargins(ctx, spec, lp)
        lp
      }
      is FrameLayout -> {
        val lp = FrameLayout.LayoutParams(w, h)
        val g = spec.opt("layout_gravity")
        if (g != null) lp.gravity = gravityOf(g.toString())
        applyMargins(ctx, spec, lp)
        lp
      }
      is android.widget.RelativeLayout -> {
        val lp = android.widget.RelativeLayout.LayoutParams(w, h)
        applyRelativeRules(spec, lp)
        applyMargins(ctx, spec, lp)
        lp
      }
      is android.widget.GridLayout -> {
        val lp = android.widget.GridLayout.LayoutParams(
          android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED),
          android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED))
        lp.width = w; lp.height = h
        val row = spec.optInt("row", -1)
        val col = spec.optInt("column", -1)
        val rs = spec.optInt("rowSpan", 1)
        val cs = spec.optInt("columnSpan", 1)
        if (row >= 0 || col >= 0) {
          lp.rowSpec = android.widget.GridLayout.spec(if (row >= 0) row else android.widget.GridLayout.UNDEFINED, rs)
          lp.columnSpec = android.widget.GridLayout.spec(if (col >= 0) col else android.widget.GridLayout.UNDEFINED, cs)
        }
        applyMargins(ctx, spec, lp)
        lp
      }
      else -> ViewGroup.LayoutParams(w, h)
    }
  }

  private fun applyMargins(ctx: Context, spec: JSONObject, lp: ViewGroup.MarginLayoutParams) {
    if (spec.has("margin") || spec.has("marginTop") || spec.has("marginBottom") ||
      spec.has("marginRight") || spec.has("marginLeft")) {
      val m = edge(spec, "margin", "margin")
      lp.setMargins(m[0], m[1], m[2], m[3])
    }
  }

  private fun applyRelativeRules(spec: JSONObject, lp: android.widget.RelativeLayout.LayoutParams) {
    when (spec.optString("alignParent")) {
      "center" -> lp.addRule(android.widget.RelativeLayout.CENTER_IN_PARENT)
      "center_horizontal" -> lp.addRule(android.widget.RelativeLayout.CENTER_HORIZONTAL)
      "center_vertical" -> lp.addRule(android.widget.RelativeLayout.CENTER_VERTICAL)
      "left" -> lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_LEFT)
      "right" -> lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_RIGHT)
      "top" -> lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP)
      "bottom" -> lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
    }
    val below = spec.opt("below"); val above = spec.opt("above")
    val leftOf = spec.opt("leftOf"); val rightOf = spec.opt("rightOf")
    // 相对 id 对齐：使用 -1 占位（View.generateViewId 的稳定负数 id 在 attach 阶段回填）
    if (below != null) lp.addRule(android.widget.RelativeLayout.BELOW, -100 - (below.toString().hashCode() and 0x7FFFFFFF) % 100000)
    if (above != null) lp.addRule(android.widget.RelativeLayout.ABOVE, -100 - (above.toString().hashCode() and 0x7FFFFFFF) % 100000)
    if (leftOf != null) lp.addRule(android.widget.RelativeLayout.LEFT_OF, -100 - (leftOf.toString().hashCode() and 0x7FFFFFFF) % 100000)
    if (rightOf != null) lp.addRule(android.widget.RelativeLayout.RIGHT_OF, -100 - (rightOf.toString().hashCode() and 0x7FFFFFFF) % 100000)
  }

  // ---------------- build 主入口 ----------------

  fun addChild(ctx: Context, parent: UiNode, spec: JSONObject, emit: (String?, String, JSONObject?) -> Unit, index: Int): UiNode? {
    val child = buildNode(ctx, spec, emit)
    val vg = parent.view as? ViewGroup ?: return null
    // scroll 容器只收一个直接子节点
    if ((parent.type == "scroll" || parent.type == "scrollH") && vg.childCount > 0) {
      vg.removeAllViews()
      parent.children.clear()
    }
    val lp = childParams(ctx, parent, spec, child.view)
    if (index < 0 || index >= vg.childCount) vg.addView(child.view, lp) else vg.addView(child.view, index, lp)
    child.parent = parent
    // 相对布局 id 规则回填：把 below/above 等目标 id 换成真实 view id
    if (parent.type == "relative") resolveRelativeIds(parent, child)
    parent.children.add(child)
    return child
  }

  private fun resolveRelativeIds(parent: UiNode, child: UiNode) {
    // RelativeLayout 规则使用 hash 占位 id：给目标控件补设同款 id 即可命中
    for (c in parent.children) {
      val cid = c.id ?: continue
      c.view.id = -100 - (cid.hashCode() and 0x7FFFFFFF) % 100000
    }
    child.id?.let { child.view.id = -100 - (it.hashCode() and 0x7FFFFFFF) % 100000 }
  }

  private fun createView(ctx: Context, type: String): View = when (type) {
    "text" -> TextView(ctx)
    "button" -> Button(ctx)
    "edit" -> EditText(ctx)
    "checkbox" -> android.widget.CheckBox(ctx)
    "switch" -> androidx.appcompat.widget.SwitchCompat(ctx)
    "radio" -> android.widget.RadioGroup(ctx)
    "spinner" -> android.widget.Spinner(ctx)
    "seek" -> android.widget.SeekBar(ctx)
    "rating" -> android.widget.RatingBar(ctx)
    "progress" -> android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal)
    "image" -> ImageView(ctx)
    "webview" -> WebView(ctx)
    "video" -> android.widget.VideoView(ctx)
    "linear" -> LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    "frame" -> FrameLayout(ctx)
    "relative" -> android.widget.RelativeLayout(ctx)
    "scroll" -> ScrollView(ctx)
    "scrollH" -> HorizontalScrollView(ctx)
    "grid" -> android.widget.GridLayout(ctx)
    "card" -> com.google.android.material.card.MaterialCardView(ctx)
    "pager" -> SimplePagerView(ctx)
    "list" -> androidx.recyclerview.widget.RecyclerView(ctx)
    "canvas" -> CanvasView(ctx)
    else -> LinearLayout(ctx)
  }
  // ---------------- build 主流程（pager 特殊包装） ----------------

  fun buildNode(ctx: Context, spec: JSONObject, emit: (String?, String, JSONObject?) -> Unit): UiNode {
    val type = spec.optString("type", "linear").lowercase()
    val raw = createView(ctx, type)
    val n = UiNode(type, raw)
    if (raw is WebView) raw.settings.javaScriptEnabled = true
    n.id = if (spec.has("id") && !spec.isNull("id")) spec.optString("id") else null
    // 树 spec 声明 events:[...]：预置 touch 上报白名单（touch 默认不上报，见 attachAllEvents）
    val declared = spec.optJSONArray("events")
    if (declared != null) {
      val set = linkedSetOf<String>()
      for (i in 0 until declared.length()) set.add(declared.optString(i))
      n.extra["listen"] = set
    }
    if (type == "pager") {
      // pager 包装：titles 存在时包 TabLayout + SimplePagerView（弃 ViewPager2：
      // 其 onLayout 强校验 page 尺寸 match_parent，动态构建场景易抛 IllegalStateException 崩掉 app 主线程）
      val wrap = LinearLayout(ctx)
      wrap.orientation = LinearLayout.VERTICAL
      val pager = raw as SimplePagerView
      n.extra["pager"] = pager
      val titles = spec.optJSONArray("titles")
      if (titles != null && titles.length() > 0) {
        val tabs = com.google.android.material.tabs.TabLayout(ctx)
        // 页签定高 32dp（默认 48dp 偏高，紧凑更美观）
        wrap.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 32f).toInt()))
        // 页签下分割线：1dp 淡灰，白底面板上区分页签区与内容区
        wrap.addView(android.view.View(ctx).apply { setBackgroundColor(0xFFCCCCCC.toInt()) },
          LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1f).toInt()))
        wrap.addView(pager, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        n.extra["tabs"] = tabs
      } else {
        wrap.addView(pager, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
      }
      n.view = wrap
    }
    applyCommon(ctx, n, spec)
    applyAttrs(ctx, n, spec, emit)
    val children = spec.optJSONArray("children")
    if (children != null) {
      when (type) {
        "pager" -> {
          val pager = n.extra["pager"] as SimplePagerView
          val pages = mutableListOf<JSONObject>()
          for (i in 0 until children.length()) {
            val cs = children.optJSONObject(i) ?: continue
            pages.add(cs)
          }
          n.extra["pages"] = pages
          // 每页一次性 build 并挂入 n.children：tree.get / findById(node.get/set) /
          // destroyAll 全树覆盖（旧实现惰性 build 当前页，页内控件对 Lua 完全不可见）
          val views = buildPagerPages(ctx, n, pager, pages, emit)
          pager.setup(views) { pos ->
            emit(n.id, "itemSelect", JSONObject().put("index", pos))
            // 程序化切换（currentItem attr / node.set）时反向同步页签；用户点页签时无需回写
            val tabs = n.extra["tabs"] as? com.google.android.material.tabs.TabLayout
            if (!pager.lastChangeFromUser) tabs?.getTabAt(pos)?.select()
          }
        }
        "list" -> { /* list 的 items 走 adapter，不按 children 处理 */ }
        else -> {
          if (n.view is ViewGroup) {
            for (i in 0 until children.length()) {
              val cs = children.optJSONObject(i) ?: continue
              addChild(ctx, n, cs, emit, -1)
            }
          }
        }
      }
    }
    attachAllEvents(ctx, n, emit)
    return n
  }

  // ---------------- 类型属性（build 与 node.set 共用） ----------------

  /**
   * pager 页节点重建：每页一次性 build 并挂入 n.children——
   * tree.get / findById(node.get/set/remove) / destroyAll 全树覆盖；
   * 页 view 由 SimplePagerView.setup 负责显示切换（不直接 addView 到容器）。
   * TabLayout 页签同步重建（页签文字取页 spec 的 title，缺省 页N）。
   */
  private fun buildPagerPages(
    ctx: Context, n: UiNode, pager: SimplePagerView, pagesSpec: List<JSONObject>,
    emit: (String?, String, JSONObject?) -> Unit
  ): List<View> {
    val tabs = n.extra["tabs"] as? com.google.android.material.tabs.TabLayout
    // 旧页节点摘除（view 由 SimplePagerView.show() 的 removeAllViews 统一清理）
    n.children.clear()
    tabs?.removeAllTabs()
    val views = mutableListOf<View>()
    for ((i, cs) in pagesSpec.withIndex()) {
      val pn = buildNode(ctx, cs, emit)
      pn.parent = n
      n.children.add(pn)
      views.add(pn.view)
      if (tabs != null) {
        val tab = tabs.newTab()
        tab.text = cs.optString("title", "页${i + 1}")
        tabs.addTab(tab)
      }
    }
    // 页签 ↔ pager 联动（listener 只挂一次，防 node.set 重建页时重复回调）
    if (tabs != null && n.extra["tabsHooked"] == null) {
      n.extra["tabsHooked"] = true
      tabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
        override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
          pager.setCurrentItemCompat(tab.position, true)
        }
        override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
      })
    }
    return views
  }

  fun applyAttrs(ctx: Context, n: UiNode, attrs: JSONObject, emit: (String?, String, JSONObject?) -> Unit) {
    val v = n.view
    when (n.type) {
      "text", "button" -> {
        val tv = v as TextView
        if (attrs.has("text")) tv.text = attrs.optString("text")
        if (attrs.has("textSize")) tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, attrs.optDouble("textSize", 14.0).toFloat())
        if (attrs.has("textColor")) tv.setTextColor(color(attrs.optString("textColor", "#FF000000")))
        if (attrs.has("bold") && attrs.optBoolean("bold")) tv.paintFlags = tv.paintFlags or android.graphics.Paint.FAKE_BOLD_TEXT_FLAG
        if (attrs.has("maxLines")) tv.maxLines = attrs.optInt("maxLines", 1)
        if (attrs.optBoolean("ellipsize")) tv.ellipsize = TextUtils.TruncateAt.END
        if (attrs.has("gravity")) tv.gravity = gravityOf(attrs.optString("gravity"))
        if (attrs.optBoolean("singleLine")) tv.setSingleLine(true)
      }
      "edit" -> {
        val et = v as EditText
        if (attrs.has("text")) et.setText(attrs.optString("text"))
        if (attrs.has("hint")) et.hint = attrs.optString("hint")
        if (attrs.has("textSize")) et.setTextSize(TypedValue.COMPLEX_UNIT_SP, attrs.optDouble("textSize", 14.0).toFloat())
        if (attrs.has("textColor")) et.setTextColor(color(attrs.optString("textColor", "#FF000000")))
        if (attrs.has("hintColor")) et.setHintTextColor(color(attrs.optString("hintColor", "#66888888")))
        if (attrs.has("gravity")) et.gravity = gravityOf(attrs.optString("gravity"))
        when (attrs.optString("inputType", "")) {
          "number" -> et.inputType = EditorInfo.TYPE_CLASS_NUMBER
          "phone" -> et.inputType = EditorInfo.TYPE_CLASS_PHONE
          "pass", "password" -> et.inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
          "multiline" -> et.inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
        }
        if (attrs.optBoolean("singleLine")) et.setSingleLine(true)
        if (attrs.has("lines")) et.setLines(attrs.optInt("lines", 1))
      }
      "checkbox", "switch" -> {
        val cb = v as android.widget.CompoundButton
        if (attrs.has("text")) cb.text = attrs.optString("text")
        if (attrs.has("textSize")) cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, attrs.optDouble("textSize", 14.0).toFloat())
        if (attrs.has("textColor")) cb.setTextColor(color(attrs.optString("textColor", "#FF000000")))
        if (attrs.has("checked")) cb.isChecked = attrs.optBoolean("checked", false)
      }
      "radio" -> {
        val rg = v as android.widget.RadioGroup
        rg.orientation = if (attrs.optString("orientation", "v").startsWith("h")) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val items = attrs.optJSONArray("items")
        if (items != null) {
          rg.removeAllViews()
          n.children.clear()
          n.extra["items"] = items
          for (i in 0 until items.length()) {
            val rb = android.widget.RadioButton(ctx)
            rb.text = items.optString(i)
            rb.id = i + 1
            rb.textSize = sp(ctx, 14f)
            rg.addView(rb)
          }
        }
        if (attrs.has("checked")) rg.check(attrs.optInt("checked", 0) + 1)
        if (attrs.has("textSize")) {
          for (i in 0 until rg.childCount) (rg.getChildAt(i) as TextView).setTextSize(TypedValue.COMPLEX_UNIT_SP, attrs.optDouble("textSize", 14.0).toFloat())
        }
      }
      "spinner" -> {
        val sp = v as android.widget.Spinner
        val items = attrs.optJSONArray("items")
        if (items != null) {
          n.extra["items"] = items
          val list = ArrayList<String>()
          for (i in 0 until items.length()) list.add(items.optString(i))
          val ad = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_item, list)
          ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
          sp.adapter = ad
        }
        if (attrs.has("selection")) sp.setSelection(attrs.optInt("selection", 0))
      }
      "seek" -> {
        val sb = v as android.widget.SeekBar
        if (attrs.has("max")) sb.max = attrs.optInt("max", 100)
        if (attrs.has("progress")) sb.progress = attrs.optInt("progress", 0)
      }
      "rating" -> {
        val rb = v as android.widget.RatingBar
        if (attrs.has("numStars")) rb.numStars = attrs.optInt("numStars", 5)
        if (attrs.has("rating")) rb.rating = attrs.optDouble("rating", 0.0).toFloat()
        if (attrs.has("stepSize")) rb.stepSize = attrs.optDouble("stepSize", 0.5).toFloat()
        if (attrs.has("isIndicator")) rb.setIsIndicator(attrs.optBoolean("isIndicator", false))
      }
      "progress" -> {
        val pb = v as android.widget.ProgressBar
        if (attrs.has("max")) pb.max = attrs.optInt("max", 100)
        if (attrs.has("indeterminate")) pb.isIndeterminate = attrs.optBoolean("indeterminate", false)
        if (attrs.has("progress")) pb.progress = attrs.optInt("progress", 0)
      }
      "image" -> {
        if (attrs.has("src")) loadImage(ctx, v as ImageView, attrs.optString("src"))
        val st = attrs.optString("scaleType", "")
        if (st.isNotEmpty()) {
          (v as ImageView).scaleType = try {
            ImageView.ScaleType.valueOf(st.uppercase())
          } catch (t: Throwable) {
            ImageView.ScaleType.FIT_CENTER
          }
        }
      }
      "webview" -> {
        val wv = v as WebView
        if (attrs.has("url")) wv.loadUrl(attrs.optString("url"))
        if (attrs.has("html")) wv.loadDataWithBaseURL(null, attrs.optString("html"), "text/html", "utf-8", null)
        if (attrs.has("localFile")) {
          val f = attrs.optString("localFile")
          wv.loadUrl("file://" + if (f.startsWith("/")) f else "/$f")
        }
      }
      "video" -> {
        val vv = v as android.widget.VideoView
        if (attrs.has("path") || attrs.has("url")) {
          vv.setVideoPath(attrs.optString("path", attrs.optString("url", "")))
        }
        if (attrs.has("start")) { if (attrs.optBoolean("start")) vv.start() else vv.pause() }
        if (attrs.has("pause") && attrs.optBoolean("pause")) vv.pause()
        if (attrs.has("stop") && attrs.optBoolean("stop")) vv.stopPlayback()
        if (attrs.has("seekTo")) vv.seekTo(attrs.optInt("seekTo", 0))
      }
      "linear" -> {
        val ll = v as LinearLayout
        if (attrs.has("orientation")) {
          ll.orientation = if (attrs.optString("orientation").startsWith("h")) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        } else if (attrs.has("orientation") == false) {
          // 默认纵向（首次 build 时 attrs.orientation 缺省 v）
        }
        if (attrs.has("gravity")) ll.gravity = gravityOf(attrs.optString("gravity"))
        if (attrs.has("weightSum")) ll.weightSum = attrs.optDouble("weightSum", 0.0).toFloat()
      }
      "scroll" -> {
        (v as ScrollView).isFillViewport = attrs.optBoolean("fillViewport", false)
      }
      "card" -> {
        val mc = v as com.google.android.material.card.MaterialCardView
        if (attrs.has("cardRadius")) mc.radius = dp(ctx, attrs.optDouble("cardRadius", 8.0).toFloat())
        if (attrs.has("cardElevation")) mc.cardElevation = dp(ctx, attrs.optDouble("cardElevation", 2.0).toFloat())
      }
      "pager" -> {
        val pager = n.extra["pager"] as? SimplePagerView ?: return
        if (attrs.has("currentItem")) pager.setCurrentItemCompat(attrs.optInt("currentItem", 0), false)
        val items = attrs.optJSONArray("items")
        if (items != null) {
          val pages = mutableListOf<JSONObject>()
          for (i in 0 until items.length()) items.optJSONObject(i)?.let { pages.add(it) }
          n.extra["pages"] = pages
          val views = buildPagerPages(ctx, n, pager, pages, emit)
          pager.setup(views, pager.onSelectedHook ?: {})
        }
      }
      "list" -> {
        val rv = v as androidx.recyclerview.widget.RecyclerView
        if (rv.layoutManager == null) {
          rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
        }
        var ad = rv.adapter as? ListAdapter
        if (ad == null) {
          ad = ListAdapter(ctx, n.id, emit)
          rv.adapter = ad
          rv.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
              val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
              if (lm.findLastVisibleItemPosition() >= (rv.adapter?.itemCount ?: 0) - 1) {
                emit(n.id, "scrollEnd", null)
              }
            }
          })
        }
        if (attrs.has("orientation") && attrs.optString("orientation").startsWith("h")) {
          rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
        }
        if (attrs.has("itemXml")) ad.template = attrs.optJSONObject("itemXml")
        if (attrs.has("items")) ad.update(attrs.optJSONArray("items") ?: JSONArray())
      }
      "canvas" -> {
        val cv = v as CanvasView
        val c = attrs.optJSONObject("canvas")
        if (c != null) {
          if (c.optBoolean("clear")) cv.ops.clear()
          val ops = c.optJSONArray("ops")
          if (ops != null) for (i in 0 until ops.length()) ops.optJSONObject(i)?.let { cv.ops.add(it) }
          cv.invalidate()
        }
      }
    }
  }

  private fun loadImage(ctx: Context, iv: ImageView, src: String) {
    if (src.startsWith("base64:")) {
      val data = Base64.decode(src.removePrefix("base64:"), Base64.DEFAULT)
      val bm = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
      if (bm != null) iv.setImageBitmap(bm)
      return
    }
    if (src.startsWith("data:image")) {
      val b64 = src.substringAfter("base64,", "")
      if (b64.isNotEmpty()) {
        val data = Base64.decode(b64, Base64.DEFAULT)
        val bm = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
        if (bm != null) iv.setImageBitmap(bm)
      }
      return
    }
    mainHandler.post {
      Thread {
        try {
          val bytes: ByteArray = if (src.startsWith("http://") || src.startsWith("https://")) {
            val conn = URL(src).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.inputStream.use { it.readBytes() }
          } else {
            File(if (src.startsWith("/")) src else "/$src").readBytes()
          }
          val bm = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
          if (bm != null) mainHandler.post { iv.setImageBitmap(bm) }
        } catch (t: Throwable) { /* 图片加载失败静默 */ }
      }.start()
    }
  }

  // ---------------- 事件（全量常挂，Lua 侧未绑定则丢弃） ----------------

  private fun attachAllEvents(ctx: Context, n: UiNode, emit: (String?, String, JSONObject?) -> Unit) {
    val v = n.view
    val id = n.id
    // AdapterView（Spinner/ListView/GridView）禁止 OnClickListener，走各自的 select/item 事件
    if (v !is android.widget.AdapterView<*>) {
      v.setOnClickListener { emit(id, "click", null) }
      v.setOnLongClickListener {
        emit(id, "longClick", null); true
      }
    }
    // touch 是唯一的高频事件源（一次 tap 产生 DOWN+MOVE×N+UP，拖动/滑动时每秒几十条，
    // 洪流占满事件队列会挤掉 click）：默认不上报，仅当该节点显式注册关心 touch
    // （ui.bind("touch") → node.listen op → extra["listen"]，或树 spec 声明 events 含 touch）才 emit
    v.setOnTouchListener { _, ev ->
      val listen = n.extra["listen"] as? Set<*>
      if (listen == null || "touch" !in listen) return@setOnTouchListener false
      val d = JSONObject().put("action", ev.actionMasked)
        .put("x", ev.x).put("y", ev.y)
      emit(id, "touch", d)
      false
    }
    when (v) {
      is android.widget.CompoundButton -> v.setOnCheckedChangeListener { _, checked ->
        emit(id, "check", JSONObject().put("checked", checked))
      }
      is android.widget.RadioGroup -> v.setOnCheckedChangeListener { _, checkedId ->
        emit(id, "check", JSONObject().put("index", checkedId - 1))
      }
      is android.widget.Spinner -> v.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: android.widget.AdapterView<*>?, view: View?, pos: Int, l: Long) {
          val d = JSONObject().put("index", pos).put("item", v.getItemAtPosition(pos)?.toString() ?: "")
          emit(id, "select", d)
        }
        override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
      }
      is android.widget.SeekBar -> v.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
          emit(id, "progressChange", JSONObject().put("progress", progress).put("fromUser", fromUser))
        }
        override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
        override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
      })
      is android.widget.RatingBar -> v.setOnRatingBarChangeListener { _, rating, fromUser ->
        emit(id, "progressChange", JSONObject().put("progress", rating).put("fromUser", fromUser))
      }
      is EditText -> {
        v.addTextChangedListener(object : android.text.TextWatcher {
          override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
          override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
          override fun afterTextChanged(s: android.text.Editable?) {
            emit(id, "textChange", JSONObject().put("text", s?.toString() ?: ""))
          }
        })
        v.setOnEditorActionListener { _, _, _ ->
          emit(id, "editorAction", null); false
        }
      }
      is WebView -> {
        v.webViewClient = object : android.webkit.WebViewClient() {
          override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            emit(id, "urlLoad", JSONObject().put("url", url)); return false
          }
          override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
            emit(id, "urlLoad", JSONObject().put("url", request.url.toString())); return false
          }
          override fun onPageFinished(view: WebView?, url: String?) {
            emit(id, "pageDone", JSONObject().put("url", url ?: ""))
          }
        }
      }
    }
  }

  // ---------------- 值收集（node.get / tree.get / dialog 收集） ----------------

  fun collectValues(ctx: Context, n: UiNode): JSONObject {
    val out = JSONObject()
    val v = n.view
    out.put("type", n.type)
    n.id?.let { out.put("id", it) }
    out.put("visibility", when (v.visibility) {
      View.GONE -> "gone"; View.INVISIBLE -> "invisible"; else -> "visible"
    })
    out.put("enabled", v.isEnabled)
    out.put("alpha", v.alpha.toDouble())
    out.put("x", v.x.toInt()); out.put("y", v.y.toInt())
    out.put("w", v.width); out.put("h", v.height)
    when (v) {
      // 注意分支顺序：子类在前（EditText/CompoundButton/RatingBar 均是 TextView/ProgressBar 派生）
      is EditText -> {
        out.put("text", v.text.toString())
        v.hint?.let { out.put("hint", it.toString()) }
      }
      is android.widget.CompoundButton -> {
        out.put("checked", v.isChecked)
        out.put("text", v.text.toString())
      }
      is android.widget.RadioGroup -> out.put("selectedIndex", v.checkedRadioButtonId - 1)
      is android.widget.Spinner -> {
        out.put("selectedIndex", v.selectedItemPosition)
        v.selectedItem?.let { out.put("selectedItem", it.toString()) }
      }
      is android.widget.SeekBar -> {
        out.put("progress", v.progress); out.put("max", v.max)
      }
      is android.widget.RatingBar -> {
        out.put("progress", v.rating.toDouble()); out.put("numStars", v.numStars)
      }
      is android.widget.ProgressBar -> {
        out.put("progress", v.progress); out.put("max", v.max)
      }
      is TextView -> out.put("text", v.text.toString())
      is WebView -> out.put("url", v.url ?: "")
      is android.widget.VideoView -> {
        out.put("position", v.currentPosition); out.put("duration", v.duration)
        out.put("isPlaying", v.isPlaying)
      }
      is androidx.recyclerview.widget.RecyclerView -> out.put("count", v.adapter?.itemCount ?: 0)
      is SimplePagerView -> out.put("currentItem", v.current)
    }
    return out
  }

  // ---------------- List 适配器（items + itemXml 模板） ----------------

  private fun substitute(spec: JSONObject, item: Any?): JSONObject {
    val copy = JSONObject(spec.toString())
    substituteIn(copy, item)
    return copy
  }

  private fun substituteIn(obj: JSONObject, item: Any?) {
    val keys = ArrayList<String>()
    val it = obj.keys()
    while (it.hasNext()) keys.add(it.next())
    for (k in keys) {
      val v = obj.opt(k) ?: continue
      when (v) {
        is JSONObject -> substituteIn(v, item)
        is String -> obj.put(k, fill(v, item))
      }
    }
    val arrKeys = ArrayList<String>()
    for (k in keys) if (obj.opt(k) is JSONArray) arrKeys.add(k)
    for (k in arrKeys) {
      val arr = obj.optJSONArray(k) ?: continue
      for (i in 0 until arr.length()) {
        val e = arr.opt(i)
        if (e is JSONObject) substituteIn(e, item)
        else if (e is String) arr.put(i, fill(e, item))
      }
    }
  }

  private fun fill(s: String, item: Any?): String {
    if (item !is JSONObject) return s
    var out = s
    val kit = item.keys()
    while (kit.hasNext()) {
      val k = kit.next()
      out = out.replace("{{$k}}", item.optString(k))
    }
    return out
  }

  class ListAdapter(
    private val ctx: Context,
    private val nodeId: String?,
    private val emit: (String?, String, JSONObject?) -> Unit
  ) : androidx.recyclerview.widget.RecyclerView.Adapter<ListAdapter.VH>() {

    var template: JSONObject? = null
    var items: JSONArray = JSONArray()
      private set

    class VH(val container: FrameLayout) : androidx.recyclerview.widget.RecyclerView.ViewHolder(container)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
      VH(FrameLayout(ctx))

    override fun getItemCount(): Int = items.length()

    override fun onBindViewHolder(h: VH, pos: Int) {
      val item = items.opt(pos)
      val c = h.container
      c.removeAllViews()
      val t = template
      val spec: JSONObject = if (t != null) substitute(t, item) else
        JSONObject().put("type", "text").put("text", item?.toString() ?: "")
        .put("textSize", 14).put("padding", 16)
      val built = buildNode(ctx, spec, emit)
      c.addView(built.view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
      c.setOnClickListener {
        val d = JSONObject().put("index", pos).put("item", item ?: JSONObject.NULL)
        emit(nodeId, "itemClick", d)
      }
      c.setOnLongClickListener {
        val d = JSONObject().put("index", pos).put("item", item ?: JSONObject.NULL)
        emit(nodeId, "itemLongClick", d)
        true
      }
    }

    fun update(arr: JSONArray) {
      items = arr
      notifyDataSetChanged()
    }
  }

  // ---------------- SimplePagerView（自研页容器：TabLayout 联动 / currentItem 切换） ----------------

  class SimplePagerView(ctx: Context) : FrameLayout(ctx) {
    private var pageViews: List<View> = emptyList()
    var current = 0
      private set
    var lastChangeFromUser = false
      private set
    var onSelectedHook: ((Int) -> Unit)? = null

    /** 页视图由 buildPagerPages 一次性 build（挂 UiNode 树），这里只负责切换显示 */
    fun setup(vs: List<View>, onSelected: (Int) -> Unit) {
      pageViews = vs
      onSelectedHook = onSelected
      if (current >= pageViews.size) current = 0
      show()
    }

    fun setCurrentItemCompat(i: Int, fromUser: Boolean) {
      val ni = i.coerceIn(0, (pageViews.size - 1).coerceAtLeast(0))
      lastChangeFromUser = fromUser
      if (ni == current && childCount > 0) {
        if (fromUser) onSelectedHook?.invoke(ni)
        return
      }
      current = ni
      show()
      if (fromUser) onSelectedHook?.invoke(ni)
    }

    private fun show() {
      removeAllViews()
      val v = pageViews.getOrNull(current) ?: return
      addView(v, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
  }

  // ---------------- Canvas 自绘 View ----------------

  class CanvasView(ctx: Context) : View(ctx) {
    val ops = mutableListOf<JSONObject>()
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val path = android.graphics.Path()

    override fun onDraw(canvas: android.graphics.Canvas) {
      paint.style = android.graphics.Paint.Style.FILL
      paint.strokeWidth = 2f
      for (op in ops) {
        val c = op.optString("color", "")
        if (c.isNotEmpty()) paint.color = color(c)
        val strokeW = op.optDouble("strokeWidth", Double.NaN)
        if (!strokeW.isNaN()) paint.strokeWidth = dp(context, strokeW.toFloat())
        paint.style = if (op.optBoolean("stroke"))
          android.graphics.Paint.Style.STROKE else android.graphics.Paint.Style.FILL
        when (op.optString("c")) {
          "color" -> paint.color = color(op.optString("color", "#FF000000"))
          "clear" -> {
            paint.color = color(op.optString("color", "#00000000"))
            canvas.drawPaint(paint)
          }
          "line" -> canvas.drawLine(op.optDouble("x1", 0.0).toFloat(), op.optDouble("y1", 0.0).toFloat(),
            op.optDouble("x2", 0.0).toFloat(), op.optDouble("y2", 0.0).toFloat(), paint)
          "rect" -> {
            if (paint.style == android.graphics.Paint.Style.STROKE)
              canvas.drawRect(op.optDouble("x", 0.0).toFloat(), op.optDouble("y", 0.0).toFloat(),
                op.optDouble("x", 0.0).toFloat() + op.optDouble("w", 0.0).toFloat(),
                op.optDouble("y", 0.0).toFloat() + op.optDouble("h", 0.0).toFloat(), paint)
            else
              canvas.drawRect(op.optDouble("x", 0.0).toFloat(), op.optDouble("y", 0.0).toFloat(),
                op.optDouble("x", 0.0).toFloat() + op.optDouble("w", 0.0).toFloat(),
                op.optDouble("y", 0.0).toFloat() + op.optDouble("h", 0.0).toFloat(), paint)
          }
          "circle" -> canvas.drawCircle(op.optDouble("x", 0.0).toFloat(), op.optDouble("y", 0.0).toFloat(),
            op.optDouble("r", 0.0).toFloat(), paint)
          "roundRect" -> {
            val rx = dp(context, op.optDouble("r", 8.0).toFloat())
            canvas.drawRoundRect(op.optDouble("x", 0.0).toFloat(), op.optDouble("y", 0.0).toFloat(),
              op.optDouble("x", 0.0).toFloat() + op.optDouble("w", 0.0).toFloat(),
              op.optDouble("y", 0.0).toFloat() + op.optDouble("h", 0.0).toFloat(), rx, rx, paint)
          }
          "arc" -> canvas.drawArc(op.optDouble("x", 0.0).toFloat(), op.optDouble("y", 0.0).toFloat(),
            op.optDouble("x", 0.0).toFloat() + op.optDouble("w", 0.0).toFloat(),
            op.optDouble("y", 0.0).toFloat() + op.optDouble("h", 0.0).toFloat(),
            op.optDouble("start", 0.0).toFloat(), op.optDouble("sweep", 0.0).toFloat(),
            op.optBoolean("useCenter", true), paint)
          "text" -> {
            val ts = op.optDouble("size", Double.NaN)
            if (!ts.isNaN()) paint.textSize = sp(context, ts.toFloat())
            canvas.drawText(op.optString("text", ""), op.optDouble("x", 0.0).toFloat(),
              op.optDouble("y", 0.0).toFloat(), paint)
          }
          "path" -> {
            path.reset()
            val pts = op.optJSONArray("pts")
            if (pts != null && pts.length() >= 4) {
              path.moveTo(pts.optDouble(0, 0.0).toFloat(), pts.optDouble(1, 0.0).toFloat())
              var i = 2
              while (i + 1 < pts.length()) {
                path.lineTo(pts.optDouble(i, 0.0).toFloat(), pts.optDouble(i + 1, 0.0).toFloat())
                i += 2
              }
              if (op.optBoolean("close")) path.close()
              canvas.drawPath(path, paint)
            }
          }
        }
      }
    }
  }
}
