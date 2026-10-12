# 动态 UI 设计

> 文档版本 v1.0（2026-09-18，文档三层化重组新建）
> 本域权威范围：动态 UI 体系的设计决策与机制原理——双进程分工、事件管线、控件工厂、分页与弹窗取舍、代码映射与演进记录；接口契约见 [contracts.md](contracts.md)。

## 架构总述

动态 UI 采用**双进程分工**：View 实例只存在于 app 主进程，Lua 只持有跨进程代理。

```
root 进程 Lua（脚本）                     app 主进程（悬浮窗权限所在进程）
──────────────────────                    ──────────────────────────────────
ui.createWindow(cfg, tree)                UiMgrImp（单例，op 分发/事件队列）
  │ json.encode(cmd)                        ├─ mainHandler.post → 主线程落点
  ▼                                        ├─ JsonViewFactory.buildNode（JSON→View 树）
uimgr 代理（root Lua 全局，String 收发）    ├─ FloatWin（WindowManager overlay 悬浮窗）
  │ s:op(json) ─────────────────────────►  ├─ DialogHost（弹窗 scrim 窗）
  │ ◄───────────── 响应 {"ok","data"}      └─ wins/dialogs 注册表 + events 队列
ui.bind(fn) → _cbs 注册表                          │ listener 常挂 emit 入队
_dispatchLoop（50ms 轮询协程）                      ▼
  │ s:pollEvents() ◄──────────────────  pollEvents() 排空 → JSON 数组
  ▼
_route → thread.start 每事件独立协程 → fn(ev)
```

分工原因：悬浮窗权限（SYSTEM_ALERT_WINDOW）授予的是 app 包，且 View/WindowManager 操作必须稳定落在有主 Looper 的进程；root 引擎进程（uid 0 app_process）没有 Android UI 线程环境。故 UI 实例固定 app 主进程、Lua 经 `addLocalService("uimgr",...)` 注入的远程代理调用（`autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:118`、`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\IUiMgr.kt:8`）。

跨进程面收敛为两个 String 方法（op/pollEvents），服务接口仅此两员，root 进程 Proxy 重建要求第三参必须传 interface（`autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:115`、`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\IUiMgr.kt:14`）。通道底层机制（stdin/stdout 帧协议、本地服务注册回放）归 [../ipc/contracts.md](../ipc/contracts.md) ③。

Lua 层（uilib）以 `LuaEnvContributor` 内嵌 Lua 源码注入全局 `ui`/`console` 表，而非 Kotlin 桥接——事件路由、等待者管理等纯 Lua 逻辑可直接改，且 root/沙箱两环境共用同一份实现（`autolua2\engine\src\main\java\com\autolua\engine\extension\ui\UiLib.kt:22`、`UiLib.kt:24`；注册点见 [../luaapi/contracts.md](../luaapi/contracts.md)）。

## 事件管线

四段管线：listener 常挂 → 队列 → 轮询 → 路由。

1. **listener 常挂**：`attachAllEvents` 在 build 时对每个控件挂载全部适用 listener（click/longClick/touch 恒挂；check/select/progressChange/textChange/editorAction/urlLoad/pageDone 按控件类型挂），不问 Lua 是否绑定——事件触发时若无绑定者，代价只是组帧+入队+丢弃，换来"build 后随时 bind 即生效、无需重建 View"的简单模型（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\JsonViewFactory.kt:644`）。
2. **队列**：listener 回调统一走 `emitOf(win)` 组帧，`pushEvent` 入 `ConcurrentLinkedQueue`，容量 256 超限丢最旧——入队发生在 app 主线程（listener 回调），出队发生在跨进程调用线程，用并发队列解耦且不回压主线程（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\UiMgrImp.kt:65`、`UiMgrImp.kt:75`）。
3. **轮询**：uilib `_dispatchLoop` 50ms 一次 `pollEvents()` 排空（String JSON 数组），选择拉模式而非推送，是因为 root→app 方向已有 op 通道，app→root 推送需要第二条反向通道，而 UI 事件 50ms 粒度对人类交互足够（`autolua2\engine\src\main\java\com\autolua\engine\extension\ui\UiLib.kt:100`、`UiLib.kt:122`）。
4. **路由**：`_route` 按 `ev.ev` 分流——`ui` 事件以 `win\1id\1event` 三元组查 `_cbs`，`dialog` 事件以 `tostring(req)` 查 `_waiters`（唤醒同步等待）/`_dlgCb`（异步回调）；每事件 `thread.start` 独立协程，回调可阻塞可再入 UI 操作（`autolua2\engine\src\main\java\com\autolua\engine\extension\ui\UiLib.kt:67`、`UiLib.kt:88`）。

与宿主事件总线的对照：无障碍宿主事件（toast/notification）走 EventBridge 镜像文件 + EventLib 60ms 轮询（[../../01-架构总览.md](../../01-架构总览.md) §9.6），与本域 50ms pollEvents 同为"宿主产生、Lua 轮询消费"模型，但通道完全独立——前者跨进程走文件，后者走 uimgr 服务直调。

退出设计见 [contracts.md](contracts.md) ⑥（空闲 3s 自动停，防 worker 永不回 idle）。

## 控件工厂机制

- **单工厂全量控件**：`JsonViewFactory` 一个 object 承担建 View（createView/buildNode）、通用属性（applyCommon）、类型属性（applyAttrs）、事件挂载（attachAllEvents）、值收集（collectValues）五职责；build 与 node.set 共用 applyCommon/applyAttrs，保证"创建时能设的属性、运行时都能改"（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\JsonViewFactory.kt:347`、`JsonViewFactory.kt:422`）。
- **ContextThemeWrapper**：服务 context 无 AppCompat 主题，Material 组件（MaterialCardView/TabLayout）、SwitchCompat 直接用会崩；`UiMgrImp.init` 将 applicationContext 包一层 `ContextThemeWrapper(..., Theme_AppCompat_Light_NoActionBar)` 得 `uiContext`，所有控件构建与弹窗均用该 context（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\UiMgrImp.kt:56`、`UiMgrImp.kt:58`）。
- **collectValues 子类优先**：值收集的 when(v) 分支顺序是行为契约——EditText/CompoundButton/RatingBar 是 TextView/ProgressBar 的子类，Kotlin when 按书写顺序命中，子类分支必须在前（源码 :727 注释固化）；改动此函数时禁止把父类分支上移（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\JsonViewFactory.kt:727`）。
- **相对布局 id 占位**：RelativeLayout 的 below/above/leftOf/rightOf 需要 view id，但 JSON 控件 id 是字符串；设计用 `-100 - (id.hashCode() % 100000)` 生成稳定负数占位 id，addChild 后 `resolveRelativeIds` 回填真实 view id（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\JsonViewFactory.kt:272`、`JsonViewFactory.kt:310`）。
- **节点树寻址**：`UiNode` 保存 type/view/id/parent/children，`findById` 递归寻址支撑 node.set/get/remove 与 tree.get；节点删除级联销毁 WebView/VideoView 防 webcore 泄漏（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\JsonViewFactory.kt:34`、`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\UiMgrImp.kt:244`）。
- **list 复用工厂**：ListAdapter 每个条目用 itemXml 模板（`{{字段}}` 文本替换）递归调 buildNode 生成，与主树共用全部控件能力；scroll 容器只收一个直接子节点（多插自动清空）（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\JsonViewFactory.kt:804`、`JsonViewFactory.kt:297`）。
- **图片加载**：base64/data URI 同步解码；本地文件/URL 起子线程下载后主线程 setImageBitmap，失败静默（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\JsonViewFactory.kt:608`）。

## 分页与弹窗

- **SimplePagerView 自研**（弃 ViewPager2）：ViewPager2 的 onLayout 强校验每页尺寸必须 match_parent（"Pages must fill the whole ViewPager2"），动态 JSON 构建场景（页面内容异步重建、wrap 尺寸）极易抛 IllegalStateException **崩掉 app 主进程**；自研方案为 FrameLayout 单页切换——`pages` 存 JSONObject 描述、切页时重新 buildNode，内存换稳定性，页数有限的设置面板场景可接受（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\JsonViewFactory.kt:850`、`JsonViewFactory.kt:354`）。
- **TabLayout 联动**：pager 带 titles 时外包垂直 LinearLayout（TabLayout + pager weight=1）；页签选中 → `setCurrentItemCompat`，程序化切换（currentItem 属性/node.set）反向 `tabs?.getTabAt(pos)?.select()`，以 `lastChangeFromUser` 区分来源防回环（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\JsonViewFactory.kt:353`、`JsonViewFactory.kt:399`、`JsonViewFactory.kt:870`）。
- **弹窗不依赖 Activity Dialog**：服务上下文（无窗口 token 的 Activity）创建 AlertDialog 不可靠；DialogHost 用 WindowManager 直接挂"全屏 scrim + 面板"两个 overlay 层级实现，scrim 拦截全部触摸、面板可聚焦（软键盘）、requestId 闭环回传结果，可叠栈（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\DialogHost.kt:24`、`DialogHost.kt:66`）。
- **拖动与点击互斥**：DragFrameLayout 以 touchSlop（约 12px 量级系统值）区分，拖动启用时 MOVE 超阈值即拦截；滚动/输入类子树（EditText/ScrollView/RecyclerView/SimplePagerView）整条跳过拖动逻辑，避免"想滚窗却拖走窗"（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\UiMgrImp.kt:434`、`UiMgrImp.kt:447`）。

## 代码映射表

| 机制 | 文件与锚点 |
|---|---|
| op 分发/主线程落点/8s 超时 | `autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\UiMgrImp.kt:92`、`:128` |
| 事件队列（cap 256）/pollEvents | `UiMgrImp.kt:42`、`:75`、`:80` |
| FloatWin 悬浮窗（flags/fullscreen/drag） | `UiMgrImp.kt:314`（init :329、create :363、moveTo :390） |
| DragFrameLayout 拖动互斥 | `UiMgrImp.kt:434`（skippable :447、intercept :465） |
| 弹窗宿主（scrim/fields/buttons/sheet/custom） | `autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\DialogHost.kt:66`（dismiss :260、collectNode :278） |
| 控件工厂（createView/applyAttrs/attachAllEvents/collectValues） | `autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\JsonViewFactory.kt:319`、`:422`、`:644`、`:714` |
| SimplePagerView + TabLayout 联动 | `JsonViewFactory.kt:850`、`:353` |
| ListAdapter（itemXml 模板） | `JsonViewFactory.kt:804` |
| CanvasView 自绘指令 | `JsonViewFactory.kt:893` |
| uilib 事件路由/轮询/退出（Lua 内嵌） | `autolua2\engine\src\main\java\com\autolua\engine\extension\ui\UiLib.kt:67`、`:100`、`:115` |
| ui 表 API（win/node/bind/dialog/console） | `UiLib.kt:145`-`:374` |
| 服务装配（MainApp） | `autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:92`（init）、`:118`（uimgr 注册） |
| 宿主旧服务（toast/信号/MLN 悬浮视图） | `autolua2\app\src\main\java\com\autolua\autolua2\extension\imp\UserInterfaceImp.kt:54`（toast）、`:92`（signal）；`FloatViewImp.kt:37` |
| 悬浮控制条 | `autolua2\app\src\main\java\com\autolua\autolua2\view\imp\FloatControllerImp.kt:46`（init/drag）、`:164`（updateState） |

## 演进

- **ViewPager2 弃用记录**：动态 UI 初版曾接 ViewPager2，因 "Pages must fill the whole ViewPager2" 强校验在动态构建下崩溃 app 主进程而弃用，换 SimplePagerView（源码注释保留原因，防后人重引）（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\JsonViewFactory.kt:354`）。
- **MLN 保留范围收敛**：MLN（LuaView）曾定位为完整 UI 体系（UI.createView 悬浮视图 + 热重载），现收敛为两职责——① `ui_debug` 调试热重载预览（DebugActivity + hotreload.lua，见 [client.md](client.md)）；② 旧 `UI` 服务兼容（toast/信号/FloatView）。生产动态 UI 一律走 `ui` 表；viewWrapper 级 View 直操不对齐（[../../06-开发铁律与踩坑手册.md](../../06-开发铁律与踩坑手册.md) §4、[../../02-Lua-API规范.md](../../02-Lua-API规范.md) §17）。
- **事件通道演进**：老 8176 调试通道退役后，ui_debug 启停改经 IdeServerManager 的 UiDebugDelegate（`autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt:230`）；pollEvents JSON 通道契约稳定未变（[../ipc/contracts.md](../ipc/contracts.md) ④）。
- **待决事项**：execute 偶发挂死（桥接层既有问题）仍会偶发阻塞 UI 全链路集成测试，归 [../../06-开发铁律与踩坑手册.md](../../06-开发铁律与踩坑手册.md) §4 跟踪，与本域 dispatcher 退出坑（contracts ⑥）区分排障。
