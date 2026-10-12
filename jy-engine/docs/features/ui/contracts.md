# 动态 UI 契约

> 文档版本 v1.0（2026-09-18，文档三层化重组新建）
> 本域权威范围：动态 UI 体系（Lua `ui` 表）在引擎/宿主两侧的实现契约——op 协议、控件面、事件 JSON、轮询协议、回调语义、dispatcher 退出、窗口/弹窗协议；脚本作者视角的 API 面以 [../../02-Lua-API规范.md](../../02-Lua-API规范.md) §17.1 为权威。

## ① op 协议（uimgr:op(json)）

- 接口 `IUiMgr` 仅有两个方法 `op(json: String): String` 与 `pollEvents(): String`，参数与返回值**一律为 String**——root 进程 Lua 经跨进程代理调用时规避 Parcel 对复杂类型的序列化限制，JSON 是唯一载荷格式（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\IUiMgr.kt:11`、`IUiMgr.kt:14`）。
- 请求帧 `{"op":"<名称>", "req":<任意透传 可缺省>, ...参数}`；`op` 缺失直接返回错误帧（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\UiMgrImp.kt:96`、`UiMgrImp.kt:98`）。
- 响应帧 `{"req":<原样带回 可缺省>,"ok":bool,"data":{...} 成功时,"err":"..." 失败时}`（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\UiMgrImp.kt:119`）。
- op **同步执行**：提交到 app 主线程 Handler 落点执行，调用线程 `CountDownLatch` 等待，超时 8 秒报 `ui op timeout`——op 内不允许任何阻塞等待（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\UiMgrImp.kt:102`、`UiMgrImp.kt:112`）。
- Lua 侧封装 `_call(cmd)`：`s:op(json.encode(cmd))` → `json.decode` → 校验 `r.ok`，失败返回 `nil, err`（`autolua2\engine\src\main\java\com\autolua\engine\extension\ui\UiLib.kt:56`）。
- op 类型全集（`dispatch` 的 when 分支，未知名抛 `unknown op`）：`win.create / win.close / win.show / win.hide / win.move / win.setSize / win.setAlpha / win.list / node.add / node.set / node.remove / node.get / tree.get / dialog.show / dialog.close / toast`（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\UiMgrImp.kt:128`、`UiMgrImp.kt:150`）。
- `win.create` 帧结构：`{op, win?, cfg:{win?,x,y,w,h,drag,focus,alpha,fullscreen,gravity,notTouchable,autoShow}, root?:<控件树>}`，win 缺省自动分配 `w<n>` 递增 id，同名旧窗先关再建，cfg.autoShow 缺省 true（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\UiMgrImp.kt:156`、`UiMgrImp.kt:159`、`UiMgrImp.kt:169`）。
- `node.*` 帧结构：`{op, win, id?, parent?, node?, attrs?, index?}`；节点寻址先查窗口注册表 `wins` 再查弹窗注册表 `dialogs`（弹窗 win=`__dlg_<req>` 同样可 node.set/get 寻址）（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\UiMgrImp.kt:179`、`UiMgrImp.kt:185`、`UiMgrImp.kt:202`）。
- `toast` 帧：`{op:"toast", msg, dur}`，dur=1 长时其余短时（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\UiMgrImp.kt:145`）。

## ② 控件 type 全集与通用属性

**与 02 §17.1 的对账关系**：本节是工厂实现的完备清单（createView 分支全集），[../../02-Lua-API规范.md](../../02-Lua-API规范.md) §17.1 是脚本作者视角的等价表述；两侧以 `createView` 的 when 分支与 `JsonViewFactory` 文件头注释为准对账，新增控件 type 必须同步三处：createView 分支、applyAttrs 分支、02 §17.1（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\JsonViewFactory.kt:319`、`JsonViewFactory.kt:62`）。

- 控件 type 全集：布局容器 `linear`(默认纵向) `frame` `relative` `scroll` `scrollH` `grid` `card`(MaterialCardView) `pager`(SimplePagerView)；基础 `text` `button` `edit` `checkbox` `switch`(SwitchCompat) `radio`(RadioGroup) `spinner` `seek`(SeekBar) `rating`(RatingBar) `progress`(水平 ProgressBar)；媒体 `image` `webview` `video` `list`(RecyclerView) `canvas`(自绘)；**未知 type 回退为 LinearLayout** 不报错（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\JsonViewFactory.kt:319`、`JsonViewFactory.kt:343`）。
- 通用属性（applyCommon，build 与 node.set 共用）：`tag enabled alpha clickable rotation elevation bg visibility`(visible/invisible/gone) `padding/pad/padTop/...`；w/h/margin 在 childParams 层处理（linear 支持 layout_gravity/weight，relative 支持 alignParent/below/above/leftOf/rightOf，grid 支持 row/column/rowSpan/columnSpan）（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\JsonViewFactory.kt:194`、`JsonViewFactory.kt:217`、`JsonViewFactory.kt:272`）。
- 尺寸语法：`-1/"match"/"fill"/"match_parent"`=MATCH_PARENT、`-2/"wrap"/"wrap_content"`=WRAP_CONTENT、数字=px、`"50%"`=屏幕宽/高百分比（horizontal 定向）、`"dp:12"`/`"sp:14"` 带单位（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\JsonViewFactory.kt:103`）。
- bg 语法：字符串/数字按色值纯色填充；对象 `{color, shape:rect/oval/line, corner(数字或4角数组), stroke:{color,width}}` 走 GradientDrawable（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\JsonViewFactory.kt:121`）。
- 类型专属属性走 applyAttrs 的 when(n.type) 分支（build 与 node.set 同一入口）：`text/button`(text/textSize/textColor/bold/maxLines/ellipsize/gravity/singleLine)、`edit`(hint/inputType:number|phone|pass|multiline/lines)、`checkbox/switch`(text/checked)、`radio`(items/checked 按 index)、`spinner`(items/selection)、`seek`/`progress`(max/progress)、`rating`(numStars/rating/stepSize)、`image`(src: 本地路径|URL|`base64:`|`data:image`)、`webview`(url/html/localFile)、`video`(path/start/pause/stop/seekTo)、`card`(cardRadius/cardElevation)、`pager`(currentItem/items)、`list`(items/itemXml `{{字段}}` 模板替换)、`canvas`(ops 指令数组)（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\JsonViewFactory.kt:422`、`JsonViewFactory.kt:593`）。
- **collectValues 分支顺序契约**：值收集 when(v) 必须把子类分支放在父类之前——`EditText`(TextView 派生)、`CompoundButton`(checkbox/switch，TextView 派生)、`RatingBar`(ProgressBar 派生) 命中专属分支后不可再落到 `TextView`/`ProgressBar` 通用分支，否则 text/checked/rating 值丢失或重复；源码以注释固化此约束（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\JsonViewFactory.kt:714`、`JsonViewFactory.kt:727`）。
- `ui.get` 返回值表由 collectValues 组装：公共字段 type/id/visibility/enabled/alpha/x/y/w/h + 类型专属字段（text/checked/selectedIndex/progress/url/currentItem 等）（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\JsonViewFactory.kt:728`、`JsonViewFactory.kt:757`）。

## ③ 事件 JSON 结构（win/id/event 三元组）

- 控件事件帧：`{"ev":"ui","win":<winId>,"id":<控件id 可缺省>,"event":<事件名>,"data":<负载 JSONObject 可缺省>}`，由窗口级 `emitOf(win)` 闭包统一组帧（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\UiMgrImp.kt:65`）。
- 弹窗结果帧：`{"ev":"dialog","req":<n>,"action":"ok|cancel|select|b<n>|...","values":{...},"index":<n 仅 sheet 携带>}`（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\UiMgrImp.kt:282`）。
- 事件名全集（Java 侧 listener 全量常挂，见 design.md 事件管线）：`click longClick touch`(data={action,x,y}) `check`(checkbox/switch 带 checked；radio 带 index) `select`(spinner) `progressChange`(seek/rating) `textChange` `editorAction` `itemClick` `itemLongClick` `scrollEnd`(list 滚到底) `itemSelect`(pager 换页) `urlLoad` `pageDone`(webview)（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\JsonViewFactory.kt:644`、`JsonViewFactory.kt:580`、`JsonViewFactory.kt:399`）。
- AdapterView（Spinner 等）禁止 OnClickListener，click/longClick 只对非 AdapterView 挂载；touch listener 恒返回 false 不消费事件（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\JsonViewFactory.kt:648`、`JsonViewFactory.kt:654`）。
- **touch 默认不上报（2026-09-23，事件洪流治理）**：touch 是唯一高频事件源（一次 tap 产生 DOWN+MOVE×N+UP，拖动/滑动时每秒几十条，占满队列会挤掉 click）。Java 侧 touch listener 检查节点 `extra["listen"]` 白名单，未注册不 emit。开启途径：①`ui.bind(win,id,"touch",fn)`（Lua 侧自动发 `node.listen` op 注册）；②树 spec 声明 `events:["touch",...]` 预置。其余事件低频，保持全量常挂（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\JsonViewFactory.kt:684`、`UiMgrImp.kt:222`）。
- `node.listen` op：`{"op":"node.listen","win":<winId>,"id":<控件id>,"events":[...]}` → 覆盖式登记节点关心的事件集合（`extra["listen"]`），节点不存在抛错（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\UiMgrImp.kt:224`）。
- 队列容量 **cap 256，超限丢最旧**：`MAX_EVENTS = 256`，pushEvent 入队前若 `events.size >= MAX_EVENTS` 先 poll 一条丢弃——事件风暴下不回压、不阻塞 UI 主线程，代价是低频消费时丢事件（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\UiMgrImp.kt:42`、`UiMgrImp.kt:75`）。
- Lua 侧路由键：`ui` 事件按 `win\1id\1event` 三元组拼接字符串匹配 `ui.bind` 注册表 `_cbs`；`dialog` 事件按 `tostring(req)` 匹配同步等待者 `_waiters` 或异步回调 `_dlgCb`（`autolua2\engine\src\main\java\com\autolua\engine\extension\ui\UiLib.kt:84`、`UiLib.kt:70`）。
- 未绑定的事件在路由层直接丢弃，不回压、不告警（`autolua2\engine\src\main\java\com\autolua\engine\extension\ui\UiLib.kt:16`、`UiLib.kt:87`）。

## ④ pollEvents 协议（50ms 轮询、String 数组）

- `pollEvents(): String` 一次性排空事件队列：空返回字面量 `"[]"`；非空逐条 poll 还原为 JSONObject 拼 JSONArray 返回（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\UiMgrImp.kt:80`）。
- 消费方**唯一**：uilib 的 `_dispatchLoop` 轮询协程，`thread.sleep(50)` 固定 50ms 间隔调用 `s:pollEvents()`，帧长 >1 才 json.decode，解析出的事件逐条 `_route` 分发（`autolua2\engine\src\main\java\com\autolua\engine\extension\ui\UiLib.kt:100`、`UiLib.kt:122`）。
- 轮询协程由 `ui._ensureDispatch()` 惰性启动（`_disp` 标志防重入），`ui.createWindow`/`ui.bind`/`ui.dialog` 均会触发（`autolua2\engine\src\main\java\com\autolua\engine\extension\ui\UiLib.kt:128`、`UiLib.kt:152`、`UiLib.kt:204`、`UiLib.kt:252`）。
- `uimgr` 服务不可用时轮询空转返回 `"[]"` 不崩溃（脚本在未注入环境跑 UI 代码由 `_svc()` 在调用期报错）（`autolua2\engine\src\main\java\com\autolua\engine\extension\ui\UiLib.kt:104`、`UiLib.kt:49`）。

## ⑤ bind 回调语义（每事件独立协程）

- `ui.bind(win, id, event, fn)` 注册 `fn(ev)`，ev 为完整事件帧 `{ev,win,id,event,data}`；返回路由 key（`autolua2\engine\src\main\java\com\autolua\engine\extension\ui\UiLib.kt:200`）。
- **每事件独立 thread 协程派发**：`_route` 对每个命中回调 `thread.start` 一个新协程 pcall 执行，回调内可阻塞（如 `ui.confirm` 同步弹窗等待）、可继续调用 `ui.add/set/remove` 等 UI 操作，多个事件互不饿死、互不阻塞（`autolua2\engine\src\main\java\com\autolua\engine\extension\ui\UiLib.kt:88`）。
- 回调内异常被 pcall 捕获并以 `ui callback error: ...` 打印，不上抛不杀轮询协程（`autolua2\engine\src\main\java\com\autolua\engine\extension\ui\UiLib.kt:91`）。
- 弹窗异步回调（`opts.onResult`）同样走独立协程；`_dlgCb` 触发后即注销（一次性）（`autolua2\engine\src\main\java\com\autolua\engine\extension\ui\UiLib.kt:77`）。
- `ui.unbind(win,id,event)` 仅删除注册表条目，不影响已派发中的协程（`autolua2\engine\src\main\java\com\autolua\engine\extension\ui\UiLib.kt:208`）。

## ⑥ dispatcher 退出契约（ui.shutdown() + 空闲 3s —— 重大坑）

- 轮询协程**不会永久常驻**：每轮（50ms）无事件、且 `_cbs` 与 `_waiters` 注册表均为空时，`_quiet` 累加 50；连续 3 秒（`_quiet >= 3000`）即 break 退出，`_disp` 复位 false——这是引擎 worker 能回到 idle 状态的前提（`autolua2\engine\src\main\java\com\autolua\engine\extension\ui\UiLib.kt:115`）。
- **重大坑**：只要存在任何残留 `ui.bind` 注册或未完成的弹窗等待者，dispatcher 就永不退出 → worker 永不回 idle → 后续 `execute` 永远排队不开始。脚本收尾必须调用 `ui.shutdown()`：清空三个注册表并置 `_stop=true` 令轮询协程在下一轮退出（`autolua2\engine\src\main\java\com\autolua\engine\extension\ui\UiLib.kt:137`、`UiLib.kt:141`）。
- `ui.shutdown()` 只停轮询，**不关窗口**（窗口/绑定表被清空但 app 侧 View 仍在）；需要视觉清理由脚本显式 `ui.close(win)`（`autolua2\engine\src\main\java\com\autolua\engine\extension\ui\UiLib.kt:136`）。
- 与 [../../06-开发铁律与踩坑手册.md](../../06-开发铁律与踩坑手册.md) 的关系：手册 §4"引擎 execute 偶发挂死"记录了 execute 不回 idle 的既有问题（归因于桥接层，与本 dispatcher 无关）；但"UI dispatcher 未退出导致 execute 永不开始"是**脚本侧可控**的独立成因，本契约为其唯一权威描述，排障时先查 `ui.shutdown()` 是否调用、再按手册流程重置环境。
- 每次重新 bind/弹窗都会自动重启轮询（`_ensureDispatch`），退出后无需手工恢复（`autolua2\engine\src\main\java\com\autolua\engine\extension\ui\UiLib.kt:128`）。

## ⑦ 窗口/弹窗协议

- 悬浮窗（FloatWin）：窗类型 O+ 用 `TYPE_APPLICATION_OVERLAY`、低版本 `TYPE_PHONE`，`PixelFormat.TRANSLUCENT`；cfg 开关映射 `focus=false → FLAG_NOT_FOCUSABLE`、`notTouchable → FLAG_NOT_TOUCHABLE`、`fullscreen → FLAG_LAYOUT_IN_SCREEN + MATCH_PARENT`、`drag → DragFrameLayout 拖动`、`gravity:"center"` 居中、`alpha` 窗透明度（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\UiMgrImp.kt:330`、`UiMgrImp.kt:348`、`UiMgrImp.kt:357`）。
- 窗口操作（win.show/hide/move/setSize/setAlpha）对未显示窗口只改 LayoutParams 缓存，show 时生效；moveTo 合并连续更新（movePending 防抖，主线程 updateViewLayout）（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\UiMgrImp.kt:390`、`UiMgrImp.kt:401`）。
- 拖动协议：`DragFrameLayout` 以 touchSlop 阈值互斥拖动与点击；子树含 EditText/ScrollView/HorizontalScrollView/RecyclerView/SimplePagerView 时该触摸不触发拖动（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\UiMgrImp.kt:447`、`UiMgrImp.kt:465`）。
- 弹窗协议：`dialog.show` 帧 `{op, req, dlg:{type,title,msg,buttons,fields,items,content,w,cancelable}}`；独立全屏 scrim overlay 窗 + 面板，**不依赖 Activity Dialog**（服务上下文不可靠）；弹窗注册名 `__dlg_<req>`，同 req 重复 show 先关旧窗；结果经 `onDialogResult` 回调入队（见 ③ dialog 帧）（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\UiMgrImp.kt:259`、`UiMgrImp.kt:262`）。
- 弹窗类型：`alert`（默认按钮"确定"/action=ok）、`confirm`（默认确定+取消）、`input`（fields 多字段输入，values 按字段 id 收集）、`sheet`（底部菜单，action=select + index，取消 index=-1 由 Lua 层补）、`custom`（content 控件树注册进 `__dlg_<req>`，可 node.set/get 寻址，关闭时按 id 收集 slim 值 text/checked/selectedIndex/selectedItem/progress/max/currentItem/url）（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\DialogHost.kt:24`、`DialogHost.kt:278`、`autolua2\engine\src\main\java\com\autolua\engine\extension\ui\UiLib.kt:311`）。
- 弹窗可聚焦（lp.flags=0）以支持输入框软键盘；scrim 点击取消受 `cancelable`（默认 true）控制；返回键同取消（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\DialogHost.kt:88`、`DialogHost.kt:243`）。
- 同步等待协议：`opts.wait=true` 时 Lua 在 `_waiters` 挂起，轮询 40ms 检查 `w.done`，`opts.timeout` 毫秒超时返回 `nil,"dialog timeout"`；**必须在 thread 协程内调用**（阻塞当前协程而非引擎 worker）（`autolua2\engine\src\main\java\com\autolua\engine\extension\ui\UiLib.kt:253`、`UiLib.kt:265`）。
- 生命周期释放：`IUiMgr.onRelease` 主线程统一关全部窗口/弹窗并清空事件队列（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\UiMgrImp.kt:298`）；窗口/节点销毁级联释放 WebView（stopLoading+about:blank+destroy）与 VideoView（stopPlayback）（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\UiMgrImp.kt:244`）。
