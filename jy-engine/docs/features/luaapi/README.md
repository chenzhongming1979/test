# Lua API 服务集（luaapi）

> 文档版本 v1.0（2026-09-18，文档三层化重组新建）
> 本域权威范围：面向脚本作者的 Lua API 服务集**实现域**（引擎开发者视角）——覆盖 `autolua2\engine\src\main\java\com\autolua\engine\extension\` 下 14 个扩展包、cpp 侧 `autolua2\engine\src\main\cpp\extension\` 原生图色/网络库、注入时机与插件贡献链路；脚本作者视角的 API 权威手册是根文档 [02-Lua-API规范.md](../../02-Lua-API规范.md)。
> 状态：商业引擎对齐覆盖率 **懒人 ~65% / 派大星 ~68%**（2026-09-14 基线，见 [API_GAP_FIREFLY.md](API_GAP_FIREFLY.md)）；实施追踪以两份 API_GAP 存档账本为准。

## 本域文档

| 文档 | 职责 |
|---|---|
| [contracts.md](contracts.md) | 契约层：Lua 全局表→实现类映射总表、注入顺序契约、compat.lua 内嵌与 attach 约定、插件贡献 putGlobal、API 增补流程铁律 |
| [design.md](design.md) | 设计层：架构总述、注册与注入机制、compat 兼容层设计、商业引擎对齐策略、代码映射表、演进与补齐路线 |
| [API_GAP_LR.md](API_GAP_LR.md) | **存档账本**：懒人精灵 ~570 项缺口对比 + 9 批次补齐记录（实施追踪） |
| [API_GAP_FIREFLY.md](API_GAP_FIREFLY.md) | **存档账本**：派大星 1354 项/59 模块对比 + 补齐批次记录与 P0-P3 路线（实施追踪） |

## 全局真相源交叉引用

| 全局文档 | 与本域相关的权威内容 |
|---|---|
| [../../02-Lua-API规范.md](../../02-Lua-API规范.md) | 脚本 API 权威手册：§1.1 attach 附件（:58）、§17.1 动态 UI（:716）、§19 扩展模块 19.1–19.14（:808–975）、附录商业引擎命名对照（:1020） |
| [../../01-架构总览.md](../../01-架构总览.md) | §6 C++ 层模块地图与 Lua 环境注入顺序（:149，顺序清单 :165-179）、§8 Lua 运行环境与服务注入矩阵（:232） |
| [../../04-插件包规范.md](../../04-插件包规范.md) | §12.3 插件 Lua API 贡献（putGlobal 契约与示例，:321） |
| [../../06-开发铁律与踩坑手册.md](../../06-开发铁律与踩坑手册.md) | 开发铁律与踩坑（如 thread 两阶段化回归禁简化 ：39；collectValues 的 when 子类在前铁律当前落点在代码 `app\...\extension\ui\JsonViewFactory.kt:727`） |

## 代码入口

Java/Kotlin 侧包均位于 `autolua2\engine\src\main\java\com\autolua\engine\extension\` 下（下表省略公共前缀）：

| 包 / Lib | 对应 Lua 全局 | 路径（注册点/入口行号已核实） |
|---|---|---|
| ui\UiLib | `ui` 表 + `console` 表（动态 UI） | `ui\UiLib.kt:22` contribute 内嵌 uilib Lua（:24 loadBuffer）；服务名 `uilib` 注册于 `engine\core\root\Server.kt:193` |
| node\（UiSelector/UiObject/ByMatcher/UiAutomator/NodeLib） | `UiSelector` / `getRootInActiveWindow` / `setText` / `waitFor` 全局工厂 + NodeLib 系（`getNodeXml` `saveNode` `findByIndex` `keepNode` `releaseNode` `updateNode` `setNodeMode` `openAccessibility` `closeAccessibility` `isAccServiceOk`）+ 沙箱只读桥 `nodeLib` | `node\UiSelector.kt`、`node\UiObject.kt`、`node\ByMatcher.kt`、`node\UiAutomator.kt`、`node\NodeLib.kt`、`node\NodeLibBridge.kt`、`node\imp\root\UiAutomatorImp.kt`、`node\imp\accessibility\AccessibilityImp.kt`；全局注册于 `core\AutoLuaEngineOnLocal.kt` rawNewLuaContext |
| input\（InputManager/ImeLib/PointersState） | `ime` 表（触控 tap/swipe 系由 C++ InputManager 注入） | `input\ImeLib.kt:23`（contribute :83）、`input\InputManager.kt:3`、`input\InputManagerImp.kt:13`、`input\PointersState.java:8`；`ime` 服务注册于 `Server.kt:183` |
| display\ | 无独立全局（屏函数经 C++ Display::injectToLua 暴露） | `display\Display.java:5`、`display\DisplayImplement.java:20`、`display\EmptyDisplay.kt`；cpp 侧 `cpp\extension\Display.h:19` injectToLua |
| ocr\OcrLib | `ocr` 表 | `ocr\OcrLib.kt:34`（contribute :104）；服务注册于 `Server.kt:179` |
| compat\CompatLib | 全局函数/别名大集合（utf8 扩展、图色别名、setTimer、auto.*、autoCollection、fhttp、event 总线、project Fire、attach 等） | `compat\CompatLib.kt:24`（contribute :26，COMPAT_LUA 内嵌串 ：41-1643）；服务注册于 `Server.kt:190` |
| bridge\（JavaBridgeLib/LuaEngineLib） | `luajava`（import/importClass）、`LuaEngine` | `bridge\JavaBridgeLib.kt:24`（contribute :62）、`bridge\LuaEngineLib.kt:14`、`bridge\LuaClassWrapper.kt:19`；注册于 `Server.kt:148` / `Server.kt:150` |
| event\（EventLib/EventBridge） | `event` 总线表（compat 封装） | `event\EventLib.kt:20`（contribute :38）、`event\EventBridge.java:24`（镜像文件 ：29）；服务注册于 `Server.kt:187` |
| thread\ThreadLib | `thread` 表 | `thread\ThreadLib.kt:22`（Java 任务池桥）；Lua 注入由 C++ 完成：`cpp\base\thread_lib.cpp:477` injectThreadLib，`cpp\base\lua_context.cpp:518` |
| net\NetLib | `http` 服务对象（fhttp 由 compat 组装） | `net\NetLib.kt:33`、`net\INetLib.kt`；服务注册于 `Server.kt:146` |
| file\FileLib | `file` 表 + 全局函数别名 | `file\FileLib.kt:13`（contribute :146）；服务注册于 `Server.kt:153` |
| app\AppLib | `app` 域全局函数 | `app\AppLib.kt:16`（contribute :205）；服务注册于 `Server.kt:159` |
| device\DeviceLib | 设备全局函数（readPasteboard/vibrate 等） | `device\DeviceLib.kt:21`（contribute :193）；服务注册于 `Server.kt:155` |
| db\MySqlLib | `mysql` 表 + connectSQL 等全局 | `db\MySqlLib.kt:31`（contribute :328）；服务注册于 `Server.kt:185` |
| crypto\CryptoLib | crypt 域全局函数（MD5/sha/base64/aes/rsa） | `crypto\CryptoLib.kt:25`（contribute :163）；服务注册于 `Server.kt:157` |
| media\MediaLib | `media` 表 + playAudio/stopAudio | `media\MediaLib.kt:14`（contribute :59）；服务注册于 `Server.kt:181` |
| plugin\PluginLib | `plugin` 表 | `plugin\PluginLib.kt:22`（contribute :26）；服务注册于 `Server.kt:177` |
| cpp image_lib | `images` 表（loadImage 系图像对象） | `cpp\extension\image_lib.cpp:637` injectImageLib；由 `cpp\engine.cpp:345` 注入 |
| cpp ws_lib | `ws` 表 | `cpp\extension\ws_lib.cpp:412` injectWsLib；由 `cpp\engine.cpp:346` 注入 |
| cpp Display/InputManager/VisionBridge | tap/swipe/keyPress/compareColor 系 / loadImage、FIND_ORDER / 共享帧 | `cpp\extension\Display.h:19`、`cpp\extension\InputManager.h:28`（注入调用 `cpp\engine.cpp:343-344`）；图色基底 injectOther：`cpp\3rd\autolua_vision\src\lua_vision.cc:151`；VisionBridge：`cpp\extension\VisionBridge.cpp:110`（extern "C" JNIEXPORT） |

## 何时读本文档

- **新增/修改一个 Lua API**（全局函数、`xxx` 表方法、别名）→ 先读 [contracts.md](contracts.md) 的映射总表与 API 增补流程铁律，再按流程改 [../../02-Lua-API规范.md](../../02-Lua-API规范.md)。
- **排查"Lua 侧拿到 nil / 全局缺失"类问题**（注入顺序、compat 断链、跨进程服务限制）→ [contracts.md](contracts.md) §注入顺序契约。
- **要理解服务集架构、双端注入点、compat 兼容层组织方式、商业引擎对齐思路** → [design.md](design.md)。
- **要查某商业引擎 API 是否已补齐、属于哪个批次、P0-P3 路线** → [API_GAP_LR.md](API_GAP_LR.md) / [API_GAP_FIREFLY.md](API_GAP_FIREFLY.md)（实施追踪账本，勿在本域新建第三本）。
- **写脚本用 API**（而非改引擎）→ 直接读根文档 [../../02-Lua-API规范.md](../../02-Lua-API规范.md)，本域文档是引擎开发者视角。
