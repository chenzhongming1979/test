
#include"LuaInterpreter.h"

#include<assert.h>
#include<ctime>
#include"lua.hpp"
#include "my_log.h"
#include "lua_context.h"
#include "thread_lib.h"

// 前置声明：resetLuaState 的注入表引用（定义在文件后部 lSleep 附近）
static int lExitScript(lua_State *L);

LuaInterpreter::LuaInterpreter(std::shared_ptr<LuaStateFactory> factory)
    :workerRunning_(false),
    luaFactory_(factory),
    luaState_(nullptr),
      codeType_(nullptr),
      executeMode_(kNone),
      callback_(nullptr),
      state_(ALEState::kIdle),
      destroy_(false)
{
}

void LuaInterpreter::quit() {
    ensureHasSignal(SignalEvent::kQuit);
}


class MyObserver:public LuaInterpreter::Observer{
    std::mutex mutex_;
    std::condition_variable condition_;
    std::atomic<ALEState> state_;
public:
    void onStateChange(ALEState state) override {
        state_.store(state);

    }
    ALEState wait(int seconds = 0){
        std::unique_lock<std::mutex> locker(mutex_);

        return state_;
    }
};

void LuaInterpreter::onReleaseAllResource() {
    if(luaFactory_ && luaState_){
        luaFactory_->release(luaState_);
    }
    {
        std::unique_lock<std::shared_mutex> locker(observersMutex_);
        observers_.clear();
    }
    codeProviders_.clear();
    resourceProviders_.clear();
    luaFactory_.reset();
    initializeLuaThreadCallback_ = nullptr;
    releaseLuaThreadCallback_ = nullptr;
    code_.clear();
    codeType_ = nullptr;
    executeMode_ = kNone;
    callback_ = nullptr;
    errorCallback_ = nullptr;
}


void LuaInterpreter::destroy() {
    bool expected = false;
    if(!destroy_.compare_exchange_strong(expected,true))
        return;
    LOGD("LuaInterpreter destroy");
    std::mutex mutex;
    std::condition_variable condition;
    std::unique_lock<std::mutex> locker(mutex);
    workerStopCallback_ = [&condition](){
        condition.notify_all();
    };
    locker.unlock();
    while (workerRunning_){
        quit();
        locker.lock();
        condition.wait_for(locker,std::chrono::seconds(3),[this]{
            return !workerRunning_;
        });
        LOGD("LuaInterpreter wait for worker stop %d",workerRunning_.load());
    }
    onReleaseAllResource();
}


LuaInterpreter::~LuaInterpreter()
{
    destroy();
}


void LuaInterpreter::onInitializeLuaThread(std::function<void()> callback)
{
    initializeLuaThreadCallback_ = std::move(callback);
}

void LuaInterpreter::onReleaseLuaThreadBefore(std::function<void()> callback)
{
    releaseLuaThreadCallback_ = std::move(callback);
}



int LuaInterpreter::execute(const std::string &code, CodeMode codeType,
                            int executeMode, Callback callback, ErrorCallback errorCallback)
{
    {
        std::shared_lock<std::shared_mutex> locker(stateMutex_);
        if(state_ != ALEState::kIdle){
            LOGE("LuaInterpreter execute reject: state=%d (not Idle)", (int)state_);
            return 1;
        }
    }
    auto locker = signalEvent_.locker();
    if(signalEvent_.has(SignalEvent::kExecute)){
        LOGE("LuaInterpreter execute reject: kExecute pending");
        return 2;
    }
    signalEvent_.add(SignalEvent::kExecute);
    code_ = code;
    if(codeType == CodeMode::kTextOrBinary)
        codeType_ = "bt";
    else if(codeType == CodeMode::kBinary)
        codeType_ = "b";
    else if(codeType == CodeMode::kText)
        codeType_ = "t";
    else
        codeType_ = "bt";
//    LOGE("lua interpreter now code type = %s\n",codeType_);
    executeMode_ = executeMode;
    callback_ = std::move(callback);
    errorCallback_ = std::move(errorCallback);
    bool expected = false;
    if(workerRunning_.compare_exchange_strong(expected,true))
    {
        std::thread t(&LuaInterpreter::loop,this);
        t.detach();
    }else{
        signalEvent_.notify();
    }
    return 0;
}

static int lErrorCallback(lua_State*L)
{
    auto luaMethod = (LuaInterpreter::ErrorCallback*)lua_touserdata(L,lua_upvalueindex(1));
    return (*luaMethod)(L);
}

void LuaInterpreter::loop()
{
    if(initializeLuaThreadCallback_)
        initializeLuaThreadCallback_();
    auto locker = signalEvent_.locker();
    while (true)
    {
        int signals = signalEvent_.wait(locker);
        if(signals & SignalEvent::kQuit) break;
        if(signals & SignalEvent::kAwake){
            signalEvent_.clean(SignalEvent::kAwake);
            continue;
        }
        if(signals & SignalEvent::kInterrupt)
        {
            signalEvent_.clean(SignalEvent::kInterrupt);
            LOGD("LuaInterpreter loop clean stray kInterrupt");
            continue;
        }
        if(signals == SignalEvent::kNon) continue;
        else if(not(signals & SignalEvent::kExecute)){
            LOGE("LuaInterpreter now signal %s",signalEvent_.toString().c_str());
            LOGE("LuaInterpreter unexpected signal %d",signals);
            signalEvent_.set(SignalEvent::kNon);
            continue;
        }
        signalEvent_.clean(SignalEvent::kExecute);
        locker.unlock();
        {
            std::unique_lock<std::shared_mutex> stateLocker(stateMutex_);
            state_ = ALEState::kRunning;
        }
        notifyStateChange(ALEState::kRunning);
        if(luaState_ == nullptr)
        {
            resetLuaState();
            // 创建失败（Java 侧 companion 异常）：丢弃本次执行并回 Idle，
            // 保证上层状态广播/观察者能收到结束事件，而不是线程崩溃
            if(luaState_ == nullptr){
                LOGE("LuaInterpreter: lua state unavailable, drop execute");
                code_ = "";
                callback_ = nullptr;
                errorCallback_ = nullptr;
                {
                    std::unique_lock<std::shared_mutex> stateLocker(stateMutex_);
                    state_ = ALEState::kIdle;
                }
                notifyStateChange(ALEState::kIdle);
                locker.lock();
                continue;
            }
        }else if(executeMode_ & kNewLua)
        {
            releaseLuaState();
            resetLuaState();
            if(luaState_ == nullptr){
                LOGE("LuaInterpreter: lua state recreate failed, drop execute");
                code_ = "";
                callback_ = nullptr;
                errorCallback_ = nullptr;
                {
                    std::unique_lock<std::shared_mutex> stateLocker(stateMutex_);
                    state_ = ALEState::kIdle;
                }
                notifyStateChange(ALEState::kIdle);
                locker.lock();
                continue;
            }
        }
        int luaTop = lua_gettop(luaState_);
        if(errorCallback_)
        {
            lua_pushlightuserdata(luaState_,&errorCallback_);
            lua_pushcclosure(luaState_,lErrorCallback,1);
        }
        LOGD("LuaInterpreter ready execute code");
        // 状态锁：与 thread 协程线程互斥（sleep 等挂起点会释放）
        autolua::lockLuaState(luaState_);
        int r = luaL_loadbufferx(luaState_,code_.c_str(),code_.size(),"bootstrap",codeType_);
        LOGD("LuaInterpreter load buffer result = %d",r);
        if(r == LUA_OK){
            r = lua_pcall(luaState_,0,LUA_MULTRET,errorCallback_?-2:0);
            LOGD("LuaInterpreter call result = %d",r);
        }
        if(r != LUA_OK){
            LOGD("LuaInterpreter error %s",lua_tostring(luaState_,-1));
        }
        if(LUA_TFUNCTION == lua_getglobal(luaState_,"OnStop")){
            LOGD("LuaInterpreter calling global OnStop");
            lua_pcall(luaState_,0,0,errorCallback_?luaTop+1:0);
        }else{
            lua_pop(luaState_,1);
            LOGD("LuaInterpreter global OnStop not present");
        }
        if(callback_)
        {
            LOGD("LuaInterpreter result callback");
            callback_(r,errorCallback_?luaTop+2:luaTop+1, luaState_);
        }
        lua_settop(luaState_,luaTop);
        autolua::unlockLuaState(luaState_);
        if(!(executeMode_ & kRetainLua))
        {
            LOGD("LuaInterpreter release lua_state");
            releaseLuaState();
        }
        code_ = "";
        callback_ = nullptr;
        errorCallback_ = nullptr;
        LOGD("LuaInterpreter wait execute signal");
        {
            std::unique_lock<std::shared_mutex> stateLocker(stateMutex_);
            state_ = ALEState::kIdle;
        }
        notifyStateChange(ALEState::kIdle);
        locker.lock();
    }
    if(releaseLuaThreadCallback_)
        releaseLuaThreadCallback_();
    signalEvent_.clean(SignalEvent::kQuit);
    locker.unlock();
    std::function<void()> callback;
    {
        callback = std::move(workerStopCallback_);
    }
    workerRunning_ = false;
    if (callback) callback();
}


void LuaInterpreter::resetLuaState()
{
    lua_State*L = luaFactory_->create();
    if(!L){
        LOGE("LuaInterpreter: create lua state failed, keep state empty");
        return;
    }
    auto context = toLuaContext(L);
    context->signalEvent = &signalEvent_;
    context->interpreter = this;
    luaState_ = L;
    injectMethod("sleep",lSleep);
    injectMethod("require",lRequire);
    injectMethod("loadfile",lLoadFile);
    injectMethod("loadresource",lLoadResource);
    injectMethod("exitScript",lExitScript);
}

void LuaInterpreter::releaseLuaState()
{
    if(luaState_){
        luaFactory_->release(luaState_);
        luaState_ = nullptr;
    }
}


void LuaInterpreter::interrupt()
{
    ensureHasSignal(SignalEvent::kInterrupt);
    // 同步取消 thread 协程任务（协程在 sleep/lock 挂起点结束，不再 resume）
    if(luaState_){
        autolua::stopAllThreadTasks(luaState_);
    }
}

ALEState LuaInterpreter::getState()
{
    std::shared_lock<std::shared_mutex> locker(stateMutex_);
    return state_;
}


// exitScript()：请求中断当前脚本（懒人 exitScript）。
// 置 kInterrupt 信号并取消协程任务，随后抛错终止本次执行；
// loop 捕获错误后走 OnStop -> Idle，状态广播正常回收
static int lExitScript(lua_State *L) {
    auto* self = (LuaInterpreter*)lua_touserdata(L, lua_upvalueindex(1));
    self->interrupt();
    lua_pushstring(L, "exitScript");
    return lua_error(L);
}

int LuaInterpreter::lSleep(lua_State *L) {
    auto* self = (LuaInterpreter*)lua_touserdata(L, lua_upvalueindex(1));
    lua_Integer time = luaL_checkinteger(L,1);
    auto & signalEvent = self->signalEvent_;
    // sleep 等待期间释放状态锁，允许 thread 协程并行执行
    autolua::unlockLuaState(L);
    auto locker = signalEvent.locker();
    int signals = signalEvent.wait_for(locker,time);
    // lua_error 走 longjmp（NDK Lua 未启用 -fexceptions），跳过 C++ RAII 析构——
    // 必须在抛错前显式释放 signalEvent_ 锁，否则 loop 线程 locker.lock() 永久死锁，
    // 表现为"stop_script 后再 run_script 永远静默失败"
    if(signals & SignalEvent::kQuit || (signals & SignalEvent::kInterrupt)){
        locker.unlock();
        autolua::lockLuaState(L);
        lua_pushstring(L,"interrupt");
        lua_error(L);
    }
    autolua::lockLuaState(L);
    return 0;
}

void LuaInterpreter::notifyStateChange(ALEState state) {
    std::vector<decltype(observers_.begin())> to_erase;
    {
        std::shared_lock<std::shared_mutex> locker(observersMutex_);
        for(auto it = observers_.begin();it!=observers_.end();it++){
            if(auto ptr = it->lock()){
                ptr->onStateChange(state);
            }else{
                to_erase.push_back(it);
            }
        }
    }
    if(!to_erase.empty()){
        std::unique_lock<std::shared_mutex> locker(observersMutex_);
        for(auto& it:to_erase){
            observers_.erase(it);
        }
    }
}

void LuaInterpreter::pause() {
    ensureHasSignal(SignalEvent::kPause);
}

void LuaInterpreter::resume() {
    ensureHasSignal(SignalEvent::kResume);
}


void LuaInterpreter::attach(std::shared_ptr<Observer> observer, bool immediately) {
    std::unique_lock<std::shared_mutex> locker(observersMutex_);
    observers_.insert(observer);
    if(immediately){
        observer->onStateChange(getState());
    }
}

void LuaInterpreter::detach(std::shared_ptr<Observer> observer) {
    std::unique_lock<std::shared_mutex> locker(observersMutex_);
    observers_.erase(observer);
}

int LuaInterpreter::lRequire(struct lua_State *L) {
    auto* self = (LuaInterpreter*)lua_touserdata(L,lua_upvalueindex(1));
    const char* name = luaL_checkstring(L,1);
    auto context = toLuaContext(L);
    luaL_requiref(L,name,rawRequire,0);
    return 1;
}

int LuaInterpreter::lLoadFile(struct lua_State *L) {
    auto* self = (LuaInterpreter*)lua_touserdata(L,lua_upvalueindex(1));
    const char* name = luaL_checkstring(L,1);
    std::string code;
    std::set<std::shared_ptr<CodeProvider>> providers;
    {
        std::shared_lock<std::shared_mutex> locker(self->codeProvidersMutex_);
        providers = self->codeProviders_;
    }
    int type;
    int err = 0;
    for(auto& provider:providers){
        err = provider->loadFile(&self->signalEvent_,name,code,type);
        if(err == 0) {
            if (!code.empty())
                break;
        }else
            break;
    }
    if(err & SignalEvent::kInterrupt || err & SignalEvent::kQuit) {
        lua_pushstring(L, "interrupt");
    }else if(code.empty()){
        lua_pushfstring(L,"file '%s' not found",name);
    }else{
        int r;
        if(type == (int)CodeMode::kTextOrBinary){
            r =luaL_loadbufferx(L,code.c_str(),code.size(),name,"bt");
        }else{
            r =luaL_loadbufferx(L,code.c_str(),code.size(),name, type == (int)CodeMode::kText ? "t" : "b");
        }
        if(r == LUA_OK)
            return 1;
    }
    lua_error(L);
    return 0;
}

int LuaInterpreter::lLoadResource(struct lua_State *L) {
    auto* self = (LuaInterpreter*)lua_touserdata(L,lua_upvalueindex(1));
    const char* name = luaL_checkstring(L,1);
    std::string resource;
    int err = 0;
    std::set<std::shared_ptr<ResourceProvider>> providers;
    {
        std::shared_lock<std::shared_mutex> locker(self->resourceProvidersMutex_);
        providers = self->resourceProviders_;
    }

    for(auto& provider:providers){
        err = provider->loadResource(&self->signalEvent_,name,resource);
        if(err == 0){
            if(resource.empty())
                continue;
            lua_pushlstring(L,resource.c_str(),resource.size());
            return 1;
        }else
            break;
    }
    if(err & SignalEvent::kInterrupt || err & SignalEvent::kQuit){
        lua_pushstring(L,"interrupt");
    }else{
        lua_pushfstring(L,"resource '%s' not found",name);
    }
    lua_error(L);
    return 0;
}

void LuaInterpreter::injectMethod(const char *name, lua_CFunction func) {
    lua_pushlightuserdata(luaState_,this);
    lua_pushcclosure(luaState_,func,1);
    lua_setglobal(luaState_,name);
}



void LuaInterpreter::ensureHasSignal(int signal) {
    auto locker = signalEvent_.locker();
    if(signalEvent_.has(signal))
        return;
    signalEvent_.add(signal);
    signalEvent_.notify();
}

void LuaInterpreter::addCodeProvider(std::shared_ptr<CodeProvider> provider) {
    std::unique_lock<std::shared_mutex> locker(codeProvidersMutex_);
    codeProviders_.insert(provider);
}

void LuaInterpreter::removeCodeProvider(std::shared_ptr<CodeProvider> provider) {
    std::unique_lock<std::shared_mutex> locker(codeProvidersMutex_);
    codeProviders_.erase(provider);
}

void LuaInterpreter::addResourceProvider(std::shared_ptr<ResourceProvider> provider) {
    std::unique_lock<std::shared_mutex> locker(resourceProvidersMutex_);
    resourceProviders_.insert(provider);
}

void LuaInterpreter::removeResourceProvider(std::shared_ptr<ResourceProvider> provider) {
    std::unique_lock<std::shared_mutex> locker(resourceProvidersMutex_);
    resourceProviders_.erase(provider);
}

int LuaInterpreter::rawRequire(struct lua_State *L) {
    auto context= toLuaContext(L);
    auto self  = (LuaInterpreter*)context->interpreter;
    const char* name = luaL_checkstring(L,1);

    std::string code;
    std::set<std::shared_ptr<CodeProvider>> providers;
    {
        std::shared_lock<std::shared_mutex> locker(self->codeProvidersMutex_);
        providers = self->codeProviders_;
    }
    int type;
    int err = 0;
    for(auto& provider:providers){
        err = provider->loadModule(&self->signalEvent_,name,code,type);
        if(err == 0) {
            if(!code.empty())
                break;
        }else
            break;
    }
    if(err & SignalEvent::kInterrupt || err & SignalEvent::kQuit){
        lua_pushstring(L,"interrupt");
    }else if(code.empty()){
        lua_pushfstring(L,"module '%s' not found",name);
    }else{
        int r;
        if (type == (int)CodeMode::kTextOrBinary) {
            r = luaL_loadbufferx(L, code.c_str(), code.size(), name, "bt");
        } else {
            r = luaL_loadbufferx(L, code.c_str(), code.size(), name,
                                 type == (int)CodeMode::kText ? "t" : "b");
        }
        if (r == LUA_OK) {
            r = lua_pcall(L, 0, 1, 0);
        }
        if (r == LUA_OK) {
            return 1;
        }
    }
    lua_error(L);
    return 0;
}


int LuaInterpreter::SignalEvent::wait_for(std::unique_lock<std::mutex> &locker,
                                          int timeout) {
    timeval startTime{};
    gettimeofday(&startTime, nullptr);
    condVal_.wait_for(locker,std::chrono::milliseconds(timeout),[this,&startTime,&timeout]{
        if(signals_ & kQuit || (signals_ & kInterrupt))
            return true;
        if(signals_ & kPause){
            if(signals_ & kResume){
                signals_ &= ~kResume;
                signals_ &= ~kPause;
                timeval now{};
                gettimeofday(&now, nullptr);
                int64_t interval = (now.tv_sec - startTime.tv_sec)*1000+(now.tv_usec-startTime.tv_usec)/1000;
                return interval >= timeout;
            }
            return false;
        }
        return signals_ != 0;
    });
    return signals_;
}