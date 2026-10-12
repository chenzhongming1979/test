# JY-Frame（autolua2）Lua API 参考手册

> 文档版本：v1.1（2026-09-18；v1.1 新增节点增强：等待轮询/新条件/树遍历几何派生/NodeLib 节点树导出与调试）
> 分类方式参考《懒人精灵》官方文档模块划分（math/string/array/time/io/color/touch/node/device/ui/extension…），并结合派大星（FireFly）风格补充。
> 范围：脚本可直接调用的全部 Lua API（C++ 全局 + Java 注入服务 + compat 兼容层）。UI 配置体系（MLN）、OCR/YOLO 插件接口不在本篇。
> 所有签名均从源码核实：`cpp\extension\{Display,InputManager,thread_lib,json_lib}`、`engine\...\extension\{net,bridge,file,device,crypto,app,compat,node}`。

## 运行环境说明

| 环境 | 说明 |
|---|---|
| root 主引擎 | app_process 进程（uid 0），全部能力可用：图色/触控/节点/应用控制/shell |
| IDE 沙箱 | app 进程内 LuaEngineExecutor，图色/触控经 VisionBridge 可用；`am force-stop` 等 root 命令失败返回 false |
| 服务注册 | http / luajava / LuaEngine / file / device / crypt / app / compat 均为 Lua 所在进程本地服务；thread / json / 图色 / 触控为 C++ 原生注入，所有环境一致 |

网络、文件 IO 必须在非主线程执行（Lua worker / IDE 消息线程池均满足，脚本内无需关心）。

---

## 目录

1. [环境与基础](#1-环境与基础)
2. [图色](#2-图色)
3. [触控](#3-触控)
4. [节点与无障碍](#4-节点与无障碍)
5. [文件](#5-文件)
6. [设备](#6-设备)
7. [应用与 shell](#7-应用与-shell)
8. [网络](#8-网络)
9. [加密](#9-加密)
10. [JSON](#10-json)
11. [线程与定时](#11-线程与定时)
12. [Java 桥](#12-java-桥)
13. [字符串扩展](#13-字符串扩展)
14. [数组/表扩展](#14-数组表扩展)
15. [数学与随机](#15-数学与随机)
16. [时间](#16-时间)
17. [UI 与悬浮窗](#17-ui-与悬浮窗)
18. [常量表](#18-常量表)
19. [扩展模块](#19-扩展模块)
20. [虚拟屏](#20-虚拟屏virtualdisplay)

---

## 1. 环境与基础

注册点：`core\LuaInterpreter.cc`（C++）。

| API | 说明 |
|---|---|
| sleep(ms) | 毫秒级延时；可被脚本 interrupt 打断 |
| print(...) | 输出日志（root 引擎走 logcat；沙箱经 IdeLog 回传 IDE 控制台） |
| loadfile(path) | 加载脚本文件（自定义 CodeProvider 查找） |
| loadresource(name) | 加载资源脚本 |
| require(mod) | 标准模块加载 |
| ROOT_DIR | 全局常量，脚本项目根目录 |
| Lua 5.4 标准库 | string / table / math / io / os / utf8(标准) / coroutine / debug 全量可用 |

### 1.1 attach 附件（懒人风格，compat）

约定目录 `ROOT_DIR/attach/`——脚本工程内的 `attach/` 子目录随工程 zip / Fire 热更
自然分发（同步打包不筛文件类型），Lua 侧经本表按相对路径访问。
依赖 ROOT_DIR（工程启动后有效）。注册点：`extension\compat\CompatLib.kt`（compat.lua）。

| API | 说明 |
|---|---|
| attach.dir() | 附件根目录绝对路径（`ROOT_DIR/attach`） |
| attach.path(name) | 附件相对路径 → 绝对路径；不校验存在性，name 非法返回 nil |
| attach.exists(name) | 附件是否存在 |
| attach.read(name) | 读附件文本内容（二进制自行 `file.read(attach.path(n), true)`） |
| attach.loadImage(name) | 读附件图片 = `loadImage(attach.path(name))`；无图色环境时报错 |
| attach.require(name) | 动态加载附件 lua 模块 `attach/<name>.lua`（返回值即模块），带缓存 |

```lua
local img = attach.loadImage("btn.png")      -- 附件图片
local cfg = attach.read("config.json")       -- 附件配置
local util = attach.require("utils")         -- 附件 lua 模块（utils.lua）
```

> 插件也可向脚本贡献自定义 Lua 全局（如 OCR 插件的 `ocr`、示例插件的 `plugindemo`）：
> 插件实现 `LuaEnvContributor` 即可，机制见 `04-插件包规范.md` 与
> `plugins\demo-luaapi\`。附件里的 lua 模块由脚本经 `attach.require` 主动加载。

---

## 2. 图色

注册点：`cpp\extension\Display.cpp`（全局函数，upvalue 绑定 Display 实例）+ `3rd\autolua_vision\src\lua_vision.cc`（Bitmap）。

### 2.1 屏幕控制

| API | 说明 |
|---|---|
| getBaseSize() | 返回 w, h（基准分辨率） |
| getBaseDensity() | 基准密度 |
| getBaseDirection() / getRotation() | 屏幕方向 |
| isChangeDirection() | 是否发生旋转 |
| resetScreen() / updateScreen() | 重置 / 刷新截屏（updateScreen 释放旧帧） |
| keepScreen() | 保持当前帧不再重新截图（配合批量取色提效） |
| updateAndKeepScreen() | 刷新一帧并保持 |
| saveScreen(path[, fmt]) | 整屏保存为图片文件（仅支持 png；fmt="jpeg" 返回 false + 错误信息，不落 0 字节文件） |
| screenshot([timeout]) | **无障碍截图**（全局函数，API 30+）：无障碍服务 takeScreenshot 主屏，返回软件 ARGB_8888 Bitmap（像素可直接读）；失败/超时/API<30/服务未连接返回 nil；timeout 缺省 3000ms；内部阻塞等待回调，禁止主线程调用 |

> **旋转自动跟随**：屏幕旋转发生时引擎自动重建虚拟屏（容忍 ≤2 帧旧数据），图色函数不抛 "display rotate"；`isChangeDirection`/`resetScreen` 保留供兼容查询/手动重置。
> **无帧保护**：熄屏/采集停止时图色调用 3 秒内抛可捕获错误 `screen frame unavailable (screen off or capture stopped?)`（pcall 可接），不再永久阻塞；期间拿不到真实帧时 updateScreen 返回兜底黑帧。
> **适用运行模式**：root 主引擎（DisplayImplement 镜像直读）+ IDE 沙箱（SharedMemoryDisplay 读 MediaProjection 共享内存，随 [plans/虚拟屏功能实施任务书.md](plans/虚拟屏功能实施任务书.md) §2 落地）；无授权采集时同上抛错。

### 2.2 取色找色（自动刷帧）

| API | 说明 |
|---|---|
| getColor(x, y) | 取指定点颜色值（整数） |
| getColorCount(color, sim[, region]) | 指定颜色在区域内的数量 |
| isColor(x, y, color, sim) | 指定点是否为某色 |
| whichColor(x, y, colorTable) | 指定点匹配颜色表中的哪一项 |
| findColor(x1, y1, x2, y2, color, sim[, order]) | 区域查找单色，返回 x, y（找不到 -1,-1）；`findColorEx` 语义相同 |
| findColorAllPoint(x1, y1, x2, y2, color, sim[, order]) | 区域内**全部**单色匹配位置，返回 {{x,y},...} |
| isFeature(x, y, featureStr, sim) | 特征多点比色判定 |
| findFeature(x1, y1, x2, y2, featureStr, sim[, order]) | 多点特征查找，返回首点 x, y |
| findFeatureAll(x1, y1, x2, y2, featureStr, sim[, order]) | 区域内**全部**特征匹配原点，返回 {{x,y},...} |
| findMultiColor(x1,y1,x2,y2, firstColor, offsetStr, sim[, order]) | compat：懒人多点找色（特征串自动拼接），返回首点 |
| findMultiColorAll(x1,y1,x2,y2, firstColor, offsetStr, sim[, order]) | compat：懒人多点找色**全部位置** |
| isImage / whichImage / findImage(img, sim[, order]) | 模板图找图（img 为 loadImage 的 Bitmap 或路径字符串，支持竖线分隔多路径轮询），findImage 返回 x, y, 序号 |
| findImageAll(x1, y1, x2, y2, imgNames, sim[, order]) | 模板**全部**匹配位置，返回 {{x,y},...}（imgNames 同 findImage 路径串，竖线分隔多模板，命中任一即收集；含重叠匹配） |
| getScreenPixel(x1, y1, x2, y2) | 区域像素导出为二维数组（每行一表，元素 0xRRGGBB） |
| isDisplayDead(x1, y1, x2, y2[, delayMs]) | 区域静止检测：间隔 delayMs（默认 1000ms）两次强制采样，像素完全一致返回 true |

- `sim` 相似度 0~1。
- `order` 查找顺序，可传 FIND_ORDER 常量（见 [18. 常量表](#18-常量表)）。
- 特征串格式：`"x|y|色值,x|y|色值,..."`，首点相对坐标，懒人多点找色可直接拼接（compat 已封装 findMultiColor）。

### 2.3 Bitmap 对象

| API | 说明 |
|---|---|
| loadImage(path) | 加载图片为 Bitmap 对象 |
| bmp:getColor(x, y) 等 | 与全局同名图色方法一致，作用于该图 |
| bmp:save(path) / saveImage(bmp, path) | 保存 |
| bmp:clone() / cloneImage(bmp[, x1,y1,x2,y2]) | 克隆（可选区域裁剪） |
| bmp:getSize() / getImageSize(bmp) | 返回 w, h |

### 2.4 images 图像处理模块（全局 images 表）

入参图像为 loadImage 的 Bitmap 对象；几何变换输出统一 RGBA 布局。

| API | 说明 |
|---|---|
| images.crop(img, x, y, w, h) | 区域裁剪，返回新图 |
| images.clone(img) | 整图拷贝 |
| images.resize(img, w, h) | 最近邻缩放 |
| images.rotate90(img) / rotate180 / rotate270 | 旋转（90 为顺时针） |
| images.flipHorizontal(img) / flipVertical(img) | 左右 / 上下镜像 |
| images.getData(img) | 紧凑像素字节串（width×stride×height） |
| images.fromData(w, h, dataStr[, stride]) | 字节串构建图像（默认 RGBA 4 字节） |
| images.toPng(img) | 编码 PNG 字节串 |
| images.toJpg(img[, quality]) | 编码 JPEG 字节串（quality 1-100，默认 80；别名 `images.compress`） |
| images.fromJpg(dataStr) | JPEG 字节串 → image |
| images.saveJpg(img, path[, quality]) | JPEG 落盘，返回 bool, err |
| images.readJpg(path) | 加载 JPEG 文件（libjpeg-turbo 静态编译，无 SIMD） |
| images.save(img, path) | PNG 落盘，返回 bool, err |
| images.read(path) | 加载图片（=loadImage） |
| images.toBase64(img) | PNG → base64 |
| images.fromBase64(str) | base64 → image |
| images.readImageToBase64(path) | 图片文件 → base64(PNG) |
| images.writeFileFromBase64(str, path) | base64 解码落盘 |
| images.isNull(img) / getWidth / getHeight / getSize | 状态与尺寸 |

```lua
local img = loadImage(ROOT_DIR .. "/res/btn.png")
local half = images.resize(img, img.width_ / 2, img.height_ / 2)  -- 建议用 images.getSize
local w, h = images.getSize(half)
images.save(images.rotate90(half), ROOT_DIR .. "/out.png")
```

### 2.5 屏幕缩放适配（compat）

```lua
setScreenScale(scale)   -- 脚本按虚拟分辨率编写（如 720p 基准），运行设备自动换算
getScreenScale()        -- 默认 1（不缩放）
```
作用范围：触控（tap/swipe/touchDown/touchMove/touchUp/longTap）与图色（getColor/isColor/cmpColor/findColor/findFeature/isFeature/getColorCount/findImage/isImage/findColorAllPoint/findFeatureAll）的坐标与区域，find 系返回坐标自动反算回虚拟坐标系。

### 2.6 懒人风格别名（compat）

| API | 说明 |
|---|---|
| snapShot(path[, x, y, w, h]) | 区域截图保存（无区域参数时整屏） |
| cmpColor(x, y, color, sim) / cmpColorEx | 比色（=isColor / isFeature） |
| getPixelColor(x, y) | 取色（=getColor） |
| getColorNum(x1, y1, x2, y2, color, sim) | 区域颜色计数（懒人参数序） |
| keepCapture() / releaseCapture() | 保持帧 / 刷新帧（=keepScreen/updateScreen） |
| colorToRGB(color) / colorDiff(c1, c2) | 色值拆分 / 最大分量差 |
| findPic / findPicEx / findPicFast / findImageEx | 懒人找图别名（=findImage） |
| findPicAllPoint / findPicAll | 懒人模板全匹配别名（=findImageAll） |

---

## 3. 触控

注册点：`cpp\extension\InputManager.cpp`（人化随机轨迹内置）。

### 3.1 基础操作

| API | 说明 |
|---|---|
| tap(x, y) | 单击（人化按下-抬起） |
| swipe(x1, y1, x2, y2, dur) | 滑动，dur 毫秒 |
| keyDown(key) / keyUp(key) | 物理按键按下 / 抬起 |
| keyPress(key[, ms]) | 按键并保持 ms 毫秒（默认人化时长） |
| useHardwareInput(flag) | 切换硬件输入通道（root 下默认 true） |
| releaseAllDown() | 释放所有按下状态 |
| createPointer() | 创建多指手势 Pointer 对象 |

### 3.2 便捷触控（懒人/派大星风格）

| API | 说明 |
|---|---|
| touchDown(id, x, y) | 指定手指 id 按下 |
| touchMove(id, x, y[, steps]) | 手指移动（steps 插值步数，自动分帧） |
| touchUp(id) | 手指抬起 |
| longTap(x, y[, dur]) | 长按，默认 800ms |

### 3.3 文本输入与提示（compat）

| API | 说明 |
|---|---|
| inputText(str) | 输入文本：全 ASCII 走 root `input text`（无需焦点）；含非 ASCII 先写剪贴板再对焦点/可见可编辑节点执行粘贴（需无障碍在线） |
| toast(msg[, dur]) | 弹 Toast：沙箱走 UI 服务；root 经 luajava 反射（无 Context 时降级 system Context），失败返回 false |
| hideToast() | 取消上一个 toast（懒人语义） |

### 3.4 Pointer 对象（复杂手势）

```lua
local p = createPointer()
p.x = 500; p.y = 500      -- 属性可写：x y major minor pressure size
p:sync()                   -- 提交一帧（按下/移动）
p:up()                     -- 抬起
```

---

## 4. 节点与无障碍

注册点：`engine\...\extension\node\`（Java 注入）+ compat 全局工厂。

### 4.1 全局入口

| API | 说明 |
|---|---|
| UiSelector() | 创建选择器，链式调用追加条件 |
| getRootInActiveWindow() | 获取当前活动窗口根节点（UiObject） |
| setText(text) | 对焦点节点输入文本 |
| waitFor(sel, timeout) | 等待选择器匹配出现（引擎侧 50ms 轮询），返回 UiObject 或超时 nil；timeout 缺省 10000ms（懒人语义） |

> **运行模式**：root 主引擎（反射 UiAutomation）与沙箱/IDE（无障碍 AccessibilityImp，服务已连接时自动装配）均可用；沙箱下服务未连接时调用节点工厂/动作会抛中文提示错误（引导开启无障碍）。沙箱查找链路：活动窗口 → 全窗口 fallback（active 优先、layer 降序，上限 10 窗口）。

### 4.2 UiSelector 链式条件

文本/描述/类名/资源名各支持 `精确 / Contains / StartsWith / EndsWith / Matches` 五系：

| 条件族 | 方法 |
|---|---|
| 文本 | text textContains textStartsWith textEndsWith textMatches |
| 描述 | desc descContains descStartsWith descEndsWith descMatches |
| 内容（text OR desc） | content contentContains contentStartsWith contentEndsWith contentMatches |
| 类名 | clazz clazzContains clazzStartsWith clazzEndsWith clazzMatches |
| 资源 id | id(res) idContains idStartsWith idEndsWith idMatches |
| 包名 | pkg(pkg) pkgContains pkgStartsWith pkgEndsWith pkgMatches |
| 提示 | hint hintContains hintStartsWith hintEndsWith hintMatches |
| 布尔态 | clickable checkable checked enabled focusable focused longClickable scrollable selected visibleToUser editable password |
| 层级/位置 | index depth(min[,max]) minDepth maxDepth hasChild(sel) bounds(l,t,r,b) boundsInside(l,t,r,b) boundsContains(x,y) drawingOrder(i) |
| 屏幕几何 | screenCenterX([tol]) screenCenterY([tol]) screenCoverage([min]) |
| 交集组合 | and(sel)——Lua 保留字，冒号调用非法，用别名 andSel(sel) |

> `content` 系列为节点 text OR contentDescription **任一匹配**即通过（派大星语义，两者皆空不匹配）。
> `and` 是 Lua 保留字，`sel:and(x)` 冒号语法非法——用别名 `sel:andSel(x)`（或索引形式 `sel["and"](sel, x)`），可链式叠加多个，要求所有子选择器在同一节点上全部匹配。
> `screenCenterX/screenCenterY` 的 tol 与 `screenCoverage` 的 min 均为屏幕尺寸比例（0-1）：tol 为节点中心允许偏离屏幕中心线的比例，min 为节点面积占屏幕面积的最小比例（min=0 时要求节点面积 > 0）。
> **属性赋值糖（__newindex）**：`sel.text = "登录"` ⇔ `sel:text("登录")`（text/desc/content/clazz/id/pkg/hint 五系同构，`sel.idContains = "btn"` 亦可）；`sel.clickable = false` ⇔ `sel:clickable(false)`（false=必须不满足）；`sel.index = 2` / `sel.drawingOrder = n`。不支持的键抛中文错误。
> **调用糖（__call）**：`sel(...)` ⇔ `sel:findOne(...)`（可选 timeout 参数，缺省即时查找）。

### 4.3 查找

```lua
local root = getRootInActiveWindow()
local node = root:findOne(UiSelector():text("登录"))    -- 单个，nil 表示无
local list = root:find(UiSelector():idContains("btn"))  -- 全部，返回数组
```

**选择器侧便捷动作**（懒人 `sel:xxx()` 语义；主窗口无结果时自动在其他窗口兜底查找）：

| 方法 | 说明 |
|---|---|
| sel:findOne([timeout]) | 无参：即时查找首个匹配（无则 nil）；timeout>0：50ms 轮询直至找到或超时返回 nil |
| sel:findAll([timeout]) | 无参：即时查找全部，返回数组；timeout>0：轮询直至非空或超时返回空表 |
| sel:untilFindOne() | 无限轮询（50ms）直到找到，永不返回 nil（慎用，避免死等） |
| sel:waitFor([timeout]) | 等待匹配出现，返回 boolean；无参=无限等直到出现返回 true，有参超时返回 false |
| sel:findOnce(i) | 按索引取匹配（越界 nil） |
| sel:exists() | 是否存在匹配 |
| sel:click() / sel:longClick() | 对**首个匹配**节点执行动作，返回 bool（懒人语义为全部匹配，批量请用 autoCollection，见 4.6） |
| sel:setText(t) | 对首个匹配节点设置文本，返回 bool |

> **多窗口兜底**：findOne/findOnce/exists/click/longClick/setText 先查主窗口（活动窗口），无结果时遍历其他窗口（弹窗、分屏等）；findAll 主窗口有结果直接返回，无结果时合并全部窗口的匹配。
> **与懒人语义差异（迁移注意）**：懒人 `findOne()` 无参 = 无限等待；本引擎无参 = 即时查找。迁移脚本请把 `findOne()` 改为 `findOne(2147483647)` 或 `untilFindOne()`。

```lua
local list = auto.untilFind(auto.text("登录"))    -- 阻塞轮询（50ms）直到非空，返回全部匹配
local node = auto.untilFindOne(auto.text("登录")) -- 同上，返回首个（无限等待，慎用）
```

### 4.4 UiObject 属性与方法

属性读取（无参调用）：`text desc clazz pkg res bounds(数组{L,T,R,B}) boundsInParent(数组{L,T,R,B}) childCount parent clickable checkable checked focusable focused scrollable selected enabled editable visibleToUser longClickable password drawingOrder(API24+,低版本 -1)`

| 方法 | 说明 |
|---|---|
| child(i) | 第 i 个子节点 |
| childs() | 全部子节点数组 |
| depth() | 树深度（根为 0） |
| firstChild() / lastChild() | 首个 / 最后一个子节点（无则 nil） |
| sibling(offset) | 兄弟节点（父内索引 + offset，无父或越界 nil） |
| nextSibling() / previousSibling() | 后一个 / 前一个兄弟节点（= sibling(±1)） |
| siblings() | 除自己外的全部兄弟节点数组 |
| indexInParent() | 在父节点中的索引（无父或找不到返回 -1） |
| hasChildren() | 是否有子节点 |
| centerX() / centerY() / width() / height() | 屏幕范围几何派生（中心 x/y、宽/高） |
| center() / point() | 返回中心点 x, y **两个值**（`local x, y = nd:center()`） |
| findFocus(f) | 焦点子节点 |
| setText(t) | 设置文本 |
| find(sel) / findOne(sel) | 在子树中查找 |
| recycle() | 回收节点 |

> **节点调用/赋值糖**：`node.text = "hello"` ⇔ `node:setText("hello")`（唯一可写属性）；`node()` 刷新自身并返回 self（refresh 失败=节点已失效，抛错提示重查）；`node(i)` ⇔ `node:child(i)`，越界 nil。

### 4.5 节点动作（performAction 封装，返回 bool；API 23+ 动作在低版本返回 false）

无参：`click longClick scrollForward scrollBackward scrollUp scrollDown scrollLeft scrollRight focus clearFocus select copy paste cut collapse expand contextClick clearSelection dismiss show(=SHOW_ON_SCREEN) accessibilityFocus clearAccessibilityFocus pageUp pageDown pageLeft pageRight(API34) imeEnter(API30) showTooltip hideTooltip(API28) performAction(action)`。

带参：`setSelection(start, ends)`、`scrollTo(row, col)`（表格，API26）、`setProgress(value 0~1)`（API24）、`nextAtMovementGranularity(g)` / `previousAtMovementGranularity(g)`、`nextHtmlElement(elem)` / `previousHtmlElement(elem)`（WebView）。

### 4.6 autoCollection 节点集合批量操作（派大星对齐，compat）

```lua
local col = autoCollection.find(auto.text("确定"))  -- 全窗口查找 → 集合
-- 或 autoCollection.create(nodeArray) / autoCollection.findAll(selector) / autoCollection.findOne(selector)
col:isEmpty() col:nonEmpty() col:size() col:count() col:get(i) col:first() col:last()
col:toList() col:toArray() col:each(function(node, i) end)
col:contains(node)                                   -- 指纹比对（text/desc/res/bounds）
col:find(selector) → col2  col:findOne(selector)     -- 对集合内各节点子树二次筛选
col:setText(t)                                       -- 批量，返回成功数
-- 批量动作（遍历执行，返回成功数）：
col:click() col:longClick() col:select() col:copy() col:cut() col:paste()
col:focus() col:clearFocus() col:performAction(action)
col:scrollForward() col:scrollBackward() col:scrollUp() col:scrollDown() col:scrollLeft() col:scrollRight()
col:expand() col:collapse() col:dismiss() col:show()
```

### 4.7 compat 全局条件工厂（懒人风格，免写 UiSelector()）

`id(v) idContains(v) idStartsWith(v) idEndsWith(v) idMatches(v)`、`text(v) textContains(v) textStartsWith(v) textEndsWith(v) textMatches(v)`、`desc(v) descContains(v) descStartsWith(v) descEndsWith(v) descMatches(v)`、`className(v) classNameContains(v) classNameStartsWith(v) classNameEndsWith(v) classNameMatches(v)`、`packageName(v) packageNameMatches(v)`、`clickable(v) scrollable(v) enabled(v) checkable(v) checked(v) focusable(v) focused(v) longClickable(v) selected(v) editable(v) visibleToUser(v) password(v) depthSel(min,max) drawingOrderSel(i)` —— 全部返回可继续链式的 UiSelector。

### 4.8 auto.* 全局工厂（派大星风格）

```lua
auto.text("登录"):clickable(true)     -- 条件工厂，等价 UiSelector():text(...)
auto.findOne(sel) / auto.find(sel)    -- 在当前窗口查找
auto.untilFind(sel) / auto.untilFindOne(sel)  -- 阻塞轮询（50ms）直到找到，无限等待（慎用）
auto.waitFor(sel, timeout)            -- 轮询等待出现，超时返回 nil
auto.exists(sel)                      -- 是否存在
auto.getScreenSize()                  -- 返回 w, h
auto.click(x,y) / auto.swipe(...) / auto.longClick(x,y[,dur])
auto.back() / auto.home() / auto.recents()
auto.touchDown/Move/Up(id, ...) / auto.press(key, ms)
```

条件方法与全局工厂同名（auto.text/auto.id/auto.desc/auto.className/auto.clickable/auto.enabled/auto.scrollable/auto.depth/auto.visibleToUser/auto.editable/auto.bounds/auto.boundsInside）。

### 4.9 节点树导出与调试（NodeLib）

全局函数（引擎侧直注，需无障碍在线；注册点 `NodeLib.kt` + `AutoLuaEngineOnLocal.kt`）：

| API | 说明 |
|---|---|
| getNodeXml([includeInvisible]) | 导出当前节点树 XML 字符串；includeInvisible 缺省走 setNodeMode 持久状态（enableInvisible，默认 false：vt=0 的节点整个子树跳过）；无障碍不可用（root 为 null）时抛错 |
| saveNode([path], [includeInvisible]) | 导出节点树 XML 并落盘（懒人 saveNode/saveNodeNew 轻量对齐：写通用 XML，不做 .node 私有格式）；缺省路径 `<rootDir>/node_dump_<yyyyMMdd_HHmmss>.xml`（rootDir 同 ROOT_DIR，未注入时退 java.io.tmpdir、再退 /data/local/tmp），自动建父目录；返回实际写入路径 string；无障碍不可用或写文件失败抛错 |
| findByIndex(path) | 按每层父内索引路径定位节点（如 `"0|1|2"`，容忍 `/` 分隔），返回 UiObject 或 nil；与 getNodeXml 导出的 `i` 属性对应（节点工具路径可直接回放）；越界/负数/非法 token/中途断链返回 nil，空路径抛错 |
| setNodeMode([mode], [enableGetInVisibleNode]) | 切换节点获取模式（懒人语义轻量版）：mode=0 全部节点（默认）/ 1 简单模式——动态开关服务 FLAG_INCLUDE_NOT_IMPORTANT_VIEWS（以当前 serviceInfo 为基线仅动该位，两通道均适用）；enableGetInVisibleNode 更新持久状态并作为 getNodeXml 缺省 includeInvisible；非空参数才更新对应状态 |
| keepNode() | 锁定当前界面：缓存 root 快照，后续选择器查找全部走缓存（懒人语义；批量查找提效且防界面切换干扰） |
| releaseNode() | 清除缓存，恢复实时取 root |
| updateNode() | 重抓 root 刷新缓存（界面已切换但仍需在锁定态查找时用） |
| openAccessibility() | 尝试开启无障碍：root shell 写 settings 自动启用（追加式，不清其他服务）并返回 true；失败（非 root/沙箱）跳系统无障碍设置页并返回 false |
| closeAccessibility() | 关闭本引擎无障碍服务（与 openAccessibility 对称）：从 settings 启用列表移除本服务组件（按 `:`/空格 分隔，移除后为空写 `''`）并写 accessibility_enabled 0；两步 shell 退出码均 0 返回 true；拿不到包名（root 进程无 Context）直接返回 false——整串置空会误伤其他服务，且不跳设置页 |
| isAccServiceOk() | 无障碍服务是否可用（服务已连接，或 settings 启用列表已含本服务组件） |

XML 契约（VSCode 插件按此解析，字段名勿改）：根元素 `<nodes w=".." h="..">`（屏幕宽高）；节点元素 `<n>` 属性（布尔 0/1）：`i`=父内索引、`d`=深度（root 为 0）、`c/t/de/id/p`=类名/文本/desc/资源 id/包名、`b/bp`=屏幕/父内 bounds（"l,t,r,b"）、`ck/cc/ch/en/fo/fc/sc/lc/se/ed/vt/pw`=布尔态（clickable/checkable/checked/enabled/focusable/focused/scrollable/longClickable/selected/editable/visibleToUser/password）、`do`=drawingOrder（API<24 写 -1）；文本字段已 XML 转义。

落盘与索引回放：`saveNode()` 一步完成"导出 XML + 写文件"并返回实际路径（可直接上传/展示/供节点工具打开）；节点工具里逐层复制的父内索引（对应 `i` 属性）可用 `findByIndex("0|1|2")` 直接回放定位到 UiObject。

```lua
local xml = getNodeXml()                     -- 仅可见节点
writeFile(ROOT_DIR .. "/tree.xml", xml)
getNodeXml(true)                             -- 含不可见节点

local path = saveNode()                      -- 落 rootDir/node_dump_<时间戳>.xml，返回实际路径
saveNode(ROOT_DIR .. "/dump/ui.xml", true)   -- 指定路径 + 含不可见节点

local node = findByIndex("0|1|2")            -- 与 getNodeXml 的 i 属性对应（逐层父内索引）
if node then node:click() end

setNodeMode(1)                               -- 简单模式（仅重要视图），getNodeXml 缺省不再含不可见节点
setNodeMode(0)                               -- 恢复全部节点（默认）

keepNode()                                   -- 锁定当前界面（懒人快照缓存）
local ok = text("确定"):exists()              -- 查找走缓存快照
updateNode()                                 -- 界面切换后重抓刷新缓存
releaseNode()                                -- 解锁，恢复实时查找

if not isAccServiceOk() then openAccessibility() end
closeAccessibility()                         -- 关闭本引擎无障碍服务（root shell 写 settings）
```

**IDE 沙箱**：沙箱无选择器/动作能力，仅注入只读桥 `nodeLib`（`nodeLib.getNodeXml([includeInvisible])` / `nodeLib.isAccServiceOk()` / `nodeLib.saveNode([path], [includeInvisible])` / `nodeLib.findByIndex(path)`；不暴露 setNodeMode/closeAccessibility 与 keepNode 等写入口，避免污染主引擎查找缓存/无障碍开关状态），供 VSCode 插件经设备 EXECUTE 通道抓取节点树与落盘调试。

**VSCode 插件**：命令 "AutoLua: 抓取节点树"（`autolua2.grabNodeTree`）——从设备拉取 getNodeXml（未连接/失败时降级剪贴板/本地 .xml 文件）→ WebView 树面板（折叠/过滤/属性表），并一键生成懒人式 / UiSelector 式选择器代码复制（全树验证最少条件唯一定位）。

---

## 5. 文件

服务：`extension\file\FileLib.kt` → 全局 `file` 对象 + 懒人风格全局函数。

### 5.1 全局函数（懒人风格）

| API | 说明 |
|---|---|
| fileExist(path) | 文件/目录是否存在 |
| mkdir(path) | 递归创建目录 |
| delfile(path) | 递归删除文件/目录 |
| fileSize(path) | 文件字节数（目录 -1） |
| readFile(path) | 读文本（UTF-8） |
| writeFile(path, content) | 写文本（覆盖，自动建父目录） |
| listDir(path) | 目录列举，返回名字数组 |

### 5.2 file.* 对象式 API

| API | 说明 |
|---|---|
| file.exists(path) / file.isFile(path) / file.isDirectory(path) | 类型判断 |
| file.size(path) | 字节数 |
| file.mkdir(path) / file.mkdirs(path) | 创建目录（单级 / 递归） |
| file.remove(path) | 单层删除（空目录或文件） |
| file.deleteDir(path) | 递归删除 |
| file.rename(from, to) / file.copy(from, to) | 重命名 / 复制（支持目录递归） |
| file.mTime(path) | 最后修改时间（毫秒时间戳） |
| file.listDir(path) | 目录列举 |
| file.read(path[, binary]) | 读文本；binary=true 返回字节数组 |
| file.write(path, content[, append]) | 写文本；append=true 追加 |
| file.append(path, content) | 追加文本 |

---

## 6. 设备

服务：`extension\device\DeviceLib.kt` → 全局 `device` 对象 + 同名全局函数（下表方法全局亦可直接调）。

### 6.1 系统信息（Build，无需 Context）

| API | 说明 |
|---|---|
| getBrand() / getModel() / getManufacturer() | 品牌 / 机型 / 制造商 |
| getBoard() / getHardware() / getProduct() / getDevice() | 硬件标识 |
| getFingerprint() / getId() / getBootLoader() | 固件信息 |
| getCpuAbi() / getCpuAbi2() | 主/次 ABI（device.getCpuAbi2 仅对象式） |
| getSdkVersion() | Android API Level |
| getOsVersionName() / getAndroidVersion() | 系统版本号（如 "13"） |

### 6.2 标识与状态（需 Context；root 进程返回空/nil）

| API | 说明 |
|---|---|
| getAndroidId() / getDeviceId() | ANDROID_ID（Android 10+ 无 IMEI，两者同值） |
| getBatteryLevel() | 电量百分比（0-100，失败 -1） |
| device.isCharging() | 是否充电中（仅对象式） |
| getDisplayDpi() | 屏幕密度 DPI |
| getDisplaySize() | 返回 w, h 两值 |
| getMacAddress() / getWifiMac() | MAC 地址 |
| getIpAddress() | IPv4 地址 |
| device.isWifiEnabled() | WiFi 是否开启（仅对象式） |

```lua
print(getModel(), getSdkVersion())          -- 全局风格
local w, h = getDisplaySize()
print(device.isCharging())                  -- 对象式扩展
```

---

## 7. 应用与 shell

服务：`extension\app\AppLib.kt` → 全局 `app` 对象。root 主引擎经 `am/pm/dumpsys/pidof` 实现，沙箱降级为 shell 权限。

### 7.1 全局函数（懒人风格）

| API | 说明 |
|---|---|
| runApp(target) | 启动应用：包名或 http(s) 链接（浏览器打开） |
| stopApp(packageName) | 停止应用（am force-stop，需 root） |
| appIsRunning(packageName) | 是否在运行（pidof） |
| appIsFront(packageName) | 是否前台应用 |
| frontAppName() | 前台应用包名 |
| getCurrentActivity() | 当前 resumed Activity 描述 |
| getInstalledApps() | 第三方应用包名数组 |
| exec(cmd[, isRet]) | 执行 shell 命令；isRet 非 false 返回输出字符串，否则返回 (output, exitCode) 两值 |

### 7.2 app.* 对象式扩展

| API | 说明 |
|---|---|
| app.execOut(cmd) | 仅返回输出 |
| app.launchPackage(packageName) | monkey 方式启动 |
| app.killApp(packageName) | 同 stopApp |
| app.isAppInstalled(packageName) | pm path 判定 |
| app.getTopPackageName() | 前台包名（mCurrentFocus 解析） |
| app.getAppVersionName(packageName) | 版本号 |

```lua
runApp("com.tencent.mm")
if appIsFront("com.tencent.mm") then print("微信已到前台") end
local out, code = exec("ls /sdcard", false)
```

---

## 8. 网络

服务：`extension\net\NetLib.kt`（HttpURLConnection 同步实现）→ 全局 `http` 对象。返回多返回值 `(body, code, headers)`。

| API | 说明 |
|---|---|
| http.request{options} | 完整请求。options：`url`(必填) `method`(默认 GET) `headers`(表) `body`(字符串/字节数组) `timeout`(毫秒，默认 10000) `binary`(true 时 body 为字节数组) |
| http.get(url) | 便捷 GET，返回 body, code |
| http.post(url, body) | 便捷 POST（form-urlencoded），返回 body, code |

```lua
-- 完整请求
local body, code, headers = http.request{
    url = "http://api.example.com/login",
    method = "POST",
    headers = {["Content-Type"] = "application/json"},
    body = '{"user":"a","pwd":"b"}',
    timeout = 10000,
}
-- 便捷式
local html, code = http.get("http://www.baidu.com")
-- 二进制下载
local data, code = http.request{url="http://x/img.png", binary=true}
```

### compat 全局别名

| API | 说明 |
|---|---|
| httpGet(url) | → http.get，返回 body, code |
| httpPost(url, bodyData) | → http.post |
| downloadFile(url, path) | GET 二进制下载并落盘，返回 (true, code) 或 (false, code) |

---

## 9. 加密

服务：`extension\crypto\CryptoLib.kt` → 全局 `crypt` 对象 + 常用全局别名。

### 9.1 哈希

| API | 说明 |
|---|---|
| crypt.md5(data) | MD5 hex 字符串 |
| crypt.sha1(data) / crypt.sha256(data) / crypt.sha512(data) | SHA 族 hex |
| crypt.fileMD5(path) | 文件 MD5 |
| MD5(data) / fileMD5(path) / sha256(data) | 全局别名 |

### 9.2 Base64 / URL

| API | 说明 |
|---|---|
| crypt.encodeBase64(data) / crypt.decodeBase64(data) | UTF-8 文本编解码 |
| crypt.encodeBase64Bytes(bytes) / crypt.decodeBase64Bytes(str) | 字节级编解码 |
| crypt.getFileBase64(path) | 文件转 Base64 |
| crypt.encodeUrl(data) / crypt.decodeUrl(data) | URL 编解码 |
| encodeBase64 / decodeBase64 / getFileBase64 / encodeUrl / decodeUrl | 全局别名 |

### 9.3 AES / RSA

| API | 说明 |
|---|---|
| crypt.aesCrypt(mode, key, data[, iv]) | 懒人 aes_crypt 语义：mode "E"加密/"D"解密；key 16/24/32 字节；有 iv 用 CBC、无 iv 用 ECB（PKCS5Padding）；加密返回 Base64，解密入参按 Base64 |
| crypt.aesKeygen(size) / crypt.aesIvgen() | 随机 key / iv（原始字节字符串） |
| crypt.rsaGenerateKey(keySize) | 返回 {publicKey=base64, privateKey=base64} |
| crypt.rsaEncrypt(data, keyBase64[, privateKey]) | 公钥加密（true 时私钥，签名语义），返回 Base64 |
| crypt.rsaDecrypt(dataBase64, keyBase64[, privateKey]) | 私钥解密（默认 true），返回明文 |

```lua
local key = crypt.aesKeygen(16)
local enc = crypt.aesCrypt("E", "0123456789abcdef", "hello")           -- ECB
local dec = crypt.aesCrypt("D", "0123456789abcdef", enc, "0123456789abcdef") -- CBC 解密
```

---

## 10. JSON

注册点：`cpp\base\json_lib.cpp`（cjson 绑定为全局 `json`）。

| API | 说明 |
|---|---|
| json.encode(value) | Lua 表/值 → JSON 字符串（int 键表自动作数组） |
| json.decode(str) | JSON 字符串 → Lua 表 |
| jsonLib | compat 注册的懒人风格别名 = json |

```lua
local t = json.decode('{"a":1,"list":[1,2,3]}')
local s = json.encode({name="x", nums={1,2,3}})
```

---

## 11. 线程与定时

注册点：`cpp\base\thread_lib.cpp`（同 state 协程方案，_G 与闭包天然共享）。

### 11.1 thread.* 原语

| API | 说明 |
|---|---|
| thread.start(func, ...) | 启动线程任务，返回句柄；其余参数作为 func 入参 |
| thread.sleep(ms) | 线程内延时（挂起让出执行权，不阻塞其他线程） |
| thread.lock() | 创建互斥锁对象；`lk:acquire()` 加锁 / `lk:release()` 解锁（等待期间让出调度） |
| thread.stop(handle) | 终止任务（在挂起点抛出） |
| thread.join(handle) | 阻塞等待结束，返回 ok, 结果 |
| thread.currentId() | 当前线程 id |
| thread.isMain() | 是否主 worker |
| thread.setShared(key, v) / getShared(key) / hasShared(key) / removeShared(key) | 跨线程共享表 |
| thread.atomicInc(key[, delta]) | 原子自增（默认 +1），返回新值 |

### 11.2 定时与别名（compat）

| API | 说明 |
|---|---|
| setTimer(func, ms, ...) | 后台延时执行（thread 协程驱动，不阻塞主脚本），返回句柄 |
| beginThread(func, ...) / newThread(func, ...) | → thread.start（懒人别名） |
| th.stopThread(handle) | → thread.stop |

```lua
local lk = thread.lock()
local cnt = 0
local t1 = thread.start(function()
    for i = 1, 5 do
        lk:acquire() ; cnt = cnt + 1 ; lk:release()
        thread.sleep(100)
    end
end)
thread.join(t1)
setTimer(function() print("3 秒后执行") end, 3000)
```

---

## 12. Java 桥

服务：`extension\bridge\`（JavaBridgeLib + LuaEngineLib）→ 全局 `luajava`、`LuaEngine` 对象。

### 12.1 luajava 反射桥

| API | 说明 |
|---|---|
| import(name) / importClass(name) | 全局函数：加载 Java 类，返回类对象（支持后续链式静态调用） |
| luajava.bindClass(name) | 对象式：同 import |
| luajava.new(classObj, ...) | 按参数匹配构造器实例化 |
| luajava.newInstance(name, ...) | 类名 + 参数直接实例化 |
| cls:new(...) | 构造语法糖 |
| cls.静态字段 / cls:静态方法(...) | 静态访问 |

```lua
local Integer = import("java.lang.Integer")
print(Integer:parseInt("42"))
local sb = import("java.lang.StringBuilder"):new()
sb:append("a"):append("b")
print(sb:toString())
```

### 12.2 LuaEngine 桥

| API | 说明 |
|---|---|
| LuaEngine.getContext() | Android Context（root 进程反射 ActivityThread 获取，可能 nil） |
| LuaEngine.loadApk(path, className) | DexClassLoader 加载 apk/dex 中的类，返回类对象（配合 luajava:new 实例化） |
| LuaEngine.getVersion() | 引擎版本（"2.0"） |

---

## 13. 字符串扩展

注册点：compat 兼容层（懒人风格，基于标准 string/utf8 组合）。

### 13.1 splitStr

| API | 说明 |
|---|---|
| splitStr(str[, sep]) | 按分隔符分割（默认逗号），返回数组 |

### 13.2 utf8.* 扩展（按字符位置，1 起始，负数从尾计）

| API | 说明 |
|---|---|
| utf8.length(s) | 字符数（= 标准 utf8.len） |
| utf8.sub(s, i[, j]) | 按字符取子串 |
| utf8.left(s, n) / utf8.right(s, n) | 左 / 右 n 字符 |
| utf8.mid(s, i[, j]) | 同 sub |
| utf8.inStr(s, sub[, from]) | 子串首次出现字符位置（0 = 不存在） |
| utf8.inStrRev(s, sub) | 最后一次出现位置 |
| utf8.strReverse(s) | 按字符反转 |
| utf8.strCut(s, pos) | 取第 pos 个字符 |
| utf8.gsub(s, replFunc) | 逐字符替换（回调返回 nil 保留原字符） |

---

## 14. 数组/表扩展

| API | 说明 |
|---|---|
| table.contains(t, v) | 是否包含元素 |
| table.indexOf(t, v) | 元素下标（1 起始，无则 -1） |

Lua 5.4 标准 table 库（concat/insert/remove/sort/unpack）同时可用。

---

## 15. 数学与随机

### 15.1 math 扩展（compat）

| API | 说明 |
|---|---|
| math.clamp(v, lo, hi) | 限幅 |
| math.round(v) | 四舍五入 |
| math.lerp(a, b, t) | 线性插值 |
| math.sign(v) | 符号（-1/0/1） |
| math.isEven(v) / math.isOdd(v) | 奇偶 |
| math.inRange(v, lo, hi) | 区间判定 |
| math.isInteger(v) | 是否整数 |

### 15.2 frandom 随机扩展（compat）

| API | 说明 |
|---|---|
| frandom.seed([s]) | 播种（默认 os.time） |
| frandom.int(lo, hi) / frandom.float(lo, hi) | 整数 / 浮点随机 |
| frandom.boolean([p]) / frandom.chance([p]) | 概率布尔（默认 0.5） |
| frandom.pick(list) | 随机取元素 |
| frandom.floatRange(lo, hi) | 同 float |
| frandom.hex([n]) | n 位 hex 串（默认 8） |
| frandom.string([n[, charset]]) | 随机字符串（默认 8 位字母数字） |
| frandom.uuid() | UUID v4 |
| frandom.shuffle(list) | 原地洗牌 |
| frandom.weighted(items) | 权重抽取：{{value=..., weight=...}, ...} |
| rnd(lo, hi) | 懒人别名 = frandom.int |

---

## 16. 时间

| API | 说明 |
|---|---|
| systemTime() | 系统墙钟毫秒（System.currentTimeMillis） |
| tickCount() | 开机计时毫秒（SystemClock.elapsedRealtime） |
| getNetWorkTime() | 网络时间 "YYYY-MM-DD HH:MM:SS"（HTTP Date 头解析，需网络） |
| time.unix() / time.timestamp() / time.millisecond() | 秒 / 毫秒 / 当前毫秒部分 |
| time.now() | os.date("*t") 表 |
| time.year() / time.month() / time.day() / time.hour() / time.minute() / time.second() / time.weekday() | 分量 |
| time.utc() | UTC 表 |
| time.format([t[, fmt]]) | 格式化（表或时间戳，默认 "%Y-%m-%d %H:%M:%S"） |
| time.parse(str) | 解析 "YYYY-MM-DD [HH:MM:SS]" → 时间戳 |
| time.diff(t1, t2) / time.addDays/addHours/addMinutes/addSeconds(t, n) | 差值 / 平移 |
| time.isLeapYear(y) / time.daysInMonth(y, m) | 闰年 / 月天数 |

---

## 17. UI 与悬浮窗

注册点：`app\...\extension\`（沙箱注入服务）。

| API | 说明 |
|---|---|
| UI.showToast(msg[, dur]) | Toast 提示 |
| UI.createView(uri, LayoutParams) | 创建悬浮视图 |
| UI.putSignal(sig, data) / UI.takeSignal(sig[, timeout]) | 信号收发（UI 与脚本通信） |
| FloatController | 悬浮球启停控制服务 |
| FloatView 对象 | show() hide() setPosition(x,y) setSize(w,h) setContent(view) isShowing() setAlpha(a) |
| LayoutParamsFlag | NOT_FOCUSABLE / NOT_TOUCHABLE / NOT_TOUCH_MODAL |

UI 体系完整能力（布局/热重载）走 MLN Kit，见 app 侧 UI 文档，不在本篇展开。
生产路线动态 UI 用 `ui` 表（17.1），MLN 仅保留调试预览。

### 17.1 动态 UI（全局 ui 表；JSON 控件树 + op 协议）

UI 实例固定在 app 主进程（`uimgr` 服务，`app\...\extension\ui\`），Lua（root 进程/沙箱）经跨进程代理调用；
控件事件经 `uimgr:pollEvents()` 事件队列回传，`uilib`（engine `extension\ui\UiLib.kt`）内置 50ms
轮询协程统一派发。root/沙箱同 API。

**窗口**：

| API | 说明 |
|---|---|
| ui.createWindow(cfg, root) → win | 创建悬浮窗并立即显示。cfg={win?,x,y,w,h,drag,focus,alpha,fullscreen,gravity,notTouchable}；root=控件树。尺寸：-1=match -2=wrap 数字=px "50%"=屏幕百分比 |
| ui.win | createWindow 别名 |
| ui.close(win) | 关窗（级联销毁 WebView/VideoView） |
| ui.show / ui.hide(win) | 显示 / 隐藏 |
| ui.move(win,x,y) / ui.setSize(win,w,h) / ui.setAlpha(win,a) | 窗级操作 |
| ui.list() → {win,...} | 窗口 id 列表 |

**控件 type 全集**：容器 `linear`(默认纵向) `frame` `relative`(alignParent/below/above/leftOf/rightOf) `scroll` `scrollH` `grid` `card` `pager`(可带 titles 页签)；基础 `text` `button` `edit` `checkbox` `switch` `radio` `spinner` `seek` `rating` `progress`；媒体 `image`(src: 路径/URL/base64:) `webview`(url/html/localFile) `video` `list`(items + itemXml 模板 `{{字段}}` 替换)；自绘 `canvas`(ops: color/clear/line/rect/circle/roundRect/arc/text/path)。

**通用属性**：`id w h margin/marginTop/... padding/padTop/... gravity layout_gravity bg`(色值 或 {color,corner,stroke:{color,width},shape}) `visibility`(visible/invisible/gone) `enabled alpha clickable rotation elevation tag weight`。

**控件增删改查**：

| API | 说明 |
|---|---|
| ui.add(win, parent, node[, index]) → id | 动态增（node 同 build 描述；scroll 容器自动替换唯一子节点） |
| ui.set(win, id, attrs) | 动态改（批量属性一帧；list items/itemXml、canvas ops、webview url、video start/pause 同此） |
| ui.remove(win, id) | 动态删（连子树） |
| ui.get(win, id) → 值表 | 动态查 {type,text,checked,selectedIndex,progress,url,currentItem,x,y,w,h,...} |
| ui.tree(win) → 整树快照 | children 递归 |

**事件**（Java 侧全量常挂，未 bind 自动丢弃）：`click longClick touch check select progressChange textChange editorAction itemClick itemLongClick scrollEnd itemSelect(pager) urlLoad pageDone`。

| API | 说明 |
|---|---|
| ui.bind(win, id, event, fn) → key | 回调 fn(ev)，ev={ev,win,id,event,data}。**每事件独立 thread 协程**：回调内可同步弹窗/继续 ui_add/set/remove，互不阻塞 |
| ui.unbind(win, id, event) | 解绑 |

**弹窗**（独立 overlay 窗 + 全屏 scrim + requestId 闭环，可叠栈；win="__dlg_<req>" 可 node.set 寻址）：

| API | 说明 |
|---|---|
| ui.dialog(opts) | opts.type=alert\|confirm\|input\|sheet\|custom；title/msg/buttons({text,action}或字符串)/fields({id,label,text,inputType})/items/content(控件树)；**wait=true 同步等结果（须在 thread 协程内）**；timeout 毫秒(0=不限)；onResult 异步回调。结果 {action,values,index} |
| ui.alert(title,msg[,onOk]) | 同步返回结果表；传 onOk 转异步 |
| ui.confirm(title,msg[,cb]) → bool | 同步返回是否"确定" |
| ui.input(opts[,cb]) → 结果表 | opts={title,label,text,inputType,fields}；values 按字段 id |
| ui.sheet(title,items[,cb]) → {action,index} | 底部菜单；取消 index=-1 |
| ui.dialogClose(win[, action]) | 代码关闭弹窗 |

custom 弹窗 content=控件树，关闭时按 id 收集 values（text/checked/selectedIndex/progress）。

**console 日志悬浮窗**（全局 console 表）：`show / dismiss / println(s) / clear / isShowing`（可拖动，200 行环形缓冲）。

```lua
local win = ui.createWindow({ w = 300, h = 200, drag = true }, {
  type = "linear", bg = { color = "#F0222233", corner = 10 }, padding = 16,
  children = {
    { type = "text", id = "title", text = "挂机面板" },
    { type = "edit", id = "acc", text = "默认号" },
    { type = "button", id = "go", text = "启动" } } })
ui.bind(win, "go", "click", function(ev)
  if ui.confirm("确认", "开始挂机？") then
    local acc = ui.get(win, "acc").text
    ui.set(win, "title", { text = "运行中：" .. acc })
    ui.add(win, nil, { type = "checkbox", id = "loop", text = "循环" })
  end
end)
```

---

## 18. 常量表

### 18.1 KeyCode（全局表）

HOME BACK MENU VOLUME_UP VOLUME_DOWN POWER RECENT VOLUME_MUTE DPAD_UP DPAD_DOWN DPAD_LEFT DPAD_RIGHT DPAD_CENTER ENTER TAB DEL SEARCH CAMERA CALL ENDCALL

```lua
keyPress(KeyCode.BACK)
auto.home()
```

### 18.2 LayoutParamsFlag（全局表）

NOT_FOCUSABLE NOT_TOUCHABLE NOT_TOUCH_MODAL

### 18.3 FIND_ORDER（找色/找图方向）

findColor/findImage 的 order 参数：默认从头扫描；方向常量在 Display 注册表内（UP/DOWN/LEFT/RIGHT 及组合 8 向）。

---

## 19. 扩展模块

### 19.1 path 路径处理（全局 path 表，compat）

| API | 说明 |
|---|---|
| path.join(...) | 拼接（自动处理斜杠去重） |
| path.dirname / basename / getName / getParent | 目录 / 文件名 |
| path.extname | 扩展名（带点 ".txt"） |
| path.normalize / resolve / getAbsolutePath | 规范化 / 基于 ROOT_DIR 解析 |
| path.exists / isReadable | 存在 / 可读 |
| path.isAbsolutePath / isAssetPath / stripAssetPrefix | 判定与剥离 |
| path.getProjectDir / setProjectDir | 项目根（=ROOT_DIR） |
| path.getAppFilesDir | app files 目录（luajava 反射，root 降级 nil） |
| path.getCurrentScriptPath / setCurrentScriptPath / getWorkingDir | 脚本路径 |
| path.resolveCandidates(...) | 返回首个存在的候选路径 |

### 19.2 coordinate 坐标转换（全局 coordinate 表，派大星对齐）

| API | 说明 |
|---|---|
| getScreenSize() | 实际分辨率 |
| setScreenSize(vw, vh) | 设虚拟分辨率基准（复用 setScreenScale） |
| p2rX/p2rY/p2r/p2rRect | 百分比(0~100) → 像素 |
| r2pX/r2pY/r2p/r2pRect | 像素 → 百分比 |
| getCenter / getCenterPercent | 屏幕中心 |

### 19.3 timer 定时器（全局 timer 表 + setTimeout/setInterval，派大星对齐）

```lua
local id = timer.set(fn, ms, repeat)  -- repeat=true 周期执行
timer.clear(id) / timer.clearAll()
setTimeout(fn, ms) / setInterval(fn, ms) / clearTimeout(id) / clearInterval(id)
```

### 19.4 事件回调（懒人 setXxxEventCallBack 系，进程内总线）

`setUserEventCallBack(cb) / setPluginEventCallBack(cb) / setNotifyEventCallBack(cb)` +
`sendUserEvent(name, data) / sendPluginEvent(name, data) / sendNotifyEvent(name, data)`（无回调返回 false）。

### 19.5 media 音频（全局 media 表 + playAudio/stopAudio，派大星对齐）

`playAudio(path[, loop]) stopAudio pauseAudio resumeAudio seekTo(ms) isPlaying getDuration getCurrentPosition getPosition setVolume(0~1) release`。

### 19.6 fhttp 网络请求（全局 fhttp 表，派大星对齐；底层 http 服务）

同步：`get(url[,headers]) post(url,body[,headers]) postJson(url,json[,headers]) postForm(url,formTable[,headers]) put delete patch head options download(url,path[,headers]) upload(url,path,fieldName[,fileName,form]) setTimeout(ms) setProxy(host,port)`。

异步族（**句柄模式，网络 IO 在独立 Java 线程**）：`http.requestAsync(options) / http.downloadAsync(url,path[,headers]) / http.uploadAsync(url,path,fieldName[,fileName,form])` 返回句柄；`handle:isDone()` 轮询、`handle:get()` 取结果 `(body, code, headers)`（download/upload 为 bool）、`handle:cancel()` 中断。`fhttp.requestAsync(opts)` 直通句柄；`fhttp.getAsync(url,cb)` 等 **回调族** 由 compat 包装（thread 协程内回调受 Java 调用限制，长任务/并发场景一律用句柄轮询）。

### 19.7 ws 双向 WebSocket（全局 ws 表，libhv 绑定；事件轮询模型）

```lua
-- 服务端
ws.serverStart(9000)                      -- 端口占用返回 nil, err
local ev, conn, msg = ws.serverPoll(50)   -- "open",conn | "message",conn,msg | "close",conn | nil
ws.serverSend(conn, "text")               -- ws.serverSend(conn, bytes, true) 二进制
ws.serverBroadcast("hi")                  -- 广播，返回送达数
ws.serverConnCount() / ws.serverIsRunning() / ws.serverStop()
-- 客户端
local cid = ws.clientConnect("ws://127.0.0.1:9000/test"[, connectTimeoutMs])
local ev, data = ws.clientPoll(cid, 50)   -- "open" | "message",data | "close" | nil
ws.clientSend(cid, "text")                -- ws.clientSend(cid, bytes, true) 二进制
ws.clientIsOpen(cid) / ws.clientClose(cid)
```

### 19.8 plugin 插件管理（全局 plugin 表；契约见 04-插件包规范.md）

| API | 说明 |
|---|---|
| plugin.list() | {{id,name,version,provides={...},loaded}, ...} |
| plugin.getLoaded() | {[id]=true,...} |
| plugin.load(id) / plugin.unload(id) | 加载 / 卸出（unload 保留安装目录） |
| plugin.loadAll() | 重载全部 |
| plugin.install(zipPath) | 本地 zip 直装 → bool, err |
| plugin.uninstall(id) | 卸载（删目录） |
| plugin.getPluginPath(id) / getSoPath(id) | dex/jar / so 目录 |

### 19.9 ocr 脚本调用（全局 ocr 表；OCR 插件运行时）

| API | 说明 |
|---|---|
| ocr.isSupported() / ocr.init() | 是否安装了 provides=["ocr"] 的插件 |
| ocr.list() | 已安装 OCR 插件 id 数组（多 OCR 共存） |
| ocr.use(id?) | 切换激活 OCR 插件 → bool；缺省/nil 恢复默认（第一个注册的） |
| ocr.active() | 当前生效插件 id（无插件为 nil） |
| ocr.ocrText(x1,y1,x2,y2) | 区域识别，返回文本（多行拼接）；无 Display 返回 nil, err |
| ocr.ocrEx(x1,y1,x2,y2) | 返回块数组 {{text=,x=,y=,w=,h=,confidence=},...} |
| ocr.ocrExNew(x1,y1,x2,y2) | 块坐标相对基点（懒人形态） |
| ocr.recognizeRaw(frame,fw,fh,stride,x,y,w,h) | 直接识别帧数据（getScreenFrame 提供） |
| ocr.recognizeFile(pngPath) | 识别 PNG 文件（调试用） |

### 19.10 ime 输入法（全局 ime 表，`imeLib` 别名；派大星对齐）

root 进程零 Context 依赖：settings/am shell + 广播到宿主 AutoLuaInputMethodService（receiver 已导出）执行。

| API | 说明 |
|---|---|
| ime.setText(text) / commitText / inputLText | 提交文本到焦点输入框 |
| ime.clearField([n]) / deleteChar([n]) | 清空输入框 / 退格删除（默认 1） |
| ime.showKeyboard() / hideKeyboard() / finishInput() | 键盘显示 / 隐藏 |
| ime.keyEvent(keyCode) | 经 IME 发送按键 |
| ime.isActive() | 键盘是否已显示（dumpsys input_method mInputShown） |
| ime.isOurIMEActive() | 本引擎 IME 是否为当前默认输入法 |
| ime.setAsDefaultIME() | root 写 settings 切换为本 IME 并回读验证 |
| ime.setCurrentIME(imeId) | 切换到指定输入法（root） |
| ime.showInputMethodPicker() | 弹出系统输入法选择器（宿主 IME 服务代理） |
| ime.getCurrentIMEName() | 当前默认输入法 id |
| ime.openIMESettings() | 打开系统输入法设置页 |

### 19.11 mysql 数据库（全局 mysql 表 + 懒人全局函数；零依赖 MySQL 协议客户端）

支持 MySQL 4.1+（认证：mysql_native_password、caching_sha2_password 快速认证；完整认证需 SSL/RSA 不支持，报错提示改用户认证方式）。连接非并发安全（脚本内串行使用）。

```lua
-- 对象式
local conn = mysql.connect(host, port, user, pwd, db[, timeoutMs])  -- 失败 nil, err
local rows = conn:query("select * from t")   -- 失败 nil, err
local affected = conn:execute("update t set a=1")
conn:ping()                                  -- COM_PING
conn:close()
conn.connected / conn.affectedRows / conn.lastInsertId
mysql.escape(str)                            -- \0 \n \r \ ' " \Z 转义

-- 懒人全局（隐式当前连接）
connectSQL(host, port, user, pwd, db[, timeout]) → bool, err
executeQuerySQL(sql) → rows / nil, err
executeSQL(sql) → affected / nil, err
closeSQL() → bool
-- rows 每行为表：[1..n] 按列序 + [列名]=值 双形态；NULL 为 nil
```

### 19.12 event 事件总线（全局 event 表，派大星对齐）

多播注册 + 系统事件源。系统事件（toast/notification/click/touch/key）经无障碍服务 → 事件队列文件（追加式 JSON-lines，seq 单调，256KB 截断重置）→ 引擎轮询协程（60ms，读端心跳保活）分发，首个监听者注册时自动开启转发。

```lua
local id = event.on(name, cb)      -- 任意事件名多播注册，返回监听 id
event.off(id)                      -- 移除（传事件名则按名清空）
event.removeListener(id) / event.removeListeners([name])
event._emit(name, ...)             -- 触发（回调异常隔离，返回送达数）

event.onStopEvent(cb)              -- 脚本停止（引擎 OnStop 桥，多播；setStopCallBack 单播并存）
event.onToast(cb)                  -- cb(pkg, className, text)
event.onNotification(cb)           -- cb(pkg, className, text)
event.onTouch(cb) / event.onClick(cb)
event.onKey(cb)                    -- cb(keyCode, action)：音量键监听（服务端智能拦截：
                                   --  仅 VOLUME_UP/DOWN/MUTE；有脚本消费心跳时拦截，静默 3 秒自动放行）
event.on("hotfix_finish", cb)      -- cb(ok[, info])；等价 project.setHotFixCallback(cb)
event.onVoiceDown/onVoiceUp/onGesture(cb)  -- 事件源缺失，注册返回 false
event.getPermissionStatus()        -- {root, accessibility, overlay, touch}
event.hasTouchPermission()         -- bool
```

### 19.13 project 工程与 Fire 热更（全局 project 表；纯 Lua 实现）

热更目录 `项目根/hotfix/`：pending（待应用）/ backup（回滚快照）。

```lua
project.getProjectDir() / project.getHotfixDir()
project.hotfixFire([dir])          -- 应用 pending（或指定目录）覆盖到项目根，被覆盖文件先备份 → true, n
project.hotfixFireFromUrl(url)     -- 下载 zip → 解压 pending → 应用
project.hotfixFireFromDir(dir)     -- 目录复制为 pending → 应用
project.hasPendingHotfix()         -- pending 非空
project.rollback()                 -- backup 恢复到项目根
project.clearCustomFire()          -- 清 pending + backup
project.setHotFixCallback(cb)      -- = event.on("hotfix_finish", cb)
```

### 19.14 cv.Mat 图像加工（全局 cv 表；零依赖纯 C++ OpenCV 子集）

Mat = rows/cols/ch(1/3/4) 连续 8U 缓冲，userdata + metatable（GC 自动 release，亦可显式）。

```lua
-- 创建
cv.createMat(rows, cols[, ch=1])                       -- 零填充
cv.createMatFromData(rows, cols, dataStr[, ch=1])
cv.createGrayMatFromRgba(rows, cols, rgbaData)
cv.captureMat(x, y, w, h)                              -- 整帧 RGBA 取区域
cv.matToData(m) → dataStr, rows, cols, ch
cv.matToImage(m) / images.getMat(img) / images.getGrayMat(img) / images.toJavaMat(img)

-- Mat 对象
m.rows m.cols m.channels m.type("CV_8UC1") m.isEmpty m.size
m:get(r, c) → v1[,v2,v3,v4]    m:put(r, c, v...)
m:getData() m:clone() m:submat(row, col, h, w) m:setTo(v[, ch]) m:release()

-- 颜色空间（cv.COLOR_RGBA2GRAY 等 12 常量）
cv.cvtColor(m, code) cv.toGray(m)
-- 二值/滤波
cv.threshold(m, th, maxval[, inv]) cv.adaptiveThreshold(m[, blk[, c]])
cv.blur(m, k) cv.gaussianBlur(m[, k=3|5]) cv.medianBlur(m, k)
cv.dilate/erode(m[, k[, iters]]) cv.morphologyOpen/Close(m[, k[, iters]])
-- 算术/位运算
cv.add(a, b[, wa]) cv.subtract(a, b) cv.addWeighted(a, wa, b, wb, gamma) cv.alphaBlend(a, b, ratio)
cv.bitwiseNot(m) cv.bitwiseAnd/Or/Xor(a, b)
cv.inRange(m, loR,loG,loB, hiR,hiG,hiB) cv.colorBinary(m, {r,g,b}[, dist])
cv.equalizeHist(m) cv.convertScaleAbs(m, alpha[, beta])
-- 几何
cv.resize(m, rows, cols) cv.resizeRatio(m, ratio) cv.flip(m, mode)
cv.rotate(m, angle) cv.rotateArbitrary(m, angleDeg[, bg]) cv.cropImage(m, x, y, w, h) cv.cropBlackBorder(m[, th])
-- 连通域/统计/绘制
cv.extractContours(m[, minArea]) → {{x,y,w,h,area},...}
cv.despeckle(m, minArea) cv.keepComponentsBySize(m, minArea)
cv.minMaxLoc(m) → min,max,minX,minY,maxX,maxY  cv.mean(m)  cv.sum(m)  cv.countNonZero(m)
cv.split(m) cv.merge({m1,m2,m3})
cv.drawRect/drawLine/drawCircle(m, ..., {r,g,b})
-- 资源管理
cv.isAvailable() cv.getVersion() cv.getMatStats() cv.forceGc() cv.encodePng(m)
```
> 不对齐：warpAffine/warpPerspective/putText（无字体引擎）、.cv 特征文件、GPU。OCR/YOLO 走插件。

---

## 20. 虚拟屏（virtualDisplay）

> 状态：**已实现（Shizuku 路径，虚拟屏任务书 §4.3/§4.4/§5 首批）**；root 直建路径（引擎 Display 直挂虚拟屏）随任务书 §6.1 未落地——当前 `create` 只走 Shizuku UserService 链路，无 Shizuku 时抛可读错误（引导授权）。三环境（root 主引擎 / IDE 沙箱 / 本地降级引擎）均注册 `virtualDisplay` 全局名（01 §8 铁律），实现与对码见 features/luaapi/contracts.md §① virtualDisplay 小节。

多实例虚拟屏体系：把第三方 App 跑进引擎自建的虚拟屏，面向多开挂机。API 对象化——每个虚拟屏是一个 `vd` 对象，多开天然成立；displayId 绑定进 vd 对象，注入与取帧由引擎内部路由到各自的屏，**脚本坐标即虚拟屏自身坐标**，作者无感知。

技术底座（已随任务书 §4 首批落地，Shizuku 路径）：
- **建屏双分支（对脚本透明）**：Android ≤14 反射 `SurfaceControl.createDisplay`；15+ 反射隐藏类 `DisplayControl.createVirtualDisplay` + `SurfaceControl.Transaction`（scrcpy 2.4+ 同款）。无 root 走 Shizuku（shell uid）UserService。
- **拉起**：shell 身份 `am start --display <id>`（activity 缺省服务端自动 resolve 入口）。
- **注入**：displayId 定向 AIDL（MotionEvent.obtain 组装 + 反射 `InputEvent.setDisplayId`，方法缺失时降级主屏注入并返回错误码 3）；无障碍通道不做分屏。
- **取帧**：该屏 ImageReader(RGBA_8888) → 服务端紧凑 RGBA 转换 → 每屏 A/B MemoryFile ping-pong 写（9 字节自描述头 + 跨进程 AtomicLong counter，协议与主屏共享内存管线同构）→ 客户端 mmap + AIDL counter 轮询 seqlock 读。

### 20.1 virtualDisplay 全局表

| API | 说明 |
|---|---|
| virtualDisplay.create{width=, height=, dpi=} | 创建虚拟屏，返回 vd 对象（多实例并存）。width/height 必填，dpi 缺省 320。无 Shizuku 时**抛可读错误**（引导启动并授权 Shizuku；root 直建路径未落地随任务书 §6.1），不静默、不返回假对象 |
| virtualDisplay.list() | 当前全部实例数组 {vd,...}（可遍历取 size/displayId） |

### 20.2 vd 对象方法

| API | 说明 |
|---|---|
| vd:runApp(pkg[, activity]) | 在该屏拉起应用（shell `am start --display`；activity 缺省走包默认入口）。失败抛错（code=1 参数/入口解析失败、2 执行失败、3 am 报告错误） |
| vd:tap(x, y) | 单击（注入带 displayId；系统拒绝时抛错 code=4） |
| vd:swipe(x1, y1, x2, y2[, durationMs]) | 滑动，durationMs 缺省 300ms |
| vd:longTap(x, y[, ms]) | 长按，默认 1000ms |
| vd:gesture(...) | **暂缺**（复杂手势定向注入未随首批落地，后续批次补；全局 gesture 与无障碍通道均不作用于虚拟屏，见 §20.4） |
| vd:findColor(x1, y1, x2, y2, color, sim[, order]) | 区域查找单色，帧源 = 该屏（签名同 [§2.2](#22-取色找色自动刷帧) 全局同名函数，下同） |
| vd:findColorAllPoint(x1, y1, x2, y2, color, sim[, order]) | 区域全部单色匹配位置，帧源 = 该屏。**由 images+bmp 组合实现**（Bitmap 无 findColorAllPoint）：窗口推进扫描，收集序固定行优先、内部窗口查找强制 order=1，order 参数仅透传单窗查找方向 |
| vd:findFeatureAll(x1, y1, x2, y2, featureStr, sim[, order]) | 全部特征匹配原点，帧源 = 该屏。**由 images+bmp 组合实现**（同上扫描语义） |
| vd:findImage(img, sim[, order]) | 模板找图，帧源 = 该屏（全屏区域形态；img 为路径串，竖线分隔多路径轮询，同全局；返回 x, y, 序号） |
| vd:findImageAll(x1, y1, x2, y2, imgNames, sim[, order]) | 模板全部匹配位置，帧源 = 该屏。**由 images+bmp 组合实现**（同上扫描语义） |
| vd:getScreenFrame() | 原始帧 → data, w, h, stride, format（同全局 getScreenFrame；stride=w*4，format "RGBA"） |
| vd:getScreenPixel(x1, y1, x2, y2) | 区域像素二维数组（每行一表，元素 0xRRGGBB） |
| vd:saveScreen(path[, fmt]) | 整屏保存（仅 png，同 [§2.1](#21-屏幕控制) 全局约束；fmt="jpeg" 返回 false + 错误信息） |
| vd:getSize() | → w, h |
| vd:getDisplayId() | → displayId |
| vd:destroy() | 销毁虚拟屏并释放帧缓冲；destroy 后再调用图色/注入方法抛错 |

组合层口径（首批实现结论）：图色方法 = `vd:__frame()` 原始帧字节 → `images.fromData(w,h,data)` → Bitmap 同名方法（Bitmap 方法集以 3rd\autolua_vision 实际注册为准：getColor/getColorCount/isColor/whichColor/findColor/isFeature/findFeature/isImage/whichImage/findImage/save/clone/getSize）。帧为紧凑 RGBA、R 在 [0]，与全局 findColor 的目标色字节序适配同口径（VisionBridge 已验证语义）。

### 20.3 运行模式矩阵

| 运行环境 | virtualDisplay.create | 说明 |
|---|---|---|
| 无 root 且无 Shizuku | ❌ 抛可读错误 | 引导启动并授权 Shizuku，不静默、不降级 |
| 无 root + Shizuku（shell uid） | ✅ | 建屏 / 拉起 / 注入 / 取帧全链路（任务书 §4 首批已落地） |
| root | ✅（经 Shizuku） | root 直建路径（DisplayImplement 直挂虚拟屏）随任务书 §6.1 未落地；root 环境下 Shizuku 已授权时同样可用 |

**三环境适用说明**：`virtualDisplay` 全局名在 root 主引擎（Server.kt initEngine）/ IDE 沙箱（MainApp ideExecutor.registerService）/ 本地降级引擎（buildLocalSandboxEngine）三处同款注册（01 §8 铁律），脚本在三环境行为一致；建屏能力只取决于 Shizuku 授权状态，与运行环境无关。

主屏与虚拟屏互不影响：[§2 图色](#2-图色) / [§3 触控](#3-触控) 全局函数仍作用于默认屏（displayId=0）；每个 vd 的帧源与注入只路由到各自 displayId，≥2 个虚拟屏并行互不串扰。

### 20.4 限制声明（脚本作者必读）

1. **虚拟屏内无无障碍节点**：无障碍服务只挂默认屏，虚拟屏内 App 不产生节点——`UiSelector` / `getRootInActiveWindow`（[§4](#4-节点与无障碍)）在虚拟屏场景不可用。
2. **虚拟屏内无无障碍手势**：dispatchGesture 只作用于默认屏，虚拟屏内触控只能走 vd:tap/swipe/longTap（注入通道带 displayId）；vd:gesture 复杂手势暂缺（首批未落地）。
3. **感知仅图色 + OCR**：控件/文字感知用 vd:getScreenFrame / vd:getScreenPixel / vd:findImage 系 + `ocr.*`（含 ncnn-ocr 插件通道，见 §19.9；`ocr.recognizeRaw(frame,fw,fh,stride,...)` 直接吃 vd:getScreenFrame 输出）；无点阵字库。
4. **坐标系 = 虚拟屏自身坐标**：vd 对象绑定 displayId，脚本坐标无需换算，引擎内部完成 → 事件 displayId 映射；主屏缩放适配 setScreenScale（§2.5）不影响虚拟屏坐标。
5. **Android 15+ 双分支对脚本透明**：≤14 走 SurfaceControl.createDisplay、15+ 走 DisplayControl.createVirtualDisplay + SurfaceControl.Transaction，脚本无感知差异；15+ 帧格式强制 RGBA_8888（RGB_565 被拒）。

### 20.5 示例

```lua
-- 多开示例：Shizuku 已授权环境下两个虚拟屏并行挂机（无 Shizuku 时 create 抛错）
local vds = {}
for i = 1, 2 do
  local vd = virtualDisplay.create{ width = 720, height = 1280, dpi = 320 }
  vd:runApp("com.xxx.game", "com.xxx.game.MainActivity")
  table.insert(vds, vd)
end

while true do
  for i, vd in ipairs(vds) do
    local x, y = vd:findImage("btn_start.png", 0.9)
    if x > 0 then
      vd:tap(x, y)
    else
      -- 找不到目标：帧导出 + OCR 兜底感知（虚拟屏内唯一文字手段）
      local data, w, h, stride, fmt = vd:getScreenFrame()
      local blocks = ocr.recognizeRaw(data, w, h, stride, 0, 0, w, h)
      for _, b in ipairs(blocks or {}) do print(i, b.text) end
    end
  end
  sleep(1000)
end

for _, vd in ipairs(virtualDisplay.list()) do vd:destroy() end
```

```lua
-- 错误路径：无 Shizuku 时 create 抛可读错误（不静默）
local ok, err = pcall(virtualDisplay.create, { width = 720, height = 1280 })
if not ok then
  print(err)  -- virtualDisplay.create: no Shizuku service (please start Shizuku and
              --   authorize this app, or run engine as root; root direct-create path
              --   is not implemented yet - see plan §6.1)
end
```

---

## 附：与商业引擎的命名对照（迁移速查）

| 懒人精灵 | 本引擎 | 说明 |
|---|---|---|
| jsonLib.encode/decode | json.encode/decode（jsonLib 别名已注册） | 直接替换 |
| cryptLib.aes_crypt | crypt.aesCrypt | 签名同构 |
| http.request | http.request | 同名 |
| findMultiColorEx | findMultiColor（compat 同名封装） | 直接用 |
| findPic/findPicEx/findPicFast | findImage（compat 同名别名已注册） | 路径参数兼容 |
| uiNode 全局工厂（text()/id()...） | compat 全局工厂同名 | 直接用 |
| device.sleep | sleep | 派大星同名不同义陷阱：本引擎统一 sleep |
| setTimer / rnd / systemTime / tickCount | 同名（compat） | 直接用 |
| runApp/stopApp/appIsFront/exec | 同名（AppLib） | 直接用 |
| fileExist/mkdir/delfile/readFile/writeFile/listDir | 同名（FileLib） | 直接用 |
| imeLib.* 全套输入法 | ime.*（imeLib 别名已注册） | 直接替换 |
| getUIxxx 动态 UI | 不对齐（走 MLN） | 需改造 |
| ocr.createOcr 等图色 OCR | 不对齐（走插件） | 见 04-插件包规范.md |
| virtualDisplay.createVirtualDisplay / runAppWithVirtualDisplay / tap / swipe / findColor... | virtualDisplay.create{...} / vd:runApp / vd:tap / vd:swipe / vd:findColor... | 对象化多开（vd 绑定各自 displayId）；规范见 [§20](#20-虚拟屏virtualdisplay)，规划中 |

