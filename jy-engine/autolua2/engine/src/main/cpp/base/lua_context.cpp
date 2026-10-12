//
// Created by lizhi on 2024/5/8.
//

#include "lua_context.h"

#include "util.h"
#include "mlua.h"
#include "thread_lib.h"
#include "json_lib.h"
#include "cv_lib.h"

#include <mutex>

#define toLuaState(L) ((lua_State*)L)

#define PUSH_THROWABLE_ERROR "push java throwable error"


static void throwTypeError(JNIEnv *env, lua_State*L,int index,int exception)
{
    char buffer[1024];
    const char* exceptionName = lua_typename(L,exception);
    const char* nowName = lua_typename(L,lua_type(L,index));
    sprintf(buffer,"luaState %p  index %d exception type %s now type %s",L,index,exceptionName,nowName);
    LocalReference<jclass> jobj(env,env->FindClass("com/autolua/engine/base/LuaTypeError"));
    env->ThrowNew(jobj.get(),buffer);
}

extern "C"  JNIEXPORT jlong JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_toInteger(JNIEnv *env,
                                                                          jobject jobj, jlong native_lua,
                                                           jint index) {
    lua_State *L =toLuaState(native_lua);
    if (lua_isnumber(L,index))
    {
        return (jlong)lua_tointeger(L,index);
    } else{
        throwTypeError(env, L, index,LUA_TNUMBER);
    }
    return 0;
}



extern "C" JNIEXPORT jdouble JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_toNumber(JNIEnv *env, jobject jobj, jlong native_lua,
                                                          jint index) {
    lua_State *L =toLuaState(native_lua);
    if (lua_isnumber(L,index))
    {
        return (jdouble)lua_tonumber(L,index);
    }else{
        throwTypeError(env,L,index,LUA_TNUMBER);
    }
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_toBoolean(JNIEnv *env, jobject jobj, jlong native_lua,
                                                           jint index) {
    lua_State *L =toLuaState(native_lua);
    return lua_toboolean(L,index);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_toBytes(JNIEnv *env, jobject jobj, jlong native_lua,
                                                         jint index) {
    lua_State *L =toLuaState(native_lua);
    if(lua_isstring(L,index))
    {
        size_t len = 0;
        const char* str = lua_tolstring(L,index,&len);
        jbyteArray result = env->NewByteArray(len);
        if (env->ExceptionCheck())
            return nullptr;
        env->SetByteArrayRegion(result,0,len,(jbyte*)str);
        return result;
    } else{
        throwTypeError(env,L,index,LUA_TSTRING);
    }
    return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_toString(JNIEnv *env, jobject jobj, jlong native_lua,
                                                          jint index) {
    lua_State *L =toLuaState(native_lua);
    if(lua_isstring(L,index))
    {
        size_t len = 0;
        const char* str = lua_tolstring(L,index,&len);
        jstring result = env->NewStringUTF(str);
        return result;
    } else{
        throwTypeError(env,L,index,LUA_TSTRING);
    }
    return nullptr;
}


extern "C" JNIEXPORT void JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_pushNil(JNIEnv *env, jobject jobj, jlong native_lua) {
    lua_pushnil(toLuaState(native_lua));
}

extern "C" JNIEXPORT void JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_push__JJ(JNIEnv *env, jobject jobj, jlong native_lua,
                                                          jlong v) {
    lua_pushinteger(toLuaState(native_lua),v);
}

extern "C" JNIEXPORT void JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_push__JD(JNIEnv *env, jobject jobj, jlong native_lua,
                                                          jdouble v) {
    lua_pushnumber(toLuaState(native_lua),v);
}


extern "C"
JNIEXPORT void JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_push__J_3B(JNIEnv *env, jobject jobj,
                                                            jlong native_lua, jbyteArray v) {
    if (v){
        LocalJavaBytes localJavaBytes(env,v);
        lua_pushlstring(toLuaState(native_lua),localJavaBytes.str(),localJavaBytes.size());
    } else{
        lua_pushnil(toLuaState(native_lua));
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_push__JZ(JNIEnv *env, jobject jobj,
                                                          jlong native_lua, jboolean v) {
    lua_pushboolean(toLuaState(native_lua),v);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_pushValue(JNIEnv *env, jobject jobj,
                                                           jlong native_lua, jint index) {
    lua_pushvalue(toLuaState(native_lua),index);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_type(JNIEnv *env, jobject jobj, jlong native_lua, jint index) {
    return lua_type(toLuaState(native_lua),index);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_isInteger(JNIEnv *env, jobject jobj, jlong native_value,
                                                           jint index) {
    return lua_isinteger(toLuaState(native_value),index);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_getTop(JNIEnv *env, jobject jobj, jlong native_lua) {
    return lua_gettop(toLuaState(native_lua));
}

extern "C" JNIEXPORT void JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_setTop(JNIEnv *env, jobject jobj, jlong native_lua,
                                                        jint index) {
    lua_settop(toLuaState(native_lua),index);
}



static const char* getCodeMode(int t)
{
    const char* mode;
    if (t == 0)
        mode = "bt";
    else if(t == 1)
        mode = "t";
    else
        mode = "b";
    return mode;
}

static bool checkLuaLoadOrCallError(lua_State*L,JNIEnv*env,int code)
{
    if (code == LUA_OK)
        return false;
    jclass clazz  = env->FindClass("com/autolua/engine/base/LuaError");
    const char* message;
    if (lua_isstring(L,-1))
        message = lua_tostring(L,-1);
    else
        message = "unknown error";
    env->ThrowNew(clazz,message);
    return true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_loadBuffer(JNIEnv *env, jobject jobj, jlong native_lua,
                                                            jbyteArray code, jstring chunk_name,
                                                            jint code_type) {
    LocalJavaBytes codeWrapper(env,code);
    LocalJavaString chunkName(env,chunk_name);
    lua_State *L = toLuaState(native_lua);
    int result= luaL_loadbufferx(L,
                                 codeWrapper.str(), codeWrapper.size(), chunkName.str(), getCodeMode(code_type));
    checkLuaLoadOrCallError(L,env,result);
}


extern "C" JNIEXPORT void JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_loadFile(JNIEnv *env, jobject jobj, jlong native_lua,
                                                          jstring file_name, jint code_type) {
    LocalJavaString fileName(env,file_name);
    lua_State *L = toLuaState(native_lua);
    int result = luaL_loadfilex(L, fileName.str(), getCodeMode(code_type));
    checkLuaLoadOrCallError(L,env,result);
}


extern "C" JNIEXPORT void JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_pop(JNIEnv *env, jobject jobj, jlong native_lua, jint n) {
    lua_pop(toLuaState(native_lua), n);
}


extern "C" JNIEXPORT jlong JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_toPointer(JNIEnv *env, jobject jobj, jlong native_lua,
                                                           jint index) {
    return (jlong)lua_topointer(toLuaState(native_lua),index);
}

#define NewSetMethod(name) static int SetMethod##name(lua_State*L) \
{\
    int tableIndex = lua_tointeger(L,lua_upvalueindex(1));\
    lua_##name(L,tableIndex);\
    return 0;\
}

NewSetMethod(settable)
NewSetMethod(rawset)

static int luaProtectCall(lua_State*L,jint tableIndex,lua_CFunction method,int resultSum)
{

    int oldTop = lua_gettop(L);
    lua_pushinteger(L,tableIndex);
    lua_pushcclosure(L,method,1);
    for (int i = 1; i <=oldTop; ++i) {
        lua_pushvalue(L,i);
    }
    return lua_pcall(L,oldTop,resultSum,0);
}

static void setTable(JNIEnv*env,jlong native_lua,jint tableIndex,lua_CFunction method)
{
    lua_State *L = toLuaState(native_lua);
    int result = luaProtectCall(L,tableIndex,method,0);
    checkLuaLoadOrCallError(L,env,result);
    lua_pop(L,2);
}

extern "C" JNIEXPORT void JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_setTable(JNIEnv *env, jobject jobj, jlong native_lua,
                                                          jint table_index) {
    setTable(env,native_lua,table_index,SetMethodsettable);
}

#define NewGetMethod(name) static int GetMethod##name(lua_State*L)\
{\
    int tableIndex = lua_tointeger(L,lua_upvalueindex(1));\
    int result = lua_##name(L,tableIndex);\
    lua_pushinteger(L,result);\
    return 2;\
}

NewGetMethod(gettable)
NewGetMethod(rawget)

static jint getTable(JNIEnv*env,jlong native_lua,jint table_index,lua_CFunction method)
{
    lua_State *L = toLuaState(native_lua);
    jint result = luaProtectCall(L,table_index,method,2);
    if(!checkLuaLoadOrCallError(L,env,result))
    {
        result = lua_tointeger(L,-1);
        lua_copy(L,-2,-3);
        lua_pop(L,2);
    } else{
        lua_pop(L,1);
    }
    return result;
}

extern "C" JNIEXPORT jint
JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_getTable(JNIEnv *env, jobject jobj,
                                                          jlong native_lua, jint table_index) {
    return getTable(env,native_lua,table_index,GetMethodgettable);
}


extern "C"
JNIEXPORT void JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_rawSet(JNIEnv *env, jobject jobj,
                                                        jlong native_lua, jint table_index) {
    setTable(env,native_lua,table_index,SetMethodrawset);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_rawGet(JNIEnv *env, jobject jobj,
                                                        jlong native_lua, jint table_index) {
    return getTable(env,native_lua,table_index,GetMethodrawget);
}

static int lua_setGlobal(lua_State*L)
{
    auto context = toLuaContext(L);
    auto jKey = (jstring)lua_touserdata(L,1);
    LocalJavaString key(GetJNIEnv(),jKey);
    lua_setglobal(L,key.str());
    return 0;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_autolua_engine_base_LuaContextImplement_setGlobal(JNIEnv *env, jclass clazz,
                                                           jlong native_lua, jstring key) {
    lua_State *L = toLuaState(native_lua);
    lua_pushcfunction(L,lua_setGlobal);
    lua_pushlightuserdata(L,key);
    lua_pushvalue(L,-3);
    int result = lua_pcall(L,2,0,0);
    checkLuaLoadOrCallError(L,env,result);
    lua_pop(L,1);
}

static int lua_getGlobal(lua_State*L)
{
    auto context = toLuaContext(L);
    auto jKey = (jstring)lua_touserdata(L,1);
    LocalJavaString key(GetJNIEnv(),jKey);
    int result = lua_getglobal(L,key.str());
    lua_pushinteger(L,result);
    return 2;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_getGlobal(JNIEnv *env, jobject jobj,
                                                           jlong native_lua, jstring key) {
    lua_State *L = toLuaState(native_lua);
    lua_pushcfunction(L,lua_getGlobal);
    lua_pushlightuserdata(L,key);
    jint result = lua_pcall(L,1,2,0);
    if (!checkLuaLoadOrCallError(L,env,result))
    {
        result = lua_tointeger(L,-1);
        lua_pop(L,1);
    }
    return result;
}


extern "C"
JNIEXPORT void JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_createTable(JNIEnv *env, jobject jobj,
                                                             jlong native_lua, jint array_size,
                                                             jint dictionary_size) {
    lua_createtable(toLuaState(native_lua),array_size,dictionary_size);
}




extern "C"
JNIEXPORT jlong JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_toLuaObjectAdapter(JNIEnv *env, jobject jobj,
                                                                    jlong native_lua,
                                                                    jint index) {
    auto L = toLuaState(native_lua);
    auto r = luaL_testudata(L,index, CLASS_METATABLE_NAME(LuaObjectAdapter));
    if(r){
        return toLuaObjectAdapterId(r);
    }
    return 0;
}


static int pushJavaThrowable(JNIEnv* env,lua_State*L,jthrowable throwable)
{
    LocalReference<jclass> clazz(env,env->FindClass("java/lang/Throwable"));
    jmethodID method = env->GetMethodID(clazz.get(),"getMessage", "()Ljava/lang/String;");
    auto message = (jstring)env->CallObjectMethod(throwable,method);
    if (!env->ExceptionCheck())
    {
        LocalJavaString cMessage(env, message);
        lua_pushlstring(L, cMessage.str(), cMessage.size());
        return 1;
    }
    env->ExceptionDescribe();
    env->ExceptionClear();
    return 0;
}

static int catchAndPushJavaThrowable(JNIEnv *env, lua_State*L)
{
    LocalReference<jthrowable> throwable(env,env->ExceptionOccurred());
    if (throwable.get() != nullptr)
    {
        env->ExceptionDescribe();
        env->ExceptionClear();
        if (!pushJavaThrowable(env,L,throwable.get()))
            lua_pushstring(L,PUSH_THROWABLE_ERROR);
        return 1;
    }
    return 0;
}

// ---- Java 桥的栈绑定 ----
// LuaContextImplement.nativeLua 固定指向创建时的主 State；__index/__call 等元方法
// 可能在 thread 协程上下文执行（thread.start 协程栈独立），Java 侧 adapter 读
// "栈位 1/2" 时若仍指向主栈则全部错位——主协程调用恰好同栈掩盖了该缺陷（读到的
// 是主栈残留，碰巧是 string 才工作），协程上下文必炸 LuaTypeError（index no value）。
// 修法：回调入口把 nativeLua 切到当前执行栈 L，出口恢复。安全性依赖同 State
// 全局串行（worker execute 持锁 + lSleep 释放 / nativeResume 持锁，见 thread_lib）。
// 注意：lua_error 走 longjmp 跳过 C++ RAII——恢复必须在 error 前显式完成。
static jlong bindNativeLua(JNIEnv *env, LuaContext *context, lua_State *L) {
    jlong prev = env->GetLongField(context->lua_context, context->ptr);
    env->SetLongField(context->lua_context, context->ptr, (jlong)L);
    return prev;
}
static void unbindNativeLua(JNIEnv *env, LuaContext *context, jlong prev) {
    env->SetLongField(context->lua_context, context->ptr, prev);
}


static int callMethod(lua_State*L){
    auto context = toLuaContext(L);
    auto env = GetJNIEnv();
    // self 由 indexMethod 闭包捕获（upvalue 1）：obj.m(a) 与 obj:m(a) 两种形式均可调用，
    // 参数起点（是否跳过冒号 self）由 Java 侧按栈首是否为本对象自适应
    auto adapter = (LuaObjectAdapter*)lua_touserdata(L, lua_upvalueindex(1));
    if(adapter == nullptr)
        luaL_error(L, "invalid object: method called without receiver");
    const char* methodName = lua_tostring(L, lua_upvalueindex(2));
    jstring  name = env->NewStringUTF(methodName);
    jlong prevLua = bindNativeLua(env, context, L);
    auto result = env->CallIntMethod(context->lua_context, context->callMethod, adapter->id, name);
    unbindNativeLua(env, context, prevLua);
    if(catchAndPushJavaThrowable(env,L)){
        env->DeleteLocalRef(name);
        lua_error(L);
    }
    env->DeleteLocalRef(name);
    return result;
}

static int invokeMethod(lua_State*L){
    auto context = toLuaContext(L);
    auto env = GetJNIEnv();
    auto adapter = luaL_checkObject(LuaObjectAdapter,L,1);
    jlong prevLua = bindNativeLua(env, context, L);
    auto result = env->CallIntMethod(context->lua_context, context->invokeMethod, adapter->id);
    unbindNativeLua(env, context, prevLua);
    if(catchAndPushJavaThrowable(env,L)){
        lua_error(L);
    }
    return result;
}


static int indexMethod(lua_State*L){
    auto context = toLuaContext(L);
    auto env = GetJNIEnv();
    auto adapter = (LuaObjectAdapter*)lua_touserdata(L,1);
    const char* methodName = lua_tostring(L,2);
    jstring  name = env->NewStringUTF(methodName);
    jlong prevLua = bindNativeLua(env, context, L);
    auto res = env->CallIntMethod(context->lua_context, context->indexMethod, adapter->id);
    unbindNativeLua(env, context, prevLua);
    if(catchAndPushJavaThrowable(env,L)){
        env->DeleteLocalRef(name);
        lua_error(L);
    }
    env->DeleteLocalRef(name);
    if(res == 0){
        return 0;
    }
    if(res == 1){
        return 1;
    }
    // res == 2：方法存在。闭包捕获 self + 方法名，点调用 obj.m(a) / 冒号调用 obj:m(a) 均可
    lua_pushvalue(L,1);
    lua_pushvalue(L,2);
    lua_pushcclosure(L,callMethod,2);
    return 1;
}

static int newIndex(lua_State*L){
    auto context = toLuaContext(L);
    auto env = GetJNIEnv();
    auto adapter = luaL_checkObject(LuaObjectAdapter,L,1);
    jlong prevLua = bindNativeLua(env, context, L);
    auto res = env->CallIntMethod(context->lua_context, context->newIndexMethod, adapter->id);
    unbindNativeLua(env, context, prevLua);
    if(catchAndPushJavaThrowable(env,L)){
        lua_error(L);
    }
    return 0;

}

static int release(lua_State*L){
    auto context = toLuaContext(L);
    auto env = GetJNIEnv();
    auto adapter = luaL_checkObject(LuaObjectAdapter,L,1);
    jlong prevLua = bindNativeLua(env, context, L);
    env->CallVoidMethod(context->lua_context, context->releaseMethod, adapter->id);
    unbindNativeLua(env, context, prevLua);
    if(catchAndPushJavaThrowable(env,L)){
        lua_error(L);
    }
    return 0;
}



extern "C"{
JNIEXPORT jlong JNICALL
Java_com_autolua_engine_base_LuaContextImplement_createLuaContext(JNIEnv *env, jobject thiz) {
    auto context = new LuaContext();
    // 强引用防 GC：companion 被回收后所有 Lua->Java 桥调用直接 SIGSEGV
    context->lua_context = env->NewGlobalRef(thiz);
    auto clazz = env->FindClass("com/autolua/engine/base/LuaContextImplement");
    context->lua_context_class = (jclass)env->NewWeakGlobalRef(clazz);
    context->indexMethod = env->GetMethodID(clazz, "indexMethod", "(J)I");
    context->callMethod = env->GetMethodID(clazz,"callMethod","(JLjava/lang/String;)I");
    context->newIndexMethod = env->GetMethodID(clazz,"newIndexMethod","(J)I");
    context->invokeMethod = env->GetMethodID(clazz,"invokeMethod","(J)I");
    context->releaseMethod = env->GetMethodID(clazz,"release","(J)V");
    context->ptr = env->GetFieldID(clazz,"nativeLua","J");
    auto L = luaL_newstate();
    luaL_openlibs(L);
    // thread 库（同 state 协程多线程原语）+ per-state 互斥锁
    autolua::injectThreadLib(L);
    // json 库（cJSON 绑定：encode/decode）
    autolua::injectJsonLib(L);
    // cv 库（OpenCV Mat 体系子集：零依赖图像加工）
    autolua::injectCvLib(L);
    context->mutex = new std::recursive_mutex();
    void *p = lua_getextraspace(L);
    *(static_cast<LuaContext**>(p)) = context;

    if (luaL_newClassMetatable(LuaObjectAdapter,L)){
        luaL_Reg methods[] = {
                {"__index",indexMethod},
                {"__newindex",newIndex},
                {"__gc",release},
                {"__call",invokeMethod},
                {nullptr, nullptr}
        };
        luaL_setfuncs(L,methods,0);
    }

    return (jlong)L;
}

}

extern "C"
JNIEXPORT void JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_destroy(JNIEnv *env, jobject thiz,
                                                                        jlong native_lua) {
    auto L = toLuaState(native_lua);
    auto context = toLuaContext(L);
    autolua::cleanupThreadTasks(L);
    lua_close(L);
    delete static_cast<std::recursive_mutex *>(context->mutex);
    env->DeleteGlobalRef(context->lua_context);
    env->DeleteWeakGlobalRef(context->lua_context_class);
    delete context;
}


extern "C"
JNIEXPORT void JNICALL
Java_com_autolua_engine_base_LuaContextImplement_pushLuaObjectAdapter(JNIEnv *env, jclass clazz,
                                                                      jlong native_lua,
                                                                      jlong adapter_id) {
    auto L = toLuaState(native_lua);
    auto adapter = luaL_pushNewObject(LuaObjectAdapter,L);
    adapter->id = adapter_id;
}
extern "C"
JNIEXPORT jint JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_pcall(JNIEnv *env, jobject thiz,
                                                                      jlong native_lua, jint n_args,
                                                                      jint n_results,
                                                                      jint err_func){
    auto L = toLuaState(native_lua);
    // 执行入口互斥：主 worker / IDE 沙箱执行与 thread 协程 resume 并发保护
    autolua::lockLuaState(L);
    int r = lua_pcall(L,n_args,n_results,err_func);
    autolua::unlockLuaState(L);
    return r;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_next(JNIEnv *env, jobject thiz,
                                                                     jlong native_lua, jint index) {
    auto L = toLuaState(native_lua);
    return lua_next(L,index);
}
extern "C"
JNIEXPORT jlong JNICALL
Java_com_autolua_engine_base_LuaContextImplement_00024Companion_len(JNIEnv *env, jobject thiz,
                                                                    jlong native_lua, jint index) {
    auto L = toLuaState(native_lua);
    return (jlong)luaL_len(L,index);
}