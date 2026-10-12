//
// 图色增强补齐实现（见 vision_extra.h）。
//
// 坐标约定与既有图色函数一致：区域 (x, y, x1, y1) 为含端点闭区间。
// 颜色参数兼容整数色值与字符串色值（decodeColor 全类型：单色/色域/排除/组合）。
//
#include "vision_extra.h"

#include <lua.hpp>
#include <vector>
#include <string.h>

#include "vision.h"
#include "vision_feature.h"
#include "vision_util.h"
#include "Bitmap.h"
#include "CommonBitmap.h"

// loadImages 定义于 lua_vision.cc（全局作用域）：
//   bool loadImages(const char *names, size_t size, std::vector<CommonBitmap> &images)
// 注：其内部 resourceProvider 为该编译单元的文件级静态，本单元引用时恒为空——
// 即仅支持绝对路径直读；资源相对路径（resourceProvider 回调）不可用。
bool loadImages(const char *names, size_t size, std::vector<autolua::CommonBitmap> &images);

namespace autolua {

// ----------------------------------------------------------------------
// 收集器：compare 永不返回 true，借助 orderFindColor 遍历整区域收集全部匹配
// ----------------------------------------------------------------------

template<class TColor>
class PointCollector {
    Bitmap *bitmap_;
    TColor color_;
    int shift_;
public:
    std::vector<Point> points;

    PointCollector(Bitmap *bitmap, TColor color, int shift)
            : bitmap_(bitmap), color_(color), shift_(shift) {}

    bool compare(int x, int y, const unsigned char *pixel) {
        if (compareColor(pixel, color_, shift_))
            points.emplace_back(x, y);
        return false;
    }
};

class FeatureAllCollector {
    Bitmap *bitmap_;
    FeatureCompositionRoot *feature_;
    int shift_;
public:
    std::vector<Point> points;

    FeatureAllCollector(Bitmap *bitmap, FeatureCompositionRoot *feature, int shift)
            : bitmap_(bitmap), feature_(feature), shift_(shift) {}

    bool compare(int x, int y, const unsigned char *) {
        if (isFeature(bitmap_, x, y, feature_, shift_))
            points.emplace_back(x, y);
        return false;
    }
};

// push {{x,y},{x,y},...} 数组
static void pushPoints(lua_State *L, const std::vector<Point> &points) {
    lua_createtable(L, (int) points.size(), 0);
    int index = 1;
    for (auto &p: points) {
        lua_createtable(L, 2, 0);
        lua_pushinteger(L, p.x);
        lua_rawseti(L, -2, 1);
        lua_pushinteger(L, p.y);
        lua_rawseti(L, -2, 2);
        lua_rawseti(L, -2, index++);
    }
}

static void ensureOrder(lua_State *L, int index, int *order) {
    *order = (int) luaL_optinteger(L, index, 1);
    if (*order < 1 || *order > 8)
        luaL_error(L, "Order must be between 1 and 8");
}

static void ensureRect(lua_State *L, Bitmap *bitmap, int *x, int *y, int *x1, int *y1) {
    *x = (int) luaL_checkinteger(L, 1);
    *y = (int) luaL_checkinteger(L, 2);
    *x1 = (int) luaL_checkinteger(L, 3);
    *y1 = (int) luaL_checkinteger(L, 4);
    if (!isInBitmapScope(bitmap, *x, *y, *x1, *y1))
        luaL_error(L, "The coordinates are off screen");
}

// ----------------------------------------------------------------------
// findColorAllPoint(x1, y1, x2, y2, color, sim[, order]) -> {{x,y},...}
// ----------------------------------------------------------------------
int visionFindColorAllPoint(lua_State *L) {
    auto *bitmap = (Bitmap *) lua_touserdata(L, lua_upvalueindex(1));
    int x, y, x1, y1;
    ensureRect(L, bitmap, &x, &y, &x1, &y1);
    auto shift = (int) ((1 - luaL_optnumber(L, 6, 1)) * MAX_COLOR_SHIFT);
    int order;
    ensureOrder(L, 7, &order);

    if (lua_isinteger(L, 5)) {
        Color color((ColorValueType) lua_tointeger(L, 5));
        PointCollector<Color *> collector(bitmap, &color, shift);
        orderFindColor(bitmap, x, y, x1, y1, order, &collector);
        pushPoints(L, collector.points);
    } else if (lua_isstring(L, 5)) {
        size_t size = 0;
        const char *str = lua_tolstring(L, 5, &size);
        ColorComposition *comp = decodeColor(str, (int) size);
        if (comp == nullptr)
            luaL_error(L, "Invalid color string");
        PointCollector<ColorComposition *> collector(bitmap, comp, shift);
        orderFindColor(bitmap, x, y, x1, y1, order, &collector);
        freeColorComposition(comp);
        pushPoints(L, collector.points);
    } else {
        luaL_error(L, "Invalid color type");
    }
    return 1;
}

// ----------------------------------------------------------------------
// findFeatureAll(x1, y1, x2, y2, featureStr, sim[, order]) -> {{x,y},...}
// 特征串格式与 findFeature 一致："x|y|色[,x|y|色...]"，首点为匹配原点。
// ----------------------------------------------------------------------
int visionFindFeatureAll(lua_State *L) {
    auto *bitmap = (Bitmap *) lua_touserdata(L, lua_upvalueindex(1));
    int x, y, x1, y1;
    ensureRect(L, bitmap, &x, &y, &x1, &y1);
    auto sim = luaL_optnumber(L, 6, 1);
    int order;
    ensureOrder(L, 7, &order);

    size_t featureSize = 0;
    const char *featureStr = luaL_checklstring(L, 5, &featureSize);
    FeatureCompositionRoot feature;
    if (!decodeFeature(featureStr, (int) featureSize, &feature))
        luaL_error(L, "Invalid feature string");
    auto shiftSum = (int) ((1 - sim) * MAX_COLOR_SHIFT * feature.count);
    FeatureAllCollector collector(bitmap, &feature, shiftSum);
    orderFindColor(bitmap, x, y, x1, y1, order, &collector);
    freeFeatureComposition(&feature);
    pushPoints(L, collector.points);
    return 1;
}

// ----------------------------------------------------------------------
// findImageAll(x1, y1, x2, y2, imageNames, sim[, order]) -> {{x,y},...}
// 模板全匹配（懒人 findPicAllPoint）：imageNames 与 findImage/isImage 一致，
// 竖线分隔多模板，命中任一模板即收集该点（含重叠匹配，未做去重）。
// ----------------------------------------------------------------------
class ImageAllCollector {
    Bitmap *bitmap_;
    std::vector<CommonBitmap> *images_;
    std::vector<int> shiftSums_;
public:
    std::vector<Point> points;

    ImageAllCollector(Bitmap *bitmap, std::vector<CommonBitmap> *images, int onePointShiftSum)
            : bitmap_(bitmap), images_(images) {
        for (auto &img: *images_)
            shiftSums_.push_back((int) (img.width_ * img.height_ * onePointShiftSum));
    }

    bool compare(int x, int y, const unsigned char *) {
        for (size_t i = 0; i < images_->size(); i++) {
            if (isImage(bitmap_, x, y, &images_->at(i), shiftSums_.at(i))) {
                points.emplace_back(x, y);
                break;
            }
        }
        return false;
    }
};

int visionFindImageAll(lua_State *L) {
    auto *bitmap = (Bitmap *) lua_touserdata(L, lua_upvalueindex(1));
    int x, y, x1, y1;
    ensureRect(L, bitmap, &x, &y, &x1, &y1);
    auto sim = luaL_optnumber(L, 6, 1);
    int order;
    ensureOrder(L, 7, &order);

    size_t namesSize = 0;
    const char *names = luaL_checklstring(L, 5, &namesSize);
    std::vector<CommonBitmap> images;
    if (!loadImages(names, namesSize, images) || images.empty())
        luaL_error(L, "Invalid image string");
    auto onePointShiftSum = (int) ((1 - sim) * MAX_COLOR_SHIFT);
    ImageAllCollector collector(bitmap, &images, onePointShiftSum);
    orderFindColor(bitmap, x, y, x1, y1, order, &collector);
    pushPoints(L, collector.points);
    return 1;
}

// ----------------------------------------------------------------------
// getScreenFrame() -> data, w, h, stride, format
// 整帧像素一次性导出（captureScreen 高性能帧访问）：data 为长度 w*stride*h
// 的字节串（行主序，行间含 rowStride padding 时 stride > w*4），format 恒为
// "RGBA"（ImageReader RGBA_8888 内存序）。OCR 插件/外部管道直接消费。
// 【字节序语义确认，待真机实测复核】原始帧透传不改：format 标签与
// MediaProjection 共享内存管线（isRgbaOrder()==true，VisionBridge.cpp:12-16）一致；
// root 镜像 ImageReader 历史口径为 BGRA（isRgbaOrder()==false），消费方按实例
// 字节序自行处理，此处标签维持现状（任务书 D：透传不动）。
// ----------------------------------------------------------------------
int visionGetScreenFrame(lua_State *L) {
    auto *bitmap = (Bitmap *) lua_touserdata(L, lua_upvalueindex(1));
    int w = bitmap->width_, h = bitmap->height_, stride = bitmap->rowShift_;
    if (w <= 0 || h <= 0 || bitmap->origin_ == nullptr)
        luaL_error(L, "getScreenFrame: frame unavailable");
    size_t size = (size_t) stride * h;
    lua_pushlstring(L, (const char *) bitmap->origin_, size);
    lua_pushinteger(L, w);
    lua_pushinteger(L, h);
    lua_pushinteger(L, stride);
    lua_pushstring(L, "RGBA");
    return 5;
}

// ----------------------------------------------------------------------
// getScreenPixel(x1, y1, x2, y2) -> {row = {color, ...}, ...}
// 色值为 0xRRGGBB 整数（与 getColor 一致）。
// 【字节序语义，待真机实测复核】色值经 vision.h getColor() 的 UNORDERED_PIXEL
// 映射（r=帧[2], g=帧[1], b=帧[0]）逐实例自适应：
//   - 帧为 RGBA（isRgbaOrder()==true）：返回文档口径 0xRRGGBB；
//   - 帧为 BGRA（root 历史口径，isRgbaOrder()==false）：保持现状位级不变。
// 无需在此按 isRgbaOrder 再做 R/B 通道对调（对调会破坏与 getColor/findColor
// 的一致性——三者共用同一套 UNORDERED 映射，口径天然逐实例一致）。
// ----------------------------------------------------------------------
int visionGetScreenPixel(lua_State *L) {
    auto *bitmap = (Bitmap *) lua_touserdata(L, lua_upvalueindex(1));
    int x, y, x1, y1;
    ensureRect(L, bitmap, &x, &y, &x1, &y1);
    int rows = y1 - y + 1, cols = x1 - x + 1;
    lua_createtable(L, rows, 0);
    for (int row = 0; row < rows; row++) {
        lua_createtable(L, cols, 0);
        for (int col = 0; col < cols; col++) {
            lua_pushinteger(L, (int) getColor(bitmap, x + col, y + row));
            lua_rawseti(L, -2, col + 1);
        }
        lua_rawseti(L, -2, row + 1);
    }
    return 1;
}

} // namespace autolua
