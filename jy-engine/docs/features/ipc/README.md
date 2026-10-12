# 跨进程与权限通道（ipc）

> 文档版本 v1.0（2026-09-18，文档三层化重组新建）
> 本域权威范围：app 进程与 root 引擎进程之间的全部通道——root RPC（su+app_process+stdin/stdout 帧协议）、UI 事件轮询通道、输入注入四级降级链、AIDL 整树 dump、共享内存截图帧、隐藏 API 镜像、输入法通道的契约与设计。

## 本域文档

| 文档 | 内容 |
|---|---|
| [contracts.md](contracts.md) | 七大通道契约：stdin/stdout 帧协议、Parcel/InvokePackage 序列化、本地服务注册、pollEvents JSON、IInjectChannel 降级语义、AIDL AuxDumpService、共享内存 seqlock 帧协议 |
| [design.md](design.md) | 双进程模型、root RPC 链路、UI 事件通道设计取舍、注入降级链决策顺序、共享内存帧机制、代码映射表、已知约束与坑 |

## 全局真相源交叉引用

| 主题 | 权威位置 | 说明 |
|---|---|---|
| 进程模型 | [01-架构总览.md](../../01-架构总览.md) §4 | app / root 双进程职责划分 |
| 服务注入矩阵 | [01-架构总览.md](../../01-架构总览.md) §8 | 各运行环境（root 主引擎 / IDE 沙箱）的 Lua 全局服务清单 |
| 脚本执行数据流 | [01-架构总览.md](../../01-架构总览.md) §9.1 | run_script → 跨进程 execute → Lua 解释器的全链路 |
| 输入注入降级链数据流 | [01-架构总览.md](../../01-架构总览.md) §9.3 | InjectShellClient 状态机四通道择优与降级 |
| 关键机制索引（seqlock 帧协议等） | [01-架构总览.md](../../01-架构总览.md) §10 | A/B 双缓冲 + 帧计数校验重试三侧同协议 |
| ui 表 / pollEvents 脚本侧 | [02-Lua-API规范.md](../../02-Lua-API规范.md) §17.1 | ui_* API 全集与事件回调模型（脚本视角） |
| 注入/截图/dump/IME 细节全案 | [../engine-core/INTEGRATION_PLAN.md](../engine-core/INTEGRATION_PLAN.md) §2/§3/§4/§6 | 方案二（注入 stdin/stdout 行协议）、方案一（截图管线）、方案三 §4.3.3（dump 跨进程传输）、方案五（模拟输入法） |
| 铁律与踩坑 | [06-开发铁律与踩坑手册.md](../../06-开发铁律与踩坑手册.md) | 残留 root daemon、读线程静默死亡（管道积压反卡写线程）、execute 偶发挂死等 |

## 代码入口

| 通道 | 路径 |
|---|---|
| root RPC 链路（拉起/宿主/客户端/传输/服务表/序列化/回调） | `autolua2\engine\src\main\java\com\autolua\engine\core\root\`（RootProcessStarter.kt、Server.kt、Client.kt、Transporter.kt、TransportImp.kt、ServiceManager.kt、ServiceManagerImp.kt、InvokePackage.kt、DataCallbackManager.java） |
| UI 事件轮询通道（app 生产端 / root 消费端） | `autolua2\app\src\main\java\com\autolua\autolua2\extension\ui\UiMgrImp.kt`；`autolua2\engine\src\main\java\com\autolua\engine\extension\ui\UiLib.kt`；服务注册点 `autolua2\app\src\main\java\com\autolua\autolua2\MainApp.kt` |
| 输入注入降级链 | `autolua2\engine\src\main\java\com\autolua\engine\input\inject\`（InjectorCore、IInjectChannel、DirectRootChannel、RootInjectorChannel、RootInputInjectorMain、InjectShellClient、ShizukuInjectorChannel、ShizukuUserServiceMain、AccessibilityChannel、MotionEventBuilder、Humanizer、InjectMode） |
| AIDL 整树 dump | `autolua2\engine\src\main\java\com\autolua\engine\accessibility\`（AuxDumpService、AuxDumpServiceLocal、AutoLuaAccessibilityService）；AIDL 声明 `autolua2\engine\src\main\aidl\com\autolua\engine\accessibility\IAuxDumpService.aidl` |
| 共享内存截图帧（Java 写侧 / C++ 读侧） | `autolua2\engine\src\main\java\com\autolua\engine\display\sharedmem\`（SharedMemoryManager、FrameBuffer）；`autolua2\engine\src\main\cpp\extension\SharedFrame.h` / `SharedFrame.cpp` |
| 隐藏 API 镜像 | `autolua2\engine\src\main\java\com\autolua\engine\conceal\`（SurfaceControlWrap、InputManagerWrap、IWindowManager、ActivityManagerNative）；框架隐藏类声明 `autolua2\engine\src\main\java\android\`（app/ActivityThread、os/ServiceManager、view/SurfaceControl 等） |
| 输入法通道 | `autolua2\engine\src\main\java\com\autolua\engine\inputmethod\`（AutoLuaInputMethodService、ImeApi） |

## 何时读本文档

- 要改 root 引擎拉起方式、RPC 方法分发、帧协议编解码，或排查"root 调用超时/无响应"时 → [contracts.md](contracts.md) ①②③、[design.md](design.md) root RPC 链路
- 要给动态 UI 加控件事件/弹窗事件，或排查"UI 回调不触发/弹窗不返回"时 → [contracts.md](contracts.md) ④、[design.md](design.md) UI 事件通道、[02-Lua-API规范.md](../../02-Lua-API规范.md) §17.1
- 要加新注入通道、改降级顺序，或排查"某机型点击无效"时 → [contracts.md](contracts.md) ⑤、[design.md](design.md) 注入降级链、[../engine-core/INTEGRATION_PLAN.md](../engine-core/INTEGRATION_PLAN.md) §3
- 要改截图帧共享布局、接 C++/Lua 读侧，或排查"截图花屏/帧不更新"时 → [contracts.md](contracts.md) ⑦、[design.md](design.md) 共享内存帧、[01-架构总览.md](../../01-架构总览.md) §9.2/§10
- 要扩无障碍整树 dump 字段或改节点模式切换时 → [contracts.md](contracts.md) ⑥、[../engine-core/INTEGRATION_PLAN.md](../engine-core/INTEGRATION_PLAN.md) §4.3.3
- 要做文本输入/输入法切换功能时 → [design.md](design.md) 输入法通道、[../engine-core/INTEGRATION_PLAN.md](../engine-core/INTEGRATION_PLAN.md) §6
- 改本域任何代码前 → [06-开发铁律与踩坑手册.md](../../06-开发铁律与踩坑手册.md)（必读）
