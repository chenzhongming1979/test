package com.autolua.engine.ide;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.util.function.Supplier;

/**
 * type=run_script：远程启动工程脚本（方案八 9.5，走主引擎非沙箱）。
 * <p>
 * 协议：{@code run_script {path}}（相对根目录，如 /main.lua 或 /demo/main.lua）→
 * 回 {@code {"type":"script_started","data":{"path":..,"ok":true}}}；提交失败回 error。
 * <p>
 * 路径解析（设备端拼绝对路径，两根按序探测）：
 * <ol>
 *     <li>ROOT_DIR（主引擎项目根，MainApp 注入的 {@code <externalFilesDir>/project}）——
 *         设备上已存在的工程脚本；</li>
 *     <li>ide_push（{@code getFilesDir()/ide_push/}）——PC 端 push_zip 推送落盘的工程，
 *         打通"推 zip → 直接跑"链路。</li>
 * </ol>
 * 解析后做 {@code ..} 越界防护（对齐 {@link IdePaths#safeResolve} 语义），
 * 越出根目录或文件不存在回 error。
 * 执行经 {@link ScriptRunDelegate}（app 层接线 AutoLuaEngineProxy，主引擎异步执行）。
 */
public class RunScriptHandler implements IdeCommandHandler {

    private static final String TAG = "AutoLuaIde";

    /** 主引擎执行委托供应器（app 层接线；未接线时 run_script 回 error） */
    private final Supplier<ScriptRunDelegate> delegateSupplier;
    /** ROOT_DIR 供应器（MainApp 注入的主引擎项目根，带尾斜杠与否均可） */
    private final Supplier<String> rootDirSupplier;
    /** 应用上下文供应器（定位 ide_push 根目录） */
    private final Supplier<Context> contextSupplier;

    public RunScriptHandler(Supplier<ScriptRunDelegate> delegateSupplier,
                            Supplier<String> rootDirSupplier,
                            Supplier<Context> contextSupplier) {
        this.delegateSupplier = delegateSupplier;
        this.rootDirSupplier = rootDirSupplier;
        this.contextSupplier = contextSupplier;
    }

    public void register(IdeMessageRouter router) {
        router.register(WsProtocol.CMD_RUN_SCRIPT, this);
    }

    @Override
    public String handle(JSONObject data, String clientId) throws Exception {
        String rel = data.optString("path", "");
        if (rel.isEmpty()) {
            throw new IllegalArgumentException("path required, e.g. {\"path\":\"/main.lua\"}");
        }
        ScriptRunDelegate delegate = delegateSupplier.get();
        if (delegate == null) {
            throw new IllegalStateException("script run delegate not set (engine not wired)");
        }
        // 两根按序探测：ROOT_DIR（工程脚本）→ ide_push（PC 推送的 zip 解压根）。
        // 注意：resolveIn 的 canonical 检查对不存在的文件也返回非 null（仅拒绝路径越界），
        // 因此必须用 isFile() 判定命中，否则 ROOT_DIR 目录存在时 ide_push 回退永不触发。
        File target = resolveIn(rootDirSupplier.get(), rel);
        if (target == null || !target.isFile()) {
            Context ctx = contextSupplier.get();
            if (ctx != null) {
                File alt = resolveIn(new File(ctx.getFilesDir(), WsProtocol.IDE_PUSH_DIR).getAbsolutePath(), rel);
                if (alt != null && alt.isFile()) {
                    target = alt;
                }
            }
        }
        if (target == null || !target.isFile()) {
            throw new java.io.FileNotFoundException("script not found in ROOT_DIR/ide_push: " + rel);
        }
        String absPath = target.getAbsolutePath();
        Log.d(TAG, "run_script submit: " + absPath);
        boolean ok = delegate.runScript(absPath);
        JSONObject ret = new JSONObject();
        ret.put("path", rel);
        ret.put("ok", ok);
        return ret.toString();
    }

    @Override
    public String responseType() {
        return WsProtocol.MSG_SCRIPT_STARTED;
    }

    /**
     * 在指定根目录内解析相对路径（容错 / \ 分隔符与开头 /），
     * 含 {@code ..} 越界防护：规范化后不在根目录内返回 null（不抛错，供两根按序探测）。
     */
    private static File resolveIn(String rootDir, String relPath) {
        if (rootDir == null || rootDir.isEmpty()) {
            return null;
        }
        String rel = relPath.replace('\\', '/');
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        if (rel.isEmpty()) {
            return null;
        }
        File root = new File(rootDir);
        File target = new File(root, rel);
        try {
            String canon = target.getCanonicalPath();
            String rootCanon = root.getCanonicalPath();
            if (!canon.equals(rootCanon) && !canon.startsWith(rootCanon + File.separator)) {
                return null; // 越出根目录
            }
        } catch (Exception e) {
            return null;
        }
        return target;
    }
}
