# AGENTS.md

本目录是**多项目工作区**，不是单仓库单构建。各子目录相互独立、工具链不同，改动前先确认自己在哪个子项目里。

## 工作区构成

| 目录 | 是什么 | git 状态 |
|---|---|---|
| `game-bot/` | 懒人精灵 Lua + AutoJS JS 的征途手游挂机脚本（`main.lua` 入口，`modules/`、`utils/`） | 根仓库唯一跟踪内容；根仓库**尚无任何提交**，无 remote |
| `jy-frame-lrjl/` | 懒人精灵「JY 全模块框架」源码 + `doc-flat/` 知识库（34 篇）+ `.trae/rules/` 规则 | 独立 git，origin=`https://gitee.com/lwg_6/jy-frame-lrjl.git`，分支 `master` |
| `jy-engine/` | JY-Engine 源码主仓：`autolua2/`（Android 引擎）+ `engine/`（云控壳 Lua 工作区）+ `autoScript/`（Python 脚本生成套装）+ `docs/` | **不是 git 仓库**（经 `.download_jy.py` 从 gitee 拉取），勿对其执行 git 命令；`autolua2` 的 3rd 子模块未下载 |
| `jy-engine-raw/` | 引擎单文件参考抽取：`JY_engine.lua`、`frameSdk/`、`main.lua` | 无 git |
| `jy-engine-test/` | `frameSdk-autolua2` 静态自测（`test.lua` + SDK 副本） | 无 git |
| `.download_jy.py`、`jy-engine-tree.json` | gitee 文件清单与并行拉取脚本（`python .download_jy.py`，默认跳过 3rd 与 >4MB 文件） | — |

## game-bot/（根仓库唯一跟踪内容）

- 懒人精灵 Lua 挂机脚本：入口 `main.lua`，**顶层直接执行** `init()` + `mainLoop()`（无 `return M`），模块用 `import("config/settings")` 等相对路径加载。
- `main.js` 是 AutoJS（JavaScript）的平行实现，入口在文件末尾 `startFarm()`；与 Lua 版是两份独立代码，改一处不要假设另一处同步。
- 依赖懒人精灵/AutoJS 运行时全局（`import`、`device`、`events`、`files`、`findColor`、`tap`、`sleep` 等），**本容器无法运行**，无测试、无构建。
- 配置集中在 `config/settings.lua`（开发分辨率 720x1280；日志落 `/sdcard/game-bot/logs/`；模板 `/sdcard/game-bot/templates/`）。
- `color_finder.lua` 是核心找色封装；`*_example.lua`、`code_viewer.lua` 是示例/手机端源码查看工具，不在运行链路上。

## 容器内环境（重要）

- 容器为 Linux（PRoot），可用 `lua5.4`、`python3`、`node`；**没有 `adb`**。
- `autolua2` 的 Android 构建工具链是 **Windows + Android Studio**（JDK17 / NDK 25.2 / CMake 3.22），**本容器无法构建 APK**，不要在容器里尝试 `gradlew`。
- 因此：容器内能做的验证主要是 Lua 静态测试与文本检查；真机/模拟器联调需在 Windows 侧。

## 改动前后怎么做验证

- **frameSdk 静态自测（本地可跑，无需引擎）**：
  `cd ~/workspace/jy-engine-test && lua5.4 test.lua` —— mock 引擎注入的全局函数，预期 `34 通过, 0 失败`。
  `test.lua` 用绝对路径 `~/workspace/jy-engine-test/?.lua` 装载 `frameSdk-autolua2`，移动目录需同步改第 146 行。
- 引擎文档一致性（设计真相源，Windows 侧仓库根执行）：`python docs/check_docs_consistency.py`，全 PASS 才算一致。

## 硬规则：改 `jy-frame-lrjl/`（来自其 `.trae/rules/`，优先级高）

- AI **仅可修改 3 个文件**：`jy-frame/脚本/Funs.lua`、`JUI.lua`、`jy-frame.lua`。
- `jy-frame/脚本/JY/` 全部为框架核心，**禁止修改**。（2026-09-09 所有者指令的例外：`nd_*` 节点指令已合入 `JY/Utils/ActionsUtils.lua` 与 `JY/Actions/ActionFramework.lua`；此后 `JY/` 仍禁改。）
- 职责划分：`Funs.lua` 是**唯一**可写函数体/注册函数（`jy_task.FunRegistry.addFun`）的地方；`jy-frame.lua` **只**放 `taps`/`conf`/`vars` 定义与 `EnvInit`/`run.scriptRun` 调用，**禁止**写函数体或注册。
- 涉及 JY 框架的技术问答，一律以 `jy-frame-lrjl/doc-flat/` 知识库为答案依据（问题类型→文档映射见 `脚本开发规则.md`）。
- 任务模块 `taps` 的字段/锚点/执行顺序等规范见 `.trae/rules/任务模块编写规则.md`；特征行按数字索引升序执行，与锚点无关。

## 跨项目联动（`.trae/rules/联动流水线规则.md`）

- **JY 框架有两份副本，必须保持一致**：`jy-engine/engine/frame/JY/` 与 `jy-frame-lrjl/jy-frame/脚本/JY/`（25 个 .lua 同名一一对应）。同步用**整文件覆盖**（不做手工 merge），改一侧要同步另一侧。
- 依赖链：`engine` 打包依赖 `autolua2` 源码 → 改 `autolua2` 必须重新打包 `engine` 再装机（旧 APK 内嵌旧引擎）。
- JY 文件含 emoji/特殊字符，同步时不要用会截断的读取/写工具。

## jy-engine 构建与联调命令（Windows 侧，容器内不可执行，仅备查）

- 构建 APK：`powershell -File autolua2\tools\build_app.ps1`（重定向目录 `autolua2\build\` 不存在会**静默失败**，先建目录）。
- 引擎全量自测：`cd autolua2\tools\selftest && node run_remote.js`（参考基线 PASS=442 FAIL=0）。
- 云控端到端：在 `jy-engine/engine/` 下 `python script/ws_client.py --demo --secret-file config\brand.json`。
- 前置：已 root 的模拟器；重装 APK 或杀过引擎进程后，先清 `app_process64` 残留 daemon，否则节点域全 FAIL。

## 改云控壳/协议前的约束（`jy-engine/engine/docs/AGENT_GUIDE.md`）

- **别改** `engine/frame/jy_protocol.lua`、`engine/frame/jyframe.lua`、`engine/main.lua`（协议与壳工程打包进 APK，改动须重打包才生效）。
- 契约是**设备端零预置业务数据**：`taps`/`conf`/`vars` 全靠 WS（9300，PSK 帧加密）下发。
- 断言语义：`ok:true` 只表示「指令执行无异常」，业务成败看 `ret`/`msg`（`pageTest` 期望 `ret=5`，不是 `1`）。

## 已知坑（跨项目）

- 引擎内置 `loadfile` 对缺失文件**直接抛错**（非返回 nil）；用 `loadfile` 探测文件必须 `pcall` 包裹。
- cjson 解出的 JSON 数字键是字符串，JY 消费用 number 索引：`sync` 路径会自动转换，自写客户端走内联路径需自行处理。
- 改 Lua/C++ 引擎后若 `clean` 或编辑器外部改源码，Gradle VFS/Ninja 缓存会报旧错误 → 清 `engine\.cxx` 或 `--no-build-cache`。
