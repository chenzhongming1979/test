package com.autolua.engine.extension.node

import android.graphics.Bitmap
import com.autolua.engine.accessibility.AccessibilityCapture
import com.autolua.engine.base.LuaContext
import com.autolua.engine.base.LuaObjectAdapter
import com.autolua.engine.extension.node.imp.accessibility.AccessibilityImp

/** waitFor(sel, timeout) 全局函数：转交 UiSelector.findOneWait 做带超时轮询查找。
    timeout 参数用 Any? 承接，避免 KotlinFunctionWrapper 对 primitive 签名的反射 mangling 问题 */
private class WaitForNodeFunction : Function2<UiSelector?, Any?, UiObject?> {
  override fun invoke(sel: UiSelector?, timeout: Any?): UiObject? {
    if(sel == null) return null
    val t = (timeout as? Number)?.toLong() ?: 10000L
    return sel.findOneWait(t)
  }
}

/**
 * 节点域全局注册安装器（三环境一致性铁律的收敛点）：
 * root 主引擎（AutoLuaEngineOnLocal.rawNewLuaContext）与 IDE 沙箱执行器
 * （LuaEngineExecutor，裸 LuaContextImplement 不经 Builder）共用本安装器，
 * 保证 UiSelector/getRootInActiveWindow/setText/NodeLib 全族/waitFor/screenshot
 * 在两环境同名同语义。
 *
 * automator 为 null 时只注册 screenshot（无障碍截图不依赖节点通道，
 * root 引擎进程无服务实例时优雅返回 nil）。
 */
object NodeDomainInstaller {

  fun install(luaContext: LuaContext, automator: UiAutomator?) {
    if(automator != null){
      // NodeLib 接线：懒人节点缓存 + 多窗口兜底 + XML 树导出 + 无障碍开关
      NodeLib.automator = automator
      NodeLib.rootGetter = { automator.getRootInActiveWindow() }
      UiSelector.extraRootsProvider = { NodeLib.otherRoots() }
      luaContext.push {
        UiSelector().also { it.rootProvider = { NodeLib.cachedOrFreshRoot() } }
      }
      luaContext.setGlobal("UiSelector")
      luaContext.push {
        automator.getRootInActiveWindow()
      }
      luaContext.setGlobal("getRootInActiveWindow")

      luaContext.push{it:String->
        automator.setText(it)
      }
      luaContext.setGlobal("setText")

      // NodeLib 全局函数（keepNode 懒人语义 + XML 导出 + 无障碍开关；均回传 boolean/string）
      luaContext.push { NodeLib.keepNode(); true }
      luaContext.setGlobal("keepNode")
      luaContext.push { NodeLib.releaseNode(); true }
      luaContext.setGlobal("releaseNode")
      luaContext.push { NodeLib.updateNode(); true }
      luaContext.setGlobal("updateNode")
      luaContext.push { NodeLib.isAccServiceOk() }
      luaContext.setGlobal("isAccServiceOk")
      luaContext.push { NodeLib.openAccessibility() }
      luaContext.setGlobal("openAccessibility")
      // getNodeXml([includeInvisible])：可选参，手动读栈（lambda 固定参数形态不兼容无参调用）；
      // 缺省 null 时走 NodeLib.enableInvisible 状态（setNodeMode 可改）
      luaContext.push(object : LuaObjectAdapter {
        override fun invoke(L: LuaContext): Int {
          val t = L.type(2)
          val includeInvisible = if (t == LuaContext.ValueType.BOOLEAN) L.toBoolean(2) else null
          L.push(NodeLib.getNodeXml(includeInvisible))
          return 1
        }
        override fun index(L: LuaContext): Int = 0
        override fun newIndex(L: LuaContext): Int = 0
        override fun call(L: LuaContext, name: String): Int = 0
        override fun release(L: LuaContext) {}
      })
      luaContext.setGlobal("getNodeXml")

      // saveNode([path],[includeInvisible])：节点树 XML 落盘，返回实际写入路径（两参均可选）
      luaContext.push(object : LuaObjectAdapter {
        override fun invoke(L: LuaContext): Int {
          val path = if (L.type(2) == LuaContext.ValueType.STRING) L.toString(2) else null
          val includeInvisible = if (L.type(3) == LuaContext.ValueType.BOOLEAN) L.toBoolean(3) else null
          L.push(NodeLib.saveNode(path, includeInvisible))
          return 1
        }
        override fun index(L: LuaContext): Int = 0
        override fun newIndex(L: LuaContext): Int = 0
        override fun call(L: LuaContext, name: String): Int = 0
        override fun release(L: LuaContext) {}
      })
      luaContext.setGlobal("saveNode")

      // findByIndex(path)："0|1|2" 每层父内索引路径定位（对应 getNodeXml 的 i 属性），
      // 命中返回节点对象，越界/非法路径返回 nil；空 path 抛 IllegalArgumentException（Lua 侧 error）
      luaContext.push(object : LuaObjectAdapter {
        override fun invoke(L: LuaContext): Int {
          if (L.type(2) != LuaContext.ValueType.STRING) {
            L.pushNil()
            return 1
          }
          val node = NodeLib.findByIndex(L.toString(2) ?: "")
          if (node != null) L.push(node) else L.pushNil()
          return 1
        }
        override fun index(L: LuaContext): Int = 0
        override fun newIndex(L: LuaContext): Int = 0
        override fun call(L: LuaContext, name: String): Int = 0
        override fun release(L: LuaContext) {}
      })
      luaContext.setGlobal("findByIndex")

      // setNodeMode([mode],[enableGetInVisibleNode])：切换节点获取模式（懒人语义轻量版），
      // mode=0 全部节点/1 简单模式，enableGetInVisibleNode 更新 getNodeXml 缺省含不可见；
      // 非空才更新，无返回值
      luaContext.push(object : LuaObjectAdapter {
        override fun invoke(L: LuaContext): Int {
          val mode = if (L.type(2) == LuaContext.ValueType.NUMBER) L.toLong(2).toInt() else null
          val enableInvisible = if (L.type(3) == LuaContext.ValueType.BOOLEAN) L.toBoolean(3) else null
          NodeLib.setNodeMode(mode, enableInvisible)
          return 0
        }
        override fun index(L: LuaContext): Int = 0
        override fun newIndex(L: LuaContext): Int = 0
        override fun call(L: LuaContext, name: String): Int = 0
        override fun release(L: LuaContext) {}
      })
      luaContext.setGlobal("setNodeMode")

      // closeAccessibility()：关闭本引擎无障碍服务（与 openAccessibility 对称），返回是否成功
      luaContext.push { NodeLib.closeAccessibility() }
      luaContext.setGlobal("closeAccessibility")

      // waitFor(sel, timeout)：等待选择器匹配节点出现（引擎侧带超时轮询），超时返回 nil
      luaContext.push(WaitForNodeFunction())
      luaContext.setGlobal("waitFor")
    }
    // screenshot(timeoutMs?)：无障碍截图（API 30+，服务连接且具备截图能力时可用）。
    // 不依赖节点通道：沙箱（IDE）与宿主同进程可用；root 引擎进程无服务实例，优雅返回 nil。
    // 返回软件 ARGB_8888 Bitmap，像素可直接读取；失败/超时/API<30 返回 nil。禁止主线程调用（内部阻塞等待）。
    luaContext.push(adapter { L ->
      val timeout = if (L.type(2) == LuaContext.ValueType.NIL) 0L else L.toLong(2)
      val bitmap = AccessibilityCapture.takeScreenshot(timeout)
      if (bitmap != null) L.pushValue(bitmap, Bitmap::class.java) else L.pushNil()
      1
    })
    luaContext.setGlobal("screenshot")
  }

  /** IDE 沙箱便捷入口：无障碍服务已连接时以 AccessibilityImp 装配（幂等由调用方保证） */
  fun installForSandboxIfReady(luaContext: LuaContext): Boolean {
    val svc = try {
      com.autolua.engine.accessibility.AutoLuaAccessibilityService.getInstance()
    } catch (t: Throwable) {
      null
    }
    if (svc == null) return false
    install(luaContext, AccessibilityImp())
    return true
  }

  /** 裸栈适配器：与 EventLib/PluginLib 等 Lib 的 adapter 同构，用于可变参数注册 */
  private fun adapter(body: (LuaContext) -> Int): LuaObjectAdapter = object : LuaObjectAdapter {
    override fun invoke(L: LuaContext): Int = body(L)
    override fun index(L: LuaContext): Int = 0
    override fun newIndex(L: LuaContext): Int = 0
    override fun call(L: LuaContext, name: String): Int = 0
    override fun release(L: LuaContext) {}
  }
}
