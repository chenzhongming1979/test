package com.autolua.engine.ide;

/**
 * 三期图色加速 JNI 通道：C++ 实现见 engine/src/main/cpp/extension/VisionBridge.cpp，
 * 静态注册（Java_com_autolua_engine_ide_VisionJni_*），与
 * {@link com.autolua.engine.display.sharedmem.SharedMemoryManager} 同库（libengine）。
 * <p>
 * 帧格式均为 RGBA 字节流（R 在每像素第 0 字节），与
 * ScreenshotProviderManager.captureFull() 一致。
 * 全部方法返回 int[]；<b>返回 null 表示 JNI 通道不可用/参数非法</b>，
 * 调用方（VisionCommandHandler）应回退二期纯 Java 实现。
 */
final class VisionJni {

    static {
        // 与 SharedMemoryManager 一致加载 libengine.so（重复加载为无操作）
        System.loadLibrary("engine");
    }

    private VisionJni() {
    }

    /**
     * 找图：frame（整帧 RGBA）内 rect 区域滑窗匹配模板（RGBA 字节，Java 侧已解码 PNG）。
     * 判定与 Java 回退实现一致：锚点预筛选 + 逐像素三通道容差 + 命中率 ≥ threshold。
     *
     * @return {found, x, y, similarity千分比}（x/y 为帧内坐标 = 屏幕绝对坐标）；
     * 未找到 {0,-1,-1,0}；null = 通道不可用
     */
    static native int[] nativeFindImage(byte[] frame, int frameW, int frameH,
                                        byte[] templateRgba, int templateW, int templateH,
                                        double threshold,
                                        int rectX, int rectY, int rectW, int rectH);

    /**
     * 比色：points（帧内绝对坐标 {x0,y0,...}）逐点与 colors（0xRRGGBB）容差比对，
     * 全部命中才返回 1（调用方需先做搜索区域边界预检）。
     *
     * @return {match:0/1}；null = 通道不可用
     */
    static native int[] nativeCmpColor(byte[] frame, int frameW, int frameH,
                                       int[] points, int[] colors, double similarity);

    /**
     * 多点找色：offsets 为各校验点相对锚点的偏移（{0,0,ox1,oy1,...}，含锚点自身），
     * colors[0] 为锚点色；在 rect 内行主序扫描锚点，命中后校验偏移点（须仍在 rect 内）。
     *
     * @return {found, anchorX, anchorY, sim千分比}（锚点为帧内坐标）；未找到 {0,-1,-1,0}；
     * null = 通道不可用
     */
    static native int[] nativeFindMultiColor(byte[] frame, int frameW, int frameH,
                                             int[] offsets, int[] colors, double similarity,
                                             int rectX, int rectY, int rectW, int rectH);
}
