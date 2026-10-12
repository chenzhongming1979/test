# 宿主客户端（app）

> 文档版本 v1.0（2026-09-18，文档三层化重组新建）
> 本域权威范围：宿主客户端模块 `autolua2\app`——引擎装配中枢（MainApp / MainService）、进程保活（keepalive）、脚本工程管理（project）、调试发现（debug）、页面与基础组件（activity / base）、AndroidManifest 权威清单与模块依赖。

> 状态：v1.0 已对码——本文档及本域 contracts.md / design.md 全部行号均经 Read / Grep 核实（对码基准日 2026-09-18）。已知风险：keepalive 六组件未在 AndroidManifest.xml 声明（详见 contracts.md ③⑤）。

## 本域文档

| 文档 | 内容 | 什么时候读 |
|---|---|---|
| [contracts.md](contracts.md) | 五条契约：① 本地服务注册清单 ② 引擎创建/启动 ③ 保活组件 ④ 工程目录 ⑤ Manifest 权威清单，每条句末对码标注 | 改服务注入、引擎装配、保活、工程装载、Manifest 之前必读 |
| [design.md](design.md) | 架构总述（装配中枢图文字化）、引擎装配与双执行环境、保活体系、工程管理与页面结构、调试发现、代码映射表、演进 | 理解装配链路、设计动机与历史包袱时 |

## 全局真相源交叉引用

| 真相源 | 章节 | 本域相关内容 |
|---|---|---|
| [01-架构总览.md](../../01-架构总览.md) | §7 模块地图 app（184-198 行） | app 各包职责速览与 Manifest 要点 |
| [01-架构总览.md](../../01-架构总览.md) | §8 Lua 运行环境与服务注入矩阵（232-261 行） | 三执行环境注册点、跨进程服务限制、LuaEnvContributor |
| [01-架构总览.md](../../01-架构总览.md) | §9.1 脚本执行（root 主引擎）（265-277 行） | run_script / 悬浮球 → root 引擎 → print 回传全链路 |
| [ipc/contracts.md](../ipc/contracts.md) | ③ 本地服务注册协议（26-33 行） | `addLocalService` 底层协议、root Client 代理还原、保留服务号 |
| `../ui/client.md` | —（UI 域文档规划中，尚未建立） | uimgr 动态 UI（IUiMgr/UiMgrImp op 协议）集成细节，建成后由其承接 |
| [05-构建与自测指南.md](../../05-构建与自测指南.md) | 全文 | APK 构建、模拟器联调、远程自测运行器 |
| [engine-core/INTEGRATION_PLAN.md](../engine-core/INTEGRATION_PLAN.md) | §5 方案四：进程保活体系（541-607 行） | 保活全案设计存档（方案四归属 app 域执行，本域 design.md 仅引要点） |
| [engine-core/INTEGRATION_PLAN.md](../engine-core/INTEGRATION_PLAN.md) | §6 方案五：模拟输入法通道（609 行起） | IME 通道归 ipc/inputmethod 域，此处仅作索引，不在本域展开 |

## 代码入口

| 文件 / 目录 | 职责 | 关键锚点（已核实） |
|---|---|---|
| `autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt` | 引擎装配中枢：MLN 初始化 → 悬浮球 → 引擎创建器 → IDE 通道 → UDP 发现 → 无障碍拉活委托 → 插件加载 → 启动 MainService | `setEngineCreator` :110-120；IDE 沙箱服务注册 :127-165；`setScriptRunDelegate` :187-226；`setUiDebugDelegate` :230-249；`AccessibilityLaunchHelper.setDelegate` :254-267；`PluginManager` :271-287 |
| `autolua2\app\src\main\java\com\autolua\autolua2\MainService.kt` | 前台通知保活 + WakeLock + `IMainService` AIDL（`MyAutoLuaEngineService`） | `startForeground` :50；WakeLock 10min :51-56；onDestroy 逆销毁 :59-64；`MyAutoLuaEngineService` :72-85 |
| `autolua2\app\src\main\java\com\autolua\autolua2\IdeLogService.kt` | root 脚本 print 跨进程回传 → IDE WS log 帧（接口 `IdeLog` + 实现） | 接口约束注释 :5-15；`log` 转发 :20-23 |
| `autolua2\app\src\main\java\com\autolua\autolua2\keepalive\` | 保活全家桶：Manager 门面 / Service / JobService / AlarmSender / ContentProvider / BootReceiver | `KeepAliveManager.start` :38-44；`emergencyKeepAlive` :50-56；持久 Job :83-105 |
| `autolua2\app\src\main\java\com\autolua\autolua2\project\ProjectManagerImp.kt` | 脚本工程扫描/安装/删除/版本切换（单线程任务队列） | `init` :358-372；`checkProjectInfo`（package.json）:212-224；`startProjectActivity` :419-438 |
| `autolua2\app\src\main\java\com\autolua\autolua2\base\` | Path（uri→绝对路径）、Constants（端口/通知 ID）、Configure（rootDir 派生目录）、DataChannel（协程等待/通知原语） | `Constants.DEBUG_PORT` :4；`Configure.rootDir` :6-7 |
| `autolua2\app\src\main\java\com\autolua\autolua2\activity\` | SplashActivity（权限申请）→ MainActivity（底部导航三页）、ProjectActivity（MLN UI 容器）、DebugActivity（UI 热重载）、PreferencesHelper | Splash 权限清单 :71-73；MainActivity 三 Tab :26-37；ProjectActivity 启动脚本 :34-38 |
| `autolua2\app\src\main\java\com\autolua\autolua2\activity\ui\` | script / debug / my 三 Fragment + ViewModel | DebugFragment UDP 开关 :216-218 |
| `autolua2\app\src\main\java\com\autolua\autolua2\debug\` | DiscoveryManager（UDP 发现开关）+ BroadcastLocationServer（问答协议）；DebugService/DebugServer(Imp) 为退役的 8176 老通道（代码保留不接线） | 问答常量 `BroadcastLocationServer.kt` :92-93；`DiscoveryManager.apply` :50-56 |
| `autolua2\app\src\main\java\com\autolua\autolua2\mln\` | MLN Lua UI 体系接入（UD/UI、生命周期监听、Glide provider、引擎状态常量） | `UI.LUA_CLASS_NAME` 注册 `MainApp.kt` :82-85 |
| `autolua2\app\src\main\java\com\autolua\autolua2\extension\`、`view\` | UserInterface(Imp)（Lua `UI` 服务）、UiMgrImp/IUiMgr（动态 UI）、FloatController(Imp)（悬浮球） | 悬浮球点击启停 `MainApp.kt` :94-100 |
| `autolua2\app\src\main\AndroidManifest.xml` | 权威组件/权限清单 | MainService（specialUse）:34-40；四个 Activity :42-73；权限 :10-16 |
| `autolua2\app\build.gradle.kts` | 模块依赖与构建配置 | `implementation(project(":engine"))` :153 |

## 何时读本文档

- 要**新增/修改注入给 Lua 的服务**（任一执行环境）时——先读 [contracts.md](contracts.md) ① 与 01-架构总览 §8，理解"三处注册点必须同步"的铁律。
- 要**排查脚本没跑起来 / print 不回传 / VSCode 连不上**时——读 [design.md](design.md)「引擎装配与双执行环境」与 01-架构总览 §9.1。
- 要**改动保活策略或加保活组件**时——读 [contracts.md](contracts.md) ③ 与 engine-core/INTEGRATION_PLAN.md §5（方案四原案），并注意 keepalive 未在 Manifest 声明的现状。
- 要**改脚本工程格式（package.json）或工程装载流程**时——读 [contracts.md](contracts.md) ④。
- 要**动 AndroidManifest（组件/权限）**时——先对 [contracts.md](contracts.md) ⑤ 的权威清单，防止漏声明/错权限。
- 要**做模拟器联调 / 打 APK**时——转 [05-构建与自测指南.md](../../05-构建与自测指南.md)。
