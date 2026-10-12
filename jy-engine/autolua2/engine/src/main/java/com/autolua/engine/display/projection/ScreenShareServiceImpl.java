package com.autolua.engine.display.projection;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import com.autolua.engine.display.sharedmem.SharedMemoryManager;

import java.io.FileDescriptor;
import java.lang.reflect.Field;

/**
 * IScreenShareService 实现（AIDL 服务端，运行在 CaptureScreenService 所在进程）。
 *
 * <p>getSharedFds：{@link SharedMemoryManager#getFds()} 取两块共享内存 fd →
 * dup 出独立句柄装进 Bundle 跨 Binder 传输（远端进程拿到 fd 后自行 mmap 读帧）。</p>
 *
 * <p>fd int → ParcelFileDescriptor 路径说明：不能用 {@code adoptFd(fd)}——
 * adoptFd 会接管原 fd 生命周期，其 close 会把 MemoryFile 的真实 fd 一并关掉、破坏本进程
 * 共享内存；因此反射把 int 写入 {@code FileDescriptor.descriptor} 字段构出 FileDescriptor，
 * 再走公开 API {@link ParcelFileDescriptor#dup(FileDescriptor)}——dup 出的是独立副本句柄，
 * 关闭副本不影响原共享内存（与 SharedMemoryManager 反射取 descriptor 字段的既有做法一致）。</p>
 */
public class ScreenShareServiceImpl extends IScreenShareService.Stub {
    private static final String TAG = "ScreenShareServiceImpl";

    /** Bundle 键：共享内存 A 块 fd（ParcelFileDescriptor） */
    public static final String KEY_FD_A = "fdA";
    /** Bundle 键：共享内存 B 块 fd（ParcelFileDescriptor） */
    public static final String KEY_FD_B = "fdB";
    /** Bundle 键：帧宽 */
    public static final String KEY_WIDTH = "width";
    /** Bundle 键：帧高 */
    public static final String KEY_HEIGHT = "height";
    /** Bundle 键：写模式 "app"（ping-pong）/ "root"（seqlock） */
    public static final String KEY_MODE = "mode";
    /** Bundle 键：共享内存是否就绪（fd 有效） */
    public static final String KEY_READY = "ready";

    @Override
    public Bundle getSharedFds() throws RemoteException {
        SharedMemoryManager smm = SharedMemoryManager.getInstance();
        int[] fds = smm.getFds();
        boolean ready = fds[0] > 0 && fds[1] > 0;
        Bundle bundle = new Bundle();
        bundle.putInt(KEY_WIDTH, smm.getScreenWidth());
        bundle.putInt(KEY_HEIGHT, smm.getScreenHeight());
        bundle.putString(KEY_MODE, smm.isSeqlockMode() ? "root" : "app");
        bundle.putBoolean(KEY_READY, ready);
        if (!ready) {
            return bundle;
        }
        ParcelFileDescriptor pfdA = dupFdQuiet(fds[0]);
        ParcelFileDescriptor pfdB = dupFdQuiet(fds[1]);
        if (pfdA == null || pfdB == null) {
            closeQuietly(pfdA);
            closeQuietly(pfdB);
            bundle.putBoolean(KEY_READY, false);
            return bundle;
        }
        // PFD 随 Bundle 过 Binder 时由框架 dup 传输；本地副本依赖 PFD finalizer 回收
        bundle.putParcelable(KEY_FD_A, pfdA);
        bundle.putParcelable(KEY_FD_B, pfdB);
        return bundle;
    }

    @Override
    public void requestFrame() throws RemoteException {
        // App 模式手动节拍预留位：唤醒采集服务立即巡检一拍
        //（帧实际由 VirtualDisplay→ImageReader 回调持续写入共享内存）
        CaptureScreenService.requestFrameOnce();
    }

    @Override
    public boolean isReady() throws RemoteException {
        int[] fds = SharedMemoryManager.getInstance().getFds();
        return fds[0] > 0 && fds[1] > 0;
    }

    /**
     * int fd 反射包成 FileDescriptor 再 dup(FileDescriptor)，得到接管副本句柄的 PFD。
     * 失败返回 null（调用方置 ready=false 降级）。
     */
    private static ParcelFileDescriptor dupFdQuiet(int fd) {
        if (fd <= 0) {
            return null;
        }
        try {
            FileDescriptor fdesc = new FileDescriptor();
            Field descriptor = FileDescriptor.class.getDeclaredField("descriptor");
            descriptor.setAccessible(true);
            descriptor.setInt(fdesc, fd);
            return ParcelFileDescriptor.dup(fdesc);
        } catch (Throwable t) {
            Log.w(TAG, "dup fd " + fd + " failed: " + t);
            return null;
        }
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        if (pfd != null) {
            try {
                pfd.close();
            } catch (Throwable ignore) {
            }
        }
    }
}
