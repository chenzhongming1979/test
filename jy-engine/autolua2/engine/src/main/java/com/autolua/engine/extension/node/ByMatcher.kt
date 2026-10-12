package com.autolua.engine.extension.node

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

class ByMatcher(private val node:AccessibilityNodeInfo, private val uiSelector: UiSelector, private val one: Boolean = true) {

  // 同一轮匹配内的 getChild 结果缓存：树遍历与 hasChild/and 匹配共享同一份子节点列表，
  // 避免同一子节点被重复跨进程拉取；每轮查找节点均为新实例，identity 键不会跨轮污染。
  private val childCache = java.util.IdentityHashMap<AccessibilityNodeInfo, List<AccessibilityNodeInfo>>()

  /** 子节点列表（单轮匹配内缓存） */
  private fun childrenOf(node:AccessibilityNodeInfo):List<AccessibilityNodeInfo>{
    val cached = childCache[node]
    if(cached != null) return cached
    val list = mutableListOf<AccessibilityNodeInfo>()
    val count = node.childCount
    for(i in 0 until count){
      val child = node.getChild(i)
      if(child != null) list.add(child)
    }
    childCache[node] = list
    return list
  }

  fun find():List<UiObject>{
    val result = mutableListOf<UiObject>()
    find(node, 0,0, result)
    return result
  }

  private fun find(node:AccessibilityNodeInfo, depth:Int,index:Int, result:MutableList<UiObject>){
    if(matchesSelector(uiSelector, node, depth, index, this)){
      result.add(UiObject(node))
      if(one)
        return
    }
    val children = childrenOf(node)
    for(i in children.indices){
      find(children[i], depth + 1,i, result)
      if(one && result.isNotEmpty())
        return
    }
  }


  companion object{
    private fun matchesSelector(selector: UiSelector, node:AccessibilityNodeInfo, depth:Int, index:Int, matcher: ByMatcher):Boolean{
      if((selector.minDepth != null && depth < selector.minDepth!!)
        || (selector.maxDepth != null && depth > selector.maxDepth!!)
        || (selector.index != null && index != selector.index!!)) return false
      if((selector.clickable != null && node.isClickable != selector.clickable!!)
        || (selector.checkable != null && node.isCheckable != selector.checkable!!)
        || (selector.checked != null && node.isChecked != selector.checked!!)
        || (selector.enabled != null && node.isEnabled != selector.enabled!!)
        || (selector.focusable != null && node.isFocusable != selector.focusable!!)
        || (selector.focused != null && node.isFocused != selector.focused!!)
        || (selector.longClickable != null && node.isLongClickable != selector.longClickable!!)
        || (selector.scrollable != null && node.isScrollable != selector.scrollable!!)
        || (selector.selected != null && node.isSelected != selector.selected!!)
        || (selector.visibleToUser != null && node.isVisibleToUser != selector.visibleToUser!!)
        || (selector.editable != null && node.isEditable != selector.editable!!)
        || (selector.password != null && node.isPassword != selector.password!!)) return false
      // drawingOrder 为 API 24+ 属性，低版本直接视为不匹配
      if(selector.drawingOrder != null
        && (Build.VERSION.SDK_INT < Build.VERSION_CODES.N
          || node.drawingOrder != selector.drawingOrder!!)) return false
      if(selector.desc != null && !matchesOrNull(selector.desc, node.contentDescription)) return false
      if(selector.clazz != null && !matchesOrNull(selector.clazz, node.className)) return false
      if(selector.pkg != null && !matchesOrNull(selector.pkg, node.packageName)) return false
      if(selector.res != null && !matchesOrNull(selector.res, node.viewIdResourceName)) return false
      if(selector.text != null && !matchesOrNull(selector.text, node.text)) return false
      // content 系列：text OR contentDescription 任一匹配（两者皆空则不匹配）
      if(!matchesContent(selector, node)) return false
      if(selector.bounds != null || selector.boundsInside != null || selector.boundsContainsX != null){
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if(selector.bounds != null && rect != selector.bounds) return false
        if(selector.boundsInside != null && !selector.boundsInside!!.contains(rect)) return false
        if(selector.boundsContainsX != null
          && !rect.contains(selector.boundsContainsX!!, selector.boundsContainsY!!)) return false
      }
      if(!matchesScreenGeometry(selector, node)) return false
      // and 组合器：主条件全过后，再要求所有子选择器在同一节点上匹配（递归含 hasChild 等内部条件）
      if(!matchesAnd(selector, node, depth, index, matcher)) return false
      return matchesHint(selector, node) && matchesChild(selector, node, depth, matcher)
    }

    private fun matchesHint(selector: UiSelector, node:AccessibilityNodeInfo):Boolean{
      if(Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
        return true
      return (selector.hint == null || matchesOrNull(selector.hint, node.hintText))
    }

    /** 节点属性为 null（无 text/desc 等）时视为不匹配，避免 Matcher NPE */
    private fun matchesOrNull(pattern: java.util.regex.Pattern?, cs: CharSequence?):Boolean{
      if(cs == null) return false
      return pattern!!.matcher(cs).matches()
    }

    /** content 条件：node.text 或 node.contentDescription 任一与 pattern 匹配即通过（text OR desc 语义） */
    private fun matchesContent(selector: UiSelector, node:AccessibilityNodeInfo):Boolean{
      if(selector.content == null) return true
      val text = node.text
      if(text != null && matchesOrNull(selector.content, text)) return true
      val desc = node.contentDescription
      if(desc != null && matchesOrNull(selector.content, desc)) return true
      return false
    }

    /** 屏幕几何条件：screenCenterX/screenCenterY（容差为屏幕尺寸比例）、screenCoverage（节点面积占屏幕面积比例，0-1） */
    private fun matchesScreenGeometry(selector: UiSelector, node:AccessibilityNodeInfo):Boolean{
      if(selector.screenCenterXTol == null && selector.screenCenterYTol == null
        && selector.screenCoverageMin == null) return true
      val rect = android.graphics.Rect()
      node.getBoundsInScreen(rect)
      val metrics = android.content.res.Resources.getSystem().displayMetrics
      val screenW = metrics.widthPixels
      val screenH = metrics.heightPixels
      if(selector.screenCenterXTol != null
        && Math.abs(rect.centerX() - screenW / 2.0) > screenW * selector.screenCenterXTol!!) return false
      if(selector.screenCenterYTol != null
        && Math.abs(rect.centerY() - screenH / 2.0) > screenH * selector.screenCenterYTol!!) return false
      if(selector.screenCoverageMin != null){
        val screenArea = screenW.toLong() * screenH.toLong()
        val nodeArea = rect.width().toLong() * rect.height().toLong()
        // min 默认 0 时要求节点面积 > 0
        if(screenArea <= 0 || nodeArea <= 0) return false
        if(nodeArea.toDouble() / screenArea < selector.screenCoverageMin!!) return false
      }
      return true
    }

    /** and 组合器：所有子选择器均需在同一节点上匹配（递归 matchesSelector，传相同 node/depth/index） */
    private fun matchesAnd(selector: UiSelector, node:AccessibilityNodeInfo, depth:Int, index:Int, matcher: ByMatcher):Boolean{
      val ands = selector.andSels ?: return true
      for(sel in ands){
        if(!matchesSelector(sel, node, depth, index, matcher)) return false
      }
      return true
    }

    private fun matchesChild(selector: UiSelector, node:AccessibilityNodeInfo,depth:Int, matcher: ByMatcher):Boolean{
      if(selector.hasChild == null)
        return true
      // 复用单轮 childCache 的子节点列表，避免 hasChild 匹配放大 getChild 跨进程调用
      val children = matcher.childrenOf(node)
      for(i in children.indices){
        if(matchesSelector(selector.hasChild!!, children[i], depth, i, matcher))
          return true
      }
      return false
    }

  }

}