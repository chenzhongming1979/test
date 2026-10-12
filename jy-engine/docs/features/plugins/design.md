# 插件体系设计

> 文档版本 v1.0（2026-09-18，文档三层化重组新建）。
> 本域权威范围：plugins 域的设计决策与机制说明（加载/安装/生命周期/OCR 通道/ncnn-ocr 案例）；包格式与接口完整签名的平台契约权威在 [../../04-插件包规范.md](../../04-插件包规范.md)（§1-§8），域内契约摘要见 [contracts.md](contracts.md)。所有对码标注均经 Read/Grep 核实。

## 架构总述

插件体系 = 宿主框架（engine 内 `plugin\` 包）+ 插件包（zip：manifest.json + classes.jar/dex + 可选 `lib/<abi>/*.so`）+ Lua/WS 两个消费面：

- **框架层**：`PluginManager`（单例）负责安装校验、DexClassLoader 加载、注册表与生命周期；`IPlugin`/`IPluginContext` 是宿主与插件之间刻意收敛的能力边界——插件不持有 Context、不触碰宿主内部组件，能力面只有截图帧/私有目录/日志/ABI/so 加载（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java:65`；`autolua2\engine\src\main\java\com\autolua\engine\plugin\IPluginContext.java:6-9`）。
- **目录布局**（全部在 app 私有目录内，root 进程经目录 override 注入访问）：安装根 `getDir("plugins")/<id>/<version>/`、so 根 `getDir("plugin_libs")/<id>/`（平铺）、数据目录 `getDir("plugin_data")/<id>/`、odex 输出 `getCodeCacheDir()`（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java:44-50`；目录 override `PluginManager.java:102-107`、`:170-175`）。
- **消费面**：Lua 侧全局 `plugin` 表（生命周期）与 `ocr` 表（OCR 运行时）权威 = [04-插件包规范.md](../../04-插件包规范.md) §12 / [02-Lua-API规范.md](../../02-Lua-API规范.md) §19.8/§19.9；IDE WS 侧 `plugin_push_*`/`plugin_list`/`plugin_remove`/`ocr` 命令权威 = 04 §8。OCR 两面共用同一激活选择入口 `PluginManager.getOcrPlugin()`（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java:300-301`）。
- **进程模型**：插件注册在 Lua 所在进程本地（root 引擎进程由 Server.kt 注入目录 override 后 loadAll；沙箱由 app 注册）——OCR 帧数据 ~3.7MB 超 Binder 1MB 限制，不走跨进程（`autolua2\engine\src\main\java\com\autolua\engine\extension\ocr\OcrLib.kt:31-32`）。三环境服务注入矩阵见 [../../01-架构总览.md](../../01-架构总览.md) §8。

## 加载与安装机制（DexClassLoader / so 解压 / 原子交换）

`install(zip)` 全流程（对码 `autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java:364-490`；包格式权威 = 04 §1）：

1. **校验**：zip 必含 manifest.json + classes.jar（优先）或 classes.dex；按"除 manifest 外全部 file entry 按名排序，`<name>:<size>:<entry sha256>` 逐行 + `\n` 串接后整体 sha256"计算内容清单 hash 与 manifest.sha256 比对，失配拒绝且报文带计算值便于回填（`PluginManager.java:843-902`，清单计算 `:870-880`、失配报错 `:895-899`）。注入验签公钥后对该 hash 做 SHA256withECDSA 验签（`PluginManager.java:142-157`）。
2. **so 解压**：按 `Build.SUPPORTED_ABIS` 优先级匹配包内 `lib/<abi>/*.so`，取第一个命中 ABI 的全部 so **平铺**解压到 so 根目录；包内含 lib/ 但无一命中 → SecurityException 拒装（`PluginManager.java:959-1007`，平铺落点 `:999`）。ABI 规则权威 = 04 §4。
3. **暂存目录**：dex 落 `<version>.tmp`、so 落 `<id>.so.tmp`，旧安装转正前不动（覆盖旧 mmap 文件会 SIGSEGV，实测）（`PluginManager.java:378-411`）。
4. **DexClassLoader 实例化**：`DexClassLoader(dexPath, codeCacheDir, librarySearchPath, PluginManager 的 classloader)`；librarySearchPath = 宿主 nativeLibraryDir 优先 + 插件 so 根目录（模拟器 libnb 转译层适配）；loadClass → newInstance → 强转 IPlugin → 创建数据目录 → `onInit`（`PluginManager.java:905-950`，ClassLoader 构造 `:933-937`，onInit `:948`）。
5. **原子交换**：onInit 成功后旧目录改 `.old` → 暂存 rename 转正 → 注册 + 旧实例 onRelease + `.old` try-delete；失败回滚只删暂存（协议细节见 [contracts.md](contracts.md) §3；`PluginManager.java:429-486`）。
6. **三个本地安装入口**共用同一 `install`：assets 预置 `installFromAssets()`（幂等跳过同 id 同版本，`PluginManager.java:556-598`）、外部目录扫描 `installFromExternalDir()`（processed/failed 归档三层容忍，`PluginManager.java:510-543`）、WS plugin_push 三段推送（04 §8）。启动时序：`loadAll → installFromAssets → installFromExternalDir`（`PluginManager.java:57-61` 注释）。

## 生命周期

权威 = 04 §6；对码摘要（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java`）：

| 阶段 | 行为 | 对码 |
|---|---|---|
| 安装 | 校验 → 暂存解压 → DexClassLoader → onInit（异常回滚）→ 原子转正 → 注册 | `:364-490` |
| 覆盖（无运行实例） | .old 交换 + 新实例注册 + 旧实例 onRelease | `:429-477` |
| 覆盖（有运行实例） | 仅落盘不热重载（so 不可卸载 + libomp 多副本 abort），重启后 loadAll 生效 | `:458-464` |
| 卸载 uninstall | onRelease → 移表 → 版本目录/so 目录/数据目录 try-delete | `:678-696` |
| 卸出 unload | onRelease + 移表，目录保留（"已安装未加载"态） | `:821-829` |
| 加载 loadOne | 取最高版本目录尝试加载（plugin.load(id) 后端） | `:785-814` |
| 重启恢复 loadAll | 扫描安装根，跳过 .tmp/.old，逐插件取最高版本重载（不再 sha256 校验），失败跳过 | `:706-750`，重载 `:753-778` |
| 脚本级 Lua 口 | plugin.list/load/unload/install/uninstall 等（PluginLib） | 权威 = 04 §12.1 |

## OCR 插件通道（OcrLib 委托、多插件共存）

- **委托链**：Lua `ocr.*` → `OcrLib`（engine `extension\ocr\OcrLib.kt`，`LuaEnvContributor` 实现，contribute 时注册全局 `ocr` 表，`autolua2\engine\src\main\java\com\autolua\engine\extension\ocr\OcrLib.kt:104-151`）→ `PluginManager.getOcrPlugin()` 取当前生效插件 → `IOcrPlugin.recognize`。OcrLib 负责把带行尾 padding 的 stride 帧重排为契约要求的紧凑 frameW*4/行，并钳制识别区域（`OcrLib.kt:57-85`，stride 重排 `:74-82`）。
- **多插件共存（路线 A：门面 + 中央激活选择）**：所有识别类接口统一走"当前生效"插件——显式 `ocr.use(id)` 优先，未切换取安装序第一个；激活态是 PluginManager 内的一个 volatile 字段，切换无需重启引擎，各插件 native 推理会话独立（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java:247-308`；语义细则见 [contracts.md](contracts.md) §5）。
- **大 so 插件本地加载**：OCR 插件运行在 Lua 所在进程（root 进程本地加载，帧数据不走跨进程），`ocr` 表在两个进程各有一份、各用各的激活态（`OcrLib.kt:27-32`）。
- **WS 通道**：IDE `ocr` 命令与脚本共用 `getOcrPlugin()`；插件 recognize 抛错回 `ocr plugin error`，无插件回能力不可用（权威 = 04 §8 错误语义）。

## 案例：ncnn-ocr

`autolua2\plugins\ncnn-ocr\`（id `com.autolua.plugin.ocr.ncnn`，v1.0.0）是第一个真实 OCR 插件：Java 壳 + native 推理全部在 `libncnnocr.so`（静态链入 ncnn）。

- **模型来源与转换**（详见 `autolua2\plugins\ncnn-ocr\MODEL_CONVERT.md`）：PaddleOCR 官方 **PP-OCRv4 mobile**（det=ch_PP-OCRv4_det_infer、rec=ch_PP-OCRv4_rec_infer、字典 ppocr_keys_v1.txt）→ paddle2onnx（opset 11）→ onnxsim 化简 → **pnnx 转 ncnn（必须 `fp16=0`）**。pnnx 默认 fp16=1 把权重存半精度，x86 转译模拟器上 det 概率图 max 只有 ~0.17（正常 1.0）、识别全废且无报错——fp16 权重主要面向 ARM（MODEL_CONVERT.md:72-84，2026-09-18 实测踩坑）；rec 必须指定静态 `inputshape=[1,3,48,320]f32`（动态宽度维度直转会在 onnxruntime 校验时 Reshape 失败；插件 rec 前处理固定输出 48×320 右侧补 0）。
- **静态链**：`cpp\CMakeLists.txt` 把 `libncnn.a` 以 STATIC IMPORTED 链入，编译/链接均带 `-fopenmp`、链接侧加 `-static-openmp`——ncnn 依赖 OpenMP，NDK r21+ 用静态链避免插件 so 带动态 libomp 依赖（也因此同进程不允许第二份 so 副本，见 [contracts.md](contracts.md) §6）（`autolua2\plugins\ncnn-ocr\cpp\CMakeLists.txt:31-33`、`:47-48`）。
- **build.ps1 要点**（`autolua2\plugins\ncnn-ocr\build.ps1`）：七步一键构建——定位 engine classes.jar → javac（-source/-target 8）→ d8（--min-api 22）→ NDK cmake 逐 ABI 编 libncnnocr.so → 打 classes.jar（dex + models/ 五文件内嵌为资源）→ 打 zip + 内容清单 sha256 **两遍回填校验**（回填 manifest 后重打，hash 必须稳定）→ 调 sign-plugin.ps1 签名（`build.ps1:211-228`）。参数：`-NdkDir`（多 NDK 共存必传，实测 NDK 28.2 报 CMAKE_CXX_COMPILER not set，推荐 25.2.9519653）、`-X86_64`（模拟器需）、`-ArmV7`、`-SkipNative`、`-NcnnDir`、`-SdkDir`（`build.ps1:20-27`）。产物 `build\ncnn-ocr-1.0.0.zip`（实测 21,154,217 字节 ≈ 20.2MB，含内嵌模型）。
- **模型分发三通道**（onInit 按序查找）：classes.jar 资源 `models/*`（随包过 sha256，首推）→ `plugin_data/<id>/models/`（上次释放残留）→ `/data/local/tmp/autolua_ocr_models/`（开发迭代免重打包）（`autolua2\plugins\ncnn-ocr\src\com\autolua\plugin\ocr\ncnn\NcnnOcrPlugin.java:20-24`、`:85`）。
- **so 两级加载**：优先 `System.loadLibrary("ncnnocr")`（经插件自身 classloader；模拟器命中宿主 jniLibs 预置 so），ULE 回落 `System.load(soDir 绝对路径)`（`NcnnOcrPlugin.java:73-83`；宿主 jniLibs 预置落点 `autolua2\app\src\main\jniLibs\x86_64\libncnnocr.so`）。
- **实测状态**：x86_64 转译模拟器（Android 9 + libnb）识别链路通过；模拟器 4 vCPU 性能基线 720x1280 全屏 = 帧获取 31ms + 全屏识别 ~4.8s（rec 占大头），真机数据待实测（`NcnnOcrPlugin.java:42-44` 注释）。

## 代码映射表

| 设计点 | 代码落点 |
|---|---|
| 安装/校验/原子交换 | `autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java`（install :364，verifyAndReadMeta :843，extractNativeLibs :959） |
| DexClassLoader 实例化 | 同上（instantiate :905） |
| 生命周期 loadAll/loadOne/unload/uninstall | 同上（:706/:785/:821/:678） |
| manifest 模型 | `autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginMeta.java` |
| 能力边界 | `IPlugin.java` / `IPluginContext.java` / `PluginContext.java`（同目录） |
| OCR 契约 | `IOcrPlugin.java` / `OcrTextBlock.java`（同目录） |
| Lua OCR 面 | `autolua2\engine\src\main\java\com\autolua\engine\extension\ocr\OcrLib.kt` |
| Lua API 贡献 | `autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginLuaHelper.kt`（接线见 [../luaapi/contracts.md](../luaapi/contracts.md)） |
| demo-luaapi（贡献案例） | `autolua2\plugins\demo-luaapi\src\com\autolua\plugin\demoapi\DemoLuaApiPlugin.java` |
| demo-ocr（mock OCR + 打包工具） | `autolua2\plugins\demo-ocr\src\com\autolua\plugin\demo\DemoOcrPlugin.java`、`autolua2\plugins\demo-ocr\build\MakeZip.java` |
| ncnn-ocr（真实 OCR） | `autolua2\plugins\ncnn-ocr\src\com\autolua\plugin\ocr\ncnn\NcnnOcrPlugin.java` + `autolua2\plugins\ncnn-ocr\cpp\ncnn_ocr.cpp` |
| 签名工具 | `autolua2\plugins\sign-plugin.ps1` + `autolua2\plugins\signing\dev-key.json` |

## 演进

以下为进行中/未完成的演进方向（04 §9/§13 已列，任务书沉淀在 `../../plans/` 待建目录，暂无死链）：

- **签名体系落地**：sign-plugin.ps1（ECDSA P-256）、dev-key.json、PluginManager 验签代码（`setSignaturePublicKeyHex`/`verifySignature`，`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java:120`、`:142`）与 ncnn-ocr 打包签名均已就绪；未完成的是宿主 app 层 release 构建注入公钥的接线与发布密钥管理流程（现状默认 null = 开发模式不验签，`PluginManager.java:109-112`）。
- **插件市场 API**：检索/下载/版本管理（对标按键精灵插件市场），依赖签名体系先行。
- **真机联调**：安装/OCR/覆盖/卸载全链路上真机验证（ncnn-ocr 目前模拟器实测通过）。
- **ncnn-ocr 可选优化**（v1 不做，路线见 MODEL_CONVERT.md 附录）：int8 量化、ncnn2mem 模型加密、方向分类 cls 模型、旋转文本最小外接矩形。
