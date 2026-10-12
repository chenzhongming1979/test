# 基础能力三件套实施计划（luajava 反射桥 / thread 多线程 / http.request）

> 生成日期：2026-09-12
> 目标：为脚本提供 Java 反射桥、Lua 多线程原语、luasocket 风格 HTTP 三项底层能力。
> 对应 API 缺口报告：../luaapi/API_GAP_LR.md / ../luaapi/API_GAP_FIREFLY.md。
>
> **✅ 实施状态（2026-09-12，提交 3b3ef81）**：本计划全部落地并构建验证通过。实际实现与本计划一致（差异：thread 多线程采用 C++ `base/thread_lib.cpp` + Java `extension/thread/ThreadLib.kt`，yield 编码 `(op<<24)|value`；per-state 递归锁挂在 `LuaContext.mutex`；冒烟脚本 `tools/bridge_test/main.lua`）。后续 16 大类扩展见 API_GAP_*.md"补齐批次记录"（0dc3a52）。模拟器实测待执行。

## 0. 通用底座（三项共用，先行）

1. **Lua table → Java Map/List 转换**（LuaContextImplement.table2JavaObj 扩展）：
   目标类型为 `java.util.Map` → LinkedHashMap<String,Object>（值递归转换：number/boolean/string 直取、嵌套 table 递归、LuaObjectAdapter 取 .obj）；
   目标类型为 `java.util.List` → ArrayList<Object>（连续数字键）。服务方法参数声明为 `Map<*,*>`/`List<*>` 即可接收 Lua 表。
2. **Java 多返回值**（JavaObjectWrapper.call 扩展）：方法返回 `Object[]` 时逐个 pushValue，实现 Lua 多返回值（如 http.request 的 body, status, headers）。
3. **LuaEnvContributor 机制**（AutoLuaEngineOnLocal / LuaEngineExecutor）：本地服务实例若实现 `LuaEnvContributor` 接口，注入为全局对象后回调 `contribute(LuaContext)`，允许服务自行注册全局函数（如 `import`）。

## 1. 特性③ http.request（luasocket 风格 HTTP）

- 新增 `engine/src/main/java/com/autolua/engine/extension/net/`：
  - `INetLib.kt`：`request(options: Map<*, *>): Any?`、`get(url): Any?`、`post(url, body): Any?`
  - `NetLib.kt`：HttpURLConnection 实现。
- Lua API：
  ```lua
  local body, code, headers = http.request{
    url="http://x/api", method="POST",            -- method 默认 GET
    headers={["Content-Type"]="application/json"}, -- 可选
    body='{"a":1}', timeout=10000,                 -- 可选，毫秒
    binary=false                                    -- true 时 body 返回 ByteArray
  }
  local ret, code = http.get("http://x")           -- 便捷 GET
  local ret, code = http.post("http://x", "a=1")   -- 便捷 POST(form)
  ```
  返回 `Object[]{body(String|ByteArray), status(Int), headers(Map)}`，经底座多返回值直接展开。
- 注入（**本地进程内实例**，不走跨进程）：
  - root 主引擎：Server.kt initEngine 里 `builder.addLocalService("http", NetLib::class.java, INetLib::class.java)`（Type.CLASS 由 root 进程实例化）
  - IDE 沙箱：MainApp 里 `ideExecutor.registerService("http", NetLib(...), INetLib::class.java)`

## 2. 特性① luajava 反射桥 + LuaEngine.* java 桥

- 新增 `engine/src/main/java/com/autolua/engine/extension/bridge/`：
  - `LuaClassWrapper.kt`：包装 `Class<*>` 的 LuaObjectAdapter。
    - `:new(...)` → 按参数个数匹配构造器 → newInstance
    - 静态方法：MethodCache.findMethod(clazz, name).invoke(null, ...)
    - 静态字段：index/newIndex 读写 `clazz.getField(name)`
  - `IJavaBridge.kt` + `JavaBridgeLib.kt`（实现 LuaEnvContributor）：
    - `bindClass(name): Class<*>`（LuaClassWrapper）
    - `newInstance(name, ...args)`、`new(classObj, ...args)`
    - contribute()：注册全局 `import(name)` / `importClass(name)`（= bindClass）
  - `ILuaEngineLib.kt` + `LuaEngineLib.kt`：
    - `getContext()`：反射 `ActivityThread.currentApplication()`（root 进程），app 进程用构造注入的 Context
    - `loadApk(path, className)`：DexClassLoader 加载 → 返回 Class（LuaClassWrapper）
    - `getVersion()`：引擎版本
- 返回值统一走 `pushValue(Class)` → 已改为包装 LuaClassWrapper（替代原 JavaObjectWrapper(Class)）。
- 注入：Server.kt（root 本地）+ LuaEngineExecutor/MainApp（沙箱本地）注册 `luajava`、`LuaEngine` 两个全局对象。
- 限制说明：跨进程服务（app 进程实例）不能返回 LuaObjectAdapter，故 luajava/LuaEngine 必须本地注册（已满足）。

## 3. 特性② thread 多线程原语（同 state 协程 + 状态锁）

设计取舍：独立 state 方案会丢失闭包且 _G 不共享，lock/atomic 无真实语义；故采用 **同一 lua_State 内 coroutine + 全局状态锁** 方案（闭包/共享 _G/stop 语义完整）。

- C++（新增 `engine/src/main/cpp/extension/ThreadLib.cpp`，CMakeLists 登记）：
  - `thread.start(func, ...)`：`lua_newthread` 创建协程 → registry ref → 参数 xmove → JNI 调 Java `ThreadLib.start(nativeLua, ref)` → 返回 thread 句柄 userdata
  - `thread.sleep(ms)`：yield{op="sleep",ms}（挂起点）
  - `thread.lock()`：返回 Java ReentrantLock 包装；`lock()` 等待以 yield{op="lock", handle} 挂起实现（等待期间释放状态锁，防死锁）
  - `thread.atomicInc(key, delta)`、`thread.setShared/getShared/hasShared/removeShared`：转调 Java 共享表（ConcurrentHashMap）
  - `thread.stop(handle)` / `thread.join(handle)` / `thread.currentId()` / `thread.isMain()`
  - 状态锁：`LuaContext` 结构新增 `void* mutex`（per-state `std::recursive_mutex`）；主 worker 在 lua_pcall 前后持锁、`lSleep` 等待时释放；协程线程 resume 前抢锁、yield 后释放。
- Java（新增 `engine/src/main/java/com/autolua/engine/extension/thread/`）：
  - `ThreadLib.kt`：协程任务表（handle→CoroutineTask）、线程池执行 resume 循环
    （resume → LUA_YIELD{sleep/lock} → 处理 → 再 resume，直到 dead/返回）、
    共享表 ConcurrentHashMap、stop 标志（sleep 点抛 LuaError 终止协程）、join。
- 引擎接线：
  - 注入点在 `lua_context.cpp createLuaContext`（luaL_openlibs 后统一注册 thread 库）→ 主引擎/沙箱所有 LuaContext 自动具备
  - 主 worker 锁接线：LuaInterpreter execute 循环与 lSleep；引擎 interrupt 时 ThreadLib.stopAll
- Lua 用法：
  ```lua
  local lk = thread.lock()
  local t = thread.start(function(a, b)
    thread.sleep(100)
    lk:lock() ; shared_cnt = (shared_cnt or 0) + 1 ; lk:unlock()
    return a + b
  end, 1, 2)
  local ok, res = thread.join(t)   -- 阻塞等待协程结束，返回 ok, 结果
  ```

## 4. 注入矩阵

| 全局名 | root 主引擎（Server.kt，本地 CLASS 服务） | IDE 沙箱（MainApp → LuaEngineExecutor） |
|---|---|---|
| http | `addLocalService("http", NetLib::class, INetLib::class)` | registerService 同名 |
| luajava + 全局 import/importClass | `addLocalService("luajava", JavaBridgeLib::class, IJavaBridge::class)` | registerService 同名（contribute 钩子生效） |
| LuaEngine | `addLocalService("LuaEngine", LuaEngineLib::class, ILuaEngineLib::class)` | registerService 同名（app Context 注入） |
| thread | lua_context.cpp 统一注入（所有 LuaContext） | 同左 |

## 5. 验证与交付

1. 编译：`.\gradlew.bat :engine:assembleRelease`（PowerShell `&` 调用）
2. 提供 Lua 冒烟脚本 `autolua2/tools/bridge_test/main.lua`（http.request、import/new、thread.start+lock+shared）
3. 模拟器实测由用户执行；编译通过后 git 提交（分期提交：底座+http / luajava / thread）
