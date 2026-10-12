//
// cv 库（OpenCV Mat 体系子集：零依赖纯 C++ 图像加工）
// Created by TRAE on 2026/9/14.
//

#ifndef AUTOLUA2_CV_LIB_H
#define AUTOLUA2_CV_LIB_H

struct lua_State;

namespace autolua {

    // 向 Lua 注入全局 cv 库 + cv.Mat 元表（在 createLuaContext 的 luaL_openlibs 之后调用）
    void injectCvLib(lua_State *L);
}

#endif //AUTOLUA2_CV_LIB_H
