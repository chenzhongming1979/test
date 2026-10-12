// ncnn_ocr.cpp —— PP-OCR(ncnn) OCR 插件推理实现（单文件）
//
// 流程：RGBA 帧 → 裁剪识别区域 → det(DBNet) 概率图 → DB 后处理出文本框 →
//       逐框裁剪 → rec(CRNN) → CTC 贪心解码 → OcrTextBlock 列表
//
// 前后处理对齐 PaddleOCR 官方 C++ 部署（deploy/cpp_infer）的行为：
// - det：限制长边 960、短边取整到 32 的倍数（最小 32）；
//   归一化统计量为 ImageNet RGB 顺序，直接作用在 BGR 通道上（官方即如此，训练一致）。
// - rec：等比缩放到高 48、宽 clamp 到 [16,320]，右侧补 0（等价于原图 127.5 灰），
//   归一化 (x/255 - 0.5) / 0.5，BGR 通道序。
// - DB 后处理（v1 轴对齐近似）：阈值 0.3 二值化 → 连通域 bbox → 分数过滤 0.5 →
//   unclip：d = area * ratio / perimeter（Clipper delta 语义，每边外扩 d）。
//   水平文本场景与官方多边形版本等价；大角度旋转文本会有框偏大误差（README 已注明）。
//
// 模型转换后务必核对 cpp 顶部 4 个 blob 名常量（见 MODEL_CONVERT.md 第 5 步）。

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "platform.h"
#include "net.h"
#include "mat.h"

#define LOG_TAG "NcnnOcr"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ==================== 参数区：转换后按 param 文件核对 ====================
// 本次模型（RapidOCR ONNX → pnnx 20260526 → ncnn）实测 blob 名：
//   det: Input in0 → Deconvolution out0；rec: Input in0 → Softmax out0
// 换模型/转换器后须重新核对（方法见 MODEL_CONVERT.md 第 5 步）。
static const char* DET_INPUT_BLOB  = "in0";
static const char* DET_OUTPUT_BLOB = "out0";
static const char* REC_INPUT_BLOB  = "in0";
static const char* REC_OUTPUT_BLOB = "out0";

// det(DBNet) 参数，对齐 PP-OCR mobile 默认
static const float DB_THRESH       = 0.3f;   // 概率图二值化阈值
static const float DB_BOX_THRESH   = 0.5f;   // 框内平均分过滤
static const float DB_UNCLIP_RATIO = 1.6f;   // unclip 比例（官方 mobile det 1.5~1.6）
static const int   DB_MIN_SIZE     = 3;      // 框最小边
static const int   DET_LIMIT_SIDE  = 960;    // det 输入长边上限

// rec 参数（ch_PP-OCRv3/v4 mobile rec）
static const int   REC_IMG_H     = 48;
static const int   REC_IMG_W_MAX = 320;
static const int   REC_IMG_W_MIN = 16;

// det 归一化统计量（BGR 通道序应用，见文件头注释）
static const float DET_MEAN[3] = {0.485f, 0.456f, 0.406f};
static const float DET_STD[3]  = {0.229f, 0.224f, 0.225f};

// rec 归一化
static const float REC_MEAN = 0.5f;
static const float REC_STD  = 0.5f;

// ==================== 全局状态 ====================
static ncnn::Net* g_det = nullptr;
static ncnn::Net* g_rec = nullptr;
// 字典：g_dict[i] 对应类别 idx=i+1（0 为 CTC blank）；space 占 idx = g_dict.size()+1
static std::vector<std::string> g_dict;
static std::mutex g_mutex;    // ncnn::Net 非并发安全，recognize 串行化
static bool g_ready = false;

// ==================== 基础辅助 ====================

static bool readFileAll(const char* path, std::vector<unsigned char>& out) {
    FILE* f = fopen(path, "rb");
    if (!f) {
        return false;
    }
    if (fseek(f, 0, SEEK_END) != 0) {
        fclose(f);
        return false;
    }
    long n = ftell(f);
    if (n <= 0) {
        fclose(f);
        return false;
    }
    rewind(f);
    out.resize((size_t)n);
    size_t rd = fread(out.data(), 1, (size_t)n, f);
    fclose(f);
    return rd == (size_t)n;
}

/** 读 ppocr_keys_v1.txt：按行拆分（UTF-8 原样保留），跳过空行 */
static bool loadDict(const char* path, std::vector<std::string>& dict) {
    std::vector<unsigned char> buf;
    if (!readFileAll(path, buf) || buf.empty()) {
        return false;
    }
    const char* p = reinterpret_cast<const char*>(buf.data());
    size_t total = buf.size();
    size_t start = 0;
    for (size_t i = 0; i <= total; i++) {
        if (i == total || p[i] == '\n') {
            size_t end = i;
            if (end > start && p[end - 1] == '\r') {
                end--;
            }
            if (end > start) {
                dict.emplace_back(p + start, end - start);
            }
            start = i + 1;
        }
    }
    return !dict.empty();
}

/**
 * 从 RGBA 帧全帧坐标系 (fx, fy) 处双线性采样，输出 0..255 的 B,G,R 三通道值。
 * 越界坐标 clamp 到边缘。
 */
static inline void sampleBilinearBGR(const unsigned char* frame, int fw, int fh,
                                     float fx, float fy, float bgr[3]) {
    if (fx < 0) fx = 0;
    if (fy < 0) fy = 0;
    if (fx > fw - 1) fx = fw - 1;
    if (fy > fh - 1) fy = fh - 1;
    const int x0 = (int)fx, y0 = (int)fy;
    int x1 = x0 + 1, y1 = y0 + 1;
    if (x1 > fw - 1) x1 = fw - 1;
    if (y1 > fh - 1) y1 = fh - 1;
    const float ax = fx - x0, ay = fy - y0;
    const float w00 = (1 - ax) * (1 - ay), w10 = ax * (1 - ay);
    const float w01 = (1 - ax) * ay, w11 = ax * ay;

    const unsigned char* p00 = frame + ((size_t)y0 * fw + x0) * 4;
    const unsigned char* p10 = frame + ((size_t)y0 * fw + x1) * 4;
    const unsigned char* p01 = frame + ((size_t)y1 * fw + x0) * 4;
    const unsigned char* p11 = frame + ((size_t)y1 * fw + x1) * 4;
    // RGBA → BGR
    bgr[0] = p00[2] * w00 + p10[2] * w10 + p01[2] * w01 + p11[2] * w11; // B
    bgr[1] = p00[1] * w00 + p10[1] * w10 + p01[1] * w01 + p11[1] * w11; // G
    bgr[2] = p00[0] * w00 + p10[0] * w10 + p01[0] * w01 + p11[0] * w11; // R
}

// ==================== det 前处理 ====================

/** 计算 det 输入尺寸：长边限 960，两边取整到 32 的倍数（最小 32） */
static void detTargetSize(int cw, int ch, int& dw, int& dh) {
    int maxSide = cw > ch ? cw : ch;
    float ratio = maxSide > DET_LIMIT_SIDE ? (float)DET_LIMIT_SIDE / maxSide : 1.0f;
    int rw = (int)lroundf(cw * ratio);
    int rh = (int)lroundf(ch * ratio);
    dw = rw / 32 * 32;
    dh = rh / 32 * 32;
    if (dw < 32) dw = 32;
    if (dh < 32) dh = 32;
}

/**
 * 构建 det 输入 Mat：从 RGBA 帧 (cx,cy,cw,ch) 区域双线性缩放到 (dw,dh)，
 * BGR 通道序 + 官方统计量归一化，CHW 布局。
 */
static bool buildDetInput(const unsigned char* frame, int fw, int fh,
                          int cx, int cy, int cw, int ch,
                          int dw, int dh, ncnn::Mat& in) {
    in.create(dw, dh, 3);
    if (in.empty()) {
        return false;
    }
    const float sx = (float)cw / dw;
    const float sy = (float)ch / dh;
    for (int y = 0; y < dh; y++) {
        float fy = (y + 0.5f) * sy - 0.5f + cy;
        float* pb = in.channel(0).row(y);
        float* pg = in.channel(1).row(y);
        float* pr = in.channel(2).row(y);
        for (int x = 0; x < dw; x++) {
            float fx = (x + 0.5f) * sx - 0.5f + cx;
            float bgr[3];
            sampleBilinearBGR(frame, fw, fh, fx, fy, bgr);
            pb[x] = (bgr[0] / 255.0f - DET_MEAN[0]) / DET_STD[0];
            pg[x] = (bgr[1] / 255.0f - DET_MEAN[1]) / DET_STD[1];
            pr[x] = (bgr[2] / 255.0f - DET_MEAN[2]) / DET_STD[2];
        }
    }
    return true;
}

// ==================== det DB 后处理 ====================

struct DbBox {
    int x, y, w, h;   // 帧坐标
    float score;      // 框内平均置信度
};

/**
 * DB 后处理（v1 轴对齐近似）：
 * 概率图二值化 → 4 邻域连通域 → bbox+分数过滤 → unclip 外扩 →
 * 映射回裁剪坐标 → 平移到帧坐标。
 */
static void dbPostprocess(const ncnn::Mat& prob, int mapW, int mapH,
                          int cx, int cy,
                          float scaleBackX, float scaleBackY,
                          std::vector<DbBox>& out) {
    const int n = mapW * mapH;
    std::vector<unsigned char> bin(n, 0);
    for (int i = 0; i < n; i++) {
        bin[i] = prob[i] > DB_THRESH ? 1 : 0;
    }
    std::vector<int> visited(n, 0);
    std::vector<int> stack;
    stack.reserve(4096);

    for (int y = 0; y < mapH; y++) {
        for (int x = 0; x < mapW; x++) {
            const int idx0 = y * mapW + x;
            if (!bin[idx0] || visited[idx0]) {
                continue;
            }
            // ---- 洪泛收集连通域：bbox + 像素数 + 概率和 ----
            int minX = x, maxX = x, minY = y, maxY = y;
            long cnt = 0;
            double probSum = 0;
            stack.clear();
            stack.push_back(idx0);
            visited[idx0] = 1;
            while (!stack.empty()) {
                int idx = stack.back();
                stack.pop_back();
                int px = idx % mapW;
                int py = idx / mapW;
                cnt++;
                probSum += prob[idx];
                if (px < minX) minX = px;
                if (px > maxX) maxX = px;
                if (py < minY) minY = py;
                if (py > maxY) maxY = py;
                if (px > 0 && bin[idx - 1] && !visited[idx - 1]) { visited[idx - 1] = 1; stack.push_back(idx - 1); }
                if (px < mapW - 1 && bin[idx + 1] && !visited[idx + 1]) { visited[idx + 1] = 1; stack.push_back(idx + 1); }
                if (py > 0 && bin[idx - mapW] && !visited[idx - mapW]) { visited[idx - mapW] = 1; stack.push_back(idx - mapW); }
                if (py < mapH - 1 && bin[idx + mapW] && !visited[idx + mapW]) { visited[idx + mapW] = 1; stack.push_back(idx + mapW); }
            }
            if (cnt < DB_MIN_SIZE) {
                continue;
            }
            float score = (float)(probSum / cnt);
            if (score < DB_BOX_THRESH) {
                continue;
            }
            // ---- unclip：d = area*ratio/perimeter，每边外扩 d（Clipper delta 语义）----
            const float bw = (float)(maxX - minX + 1);
            const float bh = (float)(maxY - minY + 1);
            const float area = bw * bh;
            const float perim = 2.0f * (bw + bh);
            const float d = perim > 0 ? area * DB_UNCLIP_RATIO / perim : 0.f;
            float ux0 = minX - d, uy0 = minY - d;
            float ux1 = maxX + 1 + d, uy1 = maxY + 1 + d;
            // clip 到概率图范围
            if (ux0 < 0) ux0 = 0;
            if (uy0 < 0) uy0 = 0;
            if (ux1 > mapW) ux1 = (float)mapW;
            if (uy1 > mapH) uy1 = (float)mapH;
            // 概率图坐标 → 裁剪区域坐标
            float fx0 = ux0 * scaleBackX, fy0 = uy0 * scaleBackY;
            float fx1 = ux1 * scaleBackX, fy1 = uy1 * scaleBackY;
            int bx0 = (int)lroundf(fx0), by0 = (int)lroundf(fy0);
            int bx1 = (int)lroundf(fx1), by1 = (int)lroundf(fy1);
            int bwid = bx1 - bx0, bhei = by1 - by0;
            if (bwid < DB_MIN_SIZE || bhei < DB_MIN_SIZE) {
                continue;
            }
            // 裁剪坐标 → 帧坐标（钳制由调用方 rect 保证在界）
            DbBox box;
            box.x = cx + bx0;
            box.y = cy + by0;
            box.w = bwid;
            box.h = bhei;
            box.score = score;
            out.push_back(box);
        }
    }
    // 按阅读顺序排序：先 y 后 x
    std::sort(out.begin(), out.end(), [](const DbBox& a, const DbBox& b) {
        if (a.y != b.y) return a.y < b.y;
        return a.x < b.x;
    });
}

// ==================== rec 前处理 ====================

/**
 * 构建单框 rec 输入：框区域等比缩放到高 48、宽 clamp [16,320]，
 * 右侧补 0 到 320 宽，BGR + (x/255-0.5)/0.5 归一化，CHW。
 */
static bool buildRecInput(const unsigned char* frame, int fw, int fh,
                          const DbBox& box, ncnn::Mat& in) {
    const float aspect = (float)box.w / (float)box.h;
    int rw = (int)lroundf(REC_IMG_H * aspect);
    if (rw < REC_IMG_W_MIN) rw = REC_IMG_W_MIN;
    if (rw > REC_IMG_W_MAX) rw = REC_IMG_W_MAX;

    in.create(REC_IMG_W_MAX, REC_IMG_H, 3);
    if (in.empty()) {
        return false;
    }
    const float sx = (float)box.w / rw;
    const float sy = (float)box.h / REC_IMG_H;
    for (int y = 0; y < REC_IMG_H; y++) {
        float fy = (y + 0.5f) * sy - 0.5f + box.y;
        float* pb = in.channel(0).row(y);
        float* pg = in.channel(1).row(y);
        float* pr = in.channel(2).row(y);
        for (int x = 0; x < REC_IMG_W_MAX; x++) {
            if (x >= rw) {
                pb[x] = pg[x] = pr[x] = 0.0f; // 补 0 ≡ 原图 127.5 灰
                continue;
            }
            float fx = (x + 0.5f) * sx - 0.5f + box.x;
            float bgr[3];
            sampleBilinearBGR(frame, fw, fh, fx, fy, bgr);
            pb[x] = (bgr[0] / 255.0f - REC_MEAN) / REC_STD;
            pg[x] = (bgr[1] / 255.0f - REC_MEAN) / REC_STD;
            pr[x] = (bgr[2] / 255.0f - REC_MEAN) / REC_STD;
        }
    }
    return true;
}

// ==================== CTC 解码 ====================

/**
 * 贪心 CTC：逐时间步 argmax，去重连续相同、去 blank(0)。
 * 类别映射：idx-1 < dict.size() → g_dict[idx-1]；idx == dict.size()+1 → 空格。
 * conf = 保留字符位置 argmax 概率的均值。
 *
 * 输出布局：onnx rank-3 (1,T,C) 经 onnx2ncnn 映射为 c=1,h=T,w=C（rank-2 同理 h=T,w=C），
 * 故统一取 C=out.w、T=out.h；若 total 对不上预期类别数则在日志报布局异常。
 */
static bool ctcDecode(const ncnn::Mat& out, std::string& text, float& conf) {
    const int expectC = (int)g_dict.size() + 2; // blank + dict + space
    int C = out.w, T = out.h;
    if (out.c > 1) {
        ALOGE("rec output unexpected layout: dims=%d c=%d h=%d w=%d (expect c=1,h=T,w=C)",
              out.dims, out.c, out.h, out.w);
        return false;
    }
    if ((size_t)T * C != out.total() || C != expectC) {
        ALOGE("rec output size mismatch: T=%d C=%d total=%zu expectC=%d", T, C, out.total(), expectC);
        return false;
    }
    text.clear();
    double confSum = 0;
    int kept = 0;
    int prev = -1;
    for (int t = 0; t < T; t++) {
        const float* row = out.row(t);
        int best = 0;
        float bestP = row[0];
        for (int c = 1; c < C; c++) {
            if (row[c] > bestP) {
                bestP = row[c];
                best = c;
            }
        }
        if (best != 0 && best != prev) {
            if (best - 1 < (int)g_dict.size()) {
                text += g_dict[best - 1];
            } else {
                text += " "; // space 类
            }
            confSum += bestP;
            kept++;
        }
        prev = best;
    }
    conf = kept > 0 ? (float)(confSum / kept) : 0.f;
    return true;
}

// ==================== 推理主流程 ====================

/** det 输出 blob 名兜底：主名失败时尝试常见候选 */
static bool extractDet(ncnn::Extractor& ex, ncnn::Mat& out) {
    if (ex.extract(DET_OUTPUT_BLOB, out) == 0 && !out.empty()) return true;
    static const char* FB[] = {"save_infer_model/scale_0.tmp_0", "output", "pnnx_output_0", "out"};
    for (const char* n : FB) {
        if (ex.extract(n, out) == 0 && !out.empty()) {
            ALOGI("det output blob fallback -> %s", n);
            return true;
        }
    }
    return false;
}

static bool extractRec(ncnn::Extractor& ex, ncnn::Mat& out) {
    if (ex.extract(REC_OUTPUT_BLOB, out) == 0 && !out.empty()) return true;
    static const char* FB[] = {"save_infer_model/scale_0.tmp_1", "output", "pnnx_output_0", "out"};
    for (const char* n : FB) {
        if (ex.extract(n, out) == 0 && !out.empty()) {
            ALOGI("rec output blob fallback -> %s", n);
            return true;
        }
    }
    return false;
}

struct RecResult {
    std::string text;
    float conf;
    // 结果随行携带帧坐标（rec 可能逐框失败，不能与 boxes 下标对齐）
    int x, y, w, h;
};

static bool recognizeAll(const unsigned char* frame, int fw, int fh,
                         int rx, int ry, int rw, int rh,
                         std::vector<DbBox>& boxes, std::vector<RecResult>& recs) {
    // ---- det ----
    int dw, dh;
    detTargetSize(rw, rh, dw, dh);
    ncnn::Mat detIn;
    if (!buildDetInput(frame, fw, fh, rx, ry, rw, rh, dw, dh, detIn)) {
        return false;
    }
    ncnn::Extractor dex = g_det->create_extractor();
    dex.set_light_mode(true);
    if (dex.input(DET_INPUT_BLOB, detIn) != 0) {
        ALOGE("det input blob '%s' not found", DET_INPUT_BLOB);
        return false;
    }
    ncnn::Mat prob;
    if (!extractDet(dex, prob)) {
        ALOGE("det output blob not found");
        return false;
    }
    int mapW = prob.w, mapH = prob.h;
    if (mapW <= 0 || mapH <= 0) {
        return false;
    }
    // 诊断：概率图统计（Python 基准见 tools/diag_det_onnx.py：
    // 正常应 min≈0 max≈1 mean≈0.035 >0.3 像素数千级；全低分说明 ncnn 推理环节问题）
    {
        float pmin = 1e9f, pmax = -1e9f;
        double psum = 0;
        const size_t n = (size_t)prob.w * prob.h * prob.c;
        for (size_t i = 0; i < n; i++) {
            float v = prob[i];
            if (v < pmin) pmin = v;
            if (v > pmax) pmax = v;
            psum += v;
        }
        ALOGI("det prob dims c=%d %dx%d min=%.4f max=%.4f mean=%.4f",
              prob.c, mapW, mapH, pmin, pmax, n ? (float)(psum / n) : 0.f);
    }
    dbPostprocess(prob, mapW, mapH, rx, ry,
                  (float)rw / mapW, (float)rh / mapH, boxes);
    ALOGI("det boxes=%d", (int)boxes.size());

    // ---- rec 逐框 ----
    recs.reserve(boxes.size());
    for (const DbBox& box : boxes) {
        ncnn::Mat recIn;
        if (!buildRecInput(frame, fw, fh, box, recIn)) {
            continue;
        }
        ncnn::Extractor rex = g_rec->create_extractor();
        rex.set_light_mode(true);
        if (rex.input(REC_INPUT_BLOB, recIn) != 0) {
            ALOGE("rec input blob '%s' not found", REC_INPUT_BLOB);
            continue;
        }
        ncnn::Mat out;
        if (!extractRec(rex, out)) {
            continue;
        }
        RecResult r;
        if (ctcDecode(out, r.text, r.conf) && !r.text.empty()) {
            r.x = box.x;
            r.y = box.y;
            r.w = box.w;
            r.h = box.h;
            recs.push_back(r);
        }
    }
    return true;
}

// ==================== JNI ====================

static jstring toJString(JNIEnv* env, const std::string& s) {
    return env->NewStringUTF(s.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_autolua_plugin_ocr_ncnn_NcnnOcrPlugin_nativeInit(
        JNIEnv* env, jclass, jstring detParam, jstring detBin,
        jstring recParam, jstring recBin, jstring dictPath, jint numThreads) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (g_ready) {
        return JNI_TRUE;
    }
    const char* cDetParam = env->GetStringUTFChars(detParam, nullptr);
    const char* cDetBin = env->GetStringUTFChars(detBin, nullptr);
    const char* cRecParam = env->GetStringUTFChars(recParam, nullptr);
    const char* cRecBin = env->GetStringUTFChars(recBin, nullptr);
    const char* cDict = env->GetStringUTFChars(dictPath, nullptr);

    bool ok = loadDict(cDict, g_dict);
    if (!ok) {
        ALOGE("load dict failed: %s", cDict);
    }
    if (ok) {
        g_det = new ncnn::Net();
        g_det->opt.num_threads = numThreads > 0 ? numThreads : 4;
        g_det->opt.use_winograd_convolution = true;
        g_det->opt.use_sgemm_convolution = true;
        if (g_det->load_param(cDetParam) != 0) {
            ALOGE("det load_param failed: %s", cDetParam);
            ok = false;
        } else if (g_det->load_model(cDetBin) != 0) {
            ALOGE("det load_model failed: %s", cDetBin);
            ok = false;
        }
    }
    if (ok) {
        g_rec = new ncnn::Net();
        g_rec->opt.num_threads = numThreads > 0 ? numThreads : 4;
        g_rec->opt.use_winograd_convolution = true;
        g_rec->opt.use_sgemm_convolution = true;
        if (g_rec->load_param(cRecParam) != 0) {
            ALOGE("rec load_param failed: %s", cRecParam);
            ok = false;
        } else if (g_rec->load_model(cRecBin) != 0) {
            ALOGE("rec load_model failed: %s", cRecBin);
            ok = false;
        }
    }
    ALOGI("nativeInit: dict=%zu classes=%d det/rec loaded=%s",
          g_dict.size(), (int)g_dict.size() + 2, ok ? "true" : "false");

    env->ReleaseStringUTFChars(detParam, cDetParam);
    env->ReleaseStringUTFChars(detBin, cDetBin);
    env->ReleaseStringUTFChars(recParam, cRecParam);
    env->ReleaseStringUTFChars(recBin, cRecBin);
    env->ReleaseStringUTFChars(dictPath, cDict);

    if (!ok) {
        delete g_det;
        delete g_rec;
        g_det = nullptr;
        g_rec = nullptr;
        g_dict.clear();
        return JNI_FALSE;
    }
    g_ready = true;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_autolua_plugin_ocr_ncnn_NcnnOcrPlugin_nativeRecognize(
        JNIEnv* env, jclass, jbyteArray jframe, jint frameW, jint frameH,
        jint rectX, jint rectY, jint rectW, jint rectH) {
    if (!g_ready) {
        ALOGE("recognize before init");
        return nullptr;
    }
    jsize len = env->GetArrayLength(jframe);
    if (len < (jsize)((size_t)frameW * frameH * 4)) {
        ALOGE("frame size mismatch: len=%d need=%dx%d*4", len, frameW, frameH);
        return nullptr;
    }
    // 防御性钳制识别区域
    int x0 = rectX < 0 ? 0 : rectX;
    int y0 = rectY < 0 ? 0 : rectY;
    if (x0 > frameW - 1) x0 = frameW - 1;
    if (y0 > frameH - 1) y0 = frameH - 1;
    int w = rectW < 1 ? 1 : rectW;
    int h = rectH < 1 ? 1 : rectH;
    if (w > frameW - x0) w = frameW - x0;
    if (h > frameH - y0) h = frameH - y0;
    if (w < 4 || h < 4) {
        ALOGI("rect too small: %dx%d", w, h);
        return nullptr;
    }

    jbyte* frame = env->GetByteArrayElements(jframe, nullptr);
    if (!frame) {
        return nullptr;
    }

    std::vector<DbBox> boxes;
    std::vector<RecResult> recs;
    {
        std::lock_guard<std::mutex> lk(g_mutex);
        recognizeAll(reinterpret_cast<const unsigned char*>(frame), frameW, frameH,
                     x0, y0, w, h, boxes, recs);
    }
    env->ReleaseByteArrayElements(jframe, frame, JNI_ABORT);

    const int n = (int)recs.size();
    if (n <= 0) {
        return nullptr; // 空结果
    }
    // 打包 Object[]{String[] texts, int[] boxes(n*4), float[] confs}
    jclass objCls = env->FindClass("java/lang/Object");
    jobjectArray result = env->NewObjectArray(3, objCls, nullptr);

    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray texts = env->NewObjectArray(n, strCls, nullptr);
    for (int i = 0; i < n; i++) {
        env->SetObjectArrayElement(texts, i, toJString(env, recs[i].text));
    }

    jintArray jboxes = env->NewIntArray(n * 4);
    std::vector<jint> boxBuf(n * 4);
    for (int i = 0; i < n; i++) {
        boxBuf[4 * i + 0] = recs[i].x;
        boxBuf[4 * i + 1] = recs[i].y;
        boxBuf[4 * i + 2] = recs[i].w;
        boxBuf[4 * i + 3] = recs[i].h;
    }
    env->SetIntArrayRegion(jboxes, 0, n * 4, boxBuf.data());

    jfloatArray jconfs = env->NewFloatArray(n);
    std::vector<jfloat> confBuf(n);
    for (int i = 0; i < n; i++) {
        confBuf[i] = recs[i].conf;
    }
    env->SetFloatArrayRegion(jconfs, 0, n, confBuf.data());

    env->SetObjectArrayElement(result, 0, texts);
    env->SetObjectArrayElement(result, 1, jboxes);
    env->SetObjectArrayElement(result, 2, jconfs);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_autolua_plugin_ocr_ncnn_NcnnOcrPlugin_nativeDestroy(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lk(g_mutex);
    if (!g_ready) {
        return;
    }
    delete g_det;
    delete g_rec;
    g_det = nullptr;
    g_rec = nullptr;
    g_dict.clear();
    g_ready = false;
    ALOGI("nativeDestroy done");
}

// ==================== 动态注册（转译模拟器兼容） ====================
// x86_64 模拟器（houdini 半初始化 native bridge）下 JNI 静态符号（Java_...）
// dlsym 不可见，报 No implementation found；JNI_OnLoad + RegisterNatives 动态
// 注册不依赖 dlsym。真机静态导出保留兜底，两条路都通。
// 注意：JNI_OnLoad 里 FindClass 走系统类加载器，看不到插件 DexClassLoader 的类
// （必然失败）；必须清掉 pending exception 后优雅返回，否则后续 JNI 调用
// （NewGlobalRef 等）带 pending exception 直接 abort。注册不成时静态导出兜底。
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv((void**) &env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass cls = env->FindClass("com/autolua/plugin/ocr/ncnn/NcnnOcrPlugin");
    if (cls == nullptr) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        return JNI_VERSION_1_6; // 插件类不可见（预期）：静态 Java_ 导出兜底
    }
    static const JNINativeMethod kMethods[] = {
        {"nativeInit",
         "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)Z",
         (void*) &Java_com_autolua_plugin_ocr_ncnn_NcnnOcrPlugin_nativeInit},
        {"nativeRecognize", "([BIIIIII)[Ljava/lang/Object;",
         (void*) &Java_com_autolua_plugin_ocr_ncnn_NcnnOcrPlugin_nativeRecognize},
        {"nativeDestroy", "()V",
         (void*) &Java_com_autolua_plugin_ocr_ncnn_NcnnOcrPlugin_nativeDestroy},
    };
    jint rc = env->RegisterNatives(cls, kMethods, sizeof(kMethods) / sizeof(kMethods[0]));
    env->DeleteLocalRef(cls);
    if (rc != JNI_OK) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        return JNI_VERSION_1_6; // 同上：交由静态导出兜底
    }
    ALOGI("JNI_OnLoad: RegisterNatives ok");
    return JNI_VERSION_1_6;
}

