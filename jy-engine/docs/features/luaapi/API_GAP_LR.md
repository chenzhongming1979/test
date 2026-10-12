# JY-Frame（autolua2）与懒人精灵 API 对比报告

> 生成日期：2026-09-12
> 对比基准：`doc-lrword/AGENT索引.md`（懒人精灵官方文档离线版逐函数索引，Android 平台 24 模块）
> 对象仓库：本仓库 `autolua2/`（engine + app）
> 用途：识别 JY-Frame 相对懒人精灵的 API 缺口，指导后续补齐计划。禁止对外传播懒人文档内容。

## 状态图例

| 标记 | 含义 |
|---|---|
| ✅ 同名实现 | Lua 全局函数/方法同名可用，语义基本一致 |
| 🟡 等价实现 | 能力已覆盖，但函数名/调用形式/参数语义不同 |
| 🟠 同名不同义 | 函数名相同但参数格式/返回语义与懒人不同（迁移脚本需改参数） |
| ❌ 未实现 | 引擎内不存在该 Lua API |
| 🔌 插件预留 | 按已拍板动态插件方案（../../04-插件包规范.md）预留接入点，Lua API 未定 |

---

## ⭐ 补齐批次记录（实施追踪，2026-09-12）

> 下文逐项判定为报告生成时（3b3ef81 之前）的快照，未逐项重标；实际缺口以下表批次为准。

| 批次 | 提交 | 内容 | 对应本报告缺口 |
|---|---|---|---|
| 三件套 | `3b3ef81` | `http.request`（luasocket 风格，NetLib/HttpURLConnection）；luajava 反射桥（全局 `import/importClass`，LuaClassWrapper）；`LuaEngine.getContext/loadApk`；thread 多线程原语（同 state 协程 + per-state 递归锁） | 3.7 luasocket ✅；json 之外的底层桥接 |
| 16 大类 | `0dc3a52` | json（C++ 绑 cjson 全局 `json.encode/decode`）；file 域（`file.*` + 全局 `fileExist/mkdir/delfile/readFile/writeFile/listDir/fileSize`，覆盖 lfs 高频项）；device 域（DeviceLib：Build/电量/DPI/MAC/IP/AndroidId）；cipher 域（CryptoLib：md5/sha/base64/url/aes/rsa + `MD5/encodeBase64` 别名）；app 域（AppLib：`runApp/stopApp/appIsFront/appIsRunning/frontAppName/exec/getInstalledApps`）；compat 兼容层（utf8/table/math 扩展、`setTimer/rnd` 系、时间全局）；触控便捷层（`touchDown/touchMove/touchUp/longTap` + KeyCode 扩 13 键）；节点（UiObject 11 个 ACTION + UiSelector 补 bounds/visibleToUser/editable/idContains 系列） | P0/P1/P2 大部分项 ✅（见"四"表勾销） |
| 系统操作 | `fba79cd` | AppLib 系统操作 15 项（`lockScreen/unLockScreen/setWifiEnable/setBTEnable/setAirplaneMode/setDpiToVir/setDpiToRealy/sendSms/phoneCall/runIntent/installApk/scanImage/setDisplayPowerOff` 等）；DeviceLib `readPasteboard/writePasteboard`（root IClipboard 反射）+ `vibrate`；FileLib `zip/unZip`（递归+防穿越）；全局 `exitScript`；compat `clipboard.get/set、getRunEnvType、checkIsDebug、setStopCallBack` | 3.16 系统操作/剪贴板/压缩 ✅ |
| 节点动作 | `0bf13cb` | UiSelector 便捷动作（`sel:findOne/findAll/findOnce/exists/click/longClick/setText`）+ pkgContains/StartsWith/EndsWith + `password/drawingOrder` 条件；UiObject `boundsInParent/password/drawingOrder` 属性 + `depth()/childs()`；C++ `findImageAll`（模板全匹配）+ compat `findPicAllPoint/findPicAll`；compat `inputText`（ASCII→input text，中文→剪贴板+焦点粘贴）、`toast/hideToast`；条件工厂补齐 9 个 | 3.14 节点 sel 侧/属性、3.12 模板全匹配、3.13 inputText/toast ✅ |
| 扩展模块 | `a9183ad` | 图片：`images.binarize/rotate(任意角度)` + 懒人别名 `getImage/scaleImage/rotateImage/binaryImage/binaryRect`；`path` 19 项；`coordinate` 12 项；`timer`/事件回调总线（setXxxEventCallBack + sendXxxEvent）；`media` 表 + `playAudio/stopAudio`；`extractAssets`；`fhttp` 24 项（postJson/postForm/put/delete/patch/head/options/download/upload/setTimeout/setProxy + 异步族）；`ws` 双向 WebSocket 12 项（libhv，事件轮询模型）；`getScreenFrame` + `captureScreen.getFrame/getSharedMemorySize`；`plugin` 表 9 项（list/load/unload/install/getSoPath，PluginManager 目录 override 支持 root 无 Context 加载）；`ocr` 表（isSupported/ocrText/ocrEx/ocrExNew/recognizeRaw/recognizeFile）；`exitScript`；修复 LuaContextImplement 嵌套表转换 SIGSEGV | 3.12 周边收尾、3.18 extension 图片处理、extension qrDecode 外几乎全清；派大星侧 media/coordinate/timer/fhttp/websocket/captureScreen 帧/plugin ✅（详见 FIREFLY 报告批次表） |
| 输入法/JPEG/异步网 | 本批次 | `ime` 输入法 16 项（root shell settings/am + 宿主广播，零 Context；ImeLib.kt + AutoLuaInputMethodService 增 ACTION_SHOW_PICKER/RECEIVER_EXPORTED）；JPEG 编解码（libjpeg-turbo 3.0.4 静态编译接 C++ extension：`images.toJpg/fromJpg/saveJpg/readJpg` + `compress` 别名）；fhttp 异步解耦（NetLib `requestAsync/downloadAsync/uploadAsync` 独立 Java 线程 + AsyncHandle 句柄 `isDone/get/cancel`，解除 thread 协程内 Java 阻塞限制；compat 回调族保留、句柄轮询为主推）；OCR demo 插件全链路自测通过（install→load→ocrText→unload→uninstall） | imeLib 体系 ✅；3.18 图片 JPEG 压缩 ✅；3.7 http 异步 ✅ |
| MySQL 桥 | 本批次 | 懒人全局 `connectSQL/executeQuerySQL/executeSQL/closeSQL`（隐式当前连接）+ 对象式 `mysql.connect/query/execute/ping/close` + `mysql.escape`；零依赖手写 MySQL 协议客户端（MySqlLib.kt：4.1+ 握手 HandshakeResponse41、mysql_native_password 与 caching_sha2_password 快速认证、LCB/LencStr 结果集解析、COM_QUERY/COM_PING）；root+沙箱双注册；自测 8 项（错误路径+转义）PASS | 3.x MySQL 桥 ✅ |
| autoCollection | `e5c788f` | compat 纯 Lua 集合包装：`autoCollection.find/findAll/findOne/create` + 容器 11 项（contains 用 text\|desc\|res\|bounds 指纹比对）+ 二次筛选 find/findOne/setText + 批量动作 19 项（遍历返回成功数）；UiObject 11 个 ACTION 修复（index()/call() 双闸门补齐 + API23+ 嵌套 AccessibilityAction 常量取 .id） | 3.x 节点集合批量操作 ✅ |
| event/Fire/cv | `5cd1ff4` | thread 竞态修复（start 两阶段化，8 轮全绿）；event 事件总线（compat 多播 + 无障碍事件源桥：镜像文件→60ms 轮询；onStopEvent 多播与 setStopCallBack 并存）；project Fire 热更（hotfixFire/FromUrl/FromDir/rollback/回滚备份）；cv 库 60+ 项（零依赖纯 C++ Mat：cvtColor/threshold/gaussianBlur/dilate/erode/inRange/extractContours/minMaxLoc 等） | 3.x 事件总线/Fire 热更/图像加工 ✅ |
| 节点增强 | 本次 | ① 等待轮询：`sel:findOne([timeout])/findAll([timeout])`（50ms 轮询）、`sel:untilFindOne()`（无限轮询）、`sel:waitFor([timeout])`（boolean）、compat `auto.untilFind/untilFindOne`；② 新条件：content 五系（text OR desc 任一匹配）、and(sel) 交集组合器（**and 为 Lua 保留字，别名 andSel**）、boundsContains(x,y)、screenCenterX/Y([tol])/screenCoverage([min])；③ 多窗口兜底（便捷动作主窗口无结果时遍历 otherRoots）；④ UiObject 树遍历/几何派生（sibling/nextSibling/previousSibling/firstChild/lastChild/indexInParent/hasChildren + centerX/centerY/width/height/center/point）；⑤ NodeLib 全局：keepNode/releaseNode/updateNode（快照缓存）、getNodeXml([includeInvisible])（XML 树导出）、openAccessibility（root shell 自动启用，失败跳设置页）/isAccServiceOk；⑥ IDE 沙箱 nodeLib 只读桥 + VSCode 插件"抓取节点树"面板（autolua2.grabNodeTree） | 3.14 选择器查找执行/nodeLib 体系、3.15 isAccServiceOk/findNextNode 🟡 等大幅收口（见 §3.14/3.15 重标行） |
| 无障碍专项 | `32c5761`/`7152193`/`151a108`/本批次 | ① 节点可靠性：UiAutomatorImp 忙等改 3s 有界等待、根节点 150ms TTL 缓存（refresh 校验+obtain 拷贝）、ByMatcher 单轮子节点复用去放大、全局 `waitFor(sel,timeout)`（懒人语义，引擎侧轮询）；② 沙箱节点：`imp\accessibility\AccessibilityImp`（服务连接自动装配，活动窗口→全窗口 fallback）+ CompatLib 守卫（服务未连接触发时抛中文引导错误）；③ 事件：EventBridge 队列化（JSON-lines+seq+256KB 截断重置+读端游标/心跳）+ 服务 `onKeyEvent` 音量键智能拦截 + compat `event.onKey(cb)`（key 事件跳过去重）；④ 截图：config `canTakeScreenshot` + `AccessibilityCapture`（API30 硬闸）+ 全局 `screenshot([timeout])`；⑤ 手势：TouchStroke/AccessibilityChannel 支持 `willContinue`/`continueStroke`（API26 闸门）+ gestureJson `continue/strokeId` 协议；⑥ app 侧：`AccessibilityWatchdog`（root su 自动重开+通知引导）、`RestrictedSettingsGuide`（Android13+ 侧载引导）、厂商引导矩阵扩 6 品牌 | 3.14 等待查找/缓存、3.15 无障碍可靠性、3.x key 监听与截图缺口 ✅；watchdog/引导为 app 域（features/app） |
| P2 收尾 | 本次 | NodeLib 四项：① `saveNode([path],[includeInvisible])`——XML 落盘（缺省 `<rootDir>/node_dump_<yyyyMMdd_HHmmss>.xml`，返回实际路径，懒人 saveNode/saveNodeNew 轻量对齐：**不做 .node 私有格式**）；② `findByIndex("0|1|2")`——逐层父内索引路径定位（容忍 `/` 分隔，与 getNodeXml 的 i 属性对应）；③ `setNodeMode([mode],[enableGetInVisibleNode])`——0=全部/1=简单模式（动态开关 FLAG_INCLUDE_NOT_IMPORTANT_VIEWS，以当前 serviceInfo 为基线仅动该位）+ enableInvisible 持久状态（getNodeXml 缺省 includeInvisible），**无懒人 root/句柄模式区分**；④ `closeAccessibility()`——settings 移除本服务组件 + accessibility_enabled 0（与 openAccessibility 对称）；沙箱 nodeLib 桥扩 saveNode/findByIndex（setNodeMode/closeAccessibility 不进桥）；selftest 节点增强段补 4 组用例（saveNode 落盘/findByIndex 路径/setNodeMode 往返/closeAccessibility 仅存在性） | 3.14 nodeLib 体系 saveNode/setNodeMode/closeAccessibility ✅、3.15 findByIndex/setNodeMode ✅ |
| 图色周边重标 | 逆向核对 | 2026-10-01 与懒人官方文档逐项核对代码实测：`findColorAllPoint`/`getScreenPixel`（C++ 原生，vision_extra.cpp）、`findPicAllPoint/findPicAll`（compat 懒人签名→findImageAll）、`findMultiColor` 系懒人偏移串签名（compat 拼接特征串→findFeature/findFeatureAll）、`setScreenScale`（虚拟分辨率→触控+图色换算）、`isDisplayDead`（双帧采样静止检测）均已实现，§3.12 相关行已重标 ✅。**确认为真缺口**（勿再误标已补）：字库点阵 OCR（setDict/useDict/findStr）、本地 Tesseract createOcr、LuaEngine.snapShot 区域一键截图、找圆 findCircle、imgui、ffi/gobridge；懒人三环境模式切换（setRootEnvMode/setHandleEnvMode/setAccessibilityEnvMode）与 beginThread 真多线程语义为架构性差异（本引擎 root 进程+沙箱双轨、同 state 协程），不做逐 API 对齐 | §3.12 图色域重标（本表为准）；真缺口清单固化 |

- 注入矩阵：root 主引擎（Server.kt `addLocalService`）+ IDE 沙箱（MainApp `registerService`）双路径本地注册 15 服务：http / luajava / LuaEngine / file / device / crypt / app / **plugin / ocr / media / ime / mysql** / compat（ime、mysql 为 LuaEnvContributor 直贡献全局表）。
- 构建验证：模拟器实测全量自测 `tools/selftest/main.lua`（a9183ad 时 **PASS=352 FAIL=0**；7dfe170 **PASS=370 FAIL=5**：4 ime 环境预期 + 1 fhttp 回调已知限制；MySQL 桥 **PASS=400 FAIL=0**；event/Fire/cv 批次 **PASS=442 FAIL=0** 最新基线，2026-09-14）。

---

## 一、本引擎 Lua API 实际暴露面（核实过的注册点）

### 1. C++ 侧注册（root 引擎进程 / 沙箱共用）

**基础环境** — `engine/src/main/cpp/core/LuaInterpreter.cc`
- `sleep(ms)`（可被 interrupt 打断）、`require`（自定义 CodeProvider 查找）、`loadfile`、`loadresource`
- Lua 5.4 全标准库（`luaL_openlibs`）：`string.* / table.* / math.* / io.* / os.* / utf8.*(标准) / coroutine / debug` 等
- 全局常量：`ROOT_DIR`（脚本项目根目录）

**图色/Display** — `engine/src/main/cpp/extension/Display.cpp`（全局函数，upvalue 绑定 Display 实例）
- 屏幕控制：`getBaseSize getBaseDensity getBaseDirection getRotation isChangeDirection resetScreen updateScreen keepScreen updateAndKeepScreen saveScreen`
- 取色找色（ByUpData 包装，自动刷帧）：`getColor getColorCount isColor whichColor findColor isFeature findFeature isImage whichImage findImage`
  - 相似度参数 0~1（内部换算 MAX_COLOR_SHIFT），支持 8 方向查找顺序常量表（FIND_ORDER）
- Bitmap 对象（`engine/src/main/cpp/3rd/autolua_vision/src/lua_vision.cc`）：全局 `loadImage(path)`；对象方法 `getColor getColorCount isColor whichColor findColor isFeature findFeature isImage whichImage findImage save clone getSize`；另 `saveImage cloneImage getImageSize`

**触控/InputManager** — `engine/src/main/cpp/extension/InputManager.cpp`
- 全局：`createPointer() tap(x,y) swipe(x1,y1,x2,y2,dur) keyDown(key) keyUp(key) keyPress(key) useHardwareInput()`
- Pointer 对象（多指手势）：属性 `x y major minor pressure size`（可写即移动），方法 `sync()`（提交一帧）、`up()`（抬起）

### 2. Java/Kotlin 侧注入（JavaObjectWrapper 包装）

**节点（无障碍）** — `engine/src/main/java/com/autolua/engine/extension/node/`
- 全局工厂：`UiSelector()`、`getRootInActiveWindow()`（返回 UiObject）、全局 `setText(text)`
- UiSelector 链式条件（`UiSelector.kt`）：`text textContains textStartsWith textEndsWith textMatches`、`desc` 同前缀系列、`clazz clazzContains clazzStartsWith clazzEndsWith clazzMatches`、`pkg pkgMatches`、`res resMatches`、`hint` 全系列、`clickable checkable checked enabled focusable focused longClickable scrollable selected`、`index depth/minDepth/maxDepth hasChild`
- UiObject：属性 `text desc clazz pkg res bounds(数组{L,T,R,B}) childCount parent clickable checkable checked focusable focused scrollable selected enabled editable visibleToUser longClickable`；方法 `child(i) findFocus(f) setText(t) recycle() find(selector) findOne(selector)`
- 匹配器：`ByMatcher.kt`（DFS 全树匹配，支持 child/hint 子条件）

**UI / 悬浮窗** — `app/src/main/java/com/autolua/autolua2/extension/`
- `UI` 服务：`showToast(msg,dur) createView(uri,LayoutParams) putSignal(sig,data) takeSignal(sig,timeout)`
- `FloatController` 服务（悬浮球启停控制）
- FloatView 对象：`show hide setPosition setSize setContent isShowing setAlpha`

**常量表**：`KeyCode`（HOME/BACK/MENU/VOLUME_UP/VOLUME_DOWN/POWER）、`LayoutParamsFlag`（NOT_FOCUSABLE/NOT_TOUCHABLE/NOT_TOUCH_MODAL）

### 3. 运行环境说明
- 主引擎 = root 进程（app_process，uid 0）`Server.kt` 组装：`DisplayImplement + InputManagerImp + UiAutomator` 全量注入
- IDE 沙箱（`LuaEngineExecutor`）：仅 `UI + FloatController + ROOT_DIR`，无图色/触控/节点
- 插件系统：`PluginManager`（dex/jar + so，方案七），OCR 走插件（接入点 `VisionCommandHandler.ocr` / `CapabilitiesHandler.SUPPORT_OCR` 已预留）

---

## 二、总体统计

| 大类 | 文档项数 | ✅ | 🟡/🟠 | ❌/🔌 | 覆盖率 |
|---|---|---|---|---|---|
| Lua 标准库类（math/array/io/string/time） | 59 | 41 | 4 | 14 | ~76% |
| 图色（color） | 49 | 2 | 10 | 37 | ~24% |
| 触控（touch） | 46 | 5 | 3 | 38 | ~17% |
| 节点（node + accessibility） | 111 | ~54 | ~31 | ~26 | ~76% |
| 设备/系统（device） | 78 | 3 | 3 | 72 | ~8% |
| UI 交互（ui） | 28 | 0 | 2 | 26 | ~7% |
| 扩展（extension） | 54 | 0 | 1 | 53 | ~2% |
| 独立大库（imgui/dynamicui/virtualscreen/console/luaopenov） | 178 | 0 | 0 | 178 | 0% |
| 网络与数据（network/json/luasocket） | 18 | 0 | 0 | 18 | 0% |
| 底层绑定（cipher/lfs/ffi/gobridge） | 22 | 0 | 0 | 22 | 0% |
| **合计** | **~570** | **52** | **~52** | **~466** | **~18%** |

> 口径说明：extension.md 与 accessibility.md 大量重复（nodeLib/Path/Gesture 重复计入），合计数按去重后的独立函数估算；iOS 章节按懒人官方索引说明不纳入。
> **节点行 2026-09-18 保守重估（快照未逐项重标）**：计入 0dc3a52/0bf13cb/本次节点增强批次（11 个 ACTION、sel 便捷动作与等待轮询、多窗口兜底、新条件、树遍历/几何派生、getNodeXml 导出、keepNode 快照缓存、无障碍开关）+ P2 收尾批次（saveNode/findByIndex/setNodeMode/closeAccessibility 四项转 ✅，其中 setNodeMode 计 🟡），✅+🟡 约 85/111 → **~76%**（保守值，精确以 §3.14/3.15 重标行与"⭐ 补齐批次记录"为准）。
> **2026-09-12 补齐后（3b3ef81 + 0dc3a52）**：网络与数据（json/luasocket）、底层绑定（cipher/lfs 高频项）转 ✅，设备/系统（app 控制、device 基础信息）、触控便捷层、节点 ACTION 补齐 —— 整体覆盖率约 **18% → ~45%**（见"⭐ 补齐批次记录"）。
> **virtualscreen 计数口径（2026-09-18）**：上表 15 项仍按未覆盖计数（实现未开始，数字不动）；该方向已立项——Shizuku 虚拟屏路线见 [plans/虚拟屏功能实施任务书.md](../../plans/虚拟屏功能实施任务书.md) §4/§5，API 规范已先行落 02 §20，落地后本表与 §3.19/§四 同步回填。
> **图色域重标（2026-10-01，逆向核对）**：§3.12 图色周边 5 项转 ✅（findMultiColor 系/findPicAllPoint/getScreenPixel/setScreenScale/isDisplayDead），图色行"~24%"已显著过时，精确覆盖率以 §3.12 重标行与批次表"图色周边重标"行为准；**真缺口清单**（字库点阵 OCR/Tesseract createOcr/LuaEngine.snapShot 区域截图/找圆/imgui/ffi）同步固化于批次表。

---

## 三、逐模块函数级对比

### 3.1 math（24 项）— ✅ 全覆盖

Lua 5.4 标准库（`3rd/lua/lmathlib.c`），全部同名同义。

`abs asin acos atan ceil cos deg exp floor log max min fmod modf randomseed random rad sin sqrt tan pi tointeger type ult` → ✅ 24/24

### 3.2 array（5 项）— ✅ 全覆盖

标准 `table` 库：`#长度 table.concat table.insert table.remove table.sort` → ✅ 5/5

### 3.3 string（12 项）— 🟡 部分覆盖

| 懒人 API | 状态 | 本引擎对应 |
|---|---|---|
| string.lower / string.upper | ✅ | 标准库 |
| string.find | ✅ | 标准库 |
| string.reverse | ✅ | 标准库 |
| string.format | ✅ | 标准库 |
| string.char / string.byte | ✅ | 标准库 |
| string.len / string.rep | ✅ | 标准库 |
| splitStr(str, sep) | ❌ | 无全局 split，需自行 table.gmatch 实现 |
| utf8.length | 🟡 | 标准 `utf8.len`（名字不同） |
| utf8.inStr / utf8.inStrRev | ❌ | 无（可用 utf8.codepoint 组合实现） |
| utf8.strReverse | ❌ | 无 |
| utf8.left / utf8.right / utf8.mid | ❌ | 无（可用 utf8.offset 组合实现） |
| utf8.strCut | ❌ | 无 |

### 3.4 time（5 项）— 🟡 少量覆盖

| 懒人 API | 状态 | 本引擎对应 |
|---|---|---|
| os.date / os.time | ✅ | 标准库 |
| systemTime() | ❌ | 可用 `os.time()*1000` 近似（毫秒级需补） |
| tickCount() | ❌ | 可用 `os.clock()` 近似（语义不同：CPU 时钟） |
| getNetWorkTime() | ❌ | NTP/HTTP 网络对时，无 |

### 3.5 io（6 项）— ✅ 全覆盖

`io.open io.input io.output io.read io.write io.lines` → ✅ 6/6（标准库）

### 3.6 lfs（12 项）— 🟡 高频项已补（0dc3a52 FileLib）

`attributes chdir currentdir dir link lock mkdir rmdir symlinkattributes touch unlock lock_dir` → 原判 0/12；**现已补**：全局 `fileExist/mkdir/delfile/readFile/writeFile/fileSize/listDir` + `file.*` 对象式 API（copy/rename/remove/deleteDir/mTime 等，FileLib.kt，root+沙箱双注入）。`lock/link/symlinkattributes` 等 POSIX 项仍缺（低频，不补）。

### 3.7 luasocket（1 项）— ✅ 已补（3b3ef81）

`http.request` → ✅ NetLib/HttpURLConnection 实现（表参数 + 多返回值 `body, code, headers`，另有 `http.get/http.post` 便捷式）

### 3.8 ffi（2 项）— ❌

`ffi.cdef ffi.load` → ❌ 0/2（Lua 5.4 无 LuaJIT ffi）

### 3.9 gobridge（2 项）— ❌

`gobridge.register gobridge.call` → ❌ 0/2

### 3.10 json（2 项）— ✅ 已补（0dc3a52）

| 懒人 API | 状态 | 说明 |
|---|---|---|
| jsonLib.encode | ✅ | C++ json_lib.cpp 将 cjson 绑定为全局 `json.encode`（table → JSON string） |
| jsonLib.decode | ✅ | 全局 `json.decode`（JSON string → table） |

### 3.11 cipher（6 项）— 🟡 核心已补（0dc3a52 CryptoLib）

`cryptLib.aes_crypt aes_keygen aes_ivgen rsa_generate_key rsa_encrypt rsa_decrypt` → **现已补**：`crypt.*`（md5/sha/sha256/sha512/base64 编解码/urlencode/urldecode/aes-128/rsa）+ 全局别名 `MD5/encodeBase64`。懒人专有 keygen/ivgen 辅助函数未单独对齐（aes/rsa 主链路可用）。

### 3.12 color 图色（49 项）— 🟡 核心有、周边缺

| 懒人 API | 状态 | 本引擎对应 / 说明 |
|---|---|---|
| findColor(x1,y1,x2,y2,offColor,sim,order) | 🟠 同名不同义 | 同名函数存在（Display/lua_vision），但参数为 `单色值+相似度+方向`，非懒人的偏移表字符串。迁移脚本必须改参数 |
| findImage / findPic / findPicEx / findPicFast | 🟡 | 模板匹配统一为 `findImage(模板Bitmap,相似度,方向)`；懒人的 png 路径字符串参数不同；`findPicFast` 需先 `loadImage` |
| keepCapture() | 🟡 | `keepScreen()`（保持当前帧，避免重复截图） |
| releaseCapture() | 🟡 | `updateScreen()`（释放/刷新帧） |
| snapShot(path,x,y,w,h) | 🟡 | `saveScreen(path)`（整屏保存，不支持区域裁剪参数） |
| getPixelColor(x,y) | 🟡 | `getColor(x,y)` |
| getColorNum(x1,y1,x2,y2,color,sim) | 🟡 | `getColorCount(color,sim)`（区域参数不同） |
| cmpColor(x,y,color,sim) | 🟡 | `isColor(x,y,color,sim)` |
| findMultiColor / findMultiColorT / findMultiColorAll / findMultiColorAllT | ✅ | compat 懒人签名 `findMultiColor(x1,y1,x2,y2,firstColor,offsetColorStr,sim,order)`（偏移串拼接→findFeature）；findMultiColorAll 走 C++ findFeatureAll 原生加速（CompatLib.kt L513/L234，2026-10-01 重标） |
| findPicAllPoint | ✅ | C++ 原生 findImageAll + compat `findPicAllPoint/findPicAll(x1,y1,x2,y2,pngNames,sim,order)` 懒人签名（vision_extra.cpp L154、CompatLib.kt L533，2026-10-01 重标） |
| getScreenPixel(x1,y1,x2,y2) | ✅ | C++ 原生区域像素二维数组导出（vision_extra.cpp L226，每行一表、元素 0xRRGGBB；虚拟屏 `vd:getScreenPixel` 同款，2026-10-01 重标） |
| colorToRGB(color) | ❌ | 无（Lua 侧可自行位运算） |
| colorDiff(c1,c2) | ❌ | 无 |
| setScreenScale(scale) | ✅ | compat 实现：脚本按虚拟分辨率坐标编写，引擎换算实际坐标（触控+图色，CompatLib.kt L263，2026-10-01 重标） |
| isDisplayDead(x1,y1,x2,y2,t) | ✅ | Display 屏幕区域静止检测：两次强制刷帧采样（间隔 delayMs）像素一致即 true（Display.h L45，2026-10-01 重标） |
| findCircle(...) | ❌ | 无霍夫找圆 |
| LuaEngine.snapShot | ❌ | 无 LuaEngine java 桥 |
| setDict / useDict | ❌ | 无字库体系 |
| findStr / findStrEx / findStrExNew | ❌ | 无点阵字库查找 |
| ocr / ocrj / ocrNew / ocrjNew | ❌ | 无点阵 OCR |
| ocrEx / ocrBinaryEx | ❌ | 无神经网络 OCR |
| createOcr / ocrText / ocrTextEx / ocrTextExJ / releaseOcr / setWhiteList | 🟡 | `ocr` 表 Lua 调用口已通（ocrText/ocrEx/ocrExNew/recognizeRaw/recognizeFile，a9183ad）；识别能力依赖插件实现，懒人 createOcr(Tesseract)/setWhiteList 形态未对齐 |
| YoloV5.detect | 🔌 | 插件预留（同上） |
| PaddleOcr.loadModel/loadOnnxModel/loadNnccModel/detect/detectWithPadding | 🔌 | 插件预留（同上） |

> 小结（2026-10-01 逆向+代码双向重标）：**单点取色/比色/计数/模板找图/懒人签名多点找色/找图全匹配/像素数组导出/屏幕缩放适配/区域静止检测**均已具备；仍缺**字库点阵 OCR（setDict/useDict/findStr）、本地 Tesseract createOcr、LuaEngine.snapShot 区域一键截图、找圆 findCircle**；OCR/YOLO 能力走插件（Lua 调用口已通，可参照懒人生态 PP-OCR 插件模式实装）。

### 3.13 touch 触控（46 项）— 🟡

| 懒人 API | 状态 | 本引擎对应 / 说明 |
|---|---|---|
| tap(x,y) | ✅ | 同名（`InputManager.tap`） |
| swipe(x1,y1,x2,y2,dur) | ✅ | 同名 |
| keyDown(key) / keyUp(key) / keyPress(key) | ✅ | 同名；键值用 `KeyCode` 表或 Android keycode |
| touchDown(id,x,y) / touchMove(id,x,y) / touchUp(id,x,y) | 🟡 | `Pointer` 对象协议：`p=createPointer(); p.x=..; p.y=..; p:sync(); p:up()`。单指简单场景无 touchDown/Move/Up 直伤 API |
| longTap(x,y,dur) | ❌ | 无（可用 Pointer+sleep 组合） |
| touchMoveEx(点集) | ❌ | 无增强滑动（但 Pointer 手势天然支持任意轨迹） |
| inputText(str) | 🟡 | 无全局 inputText；无障碍通道有全局 `setText()`（需焦点框）；root 通道注入文本能力未暴露 |
| imeLib.lock / unlock / setText / deleteChar / finishInput / keyEvent | ✅ | 全局 `ime` 表（`imeLib` 别名）：setText/commitText/inputLText/clearField/deleteChar/showKeyboard/hideKeyboard/finishInput/keyEvent/isActive/isOurIMEActive/setAsDefaultIME/setCurrentIME/showInputMethodPicker/getCurrentIMEName/openIMESettings；root shell（settings/am）+ 广播宿主 IME 服务执行，零 Context 依赖（ImeLib.kt，本批次） |
| setOnTouchListener(cb) | ❌ | 无取指坐标回调 |
| toast(msg) | 🟡 | `UI.showToast(msg,dur)` |
| hideToast() | ❌ | 无 |
| getUIText/setUIText/getUISelected/setUISelect/getUICheck/setUICheck/getUIEnable/setUIEnable/getUIVisible/setUIVisible/setUITextColor/setUIBackground/setUIConfig/getUIWebViewUrl/setUIWebViewUrl/getUISelectText/closeWindow | ❌ | 静态 UI 配置访问体系全缺（懒人 UI 配置文件方案） |
| showUI / showUIEx | ❌ | 无（MLN UI 体系独立存在但非此 API 形态） |
| createHUD / showHUD / hideHUD | ❌ | 无 HUD |
| createDragView / getDragViewPos / removeDragView / setDragViewOnClick | ❌ | 无（FloatView 有 show/hide/setPosition/setSize/setContent/isShowing/setAlpha，但非懒人 API 形态） |

### 3.14 node 节点（87 项）— 🟡 形式差异最大

懒人节点 = **全局工厂选择器 + 成员查找**（`local sel = text("x")` 直接查找）；本引擎 = **`UiSelector():text("x")` 构造条件 + `getRootInActiveWindow():findOne(sel)` 执行查找**，方向相反。**compat 兼容层已提供懒人全局工厂形态**（`text/desc/className` 等别名全套 → `UiSelector` 链，`auto.findOne/untilFind/untilFindOne/exists/waitFor`，CompatLib.kt L590-L626），存量懒人脚本节点段可近乎直迁（仅 `sel:findOne()` 无参语义差异：懒人=无限等待，本引擎=即时查找，改用 `untilFindOne`）。

**选择器条件（37 项）**

| 懒人 API | 状态 | 本引擎对应 |
|---|---|---|
| id(id) | 🟡 | `UiSelector():res(id)`（全字匹配） |
| idContains / idStartsWith / idEndsWith | ✅ | 同名（0bf13cb，→ res Pattern） |
| idMatches | ✅ | `resMatches` |
| text / textContains / textStartsWith / textEndsWith / textMatches | ✅ | 同名（UiSelector 方法） |
| desc / descContains / descStartsWith / descEndsWith / descMatches | ✅ | 同名 |
| className / classNameContains / classNameStartsWith / classNameEndsWith / classNameMatches | 🟡 | `clazz` 系列（前缀改名） |
| packageName / packageNameContains / packageNameStartsWith / packageNameEndsWith / packageNameMatches | 🟡 | `pkg`（全字+Matches；缺 Contains/StartsWith/EndsWith） |
| bounds(l,t,r,b) / boundsInside(l,t,r,b) | ✅ | 同名（0dc3a52）；另补 `boundsContains(x,y)` 点包含（本次） |
| drawingOrder(i) | ✅ | 同名（0bf13cb；API<24 视为不匹配） |
| depth(i) | ✅ | `depth(min[,max])`（还多出 minDepth/maxDepth） |
| index(i) | ✅ | 同名 |
| visibleToUser(bool) | ✅ | 同名（0dc3a52） |
| selected / clickable / longClickable / enabled / password / scrollable / checked / checkable / focusable / focused | ✅ | 全部同名（password 0bf13cb 补齐） |

**选择器查找执行（5 项）**

| 懒人 API | 状态 | 本引擎对应 |
|---|---|---|
| sel:findOne() | 🟠 同名不同义 | `sel:findOne([timeout])`：timeout>0 时 50ms 轮询至超时返回 nil（本次）；**无参语义不同**——懒人=无限等待，本引擎=即时查找。迁移脚本改 `findOne(2147483647)` 或 `sel:untilFindOne()`（=懒人无参语义，无限轮询） |
| sel:findAll() | ✅ | `sel:findAll([timeout])`：无参即时，timeout>0 轮询至非空或超时空表（本次） |
| sel:findOnce(i) | ✅ | 同名（0bf13cb） |
| sel:waitFor / sel:untilFindOne | ✅ | `sel:waitFor([timeout])` 返回 boolean（无参=无限等）、`sel:untilFindOne()` 无限轮询（本次） |
| sel:click() / sel:longClick() | ✅ | 同名（0bf13cb）；**语义差异**：本引擎点击**首个匹配**（懒人为全部匹配），批量用 autoCollection |

**节点属性读取（14 项）**（UiObject，点号访问）

| 懒人 API | 状态 | 本引擎对应 |
|---|---|---|
| node:id | 🟡 | `obj.res` |
| node:text / node:desc | ✅ | `obj.text / obj.desc` |
| node:className | 🟡 | `obj.clazz` |
| node:packageName | 🟡 | `obj.pkg` |
| node:bounds | ✅ | `obj.bounds`（返回 {l,t,r,b} 数组） |
| node:boundsInParent | ✅ | `obj.boundsInParent`（数组{L,T,R,B}，0bf13cb） |
| node:childCount | ✅ | `obj.childCount` |
| node:childs | ✅ | `obj:childs()`（0bf13cb；另有 firstChild/lastChild/sibling 系/siblings/hasChildren，本次） |
| node:parent | ✅ | `obj.parent` |
| node:drawingOrder / node:depth | ✅ | `obj.drawingOrder`（API24+，低版本 -1）/ `obj:depth()`（0bf13cb）；另补 indexInParent()/centerX/centerY/width/height/center/point（本次） |
| node:toJson | ❌ | 无 |

**节点动作（20 项）**（0dc3a52 起 UiObject ACTION 分发全量落地，以下均 ✅）

| 懒人 API | 状态 | 本引擎对应 |
|---|---|---|
| node:setText(t) | ✅ | 同名（ACTION_SET_TEXT） |
| node:click / node:longClick | ✅ | 同名（0dc3a52 修复 index 闭包分发，`nd:click()` 可直接调用） |
| node:scrollTo / scrollUp / scrollDown / scrollLeft / scrollRight / scrollForward / scrollBackward | ✅ | 同名（0dc3a52；scrollTo(row,col) 表格 API26） |
| node:focus / node:clearFocus | ✅ | 同名（0dc3a52） |
| node:copy / node:paste / node:cut | ✅ | 同名（0dc3a52） |
| node:select / node:setSelection / node:setProgress | ✅ | 同名（setSelection(start,ends)、setProgress(0~1)，0dc3a52） |
| node:collapse / node:expand / node:contextClick | ✅ | 同名（0dc3a52） |

**nodeLib 体系（11 项）**

| 懒人 API | 状态 | 说明 |
|---|---|---|
| nodeLib.getNodeXml() | ✅ | 全局 `getNodeXml([includeInvisible])` 整树 XML 导出（本次；根元素 `<nodes w h>`，节点元素 `<n>` 含 i/d/c/t/de/id/p/b/bp/布尔态/do 属性，布尔 0/1、文本 XML 转义；includeInvisible 缺省走 setNodeMode 状态（enableInvisible，默认 false 时不可见子树跳过）；VSCode 插件按此契约解析） |
| nodeLib.saveNode / saveNodeNew | ✅ | 全局 `saveNode([path],[includeInvisible])` XML 落盘（P2 收尾；懒人 saveNode/saveNodeNew 轻量对齐：写通用 XML，**不做 .node 私有格式**；缺省落 `<rootDir>/node_dump_<yyyyMMdd_HHmmss>.xml`，自动建父目录，返回实际写入路径 string，失败抛错；沙箱 nodeLib 桥同款） |
| nodeLib.lockNode / unlockNode | ✅ | = `keepNode()/releaseNode()` 轻量版（本次）：仅 root 快照缓存（锁定当前界面/恢复实时），无懒人节点级锁定语义 |
| nodeLib.keepNode / releaseNode | ✅ | 全局 `keepNode()/releaseNode()`（本次）：keepNode 后选择器查找走缓存快照，releaseNode 清缓存 |
| nodeLib.updateNode / setNodeMode | ✅/🟡 | `updateNode()` ✅（重抓 root 刷新缓存，本次）；setNodeMode 🟡（P2 收尾：`setNodeMode([mode],[enableGetInVisibleNode])`——mode 0=全部/1=简单模式动态开关服务 FLAG_INCLUDE_NOT_IMPORTANT_VIEWS（以当前 serviceInfo 为基线仅动该位）+ enableInvisible 持久状态（getNodeXml 缺省 includeInvisible）；**无懒人 root/句柄模式区分**） |
| nodeLib.openAccessibility / closeAccessibility | ✅/✅ | `openAccessibility()` ✅（本次：root shell 写 settings 自动启用，追加式不清其他服务；失败跳系统无障碍设置页返回 false）；closeAccessibility ✅（P2 收尾：从启用列表移除本服务组件（移除后为空写 `''`）+ accessibility_enabled 0，两步 shell 退出码均 0 返回 true；拿不到包名（root 进程无 Context）返回 false） |

### 3.15 accessibility（24 项）— 与 node/extension 重复，独立项如下

| 懒人 API | 状态 | 说明 |
|---|---|---|
| nodeLib.isAccServiceOk() | ✅ | 全局 `isAccServiceOk()`（本次）：服务已连接或 settings 启用列表含本服务组件即 true |
| nodeLib.openSnapService / isSnapServiceOk | ❌ | 无截图服务开关查询（本引擎截图权限在 Java 侧管理） |
| nodeLib.findNextNode / findPreNode | 🟡 | `node:sibling(offset)/nextSibling()/previousSibling()` 可替代（本次） |
| nodeLib.findChildNodes / findParentNode / findByIndex | 🟡 | findByIndex ✅（P2 收尾：全局 `findByIndex("0|1|2")` 每层父内索引路径定位，容忍 `/` 分隔，与 getNodeXml 导出的 `i` 属性对应——节点工具路径可直接回放；越界/负数/非法 token/中途断链返回 nil，空路径抛错；沙箱 nodeLib 桥同款）；findChildNodes / findParentNode ❌（可用 `childs()/parent/child(i)` 组合替代） |
| nodeLib.findOne / findAll / findInNode / setText / click | 🟡 | 能力已由 `sel:findOne([timeout])/findAll([timeout])/setText/click` 等便捷动作覆盖（见 3.14，本次含等待轮询/多窗口兜底）；nodeLib 命名空间形态未做 |
| nodeLib.setNodeMode / updateNode | 🟡/✅ | `updateNode()` ✅（重抓 root 刷新缓存，本次）；setNodeMode 🟡（P2 收尾：`setNodeMode([mode],[enableGetInVisibleNode])`——0=全部/1=简单模式 + enableInvisible 持久状态，见 3.14；无 root/句柄模式区分） |
| Path.new / setStartTime / setDurTime / addPoint | ❌ | 无手势描述对象（Pointer 协议可替代） |
| Gesture.new / addPath / dispatch | ❌ | 无批量手势对象 |

### 3.16 device（78 项）— ❌ 最大缺口区

**✅/🟡 已覆盖（6 项）**

| 懒人 API | 状态 | 本引擎对应 |
|---|---|---|
| sleep(ms) | ✅ | 同名（LuaInterpreter，支持 interrupt 打断，优于懒人） |
| print / printEx | ✅ | `print` 标准库（printEx 无） |
| xpcall | ✅ | 标准库 |
| getDisplaySize() | 🟡 | `getBaseSize()` |
| getDisplayRotate() | 🟡 | `getRotation()` |
| getWorkPath() | 🟡 | `ROOT_DIR` 全局常量 |

**❌ 应用控制（9 项）**：`runApp stopApp appIsFront appIsRunning frontAppName getCurrentActivity getPackageName restartScript exitScript`
> 脚本实用性刚需之首。root 进程可直接 `am start / am force-stop`，落地成本低。

**❌ 设备信息（26 项）**：`getCpuArch getSdPath getApkVerInt getScriptVersion getSubscriberId getSimSerialNumber getDisplayDpi getBatteryLevel getOaid getInstalledApk getInstalledApps getInsallAppInfos getDisplayInfo getSensorsInfo getDeviceId getBrand getBootLoader getBoard getManufacturer getProduct getDevice getModel getHardware getId getFingerprint getCpuAbi/getCpuAbi2 getSdkVersion getOsVersionName getWifiMac`
> 多为 Build.XXX/TelephonyManager 读取，Java 侧一次性批量暴露即可。

**❌ 系统操作（14 项）**：`vibrate setDisplayPowerOff setDpiToVir setDpiToRealy lockScreen unLockScreen setBTEnable setWifiEnable setAirplaneMode sendSms phoneCall runIntent installApk scanImage`
> root/设备管理器场景刚需。

**❌ 执行与控制流（10 项）**：`exec(cmd) rnd setTimer setMainThreadPause setMainThreadResume setStopCallBack setUserEventCallBack setPluginEventCallBack sendPluginEvent setNotifyEventCallBack checkIsDebug getRunEnvType setLogOff setRootEnvMode/setHandleEnvMode/setAccessibilityEnvMode`
> `exec` root 命令执行是派大星/懒人共同的核心能力，**高优**；`setTimer` 多任务刚需；`rnd` Lua math.random 可替代。

**❌ 剪贴板/输入（2 项）**：`readPasteboard writePasteboard`

**❌ 媒体/资源（5 项）**：`playAudio stopAudio extractAssets extractApkAssets installLrPkg`

**❌ 回调与缓存（4 项）**：`LuaEngine.registerExitCallback setSnapCacheBitmap setSnapCacheTime getLrApi getUIConfig`

**❌ 压缩（2 项）**：`zip unZip`

### 3.17 ui 交互（28 项）— 🟡（动态 UI 已落地，2026-09-15）

**已落地（`ui` 表，JSON 控件树 + op 协议，../../02-Lua-API规范.md §17.1）**：`showUI` 等价→`ui.createWindow`（原生 View 悬浮窗+拖动）；`getUIText/setUIText/getUISelected/...` 读写等价→`ui.get/ui.set`（语义按控件树寻址，非 page+id 协议）；`closeWindow`→`ui.close`；HUD→`FloatController`/自建窗口。
仍不对齐：懒人 XML DSL 字符串格式、page+id 协议形态、HUD native 状态持久化（迁移脚本需改写调用层）。
等价：`toast`→`UI.showToast`/`ui.toast`；悬浮窗能力另有 `UI.createView + FloatView`。

### 3.18 extension（54 项）— ❌（去重后独立项如下）

| 懒人 API | 状态 | 说明 |
|---|---|---|
| import(java类) | ❌ | 无 Java 类导入桥（JavaObjectWrapper 仅注入预注册服务；`LuaEngine.loadApk`→PluginManager 等价但 Lua 侧无 import 形态） |
| LuaEngine.getContext | ❌ | 无 Context 暴露 |
| connectSQL / closeSQL / executeQuerySQL / executeSQL | ✅ | 懒人全局四函数 + 对象式 `mysql.connect/query/execute/ping/close` + `mysql.escape`（零依赖 MySQL 协议客户端 MySqlLib.kt：4.1+ 握手、native/sha2 快速认证、LCB 结果集解析；sha2 完整认证需 SSL/RSA 不支持）；root+沙箱双注册（本批次） |
| beginThread / newThread / th.stopThread | ❌ | 引擎为单 worker 线程模型，无 Lua 多线程 |
| qrDecode | ❌ | 无二维码解析（可走插件） |
| MD5 / fileMD5 | ❌ | 无 |
| encodeBase64 / decodeBase64 / getFileBase64 | ❌ | 无 |
| encodeUrl / decodeUrl | ❌ | 无 |
| bdOcr | 🔌 | 插件预留 |
| getImage / rotateImage / scaleImage / binaryImage / binaryRect | ❌ | 图片处理只有 loadImage/saveImage/clone/getSize，无旋转/缩放/二值化 |
| fileExist / mkdir / delfile / readFile / writeFile / fileSize | ❌ | 全局文件函数全缺（Lua io.open 可替代 read/write，但 mkdir/delfile/size 需 lfs 或全局函数补齐）——**高频刚需** |
| nodeLib.* / Path / Gesture | 同 3.14/3.15 | 重复项 |

### 3.19 其余整模块未实现（12 模块 178+18+22 项）

| 模块 | 项数 | 内容 |
|---|---|---|
| dynamicui | 63 | 🟡 **等价已落地**：`ui.createWindow/ui.add/ui.set`（JSON 控件树直建原生 View，2026-09-15），API 形态不同（ui.add 非 ui.newLayout/addButton 链），调用层需适配 |
| imgui | 82 | ❌ 不对齐（懒人=native Dear ImGui 渲染到 Surface；本引擎 canvas 控件可覆盖轻量绘制，高频重绘场景不做） |
| console | 9 | ✅ **对齐**：`console.show/dismiss/println/clear/isShowing`（可拖动日志悬浮窗，200 行环形缓冲，UiLib 内置） |
| virtualscreen | 15 | 🟢 **首批落地（Shizuku 路径，虚拟屏任务书 §4.3-4.5）**：`virtualDisplay` 表 + vd 对象（建屏/拉起/定向注入/按屏帧取全链路，02 §20 已回填）。15 项对照结论摘要——等价/改名落地 12 项：createVirtualDisplay→`virtualDisplay.create{}`（改名）、runAppWithVirtualDisplay→`vd:runApp`（改名）、tap/longTap/swipe→`vd:tap/longTap/swipe`（等价，注入带 displayId）、findColor/findColorAllPoint/findFeatureAll→同名（等价，all 系由 images+bmp 窗口扫描组合实现、收集序行优先）、findImage→`vd:findImage`（等价，全屏形态）、findImageAll→同名（组合实现）、saveScreen→`vd:saveScreen`（等价，仅 png）；暂缺 2 项：复杂手势 gesture（后续批次）、节点系操作（虚拟屏内无无障碍通道，02 §20.4 声明不做）；坐标系形态差异 1 项：本引擎 vd 对象绑定 displayId（坐标即虚拟屏坐标），无懒人全局 displayId 直传形态。root 直建路径随任务书 §6.1 未落地 |
| luaopenov | 9 | `cv.snapShot/newPoint/newInt/...` OpenCV Mat/指针封装 |
| network | 15 | `httpGet httpPost asynHttpGet asynHttpPost downloadFile uploadFile startWebSocket closeWebSocket sendWebSocket LuaEngine.httpPost/httpPostData/httpGet/sendMail/sendMailWithFile/sendMailWithMultiFile` |
| luasocket | 1 | `http.request` |
| json | 2 | `jsonLib.encode/decode` |
| cipher | 6 | `cryptLib.*` AES/RSA |
| lfs | 12 | luafilesystem 全部 |
| ffi | 2 | `ffi.cdef/load` |
| gobridge | 2 | `gobridge.register/call` |

---

## 四、补齐优先级建议（结合脚本生态刚需）— 已于 3b3ef81 + 0dc3a52 基本清账

| 优先级 | 缺口 | 建议落点 | 状态 |
|---|---|---|---|
| P0 | `jsonLib.encode/decode` | 全局 json 库 | ✅ 0dc3a52（C++ 绑 cjson） |
| P0 | `node:click/longClick` + 滚动 7 项 | UiObject ACTION 分发 | ✅ 0dc3a52（11 个 ACTION） |
| P0 | 全局文件函数 `fileExist/mkdir/delfile/readFile/writeFile/fileSize` | 全局函数注入 | ✅ 0dc3a52（FileLib contribute + listDir） |
| P1 | `runApp/stopApp/appIsFront/appIsRunning/frontAppName/exec` | root `am/pm` 封装 | ✅ 0dc3a52（AppLib） |
| P1 | `touchDown/touchMove/touchUp/longTap` 便捷 API | Pointer 协议薄封装 | ✅ 0dc3a52（InputManager C++ 便捷层） |
| P1 | `httpGet/httpPost/downloadFile` | HTTP Lua 绑定 | ✅ 3b3ef81（http.request/get/post；downloadFile 未做，可由 request binary 变通） |
| P1 | 节点选择器补 `idContains/idStartsWith/idEndsWith/bounds/visibleToUser` | UiSelector 分支 | ✅ 0dc3a52（含 editable/boundsInside）+ 0bf13cb（password/drawingOrder/pkg 系列） |
| P2 | OCR Lua API（createOcr/ocrText/findStr 系列） | 插件方案接口定型 | ✅ a9183ad（`ocr.ocrText/ocrEx/ocrExNew/recognizeRaw` Lua 调用口已通；识别能力依赖插件实现） |
| P2 | `systemTime/tickCount/getNetWorkTime/setTimer/rnd` | 全局函数 | ✅ 0dc3a52（compat 兼容层 setTimer/frandom/时间全局）+ a9183ad（timer 表/事件回调） |
| P2 | `readPasteboard/writePasteboard` | 注入服务 | ✅ fba79cd（DeviceLib，root IClipboard 反射） |
| P3 | `getNodeXml/saveNode`（节点调试导出） | ByMatcher 树序列化 | ✅ `getNodeXml([includeInvisible])` ✅ + `saveNode([path],[includeInvisible])` ✅（P2 收尾，XML 落盘返回路径；另补 findByIndex/setNodeMode/closeAccessibility，详见批次表"节点增强/P2 收尾"行） |
| P3 | lfs/加密/imgui/dynamicui/virtualscreen | 插件/MLN 承载 | 🟡 lfs 高频项/加密核心已补；**动态 UI 已落地（aaf58e9，`ui` 表/console）**，dynamicui 等价 ✅、imgui 不做；**virtualscreen 🟢 首批落地（Shizuku 路径，§4.3-4.5，`virtualDisplay` 表 + vd 对象，15 项对照见 3.19 表）**，root 直建路径随任务书 §6.1 未落地 |
| 追加 | 图片二值化/旋转、path、coordinate、media、timer、事件回调、fhttp 全量、ws 双向、帧导出、plugin/ocr Lua 口 | 各域服务 | ✅ a9183ad（详见顶部批次表"扩展模块"行） |




