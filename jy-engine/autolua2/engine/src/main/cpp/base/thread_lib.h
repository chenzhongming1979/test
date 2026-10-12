//
// thread 库（Lua 多线程原语，同 state 协程方案）
// Created by TRAE on 2026/9/12.
//

#ifndef AUTOLUA2_THREAD_LIB_H
#define AUTOLUA2_THREAD_LIB_H

struct lua_State;

namespace autolua {

    // 向 Lua 注入全局 thread 库（在 createLuaContext 的 luaL_openlibs 之后调用）
    void injectThreadLib(lua_State *L);

    // 引擎 interrupt 时停止该 state 的全部协程任务（Java 侧置 stopped 标志）
    void stopAllThreadTasks(lua_State *L);

    // state 销毁时清理任务映射
    void cleanupThreadTasks(lua_State *L);

    // 主 worker 执行入口的加锁/解锁（LuaInterpreter run/lSleep 使用）
    void lockLuaState(lua_State *L);
    void unlockLuaState(lua_State *L);
}

#endif //AUTOLUA2_THREAD_LIB_H
