package com.autolua.engine.ide;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * type=push_zip_start / push_zip_chunk / push_zip_complete：大工程 zip 分块推送（二期）。
 * <p>
 * 协议：
 * <ul>
 *     <li>push_zip_start {name, totalChunks, totalSize} —— 建立分块会话，
 *         临时文件 {@code cache/ide_push_zip/<name>.zip.tmp}（静默不回包）</li>
 *     <li>push_zip_chunk {name, index, data(base64)} —— base64 解码追加到临时文件
 *         （index 从 1 起严格顺序校验；静默不回包）</li>
 *     <li>push_zip_complete {name, expectedMd5} —— 校验分片数与 MD5
 *         （不一致回 error），通过后 ZipInputStream 流式解压到
 *         {@code getFilesDir()/ide_push/}，
 *         回 {@code {"type":"push_result","data":{"unzipped":n,"failed":[...]}}}</li>
 * </ul>
 * zip slip 防护：每个条目经 {@link IdePaths#safeResolve} 规范化，越出 ide_push 根目录即拒绝。
 * <p>
 * 本类不直接实现 {@link IdeCommandHandler}：三条命令回包语义不同
 * （start/chunk 静默、complete 回 push_result），注册时用匿名类分别声明；
 * 会话状态经 synchronized 保护（消息处理线程池为多线程）。
 */
public class PushZipHandler {

    private static final String TAG = "AutoLuaIde";
    private static final int COPY_BUF = 8192;

    /** 应用上下文供应器（engine 无 app context，由 Manager 注入） */
    private final Supplier<Context> contextSupplier;

    // ---- 分块会话状态（start 建立，complete/异常时清空）----
    private String name;
    private int totalChunks;
    private long totalSize;
    private int received;
    private File tmpFile;
    private FileOutputStream out;
    private MessageDigest md5;

    public PushZipHandler(Supplier<Context> contextSupplier) {
        this.contextSupplier = contextSupplier;
    }

    public void register(IdeMessageRouter router) {
        // 会话开始：建立临时文件，静默不回包
        router.register(WsProtocol.CMD_PUSH_ZIP_START, new IdeCommandHandler() {
            @Override
            public String handle(JSONObject data, String clientId) throws Exception {
                handleStart(data);
                return null;
            }

            @Override
            public boolean replyOnNull() {
                return false;
            }
        });
        // 追加分块：静默不回包
        router.register(WsProtocol.CMD_PUSH_ZIP_CHUNK, new IdeCommandHandler() {
            @Override
            public String handle(JSONObject data, String clientId) throws Exception {
                handleChunk(data);
                return null;
            }

            @Override
            public boolean replyOnNull() {
                return false;
            }
        });
        // 完成：MD5 校验 + 解压，回 push_result
        router.register(WsProtocol.CMD_PUSH_ZIP_COMPLETE, new IdeCommandHandler() {
            @Override
            public String handle(JSONObject data, String clientId) throws Exception {
                return handleComplete(data);
            }

            @Override
            public String responseType() {
                return WsProtocol.MSG_PUSH_RESULT;
            }
        });
    }

    /** 会话开始：校验参数、建立临时文件与 MD5 累积器（覆盖未完成的旧会话） */
    private synchronized void handleStart(JSONObject data) throws Exception {
        String n = sanitizeName(data.optString("name", ""));
        int chunks = data.optInt("totalChunks", 0);
        if (n.isEmpty() || chunks <= 0) {
            throw new IllegalArgumentException("push_zip_start: invalid name or totalChunks");
        }
        resetSession();
        Context ctx = contextSupplier.get();
        if (ctx == null) {
            throw new IllegalStateException("app context not attached");
        }
        File dir = new File(ctx.getCacheDir(), WsProtocol.IDE_PUSH_ZIP_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("mkdirs failed: " + dir);
        }
        this.name = n;
        this.totalChunks = chunks;
        this.totalSize = data.optLong("totalSize", 0);
        this.received = 0;
        this.tmpFile = new File(dir, n + ".zip.tmp");
        this.out = new FileOutputStream(tmpFile);
        this.md5 = MessageDigest.getInstance("MD5");
        Log.d(TAG, "push_zip start: " + n + ", chunks=" + chunks + ", size=" + totalSize);
    }

    /** 追加一个分块：index 严格顺序（1 起），base64 解码后落盘并累积 MD5 */
    private synchronized void handleChunk(JSONObject data) throws Exception {
        if (out == null) {
            throw new IllegalStateException("push_zip_chunk: no active session");
        }
        String n = data.optString("name", "");
        if (!n.equals(name)) {
            throw new IllegalStateException("push_zip_chunk: name mismatch: " + n);
        }
        int index = data.optInt("index", -1);
        if (index != received + 1) {
            throw new IllegalStateException("push_zip_chunk: index out of order, expect "
                    + (received + 1) + " got " + index);
        }
        String chunk = data.optString("data", "");
        if (chunk.isEmpty()) {
            throw new IllegalArgumentException("push_zip_chunk: data is empty");
        }
        byte[] bytes = Base64.decode(chunk, Base64.DEFAULT);
        out.write(bytes);
        md5.update(bytes);
        received++;
    }

    /** 完成：校验分片数与 MD5 → 流式解压到 ide_push/ → 回 push_result */
    private synchronized String handleComplete(JSONObject data) throws Exception {
        if (out == null) {
            throw new IllegalStateException("push_zip_complete: no active session");
        }
        try {
            if (received != totalChunks) {
                throw new IllegalStateException("push_zip_complete: chunk count mismatch, expect "
                        + totalChunks + " got " + received);
            }
            out.flush();
            out.close();
            out = null;
            String actual = toHex(md5.digest());
            String expected = data.optString("expectedMd5", "");
            if (!expected.isEmpty() && !actual.equalsIgnoreCase(expected)) {
                throw new IllegalStateException("push_zip_complete: md5 mismatch, expect "
                        + expected + " actual " + actual);
            }
            Context ctx = contextSupplier.get();
            if (ctx == null) {
                throw new IllegalStateException("app context not attached");
            }
            File root = new File(ctx.getFilesDir(), WsProtocol.IDE_PUSH_DIR);
            if (!root.isDirectory() && !root.mkdirs()) {
                throw new IOException("mkdirs failed: " + root);
            }
            int unzipped = 0;
            List<String> failed = new ArrayList<>();
            ZipInputStream zis = new ZipInputStream(new FileInputStream(tmpFile));
            try {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String entryName = entry.getName();
                    try {
                        // zip slip 防护：规范化路径必须落在 ide_push 根目录内
                        File target = IdePaths.safeResolve(root, entryName);
                        if (entry.isDirectory()) {
                            if (!target.isDirectory() && !target.mkdirs()) {
                                throw new IOException("mkdirs failed: " + target);
                            }
                        } else {
                            File parent = target.getParentFile();
                            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                                throw new IOException("mkdirs failed: " + parent);
                            }
                            FileOutputStream fos = new FileOutputStream(target);
                            try {
                                byte[] buf = new byte[COPY_BUF];
                                int n;
                                while ((n = zis.read(buf)) > 0) {
                                    fos.write(buf, 0, n);
                                }
                            } finally {
                                fos.close();
                            }
                            unzipped++;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "unzip entry failed: " + entryName + ", " + e);
                        failed.add(entryName);
                    } finally {
                        zis.closeEntry();
                    }
                }
            } finally {
                zis.close();
            }
            JSONObject result = new JSONObject();
            result.put("unzipped", unzipped);
            JSONArray failedArr = new JSONArray();
            for (String p : failed) {
                failedArr.put(p);
            }
            result.put("failed", failedArr);
            Log.d(TAG, "push_zip complete: " + name + ", unzipped=" + unzipped
                    + ", failed=" + failed.size());
            return result.toString();
        } finally {
            resetSession();
        }
    }

    /** 清空会话状态并删除临时文件 */
    private synchronized void resetSession() {
        name = null;
        totalChunks = 0;
        totalSize = 0;
        received = 0;
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
            }
            out = null;
        }
        md5 = null;
        if (tmpFile != null && tmpFile.exists()) {
            tmpFile.delete();
        }
        tmpFile = null;
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

    /** 字节数组转小写 hex */
    private static String toHex(byte[] digest) {
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
