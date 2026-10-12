# 桥接层契约

> 文档版本 v1.0（2026-09-18，文档三层化重组新建）
> 本域权威范围：Lua↔Java 互操作的对外契约——LuaEnvContributor 接口与生效点、Java↔Lua 类型包装规则、thread 挂起协议与状态锁、桥接域注入矩阵、index()/call() 双闸门；机制原理与设计取舍见 [./design.md](./design.md)，三件套方案存档见 [./BASIC_BRIDGE_PLAN.md](./BASIC_BRIDGE_PLAN.md)。

## 1. LuaEnvContributor 接口契约

本地服务注入为 Lua 全局对象后回调 `contribute`，允许服务自行向 Lua 环境注册全局函数/常量（如 luajava 的全局 import）。

| 契约项 | 内容 | 对码 |
|---|---|---|
| 接口签名 | `interface LuaEnvContributor { fun contribute(ctx: LuaContext) }`，调用时持有对应 lua_State 的访问权 | （`autolua2\engine\src\main\java\com\autolua\engine\base\LuaEnvContributor.kt:11`，方法 `:13`） |
| 生效点①：root 主引擎 | `pushLocalServices` 逐服务包装注入后，检查**原始实例**是否实现该接口（CLASS 型服务经 JavaObjectWrapper 包装后自身不再是 LuaEnvContributor，故必须查 raw），是则 `contribute(luaContext)`，异常走 onError 不中断其余服务 | （`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:206`，raw 判定 `:215`，异常隔离 `:216-221`） |
| 生效点②：IDE 沙箱 | `injectService` 包装注入同名全局变量后，`service instanceof LuaEnvContributor` 则 `contribute(sandbox)`，行为与主引擎一致 | （`autolua2\engine\src\main\java\com\autolua\engine\ide\LuaEngineExecutor.java:366`，判定与调用 `:383-386`） |
| 插件贡献（附加通道） | 已加载插件实现 LuaEnvContributor 的，经 `PluginManager.contributeLua` 向每个新建 LuaContext 注入；插件晚于 context 加载的需重建 context 生效；沙箱侧由 app 层在插件 loadAll 后调用 `contributePlugins()` 手动触发 | （`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:229`；`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java:328-330`；`autolua2\engine\src\main\java\com\autolua\engine\ide\LuaEngineExecutor.java:343`） |

现存实现者（均为 extension 各域库）：JavaBridgeLib（luajava 全局 import）、MediaLib、PluginLib、CompatLib、UiLib、OcrLib、ImeLib、MySqlLib、AppLib、DeviceLib、CryptoLib、FileLib、EventLib（`autolua2\engine\src\main\java\com\autolua\engine\extension\bridge\JavaBridgeLib.kt:24` 及 extension\ 下各 Lib；CompatLib 注册顺位铁律见 ../../01-架构总览.md §8）。

设计取舍细节见 ./BASIC_BRIDGE_PLAN.md §2。

## 2. 类型包装规则

### 2.1 Java → Lua（pushValue 分发表）

| Java 值形态 | Lua 形态 | 对码 |
|---|---|---|
| `null` | pushNil | （`autolua2\engine\src\main\java\com\autolua\engine\base\LuaContextImplement.kt:353-356`） |
| 基本类型/装箱数字与布尔 | Lua integer/number/boolean（Int→Long、Float→Double 窄化） | （`autolua2\engine\src\main\java\com\autolua\engine\base\LuaContextImplement.kt:361-380`） |
| `String` / `ByteArray` | string 直推 | （`autolua2\engine\src\main\java\com\autolua\engine\base\LuaContextImplement.kt:381-382`） |
| `Class<*>`（枚举类） | name→ordinal 映射表 | （`autolua2\engine\src\main\java\com\autolua\engine\base\LuaContextImplement.kt:383-386`、`342-350`） |
| `Class<*>`（非枚举） | `LuaClassWrapper`（类包装 userdata） | （`autolua2\engine\src\main\java\com\autolua\engine\base\LuaContextImplement.kt:388`） |
| `LuaObjectAdapter` 实例 | 直推（不再二次包装） | （`autolua2\engine\src\main\java\com\autolua\engine\base\LuaContextImplement.kt:392-393`） |
| `Map<*,*>` | table（key toString） | （`autolua2\engine\src\main\java\com\autolua\engine\base\LuaContextImplement.kt:394-401`） |
| `List<*>` | 数组表（index 从 1 起） | （`autolua2\engine\src\main\java\com\autolua\engine\base\LuaContextImplement.kt:402-411`） |
| `interface` 实例 | `JavaObjectWrapper(接口, 实例, methodCache)` | （`autolua2\engine\src\main\java\com\autolua\engine\base\LuaContextImplement.kt:412-413`） |
| 无方法数据类 | 字段展开为 table（pushData） | （`autolua2\engine\src\main\java\com\autolua\engine\base\LuaContextImplement.kt:414-415`、`327-340`） |
| 数组 | 数组表（pushArray） | （`autolua2\engine\src\main\java\com\autolua\engine\base\LuaContextImplement.kt:416-417`、`316-325`） |
| 其余对象 | `JavaObjectWrapper`（反射调用具体类方法） | （`autolua2\engine\src\main\java\com\autolua\engine\base\LuaContextImplement.kt:419`） |
| `Function<*>` | `KotlinFunctionWrapper` 包装（可调用 userdata） | （`autolua2\engine\src\main\java\com\autolua\engine\base\LuaContextImplement.kt:291-293`） |
| `LuaObjectAdapter` 入栈统一通道 | objectCache.put 换取 adapter id → JNI `pushLuaObjectAdapter(nativeLua, id)` 压 userdata | （`autolua2\engine\src\main\java\com\autolua\engine\base\LuaContextImplement.kt:281-289`、`:531`） |

### 2.2 Lua → Java（toValue 转换表）

| Lua 值 → 目标类型 | 规则 | 对码 |
|---|---|---|
| 基本类型（int/float/byte/short） | 经 toLong/toDouble 窄化 | （`autolua2\engine\src\main\java\com\autolua\engine\base\LuaContextImplement.kt:97-110`） |
| `Char` | string 且长度必须为 1，否则抛 LuaTypeError | （`autolua2\engine\src\main\java\com\autolua\engine\base\LuaContextImplement.kt:104-108`） |
| 枚举 | 按 Lua 整数 ordinal 取 enumConstants | （`autolua2\engine\src\main\java\com\autolua\engine\base\LuaContextImplement.kt:114-118`） |
| USERDATA → 目标类 | 解包 `JavaObjectWrapper.obj` / `LuaClassWrapper.obj`，isInstance 校验失败抛错 | （`autolua2\engine\src\main\java\com\autolua\engine\base\LuaContextImplement.kt:120-132`） |
| TABLE | table2JavaObj：Map / List / 数组 / 无参构造+字段 Bean | （`autolua2\engine\src\main\java\com\autolua\engine\base\LuaContextImplement.kt:133-135`、`151-187`） |
| `Object` 形参兜底装箱 | string/boolean/number 按包装类型转换（Map.get(Object) 等签名） | （`autolua2\engine\src\main\java\com\autolua\engine\base\LuaContextImplement.kt:136-145`） |
| 遍历中的索引归一化 | `absIdx` 相对索引 → 绝对索引；嵌套表遍历中 pushNil/pop 移栈后相对索引失效曾致 SIGSEGV，**勿回退** | （`autolua2\engine\src\main\java\com\autolua\engine\base\LuaContextImplement.kt:197-199`；陷阱见 ../../06-开发铁律与踩坑手册.md §1.2） |

### 2.3 JavaObjectWrapper 语义（实例对象包装）

| 钩子 | 契约 | 对码 |
|---|---|---|
| `index` | `findMethod(aClass, name) != null` 返回 2（存在方法，Lua 侧生成闭包），否则 0 | （`autolua2\engine\src\main\java\com\autolua\engine\base\JavaObjectWrapper.kt:27-30`） |
| `newIndex` | 反射写 public 字段 | （`autolua2\engine\src\main\java\com\autolua\engine\base\JavaObjectWrapper.kt:32-38`） |
| `call` 参数起点 | 冒号调用 `obj:m(a)` 栈首 self 跳过（argStart=2），点调用 `obj.m(a)` 从 1 起；index 超栈顶视为未传参取 defaultValue | （`autolua2\engine\src\main\java\com\autolua\engine\base\JavaObjectWrapper.kt:48-49`、`71`、缺省值 `:16-26`） |
| `call` vararg | 尾参数组收集剩余全部 Lua 实参 | （`autolua2\engine\src\main\java\com\autolua\engine\base\JavaObjectWrapper.kt:57-68`） |
| `call` 重载解析 | `pickOverload` 按实参数量与 Lua 值类型逐参打分（scoreArg），取最高分；全部不适配回退首个同名方法 | （`autolua2\engine\src\main\java\com\autolua\engine\base\JavaObjectWrapper.kt:93-126`、打分 `:129-163`） |
| `call` 返回值 | `Object[]`（声明或运行时 component==Object，覆盖泛型擦除）展开为 Lua 多返回值；其余 pushValue 单返回 | （`autolua2\engine\src\main\java\com\autolua\engine\base\JavaObjectWrapper.kt:79-89`） |
| `release` | obj 实现 `Releasable` 时回调 `onRelease()` | （`autolua2\engine\src\main\java\com\autolua\engine\base\JavaObjectWrapper.kt:171-175`） |

### 2.4 KotlinFunctionWrapper 语义

包装 `Function<*>` 仅支持直接调用（`invoke` 反射）：按 invoke 方法签名逐参 `toValue`（栈 index 从 2 起），结果 pushValue 返回 1 个值；index/newIndex/call 未实现。invoke 方法筛选规则：`isRightInvoke` 排除首参为 Object 的桥接合成方法（`autolua2\engine\src\main\java\com\autolua\engine\base\KotlinFunctionWrapper.kt:20-31`、`:6`、`:38-53`）。

### 2.5 MethodCache 语义

| 方法 | 契约 | 对码 |
|---|---|---|
| `findMethod(clazz, name)` | 键 `clazz.name + ":" + name` 缓存首个同名方法，未命中时遍历 clazz.methods | （`autolua2\engine\src\main\java\com\autolua\engine\base\MethodCache.kt:9-22`） |
| `findMethods(clazz, name)` | 同名方法全集缓存（重载解析 pickOverload 用），首次查询后缓存 | （`autolua2\engine\src\main\java\com\autolua\engine\base\MethodCache.kt:25-33`） |

缓存实例归属：LuaContextImplement 默认自建（`autolua2\engine\src\main\java\com\autolua\engine\base\LuaContextImplement.kt:12-13`）；root 主引擎单例（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:192`）；沙箱单例（`autolua2\engine\src\main\java\com\autolua\engine\ide\LuaEngineExecutor.java:64`）；JavaBridgeLib 自持（`autolua2\engine\src\main\java\com\autolua\engine\extension\bridge\JavaBridgeLib.kt:26`）。

### 2.6 ObjectCache 语义（adapter id ↔ Java 实例）

- 接口四方法 `put/get/remove/clear`（`autolua2\engine\src\main\java\com\autolua\engine\base\ObjectCache.kt:3-7`）。
- 实现 `ObjectCacheImp`：`LongSparseArray` + `ReentrantReadWriteLock`；id 从 1 自增，冲突自旋换号（`autolua2\engine\src\main\java\com\autolua\engine\base\ObjectCacheImp.kt:9-36`、锁 `:11`）。
- 与 C++ 的配合：userdata 内存保存 adapterId，`__gc` 触发 Java 侧 `release(id)` → `objectCache.remove`（`autolua2\engine\src\main\cpp\base\lua_context.cpp:529-531`；`autolua2\engine\src\main\java\com\autolua\engine\base\LuaContextImplement.kt:53-59`）。
- 错误类型契约：`LuaError` 为桥接层运行时异常基类（Serializable）（`autolua2\engine\src\main\java\com\autolua\engine\base\LuaError.kt:5`）；类型不匹配抛子类 `LuaTypeError`（陷阱见 ../../06-开发铁律与踩坑手册.md §1.1）。

## 3. thread 多线程契约（yield 编码与状态锁）

### 3.1 注入与常量

| 契约项 | 内容 | 对码 |
|---|---|---|
| 注入点 | `createLuaContext` 内 `luaL_openlibs` 之后 `injectThreadLib(L)` 注册全局 `thread` 表（14 个函数） | （`autolua2\engine\src\main\cpp\base\lua_context.cpp:516-518`；`autolua2\engine\src\main\cpp\base\thread_lib.cpp:477-480`、函数表 `:459-475`） |
| 挂起返回编码 | `nativeResume` 返回 `(tag<<32)\|payload`：tag 0=yield、1=done、2=error；yield 时 payload 为 32 位编码 | （`autolua2\engine\src\main\cpp\base\thread_lib.cpp:8-9`、`:36-38`、`:149-151`、`:180-192`） |
| yield 值编码 | `encodeYield(op, value) = (op<<24) \| (value & 0x00FFFFFF)`；`OP_SLEEP=1`、`OP_LOCK=2`；value 上限 `YIELD_VALUE_MASK=0x00FFFFFF` | （`autolua2\engine\src\main\cpp\base\thread_lib.cpp:52-54`、`:40-41`、`:49-50`） |
| 挂起点 | `thread.sleep(ms)` 与 `thread.acquire(lockId)` 均 push 编码整数后 `lua_yield`；主线程调用直接 luaL_error | （`autolua2\engine\src\main\cpp\base\thread_lib.cpp:252-261`、`:280-288`、主线程拦截 `:256-258`/`:282-284`） |

### 3.2 per-state 递归锁

- 每个 LuaContext 创建时分配 `std::recursive_mutex` 存入 `LuaContext.mutex`（`autolua2\engine\src\main\cpp\base\lua_context.cpp:523`）。
- 主 worker 执行入口（LuaInterpreter run/lSleep）与协程 resume 都持锁：`lockLuaState/unlockLuaState` 经 `stateMutex(L)` 取锁（`autolua2\engine\src\main\cpp\base\thread_lib.cpp:68-75`；声明 `autolua2\engine\src\main\cpp\base\thread_lib.h:22-24`）。
- 等待必须先解锁：`thread.join` 阻塞等待前 `unlockLuaState`、返回后重新加锁，否则协程拿不到锁死锁（`autolua2\engine\src\main\cpp\base\thread_lib.cpp:319-334`）。
- 销毁顺序：`cleanupThreadTasks(L)` → `lua_close(L)` → `delete mutex`（`autolua2\engine\src\main\cpp\base\lua_context.cpp:548-550`）。

### 3.3 Java 桥与任务生命周期

- Java 桥 `object ThreadLib`（`autolua2\engine\src\main\java\com\autolua\engine\extension\thread\ThreadLib.kt:22`）：JNI `nativeResume(luaPtr, ref, taskId): Long`（`:27`）；C 侧静态绑定 begin/submit/joinBlocking/stop/stopAll/createLock/releaseLock/remove 八方法（`autolua2\engine\src\main\cpp\base\thread_lib.cpp:102-120`）。
- 两阶段启动：Java `begin()` 先登记 taskId → C 侧注册 co/taskId/ref 映射 → `submit()` 提交池线程；先 submit 会因映射未注册直接 ERROR（`autolua2\engine\src\main\cpp\base\thread_lib.cpp:210-247`；`autolua2\engine\src\main\java\com\autolua\engine\extension\thread\ThreadLib.kt:87-106`）。
- resume 循环：首次 resume 协程栈为 `[func, args...]`；非首次先 pop 上次 yield 残留的 1 个编码整数（`autolua2\engine\src\main\cpp\base\thread_lib.cpp:167-179`）。
- 共享数据：registry `thread_shared` 表统一挂主线程 state（同 state 协程天然共享），`atomicInc` 在状态锁内读改写（`autolua2\engine\src\main\cpp\base\thread_lib.cpp:126-144`、`:437-455`）。
- 停止/清理：`stopAllThreadTasks`（引擎 interrupt 时停该 state 全部任务）、`cleanupThreadTasks`（state 销毁清映射与 shared ref）（`autolua2\engine\src\main\cpp\base\thread_lib.cpp:505-513`、`:515-536`）；取消任务由 Java 侧 `nativeUnref` 释放 registry ref，join 不得重复 unref 损坏 freelist（`:197-205`、`:369-373`）。

Lua 侧 API 面以 ../../02-Lua-API规范.md §11 为准；设计取舍细节见 ./BASIC_BRIDGE_PLAN.md §3。

## 4. 注入矩阵（桥接域注册点）

| Lua 全局名 | Java 实现 | 暴露接口 | 注册点 | 环境贡献 |
|---|---|---|---|---|
| `http` | `NetLib` | `INetLib` | root 主引擎本地注册（`autolua2\engine\src\main\java\com\autolua\engine\core\root\Server.kt:146-147`） | 无（table 参数契约见 §2.2） |
| `luajava` | `JavaBridgeLib` | `IJavaBridge` | root 主引擎本地注册（`autolua2\engine\src\main\java\com\autolua\engine\core\root\Server.kt:148-149`） | 注册全局 `import` / `importClass`（`autolua2\engine\src\main\java\com\autolua\engine\extension\bridge\JavaBridgeLib.kt:62-67`） |
| `LuaEngine` | `LuaEngineLib` | `ILuaEngineLib` | root 主引擎本地注册（`autolua2\engine\src\main\java\com\autolua\engine\core\root\Server.kt:150-151`） | 无 |
| `thread` | 无 Java 服务对象 | —（C 静态桥） | C++ `createLuaContext` 注入全局 `thread` 表（`autolua2\engine\src\main\cpp\base\lua_context.cpp:518`） | 无 |

时序与生效点约束：

- 注册入口为 `Builder.addLocalService` 三重载，**第三参必须是 interface**（具体类会致 root 进程 Proxy 构造启动即崩）；接口必须写入 `mInterface` 供包装使用（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:391-415`、mInterface `:412-414`；陷阱 ../../06-开发铁律与踩坑手册.md §1.5）。
- root 主引擎注入时序：C++ 原生注入（injectOther/display/input/images）完成后 JNI 回调 `postNativeInject` → `pushLocalServices`（Java 服务必须在原生图色之后，否则 compat 包装捕获到 nil）（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:256-261`；全链顺序见 ../../01-架构总览.md §6）。
- CLASS 型服务惰性实例化：优先 `(LuaContext)` 构造器，其次无参构造器（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:180`；JavaBridgeLib/LuaEngineLib 均提供两形态构造，`autolua2\engine\src\main\java\com\autolua\engine\extension\bridge\JavaBridgeLib.kt:29-33`、`autolua2\engine\src\main\java\com\autolua\engine\extension\bridge\LuaEngineLib.kt:14-17`）。
- IDE 沙箱注册点：`registerService(name, service[, thisInterface])` 注册即注入常驻沙箱（自动探测暴露接口，无接口按具体类包装）（`autolua2\engine\src\main\java\com\autolua\engine\ide\LuaEngineExecutor.java:295-313`、探测 `:399-401`、包装注入 `:366-382`）。
- 跨进程铁律：需要返回 Java 对象给 Lua 的域服务必须在 Lua 所在进程本地注册（root 进程 Server.kt / 沙箱 LuaEngineExecutor）（../../06-开发铁律与踩坑手册.md §1.4）。
- 完整 14 域矩阵与 LuaEnvContributor 全局注入表见 ../../01-架构总览.md §8。

## 5. index()/call() 双闸门

Lua 对 userdata 的方法调用**先经 index() 判名、再经 call() 分发**，两道闸门缺一不可：

1. 元表层：`LuaObjectAdapter` 类元表挂 `__index=indexMethod`、`__gc=release`、`__call=invokeMethod`（`autolua2\engine\src\main\cpp\base\lua_context.cpp:527-531`）。
2. 第一闸门 index：C++ `indexMethod` 调 Java `adapter.index(L)`，返回值语义 0=不存在 / 1=已压结果值 / 2=方法存在（此时闭包捕获 self+方法名 pushcclosure）（`autolua2\engine\src\main\cpp\base\lua_context.cpp:451-474`）。
3. 第二闸门 call：Lua 调用生成的闭包后经 `callMethod` 分发到 `adapter.call(L, name)`（`autolua2\engine\src\main\java\com\autolua\engine\base\LuaContextImplement.kt:46-49`）。
4. 各包装器 index 实现：JavaObjectWrapper 方法存在返回 2（`autolua2\engine\src\main\java\com\autolua\engine\base\JavaObjectWrapper.kt:27-30`）；LuaClassWrapper `new` 语法糖返回 2、静态字段读取返回 1、方法存在返回 2（`autolua2\engine\src\main\java\com\autolua\engine\extension\bridge\LuaClassWrapper.kt:38-53`）；JavaBridgeLib 匿名全局函数 index 恒 0（`autolua2\engine\src\main\java\com\autolua\engine\extension\bridge\JavaBridgeLib.kt:90`）。

**契约红线**：新增可调用方法必须 index()、call() 两处同步加分支——只加 call() 会得到"看似注册实际 nil"（../../06-开发铁律与踩坑手册.md §1.3；根索引 ../../01-架构总览.md §10）。
