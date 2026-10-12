# 引擎核心契约

> 文档版本 v1.0（2026-09-18，文档三层化重组新建）
> 本域权威范围：引擎生命周期 / 执行调度 / Lua worker 状态机 / 截图管线对外契约；截图共享内存帧协议与注入降级链归 ipc 域，本文不展开。

## 1. Builder 契约

`AutoLuaEngine.Builder` 是引擎接入的唯一构造入口，实现类为 `AutoLuaEngineOnLocal.Builder`。

| 方法签名 | 对码 |
|---|---|
| `addRemoteService(remoteServerConfigure: RemoteServerConfigure): Builder` | （`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngine.kt:201`） |
| `addLocalService(localService: LocalService<*>): Builder` | （`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngine.kt:202`） |
| `addLocalService(name: String, service: Any, thisInterface: Class<*>): Builder` | （`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngine.kt:203`） |
| `addLocalService(name: String, serviceClass: Class<*>, thisInterface: Class<*>): Builder`（CLASS 型，惰性实例化） | （`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngine.kt:204`） |
| `addEnvironment(key: String, value: Any): Builder` | （`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngine.kt:205`） |
| `addCodeProvider(codeProvider: CodeProvider): Builder` | （`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngine.kt:206`） |
| `addResourceProvider(resourceProvider: ResourceProvider): Builder` | （`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngine.kt:207`） |
| `setDisplay(display: Display): Builder` | （`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngine.kt:208`） |
| `setInputManager(inputManager: InputManager): Builder` | （`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngine.kt:209`） |
| `setUiAutomator(uiAutomator: UiAutomator): Builder` | （`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngine.kt:210`） |
| `isRoot(isRoot: Boolean): Builder` | （`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngine.kt:211`） |
| `build(): AutoLuaEngine?` | （`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngine.kt:212`） |

配套数据契约：

- `LocalService(name, type=OBJECT|CLASS, service, mInterface, mClass)`：OBJECT 型须带实例与接口；CLASS 型由引擎在注入时反射实例化（优先 `(LuaContext)` 构造器，其次无参构造器）（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngine.kt:94`，实例化见 `autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:180`）。
- `Environment(key, value)` 支持 STRING/LONG/DOUBLE/BOOLEAN/BYTE_ARRAY/INT/FLOAT 七类，类型自动推断，非法类型抛 IllegalArgumentException（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngine.kt:112`）。
- `RemoteServerConfigure.services` 位掩码：CODE_PROVIDER=0b1 / RESOURCE_PROVIDER=0b10 / OBSERVER=0b100 / CONTROLLER=0b1000（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngine.kt:80`）。
- 执行 flags 常量：`FLAG_NONE=0`、`FLAG_NEW_LUA=0b1`、`FLAG_NOT_RETAIN_LUA=0b10`（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngine.kt:194`）。位值与 C++ `LuaInterpreter::ExecuteFlag` 一一对应（kNewLua=0b1、kRetainLua=0b10，见 `autolua2\engine\src\main\cpp\core\LuaInterpreter.h:28`）；注意命名陷阱——Java 侧叫 `FLAG_NOT_RETAIN_LUA` 的 0b10 位在 C++ 侧叫 `kRetainLua`，行为以 C++ 为准：该位置位时执行结束**不**销毁 lua_State（`autolua2\engine\src\main\cpp\core\LuaInterpreter.cc:248`），未置位时执行后释放重建。

Builder 实现侧约束：

- `build()` 未显式设置 Display/InputManager 时回退 `EmptyDisplay()`/`EmptyInputManager()`（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:460`）。
- CLASS 型 `addLocalService(name, serviceClass, thisInterface)` 必须把接口写入 `mInterface`——`pushLocalServices` 构建 `JavaObjectWrapper` 时依赖它反射方法表，漏写会在创建 LuaContext 时 NPE（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:405`）。
- 代理层 `AutoLuaEngineProxy` 为单例（`instance by lazy`），engine 由 `engineCreator` 惰性创建并自动挂状态监听（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineProxy.kt:20`，ensureEngine 见 `:45`）。
- 服务注入矩阵（三执行环境注册点、16 步注入顺序）权威在 ../../01-架构总览.md §8，此处为域内摘要：本域只负责 `pushLocalServices` 把 Builder 收集的服务包装成 Lua 全局（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:206`）。
- 沙箱 Display 装配契约（虚拟屏任务书 §2）：无 root 降级引擎 `MainApp.buildLocalSandboxEngine` 在 `CaptureScreenService.isRunning()` 为 true 时 `setDisplay(SharedMemoryDisplay())`（共享内存帧管线桥接，帧协议见 ../ipc/contracts.md §⑦），否则不设 Display、由 `build()` 回退 `EmptyDisplay()` 并打点日志（`autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:309`、降级装配点 `:121`，EmptyDisplay 回退 `autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:463`）；root 引擎装配不受影响，仍为 Server.kt 的 `DisplayImplement()`（`autolua2\engine\src\main\java\com\autolua\engine\core\root\Server.kt:141`）；DisplayImplement 建屏内核自虚拟屏任务书 §6.1a 起为 `DisplayControlWrap` 双路径统一入口（失败兜底 SurfaceControlWrap 直调、日志注明分支），基准尺寸经 IWindowManager → DisplayManagerGlobal 两级 fallback，仍失败 initialize 抛可读 RuntimeException（详见 ../ipc/contracts.md §⑤ root 主链路复用建屏内核条目，`autolua2\engine\src\main\java\com\autolua\engine\extension\display\DisplayImplement.java:124`、`autolua2\engine\src\main\java\com\autolua\engine\conceal\IWindowManager.java:67`）。

## 2. 执行控制语义（execute / interrupt / pause / resume / waitForStop）

- `execute(script, flags)` / `execute(script, codeMode, flags)`：非阻塞投递，把代码装入 SignalEvent 的 kExecute 信号后立即返回；worker 非空闲（非 kIdle）返回码 1，已有 kExecute 挂起返回码 2，成功返回 0（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngine.kt:48`，实现 `autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:336`，返回码语义 `autolua2\engine\src\main\cpp\core\LuaInterpreter.cc:108`）。CodeMode 三态 TEXT_OR_BINARY(0)/TEXT(1)/BINARY(2)（`autolua2\engine\src\main\java\com\autolua\engine\base\LuaContext.kt:55`）映射 C++ `"bt"/"t"/"b"` 传给 `luaL_loadbufferx`（C++ 侧映射见 `autolua2\engine\src\main\cpp\core\LuaInterpreter.cc:121`）。
- `interrupt()`：置 kInterrupt 信号并同步取消 thread 协程任务（stopAllThreadTasks）；脚本在 sleep/lock 等挂起点以 lua_error("interrupt") 终止（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:347`，`autolua2\engine\src\main\cpp\core\LuaInterpreter.cc:306`，挂起点抛错 `:341`）。
- `pause()` / `resume()`（三期新增）：JNI 入口返回发信号后的 worker 状态 int（ALEState 风格 0~6）；kPause 为异步生效——脚本走到 sleep 等挂起点才迁移状态，调用方需经 getState 轮询（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:354`，JNI `autolua2\engine\src\main\cpp\engine.cpp:61`，透传 `:157`，信号置位 `autolua2\engine\src\main\cpp\core\LuaInterpreter.cc:368`）。kPause 与 kResume 同时置位时在 `SignalEvent::wait` 谓词内相互抵消（`autolua2\engine\src\main\cpp\core\LuaInterpreter.h:124`）。
- 代理层语义：`AutoLuaEngineProxy.pause()/resume()` 在 engine 未创建时静默忽略，不走 ensureEngine，避免为一次 pause 拉起引擎/root 进程（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineProxy.kt:101`）。
- **代码中不存在名为 blockInterrupt 的独立 API**；「阻塞式中断」语义由信号机制实现：kInterrupt/kQuit 在 `SignalEvent::wait` 谓词中优先返回真（`autolua2\engine\src\main\cpp\core\LuaInterpreter.h:124`），配合 lSleep 挂起点抛错达成阻塞中断效果。阻塞等待引擎线程退出用 `waitForStop()`（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:101`，JNI `autolua2\engine\src\main\cpp\engine.cpp:691`）。
- fire-and-forget 契约：execute/interrupt/pause/resume 在接口上刻意声明为 Unit 返回——root 进程 Client 代理对转发方法统一 fire-and-forget（InvocationHandler 恒返回 null），任何原始类型返回值都会在代理侧拆箱 NPE（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngine.kt:44`，pause 同款注释 `:54`）。
- Lua 侧 `exitScript()`：置 kInterrupt 并抛 lua_error 终止本次执行，loop 收尾走 OnStop→kIdle 正常回收（`autolua2\engine\src\main\cpp\core\LuaInterpreter.cc:322`，注入点 `:292`）。
- 生命周期方法契约：`start(callback)` 返回 0=成功、非 0=已在运行（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:90`）；`destroy()` 需同步防重入，nativePtr 置 0 后释放（`:110`）；`stop()` 仅在 kRunning→kStopping CAS 成功时执行级联停止（`autolua2\engine\src\main\cpp\engine.cpp:166`）。

## 3. worker 状态机状态集（ALEState.h 对码）

C++ 侧 `enum class ALEState:int` 七态（`autolua2\engine\src\main\cpp\core\ALEState.h:7`）：

| 值 | C++ 状态 | Java State（仅 0~3） | 触发点 |
|---|---|---|---|
| 0 | kIdle | IDLE | worker 初始 / 脚本执行收尾（`autolua2\engine\src\main\cpp\core\LuaInterpreter.cc:257`） |
| 1 | kStarting | STARTING | 引擎 start() CAS 迁移（`autolua2\engine\src\main\cpp\engine.cpp:269`） |
| 2 | kRunning | RUNNING | loop 取到 kExecute 进入执行（`autolua2\engine\src\main\cpp\core\LuaInterpreter.cc:177`） |
| 3 | kStopping | STOPPING | stop() CAS 迁移（`autolua2\engine\src\main\cpp\engine.cpp:166`） |
| 4 | kPausing | —（注释保留） | pause 信号置位、未到挂起点（`autolua2\engine\src\main\cpp\core\ALEState.h:12`） |
| 5 | kPaused | —（注释保留） | 挂起点暂停生效 |
| 6 | kResuming | —（注释保留） | resume 已受理 |

约束与陷阱：

- Java `State` 枚举仅定义 0~3，PAUSING/PAUSED 被注释保留；`State.fromInt` 用 `entries.first{}` 精确匹配，收到 4~6 会抛异常——因此 pause/resume JNI 的返回值在 Kotlin 侧只记日志、不转枚举（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngine.kt:145`，`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:352`）。
- 引擎态与 worker 态分离：`getState(target)` 按 Target.kEngine/kWorker/kDebugger 分流（`autolua2\engine\src\main\cpp\engine.cpp:302`，枚举 `autolua2\engine\src\main\cpp\engine.h:42`）。
- 状态回调链：worker 状态变化 → `LuaInterpreterObserver::onStateChange` → JNI 回调 Java `onStateChanged(II)V` → `notifyObservers`（`autolua2\engine\src\main\cpp\engine.cpp:464`，jmethodID 注册 `:110`，Java 侧 `autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:80`）。
- 兜底契约：lua_State 创建失败（Java 侧 companion 异常返回 0）时丢弃本次执行并回 kIdle，保证观察者收到结束事件而非线程崩溃（`autolua2\engine\src\main\cpp\core\LuaInterpreter.cc:185`，JNI 拦截 `autolua2\engine\src\main\cpp\engine.cpp:330`）。

## 4. protoc 生成契约

- proto 源文件：`autolua2\engine\src\main\proto\interaction.proto`、`autolua2\engine\src\main\proto\Authorization.proto`。
- 构建期任务链：`downloadProtoc`（按宿主 OS 下载 protoc 25.3）→ `unzipProtoc` → `generateProto`，且 `preBuild` 依赖 `generateProto`（`autolua2\engine\build.gradle.kts:25`，任务链 `:31`，preBuild 挂接 `:96`）。
- 生成输出：`--cpp_out` 与 `--java_out=lite` 双通道输出到 `build/generated/source/proto`（`autolua2\engine\build.gradle.kts:87`）；Java 生成目录追加进 sourceSets（`:114`）；运行时依赖 `protobuf.javalite`（`:178`）。
- 模块地图（Java/C++ 层全貌）权威在 ../../01-架构总览.md §5 / §6，此处为域内摘要。

## 5. CMake ABI 矩阵

| 项 | 契约值 | 对码 |
|---|---|---|
| ABI | armeabi-v7a / arm64-v8a / x86 / x86_64 四 ABI 全编 | （`autolua2\engine\build.gradle.kts:134`） |
| STL | `-DANDROID_STL=c++_shared` | （`autolua2\engine\build.gradle.kts:131`） |
| 编译开关 | cppFlags `-frtti -fexceptions -std=c++17`；cFlags `-fPIE -fexceptions` | （`autolua2\engine\build.gradle.kts:132`） |
| NDK / CMake | ndkVersion 25.2.9519653；CMake 3.22.1，path=src/main/cpp/CMakeLists.txt | （`autolua2\engine\build.gradle.kts:162`，`:147`） |
| 静态/子目标 | lua_static（排除 lua.c/luac.c/onelua.c）、libhv static、cjson static、remote、autolua_vision static | （`autolua2\engine\src\main\cpp\CMakeLists.txt:17`，`:27`，`:55`，`:63`，`:66`） |
| 库产物 | engine_base=SHARED（base 目录 glob，含 jni_onload 自初始化 G_JavaVM）、engine_core=STATIC（LuaInterpreter 所在）、engine=SHARED 主库 | （`autolua2\engine\src\main\cpp\CMakeLists.txt:38`，`:51`，`:74`） |
| 链接陷阱 | extension 以 `--whole-archive` 强制全量链入——其 JNI 导出函数（SharedFrame/VisionBridge）无 C++ 引用者，普通静态链接会丢弃 obj 导致 UnsatisfiedLinkError | （`autolua2\engine\src\main\cpp\CMakeLists.txt:98`） |
| AIDL | `buildFeatures.aidl = true` 需显式开启（AGP 8 默认关闭），注入降级链 Shizuku UserService 依赖生成的 Stub | （`autolua2\engine\build.gradle.kts:165`） |

构建排障入口见 ../../05-构建与自测指南.md §5（工具链已知坑）。

## 6. 线程模型约束

- worker 线程：首次 execute 时惰性创建并 detach，常驻循环直到 kQuit；结束时执行 workerStopCallback_ 并复位 workerRunning_（`autolua2\engine\src\main\cpp\core\LuaInterpreter.cc:133`，退出清理 `:268`）。
- 引擎主线程：hv::EventLoop 独立线程承载 `onRun`；start/stop 的服务级联经 `runInLoop` 投递到该线程串行执行（`autolua2\engine\src\main\cpp\engine.cpp:142`，`:284`，级联 `:169`，`:273`）。
- JNI 线程挂载：worker 线程创建前 attachJavaThread、退出前 detachJavaThread（`autolua2\engine\src\main\cpp\engine.cpp:79`，挂接 `:132`）。
- lua_State 互斥：`LuaContext.mutex` 为 per-state 递归互斥锁，保护主 worker 与 thread 协程线程对同一 lua_State 的并发访问；执行期 lockLuaState/unlockLuaState 包裹（`autolua2\engine\src\main\cpp\core\LuaInterpreter.cc:226`，`:247`）；sleep 等挂起点必须先解锁再等待、醒来后重新加锁（`:336`）；结构定义见 `autolua2\engine\src\main\cpp\base\lua_context.h:22`。
- 代理串行化：AutoLuaEngineProxy 以单线程 executor + 协程 dispatcher 串行化全部引擎调用（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineProxy.kt:16`）。
- composite 线程安全：MessageObserver 读写锁保护观察者列表（`autolua2\engine\src\main\java\com\autolua\engine\core\composite\MessageObserver.kt:10`）；pushEnvironment/pushLocalServices 以 synchronized 保护（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:142`，`:207`）。
- C++ 层模块地图权威在 ../../01-架构总览.md §6，此处为域内摘要；跨域并发铁律见 ../../06-开发铁律与踩坑手册.md。

## 7. 域边界声明

- 截图共享内存帧协议（SharedFrame 帧头/换帧语义）归 ipc 域文档；本域只约定 Java 侧写入点 `writeFrameSwap`（`autolua2\engine\src\main\java\com\autolua\engine\display\projection\CaptureScreenService.java:423`）。
- 输入注入四级降级链归 ipc 域文档（本域 build.gradle.kts 仅涉及其 AIDL/依赖项）。
- 节点查找（UiAutomator）、IDE 图色远程计算、事件总线的机制与数据流权威在 ../../01-架构总览.md §9.4 / §9.5 / §9.6。
