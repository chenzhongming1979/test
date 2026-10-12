//
// ws 双向 WebSocket 模块（见 ws_lib.h）：libhv 绑定，Lua 全局 `ws` 表。
//
// 线程模型：hv 事件循环线程与 Lua worker 线程解耦——所有事件（open/message/close）
// 入内存队列，Lua 侧 clientPoll/serverPoll 拉取（带超时），回调里永不直接触碰 Lua。
//
// 客户端：clientConnect(url) → id；clientPoll(id[, timeoutMs]) → "open" |
//          "message",msg | "close" | nil；clientSend/clientSendBinary/clientClose/clientIsOpen。
// 服务端：serverStart(port) → bool；serverPoll([timeoutMs]) → "open",connId |
//          "message",connId,msg | "close",connId | nil；serverSend/serverSendBinary/
//          serverBroadcast/serverStop/serverIsRunning。
//
// 锁注意：服务端回调（onopen/onmessage/onclose）在持有 gSrvMutex 的语境下还会
// 经 pushEventSrv 再入锁，故 gSrvMutex 必须用 recursive_mutex。
//
#include "ws_lib.h"

#include <lua.hpp>
#include <hv/WebSocketClient.h>
#include <hv/WebSocketServer.h>

#include <atomic>
#include <chrono>
#include <deque>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

namespace autolua {
namespace wslib {

struct WsEvent {
    int connId;
    std::string type;  // "open" | "message" | "close"
    std::string data;
};

struct WsClient {
    std::shared_ptr<hv::WebSocketClient> client;
    std::mutex mutex;
    std::deque<WsEvent> queue;
};

static std::mutex gMutex;
static std::map<int, std::shared_ptr<WsClient>> gClients;
static int gClientSeq = 0;

static hv::WebSocketServer *gServer = nullptr;
static hv::WebSocketService *gService = nullptr;
static std::thread *gServerThread = nullptr;
static std::atomic<int> gServerStatus{0};  // 0 stopped 1 running
// recursive：服务端回调持锁语境下还会经 pushEventSrv 再入锁
static std::recursive_mutex gSrvMutex;
static std::map<int, WebSocketChannelPtr> gServerConns;
static std::deque<WsEvent> gServerEvents;
static int gServerConnSeq = 0;

static void pushEvent(std::deque<WsEvent> &q, std::mutex &m,
                      int connId, const char *type, const std::string &data = "") {
    std::lock_guard<std::mutex> lock(m);
    WsEvent ev;
    ev.connId = connId;
    ev.type = type;
    ev.data = data;
    q.push_back(ev);
    if (q.size() > 4096) q.pop_front();  // 防无消费时无限膨胀
}

// 服务端事件入队（gSrvMutex，可重入）
static void pushEventSrv(int connId, const char *type, const std::string &data = "") {
    std::lock_guard<std::recursive_mutex> lock(gSrvMutex);
    WsEvent ev;
    ev.connId = connId;
    ev.type = type;
    ev.data = data;
    gServerEvents.push_back(ev);
    if (gServerEvents.size() > 4096) gServerEvents.pop_front();
}

// ------------------------------------------------------------------
// 客户端
// ------------------------------------------------------------------

// ws.clientConnect(url[, connectTimeoutMs]) -> connId
static int wsClientConnect(lua_State *L) {
    const char *url = luaL_checkstring(L, 1);
    auto holder = std::make_shared<WsClient>();
    holder->client = std::make_shared<hv::WebSocketClient>();
    auto shared = holder->client;
    if (lua_isnumber(L, 2)) {
        shared->setConnectTimeout((int) lua_tointeger(L, 2));
    }
    shared->setPingInterval(10000);
    // 回调捕获 weak_ptr：clientClose 后 WsClient 可能已析构，防止 UAF
    std::weak_ptr<WsClient> weak = holder;
    shared->onopen = [weak]() {
        if (auto c = weak.lock()) pushEvent(c->queue, c->mutex, 0, "open");
    };
    shared->onmessage = [weak](const std::string &msg) {
        if (auto c = weak.lock()) pushEvent(c->queue, c->mutex, 0, "message", msg);
    };
    shared->onclose = [weak]() {
        if (auto c = weak.lock()) pushEvent(c->queue, c->mutex, 0, "close");
    };
    int ret = shared->open(url);
    if (ret != 0) {
        return luaL_error(L, "ws.clientConnect: open failed (%d): %s", ret, url);
    }
    std::lock_guard<std::mutex> lock(gMutex);
    int id = ++gClientSeq;
    gClients[id] = holder;
    lua_pushinteger(L, id);
    return 1;
}

static std::shared_ptr<WsClient> takeClient(lua_State *L, int index) {
    int id = (int) luaL_checkinteger(L, index);
    std::lock_guard<std::mutex> lock(gMutex);
    auto it = gClients.find(id);
    if (it == gClients.end()) luaL_error(L, "ws: no such connection %d", id);
    return it->second;
}

// 从队列取一个事件（阻塞至 timeoutMs，0=非阻塞）；无事件返回 false
static bool popEvent(std::deque<WsEvent> &q, std::mutex &m, int timeoutMs, WsEvent &out) {
    int waited = 0;
    while (true) {
        {
            std::lock_guard<std::mutex> lock(m);
            if (!q.empty()) {
                out = q.front();
                q.pop_front();
                return true;
            }
        }
        if (waited >= timeoutMs) return false;
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
        waited += 5;
    }
}

// 服务端事件拉取（gSrvMutex，可重入不适用——这里单次加锁）
static bool popEventSrv(int timeoutMs, WsEvent &out) {
    int waited = 0;
    while (true) {
        {
            std::lock_guard<std::recursive_mutex> lock(gSrvMutex);
            if (!gServerEvents.empty()) {
                out = gServerEvents.front();
                gServerEvents.pop_front();
                return true;
            }
        }
        if (waited >= timeoutMs) return false;
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
        waited += 5;
    }
}

// ws.clientPoll(connId[, timeoutMs=0]) -> "open" | "message",msg | "close" | nil
static int wsClientPoll(lua_State *L) {
    auto c = takeClient(L, 1);
    int timeoutMs = (int) luaL_optinteger(L, 2, 0);
    WsEvent ev;
    if (!popEvent(c->queue, c->mutex, timeoutMs, ev)) return 0;
    if (ev.type == "message") {
        lua_pushstring(L, "message");
        lua_pushlstring(L, ev.data.data(), ev.data.size());
        return 2;
    }
    lua_pushstring(L, ev.type.c_str());
    return 1;
}

// ws.clientSend(connId, msg[, binary])
static int wsClientSend(lua_State *L) {
    auto c = takeClient(L, 1);
    size_t size = 0;
    const char *msg = luaL_checklstring(L, 2, &size);
    bool binary = lua_toboolean(L, 3) != 0;
    if (!c->client->isConnected()) return luaL_error(L, "ws.clientSend: not connected");
    int ret = binary ? c->client->send(msg, (int) size, WS_OPCODE_BINARY)
                     : c->client->send(std::string(msg, size));
    if (ret < 0) return luaL_error(L, "ws.clientSend: send failed (%d)", ret);
    lua_pushboolean(L, true);
    return 1;
}

// ws.clientClose(connId)
static int wsClientClose(lua_State *L) {
    int id = (int) luaL_checkinteger(L, 1);
    std::shared_ptr<WsClient> c;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        auto it = gClients.find(id);
        if (it == gClients.end()) {
            lua_pushboolean(L, false);
            return 1;
        }
        c = it->second;
        gClients.erase(it);
    }
    try {
        c->client->close();
    } catch (...) {
    }
    lua_pushboolean(L, true);
    return 1;
}

// ws.clientIsOpen(connId) -> bool
static int wsClientIsOpen(lua_State *L) {
    auto c = takeClient(L, 1);
    lua_pushboolean(L, c->client->isConnected());
    return 1;
}

// ------------------------------------------------------------------
// 服务端
// ------------------------------------------------------------------

// 端口占用探测：能 connect 成功即视为已被占用
static bool portInUse(int port) {
    int fd = ::socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return false;
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons((unsigned short) port);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    int ret = ::connect(fd, (sockaddr *) &addr, sizeof(addr));
    ::close(fd);
    return ret == 0;
}

// ws.serverStart(port) -> bool
static int wsServerStart(lua_State *L) {
    int port = (int) luaL_checkinteger(L, 1);
    std::lock_guard<std::recursive_mutex> lock(gSrvMutex);
    if (gServerStatus.load() == 1) {
        lua_pushboolean(L, true);  // 已在运行，幂等
        return 1;
    }
    if (portInUse(port)) {
        lua_pushnil(L);
        lua_pushstring(L, "port already in use");
        return 2;
    }
    gService = new hv::WebSocketService();
    gService->ping_interval = 10000;
    gService->onopen = [](const WebSocketChannelPtr &channel, const HttpRequestPtr &) {
        std::lock_guard<std::recursive_mutex> lk(gSrvMutex);
        int id = ++gServerConnSeq;
        gServerConns[id] = channel;
        pushEventSrv(id, "open");
    };
    gService->onmessage = [](const WebSocketChannelPtr &channel, const std::string &msg) {
        std::lock_guard<std::recursive_mutex> lk(gSrvMutex);
        int id = 0;
        for (auto &kv: gServerConns) {
            if (kv.second == channel) { id = kv.first; break; }
        }
        pushEventSrv(id, "message", msg);
    };
    gService->onclose = [](const WebSocketChannelPtr &channel) {
        std::lock_guard<std::recursive_mutex> lk(gSrvMutex);
        for (auto it = gServerConns.begin(); it != gServerConns.end(); ++it) {
            if (it->second == channel) {
                pushEventSrv(it->first, "close");
                gServerConns.erase(it);
                break;
            }
        }
    };
    gServer = new hv::WebSocketServer(gService);
    gServer->setPort((unsigned short) port);
    gServer->setThreadNum(2);
    gServerThread = new std::thread([]() {
        gServer->run();
        gServerStatus.store(0);
    });
    gServerStatus.store(1);
    lua_pushboolean(L, true);
    return 1;
}

// ws.serverStop()
static int wsServerStop(lua_State *L) {
    // stop()/join 会等待 hv 线程（其 onclose 回调要拿 gSrvMutex），
    // 故必须在【不持锁】状态下执行 stop 与 join，否则锁顺序死锁
    hv::WebSocketServer *srv = nullptr;
    std::thread *th = nullptr;
    {
        std::lock_guard<std::recursive_mutex> lock(gSrvMutex);
        srv = gServer;
        gServer = nullptr;
        th = gServerThread;
        gServerThread = nullptr;
        gServerStatus.store(0);
        gServerConns.clear();
        gServerEvents.clear();
    }
    if (srv != nullptr) {
        srv->stop();
    }
    if (th != nullptr) {
        if (th->joinable()) th->join();
        delete th;
    }
    // hv 线程已全部退出，安全销毁
    {
        std::lock_guard<std::recursive_mutex> lock(gSrvMutex);
        delete srv;
        delete gService;
        gService = nullptr;
        gServerConns.clear();
        gServerEvents.clear();
    }
    lua_pushboolean(L, true);
    return 1;
}

// ws.serverPoll([timeoutMs=0]) -> "open",connId | "message",connId,msg | "close",connId | nil
static int wsServerPoll(lua_State *L) {
    int timeoutMs = (int) luaL_optinteger(L, 1, 0);
    WsEvent ev;
    if (!popEventSrv(timeoutMs, ev)) return 0;
    if (ev.type == "message") {
        lua_pushstring(L, "message");
        lua_pushinteger(L, ev.connId);
        lua_pushlstring(L, ev.data.data(), ev.data.size());
        return 3;
    }
    lua_pushstring(L, ev.type.c_str());
    lua_pushinteger(L, ev.connId);
    return 2;
}

// ws.serverSend(connId, msg[, binary])
static int wsServerSend(lua_State *L) {
    int connId = (int) luaL_checkinteger(L, 1);
    size_t size = 0;
    const char *msg = luaL_checklstring(L, 2, &size);
    bool binary = lua_toboolean(L, 3) != 0;
    WebSocketChannelPtr channel;
    {
        std::lock_guard<std::recursive_mutex> lock(gSrvMutex);
        auto it = gServerConns.find(connId);
        if (it == gServerConns.end()) return luaL_error(L, "ws.serverSend: no such conn %d", connId);
        channel = it->second;
    }
    int ret = binary ? channel->send(msg, (int) size, WS_OPCODE_BINARY)
                     : channel->send(std::string(msg, size), WS_OPCODE_TEXT);
    if (ret < 0) return luaL_error(L, "ws.serverSend: send failed (%d)", ret);
    lua_pushboolean(L, true);
    return 1;
}

// ws.serverBroadcast(msg[, binary]) -> 发送到的连接数
static int wsServerBroadcast(lua_State *L) {
    size_t size = 0;
    const char *msg = luaL_checklstring(L, 1, &size);
    bool binary = lua_toboolean(L, 2) != 0;
    int count = 0;
    std::lock_guard<std::recursive_mutex> lock(gSrvMutex);
    for (auto &kv: gServerConns) {
        int ret = binary ? kv.second->send(msg, (int) size, WS_OPCODE_BINARY)
                         : kv.second->send(std::string(msg, size), WS_OPCODE_TEXT);
        if (ret >= 0) count++;
    }
    lua_pushinteger(L, count);
    return 1;
}

// ws.serverIsRunning() -> bool
static int wsServerIsRunning(lua_State *L) {
    lua_pushboolean(L, gServerStatus.load() == 1);
    return 1;
}

// ws.serverConnCount() -> int
static int wsServerConnCount(lua_State *L) {
    std::lock_guard<std::recursive_mutex> lock(gSrvMutex);
    lua_pushinteger(L, (lua_Integer) gServerConns.size());
    return 1;
}

} // namespace wslib

static const luaL_Reg WS_LIB[] = {
        {"clientConnect",   wslib::wsClientConnect},
        {"clientPoll",      wslib::wsClientPoll},
        {"clientSend",      wslib::wsClientSend},
        {"clientClose",     wslib::wsClientClose},
        {"clientIsOpen",    wslib::wsClientIsOpen},
        {"serverStart",     wslib::wsServerStart},
        {"serverStop",      wslib::wsServerStop},
        {"serverPoll",      wslib::wsServerPoll},
        {"serverSend",      wslib::wsServerSend},
        {"serverBroadcast", wslib::wsServerBroadcast},
        {"serverIsRunning", wslib::wsServerIsRunning},
        {"serverConnCount", wslib::wsServerConnCount},
        {nullptr, nullptr}
};

int injectWsLib(lua_State *L) {
    lua_newtable(L);
    luaL_setfuncs(L, WS_LIB, 0);
    lua_setglobal(L, "ws");
    return 0;
}

} // namespace autolua
