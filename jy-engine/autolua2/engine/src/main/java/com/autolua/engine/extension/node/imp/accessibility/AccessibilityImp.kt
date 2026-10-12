package com.autolua.engine.extension.node.imp.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.autolua.engine.accessibility.AutoLuaAccessibilityService
import com.autolua.engine.extension.node.UiAutomator
import com.autolua.engine.extension.node.UiObject

/**
 * 无障碍（免 root/沙箱）节点实现：经 AutoLuaAccessibilityService 提供节点查找与操作。
 *
 * 查找链路：服务连接（isRunning）→ 活动窗口根 rootInActiveWindow → 全窗口兜底
 * （活动窗口根不可用时按 active 优先、layer 降序从 getWindows() 取最优候选根）；
 * 其他窗口根由 [windowRoots] 提供，上层 UiSelector 便捷动作在主窗口无结果时
 * 逐窗口顺序匹配（对 Lua 侧透明）。
 *
 * 节点动作（click/setText 等）走 UiObject 既有 ACTION 映射
 * （AccessibilityNodeInfo.performAction 原生支持，无需 root）。
 */
class AccessibilityImp : UiAutomator {

  /** 服务由系统绑定管理，无需初始化连接 */
  override fun init(): Boolean = true

  override fun getRootInActiveWindow(): UiObject? {
    // 服务未连接：有界等待（≤500ms，60ms 轮询；服务刚开启时 onServiceConnected 异步到达），不忙等
    var svc = AutoLuaAccessibilityService.getInstance()
    if (svc == null) {
      val deadline = SystemClock.uptimeMillis() + SERVICE_WAIT_TIMEOUT_MS
      while (svc == null && SystemClock.uptimeMillis() < deadline) {
        SystemClock.sleep(SERVICE_POLL_INTERVAL_MS)
        svc = AutoLuaAccessibilityService.getInstance()
      }
      if (svc == null) return null
    }
    return activeRoot(svc)
  }

  /** 活动窗口根：rootInActiveWindow 短轮询；仍为 null 时全窗口取最优候选（P1.6 fallback） */
  private fun activeRoot(svc: AccessibilityService): UiObject? {
    val deadline = SystemClock.uptimeMillis() + ROOT_WAIT_TIMEOUT_MS
    var r: AccessibilityNodeInfo? = svc.rootInActiveWindow
    while (r == null && SystemClock.uptimeMillis() < deadline) {
      SystemClock.sleep(ROOT_POLL_INTERVAL_MS)
      r = svc.rootInActiveWindow
    }
    if (r != null) return UiObject(r)
    // 活动窗口根不可用：按 active 优先、layer 降序遍历窗口，取第一个有根的窗口
    for (w in sortedWindows(svc)) {
      val root = try {
        w.root
      } catch (t: Throwable) {
        null
      } ?: continue
      return UiObject(root)
    }
    Log.w(TAG, "getRootInActiveWindow: no root within " + ROOT_WAIT_TIMEOUT_MS + "ms")
    return null
  }

  /** 窗口列表排序：active 优先，其次 focused，同优先级按 layer 降序；上限 [MAX_WINDOWS] */
  private fun sortedWindows(svc: AccessibilityService): List<AccessibilityWindowInfo> {
    return try {
      val list = ArrayList<AccessibilityWindowInfo>(svc.windows)
      list.sortWith(
        compareByDescending<AccessibilityWindowInfo> { it.isActive }
          .thenByDescending { it.isFocused }
          .thenByDescending { it.layer })
      if (list.size > MAX_WINDOWS) list.subList(0, MAX_WINDOWS) else list
    } catch (t: Throwable) {
      // 窗口列表不可用（FLAG_RETRIEVE_INTERACTIVE_WINDOWS 未授予等）：全窗口兜底降级为空
      emptyList()
    }
  }

  /**
   * 多窗口兜底：遍历服务窗口列表取其他窗口 root（layer 降序，上限 [MAX_WINDOWS]），
   * 过滤活动/焦点窗口——主 root 已由 [getRootInActiveWindow] 提供（含其最优候选兜底）；
   * 上层 UiSelector 便捷动作在主窗口无结果时按本列表顺序逐窗口匹配。
   */
  override fun windowRoots(): List<UiObject> {
    val svc = AutoLuaAccessibilityService.getInstance() ?: return emptyList()
    val result = ArrayList<UiObject>()
    try {
      for (w in sortedWindows(svc)) {
        if (w.isActive || w.isFocused) continue
        val root = w.root ?: continue
        result.add(UiObject(root))
      }
    } catch (t: Throwable) {
      // 无窗口列表权限等异常：多窗口兜底降级为空
    }
    return result
  }

  override fun setText(text: String): Boolean {
    val svc = AutoLuaAccessibilityService.getInstance() ?: return false
    // 焦点节点写入（AccessibilityService.findFocus 与 root 通道 UiAutomation.findFocus 同语义）
    val obj = try {
      svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    } catch (t: Throwable) {
      null
    } ?: return false
    val b = Bundle()
    b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    return obj.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
  }

  /**
   * NodeLib.setNodeMode flags 通道（app 进程无障碍服务）：
   * 以"当前 serviceInfo"为基线增删 FLAG_INCLUDE_NOT_IMPORTANT_VIEWS(0x02)，
   * 不动其他 flags（事件订阅/RETRIEVE_INTERACTIVE_WINDOWS 等保持原样）；
   * 服务未连接或任一步异常时吞掉降级 no-op，不影响主流程。
   */
  override fun setIncludeNotImportantViews(enable: Boolean) {
    try {
      val svc = AutoLuaAccessibilityService.getInstance() ?: return
      val info = svc.serviceInfo ?: return // getSystemService 返回系统侧拷贝，改完回传安全
      info.flags = if (enable) {
        info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
      } else {
        info.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS.inv()
      }
      svc.setServiceInfo(info)
    } catch (t: Throwable) {
      // flags 动态切换尽力而为：异常吞掉降级 no-op（不破坏节点查找主链路）
    }
  }

  /** 服务由系统绑定管理，无需释放资源 */
  override fun destroy() {
  }
  companion object {
    private const val TAG = "AccessibilityImp"
    /** 服务未连接时的有界等待上限（ms） */
    private const val SERVICE_WAIT_TIMEOUT_MS = 500L
    /** 服务连接轮询间隔（ms） */
    private const val SERVICE_POLL_INTERVAL_MS = 60L
    /** 活动窗口根为 null 时的有界等待上限（ms），public 可按需调整 */
    @JvmField var ROOT_WAIT_TIMEOUT_MS = 500L
    /** 活动窗口根轮询间隔（ms） */
    private const val ROOT_POLL_INTERVAL_MS = 60L
    /** 全窗口兜底的窗口数量上限 */
    private const val MAX_WINDOWS = 10
  }
}
