package com.autolua.engine.ide;

import android.content.Context;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import com.autolua.engine.plugin.PluginManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 插件 WS 命令（方案七）：plugin_push_start / plugin_push_chunk / plugin_push_complete /
 * plugin_list / plugin_remove。
 * <p>
 * 协议：
 * <ul>
 *     <li>plugin_push_start {name, totalChunks, totalSize, sha256} —— 建立分块会话，
 *     临时文件 {@code cache/ide_plugin_push/<name>.zip.tmp}（静默不回包）</li>
 *     <li>plugin_push_chunk {name, index, data(base64)} —— base64 解码追加
 *     （index 从 1 起严格顺序校验；静默不回包）</li>
 *     <li>plugin_push_complete {name} —— 校验分片数与整包文件 sha256（与 start 的
 *     sha256 比对），通过后调 {@link PluginManager#install}（manifest 内 sha256
 *     为内容清单 hash，由 PluginManager 再验），回
 *     {@code {"type":"plugin_result","data":{"installed":true,"id","version","provides"}}}</li>
 *     <li>plugin_list —— 回 {@code {"plugins":[{id,version,provides}...]}}</li>
 *     <li>plugin_remove {id} —— 回 {@code {"removed":bool,"id"}}</li>
 * </ul>
 * 分块会话超时 {@link WsProtocol#PLUGIN_PUSH_SESSION_TIMEOUT_MS}（30s，以最近一次活动计）
 * 由守护线程清扫清理临时文件。与 {@link PushZipHandler} 的差异：本处理器把 zip 原样落盘
 * 后整体交给 PluginManager 安装（不解压），会话有超时，回包统一 plugin_result。
 */
public class PluginCommandHandler {

    private static final String TAG = "AutoLuaIde";
    private static final int COPY_BUF = 8192;

    /** 应用上下文供应器（临时目录 + PluginManager 目录创建依赖） */
    private final Supplier<Context> contextSupplier;

    // ---- 分块会话状态（start 建立，complete/超时/异常时清空）----
    private String name;
    private int totalChunks;
    private long totalSize;
    private String expectedSha256;
    private int received;
    private File tmpFile;
    private FileOutputStream out;
    /** 最近一次会话活动时间（elapsedRealtime，超时判定基准） */
    private long lastActivityAt;

    /** 会话超时清扫线程（守护，进程生命周期内常驻） */
    private final ScheduledExecutorService sweepExecutor;

    public PluginCommandHandler(Supplier<Context> contextSupplier) {
        this.contextSupplier = contextSupplier;
        this.sweepExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "plugin-push-sweep");
                t.setDaemon(true);
                return t;
            }
        });
        // 每 10s 清扫一次超时会话（最近活动超过 30s 未完成的会话丢弃）
        sweepExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                sweepStaleSession();
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    public void register(IdeMessageRouter router) {
        // 会话开始：建立临时文件，静默不回包
        router.register(WsProtocol.CMD_PLUGIN_PUSH_START, new IdeCommandHandler() {
            @Override
            public String handle(JSONObject data, String clientId) throws Exception {
                handlePushStart(data);
                return null;
            }

            @Override
            public boolean replyOnNull() {
                return false;
            }
        });
        // 追加分块：静默不回包
        router.register(WsProtocol.CMD_PLUGIN_PUSH_CHUNK, new IdeCommandHandler() {
            @Override
            public String handle(JSONObject data, String clientId) throws Exception {
                handlePushChunk(data);
                return null;
            }

            @Override
            public boolean replyOnNull() {
                return false;
            }
        });
        // 完成：文件 sha256 校验 + 安装，回 plugin_result
        router.register(WsProtocol.CMD_PLUGIN_PUSH_COMPLETE, new IdeCommandHandler() {
            @Override
            public String handle(JSONObject data, String clientId) throws Exception {
                return handlePushComplete(data);
            }

            @Override
            public String responseType() {
                return WsProtocol.MSG_PLUGIN_RESULT;
            }
        });
        // 已安装插件列表
        router.register(WsProtocol.CMD_PLUGIN_LIST, new IdeCommandHandler() {
            @Override
            public String handle(JSONObject data, String clientId) throws Exception {
                return handleList();
            }

            @Override
            public String responseType() {
                return WsProtocol.MSG_PLUGIN_RESULT;
            }
        });
        // 卸载插件
        router.register(WsProtocol.CMD_PLUGIN_REMOVE, new IdeCommandHandler() {
            @Override
            public String handle(JSONObject data, String clientId) throws Exception {
                return handleRemove(data);
            }

            @Override
            public String responseType() {
                return WsProtocol.MSG_PLUGIN_RESULT;
            }
        });
    }

    // ------------------------------------------------------------------
    // plugin_push 三段推送
    // ------------------------------------------------------------------

    /** 会话开始：校验参数、建立临时文件（覆盖未完成的旧会话） */
    private synchronized void handlePushStart(JSONObject data) throws Exception {
        String n = sanitizeName(data.optString("name", ""));
        int chunks = data.optInt("totalChunks", 0);
        String sha = data.optString("sha256", "").trim();
        if (n.isEmpty() || chunks <= 0 || sha.isEmpty()) {
            throw new IllegalArgumentException(
                    "plugin_push_start: name/totalChunks/sha256 are required");
        }
        resetSession();
        Context ctx = requireContext();
        File dir = new File(ctx.getCacheDir(), WsProtocol.IDE_PLUGIN_PUSH_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("mkdirs failed: " + dir);
        }
        this.name = n;
        this.totalChunks = chunks;
        this.totalSize = data.optLong("totalSize", 0);
        this.expectedSha256 = sha;
        this.received = 0;
        this.tmpFile = new File(dir, n + ".zip.tmp");
        this.out = new FileOutputStream(tmpFile);
        this.lastActivityAt = SystemClock.elapsedRealtime();
        Log.d(TAG, "plugin_push start: " + n + ", chunks=" + chunks + ", size=" + totalSize);
    }

    /** 追加一个分块：index 严格顺序（1 起），base64 解码后落盘 */
    private synchronized void handlePushChunk(JSONObject data) throws Exception {
        if (out == null) {
            throw new IllegalStateException("plugin_push_chunk: no active session");
        }
        String n = data.optString("name", "");
        if (!n.equals(name)) {
            throw new IllegalStateException("plugin_push_chunk: name mismatch: " + n);
        }
        int index = data.optInt("index", -1);
        if (index != received + 1) {
            throw new IllegalStateException("plugin_push_chunk: index out of order, expect "
                    + (received + 1) + " got " + index);
        }
        String chunk = data.optString("data", "");
        if (chunk.isEmpty()) {
            throw new IllegalArgumentException("plugin_push_chunk: data is empty");
        }
        byte[] bytes = Base64.decode(chunk, Base64.DEFAULT);
        out.write(bytes);
        received++;
        lastActivityAt = System.currentTimeMillis();
    }

    /** 完成：校验分片数与整包 sha256 → 安装 → 回 {installed,id,version,provides} */
    private synchronized String handlePushComplete(JSONObject data) throws Exception {
        if (out == null) {
            throw new IllegalStateException("plugin_push_complete: no active session");
        }
        String n = data.optString("name", "");
        if (!n.equals(name)) {
            throw new IllegalStateException("plugin_push_complete: name mismatch: " + n);
        }
        try {
            if (received != totalChunks) {
                throw new IllegalStateException("plugin_push_complete: chunk count mismatch, expect "
                        + totalChunks + " got " + received);
            }
            out.flush();
            out.close();
            out = null;
            String actual = sha256Hex(tmpFile);
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                throw new IllegalStateException("plugin_push_complete: zip sha256 mismatch, expect "
                        + expectedSha256 + " actual " + actual);
            }
            // 落盘为正式 zip 名后交给 PluginManager 安装（内容清单 sha256 由其再验）
            Context ctx = requireContext();
            File dir = new File(ctx.getCacheDir(), WsProtocol.IDE_PLUGIN_PUSH_DIR);
            File zipFile = new File(dir, name + ".zip");
            if (zipFile.exists() && !zipFile.delete()) {
                throw new IOException("delete failed: " + zipFile);
            }
            if (!tmpFile.renameTo(zipFile)) {
                throw new IOException("rename failed: " + tmpFile + " -> " + zipFile);
            }
            Log.d(TAG, "plugin_push complete: " + name + ", installing...");
            PluginManager mgr = PluginManager.getInstance();
            mgr.setApplicationContext(ctx);
            PluginManager.InstalledPlugin p = mgr.install(zipFile);
            // 安装成功后临时 zip 可删（所需内容已全部落盘到插件目录）
            //noinspection ResultOfMethodCallIgnored
            zipFile.delete();
            JSONObject result = new JSONObject();
            result.put("installed", true);
            result.put("id", p.meta.id);
            result.put("version", p.meta.version);
            JSONArray provides = new JSONArray();
            if (p.meta.provides != null) {
                for (String s : p.meta.provides) {
                    provides.put(s);
                }
            }
            result.put("provides", provides);
            return result.toString();
        } finally {
            resetSession();
        }
    }

    // ------------------------------------------------------------------
    // plugin_list / plugin_remove
    // ------------------------------------------------------------------

    private String handleList() throws Exception {
        JSONArray arr = new JSONArray();
        for (PluginManager.InstalledPlugin p : PluginManager.getInstance().getInstalled()) {
            JSONObject o = new JSONObject();
            o.put("id", p.meta.id);
            o.put("version", p.meta.version);
            JSONArray provides = new JSONArray();
            if (p.meta.provides != null) {
                for (String s : p.meta.provides) {
                    provides.put(s);
                }
            }
            o.put("provides", provides);
            arr.put(o);
        }
        JSONObject result = new JSONObject();
        result.put("plugins", arr);
        return result.toString();
    }

    private String handleRemove(JSONObject data) throws Exception {
        String id = data.optString("id", "").trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("plugin_remove: id is required");
        }
        boolean removed = PluginManager.getInstance().uninstall(id);
        JSONObject result = new JSONObject();
        result.put("removed", removed);
        result.put("id", id);
        return result.toString();
    }

    // ------------------------------------------------------------------
    // 会话清理
    // ------------------------------------------------------------------

    /** 清扫超时会话：最近活动超过阈值即丢弃（守护线程调用） */
    private synchronized void sweepStaleSession() {
        if (out == null) {
            return;
        }
        if (System.currentTimeMillis() - lastActivityAt
                > WsProtocol.PLUGIN_PUSH_SESSION_TIMEOUT_MS) {
            Log.w(TAG, "plugin push session timeout: " + name);
            resetSession();
        }
    }

    /** 清空会话状态并删除临时文件 */
    private synchronized void resetSession() {
        name = null;
        totalChunks = 0;
        totalSize = 0;
        expectedSha256 = null;
        received = 0;
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
            }
            out = null;
        }
        if (tmpFile != null && tmpFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            tmpFile.delete();
        }
        tmpFile = null;
        lastActivityAt = 0;
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private Context requireContext() {
        Context ctx = contextSupplier.get();
        if (ctx == null) {
            throw new IllegalStateException("app context not attached");
        }
        return ctx;
    }

    /** 计算文件 sha256 hex（流式） */
    private static String sha256Hex(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] buf = new byte[COPY_BUF];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        } finally {
            in.close();
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : md.digest()) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** 会话名白名单化：仅保留 [A-Za-z0-9._-]，拒绝路径穿越 */
    private static String sanitizeName(String raw) {
        if (raw == null) {
            return "";
        }
        String n = raw.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        if (n.contains("..")) {
            return "";
        }
        return n;
    }
}
