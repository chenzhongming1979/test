-- targeted probe: rsa group / net headers / getNetWorkTime / java bridge vararg
print("R0 start")
local kp = crypt.rsaGenerateKey(1024)
print("R1 kp type=" .. type(kp))
local pub = kp.publicKey
print("R2 pub=" .. tostring(pub):sub(1, 16))
local re = crypt.rsaEncrypt("rsa-msg", pub)
print("R3 re=" .. tostring(re):sub(1, 16))
local dec = crypt.rsaDecrypt(re, kp.privateKey)
print("R4 dec=" .. tostring(dec))

print("N0 net start")
local body2, code2, headers = http.request{ url = "http://www.baidu.com", method = "GET", timeout = 8000 }
print("N1 code=" .. tostring(code2) .. " headersType=" .. type(headers))
if type(headers) == "table" then
  local n = 0
  for k, v in pairs(headers) do
    n = n + 1
    if n <= 6 then print("H: " .. tostring(k) .. "=" .. tostring(v)) end
  end
  print("N1 headerCount=" .. n)
else
  print("N1 headers not table")
end
local nwt = getNetWorkTime()
print("N2 nwt=" .. tostring(nwt) .. " type=" .. type(nwt))

print("J0 java bridge start")
local okj, errj = pcall(function()
  local inst = luajava.newInstance("java.lang.String", "xyz")
  print("J1 newInstance=" .. tostring(inst:toString()))
end)
print("J2 pcall=" .. tostring(okj) .. " err=" .. tostring(errj))
print("Z all done")
