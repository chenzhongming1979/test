//
// json 库实现：cJSON <-> Lua 双向转换
// Created by TRAE on 2026/9/12.
//

#include "json_lib.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>

extern "C" {
#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"
}

#include "cJSON.h"

namespace autolua {

    // ---------------- Lua -> cJSON ----------------

    static cJSON *luaValueToCjson(lua_State *L, int index);

    // 连续正整数键 1..n 判定为数组（空表输出 {}）
    static cJSON *luaTableToCjson(lua_State *L, int index) {
        // 调用方可能传相对索引（如 -1），函数内 pushnil 后相对索引会错位——先转绝对索引
        index = lua_absindex(L, index);
        // 第一遍：统计键形态
        bool isArray = true;
        int n = 0;
        lua_pushnil(L);
        while (lua_next(L, index)) {
            if (lua_type(L, -2) == LUA_TNUMBER) {
                lua_Number k = lua_tonumber(L, -2);
                if (k == (lua_Number)(lua_Integer) k && k >= 1 && k == (lua_Number) (n + 1)) {
                    n++;
                } else {
                    isArray = false;
                }
            } else if (lua_type(L, -2) == LUA_TSTRING) {
                isArray = false;
            } else {
                isArray = false;
            }
            lua_pop(L, 1);
        }
        if (n == 0) isArray = false;

        if (isArray) {
            cJSON *arr = cJSON_CreateArray();
            for (int i = 1; i <= n; i++) {
                lua_rawgeti(L, index, i);
                cJSON_AddItemToArray(arr, luaValueToCjson(L, -1));
                lua_pop(L, 1);
            }
            return arr;
        }

        cJSON *obj = cJSON_CreateObject();
        lua_pushnil(L);
        while (lua_next(L, index)) {
            char keyBuf[32];
            const char *key = nullptr;
            if (lua_type(L, -2) == LUA_TSTRING) {
                key = lua_tostring(L, -2);
            } else if (lua_type(L, -2) == LUA_TNUMBER) {
                // 数字键转字符串（不得用 lua_tostring：会原位改写键值破坏 lua_next 迭代）
                std::snprintf(keyBuf, sizeof(keyBuf), "%lld", (long long) lua_tointeger(L, -2));
                key = keyBuf;
            }
            if (key) {
                cJSON_AddItemToObject(obj, key, luaValueToCjson(L, -1));
            }
            lua_pop(L, 1);
        }
        return obj;
    }

    static cJSON *luaValueToCjson(lua_State *L, int index) {
        switch (lua_type(L, index)) {
            case LUA_TBOOLEAN:
                return cJSON_CreateBool(lua_toboolean(L, index) ? 1 : 0);
            case LUA_TNUMBER: {
                if (lua_isinteger(L, index)) {
                    return cJSON_CreateNumber((double) lua_tointeger(L, index));
                }
                return cJSON_CreateNumber(lua_tonumber(L, index));
            }
            case LUA_TSTRING: {
                size_t len = 0;
                const char *s = lua_tolstring(L, index, &len);
                // cJSON_CreateString 以 \0 截断；二进制字符串（含 \0）按前缀处理
                return cJSON_CreateString(s ? s : "");
            }
            case LUA_TTABLE:
                return luaTableToCjson(L, index);
            default:
                // function/userdata/thread 等：JSON 无对应类型，按 null 序列化
                return cJSON_CreateNull();
        }
    }

    static int jsonEncode(lua_State *L) {
        cJSON *c = luaValueToCjson(L, 1);
        char *s = cJSON_PrintUnformatted(c);
        lua_pushstring(L, s ? s : "null");
        if (s) cJSON_free(s);
        cJSON_Delete(c);
        return 1;
    }

    // ---------------- cJSON -> Lua ----------------

    static void cjsonToLua(lua_State *L, const cJSON *c) {
        if (c == nullptr) {
            lua_pushnil(L);
            return;
        }
        switch (c->type & 0xFF) {
            case cJSON_False:
                lua_pushboolean(L, 0);
                return;
            case cJSON_True:
                lua_pushboolean(L, 1);
                return;
            case cJSON_NULL:
            case cJSON_Invalid:
                lua_pushnil(L);
                return;
            case cJSON_Number: {
                double d = c->valuedouble;
                if (std::floor(d) == d && std::abs(d) < 9.0e15) {
                    lua_pushinteger(L, (lua_Integer) d);
                } else {
                    lua_pushnumber(L, d);
                }
                return;
            }
            case cJSON_String:
                lua_pushstring(L, c->valuestring ? c->valuestring : "");
                return;
            case cJSON_Array: {
                int i = 1;
                lua_createtable(L, cJSON_GetArraySize(c), 0);
                for (cJSON *item = c->child; item; item = item->next) {
                    lua_pushinteger(L, i++);
                    cjsonToLua(L, item);
                    lua_rawset(L, -3);
                }
                return;
            }
            case cJSON_Object: {
                lua_createtable(L, 0, cJSON_GetArraySize(c));
                for (cJSON *item = c->child; item; item = item->next) {
                    lua_pushstring(L, item->string ? item->string : "");
                    cjsonToLua(L, item);
                    lua_rawset(L, -3);
                }
                return;
            }
            default:
                lua_pushnil(L);
                return;
        }
    }

    static int jsonDecode(lua_State *L) {
        size_t len = 0;
        const char *s = luaL_checklstring(L, 1, &len);
        cJSON *c = cJSON_ParseWithLength(s, len);
        if (c == nullptr) {
            const char *err = cJSON_GetErrorPtr();
            if (err == nullptr) err = s;
            luaL_error(L, "json.decode: invalid json near \"%.24s\"", err);
        }
        cjsonToLua(L, c);
        cJSON_Delete(c);
        return 1;
    }

    // ---------------- 注入 ----------------

    static const luaL_Reg jsonLibFuncs[] = {
            {"encode", jsonEncode},
            {"decode", jsonDecode},
            {nullptr,  nullptr}
    };

    void injectJsonLib(lua_State *L) {
        luaL_newlib(L, jsonLibFuncs);
        lua_setglobal(L, "json");
    }
}
