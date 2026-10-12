# IDE 通道发送 stop_script（模拟 VSCode 停止按钮）
import json, websocket
ws = websocket.create_connection("ws://127.0.0.1:18081", timeout=10)
print(ws.recv()[:60])
ws.send(json.dumps({"type": "stop_script", "data": {}}))
print("stop_script sent")
ws.close()
