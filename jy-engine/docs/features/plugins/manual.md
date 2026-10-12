# 插件开发与安装手册

> 文档版本 v1.0（2026-09-18，文档三层化重组新建）。
> 本域权威范围：plugins 域的操作手册（开发步骤 / 本地安装 / 签名 / ncnn-ocr 构建 / 常见问题）；三种本地安装方式的平台契约权威在 [../../04-插件包规范.md](../../04-插件包规范.md) §11，包格式与 manifest 权威在同文 §1/§2，WS 安装通道权威在同文 §8。所有对码标注均经 Read/Grep 核实。

## 开发一个插件的步骤

以 demo-luaapi（Lua API 贡献型）与 demo-ocr（OCR mock 型）为模板，两者均不进主构建、独立编译。

### 1. 目录结构

```
plugins/<your-plugin>/
├── README.md
├── manifest.json                          # sha256 占位，打包后回填
├── src/com/yourpkg/YourPlugin.java        # 入口类（实现 IPlugin）
├── cpp/                                   # 可选：native 推理库
└── build.ps1                              # 推荐：一键构建脚本
```

活体参考：`autolua2\plugins\demo-luaapi\`（`README.md` + `manifest.json` + `src\com\autolua\plugin\demoapi\DemoLuaApiPlugin.java`）、`autolua2\plugins\demo-ocr\`（多出 `build\MakeZip.java` 打包工具与 `build\verify_zip.ps1`）。

### 2. 编写 manifest.json

字段全表权威 = [04-插件包规范.md](../../04-插件包规范.md) §2，Java 对码见 [contracts.md](contracts.md) §1。最小可用样例（demo-ocr 实物）：

```json
{
  "id": "com.autolua.plugin.ocr.demo",
  "name": "Demo OCR (mock)",
  "version": "1.0.0",
  "mainClass": "com.autolua.plugin.demo.DemoOcrPlugin",
  "provides": ["ocr"],
  "minHostVersion": "0.0.0",
  "sha256": "<打包后回填>"
}
```

`id`/`version` 仅允许 `[A-Za-z0-9._-]` 且不含 `..`（防路径穿越）；`mainClass` 必须实现 `IPlugin`；`sha256` 必填。

### 3. 实现入口类

- 基本型：实现 `IPlugin`，在 `onInit` 里经 `IPluginContext` 拿截图帧/私有目录/so 加载，`onRelease` 释放资源（接口签名见 [contracts.md](contracts.md) §2，权威 = 04 §7）。
- OCR 型：再实现 `IOcrPlugin.recognize(...)`，`provides` 写 `["ocr"]`（mock 参考 `autolua2\plugins\demo-ocr\src\com\autolua\plugin\demo\DemoOcrPlugin.java:21`）。
- Lua API 贡献型：再实现 `com.autolua.engine.base.LuaEnvContributor`，在 `contribute` 里 `PluginLuaHelper.INSTANCE.putGlobal(lua, "yourGlobal", new Api(ctx), Api.class)`（参考 `autolua2\plugins\demo-luaapi\src\com\autolua\plugin\demoapi\DemoLuaApiPlugin.java:58-65`）；语义约束（后装插件需重建 context、保留名禁用）见 [contracts.md](contracts.md) §4。
- 含 so 的插件：onInit 里经**插件自身 classloader** `System.loadLibrary("xxx")`，ULE 回落 `System.load(ctx.getNativeLibraryDir()/libxxx.so)`（参考 `autolua2\plugins\ncnn-ocr\src\com\autolua\plugin\ocr\ncnn\NcnnOcrPlugin.java:73-83`；原理见 [contracts.md](contracts.md) §6）。

### 4. 编译 + 打 zip

无 so 插件（javac + d8，命令实例见 `autolua2\plugins\demo-luaapi\README.md`，需先 `gradlew :engine:assembleDebug` 拿 engine classes.jar）：

```powershell
javac -source 8 -target 8 -encoding UTF-8 -cp "$AJ;$ENG" -d build\classes src\...
& "$SDK\build-tools\<ver>\d8.bat" --release --min-api 22 --lib $AJ --classpath $ENG --output build (Get-ChildItem build\classes -Recurse -Filter *.class).FullName
```

zip 布局 = `manifest.json` + `classes.jar`（或 `classes.dex`）+ 可选 `lib/<abi>/*.so`（权威 = 04 §1）。打 zip 与内容清单 sha256 计算可直接复用 demo-ocr 的打包工具 `java MakeZip`（`autolua2\plugins\demo-ocr\build\MakeZip.java:25`）：`MakeZip zip <zip> <file:entryName>...` 建 zip、`MakeZip hash <zip>` 按 04 §3 同款算法输出内容清单 hash（`MakeZip.java:74-114`），把输出回填 manifest 的 `sha256` 字段后重打即可。

**推荐**：带 so/模型的插件直接抄 `autolua2\plugins\ncnn-ocr\build.ps1`（七步：javac → d8 → NDK cmake → classes.jar → zip → sha256 两遍回填 → 签名），sha256 回填与稳定性校验脚本已内建（`build.ps1:211-221`）。

### 5. 安装与验证

见下节三种本地安装方式；装完用 WS `plugin_list` / `capabilities` 验证（权威 = 04 §8），logcat 过滤 `AutoLuaPlugin`。

## 三种本地安装方式（摘要）

安装逻辑同为 `PluginManager.install`，sha256 内容清单校验 → 解压 → DexClassLoader → onInit 全流程一致（权威 = [04-插件包规范.md](../../04-插件包规范.md) §11）：

1. **assets 预置（开箱即用）**：zip 放 `autolua2\app\src\main\assets\plugins\`（当前预置 `demo-ocr.zip`），app 启动时 `installFromAssets()` 自动装；同 id 同版本已装则跳过（幂等），同 id 新版本覆盖升级（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java:556-598`）。
2. **外部目录放 zip（免存储权限，推荐分发方式）**：zip push 到 `/sdcard/Android/data/com.autolua.autolua2/files/plugins/`，启动时 `installFromExternalDir()` 扫描安装；成功归档 `processed/`、失败归档 `failed/`（三层容忍，幂等靠"移走"）（`PluginManager.java:510-543`）。
3. **WS plugin_push 三段推送（IDE/远程联调）**：`plugin_push_start`（带整包 zip 文件 sha256）→ `plugin_push_chunk`（分块 base64）→ `plugin_push_complete`；文件级 + 内容清单双重校验（权威 = 04 §8，JSON 序列可直接复制）。

启动时序：`loadAll()`（恢复已装）→ `installFromAssets()` → `installFromExternalDir()`（`PluginManager.java:57-61` 注释）。三种方式落盘后均持久，进程重启由 `loadAll()` 恢复。脚本内也可 `plugin.install("/path/x.zip")` 直装（Lua 口权威 = 04 §12.1 / [02-Lua-API规范.md](../../02-Lua-API规范.md) §19.8）。

## 签名（sign-plugin.ps1）

工具：`autolua2\plugins\sign-plugin.ps1`（ECDSA P-256 / secp256r1，签名对象 = 内容清单 sha256 的 hex 小写字符串，manifest.signature 存 base64 DER，Java 侧 SHA256withECDSA 直接验——`sign-plugin.ps1:9-11`）：

```powershell
# ① 一次性生成密钥对（私钥妥善保管勿入库；开发密钥现存放于 autolua2\plugins\signing\dev-key.json）
.\sign-plugin.ps1 -GenerateKey -OutKey ..\signing\dev-key.json   # 输出 qx/qy 供宿主注入

# ② 给插件 zip 签名（读 zip → 内容清单 sha256 → ECDSA 签名 → manifest.signature 注入 → 重打包，幂等）
.\sign-plugin.ps1 -Zip <plugin.zip> -Key ..\signing\dev-key.json
```

- `ncnn-ocr\build.ps1` 第 7/7 步已自动调签名（`build.ps1:223-228`），其他插件可手动补签；
- **宿主侧现状**：`PluginManager.setSignaturePublicKeyHex(qxHex, qyHex)` 注入公钥后安装强制验签；未注入 = 开发模式不验签（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java:120`、`:142-157`）。release 宿主接线为进行中演进（见 [design.md](design.md) 演进章节）。

## ncnn-ocr 构建与安装验证

### 构建（build.ps1 + CMake/NDK）

前置：engine 已 `gradlew :engine:assembleDebug` 一次；ncnn Android 预编译包（CPU 版，非 vulkan）解压到 `autolua2\plugins\ncnn-ocr\third_party\ncnn-android`；模型 5 文件就位于 `models/`（转换步骤见 `autolua2\plugins\ncnn-ocr\MODEL_CONVERT.md`，**pnnx 必须 `fp16=0`**）。

```powershell
cd autolua2\plugins\ncnn-ocr
.\build.ps1                            # 默认 arm64-v8a
.\build.ps1 -X86_64                    # x86_64 开发模拟器需要
.\build.ps1 -NdkDir D:\Android\SDK\ndk\25.2.9519653   # 多 NDK 共存必传（实测 NDK 28.2 cmake 报错，推荐 25.2.9519653）
```

要点：产物 `build\ncnn-ocr-1.0.0.zip`（实测 ≈20.2MB，模型内嵌 classes.jar 资源随包过 sha256）；native 库 `libncnnocr.so` 静态链 libncnn.a + `-static-openmp`（`autolua2\plugins\ncnn-ocr\cpp\CMakeLists.txt:31-48`）；x86_64 模拟器还需把 so 预置进宿主 APK `autolua2\app\src\main\jniLibs\x86_64\libncnnocr.so`（转译层对私有目录 so 的 JNI 绑定异常规避，已做）；build.ps1 必须保持 **UTF-8 with BOM** 编码、cmake `-D` 参数用括号表达式拼接（两坑详见 `autolua2\plugins\ncnn-ocr\README.md`）。

### 安装验证

```bash
adb push build\ncnn-ocr-1.0.0.zip /data/local/tmp/ncnn-ocr.zip
adb logcat -s NcnnOcr AutoLuaPlugin
```

```lua
-- Lua（脚本或远程控制台）
plugin.install('/data/local/tmp/ncnn-ocr.zip')
ocr.use("com.autolua.plugin.ocr.ncnn")   -- 与 demo 插件并存时切换
ocr.ocrText(0, 0, 719, 1279)             -- 或 ocr.ocrEx(...)
```

WS 侧（权威样例 = 04 §8）：

```json
{"type":"ocr","data":{}}
{"type":"ocr","data":{"rect":{"x":0,"y":0,"w":1080,"h":2340}}}
```

预期：logcat `nativeInit: dict=6623 classes=6625 det/rec loaded=true`；WS 回包 `results:[{text=..., x=, y=, w=, h=, confidence=}]`。det 概率图 `max` 应接近 1.0——只有 ~0.1x 即模型被 fp16 转换污染（重转，fp16=0）。

## 常见问题

| 症状 | 原因与处置 |
|---|---|
| 安装报 `manifest sha256 mismatch: manifest=..., computed=...` | 内容清单 hash 不匹配（打包后改过包内任何文件）。把报文里的 `computed` 值直接回填 manifest.json 的 `sha256` 重打（`autolua2\engine\src\main\java\com\autolua\engine\plugin\PluginManager.java:895-899`） |
| 安装报 `plugin zip contains native libs but none matches device ABIs` | 包内 `lib/<abi>` 与设备 `SUPPORTED_ABIS` 无一命中。按目标设备 ABI 重打（ncnn-ocr 用 `-X86_64`/`-ArmV7` 开关）（`PluginManager.java:1005-1006`） |
| 安装报 `manifest.signature missing/invalid` | 宿主已注入验签公钥但包未签名/签名不匹配。用 `sign-plugin.ps1 -Zip ... -Key ...` 补签（`PluginManager.java:147-156`） |
| native 方法 `No implementation found`（模拟器高发） | so 注册到了错误的类加载器，或转译层对私有目录 so 绑定异常。插件内用自身 classloader `System.loadLibrary`（勿只靠 `ctx.loadNativeLibrary`）；模拟器把 so 预置进宿主 jniLibs（`IPluginContext.java:49-54`；`NcnnOcrPlugin.java:65-83`） |
| 覆盖安装新版本后行为没变 | 有运行实例时仅落盘不热重载（so 不可卸载 + libomp 单副本约束），**进程重启后由 loadAll 生效**（`PluginManager.java:458-464`） |
| 装了贡献型插件但 Lua 全局不存在 | 贡献时机 = LuaContext 创建时，后装插件需重建 context（主引擎重启 / 沙箱重跑启动流程）（`PluginLuaHelper.kt:25-28`） |
| `ocr.use("xxx")` 返回 false | 目标 id 未安装、`provides` 不含 `"ocr"` 或未实现 `IOcrPlugin`；`ocr.list()` 查可用 id（`PluginManager.java:272-284`） |
| `No implementation found`/ULE 换加载路径仍失败：`libncnnocr.so 加载失败：搜索路径全 miss` | 宿主 jniLibs 与插件 so 根目录都没有该 so。重打 zip 确认 `lib/<abi>/libncnnocr.so` 在包内（`NcnnOcrPlugin.java:76-82`） |
| 识别结果乱码/置信度异常低 | rec 字典与模型不匹配（换模型没换字典，logcat `rec output size mismatch ... expectC=6625`）；或模型 fp16 转换污染（重转 fp16=0）（MODEL_CONVERT.md 第 7 步） |
