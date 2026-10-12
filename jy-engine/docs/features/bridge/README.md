# Lua-Java 桥接（bridge）

> 文档版本 v1.0（2026-09-18，文档三层化重组新建）
> 本域权威范围：Lua↔Java 互操作机制——LuaContext 栈原语与类型双向包装、luajava 反射桥（import/bindClass/new）、LuaEngine.* java 桥、thread 同 state 协程多线程原语、LuaEnvContributor 环境贡献机制（`autolua2\engine` 的 base、extension\bridge、cpp\base 相关文件）；HTTP 请求语义归 luaapi 域（本域仅涉及其注册点），IDE 沙箱执行链与跨进程通道归 ipc 域。

## 本域文档

| 文档 | 内容 | 何时读 |
|---|---|---|
| [contracts.md](./contracts.md) | LuaEnvContributor 接口契约与两生效点、Java↔Lua 类型包装规则（JavaObjectWrapper/KotlinFunctionWrapper/MethodCache 语义）、thread yield 编码 (op<<24)\|value 与 per-state 递归锁、注入矩阵（http/luajava/LuaEngine/thread 注册点）、index()/call() 双闸门 | 改动桥接接口签名、类型转换规则、thread 挂起协议或新增可被 Lua 调用的方法前，先对契约 |
| [design.md](./design.md) | 架构总述（三层结构）、反射桥机制（import/importClass/方法缓存）、多线程模型（同 state 协程+递归锁）、环境贡献机制生命周期、代码映射表、演进与存档 | 需要理解或修改桥接内部机制（反射调用链、协程调度、贡献时序）时 |
| [BASIC_BRIDGE_PLAN.md](./BASIC_BRIDGE_PLAN.md) | luajava/thread/http 三件套设计全案存档（§0 通用底座、§2 luajava+LuaEngine、§3 thread、§4 注入矩阵），已随 3b3ef81 交付 | 追溯三件套设计取舍、当初方案对比与验证结论时 |

## 全局真相源交叉引用

| 主题 | 权威位置 | 说明 |
|---|---|---|
| Lua 环境注入顺序 | [../../01-架构总览.md](../../01-架构总览.md) §6 | thread/json/cv 等 C++ 注入与 Java pushLocalServices 在全链中的先后次序（compat 依赖图色原生注入先行）；本域只深挖桥接相关步骤 |
| 服务注入矩阵（含 LuaEnvContributor 注入表） | [../../01-架构总览.md](../../01-架构总览.md) §8 | 全部域服务的全局名/接口/注册点矩阵与 contribute 注册的全局函数清单；本域 contracts.md §4 只展开桥接域四个注册点 |
| index()/call() 双闸门 | [../../01-架构总览.md](../../01-架构总览.md) §10 | 关键机制索引中的"LuaObjectAdapter 桥接"条目：方法调用先经 index() 判名（返回 2 才生成闭包）再 call() 分发 |
| Lua-Java 桥接层 6 陷阱 | [../../06-开发铁律与踩坑手册.md](../../06-开发铁律与踩坑手册.md) §1 | 类型读取抛错、相对索引勿回退、双闸门两处同步、跨进程不能返回 LuaObjectAdapter、interface 必传、C++ create() 拦截 ptr==0 |
| Java 桥 API 面（Lua 侧用法） | [../../02-Lua-API规范.md](../../02-Lua-API规范.md) §12 | import/importClass/luajava.bindClass/new、LuaEngine.* 的 Lua 侧调用规范 |
| 线程 API 面（Lua 侧用法） | [../../02-Lua-API规范.md](../../02-Lua-API规范.md) §11 | thread.start/sleep/lock/acquire/join/共享表/atomicInc 的 Lua 侧调用规范 |
| 三件套设计存档 | [./BASIC_BRIDGE_PLAN.md](./BASIC_BRIDGE_PLAN.md) | luajava/thread/http 设计全案（已交付）；本域两文档与其重叠处只讲"现状契约/机制"，设计取舍细节指向存档 |

## 代码入口

| 模块 | 路径 |
|---|---|
| Lua 栈原语接口（ValueType/CodeMode/栈常量） | `autolua2\engine\src\main\java\com\autolua\engine\base\LuaContext.kt` |
| Lua 栈原语 JNI 实现（toValue/pushValue/absIdx） | `autolua2\engine\src\main\java\com\autolua\engine\base\LuaContextImplement.kt` |
| 环境贡献接口（两生效点） | `autolua2\engine\src\main\java\com\autolua\engine\base\LuaEnvContributor.kt` |
| Java 对象包装（反射调用/重载打分） | `autolua2\engine\src\main\java\com\autolua\engine\base\JavaObjectWrapper.kt` |
| Kotlin 函数包装（invoke 反射） | `autolua2\engine\src\main\java\com\autolua\engine\base\KotlinFunctionWrapper.kt` |
| 方法缓存（单方法 + 重载全集） | `autolua2\engine\src\main\java\com\autolua\engine\base\MethodCache.kt` |
| 对象缓存（adapter id ↔ Java 实例） | `autolua2\engine\src\main\java\com\autolua\engine\base\ObjectCache.kt`、`ObjectCacheImp.kt` |
| 桥接错误类型 | `autolua2\engine\src\main\java\com\autolua\engine\base\LuaError.kt`（子类 `LuaTypeError.kt`） |
| luajava 反射桥（bindClass/new/import 贡献） | `autolua2\engine\src\main\java\com\autolua\engine\extension\bridge\JavaBridgeLib.kt`（接口 `IJavaBridge.kt`） |
| Java 类包装器（new/静态调用/静态字段） | `autolua2\engine\src\main\java\com\autolua\engine\extension\bridge\LuaClassWrapper.kt` |
| LuaEngine.* java 桥（getContext/loadApk） | `autolua2\engine\src\main\java\com\autolua\engine\extension\bridge\LuaEngineLib.kt`（接口 `ILuaEngineLib.kt`） |
| thread Java 桥（任务池/joinBlocking） | `autolua2\engine\src\main\java\com\autolua\engine\extension\thread\ThreadLib.kt` |
| C++ Lua 上下文（createLuaContext/元表桥/per-state 锁） | `autolua2\engine\src\main\cpp\base\lua_context.h`、`lua_context.cpp` |
| C++ thread 库（yield 编码/协程 resume） | `autolua2\engine\src\main\cpp\base\thread_lib.h`、`thread_lib.cpp` |
| Lua 环境安装器抽象 | `autolua2\engine\src\main\cpp\LuaEnvironmentSetup.h` |
| root 主引擎注册点（服务注册+注入时序） | `autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt`、`core\root\Server.kt` |
| IDE 沙箱注册点 | `autolua2\engine\src\main\java\com\autolua\engine\ide\LuaEngineExecutor.java` |

## 何时读本文档

- 要新增一个可被 Lua 调用的 Java 方法/包装器：先对 contracts.md §2 类型包装规则与 §5 index()/call() 双闸门（两处必须同步加分支），再读 design.md §2 反射桥机制。
- 要新增一个向 Lua 注册全局函数/常量的服务：实现 LuaEnvContributor，先读 contracts.md §1 两生效点（root 主引擎与 IDE 沙箱行为必须一致），时序细节见 design.md §4。
- 要改动 thread 库（挂起协议/锁策略/任务生命周期）：先对 contracts.md §3 的 yield 编码与递归锁契约，机制见 design.md §3，Lua 侧 API 以 [../../02-Lua-API规范.md](../../02-Lua-API规范.md) §11 为准。
- 要排查"方法看似注册实际 nil"、嵌套表转换崩溃、服务注入顺序问题：直接查 [../../06-开发铁律与踩坑手册.md](../../06-开发铁律与踩坑手册.md) §1 对应陷阱。
- 要追溯 luajava/thread/http 当初的设计方案与备选取舍：查存档 [./BASIC_BRIDGE_PLAN.md](./BASIC_BRIDGE_PLAN.md)。
