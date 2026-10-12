--[[ auth_provider_demo.lua —— 自定义 auth Provider 演示（契约：contracts.md §4.4）
用法：sync 下发 conf.auth = {provider="custom", module="auth_provider_demo", ...}
行为：login 延迟 3 秒模拟网络耗时，任意卡密均授权（token="demo-<card>"，expire=now+600）；
      卡密含 "FAILHB" 时 heartbeat 恒失败——联调验证连续失败 fail_limit 次 → expired 拦截。
--]]
local M = {}

local function sleepMs(ms)
    local fs = rawget(_G, "frameSdk")
    if type(fs) == "table" and fs.sleep ~= nil then pcall(fs.sleep, ms) end
end

-- 契约：true, token, expireTs(os.time 秒) | false, 原因
function M.login(card, device)
    sleepMs(3000)  -- 模拟网络延迟
    return true, "demo-" .. tostring(card), os.time() + 600
end

-- 契约：true, remainSeconds | false, 原因
function M.heartbeat(token)
    if type(token) == "string" and token:find("FAILHB", 1, true) then
        return false, "演示心跳失败(FAILHB)"
    end
    return true, 600
end

-- 可选
function M.logout(token)
    return true
end

return M
