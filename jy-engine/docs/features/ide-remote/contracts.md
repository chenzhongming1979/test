# IDE 链路域内契约

> 文档版本 v1.0（2026-09-18，文档三层化重组新建）
> 本域权威范围：IDE WebSocket 域内契约摘要与对码落点；帧格式 / 命令 / 消息全集以 ../../03-WS协议规范.md 为权威（§1 连接 / §2 命令表 / §3 消息表 / §4 二进制流），本文与其冲突时以 03 与代码为准。

以下对码基准路径约定：`engine\ide\` = `autolua2\engine\src\main\java\com\autolua\engine\ide\`，行号均经 Grep/Read 核实。

## ① 端口与 UDP 发现契约

- WS 监听端口首选 **8080**（`engine\ide\WsProtocol.java:192`），回退上限 **8085**（`engine\ide\WsProtocol.java:194`）；启动时依次 bind 试探 8080..8085 取第一个可用端口，全占用返回 false（`engine\ide\IdeServerManager.java:310`）。
- PC 端连接时对 8080..8085 顺序握手探测（`autolua2\vscode-plugin\src\protocol.ts:82`）（`autolua2\vscode-plugin\src\protocol.ts:83`）（`autolua2\vscode-plugin\src\deviceManager.ts:45`）；adb 场景先 `adb forward tcp:<local> tcp:8080..8085` 再连 127.0.0.1（`autolua2\vscode-plugin\src\adb.ts:68`）。
- 心跳：服务端每 **30s**（`engine\ide\WsProtocol.java:196`）向全部在线连接 sendPing，并禁用 Java-WebSocket 自带连接丢失检测（`engine\ide\IdeWebSocketServer.java:92`）（`engine\ide\IdeWebSocketServer.java:272`）；PC 端心跳间隔 30s 对齐（`autolua2\vscode-plugin\src\protocol.ts:92`）（`autolua2\vscode-plugin\src\deviceManager.ts:203`）。
- 单命令等待回包超时 **15s**（`autolua2\vscode-plugin\src\protocol.ts:90`），超时由 PC 端串行队列裁决（`autolua2\vscode-plugin\src\deviceManager.ts:82`）；设备端对命令耗时无对称超时约束。
- UDP 发现：设备端在与 WS **同一端口**（IdeServerManager 当前端口）监听问句 `AutoLuaEngine,Where are you?`（`autolua2\app\src\main\java\com\autolua\autolua2\debug\BroadcastLocationServer.kt:92`），命中回 `AutoLuaKit,I am here!`（`autolua2\app\src\main\java\com\autolua\autolua2\debug\BroadcastLocationServer.kt:93`）；UDP 端口取 `IdeServerManager.getPort()`，服务未运行时跳过启动（`autolua2\app\src\main\java\com\autolua\autolua2\debug\DiscoveryManager.kt:61`）。
- UDP 发现开关持久化于 Preferences key `udp_discovery`（默认开），随 IdeServerManager 启动后 `apply` 生效（`autolua2\app\src\main\java\com\autolua\autolua2\debug\DiscoveryManager.kt:25`）（`autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:180`）；PC 端广播探测与子网扫描消费应答字符串（`autolua2\vscode-plugin\src\discovery.ts:22`）（`autolua2\vscode-plugin\src\discovery.ts:73`）。
- 连接建立即握手：服务端回 `hello`，data 含 `clientId`（8 位 UUID 截断）/`device_name`/`app_version`/`app_version_code`（`engine\ide\IdeWebSocketServer.java:231`）。
- 帧统一 `{"type":"<cmd>","data":{...}}` JSON 文本、**无请求 id**，按 type 配对回包；未识别 type 回 error `unknown type` 且不断连（`engine\ide\WsProtocol.java:7`）（`engine\ide\IdeMessageRouter.java:74`）。

## ② 命令分发注册机制

- IdeMessageRouter 持 `LinkedHashMap` 注册表 type→Handler，同名 type 覆盖（`engine\ide\IdeMessageRouter.java:42`）；`dispatch` 中畸形 JSON / 未知 type / Handler 抛错一律转 error 回包不断连（`engine\ide\IdeMessageRouter.java:60`）。
- 注册集中在 `IdeServerManager.registerBuiltinHandlers()`（`engine\ide\IdeServerManager.java:223`），14 个 Handler 共注册 **33 条** type→handler；能力协商 `commands` 数组动态读该注册表快照（`engine\ide\CapabilitiesHandler.java:42`）。
- 会话型命令（push_files/push_zip/plugin_push）依赖消息到达顺序，服务端以**单线程**消息池串行处理（`engine\ide\IdeWebSocketServer.java:42`）（`engine\ide\IdeWebSocketServer.java:151`）。
- 逐 Handler 注册对码（行号为 `router.register(...)` 调用点）：

| 期 | 命令 type | Handler 注册点 |
|---|---|---|
| 一期 | execute | `engine\ide\ExecuteHandler.java:43` |
| 一期 | stop_script / pause_script / resume_script | `engine\ide\ScriptLifecycleHandler.java:15` / :16 / :17 |
| 一期 | push_files_start / push_file / push_files_end | `engine\ide\PushFilesHandler.java:50` / :63 / :75 |
| 一期 | get_file_md5_list | `engine\ide\FileMd5Handler.java:25` |
| 一期 | delete_files | `engine\ide\DeleteFilesHandler.java:24` |
| 一期 | ping | `engine\ide\PingHandler.java:11` |
| 二期 | push_zip_start / chunk / complete | `engine\ide\PushZipHandler.java:64` / :77 / :90 |
| 二期 | cmp_color / find_multi_color / find_image / ocr | `engine\ide\VisionCommandHandler.java:72` / :73 / :74 / :75 |
| 二期 | capabilities | `engine\ide\CapabilitiesHandler.java:98` |
| 四期 | plugin_push_start / chunk / complete | `engine\ide\PluginCommandHandler.java:88` / :101 / :114 |
| 四期 | plugin_list / plugin_remove | `engine\ide\PluginCommandHandler.java:126` / :138 |
| 五期 | inject_touch / inject_swipe / inject_key / inject_text | `engine\ide\InjectCommandsHandler.java:49` / :60 / :71 / :82 |
| 五期 | stream_start / stream_stop / stream_sync | `engine\ide\StreamCommandsHandler.java:22` / :33 / :42 |
| 五期 | run_script | `engine\ide\RunScriptHandler.java:48` |
| 六期 | ui_debug_start / ui_debug_stop | `engine\ide\UiDebugHandler.java:27` / :41 |

- 计数口径：`WsProtocol.java` 实有 `CMD_*` 常量 **33 个**（一期 10 + 二期 8 + 四期 5 + 五期 8 + 六期 2，`engine\ide\WsProtocol.java:42` 至 :156）、`MSG_*` 常量 **17 个**（`engine\ide\WsProtocol.java:19` 至 :158）；根文档 03 §2/§3 标题的"34/18"为早期口径（其 §3 表格亦未列 `stream_sync_result`），新增命令时以本表与代码为准。
- 注册顺序即 capabilities.commands 顺序：新增命令若需被 IDE 端能力探测发现，须同步注册并核对回包（`engine\ide\IdeMessageRouter.java:48`）。

## ③ 沙箱执行器契约

- execute 走**独立 Lua 虚拟机**（LuaContextImplement / libengine_base.so JNI），与主引擎脚本环境不共享状态；多客户端并发 execute 由内部锁串行化（`engine\ide\LuaEngineExecutor.java:31`）（`engine\ide\LuaEngineExecutor.java:58`）（`engine\ide\LuaEngineExecutor.java:125`）。
- 执行序列固定三步：① push 空串 + setGlobal `_WS_PARAMS`（`engine\ide\LuaEngineExecutor.java:129`）→ ② `"_WS_RESULT = nil\n" + code` loadBuffer（chunk 名 `ide_exec`）后 pcall（`engine\ide\LuaEngineExecutor.java:133`）（`engine\ide\LuaEngineExecutor.java:55`）→ ③ getGlobal `_WS_RESULT` 按类型取回（nil→无 data 回包；number/boolean→字符串；table/function→类型名占位）（`engine\ide\LuaEngineExecutor.java:150`）。
- 错误约定：执行失败返回以 `__ERROR:` 前缀的串（`engine\ide\WsProtocol.java:185`），ExecuteHandler 据此转 error 回包（`engine\ide\ExecuteHandler.java:27`）；执行器未注入时默认 no-op 实现同前缀报 `engine not attached`（`engine\ide\EngineExecutorHolder.java:19`）。
- print 重定向：沙箱全局 print 被覆写为逐行回调 OutputListener，永不抛错（`engine\ide\LuaEngineExecutor.java:230`）（`engine\ide\LuaEngineExecutor.java:274`）；`[IMG]` 分片协议行同样经此通道输出（`engine\ide\WsProtocol.java:204`）。
- 服务注入清单（MainApp 对沙箱 registerService 共 **18 项**，注册即注入常驻沙箱）：UI、FloatController（`autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:128`）、http/luajava/LuaEngine（MainApp.kt:131/133/135）、file/device/crypt/app（MainApp.kt:138-144）、plugin/ocr/media/ime/mysql/event（MainApp.kt:147-157）、compat（MainApp.kt:160）、uimgr/uilib（MainApp.kt:163/164）。
- 与主引擎 pushLocalServices 的差异：注入序列一致（非 LuaObjectAdapter 的服务包成 JavaObjectWrapper → push → setGlobal，`engine\ide\LuaEngineExecutor.java:366`）；但主引擎的 display/input 为 C++ 对象注入，engine_base 无对应 JNI，**沙箱侧不可对齐**（`engine\ide\LuaEngineExecutor.java:44`）；沙箱额外多 `ROOT_DIR` 全局注入（`engine\ide\LuaEngineExecutor.java:320`）与插件 Lua 贡献 `contributePlugins()`（`engine\ide\LuaEngineExecutor.java:343`）。
- 生命周期语义：stop → 主引擎 interrupt（`engine\ide\LuaEngineExecutor.java:188`）；pause/resume 经 LifecycleDelegate 透传 AutoLuaEngineProxy，未接线时 no-op（`engine\ide\LuaEngineExecutor.java:361`）（`autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:167`）。

## ④ 大文件分片协议

- push_files 会话：start 清空会话缓冲（静默不回包，`engine\ide\PushFilesHandler.java:50`）→ push_file 收集 `{path, content, encoding?}`（encoding 默认 base64、可选 utf8，`engine\ide\PushFilesHandler.java:100`）→ end 统一落盘 `getFilesDir()/ide_push/`（`engine\ide\WsProtocol.java:198`）（`engine\ide\PushFilesHandler.java:124`），回 push_result `{written, failed}`（`engine\ide\PushFilesHandler.java:146`）。
- push_zip 分块：start `{name, totalChunks, totalSize}` 建临时文件 `cache/ide_push_zip/<name>.zip.tmp`（`engine\ide\PushZipHandler.java:115`）→ chunk `{name, index, data}` **index 从 1 起严格顺序校验**、乱序即断会话（`engine\ide\PushZipHandler.java:138`）→ complete `{name, expectedMd5}` 校验分片数与 MD5（`engine\ide\PushZipHandler.java:166`）通过后 ZipInputStream 流式解压到 ide_push/，回 `{unzipped, failed}`（`engine\ide\PushZipHandler.java:176`）。
- 路径安全：push_file 与 zip 条目均经 `IdePaths.safeResolve` 规范化，`..` 越出 ide_push 根目录即拒绝（zip slip 防护）（`engine\ide\IdePaths.java:22`）（`engine\ide\PushZipHandler.java:188`）。
- 插件分块 plugin_push_* 与 push_zip 同构（sha256 整包校验、分块会话 30s 超时丢弃，`engine\ide\WsProtocol.java:104`）（`engine\ide\WsProtocol.java:100`）。
- `[IMG]` 输出分片（脚本 → IDE 方向）：`[IMG]S|w|h|totalChunks` 开始、`[IMG]D|i|base64` 追加（i 从 1 起严格顺序，`engine\ide\ImageChunkAssembler.java:92`）、`[IMG]E` 完成；防护三件套——单帧 base64 累计上限 8MB（`engine\ide\WsProtocol.java:206`）（`engine\ide\ImageChunkAssembler.java:100`）、会话 30s 惰性超时（`engine\ide\ImageChunkAssembler.java:152`）、PNG 魔数校验后广播 image 帧（`engine\ide\ImageChunkAssembler.java:128`）。
- PC 端配套：push_zip 的 complete 等待放宽到 60s（`autolua2\vscode-plugin\src\sync.ts:174`）；增量同步先取 `get_file_md5_list` 基线再差量 push（`autolua2\vscode-plugin\src\sync.ts:58`）。

## ⑤ vscode protocol.ts ↔ WsProtocol.java 对齐铁律

- protocol.ts 是 WsProtocol.java 的**客户端镜像**，新增/改名命令必须双端同步：`Cmd` 33 条与 Java 端 33 个 `CMD_*` 当前一一对应（`autolua2\vscode-plugin\src\protocol.ts:12` 对照 `engine\ide\WsProtocol.java:42`），破坏对应即静默功能失效。
- 已知不对称：Java 端 `MSG_STREAM_SYNC_RESULT`（`engine\ide\WsProtocol.java:128`）在 protocol.ts 的 `Msg` 中**无镜像**（`autolua2\vscode-plugin\src\protocol.ts:55`），该回包目前仅 tools\remote.html 以字符串字面量消费（`autolua2\tools\remote.html:263`），vscode 插件不订阅；补协议常量时应先补齐此缺口。
- 静默命令契约：push_files_start / push_file / push_zip_start / push_zip_chunk 等设备端不回包，PC 端以 `expected=null` 发送并立即返回（`autolua2\vscode-plugin\src\deviceManager.ts:75`；服务端对应 replyOnNull=false 见 `engine\ide\PushFilesHandler.java:58`）；新增 fire-and-forget 命令须双端同时声明。
- 回包 type 归属：会话型 Handler 用匿名类分别声明 responseType（如 push_files_end→push_result，`engine\ide\PushFilesHandler.java:82`），PC 端 `request(type, data, expected)` 的 expected 必须与之一致（`autolua2\vscode-plugin\src\deviceManager.ts:79`）。
- 双端各持一份端口 / UDP / 超时常量（WS_PORT_MIN/MAX、UDP_PROBE、UDP_ANSWER、CMD_TIMEOUT_MS、HEARTBEAT_MS，`autolua2\vscode-plugin\src\protocol.ts:82`），修改任一数值需双向同步并回写根文档 03 §1。
- 广播消息（log / image / script_state / stream_rotate）无请求配对，PC 端在 message 事件按 type 分支消费（`autolua2\vscode-plugin\src\deviceManager.ts:145`；script_state 消费见 `autolua2\vscode-plugin\src\runner.ts:25`）。

## ⑥ 二进制流（权威 = 03 §4）

- 同一 WS 连接混传文本 JSON 与二进制帧；服务端忽略客户端上行二进制（投屏数据只有设备→PC 方向，`engine\ide\IdeWebSocketServer.java:156`），定向二进制发送经 `sendBinaryToClient`（`engine\ide\IdeWebSocketServer.java:196`）。
- h264 帧头 `[4B seq][4B flag][4B len][payload]`：flag=0 为 avcC 配置集（SPS/PPS，WebCodecs 直接作 VideoDecoder.configure 的 description）；flag=1 为 AVCC 视频帧（每 NALU 前 4B 大端长度、无起始码，首 NALU `type&0x1F==5` 为 I 帧）（`engine\ide\WsProtocol.java:161`）。
- jpeg 帧头 `[4B seq][4B w][4B h][4B len][jpeg]`：w/h 为缩放后实际宽高，PC 端 drawImage 直接使用（`engine\ide\WsProtocol.java:170`）。
- 设备端编码：MediaCodec 输出 Annex-B → AVCC 化下发（`autolua2\engine\src\main\java\com\autolua\engine\display\stream\ScreenStreamController.java:696`），SPS/PPS 提取构造 avcC box 作 flag=0 首帧（ScreenStreamController.java:733）；旋转推 `stream_rotate` 事件且编码器随新尺寸惰性重建（ScreenStreamController.java:384）。
- stream_sync 契约：PC 端解码器出错重建后必须从 IDR 起解，设备端经 request-sync-frame 让下一帧即为 IDR，不支持该参数时降级重建编码器（ScreenStreamController.java:140）；回包 type 固定 `stream_sync_result`，`requested:false` 表示无 h264 会话（`engine\ide\StreamCommandsHandler.java:45`）。
- 模拟器静止屏不合成新帧：等不到 I 帧时须滑动画面或发 stream_sync 强制刷帧（根文档 03 §4）；PC 端 remote.html 有 IDR 超时自动重发逻辑（`autolua2\tools\remote.html:469`）。
- 分析工具：`tools\h264_probe.mjs` dump avcC 结构与 NALU type 序列（帧头 seq/flag/len 读取见 `autolua2\tools\h264_probe.mjs:21`）；`tools\ws_test.mjs` 覆盖 capabilities+ping+二进制帧收包（`autolua2\tools\ws_test.mjs:15`）。
