//
// Created by lizhi on 2024/5/8.
//

#ifndef AUTOLUA2_LUA_CONTEXT_H
#define AUTOLUA2_LUA_CONTEXT_H
#include "jni.h"


struct  LuaContext{
    jobject lua_context;
    jclass lua_context_class;
    jmethodID indexMethod;
    jmethodID newIndexMethod;
    jmethodID callMethod;
    jmethodID invokeMethod;
    jmethodID releaseMethod;
    jfieldID ptr;
    void* signalEvent;
    void* interpreter;
    void* display;
    // per-state 互斥锁（std::recursive_mutex*）：主 worker 与 thread 协程线程并发访问同一
    // lua_State 的互斥保护；thread 库（thread_lib.cpp）的挂起点（sleep/lock 等待）负责先释放
    void* mutex;
} ;

struct LuaObjectAdapter {
    jlong id;
};

#define toLuaObjectAdapterId(p) ((LuaObjectAdapter*)p)->id
#define  toLuaContext(L) (*(static_cast<LuaContext**>(lua_getextraspace(L))))

#endif //AUTOLUA2_LUA_CONTEXT_H
