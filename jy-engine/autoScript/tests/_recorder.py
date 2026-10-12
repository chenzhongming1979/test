# -*- coding: utf-8 -*-
"""录制器：状态机增量记录（会话工具）

用法:
  python tests/_recorder.py init <run_id> <goal>
  python tests/_recorder.py state <analyze_json>     # 从 analyze 输出加状态(去重)，打印 sid
  python tests/_recorder.py trans <from> <to> <type> <x> <y> <ok:0|1> <dur_ms> [verify_count]
  python tests/_recorder.py task <name> <target_scene> <status> <order> [run_once]
  python tests/_recorder.py anomaly <state_id> <type> <handled:0|1>
  python tests/_recorder.py done
  python tests/_recorder.py show
"""
import json
import sys

sys.path.insert(0, "scripts")
sys.stdout.reconfigure(encoding="utf-8")
from models import (Action, ScreenState, TaskNode, Transition,
                    find_duplicate_state, load_run, new_run, save_run)

PATH = ""  # 由 init/全局 run_id 决定


def _path(run_id):
    return f"state/{run_id}.json"


def main(argv):
    cmd = argv[1]
    if cmd == "init":
        run = new_run(argv[3], "emulator-5558", (1280, 720))
        run["run_id"] = argv[2]
        save_run(run, _path(argv[2]))
        print(f"run_id={argv[2]}")
        return
    run_id = argv[2] if cmd in ("state", "trans", "task", "anomaly",
                                "done", "show", "export") else None
    if cmd in ("state", "trans", "task", "anomaly"):
        args = argv[3:]
    elif cmd in ("done", "show", "export"):
        args = []
    else:
        args = argv[2:]
    if cmd == "export":
        run_id = argv[2]
    run = load_run(_path(run_id))

    if cmd == "state":
        data = json.load(open(args[0], encoding="utf-8"))["data"]
        dup = find_duplicate_state(run, data["phash"], data["scene_label"])
        if dup is not None:
            print(f"sid={dup} (dedup)")
            return
        st = ScreenState.from_dict(data)
        st.state_id = len(run["states"])
        run["states"].append(st.to_dict())
        print(f"sid={st.state_id} scene={st.scene_label}")
    elif cmd == "trans":
        act = Action(type=args[2], coord=(int(args[3]), int(args[4])))
        t = Transition(from_state=int(args[0].split(",")[0]) if False else int(args[0]),
                       to_state=int(args[1]), action=act,
                       success=args[5] == "1", duration_ms=int(args[6]),
                       verify_count=int(args[7]) if len(args) > 7 else 1)
        run["transitions"].append(t.to_dict())
        print(f"trans: {t.from_state}->{t.to_state} {act.type} {act.coord}")
    elif cmd == "task":
        node = TaskNode(task_id=len(run["task_tree"]), name=args[0],
                        target_scene=args[1], status=args[2], order=int(args[3]),
                        run_once=len(args) > 4 and args[4] == "1")
        run["task_tree"].append(node.to_dict())
        print(f"task: {node.name} -> {node.target_scene} ({node.status})")
    elif cmd == "anomaly":
        run["anomalies"].append({"state_id": int(args[0]), "type": args[1],
                                 "trigger_action": "click", "handled": args[2] == "1",
                                 "handle_method": "", "screenshot": ""})
        print(f"anomaly: state={args[0]} {args[1]} handled={args[2]}")
    elif cmd == "done":
        run["status"] = "completed"
        import time
        run["end_time"] = time.time()
        print("run completed")
    elif cmd == "export":
        import compile as C
        out = f"output/{run_id}.lua"
        try:
            data = C.compile_run(run_id, "lua", out)
            print(f"export ok: {data['output_path']} modules={data['modules_count']} "
                  f"features={data['features_count']} warnings={len(data['warnings'])}")
            for w in data["warnings"]:
                print(f"  warn: {w}")
        except Exception as e:
            print(f"export failed: {e}")
        return
    elif cmd == "show":
        print(json.dumps({"states": [(s["state_id"], s["scene_label"]) for s in run["states"]],
                          "transitions": len(run["transitions"]),
                          "tasks": [(t["name"], t["target_scene"], t["status"]) for t in run["task_tree"]],
                          "anomalies": len(run["anomalies"])},
                         ensure_ascii=False))
        return
    else:
        print(f"unknown cmd {cmd}")
        return
    save_run(run, _path(run_id))


if __name__ == "__main__":
    main(sys.argv)
