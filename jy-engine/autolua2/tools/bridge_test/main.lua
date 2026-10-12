-- 基础能力三件套冒烟测试：http / luajava(import) / thread
-- 用法：IDE 沙箱（调试页执行）或主引擎运行本脚本；逐段查看输出

print("=== 1. http.request ===")
local body, code, headers = http.request{
    url = "https://httpbin.org/get",
    method = "GET",
    timeout = 10000
}
print("http GET code =", code)
print("http body length =", body and #body or 0)
print("http has headers =", headers ~= nil)

local b2, c2 = http.get("https://httpbin.org/status/200")
print("http.get code =", c2)

print("=== 2. luajava / import 反射桥 ===")
local JInteger = import("java.lang.Integer")
print("import Integer ->", JInteger ~= nil)
local n = JInteger:new(42)
print("Integer:new(42) =", n, n:toInt())
print("Integer.parseInt('99') =", JInteger:parseInt("99"))
print("Integer.MAX_VALUE =", JInteger.MAX_VALUE)

local cls = luajava.bindClass("java.lang.StringBuilder")
local sb = cls:new("hello")
sb:append(" luajava")
print("StringBuilder =", sb:toString())

print("=== 3. LuaEngine 桥 ===")
print("LuaEngine.version =", LuaEngine.getVersion())
local ctx = LuaEngine.getContext()
print("LuaEngine.context =", ctx)

print("=== 4. thread 多线程（协程 + 共享 + 锁） ===")
thread.setShared("counter", 0)
thread.setShared("msg", "from-main")

local lk = thread.lock()

local t1 = thread.start(function(tag)
    -- 协程内：共享 _G、共享表、锁、sleep 均可用
    thread.sleep(200)
    thread.acquire(lk)
    local c = thread.getShared("counter")
    thread.setShared("counter", c + 1)
    thread.release(lk)
    print("coroutine", tag, "saw msg =", thread.getShared("msg"))
    return tag .. "-done", 100 + 1
end, "workerA")

local t2 = thread.start(function(tag)
    thread.acquire(lk)
    local c = thread.getShared("counter")
    thread.setShared("counter", c + 10)
    thread.release(lk)
    -- atomicInc 需要 key 已存在（整数值）
    thread.setShared("atomic", 5)
    thread.atomicInc("atomic", 3)
    return tag .. "-done"
end, "workerB")

print("main: isMain =", thread.isMain(), " t1 id =", t1, " t2 id =", t2)

local ok1, r1a, r1b = thread.join(t1)
print("join t1 ->", ok1, r1a, r1b)
local ok2, r2 = thread.join(t2)
print("join t2 ->", ok2, r2)

print("counter (expect 11) =", thread.getShared("counter"))
print("atomic (expect 8) =", thread.getShared("atomic"))

-- 协程错误传播
local t3 = thread.start(function()
    thread.sleep(50)
    error("boom-inside-coroutine")
end)
local ok3 = thread.join(t3)
print("join t3 (expect false) =", ok3)

-- stop 语义：在 sleep 挂起点取消
local t4 = thread.start(function()
    while true do
        thread.sleep(100)
    end
end)
thread.sleep(250)
thread.stop(t4)
print("stop t4 ->", thread.join(t4))

print("=== ALL DONE ===")
