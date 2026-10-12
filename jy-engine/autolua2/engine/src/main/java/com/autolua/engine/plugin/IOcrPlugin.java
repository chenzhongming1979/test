package com.autolua.engine.plugin;

import java.util.List;

/**
 * OCR 插件契约（第一个目标插件类型，方案七）。
 * <p>
 * 实现 {@code provides = ["ocr"]} + 本接口后，宿主 {@code PluginManager.getOcrPlugin()}
 * 可便捷查找；IDE WS 通道 {@code ocr} 命令自动从"能力不可用"切换为插件识别。
 */
public interface IOcrPlugin extends IPlugin {

    /**
     * 在 RGBA 帧内识别文字。
     *
     * @param frame 整帧 RGBA 像素流（frameW*frameH*4 字节，行主序）
     * @param frameW 帧宽（像素）
     * @param frameH 帧高（像素）
     * @param rectX 识别区域左上角 x（帧内坐标；帧原点即屏幕原点）
     * @param rectY 识别区域左上角 y
     * @param rectW 识别区域宽
     * @param rectH 识别区域高
     * @return 文本块列表（坐标为帧内坐标）；无文字返回空列表，不可返回 null
     * @throws Exception 识别失败（宿主转为 ocr plugin error 回包）
     */
    List<OcrTextBlock> recognize(byte[] frame, int frameW, int frameH,
                                 int rectX, int rectY, int rectW, int rectH) throws Exception;
}
