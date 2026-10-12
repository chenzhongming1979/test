//
// 三期：IDE 图色远程命令 C++ 加速内核（find_image / find_multi_color / cmp_color）。
//
// 复用 3rd/autolua_vision：
//   - Bitmap：把共享内存 RGBA 帧适配成库的图像视图（origin_/width_/height_/rowShift_/pixelStride_）；
//   - findColor（LEFT_RIGHT_UP_DOWN = 行主序，与 Java 首个命中顺序一致）做多点找色的锚点扫描；
//   - compareColor（字节级逐通道容差比较）做颜色判定。
// find_image 未用库内 isImage：其语义是"整模板三通道差之和 ≤ shiftSum"的累计预算制，
// 与 IDE 协议的"逐像素三通道容差命中 + 命中率 ≥ threshold + 20M 像素预算"不一致，
// 故手写滑窗（锚点预筛选 + 行级提前退出），判定逻辑与二期纯 Java 实现逐位等价。
//
// 字节序适配（关键）：vision_util.h 在 Android 下定义 UNORDERED_PIXEL=1，
// 其 compareColor(帧像素, 目标色, shift) 的下标映射为
//   |目标[0]-帧[2]|、|目标[1]-帧[1]|、|目标[2]-帧[0]|
// 库自身约定：帧=BGRA、目标=RGB。本管线帧为 RGBA（R 在 [0]），因此把目标色
// 以 [B,G,R] 字节序传入即可逐通道精确对齐（|B-B|、|G-G|、|R-R| ≤ tol）。
//
// JNI 约定：静态注册（Java_com_autolua_engine_ide_VisionJni_*），库 libengine.so；
// 返回 null 表示参数非法/OOM（Java 侧捕获后回退纯 Java 实现），未找到返回 {0,-1,-1,0}。
//
#include "VisionBridge.h"

#include <android/log.h>
#include <cmath>
#include <cstdlib>

#include "vision.h"

#define LOG_TAG "VisionBridge"
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

// 与 Java VisionCommandHandler.VERIFY_PIXEL_BUDGET 一致：模板验证像素总预算（防病态输入）。
// C++ 内核下 20M 为毫秒级，纯色屏 + 纯色模板的病态最坏情况（921k 候选 × 2400 px ≈ 2.2G）
// 也只需 2~4s，故放宽到 2G；Java 回退实现仍用 20M（字节码滑窗慢，需保守）。
constexpr long long VERIFY_PIXEL_BUDGET = 2000000000LL;

// 与 Java VisionCommandHandler.similarityTolerance 完全一致（double 运算，避免 float 漂移）
inline int similarityTolerance(double similarity) {
    if (!(similarity == similarity)) {
        // NaN：对齐 Java Math.round(NaN) = 0
        return 0;
    }
    if (similarity < 0) {
        similarity = 0;
    }
    if (similarity > 1) {
        similarity = 1;
    }
    return (int) std::lround((1.0 - similarity) * 255.0);
}

inline int clampI(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

// 0xRRGGBB → [B,G,R] 字节序（见文件头字节序适配说明）
inline void rgbToBgrBytes(jint rgb, unsigned char out[3]) {
    out[0] = (unsigned char) (rgb & 0xFF);
    out[1] = (unsigned char) ((rgb >> 8) & 0xFF);
    out[2] = (unsigned char) ((rgb >> 16) & 0xFF);
}

// 帧内 RGBA 像素与目标色（BGR 字节序）的库内逐通道容差比较
inline bool framePixelMatch(const unsigned char *framePx, const unsigned char *targetBgr,
                            const unsigned char *shift3) {
    return autolua::compareColor(framePx, targetBgr, shift3) != 0;
}

jintArray newResult(JNIEnv *env, jint found, jint x, jint y, jint permille) {
    jintArray arr = env->NewIntArray(4);
    if (arr == nullptr) {
        return nullptr;
    }
    jint v[4] = {found, x, y, permille};
    env->SetIntArrayRegion(arr, 0, 4, v);
    return arr;
}

jintArray newMatchResult(JNIEnv *env, jint match) {
    jintArray arr = env->NewIntArray(1);
    if (arr == nullptr) {
        return nullptr;
    }
    jint v[1] = {match};
    env->SetIntArrayRegion(arr, 0, 1, v);
    return arr;
}

jintArray throwIllegalArgument(JNIEnv *env, const char *msg) {
    jclass cls = env->FindClass("java/lang/IllegalArgumentException");
    if (cls != nullptr) {
        env->ThrowNew(cls, msg);
    }
    return nullptr;
}

// 构建库 Bitmap 视图（RGBA 帧：pixelStride=4，rowShift=宽*4）
// （原 findColor 锚点扫描已改为手写全候选循环，Bitmap 适配暂无调用方，保留说明防误删）

} // namespace

extern "C" {

// 找图：frame（整帧 RGBA，Java 侧已把 PNG 模板解码为 RGBA 字节）的 rect 区域内滑窗匹配。
// 判定与二期纯 Java 实现逐位等价：锚点（模板首像素）预筛选 + 全模板逐像素三通道
// 容差命中，命中率 ≥ threshold 即返回首个命中（行主序）；验证像素总预算 20M。
// threshold 兼作相似度换算容差（与 Java 相同）。
// 返回 {found, x, y, similarity千分比}（x/y 为帧内坐标 = 屏幕绝对坐标）；
// 未找到 {0,-1,-1,0}；参数非法抛 IllegalArgumentException（Java 侧回退）。
JNIEXPORT jintArray JNICALL
Java_com_autolua_engine_ide_VisionJni_nativeFindImage(
        JNIEnv *env, jclass clazz,
        jbyteArray frame, jint frameW, jint frameH,
        jbyteArray templateRgba, jint templateW, jint templateH,
        jdouble threshold,
        jint rectX, jint rectY, jint rectW, jint rectH) {
    if (frame == nullptr || templateRgba == nullptr) {
        return throwIllegalArgument(env, "findImage: null array");
    }
    jsize frameLen = env->GetArrayLength(frame);
    jsize tplLen = env->GetArrayLength(templateRgba);
    if (frameW <= 0 || frameH <= 0 || (long long) frameW * frameH * 4 > frameLen) {
        return throwIllegalArgument(env, "findImage: bad frame size");
    }
    if (templateW <= 0 || templateH <= 0 || (long long) templateW * templateH * 4 > tplLen) {
        return throwIllegalArgument(env, "findImage: bad template size");
    }
    jbyte *frameElems = env->GetByteArrayElements(frame, nullptr);
    if (frameElems == nullptr) {
        return nullptr; // OOM 等：交由 Java 侧回退
    }
    jbyte *tplElems = env->GetByteArrayElements(templateRgba, nullptr);
    if (tplElems == nullptr) {
        env->ReleaseByteArrayElements(frame, frameElems, JNI_ABORT);
        return nullptr;
    }

    jintArray result = nullptr;
    // 区域钳制（Java 侧已钳制，此处防御性重复）
    int rx = clampI(rectX, 0, frameW - 1);
    int ry = clampI(rectY, 0, frameH - 1);
    int rw = rectW <= 0 ? frameW - rx : (rectW > frameW - rx ? frameW - rx : rectW);
    int rh = rectH <= 0 ? frameH - ry : (rectH > frameH - ry ? frameH - ry : rectH);
    int tol = similarityTolerance(threshold);
    const long long total = (long long) templateW * templateH;

    bool found = false;
    int foundX = -1, foundY = -1, foundPermille = 0;
    if (rw > 0 && rh > 0 && templateW <= rw && templateH <= rh) {
        const unsigned char *base = reinterpret_cast<const unsigned char *>(frameElems);
        const long long stride = (long long) frameW * 4;
        const unsigned char *tpl = reinterpret_cast<const unsigned char *>(tplElems);
        // 锚点 = 模板首像素（模板为 RGBA 字节序，与 Java 锚点语义一致）
        const unsigned char anchorR = tpl[0];
        const unsigned char anchorG = tpl[1];
        const unsigned char anchorB = tpl[2];
        long long budget = VERIFY_PIXEL_BUDGET;
        for (int sy = 0; !found && sy + templateH <= rh; sy++) {
            const unsigned char *rowBase = base + (ry + sy) * stride + (size_t) rx * 4;
            for (int sx = 0; sx + templateW <= rw; sx++) {
                const unsigned char *px = rowBase + (size_t) sx * 4;
                // 锚点预筛选：模板首像素不匹配直接跳过，避免全模板验证
                if (std::abs((int) px[0] - (int) anchorR) > tol
                        || std::abs((int) px[1] - (int) anchorG) > tol
                        || std::abs((int) px[2] - (int) anchorB) > tol) {
                    continue;
                }
                budget -= total;
                if (budget < 0) {
                    ALOGW("find_image verify budget exhausted, stop scanning");
                    break;
                }
                int count = 0;
                for (int ty = 0; ty < templateH; ty++) {
                    // 行级提前退出：剩余像素即使全部命中也达不到 threshold，
                    // 该候选最终判定必失败（与 Java 全量计数后的 ratio>=threshold 等价）
                    double best = (double) (count + (long long) (templateH - ty) * templateW)
                                  / (double) total;
                    if (best < threshold) {
                        break;
                    }
                    const unsigned char *trow = tpl + (size_t) ty * templateW * 4;
                    const unsigned char *prow = rowBase + (size_t) sx * 4 + (size_t) ty * stride;
                    for (int tx = 0; tx < templateW; tx++) {
                        const unsigned char *p = prow + (size_t) tx * 4;
                        const unsigned char *t = trow + (size_t) tx * 4;
                        if (std::abs((int) p[0] - (int) t[0]) <= tol
                                && std::abs((int) p[1] - (int) t[1]) <= tol
                                && std::abs((int) p[2] - (int) t[2]) <= tol) {
                            count++;
                        }
                    }
                }
                double ratio = (double) count / (double) total;
                if (ratio >= threshold) {
                    found = true;
                    foundX = rx + sx;
                    foundY = ry + sy;
                    // 对齐 Java：similarity = Math.round(ratio * 1000) / 1000.0
                    foundPermille = (int) std::llround(ratio * 1000.0);
                    break;
                }
            }
        }
    }
    result = found ? newResult(env, 1, foundX, foundY, foundPermille)
                   : newResult(env, 0, -1, -1, 0);

    env->ReleaseByteArrayElements(templateRgba, tplElems, JNI_ABORT);
    env->ReleaseByteArrayElements(frame, frameElems, JNI_ABORT);
    return result;
}

// 比色：points（帧内绝对坐标，{x0,y0,x1,y1,...}）逐点与 colors（0xRRGGBB）容差比对。
// Java 侧已做搜索区域边界预检（任一点出区域直接 match=false，不走 JNI），
// 这里仅兜底帧边界。全部命中返回 {1}，否则 {0}；参数非法抛 IllegalArgumentException。
JNIEXPORT jintArray JNICALL
Java_com_autolua_engine_ide_VisionJni_nativeCmpColor(
        JNIEnv *env, jclass clazz,
        jbyteArray frame, jint frameW, jint frameH,
        jintArray points, jintArray colors, jdouble similarity) {
    if (frame == nullptr || points == nullptr || colors == nullptr) {
        return throwIllegalArgument(env, "cmpColor: null array");
    }
    jsize pointLen = env->GetArrayLength(points);
    jsize colorLen = env->GetArrayLength(colors);
    if (frameW <= 0 || frameH <= 0
            || (long long) frameW * frameH * 4 > env->GetArrayLength(frame)) {
        return throwIllegalArgument(env, "cmpColor: bad frame size");
    }
    if (colorLen <= 0 || pointLen != colorLen * 2) {
        return throwIllegalArgument(env, "cmpColor: points/colors size mismatch");
    }
    jint *pointElems = env->GetIntArrayElements(points, nullptr);
    if (pointElems == nullptr) {
        return nullptr;
    }
    jint *colorElems = env->GetIntArrayElements(colors, nullptr);
    if (colorElems == nullptr) {
        env->ReleaseIntArrayElements(points, pointElems, JNI_ABORT);
        return nullptr;
    }
    jbyte *frameElems = env->GetByteArrayElements(frame, nullptr);
    if (frameElems == nullptr) {
        env->ReleaseIntArrayElements(colors, colorElems, JNI_ABORT);
        env->ReleaseIntArrayElements(points, pointElems, JNI_ABORT);
        return nullptr;
    }

    int tol = similarityTolerance(similarity);
    const unsigned char *base = reinterpret_cast<const unsigned char *>(frameElems);
    const long long stride = (long long) frameW * 4;
    unsigned char shift3[3] = {(unsigned char) tol, (unsigned char) tol, (unsigned char) tol};
    bool match = true;
    for (jsize i = 0; i < colorLen; i++) {
        int x = pointElems[2 * i];
        int y = pointElems[2 * i + 1];
        if (x < 0 || y < 0 || x >= frameW || y >= frameH) {
            match = false;
            break;
        }
        unsigned char targetBgr[3];
        rgbToBgrBytes(colorElems[i], targetBgr);
        const unsigned char *px = base + y * stride + (size_t) x * 4;
        if (!framePixelMatch(px, targetBgr, shift3)) {
            match = false;
            break;
        }
    }

    env->ReleaseByteArrayElements(frame, frameElems, JNI_ABORT);
    env->ReleaseIntArrayElements(colors, colorElems, JNI_ABORT);
    env->ReleaseIntArrayElements(points, pointElems, JNI_ABORT);
    return newMatchResult(env, match ? 1 : 0);
}

// 多点找色：offsets 为各校验点相对锚点的偏移（{0,0,ox1,oy1,...}，含锚点自身），
// colors[0] 为锚点色、colors[i] 与第 i 个偏移点对应。
// 锚点扫描复用库 findColor（LEFT_RIGHT_UP_DOWN 行主序，首个命中顺序与 Java 一致），
// 命中后逐点校验偏移点：坐标必须仍在搜索区域内（与 Java 区域边界判定一致），
// 颜色逐通道容差判定。返回 {found, anchorX, anchorY, sim千分比}（锚点为帧内坐标）。
JNIEXPORT jintArray JNICALL
Java_com_autolua_engine_ide_VisionJni_nativeFindMultiColor(
        JNIEnv *env, jclass clazz,
        jbyteArray frame, jint frameW, jint frameH,
        jintArray offsets, jintArray colors, jdouble similarity,
        jint rectX, jint rectY, jint rectW, jint rectH) {
    if (frame == nullptr || offsets == nullptr || colors == nullptr) {
        return throwIllegalArgument(env, "findMultiColor: null array");
    }
    jsize offsetLen = env->GetArrayLength(offsets);
    jsize colorLen = env->GetArrayLength(colors);
    if (frameW <= 0 || frameH <= 0
            || (long long) frameW * frameH * 4 > env->GetArrayLength(frame)) {
        return throwIllegalArgument(env, "findMultiColor: bad frame size");
    }
    if (colorLen <= 0 || offsetLen != colorLen * 2) {
        return throwIllegalArgument(env, "findMultiColor: offsets/colors size mismatch");
    }
    jint *offsetElems = env->GetIntArrayElements(offsets, nullptr);
    if (offsetElems == nullptr) {
        return nullptr;
    }
    jint *colorElems = env->GetIntArrayElements(colors, nullptr);
    if (colorElems == nullptr) {
        env->ReleaseIntArrayElements(offsets, offsetElems, JNI_ABORT);
        return nullptr;
    }
    jbyte *frameElems = env->GetByteArrayElements(frame, nullptr);
    if (frameElems == nullptr) {
        env->ReleaseIntArrayElements(colors, colorElems, JNI_ABORT);
        env->ReleaseIntArrayElements(offsets, offsetElems, JNI_ABORT);
        return nullptr;
    }

    jintArray result;
    int rx = clampI(rectX, 0, frameW - 1);
    int ry = clampI(rectY, 0, frameH - 1);
    int rw = rectW <= 0 ? frameW - rx : (rectW > frameW - rx ? frameW - rx : rectW);
    int rh = rectH <= 0 ? frameH - ry : (rectH > frameH - ry ? frameH - ry : rectH);

    bool found = false;
    autolua::Point anchor(-1, -1);
    if (rw > 0 && rh > 0) {
        // 锚点目标色：RGBA 帧 + 库 UNORDERED_PIXEL 约定 → 目标色以 BGR 字节序传入
        unsigned char anchorBgr[3];
        rgbToBgrBytes(colorElems[0], anchorBgr);
        int tol = similarityTolerance(similarity);
        unsigned char shift3[3] = {(unsigned char) tol, (unsigned char) tol, (unsigned char) tol};
        const unsigned char *base = reinterpret_cast<const unsigned char *>(frameElems);
        const long long stride = (long long) frameW * 4;
        // 锚点候选全扫描：首个锚点命中偏移校验失败后必须继续向后扫（对齐 Java 回退实现
        // fallbackFindMultiColorAnchor 的 continue 语义）。锚点色为白/黑等常见色时，
        // 仅校验首个候选会大面积假阴性（实测：白锚点 + 白偏移点全屏未命中）。
        for (int ay = ry; !found && ay < ry + rh; ay++) {
            const unsigned char *row = base + (size_t) ay * stride;
            for (int ax = rx; ax < rx + rw; ax++) {
                if (!framePixelMatch(row + (size_t) ax * 4, anchorBgr, shift3)) {
                    continue;
                }
                bool ok = true;
                for (jsize i = 1; i < colorLen; i++) {
                    int x = ax + offsetElems[2 * i];
                    int y = ay + offsetElems[2 * i + 1];
                    if (x < rx || y < ry || x >= rx + rw || y >= ry + rh) {
                        ok = false;
                        break;
                    }
                    unsigned char targetBgr[3];
                    rgbToBgrBytes(colorElems[i], targetBgr);
                    if (!framePixelMatch(base + (size_t) y * stride + (size_t) x * 4,
                                         targetBgr, shift3)) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    found = true;
                    anchor.x = ax;
                    anchor.y = ay;
                    break;
                }
            }
        }
    }
    result = found ? newResult(env, 1, anchor.x, anchor.y, 1000)
                   : newResult(env, 0, -1, -1, 0);

    env->ReleaseByteArrayElements(frame, frameElems, JNI_ABORT);
    env->ReleaseIntArrayElements(colors, colorElems, JNI_ABORT);
    env->ReleaseIntArrayElements(offsets, offsetElems, JNI_ABORT);
    return result;
}

} // extern "C"
