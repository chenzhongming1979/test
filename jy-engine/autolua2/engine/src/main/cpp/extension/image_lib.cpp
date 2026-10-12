//
// images 图像处理模块实现（见 image_lib.h）。
//
// 像素布局跟随 autolua_vision 约定：CommonBitmap 经 lodepng 解码为 RGBA(4字节/像素)；
// 屏幕帧 stride 可能为 3。几何变换逐像素按 pixelStride_ 字节搬移，不做通道重排，
// 与 saveImageTo(lodepng encode) 行为一致。
//
#include "image_lib.h"

#include <lua.hpp>
#include <vector>
#include <string.h>
#include <cmath>
#include <setjmp.h>

#include "lua_util.h"
#include "CommonBitmap.h"
#include "vision_util.h"
#include <lodepng.h>
#include <jpeglib.h>

namespace autolua {

// ----------------------------------------------------------------------
// 参数工具
// ----------------------------------------------------------------------

/** 取参数位置的图像（CommonBitmap/Bitmap userdata），nil 检查按需 */
static Bitmap *toImage(lua_State *L, int index) {
    auto *image = lua::toObject<Bitmap>(L, index);
    if (image == nullptr || image->origin_ == nullptr)
        luaL_error(L, "images: expect an image (loadImage result) at arg %d", index);
    return image;
}

static void checkRectArgs(lua_State *L, Bitmap *image, int index, int &x, int &y, int &w, int &h) {
    x = (int) luaL_checkinteger(L, index);
    y = (int) luaL_checkinteger(L, index + 1);
    w = (int) luaL_checkinteger(L, index + 2);
    h = (int) luaL_checkinteger(L, index + 3);
    if (w <= 0 || h <= 0 || !isInBitmapScope(image, x, y, x + w - 1, y + h - 1))
        luaL_error(L, "images: rect (%d,%d,%d,%d) out of bounds", x, y, w, h);
}

/** 新建输出图（常用 4 字节 RGBA 布局） */
static CommonBitmap *pushNewImage(lua_State *L, int w, int h) {
    auto *out = luaL_pushNewObject(CommonBitmap, L);
    out->create(w, h, 4);
    return out;
}

#define PIXEL_BYTES(image) ((image)->pixelStride_)

// ----------------------------------------------------------------------
// crop / clone / resize
// ----------------------------------------------------------------------

// images.crop(img, x, y, w, h) -> image
static int imageCrop(lua_State *L) {
    auto *src = toImage(L, 1);
    int x, y, w, h;
    checkRectArgs(L, src, 2, x, y, w, h);
    auto *out = luaL_pushNewObject(CommonBitmap, L);
    out->load(src, x, y, w, h);
    return 1;
}

// images.clone(img) -> image（整图拷贝）
static int imageClone(lua_State *L) {
    auto *src = toImage(L, 1);
    auto *out = luaL_pushNewObject(CommonBitmap, L);
    out->load(src, 0, 0, src->width_, src->height_);
    return 1;
}

// images.resize(img, w, h) -> image（最近邻插值，输出 RGBA）
static int imageResize(lua_State *L) {
    auto *src = toImage(L, 1);
    int w = (int) luaL_checkinteger(L, 2);
    int h = (int) luaL_checkinteger(L, 3);
    if (w <= 0 || h <= 0) luaL_error(L, "images.resize: size must be positive");
    int stride = PIXEL_BYTES(src);
    auto *out = pushNewImage(L, w, h);
    for (int dy = 0; dy < h; dy++) {
        int sy = dy * src->height_ / h;
        const unsigned char *srcRow = src->origin_ + (size_t) sy * src->rowShift_;
        unsigned char *dstRow = out->origin_ + (size_t) dy * out->rowShift_;
        for (int dx = 0; dx < w; dx++) {
            int sx = dx * src->width_ / w;
            memcpy(dstRow + dx * 4, srcRow + (size_t) sx * stride, stride);
        }
    }
    return 1;
}

// ----------------------------------------------------------------------
// rotate / flip
// ----------------------------------------------------------------------

// 通用像素搬移：dst(dx,dy) = src(sx,sy)
static void transform(Bitmap *src, CommonBitmap *out, int dstW, int dstH,
                      int (*mapX)(int, int, Bitmap *), int (*mapY)(int, int, Bitmap *)) {
    int stride = PIXEL_BYTES(src);
    for (int dy = 0; dy < dstH; dy++) {
        unsigned char *dstRow = out->origin_ + (size_t) dy * out->rowShift_;
        for (int dx = 0; dx < dstW; dx++) {
            int sx = mapX(dx, dy, src);
            int sy = mapY(dx, dy, src);
            memcpy(dstRow + dx * 4,
                   src->origin_ + (size_t) sy * src->rowShift_ + (size_t) sx * stride, stride);
        }
    }
}

static int mapXRot90(int dx, int dy, Bitmap *src) { return dy; }                     // 顺时针90
static int mapYRot90(int dx, int dy, Bitmap *src) { return src->height_ - 1 - dx; }

static int mapXRot180(int dx, int dy, Bitmap *src) { return src->width_ - 1 - dx; }
static int mapYRot180(int dx, int dy, Bitmap *src) { return src->height_ - 1 - dy; }

static int mapXRot270(int dx, int dy, Bitmap *src) { return src->width_ - 1 - dy; }
static int mapYRot270(int dx, int dy, Bitmap *src) { return dx; }

static int mapXFlipH(int dx, int dy, Bitmap *src) { return src->width_ - 1 - dx; }
static int mapYFlipH(int dx, int dy, Bitmap *src) { return dy; }

static int mapXFlipV(int dx, int dy, Bitmap *src) { return dx; }
static int mapYFlipV(int dx, int dy, Bitmap *src) { return src->height_ - 1 - dy; }

static int imageRotate(lua_State *L, int (*mapX)(int, int, Bitmap *), int (*mapY)(int, int, Bitmap *),
                       int dstW, int dstH) {
    auto *src = toImage(L, 1);
    auto *out = pushNewImage(L, dstW, dstH);
    transform(src, out, dstW, dstH, mapX, mapY);
    return 1;
}

// images.rotate90(img)：顺时针 90 度，输出 w=h_src, h=w_src
static int imageRotate90(lua_State *L) {
    auto *src = toImage(L, 1);
    return imageRotate(L, mapXRot90, mapYRot90, src->height_, src->width_);
}

static int imageRotate180(lua_State *L) {
    auto *src = toImage(L, 1);
    return imageRotate(L, mapXRot180, mapYRot180, src->width_, src->height_);
}

static int imageRotate270(lua_State *L) {
    auto *src = toImage(L, 1);
    return imageRotate(L, mapXRot270, mapYRot270, src->height_, src->width_);
}

// images.flipHorizontal(img) 左右镜像 / images.flipVertical(img) 上下镜像
static int imageFlipHorizontal(lua_State *L) {
    auto *src = toImage(L, 1);
    return imageRotate(L, mapXFlipH, mapYFlipH, src->width_, src->height_);
}

static int imageFlipVertical(lua_State *L) {
    auto *src = toImage(L, 1);
    return imageRotate(L, mapXFlipV, mapYFlipV, src->width_, src->height_);
}

// ----------------------------------------------------------------------
// 像素字节 / PNG 编解码 / base64
// ----------------------------------------------------------------------

// 紧凑像素拷贝（行间去 rowShift_ padding）
static std::vector<unsigned char> compactPixels(Bitmap *image) {
    int rowSize = image->width_ * image->pixelStride_;
    std::vector<unsigned char> out((size_t) rowSize * image->height_);
    for (int row = 0; row < image->height_; row++) {
        memcpy(out.data() + (size_t) row * rowSize,
               image->origin_ + (size_t) row * image->rowShift_, (size_t) rowSize);
    }
    return out;
}

// images.getData(img) -> 紧凑像素字节串（width*stride*height）
static int imageGetData(lua_State *L) {
    auto *src = toImage(L, 1);
    auto data = compactPixels(src);
    lua_pushlstring(L, (const char *) data.data(), data.size());
    return 1;
}

// images.fromData(w, h, dataStr[, stride=4]) -> image
static int imageFromData(lua_State *L) {
    int w = (int) luaL_checkinteger(L, 1);
    int h = (int) luaL_checkinteger(L, 2);
    size_t size = 0;
    const char *data = luaL_checklstring(L, 3, &size);
    int stride = (int) luaL_optinteger(L, 4, 4);
    if (stride != 3 && stride != 4) luaL_error(L, "images.fromData: stride must be 3 or 4");
    if (w <= 0 || h <= 0) luaL_error(L, "images.fromData: size must be positive");
    if (size < (size_t) w * h * stride) luaL_error(L, "images.fromData: data too small");
    auto *out = luaL_pushNewObject(CommonBitmap, L);
    out->create(w, h, stride);
    memcpy(out->origin_, data, (size_t) w * h * stride);
    return 1;
}

// ----------------------------------------------------------------------
// JPEG 编解码（libjpeg-turbo 3.0.4，无 SIMD）
// ----------------------------------------------------------------------

// libjpeg 错误模型：setjmp/longjmp 退出（C 栈帧不可抛 C++ 异常）
struct JpegErrMgr {
    jpeg_error_mgr pub;
    jmp_buf setjmpBuf;
    char msg[JMSG_LENGTH_MAX + 1];
};

static void jpegErrExit(j_common_ptr cinfo) {
    auto *e = (JpegErrMgr *) cinfo->err;
    (*cinfo->err->format_message)(cinfo, e->msg);
    longjmp(e->setjmpBuf, 1);
}

// images.toJpg(img[, quality]) -> jpeg 字节串；images.compress 为别名
static int imageToJpg(lua_State *L) {
    auto *src = toImage(L, 1);
    int quality = 80;
    if (lua_gettop(L) >= 2 && !lua_isnil(L, 2))
        quality = (int) luaL_checkinteger(L, 2);
    if (quality < 1) quality = 1;
    if (quality > 100) quality = 100;

    jpeg_compress_struct cinfo{};
    JpegErrMgr jerr{};
    cinfo.err = jpeg_std_error(&jerr.pub);
    jerr.pub.error_exit = jpegErrExit;
    if (setjmp(jerr.setjmpBuf)) {
        jpeg_destroy_compress(&cinfo);
        luaL_error(L, "images.toJpg: %s", jerr.msg);
    }
    jpeg_create_compress(&cinfo);
    unsigned char *outBuf = nullptr;
    unsigned long outSize = 0;
    jpeg_mem_dest(&cinfo, &outBuf, &outSize);
    cinfo.image_width = (JDIMENSION) src->width_;
    cinfo.image_height = (JDIMENSION) src->height_;
    cinfo.input_components = 3;
    cinfo.in_color_space = JCS_RGB;
    jpeg_set_defaults(&cinfo);
    jpeg_set_quality(&cinfo, quality, TRUE);
    jpeg_start_compress(&cinfo, TRUE);
    auto data = compactPixels(src);
    int stride = src->pixelStride_;
    int w = src->width_;
    std::vector<unsigned char> row((size_t) w * 3);
    while (cinfo.next_scanline < cinfo.image_height) {
        const unsigned char *s = data.data() + (size_t) cinfo.next_scanline * src->rowShift_;
        for (int x = 0; x < w; x++) {
            row[(size_t) x * 3] = s[(size_t) x * stride];
            row[(size_t) x * 3 + 1] = s[(size_t) x * stride + 1];
            row[(size_t) x * 3 + 2] = s[(size_t) x * stride + 2];
        }
        JSAMPROW rp = row.data();
        jpeg_write_scanlines(&cinfo, &rp, 1);
    }
    jpeg_finish_compress(&cinfo);
    jpeg_destroy_compress(&cinfo);
    lua_pushlstring(L, (const char *) outBuf, outSize);
    if (outBuf != nullptr) free(outBuf);
    return 1;
}

// images.fromJpg(dataStr) -> image（turbo 扩展 JCS_EXT_RGBA 直出 4 字节布局）
static int imageFromJpg(lua_State *L) {
    size_t size = 0;
    const char *data = luaL_checklstring(L, 1, &size);
    if (size == 0) luaL_error(L, "images.fromJpg: empty data");

    jpeg_decompress_struct cinfo{};
    JpegErrMgr jerr{};
    cinfo.err = jpeg_std_error(&jerr.pub);
    jerr.pub.error_exit = jpegErrExit;
    if (setjmp(jerr.setjmpBuf)) {
        jpeg_destroy_decompress(&cinfo);
        luaL_error(L, "images.fromJpg: %s", jerr.msg);
    }
    jpeg_create_decompress(&cinfo);
    jpeg_mem_src(&cinfo, (unsigned char *) data, size);
    jpeg_read_header(&cinfo, TRUE);
    cinfo.out_color_space = JCS_EXT_RGBA;
    jpeg_start_decompress(&cinfo);
    int w = (int) cinfo.output_width, h = (int) cinfo.output_height;
    auto *out = pushNewImage(L, w, h);
    while (cinfo.output_scanline < cinfo.output_height) {
        unsigned char *dst = out->origin_ + (size_t) cinfo.output_scanline * out->rowShift_;
        JSAMPROW rp = dst;
        jpeg_read_scanlines(&cinfo, &rp, 1);
    }
    jpeg_finish_decompress(&cinfo);
    jpeg_destroy_decompress(&cinfo);
    return 1;
}

// images.saveJpg(img, path[, quality]) -> bool, err
static int imageSaveJpg(lua_State *L) {
    // 复用 toJpg 逻辑：把参数栈调成 toJpg 形态后调用其实现
    lua_pushcfunction(L, imageToJpg);
    lua_pushvalue(L, 1);
    if (lua_gettop(L) >= 3 && !lua_isnil(L, 3)) lua_pushvalue(L, 3);
    else lua_pushinteger(L, 80);
    lua_call(L, 2, 1);
    size_t size = 0;
    const char *jpeg = luaL_checklstring(L, -1, &size);
    const char *path = luaL_checkstring(L, 2);
    FILE *file = fopen(path, "wb");
    if (file == nullptr) {
        lua_pushboolean(L, false);
        lua_pushstring(L, "open file failed");
        return 2;
    }
    fwrite(jpeg, 1, size, file);
    fclose(file);
    lua_pushboolean(L, true);
    return 1;
}

// images.readJpg(path) -> image
static int imageReadJpg(lua_State *L) {
    const char *path = luaL_checkstring(L, 1);
    FILE *file = fopen(path, "rb");
    if (file == nullptr) luaL_error(L, "images.readJpg: open file failed");
    fseek(file, 0, SEEK_END);
    long fsize = ftell(file);
    fseek(file, 0, SEEK_SET);
    std::vector<unsigned char> buf((size_t) fsize);
    size_t rd = fread(buf.data(), 1, (size_t) fsize, file);
    fclose(file);
    lua_pushlstring(L, (const char *) buf.data(), rd);
    lua_replace(L, 1);  // 数据串替换路径参数，转入 fromJpg
    return imageFromJpg(L);
}

// PNG 编码（跟随图像 stride 选 RGB/RGBA）
static int encodePng(Bitmap *image, std::vector<unsigned char> &png) {
    lodepng::State state;
    state.info_raw.colortype = image->pixelStride_ == 4 ? LodePNGColorType::LCT_RGBA
                                                        : LodePNGColorType::LCT_RGB;
    auto data = compactPixels(image);
    return lodepng::encode(png, data, image->width_, image->height_, state);
}

// images.toPng(img) -> png 字节串
static int imageToPng(lua_State *L) {
    auto *src = toImage(L, 1);
    std::vector<unsigned char> png;
    auto error = encodePng(src, png);
    if (error) luaL_error(L, "images.toPng: %s", lodepng_error_text(error));
    lua_pushlstring(L, (const char *) png.data(), png.size());
    return 1;
}

// images.save(img, path) -> bool, err（PNG 落盘）
static int imageSave(lua_State *L) {
    auto *src = toImage(L, 1);
    const char *path = luaL_checkstring(L, 2);
    std::vector<unsigned char> png;
    auto error = encodePng(src, png);
    if (error) {
        lua_pushboolean(L, false);
        lua_pushstring(L, lodepng_error_text(error));
        return 2;
    }
    FILE *file = fopen(path, "wb");
    if (file == nullptr) {
        lua_pushboolean(L, false);
        lua_pushstring(L, "open file failed");
        return 2;
    }
    fwrite(png.data(), 1, png.size(), file);
    fclose(file);
    lua_pushboolean(L, true);
    return 1;
}

// images.read(path) -> image（=loadImage）
static int imageRead(lua_State *L) {
    const char *path = luaL_checkstring(L, 1);
    auto *image = luaL_pushNewObject(CommonBitmap, L);
    if (!image->load(path))
        return 0;
    return 1;
}

// ----------------------------------------------------------------------
// base64（自实现，避免依赖 Java 层 crypto）
// ----------------------------------------------------------------------

static const char B64_ALPHABET[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

static std::string b64Encode(const unsigned char *data, size_t size) {
    std::string out;
    out.reserve((size + 2) / 3 * 4);
    for (size_t i = 0; i < size; i += 3) {
        unsigned int v = data[i] << 16;
        if (i + 1 < size) v |= data[i + 1] << 8;
        if (i + 2 < size) v |= data[i + 2];
        out += B64_ALPHABET[(v >> 18) & 0x3F];
        out += B64_ALPHABET[(v >> 12) & 0x3F];
        out += i + 1 < size ? B64_ALPHABET[(v >> 6) & 0x3F] : '=';
        out += i + 2 < size ? B64_ALPHABET[v & 0x3F] : '=';
    }
    return out;
}

static int b64Value(char c) {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+') return 62;
    if (c == '/') return 63;
    return -1;
}

static bool b64Decode(const char *in, size_t size, std::vector<unsigned char> &out) {
    out.clear();
    out.reserve(size / 4 * 3);
    unsigned int buf = 0;
    int bits = 0;
    for (size_t i = 0; i < size; i++) {
        char c = in[i];
        if (c == '=' || c == '\n' || c == '\r') continue;
        int v = b64Value(c);
        if (v < 0) return false;
        buf = (buf << 6) | (unsigned int) v;
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out.push_back((unsigned char) ((buf >> bits) & 0xFF));
        }
    }
    return true;
}

// images.toBase64(img) -> base64(PNG)
static int imageToBase64(lua_State *L) {
    auto *src = toImage(L, 1);
    std::vector<unsigned char> png;
    auto error = encodePng(src, png);
    if (error) luaL_error(L, "images.toBase64: %s", lodepng_error_text(error));
    lua_pushstring(L, b64Encode(png.data(), png.size()).c_str());
    return 1;
}

// images.fromBase64(b64Str) -> image（PNG/PNG 字节均可，lodepng 按 PNG 解码）
static int imageFromBase64(lua_State *L) {
    size_t size = 0;
    const char *str = luaL_checklstring(L, 1, &size);
    std::vector<unsigned char> bytes;
    if (!b64Decode(str, size, bytes) || bytes.empty())
        luaL_error(L, "images.fromBase64: invalid base64");
    auto *image = luaL_pushNewObject(CommonBitmap, L);
    if (!image->load(bytes.data(), (unsigned int) bytes.size()))
        return 0;
    return 1;
}

// images.readImageToBase64(path) -> base64(PNG)
static int imageReadImageToBase64(lua_State *L) {
    const char *path = luaL_checkstring(L, 1);
    CommonBitmap image;
    if (!image.load(path))
        luaL_error(L, "images.readImageToBase64: load failed: %s", path);
    std::vector<unsigned char> png;
    auto error = encodePng(&image, png);
    if (error) luaL_error(L, "images.readImageToBase64: %s", lodepng_error_text(error));
    lua_pushstring(L, b64Encode(png.data(), png.size()).c_str());
    return 1;
}

// images.writeFileFromBase64(b64Str, path) -> bool（解码字节直接落盘）
static int imageWriteFileFromBase64(lua_State *L) {
    size_t size = 0;
    const char *str = luaL_checklstring(L, 1, &size);
    const char *path = luaL_checkstring(L, 2);
    std::vector<unsigned char> bytes;
    if (!b64Decode(str, size, bytes)) {
        lua_pushboolean(L, false);
        return 1;
    }
    FILE *file = fopen(path, "wb");
    if (file == nullptr) {
        lua_pushboolean(L, false);
        return 1;
    }
    fwrite(bytes.data(), 1, bytes.size(), file);
    fclose(file);
    lua_pushboolean(L, true);
    return 1;
}

// ----------------------------------------------------------------------
// 尺寸 / 状态
// ----------------------------------------------------------------------

static int imageIsNull(lua_State *L) {
    auto *image = lua::toObject<Bitmap>(L, 1);
    lua_pushboolean(L, image == nullptr || image->origin_ == nullptr);
    return 1;
}

static int imageGetWidth(lua_State *L) {
    auto *image = toImage(L, 1);
    lua_pushinteger(L, image->width_);
    return 1;
}

static int imageGetHeight(lua_State *L) {
    auto *image = toImage(L, 1);
    lua_pushinteger(L, image->height_);
    return 1;
}

static int imageGetSize(lua_State *L) {
    auto *image = toImage(L, 1);
    lua_pushinteger(L, image->width_);
    lua_pushinteger(L, image->height_);
    return 2;
}

// ----------------------------------------------------------------------
// 二值化（懒人 binaryImage/binaryRect）与任意角度旋转
// ----------------------------------------------------------------------

// 灰度值（按 stride 取 RGB；0.299R+0.587G+0.114B）
static inline int grayOf(const unsigned char *pixel, int stride) {
    int r = pixel[0], g = pixel[1], b = pixel[2];
    return (299 * r + 587 * g + 114 * b) / 1000;
}

// images.binarize(img, threshold[, x, y, w, h]) -> image
// 阈值 0~255：灰度 >= 阈值置白(0xFFFFFF)，否则黑；区域外像素透传；输出 RGBA
static int imageBinarize(lua_State *L) {
    auto *src = toImage(L, 1);
    int threshold = (int) luaL_checkinteger(L, 2);
    if (threshold < 0 || threshold > 255) luaL_error(L, "images.binarize: threshold must be 0~255");
    int x = 0, y = 0, w = src->width_, h = src->height_;
    if (lua_gettop(L) >= 6) {
        x = (int) luaL_checkinteger(L, 3);
        y = (int) luaL_checkinteger(L, 4);
        w = (int) luaL_checkinteger(L, 5);
        h = (int) luaL_checkinteger(L, 6);
        if (w <= 0 || h <= 0 || !isInBitmapScope(src, x, y, x + w - 1, y + h - 1))
            luaL_error(L, "images.binarize: rect out of bounds");
    }
    int stride = PIXEL_BYTES(src);
    auto *out = pushNewImage(L, src->width_, src->height_);
    for (int row = 0; row < src->height_; row++) {
        const unsigned char *srcRow = src->origin_ + (size_t) row * src->rowShift_;
        unsigned char *dstRow = out->origin_ + (size_t) row * out->rowShift_;
        for (int col = 0; col < src->width_; col++) {
            unsigned char *dst = dstRow + (size_t) col * 4;
            if (col >= x && col < x + w && row >= y && row < y + h) {
                int gray = grayOf(srcRow + (size_t) col * stride, stride);
                dst[0] = dst[1] = dst[2] = gray >= threshold ? 255 : 0;
                dst[3] = 255;
            } else {
                memcpy(dst, srcRow + (size_t) col * stride, stride);
                if (stride == 3) dst[3] = 255;
            }
        }
    }
    return 1;
}

// images.rotate(img, angle) -> image（任意角度，逆映射最近邻，中心旋转，出界填黑）
static int imageRotateAny(lua_State *L) {
    auto *src = toImage(L, 1);
    double angle = luaL_checknumber(L, 2);
    double rad = angle * 3.14159265358979323846 / 180.0;
    double cosA = cos(rad), sinA = sin(rad);
    int w = src->width_, h = src->height_;
    double cx = (w - 1) / 2.0, cy = (h - 1) / 2.0;
    int stride = PIXEL_BYTES(src);
    auto *out = pushNewImage(L, w, h);
    for (int dy = 0; dy < h; dy++) {
        unsigned char *dstRow = out->origin_ + (size_t) dy * out->rowShift_;
        for (int dx = 0; dx < w; dx++) {
            unsigned char *dst = dstRow + (size_t) dx * 4;
            // 逆映射：输出点绕中心反向旋转得源点
            double rx = cosA * (dx - cx) + sinA * (dy - cy) + cx;
            double ry = -sinA * (dx - cx) + cosA * (dy - cy) + cy;
            int sx = (int) (rx + 0.5), sy = (int) (ry + 0.5);
            if (sx >= 0 && sx < w && sy >= 0 && sy < h) {
                memcpy(dst, src->origin_ + (size_t) sy * src->rowShift_ + (size_t) sx * stride, stride);
                if (stride == 3) dst[3] = 255;
            } else {
                dst[0] = dst[1] = dst[2] = dst[3] = 0;
            }
        }
    }
    return 1;
}

// ----------------------------------------------------------------------
// 注册
// ----------------------------------------------------------------------

static const luaL_Reg IMAGE_LIB[] = {
        {"crop",                 imageCrop},
        {"clone",                imageClone},
        {"resize",               imageResize},
        {"rotate90",             imageRotate90},
        {"rotate180",            imageRotate180},
        {"rotate270",            imageRotate270},
        {"rotate",               imageRotateAny},
        {"binarize",             imageBinarize},
        {"flipHorizontal",       imageFlipHorizontal},
        {"flipVertical",         imageFlipVertical},
        {"getData",              imageGetData},
        {"fromData",             imageFromData},
        {"toPng",                imageToPng},
        {"toJpg",                imageToJpg},
        {"compress",             imageToJpg},
        {"fromJpg",              imageFromJpg},
        {"saveJpg",              imageSaveJpg},
        {"readJpg",              imageReadJpg},
        {"save",                 imageSave},
        {"read",                 imageRead},
        {"toBase64",             imageToBase64},
        {"fromBase64",           imageFromBase64},
        {"readImageToBase64",    imageReadImageToBase64},
        {"writeFileFromBase64",  imageWriteFileFromBase64},
        {"isNull",               imageIsNull},
        {"getWidth",             imageGetWidth},
        {"getHeight",            imageGetHeight},
        {"getSize",              imageGetSize},
        {nullptr, nullptr}
};

int injectImageLib(lua_State *L) {
    lua_newtable(L);
    luaL_setfuncs(L, IMAGE_LIB, 0);
    lua_setglobal(L, "images");
    return 0;
}

} // namespace autolua
