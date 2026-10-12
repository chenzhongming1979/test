//
// ws 双向 WebSocket 模块（libhv 绑定）：Lua 全局 `ws` 表。
// 客户端/服务端事件均入队，Lua 侧 clientPoll/serverPoll 拉取——
// hv 事件循环线程与 Lua worker 线程解耦，回调永不直接触碰 Lua。
//
#ifndef AUTOLUA2_WS_LIB_H
#define AUTOLUA2_WS_LIB_H

struct lua_State;

namespace autolua {

int injectWsLib(lua_State *L);

} // namespace autolua

#endif //AUTOLUA2_WS_LIB_H
