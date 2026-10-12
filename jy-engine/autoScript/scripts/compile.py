# scripts/compile.py
"""compile.py — 脚本编译

将运行时状态机 JSON（state/<run_id>.json）编译为 JY-Frame taps 表脚本。
流程：load_state_machine → de_explore → build_modules → build_task_list
     → render_template → 写文件（compile_run）。
落地 docs/07 §6 映射规则 M01-M09/M12-M14/M16（M10/M11/M15 属解析器层）。

stdout 只输出统一 JSON（ensure_ascii=False），日志走 stderr。

CLI:
    python scripts/compile.py --run-id <id> [--platform lua] [--output <path>]
    python scripts/compile.py list-runs
    python scripts/compile.py inspect --run-id <id>
"""
import argparse
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# === 配置 ===
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG_PATH = os.path.join(ROOT, "config", "models.yaml")
TEMPLATE_DIR = os.path.join(ROOT, "templates")
OUTPUT_DIR = os.path.join(ROOT, "output")

DEFAULT_CONFIG = {
    "timeout_multiplier": 1.5,
    "big_count_default": 20,
    "loop_detection_threshold": 3,
    "no_feature_count": 5,
    "default_platform": "lua",
}
SUPPORTED_PLATFORMS = ("lua",)   # 首期仅 Lua（懒人精灵）
MIN_TIMEOUT_MS = 5000            # 模块超时下限
COORD_TOL = 10                   # 同坐标循环判定半径（px，±10 视为同坐标）
DEFAULT_TOLERANCE = "101010"     # 无色点/缺 tolerance 时的固定偏色
LOOP_FALLBACK_COUNT = 10         # 循环模块无 bigCount 时的默认循环次数
EXIT_MODULE = "通用任务退出"      # M08 锚点模块（框架契约模块）
POPUP_MODULE = "通用弹窗"        # M07 异常恢复模块
MAX_CHAIN_DEPTH = 7              # M16 模块链深上限

_LUA_NAME_RE = re.compile(r"^[A-Za-z_\u4e00-\u9fff][A-Za-z0-9_\u4e00-\u9fff]*$")


# === 核心函数（迁移时直接 import，零改动）===

def load_state_machine(run_id: str, state_dir: str = "state") -> dict:
    """加载运行时状态机 JSON；文件缺失抛 FileNotFoundError(RUN_NOT_FOUND)。"""
    d = state_dir if os.path.isabs(state_dir) else os.path.join(ROOT, state_dir)
    path = os.path.join(d, f"{run_id}.json")
    if not os.path.isfile(path):
        raise FileNotFoundError(f"RUN_NOT_FOUND: 状态机文件不存在: {path}")
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def de_explore(run: dict) -> dict:
    """去探索化（M06）。

    1) 成功转移：同一 from_state→to_state 只保留第一条；
    2) 失败转移：分组保留原始记录（不丢失败信息，供 notFun 备胎映射）。
    返回 {"states", "transitions", "failed_transitions", "anomalies"}
    （anomalies 原样透传）。
    """
    seen, kept, failed = set(), [], []
    for t in run.get("transitions", []):
        if not t.get("success"):
            failed.append(t)
            continue
        key = (t.get("from_state"), t.get("to_state"))
        if key in seen:
            continue
        seen.add(key)
        kept.append(t)
    return {
        "states": run.get("states", []),
        "transitions": kept,
        "failed_transitions": failed,
        "anomalies": run.get("anomalies", []),
    }


def compute_anchor(transitions: list, states: list) -> int:
    """M08 锚点状态：成功入边最多的状态（去重成功转移、排除自环）。

    并列取 states 中最早出现者；无任何成功入边则取 state 0（缺失时取首状态）。
    """
    indeg = {}
    for t in transitions:
        if t.get("from_state") == t.get("to_state"):
            continue
        indeg[t.get("to_state")] = indeg.get(t.get("to_state"), 0) + 1
    order = [st.get("state_id") for st in states]
    if not indeg:
        return 0 if 0 in order else (order[0] if order else 0)
    best = max(indeg.values())
    for sid in order:
        if indeg.get(sid, 0) == best:
            return sid
    return order[0] if order else 0


def _analyze_tasks(run: dict, transitions: list, states_by_id: dict,
                   anchor_sid: int) -> list:
    """任务链分析（M01/M03/M14）。

    对 task_tree 每个节点推导：
    - first: 任务首条成功转移（to_state 场景标签 == target_scene 的最早成功转移）；
    - sig: 链签名 (from_state, action.type, round(coord))；
    - delegate_to: 链签名相同的组内首个任务的模块名（槽2 执行方法委托）；
    - light: 轻量任务（模块链长度==1 且首转移 from_state==anchor 且特征有 stable_points）。
    返回按 task_tree 顺序的 info 列表。
    """
    tasks = run.get("task_tree") or []
    infos = []
    for node in tasks:
        status = node.get("status", "pending")
        scene = node.get("target_scene") or ""
        first = None
        if status not in ("failed", "skipped") and scene:
            for t in transitions:
                st = states_by_id.get(t.get("to_state"))
                if st is not None and st.get("scene_label") == scene:
                    first = t
                    break
        sig = None
        if first is not None:
            act = first.get("action") or {}
            coord = act.get("coord") or (0, 0)
            sig = (first.get("from_state"), act.get("type"),
                   (round(coord[0]), round(coord[1])))
        infos.append({"node": node, "first": first, "sig": sig,
                      "delegate_to": None, "light": False, "ps_param": ""})

    # M14 轻量判定：链长（沿成功转移图从 to_state 可达的状态数）==1
    def reachable(sid):
        seen, stack = {sid}, [sid]
        while stack:
            cur = stack.pop()
            for t in transitions:
                nxt = t.get("to_state")
                if t.get("from_state") == cur and nxt not in seen:
                    seen.add(nxt)
                    stack.append(nxt)
        return seen

    for info in infos:
        t = info["first"]
        if t is None:
            continue
        act = t.get("action") or {}
        if (len(reachable(t.get("to_state"))) == 1
                and t.get("from_state") == anchor_sid
                and act.get("type") == "click" and act.get("coord")):
            elem = _find_target_element(
                states_by_id.get(t.get("from_state")) or {}, act["coord"])
            if elem and elem.get("stable_points"):
                info["light"] = True

    # M03 委托分组：链签名相同的任务共享组内首个任务的模块
    groups = {}
    for i, info in enumerate(infos):
        if info["sig"] is not None and info["node"].get("status") not in ("failed", "skipped"):
            groups.setdefault(info["sig"], []).append(i)
    for idxs in groups.values():
        if len(idxs) <= 1:
            continue
        head = infos[idxs[0]]
        for i in idxs[1:]:
            if head["light"]:
                infos[i]["light"] = True      # 组首为轻量 → 成员同为轻量条目
            else:
                infos[i]["delegate_to"] = head["node"].get("name", "")
    return infos


def _make_feature_row(t: dict, state: dict, to_name: str, out: bool,
                      real_res, design_res) -> dict:
    """由成功转移构造一条特征行（状态模块与任务模块共用）。"""
    act = t.get("action") or {}
    coord = act.get("coord")
    feat = t.get("feature") or {}
    anchor = feat.get("anchor") or _detect_anchor(coord[0], coord[1],
                                                  real_res[0], real_res[1])
    elem = _find_target_element(state, coord)
    ps = _build_ps_param(elem, coord, anchor, real_res, design_res)
    name = (elem or {}).get("text") or f"点击{coord[0]}x{coord[1]}"
    return {
        "name": _lua_escape(name),
        "priority": 5,
        "ff": "ps",
        "ps_param": ps,
        "big_count": None,
        "degree": 0.9,
        "out": out,
        "next_method": _lua_escape(to_name) if to_name else "",
        "not_fun": "",
        "wait_out": 0,
        "is_click": None,
        "wait": None,
        "mathch_check": False,
        "exec_cond": "",
        "var_assign": "",
        "todo": False,
        "_coord": (coord[0], coord[1]),
    }


def build_modules(run: dict, warnings: list = None) -> list:
    """构建任务模块列表（M06/M07/M08/M13/M14/M16）。

    - 状态模块：每状态一个，scene_label 重名追加 _2/_3；每条成功出边一条特征，
      同坐标（±10px）成功点击合并判定 ps_s + bigCount（M09/M12）；
    - M08 锚点：to_state==锚点状态的特征行 运行后方法="通用任务退出" + out=true，
      不再生成终态回归空模块（主界面_2）；
    - M06 失败转移 → notFun 备胎：映射到同 from/同坐标（±10px）的成功特征行；
    - M07 通用弹窗：handled=true 异常按其状态场景生成（关闭+跳回被打断模块）；
    - M14 任务模块：非轻量、非委托任务生成任务名同名模块（首转移单行）；
    - M13 模块级配置：无特征次数 / 无特征运行；
    - M16 静态校验：链深 >7、空模块告警。
    """
    warnings = warnings if warnings is not None else []
    cfg = _load_config()
    threshold = int(cfg["loop_detection_threshold"])
    multiplier = float(cfg["timeout_multiplier"])
    no_feature_count = int(cfg.get("no_feature_count", 5))

    cleaned = de_explore(run)
    states = cleaned["states"]
    transitions = cleaned["transitions"]
    failed_transitions = cleaned["failed_transitions"]

    states_by_id = {st.get("state_id"): st for st in states}
    state_order = [st.get("state_id") for st in states]
    anchor_sid = compute_anchor(transitions, states)

    # M01/M03/M14 任务链分析（提前执行：轻量任务的首转移由任务列表条目自带，
    # 不进入状态模块，避免其他任务流程误触）
    task_infos = _analyze_tasks(run, transitions, states_by_id, anchor_sid)
    light_sigs = {info["sig"] for info in task_infos
                  if info.get("light") and info.get("sig")}

    # 模块命名（scene_label 重名追加 _2/_3）
    used, state_names, labels = {}, {}, {}
    for st in states:
        sid = st.get("state_id")
        label = st.get("scene_label") or f"状态{sid}"
        labels[sid] = label
        n = used.get(label, 0) + 1
        used[label] = n
        state_names[sid] = label if n == 1 else f"{label}_{n}"

    # 同坐标成功点击统计（用原始记录，保留探索期全部成功尝试）
    clicks = {}
    for t in run.get("transitions", []):
        if not t.get("success"):
            continue
        act = t.get("action") or {}
        if act.get("type") == "click" and act.get("coord"):
            clicks.setdefault(t.get("from_state"), []).append(
                (act["coord"][0], act["coord"][1]))

    from_edges = {t.get("from_state") for t in transitions}
    terminal_ids = {sid for sid in state_order if sid not in from_edges}

    real_res = run.get("resolution") or [0, 0]
    design_res = run.get("design_resolution") or real_res

    # M07 通用弹窗是否存在（决定业务模块 无特征运行 与锚点行 notFun）
    popup_state_ids = []
    for a in run.get("anomalies", []):
        if not a.get("handled"):
            continue
        sid = a.get("state_id")
        if sid not in states_by_id:
            warnings.append(f"通用弹窗异常 state_id={sid} 不在 states 中，已跳过")
            continue
        if sid not in popup_state_ids:
            popup_state_ids.append(sid)   # 同一弹窗场景合并为一行
    has_popup = bool(popup_state_ids)

    modules = []
    rows_by_sid = {}   # sid -> 状态模块特征行列表（notFun 映射用）
    wait_out_counts = {}   # sid -> outCount（M10：等待确认行连续检测阈值）
    # --- 状态模块 ---
    for st in states:
        sid = st.get("state_id")
        features = []
        for t in (t for t in transitions if t.get("from_state") == sid):
            act = t.get("action") or {}
            coord = act.get("coord")
            # M10：等待类转移 → mathchCheck 确认行（模块级 outCount 连续检测 N 次退出）
            if act.get("type") == "wait":
                to_st = states_by_id.get(t.get("to_state")) or {}
                elem = next((e for e in (to_st.get("elements") or [])
                             if e.get("stable_points")), None)
                feat = t.get("feature") or {}
                center = (elem or {}).get("center") or (0, 0)
                w_anchor = feat.get("anchor") or _detect_anchor(
                    center[0], center[1], real_res[0], real_res[1])
                w_ps = _build_ps_param(elem, center, w_anchor, real_res, design_res)
                to_label = state_names.get(t.get("to_state"),
                                           to_st.get("scene_label") or "目标")
                features.append({
                    "name": _lua_escape(f"{to_label}确认"),
                    "priority": 5,
                    "ff": "ps",
                    "ps_param": w_ps,
                    "big_count": None,
                    "degree": 0.9,
                    "out": False,
                    "next_method": "",
                    "not_fun": "",
                    "wait_out": max(int(t.get("duration_ms", 0) or 0), 3000),
                    "is_click": False,
                    "wait": 0,
                    "mathch_check": True,
                    "exec_cond": "",
                    "var_assign": "",
                    "todo": not (elem or {}).get("stable_points"),
                    "_coord": None,
                })
                wait_out_counts[sid] = max(wait_out_counts.get(sid, 0),
                                           int(t.get("verify_count", 2) or 2))
                continue
            if act.get("type") != "click" or not coord:
                print(f"[compile] 跳过非点击转移: 状态{sid} -> {t.get('to_state')}",
                      file=sys.stderr)
                continue
            # 轻量任务的首转移：由任务列表条目自带特征承担，不进状态模块
            if (sid, act.get("type"),
                    (round(coord[0]), round(coord[1]))) in light_sigs:
                continue
            # 同坐标成功点击次数（±10px 视为同坐标）
            cnt = sum(1 for (px, py) in clicks.get(sid, [])
                      if abs(px - coord[0]) <= COORD_TOL and abs(py - coord[1]) <= COORD_TOL)
            is_loop = cnt > threshold

            to_sid = t.get("to_state")
            # M08：回到锚点 → 通用任务退出收口，不再生成终态回归空模块
            out = to_sid == anchor_sid or to_sid in terminal_ids
            to_name = EXIT_MODULE if to_sid == anchor_sid else \
                state_names.get(to_sid, f"状态{to_sid}")
            row = _make_feature_row(t, st, to_name, out, real_res, design_res)
            if is_loop:
                row["ff"] = "ps_s"
                row["big_count"] = cnt
            features.append(row)

        durs = [t.get("duration_ms", 0) for t in run.get("transitions", [])
                if t.get("success") and t.get("from_state") == sid]
        avg = (sum(durs) / len(durs)) if durs else 0.0
        timeout_ms = max(MIN_TIMEOUT_MS, round(avg * multiplier))

        mod_name = state_names[sid]
        loop_count = next((f["big_count"] for f in features if f["ff"] == "ps_s"), None)
        rows_by_sid[sid] = features
        if not features and sid in terminal_ids and sid != anchor_sid:
            # 仅由轻量任务到达的终态：不生成空模块（条目自带特征已闭环）
            continue
        modules.append({
            "name": _lua_escape(mod_name),
            "bracket": not _is_lua_name(mod_name),
            "kind": "state",
            "features": features,
            "timeout_ms": timeout_ms,
            "loop_count": loop_count,
            "is_terminal": sid not in from_edges,
            "no_feature_count": no_feature_count,
            "no_feature_run": POPUP_MODULE if has_popup else "",
            "out_count": wait_out_counts.get(sid),
        })

    # --- M06 notFun 失败备胎映射 ---
    def _match_row(rows, coord):
        best, best_d = None, None
        for row in rows:
            if not row.get("_coord"):
                continue   # 等待确认行/无坐标行不参与 notFun 坐标匹配
            rx, ry = row["_coord"]
            d = max(abs(rx - coord[0]), abs(ry - coord[1]))
            if d <= COORD_TOL and (best_d is None or d < best_d):
                best, best_d = row, d
        return best

    notfun_cand = {}   # id(row) -> [to_state, ...]（保序）
    for f in failed_transitions:
        to_sid = f.get("to_state")
        act = f.get("action") or {}
        coord = act.get("coord") or (0, 0)
        ctext = f"coord({coord[0]}, {coord[1]})"
        if to_sid not in states_by_id:
            warnings.append(f"失败转移丢弃（to_state {to_sid} 不在 states 中，探索噪声）: "
                            f"from {f.get('from_state')} {ctext} to {to_sid}")
            continue
        if act.get("type") != "click" or not act.get("coord"):
            warnings.append(f"失败转移丢弃（非点击动作）: "
                            f"from {f.get('from_state')} {ctext} to {to_sid}")
            continue
        row = _match_row(rows_by_sid.get(f.get("from_state"), []), coord)
        if row is None:
            warnings.append(f"失败转移无法映射 notFun: "
                            f"from {f.get('from_state')} {ctext} to {to_sid}")
            continue
        notfun_cand.setdefault(id(row), []).append(to_sid)
    all_rows = (r for rows in rows_by_sid.values() for r in rows)
    for row in all_rows:
        cands = notfun_cand.get(id(row))
        if not cands:
            continue
        counts = {}
        for sid in cands:   # dict 保序，并列时取先出现者
            counts[sid] = counts.get(sid, 0) + 1
        best_to = max(counts, key=lambda k: counts[k])
        row["not_fun"] = _lua_escape(state_names.get(best_to, f"状态{best_to}"))

    # --- 任务模块（M14：非轻量、非委托、非停用任务 → 任务名同名模块）---
    for info in task_infos:
        node, t = info["node"], info["first"]
        if node.get("status") in ("failed", "skipped"):
            continue
        if info["light"] or info["delegate_to"] or t is None:
            continue
        if (t.get("action") or {}).get("type") != "click" or not (t.get("action") or {}).get("coord"):
            continue
        name = node.get("name", "")
        if not name:
            continue
        to_sid = t.get("to_state")
        out = to_sid == anchor_sid or to_sid in terminal_ids
        to_name = EXIT_MODULE if to_sid == anchor_sid else \
            state_names.get(to_sid, f"状态{to_sid}")
        row = _make_feature_row(t, states_by_id.get(t.get("from_state")) or {},
                                to_name, out, real_res, design_res)
        # M11：run_once → 执行条件+变量赋值 幂等对（仅执行一次，跨轮跳过）
        if node.get("run_once"):
            key = _lua_escape(name)
            row["exec_cond"] = f'{{1, "任务变量", "任务_{key}", "~=", "1"}}'
            row["var_assign"] = f'{{1, "任务变量", "任务_{key}", "1", "s"}}'
        timeout_ms = max(MIN_TIMEOUT_MS, round(t.get("duration_ms", 0) * multiplier))
        modules.append({
            "name": _lua_escape(name),
            "bracket": not _is_lua_name(name),
            "kind": "task",
            "features": [row],
            "timeout_ms": timeout_ms,
            "loop_count": None,
            "no_feature_count": no_feature_count,
            "no_feature_run": POPUP_MODULE if has_popup else "",
        })

    # --- M07 通用弹窗模块 ---
    if has_popup:
        features = []
        for sid in popup_state_ids:
            st = states_by_id[sid]
            scene = st.get("scene_label") or f"状态{sid}"
            elems = [e for e in (st.get("elements") or []) if e.get("stable_points")]
            elem = elems[0] if elems else \
                ((st.get("elements") or [None])[0])
            todo = not (elem or {}).get("stable_points")
            center = (elem or {}).get("center") or (0, 0)
            anchor = _detect_anchor(center[0], center[1], real_res[0], real_res[1])
            ps = _build_ps_param(elem, center, anchor, real_res, design_res)
            # 被打断模块 = 进入该弹窗状态的成功转移的来源模块
            src = next((t.get("from_state") for t in transitions
                        if t.get("to_state") == sid), None)
            interrupted = state_names.get(src) if src is not None and src in state_names \
                else EXIT_MODULE
            features.append({
                "name": _lua_escape(f"{scene}关闭"),
                "priority": 5,
                "ff": "ps",
                "ps_param": ps,
                "big_count": None,
                "degree": 0.9,
                "out": True,
                "next_method": _lua_escape(interrupted),
                "not_fun": "",
                "wait_out": 0,
                "is_click": None,
                "wait": None,
                "todo": todo,
                "_coord": None,
            })
        modules.append({
            "name": POPUP_MODULE,
            "bracket": not _is_lua_name(POPUP_MODULE),
            "kind": "popup",
            "features": features,
            "timeout_ms": 30,
            "loop_count": 1,
            "shot_to_mem": True,
        })

    # --- M08 通用任务退出模块（锚点回归，框架契约模块）---
    anchor_st = states_by_id.get(anchor_sid) or {}
    anchor_elem = next((e for e in (anchor_st.get("elements") or [])
                        if e.get("stable_points")), None)
    anchor_todo = anchor_elem is None
    if anchor_elem is None:
        anchor_elem = (anchor_st.get("elements") or [None])[0]
    anchor_center = (anchor_elem or {}).get("center") or (0, 0)
    anchor_ps = _build_ps_param(anchor_elem, anchor_center, 5,
                                real_res, design_res)
    exit_features = [{
        "name": "主界面锚点",
        "priority": 5,
        "ff": "ps",
        "ps_param": anchor_ps,
        "big_count": None,
        "degree": 0.9,
        "out": True,
        "next_method": "",
        "not_fun": POPUP_MODULE if has_popup else "",
        "wait_out": 0,
        "is_click": False,
        "wait": 0,
        "todo": anchor_todo,
        "_coord": None,
    }]
    modules.append({
        "name": EXIT_MODULE,
        "bracket": not _is_lua_name(EXIT_MODULE),
        "kind": "exit",
        "features": exit_features,
        "timeout_ms": 60,
        "loop_count": None,
    })

    # --- M16 静态校验：链深 / 空模块 ---
    graph = {}
    for m in modules:
        for row in m["features"]:
            if row["next_method"]:
                graph.setdefault(m["name"], set()).add(row["next_method"])

    def _longest(u, visited):
        best = 1
        for v in graph.get(u, ()):   # visited 防环（自循环 ps_s 模块）
            if v not in visited:
                best = max(best, 1 + _longest(v, visited | {v}))
        return best

    for m in modules:
        if m["kind"] != "task":
            continue
        depth = _longest(m["name"], {m["name"]})
        if depth > MAX_CHAIN_DEPTH:
            warnings.append(f"任务模块[{m['name']}]链深 {depth} 超过上限 {MAX_CHAIN_DEPTH}")
    for m in modules:
        if not m["features"]:
            warnings.append(f"空模块（无特征行）: {m['name']}")

    return modules


def _task_params_str(params: list) -> str:
    """任务参数 9 槽位 → Lua 数组字面量（null/false/数字/字符串）。"""
    parts = []
    for v in params:
        if v is None:
            parts.append("null")
        elif v is True:
            parts.append("true")
        elif v is False:
            parts.append("false")
        elif isinstance(v, (int, float)):
            parts.append(str(v))
        else:
            parts.append(f'"{_lua_escape(v)}"')
    return "{" + ", ".join(parts) + "}"


def build_task_list(modules: list, run: dict, warnings: list = None) -> list:
    """由 task_tree 逐节点重建任务列表条目（M01/M02/M03/M04/M05/M14）。

    - 保持 task_tree 顺序，同名不去重（M04）；
    - failed/skipped 节点 → 注释停用条目（kind="disabled"，M05）；
    - 轻量任务 → 条目自带特征（kind="light"，M01/M14）；
    - 委托任务 → 槽2=首任务模块名（kind="schedule"，M03）；
    - 其余 → 调度条目（ff="nil"，槽位语义见 M02）；
    - repeat_count>1 → 条目重复输出 repeat_count 次（M04）。

    task_tree 为空时从模块推导单条主任务（以终态模块为目标场景）。
    """
    warnings = warnings if warnings is not None else []
    cleaned = de_explore(run)
    states_by_id = {st.get("state_id"): st for st in cleaned["states"]}
    anchor_sid = compute_anchor(cleaned["transitions"], cleaned["states"])

    module_names = {m["name"] for m in modules}

    def _entry(kind, node, info=None):
        name = node.get("name", "")
        repeat = int(node.get("repeat_count", 1) or 1)
        params = [0, None, 0, None, None, False, None, None, None]
        if repeat > 1:
            params[6] = repeat
        first = (info or {}).get("first")
        if first is not None and first.get("from_state") != anchor_sid:
            params[8] = EXIT_MODULE
        if (info or {}).get("delegate_to"):
            params[1] = (info or {})["delegate_to"]
        entry = {"kind": kind, "name": name, "repeat": repeat,
                 "params_str": _task_params_str(params),
                 "ps_param": (info or {}).get("ps_param", ""),
                 "exec_cond": "", "var_assign": ""}
        # M11：轻量条目 run_once → 幂等对直接挂条目（条目本身是可执行特征行）
        if kind == "light" and node.get("run_once"):
            key = _lua_escape(name)
            entry["exec_cond"] = f'{{1, "任务变量", "任务_{key}", "~=", "1"}}'
            entry["var_assign"] = f'{{1, "任务变量", "任务_{key}", "1", "s"}}'
        return entry

    tasks = run.get("task_tree") or []
    if not tasks:
        terminal = next((m["name"] for m in reversed(modules) if m.get("is_terminal")), None)
        target = terminal or (modules[-1]["name"] if modules else "")
        node = {"name": run.get("goal") or "主任务", "repeat_count": 1}
        entry = _entry("schedule", node)
        if target not in module_names:
            warnings.append(f"调度条目[{entry['name']}]无对应模块: {target}")
        return [entry]

    task_infos = _analyze_tasks(run, cleaned["transitions"], states_by_id, anchor_sid)
    # 轻量条目的 ps_param（设计坐标）
    real_res = run.get("resolution") or [0, 0]
    design_res = run.get("design_resolution") or real_res
    for info in task_infos:
        t = info["first"]
        if not info["light"] or t is None:
            continue
        act = t.get("action") or {}
        feat = t.get("feature") or {}
        anchor = feat.get("anchor") or _detect_anchor(act["coord"][0], act["coord"][1],
                                                      real_res[0], real_res[1])
        elem = _find_target_element(states_by_id.get(t.get("from_state")) or {},
                                    act["coord"])
        info["ps_param"] = _build_ps_param(elem, act["coord"], anchor,
                                           real_res, design_res)

    entries = []
    for node, info in zip(tasks, task_infos):
        name = node.get("name", "")
        status = node.get("status", "pending")
        if status in ("failed", "skipped"):
            warnings.append(f"任务[{name}] status={status}，已注释停用")
            entry = _entry("disabled", node, info)
        elif info["light"]:
            entry = _entry("light", node, info)
        elif info["delegate_to"]:
            if node.get("run_once"):
                warnings.append(f"任务[{name}] run_once 与执行方法委托冲突，已忽略 run_once")
            entry = _entry("schedule", node, info)
        else:
            entry = _entry("schedule", node, info)
            if _lua_escape(entry["name"]) not in module_names:
                warnings.append(f"调度条目[{entry['name']}]无对应同名模块")
        entries.append(entry)
        # M04：repeat_count>1 → 条目重复输出（节奏表达）
        repeat = int(node.get("repeat_count", 1) or 1)
        entries.extend([dict(entry) for _ in range(repeat - 1)])
    return entries


def render_template(modules: list, task_list: list, platform: str, meta: dict) -> str:
    """Jinja2 渲染 Lua 脚本；platform 仅支持 lua，否则抛 ValueError(PLATFORM_UNSUPPORTED)。"""
    if platform not in SUPPORTED_PLATFORMS:
        raise ValueError(
            f"PLATFORM_UNSUPPORTED: 平台 {platform!r} 不受支持，当前仅支持 {list(SUPPORTED_PLATFORMS)}")
    from jinja2 import Environment, FileSystemLoader
    env = Environment(loader=FileSystemLoader(TEMPLATE_DIR),
                      autoescape=False, trim_blocks=True, lstrip_blocks=True,
                      keep_trailing_newline=True)
    tpl = env.get_template("lua_template.j2")
    return tpl.render(modules=modules, task_list=task_list, meta=meta)


def compile_run(run_id: str, platform: str = "lua",
                output_path: str = None) -> dict:
    """主编译入口：加载→编译→渲染→写文件。

    返回 {"output_path", "modules_count", "features_count", "warnings"}。
    """
    path, modules, warnings = _compile_command(run_id, platform, output_path)
    features = sum(len(m["features"]) for m in modules)
    return {"output_path": path, "modules_count": len(modules),
            "features_count": features, "warnings": warnings}


# === 内部工具 ===

_COORD_FALLBACK_WARNED = False


def _load_config() -> dict:
    """读取 config/models.yaml 的 compiler 段，缺失项用默认值。"""
    cfg = dict(DEFAULT_CONFIG)
    try:
        import yaml
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f) or {}
        compiler = data.get("compiler") or {}
        for k in DEFAULT_CONFIG:
            if compiler.get(k) is not None:
                cfg[k] = compiler[k]
    except FileNotFoundError:
        pass
    except Exception as exc:
        print(f"[compile] 配置读取失败，使用默认值: {exc}", file=sys.stderr)
    return cfg


def _convert_point(x, y, real_res, design_res, anchor):
    """真实坐标 → 设计分辨率坐标。

    按契约调用 coordinate.real_to_design（lazy import，避免与并行开发的时序耦合）；
    coordinate 模块不可用时退化为本地等比换算（等分辨率时为恒等映射）。
    """
    global _COORD_FALLBACK_WARNED
    try:
        import coordinate
        return coordinate.real_to_design(x, y, real_res, design_res, anchor)
    except Exception as exc:
        if not _COORD_FALLBACK_WARNED:
            print(f"[compile] coordinate 模块不可用，使用本地兜底换算: {exc}", file=sys.stderr)
            _COORD_FALLBACK_WARNED = True
        rw = real_res[0] if real_res and len(real_res) > 1 else 0
        rh = real_res[1] if real_res and len(real_res) > 1 else 0
        dw = design_res[0] if design_res and len(design_res) > 1 else rw
        dh = design_res[1] if design_res and len(design_res) > 1 else rh
        if rw and rh and dw and dh:
            return (x * dw / rw, y * dh / rh)
        return (x, y)


def _detect_anchor(cx, cy, w, h):
    """九宫格锚点检测；按契约调用 coordinate.detect_anchor，不可用时返回中心锚点 5。"""
    try:
        import coordinate
        return int(coordinate.detect_anchor(cx, cy, w, h))
    except Exception as exc:
        if not _COORD_FALLBACK_WARNED:
            print(f"[compile] coordinate 模块不可用，锚点默认取 5: {exc}", file=sys.stderr)
            _COORD_FALLBACK_WARNED = True
        return 5


def _find_target_element(state: dict, coord):
    """在状态中定位转移动作命中的目标元素：先按 stable_points 匹配（±10px），否则取 center 最近者。"""
    elems = state.get("elements") or []
    best, best_d = None, None
    for e in elems:
        for sp in e.get("stable_points") or []:
            d = abs(sp.get("x", 0) - coord[0]) + abs(sp.get("y", 0) - coord[1])
            if d <= COORD_TOL and (best_d is None or d < best_d):
                best, best_d = e, d
    if best is not None:
        return best
    for e in elems:
        c = e.get("center") or (0, 0)
        d = abs(c[0] - coord[0]) + abs(c[1] - coord[1])
        if best_d is None or d < best_d:
            best, best_d = e, d
    return best


def _build_ps_param(elem, coord, anchor, real_res, design_res) -> str:
    """组装 ps_param："x|y|color-tol,..."，颜色小写。

    有 stable_points 用点集（各点同样经坐标换算）；否则用 action.coord 单点
    + 固定 tolerance（颜色未知以 000000 占位）。
    """
    points = (elem or {}).get("stable_points") or []
    if not points:
        points = [{"x": coord[0], "y": coord[1],
                   "color": "", "tolerance": DEFAULT_TOLERANCE}]
    parts = []
    for sp in points:
        dx, dy = _convert_point(sp.get("x", 0), sp.get("y", 0),
                                real_res, design_res, anchor)
        color = str(sp.get("color") or "").lower() or "000000"
        tol = str(sp.get("tolerance") or DEFAULT_TOLERANCE)
        parts.append(f"{_fmt_coord(dx)}|{_fmt_coord(dy)}|{color}-{tol}")
    return ",".join(parts)


def _fmt_coord(v) -> str:
    return str(int(round(v)))


def _lua_escape(s: str) -> str:
    """Lua 字符串字面量转义（用于 "..." 包裹的名称）。"""
    return str(s).replace("\\", "\\\\").replace('"', '\\"')


def _is_lua_name(s: str) -> bool:
    """是否可直接用点语法引用（中文/字母/下划线开头，仅含中文、字母、数字、下划线）。"""
    return bool(_LUA_NAME_RE.match(s))


def _resolve_output_path(output_path, run_id) -> str:
    if not output_path:
        return os.path.join(OUTPUT_DIR, f"{run_id}.lua")
    p = output_path if os.path.isabs(output_path) else os.path.join(ROOT, output_path)
    os.makedirs(os.path.dirname(os.path.abspath(p)), exist_ok=True)
    return p


def _compile_command(run_id: str, platform: str, output_path):
    """编译并写文件，返回 (output_path, modules, warnings)。"""
    run = load_state_machine(run_id)
    warnings = []
    modules = build_modules(run, warnings)
    task_list = build_task_list(modules, run, warnings)
    meta = {
        "goal": run.get("goal", ""),
        "generated_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "todo_anomalies": [a for a in run.get("anomalies", []) if not a.get("handled")],
    }
    lua = render_template(modules, task_list, platform, meta)
    path = _resolve_output_path(output_path, run.get("run_id") or run_id)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(lua)
    return path, modules, warnings


def _list_runs() -> dict:
    """扫描 state/*.json，返回运行记录摘要。"""
    runs = []
    if os.path.isdir(os.path.join(ROOT, "state")):
        for fn in sorted(os.listdir(os.path.join(ROOT, "state"))):
            if not fn.endswith(".json"):
                continue
            path = os.path.join(ROOT, "state", fn)
            try:
                with open(path, "r", encoding="utf-8") as f:
                    run = json.load(f)
                runs.append({
                    "run_id": run.get("run_id", fn[:-5]),
                    "goal": run.get("goal", ""),
                    "start_time": run.get("start_time"),
                    "states_count": len(run.get("states", [])),
                })
            except Exception as exc:
                print(f"[compile] 跳过无效状态文件 {fn}: {exc}", file=sys.stderr)
    return {"runs": runs}


def _inspect(run_id: str) -> dict:
    """返回状态机摘要（inspect 子命令，含静态校验 warnings）。"""
    run = load_state_machine(run_id)
    warnings = []
    try:
        modules = build_modules(run, warnings)
        build_task_list(modules, run, warnings)
    except Exception as exc:
        warnings.append(f"编译预检失败: {type(exc).__name__}: {exc}")
    return {
        "run_id": run.get("run_id"),
        "goal": run.get("goal", ""),
        "states_count": len(run.get("states", [])),
        "transitions_count": len(run.get("transitions", [])),
        "task_tree": run.get("task_tree", []),
        "anomalies_count": len(run.get("anomalies", [])),
        "warnings": warnings,
    }


# === CLI 入口 ===

def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")

    parser = argparse.ArgumentParser(
        prog="compile.py", description="运行时状态机 JSON → JY-Frame 脚本编译器")
    parser.add_argument("command", nargs="?", choices=["list-runs", "inspect"],
                        help="子命令（缺省为编译）")
    parser.add_argument("--run-id", dest="run_id", help="状态机运行 ID")
    parser.add_argument("--platform", default=None,
                        help="输出平台（默认取 config compiler.default_platform，即 lua）")
    parser.add_argument("--output", default=None,
                        help="输出文件路径（默认 output/<run_id>.lua）")
    args = parser.parse_args()

    t0 = time.time()
    meta = {"script": "compile.py", "command": args.command or "compile",
            "timestamp": round(time.time(), 3)}

    def _emit(payload, code):
        meta["duration_ms"] = int((time.time() - t0) * 1000)
        payload["meta"] = meta
        print(json.dumps(payload, ensure_ascii=False))
        sys.exit(code)

    try:
        if args.command == "list-runs":
            _emit({"status": "ok", "data": _list_runs()}, 0)
        if args.command == "inspect":
            if not args.run_id:
                _emit({"status": "error", "error": "MISSING_ARGUMENT",
                       "message": "inspect 需要 --run-id"}, 2)
            _emit({"status": "ok", "data": _inspect(args.run_id)}, 0)
        if not args.run_id:
            _emit({"status": "error", "error": "MISSING_ARGUMENT",
                   "message": "编译需要 --run-id（或使用 list-runs / inspect 子命令）"}, 2)
        platform = args.platform or _load_config()["default_platform"]
        path, modules, warnings = _compile_command(args.run_id, platform, args.output)
        data = {"output_path": path,
                "modules_count": len(modules),
                "features_count": sum(len(m["features"]) for m in modules),
                "warnings": warnings}
        _emit({"status": "ok", "data": data}, 0)
    except FileNotFoundError as exc:
        _emit({"status": "error", "error": "RUN_NOT_FOUND", "message": str(exc)}, 1)
    except ValueError as exc:
        code = 2 if "PLATFORM_UNSUPPORTED" in str(exc) else 1
        error = str(exc).split(":", 1)[0]
        _emit({"status": "error", "error": error, "message": str(exc)}, code)
    except Exception as exc:
        _emit({"status": "error", "error": "COMPILE_ERROR",
               "message": f"{type(exc).__name__}: {exc}"}, 1)


if __name__ == "__main__":
    main()