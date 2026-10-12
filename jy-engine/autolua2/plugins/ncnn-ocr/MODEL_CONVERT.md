# PP-OCR → ncnn 模型转换清单（ncnn-ocr 插件）

目标产物（放进 `plugins/ncnn-ocr/models/`，5 个文件缺一不可）：

```
models/
├── det.ncnn.param        # det(DBNet) 网络结构   ~100~300KB
├── det.ncnn.bin          # det 权重              ~3~5MB
├── rec.ncnn.param        # rec(CRNN) 网络结构    ~200~500KB
├── rec.ncnn.bin          # rec 权重              ~8~12MB
└── ppocr_keys_v1.txt     # 中文字典（6623 行）   ~200KB
```

推荐模型：**PP-OCRv4 mobile**（中文，速度/精度均衡，全屏 det ~100ms + 单框 rec ~10ms 级，arm64）。

---

## 第 0 步：工具准备（一次性）

| 工具 | 用途 | 获取 |
|---|---|---|
| Python 3.8+ + `pip install onnx onnxsim paddle2onnx` | paddle→onnx、onnx 化简 | pip |
| pnnx（预编译包，**无需自己编 ncnn tools**） | onnx→ncnn | https://github.com/Tencent/ncnn/releases 下 `pnnx-YYYYMMDD-windows.zip` |
| ncnn Android 预编译包（CPU 版） | 插件 so 链接用 | https://github.com/Tencent/ncnn/releases 下 `ncnn-YYYYMMDD-android.zip`（**不是** vulkan 版），解压到 `plugins/ncnn-ocr/third_party/ncnn-android` |

> 备选转换器：自己编 ncnn（`-DNCNN_BUILD_TOOLS=ON`）用 `build\tools\onnx\onnx2ncnn.exe`；
> pnnx 装不上时的等价路径，命令见第 4 步注释。

> 注意：git 全局 socks5 代理常不在线，下载 release 走直连或镜像前缀 `https://ghfast.top/https://github.com/...`。

## 第 1 步：下载官方推理模型

PaddleOCR 仓库（https://github.com/PaddlePaddle/PaddleOCR）doc docs/models 列表 → 下载解压：

- **det**：`ch_PP-OCRv4_det_infer`（内含 `inference.pdmodel` + `inference.pdiparams`）
- **rec**：`ch_PP-OCRv4_rec_infer`
- **字典**：仓库 `ppocr/utils/ppocr_keys_v1.txt`

> 纯英文数字场景可换 `en_PP-OCRv4_rec_infer`（字典不同，流程相同）；
> PP-OCRv3 / v5 同流程。**服务器版（非 mobile）模型也能转，但体积/耗时大数倍，不推荐。**

## 第 2 步：paddle → onnx

```bash
paddle2onnx --model_dir ch_PP-OCRv4_det_infer \
    --model_filename inference.pdmodel \
    --params_filename inference.pdiparams \
    --save_file det.onnx \
    --opset_version 11 \
    --enable_onnx_checker True

paddle2onnx --model_dir ch_PP-OCRv4_rec_infer \
    --model_filename inference.pdmodel \
    --params_filename inference.pdiparams \
    --save_file rec.onnx \
    --opset_version 11 \
    --enable_onnx_checker True
```

> rec 报 op 不支持时改 `--opset_version 13` 再试。

## 第 3 步：onnx 化简（必须，onnx2ncnn/pnnx 都需要）

```bash
python -m onnxsim det.onnx det_sim.onnx
python -m onnxsim rec.onnx rec_sim.onnx
```

## 第 4 步：onnx → ncnn

```bash
pnnx det_sim.onnx fp16=0
pnnx rec.onnx inputshape=[1,3,48,320]f32 fp16=0
```

**`fp16=0` 必须带（2026-09-18 实测踩坑）**：pnnx 默认 `fp16=1` 把权重存成
半精度——ncnn 的 fp16 权重主要面向 ARM，x86（转译模拟器/PC ncnn）推理精度
崩坏，det 概率图 max 只有 ~0.17（正常应为 1.0）、识别全废且无任何报错。
判别方法：`det.ncnn.bin` 体积约为 fp32 权重的一半 = fp16 存储，重转。

**rec 必须指定静态 `inputshape`**：rec.onnx 输入宽度为动态维度，pnnx 直接转
会在 onnxruntime 校验时报 `Reshape ... cannot be reshaped` 失败。插件 rec
前处理固定输出 48×320（右侧补 0），故静态化为 `[1,3,48,320]`。
det 输入本身动态（长边限 960 + 32 对齐），无需指定。

改名并放入 `plugins/ncnn-ocr/models/`：

```
det_sim.ncnn.param → models/det.ncnn.param
det_sim.ncnn.bin   → models/det.ncnn.bin
rec_sim.ncnn.param → models/rec.ncnn.param（rec.onnx 直转时产物名为 rec.ncnn.*）
rec_sim.ncnn.bin   → models/rec.ncnn.bin
```

> 备选 onnx2ncnn：
> `onnx2ncnn.exe det_sim.onnx det.ncnn.param det.ncnn.bin`（工具在 ncnn 编译产物 `tools/onnx/`）。

## 第 5 步：核对 blob 名（重要，转换后必做）

打开 `det.ncnn.param` / `rec.ncnn.param` 文本：

- **输入名**：第一层 `Input` 行里的 blob 名（onnx2ncnn 常见为 `x`；pnnx 常见 `in0`）
- **输出名**：最后一层的输出 blob 名（onnx2ncnn 常见 `save_infer_model/scale_0.tmp_1`(det) /
  `...tmp_0`(rec)；pnnx 常见 `out0`）

与 [ncnn_ocr.cpp](cpp/ncnn_ocr.cpp) 顶部 4 个常量比对，不一致就改：

```cpp
static const char* DET_INPUT_BLOB  = "x";
static const char* DET_OUTPUT_BLOB = "save_infer_model/scale_0.tmp_1";
static const char* REC_INPUT_BLOB  = "x";
static const char* REC_OUTPUT_BLOB = "save_infer_model/scale_0.tmp_0";
```

（输出名留错时运行期有兜底候选列表 + logcat 提示，但别依赖它。）

## 第 6 步：sanity check

| 检查 | 期望 |
|---|---|
| `det.ncnn.param` 首行 magic | `7767517` |
| det 权重体积 | 3~5MB（v4 mobile） |
| rec 权重体积 | 8~12MB |
| 字典行数 | 6623（`find /c /v "" ppocr_keys_v1.txt`） |
| param/文本文件编码 | UTF-8 无 BOM（param 是 ASCII，字典含中文） |

## 第 7 步：构建验证

```powershell
cd plugins\ncnn-ocr
.\build.ps1          # 模型会打进 classes.jar 资源
```

安装后 logcat 过滤 `NcnnOcr`：

- `nativeInit: dict=6623 classes=6625 det/rec loaded=true` → 模型/字典就绪
- `det prob dims c=1 WxH min=... max=...` → 概率图统计（识别一次后打印）：
  **max 应接近 1.0、mean 数个百分点**；max 只有 ~0.1x / mean=0.0000 即 fp16 坑（第 4 步）
- `rec output size mismatch ... expectC=6625` → 字典与 rec 模型不匹配（换了模型没换字典）
- `det input blob 'x' not found` → 第 5 步 blob 名没改

本地基准对照（可选，装 `pip install onnxruntime`）：`autolua2/tools/diag_det_onnx.py`
用 onnx 原模型 + cpp 同款预处理/后处理跑同一张图，logcat 的 det 概率图统计应与
其输出一致（不一致 = ncnn 模型转换问题，一致但 0 框 = 后处理/输入问题）。

## 附：后续可选优化（v1 不做）

- **int8 量化**：ncnn2table + ncnn2int8，体积/速度再砍 ~4x，精度需验证
- **ncnn2mem**：param/bin 合并加密进 so，防模型白嫖
- **方向分类 cls 模型**：识别前多一步 180° 判断（游戏 UI 文本基本不需要）
- **旋转文本**：v1 DB 后处理为轴对齐 bbox 近似，大角度文本框会偏大；需要时升级
  最小外接矩形（凸包 + 旋转卡壳，~200 行）
