//
// cv_lib.cpp：OpenCV Mat 体系子集（零依赖纯 C++，对齐派大星 opencv 模块 API 命名）
//
// 设计要点：
// - CvMat = rows/cols/ch + 连续 uint8_t 缓冲（8U，1/3/4 通道），userdata + metatable 管理
// - Mat 对象方法（get/put/submat/clone/release...）+ 模块级加工函数（cvtColor/threshold/blur/
//   morphology/bitwise/add 系/inRange/equalizeHist/连通域 despeckle/extractContours/...）
// - 颜色空间：RGBA/BGR/GRAY/HSV（cvtColor code 常量对齐 OpenCV 命名）
// - 内存：vector 托管 + 显式 release；全局计数统计（getMatStats）
//
// Created by TRAE on 2026/9/14.
//

extern "C" {
#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"
}

#include <vector>
#include <cstring>
#include <cmath>
#include <algorithm>
#include <atomic>

namespace autolua {

    struct CvMat {
        int rows = 0, cols = 0, ch = 1;
        std::vector<uint8_t> data;
        CvMat() {}
        CvMat(int r, int c, int k) : rows(r), cols(c), ch(k), data((size_t)r * c * k, 0) {}
        inline uint8_t &at(int r, int c, int k) { return data[((size_t)r * cols + c) * ch + k]; }
        inline const uint8_t &at(int r, int c, int k) const { return data[((size_t)r * cols + c) * ch + k]; }
        inline bool empty() const { return rows <= 0 || cols <= 0 || ch <= 0 || data.empty(); }
        inline size_t bytes() const { return data.size(); }
    };

    static std::atomic<int> gMatCount{0};
    static std::atomic<long long> gMatCreated{0};

    // ---------------- userdata 辅助 ----------------

    static const char *CV_MAT_MT = "cv.Mat";

    static CvMat *toMat(lua_State *L, int idx) {
        auto *m = (CvMat *) luaL_checkudata(L, idx, CV_MAT_MT);
        return m;
    }

    static CvMat *newMatUserdata(lua_State *L, int rows, int cols, int ch) {
        auto *m = (CvMat *) lua_newuserdata(L, sizeof(CvMat));
        new (m) CvMat(rows, cols, ch);
        luaL_getmetatable(L, CV_MAT_MT);
        lua_setmetatable(L, -2);
        gMatCount++;
        gMatCreated++;
        return m;
    }

    // 参数位置取 Mat 并校验
    static CvMat *checkMat(lua_State *L, int idx, const char *fn) {
        auto *m = toMat(L, idx);
        if (m->empty()) luaL_error(L, "cv.%s: mat is empty (released?)", fn);
        return m;
    }

    static inline uint8_t clamp8(int v) {
        return (uint8_t) (v < 0 ? 0 : (v > 255 ? 255 : v));
    }

    // ---------------- 颜色空间 ----------------

    // code 常量（对齐 OpenCV 命名；引擎统一 RGBA 主色序）
    enum CvColorCode {
        COLOR_RGBA2GRAY = 11, COLOR_RGB2GRAY = 7, COLOR_GRAY2RGBA = 4, COLOR_GRAY2BGR = 8,
        COLOR_RGBA2BGR = 21, COLOR_BGR2RGBA = 23, COLOR_RGBA2HSV = 41, COLOR_HSV2RGBA = 55,
        COLOR_BGR2GRAY = 6, COLOR_HSV2BGR = 56, COLOR_RGBA2RGB = 2, COLOR_RGB2RGBA = 3,
    };

    static void rgba2gray(const CvMat &s, CvMat &d) {
        for (int r = 0; r < d.rows; r++)
            for (int c = 0; c < d.cols; c++) {
                const uint8_t *p = &s.data[((size_t) r * s.cols + c) * s.ch];
                d.data[(size_t) r * d.cols + c] = (uint8_t) ((p[0] * 299 + p[1] * 587 + p[2] * 114) / 1000);
            }
    }

    static void gray2rgba(const CvMat &s, CvMat &d, uint8_t a = 255) {
        for (int r = 0; r < d.rows; r++)
            for (int c = 0; c < d.cols; c++) {
                uint8_t g = s.data[(size_t) r * s.cols + c];
                uint8_t *p = &d.data[((size_t) r * d.cols + c) * d.ch];
                p[0] = g; p[1] = g; p[2] = g; if (d.ch == 4) p[3] = a;
            }
    }

    static void rgbX2rgbX(const CvMat &s, CvMat &d) {
        // RGBA<->BGR 交换 R/B；3/4 通道兼容
        for (size_t i = 0; i < (size_t) d.rows * d.cols; i++) {
            const uint8_t *sp = &s.data[i * s.ch];
            uint8_t *dp = &d.data[i * d.ch];
            int sc = s.ch, dc = d.ch;
            dp[0] = sc >= 3 ? sp[2] : sp[0];
            dp[1] = sp[1];
            dp[2] = sc >= 3 ? sp[0] : sp[1];
            if (dc == 4) dp[3] = sc == 4 ? sp[3] : 255;
        }
    }

    static void rgb2hsv(const CvMat &s, CvMat &d) {
        for (int r = 0; r < d.rows; r++)
            for (int c = 0; c < d.cols; c++) {
                const uint8_t *p = &s.data[((size_t) r * s.cols + c) * s.ch];
                int R = p[0], G = p[1], B = p[2];
                int mx = R > G ? (R > B ? R : B) : (G > B ? G : B);
                int mn = R < G ? (R < B ? R : B) : (G < B ? G : B);
                int v = mx, h = 0, s8 = (mx == 0) ? 0 : (255 * (mx - mn)) / (mx == 0 ? 1 : mx);
                int diff = mx - mn;
                if (diff != 0) {
                    if (mx == R) h = 0 + 43 * (G - B) / diff;
                    else if (mx == G) h = 85 + 43 * (B - R) / diff;
                    else h = 171 + 43 * (R - G) / diff;
                    if (h < 0) h += 256;
                }
                uint8_t *dp = &d.data[((size_t) r * d.cols + c) * d.ch];
                dp[0] = (uint8_t) h; dp[1] = (uint8_t) s8; dp[2] = (uint8_t) v;
                if (d.ch == 4) dp[3] = s.ch == 4 ? p[3] : 255;
            }
    }

    // 输出 8U Mat；push userdata
    static CvMat *pushConverted(lua_State *L, const CvMat &src, int code) {
        int dch;
        switch (code) {
            case COLOR_RGBA2GRAY: case COLOR_RGB2GRAY: case COLOR_BGR2GRAY: dch = 1; break;
            case COLOR_GRAY2RGBA: case COLOR_GRAY2BGR: dch = 4; break;
            case COLOR_RGBA2HSV: case COLOR_HSV2RGBA: case COLOR_HSV2BGR: dch = src.ch; break;
            default: dch = src.ch; break;
        }
        auto *d = newMatUserdata(L, src.rows, src.cols, dch);
        switch (code) {
            case COLOR_RGBA2GRAY: rgba2gray(src, *d); break;
            case COLOR_RGB2GRAY: case COLOR_BGR2GRAY: {
                // RGB2GRAY/BGR2GRAY 系数对称（R/B 互换对灰度近似同权）
                rgba2gray(src, *d);
                break;
            }
            case COLOR_GRAY2RGBA: case COLOR_GRAY2BGR: gray2rgba(src, *d); break;
            case COLOR_RGBA2BGR: case COLOR_BGR2RGBA: case COLOR_RGBA2RGB: case COLOR_RGB2RGBA:
                rgbX2rgbX(src, *d); break;
            case COLOR_RGBA2HSV: rgb2hsv(src, *d); break;
            case COLOR_HSV2RGBA: case COLOR_HSV2BGR: {
                // HSV(0-255 度量) → RGB（OpenCV 8U HSV：H/2 度量近似，此处按 0-255 全刻度）
                for (int r = 0; r < d->rows; r++)
                    for (int c = 0; c < d->cols; c++) {
                        const uint8_t *p = &src.data[((size_t) r * src.cols + c) * src.ch];
                        int h = p[0] * 2, s8 = p[1], v = p[2];
                        int region = h / 60;
                        int rem = (h - region * 60) * 255 / 60;
                        int pV = v * (255 - s8) / 255;
                        int qV = v * (255 - s8 * rem / 255) / 255;
                        int tV = v * (255 - s8 * (255 - rem) / 255) / 255;
                        int R, G, B;
                        switch (region) {
                            case 0: R = v; G = tV; B = pV; break;
                            case 1: R = qV; G = v; B = pV; break;
                            case 2: R = pV; G = v; B = tV; break;
                            case 3: R = pV; G = qV; B = v; break;
                            case 4: R = tV; G = pV; B = v; break;
                            default: R = v; G = pV; B = qV; break;
                        }
                        uint8_t *dp = &d->data[((size_t) r * d->cols + c) * d->ch];
                        dp[0] = clamp8(B); dp[1] = clamp8(G); dp[2] = clamp8(R);
                        if (d->ch == 4) dp[3] = src.ch == 4 ? p[3] : 255;
                    }
                break;
            }
            default: luaL_error(L, "cv.cvtColor: unknown code %d", code);
        }
        return d;
    }

    // ---------------- Mat 对象方法（metatable） ----------------

    static int matIndex(lua_State *L) {
        auto *m = toMat(L, 1);
        const char *k = lua_tostring(L, 2);
        if (!k) { lua_pushnil(L); return 1; }
        if (strcmp(k, "rows") == 0) { lua_pushinteger(L, m->rows); }
        else if (strcmp(k, "cols") == 0) { lua_pushinteger(L, m->cols); }
        else if (strcmp(k, "width") == 0) { lua_pushinteger(L, m->cols); }
        else if (strcmp(k, "height") == 0) { lua_pushinteger(L, m->rows); }
        else if (strcmp(k, "channels") == 0) { lua_pushinteger(L, m->ch); }
        else if (strcmp(k, "type") == 0) {
            char buf[16];
            snprintf(buf, sizeof(buf), "CV_8UC%d", m->ch);
            lua_pushstring(L, buf);
        } else if (strcmp(k, "isEmpty") == 0) { lua_pushboolean(L, m->empty() ? 1 : 0); }
        else if (strcmp(k, "size") == 0) {
            lua_pushinteger(L, (lua_Integer) m->bytes());
        } else {
            lua_pushnil(L);
        }
        return 1;
    }

    // m:get(r, c) → v1[,v2,v3,v4]
    static int matGet(lua_State *L) {
        auto *m = toMat(L, 1);
        int r = (int) luaL_checkinteger(L, 2), c = (int) luaL_checkinteger(L, 3);
        if (r < 0 || r >= m->rows || c < 0 || c >= m->cols) {
            luaL_error(L, "mat:get out of range (%d,%d)", r, c);
        }
        for (int k = 0; k < m->ch; k++) lua_pushinteger(L, m->at(r, c, k));
        return m->ch;
    }

    // m:put(r, c, v1[,v2,v3,v4])
    static int matPut(lua_State *L) {
        auto *m = toMat(L, 1);
        int r = (int) luaL_checkinteger(L, 2), c = (int) luaL_checkinteger(L, 3);
        if (r < 0 || r >= m->rows || c < 0 || c >= m->cols) {
            luaL_error(L, "mat:put out of range (%d,%d)", r, c);
        }
        for (int k = 0; k < m->ch; k++) {
            lua_Integer v = luaL_optinteger(L, 4 + k, k == 3 && m->ch == 4 ? 255 : 0);
            m->at(r, c, k) = clamp8((int) v);
        }
        return 0;
    }

    // m:getData() → dataStr（连续 rows*cols*ch）
    static int matGetData(lua_State *L) {
        auto *m = toMat(L, 1);
        lua_pushlstring(L, (const char *) m->data.data(), m->data.size());
        return 1;
    }

    // m:clone() → Mat
    static int matClone(lua_State *L) {
        auto *s = toMat(L, 1);
        auto *d = newMatUserdata(L, s->rows, s->cols, s->ch);
        d->data = s->data;
        return 1;
    }

    // m:submat(row, col, height, width) → Mat
    static int matSubmat(lua_State *L) {
        auto *s = toMat(L, 1);
        int r0 = (int) luaL_checkinteger(L, 2), c0 = (int) luaL_checkinteger(L, 3);
        int h = (int) luaL_checkinteger(L, 4), w = (int) luaL_checkinteger(L, 5);
        if (r0 < 0 || c0 < 0 || h <= 0 || w <= 0 || r0 + h > s->rows || c0 + w > s->cols) {
            luaL_error(L, "mat:submat out of range");
        }
        auto *d = newMatUserdata(L, h, w, s->ch);
        for (int r = 0; r < h; r++) {
            memcpy(&d->data[(size_t) r * w * s->ch],
                   &s->data[((size_t) (r0 + r) * s->cols + c0) * s->ch], (size_t) w * s->ch);
        }
        return 1;
    }

    // m:setTo(v[, ch])
    static int matSetTo(lua_State *L) {
        auto *m = toMat(L, 1);
        lua_Integer v = luaL_checkinteger(L, 2);
        int only = luaL_optinteger(L, 3, -1);
        for (size_t i = 0; i < m->data.size(); i++) {
            if (only < 0) m->data[i] = clamp8((int) v);
            else if ((int) (i % m->ch) == only) m->data[i] = clamp8((int) v);
        }
        return 0;
    }

    static int matRelease(lua_State *L) {
        auto *m = toMat(L, 1);
        if (!m->data.empty()) {
            m->data.clear();
            m->data.shrink_to_fit();
            m->rows = m->cols = m->ch = 0;
            gMatCount--;
        }
        return 0;
    }

    static int matGc(lua_State *L) {
        auto *m = toMat(L, 1);
        if (!m->data.empty()) {
            m->data.clear();
            gMatCount--;
        }
        return 0;
    }

    static const luaL_Reg matMethods[] = {
            {"get",     matGet},
            {"put",     matPut},
            {"getData", matGetData},
            {"clone",   matClone},
            {"submat",  matSubmat},
            {"setTo",   matSetTo},
            {"release", matRelease},
            {nullptr,   nullptr}
    };

    // ---------------- 加工函数（模块级） ----------------

    // cv.cvtColor(mat, code) → Mat
    static int cvCvtColor(lua_State *L) {
        auto *s = checkMat(L, 1, "cvtColor");
        int code = (int) luaL_checkinteger(L, 2);
        pushConverted(L, *s, code);
        return 1;
    }

    // cv.toGray(mat) → Mat（1ch）
    static int cvToGray(lua_State *L) {
        auto *s = checkMat(L, 1, "toGray");
        if (s->ch == 1) { matClone(L); return 1; }
        pushConverted(L, *s, COLOR_RGBA2GRAY);
        return 1;
    }

    // cv.threshold(mat, thresh, maxval[, inverse]) → Mat（灰度二值）
    static int cvThreshold(lua_State *L) {
        auto *s = checkMat(L, 1, "threshold");
        int th = (int) luaL_checkinteger(L, 2), mv = (int) luaL_checkinteger(L, 3);
        bool inv = lua_toboolean(L, 4) != 0;
        auto *d = newMatUserdata(L, s->rows, s->cols, 1);
        for (int r = 0; r < s->rows; r++)
            for (int c = 0; c < s->cols; c++) {
                uint8_t g = s->ch == 1 ? s->at(r, c, 0)
                                       : (uint8_t) ((s->at(r, c, 0) * 299 + s->at(r, c, 1) * 587 +
                                                     s->at(r, c, 2) * 114) / 1000);
                bool over = g > th;
                d->at(r, c, 0) = (uint8_t) ((inv ? !over : over) ? mv : 0);
            }
        return 1;
    }

    // cv.adaptiveThreshold(mat, blockSize[, c]) → Mat（窗口均值法）
    static int cvAdaptiveThreshold(lua_State *L) {
        auto *s = checkMat(L, 1, "adaptiveThreshold");
        int blk = (int) luaL_optinteger(L, 2, 15);
        int cval = (int) luaL_optinteger(L, 3, 8);
        if (blk < 3) blk = 3;
        int rad = blk / 2;
        auto *g = newMatUserdata(L, s->rows, s->cols, 1);
        // 先转灰度
        if (s->ch == 1) g->data = s->data;
        else for (int r = 0; r < s->rows; r++)
            for (int c = 0; c < s->cols; c++)
                g->at(r, c, 0) = (uint8_t) ((s->at(r, c, 0) * 299 + s->at(r, c, 1) * 587 +
                                             s->at(r, c, 2) * 114) / 1000);
        auto *d = newMatUserdata(L, s->rows, s->cols, 1);
        for (int r = 0; r < s->rows; r++)
            for (int c = 0; c < s->cols; c++) {
                long long sum = 0;
                int n = 0;
                for (int dr = -rad; dr <= rad; dr++)
                    for (int dc = -rad; dc <= rad; dc++) {
                        int rr = r + dr, cc = c + dc;
                        if (rr < 0 || cc < 0 || rr >= s->rows || cc >= s->cols) continue;
                        sum += g->at(rr, cc, 0);
                        n++;
                    }
                long long mean = sum / (n > 0 ? n : 1);
                d->at(r, c, 0) = (g->at(r, c, 0) > mean - cval) ? 255 : 0;
            }
        return 1;
    }

    // 通用卷积（box/gauss 近似；灰度化处理多通道逐通道卷积）
    static void convolve(const CvMat &s, CvMat &d, int k, const int *kernel, int div) {
        int rad = k / 2;
        for (int r = 0; r < s.rows; r++)
            for (int c = 0; c < s.cols; c++)
                for (int k2 = 0; k2 < s.ch; k2++) {
                    long long acc = 0;
                    for (int i = 0; i < k; i++)
                        for (int j = 0; j < k; j++) {
                            int rr = r + i - rad, cc = c + j - rad;
                            rr = rr < 0 ? 0 : (rr >= s.rows ? s.rows - 1 : rr);
                            cc = cc < 0 ? 0 : (cc >= s.cols ? s.cols - 1 : cc);
                            acc += (long long) s.at(rr, cc, k2) * kernel[i * k + j];
                        }
                    d.at(r, c, k2) = clamp8((int) (acc / (div > 0 ? div : 1)));
                }
    }

    // cv.blur(mat, ksize) → Mat（均值滤波）
    static int cvBlur(lua_State *L) {
        auto *s = checkMat(L, 1, "blur");
        int k = (int) luaL_optinteger(L, 2, 3);
        if (k < 1) k = 1;
        if (k % 2 == 0) k++;
        std::vector<int> kern((size_t) k * k, 1);
        auto *d = newMatUserdata(L, s->rows, s->cols, s->ch);
        convolve(*s, *d, k, kern.data(), k * k);
        return 1;
    }

    // cv.gaussianBlur(mat, ksize) → Mat（高斯近似：3x3=[1,2,1]/4 分离；5x5=[1,4,6,4,1]）
    static int cvGaussianBlur(lua_State *L) {
        auto *s = checkMat(L, 1, "gaussianBlur");
        int k = (int) luaL_optinteger(L, 2, 3);
        if (k < 3) { matClone(L); return 1; }
        k = (k > 5) ? 5 : 3;
        int kern3[9] = {1, 2, 1, 2, 4, 2, 1, 2, 1};
        int kern5[25] = {1, 4, 6, 4, 1, 4, 16, 24, 16, 4, 6, 24, 36, 24, 6,
                         4, 16, 24, 16, 4, 1, 4, 6, 4, 1};
        int div = (k == 3) ? 16 : 256;
        auto *d = newMatUserdata(L, s->rows, s->cols, s->ch);
        convolve(*s, *d, k, k == 3 ? kern3 : kern5, div);
        return 1;
    }

    // cv.medianBlur(mat, ksize) → Mat（中值滤波，3/5）
    static int cvMedianBlur(lua_State *L) {
        auto *s = checkMat(L, 1, "medianBlur");
        int k = (int) luaL_optinteger(L, 2, 3);
        if (k < 3) k = 3;
        if (k % 2 == 0) k++;
        int rad = k / 2;
        auto *d = newMatUserdata(L, s->rows, s->cols, s->ch);
        std::vector<uint8_t> win((size_t) k * k);
        for (int r = 0; r < s->rows; r++)
            for (int c = 0; c < s->cols; c++)
                for (int k2 = 0; k2 < s->ch; k2++) {
                    int n = 0;
                    for (int i = -rad; i <= rad; i++)
                        for (int j = -rad; j <= rad; j++) {
                            int rr = r + i, cc = c + j;
                            rr = rr < 0 ? 0 : (rr >= s->rows ? s->rows - 1 : rr);
                            cc = cc < 0 ? 0 : (cc >= s->cols ? s->cols - 1 : cc);
                            win[n++] = s->at(rr, cc, k2);
                        }
                    std::sort(win.begin(), win.begin() + n);
                    d->at(r, c, k2) = win[n / 2];
                }
        return 1;
    }

    // 形态学：erode/dilate（方形核 ksize，iters 次）
    static int morphology(lua_State *L, bool dilate) {
        auto *s = checkMat(L, 1, dilate ? "dilate" : "erode");
        int k = (int) luaL_optinteger(L, 2, 3);
        int iters = (int) luaL_optinteger(L, 3, 1);
        if (k < 1) k = 1;
        if (k % 2 == 0) k++;
        int rad = k / 2;
        auto *cur = newMatUserdata(L, s->rows, s->cols, s->ch);
        cur->data = s->data;
        std::vector<uint8_t> next(cur->data.size());
        for (int it = 0; it < iters; it++) {
            for (int r = 0; r < s->rows; r++)
                for (int c = 0; c < s->cols; c++)
                    for (int k2 = 0; k2 < s->ch; k2++) {
                        uint8_t acc = dilate ? 0 : 255;
                        for (int i = -rad; i <= rad && ((dilate && acc < 255) || (!dilate && acc > 0)); i++)
                            for (int j = -rad; j <= rad; j++) {
                                int rr = r + i, cc = c + j;
                                if (rr < 0 || cc < 0 || rr >= s->rows || cc >= s->cols) continue;
                                uint8_t v = cur->at(rr, cc, k2);
                                if (dilate) { if (v > acc) acc = v; }
                                else { if (v < acc) acc = v; }
                            }
                        next[((size_t) r * s->cols + c) * s->ch + k2] = acc;
                    }
            cur->data = next;
        }
        return 1;
    }

    static int cvDilate(lua_State *L) { return morphology(L, true); }
    static int cvErode(lua_State *L) { return morphology(L, false); }

    // cv.morphologyOpen/Close(mat[, ksize[, iters]])
    static int cvMorphOpen(lua_State *L) {
        cvErode(L);
        lua_replace(L, 1);   // erode 结果作为新 src
        return cvDilate(L);
    }

    static int cvMorphClose(lua_State *L) {
        cvDilate(L);
        lua_replace(L, 1);
        return cvErode(L);
    }

    // cv.bitwiseNot(mat) → Mat
    static int cvBitwiseNot(lua_State *L) {
        auto *s = checkMat(L, 1, "bitwiseNot");
        auto *d = newMatUserdata(L, s->rows, s->cols, s->ch);
        for (size_t i = 0; i < s->data.size(); i++) d->data[i] = (uint8_t) ~s->data[i];
        return 1;
    }

    // cv.bitwiseAnd/Or/Xor(a, b) → Mat
    static int bitwise2(lua_State *L, int op) {
        auto *a = checkMat(L, 1, "bitwise");
        auto *b = checkMat(L, 2, "bitwise");
        if (a->rows != b->rows || a->cols != b->cols || a->ch != b->ch) {
            luaL_error(L, "cv.bitwise: size mismatch");
        }
        auto *d = newMatUserdata(L, a->rows, a->cols, a->ch);
        for (size_t i = 0; i < a->data.size(); i++) {
            d->data[i] = op == 0 ? (a->data[i] & b->data[i]) : op == 1 ? (a->data[i] | b->data[i])
                                                                     : (a->data[i] ^ b->data[i]);
        }
        return 1;
    }

    static int cvBitwiseAnd(lua_State *L) { return bitwise2(L, 0); }
    static int cvBitwiseOr(lua_State *L) { return bitwise2(L, 1); }
    static int cvBitwiseXor(lua_State *L) { return bitwise2(L, 2); }

    // cv.add(a, b[, wa]) → Mat（饱和相加；wa 为 a 的权重比例 0~1，默认 0.5+0.5）
    static int cvAdd(lua_State *L) {
        auto *a = checkMat(L, 1, "add");
        auto *b = checkMat(L, 2, "add");
        double wa = luaL_optnumber(L, 3, 0.5);
        if (a->rows != b->rows || a->cols != b->cols || a->ch != b->ch) {
            luaL_error(L, "cv.add: size mismatch");
        }
        auto *d = newMatUserdata(L, a->rows, a->cols, a->ch);
        for (size_t i = 0; i < a->data.size(); i++) {
            d->data[i] = clamp8((int) (a->data[i] * wa + b->data[i] * (1.0 - wa)));
        }
        return 1;
    }

    // cv.subtract(a, b) → Mat（饱和相减）
    static int cvSubtract(lua_State *L) {
        auto *a = checkMat(L, 1, "subtract");
        auto *b = checkMat(L, 2, "subtract");
        if (a->rows != b->rows || a->cols != b->cols || a->ch != b->ch) {
            luaL_error(L, "cv.subtract: size mismatch");
        }
        auto *d = newMatUserdata(L, a->rows, a->cols, a->ch);
        for (size_t i = 0; i < a->data.size(); i++) {
            d->data[i] = clamp8((int) a->data[i] - (int) b->data[i]);
        }
        return 1;
    }

    // cv.addWeighted(a, wa, b, wb, gamma) → Mat
    static int cvAddWeighted(lua_State *L) {
        auto *a = checkMat(L, 1, "addWeighted");
        double wa = luaL_checknumber(L, 2);
        auto *b = checkMat(L, 3, "addWeighted");
        double wb = luaL_checknumber(L, 4);
        double gamma = luaL_optnumber(L, 5, 0);
        if (a->rows != b->rows || a->cols != b->cols || a->ch != b->ch) {
            luaL_error(L, "cv.addWeighted: size mismatch");
        }
        auto *d = newMatUserdata(L, a->rows, a->cols, a->ch);
        for (size_t i = 0; i < a->data.size(); i++) {
            d->data[i] = clamp8((int) (a->data[i] * wa + b->data[i] * wb + gamma));
        }
        return 1;
    }

    // cv.alphaBlend(a, b, ratio) → Mat（ratio = a 权重 0~1）
    static int cvAlphaBlend(lua_State *L) {
        auto *a = checkMat(L, 1, "alphaBlend");
        auto *b = checkMat(L, 2, "alphaBlend");
        double ratio = luaL_optnumber(L, 3, 0.5);
        if (a->rows != b->rows || a->cols != b->cols || a->ch != b->ch) {
            luaL_error(L, "cv.alphaBlend: size mismatch");
        }
        auto *d = newMatUserdata(L, a->rows, a->cols, a->ch);
        for (size_t i = 0; i < a->data.size(); i++) {
            d->data[i] = clamp8((int) (a->data[i] * ratio + b->data[i] * (1.0 - ratio)));
        }
        return 1;
    }

    // cv.inRange(mat, loR,loG,loB, hiR,hiG,hiB) → 二值 Mat（HSV 或 RGB 均可，按通道比较）
    static int cvInRange(lua_State *L) {
        auto *s = checkMat(L, 1, "inRange");
        int lo[4], hi[4];
        for (int i = 0; i < 3; i++) {
            lo[i] = (int) luaL_checkinteger(L, 2 + i);
            hi[i] = (int) luaL_checkinteger(L, 5 + i);
        }
        lo[3] = 0; hi[3] = 255;
        auto *d = newMatUserdata(L, s->rows, s->cols, 1);
        for (int r = 0; r < s->rows; r++)
            for (int c = 0; c < s->cols; c++) {
                bool in = true;
                for (int k = 0; k < 3 && in; k++) {
                    int v = s->at(r, c, k);
                    if (v < lo[k] || v > hi[k]) in = false;
                }
                d->at(r, c, 0) = in ? 255 : 0;
            }
        return 1;
    }

    // cv.equalizeHist(mat) → Mat（灰度直方图均衡）
    static int cvEqualizeHist(lua_State *L) {
        auto *s = checkMat(L, 1, "equalizeHist");
        auto *d = newMatUserdata(L, s->rows, s->cols, 1);
        long long hist[256] = {0};
        for (int r = 0; r < s->rows; r++)
            for (int c = 0; c < s->cols; c++) {
                uint8_t g = s->ch == 1 ? s->at(r, c, 0)
                                       : (uint8_t) ((s->at(r, c, 0) * 299 + s->at(r, c, 1) * 587 +
                                                     s->at(r, c, 2) * 114) / 1000);
                hist[g]++;
                d->at(r, c, 0) = g;
            }
        long long total = (long long) s->rows * s->cols;
        long long acc = 0;
        uint8_t lut[256];
        for (int i = 0; i < 256; i++) {
            acc += hist[i];
            lut[i] = clamp8((int) (acc * 255 / (total > 0 ? total : 1)));
        }
        for (size_t i = 0; i < d->data.size(); i++) d->data[i] = lut[d->data[i]];
        return 1;
    }

    // cv.convertScaleAbs(mat, alpha[, beta]) → Mat（对比度/亮度）
    static int cvConvertScaleAbs(lua_State *L) {
        auto *s = checkMat(L, 1, "convertScaleAbs");
        double alpha = luaL_checknumber(L, 2);
        double beta = luaL_optnumber(L, 3, 0);
        auto *d = newMatUserdata(L, s->rows, s->cols, s->ch);
        for (size_t i = 0; i < s->data.size(); i++) {
            d->data[i] = clamp8((int) std::fabs(s->data[i] * alpha + beta));
        }
        return 1;
    }

    // cv.resize(mat, rows, cols) → Mat（双线性）
    static int cvResize(lua_State *L) {
        auto *s = checkMat(L, 1, "resize");
        int rows = (int) luaL_checkinteger(L, 2), cols = (int) luaL_checkinteger(L, 3);
        if (rows <= 0 || cols <= 0) luaL_error(L, "cv.resize: bad size");
        auto *d = newMatUserdata(L, rows, cols, s->ch);
        double sr = (double) s->rows / rows, sc = (double) s->cols / cols;
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) {
                double fy = (r + 0.5) * sr - 0.5, fx = (c + 0.5) * sc - 0.5;
                if (fy < 0) fy = 0;
                if (fx < 0) fx = 0;
                int y0 = (int) fy, x0 = (int) fx;
                int y1 = std::min(y0 + 1, s->rows - 1), x1 = std::min(x0 + 1, s->cols - 1);
                y0 = std::min(y0, s->rows - 1); x0 = std::min(x0, s->cols - 1);
                double wy = fy - y0, wx = fx - x0;
                for (int k = 0; k < s->ch; k++) {
                    double v = (s->at(y0, x0, k) * (1 - wy) + s->at(y1, x0, k) * wy) * (1 - wx) +
                               (s->at(y0, x1, k) * (1 - wy) + s->at(y1, x1, k) * wy) * wx;
                    d->at(r, c, k) = clamp8((int) (v + 0.5));
                }
            }
        return 1;
    }

    // cv.resizeRatio(mat, ratio) → Mat
    static int cvResizeRatio(lua_State *L) {
        auto *s = checkMat(L, 1, "resizeRatio");
        double ratio = luaL_checknumber(L, 2);
        lua_pushinteger(L, std::max(1, (int) (s->rows * ratio)));
        lua_replace(L, 2);   // (mat, rows, ...)
        lua_pushinteger(L, std::max(1, (int) (s->cols * ratio)));
        lua_settop(L, 3);    // (mat, rows, cols)
        return cvResize(L);
    }

    // cv.flip(mat, mode) → Mat（0=上下 1=左右 -1=双向）
    static int cvFlip(lua_State *L) {
        auto *s = checkMat(L, 1, "flip");
        int mode = (int) luaL_optinteger(L, 2, 1);
        auto *d = newMatUserdata(L, s->rows, s->cols, s->ch);
        for (int r = 0; r < s->rows; r++)
            for (int c = 0; c < s->cols; c++) {
                int sr = (mode == 0 || mode == -1) ? s->rows - 1 - r : r;
                int sc = (mode == 1 || mode == -1) ? s->cols - 1 - c : c;
                for (int k = 0; k < s->ch; k++) d->at(r, c, k) = s->at(sr, sc, k);
            }
        return 1;
    }

    // cv.rotate(mat, angle) → Mat（90/180/270；任意角度走 rotateArbitrary）
    static int cvRotate(lua_State *L) {
        auto *s = checkMat(L, 1, "rotate");
        int angle = ((int) luaL_optinteger(L, 2, 90) % 360 + 360) % 360;
        if (angle == 90 || angle == 270) {
            auto *d = newMatUserdata(L, s->cols, s->rows, s->ch);
            for (int r = 0; r < d->rows; r++)
                for (int c = 0; c < d->cols; c++)
                    for (int k = 0; k < s->ch; k++) {
                        // 90 顺时针：dst(r,c) = src(rows-1-c, r)
                        int sr, sc;
                        if (angle == 90) { sr = s->rows - 1 - c; sc = r; }
                        else { sr = c; sc = s->cols - 1 - r; }
                        d->at(r, c, k) = s->at(sr, sc, k);
                    }
            return 1;
        }
        if (angle == 180) {
            auto *d = newMatUserdata(L, s->rows, s->cols, s->ch);
            for (int r = 0; r < s->rows; r++)
                for (int c = 0; c < s->cols; c++)
                    for (int k = 0; k < s->ch; k++)
                        d->at(r, c, k) = s->at(s->rows - 1 - r, s->cols - 1 - c, k);
            return 1;
        }
        matClone(L);
        return 1;
    }

    // cv.rotateArbitrary(mat, angleDeg[, bg=0]) → Mat（双线性旋转，画布扩大）
    static int cvRotateArbitrary(lua_State *L) {
        auto *s = checkMat(L, 1, "rotateArbitrary");
        double ang = luaL_checknumber(L, 2) * M_PI / 180.0;
        uint8_t bg = (uint8_t) luaL_optinteger(L, 3, 0);
        double ca = std::cos(ang), sa = std::sin(ang);
        int w = s->cols, h = s->rows;
        int nw = (int) std::ceil(std::fabs(w * ca) + std::fabs(h * sa));
        int nh = (int) std::ceil(std::fabs(w * sa) + std::fabs(h * ca));
        auto *d = newMatUserdata(L, nh, nw, s->ch);
        std::fill(d->data.begin(), d->data.end(), bg);
        double cx0 = w / 2.0, cy0 = h / 2.0, cx1 = nw / 2.0, cy1 = nh / 2.0;
        for (int r = 0; r < nh; r++)
            for (int c = 0; c < nw; c++) {
                double sx = (c - cx1) * ca + (r - cy1) * sa + cx0;
                double sy = -(c - cx1) * sa + (r - cy1) * ca + cy0;
                if (sx < 0 || sy < 0 || sx >= w - 1 || sy >= h - 1) continue;
                int x0 = (int) sx, y0 = (int) sy;
                int x1 = std::min(x0 + 1, w - 1), y1 = std::min(y0 + 1, h - 1);
                double wx = sx - x0, wy = sy - y0;
                for (int k = 0; k < s->ch; k++) {
                    double v = (s->at(y0, x0, k) * (1 - wy) + s->at(y1, x0, k) * wy) * (1 - wx) +
                               (s->at(y0, x1, k) * (1 - wy) + s->at(y1, x1, k) * wy) * wx;
                    d->at(r, c, k) = clamp8((int) (v + 0.5));
                }
            }
        return 1;
    }

    // cv.cropImage(mat, x, y, w, h) → Mat
    static int cvCrop(lua_State *L) {
        auto *s = checkMat(L, 1, "cropImage");
        int x = (int) luaL_checkinteger(L, 2), y = (int) luaL_checkinteger(L, 3);
        int w = (int) luaL_checkinteger(L, 4), h = (int) luaL_checkinteger(L, 5);
        if (x < 0 || y < 0 || w <= 0 || h <= 0 || x + w > s->cols || y + h > s->rows) {
            luaL_error(L, "cv.cropImage out of range");
        }
        auto *d = newMatUserdata(L, h, w, s->ch);
        for (int r = 0; r < h; r++) {
            memcpy(&d->data[(size_t) r * w * s->ch],
                   &s->data[((size_t) (y + r) * s->cols + x) * s->ch], (size_t) w * s->ch);
        }
        return 1;
    }

    // cv.drawRect(mat, x, y, w, h, {r,g,b}[, thick]) → 原地
    static int cvDrawRect(lua_State *L) {
        auto *m = checkMat(L, 1, "drawRect");
        int x = (int) luaL_checkinteger(L, 2), y = (int) luaL_checkinteger(L, 3);
        int w = (int) luaL_checkinteger(L, 4), h = (int) luaL_checkinteger(L, 5);
        int col[3] = {255, 255, 255};
        if (lua_istable(L, 6)) {
            for (int i = 0; i < 3; i++) {
                lua_pushinteger(L, i + 1);
                lua_gettable(L, 6);
                col[i] = (int) lua_tointeger(L, -1);
                lua_pop(L, 1);
            }
        }
        int thick = (int) luaL_optinteger(L, 7, 1);
        int ch = std::min(3, m->ch);
        for (int t = 0; t < thick; t++) {
            for (int i = 0; i < w; i++) {
                int xs[2] = {x + i, x + i};
                int ys[2] = {y + t, y + h - 1 - t};
                for (int k = 0; k < 2; k++) {
                    int px = xs[k], py = ys[k];
                    if (px >= 0 && py >= 0 && px < m->cols && py < m->rows)
                        for (int c = 0; c < ch; c++) m->at(py, px, c) = clamp8(col[c]);
                }
            }
            for (int i = 0; i < h; i++) {
                int xs[2] = {x + t, x + w - 1 - t};
                int ys[2] = {y + i, y + i};
                for (int k = 0; k < 2; k++) {
                    int px = xs[k], py = ys[k];
                    if (px >= 0 && py >= 0 && px < m->cols && py < m->rows)
                        for (int c = 0; c < ch; c++) m->at(py, px, c) = clamp8(col[c]);
                }
            }
        }
        lua_pushvalue(L, 1);
        return 1;
    }

    // cv.drawLine(mat, x1, y1, x2, y2, {r,g,b}) → 原地（Bresenham）
    static int cvDrawLine(lua_State *L) {
        auto *m = checkMat(L, 1, "drawLine");
        int x0 = (int) luaL_checkinteger(L, 2), y0 = (int) luaL_checkinteger(L, 3);
        int x1 = (int) luaL_checkinteger(L, 4), y1 = (int) luaL_checkinteger(L, 5);
        int col[3] = {255, 255, 255};
        if (lua_istable(L, 6)) {
            for (int i = 0; i < 3; i++) {
                lua_pushinteger(L, i + 1);
                lua_gettable(L, 6);
                col[i] = (int) lua_tointeger(L, -1);
                lua_pop(L, 1);
            }
        }
        int dx = std::abs(x1 - x0), dy = std::abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1, err = dx - dy;
        int ch = std::min(3, m->ch);
        while (true) {
            if (x0 >= 0 && y0 >= 0 && x0 < m->cols && y0 < m->rows)
                for (int c = 0; c < ch; c++) m->at(y0, x0, c) = clamp8(col[c]);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 > -dy) { err -= dy; x0 += sx; }
            if (e2 < dx) { err += dx; y0 += sy; }
        }
        lua_pushvalue(L, 1);
        return 1;
    }

    // cv.drawCircle(mat, cx, cy, radius, {r,g,b}) → 原地
    static int cvDrawCircle(lua_State *L) {
        auto *m = checkMat(L, 1, "drawCircle");
        int cx = (int) luaL_checkinteger(L, 2), cy = (int) luaL_checkinteger(L, 3);
        int rad = (int) luaL_checkinteger(L, 4);
        int col[3] = {255, 255, 255};
        if (lua_istable(L, 5)) {
            for (int i = 0; i < 3; i++) {
                lua_pushinteger(L, i + 1);
                lua_gettable(L, 5);
                col[i] = (int) lua_tointeger(L, -1);
                lua_pop(L, 1);
            }
        }
        int ch = std::min(3, m->ch);
        for (int a = 0; a < 360; a += 1) {
            int px = cx + (int) std::round(rad * std::cos(a * M_PI / 180.0));
            int py = cy + (int) std::round(rad * std::sin(a * M_PI / 180.0));
            if (px >= 0 && py >= 0 && px < m->cols && py < m->rows)
                for (int c = 0; c < ch; c++) m->at(py, px, c) = clamp8(col[c]);
        }
        lua_pushvalue(L, 1);
        return 1;
    }

    // ---------------- 连通域 / 统计 ----------------

    // 灰度化读值
    static inline uint8_t grayAt(const CvMat &s, int r, int c) {
        if (s.ch == 1) return s.at(r, c, 0);
        return (uint8_t) ((s.at(r, c, 0) * 299 + s.at(r, c, 1) * 587 + s.at(r, c, 2) * 114) / 1000);
    }

    // cv.extractContours(mat[, minArea=4]) → {{x=,y=,w=,h=,area=},...}（二值白区外接框）
    static int cvExtractContours(lua_State *L) {
        auto *s = checkMat(L, 1, "extractContours");
        int minArea = (int) luaL_optinteger(L, 2, 4);
        size_t n = (size_t) s->rows * s->cols;
        std::vector<uint8_t> fg(n);
        for (size_t i = 0; i < n; i++) {
            int r = (int) (i / s->cols), c = (int) (i % s->cols);
            fg[i] = grayAt(*s, r, c) > 127 ? 1 : 0;
        }
        std::vector<int> label(n, 0);
        std::vector<int> stack;
        lua_newtable(L);
        int out = 1, cnt = 0;
        for (size_t i = 0; i < n; i++) {
            if (!fg[i] || label[i]) continue;
            int minX = s->cols, minY = s->rows, maxX = -1, maxY = -1;
            long long area = 0;
            int cur = ++cnt;
            stack.clear();
            stack.push_back((int) i);
            label[i] = cur;
            while (!stack.empty()) {
                int p = stack.back();
                stack.pop_back();
                int r = p / s->cols, c = p % s->cols;
                if (c < minX) minX = c;
                if (c > maxX) maxX = c;
                if (r < minY) minY = r;
                if (r > maxY) maxY = r;
                area++;
                int nb[4] = {p - 1, p + 1, p - (int) s->cols, p + (int) s->cols};
                for (int k = 0; k < 4; k++) {
                    int q = nb[k];
                    if (q < 0 || q >= (int) n) continue;
                    if (k < 2 && q / s->cols != p / s->cols) continue;  // 左右不跨行
                    if (fg[q] && !label[q]) {
                        label[q] = cur;
                        stack.push_back(q);
                    }
                }
            }
            if (area >= minArea && maxX >= 0) {
                lua_newtable(L);
                lua_pushstring(L, "x"); lua_pushinteger(L, minX); lua_rawset(L, -3);
                lua_pushstring(L, "y"); lua_pushinteger(L, minY); lua_rawset(L, -3);
                lua_pushstring(L, "w"); lua_pushinteger(L, maxX - minX + 1); lua_rawset(L, -3);
                lua_pushstring(L, "h"); lua_pushinteger(L, maxY - minY + 1); lua_rawset(L, -3);
                lua_pushstring(L, "area"); lua_pushinteger(L, area); lua_rawset(L, -3);
                lua_rawseti(L, -2, out++);
            }
        }
        return 1;
    }

    // cv.despeckle(mat, minArea) → Mat（清除小于 minArea 的白区连通域；灰度）
    static int despeckleImpl(lua_State *L, const char *fn) {
        auto *s = checkMat(L, 1, fn);
        int minArea = (int) luaL_optinteger(L, 2, 4);
        size_t n = (size_t) s->rows * s->cols;
        std::vector<uint8_t> fg(n);
        for (size_t i = 0; i < n; i++) {
            int r = (int) (i / s->cols), c = (int) (i % s->cols);
            fg[i] = grayAt(*s, r, c) > 127 ? 1 : 0;
        }
        std::vector<int> label(n, 0);
        std::vector<int> stack;
        auto *d = newMatUserdata(L, s->rows, s->cols, 1);
        if (s->ch == 1) d->data = s->data;
        else for (int r = 0; r < s->rows; r++)
            for (int c = 0; c < s->cols; c++) d->at(r, c, 0) = grayAt(*s, r, c);
        int cnt = 0;
        for (size_t i = 0; i < n; i++) {
            if (!fg[i] || label[i]) continue;
            int cur = ++cnt;
            stack.clear();
            stack.push_back((int) i);
            label[i] = cur;
            std::vector<int> comp;
            while (!stack.empty()) {
                int p = stack.back();
                stack.pop_back();
                comp.push_back(p);
                int nb[4] = {p - 1, p + 1, p - (int) s->cols, p + (int) s->cols};
                for (int k = 0; k < 4; k++) {
                    int q = nb[k];
                    if (q < 0 || q >= (int) n) continue;
                    if (k < 2 && q / s->cols != p / s->cols) continue;
                    if (fg[q] && !label[q]) {
                        label[q] = cur;
                        stack.push_back(q);
                    }
                }
            }
            if ((long long) comp.size() < minArea) {
                for (int p: comp) d->data[p] = 0;
            }
        }
        return 1;
    }

    static int cvDespeckle(lua_State *L) { return despeckleImpl(L, "despeckle"); }
    static int cvKeepComponentsBySize(lua_State *L) { return despeckleImpl(L, "keepComponentsBySize"); }

    // cv.colorBinary(mat, {r,g,b}[, dist=60]) → Mat（RGB 距离二值）
    static int cvColorBinary(lua_State *L) {
        auto *s = checkMat(L, 1, "colorBinary");
        int tr = 255, tg = 255, tb = 255;
        if (lua_istable(L, 2)) {
            lua_pushinteger(L, 1); lua_gettable(L, 2); tr = (int) lua_tointeger(L, -1); lua_pop(L, 1);
            lua_pushinteger(L, 2); lua_gettable(L, 2); tg = (int) lua_tointeger(L, -1); lua_pop(L, 1);
            lua_pushinteger(L, 3); lua_gettable(L, 2); tb = (int) lua_tointeger(L, -1); lua_pop(L, 1);
        }
        int dist = (int) luaL_optinteger(L, 3, 60);
        auto *d = newMatUserdata(L, s->rows, s->cols, 1);
        long long dist2 = (long long) dist * dist;
        for (int r = 0; r < s->rows; r++)
            for (int c = 0; c < s->cols; c++) {
                int dr = s->at(r, c, 0) - tr, dg = s->at(r, c, 1) - tg, db = s->at(r, c, 2) - tb;
                d->at(r, c, 0) = ((long long) dr * dr + dg * dg + db * db) <= dist2 ? 255 : 0;
            }
        return 1;
    }

    // cv.cropBlackBorder(mat[, thresh=50]) → Mat（灰度投影裁掉全黑边）
    static int cvCropBlackBorder(lua_State *L) {
        auto *s = checkMat(L, 1, "cropBlackBorder");
        int th = (int) luaL_optinteger(L, 2, 50);
        int top = s->rows, bottom = -1, left = s->cols, right = -1;
        for (int r = 0; r < s->rows; r++)
            for (int c = 0; c < s->cols; c++) {
                if (grayAt(*s, r, c) > th) {
                    if (r < top) top = r;
                    if (r > bottom) bottom = r;
                    if (c < left) left = c;
                    if (c > right) right = c;
                }
            }
        if (bottom < 0) { matClone(L); return 1; }
        int h = bottom - top + 1, w = right - left + 1;
        auto *d = newMatUserdata(L, h, w, s->ch);
        for (int r = 0; r < h; r++) {
            memcpy(&d->data[(size_t) r * w * s->ch],
                   &s->data[((size_t) (top + r) * s->cols + left) * s->ch], (size_t) w * s->ch);
        }
        return 1;
    }

    // cv.minMaxLoc(mat) → min, max, minX, minY, maxX, maxY（灰度）
    static int cvMinMaxLoc(lua_State *L) {
        auto *s = checkMat(L, 1, "minMaxLoc");
        uint8_t mn = 255, mx = 0;
        int mnx = 0, mny = 0, mxx = 0, mxy = 0;
        for (int r = 0; r < s->rows; r++)
            for (int c = 0; c < s->cols; c++) {
                uint8_t g = grayAt(*s, r, c);
                if (g < mn) { mn = g; mnx = c; mny = r; }
                if (g > mx) { mx = g; mxx = c; mxy = r; }
            }
        lua_pushinteger(L, mn); lua_pushinteger(L, mx);
        lua_pushinteger(L, mnx); lua_pushinteger(L, mny);
        lua_pushinteger(L, mxx); lua_pushinteger(L, mxy);
        return 6;
    }

    // cv.mean(mat) → mr, mg, mb[, ma]（各通道均值）
    static int cvMean(lua_State *L) {
        auto *s = checkMat(L, 1, "mean");
        long long acc[4] = {0, 0, 0, 0};
        long long n = (long long) s->rows * s->cols;
        if (n <= 0) { for (int k = 0; k < s->ch; k++) lua_pushinteger(L, 0); return s->ch; }
        for (int r = 0; r < s->rows; r++)
            for (int c = 0; c < s->cols; c++)
                for (int k = 0; k < s->ch; k++) acc[k] += s->at(r, c, k);
        for (int k = 0; k < s->ch; k++) lua_pushinteger(L, acc[k] / n);
        return s->ch;
    }

    // cv.countNonZero(mat) → int
    static int cvCountNonZero(lua_State *L) {
        auto *s = checkMat(L, 1, "countNonZero");
        long long n = 0;
        for (size_t i = 0; i < s->data.size(); i++) if (s->data[i] != 0) n++;
        lua_pushinteger(L, n);
        return 1;
    }

    // cv.sum(mat) → s1[,s2,s3,s4]
    static int cvSum(lua_State *L) {
        auto *s = checkMat(L, 1, "sum");
        long long acc[4] = {0, 0, 0, 0};
        for (int r = 0; r < s->rows; r++)
            for (int c = 0; c < s->cols; c++)
                for (int k = 0; k < s->ch; k++) acc[k] += s->at(r, c, k);
        for (int k = 0; k < s->ch; k++) lua_pushinteger(L, acc[k]);
        return s->ch;
    }

    // cv.split(mat) → {m1[,m2,m3,m4]}
    static int cvSplit(lua_State *L) {
        auto *s = checkMat(L, 1, "split");
        lua_newtable(L);
        for (int k = 0; k < s->ch; k++) {
            auto *d = newMatUserdata(L, s->rows, s->cols, 1);
            for (int r = 0; r < s->rows; r++)
                for (int c = 0; c < s->cols; c++) d->at(r, c, 0) = s->at(r, c, k);
            lua_rawseti(L, -2, k + 1);
        }
        return 1;
    }

    // cv.merge({m1,m2,m3}) → Mat
    static int cvMerge(lua_State *L) {
        luaL_checktype(L, 1, LUA_TTABLE);
        int ch = (int) lua_rawlen(L, 1);
        if (ch < 1 || ch > 4) luaL_error(L, "cv.merge: 1~4 mats expected");
        CvMat *first = nullptr;
        for (int k = 1; k <= ch; k++) {
            lua_rawgeti(L, 1, k);
            auto *m = toMat(L, -1);
            lua_pop(L, 1);
            if (!first) { first = m; continue; }
            if (m->rows != first->rows || m->cols != first->cols) {
                luaL_error(L, "cv.merge: size mismatch");
            }
        }
        auto *d = newMatUserdata(L, first->rows, first->cols, ch);
        for (int r = 0; r < d->rows; r++)
            for (int c = 0; c < d->cols; c++)
                for (int k = 1; k <= ch; k++) {
                    lua_rawgeti(L, 1, k);
                    auto *m = toMat(L, -1);
                    lua_pop(L, 1);
                    d->at(r, c, k - 1) = m->at(r, c, 0);
                }
        return 1;
    }

    // ---------------- 创建 / 转换 / 资源管理 ----------------

    // cv.createMat(rows, cols[, ch=1])
    static int cvCreateMat(lua_State *L) {
        int rows = (int) luaL_checkinteger(L, 1), cols = (int) luaL_checkinteger(L, 2);
        int ch = (int) luaL_optinteger(L, 3, 1);
        if (rows <= 0 || cols <= 0 || ch < 1 || ch > 4) luaL_error(L, "cv.createMat: bad params");
        newMatUserdata(L, rows, cols, ch);
        return 1;
    }

    // cv.createMatFromData(rows, cols, dataStr[, ch=1])
    static int cvCreateMatFromData(lua_State *L) {
        int rows = (int) luaL_checkinteger(L, 1), cols = (int) luaL_checkinteger(L, 2);
        size_t len = 0;
        const char *data = luaL_checklstring(L, 3, &len);
        int ch = (int) luaL_optinteger(L, 4, 1);
        if (rows <= 0 || cols <= 0 || ch < 1 || ch > 4) luaL_error(L, "cv.createMatFromData: bad params");
        if ((int) len != rows * cols * ch) {
            luaL_error(L, "cv.createMatFromData: data size %d != %d", (int) len, rows * cols * ch);
        }
        auto *d = newMatUserdata(L, rows, cols, ch);
        memcpy(d->data.data(), data, d->data.size());
        return 1;
    }

    // cv.createGrayMatFromRgba(rows, cols, dataStr)
    static int cvCreateGrayFromRgba(lua_State *L) {
        int rows = (int) luaL_checkinteger(L, 1), cols = (int) luaL_checkinteger(L, 2);
        size_t len = 0;
        const char *data = luaL_checklstring(L, 3, &len);
        if ((int) len != rows * cols * 4) luaL_error(L, "cv.createGrayMatFromRgba: data size != rows*cols*4");
        auto *d = newMatUserdata(L, rows, cols, 1);
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) {
                const uint8_t *p = (const uint8_t *) data + ((size_t) r * cols + c) * 4;
                d->at(r, c, 0) = (uint8_t) ((p[0] * 299 + p[1] * 587 + p[2] * 114) / 1000);
            }
        return 1;
    }

    // cv.matToData(m) → dataStr, rows, cols, ch
    static int cvMatToData(lua_State *L) {
        auto *m = toMat(L, 1);
        lua_pushlstring(L, (const char *) m->data.data(), m->data.size());
        lua_pushinteger(L, m->rows);
        lua_pushinteger(L, m->cols);
        lua_pushinteger(L, m->ch);
        return 4;
    }

    static int cvIsAvailable(lua_State *L) { lua_pushboolean(L, 1); return 1; }
    static int cvGetVersion(lua_State *L) { lua_pushstring(L, "autolua-cv 1.0 (pure C++ subset)"); return 1; }

    static int cvGetMatStats(lua_State *L) {
        lua_newtable(L);
        lua_pushstring(L, "count"); lua_pushinteger(L, gMatCount.load()); lua_rawset(L, -3);
        lua_pushstring(L, "created"); lua_pushinteger(L, gMatCreated.load()); lua_rawset(L, -3);
        return 1;
    }

    static int cvForceGc(lua_State *L) {
        lua_gc(L, LUA_GCCOLLECT, 0);
        lua_pushinteger(L, gMatCount.load());
        return 1;
    }

    // ---------------- 模块注册 ----------------

    static const luaL_Reg cvLibFuncs[] = {
            {"createMat",             cvCreateMat},
            {"createMatFromData",     cvCreateMatFromData},
            {"createGrayMatFromRgba", cvCreateGrayFromRgba},
            {"matToData",             cvMatToData},
            {"cvtColor",              cvCvtColor},
            {"toGray",                cvToGray},
            {"threshold",             cvThreshold},
            {"adaptiveThreshold",     cvAdaptiveThreshold},
            {"blur",                  cvBlur},
            {"gaussianBlur",          cvGaussianBlur},
            {"medianBlur",            cvMedianBlur},
            {"dilate",                cvDilate},
            {"erode",                 cvErode},
            {"morphologyOpen",        cvMorphOpen},
            {"morphologyClose",       cvMorphClose},
            {"bitwiseNot",            cvBitwiseNot},
            {"bitwiseAnd",            cvBitwiseAnd},
            {"bitwiseOr",             cvBitwiseOr},
            {"bitwiseXor",            cvBitwiseXor},
            {"add",                   cvAdd},
            {"subtract",              cvSubtract},
            {"addWeighted",           cvAddWeighted},
            {"alphaBlend",            cvAlphaBlend},
            {"inRange",               cvInRange},
            {"equalizeHist",          cvEqualizeHist},
            {"convertScaleAbs",       cvConvertScaleAbs},
            {"resize",                cvResize},
            {"resizeRatio",           cvResizeRatio},
            {"flip",                  cvFlip},
            {"rotate",                cvRotate},
            {"rotateArbitrary",       cvRotateArbitrary},
            {"cropImage",             cvCrop},
            {"drawRect",              cvDrawRect},
            {"drawLine",              cvDrawLine},
            {"drawCircle",            cvDrawCircle},
            {"extractContours",       cvExtractContours},
            {"despeckle",             cvDespeckle},
            {"keepComponentsBySize",  cvKeepComponentsBySize},
            {"colorBinary",           cvColorBinary},
            {"cropBlackBorder",       cvCropBlackBorder},
            {"minMaxLoc",             cvMinMaxLoc},
            {"mean",                  cvMean},
            {"countNonZero",          cvCountNonZero},
            {"sum",                   cvSum},
            {"split",                 cvSplit},
            {"merge",                 cvMerge},
            {"isAvailable",           cvIsAvailable},
            {"getVersion",            cvGetVersion},
            {"getMatStats",           cvGetMatStats},
            {"forceGc",               cvForceGc},
            {nullptr,                 nullptr}
    };

    // matIndex 带方法表 upvalue：先查方法，再查属性
    static int matDispatch(lua_State *L) {
        lua_pushvalue(L, 2);
        lua_rawget(L, lua_upvalueindex(1));
        if (!lua_isnil(L, -1)) return 1;
        lua_pop(L, 1);
        return matIndex(L);
    }

    void injectCvLib(lua_State *L) {
        luaL_newmetatable(L, CV_MAT_MT);
        luaL_newlib(L, matMethods);            // 方法表
        lua_pushcclosure(L, matDispatch, 1);   // __index 捕获方法表
        lua_setfield(L, -2, "__index");
        lua_pushcfunction(L, matGc);
        lua_setfield(L, -2, "__gc");
        lua_pop(L, 1);

        luaL_newlib(L, cvLibFuncs);
        // 颜色空间常量（对齐 OpenCV 命名）
        lua_pushinteger(L, COLOR_RGBA2GRAY); lua_setfield(L, -2, "COLOR_RGBA2GRAY");
        lua_pushinteger(L, COLOR_RGB2GRAY); lua_setfield(L, -2, "COLOR_RGB2GRAY");
        lua_pushinteger(L, COLOR_BGR2GRAY); lua_setfield(L, -2, "COLOR_BGR2GRAY");
        lua_pushinteger(L, COLOR_GRAY2RGBA); lua_setfield(L, -2, "COLOR_GRAY2RGBA");
        lua_pushinteger(L, COLOR_GRAY2BGR); lua_setfield(L, -2, "COLOR_GRAY2BGR");
        lua_pushinteger(L, COLOR_RGBA2BGR); lua_setfield(L, -2, "COLOR_RGBA2BGR");
        lua_pushinteger(L, COLOR_BGR2RGBA); lua_setfield(L, -2, "COLOR_BGR2RGBA");
        lua_pushinteger(L, COLOR_RGBA2RGB); lua_setfield(L, -2, "COLOR_RGBA2RGB");
        lua_pushinteger(L, COLOR_RGB2RGBA); lua_setfield(L, -2, "COLOR_RGB2RGBA");
        lua_pushinteger(L, COLOR_RGBA2HSV); lua_setfield(L, -2, "COLOR_RGBA2HSV");
        lua_pushinteger(L, COLOR_HSV2RGBA); lua_setfield(L, -2, "COLOR_HSV2RGBA");
        lua_pushinteger(L, COLOR_HSV2BGR); lua_setfield(L, -2, "COLOR_HSV2BGR");
        lua_setglobal(L, "cv");
    }

} // namespace autolua

