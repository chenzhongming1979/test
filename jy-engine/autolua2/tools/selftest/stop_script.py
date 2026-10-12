import json, subprocess, sys, time, os
import websocket

BASE = os.path.dirname(os.path.abspath(__file__))
subprocess.run(["adb", "-s", "emulator-5558", "forward", "tcp:18080", "tcp:8080"], check=True)
ws = websocket.create_connection("ws://127.0.0.1:18080", timeout=10)
print(ws.recv()[:80])
ws.send(json.dumps({"type": "stop_script", "data": {}}))
print("stop_script sent")
time.sleep(2)
try:
    while True:
        raw = ws.recv()
        print("recv:", raw[:150])
except Exception as e:
    print("recv end:", e)
ws.close()
