package com.autolua.engine.extension.node

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.autolua.engine.base.LuaContext
import com.autolua.engine.base.LuaObjectAdapter

class UiObject(val node: AccessibilityNodeInfo):LuaObjectAdapter   {
  private var isRecycled = false
  override fun index(L: LuaContext): Int {
    val key = L.toString(2);
    when(key!!){
      "clazz" ->{
        L.push(node.className.toString())
      }
      "text" ->{
        L.push(node.text.toString())
      }
      "desc" ->{
        L.push(node.contentDescription.toString())
      }
      "pkg" ->{
        L.push(node.packageName.toString())
      }
      "res" ->{
        L.push(node.viewIdResourceName.toString())
      }
      "bounds" ->{
        val rect = Rect()
        node.getBoundsInScreen(rect)
        L.createTable(4,0)
        L.push(1L)
        L.push(rect.left.toLong())
        L.setTable(-3)
        L.push(2L)
        L.push(rect.top.toLong())
        L.setTable(-3)
        L.push(3L)
        L.push(rect.right.toLong())
        L.setTable(-3)
        L.push(4L)
        L.push(rect.bottom.toLong())
        L.setTable(-3)
      }
      "childCount" ->{
        L.push(node.childCount.toLong())
      }
      "child" ->{
        return 2
      }
      "parent" ->{
        val parent = node.parent
        if(parent != null){
          L.push(UiObject(parent))
        }else{
          L.pushNil()
        }
      }
      "clickable" ->{
        L.push(node.isClickable)
      }
      "checkable" ->{
        L.push(node.isCheckable)
      }
      "checked" ->{
        L.push(node.isChecked)
      }
      "focusable" ->{
        L.push(node.isFocusable)
      }
      "focused" ->{
        L.push(node.isFocused)
      }
      "scrollable" ->{
        L.push(node.isScrollable)
      }
      "selected" ->{
        L.push(node.isSelected)
      }
      "enabled" ->{
        L.push(node.isEnabled)
      }
      "editable" ->{
        L.push(node.isEditable)
      }
      "visibleToUser" ->{
        L.push(node.isVisibleToUser)
      }
      "longClickable" ->{
        L.push(node.isLongClickable)
      }
      "password" ->{
        L.push(node.isPassword)
      }
      // 父容器内索引（API 24+，低版本返回 -1）
      "drawingOrder" ->{
        if(android.os.Build.VERSION.SDK_INT >= 24) L.push(node.drawingOrder.toLong())
        else L.push(-1L)
      }
      "boundsInParent" ->{
        val rect = Rect()
        node.getBoundsInParent(rect)
        L.createTable(4,0)
        L.push(1L); L.push(rect.left.toLong()); L.setTable(-3)
        L.push(2L); L.push(rect.top.toLong()); L.setTable(-3)
        L.push(3L); L.push(rect.right.toLong()); L.setTable(-3)
        L.push(4L); L.push(rect.bottom.toLong()); L.setTable(-3)
      }
      "depth" ->{
        return 2
      }
      "childs" ->{
        return 2
      }
      "findFocus" ->{
        return 2
      }
      "setText" ->{
        return 2
      }
      "recycle" ->{
        return 2
      }
      "find" ->{
        return 2
      }
      "findOne" ->{
        return 2
      }
      // 树遍历 / 几何派生方法名 → index 返回 2 生成方法闭包（经 call() 分发执行）
      "sibling","nextSibling","previousSibling","siblings",
      "firstChild","lastChild","indexInParent","hasChildren",
      "centerX","centerY","width","height","center","point" ->{
        return 2
      }
      // 动作方法名 → index 返回 2 生成方法闭包（Lua 侧 nd:click() 形态，经 call() 分发执行）
      "click","longClick","scrollForward","scrollBackward","scrollUp","scrollDown",
      "scrollLeft","scrollRight","focus","clearFocus","select","copy","paste","cut",
      "collapse","expand","contextClick","clearSelection","setSelection","dismiss",
      "show","accessibilityFocus","clearAccessibilityFocus","pageUp","pageDown",
      "pageLeft","pageRight","imeEnter","showTooltip","hideTooltip","scrollTo",
      "setProgress","performAction","nextAtMovementGranularity",
      "previousAtMovementGranularity","nextHtmlElement","previousHtmlElement" ->{
        return 2
      }

      else ->{
        L.pushNil()
      }
    }
    return 1
  }

  /** 属性赋值糖（__newindex）：node.text = "hello" ⇔ node:setText("hello")；其余键抛中文错误 */
  override fun newIndex(L: LuaContext): Int {
    val key = L.toString(2)
    if(key == "text"){
      val v = L.toString(3)
      setText(v ?: "")
    }else{
      throw IllegalArgumentException("UiObject 不支持属性赋值 '$key'（仅支持 text，等价 setText）")
    }
    return 0
  }

  override fun call(L: LuaContext, name: String): Int {
    when(name){
      "child" ->{
        val index = L.toLong(2).toInt()
        val child = child(index)
        if(child == null) {
          L.pushNil()
        }else{
          L.push(child)
        }
      }
      "depth" ->{
        // node:depth() 树深度：沿父链走到根的层数
        var d = 0
        var p = node.parent
        while(p != null){ d++; p = p.parent }
        L.push(d.toLong())
      }
      "childs" ->{
        // node:childs() 返回全部子节点数组
        val count = node.childCount
        val list = ArrayList<UiObject>(count)
        for(i in 0 until count){
          child(i)?.let { list.add(it) }
        }
        L.pushValue(list.toTypedArray(), Array<UiObject>::class.java)
      }
      // ---------------- 树遍历 ----------------
      "sibling" ->{
        val n = sibling(L.toLong(2).toInt())
        if(n == null) L.pushNil() else L.push(n)
      }
      "nextSibling" ->{
        val n = sibling(1)
        if(n == null) L.pushNil() else L.push(n)
      }
      "previousSibling" ->{
        val n = sibling(-1)
        if(n == null) L.pushNil() else L.push(n)
      }
      "siblings" ->{
        // node:siblings() 除自己外的全部兄弟节点数组
        val list = siblings()
        L.pushValue(list.toTypedArray(), Array<UiObject>::class.java)
      }
      "firstChild" ->{
        val c = child(0)
        if(c == null) L.pushNil() else L.push(c)
      }
      "lastChild" ->{
        val count = node.childCount
        val c = if(count > 0) child(count - 1) else null
        if(c == null) L.pushNil() else L.push(c)
      }
      "indexInParent" ->{
        L.push(indexInParent().toLong())
      }
      "hasChildren" ->{
        L.push(node.childCount > 0)
      }
      // ---------------- 几何派生（bounds 为 getBoundsInScreen） ----------------
      "centerX" ->{
        L.push(boundsInScreen().centerX().toLong())
      }
      "centerY" ->{
        L.push(boundsInScreen().centerY().toLong())
      }
      "width" ->{
        L.push(boundsInScreen().width().toLong())
      }
      "height" ->{
        L.push(boundsInScreen().height().toLong())
      }
      "center","point" ->{
        // node:center() / node:point() 返回两个值 x,y
        val rect = boundsInScreen()
        L.push(rect.centerX().toLong())
        L.push(rect.centerY().toLong())
        return 2
      }
      "findFocus" ->{
        val focus = L.toLong(2).toInt()
        val child = findFocus(focus)
        if(child == null){
          L.pushNil()
        }else{
          L.push(child)
        }
      }
      "setText" ->{
        val text = L.toString(2)
        setText(text!!)
      }
      "recycle" ->{
        recycle()
      }
      "find"->{
        val selector = L.toLuaObjectAdapter(2) as UiSelector
        val one =false
        val result = find(selector, one)
        L.pushValue(result.toTypedArray(),Array<UiObject>::class.java)
      }
      "findOne"->{
        val selector = L.toLuaObjectAdapter(2) as UiSelector
        val one =true
        val result = find(selector, one)
        if(result.isNotEmpty()){
          L.push(result[0])
        }else{
          L.pushNil()
        }
      }
      // ---------------- 节点动作（AccessibilityNodeInfo ACTION 封装） ----------------
      // 注：API 23+ 动作的 int 常量在框架主类上不存在，定义在嵌套类 AccessibilityAction
      //     的静态实例上（.id 取动作 id）；旧 int 常量编译期内联，低版本 performAction 返回 false
      "click" -> pushAction(L, AccessibilityNodeInfoCompat.ACTION_CLICK)
      "longClick" -> pushAction(L, AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
      "scrollForward" -> pushAction(L, AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD)
      "scrollBackward" -> pushAction(L, AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD)
      "scrollUp" -> pushAction(L, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id)
      "scrollDown" -> pushAction(L, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id)
      "scrollLeft" -> pushAction(L, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id)
      "scrollRight" -> pushAction(L, AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id)
      "focus" -> pushAction(L, AccessibilityNodeInfoCompat.ACTION_FOCUS)
      "clearFocus" -> pushAction(L, AccessibilityNodeInfoCompat.ACTION_CLEAR_FOCUS)
      "select" -> pushAction(L, AccessibilityNodeInfoCompat.ACTION_SELECT)
      "copy" -> pushAction(L, AccessibilityNodeInfoCompat.ACTION_COPY)
      "paste" -> pushAction(L, AccessibilityNodeInfoCompat.ACTION_PASTE)
      "cut" -> pushAction(L, AccessibilityNodeInfoCompat.ACTION_CUT)
      "collapse" -> pushAction(L, AccessibilityNodeInfoCompat.ACTION_COLLAPSE)
      "expand" -> pushAction(L, AccessibilityNodeInfoCompat.ACTION_EXPAND)
      "contextClick" -> pushAction(L, AccessibilityNodeInfo.AccessibilityAction.ACTION_CONTEXT_CLICK.id)
      "clearSelection" -> pushAction(L, AccessibilityNodeInfoCompat.ACTION_CLEAR_SELECTION)
      "dismiss" -> pushAction(L, AccessibilityNodeInfo.ACTION_DISMISS)
      "show" -> pushAction(L, AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
      "accessibilityFocus" -> pushAction(L, AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS)
      "clearAccessibilityFocus" -> pushAction(L, AccessibilityNodeInfoCompat.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
      "pageUp" -> pushAction(L, AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_UP.id)
      "pageDown" -> pushAction(L, AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_DOWN.id)
      "pageLeft" -> pushAction(L, AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT.id)
      "pageRight" -> pushAction(L, AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT.id)
      "imeEnter" -> pushAction(L, AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
      "showTooltip" -> pushAction(L, AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_TOOLTIP.id)
      "hideTooltip" -> pushAction(L, AccessibilityNodeInfo.AccessibilityAction.ACTION_HIDE_TOOLTIP.id)
      "setSelection" -> {
        // node:setSelection(start, end)
        val args = android.os.Bundle()
        args.putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_START_INT, L.toLong(2).toInt())
        args.putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_END_INT, L.toLong(3).toInt())
        L.push(node.performAction(AccessibilityNodeInfoCompat.ACTION_SET_SELECTION, args))
      }
      "scrollTo" -> {
        // node:scrollTo(row, col)
        val args = android.os.Bundle()
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, L.toLong(2).toInt())
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, L.toLong(3).toInt())
        L.push(node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id, args))
      }
      "setProgress" -> {
        // node:setProgress(value 0.0~1.0)
        val args = android.os.Bundle()
        args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, L.toDouble(2).toFloat())
        L.push(node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, args))
      }
      "performAction" -> {
        // node:performAction(action) —— 无参 ACTION 透传
        L.push(node.performAction(L.toLong(2).toInt()))
      }
      "nextAtMovementGranularity" -> {
        L.push(node.performAction(AccessibilityNodeInfoCompat.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, granularityArgs(L)))
      }
      "previousAtMovementGranularity" -> {
        L.push(node.performAction(AccessibilityNodeInfoCompat.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, granularityArgs(L)))
      }
      "nextHtmlElement" -> {
        L.push(node.performAction(AccessibilityNodeInfoCompat.ACTION_NEXT_HTML_ELEMENT, htmlElementArgs(L)))
      }
      "previousHtmlElement" -> {
        L.push(node.performAction(AccessibilityNodeInfoCompat.ACTION_PREVIOUS_HTML_ELEMENT, htmlElementArgs(L)))
      }
      else ->{
        L.pushNil()
      }
    }
    return 1
  }

  /** 执行无参 ACTION 并回传布尔结果 */
  private fun pushAction(L: LuaContext, action: Int) {
    L.push(node.performAction(action))
  }

  private fun granularityArgs(L: LuaContext): android.os.Bundle {
    val args = android.os.Bundle()
    args.putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT, L.toLong(2).toInt())
    return args
  }

  private fun htmlElementArgs(L: LuaContext): android.os.Bundle {
    val args = android.os.Bundle()
    args.putString(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_HTML_ELEMENT_STRING, L.toString(2))
    return args
  }

  private fun recycle(){
    if(!isRecycled){
      node.recycle()
      isRecycled = true
    }
  }

  /** 调用糖（__call）：node() 刷新自身并返回 self（refresh 失败=节点已失效，抛错提示重查）；node(i) ⇔ child(i)，越界 nil */
  override fun invoke(L: LuaContext): Int {
    return when(L.type(2)){
      LuaContext.ValueType.NONE, LuaContext.ValueType.NIL -> {
        if(!node.refresh()){
          throw IllegalStateException("节点已失效（refresh 失败），请重新查找后再操作")
        }
        L.pushValue(1)
        1
      }
      else -> call(L, "child")
    }
  }

  override fun release(L: LuaContext) {
    recycle()
  }


  fun child(index:Int):UiObject?{
    val child = node.getChild(index)
    return if(child != null){
      UiObject(child)
    }else{
      null
    }
  }

  fun findFocus(focus:Int):UiObject?{
    val child = node.findFocus(focus)
    return if(child != null){
      UiObject(child)
    }else{
      null
    }
  }

  fun setText(text:String):Boolean{
    val b = Bundle()
    b.putCharSequence(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    return node.performAction(AccessibilityNodeInfoCompat.ACTION_SET_TEXT, b)
  }

  /** Kotlin 侧便捷动作（Lua 侧走 call 分发） */
  fun click():Boolean = node.performAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
  fun longClick():Boolean = node.performAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)

  fun find(selector: UiSelector, one:Boolean):List<UiObject>{
    val matcher = ByMatcher(node,selector, one)
    return matcher.find()
  }

  // ---------------- 树遍历 / 几何派生（Kotlin 便捷 API，Lua 侧经 call 分发） ----------------

  /** 节点在屏幕中的范围 */
  private fun boundsInScreen(): Rect {
    val rect = Rect()
    node.getBoundsInScreen(rect)
    return rect
  }

  /** 兄弟节点：经 parent 定位自身索引后取 idx+offset 的子节点；无 parent 或越界返回 null */
  fun sibling(offset:Int):UiObject?{
    val parent = node.parent ?: return null
    val selfIndex = indexInParent(parent)
    if(selfIndex < 0) return null
    val target = selfIndex + offset
    if(target < 0 || target >= parent.childCount) return null
    return child(target)
  }

  /** 除自己外的全部兄弟节点 */
  fun siblings():List<UiObject>{
    val parent = node.parent ?: return emptyList()
    val result = ArrayList<UiObject>()
    for(i in 0 until parent.childCount){
      val c = parent.getChild(i) ?: continue
      if(c.equals(node)) continue
      result.add(UiObject(c))
    }
    return result
  }

  /** 在父节点中的索引（AccessibilityNodeInfo.equals 比对），无 parent 或找不到返回 -1 */
  fun indexInParent():Int{
    val parent = node.parent ?: return -1
    return indexInParent(parent)
  }

  private fun indexInParent(parent:AccessibilityNodeInfo):Int{
    val count = parent.childCount
    for(i in 0 until count){
      val c = parent.getChild(i)
      if(c != null && c.equals(node)) return i
    }
    return -1
  }

}