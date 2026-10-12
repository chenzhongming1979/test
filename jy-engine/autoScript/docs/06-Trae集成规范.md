# Trae 集成规范

> 版本：v1.0  
> 日期：2026-09-11  
> 状态：初稿

---

## 1. 概述

本文档定义 AutoScript 作为 Trae 套装的集成方式，包括规则文件、角色定义、脚本调用约定、环境检查清单。

Trae AI 助手通过读取 `trae/rules.md` 和 `trae/soul.md`，自主调度 `scripts/` 下的 Python 脚本完成脚本生成全流程。

---

## 2. Trae 套装结构

```
autoScript/
├── scripts/          # 可执行脚本（AI 直接调用）
├── trae/
│   ├── rules.md      # 调用规则（AI 必读）
│   └── soul.md       # 角色定义（AI 系统提示词）
├── templates/        # 输出模板
├── knowledge/        # 知识库索引
├── config/           # 配置
└── state/            # 运行时状态（JSON）
```

---

## 3. trae/soul.md — 角色定义

**作用**：定义 AI 助手的角色、能力边界、工作原则。Trae 加载后作为系统提示词的一部分。

**内容结构**：

```markdown
# 角色：JY-Frame 脚本生成专家

## 身份
你是一个精通 JY-Frame 图色脚本框架的自动化脚本生成专家。

## 能力
- 连接安卓模拟器/真机，自动截图和操作
- 分析界面，识别可点击元素和稳定色点
- 自主规划任务流程，拆解为 JY-Frame 任务模块
- 编译生成可直接运行的 JY-Frame 脚本

## 工作原则
1. 每一步操作前先截图分析，不盲目点击
2. 点击后必须验证结果（画面变化 + 语义判断）
3. 失败时重试（换点），连续失败则回退
4. 生成的特征必须经过多帧验证
5. 输出脚本严格遵循 JY-Frame 格式规范

## 可用工具
通过命令行调用 scripts/ 下的脚本：
- python scripts/adb.py ...
- python scripts/analyze.py ...
- python scripts/feature.py ...
- python scripts/compile.py ...

## 输出
最终在 output/ 目录生成 JY-Frame 脚本文件。
```

---

## 4. trae/rules.md — 调用规则

**作用**：定义 AI 在什么场景下调用什么脚本、调用顺序、参数规范。这是 AI 调度的核心依据。

**内容结构**：

### 4.1 触发条件

当用户输入包含以下意图时，启动脚本生成流程：
- "写脚本"、"生成脚本"、"自动刷"、"自动化"
- 指定了游戏/APP 和具体任务（如"刷副本"、"做日常"）

### 4.2 执行流程

**Step 0：环境检查**
```
python scripts/adb.py doctor
```
检查通过后继续，失败则报告问题。

**Step 1：截图分析**
```
python scripts/adb.py screenshot --device <device_id> --output screenshots/shot_001.png
python scripts/analyze.py --image screenshots/shot_001.png
```
获取界面元素列表和语义标签。

**Step 2：任务规划**
基于用户目标和当前界面，AI 自主规划：
- 当前在哪个界面
- 下一步应该点哪个元素
- 点击后期望到达什么界面

**Step 3：特征采样**
```
python scripts/feature.py sample --image screenshots/shot_001.png --bbox <x1,y1,x2,y2> --count 3
```
对目标元素采样稳定色点。

**Step 4：执行操作**
```
python scripts/adb.py click --device <device_id> --x <x> --y <y>
```
或滑动、输入、等待。

**Step 5：验证结果**
```
python scripts/adb.py screenshot --device <device_id> --output screenshots/shot_002.png
python scripts/feature.py compare --before screenshots/shot_001.png --after screenshots/shot_002.png
```
AI 结合画面变化和语义判断是否成功。

**Step 6：记录状态**
将状态转移写入 `state/run_<id>.json`。

**Step 7：循环**
重复 Step 1-6，直到任务完成。

**Step 8：编译输出**
```
python scripts/compile.py --run-id <id> --platform lua --output output/script.lua
```

### 4.3 失败处理规则

| 情况 | 处理 |
|------|------|
| 画面没变化 | 等待 1s，换元素内另一个点重试，最多 2 次 |
| 进入非预期界面 | 记录异常，发送 back，重新规划 |
| 连续失败 3 次 | 标记该子任务失败，跳过或暂停，报告用户 |
| 检测到状态循环（A→B→A） | 跳出循环，报告异常 |

### 4.4 坐标规范

- 所有坐标使用真实像素（截图的实际分辨率）
- 编译时由 `coordinate.py` 统一换算为 JY-Frame 设计坐标
- AI 不自行换算坐标

---

## 5. 脚本调用约定

### 5.1 通用规范

所有脚本必须遵守：
- 命令行参数用 argparse，支持 `--help`
- 成功时输出 JSON 到 stdout，以 `{"status": "ok", ...}` 开头
- 失败时输出 JSON `{"status": "error", "error": "..."}`，退出码非 0
- 不输出无关日志到 stdout（日志写 stderr 或文件）
- 所有路径支持相对路径和绝对路径

### 5.2 通用输出格式

```json
{
  "status": "ok",
  "data": { ... },
  "meta": {
    "script": "adb.py",
    "command": "screenshot",
    "timestamp": 1234567890.123,
    "duration_ms": 123
  }
}
```

### 5.3 错误输出格式

```json
{
  "status": "error",
  "error": "ADB_DEVICE_NOT_FOUND",
  "message": "设备 emulator-5554 未连接",
  "meta": { ... }
}
```

### 5.4 各脚本 CLI 概览

```bash
# adb.py
python scripts/adb.py doctor
python scripts/adb.py devices
python scripts/adb.py screenshot --device <id> --output <path>
python scripts/adb.py click --device <id> --x <x> --y <y>
python scripts/adb.py swipe --device <id> --x1 <x1> --y1 <y1> --x2 <x2> --y2 <y2> --duration <ms>
python scripts/adb.py input --device <id> --text "<text>"
python scripts/adb.py keyevent --device <id> --key <back|home>

# analyze.py
python scripts/analyze.py --image <path> [--ocr-only] [--vision-model <model>]

# feature.py
python scripts/feature.py sample --image <path> --bbox <x1,y1,x2,y2> [--count 3]
python scripts/feature.py phash --image <path>
python scripts/feature.py compare --before <path1> --after <path2>

# coordinate.py
python scripts/coordinate.py convert --x <x> --y <y> --from real --to design --resolution <w,h> --anchor <1-9>
python scripts/coordinate.py detect-anchor --x <center_x> --y <center_y> --resolution <w,h>
python scripts/coordinate.py detect --device <id>

# compile.py
python scripts/compile.py --run-id <id> --platform <lua|vb|js|go> --output <path>
python scripts/compile.py list-runs
```

---

## 6. 环境检查清单

执行 `python scripts/adb.py doctor` 检查以下项：

| 检查项 | 命令/方法 | 通过标准 |
|--------|----------|---------|
| Python 版本 | `sys.version` | >= 3.11 |
| 依赖包 | import 检查 | Pillow, numpy, opencv-python, rapidocr, jinja2, yaml 全部可导入 |
| ADB 可执行 | `adb version` | 能输出版本号 |
| ADB 设备 | `adb devices` | 至少一个设备状态为 device |
| 设备分辨率 | `adb shell wm size` | 能获取分辨率 |
| LLM API | 读 config/models.yaml | api_key 已配置，base_url 可访问 |
| 配置文件 | 检查 config/ | devices.yaml 和 models.yaml 存在且格式正确 |
| 目录权限 | 检查 output/ screenshots/ state/ | 可写 |
| JY-Frame 文档 | 检查 knowledge/ | jy_frame_index.json 指向有效路径 |

**doctor 输出示例**：
```json
{
  "status": "ok",
  "checks": [
    {"name": "python_version", "status": "ok", "detail": "3.11.5"},
    {"name": "dependencies", "status": "ok", "detail": "all 7 packages installed"},
    {"name": "adb", "status": "ok", "detail": "1.0.41"},
    {"name": "devices", "status": "ok", "detail": "1 device: emulator-5554"},
    {"name": "llm_api", "status": "ok", "detail": "configured"},
    {"name": "jy_frame_docs", "status": "warning", "detail": "doc-flat path not found, using index only"}
  ],
  "summary": {"passed": 6, "failed": 0, "warnings": 1}
}
```

---

## 7. 状态文件格式

运行时状态机存在 `state/run_<timestamp>.json`：

```json
{
  "run_id": "run_20260911_143000",
  "goal": "自动刷火副本，刷完回城",
  "device_id": "emulator-5554",
  "resolution": [720, 1280],
  "states": [
    {
      "state_id": 0,
      "scene_label": "主界面",
      "screenshot": "screenshots/shot_001.png",
      "phash": "a1b2c3d4e5f60718",
      "elements": [...],
      "features": [...]
    }
  ],
  "transitions": [
    {
      "from_state": 0,
      "to_state": 1,
      "action": {"type": "click", "x": 324, "y": 1207, "target": "副本按钮"},
      "success": true,
      "timestamp": 1234567890.123
    }
  ],
  "task_tree": [...],
  "anomalies": [...]
}
```

---

## 8. 知识库集成

`knowledge/jy_frame_index.json` 索引 JY-Frame 框架文档：

```json
{
  "framework": "JY-Frame",
  "version": "1.0",
  "doc_path": "D:\\Project\\script\\lrjl\\jy-frame-lrjl\\doc-flat",
  "documents": [
    {"id": "readme", "file": "00-README.md", "title": "技术知识库"},
    {"id": "framework", "file": "01-框架简介.md", "title": "框架简介"},
    {"id": "flow", "file": "02-框架运行流程.md", "title": "运行流程"},
    {"id": "ps", "file": "07-ps指令.md", "title": "ps指令"},
    {"id": "yolo", "file": "31-yolo指令.md", "title": "yolo指令"}
  ],
  "key_concepts": {
    "taps": "任务模块表，脚本核心数据结构",
    "feature": "特征行，{名称, 优先级, ff=指令, 参数...}",
    "ff": "动作指令ID，ps/ft/fc/pic/fs/ocr/move/nd/yolo"
  }
}
```

AI 在生成脚本时可读取此索引，必要时查阅对应文档确保格式正确。

---

## 9. 迁移到 AIStruct 的注意事项

当从 Trae 迁移到 AIStruct 时：

1. **scripts/ 全部复用**：核心函数和 CLI 都不用改
2. **trae/ 替换为 aistruct/**：
   - `soul.md` → `SKILL.md`（AIStruct Skill 格式）
   - `rules.md` → 流程 DAG（用 AIStruct 流程编辑器搭建）
3. **AI 调度方式变化**：
   - Trae：rules.md 驱动 AI 自主调度
   - AIStruct：pipeline_executor 按 DAG 节点执行
4. **状态存储可升级**：JSON → AIStruct memory_manager
5. **配置可合并**：config/ → AIStruct 统一配置

**迁移检查清单**：
- [ ] scripts/ 所有脚本在 AIStruct 环境可执行
- [ ] SKILL.md 按 AIStruct 格式编写
- [ ] 流程 DAG 在编辑器中搭建完成
- [ ] 状态存储适配 memory_manager
- [ ] 端到端测试通过
