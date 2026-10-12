# 插件体系域内契约

> 文档版本 v1.0（2026-09-18，文档三层化重组新建）。
> 本域权威范围：plugins 域内的机器级契约摘要与对码落点；包格式、manifest 字段全表、sha256 算法、ABI、so 交付、生命周期、接口完整签名、WS 命令、Lua 口与三种本地安装的**平台契约权威在 [../../04-插件包规范.md](../../04-插件包规范.md)**（下文逐条注明对应 §）。所有对码标注均经 Read/Grep 核实，行号为当前工作区实际行号。

## 1. PluginMeta 字段 → Java 字段对码表

manifest.json 由 gson 反序列化为 `PluginMeta`（字段定义权威 = [04-插件包规范.md](../../04-插件包规范.md) §2）：

| manifest 字段 | PluginMeta Java 字段 | 必填 | 说明与对码 |
|---|---|---|---|
| `id` | `public String id` | ✅ | 白名单 `[A-Za-z0-9._-]` 且不含 `..`（防路径穿越）（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginMeta.java:15`；校验落点 `PluginManager.java:1081`） |
| `name` | `public String name` |  | 显示名，宿主不消费（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginMeta.java:17`） |
| `version`（别名 `versionName`） | `@SerializedName(value="version", alternate={"versionName"}) public String version` | ✅ | 作为安装版本目录名；同 id 多版本按 `versionCompare` 数值比较取最高（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginMeta.java:19`；比较实现 `PluginManager.java:1090`） |
| `mainClass`（别名 `main`） | `@SerializedName(value="mainClass", alternate={"main"}) public String mainClass` | ✅ | 必须实现 `IPlugin`，反射实例化（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginMeta.java:22`；强转校验 `PluginManager.java:940`） |
| `provides` | `public String[] provides` | 建议 | 能力标签；`provides(String tag)` 辅助判断（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginMeta.java:25`、`:35`） |
| `minHostVersion` | `public String minHostVersion` |  | 预留未校验（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginMeta.java:27`） |
| `sha256` | `public String sha256` | ✅ | 内容清单 hash（算法权威 = 04 §3）；安装强校验，缺失即拒装（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginMeta.java:29`；校验 `PluginManager.java:892-899`） |
| `signature` | `public String signature` |  | 内容清单 sha256 的 ECDSA P-256 签名（base64 DER）；宿主注入公钥后必填（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginMeta.java:32`；验签 `PluginManager.java:142`） |

## 2. IPlugin / IPluginContext / IOcrPlugin / OcrTextBlock 签名摘要

完整签名权威 = [04-插件包规范.md](../../04-插件包规范.md) §7，此处只列方法面摘要：

- `IPlugin`：`void onInit(IPluginContext ctx) throws Exception`（抛异常=安装失败回滚）、`void onRelease()`（宿主容忍异常）、`String getId()`、`String getVersion()`、`String[] getProvides()`（`autolua2\engine\src\main\java\com\autolua\engine\plugin\IPlugin.java:18-33`）。
- `IPluginContext`：`byte[] captureScreen(x,y,w,h)` / `byte[] captureFull()`（RGBA 像素流，无帧返回 null）、`File getPluginDir()`（`plugin_data/<id>/`）、`void log(msg)` / `log(tag,msg)`（tag 前缀 `AutoLuaPlugin:<id>`）、`String getAbi()`（`SUPPORTED_ABIS[0]`）、`File getNativeLibraryDir()`（插件 so 根目录）、`boolean loadNativeLibrary(soNameOrPath)`（`autolua2\engine\src\main\java\com\autolua\engine\plugin\IPluginContext.java:11-58`；默认实现 `PluginContext.java:17-98`，`System.load` 落点 `PluginContext.java:89`）。
- `IOcrPlugin extends IPlugin`：`List<OcrTextBlock> recognize(byte[] frame, int frameW, int frameH, int rectX, int rectY, int rectW, int rectH) throws Exception`——frame 为紧凑 RGBA（frameW*4/行），rect 为帧内坐标，无文字回空列表不可回 null（`autolua2\engine\src\main\java\com\autolua\engine\plugin\IOcrPlugin.java:11-27`）。
- `OcrTextBlock`：gson 兼容 POJO，公有字段 `text / x / y / w / h / confidence(0~1)`，坐标为帧内坐标（`autolua2\engine\src\main\java\com\autolua\engine\plugin\OcrTextBlock.java:8-34`）。

宿主查找入口：`PluginManager.getOcrPlugin()` 返回"当前生效"OCR 插件实例，OcrLib 与 WS `ocr` 命令共用（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java:301`）。

## 3. 安装原子交换协议（.tmp / .old）

`PluginManager.install(zip)` 的暂存-转正协议（流程权威 = 04 §1/§6，对码 `autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java:364-490`）：

1. **校验先行**：内容清单 sha256 + manifest 必填字段 + 入口 dex/jar 存在性（`PluginManager.java:843-902`）；注入验签公钥后追加 ECDSA 验签（`PluginManager.java:900`）。
2. **全部落暂存目录**：dex → `<id>/<version>.tmp`，so → `<id>.so.tmp`；旧安装目录（`versionDir`/`soDir`）转正前不被触碰——覆盖式重写旧目录里的 so/模型文件会在 mmap 映射下原地截断，导致识别线程 SIGSEGV（root 进程实测崩溃）（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java:378-411`）。
3. **类加载与 onInit 发生在暂存目录内**，失败回滚只删本次暂存目录，旧安装无损（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java:424`、`:481-486`）。
4. **原子转正**：旧 versionDir 改名 `.old` → 暂存 rename 转正 → so 目录同样 `.old` 交换；rename 目标必须不存在，转正失败时把 `.old` 改名回去、以暂存目录注册兜底（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java:429-456`）。
5. **注册后清理**：新实例入表 + 旧实例 onRelease；`.old` 目录 try-delete（被 mmap 时删不掉属正常，容忍失败）（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java:467-477`）。
6. **暂存对 loadAll 不可见**：`loadAll` 跳过 `.tmp`/`.old` 目录，并发进程（root 注入进程）永远看不到半成品安装态（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java:738-740`）。
7. **安装即持久**：重启后由 app 层 `loadAll()` 扫描重载（最高版本优先，失败跳过不影响其余；不再做内容清单校验——安装时已验过）（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java:706-778`）。

## 4. Lua 贡献契约（IPlugin + LuaEnvContributor 双实现）

- 插件入口类同时实现 `IPlugin` 与引擎内置接口 `com.autolua.engine.base.LuaEnvContributor`（插件 dex 经 parent classloader 可见引擎类）；在 `contribute(ctx)` 内调用 `PluginLuaHelper.putGlobal(ctx, name, api, iface)` 把普通 Java/Kotlin 对象注册为 Lua 全局表（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginLuaHelper.kt:42-46`：JavaObjectWrapper 反射包装 → push → setGlobal；活体案例 `autolua2\plugins\demo-luaapi\src\com\autolua\plugin\demoapi\DemoLuaApiPlugin.java:24`、`:58-65`）。
- 触发时机：`PluginManager.contributeLua(LuaContext)` 遍历已加载插件逐个回调 `contribute`，单插件贡献失败只记日志不影响其余（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java:319-336`）。
- 接线点：每个 LuaContext 创建并完成内置服务注入之后——root 主引擎在 `pushLocalServices` 末尾、IDE 沙箱由 app 层触发 `LuaEngineExecutor`（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java:314-317` 注释；root 侧接线对码见 [../luaapi/contracts.md](../luaapi/contracts.md)）。
- **后装插件需重建 context**：贡献只发生在 LuaContext 创建时；root 主引擎 LuaContext 长生命周期（state 跨 execute 保留），安装贡献型插件后需重启引擎/重跑脚本才生效（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginLuaHelper.kt:25-28`）。
- 约束：注册同名全局会覆盖内置服务，禁用 http/file/ocr/plugin 等保留名；root 主引擎与 IDE 沙箱各有一份插件实例、各自向所在进程的 Lua 贡献（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginLuaHelper.kt:28`）。

## 5. ocr.use(id) 切换语义

多 OCR 插件共存采用"门面 + 中央激活选择"（路线 A）：

- `ocr.use(id)` 后端为 `PluginManager.setActiveOcr(pluginId)`：仅当该 id 已安装、`provides` 含 `"ocr"` 且实例实现 `IOcrPlugin` 时置为激活态并返回 true，否则 false（非法 id 被拒绝，不抛异常）（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java:272-284`）。
- `ocr.use()` / `nil` 参数恢复默认 = 第一个注册（安装序）的 OCR 插件；Lua 侧非字符串参数直接报 `IllegalArgumentException`（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java:273-275`；`autolua2\engine\src\main\java\com\autolua\engine\extension\ocr\OcrLib.kt:118-128`，类型拒绝落点 `OcrLib.kt:124`）。
- 生效规则：显式激活者优先，激活插件被卸载/卸出后回落到安装序第一个；识别类接口（Lua ocrText/ocrEx、WS ocr 命令、recognizeRaw/recognizeFile）统一走 `getOcrPlugin()` 返回的"当前生效"插件（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java:287-308`；`OcrLib.kt:36`）。
- 切换为进程内存态：app 进程与 root 进程各一套；各插件 native 推理会话独立、互不干扰，切换无需重启引擎（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java:265-267`）。Lua 面完整签名权威 = [02-Lua-API规范.md](../../02-Lua-API规范.md) §19.9。
- 大 so 插件必须在 Lua 所在进程本地加载（帧数据 ~3.7MB 超 Binder 1MB 限制，不走跨进程）：root 进程由 Server.kt 注入插件目录 override 后 `loadAll`（`autolua2\engine\src\main\java\com\autolua\engine\extension\ocr\OcrLib.kt:31-32`；目录 override 机制 `PluginManager.java:102-107`）。

## 6. 同进程 so 单次加载约束（覆盖重装仅落盘不热重载）

- **native so 无法卸载**（`System.load` 无 unload），且静态链入的运行时（如 libomp）不允许进程内多副本——同进程用新 classloader 再加载一份 so 副本会直接 `__kmp_fatal` abort（实测 SIGABRT）（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java:417-421` 注释）。
- 因此 `install` 检测到本进程已有该插件运行实例时**只落盘不热重载**：暂存-转正照常完成（旧 inode 被 mmap 存活，运行实例不受影响），返回既有实例；新版本**进程重启后由 loadAll 生效**（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java:458-464`）。
- **ART 按类加载器匹配 native 方法所属库**：插件 so 必须经插件自身 classloader 加载（插件代码内 `System.loadLibrary`，DexClassLoader 搜索路径由宿主注入：宿主 nativeLibraryDir 优先 + 插件 so 根目录）；`ctx.loadNativeLibrary` 的 `System.load` 调用方是宿主类，库注册到 app classloader，插件类的 native 方法绑定不到（实测 `No implementation found`）（`autolua2\engine\src\main\java\com\autolua\engine\plugin\IPluginContext.java:49-54`；`PluginManager.java:912-919`）。
- **同一 so 路径只能属一个类加载器**（ART 硬限制）：同进程多实例（loadAll 重载 + 覆盖安装并存）时第二个实例加载同路径 so 会 ULE，插件应回落到绝对路径 `System.load` 未被占用的副本（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java:916-919`；两级加载实测案例 `autolua2\plugins\ncnn-ocr\src\com\autolua\plugin\ocr\ncnn\NcnnOcrPlugin.java:73-83`）。
- 宿主 nativeLibraryDir 优先注入搜索路径是 x86_64 转译模拟器适配（Android 9 + libnb 转译层对 app 私有目录 so 存在 JNI 绑定异常，插件 so 预置进宿主 APK jniLibs 后经该路径可用；真机宿主内无同名 so 自然命中解压产物）（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java:912-915`）。
