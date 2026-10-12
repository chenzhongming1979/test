# 引擎核心设计

> 文档版本 v1.0（2026-09-18，文档三层化重组新建）
> 本域权威范围：引擎生命周期 / 执行调度 / Lua worker 状态机 / 截图管线的内部设计；截图共享内存帧协议与注入降级链归 ipc 域文档，本文不展开。

## 架构总述

本域为三层结构：

1. **Java 契约层**：`AutoLuaEngine` 接口定义引擎全部对外能力（生命周期 / 执行控制 / 调试器 / Builder），并内嵌 `CodeProvider` / `ResourceProvider` / `MessageObserver` 三个 SPI 接口与 `LocalService` / `Environment` / `RemoteServerConfigure` 配置载体（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngine.kt:14`）。
2. **Java 实现层**：`AutoLuaEngineOnLocal` 持有 native 指针，构造时同步 `createNative` 并回放远程服务配置（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:53`，init 块 `:56`）；`AutoLuaEngineProxy` 以单线程 executor + 协程 dispatcher 对全部调用串行化，是宿主（app 进程）实际持有的入口（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineProxy.kt:16`）。
3. **C++ 服务层**：`AutoLuaEngineService`（engine.cpp/engine.h）组合 hv::EventLoop（服务生命周期线程）、`LuaInterpreter`（worker 状态机）、ServiceManager / DebugService / FatherService（远程与调试通道）、Display / InputManager 扩展（`autolua2\engine\src\main\cpp\engine.h:23`，组装见 `autolua2\engine\src\main\cpp\engine.cpp:120`）。

关键设计决策：

- **Builder 收集的本地服务不随构造注入**，而是延迟到每个新 LuaContext 创建完成后的 `postNativeInject` 回调——Java 服务必须在原生图色函数（injectImageLib）就绪后注入，否则 compat 包装层捕获 getColor/isColor/findColor 会拿到 nil，cmpColor 断链（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:256`，C++ 侧回调点 `autolua2\engine\src\main\cpp\engine.cpp:349`）。
- **状态双轨**：引擎态（engine 级 atomic state_）与 worker 态（LuaInterpreter 内 state_）分离，经 `getState(target)` 按 kEngine/kWorker/kDebugger 分流（`autolua2\engine\src\main\cpp\engine.cpp:302`）。
- **单一 worker**：所有脚本在一个常驻 worker 线程上串行执行，通过信号（SignalEvent 位集）驱动，而非每脚本一线程（`autolua2\engine\src\main\cpp\core\LuaInterpreter.cc:150`）。
- 进程模型与三执行环境（root 主引擎 / IDE 沙箱 / 脚本 IDE 调试）的全局图景权威在 ../../01-架构总览.md §4 与 §8，本文为域内视角。

## 核心机制

### 1. worker 调度与执行管线

执行调用链（Java → C++）：

- `execute(script, flags)` → JNI `executeNative` → `AutoLuaEngineService::execute` → `LuaInterpreter::execute`（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:335`，`autolua2\engine\src\main\cpp\engine.cpp:40`，`:202`）。
- `LuaInterpreter::execute` 仅做投递：非 kIdle 返回 1、已挂 kExecute 返回 2；把代码与 CodeMode（"bt"/"t"/"b"）装入成员，置 kExecute 信号；worker 线程不存在则创建 detached loop 线程，否则 notify 唤醒（`autolua2\engine\src\main\cpp\core\LuaInterpreter.cc:108`，线程惰性创建 `:133`）。

loop 主循环（worker 线程，常驻直至 kQuit）：

1. `SignalEvent::wait` 等待信号；kQuit 退出、kAwake/kInterrupt 清位跳过、其余仅接受 kExecute（`autolua2\engine\src\main\cpp\core\LuaInterpreter.cc:150`，信号谓词 `autolua2\engine\src\main\cpp\core\LuaInterpreter.h:124`）。
2. 置 worker 态 kRunning 并广播（`autolua2\engine\src\main\cpp\core\LuaInterpreter.cc:179`）。
3. LuaState 准备：无 state 则 `resetLuaState`（经 LuaStateFactory 走第 3 节注入管线）；kNewLua 置位则先 release 再重建；创建失败（返回 nullptr）时丢弃本次执行、回 kIdle 广播，绝不以空 lua_State 继续（`autolua2\engine\src\main\cpp\core\LuaInterpreter.cc:182`，kNewLua 分支 `:200`，失败兜底 `:185`）。
4. 执行：`lockLuaState` → `luaL_loadbufferx`（段名 bootstrap，模式取 CodeMode 映射）→ `lua_pcall` → 失败记日志 → 回调全局 `OnStop`（存在才调）→ Callback（结果栈索引）→ 恢复栈顶 → `unlockLuaState`（`autolua2\engine\src\main\cpp\core\LuaInterpreter.cc:226`，load `:227`，call `:230`，OnStop `:236`）。
5. 收尾：kRetainLua 未置位则 `releaseLuaState`；清空代码与回调；worker 态回 kIdle 并广播；回到 wait（`autolua2\engine\src\main\cpp\core\LuaInterpreter.cc:248`，`:259`）。
6. 线程退出：执行 releaseLuaThreadCallback（detach JVM）、清 kQuit、复位 workerRunning_、触发 workerStopCallback_（`autolua2\engine\src\main\cpp\core\LuaInterpreter.cc:268`）。

挂起点（协程并行的缝隙）：

- `sleep`（lSleep）先 `unlockLuaState` 释放状态锁，再 `wait_for` 等待超时或信号，醒来重新加锁；等待结果含 kQuit/kInterrupt 则抛 lua_error 终止脚本——这也是 pause/interrupt 的实际生效点（`autolua2\engine\src\main\cpp\core\LuaInterpreter.cc:332`）。
- thread 库协程在 sleep/lock 挂起点让出，期间与主 worker 并行访问同一 lua_State，由 per-state 递归锁保护（`autolua2\engine\src\main\cpp\base\lua_context.h:22`）。

### 2. worker 状态机

- 状态集：C++ `ALEState` 七态 kIdle=0 / kStarting=1 / kRunning=2 / kStopping=3 / kPausing=4 / kPaused=5 / kResuming=6（`autolua2\engine\src\main\cpp\core\ALEState.h:7`）；Java `State` 仅 0~3，4~5 注释保留（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngine.kt:146`）——差异与陷阱详见 [contracts.md](./contracts.md) §3。
- 迁移点：kIdle→kRunning 在 loop 取到 kExecute（`autolua2\engine\src\main\cpp\core\LuaInterpreter.cc:179`）；kRunning→kIdle 在执行收尾（`:259`）；创建失败兜底直接回 kIdle（`:185`）。
- 引擎级状态：`start()` 以 CAS kIdle→kStarting 后在 EventLoop 线程通知 Starting → 启动 debugService/serviceManager → 通知 Running（`autolua2\engine\src\main\cpp\engine.cpp:269`）；`stop()` 以 CAS kRunning→kStopping 后级联停服务并 interrupt（`:166`）。
- 观察者广播：`notifyStateChange` 遍历弱引用观察者并顺带清理失效项（`autolua2\engine\src\main\cpp\core\LuaInterpreter.cc:348`）；`LuaInterpreterObserver::onStateChange` 转发 debugService/fatherService 并 JNI 回调 Java `onStateChanged(II)V`（`autolua2\engine\src\main\cpp\engine.cpp:464`）；Java 侧转枚举后 notifyObservers（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:80`）。

### 3. LuaContext 创建与注入管线（Java↔C++ 协作）

`LuaStateFactory::create` 是每次新 lua_State 的总装线，顺序敏感（`autolua2\engine\src\main\cpp\engine.cpp:327`）：

1. JNI 回调 Java `newLuaContext():Long`（jmethodID 注册 `autolua2\engine\src\main\cpp\engine.cpp:103`）→ Java `rawNewLuaContext`：创建 `LuaContextImplement` 入 contextCache、pushEnvironment（七类型环境变量逐个 push+setGlobal）、可选 uiAutomator 注入 UiSelector/getRootInActiveWindow/setText 全局、pushTable KeyCode 与 LayoutParamsFlag（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:296`，环境推送 `:141`）。
2. 返回 0（Java 侧 companion 异常）时 LOGE 并中止创建，防 NULL lua_State 段错误掩盖真实异常（`autolua2\engine\src\main\cpp\engine.cpp:330`）。
3. 非空 rootDir 时设置 Lua 全局 `ROOT_DIR`（`autolua2\engine\src\main\cpp\engine.cpp:337`）。
4. `injectOther(L)`（base 层通用注入）。
5. `display_.injectToLua` / `inputManager_.injectToLua`（`autolua2\engine\src\main\cpp\engine.cpp:343`）。
6. `injectImageLib` / `injectWsLib`（原生图色与 WS 库）（`autolua2\engine\src\main\cpp\engine.cpp:345`）。
7. JNI 回调 `postNativeInject(J)V` → Java `pushLocalServices`：wrapLocalService（OBJECT 直用 / CLASS 反射实例化，非 LuaObjectAdapter 的包 JavaObjectWrapper）、实现 `LuaEnvContributor` 的服务回调 contribute 注册全局、最后 PluginManager.contributeLua 注入插件全局（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:206`，contributor 判定用原始实例的说明 `:213`，插件贡献 `:229`）。此步必须在第 6 步之后，见架构总述决策一。
8. debugService/fatherService setup 与 serviceManager setup 收尾（`autolua2\engine\src\main\cpp\engine.cpp:350`）。

配套机制：

- worker 拦截方法注入：resetLuaState 后注入 sleep/require/loadfile/loadresource/exitScript 五个 lua_CFunction，并把 signalEvent/interpreter 回填进 LuaContext（extraspace）（`autolua2\engine\src\main\cpp\core\LuaInterpreter.cc:277`）。
- `LuaContext` 结构承载 Java 对象引用、jmethodID（index/newIndex/call/invoke/release）、signalEvent/interpreter/display/mutex 指针，经 `lua_getextraspace` 以 `toLuaContext` 存取（`autolua2\engine\src\main\cpp\base\lua_context.h:10`，`:32`）。
- 释放：LuaStateFactory::release → JNI `releaseContext(J)V` → Java releaseContext 从 contextCache 移除并 destroy（`autolua2\engine\src\main\cpp\engine.cpp:356`，`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:324`）。

### 4. 代码/资源供给链（composite 回调）

require/loadfile/loadresource 的跨语言回环：

1. Lua 侧 `require`/`loadfile`/`loadresource` 进拦截函数 lRequire/lLoadFile/lLoadResource（`autolua2\engine\src\main\cpp\core\LuaInterpreter.cc:390`，`:398`，`:435`）。
2. 经 `InterpreterCodeProvider::loadModule/loadFile`、`InterpreterResourceProvider::loadResource` 发起 JNI 回调（`autolua2\engine\src\main\cpp\engine.cpp:495`，`:542`，`:592`；jmethodID 注册 `:106`）。
3. Java 侧命中类级委托 `CodeProvider by codeProvider` / `ResourceProvider by resourceProvider`，由 composite 依注册顺序链式查找首个非空（`autolua2\engine\src\main\java\com\autolua\engine\core\composite\CodeProvider.kt:5`，`autolua2\engine\src\main\java\com\autolua\engine\core\composite\ResourceProvider.kt:7`，委托声明 `autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:42`）。
4. 结果以 `Code{nType, code}` 对象经 JNI 字段回传（字段 ID 注册 `autolua2\engine\src\main\cpp\engine.cpp:114`）。

源汇：Builder 的 addCodeProvider/addResourceProvider 进 composite（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:425`，`:431`）；远程服务侧 provider 由 ServiceManager 并入 interpreter（`autolua2\engine\src\main\cpp\engine.cpp:138`）。

消息与观察：onWarning/onError 转发 composite MessageObserver（读写锁保护，`autolua2\engine\src\main\java\com\autolua\engine\core\composite\MessageObserver.kt:24`）；interpreter 同时 attach serviceManager 的 observer 以便远程通道感知 worker 状态（`autolua2\engine\src\main\cpp\engine.cpp:135`，`:140`）。

### 5. 执行控制信号（interrupt / pause / resume）

契约语义（返回码、fire-and-forget、blockInterrupt 说明）权威见 [contracts.md](./contracts.md) §2；实现要点：

- interrupt：ensureHasSignal(kInterrupt) + stopAllThreadTasks 同步取消协程任务（`autolua2\engine\src\main\cpp\core\LuaInterpreter.cc:306`）。
- pause/resume：仅置 kPause/kResume 信号（`:368`，`:372`），真正的状态迁移发生在 sleep 等挂起点；kPause+kResume 同时置位时在 wait 谓词内相互抵消（`autolua2\engine\src\main\cpp\core\LuaInterpreter.h:124`）。
- Lua 侧 exitScript：置 kInterrupt 并抛错，loop 走正常收尾（`autolua2\engine\src\main\cpp\core\LuaInterpreter.cc:325`）。

### 6. 启动、停止与销毁

- 构造：Java 构造函数内 createNative 同步建 C++ 服务并回放 addRemoteServiceNative；System.loadLibrary("engine")（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:56`，`:64`）。
- start/stop：见第 2 节引擎级状态；EventLoop 线程在构造时启动并 run（`autolua2\engine\src\main\cpp\engine.cpp:142`，`:284`）。
- destroy：stop → eventLoop stop → interpreter destroy（置 destroy_ 并唤醒）→ 释放 thiz/clazz/codeClazz 弱全局引用 → join worker 线程（`autolua2\engine\src\main\cpp\engine.cpp:179`）；Java 侧同步防重入、nativePtr 置 0（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:110`）。
- 代理层：`startTask` 串联 loadfile init.lua / main.lua 作为宿主启动脚本（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineProxy.kt:30`）；startDebugger 在 IDLE 态时自动先 start 引擎（`:115`）。

## 截图管线

浓缩版（细节、版本适配分支与验收标准见本域 [INTEGRATION_PLAN.md](./INTEGRATION_PLAN.md) §2；数据流权威 ../../01-架构总览.md §9.2；共享内存帧协议归 ipc 域文档）：

- **App 模式常驻采集**：`CaptureScreenService` 为前台服务，MediaProjection + ImageReader 常驻采集。启动序：拉起前台服务 → onStartCommand 先 startForeground 再 getMediaProjection（Android 14 强制顺序）（`autolua2\engine\src\main\java\com\autolua\engine\display\projection\CaptureScreenService.java:223`，启动入口 `:273`，获取投影 `:302`）。
- **采集装配**：`initScreenshotImageReader` 建 ImageReader.newInstance(RGBA_8888, maxImages=8 抗瞬时峰值) + createVirtualDisplay("AutoLuaShot", 17=PUBLIC|AUTO_MIRROR)（`autolua2\engine\src\main\java\com\autolua\engine\display\projection\CaptureScreenService.java:369`）。
- **帧回调**：onImageAvailable 先以 `reader != activeImageReader` 指针一致性守卫过滤重建期的旧回调，再 acquireLatestImage 取最新帧，`writeFrameSwap` 写入共享内存帧池（`autolua2\engine\src\main\java\com\autolua\engine\display\projection\CaptureScreenService.java:399`，守卫 `:406`，写帧 `:423`）。
- **旋转与中断自愈**：旋转时 recreateImageReader 原地换 surface（旧 reader 置空 activeImageReader 再重建）（`autolua2\engine\src\main\java\com\autolua\engine\display\projection\CaptureScreenService.java:660`）；MediaProjection onStop 后 600ms 用保存的授权令牌重建一次（`:343`）。
- **模式管理**：ScreenshotProviderManager 管理采集模式（ROOT 通道由 RootScreenshotProvider 提供），startProjection 仅在非 ROOT 模式走投影（`autolua2\engine\src\main\java\com\autolua\engine\display\projection\ScreenshotProviderManager.java`）。
- **投屏推流**：`ScreenStreamController` 单例，双 codec——h264 主力（MediaCodec COLOR_FormatSurface 硬编 createInputSurface，SPS/PPS 转 avcC 作 flag=0 首帧下发，WebCodecs 直接作 description）与 jpeg 保底；单推流线程（优先级 -2）取帧→编码→发送全程同线程，异常不中断循环；会话 stream_start 顶替旧订阅、连续发送失败自动结束；旋转发 stream_rotate 事件并惰性重建编码器；stream_sync 仅置标志由推流线程执行 request-sync-frame（`autolua2\engine\src\main\java\com\autolua\engine\display\stream\ScreenStreamController.java:40`，类声明 `:60`，会话参数 `:91`，IDR 请求 `:140`，startSession `:160`，stopSession `:210`）。
- **帧字节序决策（虚拟屏任务书 §2，2026-09-18）**：共享内存管线帧为 RGBA、R 在 [0]（`autolua2\engine\src\main\cpp\extension\VisionBridge.cpp:12` 起的头注释口径，已验证）；而 C++ Display 链路按历史口径 BGRA 处理——PNG 导出逐像素交换 R/B（`autolua2\engine\src\main\cpp\extension\Display.cpp:89`）、root 镜像 `DisplayImplement.isRgbaOrder()` 返回 false（`autolua2\engine\src\main\java\com\autolua\engine\extension\display\DisplayImplement.java:291`）。两链路字节序约定相反，适配取 **per-instance**：新增 `SharedMemoryDisplay.isRgbaOrder()` 返回 true（`autolua2\engine\src\main\java\com\autolua\engine\extension\display\SharedMemoryDisplay.java:232`），Display.cpp 暂不感知该位、root 链路位级行为不变；管线帧经 SharedMemoryDisplay 通道的实际内存序待真机实测复核（ImageReader 实际输出序 vs 注释口径），复核后在 Display.cpp 单点统一（决策依据见 ../../plans/虚拟屏功能实施任务书.md §2）。

### 演进：截图/建屏 Provider 收敛评估（虚拟屏任务书 §6.2b，2026-09-18）

> **评估结论，落地另立批次。** 本节只做四条帧链路的收敛评估，不改代码。

四条链路差异对账：

| 链路 | 建屏内核 | 帧协议 | 消费端 |
|---|---|---|---|
| DisplayImplement（root 主链路，直读） | §6.1a 起复用 `DisplayControlWrap` 双路径统一入口（兜底 SurfaceControlWrap 直调）（`autolua2\engine\src\main\java\com\autolua\engine\extension\display\DisplayImplement.java:125`、`:131`、兜底 `:155`） | ImageReader(RGBA_8888,3) 直读 DirectByteBuffer，C++ Display 经 JNI 直取，无中间拷贝；字节序 BGRA 历史口径（isRgbaOrder=false，`DisplayImplement.java:291`） | C++ Display（图色 / PNG / transit 全家，`autolua2\engine\src\main\cpp\extension\Display.cpp:41`） |
| RootScreenshotProvider（root 辅链路，共享内存） | 三级 fallback：DisplayManagerGlobal 隐藏 API → 公开 DisplayManager → SurfaceControlWrap 四件套（`autolua2\engine\src\main\java\com\autolua\engine\display\projection\RootScreenshotProvider.java:191`） | ImageReader → RGBA byte[]（快/慢双路径）→ `writeFrameSeqlock` 固定写 A（`RootScreenshotProvider.java:583`）；旋转感知 §6.2a 起 DisplayListener 主路径 + 500ms 轮询回退（`:89`、`:496`、`:520`） | ScreenshotProviderManager/FrameBuffer（IDE 工具链取帧裁剪，`autolua2\engine\src\main\java\com\autolua\engine\display\projection\ScreenshotProviderManager.java:107`） |
| SharedMemoryDisplay（§2 沙箱消费端） | 无建屏（消费 CaptureScreenService MediaProjection flags=17 主屏镜像，装配契约见 ../engine-core/contracts.md §1） | seqlock A/B 读协议：读前后两次 counter 一致性校验 + ping-pong 块校验，READ_RETRY=8（`autolua2\engine\src\main\java\com\autolua\engine\extension\display\SharedMemoryDisplay.java:104`） | 引擎 Display 接口实现 → C++ Display（isRgbaOrder=true，`SharedMemoryDisplay.java:232`） |
| VdFrameClient（§4.4 Shizuku 按屏） | UserService 进程内 `DisplayControlWrap` 建屏 + 四件套（服务端 `autolua2\engine\src\main\java\com\autolua\engine\input\inject\ShizukuUserServiceMain.java:249`，契约见 ../ipc/contracts.md §⑤） | per-display 独立 A/B MemoryFile + binder 轮询 counter 的跨进程 seqlock（`autolua2\engine\src\main\java\com\autolua\engine\extension\virtualdisplay\VdFrameClient.java:112`，attach `:61`） | Lua virtualDisplay 的 vd 对象图色（坐标绑定各自屏） |

收敛路线建议：

1. **建屏内核收敛到 `DisplayControlWrap` 单点**：root 主链路（§6.1a）与 Shizuku UserService（§4.1）已统一；RootScreenshotProvider 三级 fallback 的第三级（SurfaceControlWrap 四件套，`RootScreenshotProvider.java:191`）可替换为 DisplayControlWrap——第一二级建出的是 `VirtualDisplay` 句柄（支持 resize/release），语义不同应保留，仅兜底层收敛。
2. **帧协议收敛到 SharedMemoryManager seqlock/ping-pong 单协议**：root 辅链路、沙箱、按屏三链路已同协议（ipc/contracts.md §⑦）；唯一异类是 DisplayImplement 直读——它是 C++ 图色热路径唯一零拷贝通道，改走共享内存需引入全帧拷贝 + JNI 下沉改造。
3. **消费端保持 `Display` 接口多实现**：SharedMemoryDisplay / DisplayImplement 已同接口，C++ 侧经 `isRgbaOrder()` 逐实例适配字节序（见上节帧字节序决策），无需合并类层次。

不动理由与风险：

- DisplayImplement 直读改共享内存协议：收益（少一份建屏代码）不抵开销（每帧全量拷贝、C++ 侧时序重构、root 链路整体回归），且 root 链路已降为维护项（任务书 §6 前提拍板），无功能缺口驱动；
- SharedMemoryManager 当前单例单屏（`autolua2\engine\src\main\java\com\autolua\engine\display\sharedmem\SharedMemoryManager.java:29`），若承载 root 主链路需支持进程内多实例/命名隔离——§4.4 已在 UserService 进程以独立 MemoryFile 组绕开，进程内多实例改造涉及 fd 下沉协议（§⑦ setSharedMemoryInfo）变更；
- DisplayImplement 与 RootScreenshotProvider 能力重叠但服务不同入口（C++ Display vs ScreenshotProviderManager），合并需先统一 root 进程内两套初始化时序（InjectorCore 环境伪造先后），回归面覆盖 05 全量自测，风险大于当期收益。


## 数据流

1. **脚本执行流**（进程内）：Java execute → executeNative → service->execute → LuaInterpreter::execute 置 kExecute → loop：resetLuaState（第 3 节注入管线）→ lockLuaState → loadbufferx/pcall → OnStop → callback → unlock → 按需 releaseLuaState → kIdle 广播（`autolua2\engine\src\main\cpp\core\LuaInterpreter.cc:150`）。root 引擎进程的跨进程变体（Server.kt initEngine 16 步注入）权威在 ../../01-架构总览.md §8 / §9.1。
2. **require/资源流**：Lua → lRequire/lLoadFile/lLoadResource → JNI 回调 Java composite 链 → Code{nType,code} 回传 → luaL load（`autolua2\engine\src\main\cpp\engine.cpp:495`）。
3. **状态回调流**：C++ notifyStateChange → Observer → JNI onStateChanged(II)V → Java Observable → 宿主监听（`autolua2\engine\src\main\cpp\engine.cpp:464`）。
4. **截图帧流**：VirtualDisplay → ImageReader 回调 → acquireLatestImage → writeFrameSwap 共享内存 → Native 图色库消费；投屏支路：帧循环 → 编码 → ConnectionSender 定向发送（`autolua2\engine\src\main\java\com\autolua\engine\display\projection\CaptureScreenService.java:399`）。帧头/换帧协议归 ipc 域文档。
5. **IDE 图色 / 节点查找 / 事件总线 / 输入注入**：权威在 ../../01-架构总览.md §9.3 / §9.4 / §9.5 / §9.6，本域不重复。

## 代码映射表

| 层 | 文件 | 职责 | 关键行 |
|---|---|---|---|
| Java 契约 | `autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngine.kt` | 引擎接口、Builder、SPI、flags、State/Target/ResultCode | execute `:48`，State `:146`，Builder `:200` |
| Java 实现 | `autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt` | JNI 桥、环境/服务注入、执行控制、Builder 实现 | createNative `:53`，pushLocalServices `:206`，postNativeInject `:256`，rawNewLuaContext `:296` |
| Java 代理 | `autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineProxy.kt` | 单线程串行代理、惰性建引擎 | executor `:16`，ensureEngine `:45` |
| composite | `autolua2\engine\src\main\java\com\autolua\engine\core\composite\CodeProvider.kt` / `ResourceProvider.kt` / `MessageObserver.kt` | 链式代码/资源查找、消息广播 | 查找 `CodeProvider.kt:5`，广播 `MessageObserver.kt:24` |
| C++ 服务 | `autolua2\engine\src\main\cpp\engine.cpp`（类定义 `engine.h`） | JNI 全集、EventLoop、服务级联、LuaStateFactory | 构造 `engine.cpp:92`，start `:269`，stop `:166`，LuaStateFactory::create `:327`，onStateChange `:464`；Target 枚举 `engine.h:42` |
| worker | `autolua2\engine\src\main\cpp\core\LuaInterpreter.cc`（.h） | 信号驱动的脚本 worker、状态机、挂起点 | execute `:108`，loop `:150`，lSleep `:332`，pause `:368`；SignalEvent `LuaInterpreter.h:35` |
| 状态集 | `autolua2\engine\src\main\cpp\core\ALEState.h` | 七态枚举 | `:7` |
| Lua 上下文 | `autolua2\engine\src\main\cpp\base\lua_context.h` / `lua_context.cpp` | LuaContext 结构、extraspace 存取、Java 对象桥（call/invoke/index） | 结构 `lua_context.h:10`，toLuaContext `:32`，callMethod `lua_context.cpp:420` |
| 截图采集 | `autolua2\engine\src\main\java\com\autolua\engine\display\projection\CaptureScreenService.java` | MediaProjection 常驻采集、旋转重建、共享内存写帧 | startProjection `:273`，装配 `:369`，帧回调 `:399` |
| 采集模式 | `autolua2\engine\src\main\java\com\autolua\engine\display\projection\ScreenshotProviderManager.java`、`RootScreenshotProvider.java`、`ScreenShareServiceImpl.java`（AIDL：`autolua2\engine\src\main\aidl\com\autolua\engine\display\projection\IScreenShareService.aidl`） | 模式切换与 Root 通道 | — |
| 投屏 | `autolua2\engine\src\main\java\com\autolua\engine\display\stream\ScreenStreamController.java` | h264/jpeg 推流会话 | 会话 `:160`，IDR `:140` |
| 构建 | `autolua2\engine\build.gradle.kts`、`autolua2\engine\src\main\cpp\CMakeLists.txt` | protoc 生成、ABI、CMake 目标 | generateProto `build.gradle.kts:67`，abiFilters `:134`；engine_base `CMakeLists.txt:38`，主库 `:74` |

## 演进与存档

- **方案全案存档**：本域 [INTEGRATION_PLAN.md](./INTEGRATION_PLAN.md) 收录方案一~八——与本文直接相关的是 §2 截图管线重构（`./INTEGRATION_PLAN.md` :77 起）、§8 Android 版本适配总表（:696 起，截图向适配分支见 §2.4）、§9 性能优化清单（:719 起）；方案三~八（无障碍整树、进程保活、模拟输入法、IDE 调试 WS、远程控制投屏）分别归属各自域。
- **三期 pause/resume**：已落地（接口 `autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngine.kt:59`，JNI `autolua2\engine\src\main\cpp\engine.cpp:61`），语义见 [contracts.md](./contracts.md) §2。
- **计划文档占位**：跨域独立实施计划规划统一收纳于仓库文档树的 `docs/plans/` 目录（占位，当前尚未创建）；创建后本域新增独立计划（非存档类）应移入该目录，并在本节登记索引。
- 上层权威：架构总览 [../../01-架构总览.md](../../01-架构总览.md)（§4 进程模型 / §5 §6 模块地图 / §8 注入矩阵 / §9 数据流 / §10 机制索引）；构建自测 [../../05-构建与自测指南.md](../../05-构建与自测指南.md)；跨域铁律 [../../06-开发铁律与踩坑手册.md](../../06-开发铁律与踩坑手册.md)。
