package com.autolua.engine.ide;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.function.Supplier;

/**
 * type=delete_files：删除 {@code getFilesDir()/ide_push/} 下 data.paths 指定的相对路径文件。
 * 回 {@code {"type":"delete_result","data":{"deleted":n,"failed":[...]}}}。
 */
public class DeleteFilesHandler implements IdeCommandHandler {

    private final Supplier<Context> contextSupplier;

    public DeleteFilesHandler(Supplier<Context> contextSupplier) {
        this.contextSupplier = contextSupplier;
    }

    public void register(IdeMessageRouter router) {
        router.register(WsProtocol.CMD_DELETE_FILES, this);
    }

    @Override
    public String responseType() {
        return WsProtocol.MSG_DELETE_RESULT;
    }

    @Override
    public String handle(JSONObject data, String clientId) throws Exception {
        Context ctx = contextSupplier.get();
        if (ctx == null) {
            throw new IllegalStateException("app context not attached");
        }
        File root = new File(ctx.getFilesDir(), WsProtocol.IDE_PUSH_DIR);
        int deleted = 0;
        JSONArray failed = new JSONArray();
        JSONArray paths = data.optJSONArray("paths");
        if (paths != null) {
            for (int i = 0; i < paths.length(); i++) {
                String p = paths.optString(i, "");
                if (p.isEmpty()) {
                    continue;
                }
                try {
                    File target = IdePaths.safeResolve(root, p);
                    if (!target.exists() || !target.delete()) {
                        failed.put(p);
                    } else {
                        deleted++;
                    }
                } catch (Exception e) {
                    failed.put(p);
                }
            }
        }
        JSONObject result = new JSONObject();
        result.put("deleted", deleted);
        result.put("failed", failed);
        return result.toString();
    }
}
