-- frameSdk-autolua2 静态逻辑测试：mock 引擎注入的全局函数/模块表，
-- 跑契约自检 + 图色/找字/触控/节点/兼容层关键命令，断言返回。
local passed, failed = 0, 0
local function check(name, cond, detail)
    if cond then passed = passed + 1; print(string.format("  [PASS] %s", name))
    else failed = failed + 1; print(string.format("  [FAIL] %s  %s", name, detail or "")) end
end

-- ==================== 工作目录 ====================
local WORK = "/tmp/jytest"
os.execute("mkdir -p " .. WORK .. "/images")
local fh = io.open(WORK .. "/images/test.png", "wb"); fh:write("PNGDATA"); fh:close()

-- ==================== 极简 JSON（够 encode/decode 用） ====================
local json = {}
function json.encode(v)
    local t = type(v)
    if t == "string" then return string.format("%q", v):gsub("^\"(.*)\"$", '"%1"')
    elseif t == "number" then return tostring(v)
    elseif t == "boolean" then return tostring(v)
    elseif t == "nil" then return "null"
    elseif t == "table" then
        -- 判断数组
        local isArr, n = true, 0
        for k in pairs(v) do n = n + 1; if type(k) ~= "number" then isArr = false end end
        if isArr and n > 0 then
            local parts = {}
            for i = 1, n do parts[i] = json.encode(v[i]) end
            return "[" .. table.concat(parts, ",") .. "]"
        else
            local parts = {}
            for k, val in pairs(v) do parts[#parts+1] = json.encode(tostring(k)) .. ":" .. json.encode(val) end
            return "{" .. table.concat(parts, ",") .. "}"
        end
    end
    return "null"
end
function json.decode(s) return { _raw = s } end

-- ==================== mock 引擎直注全局 ====================
local calls = {}
_G.getBaseSize = function() return 720, 1280 end
_G.systemTime  = function() return 1700000000000 end
_G.sleep       = function(ms) end
_G.keepScreen  = function() calls.keepScreen = (calls.keepScreen or 0) + 1 end
_G.updateScreen= function() calls.updateScreen = (calls.updateScreen or 0) + 1 end
_G.getScreenFrame = function() return "FRAME", 720, 1280, 2880 end
_G.saveScreen  = function(x1,y1,x2,y2,path) calls.saveScreen = {x1,y1,x2,y2,path} return true end
_G.getColor    = function(x,y) return "0x123456" end
_G.getColorCount = function(x1,y1,x2,y2,c,d) return 7 end

-- isColor：命中集合（0-based）
local hitSet = { ["9|19"]=true, ["29|39"]=true }
_G.isColor = function(x,y,c,sim) return hitSet[tostring(x).."|"..tostring(y)] == true end

-- findFeature：返回固定坐标
local lastFeature = nil
_G.findFeature = function(x1,y1,x2,y2,feat,deg,orient) lastFeature = feat return 100, 200 end
_G.findFeatureAll = function(x1,y1,x2,y2,feat,deg,orient) return { {100,200}, {300,400} } end
_G.findColor = function(x1,y1,x2,y2,c,deg,orient) return 15, 25 end
_G.findImage = function(x1,y1,x2,y2,path,deg,orient) return 50, 60, 1 end
_G.findImageAll = function(x1,y1,x2,y2,path,deg,orient) return { {50,60}, {70,80} } end
_G.loadImage = function(p) return { release=function() end } end

-- 触控
_G.tap = function(x,y) calls.tap = {x,y} return true end
_G.swipe = function(x1,y1,x2,y2,t) calls.swipe = {x1,y1,x2,y2,t} return true end
_G.touchDown = function(id,x,y) return true end
_G.touchMove = function(id,x,y) return true end
_G.touchUp   = function(id) return true end
_G.keyPress  = function(code) calls.keyPress = code return true end
_G.inputText = function(t) calls.inputText = t return true end
_G.KeyCode   = { HOME=3, BACK=4, RECENT=187, POWER=26, VOLUME_UP=24, VOLUME_DOWN=25 }
_G.toast     = function(t,ms) calls.toast = t end

-- shell
_G.exec    = function(cmd) return true end
_G.execOut = function(cmd)
    if cmd:find("pidof",1,true) then return "1234\n" end
    if cmd:find("pm list packages",1,true) then return "package:com.a\npackage:com.b\n" end
    if cmd:find("dumpsys activity",1,true) then return "  topResumedActivity=ActivityRecord{com.a/.Main}\n" end
    if cmd:find("monkey",1,true) then return "Events injected: 1\n" end
    return ""
end

-- 节点
local mockNode = {
    bounds = { 10, 20, 110, 60 },
    text = "副本",
    click = function(self) calls.nodeClick = true return true end,
    setText = function(self,t) return true end,
    parent = nil,
    child = function(self,i) return nil end,
    performAction = function(self,a) calls.scrollAction = a return true end,
}
_G.UiSelector = function()
    local sel = {}
    function sel:findOne() return mockNode end
    function sel:findAll() return { mockNode } end
    function sel:findOnce(i) return mockNode end
    -- 链式：任意键返回 self
    setmetatable(sel, { __index = function(t,k) return function(self_,v) return self_ end end })
    return sel
end
_G.getRootInActiveWindow = function() return mockNode end
_G.isAccServiceOk = function() return true end
_G.openAccessibility = function() return true end
_G.getNodeXml = function() return "<node/>" end
_G.keepNode = function() end
_G.releaseNode = function() end
_G.findByIndex = function(path) return mockNode end

-- import
_G.luajava = { bindClass = function(name) return { ACTION_SCROLL_UP=32768, ACTION_SCROLL_DOWN=65536 } end }

-- ==================== mock 模块表（engine.M 经 reqAny 探测全局） ====================
_G.json = json
_G.file = {
    exists = function(p) return io.open(p,"rb") ~= nil end,
    size = function(p) local f=io.open(p,"rb"); if not f then return 0 end local s=f:seek("end"); f:close(); return s end,
    write = function(p,c,append) local f=io.open(p,"w"); f:write(c); f:close(); return true end,
    mkdirs = function(p) os.execute("mkdir -p "..p); return true end,
    remove = function(p) os.execute("rm -f "..p); return true end,
    isDirectory = function(p) return false end,
    listDir = function(p) return {} end,
    unZip = function(a,b) return true end,
}
_G.http = { post=function(u,b) return "HTTPPOST" end, get=function(u) return "HTTPGET" end, request=function(t) return "BODY",200 end, setTimeout=function(t) end }
_G.thread = { start=function(fn) return "tid" end, stop=function(id) end }
_G.ocr = {
    recognizeRaw = function(data,fw,fh,stride,x,y,w,h)
        return { { x=100, y=200, w=40, h=20, text="副本", confidence=0.95 },
                 { x=300, y=400, w=30, h=15, text="冰副本", confidence=0.98 } }
    end,
    destroy = function() end,
}
_G.ime = { clearField=function() end, setText=function(t) end, hideKeyboard=function() end }
_G.cv = { captureMat = function(x,y,w,h) return "MAT" end }
_G.images = { getWidth=function(i) return 720 end, getHeight=function(i) return 1280 end, getData=function(i) return "IMG" end }
_G.event = { getPermissionStatus=function() return { touch=true, accessibility=true } end, onStopEvent=function() end }
_G.path = { getProjectDir=function() return WORK end, setProjectDir=function(p) end }
_G.project = { getProjectDir=function() return WORK end }
_G.coordinate = { getScreenSize=function() return 720,1280 end }

-- ==================== 装载 frameSdk ====================
package.path = os.getenv("HOME") .. "/workspace/jy-engine-test/?.lua;" .. os.getenv("HOME") .. "/workspace/jy-engine-test/?/init.lua;" .. package.path
print("== 装载 frameSdk-autolua2 ==")
local ok, sdk = pcall(require, "frameSdk-autolua2")
check("require 成功", ok, tostring(sdk))
if not ok then print(sdk); os.exit(1) end

local okInit, err = pcall(function() sdk.init({ bootstrapPermissions = false }) end)
check("init + 契约自检通过", okInit, tostring(err))
if not okInit then print(err); os.exit(1) end
check("_G.frameSdk 已注册", rawget(_G, "frameSdk") == sdk)

print("== 图色域 ==")
-- ps 比色：命中（p 为 1-based，适配层减 1 → 9|19 / 29|39 命中）
local r = sdk.cmpColorExT({ "10|20|FF0000-101010,30|40|00FF00-101010", 0.9 })
check("ps 全命中返回1", r == 1, "got "..tostring(r))
-- ps 比色：未命中（第2点 999|999→998|998 不在命中集）
local r2 = sdk.cmpColorExT({ "10|20|FF0000-101010,999|999|00FF00-101010", 0.9 })
check("ps 未命中返回0", r2 == 0, "got "..tostring(r2))

-- ft 找色
local fx, fy = sdk.findMultiColorT({ 0,0,720,1280, "A5F2F9-101010", "-2|-42|2E217B-101010", 0, 0.9 })
check("ft 命中返回坐标", fx == 100 and fy == 200, string.format("got %s,%s", tostring(fx), tostring(fy)))
check("ft 特征串拼接正确", lastFeature == "0|0|A5F2F9-101010,-2|-42|2E217B-101010", tostring(lastFeature))

-- fc 单色找色
local cx, cy = sdk.findColorT({ 0,0,720,1280, "FF0000-101010", 0, 0.9 })
check("fc 命中返回坐标", cx == 15 and cy == 25, string.format("got %s,%s", tostring(cx), tostring(cy)))

-- pic 找图（模板在工作目录/images/）
local pr, px, py = sdk.findPic(0,0,720,1280, "test", "555555", 1, 0.9)
check("pic 命中返回 ret,x,y", pr == 1 and px == 50 and py == 60, string.format("got %s,%s,%s", tostring(pr),tostring(px),tostring(py)))

-- 取色
local col = sdk.getPixelColor(10, 20)
check("getPixelColor 返回色值", col == "0x123456", tostring(col))

-- gcn 颜色数量
local cn = sdk.getColorNum(0,0,720,1280, "FF0000-101010", 0.9)
check("getColorNum 返回数量", cn == 7, tostring(cn))

print("== 找字/OCR 域 ==")
-- fs 找字（白名单命中"副本"）
local sr, sx, sy = sdk.findStrEx(0,0,720,1280, nil, "副本", 0.9)
check("fs 命中返回1+中心坐标", sr == 1 and sx == 120 and sy == 210, string.format("got %s,%s,%s", tostring(sr),tostring(sx),tostring(sy)))
-- fs 未命中（白名单不含）
local sr2 = sdk.findStrEx(0,0,720,1280, nil, "不存在的字", 0.9)
check("fs 未命中返回0", sr2 == 0, tostring(sr2))
-- PDOcr 格式
local pdo = sdk.PaddleOcr.detect(0,0,720,1280)
check("PDOcr.detect 返回含'副本'的JSON", type(pdo)=="string" and pdo:find("副本",1,true) ~= nil, tostring(pdo))

print("== 触控域 ==")
sdk.tap(300, 400)
check("tap 透传坐标", calls.tap and calls.tap[1]==300 and calls.tap[2]==400, tostring(calls.tap and calls.tap[1]))
sdk.swipe(10,10,200,300,500)
check("swipe 透传", calls.swipe and calls.swipe[5]==500, tostring(calls.swipe and calls.swipe[5]))
sdk.keyPress("BACK")
check("keyPress BACK → KeyCode.BACK", calls.keyPress == 4, tostring(calls.keyPress))
sdk.inputText("hello")
check("inputText 透传", calls.inputText == "hello", tostring(calls.inputText))

print("== 节点域 ==")
local n = sdk.nodeLib.findOne({ text = "副本" }, true)
check("nodeLib.findOne 返回节点", n ~= nil)
check("node:bounds() 返回 {l,t,r,b}", n and n:bounds() and n:bounds()[1]==10 and n:bounds()[4]==60,
    n and (n:bounds() and table.concat(n:bounds(),",")))
check("node:text() 返回文本", n and n:text() == "副本", n and tostring(n:text()))
local okc = pcall(function() n:click() end)
check("node:click() 生效", okc and calls.nodeClick == true)
local all = sdk.nodeLib.findAll({ text = "副本" }, true)
check("nodeLib.findAll 返回数组", type(all)=="table" and #all == 1, tostring(#all))

print("== 兼容层 ==")
local parts = sdk.splitStr("a,b,c", ",")
check("splitStr 纯文本分隔", #parts==3 and parts[1]=="a" and parts[3]=="c", table.concat(parts,"|"))
local r,g,b = sdk.colorToRGB("0x123456")
check("colorToRGB 解析", r==0x12 and g==0x34 and b==0x56, string.format("%s,%s,%s",tostring(r),tostring(g),tostring(b)))
check("table.contains 可用", type(table.contains)=="function" and table.contains({1,2,3},2)==true)
check("string.split 可用", type(string.split)=="function")

print("== 设备域 ==")
check("getDisplaySize", (function() local w,h=sdk.getDisplaySize() return w==720 and h==1280 end)())
check("appIsRunning 走 execOut", sdk.appIsRunning("com.a") == true)
check("getInstalledApk 解析", (function() local l=sdk.getInstalledApk() return #l==2 and l[1]=="com.a" end)())

print("== 降级/异常路径 ==")
-- yolo D 级降级：detect 返回空数组
local yj = sdk.YoloV5.detect(nil, false)
check("yolo 降级返回空[]", yj == "[]", tostring(yj))
-- exitScript 受控退出：抛 @EXIT_SCRIPT@
local okE, eE = pcall(sdk.exitScript, "测试退出")
check("exitScript 抛 @EXIT_SCRIPT@", (not okE) and tostring(eE):find("@EXIT_SCRIPT@",1,true) ~= nil, tostring(eE))
-- 找图模板缺失：报错
local okP = pcall(function() sdk.findPic(0,0,720,1280, "不存在的模板", "555555", 1, 0.9) end)
check("findPic 模板缺失报错", not okP)
-- getPixelColor 引擎缺失时兜底 0x000000
local savedGetColor = _G.getColor; _G.getColor = nil
local colMiss = sdk.getPixelColor(1,1); _G.getColor = savedGetColor
check("getPixelColor 缺失兜底", colMiss == "0x000000", tostring(colMiss))

print(string.rep("-", 50))
print(string.format("结果: %d 通过, %d 失败", passed, failed))
os.exit(failed == 0 and 0 or 1)
