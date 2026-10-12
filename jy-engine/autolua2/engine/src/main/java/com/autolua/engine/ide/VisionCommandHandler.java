package com.autolua.engine.ide;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import com.autolua.engine.display.projection.ScreenshotProviderManager;
import com.autolua.engine.display.sharedmem.FrameBuffer;
import com.autolua.engine.plugin.IOcrPlugin;
import com.autolua.engine.plugin.OcrTextBlock;
import com.autolua.engine.plugin.PluginManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * 图色远程命令：find_image / find_multi_color / cmp_color / ocr。
 * <p>
 * 执行方式（探索结论）：autolua2 的 C++ 图色库（3rd/autolua_vision）只注入主引擎 Lua VM
 * （Display::injectToLua），IDE 沙箱（LuaContextImplement）是无图色绑定的裸 Lua VM，
 * Java 侧亦无找图/比色入口。故本处理器不走 Lua：直接经
 * {@link ScreenshotProviderManager#captureFull()} 读共享内存截图帧（RGBA）。
 * <p>
 * 三期加速：比色/多点找色/找图内核优先走 C++（{@link VisionJni} →
 * extension/VisionBridge.cpp，复用 autolua_vision）；模板 PNG 在 Java 侧解码为 RGBA
 * 像素后传入 JNI。JNI 通道不可用（UnsatisfiedLinkError/任何异常/null 返回）时
 * 自动回退二期纯 Java 滑窗实现（fallback* 方法），回包协议完全一致。
 * OCR 当前无实现 → 回 {@link WsProtocol#ERR_OCR_UNAVAILABLE}
 * （capabilities.vision.ocr=false）。
 * <p>
 * 请求格式（对齐派大星 ws 协议）：
 * <ul>
 *     <li>cmp_color：{points:[{x,y,color}], similarity?, rect?} → {"match":bool}</li>
 *     <li>find_multi_color：{points:[{x,y,color}...]（首点为锚点，其余为相对锚点的
 *     偏移校验点）, similarity?, rect?} → {"found":bool,"points":[{x,y}...]}</li>
 *     <li>find_image：{templateBase64 或 path, threshold?, rect?}
 *     → {"found":bool,"x","y","similarity"}（x/y 为模板左上角绝对屏幕坐标）</li>
 *     <li>ocr：回 error（能力不可用）</li>
 * </ul>
 * rect {x,y,w,h} 为可选搜索区域裁剪；points 坐标为绝对屏幕坐标（相对整屏原点，
 * 不随 rect 裁剪平移）；颜色支持 "0xFFFFFF"/"#FFFFFF"/hex/十进制。
 * similarity/threshold（0~1）映射为每通道最大绝对差 (1-s)*255，RGB 三通道同时判定。
 * path 优先在 ide_push/ 内解析（IDE 推送的工程文件），找不到再按绝对路径。
 */
public class VisionCommandHandler {

    private static final String TAG = "AutoLuaIde";
    /** 找图模板验证像素总预算（防全屏纯色等病态输入下验证次数爆炸） */
    private static final long VERIFY_PIXEL_BUDGET = 20_000_000L;

    // 能力协商常量（CapabilitiesHandler 读取）
    public static final boolean SUPPORT_FIND_IMAGE = true;
    public static final boolean SUPPORT_FIND_MULTI_COLOR = true;
    public static final boolean SUPPORT_CMP_COLOR = true;

    /** 应用上下文供应器（find_image 的 path 在 ide_push/ 内解析时依赖） */
    private final Supplier<Context> contextSupplier;

    public VisionCommandHandler(Supplier<Context> contextSupplier) {
        this.contextSupplier = contextSupplier;
    }

    public void register(IdeMessageRouter router) {
        router.register(WsProtocol.CMD_CMP_COLOR, (data, clientId) -> handleCmpColor(data));
        router.register(WsProtocol.CMD_FIND_MULTI_COLOR, (data, clientId) -> handleFindMultiColor(data));
        router.register(WsProtocol.CMD_FIND_IMAGE, (data, clientId) -> handleFindImage(data));
        router.register(WsProtocol.CMD_OCR, (data, clientId) -> handleOcr(data));
    }

    /**
     * OCR 能力运行时判定（方案七）：安装了 provides 含 "ocr" 的插件即可用。
     * 供 CapabilitiesHandler 动态回填 vision.ocr（替代原静态 SUPPORT_OCR=false 常量）。
     */
    public static boolean isOcrSupported() {
        return PluginManager.getInstance().getOcrPlugin() != null;
    }

    /**
     * OCR：优先取 {@link PluginManager#getOcrPlugin()}，非 null 时经
     * {@link ScreenshotProviderManager} 取帧 → 插件 recognize → 二期协议回包；
     * 无插件保持 {@link WsProtocol#ERR_OCR_UNAVAILABLE}。
     */
    private String handleOcr(JSONObject data) throws Exception {
        IOcrPlugin plugin = PluginManager.getInstance().getOcrPlugin();
        if (plugin == null) {
            throw new IllegalStateException(WsProtocol.ERR_OCR_UNAVAILABLE);
        }
        Screen s = captureScreenRegion(data.optJSONObject("rect"));
        List<OcrTextBlock> blocks;
        try {
            blocks = plugin.recognize(s.frame, s.frameW, s.frameH,
                    s.originX, s.originY, s.width, s.height);
        } catch (Throwable t) {
            Log.w(TAG, "ocr plugin recognize failed: " + t);
            String msg = t.getMessage() != null ? t.getMessage() : t.toString();
            throw new IllegalStateException(WsProtocol.ERR_OCR_PLUGIN + msg);
        }
        if (blocks == null) {
            blocks = Collections.emptyList();
        }
        JSONArray results = new JSONArray();
        StringBuilder full = new StringBuilder();
        for (OcrTextBlock b : blocks) {
            if (b == null) {
                continue;
            }
            JSONObject o = new JSONObject();
            o.put("text", b.text == null ? "" : b.text);
            o.put("x", b.x);
            o.put("y", b.y);
            o.put("w", b.w);
            o.put("h", b.h);
            o.put("confidence", b.confidence);
            results.put(o);
            if (full.length() > 0) {
                full.append('\n');
            }
            full.append(b.text == null ? "" : b.text);
        }
        JSONObject result = new JSONObject();
        result.put("results", results);
        result.put("text", full.toString());
        return result.toString();
    }

    // ------------------------------------------------------------------
    // 命令实现
    // ------------------------------------------------------------------

    /** 比色：区域内取每个 point 的像素与期望颜色比对，全部命中才 match=true */
    private String handleCmpColor(JSONObject data) throws Exception {
        PointColor[] pts = parsePoints(data.optJSONArray("points"));
        double similarity = data.optDouble("similarity", 0.9);
        int tol = similarityTolerance(similarity);
        Screen s = captureScreenRegion(data.optJSONObject("rect"));
        boolean match;
        // 三期：C++ 内核。任一点出搜索区域直接不匹配（与原实现语义一致，不走 JNI）
        int[] xy = new int[pts.length * 2];
        int[] colors = new int[pts.length];
        boolean inRegion = true;
        for (int i = 0; i < pts.length; i++) {
            int lx = pts[i].x - s.originX;
            int ly = pts[i].y - s.originY;
            if (lx < 0 || ly < 0 || lx >= s.width || ly >= s.height) {
                inRegion = false;
                break;
            }
            xy[2 * i] = pts[i].x;
            xy[2 * i + 1] = pts[i].y;
            colors[i] = pts[i].rgb;
        }
        if (!inRegion) {
            match = false;
        } else {
            int[] r = tryJni(() -> VisionJni.nativeCmpColor(
                    s.frame, s.frameW, s.frameH, xy, colors, similarity));
            if (r != null) {
                match = r[0] == 1;
            } else {
                // JNI 通道不可用：二期纯 Java 回退
                match = fallbackCmpColor(pts, s, tol);
            }
        }
        JSONObject result = new JSONObject();
        result.put("match", match);
        return result.toString();
    }

    /** 多点找色：滑窗匹配锚点像素，命中后校验其余偏移点，返回全部命中点绝对坐标 */
    private String handleFindMultiColor(JSONObject data) throws Exception {
        PointColor[] pts = parsePoints(data.optJSONArray("points"));
        double similarity = data.optDouble("similarity", 0.9);
        int tol = similarityTolerance(similarity);
        Screen s = captureScreenRegion(data.optJSONObject("rect"));
        // 预计算各点相对锚点的偏移
        int[] ox = new int[pts.length];
        int[] oy = new int[pts.length];
        for (int i = 1; i < pts.length; i++) {
            ox[i] = pts[i].x - pts[0].x;
            oy[i] = pts[i].y - pts[0].y;
        }
        // 三期：C++ 内核（offsets 含锚点 {0,0}，锚点在 rect 内行主序扫描）
        int[] offsets = new int[pts.length * 2];
        int[] colors = new int[pts.length];
        for (int i = 0; i < pts.length; i++) {
            offsets[2 * i] = i == 0 ? 0 : ox[i];
            offsets[2 * i + 1] = i == 0 ? 0 : oy[i];
            colors[i] = pts[i].rgb;
        }
        int[] r = tryJni(() -> VisionJni.nativeFindMultiColor(
                s.frame, s.frameW, s.frameH, offsets, colors, similarity,
                s.originX, s.originY, s.width, s.height));
        if (r != null && r[0] == 1) {
            return multiColorResult(r[1], r[2], ox, oy);
        }
        if (r == null) {
            // JNI 通道不可用：二期纯 Java 滑窗回退
            int[] hit = fallbackFindMultiColorAnchor(pts, ox, oy, s, tol);
            if (hit != null) {
                return multiColorResult(hit[0], hit[1], ox, oy);
            }
        }
        JSONObject result = new JSONObject();
        result.put("found", false);
        result.put("points", new JSONArray());
        return result.toString();
    }

    /** find_multi_color 命中回包：锚点帧内坐标 + 各偏移点（协议字段不变） */
    private static String multiColorResult(int anchorX, int anchorY, int[] ox, int[] oy)
            throws Exception {
        JSONArray matched = new JSONArray();
        for (int i = 0; i < ox.length; i++) {
            JSONObject mp = new JSONObject();
            mp.put("x", anchorX + ox[i]);
            mp.put("y", anchorY + oy[i]);
            matched.put(mp);
        }
        JSONObject result = new JSONObject();
        result.put("found", true);
        result.put("points", matched);
        return result.toString();
    }

    /**
     * 找图：模板（templateBase64 或 path）在区域内滑窗匹配。
     * 三期：模板解码为 RGBA 字节后走 C++ 内核（锚点预筛选 + 容差命中率 ≥ threshold），
     * JNI 不可用时回退纯 Java 滑窗；两者判定语义一致。
     */
    private String handleFindImage(JSONObject data) throws Exception {
        Bitmap template = decodeTemplate(data, contextSupplier.get());
        if (template == null) {
            throw new IllegalArgumentException("find_image: template decode failed (templateBase64/path)");
        }
        try {
            int tw = template.getWidth();
            int th = template.getHeight();
            if (tw <= 0 || th <= 0) {
                throw new IllegalArgumentException("find_image: empty template");
            }
            int[] tpl = new int[tw * th];
            template.getPixels(tpl, 0, tw, 0, 0, tw, th);
            double threshold = data.optDouble("threshold", 0.9);
            int tol = similarityTolerance(threshold);
            Screen s = captureScreenRegion(data.optJSONObject("rect"));
            if (tw > s.width || th > s.height) {
                JSONObject r0 = new JSONObject();
                r0.put("found", false);
                return r0.toString();
            }
            // 三期：C++ 内核（模板转 RGBA 字节流，帧内滑窗）
            byte[] tplRgba = argbToRgba(tpl, tw * th);
            int[] r = tryJni(() -> VisionJni.nativeFindImage(
                    s.frame, s.frameW, s.frameH, tplRgba, tw, th, threshold,
                    s.originX, s.originY, s.width, s.height));
            if (r != null) {
                JSONObject result = new JSONObject();
                if (r[0] == 1) {
                    result.put("found", true);
                    result.put("x", r[1]);
                    result.put("y", r[2]);
                    result.put("similarity", r[3] / 1000.0);
                } else {
                    result.put("found", false);
                }
                return result.toString();
            }
            // JNI 通道不可用：二期纯 Java 滑窗回退
            return fallbackFindImage(tpl, tw, th, threshold, tol, s);
        } finally {
            template.recycle();
        }
    }

    // ------------------------------------------------------------------
    // JNI 通道与纯 Java 回退（二期实现原样保留）
    // ------------------------------------------------------------------

    /** JNI 调用包装：任何异常（UnsatisfiedLinkError 等）记日志并返回 null，由调用方回退 */
    private static int[] tryJni(Supplier<int[]> call) {
        try {
            return call.get();
        } catch (Throwable t) {
            Log.w(TAG, "vision JNI unavailable, fallback to java impl: " + t);
            return null;
        }
    }

    /** Bitmap ARGB 像素 → RGBA 字节流（每像素 R,G,B,A 4 字节，与帧布局一致） */
    private static byte[] argbToRgba(int[] argb, int count) {
        byte[] rgba = new byte[count * 4];
        for (int i = 0; i < count; i++) {
            int c = argb[i];
            rgba[i * 4] = (byte) ((c >> 16) & 0xFF);
            rgba[i * 4 + 1] = (byte) ((c >> 8) & 0xFF);
            rgba[i * 4 + 2] = (byte) (c & 0xFF);
            rgba[i * 4 + 3] = (byte) ((c >> 24) & 0xFF);
        }
        return rgba;
    }

    /** 二期纯 Java 比色回退：区域内取每个 point 的像素与期望颜色比对，全部命中才 true */
    private static boolean fallbackCmpColor(PointColor[] pts, Screen s, int tol) {
        for (PointColor pc : pts) {
            int lx = pc.x - s.originX;
            int ly = pc.y - s.originY;
            if (lx < 0 || ly < 0 || lx >= s.width || ly >= s.height
                    || !colorMatch(s.frame, s.pixel(lx, ly), pc.rgb, tol)) {
                return false;
            }
        }
        return true;
    }

    /** 二期纯 Java 多点找色回退：返回锚点帧内坐标 {x,y}，未命中返回 null */
    private static int[] fallbackFindMultiColorAnchor(PointColor[] pts, int[] ox, int[] oy,
                                                      Screen s, int tol) {
        for (int sy = 0; sy < s.height; sy++) {
            for (int sx = 0; sx < s.width; sx++) {
                if (!colorMatch(s.frame, s.pixel(sx, sy), pts[0].rgb, tol)) {
                    continue;
                }
                boolean ok = true;
                for (int i = 1; i < pts.length; i++) {
                    int x = sx + ox[i];
                    int y = sy + oy[i];
                    if (x < 0 || y < 0 || x >= s.width || y >= s.height
                            || !colorMatch(s.frame, s.pixel(x, y), pts[i].rgb, tol)) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    return new int[]{s.originX + sx, s.originY + sy};
                }
            }
        }
        return null;
    }

    /**
     * 二期纯 Java 找图回退：锚点预筛选（模板首像素容差比对）命中后才做全模板
     * 逐像素验证，匹配率 = 容差命中像素数 / 模板总像素数，≥ threshold 即命中。
     */
    private String fallbackFindImage(int[] tpl, int tw, int th, double threshold, int tol,
                                     Screen s) throws Exception {
        int anchor = tpl[0];
        long budget = VERIFY_PIXEL_BUDGET;
        for (int sy = 0; sy + th <= s.height; sy++) {
            for (int sx = 0; sx + tw <= s.width; sx++) {
                // 锚点预筛选：模板首像素颜色不匹配直接跳过，避免全模板逐像素验证
                if (!colorMatch(s.frame, s.pixel(sx, sy), anchor, tol)) {
                    continue;
                }
                budget -= (long) tw * th;
                if (budget < 0) {
                    Log.w(TAG, "find_image verify budget exhausted, stop scanning");
                    break;
                }
                int count = 0;
                for (int ty = 0; ty < th; ty++) {
                    int rowBase = s.pixel(sx, sy + ty);
                    for (int tx = 0; tx < tw; tx++) {
                        int c = tpl[ty * tw + tx];
                        int px = rowBase + tx * 4;
                        int dr = (s.frame[px] & 0xFF) - ((c >> 16) & 0xFF);
                        int dg = (s.frame[px + 1] & 0xFF) - ((c >> 8) & 0xFF);
                        int db = (s.frame[px + 2] & 0xFF) - (c & 0xFF);
                        if (Math.abs(dr) <= tol && Math.abs(dg) <= tol && Math.abs(db) <= tol) {
                            count++;
                        }
                    }
                }
                double ratio = count / (double) (tw * th);
                if (ratio >= threshold) {
                    JSONObject result = new JSONObject();
                    result.put("found", true);
                    result.put("x", s.originX + sx);
                    result.put("y", s.originY + sy);
                    result.put("similarity", Math.round(ratio * 1000) / 1000.0);
                    return result.toString();
                }
            }
        }
        JSONObject result = new JSONObject();
        result.put("found", false);
        return result.toString();
    }

    // ------------------------------------------------------------------
    // 截图与像素工具
    // ------------------------------------------------------------------

    /** 屏幕帧视图：整帧 RGBA + 搜索区域信息（pixel() 统一换算偏移） */
    private static final class Screen {
        final byte[] frame;   // 整帧 RGBA
        final int frameW;     // 整帧宽（像素，JNI 通道用）
        final int frameH;     // 整帧高（像素，JNI 通道用）
        final int width;      // 区域宽（像素）
        final int height;     // 区域高（像素）
        final int originX;    // 区域左上角绝对屏幕坐标（回包换算用）
        final int originY;
        final int rowStride;  // 整帧行字节步长（宽 * 4）
        final int baseOffset; // 区域首像素在帧内的字节偏移

        Screen(byte[] frame, int frameW, int frameH, int width, int height,
               int originX, int originY, int rowStride, int baseOffset) {
            this.frame = frame;
            this.frameW = frameW;
            this.frameH = frameH;
            this.width = width;
            this.height = height;
            this.originX = originX;
            this.originY = originY;
            this.rowStride = rowStride;
            this.baseOffset = baseOffset;
        }

        /** 区域内 (x,y) 像素在帧字节流中的偏移 */
        int pixel(int x, int y) {
            return baseOffset + y * rowStride + x * 4;
        }
    }

    /**
     * 取整帧并按 rect 裁剪出搜索区域。
     * rect 为 null/缺省时区域 = 整屏；rect 越界自动钳制到帧内。
     */
    private Screen captureScreenRegion(JSONObject rect) throws Exception {
        ScreenshotProviderManager mgr = ScreenshotProviderManager.getInstance();
        byte[] frame = mgr.captureFull();
        if (frame == null) {
            throw new IllegalStateException("screenshot unavailable (screen capture not started)");
        }
        FrameBuffer fb = mgr.getFrameBuffer();
        int fw = fb.getFrameWidth();
        int fh = fb.getFrameHeight();
        if (fw <= 0 || fh <= 0 || (long) fw * fh * 4 > frame.length) {
            throw new IllegalStateException("screenshot frame size unknown or truncated");
        }
        int x = 0, y = 0, w = fw, h = fh;
        if (rect != null) {
            x = clamp(rect.optInt("x", 0), 0, fw - 1);
            y = clamp(rect.optInt("y", 0), 0, fh - 1);
            w = clamp(rect.optInt("w", fw - x), 1, fw - x);
            h = clamp(rect.optInt("h", fh - y), 1, fh - y);
        }
        return new Screen(frame, fw, fh, w, h, x, y, fw * 4, (y * fw + x) * 4);
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    /** RGBA 像素（帧字节流偏移）与目标 RGB 的容差比对：三通道绝对差都 ≤ tol 才命中 */
    private static boolean colorMatch(byte[] frame, int px, int rgb, int tol) {
        int dr = (frame[px] & 0xFF) - ((rgb >> 16) & 0xFF);
        int dg = (frame[px + 1] & 0xFF) - ((rgb >> 8) & 0xFF);
        int db = (frame[px + 2] & 0xFF) - (rgb & 0xFF);
        return Math.abs(dr) <= tol && Math.abs(dg) <= tol && Math.abs(db) <= tol;
    }

    /** similarity(0~1) → 每通道允许的最大绝对差（1.0→0，0→255） */
    static int similarityTolerance(double similarity) {
        if (similarity < 0) {
            similarity = 0;
        }
        if (similarity > 1) {
            similarity = 1;
        }
        return (int) Math.round((1 - similarity) * 255);
    }

    /** 解析 points 数组为点色列表（坐标为绝对屏幕坐标） */
    private static PointColor[] parsePoints(JSONArray arr) throws Exception {
        if (arr == null || arr.length() == 0) {
            throw new IllegalArgumentException("points is required");
        }
        PointColor[] pts = new PointColor[arr.length()];
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.getJSONObject(i);
            PointColor pc = new PointColor();
            pc.x = p.optInt("x", -1);
            pc.y = p.optInt("y", -1);
            pc.rgb = parseColor(p.isNull("color") ? "" : p.optString("color", ""));
            pts[i] = pc;
        }
        return pts;
    }

    private static final class PointColor {
        int x;
        int y;
        int rgb;
    }

    /**
     * 解析颜色："0xFFFFFF" / "#FFFFFF" / "FFFFFF"（hex）或纯十进制数字串。
     * 判定规则：带 #/0x 前缀或含 [a-f] 视为 hex；纯数字且长度 ≤ 7 视为十进制
     * （避免 16777215 之类的十进制被误读为 hex）。
     */
    static int parseColor(String s) {
        if (s == null) {
            throw new IllegalArgumentException("color is empty");
        }
        String v = s.trim();
        if (v.isEmpty()) {
            throw new IllegalArgumentException("color is empty");
        }
        boolean hex = false;
        if (v.startsWith("#")) {
            v = v.substring(1);
            hex = true;
        } else if (v.startsWith("0x") || v.startsWith("0X")) {
            v = v.substring(2);
            hex = true;
        }
        if (!hex) {
            boolean hasAlpha = false;
            for (int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                if ((c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) {
                    hasAlpha = true;
                    break;
                }
            }
            hex = hasAlpha || v.length() > 7 || !isDigits(v);
        }
        long value = Long.parseLong(v, hex ? 16 : 10);
        return (int) (value & 0xFFFFFF);
    }

    private static boolean isDigits(String v) {
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** 解码模板：templateBase64 优先，其次 path（先 ide_push/ 内解析，再绝对路径） */
    private static Bitmap decodeTemplate(JSONObject data, Context ctx) throws Exception {
        String b64 = data.isNull("templateBase64") ? "" : data.optString("templateBase64", "");
        if (!b64.isEmpty()) {
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        }
        String path = data.optString("path", "");
        if (path.isEmpty()) {
            throw new IllegalArgumentException("find_image: templateBase64 or path is required");
        }
        File f = null;
        if (ctx != null) {
            try {
                File pushed = IdePaths.safeResolve(new File(ctx.getFilesDir(), WsProtocol.IDE_PUSH_DIR), path);
                if (pushed.isFile()) {
                    f = pushed;
                }
            } catch (IOException ignored) {
                // 越界路径按绝对路径兜底
            }
        }
        if (f == null) {
            File abs = new File(path);
            if (abs.isFile()) {
                f = abs;
            }
        }
        if (f == null) {
            throw new IllegalArgumentException("find_image: template file not found: " + path);
        }
        Log.d(TAG, "find_image template: " + f.getAbsolutePath());
        return BitmapFactory.decodeFile(f.getAbsolutePath());
    }
}
