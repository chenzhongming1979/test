# Demo OCR 插件（mock，方案七全链路验证用）

`com.autolua.plugin.ocr.demo` —— 实现宿主 `IOcrPlugin` 契约的**mock** OCR 插件：
`recognize` 只做帧缩小取样统计后返回固定文本块 `DEMO`，用于验证
插件包推送 → sha256 校验 → DexClassLoader 加载 → so 交付模型 → WS ocr 命令全链路。
**替换为真实 PP-OCR(ncnn) 推理即生产插件**（`DemoOcrPlugin.java` 内有替换点注释）。

> 本目录**不进主构建**（`settings.gradle.kts` 未包含 `plugins/`），独立编译。

## 目录结构

```
plugins/demo-ocr/
├── README.md                                  # 本文件
├── manifest.json                              # 插件包清单（sha256 字段见下方"内容清单 hash"）
├── demo-ocr-1.0.0.zip                         # 打包产物（随仓库提交；APK assets 预置同源包）
└── src/com/autolua/plugin/demo/
    └── DemoOcrPlugin.java                     # 插件入口（mainClass）
```

## 插件包格式（plugin.zip）

```
plugin.zip
├── manifest.json                              # 必需，PluginMeta（见 ../../docs/04-插件包规范.md）
├── classes.jar                                # 必需二选一：jar（DexClassLoader 支持 jar）
├── classes.dex                                #   或裸 dex
└── lib/arm64-v8a/libdemo.so                   # 可选：按设备 ABI 命中解压（demo 无 so）
```

manifest.json 字段（必填 `id`/`version`/`mainClass`/`sha256`）：

```json
{
  "id": "com.autolua.plugin.ocr.demo",
  "name": "Demo OCR (mock)",
  "version": "1.0.0",
  "mainClass": "com.autolua.plugin.demo.DemoOcrPlugin",
  "provides": ["ocr"],
  "minHostVersion": "0.0.0",
  "sha256": "<内容清单 hash，hex 小写>"
}
```

## 编译（最简 d8 命令行路径）

依赖：JDK 8+、Android SDK（`d8` 在 `%ANDROID_HOME%\build-tools\<ver>\d8.bat`，
本机 SDK 见 autolua2\local.properties 的 `sdk.dir`，如 `G:\Android\SDK`）。
classpath 需要 engine 的编译产物 `classes.jar`（内含 IPlugin/IOcrPlugin/IPluginContext/OcrTextBlock）：

- 先构建一次主工程：`cd autolua2 && gradlew.bat :engine:assembleDebug`
- 取出 aar 内 classes.jar：`engine\build\outputs\aar\engine-debug.aar`（zip 解压取 `classes.jar`）

命令（在仓库根 `autolua2\` 下执行，PowerShell；以下为本机实测通过：JDK 17 + build-tools 36.0.0）：

```powershell
$SDK = "G:\Android\SDK"                                  # = local.properties 的 sdk.dir
$AJ  = "$SDK\platforms\android-34\android.jar"
$ENG = "engine\build\intermediates\compile_library_classes_jar\debug\bundleLibCompileToJarDebug\classes.jar"  # AGP 8 实测路径
# ① javac 编译（Java 8 目标；JDK 17 下 "源值 8 已过时" 为无害警告）
javac -source 8 -target 8 -encoding UTF-8 `
      -cp "$AJ;$ENG" `
      -d plugins\demo-ocr\build\classes `
      plugins\demo-ocr\src\com\autolua\plugin\demo\DemoOcrPlugin.java
# ② d8 转 dex（--classpath 指向 engine classes.jar 可消除 desugaring 警告）
& "$SDK\build-tools\36.0.0\d8.bat" --release --min-api 22 `
      --lib $AJ --classpath $ENG --output plugins\demo-ocr\build `
      (Get-ChildItem plugins\demo-ocr\build\classes -Recurse -Filter *.class).FullName
# 产物：plugins\demo-ocr\build\classes.dex
```

> 若 `compile_library_classes_jar\debug\bundleLibCompileToJarDebug\classes.jar` 不存在
> （AGP 版本差异），复制 `engine\build\outputs\aar\engine-debug.aar` 改后缀为 `.zip`
> 解压取 `classes.jar` 即可；build-tools 版本按本机实际目录调整（`ls $SDK\build-tools`）。

## 打包 zip + 内容清单 hash（sha256）

规则：对 zip 内**除 manifest.json 外**所有 file entry 按名称排序，
逐条串接 `<name>:<未压缩size>:<该entry的sha256hex>\n`，对整段文本再做 sha256（hex 小写），
写入 manifest.json 的 `sha256` 字段。打包前先做一版 hash 占位 zip 算出正确值回填：

```powershell
function Get-ContentManifestHash($zipPath) {
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zip = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
  $sha = [System.Security.Cryptography.SHA256]::Create()
  try {
    $lines = @()
    foreach ($e in ($zip.Entries | Where-Object { $_.Name -ne '' -and $_.FullName -ne 'manifest.json' } | Sort-Object FullName)) {
      $s = $e.Open(); $ms = New-Object System.IO.MemoryStream; $s.CopyTo($ms); $s.Dispose()
      $hex = ([BitConverter]::ToString($sha.ComputeHash($ms.ToArray()))).Replace('-','').ToLower()
      $ms.Dispose()
      $lines += "$($e.FullName):$($e.Length):$hex"
    }
    $bytes = [Text.Encoding]::UTF8.GetBytes(($lines -join "`n") + "`n")
    return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-','').ToLower()
  } finally { $zip.Dispose(); $sha.Dispose() }
}
# 用法：打好 zip（manifest.json + classes.dex）后执行，把输出回填 manifest.json 的 sha256 再重打 zip
Get-ContentManifestHash "plugin.zip"
```

首次安装若回包 `manifest sha256 mismatch: manifest=..., computed=...`，
直接把 `computed=` 后的值回填 manifest.json 重打 zip 即可（校验逻辑两边同源）。

## WS 安装与验证（wscat，命令样例见 ../../docs/04-插件包规范.md）

1. `plugin_push_start` → `plugin_push_chunk`（分块 base64，index 从 1 起）→ `plugin_push_complete`
2. 回包 `{"type":"plugin_result","data":{"installed":true,"id":"com.autolua.plugin.ocr.demo",...}}`
3. `{"type":"capabilities"}` → `vision.ocr` 变 `true`，`plugins` 数组出现本插件
4. `{"type":"ocr","data":{}}` → 回 `{"type":"result","data":{"results":[{"text":"DEMO",...}],"text":"DEMO"}}`
   （需截图通道已启动；logcat 过滤 `AutoLuaPlugin` 可见 onInit/recognize 日志）
5. `{"type":"plugin_remove","data":{"id":"com.autolua.plugin.ocr.demo"}}` → `removed:true`，
   `vision.ocr` 变回 `false`
