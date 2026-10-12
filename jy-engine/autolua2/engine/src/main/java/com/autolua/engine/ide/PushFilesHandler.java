package com.autolua.engine.ide;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * type=push_files_start / push_file / push_files_end：工程文件推送（会话态收集后统一落盘）。
 * <p>
 * 协议：
 * <ul>
 *     <li>push_files_start —— 清空会话缓冲（静默不回包）</li>
 *     <li>push_file {path, content, encoding?} —— 收集到会话缓冲
 *         （encoding 默认 base64，可选 utf8；静默不回包）</li>
 *     <li>push_files_end —— 落盘到 {@code getFilesDir()/ide_push/}，
 *         回 {@code {"type":"push_result","data":{"written":n,"failed":[...]}}}</li>
 * </ul>
 * 一期单连接串行会话，会话缓冲不做 per-client 隔离。
 * <p>
 * 本类不直接实现 {@link IdeCommandHandler}：三条命令回包语义不同
 * （start/file 静默、end 回 push_result），注册时用匿名类分别声明。
 */
public class PushFilesHandler {

    private static final String TAG = "AutoLuaIde";

    /** 应用上下文供应器（engine 无 app context，由 Manager 注入） */
    private final Supplier<Context> contextSupplier;
    /** 会话缓冲：相对路径 → 文件字节（start 清空，end 落盘） */
    private final Map<String, byte[]> pending = new LinkedHashMap<>();

    public PushFilesHandler(Supplier<Context> contextSupplier) {
        this.contextSupplier = contextSupplier;
    }

    public void register(IdeMessageRouter router) {
        // 会话开始：清空缓冲，静默不回包
        router.register(WsProtocol.CMD_PUSH_FILES_START, new IdeCommandHandler() {
            @Override
            public String handle(JSONObject data, String clientId) {
                handleStart(data, clientId);
                return null;
            }

            @Override
            public boolean replyOnNull() {
                return false;
            }
        });
        // 单文件收集：静默不回包
        router.register(WsProtocol.CMD_PUSH_FILE, new IdeCommandHandler() {
            @Override
            public String handle(JSONObject data, String clientId) throws Exception {
                return handlePushFile(data, clientId);
            }

            @Override
            public boolean replyOnNull() {
                return false;
            }
        });
        // 会话结束：统一落盘，回 push_result
        router.register(WsProtocol.CMD_PUSH_FILES_END, new IdeCommandHandler() {
            @Override
            public String handle(JSONObject data, String clientId) throws Exception {
                return handleEnd(data, clientId);
            }

            @Override
            public String responseType() {
                return WsProtocol.MSG_PUSH_RESULT;
            }
        });
    }

    private void handleStart(JSONObject data, String clientId) {
        synchronized (pending) {
            pending.clear();
        }
    }

    private String handlePushFile(JSONObject data, String clientId) throws Exception {
        String path = data.optString("path", "");
        if (path.isEmpty()) {
            throw new IllegalArgumentException("path is empty");
        }
        String content = data.optString("content", "");
        String encoding = data.optString("encoding", "base64");
        byte[] bytes;
        if ("utf8".equalsIgnoreCase(encoding)) {
            bytes = content.getBytes("UTF-8");
        } else {
            // 默认 base64；DEFAULT 容忍换行分隔
            bytes = Base64.decode(content, Base64.DEFAULT);
        }
        synchronized (pending) {
            pending.put(path, bytes);
        }
        return null;
    }

    private String handleEnd(JSONObject data, String clientId) throws Exception {
        Context ctx = contextSupplier.get();
        if (ctx == null) {
            throw new IllegalStateException("app context not attached");
        }
        Map<String, byte[]> snapshot;
        synchronized (pending) {
            snapshot = new LinkedHashMap<>(pending);
            pending.clear();
        }
        File root = new File(ctx.getFilesDir(), WsProtocol.IDE_PUSH_DIR);
        int written = 0;
        List<String> failed = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : snapshot.entrySet()) {
            try {
                File target = IdePaths.safeResolve(root, entry.getKey());
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("mkdirs failed: " + parent);
                }
                FileOutputStream fos = new FileOutputStream(target);
                try {
                    fos.write(entry.getValue());
                } finally {
                    fos.close();
                }
                written++;
            } catch (Exception e) {
                Log.w(TAG, "push file failed: " + entry.getKey() + ", " + e);
                failed.add(entry.getKey());
            }
        }
        JSONObject result = new JSONObject();
        result.put("written", written);
        JSONArray failedArr = new JSONArray();
        for (String p : failed) {
            failedArr.put(p);
        }
        result.put("failed", failedArr);
        return result.toString();
    }
}
