-- probe11c: EXACT copy of selftest section 11 body, then section 14 head as canary
local DIR = "/data/local/tmp/_selftest/"

print("P11X:0 start")
local body, code = http.get("http://www.baidu.com")
print("P11X:1 http.get done")
local function checkEq(name, actual, expected)
    if actual == expected then
        print("[PASS] " .. name)
    else
        print("[FAIL] " .. name .. " | expect " .. tostring(expected) .. ", got " .. tostring(actual))
    end
end
local function check(name, ok, detail)
    if ok then
        print("[PASS] " .. name)
    else
        print("[FAIL] " .. name .. (detail ~= nil and (" | " .. tostring(detail)) or ""))
    end
end
checkEq("http.get code", code, 200)
check("http.get body 非空", type(body) == "string" and #body > 0)

local body2, code2, headers = http.request{
    url = "http://www.baidu.com", method = "GET", timeout = 8000,
}
print("P11X:2 http.request done")
checkEq("http.request code", code2, 200)
check("http.request headers.Date", headers and (headers["Date"] or headers["date"]) ~= nil)

local bin, bcode = http.request{ url = "http://www.baidu.com/favicon.ico", binary = true, timeout = 8000 }
print("P11X:3 binary done")
check("http.request binary", bcode == 200 and type(bin) == "userdata" or type(bin) == "string")

local okDl, dcode = downloadFile("http://www.baidu.com", DIR .. "dl.html")
print("P11X:4 downloadFile done")
check("downloadFile", okDl == true and dcode == 200)
check("downloadFile 落盘", fileExist(DIR .. "dl.html"))
delfile(DIR .. "dl.html")
print("P11X:5 delfile done")

local nwt = getNetWorkTime()
print("P11X:6 getNetWorkTime done")
check("getNetWorkTime", type(nwt) == "string", "expect string, got " .. type(nwt))

local pb, pcode = httpPost("http://www.baidu.com", "a=1")
print("P11X:7 httpPost done")
check("httpPost 调用", type(pcode) == "number")

print("P11X:8 section11 END, canary start")
keepScreen()
print("P11X:9 keepScreen ok")
local w, h = getBaseSize()
print("P11X:10 getBaseSize=" .. tostring(w) .. "x" .. tostring(h))
local r = getRotation()
print("P11X:11 getRotation=" .. tostring(r))
local icd = isChangeDirection()
print("P11X:12 isChangeDirection=" .. tostring(icd))
local c = getColor(0, 0)
print("P11X:13 getColor=" .. tostring(c))
local fx, fy = findColor(0, 0, 99, 99, c or 0xFFFFFF, 1)
print("P11X:14 findColor=" .. tostring(fx) .. "," .. tostring(fy))
print("P11X:99 all done")
