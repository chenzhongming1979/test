-- 屏幕实时 OCR 端到端 + 性能测试（getScreenFrame 帧链路 → ncnn 插件）
print("== diag_ocr_live start ==")
print("active before:", tostring(ocr.active()))
print("use(ncnn):", tostring(ocr.use("com.autolua.plugin.ocr.ncnn")))

-- 1. 全屏实时识别（compat ocrText：getScreenFrame → recognizeRaw）
local t0 = os.clock()
local ok, text = pcall(ocr.ocrText, 0, 0)
local t1 = os.clock()
if not ok then
    print("ocrText ERROR:", tostring(text))
else
    print("ocrText full-screen elapsed:", string.format("%.1f ms", (t1 - t0) * 1000))
    print("text head:", tostring(text):gsub("\n", " | "):sub(1, 120))
end

-- 2. ocrEx 块数组（前 5 块）
local ok2, blocks = pcall(ocr.ocrEx, 0, 0)
if not ok2 then
    print("ocrEx ERROR:", tostring(blocks))
else
    print("ocrEx blocks:", blocks and #blocks or 0)
    for i, b in ipairs(blocks or {}) do
        if i <= 5 then
            print(string.format("  [%d] %s (%d,%d) %dx%d conf=%.2f",
                i, b.text, b.x, b.y, b.w, b.h, b.confidence))
        end
    end
end

-- 3. 连续 5 次全屏识别（耗时稳定性）
for i = 1, 5 do
    local a = os.clock()
    ocr.ocrText(0, 0)
    print(string.format("run%d full-screen: %.1f ms", i, (os.clock() - a) * 1000))
end

-- 4. 区域识别（1/4 屏）
local ok4, b4 = pcall(ocr.ocrEx, 0, 0)
if ok4 and b4 and #b4 > 0 then
    local a = os.clock()
    ocr.ocrText(0, 0, 540, 960)
    print(string.format("quarter-region: %.1f ms", (os.clock() - a) * 1000))
end
print("== diag_ocr_live end ==")
