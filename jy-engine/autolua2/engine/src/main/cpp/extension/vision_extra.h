//
// 图色增强补齐（对齐懒人 color 周边 / 派大星 images）：
//   findColorAllPoint  区域内全部单色匹配点
//   findFeatureAll     区域内全部特征匹配点
//   getScreenPixel     区域像素二维数组导出
// （isDisplayDead 依赖 Display 私有采样锁，实现于 Display.cpp）
//
// 三个函数均由 Display::injectToLua 经 transitMethod 包装注册
// （自动刷帧 + shared_lock），upvalue1 为 Display*（继承 Bitmap）。
//
#ifndef AUTOLUA2_VISION_EXTRA_H
#define AUTOLUA2_VISION_EXTRA_H

struct lua_State;

namespace autolua {

int visionFindColorAllPoint(lua_State *L);

int visionFindFeatureAll(lua_State *L);

int visionFindImageAll(lua_State *L);

int visionGetScreenPixel(lua_State *L);

int visionGetScreenFrame(lua_State *L);

} // namespace autolua

#endif //AUTOLUA2_VISION_EXTRA_H
