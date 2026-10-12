package com.autolua.engine.extension.display;

import java.nio.ByteBuffer;

public interface Display {
    int getBaseWidth();
    int getBaseHeight();
    int getBaseDirection();
    int getRotation();
    int getBaseDensity();
    void destroy();
    int getDirection();
    boolean isChangeDirection();
    void update();
    ByteBuffer getDisplayBuffer();
    int getWidth();
    int getHeight();
    int getRowStride();
    int getPixelStride();
    boolean initialize(int width,int height);
    /** 当前是否有真实帧（区别于熄屏/未采集时的兜底黑帧） */
    boolean hasRealFrame();
    /** 帧像素字节序：true=RGBA（R 在 [0]，MediaProjection 共享内存管线）；false=BGRA（root 镜像 ImageReader 历史口径） */
    boolean isRgbaOrder();
}
