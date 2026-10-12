# JY-Frame（autolua2）与派大星（FireFly）API 对比报告

> 生成日期：2026-09-12
> 对比基准：`doc-firefly/md/lua/**`（派大星 FireFly Lua API 文档，48 个模块）；`md/python/**` 为派大星 Python 双语言版，本引擎为 Lua 路线不纳入逐项对比。
> 对象仓库：本仓库 `autolua2/`（engine + app）
> 方法：子代理分组逐模块提取"方法列表"表格共 **1354 项**，对照本引擎已核实 API 面逐项判定。
> 用途：识别 JY-Frame 相对派大星的 API 缺口，指导补齐计划。派大星文档仅供内部学习参考。

## 状态图例

| 标记 | 含义 |
|---|---|
| ✅ 同名实现 | 同名同义可直接用 |
| 🟡 等价实现 | 能力覆盖但名/形不同（备注标注对应物） |
| 🟠 同名不同义 | 同名但语义不同（迁移陷阱） |
| ❌ 未实现 | 引擎无该 Lua API |
| 🟦 底层有、缺 Lua 暴露 | Java/C++ 已有该能力（root daemon/插件/帧数据/libhv 等），仅缺 Lua API，补齐成本最低 |

---

## ⭐ 补齐批次记录（实施追踪，2026-09-12）

> 下文逐项判定为报告生成时（3b3ef81 之前）的快照，未逐项重标；实际缺口以下表批次为准。

| 批次 | 提交 | 内容 | 对应本报告缺口 |
|---|---|---|---|
| 三件套 | `3b3ef81` | `http.request/get/post`（NetLib）；luajava 反射桥（全局 `import/importClass`）；`LuaEngine.getContext/loadApk`（jvm 模块）；thread 多线程原语（同 state 协程） | fhttp 部分承接、thread/jvm 提升 |
| 16 大类 | `0dc3a52` | json（cjson 全局绑定）；file 域（`file.*` + 全局别名，覆盖 lfs 高频项）；device 域（Build/电量/DPI/MAC/IP）；crypt 域（md5/sha/base64/url/aes/rsa，openssl 核心）；app 域（`runApp/stopApp/appIsFront/exec` 等，shell 🟦 12 项同步解锁）；compat 兼容层（utf8/table/math 扩展、setTimer/frandom 补齐、时间全局）；触控（touchDown/Move/Up/longTap + KeyCode 扩 13 键）；节点（UiObject 11 ACTION + UiSelector 补条件） | 7.2 P0 第 1/2/3/4/6 项 + P1 第 1(部分)/2/3/4/5 项 ✅ |
| 系统操作 | `fba79cd` | `readPasteboard/writePasteboard`（clipboard 2/9 项）+ `vibrate`；`zip/unZip`（zip 基础 2 项）；AppLib 系统操作 15 项 | clipboard/zip 基础 ✅ |
| 节点动作 | `0bf13cb` | `sel:findOne/findAll/findOnce/exists/click/longClick/setText`（autoSelector 方向反转消除）+ `password/drawingOrder` 条件；UiObject `boundsInParent/depth()/childs()`；`findImageAll`（模板全匹配）+ `findPicAllPoint`；`inputText`/`toast/hideToast` | autoSelector 90%→~96%、B 组 sel 侧动作 ✅ |
| 扩展模块 | `a9183ad` | `coordinate` 12 项全补（p2r/r2p 系）；`media` 表 11 项 + playAudio/stopAudio；`timer` 表（set/clear/clearAll）+ setTimeout/setInterval；事件回调 6 项（setXxxEventCallBack/sendXxxEvent）；`fhttp` 24 项全补（method 族/download/upload/setTimeout/setProxy/异步族）；`ws` 12 项（clientConnect/Poll/Send/Close/IsOpen + serverStart/Poll/Send/Broadcast/Stop/IsRunning，事件轮询模型）；`getScreenFrame`/`captureScreen.getFrame/getSharedMemorySize`（BGRA→RGBA 帧 🟦 解锁）；`plugin` 表 9 项（list/load/unload/getLoaded/install/getPluginPath/getSoPath）；`ocr` 表 6 项（isSupported/ocrText/ocrEx/ocrExNew/recognizeRaw/recognizeFile）；`images.binarize/rotate` + 懒人别名；`path` 19 项；`extractAssets` | coordinate/media/timer/fhttp/websocket/plugin 全清 ✅、captureScreen 帧 🟦 解锁、event 部分承接、dotocr 调用口承接（识别能力依赖插件实现） |
| 输入法/JPEG/异步网 | `7dfe170` | `ime` 输入法 16 项全补（setText/commitText/inputLText/clearField/deleteChar/showKeyboard/hideKeyboard/finishInput/keyEvent/isActive/isOurIMEActive/setAsDefaultIME/setCurrentIME/showInputMethodPicker/getCurrentIMEName/openIMESettings；root shell settings/am + 广播宿主 IME 服务，零 Context，ImeLib.kt）；JPEG 编解码 `images.toJpg/fromJpg/saveJpg/readJpg` + `compress` 别名（libjpeg-turbo 3.0.4 静态编译接 C++ extension）；fhttp 异步解耦（`requestAsync/downloadAsync/uploadAsync` 独立 Java 线程 + AsyncHandle 句柄 isDone/get/cancel，解除协程内 Java 阻塞限制；回调族保留为主线程便利形态）；OCR demo 插件全链路自测通过 | ime 14 项 ✅、images.compress ✅、fhttp 异步限制解除 ✅、dotocr 链路验证 ✅ |
| autoCollection | `e5c788f` | `autoCollection.find/findAll/findOne/create` + 容器 11 项（isEmpty/nonEmpty/size/count/get/first/last/toList/toArray/each/contains 指纹比对）+ 二次筛选 3 项（find/findOne/setText 批量）+ 批量动作 19 项（遍历返回成功数），compat 纯 Lua 集合包装；**同步修复 UiObject index 闭包分发 bug**（动作方法名未在 index() 返回 2，`nd:click()` 实际不可调用）+ scroll/contextClick 魔法数换框架常量 + 新增 dismiss/show/pageUp×4/imeEnter/tooltip×2/scrollTo/setProgress/performAction/next/previous 系 | autoCollection 33 项 ✅、autoUiobject ACTION 大头 ✅ |
| thread 修复/event/Fire/cv | 本批次 | ① **thread/timer 偶发抖动修复**：ThreadLib start 两阶段化（begin 登记 → C 注册 gTaskCo → submit 驱动，消除池线程先于注册 nativeResume 的 ERROR 竞态）+ 终态任务保留至 join（finish 不自删，eraseTask/30s 过期清扫移除）+ CANCELLED 双重 unref 修复；② **event 总线**：event.on/off/removeListener(s)/_emit 多播 + onStopEvent/onToast/onNotification/onTouch/onClick/onHotfixFinish/getPermissionStatus/hasTouchPermission（系统事件 = 无障碍服务 → EventBridge 镜像文件 → 引擎 60ms 轮询协程，监听计数驱动转发开关；onVoice/onGesture 声明不支持）；③ **project Fire 热更**：hotfixFire/FromUrl/FromDir/hasPendingHotfix/rollback/clearCustomFire/setHotFixCallback（纯 Lua，backup 快照回滚）；④ **cv 库**：零依赖纯 C++ Mat 体系 60+ 项（cvtColor 12 常量/threshold/adaptiveThreshold/blur 系/形态学/算术位运算/inRange/colorBinary/equalizeHist/resize/flip/rotate 系/cropBlackBorder/extractContours/despeckle/minMaxLoc/mean/split/merge/drawRect/drawLine/drawCircle/encodePng/getMatStats + images.getMat/getGrayMat/toJavaMat 互通 + captureMat） | event 13 项 ✅~85%、project 9 项 ✅、opencv 加工管线主面 ✅ |
| 节点增强 | 本次 | ① **等待轮询**：`sel:findOne([timeout])/findAll([timeout])`（50ms 轮询）、`sel:untilFindOne()`（无限轮询）、`sel:waitFor([timeout])`（boolean）、compat `auto.untilFind/untilFindOne`；② **新条件**：content 五系（text OR contentDescription 任一匹配，派大星 OR 语义对齐）、`and(sel)` 交集组合器（**and 为 Lua 保留字，别名 andSel(sel)**，可链式叠加）、`boundsContains(x,y)` 点包含、`screenCenterX/Y([tol])/screenCoverage([min])`（tol/min 屏幕尺寸比例 0-1）；③ **多窗口兜底**（便捷动作主窗口无结果时遍历 otherRoots）；④ **UiObject 树遍历/几何派生**：sibling(offset)/nextSibling/previousSibling/siblings/firstChild/lastChild/indexInParent/hasChildren + centerX/centerY/width/height/center/point（center/point 返回 x,y 两值）；⑤ **NodeLib**：`keepNode/releaseNode/updateNode`（懒人式 root 快照缓存）、`getNodeXml([includeInvisible])`（XML 树导出契约 `<nodes w h><n i d c t de id p b bp ck cc ch en fo fc sc lc se ed vt pw do>`）、`openAccessibility`（root shell 写 settings 自动启用，失败跳设置页）/`isAccServiceOk`；⑥ IDE 沙箱 `nodeLib` 只读桥（getNodeXml/isAccServiceOk）+ VSCode 插件"抓取节点树"（autolua2.grabNodeTree：EXECUTE 通道拉取/剪贴板文件降级 → WebView 树面板 → 一键生成懒人式/UiSelector 式选择器代码） | autoSelector 硬缺口清零（boundsContains/screenCenter 系/and ✅）、autoUiobject 几何派生/树遍历 11 项 🟡→✅、node 模块条件 ✅、waitFor/untilFindOne/findOnce ✅、auto.content 系/untilFind 系 ✅ |

- 注入矩阵：Server.kt（root）+ MainApp.kt（沙箱）双路径本地注册 **16 服务**（plugin/ocr/media/ime/mysql/event；ime、mysql、event 为 LuaEnvContributor 直贡献全局表）；模拟器实测全量自测（a9183ad 时 **PASS=352 FAIL=0**；7dfe170 **PASS=370 FAIL=5**：4 ime 环境预期 + 1 fhttp 回调已知限制；autoCollection 批次 **PASS=393 FAIL=0**；MySQL 桥 **PASS=400 FAIL=0**；thread 修复 + event/Fire/cv 批次 **PASS=442 FAIL=0**，连续三轮仅 1 次外部 302 偶发）。
- 仍未补：OCR/YOLO **真实识别实现**（Lua 调用口已通、demo 插件链路已验证，等真实算法插件）、viewWrapper/canvas（不对齐声明）、event.onVoice/onGesture（无语音/手势源）、warp 系/putText（cv 声明不对齐）—— 见 7.2 表状态列。

---

## 一、总体统计

| 分组 | 模块数 | API 项数 | ✅ | 🟡 | 🟠 | 🟦 | ❌ | 可用率(✅+🟡) |
|---|---|---|---|---|---|---|---|---|
| 图色+字符（A 组） | 12 | 266 | 15 | 38 | 1 | 18 | 194 | 20% |
| 脚本+节点（B 组） | 8 | 379 | 116 | 155 | 0 | 9 | 99 | 72% |
| 触控+基础（C 组） | 11 | 148 | 32 | 28 | 0 | 1 | 87 | 41% |
| 应用/系统/网络/文件（D 组） | 15 | 286 | 15 | 25 | 1 | 14 | 231 | 14% |
| 界面/线程/扩展（E 组） | 13 | 275 | 7 | 15 | 3 | 9 | 241 | 8% |
| **合计** | **59** | **1354** | **185** | **261** | **5** | **51** | **852** | **33%** |

> 与懒人精灵对比（API_GAP_LR.md，~18% 覆盖）相比：派大星 API 面约为懒人的 2.4 倍，但**选择器/节点体系覆盖率显著更高（88–98%）**，因为派大星选择器与本引擎 UiSelector 形态接近；差距主要在 OpenCV Mat 体系、AI 推理（OCR/YOLO/语音）、View 级 UI 三大块。
> **2026-09-12 补齐后（3b3ef81 + 0dc3a52）**：json/file/lfs 高频/app+shell 核心/device 基础/openssl 核心/touch 便捷层/timer/frandom/utf8 扩展转 ✅ —— 派大星口径可用率约 **32% → ~50%**（见"⭐ 补齐批次记录"）。
> **2026-09-18 节点增强批次后（B 组行与合计行已同步）**：autoSelector 6 项硬缺口（boundsContains/screenCenterX/Y/screenCoverage/and）与 node 模块 6 项 ❌ 转 ✅、等待轮询（waitFor/untilFindOne/findOnce）与 autoUiobject 几何派生/树遍历 11 项、auto content 系/untilFind 系 🟡→✅ —— 可用率 **32% → ~33%**（B 组 68% → 72%，✅+🟡 434 → 446）。

### 模块覆盖率总表（59 模块）

| 模块 | 项数 | 覆盖(✅+🟡) | 率 | 模块 | 项数 | 覆盖 | 率 |
|---|---|---|---|---|---|---|---|
| autoSelector | 78 | 76 | 97% | node | 59 | 58 | 98% |
| auto | 82 | 66 | 80% | time | 22 | 17 | 77% |
| math | 34 | 26 | 76% | utf8 | 13 | 11 | 85% |
| string | 9 | 9 | 100% | table | 4 | 4 | 100% |
| io / ioWrapper | 15 | 15 | 100% | frandom | 12 | 7 | 58% |
| touch | 35 | 17 | 49% | shell | 33 | 12 | 36% |
| thread | 20 | 7 | 35% | plugin | 9 | 5 | 56% |
| floatMenu | 3 | 3 | 100% | float | 9 | 3 | 33% |
| activity | 9 | 3🟦 | 33% | toast | 4 | 2 | 50% |
| layoutResult | 24 | 6 | 25% | jvm | 2 | 1🟦 | 50% |
| captureScreen | 14 | 5 | 36% | images | 30 | 6 | 20% |
| imagesWrapper | 25 | 4 | 16% | opencv | 110 | 14 | 13% |
| opencvJava | 22 | 4 | 18% | hid | 7 | 2 | 29% |
| coordinate | 12 | 1 | 8% | permission | 9 | 2🟦 | 22% |
| global | 5 | 2 | 40% | json | 2 | 1🟦 | 50% |
| event | 13 | 1 | 8% | project | 9 | 1 | 11% |
| device | 51 | 5 | 10% | file | 32 | 3 | 9% |
| path | 19 | 1 | 5% | app | 23 | 0 | 0% |
| media | 12 | 0 | 0% | fhttp | 24 | 0 | 0% |
| websocket | 9 | 0 | 0% | lfs | 13 | 0 | 0% |
| zip | 20 | 0 | 0% | timer | 4 | 0 | 0% |
| clipboard | 9 | 0 | 0% | ime | 14 | 0 | 0% |
| openssl | 14 | 0 | 0% | canvas | 20 | 0 | 0% |
| paint | 7 | 0 | 0% | dialogs | 3 | 0 | 0% |
| dotocr | 6 | 0 | 0% | ppocr | 5 | 0 | 0% |
| yolo | 18 | 0 | 0% | yolo26 | 9 | 0 | 0% |
| zxing | 5 | 0 | 0% | sherpa | 13 | 0 | 0% |
| webElementWrapper | 7 | 0 | 0% | viewWrapper | 152 | 4 | 3% |
| autoCollection | 33 | 14 | 42% | autoUiobject | 98 | 55 | 56% |

> 2026-09-18 节点增强批次口径：autoSelector 70→76（硬缺口 boundsContains/boundsInside/screenCenterX/Y/screenCoverage/and 清零）、node 52→58（同）；autoUiobject/auto 覆盖数不变（几何派生/树遍历/content 系/untilFind 系为 🟡→✅ 转正），autoUiobject 98 项中 ✅ 已达 66。

---

## 二、A 组：图色方法 + 字符方法（12 模块 266 项，✅+🟡 53 / 20%）

### captureScreen（高性能截图，14 项）
| 方法 | 状态 | 备注 |
|---|---|---|
| captureScreen.init | 🟡 | 引擎首次图色调用自动初始化屏幕环境，无显式 init |
| captureScreen.captureScreen | 🟡 | 对应 updateScreen/updateAndKeepScreen 刷新帧 + saveScreen 存盘；不返回 Image 对象 |
| captureScreen.stop | ❌ | 引擎无截图生命周期控制 |
| captureScreen.isInitialized | ❌ | 无对应查询 |
| captureScreen.getSharedMemoryAddress | 🟦 | autolua_vision 持有 BGRA 帧，未暴露地址 |
| captureScreen.getSharedMemorySize | 🟦 | 同上，帧大小未暴露 |
| captureScreen.startLoop | ❌ | keepScreen 仅保持快照，无后台截图循环 |
| captureScreen.stopLoop | ❌ | 无对应 |
| captureScreen.isLoopRunning | ❌ | 无对应 |
| captureScreen.getScreenshotFromSharedMemory | 🟦 | 底层帧数据存在（autolua_vision/ScreenStreamController），缺 Lua 读取 API |
| captureScreen.getScreenWidth | 🟡 | 对应 getBaseSize()[1] |
| captureScreen.getScreenHeight | 🟡 | 对应 getBaseSize()[2] |
| captureScreen.cleanup | ❌ | 引擎自动管理，无显式清理 |
| captureScreen.getStatus | ❌ | 无对应 |

### dotocr（点阵字库 OCR，6 项）— 0%
`isAvailable clearDictCache setDict setDictFile ocr findStr` → ❌ 全缺（引擎无点阵字库体系；OCR 插件接入点仅预留无运行时）

### images（图像处理全局，30 项）
| 方法 | 状态 | 备注 |
|---|---|---|
| images.captureScreen | 🟦 | 引擎有 BGRA 帧/投屏，未暴露"获取屏幕 Image 对象"API |
| images.readImage | 🟡 | 对应 loadImage |
| images.save | 🟡 | 对应 saveImage / Bitmap:save() |
| images.getWidth / getHeight / getSize | 🟡 | 对应 getImageSize |
| images.getRealPointRGB | 🟡 | 对应 Bitmap:getColor() |
| images.clip / cutImage / crop | ❌ | 无图片裁剪 |
| images.resize | ❌ | 无缩放 |
| images.readImageToBase64 / toBase64 / fromBase64 / writeFileFromBase64 | ❌ | 无 base64 能力 |
| images.getData / byteSize / copyByte | 🟦 | 底层帧像素数据存在，未暴露原始字节访问 |
| images.compress | ✅ | `images.toJpg(img[,quality])`（别名 compress）+ fromJpg/saveJpg/readJpg，libjpeg-turbo 静态编译（本批次） |
| images.getMat / getGrayMat / toJavaMat | ✅ | cv 库互通：getMat(RGBA 4ch)/getGrayMat(1ch)/toJavaMat（本批次，零依赖 Mat） |
| images.isNull / release | ❌ | 引擎 GC 自动管理 |
| images.rotate90 / rotate180 / rotate270 / flipHorizontal / flipVertical | ❌ | 无旋转/翻转 |
| images.toPng | 🟦 | 引擎内置 lodepng，未暴露 PNG 编码 API |

### imagesWrapper（Mat 包装器，25 项）
与 images 高度同构：save/getWidth/getHeight/getRealPointRGB 🟡 同上；getData/byteSize/toPng/copyByte 🟦 同上；`createWrapper crop cutImage resize rotate×3 flip×2 compress getMat getGrayMat toJavaMat isNull release cleanupAll getActiveCount` → ❌（无 Mat 包装器/对象池体系）

### opencv（OpenCV 封装，110 项）— ✅ 核心子集已落地（本批次 cv 库）
**零依赖纯 C++ `cv` 模块**（cv_lib.cpp，不打包 OpenCV）覆盖加工管线主面 ✅：Mat 管理（createMat/createMatFromData/createGrayMatFromRgba/matToData/matToImage/captureMat/submat/clone/setTo/release/getMatStats/forceGc）、颜色空间（cvtColor 12 常量 RGBA/RGB/BGR/GRAY/HSV + toGray）、二值/滤波（threshold/adaptiveThreshold/blur/gaussianBlur/medianBlur/dilate/erode/morphologyOpen/Close）、算术位运算（add/subtract/addWeighted/alphaBlend/bitwiseNot/And/Or/Xor/inRange/colorBinary/equalizeHist/convertScaleAbs）、几何（resize/resizeRatio/flip/rotate/rotateArbitrary 双线性/cropImage/cropBlackBorder）、连通域（extractContours 外接框/despeckle/keepComponentsBySize）、统计（minMaxLoc/mean/sum/countNonZero）、split/merge、绘制（drawRect/drawLine/drawCircle）、编码（encodePng）。仍 ❌：OCR/字库（走插件）、warpAffine/Perspective/putText、.cv 特征文件、GPU/资源缓存池语义简化。
| 分类 | 方法 | 状态 |
|---|---|---|
| 找图 | findImage | ✅ 同名同义（模板匹配） |
| 找色 | findColor | ✅ 同名（引擎含相似度+8方向，能力更强） |
| 多点找色 | findMultiColorEx / findMultiColors / detectMultiColors / cmpColor / cmpColorEx | 🟡 对应 isFeature/findFeature（无 .cv 特征文件格式） |
| 取色计数 | getColorNum / getPixel / getImageInfo / clone / loadImage / saveImage / imread | 🟡 对应 getColorCount / Bitmap:getColor / getImageSize / cloneImage / loadImage |
| OCR/字库 | ocr ocrEx findStr setDict loadFileData | ❌ 无字库 OCR |
| Mat 加工 | cvvtColor toGray resize rotate cropImage blur gaussianBlur medianBlur canny threshold adaptiveThreshold inRange dilate erode morphologyOpen/Close bitwiseNot/And/Or/Xor colorBinary colorBinaryPool colorFilter colorFilterV2 applyFilters adjustHsv equalizeHist convertScaleAbs despeckle removeLines extractContours extractBlocks autoDeskew rotateCorrection invertBinaryColors dilateWhite erodeWhite thinSkeleton cropBlackBorder keepComponentsBySize gridAdjust | ❌ 无 OpenCV（共 50+ 项） |
| Mat 管理 | createMatFromData createMatFromPointer createGrayMatFromRgba submat matToImage matToBitmap bitmapToMat bitmapToImage convertTo setTo addWeighted add subtract alphaBlend split merge | ❌ 无 Mat 体系 |
| 几何变换 | rotateArbitrary warpPerspective warpAffine flip resizeRatio | ❌ 无 |
| 绘制 | drawRect drawCircle drawLine putText | ❌ 无 |
| 识别增强 | recognizeArea detectAngle | ❌ 无 |
| 资源管理 | release isAvailable getVersion isEmpty cleanupUnreleasedMats getMatStats printMatReport forceGc cacheResource clearCache getCachedResource | ❌ 无 |
| 编码 | encode / encodeJpg | ❌ 无（encodePng 🟦 lodepng 底层有） |
| 截图 | captureMat / findMultiColorsFromData / parseRegion | 🟦 / ❌ / ❌ |

### opencvJava（Java OpenCV 桥，22 项）
`importClass luajava.bindClass` ❌（无 Lua↔Java 桥）；`Mat.rows/cols/clone/get` 🟡（对应 getImageSize/cloneImage/Bitmap:getColor）；`Mat.channels copyTo release put Imgproc.*(7) Core.add bitwise_not Utils.matToBitmap/bitmapToMat CvType` ❌；`getNativeObjAddr` 🟦（C++ 持有原生指针未暴露）

### ppocr（PaddleOCR，5 项）— 0%
`isAvailable` 🟦（插件接入点已预留）；`init recognize load loadDictionary destroy` ❌（无 ncnn 推理运行时）

### yolo（YOLO 检测/分类/分割，18 项）— 0%
`isAvailable` 🟦；`newDetector newClassifier newSegmenter cleanupAll destroy destroyGpuInstance detector.load_model/detect/detect_with_mat/release classifier.load_model/classify/release segmenter.load_model/segment/segment_with_mat/release` → ❌ 全缺

### yolo26（YOLO26，9 项）— 0%
`isAvailable` 🟦；`newDetector detector.load_model/detect/detect_with_mat/release cleanupAll destroyGpuInstance destroy` → ❌ 全缺

### zxing（二维码/条码，5 项）— 0%
`decode decodeBytes decodeMulti encode encodeWithFormat` → ❌ 全缺

### string（字符串，9 项）— 100%
`lower upper len sub find gsub format rep match` → ✅ 全部（Lua 5.4 标准库）

### utf8（UTF-8 扩展，13 项）
| 方法 | 状态 | 备注 |
|---|---|---|
| utf8.length | 🟡 | 对应标准 utf8.len |
| utf8.codes | 🟡 | 标准等价（返回形式略异） |
| utf8.char | 🟠 | 同名不同义：文档为按位置取字符，标准为码点→字符串（**迁移陷阱**） |
| utf8.sub inStr inStrRev left right mid strCut strReverse gsub | 🟡 | 均需 utf8.offset/charpattern 组合实现 |
| utf8.version | ❌ | 标准库无版本字段 |

**A 组小结**：图色核心（找色/比色/计数/模板找图/多点特征）均有承接，但 **OpenCV Mat 加工体系（110 项中 94 项 ❌）与 AI 推理（OCR/YOLO/二维码 52 项）为整体空白**——按已拍板方案走插件路线，但 Lua API 契约未定。🟦 高杠杆项：BGRA 帧原始字节访问、lodepng PNG 编码暴露。

---

## 三、B 组：脚本方法 + 节点方法（8 模块 379 项，✅+🟡 271 / 72%）— 覆盖最好

### auto（全局入口，82 项）— 80%
| 方法 | 状态 | 备注 |
|---|---|---|
| auto.swipe | ✅ | 引擎同名 swipe |
| auto.text/textContains/textStartsWith/textEndsWith | ✅ | UiSelector 同条件（auto.* 全局工厂 vs 引擎 UiSelector()，形式差异） |
| auto.desc/descContains/descStartsWith/descEndsWith/descMatch(es) | ✅ | 同条件 |
| auto.textMatch/textMatches | ✅ | 同条件 |
| auto.clickable/enabled/scrollable/focusable/selected/checked/checkable/longClickable | ✅ | 同条件 |
| auto.depth | ✅ | 引擎 depth/minDepth/maxDepth |
| auto.click | 🟡 | 对应 tap(x,y) |
| auto.longClick / press | 🟡 | tap 延时抬起 / Pointer 组合 |
| auto.back / home / recents | 🟡 | keyPress(KeyCode.BACK/HOME) |
| auto.touchDown / touchMove / touchUp | 🟡 | Pointer x/y/sync/up 协议 |
| auto.drag | 🟡 | swipe 长时长近似 |
| auto.dispatchGesture | 🟡 | createPointer 多指组合可模拟，时序脚本编排 |
| auto.clickPercent / longClickPercent / swipePercent | 🟡 | 百分比换算后调 tap/swipe |
| auto.getScreenSize | 🟡 | getBaseSize 可得 |
| auto.getRoot | 🟡 | 对应 getRootInActiveWindow() |
| auto.id / idContains / idMatch(es) / idStartsWith / idEndsWith | 🟡 | 对应 res（含正则模拟） |
| auto.className 系列 | 🟡 | 对应 clazz 系列 |
| auto.packageName | 🟡 | 对应 pkg |
| auto.content 系列（6 项 OR 语义） | ✅ | `UiSelector():content/contentContains/contentStartsWith/contentEndsWith/contentMatches` 原生（text OR contentDescription 任一匹配，2026-09-18 批次；`auto.content` 工厂未注册，同 auto.text 形式差异口径用 `UiSelector():content(...)`） |
| auto.find / findOne / untilFind / untilFindOne | ✅ | `auto.find/findOne/untilFind/untilFindOne` 直用（compat；untilFind 系 50ms 无限轮询阻塞直到非空/首个节点，2026-09-18 批次） |
| auto.selector / algorithm | 🟡 | UiSelector() 工厂；算法固定 DFS |
| auto.editable / visibleToUser | ✅ | 同名条件（0dc3a52；compat `auto.editable(v)/auto.visibleToUser(v)` 工厂已注册） |
| auto.hasChildren / indexInParent | ✅ | 选择器条件对应 `hasChild(sel)/index(i)`；另新增 `node:hasChildren()/node:indexInParent()` 原生方法（2026-09-18 批次；indexInParent 无父返回 -1） |
| auto.isServiceRunning/hasInstance/serviceExists/ensureService/startService/stopService/restartService | 🟦 | 无障碍自拉活底层在（AccessibilityLaunchHelper），无 Lua 暴露 |
| auto.launchSettings / notifications / quickSettings | ❌ | 无系统面板/设置页跳转 |
| auto.bounds / boundsInside | ✅ | compat `auto.bounds(l,t,r,b) / auto.boundsInside(l,t,r,b)`（0dc3a52） |
| auto.screenCenterX/Y / screenCoverage / password | ✅ | `UiSelector():screenCenterX([tol])/screenCenterY([tol])/screenCoverage([min])` 原生条件（tol/min 为屏幕尺寸比例 0-1，2026-09-18 批次；`auto.*` 工厂未注册，形式差异同上）；`password` 条件 0bf13cb 已补 |

### autoCollection（UiObjectCollection，33 项）— ✅ 100%（本批次）
`autoCollection.find(selector)/findAll/findOne/create(nodeArray)` 返回集合；容器 `isEmpty/nonEmpty/size/count/get/first/last/toList/toArray/each/contains`（contains 按 text/desc/res/bounds 指纹比对）；二次筛选 `find/findOne/setText`（批量返回成功数）；批量动作 19 项 `click longClick select copy cut paste focus clearFocus performAction scrollForward/Backward/Up/Down/Left/Right expand collapse dismiss show`（遍历执行返回成功数，compat 纯 Lua 实现）。

### autoSelector（UiSelector 链式，78 项）— ~97%（2026-09-18 批次后），覆盖最好
- 基础条件全对齐：text/desc 系列 ✅；id→res、className→clazz、packageName→pkg 🟡（命名差异）
- with 前缀写法（withText/withId/withClassName/withContent 15 项）全部 🟡 语法糖映射
- waitFor/exists/untilFindOne/findOnce ✅（sel 侧原生：`sel:waitFor([timeout])` 返回 boolean（无参=无限等）、`sel:untilFindOne()` 无限轮询、`sel:findOnce(i)` 按索引，2026-09-18 批次）；findOneOf/findOf 🟡（循环组合）
- textEquals/descEquals ✅
- content 五系 ✅（text OR contentDescription 任一匹配，OR 语义对齐）；boundsInside ✅（0dc3a52）
- 原硬缺口已清零（2026-09-18 批次）：boundsContains(x,y) ✅、screenCenterX/Y([tol])/screenCoverage([min]) ✅（tol/min 为屏幕尺寸比例 0-1）、selector.and ✅（**and 为 Lua 保留字**，冒号调用非法——用别名 `sel:andSel(x)` 或 `sel["and"](sel, x)`，可链式叠加多个，所有子选择器须在同一节点上匹配）

### autoUiobject（UiObject 节点，98 项）— 56%（2026-09-18 批次后几何派生/树遍历 11 项 🟡→✅，✅ 已达 66）
| 分类 | 状态 |
|---|---|
| 属性读取：text/desc/bounds/childCount/parent + getText/getId/getClassName/getDescription/getPackageName/getBounds/getChildCount | ✅/🟡（id→res、className→clazz 改名） |
| 布尔状态：isClickable/isEnabled/isSelected/isChecked/isVisibleToUser/isScrollable/isFocused/isEditable/isLongClickable/isFocusable/isCheckable/isVisible | ✅ 全对齐 |
| 几何派生：centerX/centerY/width/height/point/size/clickBounds/getWidth/getHeight/getCenterX/getCenterY | ✅/🟡 centerX/centerY/width/height/point 原生（point 返回 x,y 两值，2026-09-18 批次）；getWidth/getHeight/getCenterX/getCenterY 同能力（bounds 派生）；size/clickBounds 🟡 bounds 组合 |
| 树遍历：firstChild/lastChild/sibling/siblings/getChild/getChildren/getIndexInParent/hasChildren | ✅/🟡 firstChild/lastChild/sibling(offset)/siblings/getIndexInParent/hasChildren 原生（2026-09-18 批次；sibling 无父或越界 nil）；getChild/getChildren 对应 child(i)/childs() 🟡 改名 |
| 查找：find/findOne/findChildrenByText/findChildrenByClass | ✅/🟡 |
| setText | ✅ 同名 |
| 点击：click/longClick | 🟡 bounds 中心+tap 组合 |
| **ACTION 动作**：0dc3a52 起逐步补齐，本批次修复 index 闭包分发（此前 `nd:click()` 经 index 返回 nil 实际不可调用）+ 换框架常量 + 新增：`dismiss show accessibilityFocus clearAccessibilityFocus pageUp/Down/Left/Right(API34) imeEnter(API30) showTooltip/hideTooltip(API28) scrollTo(row,col) setProgress(v) performAction(action) next/previousAtMovementGranularity next/previousHtmlElement`。剩余仅 dragStart/dragDrop 系（Pointer 手势可替代）、pressAndHold（长按手势）、moveWindow 等少数带参/自定义项 |

### event（事件，13 项）— ✅ ~85%（本批次）
多播总线：`event.on/off/removeListener(s)` + `event._emit`（进程内任意事件名）；事件源：`onStopEvent`（引擎 OnStop 桥，多播）✅、`onToast/onNotification/onTouch/onClick`（无障碍服务 → EventBridge 镜像文件 → 引擎 60ms 轮询协程分发；首个监听自动开转发）✅、`onHotfixFinish`（Fire 回调）✅、`getPermissionStatus/hasTouchPermission`（root/无障碍/注入通道判定）✅；`onVoiceDown/Up/onGesture` → 事件源缺失，注册返回 false（🟠 声明）。

### project（工程，9 项）— ✅ 100%（本批次；纯 Lua 实现）
`getProjectDir`（ROOT_DIR 或 /data/local/tmp）；热更目录 `项目根/hotfix/`：`hotfixFire([dir])`（被覆盖文件先入 backup → 覆盖项目根 → 触发 hotfix_finish）、`hotfixFireFromUrl(url)`（http.download → unZip → 应用）、`hotfixFireFromDir(dir)`、`hasPendingHotfix`、`rollback`（backup 恢复）、`clearCustomFire`、`setHotFixCallback`（= event.on("hotfix_finish")）。

### webElementWrapper（WebView 元素，7 项）— 0%
`click getText setText getValue setValue getHtml setHtml` → ❌ 全缺（无 WebView 节点注入）

### node（节点全局入口，59 项）— ~98%（2026-09-18 批次后）
与 auto 模块同源：条件工厂 text/desc 系列 ✅、id/className/packageName→res/clazz/pkg 🟡、content 系列 ✅（`UiSelector():content` 五系，text OR contentDescription 任一匹配，2026-09-18 批次；`node.content` 工厂未注册，形式差异同 auto.text 口径）、`getRoot` 🟡（getRootInActiveWindow）、`selector` 🟡（UiSelector()）；`boundsContains(x,y)/boundsInside` ✅、`screenCenterX/Y([tol])/screenCoverage([min])` ✅（tol/min 屏幕比例 0-1，2026-09-18 批次）、`password` ✅（0bf13cb）、`and` ✅（Lua 保留字，别名 `andSel(sel)`）——原 ❌ 条件硬缺口清零；另有 NodeLib 全局 `keepNode/releaseNode/updateNode/getNodeXml/openAccessibility/isAccServiceOk`（见 02-Lua-API规范.md §4.9）。

**B 组小结**：选择器条件层基本无损迁移（90–98%，2026-09-18 批次后 autoSelector/node 硬缺口清零，content OR 语义、等待轮询、多窗口兜底、树遍历/几何派生均已原生），id/className/packageName 仅命名差异；节点 ACTION 动作层已全量落地（0dc3a52 起），点击可用 bounds+tap 兜底；事件总线/Fire 热更已落地，WebView 节点注入仍空白。全组 379 项中 🟠 为 0——同名方法语义均一致，无迁移陷阱。

---

## 四、C 组：触控方法 + 基础库（11 模块 148 项，✅+🟡 60 / 41%）

### clipboard（剪贴板，9 项）— 0%
`getText setText isEmpty hasText clear setListener removeListener canRead isAppInForeground` → ❌ 全缺（ClipboardManager 标准服务，暴露成本低）

### coordinate（坐标转换，12 项）— 8%
`getScreenSize` 🟡（getBaseSize）；`setScreenSize p2r r2p p2rRect r2pRect p2rX p2rY r2pX r2pY getCenter getCenterPercent` → ❌（纯算术可 Lua 自实现，优先级低）

### hid（HID 输入设备，7 项）— 29%
`hid.home / hid.click` 🟡（keyPress/tap 功能等价，但非 HID 防检测通道）；`init requestUsbPermission detectDevices isConnected getStatus` → ❌（USB HID 通道整体缺失，属通道级差距）

### ime（输入法，14 项）— ✅ 100%（本批次）
`setText commitText clearField showKeyboard hideKeyboard isActive isOurIMEActive setAsDefaultIME setCurrentIME showInputMethodPicker getCurrentIMEName openIMESettings inputLText getCurrentText` → 全局 `ime` 表全补（`imeLib` 别名；root shell settings/am + 广播宿主 IME 服务执行，零 Context 依赖；`deleteChar/finishInput` 也已覆盖，`getCurrentText` 经无障碍 setText/节点文本可替代）。

### touch（触摸操作，35 项）— 49%
| 方法 | 状态 | 备注 |
|---|---|---|
| touch.tap | ✅ | 同名 |
| touch.swipe | ✅ | 同名 |
| touch.down / move / up | 🟡 | Pointer 协议等价（无自动插值） |
| touch.longTap | 🟡 | tap 带时长 / Pointer 组合 |
| touch.gesture / multiTouch / pinch / zoom | 🟡 | 多 Pointer 组合可模拟 |
| touch.press / home / back / power / menu / volumeUp / volumeDown | 🟡 | keyPress + KeyCode 表 |
| touch.nextId / getCurrentId / setMoveThreshold | ❌ | 无追踪 ID/插值机制 |
| touch.recent / volumeMute / dpad×5 / enter / tab / del / search / camera / call / endCall | ❌ | KeyCode 表仅 6 键，缺 13 键 |
| touch.isAvailable | ❌ | 无可用性查询 API |

### global（全局工具，5 项）— 40%
`sleep` ✅（引擎版额外支持 interrupt）、`print` ✅；`toastShow` ❌（可封装 UI.showToast）、`sendImage` ❌（IDEA 插件通道无对应物）、`importClass` ❌（无 Java 桥）

### openssl（加密解密，14 项）— 0%
`md5 sha256 sha512 hmacSha256 generateRandom base64Encode base64Decode isAvailable aesCrypt aesKeygen aesIvgen rsaEncrypt rsaDecrypt rsaGenerateKey` → ❌ 全缺（脚本侧最难绕过的空白，引擎层需引入密码学库）

### frandom（随机数，12 项）— 58%
`seed/int/float/boolean/pick/chance/floatRange` 🟡（math.random 一两行等价）；`shuffle uuid hex string weighted` ❌（适合做一个 Lua 工具库补齐）

### math（数学函数，34 项）— 76%
标准库 24 项 ✅（abs ceil floor max min sqrt sin cos tan asin acos atan rad deg random randomseed log exp modf fmod tointeger type ult pi）；`isInteger` 🟡（tointeger~=nil）、`init` 🟡（randomseed）；扩展 8 项 ❌：`version clamp round lerp sign isEven isOdd inRange`（一行级可自实现）

### json（JSON 编解码，2 项）— 50%
`json.encode` 🟦（内部 lua2json 已存在，**最低成本高收益补齐项**）；`json.decode` ❌（需暴露解析器）

### table（表操作，4 项）— 100%
`concat insert remove sort` → ✅（Lua 5.4 标准库）

**C 组小结**：touch 靠 Pointer 体系半覆盖（缺 13 个按键键码）；剪贴板/输入法/加密三大块整体空白；json.encode 一步暴露即可解锁。

---

## 五、D 组：应用/系统/网络/文件/时间/权限（15 模块 286 项，✅+🟡 40 / 14%）

### app（应用控制，23 项）— 0%
`launchApp launchPackage killApp getTopPackageName getPackageName getAppName isAppInstalled isAppEnabled isAppRunning getRunningApps getAppVersionName getAppVersionCode getInstalledApps getAppInfo openAppSetting uninstall openUrl clearAppData enableApp disableApp getContext getActivity startActivity` → ❌ 全缺（应用控制是差距最大模块之一；root 进程 `am/pm` 封装可低成本落地）

### device（设备信息，51 项）— 10%
| 方法 | 状态 | 备注 |
|---|---|---|
| getScreenSize / getScreenWidth / getScreenHeight | 🟡 | getBaseSize 系 |
| getDisplayInfo | 🟡 | getBaseSize+getBaseDensity+getBaseDirection+getRotation 组合可得部分 |
| device.sleep | 🟠 | **同名陷阱**：此处为屏幕休眠；引擎全局 sleep 为脚本延时 |
| 亮度/音量/网络状态：getScreenBrightness setScreenBrightness isScreenOn wakeUp getVolume setVolume volumeUp volumeDown isConnected isWifiEnabled isMobileDataEnabled | ❌ | |
| 设备标识：getAndroidVersion getBrand getModel getManufacturer getDevice getProduct getHardware getBoard getFingerprint getId getBootloader getCpuAbi getCpuAbi2 getSdkVersion getOsVersionName getDeviceId getAndroidId getMacAddress getIpAddress | ❌ | Build.XXX 批量暴露即可 |
| 电量/传感器：getBatteryLevel isCharging getSensorsInfo | ❌ | |
| 应用/前台：getInstalledApps getCurrentActivity getFrontApp | ❌ | |
| 系统设置：getSystemSetting setSystemSetting getSystemProperty setSystemProperty | ❌ | |
| WakeLock：acquireWakeLock releaseWakeLock isWakeLockHeld acquirePartialWakeLock releasePartialWakeLock isPartialWakeLockHeld | ❌ | |

### media（音频播放，12 项）— 0%
`playAudio stopAudio pauseAudio resumeAudio getDuration getCurrentPosition getPosition seekTo isPlaying setVolume release releaseAll` → ❌ 全缺

### shell（命令执行，33 项）— 36%（🟦 12 项高杠杆）
| 方法 | 状态 | 备注 |
|---|---|---|
| shell.isRoot / isRootAvailable / isAppGrantedRoot / getShellStatus / getMode | 🟦 | root daemon 底层存在，未暴露状态查询 |
| shell.exec / execSync / execAsync / fastCmd / fastCmdResult / sendevent / input | 🟦 | **root 引擎进程在，暴露命令执行即可连带解锁下列全部** |
| shell.getevent amStart pmInstall pmUninstall reboot shutdown killProcess takeScreenshot getMemoryInfo getStorageInfo getCpuInfo | ❌ | 依赖 exec（screencap/am/pm/input 二次封装） |
| shell.fileExists fileIsDir fileIsFile fileDelete fileMkdir fileMkdirs fileRead fileList fileWrite | ❌ | 依赖 exec（亦可走文件 API 补齐） |

### fhttp（网络请求，24 项）— ✅ 100%（a9183ad + 本批次异步解耦）
`get post postJson postForm put delete patch head options download upload` + 异步族 + `setTimeout setProxy` 全补（compat 包装 http 服务）。异步族已重构为独立 Java 线程 + AsyncHandle 句柄（`isDone/get/cancel`），解除 thread 协程内 Java 阻塞限制；回调族保留。

### websocket（双向通信，9 项）— ✅ 100%（a9183ad）
`clientConnect clientSend clientClose clientIsOpen serverStart serverStop serverSend serverBroadcast serverIsRunning` + `clientPoll/serverPoll/serverConnCount`（libhv 绑定，事件轮询模型）。

### file（文件操作，32 项）— 9%
| 方法 | 状态 | 备注 |
|---|---|---|
| file.read / write / append | 🟡 | io.open("r"/"w"/"a") 变通 |
| 存在/类型：exists isFile isDirectory isReadable isWritable can_read can_write | ❌ | |
| 目录：mkdir mkdirs listDir listDirWithFullPath rmdir deleteDirRecursive | ❌ | |
| 操作：remove delete rename copy move createNewFile | ❌ | |
| 属性：size getMTime getCTime isHidden setReadable setWritable | ❌ | |
| 路径：getName getParent getAbsolutePath resolve_path | ❌ | |

### io（标准输入输出，9 项）— 100%
`open close read write input output lines flush type` → ✅（Lua 5.4 标准库）

### ioWrapper（文件句柄实例方法，6 项）— 100%
`f:read f:write f:lines f:close f:flush f:seek` → ✅（标准语义一致）

### lfs（文件系统扩展，13 项）— 0%
`attributes chdir currentdir dir link lock mkdir rmdir symlinkattributes touch unlock lockDir version` → ❌ 全缺

### path（路径处理，19 项）— 5%
`getProjectDir` 🟡（ROOT_DIR）；`join dirname basename extname normalize resolve exists getAppFilesDir getCurrentScriptPath getWorkingDir isAbsolutePath isAssetPath isReadable resolveCandidates setAppFilesDir setCurrentScriptPath setProjectDir stripAssetPrefix` → ❌

### zip（压缩解压，20 项 = 12 方法 + 8 常量）— 0%
方法：`zipDir zipFile zipFiles unzip open addFile compressDirectory compressFile extract getInfo removeFile testPassword`；常量：`COMP_STORE COMP_DEFLATE ENC_NO_ENCRYPTION ENC_METHOD_STANDARD ENC_METHOD_AES AES_STRENGTH_128/192/256` → ❌ 全缺（PluginManager 有 zip 解压流程但 Java 层专用）

### time（时间工具，22 项）— 77%（覆盖最好的扩展模块）
| 方法 | 状态 | 备注 |
|---|---|---|
| time.unix | 🟡 | os.time() 直接等价 |
| time.timestamp / millisecond | 🟡/❌ | 秒级可得；毫秒 wall-clock 无源（os.clock 是 CPU 时间） |
| time.format | 🟡 | os.date 变通（格式符体系不同 yyyy-MM-dd vs %Y-%m-%d） |
| time.parse / setTimezone | ❌ | 反向解析与运行时切时区缺 |
| time.now year month day hour minute second weekday utc | 🟡 | os.date("*t")/os.date("!*t") |
| time.addDays addHours addMinutes addSeconds diff | 🟡 | 时间戳算术 |
| time.isLeapYear daysInMonth | ❌ | 纯算法可自实现 |

### timer（定时器，4 项）— 0%
`setTimeout delay setInterval clear` → ❌（无事件循环；协程+sleep 可阻塞式模拟，非阻塞回调定时器缺）

### permission（运行时权限，9 项）— 22%（🟦 2 项）
`requestSpecial / checkSpecial`（screen_capture）🟦（MediaProjection 授权流程 Java 侧已有）；`check checkMultiple request requestMultiple shouldShowRationale openSettings get` → ❌

**D 组小结**：io/ioWrapper/time 满意；**app/shell/exec、fhttp、文件工具三类是脚本实用性刚需**；🟦 高杠杆：shell.exec 暴露（连带解锁 21 个衍生方法）、permission.screen_capture、MediaProjection 检查。

---

## 六、E 组：界面/线程/扩展接口（13 模块 275 项，✅+🟡 22 / 8%）

### activity（Activity 布局，9 项）— 33%（🟦 3 项）
`layoutS / layout / layoutCh` 🟦（**动态 UI 已落地（2026-09-15）**：`ui.createWindow` JSON 控件树直建原生 View 悬浮窗，无 XML DSL 字符串前端，调用层适配）；`inflate listActivities registerWidget unregisterWidget executeFunction setTheme` ❌

### canvas（画布绘制，20 项）— 🟡 部分（2026-09-15）
`drawColor drawLine drawRect drawCircle drawText drawPath drawRoundRect drawArc clearCommands` 🟡 等价→`canvas` 控件 `node.set {canvas={ops={...}}}` 指令绘制（color/line/rect/circle/roundRect/arc/text/path，../../02-Lua-API规范.md §17.1）；`translate scale rotate save restore createPath drawStar getBitmap` ❌（无 Mat 级状态栈）

### dialogs（对话框，3 项）— ✅（2026-09-15）
`confirm alert input` → ✅ `ui.confirm/ui.alert/ui.input`（独立 overlay 窗 + scrim + requestId 闭环，可同步 wait 或异步 onResult）

### float（悬浮窗管理，9 项）— 33%
`layoutS / layout / layoutCh` 🟡（UI.createView→FloatView 等价，uri 加载非 XML DSL）；`setTheme closeAll listWindows executeFunction registerWidget unregisterWidget` ❌

### floatMenu（悬浮菜单，3 项）— 100%
`show / hide / isShowing` → 🟡（对应 FloatController 悬浮球）

### layoutResult（布局结果对象，24 项）— 25%
| 方法 | 状态 | 备注 |
|---|---|---|
| show / hide / setPosition / setSize / isShowing | ✅ | 与 FloatView 同名同义 |
| close | 🟡 | hide 隐藏即等效 |
| setAlpha（viewWrapper 口径） | 🟡 | FloatView.setAlpha |
| findView getPosition getSize setClickable getWindowId getRootView getView isSuccess | ❌ | |
| savePage loadPage readPage deletePage hasPage listPages | ❌ | 页面状态持久化体系 |
| setActivity isActivity setPositionAbsolute setAdapter | ❌ | |

### paint（画笔，7 项）— 0%
`create setColor setStrokeWidth setStyle setTextSize setAntiAlias setTypeface` → ❌

### toast（提示，4 项）— 50%
`toast.show` ✅（UI.showToast）；`longShow` 🟡（时长参数覆盖）；`showAt showWithIcon` ❌

### viewWrapper（View 包装器，152 项）— 3%（最大差距模块）
| 分类 | 状态 |
|---|---|
| show / hide / setSize | 🟠 同名不同义（引擎为窗级语义，非 View 级） |
| setAlpha | 🟡（窗级透明度） |
| 文本/提示：getText setText setHint getHint | ❌ |
| 可视/可用：setVisibility getVisibility gone isShown setEnabled isEnabled setClickable isClickable | ❌ |
| 尺寸/位置：getWidth getHeight setWidth setHeight setX getX setY getY | ❌ |
| 变换：setRotation getRotation setScaleX/Y setElevation setTranslationX/Y setRotationX/Y setZ | ❌ |
| 边距：setPadding setMargins getPadding×4 getMargin×4 setMinimumWidth/Height | ❌ |
| 事件监听：setOnClickListener setOnLongClickListener setOnTouchListener setOnFocusChangeListener setOnCheckedChangeListener setOnChangeListener setOnItemSelectedListener ×3 setOnRefreshListener setOnScrollListener setOnItemClickListener | ❌ |
| 状态：setSelected isSelected setChecked isChecked getChecked setActivated isActivated setPressed isPressed hasFocus | ❌ |
| 进度：setProgress getProgress setMax getMax | ❌ |
| 焦点/布局：requestFocus clearFocus requestLayout invalidate bringToFront setLayoutParams getLayoutParams setId getId setTag getTag | ❌ |
| 容器：addView removeAllViews | ❌ |
| Canvas 内嵌：drawColor/Line/Rect/Circle/Text/Oval/RoundRect/Arc/Path drawStar createPath translate scale rotate save restore getBitmap recycle clearCommands | ❌ |
| WebView：url html localFile evaluateJS findWebId | ❌ |
| VideoView：setVideoURI setVideoPath start pause stopPlayback seekTo getVideoDuration getVideoPosition isPlaying | ❌ |
| 导航/抽屉/分页：setMenuItems setOnItemSelectedListener setNavMenuItems openDrawer closeDrawer isDrawerOpen setDrawerLockMode bindViewPager setTitles getSelectedItemId | ❌ |
| 滚动：scrollTo scrollBy smoothScrollTo fullScroll | ❌ |
| 其它：click performClick performLongClick setAttr | ❌ |

### thread（多线程，20 项）— 35%
| 方法 | 状态 | 备注 |
|---|---|---|
| thread.sleep | ✅ | worker 内置 |
| thread.stop | 🟡 | 对应 interrupt |
| setShared getShared removeShared hasShared clearShared | 🟡 | 单 worker 下 _G 全局变量等价 |
| start yield currentId isMain postToMain stopAll wait waitAll isStopped lock unlock atomic sync | ❌ | 单 worker 模型无并发原语 |

### jvm（JVM 交互，2 项）— 50%
`getPointer` ❌；`loadUserLibrary` 🟦（PluginManager 支持 so 动态加载，Lua API 未定）

### plugin（原生插件，9 项）— 56%（🟦 5 项）
`plugin.load / unload / getLoaded / getPluginPath / getSoPath` 🟦（PluginManager 生命周期底层齐备，**补 Lua 暴露即可对齐**）；`newInstance createInstance findClass callStatic` ❌（受引擎"无反射桥"设计限制，缺口为架构性）

### sherpa（语音识别，13 项）— 0%
`newRecognizer newStreamingRecognizer version` + 离线识别器 `:destroy :recognize :recognize_file` + 流式识别器 `:destroy :accept :input_finished :is_ready :decode :get_result :reset` → ❌ 全缺（模型依赖 + .so，成本高）

**E 组小结（2026-09-15 更新）**：动态 UI 体系已落地（`ui` 表：JSON 控件树 + op 协议，../../02-Lua-API规范.md §17.1）——dialogs ✅、canvas 部分 🟡、layoutResult 窗口操作 ✅（ui.get/tree）、floatMenu ✅；与派大星 Android View 直操仍是两条技术路线，viewWrapper/paint（159+ 项）不建议逐项对齐；**plugin 生命周期 5 项 🟦 是本组最高杠杆**。

---

## 七、综合结论与补齐优先级（懒人 + 派大星合并视角）

### 7.1 两份文档横向结论

| 维度 | 懒人精灵（~570 项） | 派大星 FireFly（1354 项） |
|---|---|---|
| 总覆盖率 | ~18% | 33%（2026-09-18 批次后） |
| 选择器/节点 | 形态差异大（全局工厂反向查找），覆盖 ~73%（2026-09-18 重估） | 形态接近（auto.* 全局工厂 + 链式），覆盖 72–98%（2026-09-18 批次后） |
| 图色 | 核心五能力承接，OCR/字库缺 | 同 + 无 OpenCV Mat/AI 推理（179 项缺口） |
| 触控 | tap/swipe/键同名 | 同 + 多指手势/百分比/长按等价更全 |
| 应用/系统控制 | ~8% | ~10%（但 🟦 12 项 shell 系一步可解锁） |
| 网络/JSON/加密 | 全缺 | 全缺（共同空白） |
| UI 体系 | 🟡 动态 UI 已落地（`ui` 表 JSON 控件树，2026-09-15）；getUIxxx 静态配置格式不对齐 | 🟡 dialogs/canvas 部分/layoutResult 窗口操作对齐；View 直操 179 项不对齐 |
| 迁移陷阱 | findColor 同名不同参 | utf8.char、device.sleep 同名不同义 |

### 7.2 建议补齐路线（按 ROI 排序）— 2026-09-12 实施状态

**P0 — 一步暴露类（🟦 回收 + 低成本同名补齐，预计 +120 项覆盖）**
1. ✅ `json.encode/decode`：C++ json_lib 绑 cjson 全局暴露（0dc3a52）
2. ✅ `shell.exec/execSync/fastCmd/isRoot` + `runApp/stopApp` 系：AppLib root `am/pm/dumpsys/pidof` 封装 + 全局 exec（0dc3a52）
3. ✅ `node:click/longClick + scroll×6`：UiObject 11 个 ACTION（0dc3a52）+ sel 侧便捷动作 findOne/findAll/click 等（0bf13cb）
4. ✅ KeyCode 表扩充 13 键 + `touch.longTap/touchDown/touchMove/touchUp` 便捷封装（0dc3a52，C++ InputManager 便捷层）
5. ✅ `clipboard`：`readPasteboard/writePasteboard` + compat `clipboard.get/set`（fba79cd；setListener/canRead 等 7 项未做）；✅ `toastShow`/`toast/hideToast`（0bf13cb）
6. ✅ `file.exists/mkdir/listDir/remove/size` 基础 5 件套 + 全局别名（0dc3a52 FileLib）
7. ✅ 帧访问：`getScreenFrame()`（RGBA 整帧+stride）+ `captureScreen.getFrame/getSharedMemorySize`（a9183ad）；PNG 编码已有 `images.toPng`
8. ✅ `plugin.load/unload/getLoaded` + list/install/getPluginPath/getSoPath（a9183ad PluginLib）

**P1 — 绑定开发类（中等成本）**
1. ✅ `fhttp` 全 24 项：method 族/download/upload/setTimeout/setProxy/异步族（a9183ad；异步族有协程阻塞限制，见 LUA_API 19.6）
2. ✅ 设备信息批量暴露：DeviceLib Build.XXX/Battery/Wifi/MAC/IP（0dc3a52；51 项中基础 ~20 项已覆盖）
3. ✅ `systemTime/tickCount/setTimer/rnd`：compat 兼容层（0dc3a52）
4. ✅ 选择器补条件：`idContains/idStartsWith/idEndsWith/bounds/boundsInside/visibleToUser/editable/password/drawingOrder`（0dc3a52 + 0bf13cb）；✅ content 五系（text OR contentDescription 任一匹配）/boundsContains(x,y)/screenCenterX/Y([tol])/screenCoverage([min])/and（Lua 保留字，别名 andSel）（2026-09-18 节点增强批次）
5. 🟡 `openssl` 哈希+AES：CryptoLib md5/sha/base64/aes-128/rsa（0dc3a52；openssl 全 API 面未对齐）

**P2 — 体系/插件类（按生态需求）**
1. 🟡 OCR：**Lua 调用口已通**（a9183ad：`ocr.ocrText/ocrEx` + IOcrPlugin 契约 + PluginLib）；缺真实识别插件实现（PP-OCR ncnn）/YOLO 契约定型
2. ✅ `lfs/path/zip`：lfs 高频项 FileLib 承接；`path` 19 项（a9183ad）；`zip/unZip` 基础（fba79cd）；zip 加密常量/流式 API 未做
3. 🟡 事件总线：`setUserEventCallBack/setPluginEventCallBack/sendPluginEvent` 等 6 项进程内总线（a9183ad）；Fire 热更/跨进程事件未做
4. ✅ `imeLib` 输入法体系（本批次全局 `ime` 表 16 项，imeLib 别名；root shell + 宿主广播，零 Context）

**P3 — 不对齐声明（2026-09-15 更新）**
- viewWrapper/paint（Android View 直操级）：本引擎动态 UI（`ui` 表 + FloatView）已覆盖常用窗口/控件场景，View 级逐方法对齐（变换/焦点/导航抽屉/滚动系）不做
- canvas：🟡 已落地轻量子集（`canvas` 控件指令绘制 9 项等价），Mat 级状态栈（translate/save/restore/getBitmap）不做
- dialogs：✅ 已对齐（`ui.dialog` 体系）
- imgui（懒人 native Dear ImGui）：不做（canvas 覆盖轻量绘制，高频重绘场景另行评估）
- virtualscreen 虚拟屏多开（15 项）：与投屏方向相反，暂无需求
- sherpa 语音/webElementWrapper WebView 节点：无底层依赖，评估后决定

### 7.3 覆盖率预期

执行 P0 后：懒人 ~18% → **~30%**，派大星 32% → **~42%**；再加 P1 → 派大星 **~55%+**，且覆盖的是脚本实用性最高的 80% 场景（找色找图找节点、点击滑动、应用控制、网络、文件、JSON）。

> **实际达成（2026-09-12，3b3ef81 + 0dc3a52）**：P0 全部 8 项中 5 项 + P1 全部 5 项落地，懒人 ~18% → **~45%**，派大星 32% → **~50%**。
> **实际达成（2026-09-14，fba79cd + 0bf13cb + a9183ad）**：P0 全清（含 clipboard/帧访问/plugin Lua 暴露）+ P1 fhttp 全清 + P2 路径/事件总线/OCR 调用口落地，懒人 → **~65%**，派大星 → **~68%**。
> **实际达成（2026-09-14 晚，c27e26d + e5c788f + 5cd1ff4）**：MySQL 桥（connectSQL 体系）、autoCollection 批量操作、event 事件总线、Fire 热更、cv.Mat 图像加工子集（60+ 项）、ime 16 项、JPEG 编解码、fhttp 异步族全部落地；全量自测基线 **PASS=442 FAIL=0**。剩余大头：OpenCV Mat 完全体（对标 110 项，现 60+ 子集）、OCR/YOLO 真实识别插件、viewWrapper（不对齐）。
> **实际达成（2026-09-15，aaf58e9）**：动态 UI 体系落地（`ui` 表：JSON 控件树 + op 协议 + 全量控件/弹窗/dialogs/canvas 轻量绘制 + 事件回调 + console），模拟器冒烟 34/35；dialogs ✅、canvas 🟡（9 项等价）、float 窗口操作 ✅。剩余大头不变 + tap 集成测试待人工点验（引擎 execute 偶发挂死既有问题阻塞）。
> **实际达成（2026-09-18，节点增强批次）**：等待轮询（`sel:findOne([timeout])/findAll([timeout])/untilFindOne()/waitFor()` + compat `auto.untilFind/untilFindOne`）、新条件（content 五系 OR 语义/boundsContains/screenCenterX/Y/screenCoverage/and→andSel 别名）、多窗口兜底（便捷动作主窗口无结果遍历其他窗口）、UiObject 树遍历/几何派生（sibling 系/firstChild/lastChild/indexInParent/hasChildren/centerX/centerY/width/height/center/point）、NodeLib（keepNode/releaseNode/updateNode 快照缓存、getNodeXml XML 树导出、openAccessibility/isAccServiceOk）、IDE 沙箱 nodeLib 只读桥 + VSCode 插件"抓取节点树"面板落地；autoSelector 90%→~97%、node 88%→~98%、B 组 68%→72%。

---

*附：引擎 API 注册点速查*
- C++ 全局：`LuaInterpreter.cc`（sleep/require/loadfile/exitScript）、`Display.cpp` + `lua_vision.cc`（图色 + vision_extra：findColorAllPoint/findFeatureAll/findImageAll/getScreenPixel/getScreenFrame/isDisplayDead）、`InputManager.cpp`（触控）、`image_lib.cpp`（images 表）、`ws_lib.cpp`（ws 表）
- Java 注入：`AutoLuaEngineOnLocal.kt`（UiSelector/getRootInActiveWindow/setText/KeyCode/ROOT_DIR）、`MainApp.kt`（UI/FloatController）
- 本地服务（root Server.kt + 沙箱 MainApp 双路径，14 个）：http / luajava / LuaEngine / file / device / crypt / app / **plugin / ocr / media / ime** / compat（ime 为 LuaEnvContributor 直贡献全局 `ime` 表）
- 底层待暴露：MediaProjection（permission.screen_capture）。~~lodepng JPEG 压缩~~ ✅ 本批次 libjpeg-turbo；~~libhv 异步 http~~ ✅ 本批次 NetLib AsyncHandle 句柄模式（HttpURLConnection 独立线程）






