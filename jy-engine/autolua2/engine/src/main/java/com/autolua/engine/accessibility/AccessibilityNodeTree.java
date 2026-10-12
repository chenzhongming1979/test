package com.autolua.engine.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 整树 XML 的 PFD 流式传输（对照懒人 IIi.java:630-642）：
 * 大节点树 XML 可超过 Binder 1MB 事务缓冲，改为写入 {@link ParcelFileDescriptor#createPipe()}
 * 的写端，调用方/远端进程从读端流式读取。
 *
 * <p>返回 Bundle{status:int, pfd:ParcelFileDescriptor}，为后续跨进程传输（AIDL）做准备；
 * 当前提供单进程直连路径。</p>
 */
public final class AccessibilityNodeTree {
    private static final String TAG = "AccessibilityNodeTree";

    /** Bundle 键：状态码 */
    public static final String KEY_STATUS = "status";
    /** Bundle 键：管道读端（ParcelFileDescriptor） */
    public static final String KEY_PFD = "pfd";
    /** 状态：成功 */
    public static final int STATUS_OK = 0;
    /** 状态：失败 */
    public static final int STATUS_ERROR = -1;

    private AccessibilityNodeTree() {
    }

    /**
     * dump 整树并写入管道，返回 Bundle{status, pfd}。
     *
     * <p>XML 在后台线程写入管道写端（写完自动关闭写端），调用方持有读端 pfd 流式读取；
     * 管道缓冲有限（约 64KB），大 XML 必须异步写，避免与读端互相阻塞死锁。</p>
     *
     * @param service          无障碍服务（需已连接）
     * @param includeInvisible 是否包含不可见节点
     * @param targetW          目标坐标系宽（&lt;=0 不缩放）
     * @param targetH          目标坐标系高
     * @return status=STATUS_OK 时 pfd 为管道读端；status=STATUS_ERROR 表示创建管道失败
     */
    public static Bundle dumpToPipe(final AccessibilityService service, final boolean includeInvisible,
                                    final int targetW, final int targetH) {
        Bundle bundle = new Bundle();
        ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            Log.w(TAG, "create pipe failed", e);
            bundle.putInt(KEY_STATUS, STATUS_ERROR);
            return bundle;
        }
        final ParcelFileDescriptor readEnd = pipe[0];
        final ParcelFileDescriptor writeEnd = pipe[1];
        Thread writer = new Thread(new Runnable() {
            @Override
            public void run() {
                // AutoCloseOutputStream：写完自动关闭写端，读端随之读到 EOF
                OutputStream os = new ParcelFileDescriptor.AutoCloseOutputStream(writeEnd);
                try {
                    NodeXmlDumper.dumpXml(service, includeInvisible, targetW, targetH, os);
                } finally {
                    try {
                        os.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }, "al-node-xml-pipe");
        writer.setDaemon(true);
        writer.start();
        bundle.putInt(KEY_STATUS, STATUS_OK);
        bundle.putParcelable(KEY_PFD, readEnd);
        return bundle;
    }

    /**
     * 单进程直连路径：dump 整树写入管道并同步读回字符串（验证 PFD 链路与本地调用用）。
     */
    public static String dumpViaPipe(AccessibilityService service, boolean includeInvisible,
                                     int targetW, int targetH) {
        Bundle bundle = dumpToPipe(service, includeInvisible, targetW, targetH);
        if (bundle.getInt(KEY_STATUS, STATUS_ERROR) != STATUS_OK) {
            return null;
        }
        ParcelFileDescriptor pfd = bundle.getParcelable(KEY_PFD);
        if (pfd == null) {
            return null;
        }
        try {
            InputStream is = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = is.read(buffer)) > 0) {
                baos.write(buffer, 0, n);
            }
            return baos.toString("UTF-8");
        } catch (IOException e) {
            Log.w(TAG, "read pipe failed", e);
            return null;
        }
    }
}
