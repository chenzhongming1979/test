//
// images 图像处理模块（对齐派大星 images/imagesWrapper 可落地子集）：
//   crop resize rotate90/180/270 flipHorizontal flipVertical
//   getData fromData toPng toBase64 fromBase64 readImageToBase64 writeFileFromBase64
//   isNull getWidth getHeight getSize clone save read
//
// 注入为 Lua 全局表 `images`；入参图像为 loadImage 的 Bitmap（含屏幕帧不可传，
// 仅 CommonBitmap；屏幕区域处理用 crop 前先 cloneImage/snapShot 落地）。
//
#ifndef AUTOLUA2_IMAGE_LIB_H
#define AUTOLUA2_IMAGE_LIB_H

struct lua_State;

namespace autolua {

/** 注入全局 images 表（engine.cpp LuaStateFactory 调用） */
int injectImageLib(lua_State *L);

} // namespace autolua

#endif //AUTOLUA2_IMAGE_LIB_H
