# cloud 客户端 —— 接入指南

## 1. 部署（PC → 模拟器/真机）

```powershell
# 一次性：重启 app + 推送 37 文件 + 启动（main.lua 自动开云控 9300）
python run_test.py --device emulator-5558 `
  --sdk-src "d:\Project\script\lrjl\jy-frame-lrjl\jy-frame-firefly\脚本\frameSdk-autolua2"
# 输出 [jyf] 云控服务: true 即就绪
```

部署细节：重启 app（stop_script 后 run_script 不生效，实测）→ adb forward 18081→8080 → push_files 到 `ide_push/framesdk_test/` → run_script。日志实时落 `test_run.log`。

## 2. 接入云控（PC 或云端服务器）

```python
import websocket, json
ws = websocket.create_connection("ws://127.0.0.1:19300", timeout=10)  # adb forward 19300→9300
ws.send(json.dumps({"op":"ping","req":"r1"}, ensure_ascii=False))
print(ws.recv())   # {"op":"result","req":"r1","ok":true,"msg":"pong",...}
```

标准流程：`ping → sync(下发 taps/conf) → init → pageTest/taskTest/action → state`。

## 3. 单命令

```powershell
python ws_client.py --demo                                    # 全链路演示（9 项）
python ws_client.py --cmd ping
python ws_client.py --cmd pageTest --json "{\"page\":\"云控测试\"}" --wait 90
python ws_client.py --cmd sync --json-file demo_sync.json      # 大 JSON 建议走文件
```

## 4. 行为要点

- 执行串行：一次一条，前一条回执后再发下一条（长任务期间消息排队）
- `sync` 已 init 状态下会触发 dirty，下个执行 op 前自动重 EnvInit（坐标重换算）
- `ret` 退出码语义见 doc-flat/23-任务返回值.md（1=out 退出、6=超时…）
- 脚本主循环存活（pump+心跳），WS 断开不影响设备端继续运行，重连即恢复控制
