# cloud —— 引擎 WS 消息通道（引擎能力）

> 定位：**引擎只负责 WS 消息的收发**——全局 `cloud`（Lua 绑定：open/onMessage/send/recv/run/close）+ `cloudServer`（Java LocalService）+ PSK 帧加密。通道**零协议语义**：不认识 op、不路由、不解释消息内容、不读任何业务配置。
> 上层脚本（如 jy-frame 云控框架）随工程打包、run_script 动态执行，用 `cloud.onMessage` 收消息、自行定义协议并调用业务。jy-frame 的参考实现与协议契约见 [protocol-jyframe.md](protocol-jyframe.md)——引擎对其零感知。

```
┌─ 引擎 APK（Java，可混淆加固）────────────────────────┐
│ CloudWebSocketServer：9300 WS（selector 线程+30s ping）│
│ CloudFrameCodec：PSK 派生 + AES-256-CBC 组解帧         │
│   帧 {"e":1,"iv","d","s"}；错误帧 {"e":0,"err"} 恒明文 │
│ 消息队列（4096）→ Lua poll 拉取明文 JSON               │
│ LocalService "cloudServer"：start/stop/poll/send/     │
│   connections/isRunning/isEncrypted                   │
│ CloudService : LuaEnvContributor → 注入全局 cloud      │
│   （open/onMessage/send/recv/run/close，纯通道零协议） │
└──────────────────────┬───────────────────────────────┘
                       │ 明文 JSON（recv 收 / send 发）
┌──────────────────────┴───────────────────────────────┐
│ 上层脚本（随工程打包动态执行，引擎零感知）              │
│ 以 jy-frame 为例（参考使用者，非引擎内容）：            │
│   main.lua → 装载框架 → cloud.open{port,secret} →     │
│              cloud.run → onMessage 收消息 → 调业务     │
│   协议契约见 protocol-jyframe.md                       │
└───────────────────────────────────────────────────────┘
```

## 现状

| 项 | 状态 |
|---|---|
| **WS 消息通道**（全局 `cloud` 经 LuaEnvContributor 注入三环境；`cloudServer` Java 面；PSK 帧加密） | ✅ 已落地实测（加密 demo 11/11 零回归 + secret 链路生效），autolua2 77ee8f2，2026-09-21 |
| 通道 Java 下沉（WS/PSK 帧加解密/队列入引擎 APK） | ✅ 已落地实测，autolua2 2de62c8 |
| 设备端日志落盘（ScriptLogPersister 按天滚动，独立于通道） | ✅ 已落地实测，autolua2 f9c157f |
| 上层：jy-frame 云控协议参考实现（op 分发/auth/心跳/日志桥，随脚本打包） | ✅ 实测 11/11，见 [protocol-jyframe.md](protocol-jyframe.md) |
| 上层：jy-frame 框架（JY 核心/frameSdk 适配/贴牌打包流水线/VSCode 一键打包） | ✅ 已落地，见 plans/ 任务书系列（业务侧文档） |

## 文件位置

**引擎侧（本仓库 `autolua2/`）**：

| 文件 | 职责 |
|---|---|
| `engine/.../cloud/CloudWebSocketServer.java` | 9300 WS server（selector 线程 + 30s ping），解帧入队（4096），错误帧定向回 |
| `engine/.../cloud/CloudFrameCodec.java` | PSK 派生 + AES-256-CBC 组解帧（帧格式与 ws_client FrameCrypto 逐字兼容） |
| `engine/.../cloud/CloudService.kt` | LocalService `"cloudServer"`（ICloudService）+ **LuaEnvContributor**：注入全局 `cloud` 纯通道 Lua 绑定；root/沙箱/IDE 三处注册、contribute 三环境生效 |
| `engine/.../ide/ScriptLogPersister.java` | 脚本日志设备端落盘（独立于通道）：三级目录探测、按天滚动 5MB 上限 7 天清理 |

**上层脚本侧（jy-frame 工作区 `D:\Project\script\lrjl\engine\`，非引擎内容）**：

| 文件 | 职责 |
|---|---|
| `main.lua` | 脚本入口：装载框架 → `cloud.open{port,secret}` → `cloud.run{onIdle=}`（收 WS 消息、调业务） |
| `jyframe.lua` | 框架装载引导：require 劫持/品牌/main.config/frameSdk/JY；返回 brand 表（secret 供 open） |
| `jy_protocol.lua` | **协议参考实现**（可整行删掉换自定义协议）：op 分发/auth/心跳/print 日志桥 |
| `JY/`、`frameSdk-autolua2/`、`Funs.lua` | jy 框架核心与引擎适配包 |
| `script/ws_client.py` | PC 端客户端 / demo（`--secret`/`--secret-file` 启用 PSK 全帧加密；`--logs` 实时日志） |
| `script/pack.py`、`run_test.py` | 贴牌打包流水线 / 部署脚本（业务侧工具） |

## 相关文档

- [contracts.md](contracts.md) —— **引擎通道契约**（cloud API/cloudServer 面/帧加密/日志落盘）
- [protocol-jyframe.md](protocol-jyframe.md) —— jy-frame 协议契约（上层参考实现，非引擎契约）
- [design.md](design.md) —— 执行模型与关键设计决策
- [client.md](client.md) —— 客户端接入指南
