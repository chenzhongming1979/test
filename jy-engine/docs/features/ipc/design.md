# 跨进程通道设计

> 文档版本 v1.0（2026-09-18，文档三层化重组新建）
> 本域权威范围：ipc 域各通道的设计动机、架构形态与决策依据；逐条契约见 [contracts.md](contracts.md)，细节全案见 [../engine-core/INTEGRATION_PLAN.md](../engine-core/INTEGRATION_PLAN.md) §2/§3/§4/§6。

## 架构总述（双进程模型）

```
┌─ app 进程（悬浮窗权限所在进程，com.autolua.autolua2）─┐   ┌─ root 进程（su + app_process 拉起）─┐
│ MainApp                                              │   │ Server.main（引擎宿主 + Lua 运行时）  │
│  └ Client.Builder                                    │   │  └ AutoLuaEngineOnLocal              │
│     ├ RootProcessStarter（su+app_process 拉起）       │   │     ├ LuaInterpreter / C++ 引擎       │
│     ├ TransportImp ←帧协议→ TransportImp             │   │     ├ InjectorCore / 注入降级链        │
│     ├ ClientInvocationHandler（动态代理 AutoLuaEngine）│   │     ├ SharedMemoryManager（帧写侧）    │
│     ├ ServiceManagerImp（app 侧本地服务表）           │   │     └ ServiceManagerImp（root 服务表）  │
│     └ UiMgrImp / IdeLogService / UserInterfaceImp    │   │        UiLib（ui_* Lua 层，消费 uimgr）│
└──────────────────────────────────────────────────────┘   └──────────────────────────────────────┘
  通道：① root RPC 帧协议（stdin/stderr 对） ② pollEvents UI 事件轮询 ③ 注入降级链
        ④ AIDL AuxDumpService ⑤ 共享内存 seqlock 帧 ⑥ 隐藏 API 反射镜像 ⑦ 输入法广播通道
```

- root 进程由 app 侧 `RootProcessStarter` 经 `su` 写入命令行拉起：`export LD_LIBRARY_PATH` → `export CLASSPATH=<apk>` → `app_process64 /system/bin <Server 类名>`（优先 64 位 lib 目录，支持 `--nice-name` 改进程名）（`autolua2\engine\src\main\java\com\autolua\engine\core\root\RootProcessStarter.kt:9`、`RootProcessStarter.kt:56`、`RootProcessStarter.kt:62`）。
- 服务双向：root 调 app（UI/IdeLog 等经 InvokePackage 远程代理），app 调 root（execute/attach 等经动态代理 `AutoLuaEngine`）——同一对帧管道承载双向 RPC，靠 type 字节区分请求/响应（`autolua2\engine\src\main\java\com\autolua\engine\core\root\Client.kt:198`、`Server.kt:84`）。
- 跨进程明细：脚本执行数据流见 [01-架构总览.md](../../01-架构总览.md) §9.1；进程职责划分见 §4；服务注入矩阵见 §8。

## root RPC 链路

- 握手：app `Builder.build()` 先拉起 root 进程，再marshall 初始化包（environments/localServices/remoteServices）`rawWrite` 一次性写入；root `init()` 解析后 `rawWrite("ok")`（或 "abort"）应答，app 收到非 "ok" 即放弃（`autolua2\engine\src\main\java\com\autolua\engine\core\root\Client.kt:112`、`Server.kt:201`、`Client.kt:131`）。
- 调用路径（app→root）：动态代理 `invoke` → `writeByte(1)+writeValue(InvokePackage)` → `transporter.send`；root 侧 `initTransporter` 收帧解 InvokePackage → 反射 `AutoLuaEngine` 方法 → 回写响应帧；接口返回值注册为 root 本地服务回传 Long id（`autolua2\engine\src\main\java\com\autolua\engine\core\root\Client.kt:287`、`Server.kt:51`、`Server.kt:257`）。
- 调用路径（root→app）：root 侧 `ServiceInvocationHandler` 把 app 注入服务包成动态代理，调用走同一帧协议反向发 InvokePackage（serviceId 为 app 侧分配的 UInt）；app 侧 `onHandlerRequest` 在自己的 ServiceManagerImp 中寻址执行（`autolua2\engine\src\main\java\com\autolua\engine\core\root\Server.kt:275`、`Client.kt:237`）。
- 超时与防火：root→app 的 IdeLog 日志调用 fire-and-forget（脚本 print 高频，同步等回包会卡死 worker）；其余远程调用 15s 超时上限，超时抛脚本错误而非永久阻塞（`autolua2\engine\src\main\java\com\autolua\engine\core\root\Server.kt:247`、`Server.kt:252`）。
- 读线程纪律：root 侧收帧用 2 线程池（execute 长任务占线程），app 侧强制顺序派发——双线程同时阻塞在广播上会拖垮读线程，管道积压后反向卡死 root 写线程（自测 8-14 章复现）；此类坑汇总见 [06-开发铁律与踩坑手册.md](../../06-开发铁律与踩坑手册.md) §3（`autolua2\engine\src\main\java\com\autolua\engine\core\root\TransportImp.kt:173`）。
- 销毁：app `destroy` → root latch.countDown → `engine.stop()` → sleep(3s) → 写 "exit"；app 侧 releaseThread 等待 3s 后强杀进程（`autolua2\engine\src\main\java\com\autolua\engine\core\root\Server.kt:39`、`Client.kt:310`）。

## UI 事件通道

链路：app 控件 listener（全量常挂）→ `UiMgrImp` 事件队列 → root Lua `UiLib` 内嵌 ui.lua 的 50ms 轮询协程唯一消费 `pollEvents` → 按 (win,id,event) 路由到 `ui.bind` 回调/弹窗等待者。

- **为何 String 收发**：uimgr 服务接口全部收发 JSON 字符串（`op(json):String`、`pollEvents():String`）——跨进程动态代理层对参数/返回类型做 Parcel writeValue 序列化，复杂嵌套对象跨进程重建极易出错；单字符串契约简单可观测（日志直读），且与 Lua 侧 cjson 无缝（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\UiMgrImp.kt:92`、`UiMgrImp.kt:80`、`autolua2\engine\src\main\java\com\autolua\engine\extension\ui\UiLib.kt:56`）。
- **cap 256**：事件队列 `MAX_EVENTS=256`，满则丢最旧——控件 listener 全量常挂（不做动态挂卸），未绑定事件直接丢弃不回压；256 足够缓冲一次界面爆发（列表滚动 itemClick 风暴）而不至于内存膨胀，极端丢帧优于阻塞 UI 主线程（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\UiMgrImp.kt:42`、`UiMgrImp.kt:75`、`autolua2\engine\src\main\java\com\autolua\engine\extension\ui\UiLib.kt:17`）。
- **50ms 轮询**：dispatch 协程每轮 `pollEvents` 后 `thread.sleep(50)`——事件是拉模式（非推送），50ms 在操作手感和跨进程调用频次间取折中（20 次/秒）；同步 op（建窗/弹窗）不经过队列无此延迟（`autolua2\engine\src\main\java\com\autolua\engine\extension\ui\UiLib.kt:122`）。
- **dispatcher 退出条件**：`ui.shutdown()` 置 `_stop=true` 立即停轮询；或自动退出——无绑定回调、无弹窗等待者且连续 3s 无事件（`_quiet += 50` 累加至 3000 break），让引擎回 idle（`autolua2\engine\src\main\java\com\autolua\engine\extension\ui\UiLib.kt:137`、`UiLib.kt:116`）。
- **每事件独立协程**：路由命中后 `thread.start` 新协程执行回调，回调内可阻塞（再弹窗/再调 ui_*）互不饿死（`autolua2\engine\src\main\java\com\autolua\engine\extension\ui\UiLib.kt:89`）。
- 服务注册：`uimgr` 以 app 进程实例（`UiMgrImp.instance`）注册进 root 引擎 Builder 与 IDE 沙箱（`autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:118`、`MainApp.kt:163`）；脚本侧 API 见 [02-Lua-API规范.md](../../02-Lua-API规范.md) §17.1。

## 注入降级链

四通道：DIRECT_ROOT（uid==0 进程内反射，零 fork）→ ROOT（app_process 常驻子进程 stdin/stdout 行协议）→ SHIZUKU（UserService，shell uid 2000）→ ACCESSIBILITY（GestureDescription，API24+ 免 root 兜底）。全案设计见 [../engine-core/INTEGRATION_PLAN.md](../engine-core/INTEGRATION_PLAN.md) §3（含 §3.3 root 注入 stdin/stdout 行协议），数据流见 [01-架构总览.md](../../01-架构总览.md) §9.3。

- **决策顺序（start 择优）**：uid==0 且反射可达 → DIRECT_ROOT（最强，免进程间转发）；否则 Shizuku 已授权 → SHIZUKU；否则 su 探测成功 → ROOT；最后无障碍已连接 → ACCESSIBILITY；全不满足 → NONE。手动 `setPreferredMode` 可覆盖自动链（`autolua2\engine\src\main\java\com\autolua\engine\input\inject\InjectShellClient.java:145`、`InjectShellClient.java:128`）。
- **运行时降级**：某动作在当前通道失败时，按 DIRECT_ROOT → ROOT → SHIZUKU → ACCESSIBILITY 降级序尝试其余"已就绪"通道（不惰性拉起未启动通道，防 su 探测拖慢失败路径），成功即换道（`autolua2\engine\src\main\java\com\autolua\engine\input\inject\InjectShellClient.java:334`）。
- **能力差异**：`injectKey` 仅 DIRECT_ROOT/ROOT 实现（InputManager 反射可注入任意 KeyEvent）；SHIZUKU/ACCESSIBILITY 返回 false 由门面继续降级或放弃；pinch 在 ROOT 通道按 zoomIn 映射为 "pinch"/"zoom" 命令（`autolua2\engine\src\main\java\com\autolua\engine\input\inject\IInjectChannel.java:66`、`RootInjectorChannel.java:150`）。
- **人化**：`MotionEventBuilder` 统一组 MotionEvent（预分配 10 指数组，严格 recycle），`Humanizer` 提供随机抖动/步进延迟，三通道共用同一套参数（config 运行时可调）（`autolua2\engine\src\main\java\com\autolua\engine\input\inject\MotionEventBuilder.java:56`、`Humanizer.java:14`）。
- **ROOT 行协议**：一行一命令（tap/swipe/down/...），子进程逐行 `readLine` → `InjectorCore.executeCommandLine` → 回 `ok`/`err ...`，stderr 独立泵线程转发日志（`autolua2\engine\src\main\java\com\autolua\engine\input\inject\RootInputInjectorMain.java:64`、`RootInjectorChannel.java:228`）。

## 共享内存帧

- Java 写侧 `SharedMemoryManager.init(w,h)` 创建 A/B 两块 MemoryFile 并 mmap 成 direct ByteBuffer；`nativeSetSharedMemoryInfo` 把基址下沉 C++ `SharedFrame` 全局指针，C++/Lua 读侧零拷贝直访（`autolua2\engine\src\main\java\com\autolua\engine\display\sharedmem\SharedMemoryManager.java:101`、`SharedMemoryManager.java:435`、`autolua2\engine\src\main\cpp\extension\SharedFrame.h:3`）。
- 两套写协议共用 frameCounter：seqlock（写当前块后计数写后置，读侧前比对-拷贝-后比对，不等则重试 8 次）与 ping-pong swap（写 inactive 块 + 头双写）；协议选型与版本适配见 [../engine-core/INTEGRATION_PLAN.md](../engine-core/INTEGRATION_PLAN.md) §2.3，机制索引见 [01-架构总览.md](../../01-架构总览.md) §10（seqlock 帧协议行）（`autolua2\engine\src\main\java\com\autolua\engine\display\sharedmem\SharedMemoryManager.java:264`、`SharedMemoryManager.java:298`、`autolua2\engine\src\main\java\com\autolua\engine\display\sharedmem\FrameBuffer.java:38`）。
- fd 传递：跨进程（如投屏/沙箱）经 AIDL 附 fd，对端 `mapFdToByteBuffer` 重新 mmap（`autolua2\engine\src\main\java\com\autolua\engine\display\sharedmem\SharedMemoryManager.java:221`、`SharedFrame.h:47`）。
- 9 字节自描述头随帧写入两块，读侧 `parseHeader` 解析当前 bufferIndex/w/h，头部偏移 `HEADER_SIZE=9`（`autolua2\engine\src\main\java\com\autolua\engine\display\sharedmem\FrameBuffer.java:84`、`SharedMemoryManager.java:32`）。

## AIDL 整树 dump / 隐藏 API 镜像 / 输入法通道

- **AIDL 整树 dump**：`AccessibilityService.onBind` 为 final（android-34 jar 验证），故用独立 `AuxDumpService` 承载 `IAuxDumpService`（action `com.autolua.engine.AUX_DUMP`），dump 经 ParcelFileDescriptor 管道流式回传避开 1MB Binder 限制；跨进程传输设计见 [../engine-core/INTEGRATION_PLAN.md](../engine-core/INTEGRATION_PLAN.md) §4.3.3（`autolua2\engine\src\main\java\com\autolua\engine\accessibility\AuxDumpService.java:10`、`autolua2\engine\src\main\aidl\com\autolua\engine\accessibility\IAuxDumpService.aidl:9`）。
- **隐藏 API 镜像**：`conceal\` 包（SurfaceControlWrap/InputManagerWrap 等）以反射封装隐藏 API，`java\android\` 目录（app/ActivityThread、os/ServiceManager、view/SurfaceControl 等）提供编译期类签名镜像，二者配合让 root 进程免 @hide 限制直调框架能力（`autolua2\engine\src\main\java\com\autolua\engine\conceal\SurfaceControlWrap.java:8`、`autolua2\engine\src\main\java\com\autolua\engine\conceal\InputManagerWrap.java:9`）。
- **输入法通道**：`ImeApi` 切默认输入法到自研 `AutoLuaInputMethodService`（root 写 Settings.Secure 或 su -c），文本/键码经同包广播（ACTION_INPUT_TEXT 等）送达，IME 侧经 InputConnection 落字——绕开注入通道对焦区的依赖；设计见 [../engine-core/INTEGRATION_PLAN.md](../engine-core/INTEGRATION_PLAN.md) §6（`autolua2\engine\src\main\java\com\autolua\engine\inputmethod\ImeApi.java:67`、`ImeApi.java:155`、`autolua2\engine\src\main\java\com\autolua\engine\inputmethod\AutoLuaInputMethodService.java:114`）。

## 代码映射表

| 机制 | 文件 | 关键位置 |
|---|---|---|
| su+app_process 拉起 | `core\root\RootProcessStarter.kt` | :9（su）、:56-74（命令行）、:62（app_process64） |
| 帧协议编解码 | `core\root\TransportImp.kt` | :196（头）、:200（组帧）、:54（状态机）、:173（派发纪律） |
| root 引擎宿主 | `core\root\Server.kt` | :23（stdin/stderr）、:118（initEngine）、:236（invoke+超时）、:212（main） |
| app 客户端/动态代理 | `core\root\Client.kt` | :112（build）、:287（invoke）、:237（app 侧服务执行）、:310（destroy） |
| 服务表 | `core\root\ServiceManagerImp.kt` | :17（registerService）、:8（id 分配） |
| 调用包/固定服务号 | `core\root\InvokePackage.kt` | :8（字段）、:28（OBSERVER 等） |
| 回包匹配 | `core\root\DataCallbackManager.java` | :37（create）、:54（waitAndRemote）、:77（notify） |
| UI 事件生产 | `app\...\extension\ui\UiMgrImp.kt` | :42（cap 256）、:80（pollEvents）、:92（op）、:65（emitOf） |
| UI 事件消费 | `extension\ui\UiLib.kt` | :100（dispatchLoop）、:122（50ms）、:116（退出条件）、:137（shutdown）、:200（bind） |
| 通道接口/模式 | `input\inject\IInjectChannel.java` / `InjectMode.java` | :17-72（方法）、:9-17（枚举） |
| 门面降级 | `input\inject\InjectShellClient.java` | :130（start 择优）、:337（dispatch 降级） |
| 进程内注入 | `input\inject\InjectorCore.java` | :181（getService）、:265（injectInputEvent）、:799（executeCommandLine） |
| root 子进程注入 | `input\inject\RootInjectorChannel.java` / `RootInputInjectorMain.java` | :78（cmdline）、:195（一问一答）、:64（readLine 循环） |
| Shizuku 注入 | `input\inject\ShizukuInjectorChannel.java` / `ShizukuUserServiceMain.java` | :272（bindUserService）、:13（Stub 实现） |
| 无障碍注入 | `input\inject\AccessibilityChannel.java` | :85（down/up 攒 stroke）、:138（dispatch） |
| 人化组帧 | `input\inject\MotionEventBuilder.java` / `Humanizer.java` | :56（build）、:17（参数） |
| AIDL dump | `accessibility\AuxDumpService.java` + `IAuxDumpService.aidl` | :27（onBind）、:8（接口） |
| 共享内存帧 | `display\sharedmem\SharedMemoryManager.java` / `FrameBuffer.java` / `cpp\extension\SharedFrame.h` | :101（init）、:264（seqlock）、:38（读重试）、:16（C++ 下沉） |
| 隐藏 API | `conceal\*.java` + `java\android\**` | SurfaceControlWrap:21（事务）、InputManagerWrap:27（注入） |
| 输入法 | `inputmethod\AutoLuaInputMethodService.java` / `ImeApi.java` | :34（广播 action）、:67（切默认 IME）、:207（ensureChannel） |

## 已知约束与坑

- **残留 root daemon**：杀过引擎进程或重装 APK 后，残留 app_process64 daemon 占 UiAutomation 注册位 → 节点域全 FAIL 且 err 为空串；跑测前 `ps -A | grep app_process64` 全 kill（[06-开发铁律与踩坑手册.md](../../06-开发铁律与踩坑手册.md) §3）。
- **app 侧读线程静默死亡**：app 侧收帧必须顺序派发（见 root RPC 链路一节），并发派发拖垮读线程后管道积压反卡 root 写线程，表现为 root 侧整体假死（`autolua2\engine\src\main\java\com\autolua\engine\core\root\TransportImp.kt:183`；[06-开发铁律与踩坑手册.md](../../06-开发铁律与踩坑手册.md) §3）。
- **引擎 execute 偶发挂死**：run_script 偶发 script_started 后无输出/不回 idle，与 UI 无关的既有问题；重置环境（force-stop + 杀 app_process64 + 重启 app）可恢复（[06-开发铁律与踩坑手册.md](../../06-开发铁律与踩坑手册.md) §4）。
- **root daemon 常驻未做**：当前每次脚本启动都重新拉起 app_process，拉起成本高，常驻为二期方案（[06-开发铁律与踩坑手册.md](../../06-开发铁律与踩坑手册.md) §4）。
- **模拟器悬浮窗权限**：`uimgr` 建窗需 SYSTEM_ALERT_WINDOW 授权，否则 addView 抛 BadTokenException（FloatWin 静默吞、Dialog 直接抛）（[06-开发铁律与踩坑手册.md](../../06-开发铁律与踩坑手册.md) §4）。
- **静态屏幕不出帧**：共享内存帧写侧依赖合成器出帧，静止画面无新帧，图色自测前先动一下屏幕（[06-开发铁律与踩坑手册.md](../../06-开发铁律与踩坑手册.md) §3）。
