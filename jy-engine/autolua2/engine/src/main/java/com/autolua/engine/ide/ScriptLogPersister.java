package com.autolua.engine.ide;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 脚本日志设备端落盘（单例）。
 * <p>
 * 目录策略（三级探测，取第一个可写者，init 时确定）：
 * <ol>
 *   <li>{@code /sdcard/Pictures/autolua_logs}（需 MANAGE_EXTERNAL_STORAGE 授权/旧权限模型）</li>
 *   <li>{@code /sdcard/<packageName>/logs}（同上，需所有文件访问权限）</li>
 *   <li>{@code /sdcard/Android/data/<pkg>/files/logs}（应用专属外部目录，免权限兜底，必成）</li>
 * </ol>
 * 文件策略：按天 {@code script_yyyyMMdd.log} 追加；单文件超 {@link #MAX_FILE_BYTES}
 * 滚动 {@code script_yyyyMMdd_N.log}；启动时清理 {@link #RETAIN_DAYS} 天前的旧文件。
 * 全部 IO 在专用单线程执行，写失败静默（不影响脚本运行）；IDE 未连接时同样落盘
 * （挂在 dispatchScriptOutput 汇聚点，root/app 两种运行模式均可达）。
 */
public final class ScriptLogPersister {

    private static final String TAG = "ScriptLogPersister";
    private static final String FILE_PREFIX = "script_";
    private static final String FILE_SUFFIX = ".log";
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    private static final int RETAIN_DAYS = 7;
    private static final long DAY_MS = 24L * 3600 * 1000;

    private static volatile ScriptLogPersister sInstance;

    private volatile File logDir;
    private volatile boolean inited;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "script-log-persister");
        t.setDaemon(true);
        return t;
    });

    /** 当前写句柄状态（仅 ioExecutor 线程访问） */
    private Writer writer;
    private File writerFile;
    private long writerSize;
    private String writerDay = "";
    private final SimpleDateFormat dayFormat = new SimpleDateFormat("yyyyMMdd", Locale.US);

    private ScriptLogPersister() {
    }

    public static ScriptLogPersister getInstance() {
        if (sInstance == null) {
            synchronized (ScriptLogPersister.class) {
                if (sInstance == null) {
                    sInstance = new ScriptLogPersister();
                }
            }
        }
        return sInstance;
    }

    /** 应用启动时调用一次：确定目录 + 清理过期文件。失败不影响后续 append 重试。 */
    public void init(Context context) {
        ioExecutor.execute(() -> {
            try {
                logDir = resolveLogDir(context);
                inited = logDir != null;
                if (inited) {
                    cleanOldFiles();
                    Log.i(TAG, "script log dir: " + logDir.getAbsolutePath());
                } else {
                    Log.w(TAG, "no writable script log dir");
                }
            } catch (Throwable t) {
                Log.w(TAG, "init failed: " + t);
            }
        });
    }

    /** 当前日志目录（未初始化/不可写返回 null；调试展示用） */
    public File getLogDir() {
        return inited ? logDir : null;
    }

    /** 追加一行脚本日志（任意线程可调；内部串行落盘，失败静默） */
    public void append(String line) {
        if (!inited) {
            return;
        }
        ioExecutor.execute(() -> {
            try {
                appendLocked(line);
            } catch (Throwable t) {
                // 目录被系统清理等异常：静默降级，不干扰脚本
            }
        });
    }

    // ------------------------------------------------------------------
    // 内部（仅 ioExecutor 线程）
    // ------------------------------------------------------------------

    private void appendLocked(String line) throws IOException {
        String day = dayFormat.format(new Date());
        if (writer == null || !day.equals(writerDay) || writerSize >= MAX_FILE_BYTES) {
            rotate(day);
        }
        if (writer == null) {
            return; // 目录不可写（重新探测失败）
        }
        writer.write(line);
        writer.write('\n');
        writer.flush();
        writerSize += line.length() + 1;
    }

    /** 打开（或滚动）当日文件；目录失效时重探测一次 */
    private void rotate(String day) throws IOException {
        closeWriter();
        File dir = logDir;
        if (dir == null || !isDirWritable(dir)) {
            // 目录被回收等场景：append 时按原优先级重探测
            File rediscovered = resolveLogDir(null);
            if (rediscovered == null) {
                return;
            }
            dir = rediscovered;
            logDir = dir;
        }
        File f = new File(dir, FILE_PREFIX + day + FILE_SUFFIX);
        long size = f.length();
        int seq = 0;
        while (size >= MAX_FILE_BYTES) { // 滚动序号
            seq++;
            f = new File(dir, FILE_PREFIX + day + "_" + seq + FILE_SUFFIX);
            size = f.length();
            if (seq > 999) {
                break; // 防御：极端场景当天不再写
            }
        }
        Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8);
        writer = w;
        writerFile = f;
        writerSize = size;
        writerDay = day;
    }

    private void closeWriter() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
            }
            writer = null;
        }
    }

    /**
     * 三级目录探测：Pictures/autolua_logs → /sdcard/&lt;pkg&gt;/logs → app 专属外部 files/logs。
     * 返回第一个可写目录；全部失败返回 null。
     */
    private File resolveLogDir(Context context) {
        File sdcard = Environment.getExternalStorageDirectory();
        if (sdcard != null) {
            // ① 公共 Pictures（授权"所有文件访问"后可写）
            File p1 = new File(sdcard, "Pictures/autolua_logs");
            if (ensureWritable(p1)) {
                return p1;
            }
            // ② /sdcard/<包名>/logs
            String pkg = context != null ? context.getPackageName() : "autolua";
            File p2 = new File(sdcard, pkg + "/logs");
            if (ensureWritable(p2)) {
                return p2;
            }
        }
        // ③ 应用专属外部目录（免权限，兜底必成）
        if (context != null) {
            File ext = context.getExternalFilesDir(null);
            if (ext != null) {
                File p3 = new File(ext, "logs");
                if (ensureWritable(p3)) {
                    return p3;
                }
            }
        }
        return null;
    }

    /** 目录存在性/可写性探测：mkdirs + 临时文件写删 */
    private boolean ensureWritable(File dir) {
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                return false;
            }
            File probe = new File(dir, ".perm_test");
            if (!probe.createNewFile()) {
                return dir.canWrite();
            }
            probe.delete();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isDirWritable(File dir) {
        return dir != null && dir.isDirectory() && ensureWritable(dir);
    }

    /** 清理 RETAIN_DAYS 天前的旧日志文件；总量超 64MB 时删最旧（防御写爆） */
    private void cleanOldFiles() {
        try {
            File[] files = logDir.listFiles();
            if (files == null) {
                return;
            }
            long cutoff = System.currentTimeMillis() - RETAIN_DAYS * DAY_MS;
            long total = 0;
            for (File f : files) {
                String n = f.getName();
                if (!n.startsWith(FILE_PREFIX) || !n.endsWith(FILE_SUFFIX)) {
                    continue;
                }
                String day = n.substring(FILE_PREFIX.length(), FILE_PREFIX.length() + 8);
                long ts;
                try {
                    ts = new SimpleDateFormat("yyyyMMdd", Locale.US).parse(day).getTime();
                } catch (Throwable t) {
                    continue;
                }
                total += f.length();
                if (ts < cutoff) {
                    f.delete();
                }
            }
            if (total > 64L * 1024 * 1024) { // 超总量：按修改时间从旧到新删到 32MB 以下
                File[] logs = logDir.listFiles((d, name) ->
                        name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX));
                if (logs == null) {
                    return;
                }
                Arrays.sort(logs, Comparator.comparingLong(File::lastModified));
                long over = total - 32L * 1024 * 1024;
                for (File f : logs) {
                    if (over <= 0) {
                        break;
                    }
                    over -= f.length();
                    f.delete();
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
