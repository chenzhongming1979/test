# cloud 设计 —— jy-frame 云控框架

## 1. 架构

```
云端/PC（ws_client.py 或云控服务器）                设备（autolua2 引擎 app + root 引擎进程）
    │                                                  ┌────────────────────────────┐
    │──────── ws://host:9300 ────────────────────────► │ ws_lib(libhv) WS server     │
    │   JSON: {op, req, data...}                       │ cloud.lua pump 循环(主线程)  │
    │                                                  │   ├ normalizeKeys(JSON→Lua) │
    │◄───────────────── result 回执 ──────────────────│   ├ dispatch → handlers      │
    │                                                  │   │  ├ sync  → G 注册表      │
    │                                                  │   │  ├ init  → EnvInit       │
    │                                                  │   │  ├ pageTest/taskTest     │
    │                                                  │   │  ├ action/scriptRun      │
    │                                                  │   │  └ ping/state/stop/debug │
    │                                                  │ main.lua: require 劫持+骨架  │
    │                                                  │ JY 核心: TaskScheduler→     │
    │                                                  │   TaskRunner→ActionFramework│
    │                                                  │ frameSdk-autolua2 垫片       │
    │                                                  └────────────────────────────┘
```

## 2. 关键设计决策（均经模拟器实测）

### 2.1 主线程 pump 模式（非 thread 协程）
**root 引擎进程内 thread 协程不被调度**（实测佐证：loop 首行写 /sdcard/cloud_loop.flag 不落盘；沙箱/app 进程内正常）。故 poll 循环内联主线程：`while true do for _=1,8 do cloud.pollOnce(5) end; sleep(30) end`。执行串行天然成立（G 全局状态不串）；长任务执行期间消息在 hv 队列堆积，执行完继续消费。

### 2.2 require 劫持
引擎 require 走 C++ `CodeProvider.loadModule`（按外部工程根解析模块名），**绕开 package.path**——ide_push 推送目录内的包 require 不到。main.lua 劫持 `_G.require`：
- `frameSdk-autolua2[.xxx]` → dofile 本地包（主入口 short=="" 映射 init.lua）
- `JY.xxx` / `JY/xxx` → 归一化为 `JY/xxx` 后 dofile
- `Funs` → 云控最小版
- 其余走原生 require。带 modCache 缓存。

### 2.3 main.config 预写（绕过 getUISet exitScript）
UIUtils.getUISet 在静态 UI（`工作目录/main.config`）与动态 UI 保存文件均未命中时调用 `exitScript()`。云控无 UI 场景由 main.lua 预写 `main.config`（`{"cloud":{记录日志/轮号休息/账号文件/改机/单号模式/游戏版本}}`），使 initConf/initStatus 的全部 getUISet 查询命中静态分支。

### 2.4 EnvInit 最小骨架
- `conf["appid"]` 必须为数组（initStatus 按 UI选版索引）
- `taps` 必含 `"任务列表"`（行数组）与 `"任务列表模块"`（模块名数组）
- `conf["开发比例"]` = 真实分辨率 → freemInit 换算 scale=1（坐标直通）
- 卡屏检测/浮窗/卡密心跳默认关

### 2.5 sync 键归一化（normalizeKeys）
cjson 解码 JSON 对象产生的数字键是字符串（"1"≠1），JY 消费 taps 行用 ipairs 数字索引。sync 时递归将纯数字字符串键转 number 键。**JSON 表达 Lua 特征行的约定形态**：全字符串键对象 `{"1":名,"2":超时,"ff":"ps","p":...}`——数组形态 `["名",5,{...}]` 会把参数埋进 [3] 丢 hash 键，禁止使用。

### 2.6 4 层执行内联（取退出码）
- 层1 pageTest：`TaskScheduler.new():runWait(taps[page])` 直接取退出码（FrameRun.pageTest 丢弃返回值，故内联）
- 层2 taskTest：复刻 FrameRun.taskTest 链（任务列表查行 → taskParamSet 预处理 → 前置操作 → ActionFramework.actions → ret≠2 时 taskRun → 通用任务退出）
- 层3 action：单特征行 normalize → 任务参数预处理（如有）→ `ActionFramework.actions(row)`
- 层4 scriptRun：EnvInit + `Model/Model_5.run()` 独立协程（阻塞主流程模式；协程句柄供 stop）

### 2.7 EnvInit 懒执行 + dirty 重建
`sync` 在未 init 时合并入 pending 骨架；已 init 时写 G 注册表（G.setTaps/setConfig/setVars）并标记 dirty（taps 需重过 freemInit 坐标换算）。执行类 op 前调 `ensureInit()`。

## 3. 已知限制与坑（实测）

| # | 限制/坑 | 处置 |
|---|---------|------|
| 1 | 引擎 stop_script 后再 run_script，新脚本不执行且旧 ws server 端口残留 | 部署必须重启 app（run_test.py --restart 默认） |
| 2 | 引擎原生全局 import 对 'java.lang.*' 通配直接抛错 | frameSdk resolveImport 包 pcall 安全壳 |
| 3 | getUISet 无 UI 时 exitScript | main.config 预写 |
| 4 | freemInit w() 给模块注入 name 字段 | FrameInit.initTasks 加 name/类型防御（已改，懒人版同步建议） |
| 5 | 引擎 getColor 返回 0xRRGGBB 的 Lua integer | frameSdk 规范化为 "0x%06X" hex 串 |
| 6 | uimgr 代理 root 引擎 WS run_script 场景未注入 | 动态 UI 域降级（SKIP/告警），待引擎侧排查 |
| 7 | PC 端 WS 客户端禁止用 .NET ClientWebSocket（Connection 头含 Keep-Alive 被 Java-WebSocket 拒绝） | 用 Python websocket-client |

## 4. 验证记录

- frameSdk 冒烟：图色 6 项 + OCR + toast/HUD 全 PASS（PASS=10 FAIL=0 SKIP=2；SKIP=动态 UI uimgr 未注入、无障碍未开）
- 云控全链路：9/9 OK（ping/state/sync/init/pageTest[退出码=1]/action/taskTest）
- 环境：emulator-5558（NX629J，720x1280，autolua2 0.0.1 debug）
