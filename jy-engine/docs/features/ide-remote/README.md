# IDE 远程调试链路（ide-remote）

> 文档版本 v1.0（2026-09-18，文档三层化重组新建）
> 本域权威范围：VS Code 侧开发调试链路——设备端 IDE WebSocket 服务（engine\ide 全量 Handler）、编辑器插件（vscode-plugin）、远程投屏与操控（display\stream + tools\remote.html）；WS 帧格式与命令/消息全集以根文档 03-WS协议规范.md 为权威，本域只做域内契约摘要、对码落点与双端对齐契约。

## 本域文档

| 文档 | 内容 | 读者 |
|---|---|---|
| [contracts.md](contracts.md) | 域内契约：端口与 UDP 发现、命令分发注册表（逐 Handler 对码行号）、沙箱执行器、大文件分片协议、protocol.ts ↔ WsProtocol.java 对齐铁律、二进制流 | 改协议 / 加命令 / 排查连接前必读 |
| [design.md](design.md) | 设计：三端架构总述、服务生命周期与 Handler 注册、沙箱执行模型、文件同步与脚本运行链路、图色远程计算与投屏流、代码映射表、演进 | 理解整条链路与二次开发 |

## 全局真相源交叉引用

| 根文档 | 本域相关章节 | 说明 |
|---|---|---|
| [03-WS协议规范.md](../../03-WS协议规范.md) | §1 连接与发现、§2 客户端命令表、§3 设备消息表、§4 二进制流、§5 实现索引 | WS 协议权威：帧格式 / 34 命令口径 / 消息表 / 二进制流 |
| [01-架构总览.md](../../01-架构总览.md) | §7 模块地图（vscode-plugin 小节 :200）、§9.5 IDE 图色远程计算（:312） | 插件在整体架构中的位置；图色远程计算的设计定位 |
| [02-Lua-API规范.md](../../02-Lua-API规范.md) | 运行环境说明（IDE 沙箱行） | luaApiData.ts API 提示数据与本手册同步 |
| [INTEGRATION_PLAN.md](../engine-core/INTEGRATION_PLAN.md) | §7 方案六 IDE 调试 WS（:641，协议规范 §7.3 :657）、§9.8 方案八 WS 协议扩展汇总（:898）、§9.11 调试通道整合决策（:932） | 域内各能力的立项背景、分期与验收标准 |

## 代码入口

| 端 | 路径（相对仓库根） | 职责 |
|---|---|---|
| 服务端 | `autolua2\engine\src\main\java\com\autolua\engine\ide\` | WS 服务与全部命令 Handler：WsProtocol（常量）、IdeWebSocketServer（承载）、IdeServerManager（生命周期，内置 Handler 注册 :223）、IdeMessageRouter（分发）、LuaEngineExecutor（沙箱）+ 14 个业务 Handler |
| 服务端（投屏） | `autolua2\engine\src\main\java\com\autolua\engine\display\stream\ScreenStreamController.java` | H264/JPEG 编码与二进制帧下发（startSession :160、requestKeyFrame :140） |
| 编辑器端 | `autolua2\vscode-plugin\src\`：extension.ts、protocol.ts、deviceManager.ts、discovery.ts、adb.ts、sync.ts、runner.ts、luaApiData.ts | 连接 / 发现（UDP+adb）/ 增量同步与 zip 推送 / 脚本运行控制 / API 提示（autolua-vscode 0.5.11，package.json:5） |
| app 侧接线 | `autolua2\app\src\main\java\com\autolua\autolua2\`：MainApp.kt、debug\DiscoveryManager.kt、debug\BroadcastLocationServer.kt | 服务启停与委托接线（MainApp.kt:177-187）、UDP 发现应答（DiscoveryManager.kt:60） |
| 工具 | `autolua2\tools\`：ws_test.mjs、ws_test2.mjs、ws_test3.mjs、h264_probe.mjs、remote.html | 协议自测脚本；remote.html 为零安装远程控制台（对照 INTEGRATION_PLAN §9.7，remote.html:9） |

## 何时读本文档

- 要**新增或修改一条 WS 命令** → contracts.md ②（注册表）与 ⑤（双端对齐铁律），并同步根文档 03 §2/§3。
- **连接不上 / 端口回退 / UDP 发现失效** → contracts.md ①；adb 场景另见 03 §1（APK 重装后 forward 偶发失效需重设）。
- **execute 结果异常、_WS_RESULT / 服务注入问题** → contracts.md ③ 与 design.md「沙箱执行模型」。
- **推文件 / 推 zip 卡住、分片乱序、MD5 校验失败** → contracts.md ④。
- **投屏花屏 / 黑屏 / 旋转后卡住 / 模拟器静止屏不出帧** → contracts.md ⑥ 与 design.md「图色远程计算与投屏流」。
- **想了解链路全貌**（谁启动谁、数据怎么流） → design.md「架构总述」通读。
- 查 Lua API 签名 → 直接读 ../../02-Lua-API规范.md（luaApiData.ts 仅是提示数据镜像）。
