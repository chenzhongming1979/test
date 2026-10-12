package com.autolua.engine.extension.node

interface UiAutomator {
  fun init():Boolean
  /** 活动窗口根节点；等待超时或不可用时返回 null */
  fun getRootInActiveWindow():UiObject?
  /**
   * 除活动窗口外的其他窗口根节点列表（多窗口兜底；NodeLib.otherRoots 经此取窗口）。
   * 默认空实现：不支持多窗口的通道直接返回空表。
   */
  fun windowRoots():List<UiObject> = emptyList()
  fun setText(text:String):Boolean

  /**
   * 切换 FLAG_INCLUDE_NOT_IMPORTANT_VIEWS(0x02)（NodeLib.setNodeMode 通道）：
   * enable=true 保留该位（全部节点，默认），false 去掉该位（简单模式）。
   * 默认空实现：不支持动态切 flags 的通道静默降级（NodeLib 状态仍生效于 XML 导出）。
   */
  fun setIncludeNotImportantViews(enable: Boolean) {}

  fun destroy()
}
