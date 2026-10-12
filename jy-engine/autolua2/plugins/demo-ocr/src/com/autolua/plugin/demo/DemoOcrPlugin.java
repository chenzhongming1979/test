package com.autolua.plugin.demo;

import com.autolua.engine.plugin.IOcrPlugin;
import com.autolua.engine.plugin.IPluginContext;
import com.autolua.engine.plugin.OcrTextBlock;

import java.util.Collections;
import java.util.List;

/**
 * Demo OCR 插件（方案七全链路验证用 mock 实现，不真 OCR）。
 * <p>
 * <b>替换为真实 PP-OCR(ncnn) 推理即生产插件</b>：在 onInit 里
 * loadNativeLibrary 加载 ncnn/paddle 推理库、把模型释放到 {@code ctx.getPluginDir()}，
 * recognize 内取检测+识别双模型推理后回填 OcrTextBlock 列表即可——
 * 契约（IOcrPlugin）与加载机制（PluginManager）无需任何改动。
 * <p>
 * 本 mock 的 recognize 只做"帧缩小取样统计"（验证 RGBA 帧数据通路与 rect 语义），
 * 然后返回固定文本块 "DEMO"（位置居中于识别区域，置信度随画面平均亮度微调）。
 */
public class DemoOcrPlugin implements IOcrPlugin {

    private volatile IPluginContext ctx;

    @Override
    public void onInit(IPluginContext ctx) throws Exception {
        this.ctx = ctx;
        ctx.log("demo ocr onInit, abi=" + ctx.getAbi());
        // ---- 可选 so 加载演示 ----
        // demo 插件未打包 so，此处 loadNativeLibrary 返回 false 并由宿主记日志，
        // 演示"宿主解压 + 插件主动加载"的 so 交付模型调用方式；
        // 真实 PP-OCR(ncnn) 插件在此调用 loadNativeLibrary("libncnn") 等加载推理库。
        boolean loaded = ctx.loadNativeLibrary("libdemo");
        ctx.log("loadNativeLibrary(libdemo) -> " + loaded + "（演示：demo 无 so，预期 false）");
        // ---- 模型初始化占位 ----
        // 真实插件在此把 assets 模型文件释放到 ctx.getPluginDir() 并初始化推理会话
    }

    @Override
    public void onRelease() {
        IPluginContext c = ctx;
        if (c != null) {
            c.log("demo ocr onRelease");
        }
        ctx = null;
    }

    @Override
    public String getId() {
        return "com.autolua.plugin.ocr.demo";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String[] getProvides() {
        return new String[]{"ocr"};
    }

    @Override
    public List<OcrTextBlock> recognize(byte[] frame, int frameW, int frameH,
                                        int rectX, int rectY, int rectW, int rectH) throws Exception {
        if (frame == null) {
            throw new IllegalArgumentException("frame is null");
        }
        if (frameW <= 0 || frameH <= 0 || frame.length < (long) frameW * frameH * 4) {
            throw new IllegalArgumentException("frame size mismatch: " + frameW + "x" + frameH);
        }
        // 钳制识别区域到帧内（宿主已钳制，此处防御性兜底）
        int x0 = Math.max(0, Math.min(rectX, frameW - 1));
        int y0 = Math.max(0, Math.min(rectY, frameH - 1));
        int w = Math.max(1, Math.min(rectW, frameW - x0));
        int h = Math.max(1, Math.min(rectH, frameH - y0));

        // ---- mock：帧缩小取样统计（验证帧数据通路）----
        int stride = Math.max(4, Math.max(w, h) / 64);
        long sumLuma = 0;
        int samples = 0;
        for (int y = y0; y < y0 + h; y += stride) {
            int row = y * frameW;
            for (int x = x0; x < x0 + w; x += stride) {
                int px = (row + x) * 4;
                int r = frame[px] & 0xFF;
                int g = frame[px + 1] & 0xFF;
                int b = frame[px + 2] & 0xFF;
                sumLuma += (r * 299 + g * 587 + b * 114) / 1000;
                samples++;
            }
        }
        int meanLuma = samples > 0 ? (int) (sumLuma / samples) : -1;
        IPluginContext c = ctx;
        if (c != null) {
            c.log("recognize: frame " + frameW + "x" + frameH + " rect(" + x0 + "," + y0
                    + "," + w + "," + h + ") stride=" + stride
                    + " samples=" + samples + " meanLuma=" + meanLuma);
        }

        // ---- mock：返回固定文本块 "DEMO"（居中于识别区域）----
        // 真实插件在此返回检测+识别出的真实文本块列表
        int bw = Math.min(80, w);
        int bh = Math.min(24, h);
        int bx = x0 + (w - bw) / 2;
        int by = y0 + (h - bh) / 2;
        float confidence = samples > 0
                ? Math.min(0.99f, 0.90f + meanLuma / 2550f)
                : 0.5f;
        return Collections.singletonList(new OcrTextBlock("DEMO", bx, by, bw, bh, confidence));
    }
}
