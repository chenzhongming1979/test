-- 诊断4：多OCR基础语义（单插件场景；模拟器无 ncnn 回滚后仅 demo-ocr）
print("== diag_ocr_basic start ==")
print("isSupported=", ocr.isSupported())
local ids = ocr.list()
print("list count=", ids and #ids or "nil")
for i, id in ipairs(ids or {}) do print("  ocr["..i.."]=", id) end
print("active=", tostring(ocr.active()))

-- 非法 id 必须拒绝且不改变当前激活
local bad = ocr.use("com.not.exist")
print("use(bad)=", tostring(bad), "active=", tostring(ocr.active()))

-- 恢复默认（单插件场景应仍指向 demo）
print("use() reset=", tostring(ocr.use()), "active=", tostring(ocr.active()))

-- 识别链路：demo mock 对任意 PNG 返回固定文本块 DEMO
local ok, blocks = pcall(function()
  return ocr.recognizeFile("/sdcard/Android/data/com.autolua.autolua2/files/diag_ocr.png")
end)
print("recognizeFile ok=", tostring(ok))
if ok and blocks then
  print("  blocks=", #blocks)
  local b = blocks[1]
  if b then print("  first=", b.text, b.x, b.y, b.w, b.h, string.format("%.2f", b.confidence or 0)) end
elseif not ok then
  print("  err=", tostring(blocks))
end
print("== diag_ocr_basic end ==")
