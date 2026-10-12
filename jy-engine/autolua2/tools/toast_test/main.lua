-- Toast 提示框测试脚本
-- 触发方式：WS run_script → 主引擎完整环境（UI.showToast 桥接）
-- duration: 0=SHORT(2s) 1=LONG(3.5s)

UI:showToast("① 引擎启动成功 Toast 桥接正常", 1)
sleep(2000)
UI:showToast("② Lua 脚本执行中 sleep 正常", 1)
sleep(2000)
UI:showToast("③ AutoLua Toast 测试完成", 1)
