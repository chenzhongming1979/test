package com.autolua.engine.extension.display;

import android.annotation.SuppressLint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import com.autolua.engine.conceal.DisplayControlWrap;
import com.autolua.engine.conceal.IWindowManager;
import com.autolua.engine.conceal.SurfaceControlWrap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

public class DisplayImplement implements Display{

    private static final String TAG = "DisplayImplement";
    public static final int  VERTICAL = 1;
    public static final int  LEVEL = -1;
    /** 首次无帧等待时长（ms）：熄屏/采集停止时最长阻塞 3 秒后快速失败（任务书 §3.1） */
    private static final long FIRST_FRAME_WAIT_MS = 3000;
    /** 发生过一次"超时且仍无真实帧"后的降级等待时长（ms）：快速失败，避免每次都卡 3 秒 */
    private static final long DEGRADED_FRAME_WAIT_MS = 300;
    /** 兜底黑帧（全 0 DirectByteBuffer，宽*高*4）：仅在无真实帧时由 getDisplayBuffer/getRowStride/getPixelStride 返回 */
    private ByteBuffer fallbackFrame;
    /** 发生过一次"超时且仍无真实帧"；拿到真实帧（状态恢复）后重置 */
    private boolean frameTimeoutDegraded = false;
    private static final Point baseSize= new Point();
    private static final int defaultDirection;
    static {
        IWindowManager.getBaseDisplaySize(IWindowManager.MAIN_DISPLAY_TOKEN, baseSize);
        defaultDirection = baseSize.x >baseSize.y ? LEVEL:VERTICAL;
    }
    private final AtomicBoolean isDestroy = new AtomicBoolean(false);
    private final Object imageMutex = new Object();
    private int recordDirection;
    private IBinder iBinder;
    private final Point nowSize;
    private final HandlerThread handlerThread;
    private final Handler handler;
    private ImageReader imageReader;
    private Image nowImage = null;

    public DisplayImplement(int width,int height)
    {
        nowSize = new Point();
        imageReader = null;
        handlerThread = new HandlerThread("worker@"+hashCode());
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    public DisplayImplement()
    {
        this(0,0);
    }

    @SuppressLint("WrongConstant")
    public synchronized boolean initialize(int width, int height)
    {
        recordDirection = getDirection();
        int baseWidth = getBaseWidth();
        int baseHeight = getBaseHeight();
        int baseDirection = getBaseDirection();
        // §6.3：单边 0 也走自动取基准尺寸，避免 ImageReader.newInstance(w,0) 抛异常
        if (width <=0 || height<=0)
        {
            if(recordDirection != baseDirection)
            {
                width = baseHeight;
                height = baseWidth;
            }else
            {
                width = baseWidth;
                height = baseHeight;
            }
        }
        int baseX,baseY;
        if(recordDirection != baseDirection)
        {
            baseX = baseHeight;
            baseY = baseWidth;
        }else
        {
            baseX = baseWidth;
            baseY = baseHeight;
        }
        if (baseX <= 0 || baseY <= 0)
        {
            // §6.1b：IWindowManager + DisplayManagerGlobal 双路 fallback 后仍拿不到基准尺寸，
            // 显式快速失败给出可读原因，杜绝 ImageReader.newInstance(0,0) 的隐晦崩溃路径
            throw new RuntimeException("cannot determine base display size (IWindowManager.getBaseDisplaySize"
                    + " and DisplayManagerGlobal fallback both failed): " + baseX + "x" + baseY);
        }
        nowSize.x = width;
        nowSize.y = height;
        if(imageReader != null)
            imageReader.close();
        nowImage = null;
        imageReader = ImageReader.newInstance(width,height, PixelFormat.RGBA_8888,3);
        imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader reader) {
                synchronized (imageMutex)
                {
                    imageMutex.notifyAll();
                }
            }
        },handler);

        boolean secure = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || (Build.VERSION.SDK_INT == Build.VERSION_CODES.R && !"S"
                .equals(Build.VERSION.CODENAME));
        // §6.1a：优先走 DisplayControlWrap 统一入口（内部 SurfaceControl 静态 / DisplayControl+Transaction
        // 双路径自适应，复用任务书 §4.1 内核）；失败兜底旧 SurfaceControlWrap 直调路径并日志注明分支
        try {
            Object transaction = DisplayControlWrap.openTransaction();
            IBinder token = iBinder;
            boolean createdHere = false;
            try {
                if (token == null)
                {
                    token = DisplayControlWrap.createVirtualDisplay("AutoLua",secure);
                    createdHere = true;
                }
                DisplayControlWrap.setDisplaySurface(transaction,token,imageReader.getSurface());
                DisplayControlWrap.setDisplayLayerStack(transaction,token,0);
                DisplayControlWrap.setDisplaySize(transaction,token,width,height);
                DisplayControlWrap.setDisplayProjection(transaction,token,0,new Rect(0,0, baseX, baseY),new Rect(0,0,width,height));
            }catch (Throwable t)
            {
                if (createdHere && token != null)
                {
                    // 半途失败：回收本次新建 token（事务提交/销屏各兜一次），交由兜底路径重建，防泄漏
                    try{ DisplayControlWrap.closeTransaction(transaction); }catch (Throwable ignore){}
                    try{ DisplayControlWrap.destroyVirtualDisplay(token); }catch (Throwable ignore){}
                }
                throw t;
            }
            DisplayControlWrap.closeTransaction(transaction);
            iBinder = token;
            Log.i(TAG,"virtual display via DisplayControlWrap (mode=" + DisplayControlWrap.currentMode() + ")");
        }catch (Throwable dcError)
        {
            Log.w(TAG,"DisplayControlWrap branch failed, fallback to SurfaceControlWrap direct: " + dcError);
            iBinder = null;
            SurfaceControlWrap.openTransaction();
            try{
                iBinder = SurfaceControlWrap.createDisplay("AutoLua",secure);
                SurfaceControlWrap.setDisplaySurface(iBinder,imageReader.getSurface());
                SurfaceControlWrap.setDisplayLayerStack(iBinder,0);
                SurfaceControlWrap.setDisplaySize(iBinder,width,height);
                SurfaceControlWrap.setDisplayProjection(iBinder,0,new Rect(0,0, baseX, baseY),new Rect(0,0,width,height));
            }finally {
                SurfaceControlWrap.closeTransaction();
            }
            Log.i(TAG,"virtual display via SurfaceControlWrap legacy branch");
        }
        update();
        return true;
    }


    public int getBaseWidth()
    {
        return baseSize.x;
    }

    public int getBaseHeight()
    {
        return baseSize.y;
    }

    public int getBaseDirection()
    {
        return defaultDirection;
    }


    public int getRotation()
    {
        return IWindowManager.getRotation();
    }

    public int getBaseDensity()
    {
        return IWindowManager.getBaseDisplayDensity(IWindowManager.MAIN_DISPLAY_TOKEN);
    }

    @Override
    public void destroy() {
        if (isDestroy.compareAndSet(false,true))
        {
            handlerThread.interrupt();
            if (iBinder != null)
            {
                // §6.1a：销屏双路径——优先 DisplayControlWrap.destroyVirtualDisplay（内部已按
                // 当前路径 + 另一路径兜底），仍失败再走旧 SurfaceControlWrap.destroyDisplay 直调
                try {
                    DisplayControlWrap.destroyVirtualDisplay(iBinder);
                }catch (Throwable t)
                {
                    Log.w(TAG,"DisplayControlWrap.destroyVirtualDisplay failed, fallback SurfaceControlWrap.destroyDisplay: " + t);
                    SurfaceControlWrap.destroyDisplay(iBinder);
                }
                iBinder = null;
            }
            if(nowImage != null)
                nowImage.close();
            if(imageReader != null)
                imageReader.close();
        }
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        destroy();
    }

    public int getDirection()
    {
        return IWindowManager.getRotation()%2 == 0 ? getBaseDirection(): -getBaseDirection();
    }

    public boolean isChangeDirection()
    {
        return getDirection() != recordDirection;
    }


    @Override
    public void update() {
        if (iBinder == null)
            initialize(0,0);
        try{
            synchronized (imageMutex)
            {
                Image image = imageReader.acquireLatestImage();
                if (image == null)
                {
                    if (nowImage == null)
                    {
                        // §3.1 无帧挂死保护：wait 带超时（首次 3000ms，发生过一次超时无帧后降为 300ms）
                        long waitMs = frameTimeoutDegraded ? DEGRADED_FRAME_WAIT_MS : FIRST_FRAME_WAIT_MS;
                        long deadline = SystemClock.elapsedRealtime() + waitMs;
                        while (nowImage == null) {
                            long remain = deadline - SystemClock.elapsedRealtime();
                            if (remain <= 0)
                                break;
                            imageMutex.wait(remain);
                            nowImage = imageReader.acquireLatestImage();
                        }
                        if (nowImage == null)
                        {
                            // 超时仍无真实帧：不得抛异常（C++ 侧 JNI 调用不查异常，抛会出未定义行为），
                            // 惰性创建全 0 兜底黑帧；标记降级快速失败，状态恢复（拿到真实帧）后重置
                            if (!frameTimeoutDegraded)
                            {
                                frameTimeoutDegraded = true;
                                Log.w(TAG, "no real frame within " + waitMs + "ms, screen off or capture stopped? fallback to black frame");
                            }
                            ensureFallbackFrame();
                        }
                    }
                }else {
                    if (nowImage != null)
                        nowImage.close();
                    nowImage = image;
                    if (frameTimeoutDegraded)
                        frameTimeoutDegraded = false;   // 状态恢复：重置降级标记
                }
            }
        }catch (InterruptedException e)
        {
            throw  new RuntimeException(e);
        }

    }

    /** 惰性创建全 0 兜底黑帧（DirectByteBuffer，宽*高*4，按当前 nowSize；allocateDirect 内存保证清零） */
    private ByteBuffer ensureFallbackFrame() {
        int size = nowSize.x * nowSize.y * 4;
        if (fallbackFrame == null || fallbackFrame.capacity() != size)
        {
            ByteBuffer buffer = ByteBuffer.allocateDirect(size);
            buffer.order(ByteOrder.nativeOrder());
            fallbackFrame = buffer;
        }
        return fallbackFrame;
    }

    @Override
    public ByteBuffer getDisplayBuffer() {
        if (nowImage == null)
            update();
        if (nowImage == null)   // 仍无真实帧：返回全 0 兜底黑帧
            return ensureFallbackFrame();
        Image.Plane plane = nowImage.getPlanes()[0];
        return plane.getBuffer();
    }

    public int getWidth(){
        return nowSize.x;
    }

    public int getHeight(){
        return nowSize.y;
    }

    public int getRowStride(){
        if (nowImage == null)
            update();
        if (nowImage == null)   // 仍无真实帧：兜底 rowStride = w*4
            return nowSize.x * 4;
        return nowImage.getPlanes()[0].getRowStride();
    }

    public int getPixelStride(){
        if (nowImage == null)
            update();
        if (nowImage == null)   // 仍无真实帧：兜底 pixelStride = 4
            return 4;
        return  nowImage.getPlanes()[0].getPixelStride();
    }

    @Override
    public boolean hasRealFrame() {
        return nowImage != null;
    }

    @Override
    public boolean isRgbaOrder() {
        // root 镜像 ImageReader 历史口径 BGRA（Display.cpp 原注释），保持现状位级不变；
        // MediaProjection 共享内存管线为 RGBA（见 VisionBridge.cpp:12-16）。两条口径均待真机实测复核。
        return false;
    }

}
