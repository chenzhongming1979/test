package com.autolua.engine.extension.node

/**
 * IDE 沙箱专用节点只读桥（懒人 nodeLib 命名对齐）。
 * 沙箱（LuaEngineExecutor）无 UiSelector/无障碍动作能力，但 VSCode 插件
 * "抓取节点树"需在沙箱 EXECUTE 通道调用 getNodeXml；仅暴露只读能力，
 * 不暴露 keepNode 等会污染主引擎查找缓存的写入口。
 */
class NodeLibBridge {
  /** 当前屏幕节点树 XML 导出（格式契约见 NodeLib） */
  fun getNodeXml(includeInvisible: Boolean): String = NodeLib.getNodeXml(includeInvisible)

  /** 无障碍服务是否可用 */
  fun isAccServiceOk(): Boolean = NodeLib.isAccServiceOk()

  /**
   * 节点树 XML 落盘（懒人 saveNode 轻量对齐）：path 缺省落 rootDir/node_dump_时间戳.xml，
   * 返回实际写入路径；includeInvisible 缺省读 NodeLib.enableInvisible 状态。
   */
  fun saveNode(path: String?, includeInvisible: Boolean?): String =
    NodeLib.saveNode(path, includeInvisible)

  /**
   * 层分支索引路径定位节点："0|1|2" 每层为父内索引（与 getNodeXml 的 i 属性对应），
   * 越界/负数/非法 token 返回 null，空 path 抛 IllegalArgumentException。
   */
  fun findByIndex(path: String): UiObject? = NodeLib.findByIndex(path)
}
