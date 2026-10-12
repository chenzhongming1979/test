package com.autolua.engine.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 无障碍截图（API 30+，免 root / 免 MediaProjection，沙箱模式可用）：
 * 经 {@link AutoLuaAccessibilityService} 调
 * {@code takeScreenshot(displayId=Display.DEFAULT_DISPLAY, executor, callback)} 抓主屏，
 * CountDownLatch 同步等待回调；HardwareBuffer → {@code Bitmap.wrapHardwareBuffer}
 * （colorSpace 取 ScreenshotResult.getColorSpace()）→ copy(ARGB_8888) 软件位图，
 * finally 中 close 硬件缓冲。
 *
 * <p>前置条件：服务配置 {@code android:canTakeScreenshot="true"}（系统据此授予
 * {@link AccessibilityServiceInfo#CAPABILITY_CAN_TAKE_SCREENSHOT} 能力，动态 setServiceInfo
 * 改不了 capabilities，见 onServiceConnected 仅重写 flags/eventTypes）。</p>
 *
 * <p>注意：内部阻塞等待回调，禁止主线程调用（回调走独立执行器线程，不受调用线程阻塞影响）。</p>
 */
public final class AccessibilityCapture {
    private static final String TAG = "AccessibilityCapture";

    /** 默认截图超时（毫秒） */
    public static final long DEFAULT_TIMEOUT_MS = 3000;

    /** 回调专用单线程执行器：与调用线程/主线程隔离，调用线程 latch 阻塞时回调仍可送达 */
    private static final Executor CALLBACK_EXECUTOR = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "a11y-capture");
            thread.setDaemon(true);
            return thread;
        }
    });

    private AccessibilityCapture() {
    }

    /** 截图能力是否就绪：API 30+ 且无障碍服务已连接且具备 CAPABILITY_CAN_TAKE_SCREENSHOT */
    public static boolean isSupported() {
        if (Build.VERSION.SDK_INT < 30) {
            return false; // API 30 硬闸：takeScreenshot 体系起点
        }
        AutoLuaAccessibilityService service = AutoLuaAccessibilityService.getInstance();
        if (service == null) {
            return false; // 服务未连接快速失败
        }
        AccessibilityServiceInfo info = service.getServiceInfo();
        return info != null
                && (info.getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0;
    }

    /** 截图（默认超时 {@link #DEFAULT_TIMEOUT_MS}）；失败返回 null */
    public static Bitmap takeScreenshot() {
        return takeScreenshot(DEFAULT_TIMEOUT_MS);
    }

    /**
     * 截图主屏，同步等待回调（超时可调）。
     *
     * @param timeoutMs 超时（毫秒），&lt;=0 时用默认 3000
     * @return 软件 ARGB_8888 Bitmap（像素可直接读取，供图色管线消费）；失败/超时返回 null
     */
    public static Bitmap takeScreenshot(long timeoutMs) {
        if (!isSupported()) {
            return null;
        }
        AutoLuaAccessibilityService service = AutoLuaAccessibilityService.getInstance();
        if (service == null) {
            return null;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final Bitmap[] result = new Bitmap[1];
        try {
            service.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, CALLBACK_EXECUTOR,
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult screenshotResult) {
                            HardwareBuffer buffer = screenshotResult.getHardwareBuffer();
                            try {
                                Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer,
                                        screenshotResult.getColorSpace());
                                if (hardware != null) {
                                    // 硬件位图像素不可直接读取：拷贝为软件 ARGB_8888（GPU 回读），
                                    // 之后关闭 HardwareBuffer 不影响返回的软件位图
                                    result[0] = hardware.copy(Bitmap.Config.ARGB_8888, false);
                                }
                            } catch (Throwable t) {
                                Log.w(TAG, "wrap/copy screenshot failed", t);
                            } finally {
                                buffer.close(); // 硬件缓冲用完即还（try-finally 清理）
                            }
                            latch.countDown();
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            Log.w(TAG, "takeScreenshot failed, errorCode=" + errorCode);
                            latch.countDown();
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "takeScreenshot dispatch failed", t);
            return null;
        }
        try {
            long timeout = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
            if (!latch.await(timeout, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "takeScreenshot timeout after " + timeout + "ms");
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return result[0];
    }
}
