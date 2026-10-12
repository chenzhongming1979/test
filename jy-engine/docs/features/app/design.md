# 宿主客户端设计

> 文档版本 v1.0（2026-09-18，文档三层化重组新建）
> 本域权威范围：宿主客户端 `autolua2\app` 的设计决策与装配链路——MainApp 装配中枢、双执行环境、保活体系（方案四）、工程管理与页面结构、UDP 调试发现、代码映射与演进方向，机制句末对码标注；契约细节见 [contracts.md](contracts.md)。

## 架构总述（装配中枢图文字化）

MainApp（Application）是整个宿主的装配中枢，`onCreate` 按固定顺序完成七步装配，任何一步的顺序调整都可能破坏依赖（如插件必须 loadAll 后才能 contributePlugins、工程根必须先 init 才能被 IDE 通道引用）：

```
MainApp.onCreate（装配中枢，自上而下）
├─ 1. 工程系统：ProjectManagerImp.init(context, sdCardDir+"project")——单线程任务队列启动（MainApp.kt:61）
├─ 2. MLN Lua UI 引擎：LVConfigBuilder(ui/cache/image/res) → MLSEngine.init
│      （GlideImageProvider / GlobalStateListener / registerSingleInsance(UI) /
│        registerConstants(引擎状态码)）（MainApp.kt:69-90）
├─ 3. 宿主 UI 基座：UserInterfaceImp / UiMgrImp / FloatControllerImp 初始化，
│      悬浮球点击 → startTask / interrupt 启停脚本（MainApp.kt:91-100）
├─ 4. root 主引擎：setEngineCreator（Client.Builder + 4 个跨进程服务）→
│      AutoLuaEngineProxy.start()（MainApp.kt:110-121）
├─ 5. IDE 通道：LuaEngineExecutor 沙箱（18 个 registerService）→
│      IdeServerManager.attachEngine + start（8080，占用回退 8081..8085）→
│      DiscoveryManager.apply（UDP 发现随通道启停）→
│      setScriptRunDelegate（run_script → 主引擎）→ setUiDebugDelegate（MLN 热重载）
│      （MainApp.kt:127-249）
├─ 6. 无障碍自拉活委托 + 插件系统：AccessibilityLaunchHelper.setDelegate（保活+IDE 通道
│      重启）；PluginManager 注入公钥 → loadAll → contributePlugins → 异步 installFromAssets/
│      ExternalDir（MainApp.kt:254-287）
└─ 7. 启动 MainService：前台通知 + WakeLock 常驻（MainApp.kt:289）

MainService（前台常驻壳）：startForeground(10087) + PARTIAL_WAKE_LOCK(10min)
    + IMainService.Stub（MyAutoLuaEngineService，IME 通道绑定点）
    onDestroy 时逆序销毁工程线程与引擎代理（MainService.kt:50、MainService.kt:72、MainService.kt:59）
```

进程视角：app 主进程承载全部宿主功能与 Lua 全局服务实例；root 引擎进程（app_process）由 engine 域经 `Client/Server` 拉起，通过帧协议与 app 通信（跨进程协议详见 [../ipc/contracts.md](../ipc/contracts.md) ①②，本域只负责服务实例的提供侧）。整体进程模型与注入矩阵见 [../../01-架构总览.md](../../01-架构总览.md) §4、§8。

## 引擎装配与双执行环境

本域维护两个 Lua 执行环境的服务注入，"跨三环境 Lua 全局名一致"是兼容性铁律（第三环境为脚本 IDE 调试，复用 root 主引擎）：

- **root 主引擎（跨进程服务侧）**：`setEngineCreator` 只注册实例必须在 app 进程的四个服务——`UI`（toast/对话框，悬浮窗权限在 app 进程）、`FloatController`（悬浮球）、`IdeLog`（print 回传）、`uimgr`（动态 UI）；需要返回 Java 对象的域服务（http/file/device...）不能跨进程代理，由 engine 域在 root 进程本地注册（Server.kt，`autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:110`；限制原因见 [../../01-架构总览.md](../../01-架构总览.md) §8 :259）。
- **IDE 沙箱（LuaEngineExecutor，app 进程内）**：注册与 root 同款的全集 + 宿主侧 `UI/FloatController/uimgr`，另加 `uilib`（ui_* Lua API 层）；沙箱与主引擎共享 app 进程的单例实例，因此 VSCode 提交的脚本可直接寻址悬浮球与动态 UI（`autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:127`、`MainApp.kt:163`）。
- **沙箱生命周期接线**：`setLifecycleDelegate` 把沙箱 pause/resume 透传到主引擎代理（`AutoLuaEngineProxy.instance.pause()/resume()`），保证 IDE 的暂停按钮对两条执行路径语义一致（`autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:167`）。
- **run_script 内容直传**：IDE 的 run_script 经 `setScriptRunDelegate` 在 app 进程读文件内容后拼包装头提交 `AutoLuaEngineProxy.execute(wrapped)`——因为 root 引擎进程（uid 0 app_process）看不到 /storage/emulated 的 FUSE 视图，直接 loadfile 绝对路径会报 file not found（懒人/派大星同为内容直传方案）（`autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:187`、`MainApp.kt:220`）。
- **print 桥接的幂等设计**：包装头临时把 `print` 重定义到 `IdeLog` 跨进程服务；原始 print 存入全局 `__ide_orig_print` 后续运行从该键恢复，避免包装函数层层叠加导致输出 N 次运行 N 行；`__ide_no_hook` 提供解除钩子；服务方法必须冒号调用（self 占 arg#1，点语法会报 "expected LuaObjectAdapter"）；包装前还幂等刷新一次插件（root 进程 PluginManager 只在启动时 loadAll，app 进程新装插件它感知不到）（`autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:199`、`MainApp.kt:202`、`MainApp.kt:193`、`MainApp.kt:197`）。
- **插件贡献双路径**：沙箱侧在 onCreate 同步 `contributePlugins()`，主引擎侧由 engine 域在建 Lua 上下文时（pushLocalServices）对实现 `LuaEnvContributor` 的插件回调 contribute；app 进程 installFromAssets/installFromExternalDir 异步补装后再次 contribute 补偿（`autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:282`、`MainApp.kt:283`）。
- **插件安装验签**：注入 ECDSA P-256 公钥对（配套 plugins/sign-plugin.ps1），install 强制校验 manifest.signature，签名缺失/无效拒绝安装（`autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:274`）。
- **执行状态外溢**：WORKER 观察者把引擎状态映射到悬浮球（IDLE/RUNNING）并 `broadcastScriptState` 到 VSCode 插件（运行/停止按钮切换），这是"一处状态、三端同步"（悬浮球/App/VSCode）的实现点（`autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:102`）。

## 保活体系（方案四要点 + 对码）

设计全案存档于 [../engine-core/INTEGRATION_PLAN.md](../engine-core/INTEGRATION_PLAN.md) §5 :541-607（方案四归属 app 域执行，此处只引要点与对码现状）：

- **设计目标**：Android 14/15 上脚本相关进程被杀后 ≤1s 自愈；root 场景下引擎以 app_process 独立进程运行，天然规避 AMS 查杀，是最强保活层（INTEGRATION_PLAN.md §5.1 :545、§5.3.4 :598）。
- **多层自愈闭环（已对码）**：前台服务 START_STICKY 被拉起时反手重排 Job 与 Alarm；Job 的 onStartJob/onStopJob/onDestroy 三入口都尝试拉前台服务；Alarm 常规 60s 重复 + 紧急 +1s 五连发；ContentProvider 借"先于 Application.onCreate"特性在进程任何形式复活时拉活；服务自身 onTaskRemoved/onDestroy 触发 emergencyKeepAlive——形成"谁先醒谁拉全家"的互拉网（`autolua2\app\src\main\java\com\autolua\autolua2\keepalive\KeepAliveService.kt:58`、`KeepAliveJobService.kt:29`、`KeepAliveAlarmSender.kt:93`、`KeepAliveContentProvider.kt:18`、`KeepAliveService.kt:66`）。
- **无障碍看护（第一重保险）**：无障碍服务由 system_server 绑定，进程被杀会被系统重建；重建时经 `AccessibilityLaunchHelper.setDelegate` 反向拉起保活体系与 IDE WS 服务器，这是"远程唤醒"的入口（`autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:254`；原案心跳设计见 INTEGRATION_PLAN.md §5.2 :555）。
- **版本适配硬项（已对码）**：API 26+ startForegroundService；API 34+ 三参 startForeground 携带 SPECIAL_USE 类型；API 31+ 精确闹钟需授权否则降级 set()；PendingIntent 一律 FLAG_IMMUTABLE；全部含 try-catch 静默降级（`autolua2\app\src\main\java\com\autolua\autolua2\keepalive\KeepAliveService.kt:85`、`KeepAliveAlarmSender.kt:73`、`KeepAliveManager.kt:63`）。
- **现状缺口**：四组件未在 Manifest 声明（对码事实见 [contracts.md](contracts.md) ③⑤），互拉网中依赖系统组件机制（FGS/Job/广播/Provider）的环节实际无法被系统调度，保活闭环不成立；原案 Manifest 要求见 INTEGRATION_PLAN.md §5.3.2 :576-586，修复方向见本文「演进」。
- **明确不做**：SyncAdapter 账号同步拉活原案标记可选未实现（INTEGRATION_PLAN.md 状态总览表 :16）；force-stop 场景属系统行为不在验收范围（INTEGRATION_PLAN.md §5.4 :603）。

## 工程管理与页面结构

- **工程装载模型**：生产者-消费者——`addProject/removeProject/updateLastActionVersion` 只入队，独立线程 `loopHandleTask` 串行消费（kAdd/kRemove/kUpdate 三任务型），天然规避并发写目录；`stop()` 以 interrupt 收口，由 MainService.onDestroy 调用（`autolua2\app\src\main\java\com\autolua\autolua2\project\ProjectManagerImp.kt:340`、`ProjectManagerImp.kt:162`）。
- **工程即 MLN UI 项目**：package.json 的 `uiEntry/preloadEntry/mainEntry` 声明入口；`startProjectActivity` 把 `Configure.rootDir` 动态切到工程版本目录后，以 MLN `InitData(main.lua)` 打开 ProjectActivity，页面内"启动脚本"按钮再把 rootDir 同步给主引擎并唤出悬浮球——实现"UI 预览（MLN 沙箱）"与"完整脚本执行（root 主引擎）"双态（`autolua2\app\src\main\java\com\autolua\autolua2\project\ProjectManagerImp.kt:423`、`activity\ProjectActivity.kt:30`、`ProjectActivity.kt:34`）。
- **页面结构**：SplashActivity（存储+悬浮窗权限门）→ MainActivity（BottomNavigationView + NavController 三页：debug/script/my）→ ProjectActivity（MLN UI 容器，返回键分发给 MLN 实例、onDestroy 停 HotReloadServer）→ DebugActivity（UI 热重载页，监听 CLOSE_BROADCAST 关闭）（`autolua2\app\src\main\java\com\autolua\autolua2\activity\MainActivity.kt:26`、`activity\ProjectActivity.kt:51`、`activity\ProjectActivity.kt:62`、`activity\DebugActivity.kt:26`）。
- **UI 热重载接线**：IDE 的 ui_debug_start/stop 经 `setUiDebugDelegate` 迁移执行（老 8176 通道退役）——start 用 MLN `MLSBundleUtils.createInitData("assets://hotreload.lua")` 启 DebugActivity，stop 发 CLOSE_BROADCAST；extras 注入 `Configure.rootDir/resDir`（`autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:230`、`MainApp.kt:238`、`MainApp.kt:246`）。
- **配置持久化**：PreferencesHelper 承载 UDP 发现开关等用户偏好；MLN 资源目录约定 `sdCardDir/ui/{cache,image,res}`（`autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:59`、`debug\DiscoveryManager.kt:25`）。

## 调试发现（DiscoveryManager UDP）

- **问答协议**：设备监听与 IDE WS 同端口（默认 8080），收到 UDP `"AutoLuaEngine,Where are you?"` 回 `"AutoLuaKit,I am here!"`，VSCode 插件按此发现局域网设备（`autolua2\app\src\main\java\com\autolua\autolua2\debug\BroadcastLocationServer.kt:92`、`BroadcastLocationServer.kt:93`；`DiscoveryManager.kt:61` 端口取自 IdeServerManager）。
- **开关治理**：状态持久化于 Preferences（key `udp_discovery`，默认开启）；`apply()` 由两处调用——MainApp.onCreate 装配时（:180）与无障碍自拉活重启 IDE 通道时（:263）；DebugFragment 的 Switch 实时调 `setEnable`（`autolua2\app\src\main\java\com\autolua\autolua2\debug\DiscoveryManager.kt:50`、`MainApp.kt:180`、`MainApp.kt:263`、`activity\ui\debug\DebugFragment.kt:216`）。
- **状态机防御**：BroadcastLocationServer 四态（IDLE/STARTING/RUNNING/STOPPING）；STOPPING 卡在异步关端口时弃用旧实例换新实例，避免下次 start 被状态机挡住；独立 `NioEventLoopGroup(1)` 不复用已退役服务的线程组（`autolua2\app\src\main\java\com\autolua\autolua2\debug\BroadcastLocationServer.kt:96`、`DiscoveryManager.kt:70`、`DiscoveryManager.kt:88`）。
- **历史包袱**：DebugService/DebugServer/DebugServiceImp/DebugServerImp 为老 8176 protobuf 调试通道（`Constants.DEBUG_PORT=8176`），Manifest 已摘除声明、代码保留以备 MLN Kit 对接恢复；UDP 发现自 9.11 整合后挂靠新 IDE 通道（`autolua2\app\src\main\AndroidManifest.xml:32`、`base\Constants.kt:4`、`debug\DiscoveryManager.kt:10`）。

## 代码映射表

| 设计主题 | 代码落点 | 锚点 |
|---|---|---|
| 装配中枢 | `autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt` | onCreate :52-291 |
| MLN 初始化 | MainApp.kt | LVConfigBuilder :69-74、MLSEngine.init :75-90 |
| 悬浮球启停 | `view\imp\FloatControllerImp.kt` + MainApp.kt | init/点击 :93-100、状态观察 :102-108 |
| root 服务注入 | MainApp.kt | setEngineCreator :110-120 |
| IDE 沙箱注入 | MainApp.kt | registerService 全集 :127-165、rootDir :166、lifecycle :167-175 |
| run_script 直传 | MainApp.kt | setScriptRunDelegate :187-226 |
| UI 热重载 | MainApp.kt + `activity\DebugActivity.kt` | setUiDebugDelegate :230-249、CLOSE_BROADCAST :26 |
| 无障碍自拉活 | MainApp.kt | setDelegate :254-267 |
| 插件系统接线 | MainApp.kt | 公钥 :274、loadAll :278、contribute :282-287 |
| 主服务 | `MainService.kt` | 前台+WakeLock :22-57、AIDL :72-85、销毁 :59-64 |
| 保活互拉网 | `keepalive\*.kt` | Manager :38-56、Service :53-75、Job :25-63 :83-126、Alarm :55-124、Provider :18-30、Boot :16-32 |
| 工程系统 | `project\ProjectManagerImp.kt` | 目录 :53-73、package.json :205-224、任务循环 :340-353、startProjectActivity :419-438 |
| 页面 | `activity\*.kt`、`activity\ui\{script,debug,my}\` | Splash 权限 :55-73、Main 三 Tab :26-37、Project MLN :22-38、Debug UDP 开关 :216-218 |
| UDP 发现 | `debug\DiscoveryManager.kt`、`debug\BroadcastLocationServer.kt` | 开关 :32-56、问答 :92-93 |
| 路径常量 | `base\Path.kt`、`base\Configure.kt`、`base\Constants.kt` | transport :4-10、rootDir :6-7、DEBUG_PORT :4 |
| 等待/通知原语 | `base\DataChannel.kt` | create/wait/sendAndRemove :17-81 |
| Manifest | `autolua2\app\src\main\AndroidManifest.xml` | 权限 :10-16、MainService :34-40、Activity :42-73 |
| 模块依赖 | `autolua2\app\build.gradle.kts` | `project(":engine")` :153 |

## 演进

- **保活闭环修复（P1）**：把 KeepAliveService / KeepAliveJobService / KeepAliveContentProvider / BootReceiver 补进 AndroidManifest.xml（`RECEIVE_BOOT_COMPLETED` 权限已声明、specialUse 类型已就绪，只差组件声明），并考虑接线 BootReceiver 开关的 UI（当前 `setAutoStartOnBoot` 无调用点）；随后按 INTEGRATION_PLAN.md §5.4 :600-605 在 Android 14 实机验收。
- **退役代码清理（P2）**：确认 MLN Kit 对接无回归后删除 8176 老通道四文件（DebugService/DebugServer/DebugServiceImp/DebugServerImp）与 `Constants.DEBUG_PORT`/`DEBUG_DEFAULT_TOKEN`，或按 Manifest 注释一键恢复接线（`autolua2\app\src\main\AndroidManifest.xml:32`）。
- **uimgr 文档承接（P2）**：UI 域文档 `../ui/client.md` 建立后，把动态 UI op 协议细节与 pollEvents 契约的"使用侧"说明迁往该文档，本域仅保留服务实例提供侧（契约本体在 [../ipc/contracts.md](../ipc/contracts.md) ④）。
- **双执行环境一致性守护（P1）**：新增服务需同步 root（Server.kt）/沙箱（MainApp.kt :127-165）/跨进程（MainApp.kt :110-120）三处注册点，建议后续以单一注册表驱动三处，消除手工同步风险（现状铁律见 [../../01-架构总览.md](../../01-架构总览.md) §8 :234）。
- **run_script 包装头治理（P3）**：print 幂等包装/插件刷新/`__ide_no_hook` 目前内联在 MainApp 的 Kotlin 字符串里（:199-219），可下沉为独立 Lua 资源文件便于单测与版本管理。
