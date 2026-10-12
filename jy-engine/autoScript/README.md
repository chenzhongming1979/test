# AutoScript — JY-Frame 全自动脚本生成套装

> 基于 Trae AI IDE 的脚本生成工具套装：连接模拟器/手机 → 自动截图 → AI 分析界面 → 自主操作 → 自动验证 → 编译生成 JY-Frame 格式脚本。

---

## 项目定位

AutoScript 是一套** Trae 可用的脚本生成套装**，不是独立运行的程序。它由一组 Python CLI 脚本 + Trae 规则/角色定义组成，Trae 的 AI 助手按规则自主调用脚本，完成从截图到脚本生成的全流程。

**设计原则**：
- **纯脚本调用**：不用 MCP，不用独立 Agent 框架，AI 直接调 `.venv\Scripts\python.exe scripts/xxx.py`
- **文件自包含**：每个脚本文件 = 核心函数 + CLI 入口，既能 import 也能命令行执行
- **迁移冗余**：核心逻辑与 CLI 解耦，`trae/` 目录以后可整体替换为 `aistruct/`，核心脚本零改动

---

## 目录结构

```
autoScript/
├── scripts/                  # 所有 Python 代码，每个文件自包含
│   ├── adb.py                # 设备连接/截图/点击/滑动/输入
│   ├── analyze.py            # 界面分析（视觉LLM + OCR）
│   ├── feature.py            # 稳定色点采样/感知哈希/画面对比
│   ├── coordinate.py         # 锚点坐标换算
│   ├── compile.py            # 状态机→JY-Frame taps 编译
│   └── models.py             # 通用数据结构
├── trae/                     # Trae 配置（迁移时整体替换为 aistruct/）
│   ├── rules.md              # 调用规则：什么场景调什么脚本
│   └── soul.md               # 角色定义：脚本生成专家
├── templates/                # 脚本输出模板（jinja2）
│   ├── lua_template.j2
│   └── vb_template.j2
├── knowledge/                # 知识库索引
│   └── jy_frame_index.json   # 指向 JY-Frame doc-flat 文档
├── config/                   # 配置文件
│   ├── devices.yaml          # 设备配置
│   └── models.yaml           # LLM 配置
├── output/                   # 生成的脚本（运行时产生）
├── screenshots/              # 运行时截图（调试用）
├── docs/                     # 设计文档
└── README.md
```

---

## 核心工作流

Trae AI 助手按 `trae/rules.md` 的规则，自主调度脚本完成以下循环：

```
用户目标（"自动刷火副本"）
    │
    ▼
┌─ 循环 ─────────────────────────────────────┐
│  1. scripts/adb.py screenshot              │
│     → 截图                                  │
│  2. scripts/analyze.py --image shot.png    │
│     → AI 识别界面元素/文字/标签              │
│  3. scripts/feature.py sample              │
│     → 对目标元素采样稳定色点                 │
│  4. AI 规划下一步操作（点击/滑动/等待）       │
│  5. scripts/adb.py click/swipe             │
│     → 执行操作                              │
│  6. scripts/feature.py compare             │
│     → 验证操作结果（画面变化+语义判断）       │
│  7. 记录状态转移 → 继续循环或结束            │
└────────────────────────────────────────────┘
    │
    ▼
scripts/compile.py --run-id xxx
    → 去探索化 + 映射规则 → 输出 JY-Frame 脚本
```

---

## 技术栈

- **语言**：Python 3.11+
- **设备操作**：ADB（subprocess 调用）
- **图像处理**：Pillow + numpy + opencv-python
- **OCR**：RapidOCR（离线兜底）
- **视觉理解**：复用 Trae 已配置的 LLM（通过 analyze.py 调用）
- **脚本生成**：jinja2 模板
- **状态存储**：JSON 文件（轻量，Trae 套装阶段不引入数据库）
- **AI 宿主**：Trae AI IDE

---

## 迁移说明

本套装预留了向 AIStruct 迁移的能力：

| 层 | 当前（Trae） | 迁移后（AIStruct） | 是否改动 |
|----|-------------|-------------------|:--------:|
| 核心逻辑 | `scripts/*.py` 核心函数 | 同左 | ❌ 零改动 |
| CLI 入口 | `scripts/*.py` 的 `__main__` 块 | AIStruct `coding_cli_exec` 节点直接调命令行 | ❌ 零改动 |
| AI 调度 | `trae/rules.md` + `trae/soul.md` | `aistruct/` Skill 定义 + 流程 DAG | ✅ 替换目录 |
| 模板/配置/知识库 | `templates/` `config/` `knowledge/` | 同左 | ❌ 零改动 |

迁移时只需：把 `trae/` 替换为 `aistruct/`（Skill 定义 + 流程编排），其余全部复用。

---

## 快速开始

### 环境检查

```bash
.venv\Scripts\python.exe scripts/adb.py doctor
```

检查 Python 依赖、ADB 可用性、设备连接、LLM API 配置。（一律用项目 venv 解释器，含 OCR 引擎）

### 使用

1. 在 Trae 中打开本项目（项目规则 `.trae/rules/project_rules.md` 自动注入）
2. 设备上打开目标游戏到起始界面
3. 输入目标："帮我写一个自动刷火副本的脚本，从主界面开始"
4. AI 按 trae/rules.md 自主调度：截图→分析→采样→点击→验证→记录状态机→循环
5. 生成的脚本在 `output/` 目录，导入懒人精灵运行

---

## 文档导航

| 文档 | 说明 |
|------|------|
| [01-需求规格说明书](docs/01-需求规格说明书.md) | 功能需求、非功能需求、用例 |
| [02-系统架构设计](docs/02-系统架构设计.md) | 整体架构、技术选型、ADR 决策 |
| [03-模块详细设计](docs/03-模块详细设计.md) | 每个脚本的函数/CLI/算法设计 |
| [04-数据结构与接口](docs/04-数据结构与接口.md) | JSON 数据结构、CLI 接口规范 |
| [05-开发计划与里程碑](docs/05-开发计划与里程碑.md) | 分阶段计划、任务拆解、验收标准 |
| [06-Trae集成规范](docs/06-Trae集成规范.md) | Trae 规则/角色定义、调用约定、环境检查 |
| [07-按键精灵taps脚本经验总结](docs/07-按键精灵taps脚本经验总结.md) | 真实脚本库分析：模块拆分/任务定义/流程控制经验与映射规则 |

---

## 目标框架

输出脚本兼容 [JY-Frame](D:\Project\script\lrjl\jy-frame-lrjl) 图色脚本框架，支持：
- 懒人精灵（Lua）
- 按键精灵（VB）
- AutoJS（JavaScript）
- AutoGo（Go）

---

## 项目状态

当前阶段：**核心脚本已完成（M1 + M2），待端到端测试**

- [x] P0 环境准备：依赖就绪（含项目 venv `.venv`）、锚点公式确认并实现
- [x] P1 核心脚本：models / adb / coordinate / feature / analyze / compile 全部可用
- [x] P2 Trae 集成：trae/rules.md + soul.md + .trae/rules/project_rules.md + knowledge/jy_frame_index.json
- [ ] P3 端到端测试：真机链路预验证已通过（截图/分析/落盘/编译闭环，2026-09-13）；
       交互式端到端需在 Trae 对话中下达目标运行（配 DOUBAO_API_KEY 可启用视觉分析，当前走 OCR 降级）

使用前先运行 `.venv\Scripts\python.exe scripts/adb.py doctor` 做环境自检。
