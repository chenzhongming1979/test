package com.autolua.engine.ide;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.function.Supplier;

/**
 * type=get_file_md5_list：遍历 {@code getFilesDir()/ide_push/}（及 data.dir 指定的相对子目录），
 * 回 {@code {"type":"result","data":{<path>: <md5>}}}，作为 IDE 端增量同步基线。
 */
public class FileMd5Handler implements IdeCommandHandler {

    private final Supplier<Context> contextSupplier;

    public FileMd5Handler(Supplier<Context> contextSupplier) {
        this.contextSupplier = contextSupplier;
    }

    public void register(IdeMessageRouter router) {
        router.register(WsProtocol.CMD_GET_FILE_MD5_LIST, this);
    }

    @Override
    public String handle(JSONObject data, String clientId) throws Exception {
        Context ctx = contextSupplier.get();
        if (ctx == null) {
            throw new IllegalStateException("app context not attached");
        }
        File root = new File(ctx.getFilesDir(), WsProtocol.IDE_PUSH_DIR);
        String dir = data.optString("dir", "");
        File base = dir.isEmpty() ? root : IdePaths.safeResolve(root, dir);
        JSONObject result = new JSONObject();
        if (base.isDirectory()) {
            scan(base, base, result);
        }
        return result.toString();
    }

    /** 递归扫描，key 为相对 base 的路径（'/' 分隔） */
    private static void scan(File base, File dir, JSONObject out) throws Exception {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                scan(base, f, out);
            } else if (f.isFile()) {
                out.put(IdePaths.relPath(base, f), md5Hex(f));
            }
        }
    }

    /** 流式计算文件 MD5，返回小写 hex */
    private static String md5Hex(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        FileInputStream fis = new FileInputStream(file);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        } finally {
            fis.close();
        }
        StringBuilder sb = new StringBuilder(32);
        for (byte b : md.digest()) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
