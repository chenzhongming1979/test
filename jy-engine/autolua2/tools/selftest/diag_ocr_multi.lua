-- 诊断3：多OCR共存（路线A 门面+切换）链路验证
print("== diag_ocr_multi start ==")
print("isSupported=", ocr.isSupported())
local ids = ocr.list()
print("list count=", ids and #ids or "nil")
for i, id in ipairs(ids or {}) do print("  ocr["..i.."]=", id) end
print("active(default)=", tostring(ocr.active()))

local PNG = "/sdcard/Android/data/com.autolua.autolua2/files/diag_ocr.png"

-- 1) 切换到 ncnn 真实识别
local ok = ocr.use("com.autolua.plugin.ocr.ncnn")
print("use(ncnn)=", tostring(ok), "active=", tostring(ocr.active()))
local ok2, blocks = pcall(function() return ocr.recognizeFile(PNG) end)
print("ncnn recognizeFile ok=", tostring(ok2))
if ok2 and blocks then
  print("  ncnn blocks=", #blocks)
  for i = 1, math.min(5, #blocks) do
    local b = blocks[i]
    print("  ["..i.."]", b.text, b.x, b.y, b.w, b.h, string.format("%.2f", b.confidence or 0))
  end
elseif not ok2 then
  print("  ncnn err=", tostring(blocks))
end

-- 2) 切回 demo mock（固定文本 DEMO）
local ok3 = ocr.use("com.autolua.plugin.ocr.demo")
print("use(demo)=", tostring(ok3), "active=", tostring(ocr.active()))
local ok4, blocks2 = pcall(function() return ocr.recognizeFile(PNG) end)
print("demo recognizeFile ok=", tostring(ok4))
if ok4 and blocks2 then
  print("  demo blocks=", #blocks2, "first=", blocks2[1] and blocks2[1].text or "none")
elseif not ok4 then
  print("  demo err=", tostring(blocks2))
end

-- 3) 非法 id 拒绝；恢复默认
print("use(bad)=", tostring(ocr.use("com.not.exist")), "active=", tostring(ocr.active()))
print("use() reset=", tostring(ocr.use()), "active=", tostring(ocr.active()))
print("== diag_ocr_multi end ==")
