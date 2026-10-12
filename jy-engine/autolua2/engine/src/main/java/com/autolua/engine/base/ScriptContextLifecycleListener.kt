package com.autolua.engine.base

/**
 * 脚本容器生命周期监听：容器销毁（一次脚本运行结束）时回调，
 * 服务在此释放该容器运行期间创建的跨层资源。
 *
 * 注册方式：LocalService 实现类额外实现本接口（与 [LuaEnvContributor] 同构——
 * pushLocalServices 包装时检查原始实例并记录，无需额外注册动作）。
 */
interface ScriptContextLifecycleListener {
    /**
     * 脚本容器销毁（正常退出 / exitScript / interrupt 强杀，全路径必达）。
     *
     * 契约：
     * 1. 回调在引擎 loop 主线程执行——【禁止阻塞】：重资源清理必须异步
     *    （参考 CloudCore.stop 的后台线程 + stopDone 门闩模式）
     * 2. 幂等：可能被连续调用（Lua OnStop 快路径 + 本兜底），重复释放必须安全
     * 3. 异常吞掉（引擎打日志），不影响其他服务回收
     */
    fun onScriptContextDestroyed()
}
