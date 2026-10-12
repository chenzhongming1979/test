package com.autolua.engine.extension.node.imp.root

import android.app.UiAutomation
import android.os.Bundle
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.autolua.engine.extension.node.UiAutomator
import com.autolua.engine.extension.node.UiObject
import java.util.concurrent.atomic.AtomicBoolean

class UiAutomatorImp:UiAutomator {
  private val thread = HandlerThread("UiAutomator")
  private var uiAutomation:UiAutomation? = null
  private val inited = AtomicBoolean(false)
  // 根节点短 TTL 缓存：缓存根节点原件，对外返回 obtain 拷贝（Lua 侧 recycle 拷贝不影响缓存）。
  // 取舍说明：TTL 仅 150ms 且每次命中先 refresh 校验，节点失效立即丢弃重取，不会误用旧树；
  // UiObject 直接 performAction/setText 等不经 imp 的写操作无法主动失效缓存，由 150ms TTL 兜底，
  // 属可接受的短暂陈旧窗口。
  private var cachedRootNode:AccessibilityNodeInfo? = null
  private var cachedRootAt:Long = 0L
  override fun init():Boolean {
    if (inited.compareAndSet(false, true)) {
      thread.start()
      var lastError: Throwable? = null
      try {
        val clazz = Class.forName("android.app.UiAutomationConnection")
        val connection = clazz.newInstance()
        val constructor =
          UiAutomation::class.java.getConstructor(
            Looper::class.java,
            Class.forName("android.app.IUiAutomationConnection")
          )
        for (i in 0 until MAX_CONNECT_SUM) {
          try {
            uiAutomation = constructor.newInstance(thread.looper, connection)
            UiAutomation::class.java.getMethod("connect", Int::class.javaPrimitiveType)
              .invoke(uiAutomation, 1)
            // connect 成功即视为初始化成功：rootInActiveWindow 可为 null（活动窗口未就绪，
            // 由 getRootInActiveWindow 的有界等待轮询处理）。原实现把「root 暂为 null」也当
            // 失败去 disconnect 重试——shell 级 UiTestAutomationService 为系统全局唯一注册，
            // 零间隔重试会撞上 "UiAutomationService already registered"（清理未完成），
            // 100 连发全挂导致冷启动 init 恒失败。
            System.err.println("UiAutomation " + (i + 1) + " connect ok")
            return true
          } catch (e: Throwable) {
            lastError = e
            try {
              UiAutomation::class.java.getMethod("disconnect").invoke(uiAutomation)
            } catch (t: Throwable) {
            }
            SystemClock.sleep(CONNECT_RETRY_INTERVAL_MS)
          }
        }
      } catch (e: Exception) {
        e.printStackTrace(System.err)
      }
      lastError?.printStackTrace(System.err)
      // 失败后复位标记：下次 ensureInit 仍可重试，避免一次失败永久卡死
      inited.set(false)
      return false
    }
    return true
  }

  private fun ensureInit(){
    if(!inited.get()){
      val r = init()
      if(!r){
        throw RuntimeException("UiAutomator init failed")
      }
    }
  }

  override fun getRootInActiveWindow(): UiObject? {
    ensureInit()
    synchronized(this) {
      // 命中 TTL 缓存：先 refresh 校验根节点，失效（返回 false）则丢弃缓存重取
      val cached = cachedRootNode
      if (cached != null && SystemClock.uptimeMillis() - cachedRootAt < ROOT_CACHE_TTL_MS) {
        if (cached.refresh()) {
          return UiObject(AccessibilityNodeInfo.obtain(cached))
        }
        invalidateRootCache()
      }
      // 有界等待：root 为 null 时按 60ms 轮询，超过 ROOT_WAIT_TIMEOUT_MS 返回 null（只告警一次）
      val deadline = SystemClock.uptimeMillis() + ROOT_WAIT_TIMEOUT_MS
      while (true) {
        val r = uiAutomation!!.rootInActiveWindow
        if (r != null) {
          cachedRootNode = r
          cachedRootAt = SystemClock.uptimeMillis()
          // 返回拷贝：Lua 侧 recycle 拷贝不影响缓存原件
          return UiObject(AccessibilityNodeInfo.obtain(r))
        }
        if (SystemClock.uptimeMillis() >= deadline) {
          Log.w(TAG, "getRootInActiveWindow: root not available within " + ROOT_WAIT_TIMEOUT_MS + "ms")
          return null
        }
        SystemClock.sleep(ROOT_POLL_INTERVAL_MS)
      }
    }
  }

  /**
   * 多窗口兜底：UiAutomation 通道 getWindows() 直接可用，
   * 遍历窗口取 root，过滤 null 与活动（active/focused）窗口——主 root 已由
   * getRootInActiveWindow 提供；getWindows 不可用时降级为空表。
   */
  override fun windowRoots(): List<UiObject> {
    ensureInit()
    synchronized(this) {
      val ua = uiAutomation ?: return emptyList()
      val result = ArrayList<UiObject>()
      try {
        for (w in ua.windows) {
          if (w == null || w.isActive || w.isFocused) continue
          val root = w.root ?: continue
          result.add(UiObject(root))
        }
      } catch (t: Throwable) {
        // 无窗口列表权限等异常：多窗口兜底降级为空
      }
      return result
    }
  }

  /** 根节点缓存失效（写操作成功/销毁后调用） */
  private fun invalidateRootCache() {
    cachedRootNode = null
    cachedRootAt = 0L
  }

  override fun setText(text: String): Boolean {
    ensureInit()
    val obj = uiAutomation!!.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
    val b = Bundle()
    b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    val ok = obj.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    // 写操作成功后根缓存失效，避免后续查找命中旧树
    if (ok) invalidateRootCache()
    return ok
  }

  /**
   * NodeLib.setNodeMode flags 通道（root UiAutomation）：
   * getServiceInfo()/setServiceInfo() 均为公开 API，info 为系统侧拷贝，
   * 以"当前 info"为基线仅增删 FLAG_INCLUDE_NOT_IMPORTANT_VIEWS(0x02)，
   * 不影响其他 flags；info 不可得（null）时不强改避免破坏既有配置，
   * 任一步异常吞掉降级 no-op。
   */
  override fun setIncludeNotImportantViews(enable: Boolean) {
    try {
      val ua = uiAutomation ?: return
      val info = ua.serviceInfo ?: return
      info.flags = if (enable) {
        info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
      } else {
        info.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS.inv()
      }
      ua.setServiceInfo(info)
    } catch (t: Throwable) {
      // flags 动态切换尽力而为：异常吞掉降级 no-op（root 通道节点查找主链路不受影响）
    }
  }

  override fun destroy() {
    if (inited.compareAndSet(true, false)) {
      UiAutomation::class.java.getMethod("disconnect").invoke(uiAutomation)
      invalidateRootCache()
      thread.quit()
    }
  }

  companion object{
    private const val MAX_CONNECT_SUM = 100
    private const val TAG = "UiAutomatorImp"
    /** root 为 null 时的有界等待上限（ms），public 可按需调整 */
    @JvmField var ROOT_WAIT_TIMEOUT_MS = 3000L
    /** 根节点缓存 TTL（ms）：短时间内复用同一次跨进程取根结果 */
    @JvmField var ROOT_CACHE_TTL_MS = 150L
    /** root 轮询间隔（ms） */
    private const val ROOT_POLL_INTERVAL_MS = 60L
    /** UiAutomation connect 失败重试间隔（ms）：系统侧注册清理需要时间，零间隔必撞 already registered */
    private const val CONNECT_RETRY_INTERVAL_MS = 100L
  }
}