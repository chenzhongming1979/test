# 引擎核心（engine-core）

> 文档版本 v1.0（2026-09-18，文档三层化重组新建）
> 本域权威范围：引擎生命周期 / 执行调度 / Lua worker 状态机 / 截图管线（`autolua2\engine` 的 core、cpp core/base、display/projection、display/stream 与构建脚本）；截图共享内存帧协议与注入降级链归 ipc 域文档，本域不展开。

## 本域文档

| 文档 | 内容 | 何时读 |
|---|---|---|
| [contracts.md](./contracts.md) | Builder 方法签名契约、执行控制语义（execute/interrupt/pause/resume/waitForStop）、worker 状态机状态集、protoc 生成契约、CMake ABI 矩阵、线程模型约束 | 改动引擎对外接口、JNI 签名、构建产物（.so/.proto）或并发行为前，先对契约 |
| [design.md](./design.md) | 架构总述、worker 调度与执行管线、LuaContext 注入管线、截图管线、数据流、代码映射表 | 需要理解或修改引擎内部机制（调度/状态机/截图）时 |
| [INTEGRATION_PLAN.md](./INTEGRATION_PLAN.md) | 方案一~八设计全案存档（§2 截图管线、§8 Android 适配总表、§9 性能优化清单与本域相关） | 做版本适配、性能优化或追溯某方案的设计决策时 |

## 全局真相源交叉引用

| 主题 | 权威位置 | 说明 |
|---|---|---|
| 进程模型（app / root 引擎进程 / root daemon） | [../../01-架构总览.md](../../01-架构总览.md) §4 | 引擎运行于哪个进程、Local 与 root 两种形态的关系 |
| engine 模块地图（Java 层 / C++ 层） | [../../01-架构总览.md](../../01-架构总览.md) §5 / §6 | 全模块索引；本域只深挖 core 与 display 相关条目 |
| Lua 运行环境与服务注入矩阵 | [../../01-架构总览.md](../../01-架构总览.md) §8 | 三个执行环境的注入注册点与注入顺序矩阵（跨环境全局名一致性铁律） |
| 脚本执行数据流 | [../../01-架构总览.md](../../01-架构总览.md) §9.1 | 全链路视角（含 root 引擎通道）；本域 design.md 只讲进程内机制 |
| 截图管线数据流 | [../../01-架构总览.md](../../01-架构总览.md) §9.2 | 权威总览；域内细节见本域 [INTEGRATION_PLAN.md](./INTEGRATION_PLAN.md) §2 |
| 输入注入降级链 | [../../01-架构总览.md](../../01-架构总览.md) §9.3 | 归 ipc 域文档展开，本域不重复 |
| 节点查找 / IDE 图色 / 事件总线 | [../../01-架构总览.md](../../01-架构总览.md) §9.4 / §9.5 / §9.6 | 分别归节点域、ipc 域、桥接域 |
| 关键机制索引 | [../../01-架构总览.md](../../01-架构总览.md) §10 | 全仓机制速查 |
| 构建与自测 | [../../05-构建与自测指南.md](../../05-构建与自测指南.md) | 环境要求、日常构建、模拟器联调、全量自测 |
| 开发铁律与踩坑 | [../../06-开发铁律与踩坑手册.md](../../06-开发铁律与踩坑手册.md) | 跨域铁律（对码纪律、跨环境全局名一致等） |

## 代码入口

| 模块 | 路径 |
|---|---|
| 引擎接口 + Builder（契约） | `autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngine.kt` |
| 本地引擎实现（JNI 桥、服务注入、执行控制） | `autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt` |
| 线程安全引擎代理（单线程串行化） | `autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineProxy.kt` |
| 代码/资源/消息 composite | `autolua2\engine\src\main\java\com\autolua\engine\core\composite\`（CodeProvider.kt / ResourceProvider.kt / MessageObserver.kt） |
| C++ 引擎服务（worker 调度器、JNI 实现） | `autolua2\engine\src\main\cpp\engine.cpp`（类定义 `engine.h`） |
| Lua worker 状态机 | `autolua2\engine\src\main\cpp\core\ALEState.h`、`LuaInterpreter.h/.cc` |
| Lua 上下文（lua_State 互斥、Java 桥接） | `autolua2\engine\src\main\cpp\base\lua_context.h`、`lua_context.cpp` |
| 截图采集服务（App 模式常驻） | `autolua2\engine\src\main\java\com\autolua\engine\display\projection\CaptureScreenService.java` |
| 截图提供者管理/Root 通道 | `autolua2\engine\src\main\java\com\autolua\engine\display\projection\ScreenshotProviderManager.java`、`RootScreenshotProvider.java` |
| 投屏推流控制器 | `autolua2\engine\src\main\java\com\autolua\engine\display\stream\ScreenStreamController.java` |
| 构建脚本（protoc / CMake / ABI） | `autolua2\engine\build.gradle.kts`、`autolua2\engine\src\main\cpp\CMakeLists.txt` |
| proto 源文件 | `autolua2\engine\src\main\proto\interaction.proto`、`Authorization.proto` |

## 何时读本文档

- 要接入引擎：Builder 配置本地服务 / 环境变量 / CodeProvider / Display / InputManager 时（先读 contracts.md §1）。
- 要理解或改动脚本执行控制：execute / interrupt / pause / resume 的信号语义与生效时机（contracts.md §2 + design.md 核心机制）。
- 要排查引擎启动、停止、状态回调异常：worker 状态机状态集与回调链（contracts.md §3）。
- 要修改截图采集（MediaProjection/ImageReader/旋转重建）或投屏推流（h264/jpeg 双 codec）时（design.md 截图管线；帧协议与注入链勿在本域改，归 ipc 域）。
- 构建失败涉及 protoc 下载/生成、CMake 目标、ABI 缺失时（contracts.md §4/§5，另读 [../../05-构建与自测指南.md](../../05-构建与自测指南.md)）。
- 做版本适配或性能优化时，先查本域 [INTEGRATION_PLAN.md](./INTEGRATION_PLAN.md) §8 / §9 再动手。
