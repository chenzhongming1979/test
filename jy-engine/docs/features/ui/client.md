# 动态 UI 客户端集成

> 文档版本 v1.0（2026-09-18，文档三层化重组新建）
> 本域权威范围：动态 UI 与宿主 UI 在 app 进程的装配与集成——MainApp 服务注册、悬浮窗权限要求、IDE 沙箱注入、MLN 调试链；op/事件协议本身见 [contracts.md](contracts.md)。

## MainApp 装配（uimgr 服务注册）

`MainApp.onCreate` 是动态 UI 与宿主 UI 的唯一装配点，各注册锚点如下（均为主引擎 setEngineCreator 与 IDE 沙箱两条线）：

- 初始化顺序：`UserInterfaceImp.instance.init(this)` → `UiMgrImp.instance.init(this)`（ContextThemeWrapper + WindowManager）→ `FloatControllerImp.instance.init(this, 50)` 悬浮球 50dp（`autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:91`、`MainApp.kt:92`、`MainApp.kt:93`）。
- 悬浮球点击语义：IDLE 态点击 `startTask()`、RUNNING 态点击 `interrupt()`，由 WORKER observer 同步图标状态并顺带广播 IDE 脚本运行状态（`autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:94`、`MainApp.kt:102`）。
- **root 主引擎注册（跨进程服务）**：`setEngineCreator` 内 `Client.Builder.addLocalService`——`"UI"`（UserInterfaceImp）、`"FloatController"`、`"IdeLog"`、`"uimgr"`（UiMgrImp 单例，IUiMgr 接口）四项随引擎配置传给 root Server 回放为 Lua 全局；第三参必须是 interface（root 进程 Proxy.newProxyInstance 重建代理的要求）（`autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:110`、`MainApp.kt:118`）。
- 通道契约与注册回放机制（addLocalService → pushLocalServices → Lua 全局）归 [../ipc/contracts.md](../ipc/contracts.md) ③；root 侧 uilib 注册点（`Server.kt:193`）见 [../luaapi/contracts.md](../luaapi/contracts.md)。

## 悬浮窗权限要求

- 动态 UI 全部窗口（FloatWin/弹窗 scrim/悬浮球/MLN 悬浮视图）都是 WindowManager overlay：O+ 用 `TYPE_APPLICATION_OVERLAY`、低版本 `TYPE_PHONE`，运行时**必须已授予 SYSTEM_ALERT_WINDOW**，否则 `wm.addView` 抛 BadTokenException（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\UiMgrImp.kt:330`、`autolua2\app\src\main\java\com\autolua\autolua2\extension\imp\FloatViewImp.kt:47`、`autolua2\app\src\main\java\com\autolua\autolua2\view\imp\FloatControllerImp.kt:96`）。
- 失败表现差异：FloatWin.show 与悬浮球 show 静默吞异常（窗不出现），**DialogHost.show 直接抛**（op 返回 err）——排障先查权限再看是静默还是报错（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\UiMgrImp.kt:377`、`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\DialogHost.kt:255`）。
- 模拟器快捷授权：`adb shell appops set com.autolua.autolua2 SYSTEM_ALERT_WINDOW allow`（[../../06-开发铁律与踩坑手册.md](../../06-开发铁律与踩坑手册.md) §4）。
- 悬浮球生命周期：`reShow` 按 Preferences/旋转重挂，`updateState` 切 IDLE/RUNNING 图标（`autolua2\app\src\main\java\com\autolua\autolua2\view\imp\FloatControllerImp.kt:133`、`FloatControllerImp.kt:164`）。

## IDE 沙箱（ideExecutor.registerService）

IDE WebSocket 沙箱执行器与主引擎同款服务平行注册，保证 UI 脚本两环境可移植：

- `LuaEngineExecutor` 实例创建后注册全集（http/luajava/LuaEngine/file/device/crypt/app/plugin/ocr/media/ime/mysql/event/compat），其中本域两项：`registerService("uimgr", UiMgrImp.instance, IUiMgr::class.java)`（**与主引擎同一 app 进程实例，非代理**）、`registerService("uilib", UiLib(), UiLib::class.java)`（沙箱本地注册，contribute 注入全局 ui/console 表）（`autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:163`、`MainApp.kt:164`）。
- 沙箱 rootDir 对齐主引擎项目根：`ideExecutor.setRootDir(sdCardDir + "project")`；pause/resume 经 LifecycleDelegate 透传 AutoLuaEngineProxy（`autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:166`、`MainApp.kt:167`）。
- 执行器挂接：`IdeServerManager.attachEngine(ideExecutor)` + `start()`（8080 占用自动回退 8081..8085）；无障碍自拉活委托中同步重启 IDE 服务器与 UDP 发现（`autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:176`、`MainApp.kt:254`）。
- 沙箱跑 UI 脚本注意：uimgr 实例在 app 进程本地直调（无跨进程代理开销），但 op 仍是主线程落点 + 8s 超时语义，事件轮询/dispatcher 退出契约与 root 环境一致（[contracts.md](contracts.md) ①⑥）。

## 调试链（MLN ui_debug 热重载）

MLN 仅保留调试预览职责，调试链完整路径：

- **WS 命令入口**：`ui_debug_start` / `ui_debug_stop`（回帧 `ui_debug_result`），命令面与帧格式见 [../../03-WS协议规范.md](../../03-WS协议规范.md)；远程通道细节归 ide-remote 域契约 [../ide-remote/contracts.md](../ide-remote/contracts.md)（待建，三层化重组补建中）。
- **UiDebugDelegate**：IdeServerManager 收到 ui_debug_start 后委托 MainApp 注入的实现——主线程用 `MLSBundleUtils.createInitData(ASSETS_PREFIX + "hotreload.lua?ct=" + HotReloadServer.NET_CONNECTION)` 构造 InitData（带 ROOT_DIR/RESOURCE_DIR extras），拉起 `DebugActivity`；stop 发 `DebugActivity.CLOSE_BROADCAST` 本地广播关窗（`autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:230`、`MainApp.kt:231`、`MainApp.kt:245`）。
- **hotreload.lua**：MLN 调试预览入口脚本（assets 内置，随热重载服务器连接标识 `?ct=` 区分实例），经 `MLSEngine` 初始化链加载（`autolua2\app\src\main\assets\hotreload.lua`、`autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:75`）。
- **MLN 引擎初始化**：`MLSEngine.init` 注册 UD 类单例 `UI`（`LUA_CLASS_NAME="UI"`，脚本内 `putSignal/takeSignal/toast` 桥到 UserInterfaceImp）、Glide 图片 provider、全局状态监听（`autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:75`、`MainApp.kt:82`、`autolua2\app\src\main\java\com\autolua\autolua2\mln\ud\UI.kt:31`、`UI.kt:49`）。
- **旧 UI 服务（兼容保留）**：`UI.createView(uri, lp)` 返回 FloatViewImp（MLSInstance 悬浮容器，show/hide/setPosition/setSize/setContent/setAlpha），`UI.putSignal/takeSignal` 为 UI 与脚本间跨协程信号队列（`autolua2\app\src\main\java\com\autolua\autolua2\extension\imp\UserInterfaceImp.kt:61`、`UserInterfaceImp.kt:92`、`autolua2\app\src\main\java\com\autolua\autolua2\extension\imp\FloatViewImp.kt:19`）；新功能一律走 `ui` 表（[../../02-Lua-API规范.md](../../02-Lua-API规范.md) §17）。
