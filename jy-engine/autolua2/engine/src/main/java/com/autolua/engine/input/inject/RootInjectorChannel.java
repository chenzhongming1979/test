package com.autolua.engine.input.inject;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;

/**
 * 通道二：ROOT —— su 拉起 app_process 常驻注入子进程（对照 TouchShellClient.tryStartRootMode:151-183）。
 * <pre>
 * su -c "CLASSPATH=&lt;apkPath&gt; exec app_process /system com.autolua.engine.input.inject.RootInputInjectorMain service"
 * </pre>
 * 探针：启动后 sleep 300ms，{@code exitValue()} 抛 IllegalThreadStateException = 存活。
 * 协议：常驻 stdin/stdout 行协议（每命令写行 + 读行），stderr 独立线程泵。
 */
public class RootInjectorChannel implements IInjectChannel {

    private static final String TAG = "RootInjectorChannel";
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    /** 启动探针等待时长（ms） */
    private static final long PROBE_DELAY_MS = 300;
    /** su 探测超时（ms） */
    private static final long SU_PROBE_TIMEOUT_MS = 2000;

    private final Object lock = new Object();

    /** app 层注入的 APK 路径（engine 是 library，无法自取 PackageCodePath） */
    private volatile String apkPath;
    private volatile Context appContext;

    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private Thread stderrPump;
    private boolean alive;

    /** 由 InjectShellClient / app 层注入 APK 路径（context.getPackageCodePath()） */
    public void setApkPath(String path) {
        this.apkPath = path;
    }

    /** 可选：注入应用上下文，用于兜底取 getPackageCodePath */
    public void setApplicationContext(Context context) {
        this.appContext = context != null ? context.getApplicationContext() : null;
    }

    @Override
    public String name() {
        return "ROOT";
    }

    @Override
    public boolean start() {
        synchronized (lock) {
            if (alive) {
                return true;
            }
            String apk = resolveApkPath();
            if (apk == null || apk.isEmpty()) {
                logErr("apkPath 未注入且无法反射获取，ROOT 通道不可用");
                return false;
            }
            try {
                // 1. su 探测（exec su + id 验证 uid=0）
                if (!probeSu()) {
                    logWarn("su 不可用（无 root）");
                    return false;
                }
                // 2. 防重复：清理残留注入进程
                execShell("pkill -f 'app_process.*RootInputInjectorMain'");
                Thread.sleep(100);
                // 3. 拉起常驻注入进程
                String cmdline = "CLASSPATH=" + apk + " exec app_process /system "
                        + RootInputInjectorMain.class.getName() + " service";
                process = new ProcessBuilder("su", "-c", cmdline).start();
                Thread.sleep(PROBE_DELAY_MS);
                try {
                    int code = process.exitValue();
                    logErr("root 注入进程立即退出 code=" + code);
                    process = null;
                    return false;
                } catch (IllegalThreadStateException expectAlive) {
                    // 抛异常 = 进程存活 = 启动成功
                }
                writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), UTF_8));
                reader = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF_8));
                startStderrPump();
                alive = true;
                // 4. 协议探针
                if (!sendCommandLocked("ping")) {
                    logErr("root 注入进程协议探针失败");
                    stopLocked();
                    return false;
                }
                logInfo("ROOT 通道启动成功");
                return true;
            } catch (Throwable t) {
                logErr("ROOT 通道启动失败: " + t);
                stopLocked();
                return false;
            }
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            stopLocked();
        }
    }

    @Override
    public boolean isAvailable() {
        synchronized (lock) {
            return alive;
        }
    }

    @Override
    public boolean injectDown(int pointerId, float x, float y, long downTime) {
        return sendCommand("down " + x + " " + y + " " + pointerId + " " + downTime);
    }

    @Override
    public boolean injectMove(int pointerId, float x, float y, long downTime) {
        return sendCommand("move " + x + " " + y + " " + pointerId + " " + downTime);
    }

    @Override
    public boolean injectUp(int pointerId, float x, float y, long downTime) {
        return sendCommand("up " + pointerId + " " + x + " " + y + " " + downTime);
    }

    @Override
    public boolean tap(float x, float y) {
        return sendCommand("tap " + x + " " + y);
    }

    @Override
    public boolean swipe(float x1, float y1, float x2, float y2, int durationMs) {
        return sendCommand("swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + durationMs);
    }

    @Override
    public boolean pinch(float cx, float cy, float radius, float angle, int durationMs, boolean zoomIn) {
        // zoomIn=true → pinch（张开），false → zoom（捏合）；duration<=0 交给远端默认
        String cmd = zoomIn ? "pinch" : "zoom";
        return sendCommand(cmd + " " + cx + " " + cy + " " + radius + " " + angle + " " + durationMs);
    }

    @Override
    public boolean gestureJson(String strokesJson) {
        if (strokesJson == null || strokesJson.indexOf('\n') >= 0) {
            // 行协议禁止换行
            return false;
        }
        return sendCommand("gesture " + strokesJson);
    }

    /** 键码注入（行协议 key <keycode>，远端 InjectorCore.key 反射注入 KeyEvent） */
    @Override
    public boolean injectKey(int keycode) {
        return sendCommand("key " + keycode);
    }

    /** 复位远端注入进程的指针状态（行协议 reset） */
    public boolean resetState() {
        return sendCommand("reset");
    }

    /** 下发运行时配置（行协议 config k v） */
    public boolean applyConfig(String key, String value) {
        if (key == null || value == null) {
            return false;
        }
        return sendCommand("config " + key + " " + value);
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 发送命令并等待响应行；IO 失败时标记通道失效（交由门面降级） */
    private boolean sendCommand(String line) {
        synchronized (lock) {
            return sendCommandLocked(line);
        }
    }

    private boolean sendCommandLocked(String line) {
        if (!alive || writer == null || reader == null) {
            logWarn("命令未发送（通道未就绪）: cmd=[" + line + "]"
                    + " alive=" + alive + ", writer=" + (writer != null)
                    + ", reader=" + (reader != null));
            return false;
        }
        try {
            writer.write(line);
            writer.write("\n");
            writer.flush();
            String resp = reader.readLine();
            if (resp == null) {
                // 对端退出（stdout EOF）
                logErr("root 注入进程 stdout 已关闭: cmd=[" + line + "]");
                stopLocked();
                return false;
            }
            resp = resp.trim();
            if (resp.startsWith("ok")) {
                return true;
            }
            // 命令级失败：进程仍在，通道保持，由门面决定是否降级
            logWarn("命令被拒绝: cmd=[" + line + "] resp=[" + resp + "]");
            return false;
        } catch (Throwable t) {
            logErr("sendCommand 失败: cmd=[" + line + "] ex=" + t);
            stopLocked();
            return false;
        }
    }

    /** stderr 独立泵线程：读走子进程 stderr 防止管道阻塞 */
    private void startStderrPump() {
        final InputStream err = process.getErrorStream();
        stderrPump = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader r = new BufferedReader(new InputStreamReader(err, UTF_8));
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        // stderr 直接透传到 logcat
                        Log.d(TAG + "_service", line);
                    }
                } catch (Throwable ignore) {
                    // 进程退出时流关闭，正常
                }
            }
        }, "RootInjector-stderr");
        stderrPump.setDaemon(true);
        stderrPump.start();
    }

    /** su 探测：exec su 写 id 命令，验证输出含 uid=0 */
    private static boolean probeSu() {
        try {
            Process p = Runtime.getRuntime().exec("su");
            try {
                java.io.OutputStream os = p.getOutputStream();
                os.write("id\n".getBytes(UTF_8));
                os.write("exit\n".getBytes(UTF_8));
                os.flush();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), UTF_8));
                StringBuilder sb = new StringBuilder();
                long deadline = System.currentTimeMillis() + SU_PROBE_TIMEOUT_MS;
                while (System.currentTimeMillis() < deadline) {
                    if (r.ready()) {
                        String l = r.readLine();
                        if (l == null) {
                            break;
                        }
                        sb.append(l).append('\n');
                        if (sb.indexOf("uid=0") >= 0) {
                            return true;
                        }
                    } else {
                        Thread.sleep(50);
                    }
                }
                return sb.indexOf("uid=0") >= 0;
            } finally {
                try {
                    p.destroy();
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /** 快速 root 命令（pkill 等），吸收输出防管道阻塞 */
    private static void execShell(String cmd) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            InputStream in = p.getInputStream();
            byte[] buf = new byte[512];
            while (in.read(buf) >= 0) {
                // 丢弃输出直至流结束（进程退出）
            }
            InputStream err = p.getErrorStream();
            while (err.read(buf) >= 0) {
                // 丢弃
            }
            p.waitFor();
        } catch (Throwable ignore) {
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /** APK 路径：显式注入优先，其次 appContext.getPackageCodePath()，最后反射 currentApplication */
    private String resolveApkPath() {
        if (apkPath != null && !apkPath.isEmpty()) {
            return apkPath;
        }
        if (appContext != null) {
            try {
                String p = appContext.getPackageCodePath();
                if (p != null && !p.isEmpty()) {
                    return p;
                }
            } catch (Throwable ignore) {
            }
        }
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (app != null) {
                Object path = app.getClass().getMethod("getPackageCodePath").invoke(app);
                if (path instanceof String && !((String) path).isEmpty()) {
                    return (String) path;
                }
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    private void stopLocked() {
        alive = false;
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (Throwable ignore) {
        }
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (Throwable ignore) {
        }
        try {
            if (process != null) {
                process.destroy();
            }
        } catch (Throwable ignore) {
        }
        writer = null;
        reader = null;
        process = null;
        Thread pump = stderrPump;
        stderrPump = null;
        if (pump != null) {
            pump.interrupt();
        }
    }

    private static void logInfo(String msg) {
        Log.i(TAG, msg);
    }

    private static void logWarn(String msg) {
        Log.w(TAG, msg);
    }

    private static void logErr(String msg) {
        Log.e(TAG, msg);
    }
}
