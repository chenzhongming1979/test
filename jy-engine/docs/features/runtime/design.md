# runtime 设计 —— 脚本容器模型与生命周期回收

## 1. 术语与模型

```
┌─ 引擎运行时（常驻，永不受脚本影响）─────────────────────┐
│  LuaInterpreter：常驻 loop 线程 + 信号机制 + 服务注册表  │
│                                                          │
│  ┌─ 脚本容器（每次 run_script 创建，stop/结束即销毁）─┐  │
│  │  LuaState：_G 全局（require 劫持/print 包装等       │  │
│  │             随 State 销毁，天然隔离）                │  │
│  │  资源组：脚本经 LocalService 创建的跨层资源          │  │
│  │    （cloud server / ws 连接 / thread 协程 /          │  │
│  │     虚拟屏 / 插件实例 / 网络连接 ...）                │  │
│  └────────────────────────────────────────────────────┘  │
│                                                          │
│  【本设计新增】容器销毁事件 → 资源组确定性回收            │
└──────────────────────────────────────────────────────────┘
```

- **引擎**：常驻运行时。loop 线程、服务注册、观察者机制永不受脚本影响。
- **脚本容器**：一次 `run_script` = 一个容器 = 一个 LuaState + 该脚本创建的跨层资源。脚本结束（正常退出 / exitScript / interrupt 强杀）= 容器销毁。
- **资源回收的确定性**：容器销毁时，引擎**强制**回收容器内资源——不依赖脚本写清理代码，interrupt 强杀同样干净。

## 2. 现状盘点（已有 vs 缺口）

### 已有（本次之前）

| 机制 | 位置 | 隔离范围 |
|---|---|---|
| LuaState 销毁重建（非 retain 模式，loop 行 250） | LuaInterpreter.cc | Lua 层全局：`_G.require` 劫持、print 包装、全局变量 |
| `stopAllThreadTasks`（interrupt 时调用） | LuaInterpreter.cc:311 | thread 协程 |
| `OnStop` 全局钩子（脚本终止后、LuaState 销毁前调用） | LuaInterpreter.cc:242 | Lua 层自愿清理（可被脚本覆盖、强杀时依赖注册方） |
| `script_state` 广播（kIdle 时） | LuaInterpreterObserver | 状态通知（app 层浮动窗/插件按钮） |

### 缺口（本设计补齐）

**跨层资源无回收契约**：LocalService 创建的资源（CloudCore 的 WS server、NetLib 的连接、VirtualDisplay 等）存活在 Java/C++ 层，LuaState 销毁对它们不可见。脚本被 interrupt 强杀时无任何清理机会。

实测案例：cloud WS server（9300）在脚本停止后残留监听（客户端能连、无人处理）；叠加 lSleep 锁泄漏（longjmp 跳过 RAII 析构，270172d 修复）导致"停止后再运行永久失败"。

## 3. 挂载点决策

**候选 A：`releaseContext`（LuaContext 销毁链）**
- 位置：AutoLuaEngineOnLocal.releaseContext(ptr)（C++ LuaStateFactory.release 调用）
- 缺点：仅在非 retain 模式触发；IDE 沙箱 LuaState 常驻不触发；语义是"LuaState 释放"而非"一次脚本运行结束"

**候选 B：`onStateChanged(kIdle, Target.WORKER)`（选定）**
- 位置：AutoLuaEngineOnLocal.onStateChanged（C++ notifyStateChange → Java，引擎所在进程内）
- 优点：
  - **全路径覆盖**：正常退出 / exitScript / interrupt 强杀，全部经 `state_ = kIdle` 汇聚（LuaInterpreter.cc:259-263）
  - **进程正确**：回调在引擎所在进程（root worker / app 沙箱），与 CloudCore 等 LocalService 单例同进程，直接调用无需跨进程
  - **Java 内改动**：无 C++ 改动
  - **语义精确**：kIdle = 一次脚本运行结束 = 容器销毁
- 时序保证：回调时 LuaState 尚未销毁（行 242 OnStop 在 250 releaseLuaState 之前，Idle 通知在其后）——但**回调契约不依赖 LuaState**（资源清理面向 Java 层资源）

## 4. 目标架构

```
AutoLuaEngineOnLocal.onStateChanged(state, target)
    │ state == kIdle && target == WORKER
    ▼
【新增】遍历 localServices（与 LuaEnvContributor 同构模式）
    │ raw is ScriptContextLifecycleListener
    ▼
逐个回调 listener.onScriptContextDestroyed()      ← 逆序（后创建先销毁）
    │ 契约：回调内禁止阻塞（重清理异步执行）
    ▼
CloudService → CloudCore.stop()（异步，已有 stopDone 门闩）
NetLib / VirtualDisplayLib / ... → 逐步接入（接口先行，接入分期）
```

**双通道清理（cloud 现状，泛化原则）**：

| 通道 | 触发时机 | 角色 |
|---|---|---|
| OnStop Lua 钩子 | LuaState 销毁前（同步，脚本同线程） | 快路径（脚本正常退出/异常终止） |
| lifecycle listener（Java） | state=kIdle（引擎保证必达） | **确定性兜底**（interrupt 强杀也不漏） |

## 5. 生命周期时序（run → stop → run）

```
run_script A
  ├─ execute: state=Running，LuaState 创建（contribute：cloud/OnStop 注入）
  ├─ 脚本 A：cloud.open → CloudCore.start(S1) → 泵循环
  └─ stop_script → interrupt
       ├─ 泵 sleep 挂起点抛 error("interrupt")（lSleep 已修：抛前 unlock，防锁泄漏）
       ├─ OnStop（Lua 钩子）→ cloud.close → 异步 stop S1
       └─ state=kIdle → listener 广播 → CloudCore.stop（幂等，异步）
            └─ stopDone 门闩置位（防 S1 未释放完被新绑定竞争）
run_script B
  ├─ execute: state=Idle ✓ → LuaState 重建（contribute 重跑：新 cloud 表）
  └─ 脚本 B：cloud.open → 等 stopDone → start(S2) → awaitStarted 确认绑定 → 泵循环 ✓
```

## 6. 边界（明确不做）

| 项 | 决策 | 理由 |
|---|---|---|
| **多容器并行** | 不做 | 单 worker 假设无处不在：JY 的 G 全局状态、图色/截图锁、无障碍服务均为单实例资源；"云控执行须串行"是既有结论。多容器并行是系统性重构，与本设计目标（确定性回收）无关 |
| IDE 沙箱 execute 的容器语义 | 不改 | 沙箱为调试态，LuaState 常驻（脚本间共享状态是调试预期）；lifecycle listener 在沙箱侧注册了也仅在沙箱销毁时触发 |
| 跨脚本状态共享 API | 不做 | 容器间隔离是特性不是缺陷；跨脚本数据应走外部存储/云控通道 |
| 脚本热更新 | 不做 | 与容器模型正交，另行立项 |

## 7. 验证方案

1. **verify_stop_run.py**：run → stop → 再 run 循环（不重启 app）× N 轮，echo 全链正常
2. **残留检查**：每轮 stop 后 `netstat` 确认 9300 已释放（server 随容器回收）
3. **强杀路径**：interrupt 中断执行类 op（pageTest 长任务）→ server 仍被回收
4. **回归**：ws_client --demo 加密 11/11（协议行为不受影响）
