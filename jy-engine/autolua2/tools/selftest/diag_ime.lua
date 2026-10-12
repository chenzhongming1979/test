-- 诊断2：协程内 Java 调用 + currentActivityThread systemContext + settings 写
print("== diag2 start ==")
-- 1) 协程内 Java 调用
local coDone = false
local coRes = nil
thread.start(function()
    print("[coro] started")
    local ok, v = pcall(function() return LuaEngine.getVersion() end)
    print("[coro] java call ok=", ok, tostring(v))
    coRes = ok
    coDone = true
end)
local waited = 0
while not coDone and waited < 3000 do
    sleep(100)
    waited = waited + 100
end
print("coro done=", tostring(coDone), "res=", tostring(coRes))
-- 2) currentActivityThread -> getSystemContext
local ok1, AT = pcall(function() return luajava.bindClass("android.app.ActivityThread") end)
local ok2, inst = pcall(function() return AT:currentActivityThread() end)
print("currentActivityThread:", ok2, tostring(inst))
if ok2 and inst ~= nil then
    local ok3, sc = pcall(function() return inst:getSystemContext() end)
    print("getSystemContext:", ok3, tostring(sc))
    if ok3 and sc ~= nil then
        local ok4, pn = pcall(function() return sc:getPackageName() end)
        print("pkg:", ok4, tostring(pn))
        local ok5 = pcall(function()
            local RT = luajava.bindClass("java.lang.Runtime")
            local proc = RT:getRuntime():exec({"settings", "put", "secure", "default_input_method", "com.autolua.autolua2/com.autolua.engine.inputmethod.AutoLuaInputMethodService"})
            print("settings put rc=", proc:waitFor())
        end)
        print("settings put pcall:", ok5)
        local ok6, cur = pcall(function()
            local SS = luajava.bindClass("android.provider.Settings$Secure")
            return SS:getString(sc:getContentResolver(), "default_input_method")
        end)
        print("read ime:", ok6, tostring(cur))
    end
end
print("== diag2 end ==")
