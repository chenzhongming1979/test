# -*- coding: utf-8 -*-
"""
check_docs_consistency.py —— autolua2/JY-Frame docs 一致性核查

从仓库根（jy-frame-engine/）运行：python docs/check_docs_consistency.py
全部 PASS 才认为 docs 与代码一致。检查覆盖：
  R1  根文档清单存在性（01-07 + README + plans）
  R2  docs 全库 markdown 相对链接目标存在（含旧文件名引用检测）
  R3  WS 协议计数与常量全量 diff（03 §2/§3 ↔ WsProtocol.java CMD_*/MSG_*）
  R4  插件 manifest 字段对账（04 §2 ↔ PluginMeta.java）
  R5  features 8 域 README 固定骨架完整性
  R6  features 8 域 contracts.md 存在性
  R7  IPC 本地服务注册对账（AutoLuaEngineOnLocal/MainApp addLocalService 名单 ↔ ipc/app/ui 域文档）
  R8  对码基准抽查（contracts/design 中 autolua2\\...:行号 的文件真实存在）
  R9  遗留清单引用的 plans 任务书存在
"""
import os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # jy-frame-engine
DOCS = os.path.join(ROOT, "docs")
results = []

def check(name, ok, detail=""):
    results.append((ok, name, detail))

def read(path):
    with open(path, "rb") as f:
        return f.read().decode("utf-8-sig")

def rel(*p):
    return os.path.join(ROOT, *p)

# ---------- R1 根文档清单 ----------
R1_FILES = [
    "docs/README.md", "docs/01-架构总览.md", "docs/02-Lua-API规范.md",
    "docs/03-WS协议规范.md", "docs/04-插件包规范.md", "docs/05-构建与自测指南.md",
    "docs/06-开发铁律与踩坑手册.md", "docs/07-问题记录与遗留清单.md",
    "docs/check_docs_consistency.py",
    "docs/plans/引擎二期收口任务书.md", "docs/plans/插件体系演进任务书.md",
]
missing = [f for f in R1_FILES if not os.path.exists(rel(*f.split("/")))]
check("R1 根文档清单完整（01-07/README/plans/核查脚本）", not missing,
      "缺失: " + ", ".join(missing) if missing else "11/11 存在")

# ---------- R2 全库链接存在性 ----------
DOMAINS = ["engine-core", "bridge", "luaapi", "ipc", "ui", "plugins", "ide-remote", "app"]
LINK_RE = re.compile(r"\]\(([^)#\s]+?\.md)(#[^)]*)?\)")
OLD_NAMES = {"ARCHITECTURE.md", "LUA_API.md", "WS_PROTOCOL.md", "PLUGIN_SPEC.md",
             "BUILD.md", "DEV_NOTES.md"}
link_bad, old_refs = [], []
md_files = []
for base, _dirs, files in os.walk(DOCS):
    for fn in files:
        if fn.endswith(".md"):
            md_files.append(os.path.join(base, fn))
for mf in md_files:
    text = read(mf)
    for m in LINK_RE.finditer(text):
        target = m.group(1)
        if target.startswith("http"):
            continue
        dst = os.path.normpath(os.path.join(os.path.dirname(mf), target))
        if not os.path.exists(dst):
            link_bad.append(f"{os.path.relpath(mf, DOCS)} -> {target}")
        bn = os.path.basename(target)
        if bn in OLD_NAMES:
            old_refs.append(f"{os.path.relpath(mf, DOCS)} -> {target}")
check(f"R2 docs 链接全部存在（{len(md_files)} 个 .md 扫描）", not link_bad,
      "; ".join(link_bad[:8]) if link_bad else "0 悬空")
check("R2b 已删除旧文件名（ARCHITECTURE.md 等 8 个）无残留引用", not old_refs,
      "; ".join(old_refs[:8]) if old_refs else "0 残留")

# ---------- R3 WS 协议计数与常量全量 diff ----------
ws_java = read(rel("autolua2", "engine", "src", "main", "java", "com", "autolua", "engine", "ide", "WsProtocol.java"))
cmd_names = set(re.findall(r'CMD_\w+ = "(\w+)"', ws_java))
msg_names = set(re.findall(r'MSG_\w+ = "(\w+)"', ws_java))
doc03 = read(rel("docs", "03-WS协议规范.md"))
m2 = re.search(r"## 2\. 客户端 → 设备命令（(\d+) 个", doc03)
m3 = re.search(r"## 3\. 设备 → 客户端消息（(\d+) 个", doc03)

def section(text, start_marker, end_marker):
    i = text.find(start_marker)
    j = text.find(end_marker, i + len(start_marker)) if i >= 0 else -1
    return text[i:j] if i >= 0 and j > i else ""

def first_col_names(sec):
    """提取表格第一列 type 名。
    支持：`a` / `b` 合并行；`x_start/chunk/complete` 速记行（chunk/complete 沿用本行前缀展开）。"""
    names = set()
    cell_re = re.compile(r"^\|\s*((?:`[^`]+`)(?:\s*/\s*(?:`[^`]+`))*)\s*\|")
    for line in sec.splitlines():
        m = cell_re.match(line)
        if not m:
            continue
        tokens = re.findall(r"`([^`]+)`", m.group(1))
        parts = [p.strip() for p in "/".join(tokens).split("/")]
        for idx, p in enumerate(parts):
            if p in ("chunk", "complete") and idx > 0:
                prev = parts[0]  # 速记行基准 = 行首 x_start 段
                base = prev[: -len("_start")] if prev.endswith("_start") else prev.rsplit("_", 1)[0]
                names.add(base + "_" + p)
            else:
                names.add(p)
    return names

sec2 = section(doc03, "## 2.", "## 3.")
sec3 = section(doc03, "## 3.", "## 4.")
cmd_in_doc = first_col_names(sec2)
msg_in_doc = first_col_names(sec3)
check("R3a 03 §2 标题计数 == WsProtocol CMD_* 数（%d）" % len(cmd_names),
      bool(m2) and int(m2.group(1)) == len(cmd_names),
      f"标题={m2.group(1) if m2 else '?'} 代码={len(cmd_names)}")
check("R3b 03 §3 标题计数 == WsProtocol MSG_* 数（%d）" % len(msg_names),
      bool(m3) and int(m3.group(1)) == len(msg_names),
      f"标题={m3.group(1) if m3 else '?'} 代码={len(msg_names)}")
only_code = cmd_names - cmd_in_doc
only_doc = cmd_in_doc - cmd_names
check("R3c §2 命令表全量 diff（CMD_*）", not only_code and not only_doc,
      f"代码多:{sorted(only_code)} 文档多:{sorted(only_doc)}" if (only_code or only_doc) else "33/33 一致")
only_code = msg_names - msg_in_doc
only_doc = msg_in_doc - msg_names
check("R3d §3 消息表全量 diff（MSG_*）", not only_code and not only_doc,
      f"代码多:{sorted(only_code)} 文档多:{sorted(only_doc)}" if (only_code or only_doc) else "17/17 一致")

# ---------- R4 插件 manifest 字段对账 ----------
meta_java = read(rel("autolua2", "engine", "src", "main", "java", "com", "autolua", "engine", "plugin", "PluginMeta.java"))
doc04 = read(rel("docs", "04-插件包规范.md"))
fields = ["mainClass", "sha256", "minHostVersion", "signature", "provides"]
miss = [f for f in fields if f not in meta_java or f not in doc04]
check("R4 manifest 关键字段 04 §2 ↔ PluginMeta.java 对账", not miss,
      "缺: " + ", ".join(miss) if miss else "5 字段两端齐备")

# ---------- R5/R6 features 域骨架与 contracts ----------
SKELETON = ["## 本域文档", "## 全局真相源交叉引用", "## 代码入口", "## 何时读本文档"]
for d in DOMAINS:
    rd = os.path.join(DOCS, "features", d, "README.md")
    ct = os.path.join(DOCS, "features", d, "contracts.md")
    if not os.path.exists(rd):
        check(f"R5 features/{d}/README.md 骨架", False, "文件缺失")
        continue
    t = read(rd)
    miss = [s for s in SKELETON if s not in t]
    check(f"R5 features/{d}/README.md 骨架完整", not miss,
          "缺小节: " + ", ".join(miss) if miss else "4 小节齐")
    check(f"R6 features/{d}/contracts.md 存在", os.path.exists(ct))

# ---------- R7 IPC 本地服务注册对账 ----------
svc_names = set()
for java in ["autolua2/engine/src/main/java/com/autolua/engine/core/AutoLuaEngineOnLocal.kt",
             "autolua2/app/src/main/java/com/autolua/autolua2/MainApp.kt"]:
    src = read(rel(*java.split("/")))
    svc_names |= set(re.findall(r'addLocalService\(\s*"([\w\-]+)"', src))
    svc_names |= set(re.findall(r'registerService\(\s*"([\w\-]+)"', src))
pool = ""
for d in ["ipc", "app", "ui", "luaapi"]:
    p = os.path.join(DOCS, "features", d, "contracts.md")
    if os.path.exists(p):
        pool += read(p)
    p = os.path.join(DOCS, "features", d, "client.md")
    if os.path.exists(p):
        pool += read(p)
undoc = sorted(s for s in svc_names if f'"{s}"' not in pool and f"`{s}`" not in pool and s not in pool)
check("R7 IPC 本地服务注册全量对账（%d 个服务名均见于域契约文档）" % len(svc_names),
      not undoc, "未登记: " + ", ".join(undoc) if undoc else f"{len(svc_names)}/{len(svc_names)} 已登记")

# ---------- R8 对码基准抽查（文件级：引用的代码文件真实存在） ----------
checked, bad = 0, []
for mf in md_files:
    text = read(mf)
    for m in re.finditer(r"autolua2\\([^\s`\)）\|，。;；]+?)\.(\w+):(\d+)", text):
        relp, _ext, ln = m.group(1), m.group(2), m.group(3)
        checked += 1
        base = rel("autolua2", *relp.split("\\"))
        if not (os.path.exists(base + "." + _ext) or os.path.exists(base)):
            bad.append(f"{os.path.relpath(mf, DOCS)} -> autolua2\\{relp}.{_ext}")
check(f"R8 对码基准文件路径抽查（{checked} 条标注，文件级）", not bad,
      "; ".join(sorted(set(bad))[:8]) if bad else "全部文件存在")

# ---------- R9 遗留清单 → plans ----------
doc07 = read(rel("docs", "07-问题记录与遗留清单.md"))
plans_refs = re.findall(r"plans/([\w\-]+\.md)", doc07)
miss = [p for p in set(plans_refs) if not os.path.exists(rel("docs", "plans", p))]
check("R9 07 遗留清单引用的 plans 任务书存在", not miss,
      "缺: " + ", ".join(miss) if miss else f"{len(set(plans_refs))} 份齐")

# ---------- 汇总 ----------
print("===== autolua2 docs 一致性核查 =====")
fails = 0
for ok, name, detail in results:
    tag = "PASS" if ok else "FAIL"
    if not ok:
        fails += 1
    print(f"[{tag}] {name}" + (f"（{detail}）" if detail and not ok else ""))
total = sum(1 for ok, _, _ in results)
print(f"\nTotal: {total - fails} passed, {fails} failed")
sys.exit(1 if fails else 0)
