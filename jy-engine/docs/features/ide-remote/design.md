# IDE 远程链路设计

> 文档版本 v1.0（2026-09-18，文档三层化重组新建）
> 本域权威范围：IDE WebSocket 开发调试链路的设计与代码映射——设备端 WS 服务、VS Code 编辑器插件、远程投屏与操控；协议帧格式细节以 ../../03-WS协议规范.md 为权威，域内契约见 [contracts.md](contracts.md)。

## 架构总述

三端拓扑（文字化）：

```
【A 端】VS Code 插件 autolua-vscode 0.5.11（PC，Node + ws 库）
  extension.ts   命令注册 / 设备树 / 状态栏（activate :24，命令表 :75-:248）
  deviceManager.ts  WS 客户端：握手 / 心跳 / 串行请求队列（connect :42，request :70）
  discovery.ts   UDP 广播发现 + 子网扫描（discoverDevices :22，scanSubnets :73）
  adb.ts         adb forward 隧道兜底（forwardPort :68）
  sync.ts        增量同步 + zip 打包分块推送（incrementalSync :53，packageAndPushZip :137）
  runner.ts      运行/停止/暂停/恢复 + 状态订阅（ScriptRunner :11，RUN_SCRIPT :86）
  luaApiData.ts  API 提示数据（LUA_API_DOCS :17，现 287 条 / 23 组）
        │
        │  ws://<ip>:8080..8085（JSON 文本帧为主，投屏二进制帧同连接混传）
        ▼
【B 端】设备 app 进程（Android，autolua2\app）
  MainApp.kt     启动接线：构造沙箱执行器 + attachEngine + start（:127-:180）
  IdeServerManager（engine\ide）    生命周期门面 / 端口回退 / 内置 Handler 注册（registerBuiltinHandlers :223）
  IdeWebSocketServer（engine\ide）  承载：Java-WebSocket 1.5.7（engine\build.gradle.kts:189），单线程消息池 + 30s 心跳
  IdeMessageRouter（engine\ide）    type → Handler 分发，异常统一转 error
  LuaEngineExecutor（engine\ide）   IDE 沙箱（独立 Lua VM，execute 即时执行）
  DiscoveryManager → BroadcastLocationServer（app\debug）  UDP 发现应答（同端口 8080..8085）
  ScreenStreamController（engine\display\stream）          投屏编码与二进制帧下发
        │
        │  ScriptRunDelegate：app 进程读文件内容 → AutoLuaEngineProxy.execute（root 引擎进程无法直读 FUSE 存储）
        ▼
【C 端】root 主引擎进程（app_process，uid 0）
  完整 Lua 环境：C++ 图色绑定 / 输入注入 / 节点 / 插件运行时
  IdeLog LocalService：脚本 print 跨进程回传 IDE log 帧（MainApp.kt:116）
  WORKER 状态观察者 → script_state 广播（MainApp.kt:102-108）
```

另有一个**零安装旁路端**：`tools\remote.html` 单文件浏览器控制台（对照 INTEGRATION_PLAN §9.7，remote.html:9），不依赖 VS Code，直接消费同一 WS 服务（投屏 WebCodecs 硬解 + 触控回传 + zip 推送 + run_script，remote.html:12-17）。

## 服务生命周期与 Handler 注册

- 启动顺序（MainApp.onCreate）：构造 `LuaEngineExecutor` 并逐项 `registerService` 注入沙箱（MainApp.kt:127-165）→ 沙箱注入 `ROOT_DIR` 与 pause/resume 委托（MainApp.kt:166-175）→ `setApplicationContext` → `attachEngine` → `start()` → `DiscoveryManager.apply`（MainApp.kt:176-180）→ `setRootDir` / `setScriptRunDelegate`（MainApp.kt:186-187）→ `setUiDebugDelegate`（MainApp.kt:230）。
- `start()` 探测 8080..8085 取可用端口后创建 IdeWebSocketServer（IdeServerManager.java:135，findAvailablePort :310）；`stop()`/`restart()` 幂等（IdeServerManager.java:153/:169）。
- `attachEngine` 把执行器写入 EngineExecutorHolder 并挂输出监听（IdeServerManager.java:207）；未注入时 execute 走 no-op 默认实现（EngineExecutorHolder.java:16）。
- `registerBuiltinHandlers()` 集中注册 14 个 Handler / 33 条命令（IdeServerManager.java:223-241，逐条对码见 contracts.md ②）；能力协商 capabilities 的 commands 数组即此注册表快照（CapabilitiesHandler.java:42）。
- 自愈：无障碍服务自拉活时二次 `start()` 并同步 UDP 发现（方案八 9.6 守护保险，MainApp.kt:254-267）。
- 输出回传通道：脚本 print → OutputListener → `dispatchScriptOutput`（专用单线程保序，IdeServerManager.java:274）→ `[IMG]` 行交 ImageChunkAssembler、其余行广播 log 帧（IdeServerManager.java:248）；投屏二进制/文本帧经构造期注入的 `ScreenStreamController.setSender` 通道发送（IdeServerManager.java:53）。

## 沙箱执行模型（IDE 沙箱 vs root 主引擎）

- 双执行面并存：`execute` 在 **IDE 沙箱**（app 进程内独立 Lua VM，同步取回 `_WS_RESULT`，LuaEngineExecutor.java:121）；`run_script` 走 **root 主引擎**（异步 fire-and-forget，RunScriptHandler.java:79）。选型原因与执行序列注释固定在 WsProtocol.java:210 与 LuaEngineExecutor.java:17-48。
- 沙箱与主引擎共享的是**服务实例**而非 Lua 状态：MainApp 把与主引擎 addLocalService 同款实例（UI/FloatController/http/file/device/crypt/app/plugin/ocr/ime/uimgr 等 18 项）注册进沙箱（MainApp.kt:128-165），注入序列对齐主引擎 pushLocalServices（wrap → push → setGlobal，LuaEngineExecutor.java:366）；display/input 两项为 C++ 对象注入，沙箱侧无对应 JNI 不可对齐（LuaEngineExecutor.java:44）。
- 主引擎执行链路（run_script）：设备端两根按序探测脚本路径（ROOT_DIR → ide_push，RunScriptHandler.java:64）→ 回 script_started → app 进程读文件内容（规避 root 进程 FUSE 不可见，MainApp.kt:183-189）→ Lua 层幂等 print 桥接包装（原 print 存 `__ide_orig_print`，重定向到 IdeLog，MainApp.kt:199-219）→ `AutoLuaEngineProxy.execute`（MainApp.kt:220）→ 主引擎 print 经 IdeLog 跨进程代理回 app → `dispatchScriptOutput` → log 帧。
- 状态同步：主引擎 WORKER 观察者把 running/idle 广播为 script_state 帧（MainApp.kt:102-108，IdeServerManager.java:282），VS Code 插件据其切换运行/停止按钮（runner.ts:25，extension.ts:69）。
- pause/resume/stop：沙箱侧经 LifecycleDelegate 透传引擎代理（MainApp.kt:167），stop 映射为引擎 interrupt（LuaEngineExecutor.java:188）；对沙箱自身同步执行的短代码无中断点。

## 文件同步与脚本运行链路

- 增量同步（保存即推，extension.ts:233）：PC 收集文件清单（sync.ts:26）→ `get_file_md5_list` 取设备基线比对（sync.ts:58）→ 差量走 push_files_start → push_file×n（文本 utf8、二进制 base64，sync.ts:83）→ push_files_end 统一落盘 ide_push/（PushFilesHandler.java:124）→ 冗余文件 delete_files（sync.ts:105）。
- 整包推送（autolua.packageZip，extension.ts:235）：zip 打包 → push_zip_start/chunk×n/complete(expectedMd5) 分块上传（sync.ts:166-174）→ 设备端 MD5 校验 + 流式解压到 ide_push/（PushZipHandler.java:154）；index 严格顺序与 zip slip 防护见 contracts.md ④。
- 脚本运行（autolua.run / runFile，extension.ts:206/:217）：`run_script {path}` → 设备端 ROOT_DIR 优先、ide_push 兜底（打通"推 zip → 直接跑"）→ script_started → log×n → script_state（RunScriptHandler.java:52-89）。
- 停止/暂停/恢复：stop_script / pause_script / resume_script → 透传引擎代理（ScriptLifecycleHandler.java:15-17）；UI 热重载 ui_debug_start/stop 经 UiDebugDelegate 拉起/关闭 DebugActivity（9.11 老通道退役，MainApp.kt:230-249）。

## 图色远程计算与投屏流

- 图色远程计算（01-架构总览.md §9.5）：`find_image` / `find_multi_color` / `cmp_color` / `ocr` 的**计算在设备端完成**，PC 只收坐标结果；内核优先走 C++（VisionJni → extension/VisionBridge.cpp，复用 autolua_vision）+ Java fallback（VisionCommandHandler.java:32），OCR 为运行时插件判定（VisionCommandHandler.java:82）。调试脚本内截图走 `[IMG]` 分片 → image 帧（ImageChunkAssembler.java:109）。
- 投屏会话（方案八 9.3/9.8）：`stream_start {codec, fps, bitrate, quality, scale}` → ScreenStreamController.startSession 校验帧源后回 stream_started（含真实 w/h 供坐标映射，StreamCommandsHandler.java:25）→ 媒体帧循环：MediaCodec 编码（H264 Annex-B→AVCC / JPEG 直出）→ ConnectionSender → 二进制帧定向下发（ScreenStreamController.java:160）。
- 旋转与恢复：分辨率变化推 `stream_rotate`、编码器随新尺寸惰性重建（ScreenStreamController.java:360/:384）；PC 端解码器重建后发 `stream_sync` 请求 IDR（ScreenStreamController.java:140），`requested:false` 时插件/页面自动重启会话自救（remote.html:444）。
- 操控回传：inject_touch/swipe/key/text 由 InjectCommandsHandler 落到输入通道（InjectCommandsHandler.java:49-82），PC 端鼠标坐标按 stream_started 的真实宽高等比换算（remote.html:14）。
- 插件（四期方案七）：plugin_push_* 分块上传安装 + plugin_list/remove，capabilities 暴露 plugins 清单（CapabilitiesHandler.java:54）。

## 代码映射表

| 域 | 文件 | 关键锚点 |
|---|---|---|
| 协议常量 | `autolua2\engine\src\main\java\com\autolua\engine\ide\WsProtocol.java` | 33 CMD（:42-:156）/ 17 MSG（:19-:158）/ 端口心跳（:192-:196）/ 二进制帧格式注释（:161） |
| WS 承载 | `...\ide\IdeWebSocketServer.java` | 单线程消息池（:42/:68）/ hello（:231）/ 心跳（:264）/ 二进制发送（:196） |
| 生命周期 | `...\ide\IdeServerManager.java` | Handler 注册（:223）/ attachEngine（:207）/ 输出分流（:248/:274）/ script_state（:282）/ 端口探测（:310） |
| 路由 | `...\ide\IdeMessageRouter.java` | register（:42）/ dispatch（:60）/ error 兜底（:111） |
| 沙箱 | `...\ide\LuaEngineExecutor.java` | 执行三步（:129-:150）/ print 重定向（:230）/ 服务注入（:295/:366）/ ROOT_DIR（:320） |
| 文件 | `...\ide\PushFilesHandler.java` / PushZipHandler.java / FileMd5Handler.java / DeleteFilesHandler.java / IdePaths.java | 注册（:50/:64/:25/:24）/ safeResolve（IdePaths.java:22） |
| 图色 | `...\ide\VisionCommandHandler.java` / VisionJni.java | 能力位（:60-:62）/ OCR 判定（:82）/ JNI 调用（:163/:198/:261） |
| 注入/投屏/脚本 | `...\ide\InjectCommandsHandler.java` / StreamCommandsHandler.java / RunScriptHandler.java | 注册（:49/:22/:48）/ startSession（StreamCommandsHandler.java:25）/ 两根探测（RunScriptHandler.java:64） |
| 插件/UI 调试 | `...\ide\PluginCommandHandler.java` / UiDebugHandler.java / CapabilitiesHandler.java | 注册（:88/:27/:98）/ capabilities 组装（CapabilitiesHandler.java:39） |
| 投屏内核 | `autolua2\engine\src\main\java\com\autolua\engine\display\stream\ScreenStreamController.java` | 发送通道（:79/:124）/ 会话（:160/:210）/ IDR（:140）/ AVCC 化（:696/:733） |
| app 接线 | `autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt` | 沙箱注册（:128-:165）/ 启动（:177-:180）/ run_script 委托（:187-:226）/ UI 调试委托（:230）/ 自拉活（:254） |
| UDP 发现 | `autolua2\app\src\main\java\com\autolua\autolua2\debug\DiscoveryManager.kt` / BroadcastLocationServer.kt | 端口跟随（DiscoveryManager.kt:61）/ 问答字符串（BroadcastLocationServer.kt:92-93） |
| 编辑器端 | `autolua2\vscode-plugin\src\`（extension.ts / protocol.ts / deviceManager.ts / discovery.ts / adb.ts / sync.ts / runner.ts / luaApiData.ts） | 见架构总述 A 端各行号 |
| 工具 | `autolua2\tools\` ws_test*.mjs / h264_probe.mjs / remote.html | 帧头解析（h264_probe.mjs:21）/ stream_sync 自救（remote.html:444/:469） |

## 演进

- 方案六（INTEGRATION_PLAN.md §7 :641）：一期命令集 → 二期 push_zip / 图色远程 / capabilities → 三期沙箱服务注入与 print 回传 → 四期插件动态加载 → 六期 9.11 调试通道整合（老 8176 通道退役，UDP 发现与 UI 热重载并入本通道，§9.11 :932）。
- 方案八（INTEGRATION_PLAN.md §9 :812 / §9.8 WS 协议扩展汇总 :898）：投屏双 codec、远程注入、run_script 主引擎通道、进程守护自拉活；remote.html 为其 PC 端交付物（§9.7 :885）。验收标准见 §7.4（:687）与 §9.9（:912）。
- 后续扩展沿既有分期模式推进：新增命令按 contracts.md ②⑤ 双端同步注册并回写根文档 03 §2/§3；新增能力位进 CapabilitiesHandler 组装段（CapabilitiesHandler.java:39-85）；协议常量一律先落 WsProtocol.java 再镜像 protocol.ts（对码纪律见各文件头注释与 03-WS协议规范.md 权威常量源声明）。
