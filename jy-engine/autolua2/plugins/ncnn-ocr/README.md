# ncnn-ocr 插件（PP-OCR 真实 OCR，ncnn 推理路线）

`com.autolua.plugin.ocr.ncnn` —— det(DBNet 文本检测) + rec(CRNN 文本识别)，
前后处理对齐 PaddleOCR 官方 C++ 部署行为。实现 [IOcrPlugin](../../engine/src/main/java/com/autolua/engine/plugin/IOcrPlugin.java)
契约，宿主插件框架（PluginManager / OcrLib / Lua `ocrText`）零改动。

> 本目录**不进主构建**（同 demo-ocr），独立编译。

## 目录结构

```
plugins/ncnn-ocr/
├── README.md                                  # 本文件
├── MODEL_CONVERT.md                           # 模型转换步骤清单（先做这个）
├── build.ps1                                  # 一键构建：javac→d8→NDK→打包→sha256
├── manifest.json                              # 插件清单（sha256 由 build.ps1 回填）
├── models/                                    # 转换后的 5 个模型文件（见 MODEL_CONVERT.md）
├── third_party/ncnn-android/                  # ncnn Android 预编译包（自解压，不进仓库）
├── src/com/autolua/plugin/ocr/ncnn/
│   └── NcnnOcrPlugin.java                     # 插件入口：模型分发/释放 + native 桥
└── cpp/
    ├── CMakeLists.txt                         # NDK 独立构建（静态链入 ncnn）
    └── ncnn_ocr.cpp                           # 推理全实现：det前处理/DB后处理/rec/CTC解码/JNI
```

## 快速开始（3 步）

1. **转模型**：按 [MODEL_CONVERT.md](MODEL_CONVERT.md) 得到 `models/` 下 5 个文件
2. **构建**：`cd plugins\ncnn-ocr && .\build.ps1`（前置：engine 已 `assembleDebug`
   一次、ncnn 预编译包解压到 `third_party/ncnn-android`）
   产物 `build/ncnn-ocr-1.0.0.zip`，模型自动内嵌进 `classes.jar` 资源随包分发
3. **安装验证**：
   ```
   adb push build\ncnn-ocr-1.0.0.zip /data/local/tmp/ncnn-ocr.zip
   Lua: plugin.install('/data/local/tmp/ncnn-ocr.zip')
   logcat -s NcnnOcr        # nativeInit: dict=6623 classes=6625 det/rec loaded=true
   WS:  {"type":"ocr","data":{}}   # 或 Lua ocrText(x1,y1,x2,y2)
   ```
   多 OCR 插件并存时用 `ocr.use(id)` 切换激活（路线A 门面+切换，详见
   [PLUGIN_SPEC.md](../../../docs/04-插件包规范.md)），无需卸载 demo 插件：
   ```lua
   ocr.use("com.autolua.plugin.ocr.ncnn")  -- 切到 ncnn
   ocr.use()                                -- 恢复默认（第一个注册的）
   ```

## 构建参数（build.ps1）

| 参数 | 说明 |
|------|------|
| `-NdkDir <路径>` | 显式指定 NDK 根目录。**多 NDK 共存时必传**：脚本按目录名排序取最后一个，本机同时装有 `25.2.9519653` 与 `28.2.13676358` 时会选中 28.2，实测其 cmake 配置报 `CMAKE_CXX_COMPILER not set`。推荐写法：`-NdkDir <SDK>\ndk\25.2.9519653` |
| `-X86_64` | 额外构建 x86_64 so 并打进 zip（`lib/x86_64/libncnnocr.so`）。**x86_64 开发模拟器上需要**（见下节）；真机 arm64 无感——安装时按 `SUPPORTED_ABIS` 优先级只解压命中的 ABI |
| `-ArmV7` | 额外构建 armeabi-v7a |
| `-SkipNative` | 跳过 NDK 编译，直接复用 `build/jni/<abi>/libncnnocr.so`（native 步骤在别处完成后只重打包） |
| `-NcnnDir <路径>` | ncnn 预编译包目录，默认 `third_party\ncnn-android` |
| `-SdkDir <路径>` | SDK 目录，默认从 `autolua2/local.properties` 的 `sdk.dir` 读取 |

### x86_64 模拟器（Android 9 + libnb 转译层）构建与运行注意

**脚本本身的两个坑（2026-09-15 实测踩中，已修复，改动脚本时注意保持）：**

1. **build.ps1 必须保持 UTF-8 **BOM** 编码**：脚本含中文注释，PS5 对无 BOM 的
   UTF-8 文件按系统 ANSI（GBK）解码，中文注释的字节对会错位解析出引号/大括号
   等语法字符，导致后续变量赋值行被吞、cmake 收到字面 `$变量` 参数
   （CMakeCache 里出现 `CMAKE_MAKE_PROGRAM=$ninjaExe` 字面量即此症状）。
   用编辑器改动脚本后请确认仍保存为 UTF-8 with BOM。
2. **cmake 的 `-D` 参数一律用括号表达式拼接传值**（`("-DANDROID_ABI=" + $abi)`），
   不用裸 token（`-DANDROID_ABI=$abi`）。实测部分 PS5 环境下裸 token 变量不展开，
   `ANDROID_ABI` 变成字面 `$abi` → toolchain 编译器路径无效 → 报
   `CMAKE_CXX_COMPILER not set, after EnableLanguage`。

**模拟器环境问题**：该类模拟器（Android 9 + libnb 转译层）对 **app 私有目录的 so
存在 JNI 绑定异常**——`System.load` 成功，但调用 native 方法报
`No implementation found`（转译层对私有目录 dlopen/dlsym 的处理缺陷）。
标准 ARM 真机无此问题。

- 插件侧已适配：`NcnnOcrPlugin.onInit` 经**插件自身 classloader** 直接
  `System.loadLibrary("ncnnocr")`（库与插件类同属一个类加载器——ART 按类加载器
  匹配 native 方法所属库，宿主侧 `System.load` 注册的库插件类绑定不到）；
  DexClassLoader 搜索路径由宿主 instantiate 注入：**宿主 nativeLibraryDir 优先 +
  插件 so 根目录**。
- 因此模拟器上还需要把 x86_64 so 预置进宿主 APK（已做）：
  `app/src/main/jniLibs/x86_64/libncnnocr.so` ← 拷自本插件 `build/jni/x86_64/`。
  真机 arm64 在宿主目录 miss 该 so → 自然命中 zip 内解压产物，自包含语义不变。

**手动编译兜底**（标准路径仍报错时的最后手段，正常情况不需要）：

  ```powershell
  $SDK = "G:\Android\SDK"; $NDK = "$SDK\ndk\25.2.9519653"
  $CMAKE = "$SDK\cmake\3.22.1\bin\cmake.exe"; $NINJA = "$SDK\cmake\3.22.1\bin\ninja.exe"
  foreach ($abi in @("arm64-v8a", "x86_64")) {
      & $CMAKE -G Ninja -B "build\jni\$abi" -S cpp `
          ("-DCMAKE_MAKE_PROGRAM=" + $NINJA) `
          ("-DCMAKE_TOOLCHAIN_FILE=" + $NDK + "\build\cmake\android.toolchain.cmake") `
          ("-DANDROID_ABI=" + $abi) -DANDROID_PLATFORM=android-22 -DANDROID_STL=c++_static `
          ("-DNCNN_DIR=" + (Join-Path (Get-Location) "third_party\ncnn-android"))
      & $CMAKE --build "build\jni\$abi"
  }
  .\build.ps1 -SkipNative -X86_64 -NdkDir $NDK
  ```

## 模型分发（三通道，onInit 时按序查找）

1. **classes.jar 资源** `models/*`（推荐）：build.ps1 打包时内嵌，随 zip 过 sha256，
   首次 onInit 自动释放到 `plugin_data/<id>/models/`
2. **`plugin_data/<id>/models/`**：上次释放残留，或 root 手工 push
3. **`/data/local/tmp/autolua_ocr_models/`**：开发迭代通道——换模型免重打 zip，
   push 后重装插件（或重启引擎触发 onInit）即可；沙箱进程读不到自动跳过

## 实现要点

- **单 so 交付**：libncnnocr.so 静态链入 libncnn.a + `-static-openmp`，
  无 libncnn.so 动态依赖，Android 9+ 私有目录 System.load 无 namespace 问题
- **det**：长边限 960、对齐 32；BGR 通道序 + ImageNet RGB 顺序统计量（对齐官方
  cpp_infer，训练即如此）；DB 后处理 v1 用轴对齐 bbox 近似（连通域 + unclip
  `d=area*ratio/perim`），水平文本与官方多边形版等价
- **rec**：高 48、宽 clamp [16,320] 右补 0；CTC 贪心解码（blank=0，space=末位）；
  输出布局按 `c=1,h=T,w=C` 读取并校验类别数 = 字典行数+2
- **blob 名**：转换后核对 [ncnn_ocr.cpp](cpp/ncnn_ocr.cpp) 顶部 4 个常量
  （MODEL_CONVERT.md 第 5 步），运行期有兜底候选 + 日志
- **线程安全**：ncnn Net 非并发安全，recognize 全程持互斥锁串行
- 帧格式：宿主传入整帧 RGBA（与图色命令同一坐标系），识别区域 rect 在帧内坐标

## 已知限制（v1）

- 大角度旋转文本框会偏大（DB 后处理轴对齐近似）；游戏 UI 横排文本无影响
- rec 固定 320×48 输入（v4 mobile 官方配置）；超长文本行水平压缩
- 未做 int8 量化 / ncnn2mem 模型加密（MODEL_CONVERT.md 附录有路线）
- 多 OCR 插件并存：`getOcrPlugin()` 默认取第一个 ocr provider，可用 Lua
  `ocr.use(id)` / `ocr.list()` / `ocr.active()` 切换与查询（路线A 门面+切换）；
  x86_64 转译模拟器需按上文"构建参数"预置宿主 jniLibs so
