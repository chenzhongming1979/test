package com.autolua.engine.ide;

import com.autolua.engine.input.inject.InjectShellClient;
import com.autolua.engine.plugin.PluginManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.function.Supplier;

/**
 * type=capabilities：能力协商（二期，方案七扩展插件字段，方案八扩展投屏/注入/远程脚本字段）。
 * <p>
 * 回 {@code {"type":"capabilities","data":{"commands":[...当前注册的全部 type...],
 * "vision":{"find_image":bool,"find_multi_color":bool,"cmp_color":bool,"ocr":bool},
 * "plugins":[{id,version,provides}...],
 * "stream":{"h264":bool,"jpeg":bool},
 * "inject":{"mode":"DIRECT_ROOT|ROOT|SHIZUKU|ACCESSIBILITY|NONE"},
 * "run_script":bool}}}。
 * commands 动态读路由器注册表（按注册顺序）；vision 读
 * {@link VisionCommandHandler} 能力常量，ocr 为运行时判定
 * （{@link VisionCommandHandler#isOcrSupported()}，取决于是否安装了 OCR 插件）；
 * plugins 读 {@link PluginManager} 已安装插件快照；
 * stream 两 codec 均内置（方案八一期）；inject 读 {@link InjectShellClient} 当前通道
 * （未 start 时为 NONE，首次 inject_* 懒启动）；run_script 判定 app 层是否已接线委托。
 */
public class CapabilitiesHandler implements IdeCommandHandler {

    private final IdeMessageRouter router;
    /** ROOT_DIR 供应器（展示 run_script 解析根，可为 null） */
    private final Supplier<String> rootDirSupplier;

    public CapabilitiesHandler(IdeMessageRouter router, Supplier<String> rootDirSupplier) {
        this.router = router;
        this.rootDirSupplier = rootDirSupplier;
    }

    @Override
    public String handle(JSONObject data, String clientId) throws Exception {
        JSONObject result = new JSONObject();
        JSONArray commands = new JSONArray();
        for (String type : router.registeredTypes()) {
            commands.put(type);
        }
        result.put("commands", commands);
        JSONObject vision = new JSONObject();
        vision.put(WsProtocol.CMD_FIND_IMAGE, VisionCommandHandler.SUPPORT_FIND_IMAGE);
        vision.put(WsProtocol.CMD_FIND_MULTI_COLOR, VisionCommandHandler.SUPPORT_FIND_MULTI_COLOR);
        vision.put(WsProtocol.CMD_CMP_COLOR, VisionCommandHandler.SUPPORT_CMP_COLOR);
        // 方案七：OCR 能力运行时动态判定（安装 provides 含 "ocr" 的插件即可用）
        vision.put(WsProtocol.CMD_OCR, VisionCommandHandler.isOcrSupported());
        result.put("vision", vision);
        // 方案七：已安装插件清单
        JSONArray plugins = new JSONArray();
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
            plugins.put(o);
        }
        result.put("plugins", plugins);
        // 方案八：投屏 codec 能力（双 codec 均内置）
        JSONObject stream = new JSONObject();
        stream.put("h264", true);
        stream.put("jpeg", true);
        result.put("stream", stream);
        // 方案八：注入通道当前模式（首次 inject_* 懒启动前为 NONE）
        JSONObject inject = new JSONObject();
        inject.put("mode", InjectShellClient.getInstance().getMode().name());
        result.put("inject", inject);
        // 方案八：远程启动脚本（app 层已接线 ScriptRunDelegate 才可用）
        result.put("run_script", getScriptRunDelegate() != null);
        String rootDir = rootDirSupplier != null ? rootDirSupplier.get() : null;
        if (rootDir != null && !rootDir.isEmpty()) {
            result.put("root_dir", rootDir);
        }
        return result.toString();
    }

    private ScriptRunDelegate getScriptRunDelegate() {
        return IdeServerManager.getInstance().getScriptRunDelegate();
    }

    @Override
    public String responseType() {
        return WsProtocol.MSG_CAPABILITIES;
    }

    /** 单命令注册到路由器 */
    public void register(IdeMessageRouter router) {
        router.register(WsProtocol.CMD_CAPABILITIES, this);
    }
}
