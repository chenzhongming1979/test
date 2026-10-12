package com.autolua.autolua2.extension.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/**
 * 弹窗宿主（独立 overlay 窗 + 全屏 scrim + requestId 闭环）。
 *
 * 不依赖 Activity Dialog（服务上下文不可靠）。类型：
 * - alert   单按钮提示          → action="b1"（默认按钮文案 "确定"）
 * - confirm 双按钮              → buttons[0]/[1] 对应 action
 * - input   多字段输入          → values={字段id:text}
 * - sheet   底部菜单            → action="select", index
 * - custom  完整控件树 + 按钮   → 控件树注册进窗口注册表（win="__dlg_<req>"，node.set/get 可寻址），
 *                               关闭时按 id 收集 values
 *
 * 结果经 dlgResult 回调入事件队列：{"ev":"dialog","req","action","values","index"}
 */
object DialogHost {

  class Session(
    val win: String,
    val req: Long,
    val scrim: FrameLayout,
    val fields: MutableMap<String, EditText> = mutableMapOf(),
    var root: UiNode? = null          // custom 内容树（供 ui.set 寻址与收集）
  )

  /** dp → px */
  private fun dpx(ctx: Context, v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

  private fun panelBg(ctx: Context, topOnly: Boolean): GradientDrawable {
    val d = GradientDrawable()
    d.setColor(Color.WHITE)
    val r = dpx(ctx, 12f)
    d.cornerRadii = if (topOnly) floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) else
      floatArrayOf(r, r, r, r, r, r, r, r)
    return d
  }

  /**
   * 构建并挂窗。必须主线程调用。
   * @param dlg {type,title,msg,buttons,fields,items,content,w,cancelable}
   * @param emit 控件事件上抛（custom 内容树用）
   * @param dlgResult 弹窗结果回调（UiMgrImp 入队）
   * @param onClosed 窗口清理回调（注销 registry）
   */
  fun show(
    ctx: Context, wm: WindowManager, req: Long, dlg: JSONObject,
    emit: (String?, String, JSONObject?) -> Unit,
    dlgResult: (Session, String, JSONObject, Int) -> Unit,
    onClosed: (String) -> Unit
  ): Session {
    val type = dlg.optString("type", "alert").lowercase()
    val win = "__dlg_$req"
    val cancelable = dlg.optBoolean("cancelable", true)

    // ---- scrim 全屏遮罩 ----
    val scrim = FrameLayout(ctx)
    scrim.setBackgroundColor(0x80000000.toInt())
    val lp = WindowManager.LayoutParams()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
      @Suppress("DEPRECATION") lp.type = WindowManager.LayoutParams.TYPE_PHONE
    }
    lp.format = PixelFormat.TRANSLUCENT
    lp.width = ViewGroup.LayoutParams.MATCH_PARENT
    lp.height = ViewGroup.LayoutParams.MATCH_PARENT
    lp.flags = 0 // 弹窗可聚焦（输入框需要软键盘）；scrim 自身拦截全部触摸

    // ---- 面板 ----
    val isSheet = type == "sheet"
    val panel = LinearLayout(ctx)
    panel.orientation = LinearLayout.VERTICAL
    panel.background = panelBg(ctx, isSheet)
    val pad = dpx(ctx, 20f).toInt()
    panel.setPadding(pad, pad, pad, pad)
    val scrW = JsonViewFactory.screenW(ctx)
    val panelW = if (isSheet) ViewGroup.LayoutParams.MATCH_PARENT
    else {
      val w = JsonViewFactory.size(ctx, dlg.opt("w"), true)
      if (w > 0 && w < scrW) w else (scrW * 0.86f).toInt()
    }
    val panelLp = FrameLayout.LayoutParams(panelW, ViewGroup.LayoutParams.WRAP_CONTENT)
    panelLp.gravity = if (isSheet) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    else Gravity.CENTER
    panelLp.topMargin = if (isSheet) 0 else dpx(ctx, 24f).toInt()
    panelLp.bottomMargin = if (isSheet) 0 else dpx(ctx, 24f).toInt()
    scrim.addView(panel, panelLp)

    val session = Session(win, req, scrim)

    // ---- 标题 ----
    val title = dlg.optString("title", "")
    if (title.isNotEmpty()) {
      val tv = TextView(ctx)
      tv.text = title
      tv.setTextColor(0xFF222222.toInt())
      tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
      tv.paintFlags = tv.paintFlags or android.graphics.Paint.FAKE_BOLD_TEXT_FLAG
      panel.addView(tv, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dpx(ctx, 12f).toInt()
      })
    }

    // ---- 消息 ----
    val msg = dlg.optString("msg", "")
    if (msg.isNotEmpty()) {
      val tv = TextView(ctx)
      tv.text = msg
      tv.setTextColor(0xFF555555.toInt())
      tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
      panel.addView(tv, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dpx(ctx, 16f).toInt()
      })
    }

    // ---- 输入字段 ----
    val fields = dlg.optJSONArray("fields")
    if (fields != null) {
      for (i in 0 until fields.length()) {
        val f = fields.optJSONObject(i) ?: continue
        val label = f.optString("label", "")
        if (label.isNotEmpty()) {
          val lt = TextView(ctx)
          lt.text = label
          lt.setTextColor(0xFF666666.toInt())
          lt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
          panel.addView(lt, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dpx(ctx, 4f).toInt()
          })
        }
        val et = EditText(ctx)
        et.setText(f.optString("text", ""))
        et.hint = f.optString("hint", "")
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        when (f.optString("inputType", "text")) {
          "number" -> et.inputType = android.text.InputType.TYPE_CLASS_NUMBER
          "phone" -> et.inputType = android.text.InputType.TYPE_CLASS_PHONE
          "pass", "password" -> et.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
          "multiline" -> et.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        et.setSingleLine(f.optString("inputType", "text") != "multiline")
        val fid = f.optString("id", "field$i")
        session.fields[fid] = et
        panel.addView(et, LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
          bottomMargin = dpx(ctx, 12f).toInt()
        })
      }
    }

    // ---- custom 内容树 ----
    val content = dlg.optJSONObject("content")
    if (content != null) {
      val built = JsonViewFactory.buildNode(ctx, content, emit)
      session.root = built
      val cp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      cp.bottomMargin = dpx(ctx, 12f).toInt()
      panel.addView(built.view, cp)
    }

    // ---- sheet 选项 ----
    var selectIndex = -1
    val items = dlg.optJSONArray("items")
    if (items != null && isSheet) {
      for (i in 0 until items.length()) {
        val row = TextView(ctx)
        row.text = items.optString(i)
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        row.setTextColor(0xFF333333.toInt())
        row.setPadding(0, dpx(ctx, 14f).toInt(), 0, dpx(ctx, 14f).toInt())
        row.gravity = Gravity.CENTER_VERTICAL
        val idx = i
        row.setOnClickListener {
          selectIndex = idx
          dismiss(ctx, wm, session, "select", dlgResult, onClosed, idx)
        }
        panel.addView(row, LinearLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
      }
    }

    // ---- 按钮行 ----
    val buttons = dlg.optJSONArray("buttons")
    val showButtons = buttons != null && buttons.length() > 0 && !isSheet
    if (showButtons) {
      val row = LinearLayout(ctx)
      row.orientation = LinearLayout.HORIZONTAL
      for (i in 0 until buttons!!.length()) {
        val bo = buttons.opt(i)
        var text = ""
        var action = "b${i + 1}"
        when (bo) {
          is JSONObject -> {
            text = bo.optString("text")
            if (bo.has("action") && !bo.isNull("action")) action = bo.optString("action")
          }
          else -> text = bo?.toString() ?: ""
        }
        val btn = Button(ctx)
        btn.text = text
        btn.isAllCaps = false
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        btn.setOnClickListener {
          dismiss(ctx, wm, session, action, dlgResult, onClosed, selectIndex)
        }
        row.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
          marginStart = if (i > 0) dpx(ctx, 10f).toInt() else 0
        })
      }
      panel.addView(row, LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dpx(ctx, 8f).toInt()
      })
    }

    // ---- 交互 ----
    scrim.setOnClickListener {
      if (cancelable) dismiss(ctx, wm, session, "cancel", dlgResult, onClosed, selectIndex)
    }
    // 返回键取消（可聚焦窗口时有效）
    panel.isFocusableInTouchMode = true
    panel.setOnKeyListener { _, keyCode, event ->
      if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && cancelable) {
        dismiss(ctx, wm, session, "cancel", dlgResult, onClosed, selectIndex)
        true
      } else false
    }

    wm.addView(scrim, lp)
    return session
  }

  /** 关闭并回传结果（幂等：面板未挂载则跳过） */
  fun dismiss(
    ctx: Context, wm: WindowManager, s: Session, action: String,
    dlgResult: (Session, String, JSONObject, Int) -> Unit,
    onClosed: (String) -> Unit, index: Int = -1
  ) {
    if (s.scrim.windowToken == null) return
    try { wm.removeView(s.scrim) } catch (t: Throwable) { /* 已被移除 */ }
    val values = JSONObject()
    // 输入字段收集
    for ((fid, et) in s.fields) values.put(fid, et.text.toString())
    // custom 内容树按 id 收集
    s.root?.let { root ->
      collectNode(ctx, root, values)
    }
    onClosed(s.win)
    dlgResult(s, action, values, index)
  }

  private fun collectNode(ctx: Context, n: UiNode, out: JSONObject) {
    n.id?.let {
      val v = JsonViewFactory.collectValues(ctx, n)
      val slim = JSONObject()
      for (k in listOf("text", "checked", "selectedIndex", "selectedItem", "progress", "max",
        "currentItem", "url")) {
        if (v.has(k)) slim.put(k, v.opt(k))
      }
      out.put(it, slim)
    }
    for (c in n.children) collectNode(ctx, c, out)
  }
}
