//
// thread 库实现：Lua 多线程原语（同 state 协程 + per-state 递归锁）
//
// 设计要点：
// - thread.start(func, ...) 在同一 lua_State 内创建 coroutine（闭包/共享 _G 语义完整），
//   参数经 lua_xmove 转移到协程栈，registry ref 保活，交给 Java 线程池驱动 resume 循环
// - 挂起协议：协程内 thread.sleep/thread.acquire 以 lua_yield 挂起，yield 值为
//   32 位编码 (op<<24)|value；nativeResume 返回 (tag<<32)|yield编码
//   （tag: 0=yield 1=done 2=error）
// - 互斥：每个 LuaContext 持 std::recursive_mutex（LuaContext.mutex），
//   主 worker 执行入口与协程 resume 都持锁；挂起等待期间先释放锁避免死锁
// - thread 共享数据：registry 常驻表（同 state 天然共享），atomicInc 在锁内读改写
//
// Created by TRAE on 2026/9/12.
//

#include "thread_lib.h"
#include "lua_context.h"
#include "util.h"

#include <mutex>
#include <map>
#include <string>

extern "C" {
#include "lua.h"
#include "lauxlib.h"
#include "lualib.h"
#include "lstate.h"
}

namespace autolua {

    // ---------------- 常量 ----------------

    static const jint TAG_YIELD = 0;
    static const jint TAG_DONE = 1;
    static const jint TAG_ERROR = 2;

    static const jint OP_SLEEP = 1;
    static const jint OP_LOCK = 2;

    // Java 侧任务状态（ThreadLib.Task.status）
    static const jint ST_RUNNING = 0;
    static const jint ST_DONE = 1;
    static const jint ST_ERROR = 2;
    static const jint ST_CANCELLED = 3;

    static const jint YIELD_OP_MASK = 0xFF000000;
    static const jint YIELD_VALUE_MASK = 0x00FFFFFF;

    static inline jint encodeYield(jint op, jint value) {
        return (op << 24) | (value & YIELD_VALUE_MASK);
    }

    // 前置声明（threadJoin 内使用，定义在文件末尾）
    void eraseTask(jint taskId);

    // Lua 5.4 无 lua_ismainthread：以 lua_pushthread 返回值判定（非主线程会压栈需弹出）
    static bool isMainThreadLua(lua_State *L) {
        int r = lua_pushthread(L);
        lua_pop(L, 1);
        return r == 1;
    }

    // ---------------- per-state 锁 ----------------

    static std::recursive_mutex &stateMutex(lua_State *L) {
        auto ctx = toLuaContext(L);
        return *static_cast<std::recursive_mutex *>(ctx->mutex);
    }

    void lockLuaState(lua_State *L) { stateMutex(L).lock(); }

    void unlockLuaState(lua_State *L) { stateMutex(L).unlock(); }

    // ---------------- 任务映射（全局，跨 state）----------------

    static std::mutex gMapMutex;
    // co -> taskId / taskId -> co / taskId -> registry ref / taskId -> 是否已首启 / taskId -> 返回值个数
    static std::map<lua_State *, jint> gCoTask;
    static std::map<jint, lua_State *> gTaskCo;
    static std::map<jint, jint> gTaskRef;
    static std::map<jint, bool> gTaskResumed;
    static std::map<jint, int> gTaskNres;
    // state -> registry 中的 thread_shared 表 ref / taskId -> lock 持有计数（重入支持）
    static std::map<lua_State *, int> gSharedRef;
    static std::map<jint, jint> gLockHold; // taskId -> 已 acquire 的 lockId（0 = 无），支持单锁重入判定

    // ---------------- Java ThreadLib 桥 ----------------

    static jclass gThreadLibClass = nullptr;
    static jmethodID gMidBegin = nullptr;
    static jmethodID gMidSubmit = nullptr;
    static jmethodID gMidJoinBlocking = nullptr;
    static jmethodID gMidStop = nullptr;
    static jmethodID gMidStopAll = nullptr;
    static jmethodID gMidCreateLock = nullptr;
    static jmethodID gMidReleaseLock = nullptr;
    static jmethodID gMidRemove = nullptr;

    static bool ensureJavaBridge(JNIEnv *env) {
        if (gThreadLibClass) return true;
        jclass local = env->FindClass("com/autolua/engine/extension/thread/ThreadLib");
        if (!local) {
            env->ExceptionClear();
            return false;
        }
        gThreadLibClass = (jclass) env->NewGlobalRef(local);
        env->DeleteLocalRef(local);
        gMidBegin = env->GetStaticMethodID(gThreadLibClass, "begin", "()I");
        gMidSubmit = env->GetStaticMethodID(gThreadLibClass, "submit", "(JII)V");
        gMidJoinBlocking = env->GetStaticMethodID(gThreadLibClass, "joinBlocking", "(I)I");
        gMidStop = env->GetStaticMethodID(gThreadLibClass, "stop", "(I)V");
        gMidStopAll = env->GetStaticMethodID(gThreadLibClass, "stopAll", "(J)V");
        gMidCreateLock = env->GetStaticMethodID(gThreadLibClass, "createLock", "()I");
        gMidReleaseLock = env->GetStaticMethodID(gThreadLibClass, "releaseLock", "(I)V");
        gMidRemove = env->GetStaticMethodID(gThreadLibClass, "remove", "(I)V");
        return gMidBegin && gMidSubmit && gMidJoinBlocking && gMidStop && gMidStopAll && gMidCreateLock && gMidReleaseLock && gMidRemove;
    }

    // ---------------- registry shared 表 ----------------

    // 协程 co 与主 L 是不同 lua_State 指针但共享 registry；
    // shared 表 ref 必须统一挂在主线程 state 上，否则协程写入的共享表主线程看不到
    static lua_State *mainState(lua_State *L) {
        return G(L)->mainthread;
    }

    // registry 中 thread_shared 表：key 为任意 Lua 值（标量/表/函数皆可，同 state 共享）
    static int ensureSharedTable(lua_State *L) {
        lua_State *m = mainState(L);
        std::lock_guard<std::mutex> lk(gMapMutex);
        auto it = gSharedRef.find(m);
        if (it != gSharedRef.end()) {
            lua_rawgeti(L, LUA_REGISTRYINDEX, it->second);
            return it->second;
        }
        lua_newtable(L);
        int ref = luaL_ref(L, LUA_REGISTRYINDEX);
        gSharedRef[m] = ref;
        lua_rawgeti(L, LUA_REGISTRYINDEX, ref);
        return ref;
    }

    // ---------------- Java -> C：resume 循环 ----------------

    // 返回 (tag<<32) | payload；yield 时 payload 为 32 位编码，done/error 时 payload=0
    // 错误对象/完成结果保留在协程栈上，由 thread.join 提取
    extern "C" JNIEXPORT jlong JNICALL
    Java_com_autolua_engine_extension_thread_ThreadLib_nativeResume
        (JNIEnv *env, jclass, jlong luaPtr, jint ref, jint taskId) {
        auto *L = (lua_State *) luaPtr;

        lua_State *co = nullptr;
        bool first = false;
        {
            std::lock_guard<std::mutex> lk(gMapMutex);
            auto it = gTaskCo.find(taskId);
            if (it != gTaskCo.end()) co = it->second;
            auto itResumed = gTaskResumed.find(taskId);
            first = (itResumed == gTaskResumed.end()) || !itResumed->second;
            gTaskResumed[taskId] = true;
        }
        if (!co) return ((jlong) TAG_ERROR) << 32;

        lockLuaState(L);

        int narg = 0;
        if (first) {
            // 协程栈：[func, args...]，resume 参数个数 = 栈大小 - 1
            narg = lua_gettop(co) - 1;
        } else {
            // yield 残留值出栈（挂起点约定每次 yield 恰好 1 个整数编码）
            if (lua_gettop(co) > 0) lua_pop(co, 1);
        }

        int nres = 0;
        int status = lua_resume(co, L, narg, &nres);
        jlong ret;
        if (status == LUA_OK) {
            gMapMutex.lock();
            gTaskNres[taskId] = nres;
            gMapMutex.unlock();
            ret = ((jlong) TAG_DONE) << 32;
        } else if (status == LUA_YIELD) {
            lua_Integer y = lua_tointeger(co, -1);
            ret = (jlong) TAG_YIELD << 32 | (y & 0xFFFFFFFFLL);
        } else {
            // 错误对象留在 co 栈顶，join 时抛给等待方
            ret = ((jlong) TAG_ERROR) << 32;
        }
        unlockLuaState(L);
        return ret;
    }

    // 取消任务时释放 registry ref（协程不再需要保活）
    extern "C" JNIEXPORT void JNICALL
    Java_com_autolua_engine_extension_thread_ThreadLib_nativeUnref
        (JNIEnv *env, jclass, jlong luaPtr, jint ref) {
        auto *L = (lua_State *) luaPtr;
        lockLuaState(L);
        luaL_unref(L, LUA_REGISTRYINDEX, ref);
        unlockLuaState(L);
    }

    // ---------------- Lua C 函数 ----------------

    // thread.start(func, ...) -> taskId
    // 两阶段：先 Java begin() 登记 taskId，再注册 C 侧映射，最后 submit() 驱动——
    // 若先 submit，池线程可能先于 gTaskCo 注册执行 nativeResume，查不到 co 直接 ERROR
    static int threadStart(lua_State *L) {
        luaL_checktype(L, 1, LUA_TFUNCTION);
        lua_State *co = lua_newthread(L);
        int ref = luaL_ref(L, LUA_REGISTRYINDEX); // 弹出 thread，registry 保活
        int nargs = lua_gettop(L);
        lua_xmove(L, co, nargs);                  // func + args -> 协程栈

        JNIEnv *env = GetJNIEnv();
        if (!env || !ensureJavaBridge(env)) {
            luaL_unref(L, LUA_REGISTRYINDEX, ref);
            luaL_error(L, "thread.start: java bridge unavailable");
        }
        jint taskId = env->CallStaticIntMethod(gThreadLibClass, gMidBegin);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            luaL_unref(L, LUA_REGISTRYINDEX, ref);
            luaL_error(L, "thread.start: begin failed");
        }

        {
            std::lock_guard<std::mutex> lk(gMapMutex);
            gCoTask[co] = taskId;
            gTaskCo[taskId] = co;
            gTaskRef[taskId] = ref;
            gTaskResumed[taskId] = false;
        }
        env->CallStaticVoidMethod(gThreadLibClass, gMidSubmit, (jlong) L, (jint) ref, taskId);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            luaL_unref(L, LUA_REGISTRYINDEX, ref);
            eraseTask(taskId);
            luaL_error(L, "thread.start: submit failed");
        }
        lua_pushinteger(L, taskId);
        return 1;
    }

    // thread.sleep(ms)：协程挂起点（主线程不可用，主线程用全局 sleep）
    static int threadSleep(lua_State *L) {
        lua_Integer ms = luaL_checkinteger(L, 1);
        if (ms < 0) ms = 0;
        if (ms > YIELD_VALUE_MASK) ms = YIELD_VALUE_MASK;
        if (isMainThreadLua(L)) {
            luaL_error(L, "thread.sleep is only available inside thread.start (use sleep() on the main thread)");
        }
        lua_pushinteger(L, encodeYield(OP_SLEEP, (jint) ms));
        return lua_yield(L, 1);
    }

    // thread.lock() -> lockId（Java ReentrantLock 句柄）
    static int threadLock(lua_State *L) {
        JNIEnv *env = GetJNIEnv();
        if (!env || !ensureJavaBridge(env)) {
            luaL_error(L, "thread.lock: java bridge unavailable");
        }
        jint lockId = env->CallStaticIntMethod(gThreadLibClass, gMidCreateLock);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            luaL_error(L, "thread.lock: create failed");
        }
        lua_pushinteger(L, lockId);
        return 1;
    }

    // thread.acquire(lockId)：可挂起等待（协程专用，等待期间释放状态锁）
    static int threadAcquire(lua_State *L) {
        lua_Integer lockId = luaL_checkinteger(L, 1);
        if (isMainThreadLua(L)) {
            luaL_error(L, "thread.acquire is not available on the main thread (would deadlock)");
        }
        // 重入保护：同协程重复 acquire 同一把锁直接死等，提前报错
        lua_pushinteger(L, encodeYield(OP_LOCK, (jint) lockId));
        return lua_yield(L, 1);
    }

    // thread.release(lockId)
    static int threadRelease(lua_State *L) {
        lua_Integer lockId = luaL_checkinteger(L, 1);
        JNIEnv *env = GetJNIEnv();
        if (env && ensureJavaBridge(env)) {
            env->CallStaticVoidMethod(gThreadLibClass, gMidReleaseLock, (jint) lockId);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        }
        return 0;
    }

    // thread.stop(taskId)
    static int threadStop(lua_State *L) {
        lua_Integer taskId = luaL_checkinteger(L, 1);
        JNIEnv *env = GetJNIEnv();
        if (env && ensureJavaBridge(env)) {
            env->CallStaticVoidMethod(gThreadLibClass, gMidStop, (jint) taskId);
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        }
        return 0;
    }

    // thread.join(taskId) -> ok(true) [, 结果...]；error 时向调用方抛错；cancelled/不存在返回 false
    static int threadJoin(lua_State *L) {
        lua_Integer taskId = luaL_checkinteger(L, 1);
        JNIEnv *env = GetJNIEnv();
        if (!env || !ensureJavaBridge(env)) {
            luaL_error(L, "thread.join: java bridge unavailable");
        }
        // 释放状态锁等待（协程需要锁才能继续执行）
        unlockLuaState(L);
        jint status = env->CallStaticIntMethod(gThreadLibClass, gMidJoinBlocking, (jint) taskId);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            lockLuaState(L);
            luaL_error(L, "thread.join: interrupted");
        }
        lockLuaState(L);

        gMapMutex.lock();
        lua_State *co = gTaskCo[taskId];
        int ref = gTaskRef[taskId];
        int nres = gTaskNres[taskId];
        gMapMutex.unlock();

        if (status == ST_DONE) {
            lua_pushboolean(L, 1);
            if (co && nres > 0) {
                if (lua_gettop(co) >= nres) {
                    lua_xmove(co, L, nres);
                } else {
                    nres = 0;
                }
            } else {
                nres = 0;
            }
            if (ref) luaL_unref(L, LUA_REGISTRYINDEX, ref);
            eraseTask(taskId);
            return nres + 1;
        }
        if (status == ST_ERROR) {
            if (co && lua_gettop(co) > 0) {
                lua_xmove(co, L, 1);
                if (ref) luaL_unref(L, LUA_REGISTRYINDEX, ref);
                eraseTask(taskId);
                return lua_error(L);
            }
            if (ref) luaL_unref(L, LUA_REGISTRYINDEX, ref);
            eraseTask(taskId);
            luaL_error(L, "thread error");
            return 0;
        }
        // cancelled：Java finishCancelled 已 nativeUnref（勿重复 unref 损坏 freelist）；
        // 未知 taskId：gTaskRef 无记录 ref=0，同样跳过
        eraseTask(taskId);
        lua_pushboolean(L, 0);
        return 1;
    }

    // thread.currentId() -> 当前协程 taskId（主线程返回 0）
    static int threadCurrentId(lua_State *L) {
        std::lock_guard<std::mutex> lk(gMapMutex);
        auto it = gCoTask.find(L);
        if (it != gCoTask.end()) {
            lua_pushinteger(L, it->second);
        } else {
            lua_pushinteger(L, 0);
        }
        return 1;
    }

    // thread.isMain() -> 当前是否主线程
    static int threadIsMain(lua_State *L) {
        lua_pushboolean(L, isMainThreadLua(L) ? 1 : 0);
        return 1;
    }

    // ---------------- thread shared（registry 共享表）----------------

    // thread.setShared(k, v)（v 任意 Lua 值）
    static int threadSetShared(lua_State *L) {
        ensureSharedTable(L);      // [-1] shared
        lua_pushvalue(L, 1);       // key
        lua_pushvalue(L, 2);       // value
        lua_rawset(L, -3);
        lua_pop(L, 1);
        return 0;
    }

    // thread.getShared(k) -> value
    static int threadGetShared(lua_State *L) {
        ensureSharedTable(L);      // [-1] shared
        lua_pushvalue(L, 1);       // key
        lua_rawget(L, -2);         // value
        lua_remove(L, -2);         // 去 shared
        return 1;
    }

    // thread.hasShared(k) -> boolean
    static int threadHasShared(lua_State *L) {
        ensureSharedTable(L);      // [-1] shared
        lua_pushvalue(L, 1);       // key
        lua_rawget(L, -2);         // value
        int t = lua_type(L, -1);
        lua_pop(L, 2);
        lua_pushboolean(L, t != LUA_TNIL ? 1 : 0);
        return 1;
    }

    // thread.removeShared(k)
    static int threadRemoveShared(lua_State *L) {
        ensureSharedTable(L);      // [-1] shared
        lua_pushvalue(L, 1);       // key
        lua_pushnil(L);
        lua_rawset(L, -3);
        lua_pop(L, 1);
        return 0;
    }

    // thread.atomicInc(k[, delta]) -> 新值（锁内读改写；key 不存在按 0 起步，delta 默认 1）
    static int threadAtomicInc(lua_State *L) {
        lua_Integer delta = luaL_optinteger(L, 2, 1);
        lockLuaState(L);
        ensureSharedTable(L);      // [-1] shared
        lua_pushvalue(L, 1);       // key
        lua_rawget(L, -2);         // current value
        lua_Integer updated = delta;
        if (lua_type(L, -1) == LUA_TNUMBER) {
            updated = lua_tointeger(L, -1) + delta;
        }
        lua_pop(L, 1);               // pop current
        lua_pushvalue(L, 1);         // key
        lua_pushinteger(L, updated); // value
        lua_rawset(L, -3);           // shared[key] = updated
        lua_pop(L, 1);               // pop shared
        unlockLuaState(L);
        lua_pushinteger(L, updated);
        return 1;
    }

    // ---------------- 注入与生命周期 ----------------

    static const luaL_Reg threadLibFuncs[] = {
            {"start",        threadStart},
            {"sleep",        threadSleep},
            {"lock",         threadLock},
            {"acquire",      threadAcquire},
            {"release",      threadRelease},
            {"stop",         threadStop},
            {"join",         threadJoin},
            {"currentId",    threadCurrentId},
            {"isMain",       threadIsMain},
            {"setShared",    threadSetShared},
            {"getShared",    threadGetShared},
            {"hasShared",    threadHasShared},
            {"removeShared", threadRemoveShared},
            {"atomicInc",    threadAtomicInc},
            {nullptr,        nullptr}
    };

    void injectThreadLib(lua_State *L) {
        luaL_newlib(L, threadLibFuncs);
        lua_setglobal(L, "thread");
    }

    void eraseTask(jint taskId) {
        {
            std::lock_guard<std::mutex> lk(gMapMutex);
            auto itCo = gTaskCo.find(taskId);
            if (itCo != gTaskCo.end()) {
                gCoTask.erase(itCo->second);
                gTaskCo.erase(itCo);
            }
            gTaskRef.erase(taskId);
            gTaskResumed.erase(taskId);
            gTaskNres.erase(taskId);
            gLockHold.erase(taskId);
        }
        // 同步移除 Java 侧终态任务（finish 不再自删，join 期间保持可查询）
        JNIEnv *env = GetJNIEnv();
        if (env && ensureJavaBridge(env)) {
            env->CallStaticVoidMethod(gThreadLibClass, gMidRemove, taskId);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
        }
    }

    void stopAllThreadTasks(lua_State *L) {
        JNIEnv *env = GetJNIEnv();
        if (!env || !ensureJavaBridge(env)) return;
        env->CallStaticVoidMethod(gThreadLibClass, gMidStopAll, (jlong) L);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
    }

    void cleanupThreadTasks(lua_State *L) {
        std::lock_guard<std::mutex> lk(gMapMutex);
        // 清理 shared 表 ref（统一挂在主线程 state 上）
        auto itShared = gSharedRef.find(mainState(L));
        if (itShared != gSharedRef.end()) {
            gSharedRef.erase(itShared);
        }
        // 清理该 state 的任务映射（state 即将销毁，registry 随之回收）
        for (auto it = gCoTask.begin(); it != gCoTask.end();) {
            if (it->first == L) {
                jint taskId = it->second;
                gTaskCo.erase(taskId);
                gTaskRef.erase(taskId);
                gTaskResumed.erase(taskId);
                gTaskNres.erase(taskId);
                gLockHold.erase(taskId);
                it = gCoTask.erase(it);
            } else {
                ++it;
            }
        }
    }
}
