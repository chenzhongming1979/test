# Lua API 服务集契约

> 文档版本 v1.0（2026-09-18，文档三层化重组新建）
> 本域权威范围：luaapi 实现域的**契约面**——Lua 全局名与实现类的绑定关系、注入顺序、compat 内嵌约定、插件贡献约定、API 增补流程；脚本侧 API 语义以 [../../02-Lua-API规范.md](../../02-Lua-API规范.md) 为准。

## ① Lua 全局表/全局名 → 实现类映射总表

每个全局名的注册点均以 `文件:行号` 对码（行号经 Grep/Read 核实，基线 2026-09-18）。

### Java/Kotlin 侧（经 `addLocalService` → `pushLocalServices` 包装为全局对象 + LuaEnvContributor 回调）

注册总入口：root 引擎 `engine\core\root\Server.kt` initEngine（顺序敏感，:146-194）；沙箱侧另有 `app\...\MainApp.kt:112-118`（UI/FloatController/IdeLog/uimgr 跨进程服务）。机制链：`addLocalService` → `pushLocalServices`（`core\AutoLuaEngineOnLocal.kt:206`）包装为 Lua 全局 → 实现了 `LuaEnvContributor` 的回调 `contribute` 注册全局函数（`AutoLuaEngineOnLocal.kt:215-221`）。

| Lua 全局 | 实现类 | 注册点（服务名） | contribute 注册点 |
|---|---|---|---|
| `http`（服务对象） | `extension\net\NetLib`（`net\NetLib.kt:33`） | `Server.kt:146`（"http"） | 无（纯服务对象；fhttp 由 compat 组装，CompatLib.kt:1468） |
| `luajava`（服务对象） | `extension\bridge\JavaBridgeLib`（`JavaBridgeLib.kt:24`） | `Server.kt:148` | `JavaBridgeLib.kt:62`（import/importClass） |
| `LuaEngine`（服务对象） | `extension\bridge\LuaEngineLib`（`LuaEngineLib.kt:14`） | `Server.kt:150` | 无 |
| `file` 表 + fileExist/mkdir/delfile/readFile/writeFile/listDir/zip/unZip 等 | `extension\file\FileLib`（`FileLib.kt:13`） | `Server.kt:153` | `FileLib.kt:146` |
| device 全局函数（getBrand/getModel/readPasteboard/writePasteboard/vibrate 等） | `extension\device\DeviceLib`（`DeviceLib.kt:21`） | `Server.kt:155` | `DeviceLib.kt:193` |
| crypt 全局函数（MD5/fileMD5/sha256/base64/aes/rsa/url 系） | `extension\crypto\CryptoLib`（`CryptoLib.kt:25`） | `Server.kt:157` | `CryptoLib.kt:163` |
| app 全局函数（runApp/stopApp/appIsFront/exec/installApk 等） | `extension\app\AppLib`（`AppLib.kt:16`） | `Server.kt:159` | `AppLib.kt:205` |
| `plugin` 表 | `extension\plugin\PluginLib`（`PluginLib.kt:22`） | `Server.kt:177` | `PluginLib.kt:26` |
| `ocr` 表 | `extension\ocr\OcrLib`（`OcrLib.kt:34`） | `Server.kt:179` | `OcrLib.kt:104` |
| `media` 表 + playAudio/stopAudio | `extension\media\MediaLib`（`MediaLib.kt:14`） | `Server.kt:181` | `MediaLib.kt:59` |
| `ime` 表（`imeLib` 别名） | `extension\input\ImeLib`（`ImeLib.kt:23`） | `Server.kt:183`（"ime"） | `ImeLib.kt:83` |
| `mysql` 表 + connectSQL/executeQuerySQL/executeSQL/closeSQL | `extension\db\MySqlLib`（`MySqlLib.kt:31`） | `Server.kt:185` | `MySqlLib.kt:328` |
| `__event_read` / `__event_enable`（供 compat event 轮询） | `extension\event\EventLib`（`EventLib.kt:20`） | `Server.kt:187` | `EventLib.kt:38` |
| `compat` 兼容层全局大集合（见 ③） | `extension\compat\CompatLib`（`CompatLib.kt:24`） | `Server.kt:194`（"compat"，注释声明必须在以上服务之后） | `CompatLib.kt:26` |
| `ui` 表 + `console` 表 | `extension\ui\UiLib`（`UiLib.kt:20`） | `Server.kt:197`（"uilib"，惰性寻址不依赖注册顺序 ：196） | `UiLib.kt:22`（内嵌 uilib Lua，:24 loadBuffer） |
| `virtualDisplay` 表 + vd 对象（🟢 首批落地，Shizuku 路径） | `extension\virtualdisplay\VirtualDisplayLib`（`VirtualDisplayLib.kt:28`）+ `VdHandle.java:15`（Lua 冒号调用的 Java 原语对象，经 JavaObjectWrapper 自动包装）+ `VdFrameClient.java:27`（跨进程 seqlock 帧读取） | 三环境注册：root `Server.kt:191`（"virtualDisplay"，compat 之前）/ IDE 沙箱 `app\...\MainApp.kt:162`（registerService）/ 本地降级引擎 `MainApp.kt:352`（addLocalService CLASS 型）；contribute 注册 `virtualDisplay` 表（`VirtualDisplayLib.kt:30`）+ 内嵌 Lua 组合层（VD_LUA，`VirtualDisplayLib.kt:138` loadBuffer+pcall，同 CompatLib 模式），对码详见下方 [virtualDisplay 小节](#virtualdisplay-虚拟屏) |

### virtualDisplay 虚拟屏（🟢 首批落地：Shizuku 路径，虚拟屏任务书 §4.3/§4.4/§5）

> 权威 API 规范：[../../02-Lua-API规范.md](../../02-Lua-API规范.md) §20（已按实现回填）；实施方案：[../../plans/虚拟屏功能实施任务书.md](../../plans/虚拟屏功能实施任务书.md) §4/§5。root 直建路径（SurfaceControl 四件套直挂引擎 Display）属任务书 §6.1 未落地——当前 `virtualDisplay.create` 只走 Shizuku UserService 链路，三环境均注册但无 Shizuku 时 create 抛可读错误。

| 环境 | 注册点（实测对码） | 底座对码 |
|---|---|---|
| root 主引擎 | `autolua2\engine\src\main\java\com\autolua\engine\core\root\Server.kt:191`（initEngine 内 "virtualDisplay" CLASS 型注册，位置在 event 之后、compat :194 之前，compat 最后的顺序铁律未破坏） | 建屏内核 `autolua2\engine\src\main\java\com\autolua\engine\conceal\DisplayControlWrap.java:80`（双路径：≤14 SurfaceControl 静态 / 15+ DisplayControl+Transaction）；UserService 服务端 `autolua2\engine\src\main\java\com\autolua\engine\input\inject\ShizukuUserServiceMain.java:142`（createVirtualDisplay） |
| IDE 沙箱 | `autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:162`（ideExecutor.registerService("virtualDisplay", VirtualDisplayLib(), ...)） | Shizuku 客户端通道 `autolua2\engine\src\main\java\com\autolua\engine\input\inject\ShizukuInjectorChannel.java:240`（createVirtualDisplay）；帧泵/定向注入新方法 `ShizukuInjectorChannel.java:293`-`:351` |
| 本地降级引擎 | `autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:352`（buildLocalSandboxEngine 内 addLocalService CLASS 型，与 root 同款） | 同 root 行 |

- **实现文件**：`VirtualDisplayLib.kt`（表型服务 + 内嵌 Lua 组合层 + 实例注册表）、`VdHandle.java`（Lua 冒号调用的 Java 原语：runApp/tap/longTap/swipe/getSize/getDisplayId/__frame/destroy，返回 int 错误码由 Lua 组合层翻译 true/error）、`VdFrameClient.java`（attachFrameSink fd → SharedMemoryManager.mapFdToByteBuffer mmap → 跨进程 seqlock 读）。
- **Lua 组合层要点**：图色方法 = `vd:__frame()` 原始帧 → `images.fromData(w,h,data)` → Bitmap 同名方法（Bitmap 方法集以 `3rd\autolua_vision\src\lua_vision.cc:75` COMMON_BITMAP_METHODS 实际注册为准：getColor/getColorCount/isColor/whichColor/findColor/isFeature/findFeature/isImage/whichImage/findImage/save/clone/getSize）；findColorAllPoint/findFeatureAll/findImageAll 不在 Bitmap 上，组合层以窗口推进扫描实现（`VirtualDisplayLib.kt:162` _vd_scanAll，收集序固定行优先、内部窗口查找强制 order=1）；getScreenFrame 返回 data,w,h,w*4,"RGBA"；saveScreen 用 images.save（仅 png）。
- **无 Shizuku 语义**：create 抛可读错误（`VirtualDisplayLib.kt:87` CREATE_GUIDE：引导启动并授权 Shizuku；注明 root 直建路径属任务书 §6.1 未落地），不静默、不返回假对象。

### 引擎直注全局（非 addLocalService 通道）

| Lua 全局 | 实现 | 注册点 |
|---|---|---|
| `UiSelector` / `getRootInActiveWindow` / `setText` / `waitFor` | `extension\node\UiSelector`（`UiSelector.kt:10`）+ `node\UiAutomator`（`UiAutomator.kt:3`，root 实现 `imp\root\UiAutomatorImp.kt:13`，**沙箱实现 `imp\accessibility\AccessibilityImp.kt`**（全窗口 fallback：活动窗口→getWindows 按 active 优先/layer 降序，上限 10））；匹配器 `node\ByMatcher.kt:6`、节点对象 `node\UiObject.kt:10` | `core\AutoLuaEngineOnLocal.kt` rawNewLuaContext 内（`UiSelector/getRootInActiveWindow/setText` 在 `uiAutomator != null` 分支；`waitFor` 经 `WaitForNodeFunction` → `UiSelector.findOneWait`）。**沙箱装配**：`AutoLuaEngineOnLocal.Builder.build()` 兜底——未显式注入 uiAutomator 且 `!isRoot && AutoLuaAccessibilityService.isRunning()` 时自动 `AccessibilityImp()` |
| `getNodeXml` / `saveNode` / `findByIndex` / `keepNode` / `releaseNode` / `updateNode` / `setNodeMode` / `closeAccessibility` / `openAccessibility` / `isAccServiceOk` | `extension\node\NodeLib`（`NodeLib.kt` object 单例；rootDir 经 setRootDir 注入） | `AutoLuaEngineOnLocal.kt` rawNewLuaContext 内注册；**沙箱只读桥** `node\nodeLib`（`NodeLibBridge.kt`，LuaEngineExecutor 构造注册：仅 getNodeXml/isAccServiceOk/saveNode/findByIndex，写入口不进桥） |
| `screenshot([timeout])` 全局函数 | `accessibility\AccessibilityCapture.java`（API 30 硬闸 + `CAPABILITY_CAN_TAKE_SCREENSHOT` 校验；HardwareBuffer→wrap→copy ARGB_8888 软件位图） | `AutoLuaEngineOnLocal.kt` rawNewLuaContext（无条件注册，adapter 裸栈；root 引擎进程无服务实例返回 nil）；config 依赖 `res\xml\accessibility_service_config.xml` 的 `android:canTakeScreenshot="true"` |
| `KeyCode` / `LayoutParamsFlag` 常量表 | `AutoLuaEngineOnLocal.kt`（KeyCode/LayoutParamsFlag 私有 object） | `AutoLuaEngineOnLocal.kt`（pushTable + setGlobal） |
| 环境变量（addEnvironment） | `AutoLuaEngineOnLocal.kt`（pushEnvironment） | 建 context 时 |
| `thread` 表 | cpp `base\thread_lib.cpp:477` injectThreadLib（Java 桥 `extension\thread\ThreadLib.kt:22`） | `cpp\base\lua_context.cpp:518` |
| `json`（cjson） | cpp `base\json_lib.cpp:191` | `cpp\base\lua_context.cpp:520` |
| `cv` 表（Mat 体系） | cpp `base\cv_lib.cpp:1234` | `cpp\base\lua_context.cpp:522` |
| `loadImage` / CommonBitmap 元表 / `FIND_ORDER` | cpp 3rd autolua_vision | `cpp\3rd\autolua_vision\src\lua_vision.cc:151-158`（injectOther），由 `cpp\engine.cpp:342` 调用 |
| 屏函数 + compareColor 系 + vision_extra 五函数 + isDisplayDead | cpp `extension\Display.h:19`（实现在 Display.cpp） | `cpp\engine.cpp:343` |
| tap/swipe/keyPress/touchDown/Move/Up/longTap/createPointer 等 | cpp `extension\InputManager.h:28`（Java 实现 `extension\input\InputManagerImp.kt:13`、多点态 `input\PointersState.java:8`） | `cpp\engine.cpp:344` |
| `images` 表 | cpp `extension\image_lib.cpp:637` | `cpp\engine.cpp:345` |
| `ws` 表 | cpp `extension\ws_lib.cpp:412` | `cpp\engine.cpp:346` |
| 共享帧 JNI（getScreenFrame/captureScreen.getFrame 底座） | cpp `extension\SharedFrame.cpp` / `extension\VisionBridge.cpp:110`（extern "C" JNIEXPORT；whole-archive 链接原因见 01 §6） | JNI 导出，无 Lua 直接注册点 |
| `ROOT_DIR` | engine 常量串 | `cpp\engine.cpp:338-341` |

## ② 注入顺序契约

完整顺序清单见 [../../01-架构总览.md](../../01-架构总览.md) §6（:165-179）与 §8 注入矩阵（:232）。代码级时序：

1. **C++ 基底先行**：`LuaStateFactory::create`（`cpp\engine.cpp:327`）按序执行 ROOT_DIR（:340）→ injectOther 图色基底 loadImage/FIND_ORDER（:342）→ Display::injectToLua（:343）→ InputManager::injectToLua（:344）→ injectImageLib（:345）→ injectWsLib（:346）。thread/json/cv 更早，在 createLuaContext 注入（`cpp\base\lua_context.cpp:518-522`）。
2. **Java 侧服务必须最后**：C++ 注入完成后经 `postInjectMethodID` 回调 Java `postNativeInject`（`cpp\engine.cpp:349`；方法 id 获取 `engine.cpp:106` → `core\AutoLuaEngineOnLocal.kt:256` postNativeInject → `:206` pushLocalServices）。**契约依据**：compat 包装层在 contribute 时捕获 getColor/isColor/findColor 等原生函数，若先于 C++ 图色注入执行会捕获到 nil（cmpColor 断链，注释见 `engine.cpp:347-348` 与 `AutoLuaEngineOnLocal.kt:252-255`）。
3. **compat 必须在 14 域服务之后注册**：compat 的 compat.lua 引用 http/file/crypt/device/app/luajava/thread 等前序全局，Server.kt 注册序 http→luajava→LuaEngine→file→device→crypt→app→plugin→ocr→media→ime→mysql→event→virtualDisplay→compat（`Server.kt:146-194`，compat 行 ：194 处注释"必须在以上服务之后"；同义约束写入 `CompatLib.kt:22` 类注释）。uilib 在 compat 之后（`Server.kt:197`），其依赖 uimgr/thread/json 为惰性寻址，不依赖注册顺序（`Server.kt:196` 注释、`ui\UiLib.kt:13-14`）。
4. **插件贡献收尾**：pushLocalServices 末尾调用 `PluginManager.getInstance().contributeLua(luaContext)`（`AutoLuaEngineOnLocal.kt:229`）注入插件全局；插件晚于 context 加载的需重建 context 生效（`AutoLuaEngineOnLocal.kt:226-227` 注释）。
5. **三环境一致性铁律**：root 主引擎 / IDE 沙箱 / 调试三环境 Lua 全局名必须一致，新增服务同步全部注册点（[../../01-架构总览.md](../../01-架构总览.md) §8 :233；root=`Server.kt:146-194`，沙箱=`app\...\MainApp.kt:112-118` + LuaEngineExecutor 注册）。

## ③ compat.lua 内嵌与 attach 约定

- **内嵌形态**：compat.lua 不是独立文件，而是 `extension\compat\CompatLib.kt` 内的 Kotlin raw string `COMPAT_LUA`（:41 起，至 ：1643 结束，约 1600 行纯 Lua）；contribute 时 `loadBuffer` + `pcall` 执行（`CompatLib.kt:26-34`），chunk 名 `=compat`。改 compat 内容 = 改 CompatLib.kt 字符串。
- **内容组织（懒人/派大星别名分区）**：字符串/utf8 扩展、图色兼容别名（findMultiColor→findFeature、findPic 系→findImage、snapShot :254、clipboard 别名 ：427）、setTimer/beginThread/newThread（:493-505，thread 协程驱动）、应用网络全局别名（downloadFile :579）、派大星 auto.* 选择器工厂（:705 起）、autoCollection 批量集合（:769 起）、懒人图片处理别名（:878）、event 事件总线封装（:1120 起）、project 工程与 Fire 热更（:1270 起）、fhttp 模块组装（:1468 起）。
- **attach 附件约定**（契约对码 `CompatLib.kt:990-1030`）：
  - `attach.dir()`：附件目录（:991）；`attach.path(name)`：附件相对路径→绝对路径，**不校验存在性**（:994-997）；
  - `attach.exists(name)`：存在性判断（:999）；`attach.read(name)`：读文本附件，不存在时 `error` 带层级 2（:1005-1008）；
  - `attach.loadImage(name)`：等价 `loadImage(attach.path(name))`，要求图色环境已注入 loadImage，否则报"loadImage unavailable"（:1011-1016）；
  - `attach.require(name)`：加载附件 `.lua` 模块（自动补扩展名、not found/加载失败报错）（:1021-1030）。
  - 脚本侧语义权威：[../../02-Lua-API规范.md](../../02-Lua-API规范.md) §1.1。

## ④ 插件贡献契约 putGlobal

- **契约**：插件实现 `LuaEnvContributor.contribute(ctx)` → 引擎建 context 时经 `PluginManager.contributeLua` 回调（`AutoLuaEngineOnLocal.kt:229`）→ 插件内调用 `PluginLuaHelper.putGlobal(ctx, name, api, iface)` 把普通 Java/Kotlin 对象包装为 Lua 全局表（`plugin\PluginLuaHelper.kt:42-46`：JavaObjectWrapper 包装 → push → setGlobal）。
- **调用链与内置服务同一条**：方法经 JavaObjectWrapper 反射桥接（`PluginLuaHelper.kt:8-14` 注释）；冒号/点号调用均可。
- **约束**：贡献时机 = LuaContext 创建时（已加载插件）；同名全局会**覆盖内置服务**，禁用 http/file/ocr 等保留名；root 主引擎与 IDE 沙箱各有一份插件实例、各自贡献（`PluginLuaHelper.kt:24-28` 注释）。规范原文与示例：[../../04-插件包规范.md](../../04-插件包规范.md) §12.3（:321）。

## ⑤ API 增补流程铁律

1. **先改手册**：任何新增/变更 Lua API，先在 [../../02-Lua-API规范.md](../../02-Lua-API规范.md) 落章节（含参数、返回值、三环境一致性），禁止"先实现后补文档"。
2. **再实现**：按 ①② 的映射与顺序契约落实现——服务型走 `addLocalService` + LuaEnvContributor（同步全部注册点）；原生能力走 cpp 注入（CMakeLists whole-archive 约束见 01 §6 :161-163）；兼容别名进 CompatLib 内嵌串。
3. **后记账**：完成批次在 [API_GAP_LR.md](API_GAP_LR.md) / [API_GAP_FIREFLY.md](API_GAP_FIREFLY.md) 的"补齐批次记录"表勾销（提交号 + 内容 + 对应缺口），两账本是唯一实施追踪处，本域文档不复述逐项状态。
4. **回归验证**：跑 `tools\selftest\main.lua` 全量自测更新 PASS/FAIL 基线（当前基线 PASS=442 FAIL=0，2026-09-14，`API_GAP_LR.md:37`）；铁律回归项（thread 两阶段禁简化等）对照 [../../06-开发铁律与踩坑手册.md](../../06-开发铁律与踩坑手册.md)。
