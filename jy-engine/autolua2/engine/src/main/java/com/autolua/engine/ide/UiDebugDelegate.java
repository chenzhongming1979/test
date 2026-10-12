package com.autolua.engine.ide;

/**
 * UI 热重载触发委托（9.11 调试通道整合，注入模式同 {@link ScriptRunDelegate}）。
 * <p>
 * engine 模块不依赖 app 模块与 MLN Kit，UI 热重载（DebugActivity）的拉起/关闭
 * 必须由 app 层接线：MainApp 注入实现（MLSBundleUtils.createInitData +
 * DebugActivity Intent / CLOSE_BROADCAST 广播，原老通道 DebugServiceImp 逻辑迁移）。
 * <p>
 * 未接线时 {@code ui_debug_start/stop} 回 error "ui debug delegate not attached"。
 */
public interface UiDebugDelegate {

    /** 启动 UI 热重载：主线程拉起 DebugActivity（load hotreload.lua） */
    void start();

    /** 停止 UI 热重载：广播 DebugActivity.CLOSE_BROADCAST 关闭页面 */
    void stop();
}
