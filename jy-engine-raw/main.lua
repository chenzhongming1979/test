-- =====================================================================
-- 云控入口：jy_protocol op 协议驱动 JY 业务
--   ping/state/sync/init/debug/get/panel/tree/uiset/pageTest/taskTest/
--   action/scriptRun/stop/auth（回执 {op="result",req,...}）
--   旧 modle 协议的接收方案已并入 op 协议：taps/conf/vars 可随 pageTest/
--   taskTest/action/scriptRun 内联携带（消息即完整环境），uiConf 随
--   scriptRun/panel 下发（详见 jy_protocol.lua 头注）
-- jyframe.lua 引导【不可省】：引擎原生 require 查不到工程内包（走 CodeProvider），
-- 必须先由它劫持 _G.require + 定位工程根 + 装载 frameSdk/JY.FrameInit；
-- 之后 require 命中劫持缓存（幂等），EnvInit/G/全局函数全部就绪
-- 日志回传：jy_protocol 装载时包装 print → {"op":"log","line","ts"} 广播（幂等），
-- 9300 通道实时可收（PC 端 script/ws_client.py --logs 实时跟随）
-- =====================================================================
local brand = dofile(__SCRIPT_DIR__ .. "/jyframe.lua") or {}

-- 装载云控协议（装载即注册 onMessage 分发 + print 桥；返回 jy：onIdle/register）
local jy = dofile(__SCRIPT_DIR__ .. "/jy_protocol.lua")

cloud.open{ port = 9300, secret = brand.secret }   -- brand.json 带 secret 时启用 AES 全帧加密
cloud.run{ onIdle = jy.onIdle }                    -- 阻塞泵永不返回；onMessage 必须在 run 前注册完成
--ui.shutdown()               -- 仅正常退出路径（cloud.exit）会走到这里；interrupt 走停止钩子
