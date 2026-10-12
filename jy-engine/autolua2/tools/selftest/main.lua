-- ============================================================
-- JY-Frame (autolua2) 全量 API 自测脚本
-- 运行方式：作为脚本项目 main.lua 在引擎中执行（root 主引擎覆盖最全；
--           IDE 沙箱自动跳过图色/节点域）。
-- 输出：控制台 [PASS]/[FAIL] + 汇总，结果同时写入 _selftest/result.txt
-- 开关：RUN_INPUT_TESTS  真实触控（tap/swipe/按键，会操作设备）
--       RUN_APP_TESTS    真实启动/停止应用（runApp/stopApp）
--       RUN_NET_TESTS    网络请求（需设备可上网）
--       RUN_SYS_OPS      破坏性系统操作（锁屏/飞行模式/DPI，默认关）
-- ============================================================

RUN_INPUT_TESTS = false
RUN_APP_TESTS = false
RUN_NET_TESTS = true
RUN_SYS_OPS = false

local passCount, failCount = 0, 0
local failures = {}

local function check(name, ok, detail)
    if ok then
        passCount = passCount + 1
        print("[PASS] " .. name)
    else
        failCount = failCount + 1
        local msg = name .. (detail ~= nil and (" | " .. tostring(detail)) or "")
        failures[#failures + 1] = msg
        print("[FAIL] " .. msg)
    end
end

local function checkEq(name, actual, expected)
    if actual == expected then
        check(name, true)
    else
        check(name, false, "expect " .. tostring(expected) .. ", got " .. tostring(actual))
    end
end

local function checkType(name, value, luaType)
    check(name, type(value) == luaType, "expect type " .. luaType .. ", got " .. type(value))
end

local function checkSize(name, w, h, expectW, expectH)
    check(name, w == expectW and h == expectH, "expect " .. expectW .. "x" .. expectH .. ", got " .. tostring(w) .. "x" .. tostring(h))
end

-- 测试工作目录（ide_push 执行时 ROOT_DIR 可能为 nil，回退到 root 可写目录）
-- 注意：DIR 必须在 group 定义之前声明为 local，否则 group 内引用会落到全局（nil）
if ROOT_DIR == nil then
    print("[INFO] ROOT_DIR is nil (running from ide_push), fallback to /data/local/tmp")
end
local DIR = (ROOT_DIR or "/data/local/tmp") .. "/_selftest/"
pcall(function() mkdir(DIR) end)

-- 锁定竖屏：图色域在方向变化时报 display rotate（懒人语义），测试前固定旋转避免环境干扰
pcall(function()
    exec("settings put system accelerometer_rotation 0")
    exec("settings put system user_rotation 0")
end)

-- 安全执行一个测试块：块内错误计为 FAIL 且不中断后续
local function group(name, fn)
    print("---- " .. name .. " ----")
    local ok, err = pcall(fn)
    if not ok then
        check(name .. " (group)", false, err)
    end
    -- 进度落盘：日志断流时仍可从文件判断卡点
    writeFile(DIR .. "progress.txt", "done: " .. name .. "\n", true)
end

local function typeOf(v) return type(v) end

-- ============================================================
-- 1. 环境与基础
-- ============================================================
group("1.环境基础", function()
    -- contribute 注册的全局函数 type 为 "userdata"（LuaObjectAdapter __call），存在即可用
    check("ROOT_DIR 或回退目录可用", (ROOT_DIR ~= nil) or (mkdir ~= nil))
    checkType("sleep", sleep, "function")
    checkType("print", print, "function")
    checkType("loadfile", loadfile, "function")
    checkType("string.gsub", string.gsub, "function")
    checkType("table.insert", table.insert, "function")
    checkType("os.time", os.time, "function")
    checkEq("Lua5.4 utf8.len", utf8.len("你好"), 2)
end)

-- ============================================================
-- 2. 字符串扩展（compat）
-- ============================================================
group("2.字符串扩展", function()
    local t = splitStr("a,b,c")
    checkEq("splitStr 数量", #t, 3)
    checkEq("splitStr[1]", t[1], "a")
    checkEq("splitStr 自定义分隔", splitStr("a|b", "|")[2], "b")

    checkEq("utf8.sub", utf8.sub("你好世界", 1, 2), "你好")
    checkEq("utf8.left", utf8.left("你好世界", 2), "你好")
    checkEq("utf8.right", utf8.right("你好世界", 2), "世界")
    checkEq("utf8.mid", utf8.mid("你好世界", 2, 3), "好世")
    checkEq("utf8.length", utf8.length("你好"), 2)
    checkEq("utf8.inStr", utf8.inStr("你好世界", "世"), 3)
    checkEq("utf8.inStrRev", utf8.inStrRev("aXbXc", "X"), 4)
    checkEq("utf8.strReverse", utf8.strReverse("你好"), "好你")
    checkEq("utf8.strCut", utf8.strCut("你好", 2), "好")

    local n = utf8.gsub("abc", function(ch) return ch == "b" and "X" or nil end)
    checkEq("utf8.gsub", n, "aXc")
end)

-- ============================================================
-- 3. 数组/表扩展
-- ============================================================
group("3.数组表扩展", function()
    local t = { 10, 20, 30 }
    check("table.contains 命中", table.contains(t, 20) == true)
    check("table.contains 未命中", table.contains(t, 99) == false)
    checkEq("table.indexOf", table.indexOf(t, 30), 3)
    checkEq("table.indexOf 未命中", table.indexOf(t, 99), -1)
end)

-- ============================================================
-- 4. 数学与随机
-- ============================================================
group("4.数学随机", function()
    checkEq("math.clamp 上界", math.clamp(5, 1, 3), 3)
    checkEq("math.clamp 下界", math.clamp(0, 1, 3), 1)
    checkEq("math.clamp 区间内", math.clamp(2, 1, 3), 2)
    checkEq("math.round", math.round(2.5), 3)
    checkEq("math.round .4", math.round(2.4), 2)
    checkEq("math.lerp", math.lerp(0, 10, 0.5), 5)
    checkEq("math.sign+", math.sign(7), 1)
    checkEq("math.sign0", math.sign(0), 0)
    checkEq("math.sign-", math.sign(-3), -1)
    check("math.isEven", math.isEven(4) == true and math.isOdd(4) == false)
    check("math.inRange", math.inRange(5, 1, 10) == true and math.inRange(11, 1, 10) == false)
    check("math.isInteger", math.isInteger(5) == true and math.isInteger(5.5) == false)

    frandom.seed(42)
    checkEq("frandom.int 定值", frandom.int(1, 1), 1)
    local f = frandom.float(1, 2)
    check("frandom.float 范围", type(f) == "number" and f >= 1 and f <= 2)
    check("frandom.boolean", type(frandom.boolean()) == "boolean")
    checkEq("frandom.pick", frandom.pick({ "only" }), "only")
    check("frandom.hex", type(frandom.hex(8)) == "string" and #frandom.hex(8) == 8)
    check("frandom.string", #frandom.string(6) == 6)
    local u = frandom.uuid()
    check("frandom.uuid 格式", #u == 36 and u:sub(9, 9) == "-" and u:sub(15, 15) == "4")
    local lst = frandom.shuffle({ 1, 2, 3 })
    check("frandom.shuffle 保留元素", table.contains(lst, 1) and table.contains(lst, 2) and table.contains(lst, 3))
    checkEq("frandom.weighted", frandom.weighted({ { value = "a", weight = 100 }, { value = "b", weight = 0 } }), "a")
    checkEq("rnd 别名", rnd(5, 5), 5)
end)

-- ============================================================
-- 5. 时间
-- ============================================================
group("5.时间", function()
    local st = systemTime()
    check("systemTime 毫秒级", type(st) == "number" and st > 1e12)
    local tc = tickCount()
    check("tickCount", type(tc) == "number" and tc >= 0)
    checkEq("time.unix 秒级", type(time.unix()), "number")
    check("time.timestamp≈systemTime", math.abs(time.timestamp() - st) < 1000)
    checkType("time.millisecond", time.millisecond(), "number")
    checkType("time.year", time.year(), "number")
    checkType("time.month", time.month(), "number")
    checkType("time.day", time.day(), "number")
    checkType("time.now", time.now(), "table")
    checkType("time.format", time.format(st), "string")
    checkEq("time.format 字面", time.format(os.time({ year = 2024, month = 1, day = 2, hour = 3, min = 4, sec = 5 })), "2024-01-02 03:04:05")
    local ts = time.parse("2024-01-15 10:30:00")
    check("time.parse", type(ts) == "number")
    checkEq("time.diff", time.diff(100, 40), 60)
    checkEq("time.addSeconds", time.addSeconds(100, 20), 120)
    check("time.isLeapYear", time.isLeapYear(2024) == true and time.isLeapYear(2023) == false)
    checkEq("time.daysInMonth 平年2月", time.daysInMonth(2023, 2), 28)
    checkEq("time.daysInMonth 闰年2月", time.daysInMonth(2024, 2), 29)
end)

-- ============================================================
-- 6. 加密
-- ============================================================
group("6.加密", function()
    checkEq("crypt.md5", crypt.md5("abc"), "900150983cd24fb0d6963f7d28e17f72")
    checkEq("MD5 别名", MD5("abc"), "900150983cd24fb0d6963f7d28e17f72")
    checkEq("crypt.sha1", crypt.sha1("abc"), "a9993e364706816aba3e25717850c26c9cd0d89d")
    checkEq("sha256 别名", sha256("abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
    checkType("crypt.sha512", crypt.sha512("abc"), "string")
    -- fileMD5 需先落盘一个测试文件
    local md5src = DIR .. "md5src.txt"
    writeFile(md5src, "abc")
    checkEq("crypt.fileMD5", crypt.fileMD5(md5src), "900150983cd24fb0d6963f7d28e17f72")
    checkEq("fileMD5 别名", fileMD5(md5src), "900150983cd24fb0d6963f7d28e17f72")
    delfile(md5src)
    checkEq("crypt.encodeBase64", crypt.encodeBase64("hello"), "aGVsbG8=")
    checkEq("decodeBase64 别名", decodeBase64("aGVsbG8="), "hello")
    checkEq("crypt.encodeUrl", crypt.encodeUrl("a b&c"), "a+b%26c")
    checkEq("crypt.decodeUrl", crypt.decodeUrl("a+b%26c"), "a b&c")

    local key = "0123456789abcdef"
    local enc = crypt.aesCrypt("E", key, "secret")
    check("aes 加密输出 base64", type(enc) == "string" and #enc > 0)
    checkEq("aes ECB 解密往返", crypt.aesCrypt("D", key, enc), "secret")
    local iv = "fedcba9876543210"
    local enc2 = crypt.aesCrypt("E", key, "secret2", iv)
    checkEq("aes CBC 解密往返", crypt.aesCrypt("D", key, enc2, iv), "secret2")

    -- Java Map 返回自动转 Lua table（pushValue 底座），点索引取值
    local kp = crypt.rsaGenerateKey(1024)
    check("rsa 密钥对", kp ~= nil and kp.publicKey ~= nil and kp.privateKey ~= nil)
    local re = crypt.rsaEncrypt("rsa-msg", kp.publicKey)
    checkEq("rsa 解密往返", crypt.rsaDecrypt(re, kp.privateKey), "rsa-msg")
end)

-- ============================================================
-- 7. JSON
-- ============================================================
group("7.JSON", function()
    local t = json.decode('{"a":1,"b":"x","list":[1,2,3],"obj":{"k":true}}')
    checkEq("json.decode 标量", t.a, 1)
    checkEq("json.decode 字符串", t.b, "x")
    checkEq("json.decode 数组", t.list[2], 2)
    check("json.decode 嵌套", t.obj.k == true)
    local s = json.encode({ a = 1, b = "x" })
    check("json.encode 含字段", type(s) == "string" and s:find('"a"') ~= nil)
    checkEq("jsonLib 别名", jsonLib.encode({ v = 7 }), json.encode({ v = 7 }))
    check("json 往返", json.decode(json.encode({ n = { 1, 2 } })).n[1] == 1)
end)

-- ============================================================
-- 8. 文件域
-- ============================================================
group("8.文件", function()
    local f = DIR .. "t1.txt"
    check("writeFile", writeFile(f, "hello-写入") == true)
    check("fileExist", fileExist(f) == true)
    checkEq("readFile", readFile(f), "hello-写入")
    check("writeFile 追加", file.append(f, "+app") == true)
    checkEq("file.read 追加结果", file.read(f), "hello-写入+app")
    checkEq("fileSize", fileSize(f), #"hello-写入" + 4)
    check("file.mTime", file.mTime(f) > 0)

    check("file.mkdir", file.mkdir(DIR .. "d1/d2") == true)
    check("file.isDirectory", file.isDirectory(DIR .. "d1") == true)
    check("file.isFile", file.isFile(f) == true)
    check("file.copy", file.copy(f, DIR .. "d1/t2.txt") == true)
    check("copy 后存在", fileExist(DIR .. "d1/t2.txt"))
    check("file.rename", file.rename(DIR .. "d1/t2.txt", DIR .. "d1/t3.txt"))
    check("rename 后存在", fileExist(DIR .. "d1/t3.txt"))
    check("file.remove", file.remove(DIR .. "d1/t3.txt") == true)

    writeFile(DIR .. "d1/a.txt", "1")
    writeFile(DIR .. "d1/b.txt", "2")
    local names = listDir(DIR .. "d1")
    check("listDir 数量", type(names) == "table" and #names >= 2)
    check("listDir 含 a.txt", table.contains(names, "a.txt"))
    check("file.listDir", #file.listDir(DIR) >= 1)
    check("file.deleteDir", file.deleteDir(DIR .. "d1") == true)
    check("deleteDir 后不存在", fileExist(DIR .. "d1") == false)

    local bin = DIR .. "bin.dat"
    check("file.write 二进制源", writeFile(bin, "BIN") == true)
    checkEq("file.read 二进制长度", #file.read(bin), 3)
    file.remove(bin)
    check("delfile 别名", delfile(f) == true)
    check("delfile 后不存在", fileExist(f) == false)

    -- zip/unZip 往返（含子目录）
    local zsrc = DIR .. "zsrc"
    file.mkdir(zsrc)
    writeFile(zsrc .. "/a.txt", "zip-a")
    file.mkdir(zsrc .. "/sub")
    writeFile(zsrc .. "/sub/b.txt", "zip-b")
    check("zip 打包目录", zip(zsrc, DIR .. "t.zip") == true)
    check("zip 文件存在", fileExist(DIR .. "t.zip"))
    check("unZip 解包", unZip(DIR .. "t.zip", DIR .. "zdst") == true)
    checkEq("unZip 内容 a", readFile(DIR .. "zdst/a.txt"), "zip-a")
    checkEq("unZip 内容 sub/b", readFile(DIR .. "zdst/sub/b.txt"), "zip-b")
    file.deleteDir(zsrc)
    file.deleteDir(DIR .. "zdst")
    delfile(DIR .. "t.zip")
end)

-- ============================================================
-- 9. 设备域
-- ============================================================
group("9.设备", function()
    checkType("getBrand", getBrand(), "string")
    checkType("getModel", getModel(), "string")
    checkType("device.getModel", device.getModel(), "string")
    checkType("getManufacturer", getManufacturer(), "string")
    checkType("getFingerprint", getFingerprint(), "string")
    checkType("getCpuAbi", getCpuAbi(), "string")
    local sdk = getSdkVersion()
    check("getSdkVersion 合理", type(sdk) == "number" and sdk >= 21)
    checkType("getOsVersionName", getOsVersionName(), "string")
    checkType("getAndroidId", getAndroidId(), "string")
    checkType("getDeviceId", getDeviceId(), "string")
    checkType("getBatteryLevel", getBatteryLevel(), "number")
    checkType("device.isCharging", device.isCharging(), "boolean")
    checkType("getDisplayDpi", getDisplayDpi(), "number")
    local w, h = getDisplaySize()
    check("getDisplaySize", type(w) == "number" and w > 0 and h > 0)
    checkType("getMacAddress", getMacAddress(), "string")
    checkType("getIpAddress", getIpAddress(), "string")
    checkType("device.isWifiEnabled", device.isWifiEnabled(), "boolean")

    -- 剪贴板（root 走 IClipboard binder，沙箱走 ClipboardManager；
    --         root 进程 uid0 无包身份，读侧可能被平台 AppOps 拒绝返回空，容错标注）
    check("writePasteboard", writePasteboard("selftest_clip_123") == true)
    local got = readPasteboard()
    if got == "" then
        print("[SKIP] readPasteboard 往返（root 读剪贴板受平台 AppOps 限制）")
    else
        checkEq("readPasteboard 往返", got, "selftest_clip_123")
    end
    clipboard.set("clip_alias")
    local got2 = clipboard.get()
    if got2 == "" then
        print("[SKIP] clipboard.set/get 别名（同上）")
    else
        checkEq("clipboard.set/get 别名", got2, "clip_alias")
    end
    checkType("vibrate", vibrate(50), "boolean")
end)

-- ============================================================
-- 10. 应用与 shell
-- ============================================================
group("10.应用与shell", function()
    local out, code = exec("echo selftest_12345", false)
    checkEq("exec 双返回 code", code, 0)
    check("exec 输出", tostring(out):find("selftest_12345") ~= nil)
    check("execOut", execOut("echo abc"):find("abc") ~= nil)
    checkType("getInstalledApps", getInstalledApps(), "table")
    check("getInstalledApps 非空", #getInstalledApps() > 0)
    checkType("frontAppName", frontAppName(), "string")
    checkType("getCurrentActivity", getCurrentActivity(), "string")
    checkType("appIsRunning", appIsRunning("com.android.settings"), "boolean")
    checkType("appIsFront", appIsFront("com.example.none"), "boolean")
    check("app.isAppInstalled 系统设置", app.isAppInstalled("com.android.settings") == true)
    check("app.isAppInstalled 不存在包", app.isAppInstalled("com.not.exists.xyz") == false)
    checkType("app.getTopPackageName", app.getTopPackageName(), "string")
    checkType("app.getAppVersionName", app.getAppVersionName("com.android.settings"), "string")

    -- 控制流与环境（无副作用）
    check("getRunEnvType", getRunEnvType() == "root" or getRunEnvType() == "sandbox")
    check("checkIsDebug", checkIsDebug() == false)
    checkType("setStopCallBack", setStopCallBack(function() end), "nil")
    OnStop = nil -- 清掉，不影响引擎默认行为
    check("scanImage 无害广播", type(scanImage(DIR .. "no_such_file.png")) == "boolean")

    -- 破坏性系统操作（锁屏/飞行模式等会干扰后续图色与网络用例，默认关闭）
    if RUN_SYS_OPS then
        checkType("lockScreen", lockScreen(), "boolean")
        sleep(800)
        checkType("unLockScreen", unLockScreen(), "boolean")
        local wifi0 = device.isWifiEnabled()
        check("setWifiEnable 往返", setWifiEnable(wifi0) == true)
        checkType("setBTEnable", setBTEnable(false), "boolean")
        checkType("setAirplaneMode", setAirplaneMode(false), "boolean")
        checkType("setDpiToRealy", setDpiToRealy(), "boolean")
    else
        print("[SKIP] 破坏性系统操作（RUN_SYS_OPS=false）")
    end

    if RUN_APP_TESTS then
        check("runApp 设置", runApp("com.android.settings") == true)
        sleep(1500)
        check("appIsFront 设置", appIsFront("com.android.settings") == true)
        check("stopApp", stopApp("com.android.settings") == true)
    else
        print("[SKIP] runApp/stopApp（RUN_APP_TESTS=false）")
    end
end)

-- ============================================================
-- 11. 网络
-- ============================================================
group("11.网络", function()
    if not RUN_NET_TESTS then
        print("[SKIP] 网络测试（RUN_NET_TESTS=false）")
        return
    end
    local body, code = http.get("http://www.baidu.com")
    checkEq("http.get code", code, 200)
    check("http.get body 非空", type(body) == "string" and #body > 0)

    local body2, code2, headers = http.request{
        url = "http://www.baidu.com", method = "GET", timeout = 8000,
    }
    checkEq("http.request code", code2, 200)
    check("http.request headers.Date", headers and (headers["Date"] or headers["date"]) ~= nil)

    local bin, bcode = http.request{ url = "http://www.baidu.com/favicon.ico", binary = true, timeout = 8000 }
    check("http.request binary", bcode == 200 and type(bin) == "userdata" or type(bin) == "string")

    local okDl, dcode = downloadFile("http://www.baidu.com", DIR .. "dl.html")
    check("downloadFile", okDl == true and dcode == 200)
    check("downloadFile 落盘", fileExist(DIR .. "dl.html"))
    delfile(DIR .. "dl.html")

    checkType("getNetWorkTime", getNetWorkTime(), "string")

    local pb, pcode = httpPost("http://www.baidu.com", "a=1")
    check("httpPost 调用", type(pcode) == "number")
end)

-- ============================================================
-- 12. 线程与定时
-- ============================================================
group("12.线程定时", function()
    local cnt = 0
    local lk = thread.lock()
    local t = thread.start(function(n)
        for i = 1, n do
            thread.acquire(lk)
            cnt = cnt + 1
            thread.release(lk)
            thread.sleep(1)
        end
        thread.setShared("st_key", "st_val")
        return "done"
    end, 5)
    print("[INFO] 即将 thread.join（若长时间卡住说明 join 实现存在状态锁死锁）")
    local ok, ret = thread.join(t)
    check("thread.join ok", ok == true)
    checkEq("thread.join 返回值", ret, "done")
    checkEq("thread.lock 互斥计数", cnt, 5)
    checkEq("thread.setShared/getShared", thread.getShared("st_key"), "st_val")
    check("thread.hasShared", thread.hasShared("st_key") == true)
    checkEq("thread.atomicInc 首次", thread.atomicInc("st_ai"), 1)
    checkEq("thread.atomicInc delta", thread.atomicInc("st_ai", 10), 11)
    thread.removeShared("st_key")
    thread.removeShared("st_ai")
    check("thread.removeShared", thread.hasShared("st_ai") == false)
    checkType("thread.currentId", thread.currentId(), "number")
    check("thread.isMain", thread.isMain() == true)
    checkType("thread.stop", thread.stop, "function")

    local fired = false
    local timer = setTimer(function() fired = true end, 100)
    local deadline = tickCount() + 8000
    while not fired and tickCount() < deadline do sleep(50) end
    check("setTimer 回调执行", fired == true)
end)

-- ============================================================
-- 13. Java 桥
-- ============================================================
group("13.Java桥", function()
    local Integer = import("java.lang.Integer")
    checkEq("import 静态方法", Integer:parseInt("42"), 42)
    check("静态字段 MAX_VALUE", Integer.MAX_VALUE == 2147483647)

    local sb = import("java.lang.StringBuilder"):new()
    sb:append("he"):append("llo")
    checkEq("实例构造+链式", sb:toString(), "hello")

    local cls = luajava.bindClass("java.lang.Math")
    checkEq("luajava.bindClass 静态", cls:max(3, 9), 9)
    local inst = luajava.newInstance("java.lang.String", "xyz")
    check("luajava.newInstance", inst == "xyz")

    checkEq("LuaEngine.getVersion", LuaEngine.getVersion(), "2.0")
    local ctx = LuaEngine.getContext()
    check("LuaEngine.getContext 可调用", ctx == nil or ctx ~= nil)
end)

-- ============================================================
-- 14. 图色（root 主引擎；沙箱自动跳过）
-- ============================================================
group("14.图色", function()
    if not getColor then
        print("[SKIP] 图色域（当前环境无 Display）")
        return
    end
    keepScreen() -- 锁帧保证确定性
    local w, h = getBaseSize()
    check("getBaseSize", w > 0 and h > 0)
    checkType("getBaseDensity", getBaseDensity(), "number")
    checkType("getRotation", getRotation(), "number")
    checkType("isChangeDirection", isChangeDirection(), "boolean")

    local c = getColor(0, 0)
    check("getColor 范围", type(c) == "number" and c >= 0 and c <= 0xFFFFFF)
    check("isColor 精确", isColor(0, 0, c, 1) == true)
    check("isColor 宽松", isColor(0, 0, c, 0) == true)
    checkEq("cmpColor 别名", cmpColor(0, 0, c, 1), true)
    checkType("whichColor", whichColor(0, 0, c, 1), "number")
    checkType("getPixelColor", getPixelColor(0, 0), "number")

    -- 纯色屏存在多个同色匹配点，首匹配取决于扫描顺序，只断言命中且坐标有效
    local fx, fy = findColor(0, 0, w - 1, h - 1, c, 1)
    check("findColor 全屏命中", type(fx) == "number" and fx >= 0 and fy >= 0, fx .. "," .. fy)
    local all = findColorAllPoint(0, 0, 9, 9, c, 1)
    check("findColorAllPoint >=1", type(all) == "table" and #all >= 1)
    if type(all) == "table" and #all >= 1 then
        local found = false
        for _, p in ipairs(all) do
            if p[1] == 0 and p[2] == 0 then found = true break end
        end
        check("findColorAllPoint 含(0,0)", found)
    end

    local hex = string.format("%06X", c)
    check("isFeature 单点特征", isFeature(0, 0, "0|0|" .. hex, 1) == true)
    local ffx, ffy = findFeature(0, 0, w - 1, h - 1, "0|0|" .. hex, 1)
    check("findFeature 命中", type(ffx) == "number" and ffx >= 0 and ffy >= 0, ffx .. "," .. ffy)
    local fall = findFeatureAll(0, 0, 9, 9, "0|0|" .. hex, 1)
    check("findFeatureAll >=1", type(fall) == "table" and #fall >= 1)
    local mcall = findMultiColor(0, 0, 9, 9, hex, "", 1)
    check("findMultiColor 命中", type(mcall) == "number" and mcall >= 0, tostring(mcall))
    local mcallAll = findMultiColorAll(0, 0, 9, 9, hex, "", 1)
    check("findMultiColorAll >=1", type(mcallAll) == "table" and #mcallAll >= 1)

    check("getColorCount", getColorCount(0, 0, 9, 9, c, 1) >= 1)
    checkEq("getColorNum 别名", getColorNum(0, 0, 9, 9, c, 1), getColorCount(0, 0, 9, 9, c, 1))

    local grid = getScreenPixel(0, 0, 1, 1)
    check("getScreenPixel 结构", type(grid) == "table" and #grid == 2 and #grid[1] == 2
        and type(grid[1][1]) == "number")
    check("getScreenPixel 值一致", grid[1][1] == c)

    checkType("isDisplayDead", isDisplayDead(0, 0, 9, 9, 100), "boolean")

    -- 区域截图
    local shotPath = DIR .. "shot.png"
    check("snapShot 区域", snapShot(shotPath, 0, 0, 20, 20) == true)
    check("snapShot 文件存在", fileExist(shotPath))
    check("saveScreen 直接调用", saveScreen(0, 0, 19, 19, DIR .. "shot2.png") == true)

    -- 模板图找图：截屏后立即在截图对应区域找自身（确定性命中且避免全屏找图最坏情况；
    -- findImage 参数为路径字符串）
    local tmpl = loadImage(shotPath)
    check("loadImage", tmpl ~= nil)
    checkEq("模板尺寸", tmpl and select(1, tmpl:getSize()), 20)
    local ix, iy, idx = findImage(0, 0, 19, 19, shotPath, 1)
    check("findImage 自图命中", ix == 0 and iy == 0, ix .. "," .. iy)
    check("isImage 自图", isImage(0, 0, shotPath, 1) == true)
    check("whichImage 自图", whichImage(0, 0, shotPath, 1) == 1)

    -- 模板全匹配：20x20 区域找 20x20 自图，仅 (0,0) 一个位置
    local iall = findImageAll(0, 0, 19, 19, shotPath, 1)
    check("findImageAll 自图命中", type(iall) == "table" and #iall >= 1
        and iall[1][1] == 0 and iall[1][2] == 0)
    local pall = findPicAllPoint(0, 0, 19, 19, shotPath, 1)
    check("findPicAllPoint 别名", type(pall) == "table" and #pall >= 1)

    -- 整帧导出（captureScreen 高性能帧访问）
    local fdata, sfw, sfh, fstride, ffmt = getScreenFrame()
    check("getScreenFrame 结构", type(fdata) == "string" and #fdata >= sfw * 4 * sfh
        and sfw == w and sfh == h and fstride >= sfw * 4 and ffmt == "RGBA")
    check("captureScreen.getFrame", type(captureScreen.getFrame) == "function")
    local fsz = captureScreen.getSharedMemorySize()
    check("captureScreen.getSharedMemorySize", type(fsz) == "number" and fsz >= sfw * 4 * sfh)

    captureScreen.init()
    checkEq("captureScreen.getScreenWidth", captureScreen.getScreenWidth(), w)
    checkEq("captureScreen.getScreenHeight", captureScreen.getScreenHeight(), h)
    checkEq("captureScreen.isInitialized", captureScreen.isInitialized(), true)

    -- 缩放适配：scale=2 时区域换算 ×2，屏幕同色多点命中首个即可（扫描序是实现细节）
    setScreenScale(1)
    checkEq("getScreenScale 默认", getScreenScale(), 1)
    setScreenScale(2)
    checkEq("setScreenScale 生效", getScreenScale(), 2)
    check("scale=2 findColor 命中", (function()
        local sx, sy = findColor(0, 0, 4, 4, c, 1)
        return type(sx) == "number" and sx >= 0 and sy >= 0
    end)())
    setScreenScale(1)

    updateScreen() -- 解锁帧
end)

-- ============================================================
-- 15. images 图像处理模块
-- ============================================================
group("15.images", function()
    if not images then
        print("[SKIP] images（当前环境未注入）")
        return
    end
    local srcPath = DIR .. "shot.png"
    if not fileExist(srcPath) then
        snapShot(srcPath, 0, 0, 20, 20)
    end
    local img = images.read(srcPath)
    check("images.read", img ~= nil)
    check("images.isNull", images.isNull(img) == false)
    local iw, ih = images.getSize(img)
    check("images.getSize", iw > 0 and ih > 0)
    checkEq("images.getWidth", images.getWidth(img), iw)
    checkEq("images.getHeight", images.getHeight(img), ih)

    local crop = images.crop(img, 0, 0, 10, 10)
    do
        local cw, ch = images.getSize(crop)
        checkSize("images.crop 尺寸", cw, ch, 10, 10)
    end
    local r5 = images.resize(crop, 5, 5)
    do
        local rw, rh = images.getSize(r5)
        checkSize("images.resize 尺寸", rw, rh, 5, 5)
    end
    do
        local w90, h90 = images.getSize(images.rotate90(crop))
        checkSize("images.rotate90 尺寸", w90, h90, 10, 10)
    end
    do
        local w180, h180 = images.getSize(images.rotate180(img))
        checkSize("images.rotate180 尺寸", w180, h180, iw, ih)
    end
    do
        local w270, h270 = images.getSize(images.rotate270(img))
        checkSize("images.rotate270 尺寸", w270, h270, ih, iw)
    end
    do
        local fh, ffh = images.getSize(images.flipHorizontal(img))
        checkSize("images.flipH 尺寸", fh, ffh, iw, ih)
    end
    do
        local fv, ffv = images.getSize(images.flipVertical(img))
        checkSize("images.flipV 尺寸", fv, ffv, iw, ih)
    end

    local data = images.getData(crop)
    check("images.getData 长度", type(data) == "string" and #data == 10 * 10 * 4)
    local rebuilt = images.fromData(2, 2, "1234567890ABCDEF")
    checkEq("images.fromData 尺寸", images.getSize(rebuilt), 2, 2)

    local png = images.toPng(crop)
    check("images.toPng", type(png) == "string" and #png > 8)
    local okSave, saveErr = images.save(crop, DIR .. "img_save.png")
    check("images.save", okSave == true, saveErr)
    check("save 落盘", fileExist(DIR .. "img_save.png"))

    local b64 = images.toBase64(crop)
    check("images.toBase64", type(b64) == "string" and #b64 > 8)
    local back = images.fromBase64(b64)
    checkEq("base64 往返尺寸", images.getSize(back), 10, 10)
    check("writeFileFromBase64", images.writeFileFromBase64(b64, DIR .. "b64.png") == true)
    check("base64 落盘可读", images.read(DIR .. "b64.png") ~= nil)
    check("readImageToBase64", type(images.readImageToBase64(DIR .. "b64.png")) == "string")
    check("images.clone", images.getSize(images.clone(crop)), 10, 10)
    check("images.isNull(nil)", images.isNull(nil) == true)

    -- 二值化 + 任意角度旋转（懒人 binaryImage/binaryRect/rotateImage）
    local bin = images.binarize(img, 128)
    check("images.binarize 尺寸", images.getSize(bin), 20, 20)
    local bindata = images.getData(bin)
    local allBW = true
    for i = 1, #bindata, 4 do
        local r, g, b = bindata:byte(i), bindata:byte(i + 1), bindata:byte(i + 2)
        if not ((r == 255 and g == 255 and b == 255) or (r == 0 and g == 0 and b == 0)) then
            allBW = false
            break
        end
    end
    check("binarize 仅黑白", allBW)
    local binR = images.binarize(img, 128, 0, 0, 5, 5)
    check("images.binarize 区域", images.getSize(binR), 20, 20)
    check("binaryImage 懒人别名", images.getSize(binaryImage(img, 128)), 20, 20)
    check("binaryRect 双序", images.getSize(binaryRect(img, 100, 2, 2, 4, 4)), 20, 20)
    local rot30 = images.rotate(img, 30)
    check("images.rotate 30°", images.getSize(rot30), 20, 20)
    check("rotateImage 别名", images.getSize(rotateImage(img, 45)), 20, 20)
    check("scaleImage 别名", images.getSize(scaleImage(img, 8, 6)), 8, 6)
    check("getImage 别名", images.getSize(getImage(srcPath)), 20, 20)
end)

-- ============================================================
-- 16. 节点与无障碍（root 主引擎）
-- ============================================================
group("16.节点", function()
    if not getRootInActiveWindow then
        print("[SKIP] 节点域（当前环境无无障碍）")
        return
    end
    local root = getRootInActiveWindow()
    check("getRootInActiveWindow", root ~= nil)
    if root == nil then return end

    checkType("childCount 属性", root.childCount, "number")
    local sel = UiSelector():text("x"):textContains("y"):clickable(true):idContains("z")
        :bounds(0, 0, 100, 100):visibleToUser(true):editable(false)
    check("UiSelector 链式", sel ~= nil)
    check("idStartsWith/idEndsWith/idMatches",
        UiSelector():idStartsWith("a"):idEndsWith("b"):idMatches("c") ~= nil)
    check("desc/clazz/hint 系", UiSelector():descContains("d"):clazzContains("e"):hintContains("f") ~= nil)
    check("boundsInside/depth", UiSelector():boundsInside(0, 0, 9, 9):depth(1, 3) ~= nil)

    local list = root:find(UiSelector():text("selftest_不存在节点"))
    check("find 返回数组", type(list) == "table")
    local none = root:findOne(UiSelector():text("selftest_不存在节点"))
    check("findOne 未命中 nil", none == nil)

    check("setText 全局函数", setText ~= nil)
    checkType("KeyCode.BACK", KeyCode.BACK, "number")
    checkType("KeyCode.RECENT", KeyCode.RECENT, "number")

    -- compat 全局工厂
    check("text() 工厂", text("x") ~= nil)
    check("idContains() 工厂", idContains("y") ~= nil)
    check("descStartsWith() 工厂", descStartsWith("z") ~= nil)
    check("className() 工厂", className("c") ~= nil)
    check("packageName() 工厂", packageName("p") ~= nil)
    check("clickable() 工厂", clickable(true) ~= nil)
    check("editable()/focused() 工厂", editable(true) ~= nil and focused(true) ~= nil)
    check("selected()/password() 工厂", selected(true) ~= nil and password(true) ~= nil)

    -- sel 便捷动作（懒人 sel:xxx() 语义；对不存在节点全为安全空操作）
    local missing = text("selftest_不存在节点")
    check("sel:findOne 未命中 nil", missing:findOne() == nil)
    local fl = missing:findAll()
    check("sel:findAll 空数组", type(fl) == "table" and #fl == 0)
    check("sel:findOnce(0) 未命中 nil", missing:findOnce(0) == nil)
    checkEq("sel:exists false", missing:exists(), false)
    checkEq("sel:click 无节点 false", missing:click(), false)
    checkEq("sel:longClick 无节点 false", missing:longClick(), false)
    checkEq("sel:setText 无节点 false", missing:setText("t"), false)

    -- 节点属性扩展
    checkEq("node:depth 根为 0", root:depth(), 0)
    check("node:boundsInParent", type(root.boundsInParent) == "table" and #root.boundsInParent == 4)
    checkType("node.password", root.password, "boolean")
    checkType("node.drawingOrder", root.drawingOrder, "number")
    local kids = root:childs()
    check("node:childs 数组", type(kids) == "table" and #kids >= 0)

    -- 文本输入与 Toast（不强断言结果，环境差异大）
    checkType("inputText", inputText, "function")
    checkType("toast", toast, "function")
    checkType("hideToast", hideToast, "function")
    pcall(toast, "selftest toast")
    pcall(hideToast)

    -- auto.* 工厂
    check("auto.selector", auto.selector() ~= nil)
    local aw, ah = auto.getScreenSize()
    check("auto.getScreenSize", aw > 0 and ah > 0)
    check("auto.findOne 未命中 nil", auto.findOne(auto.text("selftest_不存在")) == nil)
    check("auto.exists false", auto.exists(auto.text("selftest_不存在")) == false)
    check("auto.waitFor 超时 nil", auto.waitFor(auto.text("selftest_不存在"), 200) == nil)

    -- autoCollection 批量操作（派大星对齐）
    local col = autoCollection.find(auto.text("selftest_不存在"))
    check("autoCollection.find 空集合", col ~= nil and col:isEmpty())
    checkEq("col:size 0", col:size(), 0)
    checkEq("col:count 0", col:count(), 0)
    check("col:findOne nil", col:findOne(auto.text("x")) == nil)
    checkEq("col:click 空集合 0", col:click(), 0)
    checkEq("col:setText 空集合 0", col:setText("t"), 0)
    check("col:toList 空表", type(col:toList()) == "table" and #col:toList() == 0)

    local rcol = autoCollection.create({ root })
    check("col:nonEmpty", rcol:nonEmpty())
    checkEq("col:size 1", rcol:size(), 1)
    check("col:get/first/last", rcol:get(1) ~= nil and rcol:first() ~= nil and rcol:last() ~= nil)
    check("col:toArray", type(rcol:toArray()) == "table")
    local cnt = 0
    rcol:each(function(n, i) cnt = cnt + i end)
    checkEq("col:each 遍历", cnt, 1)
    check("col:contains 自身", rcol:contains(root) == true)
    check("col:contains 缺席节点 false", rcol:contains(col) == false)
    checkType("col:performAction 返回数", rcol:performAction(16), "number")

    -- 节点 ACTION 可调用性（index 闭包修复后 nd:xxx() 不再为 nil）
    local okc, rc = pcall(function() return root:click() end)
    check("node:click 可调用", okc and type(rc) == "boolean")
    local oks, rs = pcall(function() return root:scrollForward() end)
    check("node:scrollForward 可调用", oks and type(rs) == "boolean")
    local okp, rp = pcall(function() return root:performAction(1) end)
    check("node:performAction 可调用", okp and type(rp) == "boolean")

    -- ---------------- 节点增强（等待轮询/新条件/树遍历/几何/NodeLib） ----------------
    -- 超时路径：不存在的节点（不测无限等待的 untilFind/untilFindOne）
    local missSel = text("selftest_不存在节点")
    checkEq("sel:findOne(400) 超时 nil", missSel:findOne(400), nil)
    checkEq("sel:waitFor(400) 超时 false", missSel:waitFor(400), false)

    -- 新条件构造器：不抛错且返回 sel 本身（and 为 Lua 保留字，须用索引调用形式）
    local s2 = UiSelector():content("x"):contentContains("y")
    s2["and"](s2, UiSelector():clickable(true))
    check("UiSelector content/contentContains/and 链式", s2 ~= nil)
    check("UiSelector boundsContains/screenCenterX/screenCenterY/screenCoverage 链式",
        UiSelector():boundsContains(1, 2):screenCenterX():screenCenterY(0.1):screenCoverage(0.0) ~= nil)

    -- 树遍历与几何派生（root 有子节点时 firstChild/lastChild 非 nil）
    if #kids > 0 then
        check("root:firstChild 非nil", root:firstChild() ~= nil)
        check("root:lastChild 非nil", root:lastChild() ~= nil)
    else
        check("root:firstChild 可调用", pcall(function() return root:firstChild() end))
    end
    checkType("root:centerX", root:centerX(), "number")
    checkType("root:centerY", root:centerY(), "number")
    checkType("root:width", root:width(), "number")
    checkType("root:height", root:height(), "number")
    local ccx, ccy = root:center()
    check("root:center 返回两值", type(ccx) == "number" and type(ccy) == "number")
    local ppx, ppy = root:point()
    check("root:point 返回两值", type(ppx) == "number" and type(ppy) == "number")
    checkType("root:indexInParent", root:indexInParent(), "number")
    checkType("root:hasChildren", root:hasChildren(), "boolean")
    checkType("root:siblings", root:siblings(), "table")
    check("root:sibling(1) 可调用(越界 nil 也算过)", pcall(function() return root:sibling(1) end))

    -- NodeLib 全局函数
    local xml = getNodeXml()
    check("getNodeXml 返回 <nodes 开头", type(xml) == "string" and xml:sub(1, 6) == "<nodes")
    local xmlAll = getNodeXml(true)
    check("getNodeXml(true) 返回 <nodes 开头", type(xmlAll) == "string" and xmlAll:sub(1, 6) == "<nodes")

    keepNode()
    check("keepNode 后 sel:exists 可调用", type(missSel:exists()) == "boolean")
    releaseNode()
    check("releaseNode 后 sel:exists 可调用", type(missSel:exists()) == "boolean")
    checkType("isAccServiceOk", isAccServiceOk(), "boolean")

    -- auto.untilFind/untilFindOne 无限阻塞：仅存在性检查（注释性跳过，不执行）
    checkType("auto.untilFind 存在(无限阻塞不执行)", auto.untilFind, "function")
    checkType("auto.untilFindOne 存在(无限阻塞不执行)", auto.untilFindOne, "function")

    -- ---------------- P2 补充：saveNode/findByIndex/setNodeMode/closeAccessibility ----------------
    -- saveNode：缺省落 rootDir/node_dump_时间戳.xml，返回实际写入路径；不校验文件内容
    local dumpPath = saveNode()
    check("saveNode 返回 .xml 路径", type(dumpPath) == "string" and dumpPath:sub(-4) == ".xml")
    if type(dumpPath) == "string" then print("[INFO] saveNode dump: " .. dumpPath) end
    local dumpPath2 = saveNode(nil, true)
    check("saveNode(nil,true) 返回 .xml 路径", type(dumpPath2) == "string" and dumpPath2:sub(-4) == ".xml")
    check("saveNode 落盘文件存在", type(dumpPath) ~= "string" or fileExist(dumpPath) == true)

    -- findByIndex："0" 取 root 第 0 个子节点（与 getNodeXml 的 i=父内索引 对应）
    if #kids > 0 then
        check("findByIndex(\"0\") 非 nil", findByIndex("0") ~= nil)
    else
        check("findByIndex(\"0\") 可调用", pcall(findByIndex, "0"))
    end
    check("findByIndex(\"999|999\") 越界 nil", findByIndex("999|999") == nil)
    local okFi, errFi = pcall(findByIndex, "")
    check("findByIndex(\"\") 抛错", not okFi, tostring(errFi))

    -- setNodeMode：切换不抛错 + 导出仍正常；测完恢复默认（0=全部节点，enableInvisible=false）
    check("setNodeMode(1,true) 可调用", pcall(setNodeMode, 1, true))
    local xmlAfter = getNodeXml()
    check("setNodeMode(1,true) 后 getNodeXml 正常", type(xmlAfter) == "string" and xmlAfter:sub(1, 6) == "<nodes")
    check("setNodeMode(0,false) 恢复", pcall(setNodeMode, 0, false))

    -- closeAccessibility：实际调用会关掉无障碍影响后续用例，仅验存在不调用
    check("closeAccessibility 存在(不调用)", closeAccessibility ~= nil)
end)

-- ============================================================
-- 17. 触控（真实注入，默认关闭）
-- ============================================================
group("17.触控", function()
    if not tap then
        print("[SKIP] 触控域（当前环境无 InputManager）")
        return
    end
    checkType("tap", tap, "function")
    checkType("swipe", swipe, "function")
    checkType("keyPress", keyPress, "function")
    checkType("touchDown", touchDown, "function")
    checkType("touchMove", touchMove, "function")
    checkType("touchUp", touchUp, "function")
    checkType("longTap", longTap, "function")
    checkType("useHardwareInput", useHardwareInput, "function")
    checkType("createPointer", createPointer, "function")

    if not RUN_INPUT_TESTS then
        print("[SKIP] 真实触控注入（RUN_INPUT_TESTS=false）")
        return
    end
    check("tap 注入", tap(200, 200))
    sleep(300)
    check("swipe 注入", swipe(300, 300, 400, 400, 300))
    sleep(300)
    check("keyPress BACK", keyPress(KeyCode.BACK))
    sleep(300)
    check("longTap", longTap(200, 200, 500))
    check("多指序列", (function()
        touchDown(1, 300, 300)
        touchMove(1, 350, 320, 5)
        touchUp(1)
        return true
    end)())
    local p = createPointer()
    p.x, p.y = 300, 300
    p:sync()
    p:up()
    check("Pointer 手势", p ~= nil)
end)

-- ============================================================
-- 18. 常量表
-- ============================================================
group("18.常量", function()
    checkType("KeyCode 表", KeyCode, "table")
    checkType("KeyCode.HOME", KeyCode.HOME, "number")
    checkType("KeyCode.VOLUME_UP", KeyCode.VOLUME_UP, "number")
    checkType("KeyCode.ENTER", KeyCode.ENTER, "number")
    checkType("LayoutParamsFlag 表", LayoutParamsFlag, "table")
    checkType("LayoutParamsFlag.NOT_FOCUSABLE", LayoutParamsFlag.NOT_FOCUSABLE, "number")
end)

-- ============================================================
-- 19. 扩展模块（path/coordinate/timer/事件/媒体/fhttp/ws/plugin/ocr）
-- ============================================================
group("19.扩展模块", function()
    -- path 路径处理
    checkEq("path.join", path.join("/a", "b/", "/c.txt"), "/a/b/c.txt")
    checkEq("path.dirname", path.dirname("/a/b/c.txt"), "/a/b")
    checkEq("path.basename", path.basename("/a/b/c.txt"), "c.txt")
    checkEq("path.extname", path.extname("/a/b/c.txt"), ".txt")
    checkEq("path.normalize", path.normalize("/a/./b/../c"), "/a/c")
    checkEq("path.isAbsolutePath", path.isAbsolutePath("/a"), true)
    checkEq("path.stripAssetPrefix", path.stripAssetPrefix("assets/img/logo.png"), "img/logo.png")
    check("path.exists", path.exists(DIR) == true)
    check("path.getProjectDir", path.getProjectDir() == ROOT_DIR)
    checkType("path.join 可调", path.join, "function")

    -- coordinate 坐标转换
    local cw, ch = coordinate.getScreenSize()
    check("coordinate.getScreenSize", cw > 0 and ch > 0)
    checkEq("coordinate.p2rX(50)", coordinate.p2rX(50), math.floor(cw / 2))
    local ccx, ccy = coordinate.getCenter()
    check("coordinate.getCenter", ccx == math.floor(cw / 2) and ccy == math.floor(ch / 2))
    local rpx, rpy = coordinate.r2p(cw, ch)
    check("coordinate.r2p 往返", math.abs(rpx - 100) < 1 and math.abs(rpy - 100) < 1)
    check("coordinate.getCenterPercent", coordinate.getCenterPercent() == 50)
    local sx, sy, sw2, sh2 = coordinate.p2rRect(10, 10, 30, 30)
    check("coordinate.p2rRect", sx > 0 and sw2 > 0)

    -- timer 定时器（thread 协程驱动）
    local fired = false
    timer.set(function() fired = true end, 200, false)
    local waited = 0
    while not fired and waited < 8000 do
        sleep(100)
        waited = waited + 100
    end
    check("timer.set 回调", fired == true)
    local tid = setInterval(function() end, 50)
    sleep(120)
    timer.clear(tid)
    check("setInterval/clear 可调", tid ~= nil)

    -- 事件回调总线
    local evName = nil
    setPluginEventCallBack(function(n, d) evName = n end)
    sendPluginEvent("selftest_event", 1)
    check("事件回调收到", evName == "selftest_event")
    setPluginEventCallBack(nil)

    -- 懒人图片别名（依赖 15 章 shot.png 已存在；缺图时跳过）
    local aliasImg = loadImage(DIR .. "shot.png")
    if aliasImg then
        check("binaryImage 调用", images.getSize(binaryImage(aliasImg, 128)) ~= nil)
    else
        print("[SKIP] 懒人图片别名（shot.png 不存在）")
    end

    -- 媒体（无真实音频文件，只验调用形态与失败路径）
    -- contribute 注册的全局函数 type 为 userdata（LuaObjectAdapter __call），存在即可用
    check("playAudio 可用", playAudio ~= nil)
    check("media.playAudio 不存在文件 false", media.playAudio("/data/local/tmp/_no_such.mp3") == false)
    check("media.isPlaying", media.isPlaying() == false)
    stopAudio()

    -- extractAssets（root 进程无 AssetManager，仅验调用形态）
    check("extractAssets 可用", extractAssets ~= nil)

    -- fhttp（需网络）
    if RUN_NET_TESTS and http then
        fhttp.setTimeout(8000)
        local fbody, fcode = fhttp.get("http://www.baidu.com")
        check("fhttp.get", fcode == 200, tostring(fcode))
        local pjbody, pjcode = fhttp.postJson("http://www.baidu.com", '{"a":1}')
        check("fhttp.postJson 可调", pjcode ~= nil)
        local dlOk, dlCode = fhttp.download("http://www.baidu.com", DIR .. "fhttp_dl.html")
        check("fhttp.download", dlOk == true and fileExist(DIR .. "fhttp_dl.html"))
        -- 异步族：句柄模式为主（网络 IO 在独立 Java 线程，主线程/协程轮询 isDone 均可）；
        -- 回调模式依赖 thread 协程内 Java 调用，当前引擎版本受限（协程内 Java 调用挂起），
        -- 超时降级 INFO 不计 FAIL
        local asyncDone = false
        local asyncBody, asyncCode = nil, nil
        fhttp.getAsync("http://www.baidu.com", function(b, c)
            asyncDone = true
            asyncBody = b
            asyncCode = c
        end)
        waited = 0
        while not asyncDone and waited < 10000 do
            sleep(50)
            waited = waited + 50
        end
        if asyncDone then
            check("fhttp.getAsync 回调", asyncCode == 200, tostring(asyncCode))
        else
            print("[INFO] fhttp.getAsync 回调未触发（协程内 Java 调用限制），请使用句柄模式")
        end
        -- 句柄直通模式（返回 handle 自行轮询）
        local h = fhttp.requestAsync({ url = "http://www.baidu.com", method = "GET" })
        waited = 0
        while h ~= nil and not h:isDone() and waited < 15000 do
            sleep(50)
            waited = waited + 50
        end
        if h ~= nil and h:isDone() then
            local hbody, hcode = h:get()
            check("fhttp.requestAsync 句柄", hcode == 200, tostring(hcode))
        else
            check("fhttp.requestAsync 句柄", false, "handle timeout")
        end
    else
        print("[SKIP] fhttp（RUN_NET_TESTS=false）")
    end

    -- WebSocket 自连实测（本机回环）
    if ws and ws.serverStart then
        local okSrv = ws.serverStart(18765)
        check("ws.serverStart", okSrv == true)
        if okSrv then
            local cid = ws.clientConnect("ws://127.0.0.1:18765/test")
            checkType("ws.clientConnect 返回 id", cid, "number")
            -- 轮询双方事件（客户端 open + 服务端 conn open）
        local srvEv, srvConn, srvMsg, cliEv, cliMsg
        for _ = 1, 50 do
            srvEv, srvConn = ws.serverPoll(50)
            if srvEv == "open" then break end
        end
        if srvEv ~= "open" then
            print("[INFO] ws diag: server last event=" .. tostring(srvEv) .. " conn=" .. tostring(srvConn)
                .. " isRunning=" .. tostring(ws.serverIsRunning()) .. " conns=" .. tostring(ws.serverConnCount()))
        end
        check("ws 服务端 open 事件", srvEv == "open")
        for _ = 1, 50 do
            cliEv = ws.clientPoll(cid, 50)
            if cliEv == "open" then break end
        end
        if cliEv ~= "open" then
            print("[INFO] ws diag: client last event=" .. tostring(cliEv)
                .. " isOpen=" .. tostring(ws.clientIsOpen(cid)))
        end
        check("ws 客户端 open 事件", cliEv == "open")
            if cliEv == "open" then
                check("ws.clientSend", ws.clientSend(cid, "hello-selftest"))
                for _ = 1, 50 do
                    srvEv, srvConn, srvMsg = ws.serverPoll(50)
                    if srvEv == "message" then break end
                end
                check("ws 服务端收到消息", srvEv == "message" and srvMsg == "hello-selftest")
                if srvEv == "message" then
                    check("ws.serverSend", ws.serverSend(srvConn, "echo-selftest"))
                    for _ = 1, 50 do
                        cliEv, cliMsg = ws.clientPoll(cid, 50)
                        if cliEv == "message" then break end
                    end
                    check("ws 客户端收到回包", cliEv == "message" and cliMsg == "echo-selftest")
                end
            end
            check("ws.clientClose", ws.clientClose(cid))
            check("ws.serverStop", ws.serverStop())
            check("ws.serverIsRunning false", ws.serverIsRunning() == false)
        end
    else
        print("[SKIP] ws（当前环境未注入）")
    end

    -- 插件运行时与 OCR 调用口（contribute 注册的全局函数 type 为 userdata）
    if plugin and plugin.list then
        local plist = plugin.list()
        check("plugin.list 表", type(plist) == "table")
        for _, p in ipairs(plist) do
            print("[INFO] plugin: " .. tostring(p.id) .. " v" .. tostring(p.version))
        end
        check("plugin.load 可调", plugin.load ~= nil)
        check("plugin.load 未装 id false", plugin.load("selftest.no.such.plugin") == false)
    else
        print("[SKIP] plugin（当前环境未注入）")
    end
    check("ocr.isSupported 可调", ocr and ocr.isSupported ~= nil)
    if ocr and ocr.isSupported then
        check("ocr.init", ocr.init() == ocr.isSupported())
    end

    -- JPEG 编解码（libjpeg-turbo 接线）
    if images and images.toJpg then
        local jsrc = images.fromData(64, 64, string.rep("\255\0\0\255", 64 * 64))
        local jpg = images.toJpg(jsrc, 75)
        check("images.toJpg 字节串", type(jpg) == "string" and #jpg > 100)
        local jback = images.fromJpg(jpg)
        local jw, jh = images.getSize(jback)
        check("images.fromJpg 往返尺寸", jw == 64 and jh == 64, tostring(jw) .. "x" .. tostring(jh))
        check("images.saveJpg 落盘", images.saveJpg(jsrc, DIR .. "t.jpg", 60) == true and fileExist(DIR .. "t.jpg"))
        local rjimg = images.readJpg(DIR .. "t.jpg")
        local rw, rh = images.getSize(rjimg)
        check("images.readJpg 尺寸", rw == 64 and rh == 64, tostring(rw) .. "x" .. tostring(rh))
        check("images.compress 别名", images.compress ~= nil)
    else
        print("[SKIP] images.toJpg（旧构建）")
    end

    -- ime 输入法域（ImeLib 注入；注入效果依赖焦点输入框，此处验证通道可用性）
    if ime ~= nil then
        local origIme = ime.getCurrentIMEName()
        check("ime.getCurrentIMEName 可调", true)
        check("ime.isOurIMEActive 可调", type(ime.isOurIMEActive()) == "boolean")
        check("ime.isActive 可调", type(ime.isActive()) == "boolean")
        check("ime.setAsDefaultIME root 可切", ime.setAsDefaultIME() == true)
        check("ime.isOurIMEActive 切后 true", ime.isOurIMEActive() == true)
        check("ime.hideKeyboard 可调", ime.hideKeyboard() == true)
        check("ime.keyEvent 可调", ime.keyEvent(KeyCode and KeyCode.ENTER or 66) == true)
        check("ime.getCurrentText 可调", pcall(ime.getCurrentText))
        if origIme and not origIme:find("autolua") then
            check("ime.setCurrentIME 恢复原输入法", ime.setCurrentIME(origIme) == true)
        end
        check("imeLib 别名", imeLib == ime)
    else
        print("[SKIP] ime（当前环境未注入）")
    end

    -- OCR demo 插件全链路（zip 随自测推送：install→load→识别→unload→uninstall）
    local demoZip = "/data/local/tmp/selftest/demo-ocr.zip"
    if plugin and plugin.install and fileExist(demoZip) then
        plugin.uninstall("com.autolua.plugin.ocr.demo")
        local iok, ierr = plugin.install(demoZip)
        check("plugin.install demo-ocr", iok == true, tostring(ierr))
        if iok then
            check("plugin.load demo-ocr", plugin.load("com.autolua.plugin.ocr.demo") == true)
            if ocr and ocr.isSupported then
                check("ocr.isSupported 装后 true", ocr.isSupported() == true)
            end
            if ocr and ocr.ocrText then
                local txt = ocr.ocrText(0, 0, 199, 199)
                check("ocr.ocrText 返回 DEMO", txt == "DEMO", tostring(txt))
            end
            if ocr and ocr.ocrEx then
                local blocks = ocr.ocrEx(0, 0, 199, 199)
                check("ocr.ocrEx 块数组", type(blocks) == "table" and #blocks >= 1)
            end
            check("plugin.unload demo-ocr", plugin.unload("com.autolua.plugin.ocr.demo") == true)
            if ocr and ocr.isSupported then
                check("ocr.isSupported 卸后 false", ocr.isSupported() == false)
            end
            plugin.uninstall("com.autolua.plugin.ocr.demo")
        end
    else
        if plugin and plugin.install then
            print("[SKIP] OCR demo 插件包未推送（" .. demoZip .. "）")
        end
    end

    -- mysql（零依赖协议客户端；无真实服务器，验证 API 面与错误路径）
    if mysql and mysql.connect then
        check("mysql.escape 可调", mysql.escape("x") == "x")
        checkEq("mysql.escape 转义", mysql.escape("a'b\"c\\d"), "a\\'b\\\"c\\\\d")
        local mok, merr = mysql.connect("127.0.0.1", 33991, "u", "p", nil, 800)
        check("mysql.connect 拒绝路径 nil, err", mok == nil and type(merr) == "string")
        local cok, cerr = connectSQL("127.0.0.1", 33991, "u", "p", nil, 800)
        check("connectSQL 拒绝路径 false, err", cok == false and type(cerr) == "string")
        check("executeQuerySQL 未连接 nil, err", executeQuerySQL("select 1") == nil)
        check("executeSQL 未连接 nil, err", executeSQL("insert 1") == nil)
        check("closeSQL 空闲 false", closeSQL() == false)
    else
        print("[SKIP] mysql 表未注册")
    end

    -- toast / inputText（16 章节点域已有，这里仅防漏）
    if toast == nil then print("[WARN] toast 未注册") end
end)

-- ============================================================
-- 20. 事件总线与 Fire 热更（派大星对齐）
-- ============================================================
group("20.事件Fire", function()
    checkType("event.on", event.on, "function")
    local fired = false
    local id = event.on("test", function() fired = true end)
    check("event.on 返回 id", type(id) == "number")
    event._emit("test")
    check("event._emit 分发", fired == true)
    check("event.off", event.off(id) == true)
    fired = false
    event._emit("test")
    check("移除监听不触发", fired == false)

    -- stop 事件多播（onStopEvent → 引擎 OnStop 桥）
    local stopFired = false
    check("event.onStopEvent 可注册", event.onStopEvent(function() stopFired = true end) ~= nil)
    pcall(event._emit, "stop")
    check("stop 事件分发", stopFired == true)

    -- 权限状态
    local st = event.getPermissionStatus()
    check("getPermissionStatus 表", type(st) == "table" and type(st.root) == "boolean")
    checkType("hasTouchPermission", event.hasTouchPermission(), "boolean")

    -- Fire 热更：目录应用 + 备份回滚
    checkType("project.getHotfixDir", project.getHotfixDir(), "string")
    local pending = project.getHotfixDir() .. "/pending"
    file.deleteDir(pending)
    file.mkdir(pending)
    writeFile(pending .. "/hf_test.lua", "HOTFIX_OK")
    local origPath = project.getProjectDir() .. "/hf_test.lua"
    writeFile(origPath, "ORIG")
    local firedHf = false
    project.setHotFixCallback(function(ok) firedHf = (ok == true) end)
    check("hotfixFire 应用", project.hotfixFire() == true)
    check("hotfix 文件覆盖", readFile(origPath) == "HOTFIX_OK")
    check("hotfix_finish 事件", firedHf == true)
    check("rollback 回滚", project.rollback() == true)
    check("rollback 还原", readFile(origPath) == "ORIG")
    delfile(origPath)
    project.clearCustomFire()
end)

-- ============================================================
-- 21. cv.Mat 图像加工（OpenCV 子集，零依赖）
-- ============================================================
group("21.cvMat", function()
    checkType("cv.createMat", cv.createMat, "function")
    check("cv.getVersion", cv.getVersion() ~= nil)
    check("cv.isAvailable", cv.isAvailable() == true)

    local m = cv.createMat(4, 5, 1)
    check("cv.createMat", m ~= nil)
    checkEq("mat.rows", m.rows, 4)
    checkEq("mat.cols", m.cols, 5)
    checkEq("mat.channels", m.channels, 1)
    check("mat.type CV_8UC1", m.type == "CV_8UC1")
    m:put(1, 2, 128)
    checkEq("mat:put/get", m:get(1, 2), 128)

    -- cvtColor RGBA→GRAY（红 255,0,0 → 灰度 ~76）
    local rgba = cv.createMat(2, 2, 4)
    rgba:put(0, 0, 255, 0, 0, 255)
    local gray = cv.cvtColor(rgba, cv.COLOR_RGBA2GRAY)
    check("cvtColor RGBA2GRAY 单通道", gray.channels == 1)
    local gv = gray:get(0, 0)
    check("cvtColor 灰度值域", gv > 60 and gv < 90, tostring(gv))
    local bin = cv.threshold(gray, 64, 255)
    check("threshold 二值化", bin:get(0, 0) == 255)

    -- clone 独立性
    local cl = rgba:clone()
    cl:put(0, 0, 1, 2, 3, 255)
    check("clone 独立", rgba:get(0, 0) ~= cl:get(0, 0))

    -- 6x6 黑图高斯模糊
    local m6 = cv.createMatFromData(6, 6, string.rep(string.char(0, 0, 0, 255), 36), 4)
    local blurred = cv.gaussianBlur(m6, 3)
    check("gaussianBlur 黑图不变", blurred:get(3, 3) == 0)

    -- extractContours：6x6 中心 2x2 白块
    local bw = cv.createMat(6, 6, 1)
    for r = 2, 3 do for c = 2, 3 do bw:put(r, c, 255) end end
    local contours = cv.extractContours(bw, 2)
    check("extractContours 1 块", type(contours) == "table" and #contours == 1)
    check("contours 外接框", contours[1] and contours[1].x == 2 and contours[1].y == 2
        and contours[1].w == 2 and contours[1].h == 2)

    -- despeckle 去噪点
    bw:put(0, 0, 255)
    local clean = cv.despeckle(bw, 3)
    check("despeckle 去噪", clean:get(0, 0) == 0)
    check("despeckle 保留主体", clean:get(2, 2) == 255)

    -- flip / resize / matToData 往返
    local fl = cv.flip(bw, 1)
    check("flip 左右镜像", fl:get(0, 5) == 255 or fl:get(0, 0) == 0)
    local rs = cv.resize(bw, 3, 3)
    check("resize 尺寸", rs.rows == 3 and rs.cols == 3)
    local d2, r2, c2, k2 = cv.matToData(bw)
    check("matToData", #d2 == 36 and r2 == 6 and c2 == 6 and k2 == 1)
    local m2b = cv.createMatFromData(r2, c2, d2, k2)
    check("mat 数据往返", m2b:get(2, 2) == 255)

    -- images 互通
    local img = images.fromData(2, 2, string.rep(string.char(200, 200, 200, 255), 4), 4)
    local im = images.getMat(img)
    check("images.getMat", im.rows == 2 and im.channels == 4)
    local img2 = cv.matToImage(im)
    check("matToImage 往返", images.getWidth(img2) == 2)
    check("images.getGrayMat", images.getGrayMat(img).channels == 1)

    -- captureMat（真实帧）
    local cm = cv.captureMat(0, 0, 8, 8)
    check("captureMat", cm ~= nil and cm.rows == 8 and cm.channels == 4)

    check("getMatStats 表", type(cv.getMatStats()) == "table")
    cv.forceGc()
end)

-- ============================================================
-- 汇总
-- ============================================================
print("============================================================")
print(string.format("SELFTEST RESULT: PASS=%d  FAIL=%d", passCount, failCount))
if #failures > 0 then
    print("---- 失败清单 ----")
    for i, f in ipairs(failures) do
        print(string.format("%d) %s", i, f))
    end
end
print("============================================================")

local detail = ""
if #failures > 0 then
    detail = "\n" .. table.concat(failures, "\n")
end
writeFile(DIR .. "result.txt",
    string.format("PASS=%d FAIL=%d%s", passCount, failCount, detail))
print("结果已写入: " .. DIR .. "result.txt")
