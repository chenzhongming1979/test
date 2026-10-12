-- 性能分解：帧获取 / det / rec 各段耗时 + 区域尺寸敏感性
print("== perf start ==")
local function now() return os.clock() end

ocr.use("com.autolua.plugin.ocr.ncnn")

-- 帧获取耗时
local a = now()
updateScreen()
local data, fw, fh, stride = getScreenFrame()
local b = now()
print(string.format("screen %dx%d stride=%d, frame fetch: %.1f ms", fw, fh, stride, (b - a) * 1000))

-- det 耗时 = recognizeRaw 全屏（含 det+rec）；先测纯全屏
a = now()
ocr.recognizeRaw(data, fw, fh, stride, 0, 0, fw, fh)
b = now()
print(string.format("full recognize (det+rec): %.1f ms", (b - a) * 1000))

-- 区域敏感性：不同大小区域识别耗时
local regions = {
    { "1/4 top",    0, 0, math.floor(fw / 2), math.floor(fh / 2) },
    { "1/16 corner", 0, 0, math.floor(fw / 4), math.floor(fh / 4) },
    { "strip 960x80", 0, 100, 960, 80 },
}
for _, r in ipairs(regions) do
    local tag, x, y, w, h = r[1], r[2], r[3], r[4], r[5]
    local t0 = now()
    local blks = ocr.recognizeRaw(data, fw, fh, stride, x, y, w, h)
    local t1 = now()
    print(string.format("%s (%dx%d): %.1f ms, blocks=%d", tag, w, h, (t1 - t0) * 1000, blks and #blks or 0))
end
print("== perf end ==")
