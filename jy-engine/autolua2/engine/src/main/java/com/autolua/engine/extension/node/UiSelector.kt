package com.autolua.engine.extension.node


import android.annotation.SuppressLint
import com.autolua.engine.base.LuaContext
import com.autolua.engine.base.LuaObjectAdapter
import java.util.regex.Pattern
import androidx.test.uiautomator.util.Patterns;

class UiSelector:LuaObjectAdapter {
  var index:Int? = null
  var maxDepth:Int? = null
  var minDepth:Int? = null
  var clickable:Boolean? = null
  var checkable:Boolean? = null
  var checked:Boolean? = null
  var enabled:Boolean? = null
  var focusable:Boolean? = null
  var focused:Boolean? = null
  var hasChild: UiSelector? = null
  var longClickable:Boolean? = null
  var scrollable:Boolean? = null
  var selected:Boolean? = null
  var visibleToUser:Boolean? = null
  var editable:Boolean? = null

  // bounds(l,t,r,b)：节点范围精确匹配；boundsInside(l,t,r,b)：节点范围完全位于指定区域内
  var bounds:android.graphics.Rect? = null
  var boundsInside:android.graphics.Rect? = null
  // boundsContains(x,y)：点 (x,y) 落在节点范围内
  var boundsContainsX:Int? = null
  var boundsContainsY:Int? = null

  // content 系列：node.text OR node.contentDescription 任一匹配即通过（派大星语义）
  var content:Pattern? = null
  // and(sel) 交集组合器：所有子选择器均需在同一节点上匹配
  var andSels:MutableList<UiSelector>? = null

  // 屏幕几何条件：screenCenterX/screenCenterY 容差（屏幕尺寸比例）、screenCoverage 面积占比（0-1）
  var screenCenterXTol:Double? = null
  var screenCenterYTol:Double? = null
  var screenCoverageMin:Double? = null


  var desc:Pattern? = null
  var clazz:Pattern? = null
  var pkg:Pattern? = null
  var res:Pattern? = null
  var text:Pattern? = null
  var hint :Pattern? = null
  var password:Boolean? = null
  var drawingOrder:Int? = null

  /** 根节点提供器（引擎注册 UiSelector 全局时注入）；无障碍不可用时便捷动作抛错 */
  var rootProvider: (() -> UiObject?)? = null

  private fun rootNode(): UiObject =
    rootProvider?.invoke() ?: throw IllegalStateException("UiAutomator unavailable (selector actions need accessibility)")

  /** 主窗口无结果时在额外窗口（extraRootsProvider，其他窗口）中兜底查找首个命中 */
  private fun findOneMultiWindow(): UiObject? {
    val n = rootNode().find(this, true).firstOrNull()
    if(n != null) return n
    val extras = extraRootsProvider?.invoke() ?: return null
    for(root in extras){
      val hit = root.find(this, true).firstOrNull()
      if(hit != null) return hit
    }
    return null
  }

  /** 多窗口 findAll：主窗口有结果直接返回（不再补查）；无结果时遍历额外窗口合并结果 */
  private fun findAllMultiWindow(): List<UiObject> {
    val main = rootNode().find(this, false)
    if(main.isNotEmpty()) return main
    val merged = mutableListOf<UiObject>()
    val extras = extraRootsProvider?.invoke() ?: return merged
    for(root in extras){
      merged.addAll(root.find(this, false))
    }
    return merged
  }

  /** 轮询查找单个：每轮重新取 root（含额外窗口兜底）查找；timeout<=0 表示无限等；超时返回 null。
      等待期间根节点瞬时不可用（root 为 null 抛错）视为本轮未命中，继续轮询 */
  private fun pollFindOne(timeout: Long): UiObject? {
    val deadline = if(timeout > 0) System.currentTimeMillis() + timeout else Long.MAX_VALUE
    while(true){
      val n = try { findOneMultiWindow() } catch (t: Throwable) { null }
      if(n != null) return n
      if(System.currentTimeMillis() >= deadline) return null
      Thread.sleep(50)
    }
  }

  /** 轮询查找全部：每轮重新取 root（含额外窗口兜底）查找；timeout<=0 表示无限等；超时返回空表 */
  private fun pollFindAll(timeout: Long): List<UiObject> {
    val deadline = if(timeout > 0) System.currentTimeMillis() + timeout else Long.MAX_VALUE
    while(true){
      val result = try { findAllMultiWindow() } catch (t: Throwable) { emptyList<UiObject>() }
      if(result.isNotEmpty()) return result
      if(System.currentTimeMillis() >= deadline) return emptyList()
      Thread.sleep(50)
    }
  }

  /** 全局 waitFor(sel, timeout) 用：带超时轮询等待查找单个节点；timeout<=0 视为 10s 默认；超时返回 null */
  fun findOneWait(timeout: Long): UiObject? =
    pollFindOne(if(timeout > 0) timeout else 10000L)

  override fun index(L: LuaContext): Int {
    return 2
  }

  /** 属性赋值糖（__newindex）：sel.text = "登录" ⇔ sel:text("登录")，sel.clickable = false ⇔ sel:clickable(false)。
      直接写字段（字段与 call() 分支同源）；支持字符串五系（text/desc/content/clazz/id(res)/pkg/hint）、
      布尔态（Boolean? 语义，false=必须不满足）、index/drawingOrder；其余键抛中文错误 */
  override fun newIndex(L: LuaContext): Int {
    // 先查类型再 toString：JNI 对非字符串键直接调 toString 会抛底层类型错（而非返回 null）
    if(L.type(2) != LuaContext.ValueType.STRING){
      throw IllegalArgumentException("UiSelector 属性赋值的键必须是字符串")
    }
    val key = L.toString(2) ?: throw IllegalArgumentException("UiSelector 属性赋值的键必须是字符串")
    when(L.type(3)){
      LuaContext.ValueType.STRING -> {
        val v = L.toString(3) ?: return 0
        val p = when {
          key.endsWith("Contains") -> Patterns.contains(v)
          key.endsWith("StartsWith") -> Patterns.startsWith(v)
          key.endsWith("EndsWith") -> Patterns.endsWith(v)
          key.endsWith("Matches") -> try {
            Pattern.compile(v)
          } catch(t: Throwable) {
            throw IllegalArgumentException("属性 '$key' 的值不是合法正则: ${t.message}")
          }
          else -> Pattern.compile(Pattern.quote(v))
        }
        val field = if(key.startsWith("id")) "res"
                    else key.removeSuffix("Contains").removeSuffix("StartsWith")
                            .removeSuffix("EndsWith").removeSuffix("Matches")
        when(field){
          "text" -> text = p
          "desc" -> desc = p
          "content" -> content = p
          "clazz" -> clazz = p
          "res" -> res = p
          "pkg" -> pkg = p
          "hint" -> hint = p
          else -> throw IllegalArgumentException("UiSelector 不支持字符串属性赋值 '$key'（可用: text/desc/content/clazz/id/pkg/hint 五系）")
        }
      }
      LuaContext.ValueType.BOOLEAN -> {
        val v = L.toBoolean(3)
        when(key){
          "clickable" -> clickable = v
          "checkable" -> checkable = v
          "checked" -> checked = v
          "enabled" -> enabled = v
          "focusable" -> focusable = v
          "focused" -> focused = v
          "longClickable" -> longClickable = v
          "scrollable" -> scrollable = v
          "selected" -> selected = v
          "visibleToUser" -> visibleToUser = v
          "editable" -> editable = v
          "password" -> password = v
          else -> throw IllegalArgumentException("UiSelector 布尔属性赋值不支持 '$key'（可用: clickable/checkable/checked/enabled/focusable/focused/longClickable/scrollable/selected/visibleToUser/editable/password）")
        }
      }
      LuaContext.ValueType.NUMBER -> {
        when(key){
          "index" -> index = L.toLong(3).toInt()
          "drawingOrder" -> drawingOrder = L.toLong(3).toInt()
          else -> throw IllegalArgumentException("UiSelector 数值属性赋值不支持 '$key'（可用: index/drawingOrder）")
        }
      }
      else -> throw IllegalArgumentException("UiSelector 属性 '$key' 赋值仅支持字符串/布尔/数值")
    }
    return 0
  }

  private fun optTrue(L: LuaContext,index:Int):Boolean{
    val t = L.type(index)
    if(t == LuaContext.ValueType.BOOLEAN){
      return L.toBoolean(index)
    }
    return true
  }

  /** 可选 long 参数：无参/nil 时返回默认值 */
  private fun optLong(L: LuaContext,index:Int,def:Long):Long{
    val t = L.type(index)
    if(t != LuaContext.ValueType.NONE && t != LuaContext.ValueType.NIL){
      return L.toLong(index)
    }
    return def
  }

  /** 可选 double 参数：无参/nil 时返回默认值 */
  private fun optDouble(L: LuaContext,index:Int,def:Double):Double{
    val t = L.type(index)
    if(t != LuaContext.ValueType.NONE && t != LuaContext.ValueType.NIL){
      return L.toDouble(index)
    }
    return def
  }

  @SuppressLint("RestrictedApi")
  override fun call(L: LuaContext, name: String): Int {
    when(name){
      "checkable" ->{
        checkable = optTrue(L,2)
      }
      "checked" ->{
        checked = optTrue(L,2)
      }
      "clickable" ->{
        clickable = optTrue(L,2)
      }
      "depth" ->{
        minDepth = L.toLong(2).toInt()
        if(L.isInteger(3)){
          maxDepth = L.toLong(3).toInt()
        }else{
          maxDepth = minDepth
        }
      }
      "desc" ->{
        desc = Pattern.compile(Pattern.quote(L.toString(2)!!))
      }
      "descContains" ->{
        desc = Patterns.contains(L.toString(2)!!)
      }
      "descStartsWith" ->{
        desc = Patterns.startsWith(L.toString(2)!!)
      }
      "descEndsWith" ->{
        desc = Patterns.endsWith(L.toString(2)!!)
      }
      "descMatches" ->{
        desc = Pattern.compile(L.toString(2)!!)
      }
      "enabled" ->{
        enabled = optTrue(L,2)
      }
      "focusable" ->{
        focusable = optTrue(L,2)
      }
      "focused" ->{
        focused = optTrue(L,2)
      }
      "hasChild" ->{
        hasChild = L.toLuaObjectAdapter(2) as UiSelector
      }
      "longClickable" ->{
        longClickable = optTrue(L,2)
      }
      "maxDepth" ->{
        maxDepth = L.toLong(2).toInt()
      }
      "minDepth" ->{
        minDepth = L.toLong(2).toInt()
      }
      "pkg" ->{
        pkg = Pattern.compile(Pattern.quote(L.toString(2)!!))
      }
      "pkgMatches" ->{
        pkg = Pattern.compile(L.toString(2)!!)
      }
      "pkgContains" ->{
        pkg = Patterns.contains(L.toString(2)!!)
      }
      "pkgStartsWith" ->{
        pkg = Patterns.startsWith(L.toString(2)!!)
      }
      "pkgEndsWith" ->{
        pkg = Patterns.endsWith(L.toString(2)!!)
      }
      "res" ->{
        res = Pattern.compile(Pattern.quote(L.toString(2)!!))
      }
      "resMatches" ->{
        res = Pattern.compile(L.toString(2)!!)
      }
      // id 系列增强（懒人/派大星 idContains/idStartsWith/idEndsWith）→ res Pattern
      "idContains" ->{
        res = Patterns.contains(L.toString(2)!!)
      }
      "idStartsWith" ->{
        res = Patterns.startsWith(L.toString(2)!!)!!
      }
      "idEndsWith" ->{
        res = Patterns.endsWith(L.toString(2)!!)!!
      }
      "id" ->{
        res = Pattern.compile(Pattern.quote(L.toString(2)!!))
      }
      "idMatches" ->{
        res = Pattern.compile(L.toString(2)!!)
      }
      // bounds(l,t,r,b) / boundsInside(l,t,r,b)
      "bounds" ->{
        bounds = android.graphics.Rect(
          L.toLong(2).toInt(), L.toLong(3).toInt(),
          L.toLong(4).toInt(), L.toLong(5).toInt())
      }
      "boundsInside" ->{
        boundsInside = android.graphics.Rect(
          L.toLong(2).toInt(), L.toLong(3).toInt(),
          L.toLong(4).toInt(), L.toLong(5).toInt())
      }
      // boundsContains(x,y)：点包含（Rect.contains(x,y) 语义）
      "boundsContains" ->{
        boundsContainsX = L.toLong(2).toInt()
        boundsContainsY = L.toLong(3).toInt()
      }
      // 屏幕几何条件（可选参数，无参取默认值）
      "screenCenterX" ->{
        screenCenterXTol = optDouble(L, 2, 0.0)
      }
      "screenCenterY" ->{
        screenCenterYTol = optDouble(L, 2, 0.0)
      }
      "screenCoverage" ->{
        screenCoverageMin = optDouble(L, 2, 0.0)
      }
      "visibleToUser" ->{
        visibleToUser = optTrue(L,2)
      }
      "editable" ->{
        editable = optTrue(L,2)
      }
      "scrollable" ->{
        scrollable = optTrue(L,2)
      }
      "selected" ->{
        selected = optTrue(L,2)
      }

      "text" ->{
        text = Pattern.compile(Pattern.quote(L.toString(2)!!))
      }
      "textContains" ->{
        text = Patterns.contains(L.toString(2)!!)
      }
      "textMatches" ->{
        text = Pattern.compile(L.toString(2)!!)
      }
      "textStartsWith" ->{
        text = Patterns.startsWith(L.toString(2)!!)
      }
      "textEndsWith" ->{
        text = Patterns.endsWith(L.toString(2)!!)
      }
      // content 系列：text OR contentDescription 任一匹配（派大星语义）
      "content" ->{
        content = Pattern.compile(Pattern.quote(L.toString(2)!!))
      }
      "contentContains" ->{
        content = Patterns.contains(L.toString(2)!!)
      }
      "contentStartsWith" ->{
        content = Patterns.startsWith(L.toString(2)!!)
      }
      "contentEndsWith" ->{
        content = Patterns.endsWith(L.toString(2)!!)
      }
      "contentMatches" ->{
        content = Pattern.compile(L.toString(2)!!)
      }
      // and(sel) 交集组合器：可链式叠加多个
      "and" ->{
        val sel = L.toLuaObjectAdapter(2) as UiSelector
        if(andSels == null) andSels = mutableListOf()
        andSels!!.add(sel)
      }
      // and 是 Lua 保留字，冒号语法 sel:and(x) 非法；andSel 为等价别名
      "andSel" ->{
        val sel = L.toLuaObjectAdapter(2) as UiSelector
        if(andSels == null) andSels = mutableListOf()
        andSels!!.add(sel)
      }
      "clazz" ->{
        clazz = Pattern.compile(Pattern.quote(L.toString(2)!!))
      }
      "clazzMatches" ->{
        clazz = Pattern.compile(L.toString(2)!!)
      }
      "clazzContains" ->{
        clazz = Patterns.contains(L.toString(2)!!)
      }
      "clazzStartsWith" ->{
        clazz = Patterns.startsWith(L.toString(2)!!)
      }
      "clazzEndsWith" ->{
        clazz = Patterns.endsWith(L.toString(2)!!)
      }
      "hint"->{
        hint = Pattern.compile(Pattern.quote(L.toString(2)!!))
      }
      "hintContains" ->{
        hint = Patterns.contains(L.toString(2)!!)
      }
      "hintMatches" ->{
        hint = Pattern.compile(L.toString(2)!!)
      }
      "hintStartsWith" ->{
        hint = Patterns.startsWith(L.toString(2)!!)
      }
      "hintEndsWith" ->{
        hint = Patterns.endsWith(L.toString(2)!!)
      }
      "index" ->{
        index = L.toLong(2).toInt()
      }
      "password" ->{
        password = optTrue(L,2)
      }
      "drawingOrder" ->{
        drawingOrder = L.toLong(2).toInt()
      }
      // ---------------- 便捷动作（懒人 sel:xxx() 语义；主窗口无结果时兜底其他窗口） ----------------
      "findOne" ->{
        val timeout = optLong(L, 2, 0L)
        val n = if(timeout > 0) pollFindOne(timeout) else findOneMultiWindow()
        if(n != null) L.push(n) else L.pushNil()
        return 1
      }
      "findAll" ->{
        val timeout = optLong(L, 2, 0L)
        val result = if(timeout > 0) pollFindAll(timeout) else findAllMultiWindow()
        L.pushValue(result.toTypedArray(), Array<UiObject>::class.java)
        return 1
      }
      "findOnce" ->{
        val i = L.toLong(2).toInt()
        val result = findAllMultiWindow()
        if(i >= 0 && i < result.size) L.push(result[i]) else L.pushNil()
        return 1
      }
      "exists" ->{
        L.push(findOneMultiWindow() != null)
        return 1
      }
      "click" ->{
        val n = findOneMultiWindow()
        val ok = n != null && n.click()
        L.push(ok)
        return 1
      }
      "longClick" ->{
        val n = findOneMultiWindow()
        val ok = n != null && n.longClick()
        L.push(ok)
        return 1
      }
      "setText" ->{
        val t = L.toString(2)
        val n = findOneMultiWindow()
        val ok = n != null && n.setText(t ?: "")
        L.push(ok)
        return 1
      }
      "untilFindOne" ->{
        // 无限轮询（50ms）直到找到，永不返回 nil（脚本侧自行避免死等）
        val n = pollFindOne(0L)
        if(n != null) L.push(n) else L.pushNil()
        return 1
      }
      "waitFor" ->{
        // 等待存在：无参=无限等直到找到返回 true；有参超时返回 false
        val timeout = optLong(L, 2, 0L)
        val n = pollFindOne(timeout)
        L.push(n != null)
        return 1
      }
      else ->{
        throw IllegalArgumentException("Unknown method: $name")
      }
    }
    L.pushValue(1)
    return 1
  }

  /** 调用糖（__call）：sel(...) ⇔ sel:findOne(...)——可选 timeout 参数（ms，缺省 0 即时查找），返回 UiObject 或 nil */
  override fun invoke(L: LuaContext): Int = call(L, "findOne")

  override fun release(L: LuaContext) {
  }

  companion object {
    /** 引擎注册时注入：额外窗口 root 列表（其他窗口），便捷动作在主窗口无结果时兜底查找 */
    @JvmStatic var extraRootsProvider: (() -> List<UiObject>)? = null
  }

}