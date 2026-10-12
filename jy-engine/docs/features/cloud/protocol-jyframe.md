# jy-frame 云控协议契约（上层脚本参考实现，非引擎契约）

> 归属：**jy-frame 工作区**（`D:\Project\script\lrjl\engine\frame\jy_protocol.lua`，参考实现——不需要可整行删掉换自定义协议）。随脚本打包、run_script 动态执行，挂引擎内置通道 `cloud.onMessage` 分发。**引擎零感知**：本文件全部内容（op 面/auth/心跳/回执）都是 jy-frame 与 PC 端 `ws_client.py` demo 的对齐契约，与引擎无关。
> 通道能力（收发/加密/泵）见 [contracts.md](contracts.md)。

## 1. 回执约定

消息单帧 UTF-8 JSON。请求含可选 `req`（回执关联 id）；响应统一 `{op:"result", req, ok, msg?, ret?, state?, echoOp?, ts}`。协议升级：**业务字段/op 增删只改 jy_protocol.lua + ws_client.py**；帧格式升级才动 CloudFrameCodec.java + ws_client.py FrameCrypto（建议 `e` 字段版本分流做新旧共存）。

## 2. 通用 op

| 请求 | 响应 | 说明 |
|---|---|---|
| `{"op":"ping"}` | `{ok:true, msg:"pong"}` | 连通性 |
| `{"op":"state"}` | `{ok:true, state:{inited,dirty,scriptRunning,lastOp,tapsModules,enc,auth}}` | 框架状态摘要（enc 取自 cloud.isEncrypted()） |
| `{"op":"sync","taps":{},"conf":{},"vars":{}}` | `{ok:true, msg:"sync ok（N 项）"}` | 下发任务/配置/变量（未 init 合并入 pending；已 init 写 G 注册表并标记 dirty） |
| `{"op":"init"}` | `{ok:true, msg:"EnvInit 完成"}` | 显式环境初始化（幂等；sync 后自动标记需重建） |
| `{"op":"stop"}` | `{ok:true}` | 停止 scriptRun 协程 |
| `{"op":"debug","key":"模块名"}` | `{ok:true, state:{value|keys|taps}}` | 诊断：回传 G 注册表结构（仅调试态） |

## 3. 执行类 op（返回退出码）

| 请求 | 成功响应 | 退出码语义（JY 体系 1~15） |
|---|---|---|
| `{"op":"pageTest","page":"模块名"}` | `{ok:true, ret:N, msg:"退出码=N"}` | TaskScheduler:runWait 消费 taps[page] 的返回 |
| `{"op":"taskTest","task":"任务名"}` | `{ok:true, ret:N}` | 任务列表任务全链（预处理→actions→taskRun→通用退出） |
| `{"op":"action","row":{...}}` | `{ok:true, ret:N}` | 单特征行执行 |
| `{"op":"scriptRun","mode":5}` | `{ok:true, msg:"scriptRun 已启动"}` | 层4 完整脚本（独立协程；stop 停止） |

失败：`{ok:false, msg:"原因"}`（含"无此任务模块/任务列表中无此任务/row 格式错误"等）。

## 4. taps JSON 形态约定（重要）

Lua 特征行 = **混合表**：`[1]=名, [2]=超时 + hash 参数键`。JSON 用**全字符串键对象**表达，sync 的 normalizeKeys 递归把纯数字字符串键转 number 键：

```json
"云控测试": {
  "循环次数": 1,
  "1": {"1":"Center White", "2":5, "ff":"ps", "p":"360|640|FFFFFF-101010",
        "degree":0.9, "isClick":false, "out":true, "wait":300}
}
```

- 模块 = 模块级配置键 + 数字索引行（"1"/"2"/...）
- `任务列表` 行附加 `"任务参数"`（对象；`{"是否有对应设置":false}` 走 initTasks 免设置分支）
- `任务列表模块` = 引用模块名的字符串数组
- 禁止数组形态 `["名",5,{...}]`（参数会埋进 [3] 丢 hash 键）

## 5. auth 扩展契约

### 5.1 状态机
`unauth → authing → authorized(心跳保活) → expired/denied`。未授权时执行类与 sync op 一律拒绝（`{ok:false, msg:"未授权"}`），ping/state/debug 放行。

### 5.2 协议
```
{"op":"auth","card":"卡密","device":"设备指纹(可缺省,框架自动补)"} → {ok, state:{auth:{status,expire,remain}}}
```

### 5.3 Provider 配置（jy.conf.auth）
```json
{
  "provider": "custom",                      // custom=Lua 适配器 | builtin=白名单测试
  "module": "auth_provider",                 // custom: DIR/auth_provider.lua
  "heartbeat_interval": 300,                 // 秒
  "fail_limit": 3
}
```
- `conf.auth` 经 `sync` 的 conf 顶层透传（`{"conf":{"auth":{...}}}`）：存 `jy.conf.auth`（deepMerge 覆盖），**不写入 G 注册表**；auth 未配置时 `sync` 不拦截（引导下发通道），配置后未授权时 `sync` 一律拒绝

### 5.4 自定义 Provider 约定（auth_provider.lua）
```lua
local M = {}
-- 返回 true, token, expireTs(os.time 秒) ；失败 false, 原因
function M.login(card, device) end
-- 返回 true, remainSeconds ；失败 false, 原因
function M.heartbeat(token) end
function M.logout(token) end  -- 可选
return M
```

### 5.5 builtin 测试 Provider（无外部平台时联调用）
`{"provider":"builtin"}`：白名单卡密表（云控 conf 注入），login 命中即授权（expire=now+3600），heartbeat 恒真。

## 6. 实时日志帧（jy_protocol 的 print 桥）

- 装载 jy_protocol.lua 时包装 print：每行 → `{"op":"log","line":"...","ts":unix秒}` 经 `cloud.send` **广播**全部在线客户端（加密模式走加密帧）；server 未启动时静默失败，不丢本地日志
- PC 端 `ws_client.py --logs` 实时打印；不装载 jy_protocol.lua 时无此帧（纯自定义协议场景）

## 7. 协议实测记录（emulator-5558 / autolua2）

### 7.1 auth 适配（2026-09-20）

- 向后兼容（未配置 auth）：`ws_client.py --demo --no-auth` → **9/9 OK**（sync/init/pageTest/action/taskTest 全放行）
- auth 联调（卡密 `DEMO-CARD-001`，白名单经 sync conf.auth 下发）→ **11/11 OK**：未授权拦截 / auth 成功 / pageTest ret=1 / action / taskTest / BAD-CARD denied / 重新 auth 恢复 / state 回传 auth 摘要（不回传 token）
- 心跳超限（auth_provider_demo.lua：卡密含 `FAILHB` 时 heartbeat 恒失败）：连续失败达 fail_limit → expired → 6 秒后执行类恢复拒绝 → 重新 auth 恢复 + 心跳续期
- 实现落点：custom Provider 由 `dofile <DIR>/<module>.lua` 装载（按 module 名缓存）；心跳在 `jy.onIdle`（cloud.run 空闲钩子）内计时

### 7.2 加密全链（2026-09-20 / 2026-09-21）

- 明文回归 `--demo --no-auth` → **9/9 OK**；加密全链 `--demo --secret-file brand.json` → **11/11 OK**（全链路在加密帧内完成，`enc:true`）
- 通道引擎内置后（2026-09-21，autolua2 77ee8f2）：jy_protocol.lua 挂 `cloud.onMessage`，加密 demo **11/11 零回归**；secret 链路 brand.secret → main → `cloud.open{secret=}` → Java 加密，明文客户端被拒 encrypted=预期
