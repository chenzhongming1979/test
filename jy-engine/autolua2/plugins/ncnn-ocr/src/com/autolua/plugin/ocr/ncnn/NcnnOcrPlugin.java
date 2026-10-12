package com.autolua.plugin.ocr.ncnn;

import com.autolua.engine.plugin.IOcrPlugin;
import com.autolua.engine.plugin.IPluginContext;
import com.autolua.engine.plugin.OcrTextBlock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * PP-OCR(ncnn) 真实 OCR 插件：det(DBNet 文本检测) + rec(CRNN/SVTR 文本识别)。
 * <p>
 * - 推理全部在 native 层（libncnnocr.so，静态链入 ncnn），Java 只做模型分发与结果搬运；
 * - 模型查找顺序：
 *   ① classes.jar 内资源 {@code models/*}（打包时自动释放到 pluginDir/models，推荐，
 *      随插件 zip 一起过 sha256 校验，单文件分发）；
 *   ② pluginDir/models（上次已释放，或 root 手工 push）；
 *   ③ /data/local/tmp/autolua_ocr_models（开发迭代：换模型免重打 zip，root 进程直读）；
 * - so 交付走宿主模型：onInit 里 loadNativeLibrary("libncnnocr")，
 *   zip 内 lib/&lt;abi&gt;/libncnnocr.so 由 PluginManager 解压到位。
 * <p>
 * 模型转换见本插件目录 MODEL_CONVERT.md；打包构建见 build.ps1 / README.md。
 */
public class NcnnOcrPlugin implements IOcrPlugin {

    private static final String TAG = "NcnnOcr";

    /** jar 内模型资源目录名（与 build.ps1 打包布局一致） */
    private static final String MODEL_RES_DIR = "models";
    /** 模型文件清单（缺一不可） */
    private static final String[] MODEL_FILES = {
            "det.ncnn.param", "det.ncnn.bin", "rec.ncnn.param", "rec.ncnn.bin", "ppocr_keys_v1.txt"
    };
    /** 开发迭代直读目录（root 进程可读；换模型免重打 zip） */
    private static final String FALLBACK_MODEL_DIR = "/data/local/tmp/autolua_ocr_models";
    /** 推理线程数：按核数自适应（2~4）。真机大核多（8 核时取 4），弱 vCPU 模拟器 4 核取 4；
     *  性能基线（720x1280 全屏，模拟器 4 vCPU）：帧获取 31ms + 全屏识别 ~4.8s（24 块 rec 占大头），
     *  模拟器 vCPU 单核弱不代表真机，真机数据待实测 */
    private static final int NUM_THREADS =
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));

    private volatile IPluginContext ctx;
    /** native 推理会话是否就绪（onInit 成功置 true） */
    private volatile boolean nativeReady;

    // ---- native 桥（libncnnocr.so）----
    private static native boolean nativeInit(String detParam, String detBin,
                                             String recParam, String recBin,
                                             String dictPath, int numThreads);
    private static native Object[] nativeRecognize(byte[] frame, int frameW, int frameH,
                                                   int rectX, int rectY, int rectW, int rectH);
    private static native void nativeDestroy();

    @Override
    public void onInit(IPluginContext ctx) throws Exception {
        this.ctx = ctx;
        ctx.log(TAG, "onInit, abi=" + ctx.getAbi());

        // so 加载：经插件自身 classloader（库须与插件类同类加载器，ART 按类加载器
        // 匹配 native 方法所属库；宿主侧 System.load 注册的库插件类绑定不到）。
        // ① System.loadLibrary：DexClassLoader 搜索路径由宿主 instantiate 注入
        //    （宿主 nativeLibraryDir 优先 + 插件 so 根目录）。模拟器命中宿主 jniLibs
        //    预置的 so（libnb 转译层对私有目录 so 绑定异常的规避）。
        // ② 回落 System.load(zip 内解压产物绝对路径)：同一 so 只能属一个类加载器——
        //    宿主 jniLibs 的 so 已被另一实例（loadAll 重载）的 classloader 注册时
        //    ①会 ULE，此时 soDir 路径尚未被占用，绝对路径加载仍在本 classloader 内。
        try {
            System.loadLibrary("ncnnocr");
            ctx.log(TAG, "loaded libncnnocr.so via loadLibrary");
        } catch (UnsatisfiedLinkError e) {
            File so = new File(ctx.getNativeLibraryDir(), "libncnnocr.so");
            if (!so.isFile()) {
                throw new IllegalStateException("libncnnocr.so 加载失败：搜索路径全 miss（宿主 jniLibs + " + so + "）", e);
            }
            System.load(so.getAbsolutePath());
            ctx.log(TAG, "loaded libncnnocr.so from plugin so dir (fallback): " + so);
        }

        File modelDir = resolveModels(ctx);
        if (modelDir == null) {
            // 模型缺失不阻断安装（模型可后补），但 recognize 会抛状态异常
            ctx.log(TAG, "模型未找到（jar 资源 / pluginDir/models / " + FALLBACK_MODEL_DIR
                    + " 均无）。可: adb push models/* " + FALLBACK_MODEL_DIR + "/ 后重装本插件");
            return;
        }
        ctx.log(TAG, "modelDir=" + modelDir.getAbsolutePath());

        nativeReady = nativeInit(
                file(modelDir, "det.ncnn.param"), file(modelDir, "det.ncnn.bin"),
                file(modelDir, "rec.ncnn.param"), file(modelDir, "rec.ncnn.bin"),
                file(modelDir, "ppocr_keys_v1.txt"), NUM_THREADS);
        ctx.log(TAG, "nativeInit -> " + nativeReady);
        if (!nativeReady) {
            throw new IllegalStateException("nativeInit 失败，详情见 logcat " + TAG);
        }
    }

    @Override
    public void onRelease() {
        if (nativeReady) {
            try {
                nativeDestroy();
            } catch (Throwable ignore) {
                // so 无法安全卸载（System.load 无 unload），清理内部状态即可
            }
            nativeReady = false;
        }
        ctx = null;
    }

    @Override
    public String getId() {
        return "com.autolua.plugin.ocr.ncnn";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String[] getProvides() {
        return new String[]{"ocr"};
    }

    @Override
    public List<OcrTextBlock> recognize(byte[] frame, int frameW, int frameH,
                                        int rectX, int rectY, int rectW, int rectH) throws Exception {
        if (!nativeReady) {
            throw new IllegalStateException("OCR 未就绪：onInit 时模型缺失（见 onInit 日志）");
        }
        if (frame == null) {
            throw new IllegalArgumentException("frame is null");
        }
        Object[] r = nativeRecognize(frame, frameW, frameH, rectX, rectY, rectW, rectH);
        if (r == null) {
            return Collections.emptyList();
        }
        String[] texts = (String[]) r[0];
        int[] boxes = (int[]) r[1];
        float[] confs = (float[]) r[2];
        int n = texts.length;
        List<OcrTextBlock> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new OcrTextBlock(texts[i], boxes[4 * i], boxes[4 * i + 1],
                    boxes[4 * i + 2], boxes[4 * i + 3], confs[i]));
        }
        return out;
    }

    // ------------------------------------------------------------------

    /**
     * 模型解析：jar 资源 → pluginDir/models → /data/local/tmp/autolua_ocr_models。
     *
     * @return 可用的模型目录；全部缺失返回 null
     */
    private File resolveModels(IPluginContext ctx) throws IOException {
        // ① classes.jar 内资源 models/*（build.ps1 打包时放入），释放到 pluginDir/models
        File outDir = new File(ctx.getPluginDir(), MODEL_RES_DIR);
        if (extractFromJar(outDir)) {
            return outDir;
        }
        // ② pluginDir/models：上次已释放过，或 root 手工 push 进来
        if (hasAllModels(outDir)) {
            return outDir;
        }
        // ③ 开发直读目录（root 进程）；沙箱进程读不到则返回 null
        File fallback = new File(FALLBACK_MODEL_DIR);
        if (hasAllModels(fallback)) {
            return fallback;
        }
        return null;
    }

    /** 从本插件 classes.jar（DexClassLoader 支持 jar 资源）提取 models/*，全部命中才释放 */
    private boolean extractFromJar(File outDir) throws IOException {
        InputStream probe = null;
        try {
            probe = getClass().getResourceAsStream("/" + MODEL_RES_DIR + "/" + MODEL_FILES[0]);
        } finally {
            closeQuietly(probe);
        }
        if (probe == null) {
            return false; // zip 未内嵌模型（未放 models/ 就打包）
        }
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            throw new IOException("mkdirs failed: " + outDir);
        }
        for (String name : MODEL_FILES) {
            File target = new File(outDir, name);
            InputStream in = getClass().getResourceAsStream("/" + MODEL_RES_DIR + "/" + name);
            if (in == null) {
                ctx.log(TAG, "jar 资源缺 " + name + "，放弃 jar 释放路径");
                return false;
            }
            // 已存在且长度一致则跳过（模型 bin 数 MB，避免每次初始化重写）
            // available() 个别实现可能返回 0，须 >0 才信任并做长度比对
            long expected = in.available();
            if (expected > 0 && target.isFile() && target.length() == expected) {
                closeQuietly(in);
                continue;
            }
            OutputStream os = new FileOutputStream(target);
            try {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            } finally {
                closeQuietly(os);
                closeQuietly(in);
            }
            ctx.log(TAG, "extracted " + name + " -> " + target.getAbsolutePath());
        }
        return true;
    }

    private static boolean hasAllModels(File dir) {
        if (!dir.isDirectory()) {
            return false;
        }
        for (String name : MODEL_FILES) {
            File f = new File(dir, name);
            if (!f.isFile() || f.length() == 0) {
                return false;
            }
        }
        return true;
    }

    private static String file(File dir, String name) {
        return new File(dir, name).getAbsolutePath();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignore) {
            }
        }
    }
}
