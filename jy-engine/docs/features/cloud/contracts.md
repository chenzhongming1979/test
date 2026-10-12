# cloud 契约 —— 引擎 WS 消息通道（引擎能力，零协议语义）

> 定位：**引擎只负责 WS 消息收发**。通道 = 全局 `cloud`（Lua 绑定）+ `cloudServer`（Java LocalService）+ PSK 帧加密；**不解释消息内容**——无 op 路由、无回执约定、无业务逻辑、不读任何业务配置。
> 上层协议由脚本自行定义：jy-frame 提供参考实现（工程侧 `frame/jy_protocol.lua`，随脚本打包、run_script 动态执行），其协议契约见 [protocol-jyframe.md](protocol-jyframe.md)——**引擎零感知**。

## 1. 通道 API（全局 `cloud`）

CloudService 实现 `LuaEnvContributor`，向每个新建 LuaContext 注入全局 `cloud`（root 进程 / app 沙箱 / IDE 沙箱三环境一致；chunk return 表 → pcall → setGlobal，避开 _G 代理吞赋值）：

| 方法 | 签名 | 说明 |
|---|---|---|
| `open` | `open{port=9300, secret=""} -> bool, err?` | 启动 WS server（不阻塞）；secret 非空=加密模式；端口占用/密钥派生失败 false |
| `onMessage` | `onMessage(fn) -> bool` | 注册回调（可多个）：每条已解码 JSON table 依次回调；异常被吞 |
| `send` | `send(table|string) -> bool` | 广播：table 自动 JSON 编码 / string 原样（加密模式自动组帧，编帧失败 false） |
| `recv` | `recv(ms) -> table|string|nil` | 手动拉一条：table=已解码；string=原始 JSON（解码失败透传）；nil=无消息 |
| `run` | `run{pollMs=30, onIdle=fn} -> bool, err?` | **阻塞消息泵**（永不返回）：poll→decode→回调；每轮 sleep(1) 挂起点（pause/interrupt 响应）；无消息轮次回调 onIdle（业务心跳/周期任务钩子） |
| `close` | `close() -> bool` | 停止 server（幂等） |
| `connections` | `connections() -> int` | 在线连接数 |
| `isEncrypted` | `isEncrypted() -> bool` | 是否加密模式（open 时传了 secret） |

- 泵模型约束：消息处理必须在 Lua 主线程（Java 无法回调 Lua）；`open` 与 `run` 分离——简单场景 `onMessage → open → run` 三行，自定义主循环场景 `open` 后 `while true + recv` 自主控制
- 依赖运行时 rawget（cloudServer/json/sleep），与服务注入顺序无关；`run` 需全局 sleep 或 frameSdk.sleep（仅作挂起点函数，无任何框架装载）
- **server 生命周期（随脚本容器回收）**：泵 sleep 裸调（interrupt 在挂起点抛 error 终止脚本，pcall 会吞信号）；绑定内注册全局 `OnStop` 钩子（LuaState 销毁前）+ `ScriptContextLifecycleListener` 兜底（state=kIdle 引擎保证必达）双通道 close；`stop` 异步执行 + `start` 等待 stopDone 门闩 + `awaitStarted` 绑定确认——停止后再运行不重启 app 全链可用（详见 [features/runtime/](../runtime/)）

## 2. 高级直连面（`cloudServer` LocalService，ICloudService）

Lua 可直接调底层服务（点调用无 self，对齐 crypt 风格）；绑定层 `cloud.*` 即基于它实现：

| 方法 | 签名 | 说明 |
|---|---|---|
| `start` | `start(port, secret) -> bool` | 启动 WS server；secret 非空=加密模式（幂等）；端口占用/密钥派生失败 false |
| `poll` | `poll(timeoutMs) -> string?` | 取一条**已解密**明文 JSON（毫秒阻塞）；无消息 nil；队列 4096 满丢最旧 |
| `send` | `send(plainJson) -> bool` | 广播明文 JSON（加密模式自动组帧；编帧失败 false，不落明文） |
| `connections` | `connections() -> int` | 在线连接数 |
| `isRunning` | `isRunning() -> bool` | server 是否运行中 |
| `isEncrypted` | `isEncrypted() -> bool` | 是否加密模式 |
| `stop` | `stop() -> bool` | 停止 server（幂等） |

## 3. PSK 帧加密（帧 v1，Java 实现）

> 归属：帧编解码在引擎 Java 层 `CloudFrameCodec.java`（复用 CryptoLib 的 sha256/aesCryptBytes）；Lua 侧只经通道收发明文 JSON。以下密钥派生与帧格式为 **Java 与 PC 端（ws_client FrameCrypto）的对齐契约**。

### 3.1 密钥与派生（两侧同构）

PSK 由 `open{secret=}` / `start(port, secret)` 显式传入（引擎不读任何业务配置；jy-frame 打包流水线取自 brand.json 的 `secret` 字段，pack.py 可自动生成 64 hex 字符）。

```
encKey = sha256(psk .. "|enc1") 前 32 字符   -- 32 字节 ASCII = AES-256 key
sigKey = sha256(psk .. "|sig1") 前 32 字符   -- 防篡改域密钥
```

sha256 为 UTF8 字节级（Python `hashlib` 与 Java `CryptoLib.sha256` 对同一 ASCII 串结果一致）。

### 3.2 帧格式（外层仍 JSON）

```json
{"e":1, "iv":"<16位hex>", "d":"<明文JSON的AES-256-CBC密文Base64>", "s":"<sha256(sigKey|iv|d) 前16位hex>"}
```

- 加密（Java `encode`）：`iv` = 时间 hex + 计数器取 16 位小写 hex（可打印、每帧不同）；`ct = aesCryptBytes(true, encKey, plain, iv)`（AES-256-CBC/PKCS5，密文 Base64）；`s = sha256(sigKey+"|"+iv+"|"+d) 前 16 位`
- 接收（Java `decode`）：外层 `e==1` → 验 s → 解密 → 明文 JSON 入队（Lua poll 拉取）；任何失败定向回明文错误帧
- **关键约束**：key/iv 全程可打印 ASCII（随机二进制经 UTF8 往返会损坏），密文走 Base64——Java 实现天然满足（hex 串/标准 Base64）；PC 端自定义帧字段时沿用此约束

### 3.3 错误帧（恒明文，无信息泄漏；Java 解帧失败时定向回发送方）

| 帧 | 含义 |
|---|---|
| `{"e":0,"err":"encrypted"}` | 加密 server 收到明文帧 |
| `{"e":0,"err":"plaintext"}` | 明文 server 收到加密帧（外层 e=1） |
| `{"e":0,"err":"bad frame"}` | 加密帧缺字段/验签失败/解密失败 |
| `{"e":0,"err":"enc fail"}` | 服务端组帧失败（回退错误帧，不落明文） |

### 3.4 兼容矩阵

| 设备端（open secret） | 客户端（--secret） | 行为 |
|---|---|---|
| 无 | 无 | 明文直通 |
| 有 | 有（同值） | 全帧加密 |
| 有 | 无 | 收明文回 `{"e":0,"err":"encrypted"}`（客户端据此报错退出） |
| 无 | 有 | 服务端回 `{"e":0,"err":"plaintext"}` |

## 4. 设备端日志落盘（引擎能力，与通道/上层协议无关的独立保障）

- 引擎 `ScriptLogPersister`：挂 IDE 通道汇聚点（root/app 双模式、IDE 未连接同样落盘）；目录三级探测 `/sdcard/Pictures/autolua_logs` → `/sdcard/<pkg>/logs` → 应用专属外部 `files/logs`（免权限兜底）；按天 `script_yyyyMMdd.log` 追加，单文件 5MB 滚动序号，保留 7 天 + 总量 64MB 上限
- Manifest 已声明 `MANAGE_EXTERNAL_STORAGE`（未授权自动回退应用专属目录，两种状态日志均不丢）
- 上层如需"日志实时推送"（`{"op":"log"}` 广播），由脚本自行包装 print 调 `cloud.send`（jy-frame 参考实现的做法，见 protocol-jyframe.md §6）——通道不特殊对待任何帧

## 5. 通道实测记录（emulator-5558 / autolua2）

### 5.1 PSK 帧加密实测（2026-09-20）

- 明文回归：`ws_client.py --demo --no-auth` → **9/9 OK**，`enc:false`
- 加密全链：`--demo --secret-file brand.json` → **11/11 OK**（全链路在 PC pycryptodome ↔ Java CloudFrameCodec 加密帧内完成，`enc:true`）
- 混用矩阵：明文客户端 → 加密 server → `{"e":0,"err":"encrypted"}`；加密客户端 → 明文 server → `{"e":0,"err":"plaintext"}`（恒明文错误帧）
- 抓包不可读（原始密文帧，无明文）：`{"e": 1, "iv": "6aafab530f0a4000", "d": "FZNeS/CTW+Nh1obj...", "s": "..."}`
- pack.py：`--make-zip` secret 透传/自动生成 `token_hex(32)`；`--secret` 显式指定优先

### 5.2 通道 Java 下沉实测（2026-09-20，autolua2 2de62c8）

- 构建 `gradlew assembleDebug` ✅（CloudFrameCodec/CloudWebSocketServer/CloudService 编译 + 三处注册）
- 加密全链 11/11 + 明文回归 9/9 + 实时日志 `--logs` 逐条到达（单条无重复）
- 顺带修复（frameSdk，上层适配包）：device.lua import 三路径 pcall 安全壳+通配降级；setWorkPath 工程根校正

### 5.3 通道引擎内置实测（2026-09-21，autolua2 77ee8f2）

- CloudService 实现 LuaEnvContributor 注入全局 `cloud`：部署后 main.lua 直接 `cloud.open/onMessage/run`，无 dofile 引导
- 加密 demo 11/11 零回归（上层 jy 协议参考实现挂 `cloud.onMessage`，行为与内置前一致）；secret 链路生效（brand.secret → open 参数 → Java 加密，明文客户端被拒 encrypted=预期）
- CLOUD_LUA 内嵌 Kotlin raw string，`load` 语法校验通过；依赖全运行时 rawget，与服务注入顺序无关
