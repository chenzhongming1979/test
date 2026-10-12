package com.autolua.engine.input.inject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;

/**
 * root 注入进程入口（app_process 加载执行）。
 * <p>
 * 启动方式（由 RootInjectorChannel 拉起）：
 * <pre>
 * su -c "CLASSPATH=&lt;apkPath&gt; exec app_process /system com.autolua.engine.input.inject.RootInputInjectorMain service"
 * </pre>
 * 该类会被 app_process 用 CLASSPATH=apk 的 boot 环境加载，因此：
 * <ul>
 *     <li>必须编进 APK 的 dex（engine 库的类随宿主 APK 打包）</li>
 *     <li>不得依赖 Context/Activity 生命周期，也不能引用 androidx/support</li>
 * </ul>
 * <p>
 * 行协议（stdin 逐行）：down/move/up/tap/longtap/swipe/pinch/zoom/multitouch/gesture/reset/config/ping/exit，
 * stdout 回 "ok" 或 "err &lt;msg&gt;"（严格一行/命令），欢迎信息与错误走 stderr 透传。
 */
public final class RootInputInjectorMain {

    private RootInputInjectorMain() {
    }

    public static void main(String[] args) {
        String mode = (args != null && args.length > 0) ? args[0] : "service";
        try {
            // 完整初始化：伪造 ActivityThread 环境 + InputManager 三层 fallback
            if (!InjectorCore.initFull()) {
                System.err.println("[RootInputInjectorMain] InputManager not available");
                System.exit(1);
            }
            if ("service".equals(mode)) {
                runServiceMode();
            } else {
                // 单命令模式：app_process /system <cls> <cmd> [args...]，执行完按结果退出
                StringBuilder sb = new StringBuilder(mode);
                for (int i = 1; i < args.length; i++) {
                    sb.append(' ').append(args[i]);
                }
                System.exit(InjectorCore.executeCommandLine(sb.toString()) ? 0 : 1);
            }
        } catch (Throwable t) {
            System.err.println("[RootInputInjectorMain] Error: " + t);
            t.printStackTrace();
            System.exit(1);
        }
    }

    /** 常驻服务模式：stdin 逐行读命令，stdout 回 ok/err */
    private static void runServiceMode() {
        // 欢迎信息走 stderr：stdout 严格保留给协议响应，避免客户端读到多余行
        System.err.println("[RootInputInjectorMain] service starting (stdin/stdout mode), ready="
                + InjectorCore.isReady() + ", pointers=" + InjectorCore.stateString());
        BufferedReader reader = null;
        PrintStream out = null;
        try {
            reader = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
            out = new PrintStream(System.out, true, "UTF-8");
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if ("exit".equals(trimmed)) {
                    break;
                }
                String resp;
                try {
                    resp = InjectorCore.executeCommandLine(trimmed) ? "ok" : "err command failed";
                } catch (Throwable t) {
                    resp = "err " + t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                out.println(resp);
                out.flush();
            }
        } catch (Throwable t) {
            System.err.println("[RootInputInjectorMain] service error: " + t);
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (Throwable ignore) {
            }
            if (out != null) {
                out.close();
            }
        }
    }
}
