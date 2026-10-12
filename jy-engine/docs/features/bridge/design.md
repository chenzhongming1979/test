# 桥接层设计

> 文档版本 v1.0（2026-09-18，文档三层化重组新建）
> 本域权威范围：Lua↔Java 互操作的内部机制——三层架构与反射调用链、thread 同 state 协程调度模型、LuaEnvContributor 生命周期时序；对外契约以 [./contracts.md](./contracts.md) 为准，三件套方案存档见 [./BASIC_BRIDGE_PLAN.md](./BASIC_BRIDGE_PLAN.md)。改桥接层前必读 ../../06-开发铁律与踩坑手册.md §1。

## 1. 架构总述

桥接层自下而上分三层：

```
┌─ 服务贡献层：LuaEnvContributor（服务自注册全局）+ 注入矩阵（Server.kt / LuaEngineExecutor）
├─ 对象包装层：LuaObjectAdapter 元表桥 → JavaObjectWrapper / LuaClassWrapper / KotlinFunctionWrapper
│              （MethodCache 方法缓存 + ObjectCache id↔实例映射）
└─ 栈原语层：LuaContext（接口，含 ValueType/CodeMode/栈常量）
             → LuaContextImplement（JNI）→ lua_context.cpp（lua_State / 元表 / per-state 锁）
```

- 库加载：`LuaContextImplement` companion 静态块 `System.loadLibrary("engine_base")`（`autolua2\engine\src\main\java\com\autolua\engine\base\LuaContextImplement.kt:508`）；`engine_base` 由 cpp\base 产出，链接关系与"跨模块依赖方向 base ← extension"约束见 ../../01-架构总览.md §6。
- state 创建链：`LuaContextImplement.init` 调 external `createLuaContext`（`autolua2\engine\src\main\java\com\autolua\engine\base\LuaContextImplement.kt:22-26`）→ C++ 建 LuaContext 结构、`NewGlobalRef` 强引用 Java 实现防 GC（否则所有 Lua→Java 桥调用 SIGSEGV）、缓存 index/call/newIndex/invoke/release 五个方法 ID（`autolua2\engine\src\main\cpp\base\lua_context.cpp:503-511`）→ `luaL_newstate` + `luaL_openlibs` → 依次注入 thread/json/cv 三个 C 库 → 分配 per-state 递归锁 → extraspace 回填 context 指针（`autolua2\engine\src\main\cpp\base\lua_context.cpp:512-522`）。C++ 注入全链的上下游顺序（含 ROOT_DIR、autolua_vision、Display/InputManager、RPC 服务、LuaInterpreter、Java pushLocalServices）以 ../../01-架构总览.md §6 为权威。
- 元表桥：`LuaObjectAdapter` 类元表挂 `__index`/`__gc`/`__call` 三个 C 闭包，分别转发到 Java 侧 `indexMethod/callMethod/newIndexMethod/invokeMethod/release`（`autolua2\engine\src\main\cpp\base\lua_context.cpp:524-532`；转发入口 `autolua2\engine\src\main\java\com\autolua\engine\base\LuaContextImplement.kt:31-59`）。userdata 内存持有 adapterId，`__gc` 时经 `release(id)` 从 ObjectCache 摘除并回调 `Releasable.onRelease`。
- 扩展点：`LuaEnvironmentSetup.h` 定义环境安装器抽象（单虚方法 `setup(lua_State*)`），供上层在既有注入链之外安装自定义 Lua 环境（`autolua2\engine\src\main\cpp\LuaEnvironmentSetup.h:7-10`）。
- 错误传播：Java 侧异常经 `catchAndPushJavaThrowable` 转 Lua error（`autolua2\engine\src\main\cpp\base\lua_context.cpp`，index/call 各转发点）；类型不匹配抛 `LuaTypeError`（`LuaError` 子类，`autolua2\engine\src\main\java\com\autolua\engine\base\LuaError.kt:5`）。nil/类型不符直接抛错的静默中断陷阱见 ../../06-开发铁律与踩坑手册.md §1.1。

## 2. 反射桥机制（import/importClass/方法缓存）

### 2.1 两条入口

- **对象式 luajava**：`luajava.bindClass(name)` → `Class.forName` → 返回 `LuaClassWrapper`（`autolua2\engine\src\main\java\com\autolua\engine\extension\bridge\JavaBridgeLib.kt:35-38`）；`luajava.new(classObj, ...)` / `newInstance(name, ...)` → 遍历 declaredConstructors 按参数个数匹配实例化（`:40-60`）。
- **全局函数式 import**：`contribute(ctx)` 时经 `registerGlobalFunction` 把匿名 LuaObjectAdapter 设为全局 `import` / `importClass`，调用时 `pushBindClassResult` 将类包装以 userdata 直推（`autolua2\engine\src\main\java\com\autolua\engine\extension\bridge\JavaBridgeLib.kt:62-77`）；匿名函数约定栈布局 index 1 = adapter 本体、参数从 index 2 起（`:79-96`）。设计取舍细节见 ./BASIC_BRIDGE_PLAN.md §2。

### 2.2 LuaClassWrapper（类包装器）

- `index` 三分支：`new` 返回 2（构造语法糖）→ 静态字段优先读取返回 1（含枚举常量）→ `methodCache.findMethod` 命中返回 2（`autolua2\engine\src\main\java\com\autolua\engine\extension\bridge\LuaClassWrapper.kt:38-53`）。
- `call`：`new` 走 `callNew`（先按参数个数精确匹配，逐候选尝试参数转换；再以尾参数组构造器兜底）（`:64-66`、`:95-142`）；静态方法反射 `method.invoke(null, args)`，`Object[]` 返回展开多返回值（`:83-92`）。
- 实例对象经 pushValue 包装为 JavaObjectWrapper，与类包装器配合构成完整桥（`:17` 注释约定）。

### 2.3 方法缓存与重载解析

- `MethodCache` 以 `类名:方法名` 为键缓存首个同名方法（index 闸门用）与同名方法全集（重载解析用），避免每次调用遍历 clazz.methods（`autolua2\engine\src\main\java\com\autolua\engine\base\MethodCache.kt:9-33`）。
- 重载解析 `pickOverload` 打分制：先按实参数量过滤（vararg 方法接受 ≥形参-1 个），基础分 100 − 缺参惩罚，再逐参按 Lua 类型与目标类型的兼容度加分（如 string→String 6 分、number→int 5 分），全不适配（-100）淘汰，最终回退首个同名方法（`autolua2\engine\src\main\java\com\autolua\engine\base\JavaObjectWrapper.kt:93-163`）。
- 参数起点自适应：包装器在 call 入口探测栈首是否为自身（冒号调用传入的 self），自适应决定参数从 index 1 还是 2 起；index 超栈顶按基本类型零值/null 缺省（`autolua2\engine\src\main\java\com\autolua\engine\base\JavaObjectWrapper.kt:46-49`、`autolua2\engine\src\main\java\com\autolua\engine\extension\bridge\LuaClassWrapper.kt:74-77`）。
- 泛型擦除兜底：`Object[]` 多返回值展开按**运行时** component 判定，覆盖接口泛型擦除（INetLib 声明 Object 实际返回 Object[]）场景（`autolua2\engine\src\main\java\com\autolua\engine\base\JavaObjectWrapper.kt:76-87`）。

### 2.4 LuaEngine.* java 桥

`LuaEngineLib` 提供 `getContext`（app 进程用注入 Context；root 进程反射 `ActivityThread.currentApplication()`，失败返回 null）、`loadApk`（DexClassLoader 加载 apk 插件类，app 进程 optDir 用 codeCacheDir/luaplugin，root 进程回退 uid 0 可写的 /data/local/tmp）、`getVersion`（2.0）——懒人 LuaEngine.* 的轻量对齐（`autolua2\engine\src\main\java\com\autolua\engine\extension\bridge\LuaEngineLib.kt:14-56`）。

陷阱红线（改反射桥必读）：../../06-开发铁律与踩坑手册.md §1——尤其 §1.3 双闸门两处同步、§1.5 interface 必传。

## 3. 多线程模型（同 state 协程 + 递归锁）

### 3.1 设计动机

thread 原语选择**同 lua_State 协程方案**而非多 state：协程与主线程共享 `_G`/registry/闭包，语义完整且共享数据零拷贝；代价是所有执行必须串行持有 per-state 锁（`autolua2\engine\src\main\cpp\base\thread_lib.cpp:2-12` 头注释；契约见 ./contracts.md §3）。

### 3.2 执行管线

```
thread.start(func, ...)                     thread_lib.cpp:212（两阶段）
  → lua_newthread + registry ref 保活 + lua_xmove 转移 func/args
  → Java begin() 登记 taskId → C 侧注册映射 → submit() 提交池线程
Java 池线程 resume 循环                      ThreadLib.kt:96-106
  → nativeResume(luaPtr, ref, taskId)       thread_lib.cpp:150-195
  → lockLuaState → lua_resume → (tag<<32)|yield编码 → unlockLuaState
协程内挂起点
  thread.sleep(ms)    → push (OP_SLEEP<<24)|ms   → lua_yield   thread_lib.cpp:252-261
  thread.acquire(id)  → push (OP_LOCK<<24)|lockId → lua_yield   thread_lib.cpp:280-288
thread.join(taskId)（另一协程）
  → unlockLuaState 释放状态锁等待 → joinBlocking 轮询 → 取结果/错误 → lockLuaState
                                                                      thread_lib.cpp:319-374
```

- 两阶段启动的原因：先 submit 时池线程可能先于 C 侧 `gTaskCo` 注册执行 nativeResume，查不到 co 直接 ERROR（`autolua2\engine\src\main\cpp\base\thread_lib.cpp:209-211`）。
- resume 细节：首次 resume 协程栈为 `[func, args...]`；后续 resume 前 pop 上次 yield 残留的 1 个编码整数（`autolua2\engine\src\main\cpp\base\thread_lib.cpp:167-178`）。
- 主线程判定：Lua 5.4 无 `lua_ismainthread`，以 `lua_pushthread` 返回值判定（`autolua2\engine\src\main\cpp\base\thread_lib.cpp:59-64`）；主线程调用 thread.sleep/acquire 直接报错（会与状态锁死锁）。

### 3.3 锁与共享

- per-state `std::recursive_mutex` 在 createLuaContext 分配、destroy 时最后释放（`autolua2\engine\src\main\cpp\base\lua_context.cpp:523`、`:550`）；递归语义允许主 worker 入口（LuaInterpreter run/lSleep 经 `lockLuaState`，`autolua2\engine\src\main\cpp\base\thread_lib.h:22-24`）与协程同线程重入。
- 共享表：registry `thread_shared` 表 ref 统一挂**主线程 state**（协程与主 L 是不同 lua_State 指针但共享 registry，挂错则互不可见）（`autolua2\engine\src\main\cpp\base\thread_lib.cpp:122-144`）；`atomicInc` 在状态锁内读改写保证原子（`:437-455`）。
- 锁内 JNI 红线：nativeResume 持锁期间不做阻塞 JNI 调用；join/acquire 等待一律先解锁再等（`autolua2\engine\src\main\cpp\base\thread_lib.cpp:319-334`、`:280-288`）。

### 3.4 生命周期与清理

- 取消：`thread.stop` / `stopAllThreadTasks`（引擎 interrupt 时 Java 置 stopped 标志）→ 取消任务由 Java `nativeUnref` 释放 registry ref；join 侧检测到 cancelled/未知 taskId 时跳过 unref（避免损坏 Lua ref freelist）后 `eraseTask`（`autolua2\engine\src\main\cpp\base\thread_lib.cpp:304-316`、`:505-513`、`:369-373`、`:482-503`）。
- state 销毁：destroy 先 `cleanupThreadTasks`（清 shared ref 与该 state 任务映射）再 `lua_close` 再删锁（`autolua2\engine\src\main\cpp\base\lua_context.cpp:543-550`、`autolua2\engine\src\main\cpp\base\thread_lib.cpp:515-536`）。

## 4. 环境贡献机制（LuaEnvContributor 生命周期）

### 4.1 机制定位

服务注入解决"Lua 全局对象 = Java 服务实例"，LuaEnvContributor 解决"服务还需要附加大量的全局函数/常量"（如 luajava 的全局 import、compat 的懒人函数）——服务实现 `contribute(ctx)`，在自身注入为全局对象后拿到 LuaContext 自行注册（`autolua2\engine\src\main\java\com\autolua\engine\base\LuaEnvContributor.kt:3-13`）。

### 4.2 root 主引擎时序

```
Builder.addLocalService（三重载，接口写入 mInterface）   AutoLuaEngineOnLocal.kt:391-415
  → 引擎启动，C++ 完成原生注入（图色/输入/images…）
  → JNI 回调 postNativeInject(ptr)                       AutoLuaEngineOnLocal.kt:256
  → pushLocalServices(luaContext)                        AutoLuaEngineOnLocal.kt:206
      逐服务 wrapLocalService：CLASS 型经 newLocalServiceObj 惰性实例化
        （优先 (LuaContext) 构造器），非 adapter 包装为
        JavaObjectWrapper(mInterface, obj, methodCache)   AutoLuaEngineOnLocal.kt:180-204
      → push(adapter) + setGlobal(服务名)                 AutoLuaEngineOnLocal.kt:211-212
      → raw is LuaEnvContributor → raw.contribute(ctx)（异常 onError 不中断）:215-221
      → PluginManager.contributeLua（插件贡献附加通道）    AutoLuaEngineOnLocal.kt:226-232
```

时序要点：Java 服务注入必须在 C++ 原生注入之后——compat 包装层捕获 getColor/isColor 等依赖原生图色函数就绪，早注入会拿到 nil（`autolua2\engine\src\main\java\com\autolua\engine\core\AutoLuaEngineOnLocal.kt:252-255` 注释）。contribute 检查必须用**原始实例**：CLASS 型服务包装成 JavaObjectWrapper 后自身不再是 LuaEnvContributor（`:193-194` 注释、`:215`）。

### 4.3 IDE 沙箱时序

`registerService(name, service[, thisInterface])` 注册即注入常驻沙箱（接口自动探测，无接口按具体类包装）→ `injectService` 完成 JavaObjectWrapper 包装 + push + setGlobal + contribute（与主引擎行为一致）（`autolua2\engine\src\main\java\com\autolua\engine\ide\LuaEngineExecutor.java:295-313`、`:364-395`）。插件贡献由 app 层在插件 loadAll 后调 `contributePlugins()` 手动补发，插件后装需再次调用或重建沙箱（`:338-355`）。

### 4.4 一致性约束

两生效点行为必须一致（沙箱对齐主引擎是硬要求，`autolua2\engine\src\main\java\com\autolua\engine\ide\LuaEngineExecutor.java:288-290` 注释）；新增 contribute 的全局名须与根文档注入表同步，跨环境全局名一致性铁律见 ../../01-架构总览.md §8。

## 5. 代码映射表

| 机制 | 关键文件:行 |
|---|---|
| LuaContext 接口 / 栈常量 | `autolua2\engine\src\main\java\com\autolua\engine\base\LuaContext.kt:4`（MAX_STACK `:92`） |
| JNI 实现与类型转换 | `autolua2\engine\src\main\java\com\autolua\engine\base\LuaContextImplement.kt:12`（toValue `:95`、pushValue `:352`、absIdx `:197`） |
| 库加载 / pushLuaObjectAdapter | 同上 `:508` / `:531` |
| C++ state 创建与元表桥 | `autolua2\engine\src\main\cpp\base\lua_context.cpp:503`（元表 `:524-532`、per-state 锁 `:523`） |
| 环境安装器抽象 | `autolua2\engine\src\main\cpp\LuaEnvironmentSetup.h:7-10` |
| JavaObjectWrapper（重载打分） | `autolua2\engine\src\main\java\com\autolua\engine\base\JavaObjectWrapper.kt:9`（call `:45`、pickOverload `:93`） |
| KotlinFunctionWrapper | `autolua2\engine\src\main\java\com\autolua\engine\base\KotlinFunctionWrapper.kt:6`（invoke `:20`） |
| MethodCache / ObjectCache | `autolua2\engine\src\main\java\com\autolua\engine\base\MethodCache.kt:5`、`ObjectCacheImp.kt:9` |
| LuaEnvContributor 接口 | `autolua2\engine\src\main\java\com\autolua\engine\base\LuaEnvContributor.kt:11` |
| luajava 桥 / 类包装器 / LuaEngine 桥 | `autolua2\engine\src\main\java\com\autolua\engine\extension\bridge\JavaBridgeLib.kt:24`、`LuaClassWrapper.kt:19`、`LuaEngineLib.kt:14` |
| thread Java 桥 / C 库 | `autolua2\engine\src\main\java\com\autolua\engine\extension\thread\ThreadLib.kt:22`、`autolua2\engine\src\main\cpp\base\thread_lib.cpp`（encodeYield `:52`、nativeResume `:151`、threadStart `:212`、injectThreadLib `:477`） |
| root 注册点 / 注入时序 | `autolua2\engine\src\main\java\com\autolua\engine\core\root\Server.kt:146-151`、`core\AutoLuaEngineOnLocal.kt:206`（postNativeInject `:256`） |
| 沙箱注册点 | `autolua2\engine\src\main\java\com\autolua\engine\ide\LuaEngineExecutor.java:366`（registerService `:305`） |
| 插件贡献通道 | `autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java:328-330` |

## 6. 演进与存档

- **现状**：luajava/thread/http 三件套已按 [./BASIC_BRIDGE_PLAN.md](./BASIC_BRIDGE_PLAN.md) 设计全案交付（commit 3b3ef81）——§0 通用底座（JavaObjectWrapper 重载打分、toValue/pushValue 扩展、per-state 锁）、§1 http.request（table 契约与本域 §2.2 转换直接相关）、§2 luajava 反射桥 + LuaEngine.* java 桥、§3 thread 多线程原语、§4 注入矩阵、§5 验证与交付。追溯某项设计为何如此取舍（如两阶段启动、yield 编码选型、DexClassLoader optDir 兜底）时读对应章节。
- **演进注意**：新增桥接能力时优先复用既有契约（双闸门、interface 必传、两生效点一致、compat 最后注册），勿在包装器内新增全局副作用；改挂起协议/锁策略前先对 ./contracts.md §3 并在铁律手册补踩坑条目（../../06-开发铁律与踩坑手册.md §1）。
