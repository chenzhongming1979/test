# jy-frame 云控 App · Agent 开发与测试指南

> 本文档面向 AI Agent：给出对「云控贴牌 App」生成业务代码（taps/conf/vars 下发数据、auth Provider、Lua 脚本）并用 PC 端测试客户端做端到端验证所需的全部契约。
> 所有内容以仓库代码为准（`frame/jy_protocol.lua`、`script/ws_client.py`），本文不脑补。
> 更深层的设计文档见引擎仓库 `jy-frame-engine/docs/features/cloud/`。

## 1. 这个 App 是什么

一台 Android 设备（真机/模拟器）上安装的贴牌 APK（示例品牌：`测试助手` / `com.administrator.myapp`）。
App 内置 **jy-frame 云控壳工程**（Lua）：启动后在本机 `9300` 端口开 WebSocket 服务，等待 PC 端「云控服务器」连入，接收指令驱动 JY 自动化框架执行脚本（图色/点击）。

- 工作区（本目录）：`D:\Project\script\lrjl\jy-frame-lrjl\jy-frame-engine\engine`（已并入 jy-frame-engine 联动仓库），Python/Lua 源都在这里
- 打包流水线：VSCode 插件一键打包（组壳 zip → 写 `config/brand.json` → 调 `script/pack.py --brand` → gradle 贴牌构建 → `dist/<应用名>.apk`）
- 当前设备：模拟器 `emulator-5558`（adb devices 列表中的名字，`--device` 参数传它）

## 2. 运行模型（Agent 必须知道的拓扑）

```
PC（云控服务器角色）                      设备（App，WS 服务端 9300 端口）
ws_client.py  ──adb forward tcp:19300──▶  cloud.lua（Java 层 WS 通道，Lua 只见明文 JSON）
  │  发 {op:...} 指令                      └─ jy_protocol.lua dispatch 分发
  │  收 {op:"result"/"log"}                  ├─ JY 框架执行（EnvInit/TaskScheduler/ActionFramework）
  └─ AES-256-CBC 帧加密（PSK）               └─ print 桥回推日志帧
```

- App 侧入口：打开项目（jyframe-cloud）→ `main.lua` 阻塞在 `cloud.run` 泵上；点「运行脚本」+ 悬浮球可再启动后端引擎实例（同样跑 `main.lua`）
- **先决条件**：App 已安装并启动工程（悬浮球/云控服务已开）。9300 未监听时 PC 连接会失败
- 双实例注意：UI 预览实例与后端引擎实例各跑一份 `main.lua`，但 cloud 通道是 Java 层单例，实测不冲突

## 3. 设备端路径与关键文件

| 路径 | 说明 |
|---|---|
| `/sdcard/Android/data/<包名>/files/project/projects/jyframe-cloud/v1.0/` | **工程根**（Lua 的 `__SCRIPT_DIR__`） |
| `<工程根>/brand.json` | 打包时注入：`{"cloud_url": "...", "secret": "<64hex>"}`，jyframe.lua 启动时读取 |
| `<工程根>/main.config` | JY 框架运行配置（首启自动预写） |
| `<工程根>/UISet最后配置.txt` / `UISet<名>.txt` | JUI 面板保存的控件值 JSON（panel/uiset op 读写） |
| `/sdcard/Pictures/autolua_logs/` | 引擎日志落盘（按天滚动，不依赖云控通道） |

PC 侧对应物：工作区 `config/brand.json`（打包配置，含同一把 `secret`）。

## 4. 连接与加密（PSK 帧加密 v1）

### 4.1 连接三步

```bash
# 1) 端口转发（ws_client 会自动做；手动等价命令如下）
adb -s emulator-5558 forward tcp:19300 tcp:9300
# 2) WebSocket 连 PC 本地端口
ws://127.0.0.1:19300
# 3) 收发 JSON（PSK 配置时整帧加密）
```

### 4.2 加密帧格式（`script/ws_client.py` FrameCrypto 与引擎 cloud.lua 同构）

- 派生密钥（32 字节 ASCII）：
  - `encKey = sha256(psk + "|enc1") hex[:32]`
  - `sigKey = sha256(psk + "|sig1") hex[:32]`
- 加密：AES-256-CBC，IV 为 16 位可打印 ASCII hex 字符串，PKCS7 填充（块 16）
- 发送帧：`{"e":1,"iv":"<16hex>","d":"<密文Base64>","s":"<sha256(sigKey|iv|d) hex[:16]>"}`
- 收帧：`e==1` 按上述解密；`{"e":0,"err":...}` 为设备明文错误帧（直通处理）
- PSK 来源：工作区 `config/brand.json` 的 `secret` 字段（与打包进设备 zip 根 brand.json 同值）；不匹配时报「填充错误/帧校验失败」
- 未配 PSK 可明文 JSON 直连（`--secret` 不传时），前提是打包时未强制加密

## 5. 协议参考（jy_protocol.lua dispatch，权威实现）

### 5.1 消息信封

- 请求：`{"op":"<操作名>", "req":"<可选回执关联ID>", ...参数}`；不带 `req` 则无法按 req 关联应答（建议总是带）
- 响应：`{"op":"result","req":"...","echoOp":"<请求op>","ok":true|false,"msg":"...","ret":<退出码>,"state":{...},"ts":<unix秒>}`
  - `ret`：仅执行类 op 回（msg 形如 `退出码=5` 时自动解析为数字 ret）
  - `state`：state/get/debug/panel/uiset/auth 的附加数据
- 日志帧（广播，随时插入）：`{"op":"log","line":"...","ts":...}` —— 脚本任意 `print(...)` 实时回推（print 桥，[jy_protocol.lua:709](../frame/jy_protocol.lua)）

### 5.2 op 一览

| op | 参数 | 放行(免auth) | 说明 |
|---|---|---|---|
| `ping` | - | ✔ | 回 `msg:"pong"`，链路探活 |
| `state` | - | ✔ | 回 `state`：`{inited,dirty,enc,scriptRunning,lastOp,auth{status,provider,card,expire,remain},tapsModules}` |
| `auth` | `card`（必填，string） | ✔ | 授权；成功回 `state.auth`（不含 token） |
| `sync` | `taps`/`conf`/`vars`（table，可任选） | ✖ | 合并下发环境数据；`conf.auth` 特殊透传（不进 G 注册表）；详见 §6 |
| `init` | - | ✖ | 显式 EnvInit（幂等；sync 后自动标脏，执行前会自动 init） |
| `get` | `key`（可省略）=`taps`\|`conf`\|`vars` | ✔ | 读回框架数据（未 init 时回骨架+pending 合并预览） |
| `debug` | `key`（可选） | ✔ | 诊断：回 G.taps() 全量或指定键（`state.value`） |
| `pageTest` | `page`（必填，taps 模块名）；可内联 `taps/conf/vars` | ✖ | 层1：跑单个任务模块，回退出码 `ret` |
| `taskTest` | `task`（必填，「任务列表」中的任务名）；可内联 | ✖ | 层2：完整任务（参数预处理→前置→actions→taskRun→通用退出） |
| `action` | `row`（必填，单特征行对象）；可内联 | ✖ | 层3：单行执行，回退出码 |
| `scriptRun` | `mode`（5=Model_5，其他=Model）；可内联；可带 `uiConf` | ✖ | 层4：完整脚本独立协程（先 `stop` 才能再启） |
| `stop` | - | ✔ | 停 scriptRun 协程 |
| `panel` | `uiConf`（必填，JUI 面板描述） | ✖ | 弹设置面板**阻塞**至确定/退出，回 `state.uiset={name,values}` |
| `tree` | - | ✔ | 回面板控件树 `state.tree`（面板开着才有效） |
| `uiset` | - | ✔ | 读最后保存的面板配置 `state.uiset` |

### 5.3 授权（auth）

- 状态机：`unauth →(login成功)→ authorized →(到期/心跳连续失败)→ expired`；login 失败 → `denied`（可重新 auth）
- 拦截矩阵（`AUTH_GUARDED`）：`sync/init/pageTest/taskTest/action/scriptRun/panel` 在「已配置 auth 且未授权」时一律回 `{"ok":false,"msg":"未授权(先 auth)"}`；`ping/state/auth/debug/get/uiset/tree/stop` 永远放行
- **auth 未配置（未 sync 下发 `conf.auth`）时完全不拦截**（本地调试态，向后兼容）
- Provider 两种：
  - `builtin`：白名单卡密（`cards` 数组），命中授权 1 小时（`expire=now+3600`），心跳恒真
  - `custom`：`dofile <工程根>/<module>.lua`，作者实现接口：`M.login(card, device) → true, token, expireTs | false, 原因`；`M.heartbeat(token) → true, remainSeconds | false, 原因`；`M.logout(token)` 可选
- 心跳：onIdle 内按 `heartbeat_interval`（默认 300s）触发，连续失败 `fail_limit`（默认 3）次 → expired

### 5.4 环境数据下发：两种语义（关键差异）

1. **sync 增量语义**（不带内联字段）：数据合并进骨架（`baseSkeleton` 提供默认 conf），未 init 先入 pending；JSON 数字字符串键（`"1"`）会被 `normalizeKeys` 转回 number 键
2. **内联独占语义**（pageTest/taskTest/action/scriptRun 消息直接携带 `taps/conf/vars`）：**原样透传 EnvInit**，不补骨架默认、不归一化（缺参由调用方负责，缺参报错回 `ok:false`）；conf.auth 仍会先剥离透传
- `dirty` 机制：sync 后标记需重建，下次执行前自动重新 EnvInit（taps 坐标按分辨率换算）

## 6. 数据契约（Agent 生成 taps/conf/vars 的依据）

### 6.1 taps 结构（JY 框架任务模块表）

```json
{
  "云控测试": {"循环次数": 1, "1": {特征行}},
  "任务列表": {"1": {特征行, "任务参数": {"是否有对应设置": false}}},
  "任务列表模块": {"1": "云控任务"}
}
```

- 顶层键 = 任务模块名（`pageTest.page` 用的就是它）
- **特征行 = 混合表**：`[1]`=名称(string)，`[2]`=超时(number)，其余为 hash 参数键。JSON 里用**全字符串键对象**表达（`"1"`/`"2"`），sync 路径会自动把纯数字字符串键转回 number（normalizeKeys）；**内联路径不归一化，必须自己保证消费方兼容**（稳妥做法：内联也用 `"1"`/`"2"` 字符串键 + 行内不带依赖骨架的字段）
- 常用特征行参数（见 demo）：
  - `ff:"ps"` 找色，`p:"x|y|FFFFFF-101010"`（坐标|颜色-偏差），`degree:0.9` 相似度
  - `isClick:false` 是否点击，`out:true` 超时输出，`wait:300` 行后等待
  - `任务参数`：任务参数预处理（`{p1..p9}` 数组形态，或 `{"执行模式":...}` 已预处理形态）

### 6.2 conf（云控配置）

- 骨架默认（sync 增量时可省略）：`appid/开发比例/显示浮窗(false)/卡屏检测(false)/循环次数(1)/outTime(60)/timeout(4000)/ms(80)/comWait(500)/comSleep(700)/任务循环延迟(80)...`
- `conf.auth`（同步/内联均可）：`{"provider":"builtin","cards":["DEMO-CARD-001"]}` 或 `{"provider":"custom","module":"auth_provider"}`
- demo 参考：`ws_client.py` 的 `demo_taps()` / `demo_conf(card)`（720x1280 白屏语义，找中心白色特征）

### 6.3 退出码语义（`ret` 字段）

TaskRunner（pageTest/runWait）返回值：
- `5` = 指定循环次数正常退出（demo 页配置 `"循环次数":1` → 稳定返回 5）
- `true` = 本轮循环继续（未到退出条件）
- `false` = 特征未找到/执行未成功（action/taskTest 常见：屏幕上没有目标特征时回 `退出码=false`，`ok` 仍为 true）

> Agent 写断言时：`ok:true` 只代表「指令执行完毕没有异常」；业务成败要看 `ret`/`msg`（`退出码=5`/`退出码=false`）与日志帧。

## 7. 日志机制

- **print 桥**：jy_protocol.lua 装载时包装全局 `print` → 原生 print 照常（logcat/IDE/落盘）+ `cloud.send({op:"log",line,ts})` 广播。云控框架自身日志带 `[cloud]` 前缀；幂等标志防重复包装
- **PC 收日志**：
  - 实时跟随：`python script/ws_client.py --logs --secret-file config/brand.json`（Ctrl+C 退出）
  - 顺带接收：任何命令等待 result 期间收到并打印的 `<< {"op":"log"...}` 帧
- **设备落盘**：`/sdcard/Pictures/autolua_logs/` 按天滚动（ScriptLogPersister，不依赖云控通道）

## 8. 测试指南（ws_client.py，PC 端权威测试工具）

```bash
cd D:\Project\script\lrjl\jy-frame-lrjl\jy-frame-engine\engine

# 全链路（推荐先跑）：sync(conf.auth) → 未授权拦截 → auth → pageTest/action/taskTest → 错误卡密 → 恢复
python script/ws_client.py --demo --secret-file config\brand.json
# 原始全链路（不下发 conf.auth，验证不拦截的兼容态）
python script/ws_client.py --demo --no-auth
# 单命令
python script/ws_client.py --cmd ping   --secret-file config\brand.json
python script/ws_client.py --cmd state  --secret-file config\brand.json
python script/ws_client.py --cmd pageTest --json "{\"page\":\"云控测试\"}" --secret-file config\brand.json --wait 90
python script/ws_client.py --cmd sync --json "<taps/conf JSON>" --secret-file config\brand.json
# 实时日志跟随
python script/ws_client.py --logs --secret-file config\brand.json
# 指定设备（adb devices 里的名字）
python script/ws_client.py --demo --device <device> --secret-file config\brand.json
```

- demo 结果汇总 `N/N OK`；注意：demo 重复运行时 `pageTest-unauth` 步骤会因设备仍处授权态（卡密 1h 有效）自动 SKIP（已内置）
- 断言提醒：pageTest 期望 `ret=5`（循环次数退出），不要写 `ret=1`

### 故障排查表

| 现象 | 原因/处理 |
|---|---|
| 连接失败/NO REPLY | App 未启动工程（9300 未监听）；先开 App 进项目点运行，`adb devices` 确认设备在线 |
| 解帧失败「填充错误」 | PSK 不一致：PC `config/brand.json` 的 secret 与打包进设备的不是同一把 → 重新打包或改 secret |
| `未授权(先 auth)` | 已配置 auth：先 `sync conf.auth`，再 `auth {card}`（白名单卡密） |
| `无此任务模块: xxx` | page 名不在 taps；先 sync 正确的 taps，或用 `debug`/`get` 读回确认 |
| 退出码=false | 特征没找到：检查 `p` 颜色坐标与设备分辨率/当前画面（demo 是 720x1280 白屏语义） |
| `scriptRun 已在运行` | 先发 `stop` |
| APK 装不上/签名冲突 | 设备已有同包名旧包 → 卸载重装 |

## 9. Agent 代码生成约定

可以生成：
1. **业务 taps/conf/vars JSON**（按 §6 契约），经 `sync` 下发或随执行类 op 内联
2. **auth Provider**（`custom` 模式）：`auth_provider.lua`，实现 `login/heartbeat/logout`，放工程根（打包时随 zip 进入设备）
3. **测试脚本**：基于 ws_client.py 的 Python 编排（连接、sync、auth、执行、断言 ret/msg、收日志）

不要做：
- 不要改 `frame/jy_protocol.lua` / `jyframe.lua` / `main.lua`（协议与壳工程是流水线打包进 APK 的，改动需重新打包才生效）
- 不要假设设备端有预置业务数据（契约：**设备端零预置**，taps/conf/vars 全靠 WS 下发）
- 不要在未 `auth` 时调执行类 op（若下发了 conf.auth）
- 内联下发时不要依赖骨架默认值（原样透传语义）

## 10. 已知坑（实测踩过）

1. 引擎内置 `loadfile` 对缺失文件**直接抛错**（非标准 nil 返回）——生成的 Lua 若用 loadfile 探测文件必须 pcall 包裹
2. 工程入口布局：标准模板 `ui/main.lua`；本壳工程 `main.lua` 直落工程根（引擎已做双布局兼容，勿“修复”成单一路径）
3. 授权态跨运行保留（卡密 1h）：重复测试拦截逻辑需换卡密或等过期
4. `panel` 会阻塞云控泵（弹面板期间其他 op 排队），脚本化测试时避免并行发指令
5. JSON 数字键：cjson 解出的数字键是字符串，JY 消费用 number 索引——sync 路径已自动转换，自写客户端直连内联路径时需自行处理

