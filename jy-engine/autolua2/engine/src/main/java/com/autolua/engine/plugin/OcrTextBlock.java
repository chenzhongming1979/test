package com.autolua.engine.plugin;

/**
 * OCR 识别结果文本块（POJO，gson 兼容：无参构造 + 公有字段）。
 * <p>
 * 坐标为帧内坐标（整帧原点即屏幕原点，可与图色命令坐标系直接换算）。
 */
public class OcrTextBlock {

    /** 识别出的文本 */
    public String text;
    /** 文本块左上角 x（帧内坐标） */
    public int x;
    /** 文本块左上角 y（帧内坐标） */
    public int y;
    /** 文本块宽（像素） */
    public int w;
    /** 文本块高（像素） */
    public int h;
    /** 置信度 0~1 */
    public float confidence;

    /** gson 反序列化用无参构造 */
    public OcrTextBlock() {
    }

    public OcrTextBlock(String text, int x, int y, int w, int h, float confidence) {
        this.text = text;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.confidence = confidence;
    }

    @Override
    public String toString() {
        return "OcrTextBlock{text='" + text + "', x=" + x + ", y=" + y
                + ", w=" + w + ", h=" + h + ", confidence=" + confidence + "}";
    }
}
