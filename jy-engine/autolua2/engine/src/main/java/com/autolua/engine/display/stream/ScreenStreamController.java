package com.autolua.engine.display.stream;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import com.autolua.engine.display.projection.ScreenshotProviderManager;
import com.autolua.engine.display.sharedmem.FrameBuffer;
import com.autolua.engine.display.sharedmem.SharedMemoryManager;
import com.autolua.engine.ide.WsProtocol;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 投屏流控制器（单例，方案八 9.3）：帧源 → JPEG/H.264 双 codec → WS binary 帧。
 * <p>
 * 帧源：复用截图管线 {@link ScreenshotProviderManager}——
 * {@link FrameBuffer#getLatestFrame(boolean, long)}（freshOnly=true，共享内存 seqlock 读协议不变）；
 * App 模式需先在 app 内完成 MediaProjection 授权，root 模式免授权（prepare 时自动 initRoot）。
 * <p>
 * 双 codec：
 * <ul>
 *     <li>jpeg：RGBA → Bitmap → 可选 scale → compress(JPEG) → binary 帧（保底，全兼容）；</li>
 *     <li>h264：MediaCodec COLOR_FormatSurface 硬编（createInputSurface，Bitmap 画上即可，
 *         无需手动转 YUV）→ 取 output NALU（Annex-B）→ AVCC 化下发（主力）。
 *         CODEC_CONFIG 输出提取 SPS/PPS 转 avcC box，作为 flag=0 首帧下发
 *         （WebCodecs 直接作 VideoDecoder.configure 的 description）。</li>
 * </ul>
 * 帧格式（大端，见 WsProtocol 注释）：
 * h264 → [4B seq][4B flag(0=avcC 配置集,1=AVCC 视频帧)][4B len][payload]；
 * jpeg → [4B seq][4B w][4B h][4B len][jpeg]。
 * <p>
 * 线程模型：单推流线程（HandlerThread 语义，优先级 -2 THREAD_PRIORITY_DISPLAY），
 * 取帧 → 编码 → 发送全程同线程；异常 catch 记日志不中断循环（下次继续）。
 * 送帧节拍：循环尾按 fps sleep；静止画面跳帧由 freshOnly 语义天然覆盖
 * （无新帧 → 不编码不发送，帧间压缩静止时近零带宽）。
 * <p>
 * 会话管理：一期单订阅简化——新 stream_start 顶替旧订阅（全参数生效即干净重启会话）；
 * 连续发送失败（订阅者掉线）自动结束会话。旋转：帧循环检测帧宽高变化 →
 * 发 stream_rotate 事件 + H264 编码器随新尺寸惰性重建（等下一个 I 帧）。
 */
public final class ScreenStreamController {

    private static final String TAG = "AutoLuaStream";

    private static volatile ScreenStreamController sInstance;

    /** 单例访问 */
    public static ScreenStreamController getInstance() {
        if (sInstance == null) {
            synchronized (ScreenStreamController.class) {
                if (sInstance == null) {
                    sInstance = new ScreenStreamController();
                }
            }
        }
        return sInstance;
    }

    /** 连接发送通道（IdeServerManager 注入；binary 走流帧，text 走 stream_rotate 事件） */
    public interface ConnectionSender {
        /** 定向发送二进制帧；连接不存在/失败返回 false */
        boolean sendBinary(String clientId, byte[] data);

        /** 定向发送文本消息（JSON 事件） */
        void sendText(String clientId, String message);
    }

    // ------------------------------------------------------------------
    // 会话参数（volatile：startSession 消息线程写、推流线程读）
    // ------------------------------------------------------------------

    private volatile ConnectionSender sender;
    private volatile String clientId;
    private volatile String codec = "h264";
    private volatile int fps = 15;
    private volatile int bitrate = 1_500_000;
    private volatile int quality = 70;
    private volatile float scale = 1.0f;

    /** 推流线程与运行标志 */
    private Thread streamThread;
    private final Object sessionLock = new Object();

    // 以下状态仅推流线程访问（无需锁）
    private MediaCodec encoder;
    private Surface inputSurface;
    private MediaCodec.BufferInfo bufferInfo;
    private byte[] avccConfig;   // avcC box（SPS/PPS 配置集，flag=0 payload）
    private int encW, encH;      // 编码器尺寸（scale 对齐偶数后）
    private Bitmap srcBitmap;    // 复用源帧位图（ARGB_8888）
    private int srcW, srcH;
    private int lastFrameW, lastFrameH; // 旋转检测基线
    private boolean firstFrameSent;     // 会话首帧是否已产出（静止屏幕回退喂帧的触发条件之一）
    private final ByteArrayOutputStream jpegOut = new ByteArrayOutputStream(256 * 1024);
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private long seq;
    private int sendFails;
    /** 强制 IDR 请求标志（消息线程置位，推流线程消费执行——MediaCodec 非线程安全不跨线程直调） */
    private volatile boolean syncRequested;

    private ScreenStreamController() {
    }

    /** 注入连接发送通道（IdeServerManager 启动前调用；传 null 清除） */
    public void setSender(ConnectionSender s) {
        this.sender = s;
    }

    /** 当前是否有活动会话 */
    public boolean isStreaming() {
        return streamThread != null;
    }

    /**
     * 请求下一帧即为 IDR 关键帧（stream_sync 命令，PC 端解码器重建后恢复画面）。
     * 仅置标志，由推流线程经 MediaCodec request-sync-frame 执行；
     * 编码器不支持该参数时降级重建编码器（重新下发 avcC + IDR）。
     *
     * @return true=已受理（存在 h264 会话）；false=无会话或当前是 jpeg
     */
    public boolean requestKeyFrame() {
        if (!isStreamingSafe() || !"h264".equals(codec)) {
            return false;
        }
        syncRequested = true;
        return true;
    }

    // ------------------------------------------------------------------
    // 会话管理（由 stream_start / stream_stop handler 调用，消息线程）
    // ------------------------------------------------------------------

    /**
     * 开启/更新投屏会话。
     *
     * @param clientId 订阅连接 ID
     * @param params   stream_start data：{codec, fps, bitrate, quality, scale}
     * @return stream_started 回包 data JSON（{"w":真实宽,"h":真实高}）
     * @throws Exception 参数非法或帧源未就绪（路由器转 error 回包）
     */
    public String startSession(String clientId, JSONObject params) throws Exception {
        if (clientId == null || clientId.isEmpty()) {
            throw new IllegalArgumentException("clientId required");
        }
        // 1. 参数解析（缺省沿用当前值；越界钳制）
        String newCodec = params != null ? params.optString("codec", codec) : codec;
        if (!"h264".equals(newCodec) && !"jpeg".equals(newCodec)) {
            throw new IllegalArgumentException("codec must be h264 or jpeg");
        }
        int newFps = clamp(params != null ? params.optInt("fps", fps) : fps, 1, 60);
        int newBitrate = clamp(params != null ? params.optInt("bitrate", bitrate) : bitrate,
                100_000, 20_000_000);
        int newQuality = clamp(params != null ? params.optInt("quality", quality) : quality, 10, 100);
        float newScale = params != null ? (float) params.optDouble("scale", scale) : scale;
        if (Float.isNaN(newScale) || Float.isInfinite(newScale)) {
            newScale = 1.0f;
        }
        newScale = Math.max(0.1f, Math.min(1.0f, newScale));

        // 2. 帧源准备校验（root 免授权自动 initRoot；App 模式要求投影服务已授权运行）
        if (!ScreenshotProviderManager.getInstance().prepare()) {
            throw new IllegalStateException(
                    "frame source not ready: app mode needs MediaProjection permission"
                            + " (start capture in app first) / root mode needs su");
        }

        synchronized (sessionLock) {
            // 3. 幂等重入（同连接同参数）直接回包；任何参数变化干净重启会话（单订阅简化）
            boolean same = isStreamingSafe() && clientId != null && clientId.equals(this.clientId)
                    && codec.equals(newCodec) && fps == newFps && bitrate == newBitrate
                    && quality == newQuality && Float.compare(scale, newScale) == 0;
            if (!same) {
                stopLocked();
                this.clientId = clientId;
                this.codec = newCodec;
                this.fps = newFps;
                this.bitrate = newBitrate;
                this.quality = newQuality;
                this.scale = newScale;
                startThreadLocked();
            }
            return startedPayload();
        }
    }

    /**
     * 停止会话并释放编码器（stream_stop 或参数变化时）。
     *
     * @param clientId 订阅连接；与当前会话不匹配时忽略（防止其他连接误停）
     */
    public void stopSession(String clientId) {
        synchronized (sessionLock) {
            if (clientId == null || clientId.equals(this.clientId)) {
                stopLocked();
            }
        }
    }

    /** sessionLock 内调用：循环线程退出自然释放编码器等资源 */
    private void stopLocked() {
        running = false;
        Thread t = streamThread;
        streamThread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        clientId = null;
    }

    private boolean isStreamingSafe() {
        return streamThread != null;
    }

    /** stream_started 回包 data：真实屏幕分辨率（PC 端坐标映射基准；未读到帧时退管理器尺寸） */
    private String startedPayload() {
        int w = 0, h = 0;
        try {
            FrameBuffer fb = ScreenshotProviderManager.getInstance().getFrameBuffer();
            w = fb.getFrameWidth();
            h = fb.getFrameHeight();
        } catch (Throwable ignore) {
        }
        if (w <= 0 || h <= 0) {
            SharedMemoryManager smm = SharedMemoryManager.getInstance();
            w = smm.getScreenWidth();
            h = smm.getScreenHeight();
        }
        return "{\"w\":" + w + ",\"h\":" + h + "}";
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    // ------------------------------------------------------------------
    // 推流线程
    // ------------------------------------------------------------------

    private void startThreadLocked() {
        running = true;
        seq = 0;
        sendFails = 0;
        syncRequested = false;
        firstFrameSent = false;
        avccConfig = null;
        lastFrameW = 0;
        lastFrameH = 0;
        // 新会话接管：编码器/位图置 null 由新线程自建。旧线程（可能 join 超时仍在退出）
        // 持有的是旧状态引用，其退出清理经 generation 检查跳过，不会误杀新会话资源
        encoder = null;
        inputSurface = null;
        bufferInfo = null;
        srcBitmap = null;
        srcW = 0;
        srcH = 0;
        generation++;
        Thread t = new Thread(this::loop, "AutoLuaStream");
        t.setDaemon(true);
        streamThread = t;
        t.start();
    }

    private volatile boolean running;
    /** 会话代数：新会话接管时递增；旧线程退出时 gen 不匹配则跳过状态释放（消除跨线程 recycle 竞态） */
    private int generation;

    /**
     * 推流主循环：取帧 → 编码 → 发送全程本线程；异常记日志不中断循环；
     * 尾部按 fps 节拍 sleep（喂多快编多快）。退出时统一释放编码器/位图。
     */
    private void loop() {
        final int myGen;
        synchronized (sessionLock) {
            myGen = generation;
        }
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY); // -2
        } catch (Throwable ignore) {
        }
        Log.i(TAG, "stream loop start (gen=" + myGen + "): codec=" + codec + ", fps=" + fps
                + ", bitrate=" + bitrate + ", quality=" + quality + ", scale=" + scale);
        try {
            FrameBuffer fb = ScreenshotProviderManager.getInstance().getFrameBuffer();
            while (running) {
                long frameStart = SystemClock.uptimeMillis();
                try {
                    loopOnce(fb);
                } catch (Throwable t) {
                    // 单帧异常不中断循环（记日志下次继续）；编码器态可疑时复位重建
                    Log.w(TAG, "stream frame error (continue): " + t);
                    releaseEncoderQuiet();
                }
                long cost = SystemClock.uptimeMillis() - frameStart;
                long sleep = 1000L / Math.max(1, fps) - cost;
                if (sleep > 0) {
                    try {
                        Thread.sleep(sleep);
                    } catch (InterruptedException e) {
                        break; // stop 会话（interrupt 提前醒）
                    }
                }
            }
        } finally {
            synchronized (sessionLock) {
                if (streamThread == Thread.currentThread()) {
                    streamThread = null;
                }
                if (generation == myGen) {
                    releaseThreadState();
                } else {
                    // 已被新会话顶替（stream_start 参数变化/新订阅）：编码器与位图
                    // 已归属新线程，此处绝不能释放（recycle 竞态的根源），仅自然消亡
                    Log.i(TAG, "stale stream thread exit (gen=" + myGen
                            + ", current=" + generation + "), skip release");
                }
            }
        }
        Log.i(TAG, "stream loop exit");
    }

    /** 单次迭代：旋转检测 → 取帧（freshOnly 静止跳帧）→ 按.codec 编码发送 */
    private void loopOnce(FrameBuffer fb) throws Exception {
        byte[] raw = fb.getLatestFrame(true, 0);
        int w = fb.getFrameWidth();
        int h = fb.getFrameHeight();
        // 静止画面不合成新帧：① 会话首帧未产出（开投屏黑屏）② request-sync（stream_sync）
        // 挂起无载体帧——两种情况回退取共享内存最后一帧重喂编码器，立刻出画面/IDR
        if (raw == null && (!firstFrameSent || syncRequested)) {
            raw = fb.getLatestFrame(false, 0);
            if (raw != null) {
                Log.i(TAG, firstFrameSent
                        ? "stream_sync: static screen, re-feed last frame for IDR"
                        : "session start: static screen, feed last frame as first frame");
            }
        }
        // 旋转检测：帧宽高变化 → stream_rotate 事件（H264 编码器随新尺寸惰性重建）
        if (lastFrameW > 0 && w > 0 && h > 0 && (w != lastFrameW || h != lastFrameH)) {
            onRotated(w, h);
        }
        if (w > 0 && h > 0) {
            lastFrameW = w;
            lastFrameH = h;
        }
        if (raw == null || w <= 0 || h <= 0) {
            return; // 无新帧（静止跳帧）或帧源尚未产出
        }
        // 剥离 App 模式 9 字节自描述头（Root seqlock 模式 offset=0 原样）
        int offset = fb.getHeaderOffset();
        byte[] rgba = raw;
        if (offset > 0 && raw.length > offset) {
            rgba = Arrays.copyOfRange(raw, offset, raw.length);
        }
        if ("h264".equals(codec)) {
            encodeH264(rgba, w, h);
        } else {
            encodeJpeg(rgba, w, h);
        }
    }

    /** 旋转：发 stream_rotate 事件（新分辨率），复位编码器等下一帧重建 */
    private void onRotated(int w, int h) {
        Log.i(TAG, "screen rotated: " + w + "x" + h);
        releaseEncoderQuiet();
        // 客户端收到 stream_rotate 会 resetDecoder 等新 avcC；旋转后可能长时间静止，
        // 置回首帧未产出状态让回退喂帧机制立即送出新尺寸的 avcC + IDR
        firstFrameSent = false;
        try {
            JSONObject data = new JSONObject();
            data.put("w", w);
            data.put("h", h);
            JSONObject msg = new JSONObject();
            msg.put("type", WsProtocol.MSG_STREAM_ROTATE);
            msg.put("data", data);
            ConnectionSender s = sender;
            String cid = clientId;
            if (s != null && cid != null) {
                s.sendText(cid, msg.toString());
            }
        } catch (Throwable t) {
            Log.w(TAG, "send stream_rotate failed: " + t);
        }
    }

    // ------------------------------------------------------------------
    // JPEG 路径（保底 codec）
    // ------------------------------------------------------------------

    /** RGBA → 复用 Bitmap → 可选 scale → JPEG 压缩 → [seq][w][h][len][jpeg] binary 帧 */
    private void encodeJpeg(byte[] rgba, int w, int h) throws Exception {
        Bitmap src = obtainBitmap(w, h);
        src.copyPixelsFromBuffer(ByteBuffer.wrap(rgba));
        int dw = Math.max(2, (int) (w * scale + 0.5f));
        int dh = Math.max(2, (int) (h * scale + 0.5f));
        Bitmap draw = src;
        if (dw != w || dh != h) {
            Bitmap scaled = Bitmap.createScaledBitmap(src, dw, dh, true);
            if (scaled != src) {
                draw = scaled;
            } else {
                // 同尺寸返回原 bitmap（防御）
                dw = src.getWidth();
                dh = src.getHeight();
            }
        }
        synchronized (jpegOut) {
            jpegOut.reset();
            if (!draw.compress(Bitmap.CompressFormat.JPEG, quality, jpegOut)) {
                if (draw != src) {
                    draw.recycle();
                }
                throw new IllegalStateException("jpeg compress failed");
            }
            byte[] jpeg = jpegOut.toByteArray();
            ByteBuffer buf = ByteBuffer.allocate(16 + jpeg.length).order(ByteOrder.BIG_ENDIAN);
            buf.putInt((int) (seq++ & 0x7FFFFFFFL));
            buf.putInt(dw);
            buf.putInt(dh);
            buf.putInt(jpeg.length);
            buf.put(jpeg);
            sendBinary(buf.array());
        }
        if (draw != src) {
            draw.recycle();
        }
        firstFrameSent = true;
    }

    /** 复用源帧位图（尺寸变化时重建，1080p ARGB_8888 ~8MB 常驻） */
    private Bitmap obtainBitmap(int w, int h) {
        if (srcBitmap != null && srcW == w && srcH == h && !srcBitmap.isRecycled()) {
            return srcBitmap;
        }
        if (srcBitmap != null) {
            srcBitmap.recycle();
        }
        srcBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        srcW = w;
        srcH = h;
        return srcBitmap;
    }

    // ------------------------------------------------------------------
    // H.264 路径（主力 codec，COLOR_FormatSurface 硬编）
    // ------------------------------------------------------------------

    /** 确保编码器就绪（未建/尺寸随 scale 或旋转变化 → 重建）；失败静默，下帧重试 */
    private void ensureEncoder(int srcW, int srcH) {
        int wantW = even(Math.round(srcW * scale));
        int wantH = even(Math.round(srcH * scale));
        if (wantW < 16 || wantH < 16) {
            return; // 尺寸过小（scale 异常），不建
        }
        if (encoder != null && wantW == encW && wantH == encH) {
            return;
        }
        releaseEncoderQuiet();
        try {
            MediaFormat fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, wantW, wantH);
            fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            fmt.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2); // 2s 一个 I 帧，断连恢复快
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = encoder.createInputSurface();
            encoder.start();
            bufferInfo = new MediaCodec.BufferInfo();
            encW = wantW;
            encH = wantH;
            avccConfig = null;
            Log.i(TAG, "h264 encoder started: " + encW + "x" + encH
                    + ", bitrate=" + bitrate + ", fps=" + fps);
        } catch (Throwable t) {
            Log.e(TAG, "create h264 encoder failed: " + t);
            releaseEncoderQuiet();
        }
    }

    /** 对齐偶数（编码器普遍要求宽高为 2 的倍数） */
    private static int even(int v) {
        return v < 2 ? 2 : (v & ~1);
    }

    /**
     * 送一帧进编码器并 drain 输出：Bitmap 画到 InputSurface（Surface 输入无需手动转 YUV，
     * 编码器内部处理）→ releaseOutputBuffer(index,true) 提交 → 取 NALU 下发。
     */
    private void encodeH264(byte[] rgba, int w, int h) throws Exception {
        ensureEncoder(w, h);
        // 无论编码器是否就绪都消费 sync 请求：建失败时若标志挂着，
        // loopOnce 会因 (!firstFrameSent||syncRequested) 每帧回退喂帧空转
        boolean wantSync = syncRequested;
        syncRequested = false;
        if (encoder == null || inputSurface == null) {
            return; // 建失败，下一帧重试
        }
        if (wantSync) {
            applySyncRequest();
        }
        Bitmap src = obtainBitmap(w, h);
        src.copyPixelsFromBuffer(ByteBuffer.wrap(rgba));
        // API 23+ 走硬件画布（GPU 直写，零拷贝）；unlockCanvasAndPost 同时支持
        // software/hardware 两种画布（android-34 起 unlockHardwareCanvasAndPost 已移出公共 API）
        Canvas canvas = (Build.VERSION.SDK_INT >= 23)
                ? inputSurface.lockHardwareCanvas() : inputSurface.lockCanvas(null);
        try {
            canvas.drawColor(Color.BLACK);
            canvas.drawBitmap(src, null, new Rect(0, 0, encW, encH), paint);
        } finally {
            inputSurface.unlockCanvasAndPost(canvas);
        }
        drainH264();
        firstFrameSent = true;
    }

    /**
     * 消费 stream_sync 请求（推流线程内执行，MediaCodec 线程安全约束）：
     * request-sync-frame 让编码器下一帧强制出 IDR；编码器不支持该参数时
     * 重建编码器兜底（重新下发 avcC + IDR，效果等同）。
     */
    private void applySyncRequest() {
        MediaCodec enc = encoder;
        if (enc == null) {
            return;
        }
        try {
            Bundle params = new Bundle();
            // MediaFormat.PARAMETER_KEY_REQUEST_SYNC_FRAME 的字面值（"request-sync"），
            // 避免不同 SDK 档位常量可见性差异；运行时由编码器决定是否支持，不支持走重建兜底
            params.putInt("request-sync", 0);
            enc.setParameters(params);
            Log.i(TAG, "stream_sync: request-sync-frame applied");
        } catch (Throwable t) {
            Log.w(TAG, "stream_sync: request-sync unsupported, rebuild encoder: " + t);
            releaseEncoderQuiet(); // 下一帧 ensureEncoder 重建 → 新 avcC + IDR
        }
    }

    /** 取出编码器输出：CODEC_CONFIG 提取 SPS/PPS（avcC 首帧下发）；普通帧 AVCC 化下发 */
    private void drainH264() throws Exception {
        MediaCodec enc = encoder;
        MediaCodec.BufferInfo info = bufferInfo;
        if (enc == null || info == null) {
            return;
        }
        while (running) {
            int idx = enc.dequeueOutputBuffer(info, 0);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // 部分编码器经 format changed 携带 csd（兜底提取，主路径走 CODEC_CONFIG buffer）
                if (avccConfig == null) {
                    tryExtractCsd(enc.getOutputFormat());
                }
                continue;
            }
            if (idx < 0) {
                continue;
            }
            ByteBuffer out = enc.getOutputBuffer(idx);
            if (out != null && info.size > 0) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // SPS/PPS（Annex-B）→ avcC box 缓存并作为 flag=0 首帧下发
                    byte[] annexb = copyRange(out, info.offset, info.size);
                    byte[][] spsPps = extractSpsPps(annexb);
                    if (spsPps[0] != null && spsPps[1] != null) {
                        avccConfig = buildAvcc(spsPps[0], spsPps[1]);
                        sendH264(0, avccConfig);
                        Log.i(TAG, "sps/pps sent (avcC " + avccConfig.length + "B)");
                    }
                } else if (info.size > 4) {
                    // 视频帧：Annex-B → AVCC（每 NALU 前 4B 大端长度，WebCodecs avc 格式）
                    byte[] frame = copyRange(out, info.offset, info.size);
                    sendH264(1, annexbToAvcc(frame));
                }
            }
            enc.releaseOutputBuffer(idx, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                break;
            }
        }
    }

    /** 从输出 format 的 csd-0/csd-1 兜底提取 SPS/PPS（剥离起始码后构造 avcC） */
    private void tryExtractCsd(MediaFormat fmt) {
        try {
            ByteBuffer csd0 = fmt.getByteBuffer("csd-0");
            ByteBuffer csd1 = fmt.getByteBuffer("csd-1");
            if (csd0 == null || csd1 == null) {
                return;
            }
            byte[] sps = stripStartCode(toArray(csd0));
            byte[] pps = stripStartCode(toArray(csd1));
            if (sps.length > 0 && pps.length > 0) {
                avccConfig = buildAvcc(sps, pps);
                sendH264(0, avccConfig);
            }
        } catch (Throwable t) {
            Log.w(TAG, "extract csd failed: " + t);
        }
    }

    private static byte[] toArray(ByteBuffer buf) {
        ByteBuffer dup = buf.duplicate();
        byte[] r = new byte[dup.remaining()];
        dup.get(r);
        return r;
    }

    /** 拷贝 buffer 的 [offset, offset+size) 区段（不动原 position） */
    private static byte[] copyRange(ByteBuffer out, int offset, int size) {
        ByteBuffer dup = out.duplicate();
        dup.position(offset);
        dup.limit(offset + size);
        byte[] r = new byte[size];
        dup.get(r);
        return r;
    }

    /** 剥离开头 3/4 字节起始码（csd NALU 通常自带） */
    private static byte[] stripStartCode(byte[] nalu) {
        int s = 0;
        if (nalu.length >= 4 && nalu[0] == 0 && nalu[1] == 0 && nalu[2] == 0 && nalu[3] == 1) {
            s = 4;
        } else if (nalu.length >= 3 && nalu[0] == 0 && nalu[1] == 0 && nalu[2] == 1) {
            s = 3;
        }
        return s > 0 ? Arrays.copyOfRange(nalu, s, nalu.length) : nalu;
    }

    // ------------------------------------------------------------------
    // NALU 工具（Annex-B ↔ AVCC）
    // ------------------------------------------------------------------

    /** Annex-B 起始码切分：返回 NALU 列表（无起始码；NALU 尾部 trailing zero 一并剥离） */
    private static List<byte[]> splitNalus(byte[] data) {
        List<byte[]> out = new ArrayList<>();
        int n = data.length;
        int start = -1;
        int i = 0;
        while (i + 2 < n) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                if (start >= 0) {
                    int end = i;
                    while (end > start && data[end - 1] == 0) {
                        end--; // 4B 起始码的前导 00 与 trailing zeros 归属边界，剥离
                    }
                    if (end > start) {
                        out.add(Arrays.copyOfRange(data, start, end));
                    }
                }
                start = i + 3;
                i = start;
            } else {
                i++;
            }
        }
        if (start >= 0 && start < n) {
            int end = n;
            while (end > start && data[end - 1] == 0) {
                end--;
            }
            if (end > start) {
                out.add(Arrays.copyOfRange(data, start, end));
            }
        }
        return out;
    }

    /** Annex-B → AVCC：每 NALU 前置 4B 大端长度（WebCodecs avc 格式 chunk data） */
    private static byte[] annexbToAvcc(byte[] annexb) {
        List<byte[]> nalus = splitNalus(annexb);
        if (nalus.isEmpty()) {
            return annexb; // 解析失败兜底原样（PC 端按 annexb 容错）
        }
        int total = 0;
        for (byte[] nalu : nalus) {
            total += 4 + nalu.length;
        }
        ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN);
        for (byte[] nalu : nalus) {
            buf.putInt(nalu.length);
            buf.put(nalu);
        }
        return buf.array();
    }

    /** 从 CODEC_CONFIG（Annex-B SPS+PPS）提取裸 NALU：[sps(type=7), pps(type=8)] */
    private static byte[][] extractSpsPps(byte[] annexb) {
        byte[] sps = null;
        byte[] pps = null;
        for (byte[] nalu : splitNalus(annexb)) {
            if (nalu.length < 1) {
                continue;
            }
            int type = nalu[0] & 0x1F;
            if (type == 7 && sps == null) {
                sps = nalu;
            } else if (type == 8 && pps == null) {
                pps = nalu;
            }
        }
        return new byte[][]{sps, pps};
    }

    /**
     * 构造 avcC box（WebCodecs VideoDecoder.configure 的 description，AVCDecoderConfigurationRecord）：
     * configurationVersion/profile/compat/level + lengthSizeMinusOne=3 + SPS + PPS。
     */
    private static byte[] buildAvcc(byte[] sps, byte[] pps) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(11 + sps.length + pps.length);
        out.write(1); // configurationVersion
        out.write(sps.length > 3 ? (sps[1] & 0xFF) : 0x64); // AVCProfileIndication（兜底 High 0x64）
        out.write(sps.length > 3 ? (sps[2] & 0xFF) : 0x00); // profile_compatibility
        out.write(sps.length > 3 ? (sps[3] & 0xFF) : 0x28); // AVCLevelIndication（兜底 L4.0）
        out.write(0xFF); // reserved(111111) + lengthSizeMinusOne=3（4B 长度前缀）
        out.write(0xE1); // reserved(111) + numOfSequenceParameterSets=1
        writeNalu(out, sps);
        out.write(1); // numOfPictureParameterSets
        writeNalu(out, pps);
        return out.toByteArray();
    }

    private static void writeNalu(ByteArrayOutputStream out, byte[] nalu) {
        out.write((nalu.length >>> 8) & 0xFF);
        out.write(nalu.length & 0xFF);
        out.write(nalu, 0, nalu.length);
    }

    // ------------------------------------------------------------------
    // 发送（订阅者掉线感知：连续失败自动结束会话）
    // ------------------------------------------------------------------

    /** h264 binary 帧：[4B seq][4B flag][4B len][payload] */
    private void sendH264(int flag, byte[] payload) {
        ByteBuffer buf = ByteBuffer.allocate(12 + payload.length).order(ByteOrder.BIG_ENDIAN);
        buf.putInt((int) (seq++ & 0x7FFFFFFFL));
        buf.putInt(flag);
        buf.putInt(payload.length);
        buf.put(payload);
        sendBinary(buf.array());
    }

    private void sendBinary(byte[] frame) {
        ConnectionSender s = sender;
        String cid = clientId;
        if (s == null || cid == null) {
            return;
        }
        if (s.sendBinary(cid, frame)) {
            sendFails = 0;
        } else {
            sendFails++;
            if (sendFails >= 30) {
                // 订阅者已断线：结束会话（置标志让循环自然退出并清理，不在本线程 join 自己）
                Log.w(TAG, "send binary failed " + sendFails + " times, stop stream session");
                running = false;
            }
        }
    }

    // ------------------------------------------------------------------
    // 资源释放
    // ------------------------------------------------------------------

    /** 复位编码器（失败静默；仅在推流线程或会话清理路径调用） */
    private void releaseEncoderQuiet() {
        MediaCodec enc = encoder;
        encoder = null;
        Surface sf = inputSurface;
        inputSurface = null;
        avccConfig = null;
        if (enc != null) {
            try {
                enc.stop();
            } catch (Throwable ignore) {
            }
            try {
                enc.release();
            } catch (Throwable ignore) {
            }
        }
        if (sf != null) {
            try {
                sf.release();
            } catch (Throwable ignore) {
            }
        }
    }

    /** 推流线程退出清理（编码器 + 复用位图） */
    private void releaseThreadState() {
        releaseEncoderQuiet();
        Bitmap bmp = srcBitmap;
        srcBitmap = null;
        srcW = 0;
        srcH = 0;
        if (bmp != null) {
            bmp.recycle();
        }
    }

    /** 调试信息（capabilities 面板用） */
    @Override
    public String toString() {
        return String.format(Locale.US,
                "ScreenStreamController{streaming=%s, codec=%s, fps=%d, bitrate=%d, quality=%d, scale=%.2f}",
                isStreamingSafe(), codec, fps, bitrate, quality, scale);
    }
}
