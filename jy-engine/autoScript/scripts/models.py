"""models.py — 通用数据结构定义

所有脚本共享的数据结构（dataclass + JSON 序列化）。
核心函数无副作用、可独立 import，供 analyze/feature/compile 复用。
"""
from __future__ import annotations

import time
import typing
from dataclasses import dataclass, field, fields
from typing import Optional


# === 工具 ===

def _now() -> float:
    return time.time()


def _coerce(value, hint):
    """将 JSON 反序列化来的值转回 dataclass 字段类型（tuple/list/dataclass）。"""
    if value is None:
        return None
    origin = typing.get_origin(hint)
    if origin is typing.Union:
        args = [a for a in typing.get_args(hint) if a is not type(None)]
        if len(args) == 1:
            return _coerce(value, args[0])
        return value
    if hint is tuple or origin is tuple:
        return tuple(value)
    if hint is list or origin is list:
        args = typing.get_args(hint)
        if args:
            return [_coerce(v, args[0]) for v in value]
        return list(value)
    if isinstance(hint, type) and hasattr(hint, "__dataclass_fields__"):
        return hint.from_dict(value) if isinstance(value, dict) else value
    return value


class _Serializable:
    """提供 to_dict / from_dict 的混入类。"""

    def to_dict(self) -> dict:
        result = {}
        for f in fields(self):
            v = getattr(self, f.name)
            if hasattr(v, "to_dict"):
                v = v.to_dict()
            elif isinstance(v, (tuple, list)):
                conv = lambda item: item.to_dict() if hasattr(item, "to_dict") else item
                v = [conv(i) for i in v] if isinstance(v, list) else tuple(conv(i) for i in v)
            result[f.name] = v
        return result

    @classmethod
    def from_dict(cls, data: dict):
        hints = typing.get_type_hints(cls)
        kwargs = {}
        for f in fields(cls):
            if f.name not in data:
                continue
            kwargs[f.name] = _coerce(data[f.name], hints.get(f.name, object))
        return cls(**kwargs)


# === 核心数据结构 ===

@dataclass
class UIElement(_Serializable):
    """界面元素（视觉 LLM + OCR 融合产物）"""
    id: str
    type: str                    # button/icon/list_item/input/tab/text
    text: str
    bbox: tuple                  # (x1, y1, x2, y2) 真实像素
    center: tuple                # (x, y) 点击中心点
    confidence: float
    is_primary: bool = False
    stable_points: list = field(default_factory=list)  # [{"x","y","color","tolerance"}]


@dataclass
class ScreenState(_Serializable):
    """界面状态（一次感知的完整快照）"""
    state_id: Optional[int]
    scene_label: str
    description: str
    elements: list[UIElement]
    texts: list                   # [{"text","bbox"}]
    phash: str
    screenshot: str
    resolution: tuple = (0, 0)
    timestamp: float = field(default_factory=_now)


@dataclass
class Action(_Serializable):
    """操作动作"""
    type: str                     # click/swipe/long_press/input_text/wait/back/home
    coord: Optional[tuple] = None
    swipe_from: Optional[tuple] = None
    swipe_to: Optional[tuple] = None
    duration: int = 0             # 滑动/长按时长 ms
    input_text: str = ""
    wait_seconds: float = 0
    expected_scene: str = ""
    reason: str = ""


@dataclass
class Feature(_Serializable):
    """特征行（对应 JY-Frame taps 中的一行）"""
    name: str
    ff: str                       # ps/ps_s/ps_w/ft/...
    ps_param: str                 # "x|y|color-tol,..."
    degree: float = 0.9
    priority: int = 5
    out: bool = False
    next_method: str = ""
    is_click: bool = True
    wait: int = 0
    anchor: int = 5               # 九宫格锚点 1-9
    verified: bool = False
    hit_rate: float = 0.0


@dataclass
class Transition(_Serializable):
    """状态转移记录"""
    from_state: int
    to_state: int
    action: Action
    success: bool
    duration_ms: int = 0
    verify_count: int = 1         # M10：验证时连续确认次数（等待类转移 >=2 触发 outCount）
    feature: Optional[Feature] = None
    timestamp: float = field(default_factory=_now)


@dataclass
class TaskNode(_Serializable):
    """任务分解树节点"""
    task_id: int
    name: str
    target_scene: str = ""
    is_repeatable: bool = False
    repeat_count: int = 1
    current_repeat: int = 0
    status: str = "pending"       # pending/running/completed/failed/skipped
    order: int = 0
    run_once: bool = False        # M11：仅执行一次（编译为 执行条件+变量赋值 幂等对）


@dataclass
class Anomaly(_Serializable):
    """异常界面记录"""
    state_id: int
    type: str                     # popup/timeout/loop/offline/unknown
    trigger_action: str = ""
    handled: bool = False
    handle_method: str = ""
    screenshot: str = ""


# === 状态机文件 ===

def new_run(goal: str, device_id: str, resolution: tuple,
            design_resolution: tuple = (720, 1280),
            package: str = "") -> dict:
    """创建一个新的运行状态机骨架。"""
    return {
        "run_id": f"run_{time.strftime('%Y%m%d_%H%M%S')}",
        "goal": goal,
        "device_id": device_id,
        "package": package,
        "resolution": list(resolution),
        "design_resolution": list(design_resolution),
        "start_time": _now(),
        "end_time": None,
        "status": "running",
        "states": [],
        "transitions": [],
        "task_tree": [],
        "anomalies": [],
    }


def load_run(path: str) -> dict:
    """从 JSON 文件加载状态机。"""
    import json
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def save_run(run: dict, path: str) -> str:
    """保存状态机到 JSON 文件，返回路径。"""
    import json
    import os
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(run, f, ensure_ascii=False, indent=2)
    return path


# === 状态去重 ===

def hamming_distance(hash1: str, hash2: str) -> int:
    """两个十六进制感知哈希的汉明距离。"""
    if len(hash1) != len(hash2):
        return 64
    return bin(int(hash1, 16) ^ int(hash2, 16)).count("1")


def find_duplicate_state(run: dict, phash: str, scene_label: str,
                         threshold: int = 5) -> Optional[int]:
    """查找已有状态中 phash 汉明距离 < threshold 且 scene_label 相同的，返回 state_id。"""
    for st in run.get("states", []):
        if st.get("scene_label") != scene_label or not st.get("phash"):
            continue
        if hamming_distance(st["phash"], phash) < threshold:
            return st["state_id"]
    return None


if __name__ == "__main__":
    # 自检：序列化往返
    e = UIElement("elem_001", "button", "副本", (300, 1100, 420, 1200), (360, 1150), 0.95, True)
    s = ScreenState(0, "主界面", "游戏主界面", [e], [], "a1b2c3d4e5f60718", "shot.png", (720, 1280))
    r = ScreenState.from_dict(s.to_dict())
    assert r == s, "ScreenState 序列化往返失败"
    t = Transition.from_dict({
        "from_state": 0, "to_state": 1, "success": True,
        "action": {"type": "click", "coord": [360, 1150], "reason": "测试"},
    })
    assert t.action.coord == (360, 1150)
    print("models.py self-check passed")
