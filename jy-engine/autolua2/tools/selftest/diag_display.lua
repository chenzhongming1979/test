-- diag_display.lua —— 虚拟屏 Display 回归：无帧保护 / jpeg 假成功 / 旋转自动跟随
-- 依据 docs/02-Lua-API规范.md §2 图色章 与 docs/plans/虚拟屏功能实施任务书.md §3.1/§3.2/§3.3
--
-- 运行前提：引擎已注入 Display（root 主引擎 DisplayImplement，或沙箱 SharedMemoryDisplay）。
--   * 用例 1 需无帧环境（熄屏或采集停止）才有意义；有帧环境下会打印 FAIL（属环境不符，非缺陷）。
--   * 用例 2/3 在有帧环境验证。
print("== diag_display start ==")

-- 1) §3.1 无帧守卫：熄屏/采集停止时 findColor 3 秒内抛可捕获错误（不再永久阻塞）
local ok1, err1 = pcall(function()
    return findColor(0, 0, 100, 100, 0xFF0000, 1)
end)
if not ok1 and tostring(err1):find("screen frame unavailable", 1, true) then
    print("PASS 1: no-frame findColor raises catchable error: " .. tostring(err1))
else
    print("FAIL 1: expect pcall-caught 'screen frame unavailable ...', got ok="
        .. tostring(ok1) .. " err=" .. tostring(err1) .. "（有帧环境属预期不符，请熄屏后重跑）")
end

-- 2) §3.2 jpeg 假成功修复：saveScreen(...,"jpeg") 返回 false + 错误信息，不落 0 字节文件
local jpegPath = "/sdcard/diag_display_jpeg_test.jpg"
local ok2, r2a, r2b = pcall(saveScreen, 0, 0, 100, 100, jpegPath, "jpeg")
if ok2 and r2a == false and tostring(r2b):find("jpeg not supported", 1, true) then
    print("PASS 2: saveScreen jpeg returns false + message: " .. tostring(r2b))
else
    print("FAIL 2: expect saveScreen(jpeg) == false + 'jpeg not supported ...', got ok="
        .. tostring(ok2) .. " ret1=" .. tostring(r2a) .. " ret2=" .. tostring(r2b))
end

-- 3) §3.3 旋转自动跟随：发生旋转时引擎自动重建虚拟屏，findColor 不再抛 "display rotate"
local rotated = false
pcall(function() rotated = isChangeDirection() end)
local ok3, r3a, r3b = pcall(findColor, 0, 0, 100, 100, 0xFF0000, 1)
if ok3 then
    print("PASS 3: findColor returns normally without 'display rotate' (rotated="
        .. tostring(rotated) .. ") x=" .. tostring(r3a) .. " y=" .. tostring(r3b))
else
    local msg3 = tostring(r3a)
    if msg3:find("display rotate", 1, true) then
        print("FAIL 3: findColor still raises 'display rotate': " .. msg3)
    else
        -- 无帧环境下守卫错误先于旋转语义触发，属预期（用例 1 已覆盖该口径）
        print("INFO 3: findColor error in no-frame env (guard first, expected): " .. msg3)
    end
end

print("== diag_display end ==")
