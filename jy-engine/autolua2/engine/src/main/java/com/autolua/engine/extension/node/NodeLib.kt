package com.autolua.engine.extension.node

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.autolua.engine.accessibility.AutoLuaAccessibilityService

/**
 * 节点增强库（懒人 keepNode 语义 + 多窗口兜底 + XML 树导出 + 无障碍开关）。
 *
 * 由 AutoLuaEngineOnLocal 创建 LuaContext 时接线：
 * - [rootGetter] 注入 uiAutomator::getRootInActiveWindow（主 root 来源）；
 * - [automator] 注入 UiAutomator 实例（[otherRoots] 经其 windowRoots() 取其他窗口）；
 * - 全局函数 keepNode/releaseNode/updateNode/getNodeXml/openAccessibility/isAccServiceOk 直达本单例。
 */
object NodeLib {

  /** 主 root 提供器：注册时注入 uiAutomator::getRootInActiveWindow */
  @JvmStatic var rootGetter: (() -> UiObject?)? = null

  /** UiAutomator 实例引用（otherRoots 经其 windowRoots() 取其他窗口） */
  @JvmStatic var automator: UiAutomator? = null

  /** 工程根目录（AutoLuaEngineOnLocal.setRootDir 注入，与 Lua 全局 ROOT_DIR 同值）；
      saveNode 缺省落盘目录，null 时退 java.io.tmpdir 或 /data/local/tmp */
  @JvmStatic var rootDir: String? = null

  // ---------------- 节点获取模式（懒人 setNodeMode 轻量语义） ----------------

  /** 节点获取模式：0=全部节点（默认，含不重要视图） 1=简单模式（仅重要视图） */
  @JvmStatic var nodeMode: Int = 0

  /** getNodeXml 的 includeInvisible 缺省值（setNodeMode 第二参更新；true=包含不可见节点） */
  @JvmStatic var enableInvisible: Boolean = false

  // ---------------- 懒人节点缓存（keepNode 语义） ----------------

  /** keepNode 锁定的 root 快照；非空时后续查找全部走缓存（锁定当前界面） */
  private var cachedRoot: UiObject? = null

  /** 缓存当前 root 快照（懒人语义：锁定当前界面，后续查找走缓存） */
  fun keepNode() {
    cachedRoot = rootGetter?.invoke() ?: serviceRoot()
  }

  /** 清缓存，恢复实时取 root */
  fun releaseNode() {
    cachedRoot = null
  }

  /** 重抓 root 刷新缓存 */
  fun updateNode() {
    cachedRoot = rootGetter?.invoke() ?: serviceRoot()
  }

  /** 缓存优先；无缓存时实时取 root（rootGetter 未注入时兜底无障碍服务静态实例） */
  fun cachedOrFreshRoot(): UiObject? = cachedRoot ?: rootGetter?.invoke() ?: serviceRoot()

  /** 已连接的无障碍服务实例（app 进程兜底通道；root 引擎进程服务不在本进程） */
  private fun serviceInstance(): AutoLuaAccessibilityService? = try {
    AutoLuaAccessibilityService.getInstance()?.takeIf { AutoLuaAccessibilityService.isRunning() }
  } catch (t: Throwable) {
    null
  }

  /** app 进程兜底：无障碍服务直接取活动窗口 root（rootGetter 未注入时） */
  private fun serviceRoot(): UiObject? = try {
    serviceInstance()?.rootInActiveWindow?.let { UiObject(it) }
  } catch (t: Throwable) {
    null
  }

  /** 除主窗口（活动窗口）外的其他窗口 root（多窗口兜底）；优先 UiAutomator 通道，app 进程退服务 windows；任何异常空安全 */
  fun otherRoots(): List<UiObject> {
    return try {
      val viaAutomator = automator?.windowRoots()
      if (!viaAutomator.isNullOrEmpty()) viaAutomator
      else {
        val svc = serviceInstance() ?: return emptyList()
        val active = svc.rootInActiveWindow
        svc.windows.mapNotNull { w -> w.root?.takeIf { !it.equals(active) } }
          .map { UiObject(it) }
      }
    } catch (t: Throwable) {
      emptyList()
    }
  }

  // ---------------- XML 树导出 ----------------

  /**
   * 导出当前节点树 XML（下游 VSCode 插件按此契约解析，字段名勿改）：
   * 根元素 <nodes w=".." h="..">（Resources.getSystem().displayMetrics），
   * 节点元素 <n> 属性（布尔 0/1）：i=父内索引 d=深度(root为0) c/t/de/id/p=类名/文本/desc/id/包名
   * b/bp=屏幕/父内 bounds("l,t,r,b") ck/cc/ch/en/fo/fc/sc/lc/se/ed/vt/pw=布尔态 do=drawingOrder(API<24 写 -1)。
   * includeInvisible=false 时 vt=0 的节点整个子树跳过（懒人语义）。
   *
   * @throws IllegalStateException 无障碍不可用（root 为 null）
   */
  fun getNodeXml(includeInvisible: Boolean? = null): String {
    val root = cachedOrFreshRoot() ?: throw IllegalStateException("UiAutomator unavailable")
    val include = includeInvisible ?: enableInvisible
    val dm = Resources.getSystem().displayMetrics
    val sb = StringBuilder(64 * 1024)
    sb.append("<nodes w=\"").append(dm.widthPixels)
      .append("\" h=\"").append(dm.heightPixels).append("\">")
    serialize(root.node, sb, 0, 0, include)
    sb.append("</nodes>")
    return sb.toString()
  }

  /**
   * 导出当前节点树 XML 并落盘（懒人 saveNode/saveNodeNew 轻量对齐：写通用 XML，不做 .node 私有格式）。
   *
   * @param path 落盘绝对路径；缺省 `<rootDir>/node_dump_<yyyyMMdd_HHmmss>.xml`
   *             （rootDir 未注入时退 java.io.tmpdir，再退 /data/local/tmp）
   * @param includeInvisible 是否包含不可见节点；缺省读 [enableInvisible] 状态（setNodeMode 可改）
   * @return 实际写入的绝对路径
   * @throws IllegalStateException 无障碍不可用或文件写入失败（Lua 侧 error）
   */
  fun saveNode(path: String?, includeInvisible: Boolean?): String {
    val xml = getNodeXml(includeInvisible)
    val p = path?.takeIf { it.isNotBlank() } ?: defaultDumpPath()
    try {
      val f = java.io.File(p)
      f.parentFile?.let { if (!it.exists()) it.mkdirs() }
      f.writeText(xml, Charsets.UTF_8)
    } catch (t: Throwable) {
      throw IllegalStateException("saveNode failed: $p (${t.message})")
    }
    return p
  }

  /** saveNode 缺省路径：rootDir 优先，退 java.io.tmpdir，再退 /data/local/tmp */
  private fun defaultDumpPath(): String {
    val dir = rootDir?.takeIf { it.isNotBlank() }
      ?: System.getProperty("java.io.tmpdir")?.takeIf { it.isNotBlank() }
      ?: "/data/local/tmp"
    val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
    val sep = if (dir.endsWith("/") || dir.endsWith("\\")) "" else "/"
    return "$dir${sep}node_dump_$ts.xml"
  }

  /**
   * 按层分支索引路径定位节点（懒人 findByIndex 语义轻量版）："0|1|2" 每层为父内索引，
   * 与 getNodeXml 导出的 i=父内索引 对应（节点工具路径可直接回放）。
   * 分隔符 '|'（容忍 '/'）；越界/负数/非法 token/中途断链返回 null；
   * 空 path 抛 IllegalArgumentException，无障碍不可用抛 IllegalStateException。
   */
  fun findByIndex(path: String): UiObject? {
    if (path.isBlank()) throw IllegalArgumentException("findByIndex: empty path")
    val root = cachedOrFreshRoot() ?: throw IllegalStateException("UiAutomator unavailable")
    var node: UiObject? = root
    for (token in path.split('|', '/')) {
      val cur = node ?: return null
      val idx = token.trim().toIntOrNull() ?: return null // 非法 token
      if (idx < 0) return null // 负数
      node = try {
        cur.child(idx) // 越界/节点失效时 child 可能返回 null 或抛异常，统一返回 null
      } catch (t: Throwable) {
        null
      }
    }
    return node
  }

  /**
   * 切换节点获取模式（懒人 setNodeMode 语义轻量版）：
   * - mode：0=全部节点（默认） 1=简单模式；切换无障碍服务 FLAG_INCLUDE_NOT_IMPORTANT_VIEWS(0x02)，
   *   以"当前 serviceInfo"为基线增删该位（不动其他 flags，事件订阅/RETRIEVE_INTERACTIVE_WINDOWS 不受影响）；
   * - enableGetInVisibleNode：更新 [enableInvisible]，作为 getNodeXml 缺省 includeInvisible；
   * 非空参数才更新对应状态；flags 切换经 [UiAutomator.setIncludeNotImportantViews]（异常吞掉降级 no-op）。
   */
  fun setNodeMode(mode: Int?, enableGetInVisibleNode: Boolean?) {
    if (enableGetInVisibleNode != null) enableInvisible = enableGetInVisibleNode
    if (mode != null && mode != nodeMode) {
      nodeMode = mode
      try {
        automator?.setIncludeNotImportantViews(nodeMode == 0)
      } catch (t: Throwable) {
      }
    }
  }

  /** 递归序列化节点树（直接操作 AccessibilityNodeInfo，循环 index 即父内索引） */
  private fun serialize(node: AccessibilityNodeInfo, sb: StringBuilder,
                        index: Int, depth: Int, includeInvisible: Boolean) {
    if (!includeInvisible && !node.isVisibleToUser) return
    val rect = Rect()
    node.getBoundsInScreen(rect)
    val pRect = Rect()
    node.getBoundsInParent(pRect)
    sb.append("<n i=\"").append(index)
      .append("\" d=\"").append(depth)
      .append("\" c=\"").append(esc(node.className))
      .append("\" t=\"").append(esc(node.text))
      .append("\" de=\"").append(esc(node.contentDescription))
      .append("\" id=\"").append(esc(node.viewIdResourceName))
      .append("\" p=\"").append(esc(node.packageName))
      .append("\" b=\"").append(rect.left).append(',').append(rect.top)
      .append(',').append(rect.right).append(',').append(rect.bottom)
      .append("\" bp=\"").append(pRect.left).append(',').append(pRect.top)
      .append(',').append(pRect.right).append(',').append(pRect.bottom)
      .append("\" ck=\"").append(b01(node.isClickable))
      .append("\" cc=\"").append(b01(node.isCheckable))
      .append("\" ch=\"").append(b01(node.isChecked))
      .append("\" en=\"").append(b01(node.isEnabled))
      .append("\" fo=\"").append(b01(node.isFocusable))
      .append("\" fc=\"").append(b01(node.isFocused))
      .append("\" sc=\"").append(b01(node.isScrollable))
      .append("\" lc=\"").append(b01(node.isLongClickable))
      .append("\" se=\"").append(b01(node.isSelected))
      .append("\" ed=\"").append(b01(node.isEditable))
      .append("\" vt=\"").append(b01(node.isVisibleToUser))
      .append("\" pw=\"").append(b01(node.isPassword))
      .append("\" do=\"").append(if (Build.VERSION.SDK_INT >= 24) node.drawingOrder else -1)
      .append("\">")
    val count = node.childCount
    for (i in 0 until count) {
      val child = try {
        node.getChild(i)
      } catch (t: Throwable) {
        continue // 节点已失效等异常，跳过该子节点
      } ?: continue
      serialize(child, sb, i, depth + 1, includeInvisible)
    }
    sb.append("</n>")
  }

  private fun b01(v: Boolean): String = if (v) "1" else "0"

  /** XML 转义 & < > " '（text/desc/id/className/packageName 均经此处理） */
  private fun esc(cs: CharSequence?): String {
    if (cs == null) return ""
    val s = cs.toString()
    if (s.isEmpty()) return ""
    val sb = StringBuilder(s.length + 8)
    for (ch in s) {
      when (ch) {
        '&' -> sb.append("&amp;")
        '<' -> sb.append("&lt;")
        '>' -> sb.append("&gt;")
        '"' -> sb.append("&quot;")
        '\'' -> sb.append("&apos;")
        else -> sb.append(ch)
      }
    }
    return sb.toString()
  }

  // ---------------- 无障碍开关（检测 + root shell 自动启用） ----------------

  /** 本引擎无障碍服务全类名（settings 值 "pkg/cls" 中的 cls 部分） */
  private val SERVICE_CLS: String = AutoLuaAccessibilityService::class.java.name

  /** app Context（root 引擎进程可能为 null，此时读/写 settings 走 shell 通道） */
  private fun appContext(): Context? = try {
    Class.forName("android.app.ActivityThread")
      .getMethod("currentApplication").invoke(null) as? Context
  } catch (t: Throwable) {
    null
  }

  /** shell 执行（root 进程 uid=0 时 settings/am 可写；返回 (输出, 退出码)） */
  private fun exec(cmd: String): Pair<String, Int> = try {
    val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
    val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
    val err = proc.errorStream.readBytes().toString(Charsets.UTF_8)
    proc.waitFor()
    Pair((if (out.isEmpty()) err else out).trim(), proc.exitValue())
  } catch (t: Throwable) {
    Pair("", -1)
  }

  /** 读 enabled_accessibility_services：Context contentResolver 优先，失败退 shell（永远非 null） */
  private fun readEnabledServices(): String {
    val ctx = appContext()
    if (ctx != null) {
      try {
        val v = Settings.Secure.getString(ctx.contentResolver,
          Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        if (v != null) return v
      } catch (t: Throwable) {
      }
    }
    val (out, code) = exec("settings get secure enabled_accessibility_services")
    return if (code == 0) out else ""
  }

  /**
   * 无障碍服务是否可用：服务实例已连接（静态 isRunning 标志）或
   * settings 启用列表已含本服务组件（pkg/cls 串含全类名）。
   */
  fun isAccServiceOk(): Boolean {
    val connected = try {
      AutoLuaAccessibilityService.isRunning()
    } catch (t: Throwable) {
      false
    }
    if (connected) return true
    return readEnabledServices().contains(SERVICE_CLS)
  }

  /**
   * 尝试开启无障碍：已 ok 直接 true；否则 root shell 写
   * settings put secure enabled_accessibility_services（追加式，不清其他服务）
   *
   * 引导节流（2026-09-23）：每次 run_script 的 frameSdk.init 都会调到这里，而
   * exec 链（fork sh + settings put ×2 + 失败跳设置页）单次可达秒级——模拟器等
   * 拉不起服务的环境上"每次点运行卡十几秒"。因此：60s 内已引导过则跳过整段
   * exec 链直接 false（服务何时真正上线由下一次 isAccServiceOk 动态探测）。
   */
  @Volatile
  private var lastGuideAt = 0L

  fun openAccessibility(): Boolean {
    if (isAccServiceOk()) return true
    val now = System.currentTimeMillis()
    if (now - lastGuideAt < 60_000L) return false
    lastGuideAt = now
    val ctx = appContext()
    val existing = readEnabledServices()
    val pkg = ctx?.packageName
    if (!existing.contains(SERVICE_CLS) && pkg == null) {
      // 无 Context 拿不到包名，无法拼 pkg/cls：直接引导设置页
      guideToSettings()
      return false
    }
    if (existing.contains(SERVICE_CLS)) {
      // 组件已在启用列表：只差 accessibility_enabled 开关。当前已是 1 则零 exec 快速返回
      val enabled = ctx?.let {
        try { Settings.Secure.getInt(it.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED) }
        catch (t: Throwable) { -1 }
      } ?: -1
      if (enabled == 1) return true
    }
    val newValue = if (existing.contains(SERVICE_CLS)) {
      existing // 组件已在列表：只差 accessibility_enabled 开关
    } else if (existing.isBlank() || existing == "null") {
      "$pkg/$SERVICE_CLS"
    } else {
      "$existing:$pkg/$SERVICE_CLS"
    }
    val (_, c1) = exec("settings put secure enabled_accessibility_services '$newValue'")
    val (_, c2) = exec("settings put secure accessibility_enabled 1")
    if (c1 == 0 && c2 == 0) return true
    guideToSettings()
    return false
  }

  /**
   * 关闭本引擎无障碍服务（与 openAccessibility 对称）：从 enabled_accessibility_services
   * 列表（":"/" " 分隔）移除本服务组件 "pkg/SERVICE_CLS" 后写回（移除后为空时写 ''），
   * 再写 accessibility_enabled 0；两步 shell 退出码均 0 返回 true，否则 false。
   * app Context 不可得（拿不到包名）时直接返回 false——整串置空会误伤其他服务，
   * 且关闭服务无需跳设置页（不调 guideToSettings）。
   * 不发广播：服务被禁用后系统自然走 AutoLuaAccessibilityService 现有解绑/禁用路径。
   */
  fun closeAccessibility(): Boolean {
    val pkg = appContext()?.packageName ?: return false
    val existing = readEnabledServices()
    val target = "$pkg/$SERVICE_CLS"
    val remain = existing.split(':', ' ')
      .map { it.trim() }
      .filter { it.isNotEmpty() && it != target && it != "null" }
    val (_, c1) = if (remain.isEmpty()) {
      exec("settings put secure enabled_accessibility_services ''")
    } else {
      exec("settings put secure enabled_accessibility_services '${remain.joinToString(":")}'")
    }
    val (_, c2) = exec("settings put secure accessibility_enabled 0")
    return c1 == 0 && c2 == 0
  }

  /** 跳系统无障碍设置页（2026-09-23 设计变更：**自动跳页已禁用**）——
   *  无障碍是敏感权限，应由用户手动开启；自动 am start 跳页曾在每次 init 时
   *  打断用户（叠加 exec 链单次可达 15s）。保留函数签名只记日志，调用方语义
   *  （返回 false=服务未就绪）不变。 */
  private fun guideToSettings() {
    android.util.Log.w("NodeLib", "accessibility auto-jump disabled: please enable 'AutoLua Accessibility' in system settings manually")
  }
}
