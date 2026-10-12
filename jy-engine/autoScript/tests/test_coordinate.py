"""test_coordinate.py — coordinate.py 单元测试

运行方式：python tests/test_coordinate.py（任意工作目录均可）
覆盖：
1. 已知用例（锚点8 贴底、锚点3 右上角）
2. 9 个锚点 x 2 个测试点的 design_to_real → real_to_design 往返（误差 <= 1px）
3. 反向 real → design → real 往返（误差 <= 1px）
4. detect_anchor 九宫格判定（含边界值）
5. detect_resolution 对无效设备抛 RuntimeError
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))
import coordinate as coord  # noqa: E402

REAL = (1080, 1920)
DESIGN = (720, 1280)

# 设计坐标系（720x1280）下每个锚点取 2 个测试点
DESIGN_POINTS = {
    1: [(0, 0), (240, 427)],
    2: [(360, 0), (360, 427)],
    3: [(720, 0), (480, 427)],
    4: [(0, 640), (240, 853)],
    5: [(360, 640), (400, 700)],
    6: [(720, 640), (480, 853)],
    7: [(0, 1280), (240, 1000)],
    8: [(360, 1280), (360, 1000)],
    9: [(720, 1280), (480, 1000)],
}

KNOWN_CASES = [
    ("design_to_real 锚点8 贴底", coord.design_to_real, (360, 1280), 8, (540, 1920)),
    ("design_to_real 锚点3 右上角", coord.design_to_real, (720, 0), 3, (1080, 0)),
    ("real_to_design 锚点8 贴底", coord.real_to_design, (540, 1920), 8, (360, 1280)),
    ("real_to_design 锚点3 右上角", coord.real_to_design, (1080, 0), 3, (720, 0)),
]

# (center_x, center_y, 期望锚点) @ 1080x1920
ANCHOR_GRID = [
    (180, 213, 1), (540, 213, 2), (900, 213, 3),
    (180, 960, 4), (540, 960, 5), (900, 960, 6),
    (180, 1707, 7), (540, 1707, 8), (900, 1707, 9),
    (0, 0, 1), (360, 640, 5), (719.9, 1279.9, 5), (720, 1280, 9), (1079, 1919, 9),
]

def main() -> int:
    failures = []
    total = 0

    def check(desc, ok, detail=""):
        nonlocal total
        total += 1
        if ok:
            print(f"PASS  {desc}")
        else:
            failures.append(desc)
            print(f"FAIL  {desc}  [{detail}]")

    # 1. 已知用例
    for desc, fn, (x, y), anchor, expected in KNOWN_CASES:
        got = fn(x, y, REAL, DESIGN, anchor)
        check(f"{desc}: ({x},{y}) -> {expected}", got == expected, f"got {got}")

    # 2. design → real → design 往返（误差 <= 1px）
    for anchor, points in DESIGN_POINTS.items():
        for (dx, dy) in points:
            rx, ry = coord.design_to_real(dx, dy, REAL, DESIGN, anchor)
            bx, by = coord.real_to_design(rx, ry, REAL, DESIGN, anchor)
            err = max(abs(bx - dx), abs(by - dy))
            check(f"往返 d->r->d 锚点{anchor} 设计({dx},{dy}) 经 real({rx},{ry})",
                  err <= 1, f"误差 {err}px")

    # 3. real → design → real 反向往返（误差 <= 1px）
    for anchor, points in DESIGN_POINTS.items():
        for (dx, dy) in points:
            rx, ry = coord.design_to_real(dx, dy, REAL, DESIGN, anchor)
            bx, by = coord.real_to_design(rx, ry, REAL, DESIGN, anchor)
            fx, fy = coord.design_to_real(bx, by, REAL, DESIGN, anchor)
            err = max(abs(fx - rx), abs(fy - ry))
            check(f"往返 r->d->r 锚点{anchor} real({rx},{ry})",
                  err <= 1, f"误差 {err}px")

    # 4. detect_anchor 九宫格判定与边界
    for cx, cy, expected in ANCHOR_GRID:
        got = coord.detect_anchor(cx, cy, 1080, 1920)
        check(f"detect_anchor({cx},{cy}) = {expected}", got == expected, f"got {got}")

    # 5. detect_resolution 对无效设备抛 RuntimeError
    try:
        coord.detect_resolution("__no_such_device__")
        check("detect_resolution 无效设备抛 RuntimeError", False, "未抛异常")
    except RuntimeError:
        check("detect_resolution 无效设备抛 RuntimeError", True)

    print()
    if failures:
        print(f"FAILED: {len(failures)}/{total}")
        for f in failures:
            print(f"  - {f}")
        return 1
    print(f"ALL PASSED: {total} checks")
    return 0


if __name__ == "__main__":
    sys.exit(main())