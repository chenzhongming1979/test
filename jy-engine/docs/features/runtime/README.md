# runtime —— 脚本运行时（脚本容器与生命周期）

> 定位：**引擎 = 常驻运行时，脚本 = 可销毁的容器**。每次脚本运行创建一个"脚本容器"（LuaState + 该脚本创建的跨层资源）；容器销毁时，引擎**确定性回收**容器内的所有资源——不依赖脚本自觉清理。

## 核心问题（为什么需要这个设计）

脚本通过 LocalService 创建的跨层资源（cloud WS server、网络连接、thread 协程、虚拟屏等）**不在 LuaState 里**，LuaState 销毁时它们不会自动回收。脚本被 interrupt 强杀时更没有机会执行清理代码——资源残留直到重启 app。

典型案例：cloud 通道的 WS server（9300）在脚本停止后残留监听，客户端能连上但无人处理消息；叠加引擎 lSleep 锁泄漏（270172d 已修）曾导致"停止后再运行永久失败"。

## 设计文档

- [design.md](design.md) —— 容器模型、生命周期事件、挂载点决策、边界（多容器并行不做）
- [contracts.md](contracts.md) —— `ScriptContextLifecycleListener` 接口契约与服务接入指南
- [../../plans/脚本容器生命周期回收任务书.md](../../plans/脚本容器生命周期回收任务书.md) —— 实施计划

## 与其他域的关系

- **cloud 域**：CloudService 是第一个接入生命周期回收的服务（OnStop Lua 快路径 + Java 层兜底），契约见 [../cloud/contracts.md](../cloud/contracts.md)
- **luaapi 域**：sleep/interrupt/exitScript 等挂起点语义（interrupt = sleep 挂起点抛 `error("interrupt")`）
