# 动态 UI 与宿主 UI（ui）

> 文档版本 v1.0（2026-09-18，文档三层化重组新建）
> 本域权威范围：动态 UI 体系（Lua 全局 `ui` 表：JSON 控件树 + op 协议 + 事件轮询派发）与宿主 UI（悬浮控制条 FloatController、MLN LuaView 悬浮视图与信号通道、MLN 调试热重载）的引擎/宿主实现契约与设计；脚本作者视角的 API 面见 [../../02-Lua-API规范.md](../../02-Lua-API规范.md) §17.1。

## 状态

动态 UI（`ui` 表）**已落地稳定**，是脚本 UI 的生产路线（`autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\` + `autolua2\engine\src\main\java\com\autolua\engine\extension\ui\UiLib.kt`）；MLN（LuaView 体系）**仅保留调试预览**（`ui_debug` 热重载）与旧 `UI` 服务（toast/信号）兼容，不作为新功能路线（[../../06-开发铁律与踩坑手册.md](../../06-开发铁律与踩坑手册.md) §4）。

## 本域文档

| 文档 | 内容 |
|---|---|
| [contracts.md](contracts.md) | 七条实现契约：op 协议（uimgr:op(json) String 语义 + op 类型全集）、控件 type 全集与通用属性、事件 JSON 结构（win/id/event 三元组 + cap 256）、pollEvents 协议（50ms 轮询）、bind 回调语义（每事件独立协程）、dispatcher 退出契约（ui.shutdown + 空闲 3s，重大坑）、窗口/弹窗协议 |
| [design.md](design.md) | 双进程分工架构、事件管线（listener 常挂→队列→轮询→路由）、控件工厂机制（JsonViewFactory/ContextThemeWrapper/collectValues 顺序）、分页与弹窗设计取舍、代码映射表、演进记录（ViewPager2 弃用、MLN 保留范围） |
| [client.md](client.md) | 客户端/宿主集成：MainApp 装配（uimgr 服务注册）、悬浮窗权限要求、IDE 沙箱注入、MLN 调试链（ui_debug WS 命令） |

## 全局真相源交叉引用

| 主题 | 权威位置 | 说明 |
|---|---|---|
| ui_* 脚本 API 全集（脚本作者视角） | [../../02-Lua-API规范.md](../../02-Lua-API规范.md) §17.1 | 窗口/控件/事件/弹窗/console API 表与示例；本域 contracts 与其对账关系见 [contracts.md](contracts.md) ② |
| app 模块地图（extension/ui、mln、view 包职责） | [../../01-架构总览.md](../../01-架构总览.md) §7 | MainApp 总装、`mln`、`extension`、`view` 包分工 |
| 服务注入矩阵（三环境 Lua 全局名一致铁律） | [../../01-架构总览.md](../../01-架构总览.md) §8 | root 主引擎 / IDE 沙箱注册点，uimgr/uilib 双环境同款 |
| 宿主事件→Lua event 总线（对照参考） | [../../01-架构总览.md](../../01-架构总览.md) §9.6 | 无障碍宿主事件经 EventBridge→EventLib 轮询——与 ui 域事件轮询同型不同通道 |
| 跨进程通道契约（root RPC / 本地服务注册 / pollEvents JSON 帧） | [../ipc/contracts.md](../ipc/contracts.md) ③④ | String 收发规避 Parcel 限制、addLocalService 注册、事件帧结构权威 |
| ui 表注册点（引擎侧归属） | [../luaapi/contracts.md](../luaapi/contracts.md) | `uilib` 服务 → UiLib contribute 注入全局 ui/console 表 |
| ui_debug WS 命令面 | [../../03-WS协议规范.md](../../03-WS协议规范.md) | ui_debug_start / ui_debug_stop / ui_debug_result |
| 铁律与踩坑（execute 偶发挂死、悬浮窗权限、残留 daemon） | [../../06-开发铁律与踩坑手册.md](../../06-开发铁律与踩坑手册.md) | §3 运行时陷阱、§4 已知限制 |
| IDE 远程调试通道契约 | [../ide-remote/contracts.md](../ide-remote/contracts.md)（待建） | ui_debug 命令的远程通道细节归 ide-remote 域，文档待三层化重组补建 |

## 代码入口

| 通道 | 路径 |
|---|---|
| 动态 UI 服务端（op 分发/事件队列/悬浮窗/弹窗宿主） | `autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\`（IUiMgr.kt、UiMgrImp.kt、JsonViewFactory.kt、DialogHost.kt） |
| Lua API 层（全局 ui/console 表注入） | `autolua2\engine\src\main\java\com\autolua\engine\extension\ui\UiLib.kt`（内嵌 uilib Lua） |
| 宿主 UI 旧服务（toast/信号/MLN 悬浮视图） | `autolua2\app\src\main\java\com\autolua\autolua2\extension\imp\`（UserInterfaceImp.kt、FloatViewImp.kt） |
| MLN UD 桥（Lua ↔ UserInterfaceImp） | `autolua2\app\src\main\java\com\autolua\autolua2\mln\ud\UI.kt` |
| 悬浮控制条（启停悬浮球） | `autolua2\app\src\main\java\com\autolua\autolua2\view\imp\FloatControllerImp.kt` |
| 总装配（服务注册/沙箱注入/热重载委托） | `autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt` |
| MLN 热重载入口脚本 | `autolua2\app\src\main\assets\hotreload.lua` |

## 何时读本文档

- 要给动态 UI 加控件 type/属性/事件，或排查"控件不显示/属性不生效"时 → [contracts.md](contracts.md) ②、[design.md](design.md) 控件工厂机制
- 要排查"UI 回调不触发/弹窗不返回/脚本结束后不再响应"时 → [contracts.md](contracts.md) ③④⑤⑥（⑥ dispatcher 退出契约是重大坑）、[design.md](design.md) 事件管线
- 要排查"悬浮窗不出现/BadTokenException"时 → [client.md](client.md) 悬浮窗权限、[../../06-开发铁律与踩坑手册.md](../../06-开发铁律与踩坑手册.md) §4
- 要接入 IDE 沙箱跑 UI 脚本，或做 MLN 热重载调试时 → [client.md](client.md)、[../../03-WS协议规范.md](../../03-WS协议规范.md) ui_debug 命令
- 要改跨进程调用方式（op/pollEvents 的 String 语义）时 → [contracts.md](contracts.md) ①④、[../ipc/contracts.md](../ipc/contracts.md) ③④
- 改本域任何代码前 → [../../06-开发铁律与踩坑手册.md](../../06-开发铁律与踩坑手册.md)（必读）
