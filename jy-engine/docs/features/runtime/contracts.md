# runtime 契约 —— 脚本容器生命周期回收

> 模型：一次脚本运行 = 一个脚本容器（LuaState + 该脚本创建的跨层资源）；容器销毁时引擎**强制**回收资源。设计详见 [design.md](design.md)。

## 1. 服务接入契约：`ScriptContextLifecycleListener`

```kotlin
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
```

## 2. 广播语义

| 项 | 语义 |
|---|---|
| 触发条件 | `onStateChanged(kIdle, Target.WORKER)`——一次脚本运行结束（三路径全覆盖） |
| 回调线程 | 引擎 loop 主线程（**禁止阻塞**，契约第 1 条） |
| 回调顺序 | localServices 注册顺序（当前单容器模型下顺序无语义；多服务清理无依赖） |
| 进程归属 | 引擎所在进程（root worker / app 沙箱降级引擎），LocalService 单例同进程直调 |
| IDE 沙箱 | 范围外：沙箱为调试态常驻（LuaState 不销毁），listener 不触发；沙箱资源由 execute 流程自管 |

## 3. CloudService 接入示例（首个接入者，已实现）

```kotlin
class CloudService : ICloudService, LuaEnvContributor, ScriptContextLifecycleListener {
    // ... start/stop/poll/send/connections/isRunning/isEncrypted/contribute ...

    /** 脚本容器销毁：关 cloud server（幂等；与 OnStop Lua 钩子双通道，重复调用安全） */
    override fun onScriptContextDestroyed() {
        CloudCore.stop()  // 异步 stop（后台线程 + stopDone 门闩），回调内不阻塞
    }
}
```

配套（同 commit 270172d，先于本契约落地）：

- **OnStop Lua 钩子**（cloud 绑定内注册）：脚本终止时 `cloud.close()`——快路径
- **CloudCore.stop 异步化**：后台线程 stop + stopDone 门闩，不卡引擎 loop
- **CloudWebSocketServer.awaitStarted**：绑定确认（onStart/onError latch），`start` 同步等待结果，失败不留未监听空壳
- **stopDone 门闩**：`start` 前等待上次异步 stop 完成，防端口未释放竞争

## 4. 通用原则（longjmp 与 RAII——所有挂起点实现者的铁律）

引擎 interrupt 的终止信号 = **sleep 挂起点抛 `error("interrupt")`**。Lua error 在 NDK Lua（未启用 `-fexceptions`）下走 **longjmp，跳过 C++ RAII 析构**：

```cpp
// 反例（lSleep 原始 bug——锁永久泄漏，loop 死锁，一切 run_script 静默失败）：
auto locker = signalEvent.locker();
if (interrupt) { lua_error(L); }        // longjmp 跳过 locker 析构！

// 正例：
if (interrupt) { locker.unlock(); lua_error(L); }  // 抛前显式释放
```

**规则**：任何"栈上有 C++ 锁/资源 → 调 `lua_error`/`lua_error` 可能传播（如 Lua 回调内）"的代码，必须在抛错前显式释放。
