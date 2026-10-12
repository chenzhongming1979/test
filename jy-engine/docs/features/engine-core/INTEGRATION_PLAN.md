# autolua2 引擎增强集成方案

> 基于《懒人精灵》《按键精灵》《派大星》三款商业自动化引擎 APK 的逆向分析成果整理。
> 本文档面向集成实施 Agent，所有参考实现均给出反编译产物的**精确文件路径与行号**，可直接查阅源码对照。

- 文档版本：v1.5（2026-09-14，文档体系建立：新增 01-架构总览/03-WS协议规范/05-构建与自测指南/06-开发铁律与踩坑手册，见 ../../docs/README.md 索引）
- 文档版本：v1.6（2026-09-18，文档三层化迁移：本档案移入 features/engine-core/ 作设计细节存档，各域 design.md 引用本文章节）
- 文档版本：v1.4（2026-09-12，基础 API 批量补齐交付）
- **实施状态（2026-09-10 三期完成）**：

| 方案 | 状态 | 交付位置 |
|---|---|---|
| 方案一 截图管线 | ✅ 完整交付（含跨进程 IScreenShareService AIDL fd 传输） | `engine\...\display\sharedmem\`、`display\projection\`（+ScreenShareServiceImpl）、`cpp\extension\SharedFrame.cpp` |
| 方案二 注入降级链 | ✅ 四通道全部直连（ACCESSIBILITY 通道已并入降级链） | `engine\...\input\inject\`（+AccessibilityChannel） |
| 方案三 obtainXml | ✅ 完整交付（含 IAuxDumpService AIDL PFD 流式跨进程 + AuxDumpServiceLocal 单进程直用） | `engine\...\accessibility\`（+AuxDumpService/Impl/Local） |
| 方案四 保活体系 | ✅ 完整交付（SyncAdapter 未做，可选） | `app\...\keepalive\` |
| 方案五 IME 通道 | ✅ 完整交付 | `engine\...\inputmethod\` |
| 方案六 WebSocket IDE | ✅ 一期+二期+三期完整交付：Lua 沙箱执行（LuaEngineExecutor）、**app 服务注入沙箱（UI/FloatController 同实例 + ROOT_DIR，JavaObjectWrapper 对齐主引擎）**、push_files/push_zip、图色远程（**C++ VisionBridge JNI 加速**，autolua_vision 复用+行级早退，纯 Java fallback；OCR 待接入）、[IMG] 分片、print 重定向、capabilities、**pause/resume 全链路（AutoLuaEngine 接口 → Client/Server 通用分发零改动 → pauseNative/resumeNative JNI）** | `engine\...\ide\`（21 文件）+ `cpp\extension\VisionBridge.cpp` + `core\AutoLuaEngine.kt` + `app\MainApp.kt` |
| 方案七 插件动态加载 | ✅ 框架交付（OCR 插件化路线，见 ../../04-插件包规范.md；本地免服务端直装） | `engine\...\plugin\`、`engine\...\ide\PluginCommandHandler.java`、`plugins\demo-ocr\` |
| 方案八 远程控制与投屏 | ✅ 一期交付（双 codec 投屏 H264+JPEG、操控回传、run_script 主引擎通道、无障碍自拉活、tools\remote.html 控制台；二期：root daemon/批量面板）；通道整合完成（9.11：8176 老调试通道退役，UI 热重载/UDP 设备发现迁移 8080 WebSocket IDE） | `engine\...\display\stream\ScreenStreamController.java`、`engine\...\ide\`（+RunScriptHandler/InjectCommandsHandler/StreamCommandsHandler/ScriptRunDelegate/+UiDebugDelegate/UiDebugHandler）、`accessibility\AccessibilityLaunchHelper.java`、`app\MainApp.kt`、`app\...\debug\DiscoveryManager.kt`、`tools\remote.html` |

- **基础 API 批量补齐（2026-09-12，3b3ef81 + 0dc3a52）**：对齐懒人精灵/派大星补齐 16 大类底层 Lua API——
  - 三件套 `3b3ef81`：http.request（NetLib）、luajava 反射桥（import/importClass + LuaClassWrapper）、LuaEngine.getContext/loadApk、thread 多线程（同 state 协程 + per-state 递归锁，计划见 ../bridge/BASIC_BRIDGE_PLAN.md）
  - 16 大类 `0dc3a52`：json（C++ json_lib 绑 cjson）、file（FileLib+懒人别名）、device（DeviceLib）、crypt（CryptoLib）、app（AppLib root am/pm）、compat（纯 Lua 兼容层：图色别名/auto.* 节点工厂/utf8+table+math 扩展/setTimer/frandom）；触控便捷层（touchDown/Move/Up/longTap + KeyCode 扩 13 键）；节点（UiObject 11 ACTION + UiSelector 补条件）
  - 注入：Server.kt（root）+ MainApp.kt（沙箱）双路径本地注册 8 服务（http/luajava/LuaEngine/file/device/crypt/app/compat）
  - 逐项缺口勾销状态：见 ../luaapi/API_GAP_LR.md / ../luaapi/API_GAP_FIREFLY.md"⭐ 补齐批次记录"；覆盖率懒人 ~18%→~45%、派大星 32%→~50%
  - 验证：gradle assembleRelease 4 ABI 通过（8m29s）；模拟器实测待执行

- 全量编译验证：`:engine` Java/Kotlin/资源/native(四 ABI) + `:app` = **BUILD SUCCESSFUL**
- 提交链：phase1 `dfb8533` → phase2 `816c7ee` → phase2.2 `45d3ca0` → phase3 `166aa11`
- 剩余事项：真机验收（各方案验收 checklist）、OCR 接入（选型待定：PP-OCR(ncnn) / Tesseract / MLKit——国内设备 MLKit 不可用）、沙箱 display/input 注入（需 libengine_base 扩展 JNI，影响面大暂缓）、NEON/OpenMP 找图增量优化
- 真机验证命令：wscat 连 ws://<设备IP>:8080；`{"type":"execute","data":{"code":"_WS_RESULT = tostring(UI)"}}` 期望非 nil；`{"type":"execute","data":{"code":"for i=1,10 do print(i) sleep(1000) end"}}` + pause_script/resume_script 验证暂停恢复；`{"type":"capabilities"}` 查能力
- 反编译产物根目录：`D:\tools\reverse\decompiled\`
  - `lanren\sources\` — 懒人精灵（包名 `com.nx.nxprojit`）
  - `anjian\sources\` — 按键精灵（包名 `com.cyjh.mobileanjian.vip`）
  - `paidaxing\sources\` — 派大星（包名 `com.auto.lua`）
  - `_assets\` — 已提取的明文 Lua 资产
- autolua2 代码根目录：`D:\Project\script\lrjl\jy-frame-lrjl\jy-frame-engine\autolua2\`
  - `engine\src\main\cpp\` — C++ 引擎（base / core / extension / remote，第三方库 lua、libhv、opencv 未见、cjson、protobuf）
  - `engine\src\main\java\` — Java 桥接层（`com.autolua.engine.*`，含 base/common/conceal/core/composite/root/extension.display/extension.input）
  - `app\` — Android 客户端
- 已确认现有能力：C++ Lua 解释器（`core\ALEState.h`、`LuaInterpreter.h`）、Display 扩展、InputManager 双路径（`JavaInputManager` / `NativeInputManager`）、NormalRandomNumber 人化随机、libhv 网络栈。

---

## 目录

1. [集成方案总览](#1-集成方案总览)
2. [方案一：截图管线重构（P0）](#2-方案一截图管线重构p0)
3. [方案二：输入注入四级降级链（P0）](#3-方案二输入注入四级降级链p0)
4. [方案三：无障碍 obtainXml 整树方案（P1）](#4-方案三无障碍-obtainxml-整树方案p1)
5. [方案四：进程保活体系（P1）](#5-方案四进程保活体系p1)
6. [方案五：模拟输入法通道（P2）](#6-方案五模拟输入法通道p2)
7. [方案六：IDE 调试 WebSocket 协议（P2）](#7-方案六ide-调试-websocket-协议p2)
8. [Android 版本适配总表（API 21→35）](#8-android-版本适配总表)
9. [性能优化清单](#9-性能优化清单)
10. [网络接口参考清单](#10-网络接口参考清单)
11. [附录：反编译关键文件索引](#11-附录反编译关键文件索引)

---

## 1. 集成方案总览

| 优先级 | 方案 | 对标实现 | 预期收益 | 主要改动层 |
|---|---|---|---|---|
| P0 | 截图管线重构（共享内存 A/B 双缓冲） | 派大星 SharedMemoryManager + 懒人 VirtualDisplay | 每帧 1080p≈8.3MB 全程仅 1 次 CPU 拷贝，Lua FFI 零 JNI 读帧 | Java + C++ + Lua |
| P0 | 输入注入四级降级链 | 派大星 TouchShellClient | 免 root 可用（无障碍/Shizuku），root 下注入性能与隐蔽性最大化 | Java + C++ |
| P1 | obtainXml 整树方案 | 懒人 NodeDumpByObject | 无障碍节点获取一次跨进程替代整树逐节点遍历，快一个数量级 | Java + C++ |
| P1 | 进程保活体系 | 派大星 KeepAlive 全家桶 | Android 14/15 下脚本进程长期存活 | Java |
| P2 | 模拟输入法通道 | 派大星 FireflyInputMethodService | 免 root 文本注入最优解 | Java |
| P2 | IDE 调试 WebSocket 协议 | 派大星 WebSocketApi | VSCode 插件联调基础（执行/暂停/推文件/热修/图色远程计算） | Java + C++(libhv) |

依赖关系：方案二依赖方案一的截图可用性判断（root 通道需确认投影/截屏状态）；方案六依赖方案一、二完成后的 API 面。建议按 P0 → P1 → P2 顺序实施。

---

## 2. 方案一：截图管线重构（P0）

### 2.1 目标

1. 免 root：MediaProjection + ImageReader 常驻采集，支持跨进程 Surface 投递；
2. root：免授权截屏（隐藏 API / SurfaceControl 反射）+ A/B 双缓冲共享内存输出；
3. Lua 侧通过 LuaJIT FFI seqlock 直接读共享内存，无锁、无 Bitmap、无 JNI 大数组拷贝；
4. 全链路自愈：帧超时看狗、管线自动重建、旋转自动适配。

### 2.2 参考实现索引（必读源码）

| 参考点 | 文件（相对 `paidaxing\sources\`） | 说明 |
|---|---|---|
| App 模式采集服务 | `com\auto\lua\core\images\CaptureScreen\CaptureScreenService.java` | ImageReader(8) + acquireLatestImage + 循环线程节拍 |
| Root 免授权采集 | `com\auto\lua\shell\root\RootScreenshotService.java` | `:80-118` 隐藏 API + SurfaceControl 反射 fallback |
| SurfaceControl 反射包装 | `com\auto\lua\shell\app\wrappers\SurfaceControl.java:21-36` | createDisplay/openTransaction/setDisplaySurface/setDisplayProjection/setDisplayLayerStack |
| root 进程环境伪造 | `com\auto\lua\shell\root\Workarounds.java:59-118` | 伪造 ActivityThread + FakeContext，使 UID=0 可调框架 API |
| 共享内存管理 | `com\auto\lua\core\images\screenshot\sharedmem\SharedMemoryManager.java` | A/B 双缓冲、fd 反射、seqlock 写协议 |
| Lua FFI 读侧 | `com\auto\lua\scriptruntime\engine\LuaRuntimeEngine.java:828-840` | `_root_get_frame_info` + captureScreen 补丁 |
| 懒人跨进程投影 | `lanren\sources\ILil\p028iILLL1\IL1Iii\iI1LI.java:108-135` | 脚本进程 ImageReader + Surface 经 AIDL 交核心进程建 VirtualDisplay |
| 懒人帧包装 | `lanren\sources\ILil\p028iILLL1\IL1Iii\LIl.java:23-105` | Image 拷入自管 ByteBuffer 后立即 close；stride 扩宽 Bitmap 一次拷贝 |
| 懒人投影服务 | `lanren\sources\ILil\p028iILLL1\IL1Iii\C1.java` | MediaProjection.Callback、失败重试 3 次、5s 无帧重投影 + 30s 冷却 |
| 按键格式降级 | `anjian\sources\com\cyjh\mobileanjian\screencap\ScreenShoterV3.java:143-147,459-468` | RGB_565→RGBA 异常驱动降级 + Android 15 分支 |

### 2.3 详细设计

#### 2.3.1 进程架构

```
[app 主进程]                [:media 进程]                  [:script 进程]              [root 进程(可选)]
 CaptureScreenService  ←AIDL→  MediaProjection             ScriptEngine                LuaRootService
 + SharedMemoryManager         + ImageReader(w,h,RGBA,8)   + LuaJIT FFI 读共享内存      + ServiceManager.createVirtualDisplay
 + ScreenshotProviderManager   + VirtualDisplay                                        + SurfaceControl fallback
 (provider 切换：app/root/script)                                       + SharedMemoryManager.writeFrameWithSwap
```

- Java 侧新增包：`com.autolua.engine.display.projection`（授权/投影服务）、`com.autolua.engine.display.sharedmem`（共享内存）、`com.autolua.engine.display.provider`（Provider 抽象）。
- C++ 侧扩展 `engine/src/main/cpp/extension/Display.cpp`：注册 `images.captureScreen / images.release / images.width / images.height` 等绑定（命名可与派大星 `modules/images` 对齐）。
- Manifest：`CaptureScreenService` 声明 `android:foregroundServiceType="mediaProjection"`；权限加 `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PROJECTION`（对照 `paidaxing\resources\AndroidManifest.xml:21-23,157-161`）。

#### 2.3.2 采集服务（App 模式）

对照 `CaptureScreenService.java`，关键参数与流程：

1. 授权：`MediaProjectionManager.createScreenCaptureIntent()` → onActivityResult 保存 `resultCode/resultData`（**API 35 重授权需复用**，见 2.4-⑤）。
2. `ImageReader.newInstance(w, h, ImageFormat.RGBA_8888, 8)`（maxImages=8，比 3 更抗瞬时峰值；派大星 :490-495）。
3. `mediaProjection.createVirtualDisplay("ScreenshotDisplay", w, h, density, 17 /*PUBLIC|AUTO_MIRROR*/, reader.surface, null, handler)`；`registerCallback(onStop)` 触发重授权/重建。
4. 帧回调内：
   - **一致性守卫**：回调先比对 `activeImageReader` 指针，旧 reader 的迟到回调直接丢弃（防重建竞态，:549-556）；
   - `acquireLatestImage()` 只取最新帧（积压自动丢弃）；
   - stride 快路径：`rowStride == w*4 && pixelStride == 4` 时 `buffer.get(dst, 0, size)` 整块拷贝；慢路径逐行 `arraycopy` 并强制 `alpha=255`（:224-277）；
   - ThreadLocal 缓冲复用，旋转重建时 `remove()`；
   - 空帧过滤：非零字节 < 1% 判无效丢弃（:430-444）；
   - 写共享内存（见 2.3.4）+ 通知 frameConsumers。
5. 采集节拍：独立循环线程 `SystemClock.sleep(max(1, intervalMs - elapsed))`，默认 50ms（≈20fps），WebRTC/投屏场景可 30ms（:637-671, :640-642）。
6. 线程优先级：`new HandlerThread("ScreenshotHandlerThread", -4)`（THREAD_PRIORITY_URGENT_DISPLAY，:975）。

#### 2.3.3 Root 模式免授权采集

对照 `RootScreenshotService.java`，在 root 进程（UID==0，`Process.myUid()==0` 判定）内执行：

```
1. Workarounds.init()               // 伪造 ActivityThread/FakeContext（:59-118）
2. 首选: ServiceManager.getDisplayManager()
        .createVirtualDisplay("RootScreenshot", w, h, 0, surface)   // 隐藏签名重载
3. fallback: SurfaceControl 反射四件套:
      token = SurfaceControl.createDisplay("RootScreenshot", false)
      openTransaction()
      setDisplaySurface(token, surface)
      setDisplayProjection(token, 0, rect, rect)
      setDisplayLayerStack(token, displayInfo.layerStack)   // layerStack 取 displayIds[0] 的 DisplayInfo
      closeTransaction()
4. ImageReader(3) 收帧 → RootScreenshotService.imageToBytes()（快/慢双路径同 App 模式）
```

- 主屏查询用隐藏 API：`ServiceManager.getDisplayManager().getDisplayIds()` 取 `displayIds[0]`，`getDisplayInfo(id)` 拿 w/h/dpi/rotation/layerStack（:185-207）。
- root 进程宿主沿用 autolua2 已有 `core\root` 模块；启动方式见方案二 3.3.3（app_process 命令行相同）。

#### 2.3.4 共享内存 A/B 双缓冲（核心协议）

对照 `SharedMemoryManager.java`，**协议必须与参考实现完全一致**以便 Lua 侧直接复用：

**建立**：
```java
// 1) 两块等尺寸缓冲
MemoryFile a = new MemoryFile("screenshot_a", w*h*4);
MemoryFile b = new MemoryFile("screenshot_b", w*h*4);
// 2) 反射取 fd: MemoryFile.getFileDescriptor() → FileDescriptor.descriptor 字段（:69-86）
// 3) 跨进程传 fd: ParcelFileDescriptor.dup(pfd)（dupPfd, :36-50）
// 4) native 侧 mmap:
//    Java: SharedMemoryManager.mapFdToByteBuffer(fd, size)   (native, libfast_screenshot 对应物)
//    C++ : open/mmap → 返回裸地址 getDirectBufferAddress
// 5) setSharedMemoryInfoNative(addrA, addrB, size, w, h) 下沉给 C++ 引擎
// 失败回退: ByteBuffer.allocateDirect（:288-293）
```

**写协议（二选一，按模式）**：
```java
// Root 模式（seqlock，固定写 A，对照 :417-438）:
frameCounter.incrementAndGet();          // 写前置 → 奇数 = 写进行中
sharedMemoryA.put(bytes, 0, size);
frameCounter.incrementAndGet();          // 写后置 → 偶数 = 写完成
// getNativeFrameInfo() → [w, h, addrA, counter]

// App 模式（ping-pong，对照 :440-474）:
int target = 1 - readBufferIndex;
buffers[target].put(bytes);              // 写 inactive 块
readBufferIndex = target;
frameCounter.incrementAndGet();
// 9 字节自描述头写入两块: [0]=bufferIndex(byte), [1..4]=width(int BE), [5..8]=height(int BE)
```

**读协议（Lua FFI seqlock，必须原样实现，对照 LuaRuntimeEngine.java:828-840）**：

```lua
-- 注册 C 函数: _root_get_frame_info() → w, h, addr, counter
function images.captureScreen(width, height, quality)
    local w, h, addr, size = _root_get_frame_info()
    local buf = ffi.new('uint8_t[?]', w*h*4)
    for retry = 1, 8 do
        local c1 = select(4, _root_get_frame_info())
        ffi.copy(buf, ffi.cast('void*', addr), w*h*4)
        local c2 = select(4, _root_get_frame_info())
        if c1 == c2 then break end          -- counter 未变 = 读到完整帧
    end
    return createWrapper(buf, w, h)         -- 返回带 width/height/getMat/release/toPng 的 wrapper
end
```

#### 2.3.5 跨进程 Surface 投递（免 root 双进程模式，对标懒人）

若 autolua2 采集团队选择"采集与脚本分进程"：

1. 脚本进程自建 `ImageReader`，拿 `reader.getSurface()`；
2. 经 AIDL 把 Surface 传给 `:media` 进程：`IVirtualDisplayProxy.createAndBindVirtualDisplay(w, h, dpi, surface, callback)`（对照 `iI1LI.java:123-131`、AIDL `ili11.java:65-101`）；
3. `:media` 进程用投影 token 建 VirtualDisplay 并 `setSurface(外部 surface)`；
4. 返回 displayId；脚本进程多显示器管理用 `SparseArray<ImageWrapper>`（对照 `iI1LI.java:135,308-356`），无新帧复用上一帧缓存。
5. Binder 等待：binder 未就绪时 100ms 间隔轮询，最长 10s（对照 `RunnableC01041.java:805-829`）。

#### 2.3.6 旋转 / 尺寸变化

| 模式 | 监听 | 处理 | 参考 |
|---|---|---|---|
| App | `onConfigurationChanged` + manifest `configChanges="screenSize|orientation|keyboardHidden"` | CountDownLatch(1s) 排空 handler → 新 ImageReader → `VirtualDisplay.resize(w,h,density)` + `setSurface` → 旧 reader 延迟 500ms 关闭 | 派大星 `recreateImageReaderForOrientation`（:730-774） |
| Root | 每次取尺寸时轮询 `DisplayInfo.rotation` | `(old%2 != new%2)` 即 90/270° → 宽高对调 + resize；180° 不 resize；resize 失败回退 SurfaceControl `setDisplaySurface/setDisplayProjection`，再失败 destroy+create | RootScreenshotService.java:42-69, 288-334 |
| 懒人式（无 Listener） | `postDelayed(1000ms)` 尺寸复查 | 重查真实分辨率 → 重建 reader + resize + setSurface | C1.java:353-361, 405-415 |

旋转后必须重分配共享内存尺寸并同步 root 侧（`pushScreenSizeToRoot`，:673-682）。

#### 2.3.7 帧看狗与自愈

| 看狗项 | 阈值 | 动作 | 参考 |
|---|---|---|---|
| 无新帧 | 5000ms | 重拉投影（全量重建），失败计数≥3 后 30s 冷却 | 懒人 C1.java:752-791 |
| 无回调且 acquireLatestImage 为 null | 3000ms | `rebuildScreenshotPipeline()` 整链重建（rebuildCount 可观测） | 派大星 :684-728 |
| 格式被拒（异常消息含 `producer output buffer format`） | 即时 | format 换 RGBA_8888 重建（只降级一次，`f19415r` 标志） | 按键 ScreenShoterV3.java:143-147, 494-531 |
| MediaProjection.onStop | 即时 | API 35：延迟 600ms 用保存的 resultCode/data 重授权一次 | 派大星 :617-634, 954-958 |
| resize 后稳定性 | 1500ms | postDelayed 整体重建一次确保可用 | 按键 :469-472 |

### 2.4 版本适配分支（截图向，必须全部实现）

| # | 分支 | 实现 | 依据 |
|---|---|---|---|
| ① | `SDK_INT >= 21` | MediaProjection 可用性前提 | 按键 ForScreenShotActivity.java:22 |
| ② | `SDK_INT >= 17` | `Display.getRealMetrics` 获取真实分辨率（含导航栏），<17 反射 | 按键 nd\c.java:25-41 |
| ③ | `SDK_INT >= 29` | FGS `foregroundServiceType="mediaProjection"` + 权限声明 | 懒人 Manifest:53-54,181 |
| ④ | `SDK_INT == 35` | ImageFormat 强制 RGBA_8888（Android 15 拒绝 RGB_565 虚拟屏输出） | 按键 ScreenShoterV3.java:459-463 |
| ⑤ | `SDK_INT >= 35` | `onStop` 后 600ms 复用保存的授权令牌重试（Android 15 每会话重授权，可能失败仅一次） | 派大星 :954-958 |
| ⑥ | `SDK_INT >= 34` | FGS 权限 `FOREGROUND_SERVICE_MEDIA_PROJECTION` 运行时检查 | 派大星 Manifest:21-23 |
| ⑦ | 全版本 | 异常驱动格式降级（RGBA↔RGB565，双向参考懒人/按键） | C1.java:208-221 |

### 2.5 验收标准

- [ ] 免 root：同意投屏后 `images.captureScreen()` 稳定返回全屏帧；连取 1000 帧无 OOM/无 IllegalStateException
- [ ] root：无需投屏授权即出帧；杀掉 MediaProjection 相关服务不影响
- [ ] Lua FFI 读帧 seqlock 8 次重试内成功率 100%（压力写帧场景验证）
- [ ] 旋转屏幕 90°：≤500ms 内恢复出正确方向帧，无崩溃
- [ ] 杀帧源（停投影）：3-5s 内自动恢复
- [ ] CPU：1080p@20fps 稳态采集整机 CPU 增量 <8%（对比参考：整链仅 1 次 CPU 拷贝）
- [ ] 内存：常驻增量 < 2×帧大小（双缓冲）+ 8 张 Image 池

---

## 3. 方案二：输入注入四级降级链（P0）

### 3.1 目标

构建 `DIRECT_ROOT → ROOT(app_process) → SHIZUKU → ACCESSIBILITY(无障碍)` 自动选择、运行时自动降级的注入体系；免 root 场景可用无障碍与 Shizuku，root 场景获得 evdev 级性能。点击/滑动接口在人化轨迹上与 autolua2 现有 `NormalRandomNumber` 打通。

### 3.2 参考实现索引

| 参考点 | 文件 | 说明 |
|---|---|---|
| 降级链门面 | `paidaxing\sources\com\auto\lua\shell\TouchShellClient.java` | Mode 枚举、start() 优先级、sendCommand 失败自动降级（:30-35, 107-127, 322-344） |
| root 常驻注入进程 | `paidaxing\sources\com\auto\lua\shell\root\RootInputInjector.java` | app_process + stdin/stdout 行协议；环境伪造（:881-924）；InputManager 三层 fallback（:927-959） |
| Shizuku 集成 | `paidaxing\sources\com\auto\lua\core\shizuku\WrappedShizuku.java` + `UserService.java` | 授权（:120-126）、UserService 绑定等待 5s（:200-207）、shell 进程内反射 injectInputEvent（:304-364） |
| 无障碍手势队列 | `anjian\sources\c\b.java`（GestureQueueManager） | 串行队列 + CountDownLatch 同步（:79-97, 100-130）、API26 多指（:309-329）、超时公式（:162-174） |
| 手势 Path 累积 | `anjian\sources\ya\a.java:34-60` | down/move 客户端累积 Path、up 一次派发 |
| 懒人注入器 | `lanren\sources\ILil\p028iILLL1\IL1Iii\iil1.java` | MotionEvent 多指状态机、预分配 10 指（:38-57, 97-143）、随机扰动（:111-114） |
| 懒人多 stroke 派发 | `lanren\sources\ILil\p023L11I\I1I\IL1Iii\I1I.java:97-277,356-397` | JSON 多笔划一次 GestureDescription；临时 Looper 阻塞等待 |
| evdev 直写 | `paidaxing\sources\com\auto\lua\TouchInject.java` | nativeDown/Move/Up(Slot)/nativeSendEvent/nativeOpenDevicePath 等签名 |
| root 注入备选（按键） | `anjian\sources\vd\a.java:206-217` + `mb\d.java` | setenforce 0 + chmod 677 /dev/input/* + 常驻 su 流 |

### 3.3 详细设计

#### 3.3.1 统一门面与模式选择

新建 Java 包 `com.autolua.engine.input.inject`：

```java
public enum InjectMode { DIRECT_ROOT, ROOT, SHIZUKU, ACCESSIBILITY, NONE }

public class InjectShellClient {
    // start() 选择优先级（对照 TouchShellClient.java:322-344）:
    // 1. Process.myUid()==0 且进程内反射可达 → DIRECT_ROOT（零 fork）
    // 2. Shizuku.isPermissionGranted() → SHIZUKU
    // 3. su 可执行 → ROOT(app_process)
    // 4. 无障碍服务已启用 → ACCESSIBILITY
    // 每类动作执行失败时：先试当前通道兜底 lambda，再降级（对照 :44-78）
}
```

#### 3.3.2 通道 A：无障碍手势（免 root 基础）

实现要点（对照按键 GestureQueueManager）：

```java
// 全局串行队列
private final Queue<GestureTask> queue = new ConcurrentLinkedQueue<>();
private volatile boolean executing = false;

// 同步派发
void dispatch(GestureDescription g, boolean wait) {
    GestureTask t = new GestureTask(g);
    queue.offer(t); pump();
    if (!wait) return;
    long timeout = 500 + Math.min(t.lastStrokeEndMs(),
                   GestureDescription.getMaxGestureDuration()) + 1000;
    t.latch.await(timeout, TimeUnit.MILLISECONDS);
}
// pump(): synchronized；dispatchGesture 返回 false → 立即 countDown + 递归泵下一条
//         服务断开 → 队列所有 latch 全部放行（快速失败）

// 手势构造：down/move 在客户端累积 Path，up 时一次派发整条（ya\a.java 模式）
//   down: path.reset(); path.moveTo(x,y)
//   move: path.lineTo(x,y); duration += max(dt, 10)
//   up:   duration += 10; stroke = new StrokeDescription(path, 0, duration)
// 最小时长保护: tap/swipe ≥50ms, pinch ≥100ms
// 多指同帧: API>=26 时多 StrokeDescription 加入同一 Builder（startTime 递增错峰）
// 随机偏移点击: 坐标 ±range/2 均匀随机（对照 c\b.java:236-253）
```

版本闸：每个手势入口 `if (Build.VERSION.SDK_INT >= 24)`（<24 直接返回 false，由降级链兜底）；懒人的 `GlobalActionAutomator` 临时 Looper 等待模式可作为无 Handler 场景备选（`I1I.java:323-328`：`Looper.prepare()` → dispatch → `Looper.loop()` 自旋 → 回调 `quit()`）。

辅助能力（对照按键 `bb\b.java`）：`performGlobalAction`（BACK=1/HOME=2/RECENTS=3/POWER_DIALOG=6）；文本输入走"焦点节点 + 剪贴板 PASTE(32768)"；节点点击向上递归找 clickable 祖先 `performAction(ACTION_CLICK=16)`。

#### 3.3.3 通道 B：root app_process 常驻（对标派大星 RootInputInjector）

**启动**（对照 TouchShellClient.java:151-183）：

```java
shell("pkill -f 'app_process.*AutoLuaInjector'");   // 防重复
Thread.sleep(100);
process = new ProcessBuilder("su", "-c",
    "CLASSPATH=" + apkPath + " exec app_process /system com.autolua.engine.input.inject.RootInputInjector service")
    .start();
Thread.sleep(300);
try { process.exitValue(); return false; }          // 立即退出 = 失败
catch (IllegalThreadStateException e) { connected = true; }   // 存活 = 成功
// apkPath: context.getPackageCodePath()，fallback 反射 ActivityThread.currentApplication
```

**行协议**（stdin/stdout，逐行 JSON 或空格分参）：

```
请求: <cmd> [args...]        cmd ∈ {down, move, up, tap, swipe, longtap, pinch, zoom,
                             multitouch, gesture, reset, config, ping}
响应: ok / err <msg>
// gesture: JSON 多笔划 [{points:[{x,y}], startTime, duration, delay}...]
// config: move_threshold=30
```

**进程内环境伪造**（UID=0 必需，对照 :881-924）：

```java
ActivityThread at = ActivityThread.newInstance();
ActivityThread.sCurrentActivityThread = at;
at.mSystemThread = true;
AppBindData data = AppBindData.newInstance();
data.appInfo = new ApplicationInfo();
data.appInfo.packageName = "com.android.shell";     // 伪装 shell
at.mBoundApplication = data;
Application app = Instrumentation.newApplication(Application.class,
    new FakeContext(systemContext));                // FakeContext: 包名 com.android.shell, uid=2000
at.mInitialApplication = app;
// API 31+: FakeContext 必须伪造 AttributionSource:
//   mb1.a(2000).setPackageName("com.android.shell").build()
```

**InputManager 三层 fallback**（:927-959）：

```
1. IInputManager.Stub.asInterface(ServiceManager.getService("input"))
2. ActivityThread.getSystemContext().getSystemService("input") 反射取
3. InputManager.getInstance()（新版本公开 API）
→ injectInputEvent(InputEvent, 0 /*INJECT_INPUT_EVENT_MODE_ASYNC*/)
```

**MotionEvent 组装**（对照懒人 iil1.java:97-143）：

```java
// 预分配 PointerProperties[10] / PointerCoords[10] 复用
// 多指 action: ACTION_POINTER_DOWN = 5 | (idx<<8)；ACTION_POINTER_UP = 6 | (idx<<8)
// source: InputDeviceCompat.SOURCE_TOUCHSCREEN(4098)
// pressure/size 随机扰动: nextFloat()*50+1（人化）
// downTime 全手势统一 SystemClock.uptimeMillis()
// 事件用后严格 recycle
```

**SELinux**：默认不动（派大星为可选项 `setenforceBeforeTouch`）；若注入被拒再降级执行 `setenforce 0`（按键硬性做法）。不做 magisk 补丁。

#### 3.3.4 通道 C：Shizuku（免 root 次优）

对照 `WrappedShizuku.java` / `UserService.java`：

1. 依赖：`dev.rikka.shizuku:api`（沿用派大星用法：`Shizuku.requestPermission(requestCode)`、`Shizuku.checkSelfPermission()`、pre-v11 拒绝）；
2. UserService：`Shizuku.UserServiceArgs(componentName).processName("autolua-shizuku").versioned(false)`；
3. 绑定后 `CountDownLatch.await(5000ms)` 等 binder；每个注入方法懒初始化"service==null 且已授权 → 先绑定再执行"；
4. UserService 进程（shell uid 2000）内执行与 3.3.3 完全相同的三层 InputManager fallback + `injectInputEvent` 反射（mode 0）；
5. 附带能力（可选实现）：shell 命令执行返回 `{code, stdout, stderr}`；
6. 运行时降级：ROOT 通道写流异常 → stopRootMode → 尝试 SHIZUKU；反之 DIRECT_ROOT 失败也试 SHIZUKU。

#### 3.3.5 通道 D：evdev 直写（root，性能上限）

对标 `libtouch_inject.so` 接口（`TouchInject.java`），autolua2 以 C++ 实现（挂到现有 `NativeInputManager`）：

```
nativeOpenDevice() → 枚举 /dev/input/eventX（读 /proc/bus/input/devices 匹配触摸屏 ABS_MT 位）
nativeOpenDevicePath(path)
nativeDown(slot) / nativeMove(slot) / nativeUp(slot)
nativeTap / nativeSwipe / nativeLongPress
nativeSendEvent(type, code, value)  // 原始 struct input_event
nativeSync()                        // EV_SYN
nativeScreenToPanelX/Y(x, y)        // 屏幕坐标→触摸面板坐标换算
nativeSetSampleDir(dir) / nativeSetSampleMode(mode) / nativeSampleAvailable()  // 人化轨迹采样
```

要点：Linux 多点触控协议（`ABS_MT_TRACKING_ID` slot 管理）、`write(fd, &input_event, sizeof)` + `EV_SYN` 提交；仅 root 进程可用（需 `chmod 677 /dev/input/*` 或 root uid 直写）；探针：`nativeOpenDevice()+nativeCloseDevice()` 成功才启用该 API。

#### 3.3.6 人化轨迹（与现有 NormalRandomNumber 集成）

| 参数 | 参考值 | 出处 |
|---|---|---|
| tap 按压时长 | 50ms 默认；懒人随机 50-60ms | RootInputInjector.java:539；iil1.java:111 |
| pressure/size 扰动 | nextFloat()*50+1 | iil1.java:111-114 |
| move 合并阈值 | move_threshold 默认 30px（可 config 下发） | RootInputInjector.java:35, 236-295 |
| move 插值 | `ceil(dist/threshold)` 步长线性插值，每步 sleep 15ms | RootInputInjector.java:216-295 |
| swipe | steps 均分 duration | RootInputInjector.java:461-530 |
| pinch/zoom | 20 步、300ms 默认 | RootInputInjector.java:367-368 |
| tap 随机落点 | ±range/2 均匀随机 | 按键 c\b.java:236-253 |

> 注：三家均无贝塞尔轨迹（全量 grep 证实）。如需更强拟人化，可在 autolua2 侧用 NormalRandomNumber 生成正态分布的时间/位置扰动叠加在上述线性插值上，属于增强而非对标。

### 3.4 版本适配分支（注入向）

| 分支 | 内容 | 依据 |
|---|---|---|
| API 24 | 无障碍手势体系起点；<24 仅剩 root/evdev/输入法通道 | c\b.java 类级 @RequiresApi(24) |
| API 26 | 多 StrokeDescription 同帧多指 | c\b.java:310 |
| API 31 | FakeContext 伪造 AttributionSource；无障碍 onMotionEvent 可观察真实触摸 | FakeContext.java:41-45 |
| API 34 | FGS specialUse（注入进程如需 FGS）；registerReceiver 加 RECEIVER_NOT_EXPORTED | AppForegroundService.java:210 |

### 3.5 验收标准

- [ ] 四通道各自单测：tap/swipe/longClick/pinch/multiTouch 五动作全通过
- [ ] 降级链：禁用 Shizuku 后 root 通道失败自动切无障碍，无用户干预
- [ ] 免 root（仅无障碍）：滑动轨迹与目标点偏差 ≤2px，连击 100 次 0 失败
- [ ] Shizuku：ADB 授权后注入成功率与 root 通道一致
- [ ] root：注入延迟 P95 < 5ms（对比无障碍手势 ~100ms）
- [ ] 事件流完整性：getevent 验证 down/move/up 序列与 pointer id 一致，无 orphan up

---

## 4. 方案三：无障碍 obtainXml 整树方案（P1）

### 4.1 目标

在无障碍服务侧把**整个窗口节点树一次序列化为 XML**，经跨进程传给脚本引擎，替代"逐节点跨进程遍历匹配"——一次 Binder 事务替代 N 次，大节点树获取快一个数量级。

### 4.2 参考实现索引

| 参考点 | 文件（相对 `lanren\sources\`） | 说明 |
|---|---|---|
| 序列化器 | `ILil\p028iILLL1\IL1Iii\l1Lll.java`（NodeDumpByObject，225 行） | XmlSerializer + 可见性剪枝 + NAF 标记 + 坐标缩放（:68-195） |
| 调用链 | `com\core\engine\Engine.java:157-160` → `ILil\p028iILLL1\IL1Iii\l.java:1496-1549` | obtainXml 入口、binder 死亡重启 + 模式重放、10s 超时 |
| FD 流式传输 | `ILil\p028iILLL1\I1I\IIi.java:630-642` | Bundle 携带 ParcelFileDescriptor 读出字符串，规避 Binder 1MB 限制 |
| XML 属性定义 | 同 l1Lll.java:95-144 | 与 uiautomator dump 同构 |

### 4.3 详细设计

#### 4.3.1 XML 格式（与 uiautomator dump 对齐，native/Lua 侧可直接复用解析器）

```xml
<hierarchy rotation="0">
  <node index="0" text="" resource-id="" class="android.widget.FrameLayout"
        package="com.example" content-desc="" checkable="false" checked="false"
        clickable="false" enabled="true" focusable="false" focused="false"
        scrollable="false" long-clickable="false" password="false" selected="false"
        visible-to-user="true" bounds="[0,0][1080,2400]" drawing-order="0" depth="0" empty="true">
    <node ... />
  </node>
</hierarchy>
```

#### 4.3.2 序列化规则（对照 l1Lll.java）

1. **属性集**：index/depth/text/resource-id/class/package/content-desc + 9 个 boolean 态 + bounds + bounds-in-parent + visible-to-user；`drawing-order` 仅 API≥24 取 `getDrawingOrder()`，否则写 0（:102-106）。
2. **可见性剪枝**：非 includeInvisible 模式下 `isVisibleToUser()==false` 的子树整体跳过（:68-76, 130-138）——序列化量大头。
3. **NAF 标记**（:99-101, 208-224）：节点 `clickable && enabled && text/desc 全空`，且子树无任何 text/desc（递归检查 :82-93），或类名 ∈ {GridView, GridLayout, ListView, TableLayout} 容器白名单 → 写 `empty="true"`。解析端可跳过这些死分支。
4. **坐标修正**（:146-195）：bounds 先 `intersect` 屏幕矩形裁剪；若存在虚拟屏，按 `targetW/curW`、`targetH/curH` 比例缩放所有坐标；旋转为奇数（90/270°）时交换宽高。
5. **输出**：`android.util.Xml.newSerializer()` + `ByteArrayOutputStream`；不要用 DOM。

#### 4.3.3 跨进程传输

```
无障碍服务进程                          脚本/引擎进程
 └ dump 整树 → XML bytes                 └ obtainXml(timeout=10000)
      → ParcelFileDescriptor.createPipe()      ← Binder: Bundle{status:int, pfd:PFD}
      → 写入 pipe                              → 从 pfd 读流（IIi.I1i 模式）
```

- **必须用 PFD 流式**：大节点树 XML 可超 Binder 1MB 事务缓冲（懒人实测路径 IIi.java:630-642）。
- 命令级超时 10s（Engine.java:412）；dump 方法 `synchronized` 防并发。
- 服务 binder 死亡：自动重启无障碍服务并**重放 setMode**（对照 `com\main\engine\UiSelNative.java:21-37` 的 `bNeedSetNodeMode`）。

#### 4.3.4 与节点模式联动

- `setMode(1)` = 运行时 `serviceInfo.flags |= FLAG_INCLUDE_NOT_IMPORTANT_VIEWS(0x2)` 再 dump（更全）；`setMode(0)` 还原连接时保存的原始 flags（对照 SelectToSpeakService.java:248-258）。
- 服务连接时基线 flags：`flags |= 0x72`（INCLUDE_NOT_IMPORTANT | REPORT_VIEW_IDS | RETRIEVE_INTERACTIVE_WINDOWS，参照派大星 onServiceConnected）。

#### 4.3.5 Lua 侧 API 设计

```lua
-- C++ 绑定（挂 com.autolua.engine 无障碍桥）
local tree = accessibility.dumpXml()          -- 返回字符串，含 rotation
local node = accessibility.findNode(tree, {   -- 引擎侧 C++ 解析，或提供 Lua table 化:
    id = "com.example:id/submit",
    text = "登录",
    clickable = true,
})
-- node.bounds / node.click(x, y)（中心点坐标点击，坐标已经过虚拟屏比例缩放）
```

> 节点查找可二选一：a) XML 传到脚本进程后 Lua/正则解析（灵活、慢一点）；b) 引擎 C++ 层解析匹配（快）。建议先 a 后 b。

### 4.4 验收标准

- [ ] 1080p 典型列表页整树 dump ≤ 50ms（对照逐节点遍历 500ms+）
- [ ] XML 体积 500KB+ 时跨进程不 OOM（PFD 路径生效）
- [ ] 虚拟屏场景坐标自动缩放正确（100% 命中目标）
- [ ] 杀无障碍服务后下一次 find 自动重启并恢复 dump 能力 ≤5s

---

## 5. 方案四：进程保活体系（P1）

### 5.1 目标

Android 14/15 上 `:script` 脚本进程与前台采集/注入服务长期存活；被杀后 ≤1s 自愈拉起。

### 5.2 参考实现索引

| 组件 | 参考（相对 `paidaxing\sources\`） | 要点 |
|---|---|---|
| 前台服务 | `com\auto\lua\service\AppForegroundService.java` | API34+ `startForeground(1001, n, 1073741824)`=SPECIAL_USE；PARTIAL_WAKE_LOCK；START_STICKY |
| JobScheduler | `com\auto\lua\keepalive\KeepAliveJobService.java` | 持久 job（15min 周期+5min 弹性+网络连通）+ **即时 job**（`setMinimumLatency(0).setOverrideDeadline(1000)`，jobId=`currentTimeMillis()%10000+500` 防重）+ AlarmManager 兜底 |
| ContentProvider 拉活 | `com\auto\lua\keepalive\KeepAliveContentProvider.java` | 空实现，进程启动即被 PMS 拉起 |
| 账号同步拉活 | `com\auto\lua\keepalive\KeepAliveSyncService.java` | SyncAdapter `ContentResolver.requestSync` 触发 |
| 无障碍心跳 | `com\auto\lua\core\accessibility\AutoLuaAccessibilityService.java:44-66,333-355,397-404,513-522` | 10s 心跳查前台服务存活→拉活+重排 Job；onDestroy→紧急报警五连发 |
| Boot | `com\auto\lua\receiver\BootReceiver.java:36-53` | BOOT_COMPLETED + 开关 |
| 厂商引导 | `com\auto\lua\ui\settings\KeepAliveGuideActivity.java:166-226` | `isIgnoringBatteryOptimizations` 检测 + 小米/红米/POCO 识别跳转 |
| 懒人引擎进程 | `lanren\sources\com\main\engine\Main.java:34-83` | app_process 独立进程 + `Looper.prepareMainLooper()`，天然规避 AMS 查杀（root 场景最强保活） |

### 5.3 详细设计

#### 5.3.1 组件清单与触发矩阵

```
AppForegroundService (FGS, specialUse + PARTIAL_WAKE_LOCK, START_STICKY)
   ↑ 拉活来源:
   ├─ 无障碍 10s 心跳（service 存活期间最可靠的看护者）
   ├─ KeepAliveJobService.onFinishJob（无论成败都重排 + 立即 job）
   ├─ AlarmManager setExactAndAllowWhileIdle（+1s 梯度五连发 + setRepeating(60s)）
   ├─ KeepAliveContentProvider.onCreate（任意进程启动即拉）
   ├─ KeepAliveSyncService（账号同步回调）
   └─ BootReceiver（开机）
   └─ 自身 onDestroy / onTaskRemoved → executeEmergencyKeepAlive()
```

#### 5.3.2 Manifest 要求

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"/>
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/>
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>
<!-- :script 进程的服务同样声明 specialUse 并在 onCreate 即 startForeground -->
```

#### 5.3.3 Android 14/15 适配硬性项

1. `startForeground(id, notification, 1073741824 /*FOREGROUND_SERVICE_TYPE_SPECIAL_USE*/)`（API34+，三处：主服务/脚本服务/采集服务）；
2. 动态 receiver：`registerReceiver(receiver, filter, 4 /*RECEIVER_NOT_EXPORTED*/)`（API34+）；
3. PendingIntent 一律 `FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE`（数值 201326592）；
4. `POST_NOTIFICATIONS` 运行时权限（API33+，否则前台通知不可见但不影响保活）；
5. 剪贴板前后台访问限制（API29+）相关 API 同步适配。

#### 5.3.4 root 增强项（可选）

root 可用时，引擎以 app_process 独立进程运行（同方案二 3.3.3 命令），并按按键模式释放常驻守护（`setenforce 0` 可选 + `chmod 677 /dev/input/*`）——该进程不受 AMS 生命周期管理，是最强保活。

### 5.4 验收标准

- [ ] Android 14 实测：adb am kill 全部进程后 ≤1s 内 FGS 自动恢复
- [ ] 用户force-stop 除外（系统行为，不要求）
- [ ] 无障碍开关保持 24h 脚本进程不死
- [ ] 小米 HyperOS 上按引导页设置后，后台 12h 存活

---

## 6. 方案五：模拟输入法通道（P2）

### 6.1 目标

免 root、免无障碍的**文本注入**通道：自研 IME 接收广播后经 `InputConnection` 写入文本/键码，适用于密码框、游戏聊天框等无障碍 PASTE 失效场景。

### 6.2 参考实现索引

| 参考点 | 文件（相对 `paidaxing\sources\`） | 说明 |
|---|---|---|
| IME 服务 | `com\auto\lua\inputmethod\FireflyInputMethodService.java` | 广播触发 commitText/sendKeyEvent/deleteSurroundingText |
| 抢默认输入法 | 同文件 `:107-115` | `Settings.Secure.putString(..., "default_input_method", <pkg>/<cls>)` |
| 动态 receiver | 同文件 `:135-139` | API34+ `registerReceiver(..., RECEIVER_NOT_EXPORTED)` |

### 6.3 详细设计

1. 新建 `com.autolua.inputmethod.AutoLuaInputMethodService extends InputMethodService`，Manifest 声明标准 IME service + `BIND_INPUT_METHOD` 权限 + `<intent-filter><action android:name="android.view.InputMethod"/></intent-filter>`；
2. 注册动作广播（RECEIVER_NOT_EXPORTED）：
   - `com.autolua.action.INPUT_TEXT` → `getCurrentInputConnection().commitText(text, 1)`
   - `com.autolua.action.INPUT_KEYCODE` → `sendKeyEvent(KeyCharacterMap/KeyEvent)` 或 `inputConnection.sendKeyEvent`
   - `com.autolua.action.CLEAR_TEXT` → `deleteSurroundingText`
   - `com.autolua.action.HIDE_IME` → `requestHideSelf(0)`
3. 启用方式二选一：引导用户系统设置启用；或 root 下 `Settings.Secure.putString` 直接切换（用完切回，记录原值）；
4. Lua API：`ime.inputText(s)` / `ime.sendKey(keycode)` / `ime.clearText()` / `ime.hide()`——经 IME 通道时与剪贴板注入（方案二 3.3.2 辅助能力）互为降级。

### 6.4 验收标准

- [ ] 无障碍关闭场景下，文本注入到微信/浏览器输入框成功
- [ ] 切回原输入法无残留（root 直切模式）

---

## 7. 方案六：IDE 调试 WebSocket 协议（P2）

### 7.1 目标

基于 autolua2 已有 libhv，实现 VSCode/IDE 插件联调通道：远程执行 Lua、推文件、热修、图色远程计算、日志与截图回传。

### 7.2 参考实现索引

| 参考点 | 文件（相对 `paidaxing\sources\`） | 说明 |
|---|---|---|
| WS 服务器 | `com\auto\lua\plugin\AutoLuaWebSocketServer.java` | 端口 8080，占用时回退 {8081..8085}；onOpen 回 hello（clientId/device_name/version）；30s 心跳；断连可选停止脚本 |
| 消息中枢 | `com\auto\lua\plugin\WebSocketApi.java`（1 万+ 行，重点看 handleMessage） | JSON `{type,data}` 分发；消息路由器兜底 |
| 沙箱执行 | 同文件 `:10070-10192`（executeLuaModule） | `_WS_PARAMS/_WS_OPTIONS/_WS_RESULT` 全局变量约定；root 模式落临时文件 `cache/ws_tmp/ws_module_<nanoTime>.lua` 再 executeFile |
| 图像分片回传 | `_assets\paidaxing\assets\Lua\utils\print_wrapper.lua:28-48` | `[IMG]S|w|h|totalChunks` / `[IMG]D|i|base64(12KB块)` / `[IMG]E`；AIDL `_sendOutputLine` 多进程回流 |
| 图色远程命令 | handleMessage 分发表 | find_image / find_multi_color / cmp_color / ocr / dot_ocr（手机端 opencv 代算，结果 JSON 回传） |

### 7.3 协议规范（autolua2 实现要求）

```
传输: WebSocket (libhv)，默认端口 8080，回退 8081-8085
连接: server → client {"type":"hello","data":{"clientId","device_name","app_version","app_version_code"}}
心跳: 30s ping/pong
消息: {"type": "<cmd>", "data": {...}}；未识别 type 忽略并回 {"type":"error","data":{"msg":"unknown type"}}

命令集（一期）:
  execute            {code}                       → 脚本进程沙箱执行，回 {"type":"result","data":_WS_RESULT}
  stop_script / pause_script / resume_script      → 生命周期控制
  push_files_start / push_file {path, content} / push_files_end   → 工程同步
  push_zip(_start|_chunk|_complete)               → 大工程分块推送
  get_file_md5_list                               → 增量同步基线
  delete_files {paths}
  find_image / cmp_color / find_multi_color / ocr → 沙箱执行 assets 中对应 Lua 模块
                                                    （_WS_PARAMS 传参、_WS_RESULT 取结果，错误前缀 __ERROR:）

日志通道: 脚本 print → ws text frame；图片: [IMG] 协议分片（base64 PNG，12KB/块，
         Binder parcel >60KB 偶发失败是分块依据，参考 print_wrapper.lua 注释）
```

沙箱执行模板（对照 executeLuaModule）：

```java
engine.pushString(paramsJson);  engine.setGlobal("_WS_PARAMS");
engine.executeScript("_WS_RESULT = nil\n" + moduleLuaCode);
String result = engine.getGlobalString("_WS_RESULT");   // 错误格式 "__ERROR:<msg>"
```

### 7.4 验收标准

- [ ] VSCode 插件（或 curl + wscat）可执行 `execute` 并取回 `_WS_RESULT`
- [ ] 1MB 工程经 push_zip 分块推送后 md5 校验一致
- [ ] 手机端 ocr/find_image 远程调用 ≤200ms（1080p 单目标）
- [ ] [IMG] 分片重组后的 PNG 可正常解码（>1MB 截图验证）

---

## 8. Android 版本适配总表

集成时按下表建立统一 `Compat` 工具类（建议 `com.autolua.engine.compat.Compat`），三 APK 实测分支汇总：

| API | 变化 | 对策 | 依据 |
|---|---|---|---|
| 21 | getWindows 多窗口 / MediaProjection | <21 退化单根；投影 API 最低 21 | 按键 Cservice.java:403; ForScreenShotActivity.java:22 |
| 17 | getRealMetrics 真实分辨率 | <17 反射 | 按键 nd\c.java:25-41 |
| 23 | Settings.System.canWrite、isEditable | <23 视为已授权 | 按键 Injector.java:706; eb\a.java:106 |
| 24 | **dispatchGesture 手势体系**、getDrawingOrder、disableSelf | 手势入口硬闸 <24 返回 false；dump 加 drawing-order | c\b.java 类级；l1Lll.java:102 |
| 26 | **多指同帧 StrokeDescription**、startForegroundService | 多指分支 ≥26；后台启动服务统一走 startForegroundService 包装 | c\b.java:310; 懒人 l.java:2663 |
| 29 | ContentResolver.call 签名、FGS mediaProjection 类型、剪贴板后台限制 | 两条 call 分支；声明 FGS type；Clipboard 判 29 | 懒人 RunnableC01041.java:984-988; 派大星 ClipboardApi.java:36 |
| 30 | MANAGE_EXTERNAL_STORAGE、ACTION_IME_ENTER、takeScreenshot | 存储权限分支；节点动作 <30 return false | 派大星 PermissionActivity.java:182; UiObject.java:509 |
| 31 | **AttributionSource**（root 伪造必需）、onMotionEvent 开放、后台弹窗引导 | FakeContext 伪造；onMotionEvent 观察真实触摸 | FakeContext.java:41-45; AutoLuaAccessibilityService.java:552 |
| 32 | 节点 ACTION_DRAG_START/DROP/CANCEL | <32 return false | UiObject.java:315 |
| 33 | POST_NOTIFICATIONS 运行时权限、SHOW_TEXT_SUGGESTIONS | 运行时申请；<33 return false | DeviceInfoFragment.java:293; UiObject.java:820 |
| 34 | **FGS 权限+类型强制**、RECEIVER_NOT_EXPORTED、28 行为细节 | `startForeground(id, n, SPECIAL_USE)`；registerReceiver 4 参 | AppForegroundService.java:210; FireflyInputMethodService.java:135-139 |
| 35 | MediaProjection 每会话重授权、拒绝 RGB_565 | 600ms 令牌重试一次；ImageFormat 强制 RGBA | 派大星 :954-958; 按键 ScreenShoterV3.java:459-463 |

通用硬规则：PendingIntent 一律 `FLAG_UPDATE_CURRENT|FLAG_IMMUTABLE`（201326592）；`getprop phone.id` 为空或 API35 时截图格式用 RGBA_8888。

---

## 9. 性能优化清单

集成时逐条对照落实（每条注明出处 APK 与文件）：

**截图热路径**
1. `acquireLatestImage` 拉模式只取最新帧（三家通用）
2. Image 拷入自管 ByteBuffer 后立即 close，最短占用 maxImages 池（懒人 `LIl.java:23-39`）
3. stride 快路径整块拷贝；慢路径扩宽 Bitmap 一次 `copyPixelsFromBuffer`（懒人 `LIl.java:96-98`）
4. ThreadLocal 缓冲复用，旋转重建时 `remove()`（派大星 CaptureScreenService.java:72,751）
5. 截图线程优先级 `-4`（URGENT_DISPLAY）+ `SystemClock.sleep(max(1, interval-耗时))` 精确节拍（派大星 :975,640-642）
6. A/B 共享内存 + fd dup + mmap 替代 Binder 传图（派大星 SharedMemoryManager）
7. 高频路径日志降采样（前 3 条或 %50==0；派大星 :537, :234-236）
8. 跨进程查询结果缓存（懒人 `Engine.java:35-47` mCacheStr/mCacheLong + clearCache 生命周期清理）

**注入热路径**
9. 预分配 `PointerProperties[10]/PointerCoords[10]` 复用 + MotionEvent 严格 recycle（懒人 iil1.java:38-57）
10. move_threshold 小位移吞掉（事件稀疏化）+ 多指 startTime 错峰（派大星 :236-295; 按键 c\b.java:340）
11. 常驻通道复用：su 流一次握手、app_process stdin 行协议整会话、DIRECT_ROOT 零 fork（按键 mb\d.java:134-166; 派大星 TouchShellClient.java:107-127）
12. HTTP 类 Lua 调用加 Future.get(15s) 超时防永久挂起（懒人 LuaEngine.httpPostDataEx :105-147）

**进程与 IPC**
13. 脚本引擎独立进程（`:script`），崩溃不伤主进程；输出经 Binder 回调回流（派大星 IScriptService）
14. Binder 死亡自愈：`asBinder().isBinderAlive()` + 惰性重连 + 模式重放（懒人 RunnableC01041.java:496-519; UiSelNative.java:21-37）
15. obtainXml / 截图等大数据传输一律 PFD/共享内存，禁止 Binder 大数组
16. UI 线程隔离：主线程只做 Handler 投递；`untilFind` 主线程直接抛异常防 ANR（派大星 UiSelector.java:180-195）

---

## 10. 网络接口参考清单

设计 autolua2 自有云服务（脚本市场/授权/热修）时可参考的行业实现：

**按键精灵**
- 脚本市场/论坛：`http://app.anjian.com/api/{Login, BBSList, BBSInfo, CommandList, DownloadCount, NewVersion, AddFavorite, FavoriteList, Posted, ImgCode, GetPassword, DevCommConfig, GameInfo, GetH5GameInfo}`
- 用户/云端：`http://api2.mobileanjian.com/AjApp/{AjLogin, InitData, AJSchool, AliCloudGetStorageToken, GetAntiDetectionPackage, UserShareScript, UserShareScriptDetail}`
- 授权：`auth2.mobileanjian.com`（Elfin RSA 体系 + BoxEngine.lic）；OSS STS：`http://api4.mobileanjian.com/api/GetSHStoragetToken?Data=cloud`（阿里云 OSS bucket `manjian-pan`，STS 临时凭证，响应 hex 用自定义字母表 `"UYMJOLWNTRABCDEF"` 混淆）
- 其他子域：order / debug / cloudphoneapi / renzheng

**派大星（云加密打包体系，商业脚本保护范本）**
- `http://shuangyu.icu/api/v3/{auth/login, auth/refresh, auth/profile}` — 账号体系
- `POST /api/v3/verify-startup` — body `{apk_signature, encrypted_aes_key}`，服务器返回 64 位 hex AES 密钥（绑定 APK 签名 SHA-256）
- `POST /api/v3/encrypt-file` / `package-secure` — 加密脚本/打包
- 脚本包格式 `.fire`：`[0..3]` 魔数 "FIRE" → `[8..15]` 大端 aes_data_length → `[64..64+len)` 附加数据区 → 末尾 512 字节 RSA 加密 AES 密钥

**懒人精灵**：明文域名为零（字符串全部运行时解密），如需还原可解 `ILil.IL1Iii.IL1Iii(byte[], key)` XOR 解密函数后重扫。

---

## 11. 附录：反编译关键文件索引

集成 Agent 遇到细节疑问时按此索引直接查源码：

**派大星 `paidaxing\sources\`（质量最高，首选参考）**
- 三进程/AIDL：`com\auto\lua\shell\IScriptService.java`（13 方法）、`com\auto\lua\shell\root\ILuaRootService.java`（27 事务码）、`com\auto\lua\scriptruntime\engine\LuaRuntimeEngine.java`（进程枚举/FFI 注入）
- 无障碍：`com\auto\lua\core\accessibility\AutoLuaAccessibilityService.java`、`com\auto\lua\core\automator\UiSelector.java / UiObject.java / UiGlobalSelector.java`
- 截图：`com\auto\lua\core\images\CaptureScreen\CaptureScreenService.java`、`com\auto\lua\shell\root\RootScreenshotService.java`、`com\auto\lua\core\images\screenshot\sharedmem\SharedMemoryManager.java`、`com\auto\lua\shell\root\Workarounds.java`
- 注入：`com\auto\lua\shell\TouchShellClient.java`、`com\auto\lua\shell\root\RootInputInjector.java`、`com\auto\lua\core\shizuku\WrappedShizuku.java` + `UserService.java`、`com\auto\lua\TouchInject.java`
- 保活：`com\auto\lua\keepalive\*`、`com\auto\lua\service\AppForegroundService.java`、`com\auto\lua\receiver\BootReceiver.java`
- IME：`com\auto\lua\inputmethod\FireflyInputMethodService.java`
- Lua API 面：`com\auto\lua\scriptruntime\engine\bridge\ApiBridge.java`（34 个 API 组注册）、`org\keplerproject\luajava\*`
- 明文 Lua 参考：`_assets\paidaxing\assets\Lua\ws\*.lua`（图色封装）、`_assets\paidaxing\assets\Lua\utils\print_wrapper.lua`（IDE 协议）

**懒人 `lanren\sources\`**
- JNI 接口层：`com\core\engine\Engine.java`（native 方法全集 + sendEvent 事件码表）
- 截图：`ILil\p028iILLL1\IL1Iii\C1.java`（ScreenRecEx）、`iI1LI.java`（ScrcpyNativeEx）、`LIl.java`（ImageWrapper）、`C0103LLlI1.java`（DisplayManager 反射）
- 无障碍 XML：`ILil\p028iILLL1\IL1Iii\l1Lll.java`、`ILil\p023L11I\I1I\IL1Iii\p025il\IL.java`（DFS）、`ILil\p023L11I\I1I\IL1Iii\I1I.java`（GlobalActionAutomator）
- 注入：`ILil\p028iILLL1\IL1Iii\iil1.java`（TouchEngine）、`iIlL1.java`（Legacy/Instrumentation）
- 引擎进程：`com\main\engine\Main.java`（app_process 入口）

**按键 `anjian\sources\`**
- 无障碍：`com\cyjh\event\accessibility\Cservice.java`、`c\b.java`（GestureQueueManager）、`ya\a.java`（TouchObj）、`bb\b.java`（实现）、`v\b.java`（控制器）
- 注入门面：`com\cyjh\event\Injector.java`（native→Java 反向 API 全集）
- 截图：`com\cyjh\mobileanjian\screencap\ScreenShoterV3.java`、`nd\b.java`（ImageProcessor）、`nd\c.java`（分辨率）
- root 引擎：`vd\a.java`（GeneralMqRunner）、`mb\d.java`（RootShell）、`od\a.java`（守护进程常量）
- 资源目录：`a4\s.java`（PathUtil：`/sdcard/MobileAnJian/{Plugin,Script,Find/Mqe,UIConfig,compile.lc}`）

**配置文件**
- 无障碍配置：`lanren\resources\res\xml\lr.xml`、`anjian\resources\res\xml\accessibility_reply.xml`、`paidaxing\resources\res\xml\accessibility_service_config.xml`
- Manifest：各 `resources\AndroidManifest.xml`（FGS 类型/权限声明对照）

---

## 附：实施顺序建议

```
Sprint 1 (P0): 方案一截图管线 ──┬── 可独立并行 ── 方案二注入降级链
Sprint 2 (P1): 方案三 obtainXml ──┬── 可独立并行 ── 方案四保活
Sprint 3 (P2): 方案五 IME ──┬── 方案六 WebSocket（依赖方案一/二的 API 稳定）
每方案交付物: 代码 + 单元测试 + 真机验收记录（对应 3.x.5 验收标准清单）
```

---

## 9. 方案八：远程控制与投屏（P1）

> 用户核心诉求：**PC 浏览器零安装**，经 WebSocket 统一通道实现——远程启动脚本、远程传输数据（已有）、远程操控（投屏+触控回传）、**app 进程死后仍可远程唤醒**。批量执行多设备时支持降帧率控带宽。

### 9.1 需求与现状缺口

| 需求 | 现状 | 缺口 |
|---|---|---|
| WS 连接设备 | ✅ IdeWebSocketServer 8080-8085 | 无 |
| 远程传输数据 | ✅ push_files/push_zip/plugin_push/md5/delete | 无 |
| 远程启动脚本 | ⚠️ execute=沙箱短代码 | 缺 `run_script` 走主引擎跑工程脚本 |
| 远程操控（投屏） | ❌ | 全新：画面回传 + 触控注入回传 |
| 进程死后远程唤醒 | ❌ WS 服务器随进程同生死 | 双保险唤醒（见 9.6） |

### 9.2 投屏技术选型（双 codec 共存）

| 维度 | JPEG（保底 codec） | H.264（主力 codec） |
|---|---|---|
| 压缩原理 | 帧内压缩，每帧全量 | 帧间压缩（I/P 帧，静止画面近零成本） |
| 带宽 @1080p | 静止 ~80KB/帧、变化 ~200KB/帧，10fps≈8-24Mbps | 码率独立可控 0.5-3Mbps @任意帧率 |
| 编码开销 | CPU 软编 15-30ms/帧，上限 10-20fps | MediaCodec 硬编 <5% CPU，30-60fps |
| PC 解码 | canvas 直绘，全兼容 | WebCodecs（Chrome/Edge 94+），需 NALU/AVCC 处理与 I 帧同步 |
| 可靠性 | 帧独立，丢帧无影响 | 流式状态机，中断后需等 I 帧 |
| 工作量 | 1x | 3-4x |

**决策**：双 codec 并存，`stream_start.codec` 字段选择；**一期两个都做**（H.264 为主力、JPEG 为低配浏览器保底）；批量多设备场景靠三参数码控（fps/bitrate/scale）压带宽——例如 20 台批量：`fps=5, bitrate=500k, scale=0.5` ≈ 单路 60KB/s，合计 ~1.2MB/s。

### 9.3 设备端设计：ScreenStreamController

新建 `engine\src\main\java\com\autolua\engine\display\stream\ScreenStreamController.java`（单例）：

1. **帧源**：复用截图管线——`FrameBuffer.getLatestFrame(freshOnly=true)` 读共享内存（seqlock 读协议不变）；App 模式需投影授权（CaptureScreenService.startCapture 流程已就绪），root 模式免授权
2. **JPEG 路径**：RGBA byte[] → `Bitmap.copyPixelsFromBuffer` → 可选 scale（Matrix 缩放）→ `Bitmap.compress(JPEG, quality, baos)` → WS binary 帧
3. **H.264 路径**：
   - `MediaCodec.createEncoderByType("video/avc")`：COLOR_FormatSurface + `KEY_FRAME_RATE/KEY_BIT_RATE/KEY_I_FRAME_INTERVAL`（I 帧 interval=2s，断连恢复快）
   - 帧输入：Bitmap →（canvas 绘制到 encoder InputSurface）→ 取 output NALU（Annex-B）
   - **送帧节拍控制**：取帧线程 `sleep(1000/fps)`——喂多快编多快；静止画面跳帧（帧计数器与上次相同则不送）
   - 配置集（SPS/PPS）在流开始时作为首帧下发，WebCodecs 端 configure 用
4. **会话管理**：每 WS 连接独立订阅（多 PC 同看一台）；`stream_stop` 或连接断开即释放编码器（编码器单实例时最后一个订阅者离开才释放；一期可简化为单订阅）
5. **旋转**：onConfigurationChanged → 发 JSON `{"type":"stream_rotate","data":{w,h}}` + 重启编码器（等下一个 I 帧）

### 9.4 操控回传命令

| 命令 | data | 实现 |
|---|---|---|
| `inject_touch` | `{action:"down/move/up", x, y}` | InjectShellClient 四通道降级链（一期已交付，root 直写 ~10ms） |
| `inject_swipe` | `{x1,y1,x2,y2,duration}` | 同上（Humanizer 轨迹已内置） |
| `inject_key` | `{keycode}` | InjectorCore keycode 注入 |
| `inject_text` | `{text}` | ImeApi.sendText（root 切 IME）失败则 InjectorCore 文本逐字 |
| 坐标映射 | — | PC 端按 canvas 显示尺寸/设备真实分辨率等比换算；stream 开始时下发真实 w/h |

前置：`InjectShellClient.start()` 在第一次 inject_* 时懒启动。

### 9.5 远程启动脚本（主引擎通道）

1. 新命令 `run_script {path}`（相对 ROOT_DIR，如 `/main.lua` 或 `/demo/main.lua`）：
   - 走**主引擎**（完整环境：root 进程注入服务 + C++ 图色绑定），非沙箱
   - 实现：`IdeServerManager` 新增 `ScriptRunDelegate`（同 LifecycleDelegate 注入模式），接口 `boolean runScript(String absPath)`；MainApp 接线实现为 `AutoLuaEngineProxy.instance.execute("loadfile('<absPath>')()")`（对齐 app 现有 startTask 的 `loadfile('init.lua')() loadfile('main.lua')()` 模式，path 由设备端拼接 ROOT_DIR）
   - 回包：提交成功 `{"type":"script_started"}`（主引擎异步执行）；执行日志经主引擎输出回调 → `log` 帧回传（探索主引擎 print 回调挂点：DataChannel/DebugService 输出，一期可先只回"已提交"+沙箱 print 兜底）
2. `stop_script` 已有（LifecycleDelegate → AutoLuaEngineProxy.interrupt）——`run_script` 后 stop/pause/resume 全链路语义完整

### 9.6 进程守护与远程唤醒（双保险）

| 场景 | 覆盖 | 机制 |
|---|---|---|
| app 崩溃/被系统杀/上滑清理 | ✅ 秒级 | **无障碍自拉活**：`AutoLuaAccessibilityService.onServiceConnected()` 补拉起 KeepAliveManager.start + IdeServerManager.start（system_server 自动重建无障碍服务进程，顺带复活全 app） |
| 任意被杀（root 设备） | ✅ 毫秒级 | **root daemon 敲门**：常驻 app_process 监听 9090（复用/扩展 `RootInputInjectorMain` 框架），PC 连 9090 发 `wake` → daemon 执行 `am start -n com.autolua.autolua2/.activity.SplashActivity` + 通知 app 重建注入环境 |
| 设备重启 | ✅ | BootReceiver（已有）+ 无障碍自动恢复 |
| 用户 force-stop（设置强停） | ❌ 系统级封禁 | Android 底线无解（商业引擎同），产品规避 |

- **PC 端自动重连**：remote.html 指数退避重连（1s→2s→4s→…→15s 封顶），覆盖"复活窗口期"——连不上 8080 时顺带探测 9090 发 wake（root 场景）
- daemon 常驻形式：`su -c app_process` 拉起后 nohup 化，watchdog 自守（检测自身端口掉线则自杀重生——由 su 会话循环包裹）

### 9.7 PC 端 remote.html（单文件，零依赖）

新建 `autolua2\tools\remote.html`（纯原生 JS，浏览器直接打开）：

- **连接栏**：ws:// 地址输入 + 连接/断开 + 状态灯 + 指数退避自动重连 + 9090 wake 探测
- **主区**：投屏 canvas（JPEG→drawImage；H.264→WebCodecs VideoDecoder 硬解→VideoFrame 绘制）；鼠标 down/move/up → 设备坐标（等比换算）→ inject_touch；双击/拖拽映射；键盘输入框 → inject_text；滚轮 → inject_swipe
- **侧栏**：
  - 设备面板：capabilities 解析展示（注入模式/插件/图色能力）
  - 脚本面板：本地 zip 拖拽 → push_zip 三段推送 → 文件树（get_file_md5_list）→ run_script/stop/pause/resume 按钮
  - 控制台：log 帧 + image 帧（[IMG]）渲染
  - 投屏控制：codec 切换（h264/jpeg）、预设（单机调试 30fps/3M/1.0x、单机常用 15fps/1.5M/1.0x、批量模式 5fps/500k/0.5x）
- 多设备：多标签页各连一台（批量面板做二期）

### 9.8 WS 协议扩展汇总（WsProtocol 常量）

```
stream_start {codec:"h264"|"jpeg", fps, bitrate(h264), quality(jpeg), scale} → {"type":"stream_started","data":{w,h}}
stream_stop {}
stream_rotate {w,h}（设备→PC 事件）
binary 帧：h264 → [4B seq][4B flag:0=SPS/PPS 配置集 1=视频帧][4B len][payload]
           jpeg → [4B seq][4B w][4B h][4B len][jpeg]
inject_touch / inject_swipe / inject_key / inject_text → {"type":"inject_result","data":{ok}}
run_script {path} → {"type":"script_started","data":{path}}
wake（daemon 9090 端口，文本行协议，非 WS）
capabilities 扩展："stream":{"h264":bool,"jpeg":bool},"inject":{"mode":"DIRECT_ROOT|..."},"run_script":true
```

### 9.9 实施分期与验收标准

**一期（本次）**：9.3 全部 + 9.4 全部 + 9.5 + 9.6 无障碍自拉活 + 9.7 remote.html + 9.8
**二期（可选）**：root daemon（9.6 第二保险）、批量设备面板、剪贴板同步、H.264 硬件滤镜

验收 checklist：
1. 浏览器连 ws://设备:8080 → stream_start h264 → 画面 30fps 流畅；切 jpeg 正常降级
2. 批量参数 stream_start(5fps/500k/0.5x) → 单路带宽 ≤80KB/s
3. 鼠标点击投屏画面 → 设备对应位置响应（root 模式 DIRECT_ROOT 通道）
4. 拖入工程 zip → run_script → 主引擎执行 → stop_script 可停
5. `adb shell am force-stop` 之外的 kill（`am kill`）→ 设备端无障碍 10s 内自拉活 → PC 自动重连恢复投屏
6. 旋转设备 → stream_rotate + 画面重建

### 9.10 参考实现索引

- MediaProjection→MediaCodec H264 标准管线：`D:\tools\reverse\decompiled\anjian\sources\com\cyjh\mobileanjian\` grep "MediaCodec|createInputSurface"（按键投屏/录屏实现）
- JPEG 帧化推流参考：派大星 WebSocketApi 的 [IMG] 分片（本项目一期已演进为 binary 帧）
- app_process 常驻 daemon：本项目 `engine\...\input\inject\RootInputInjectorMain.java`（一期 root 注入框架）扩展
- WebCodecs：MDN VideoDecoder API（Chrome 94+）

### 9.11 调试通道整合决策（2026-09-12，用户拍板）

**结论：保留 8080 WebSocket IDE 为唯一远程调试通道，退役 8176 老调试通道（MLN Kit），独有能力迁移。**

| 老通道能力（8176，MLN Kit） | 处置 | 去向 |
|---|---|---|
| 推工程（HTTP POST） | 退役 | 新通道 `push_files/push_zip` 已覆盖 |
| Lua 断点调试（KIT↔WORKER 中转） | 退役 | 二期在新协议加 `debug_attach/breakpoint/step/stack`（对接 LuaInterpreter debug hook）；过渡期用 `execute` 即时执行替代 |
| UI 热重载（DebugActivity） | **保留，触发方式迁移** | 新 WS 命令 `ui_debug_start/ui_debug_stop`（UiDebugDelegate，MainApp 接线），DebugActivity 本体保留 |
| UDP 设备发现（BroadcastLocationServer） | **保留，挂到新通道** | `DiscoveryManager`（app 层）包装，随 `IdeServerManager.start()` 启停，UDP 端口与 WS 一致（8080），开关入 Preferences |

**实施动作**：
1. `DebugFragment` 改造为远程调试面板：IDE 服务状态（端口/在线客户端）、本机 IP、UDP 发现开关、投屏快捷开关；移除调试服务开关/Token/端口输入
2. Manifest 摘除 `.debug.DebugServiceImp`（代码文件保留不删，git 可追溯，需要对接 MLN Kit 时可一键恢复）
3. `engineService.startDebugger()` 不再随服务调用（C++ debug hook 关闭，root 进程省资源；二期断点调试时经新命令重开）
4. `WsProtocol` 追加 `ui_debug_start/ui_debug_stop` 命令；capabilities 无需变化
5. `remote.html`：连接地址记忆（localStorage）；设备发现依赖设备端面板显示的本机 IP（浏览器无法发 UDP，扫描功能不做于网页端）




