# autolua2（JY-Frame）文档库

> **定位**：基于 Lua 的 Android 自动化开发框架（root 引擎 + IDE 沙箱双执行模式，配套 VSCode 插件远程开发调试）。
> 本文档是全库入口，**三层路由**：
> - **根目录（平台层）**：架构总览 / 平台级契约（Lua API·WS 协议·插件包）/ 规范手册 / 问题记录（见 §1）
> - **`features/<域>/`（功能域文档体系）**：10 个功能域，每域 README 导航 + contracts 契约 + design 设计（部分域含 client / manual，见 §2）
> - **`plans/`（进行中任务书）**：引擎二期收口 / 插件体系演进（见 §3）
>
> 契约分权：脚本 API 权威 = 02；WS 协议权威 = 03；插件包格式权威 = 04；域内实现契约权威 = features/<域>/contracts.md（详见 §5A.1）。
> 修订日期：2026-09-18（文档三层化重组：原 autolua2\docs 11 份文档迁移改造 + 8 域文档包新建 + 核查脚本上线，见 §7）。

---

## 0. 必读顺序（给所有 Agent）

1. **[01-架构总览](01-架构总览.md)** — 双进程模型 / 模块地图 / 服务注入矩阵 / 核心数据流。**先读**。
2. **[06-开发铁律与踩坑手册](06-开发铁律与踩坑手册.md)** — 改引擎前**必读**的框架级陷阱。
3. 按职责选读：写脚本读 [02](02-Lua-API规范.md)；做客户端/调试通道读 [03](03-WS协议规范.md) + [features/ide-remote](features/ide-remote/README.md)；写插件读 [04](04-插件包规范.md) + [features/plugins](features/plugins/README.md)；编译跑测读 [05](05-构建与自测指南.md)；查遗留读 [07](07-问题记录与遗留清单.md)。

---

## 1. 根目录（平台层）— 现存文件清单

> 平台层 = 总览 / 平台级契约 / 规范手册 / 问题记录。各域实现契约已下放 features/<域>/contracts.md（根契约文档头部注明分权与索引）。

| 文档 | 内容 |
|---|---|
| [01-架构总览](01-架构总览.md) | 进程模型（§4）/ 模块地图（§5-§7）/ Lua 环境与服务注入矩阵（§8）/ 核心数据流（§9）/ 关键机制索引（§10） |
| [02-Lua-API规范](02-Lua-API规范.md) | 脚本可调用的全部 Lua API 权威手册（20 章 + 命名对照附录；§17.1 动态 UI op 协议；§20 虚拟屏规范先行） |
| [03-WS协议规范](03-WS协议规范.md) | IDE WebSocket 协议（33 命令 / 17 消息 / 二进制流；常量源 WsProtocol.java） |
| [04-插件包规范](04-插件包规范.md) | 插件包格式 / manifest 字段 / sha256 清单 / ABI / so 交付 / 接口完整签名 / Lua 调用口 |
| [05-构建与自测指南](05-构建与自测指南.md) | 环境版本钉死 / 构建命令 / 模拟器联调五步 / 全量自测 PASS 基线 / 提交规范 |
| [06-开发铁律与踩坑手册](06-开发铁律与踩坑手册.md) | 框架级陷阱（Lua-Java 桥接 6 坑 / C++ 约束 8 条 / 运行时排障）/ 已知限制取舍 |
| [07-问题记录与遗留清单](07-问题记录与遗留清单.md) | 待办遗留滚动账本（P0-P2 + 对码 + plans 映射）/ 已完成勾销记录 |
| [check_docs_consistency.py](check_docs_consistency.py) | docs 一致性核查脚本（WS/IPC/链接/骨架/对码抽查，见 §5A.2） |

---

## 2. features/<域>/（功能域文档体系）

> 8 个功能域；每域 README 为导航（状态 + 真相源交叉引用 + 代码入口），contracts 为本域实现契约权威，design 为架构/机制/数据流（对码新写），client / manual 按需。

| 域 | 一句话 | 入口文档 |
|---|---|---|
| **engine-core** 引擎核心 | 生命周期 / 执行调度 / worker 状态机 / 截图管线；存档 INTEGRATION_PLAN 全案 | [README](features/engine-core/README.md) · [contracts](features/engine-core/contracts.md) · [design](features/engine-core/design.md) · [存档](features/engine-core/INTEGRATION_PLAN.md) |
| **ipc** 跨进程与权限通道 | root RPC 帧协议 / UI 事件轮询 / 注入降级链 / 共享内存 / AIDL | [README](features/ipc/README.md) · [contracts](features/ipc/contracts.md) · [design](features/ipc/design.md) |
| **bridge** Lua-Java 桥接 | LuaEnvContributor / 反射桥 / 类型包装 / thread 多线程；存档 BASIC_BRIDGE_PLAN | [README](features/bridge/README.md) · [contracts](features/bridge/contracts.md) · [design](features/bridge/design.md) · [存档](features/bridge/BASIC_BRIDGE_PLAN.md) |
| **luaapi** Lua API 服务集 | extension 各 *Lib 全局表注册点 / compat / attach；API_GAP 两账本存档 | [README](features/luaapi/README.md) · [contracts](features/luaapi/contracts.md) · [design](features/luaapi/design.md) · [API_GAP_LR](features/luaapi/API_GAP_LR.md) · [API_GAP_FIREFLY](features/luaapi/API_GAP_FIREFLY.md) |
| **ui** 动态 UI 与宿主 UI | JSON 控件树 + op 协议（双进程）/ 事件管线 / 控件工厂 / 悬浮控制条 | [README](features/ui/README.md) · [contracts](features/ui/contracts.md) · [design](features/ui/design.md) · [client](features/ui/client.md) |
| **plugins** 插件体系 | 加载/安装/原子交换 / OCR 插件通道 / ncnn-ocr 案例；开发手册 | [README](features/plugins/README.md) · [contracts](features/plugins/contracts.md) · [design](features/plugins/design.md) · [manual](features/plugins/manual.md) |
| **ide-remote** IDE 远程链路 | WS 服务端 / Handler 注册表 / 沙箱执行器 / vscode-plugin 对齐 / 投屏 | [README](features/ide-remote/README.md) · [contracts](features/ide-remote/contracts.md) · [design](features/ide-remote/design.md) |
| **app** 宿主客户端 | 引擎装配 / 本地服务注册 / 保活体系 / 工程管理 / UDP 发现 | [README](features/app/README.md) · [contracts](features/app/contracts.md) · [design](features/app/design.md) |
| **cloud** 云控通道 | 引擎内置 `cloud` 纯通道（Lua 绑定/Java 面）/ PSK 帧加密 / jy 协议参考实现归属 | [README](features/cloud/README.md) · [contracts](features/cloud/contracts.md) · [design](features/cloud/design.md) · [client](features/cloud/client.md) · [jy 协议](features/cloud/protocol-jyframe.md) |
| **runtime** 脚本容器生命周期 | 容器模型（引擎常驻/脚本=可销毁容器）/ 确定性资源回收契约 / longjmp 铁律 | [README](features/runtime/README.md) · [contracts](features/runtime/contracts.md) · [design](features/runtime/design.md) |

---

## 3. plans/（进行中任务书）

| 文档 | 状态 |
|---|---|
| [引擎二期收口任务书](plans/引擎二期收口任务书.md) | execute 挂死 / debug_attach / keepalive 声明 / remote.html 验收 / root daemon / NEON·瘦身 / 沙箱注入（均未开始） |
| [插件体系演进任务书](plans/插件体系演进任务书.md) | 真机联调 / 签名体系 / 插件市场 API（均未开始） |
| [虚拟屏功能实施任务书](plans/虚拟屏功能实施任务书.md) | 无 root 图色桥接 SharedMemoryDisplay / 挂死·jpeg·旋转修复 / Shizuku 多开后端 / virtualDisplay API / root 链路 15+ 维护（均未开始） |

> 已完成方案不进 plans：细节存档在所属域（[engine-core/INTEGRATION_PLAN.md](features/engine-core/INTEGRATION_PLAN.md)、[bridge/BASIC_BRIDGE_PLAN.md](features/bridge/BASIC_BRIDGE_PLAN.md)）；遗留项账本在 [07 §1](07-问题记录与遗留清单.md)。

---

## 5A. 文档修订约定与一致性核查（防止 docs 漂移）

> 核心原则：**文档是设计真相源，代码是事实真相源，两者必须一致；先改文档、后改代码、定期核查**。

### 5A.1 单一真相源分层（分权模型）

| 层 | 权威 | 约束 |
|---|---|---|
| 脚本 Lua API 面 | `docs/02` | 新增/变更 API 先改 02，再实现，后勾销 API_GAP 账本批次记录 |
| 全局表→实现注册点 | features/luaapi/contracts.md | 新增 *Lib 全局表必须回填映射总表（对码注册点行号） |
| IDE WS 协议 | `docs/03` | 命令/消息必须与 `WsProtocol.java` CMD_*/MSG_* 一一对应（脚本 R3 自动核对）；新命令双端同步 vscode protocol.ts |
| IDE 域内契约（Handler 注册/沙箱/分片） | features/ide-remote/contracts.md | Handler 增删同步 §2 注册表 |
| 插件包格式与接口 | `docs/04` | manifest 字段变更同步 PluginMeta.java（脚本 R4 核对） |
| 插件域内契约（原子交换/贡献/OCR 通道） | features/plugins/contracts.md | — |
| 跨进程帧协议 / 服务注册协议 / 注入链 | features/ipc/contracts.md | 新增本地服务必须回填（脚本 R7 对账 addLocalService 名单） |
| 宿主装配与保活契约 | features/app/contracts.md | — |
| 动态 UI op 协议 / 事件 JSON | features/ui/contracts.md（脚本面 = 02 §17.1） | 控件 type 增删双处同步 |
| 桥接机制（类型包装/贡献接口） | features/bridge/contracts.md | — |
| 引擎执行契约（Builder/状态机/ABI） | features/engine-core/contracts.md | — |
| 遗留项/问题 | `docs/07` | 各域 design「演进」节只引用不复制 |

> **铁律**：任何新增 API/命令/服务/控件类型，必须**先回填对应权威文档，再实现**；句末对码基准（`文件:行号`）随行维护。

### 5A.2 一致性自动核查

```
python docs\check_docs_consistency.py
```

从仓库根（jy-frame-engine\）运行，全部 PASS 才认为 docs 与代码一致。检查覆盖：根文档清单存在性、全库 markdown 链接（含旧文件名残留）、**03 §2/§3 与 WsProtocol.java CMD_*/MSG_* 全量 diff**、04 manifest 字段 ↔ PluginMeta.java 对账、8 域 README 骨架完整性、**本地服务注册名单全量对账**（AutoLuaEngineOnLocal/MainApp 的 addLocalService/registerService ↔ ipc/app/ui/luaapi 域文档）、对码基准文件级抽查、07→plans 引用存在性。

**执行时机**：改动涉及 Lua API / WS 协议 / IPC 服务 / manifest / 文档结构后，提交前运行一次。

### 5A.3 修订铁律

1. **先改文档、后改代码**：新增 API/命令/服务/控件先落对应权威文档，再实现
2. **删除/改名必须同步**：保留"已废弃/改为 →"对照说明（03/04 头部版本行记录口径修正）
3. **对码纪律**：域 contracts/design 每条契约句末标注（`autolua2\...:行号`），行号以 Grep -n 实测为准，禁止臆造
4. **路径引用**：一律写真实路径 `autolua2\...`，禁止 `src/*` 之类占位

---

## 7. 已删除文档登记

> 原则：**已实施完毕的历史方案、已被取代的过时文档一律删除**（设计事实已回填/沉淀于上列现行文档；需要时从 git 历史找回）。

**三层化重组（2026-09-18）**：

| 处置 | 说明 |
|---|---|
| autolua2\docs\ 整目录删除（11 份文档迁出） | ARCHITECTURE→[01](01-架构总览.md)、LUA_API→[02](02-Lua-API规范.md)、WS_PROTOCOL→[03](03-WS协议规范.md)、PLUGIN_SPEC→[04](04-插件包规范.md)、BUILD→[05](05-构建与自测指南.md)、DEV_NOTES→[06](06-开发铁律与踩坑手册.md)（§5 未完成事项抽至 [07](07-问题记录与遗留清单.md)）；INTEGRATION_PLAN→[features/engine-core/](features/engine-core/INTEGRATION_PLAN.md)、BASIC_BRIDGE_PLAN→[features/bridge/](features/bridge/BASIC_BRIDGE_PLAN.md)、API_GAP_LR/FIREFLY→[features/luaapi/](features/luaapi/README.md)；原 docs\README.md 索引职能由本文件承接 |
| 03-WS协议规范 口径修正 | 34 命令/18 消息 → **33/17**（对码 WsProtocol.java；原 §3 漏 stream_sync_result 已补） |
| 04-插件包规范 §13 瘦身 | 未完成项移 [07 §1](07-问题记录与遗留清单.md)；"真实 PP-OCR 插件"勾销（ncnn-ocr 已落地 2026-09-15） |
| 新建 8 域文档包 25 份 | 8×README + 8×contracts + 8×design + ui/client + plugins/manual（全部对码新写） |
| 新建 plans/ 2 份任务书 | [引擎二期收口](plans/引擎二期收口任务书.md) / [插件体系演进](plans/插件体系演进任务书.md) |
| 新建核查脚本 | [check_docs_consistency.py](check_docs_consistency.py)（R1-R9 规则） |

> 编号说明：01-07 为平台层序号（编号间隙预留后续平台级规范）；域内存档沿用原文件名；新增文档从 08 顺延。
