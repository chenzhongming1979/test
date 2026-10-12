# JY-Engine

安卓 Lua 自动化引擎 / 云控框架。基于开源项目 [AutoLua2](https://github.com/lizhizhuanshu/AutoLua2)（MIT License）二次开发，更名 **JY-Engine** 并持续定制演进。

> 本仓库为 JY-Engine 源码主仓库：引擎核心 / 云控壳工程 / AI 脚本生成工具链 / 引擎文档中心。发行版 APK 见 [Releases](https://gitee.com/lwg_6/jy-engine/releases)。

## 特性

- **Lua 脚本引擎**：内置 Lua 解释器（NDK 编译），支持协程、Java 桥接（luajava/import）
- **图色/节点双域自动化**：找色找图、坐标触控（root 注入）、无障碍节点域（UiSelector）、Shizuku 注入
- **动态 UI**：Lua 声明式创建悬浮窗面板（多标签页/多配置），脚本级设置面板、配置持久化
- **云控通道**：引擎内置 WebSocket 服务（端口可配 + PSK 帧加密），支持远程下发脚本、参数透传、实时日志回传
- **VSCode 插件**：设备管理、脚本同步推送、一键运行/停止、实时日志、函数文档面板
- **OCR 插件**：ncnn/PaddleOCR 本地离线识别，插件化签名加载
- **脚本容器模型**：一次运行 = 一个容器，LuaState 与跨层资源随容器回收，支持连续 run/stop 不重启
- **日志落盘**：脚本日志按天滚动落盘（5MB 上限、7 天清理），IDE 未连接同样可查
- **多环境**：root / 免 root（app 沙箱）/ IDE 沙箱 三种运行环境

## 下载

前往 [Releases](https://gitee.com/lwg_6/jy-engine/releases) 下载最新 APK：

| 版本 | 说明 |
|---|---|
| v0.1.0 | 首个 JY-Engine 发行版 |

## 快速开始

1. 安装 APK，按引导开启所需权限（存储 / 无障碍 / 投屏虚拟屏，root 环境自动注入）
2. 打开 App「开发」页，启动调试服务（默认 8080 WebSocket）
3. PC 端安装配套 VSCode 插件，`ws://设备IP:8080` 连接设备，同步脚本并运行
4. 云控模式：脚本内 `cloud.open{port=9300, secret="..."}` 启动通道，PC/服务端经 WebSocket 下发消息驱动执行

## 仓库结构

```
jy-frame-engine/
├── autolua2/    # 【引擎核心】Android 自动化框架（构建出 APK）
├── engine/      # 【云控壳工程】jy-frame Lua 业务工作区（随 APK 打包/推送执行）
├── autoScript/  # 【脚本生成】AI 全自动脚本生成套装（Python，Trae 调度）
├── docs/        # 【文档中心】引擎平台文档（架构/API/协议/规范/任务书）
└── app/         # 【APK 存档】第三方参考 APK 与 VSCode 插件安装包（本地存档，不入库）
```

```
autolua2 构建 ──▶ APK 装到设备（JY-Engine / 贴牌）
engine/ 壳工程 ──▶ 随 APK 打包（pack.py）或 VSCode 推送到设备运行
PC ws_client.py ──WS 9300（PSK 加密）──▶ 设备 cloud 通道 ──▶ JY 框架执行图色/点击
autoScript ──▶ 生成 JY-Frame 格式脚本 ──▶ 放入 engine/ 工程运行验证
```

### autolua2/ — 引擎核心

**基于 Lua 5.4 的 Android 自动化开发框架**。图色识别、触控注入、无障碍节点操作、应用/设备控制、插件扩展，配套 VSCode 插件远程开发调试（推文件 → 运行 → 日志回传 → 投屏操控）。

| 子目录 | 内容 |
|---|---|
| `engine/` | 引擎库：C++ Lua 解释器 + Java 桥接层（3rd 第三方源码直接入库，自包含构建） |
| `app/` | Android 宿主客户端：装配引擎、动态 UI、悬浮球、IDE 服务。品牌 **JY-Engine**，包名 `com.autolua.autolua2` |
| `vscode-plugin/` | VSCode 开发插件（autolua-vscode）：增量同步、运行停止、日志回传、打包流水线入口 |
| `plugins/` | 插件示例（demo-ocr / ncnn-ocr，插件契约参考实现） |
| `tools/` | 构建/自测/联调脚本（build_app.ps1、selftest、remote.html 浏览器控制台） |

- **双执行模式**：root 引擎（app_process，uid 0，能力全量）/ IDE 沙箱（app 进程内联调）
- **IDE WebSocket 服务**（8080..8085）：沙箱执行、主引擎运行、print 回传、图色远程计算、H264/JPEG 投屏
- **云控通道引擎内置**：`cloud` 纯通道（CloudWebSocketServer 9300 端口 + PSK 帧加密），业务协议由上层脚本实现
- 构建：`powershell -File autolua2\tools\build_app.ps1`（环境要求见 `docs/05-构建与自测指南.md`）

### engine/ — 云控壳工程（jy-frame Lua 工作区）

**jy-frame 云控壳工程**：贴牌 App 内置的 Lua 业务框架，设备端启动后在 9300 端口开 WS 服务，接收 PC 云控指令驱动 JY 自动化框架执行脚本。

| 路径 | 内容 |
|---|---|
| `main.lua` + `main.config` | 工作区唯一入口 + 运行配置（设备端工程根 = main.lua 所在目录） |
| `frame/` | 全部框架文件：`jyframe.lua`（装载引导）、`jy_protocol.lua`（云控协议参考实现）、`JY/`（框架核心）、`frameSdk-autolua2/`（引擎适配层）、`Funs.lua` |
| `script/` | PC 端 Python 工具：`pack.py`（贴牌打包）、`run_test.py`（部署+联调测试）、`ws_client.py`（云控 WS 客户端）等 |
| `JUI.lua`、`Funs.lua` | 框架根级文件（JUI 描述器 UI 复刻版） |
| `config/brand.json` | 打包配置（cloud_url + secret，与设备端同一把密钥） |
| `docs/AGENT_GUIDE.md` | **Agent 开发与测试指南**（生成业务代码 + 端到端验证的完整契约，Agent 必读） |

- 打包流水线：VSCode 插件一键打包（组壳 zip → 写 brand.json → pack.py --brand → gradle 贴牌构建 → dist/*.apk）
- 运行模型：PC `ws_client.py` 发 `{op:...}` 指令（AES-256-CBC PSK 帧加密）→ 设备 `jy_protocol.lua` dispatch 分发 → JY 框架执行 → 回推日志/结果帧
- 数据契约：conf/taps/vars 全部由 WS 下发，main.lua 原样透传，设备端无预置业务数据

### autoScript/ — AI 全自动脚本生成套装

**基于 Trae AI IDE 的脚本生成工具套装**（Python 3.11+，不是独立程序）：连接模拟器/手机 → 自动截图 → AI 分析界面 → 自主操作 → 自动验证 → 编译生成 JY-Frame 格式脚本。Trae 的 AI 助手按 `trae/rules.md` 规则自主调用 `scripts/` 下的 CLI 脚本完成全流程。

- `scripts/`：核心脚本（每文件自包含）：adb / analyze / feature / coordinate / compile / verify
- 输出脚本兼容 JY-Frame 格式（懒人精灵 Lua / 按键 VB / AutoJS / AutoGo）
- 使用前自检：`.venv\Scripts\python.exe scripts/adb.py doctor`（必须用项目 venv，系统 python 缺 OCR 引擎）
- 详见 `autoScript/README.md` 与其 `docs/01~07` 设计文档

### docs/ — 引擎文档中心

**autolua2 引擎平台文档**（文档是设计真相源，代码是事实真相源；先改文档、后改代码），三层路由：

| 层 | 内容 |
|---|---|
| 根目录平台层 | 01-架构总览（先读）、02-Lua API 规范（脚本 API 权威）、03-WS 协议规范（IDE 通道）、04-插件包规范、05-构建与自测指南、06-开发铁律与踩坑手册（改引擎必读）、07-问题记录与遗留清单 |
| `features/<域>/` | 10 个功能域文档包（engine-core / ipc / bridge / luaapi / ui / plugins / ide-remote / app / cloud / runtime），每域 README + contracts（域内契约权威）+ design |
| `plans/` | 进行中任务书（引擎二期收口 / 插件体系演进 / 虚拟屏功能） |

- 一致性核查：`python docs\check_docs_consistency.py`（从仓库根运行，全部 PASS 才一致）
- **Agent 阅读路径**：改引擎读 01+06；写脚本读 02；云控/端到端读 `engine/docs/AGENT_GUIDE.md` + `features/cloud/`；构建联调读 05

## 贡献

欢迎 Issue 与 Pull Request。首次贡献前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)，并按 [CLA-GUIDE.md](CLA-GUIDE.md) 指引完成 [CLA.md](CLA.md) 签署（已签署名单见 [CLA-SIGNED.md](CLA-SIGNED.md)）；PR 提交时在模板中勾选 CLA 声明即可。

## 更新日志

### v0.1.0（2026-09-24）

- 首个 JY-Engine 发行版：更名 JY-Engine，替换全新 logo 与品牌文案
- 引擎内置云控通道（CloudWebSocketServer + PSK 帧加密 + 容器级生命周期回收）
- 动态 UI 体系：多窗口/多标签页、设置面板 `ui.settings`、配置快速存取（fastSave/fastLoad）
- 稳定性修复：stop/run 循环死锁、跨进程传输通道冻结、派发器僵尸代际、持锁饥饿等系列根治
- VSCode 插件 v0.5.19：运行状态二态按钮、函数文档中文分组标签、防连点互踩

## 致谢与许可证

本项目基于 [AutoLua2](https://github.com/lizhizhuanshu/AutoLua2) 二次开发，感谢原作者 [lizhizhuanshu](https://github.com/lizhizhuanshu) 的开源贡献。

- **本仓库**（jy-frame-engine）以 [GNU AGPL-3.0](LICENSE) 授权分发（**双许可模式**，同 MySQL/Qt）：个人学习、研究及遵守 AGPL 的使用（含网络服务，须履行相应开源义务）**免费**；**闭源商用或任何不受 AGPL 约束的商业使用，须向项目方购买商业授权**；
- **上游基座 autolua2/** 沿用原项目 [MIT License](autolua2/LICENSE)（Copyright (c) 2021 lizhizhuanshu），其依原许可证已获授权的范围不受影响；
- 依据 [CLA.md](CLA.md) 提交的贡献按"AGPL + 商业授权"双许可模式授权，贡献者保留版权（签署指引见 [CLA-GUIDE.md](CLA-GUIDE.md)）。
