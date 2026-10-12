package com.autolua.engine.display.projection;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import com.autolua.engine.display.sharedmem.FrameBuffer;
import com.autolua.engine.display.sharedmem.SharedMemoryManager;

/**
 * 截图 Provider 管理器（单例）：App / Root 双通道切换 + 统一取帧入口。
 *
 * <ul>
 *     <li>setMode/select(auto)：auto 时 uid==0 选 ROOT，否则 APP；</li>
 *     <li>captureScreen(x,y,w,h)：从共享内存读帧（TTL 1000ms 缓存）后按矩形裁剪
 *         （RGBA stride 计算）；captureFull() 返回全帧；</li>
 *     <li>prepare()：ROOT → RootScreenshotProvider.initRoot()（免授权）；
 *         APP → 需先走 CaptureScreenService.startCapture 授权流程。</li>
 * </ul>
 */
public final class ScreenshotProviderManager {
    private static final String TAG = "ScreenshotProviderManager";

    /** Provider 通道 */
    public enum Mode { APP, ROOT }

    /** 帧缓存 TTL（ms）：getLatestFrame 在 TTL 内直接回缓存 */
    public static final long FRAME_TTL_MS = 1000;

    private static volatile ScreenshotProviderManager sInstance;

    public static ScreenshotProviderManager getInstance() {
        if (sInstance == null) {
            synchronized (ScreenshotProviderManager.class) {
                if (sInstance == null) {
                    sInstance = new ScreenshotProviderManager();
                }
            }
        }
        return sInstance;
    }

    private volatile Mode mode = Mode.APP;
    private final FrameBuffer frameBuffer;

    private ScreenshotProviderManager() {
        frameBuffer = new FrameBuffer(SharedMemoryManager.getInstance());
    }

    // ------------------------------------------------------------------
    // 通道选择
    // ------------------------------------------------------------------

    public void setMode(Mode newMode) {
        if (newMode != null) {
            mode = newMode;
        }
    }

    public Mode getMode() {
        return mode;
    }

    /**
     * 按名称选择通道："root" / "app"，其他值（含 "auto"）走自动判定：
     * uid==0 → ROOT，否则 APP。
     */
    public void select(String modeOrAuto) {
        if ("root".equalsIgnoreCase(modeOrAuto)) {
            mode = Mode.ROOT;
            return;
        }
        if ("app".equalsIgnoreCase(modeOrAuto)) {
            mode = Mode.APP;
            return;
        }
        mode = Process.myUid() == 0 ? Mode.ROOT : Mode.APP;
        logMode();
    }

    private void logMode() {
        // 只打一行日志，方便排查通道判定
        android.util.Log.i(TAG, "provider mode = " + mode);
    }

    /** 启动当前通道（ROOT 免授权可直启；APP 需先完成投屏授权，此处只回报状态） */
    public boolean prepare() {
        if (mode == Mode.ROOT) {
            return RootScreenshotProvider.getInstance().initRoot();
        }
        return CaptureScreenService.isRunning();
    }

    // ------------------------------------------------------------------
    // 取帧
    // ------------------------------------------------------------------

    /**
     * 按矩形裁剪取帧（RGBA stride 计算）。
     *
     * @return 裁剪后的 RGBA 像素数据（w*h*4 字节），无帧/非法矩形返回 null
     */
    public byte[] captureScreen(int x, int y, int w, int h) {
        byte[] frame = frameBuffer.getLatestFrame(false, FRAME_TTL_MS);
        if (frame == null) {
            return null;
        }
        int frameWidth = frameBuffer.getFrameWidth();
        int frameHeight = frameBuffer.getFrameHeight();
        if (frameWidth <= 0 || frameHeight <= 0) {
            return null;
        }
        // 裁剪矩形钳制到帧边界
        if (x < 0) {
            x = 0;
        }
        if (y < 0) {
            y = 0;
        }
        if (w <= 0 || h <= 0) {
            return null;
        }
        if (x + w > frameWidth) {
            w = frameWidth - x;
        }
        if (y + h > frameHeight) {
            h = frameHeight - y;
        }
        if (w <= 0 || h <= 0) {
            return null;
        }
        int offset = frameBuffer.getHeaderOffset();
        long needed = (long) offset + ((long) (y + h - 1) * frameWidth + (x + w)) * 4;
        if (needed > frame.length) {
            return null;
        }
        byte[] out = new byte[w * h * 4];
        for (int row = 0; row < h; row++) {
            int srcPos = offset + ((y + row) * frameWidth + x) * 4;
            System.arraycopy(frame, srcPos, out, row * w * 4, w * 4);
        }
        return out;
    }

    /**
     * 全帧取帧。
     *
     * @return 整帧 RGBA 数据（App 模式已剥离 9 字节自描述头；Root seqlock 模式原样）
     */
    public byte[] captureFull() {
        byte[] frame = frameBuffer.getLatestFrame(false, FRAME_TTL_MS);
        if (frame == null) {
            return null;
        }
        int offset = frameBuffer.getHeaderOffset();
        if (offset <= 0) {
            return frame;
        }
        byte[] out = new byte[frame.length - offset];
        System.arraycopy(frame, offset, out, 0, out.length);
        return out;
    }

    /** 读侧辅助（高级用法：freshOnly / 自定义 TTL） */
    public FrameBuffer getFrameBuffer() {
        return frameBuffer;
    }

    // ------------------------------------------------------------------
    // 跨进程绑定（IScreenShareService 客户端封装，供未来 :script 进程调用；
    // 同进程模式不受影响——直接用 SharedMemoryManager / frameBuffer）
    // ------------------------------------------------------------------

    private volatile IScreenShareService remoteScreenShare;
    private volatile ServiceConnection remoteConnection;

    /**
     * 绑定远端 IScreenShareService（异步）。连接成功后经
     * {@link #getRemoteScreenShare()} 拿代理，再调 getSharedFds() 取共享内存 fd。
     *
     * @return 绑定请求是否发出（连接结果异步回调）
     */
    public boolean bindRemote(Context context) {
        if (context == null) {
            return false;
        }
        if (remoteScreenShare != null) {
            return true;
        }
        Intent intent = new Intent(CaptureScreenService.ACTION_SCREEN_SHARE);
        intent.setPackage(context.getPackageName());
        ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                remoteScreenShare = IScreenShareService.Stub.asInterface(service);
                Log.i(TAG, "remote screen share service connected");
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                // 服务进程死亡：清代理，下次 bindRemote 重连
                remoteScreenShare = null;
            }
        };
        try {
            boolean ok = context.bindService(intent, conn, Context.BIND_AUTO_CREATE);
            if (ok) {
                remoteConnection = conn;
            } else {
                Log.w(TAG, "bindRemote: bindService returned false");
            }
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "bindRemote failed: " + t);
            return false;
        }
    }

    /** 解绑远端服务（客户端进程退出前调用） */
    public void unbindRemote(Context context) {
        ServiceConnection conn = remoteConnection;
        if (conn != null && context != null) {
            try {
                context.unbindService(conn);
            } catch (Throwable ignore) {
            }
        }
        remoteConnection = null;
        remoteScreenShare = null;
    }

    /** 远端服务代理（未连接返回 null） */
    public IScreenShareService getRemoteScreenShare() {
        return remoteScreenShare;
    }
}
