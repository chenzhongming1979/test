# Lua API 服务集设计

> 文档版本 v1.0（2026-09-18，文档三层化重组新建）
> 本域权威范围：luaapi 实现域的**设计面**——"*Lib 服务集 + 双端注入点"架构、注册与注入机制、compat 兼容层组织、商业引擎对齐策略与演进路线；契约条款见 [contracts.md](contracts.md)，脚本 API 语义以 [../../02-Lua-API规范.md](../../02-Lua-API规范.md) 为准。

## 架构总述

本域采用**服务集（*Lib）+ 双端注入点**架构：Java/Kotlin 侧每个能力域一个 Lib 类（`autolua2\engine\src\main\java\com\autolua\engine\extension\` 下 14 包），C++ 侧承担图色/网络/协程等原生能力（`autolua2\engine\src\main\cpp\extension\` 与 `cpp\base\`）。两类注入点在 LuaContext 创建时汇合（`cpp\engine.cpp:327` LuaStateFactory::create）：

- **C++ 注入点**：thread/json/cv 基底（`cpp\base\lua_context.cpp:518-522`）→ ROOT_DIR（`engine.cpp:338-341`）→ 图色基底 injectOther（`engine.cpp:342`，实现在 `cpp\3rd\autolua_vision\src\lua_vision.cc:151`：loadImage/CommonBitmap 元表/FIND_ORDER）→ Display 屏函数（`engine.cpp:343`，`cpp\extension\Display.h:19`）→ InputManager 触控（`engine.cpp:344`，`cpp\extension\InputManager.h:28`）→ images 表（`engine.cpp:345`，`cpp\extension\image_lib.cpp:637`）→ ws 表（`engine.cpp:346`，`cpp\extension\ws_lib.cpp:412`）。
- **Java 注入点**：C++ 完成后经 JNI 回调 `postNativeInject`（`engine.cpp:349` → `core\AutoLuaEngineOnLocal.kt:256`）→ `pushLocalServices`（`AutoLuaEngineOnLocal.kt:206`）：把 `addLocalService` 登记的服务包装为全局对象，`LuaEnvContributor` 实现类再回调 `contribute` 注册全局函数，最后插件贡献收尾（`AutoLuaEngineOnLocal.kt:229`）。

分层动机：帧数据/像素级图色留在 C++（避免 Java 侧 ~3.7MB/帧的 Binder 往返，见 `Server.kt:161-162` 注释）；设备生态、HTTP、MySQL 协议、插件机制等用 Java/Kotlin 实现更快；compat 兼容层用纯 Lua 内嵌串拼装两者（`extension\compat\CompatLib.kt:41`）。

## 注册与注入机制

- **登记**：`AutoLuaEngine.Builder.addLocalService(name, service/class, interface)`（`AutoLuaEngine.kt:202-204`，实现 `AutoLuaEngineOnLocal.kt:391-417`）。CLASS 型服务在注入时实例化（`AutoLuaEngineOnLocal.kt:180-191` newLocalServiceObj），接口必须写入 mInterface 供反射方法表（`AutoLuaEngineOnLocal.kt:412-414` 注释：漏写创建 LuaContext 时 NPE）。
- **包装**：`wrapLocalService`（`AutoLuaEngineOnLocal.kt:194-205`）把非 LuaObjectAdapter 对象包成 `JavaObjectWrapper`；Lua 调用经反射桥接，与插件贡献同一条链。
- **回调**：实现 `LuaEnvContributor` 的服务在入全局后回调 `contribute(luaContext)`（`AutoLuaEngineOnLocal.kt:215-221`）——注意判定用**原始实例**而非包装对象（`AutoLuaEngineOnLocal.kt:193` 注释）。
- **注册点矩阵**：root 引擎 `Server.kt:146-194`（15 服务：http→luajava→LuaEngine→file→device→crypt→app→plugin→ocr→media→ime→mysql→event→compat→uilib）；app 进程跨进程服务 `MainApp.kt:112-118`（UI/FloatController/IdeLog/uimgr，随引擎配置序列化到 root 回放）；沙箱与 root 同款全集（01 §8 :239）。跨进程代理不能返回 Lua 对象，需要返回对象的服务必须本地注册——这是双路径注册的根本原因（01 §8 :258）。
- **直注全局**：node 体系不走服务通道，在 `rawNewLuaContext`（`AutoLuaEngineOnLocal.kt:296`）直注 `UiSelector`/`getRootInActiveWindow`/`setText`（:300-314）与 `KeyCode`/`LayoutParamsFlag`（:315-318）；engine 全局环境变量经 `pushEnvironment`（:141）。

## compat 兼容层设计

compat 的目标是让**懒人精灵/派大星存量脚本尽量零改动迁移**，实现为 CompatLib.kt 内嵌 Kotlin raw string（`CompatLib.kt:41-1643`，contribute 时 loadBuffer+pcall，`CompatLib.kt:26-34`）：

- **懒人风格全局函数面**：utf8 扩展（length/sub/left/right/mid/inStr/inStrRev/…）、splitStr、table.contains/indexOf、math 扩展、frandom 模块、时间全局（systemTime/tickCount/time 模块）、图色兼容别名（findMultiColor/Ex→findFeature 特征串拼接；findPic/Fast/Ex→findImage；cmpColor/getPixelColor/getColorNum/keepCapture/releaseCapture/snapShot 别名）、懒人图片处理别名（getImage/scaleImage/rotateImage/binaryImage 等 ：878）、clipboard 别名（`clipboard.get/set → readPasteboard/writePasteboard`，:427）、附件 attach 表（:990-1030）。
- **派大星风格模块面**：`auto.*` 选择器工厂（条件 + 查找 + 按键，:705 起）、autoCollection 批量集合（:769 起）、`fhttp` 模块（底层复用 http 服务组装，:1468 起）、event 事件总线（on/off/emit + onToast/onNotification/onTouch/onClick 等，:1120 起，读端依赖 EventLib `__event_read` 与 EventBridge 镜像文件 `event\EventBridge.java:24,29`）、project 工程与 Fire 热更（纯 Lua backup 快照回滚，:1270 起）。
- **桥接组织**：别名层不重复造能力——一律转发到已注入的底层全局（thread/http/file/crypt/loadImage/UiSelector 等），因此**注册顺序契约**（compat 最后，见 [contracts.md](contracts.md) ②）是设计的一部分；setTimer/beginThread 等异步原语复用 `thread` 协程（:493-505）。

## 商业引擎对齐策略

三条商业引擎技术路线与本引擎的差异决定了缺口形态（详见两份账本：[API_GAP_LR.md](API_GAP_LR.md)、[API_GAP_FIREFLY.md](API_GAP_FIREFLY.md)）：

- **懒人精灵**（~570 项）：API 形态是"全局函数 + 简单模块"，与本引擎双端服务集差异中等；对齐靠 compat 别名 + 各域 contribute 补全局函数。当前覆盖率 **~65%**（`API_GAP_FIREFLY.md:514`）。
- **派大星 FireFly**（1354 项/59 模块）：API 面为懒人 2.4 倍，选择器/节点体系与本引擎 UiSelector 形态接近（覆盖 88-90%），但拖带 OpenCV Mat 全量、AI 推理、View 级 UI 三座大山。当前可用率 **~68%**（`API_GAP_FIREFLY.md:52-53,514`）。
- **技术路线差异浓缩**：① 图色——本引擎 cpp 零依赖实现核心（image_lib/cv_lib Mat 子集 60+ 项），不引入完整 OpenCV，Mat 周边与 warp 系/putText 是缺口大头；② AI——OCR/YOLO 走插件运行时（Lua 调用口已通、demo 链路已验证），真实识别算法留插件生态；③ UI——本引擎走 JSON 控件树 + op 协议动态 UI（ui 表，`ui\UiLib.kt:22`），不做 View 级 viewWrapper/canvas 对齐（声明不对齐）；④ 事件——无障碍镜像文件轮询（EventBridge）而非原生回调，onVoice/onGesture 无源不支持（`API_GAP_FIREFLY.md:37`）。
- **取舍铁律**：补齐以"能力可用 + 名字对齐"为界，不追 100% 形状复刻；同名不同义（🟠）优先消歧或文档标注迁移陷阱（状态图例见 `API_GAP_FIREFLY.md:9-17`）。

## 代码映射表

代码入口总表（Lib ↔ Lua 全局 ↔ 路径与行号）见 [README.md](README.md) §代码入口 与 [contracts.md](contracts.md) ①，此处列**设计关键件**：

| 关键件 | 职责 | 位置 |
|---|---|---|
| LuaStateFactory::create | 双端注入时序的唯一编排点 | `autolua2\engine\src\main\cpp\engine.cpp:327-353` |
| postNativeInject / pushLocalServices | Java 服务注入入口与 LuaEnvContributor 分发 | `core\AutoLuaEngineOnLocal.kt:256 / :206` |
| wrapLocalService + JavaObjectWrapper | Java 对象→Lua 桥接统一包装 | `AutoLuaEngineOnLocal.kt:194-205`；`base` 包 JavaObjectWrapper |
| CompatLib COMPAT_LUA | 懒人/派大星兼容层（内嵌 Lua 串 ~1600 行） | `extension\compat\CompatLib.kt:41-1643` |
| UiLib UI_LUA | 动态 UI Lua 层（ui/console 表，内嵌串同构） | `extension\ui\UiLib.kt:22-40` |
| UiSelector/UiObject/ByMatcher/UiAutomatorImp | 节点查找与操作体系 | `extension\node\UiSelector.kt:10`、`UiObject.kt:10`、`ByMatcher.kt:6`、`imp\root\UiAutomatorImp.kt:13` |
| InputManagerImp / PointersState | 触控注入 Java 实现（多点态） | `extension\input\InputManagerImp.kt:13`、`PointersState.java:8` |
| Display / DisplayImplement | 屏幕接口与实现（cpp 屏函数的另一端） | `extension\display\Display.java:5`、`DisplayImplement.java:20` |
| image_lib / ws_lib | 原生图色对象库 / WebSocket 库 | `cpp\extension\image_lib.cpp:637`、`ws_lib.cpp:412` |
| VisionBridge / SharedFrame | 帧共享 JNI 导出（whole-archive 保符号） | `cpp\extension\VisionBridge.cpp:110`、`SharedFrame.cpp` |
| PluginLuaHelper.putGlobal | 插件贡献 Lua 全局的唯一工具 | `plugin\PluginLuaHelper.kt:42-46` |
| EventBridge | 系统事件镜像桥（无障碍→镜像文件→轮询） | `extension\event\EventBridge.java:24`（镜像路径 ：29） |

## 演进与补齐

- **实施追踪只认两本账**：[API_GAP_LR.md](API_GAP_LR.md)（懒人 ~570 项对比 + 9 批次补齐记录，顶部批次表含自测基线 PASS=442 FAIL=0，2026-09-14）与 [API_GAP_FIREFLY.md](API_GAP_FIREFLY.md)（1354 项/59 模块对比 + 补齐批次记录 + P0-P3 路线 ：514 附近）。新批次先实现、后自测、再在账本勾销（流程见 [contracts.md](contracts.md) ⑤）。
- **已清账主线**：三件套（http.request/luajava/thread）→ 16 大类（json/file/device/crypt/app/compat/touch/node）→ 系统操作 → 节点动作 → 扩展模块（coordinate/media/timer/fhttp/ws/plugin/ocr/帧访问）→ 输入法/JPEG/异步网 → autoCollection → thread 修复/event 总线/Fire 热更/cv Mat 子集/MySQL 桥。
- **剩余 P0-P3 方向**（浓缩自账本）：OCR/YOLO 真实识别插件、cv warp 系/putText、View 级 UI 明确不对齐声明维持、event.onVoice/onGesture 等源能力、三环境一致性回归。
