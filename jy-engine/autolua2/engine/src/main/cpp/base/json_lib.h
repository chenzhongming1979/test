//
// json 库：基于 3rd/cjson（cJSON）的 Lua 绑定，注入为全局 json 表
// json.encode(value) -> string（table/string/number/boolean/nil，数组表→JSON 数组）
// json.decode(str) -> value（JSON 数组→Lua 表 1..n，对象→字符串键表）
//
// Created by TRAE on 2026/9/12.
//

#ifndef AUTOLUA2_JSON_LIB_H
#define AUTOLUA2_JSON_LIB_H

struct lua_State;

namespace autolua {

    // 在 createLuaContext（luaL_openlibs）之后调用，注入全局 json = {encode, decode}
    void injectJsonLib(lua_State *L);
}

#endif //AUTOLUA2_JSON_LIB_H
