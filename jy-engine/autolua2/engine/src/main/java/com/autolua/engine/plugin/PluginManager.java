package com.autolua.engine.plugin;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.google.gson.Gson;

import dalvik.system.DexClassLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 插件管理器（单例，方案七 插件动态加载框架）。
 * <p>
 * 目录布局（全部在 app 私有目录内）：
 * <ul>
 *     <li>安装根：{@code getDir("plugins")/&lt;pluginId&gt;/&lt;version&gt;/}
 *     （classes.jar 或 classes.dex + manifest.json 副本）</li>
 *     <li>so 根：{@code getDir("plugin_libs")/&lt;pluginId&gt;/}（解压出的 *.so 平铺）</li>
 *     <li>数据目录：{@code getDir("plugin_data")/&lt;pluginId&gt;/}（插件私有，IPluginContext.getPluginDir）</li>
 *     <li>odex 输出：{@code getCodeCacheDir()}（API 21+）</li>
 * </ul>
 * 安装流程：sha256 内容清单校验 → manifest 解析 → 解压 dex/jar + so →
 * DexClassLoader → 反射实例化 → onInit（异常回滚）→ 注册。
 * so 交付模型：宿主只解压到 so 根目录，插件在 onInit 里经
 * {@link IPluginContext#loadNativeLibrary} 主动 System.load（Android 9+ 兼容）。
 * <p>
 * 持久化：安装本身落盘即持久；重启后由 app 层调 {@link #loadAll()} 扫描重载
 * （失败的插件跳过并记日志，不影响其余插件）。
 * 本地免服务端直装：{@link #installFromAssets()}（assets 预置 zip，开箱即用）与
 * {@link #installFromExternalDir()}（外部免权限目录 zip 扫描安装），app 层启动时按
 * loadAll → installFromAssets → installFromExternalDir 顺序调用。
 * 同 id 新版本安装时覆盖：旧实例 onRelease + 旧版本目录延迟删除（dex 被 mmap
 * 删不掉是正常现象，try-delete 容忍失败）。
 */
public final class PluginManager {

    private static final String TAG = "AutoLuaPlugin";

    /** 安装根目录名：getDir(PLUGINS_ROOT_DIR) */
    public static final String PLUGINS_ROOT_DIR = "plugins";
    /** so 根目录名：getDir(PLUGIN_LIBS_ROOT_DIR) */
    public static final String PLUGIN_LIBS_ROOT_DIR = "plugin_libs";
    /** 插件私有数据目录名：getDir(PLUGIN_DATA_ROOT_DIR) */
    public static final String PLUGIN_DATA_ROOT_DIR = "plugin_data";
    /** 插件包清单文件名 */
    public static final String MANIFEST_NAME = "manifest.json";
    /** 入口 dex/jar 固定名（按优先级查找）：jar 优先（可额外携带资源），其次裸 dex */
    public static final String ENTRY_JAR = "classes.jar";
    public static final String ENTRY_DEX = "classes.dex";

    /** assets 预置插件目录名：assets/plugins/*.zip（打包进 APK，开箱即用） */
    public static final String ASSET_PLUGIN_DIR = "plugins";
    /** 外部免权限插件目录名：getExternalFilesDir(null)/plugins/（免存储权限） */
    public static final String EXTERNAL_PLUGIN_DIR = "plugins";
    /** 外部目录安装成功后 zip 归档子目录名（防下次启动重复安装） */
    public static final String EXTERNAL_PROCESSED_DIR = "processed";
    /** 外部目录安装失败 zip 归档子目录名（便于排查） */
    public static final String EXTERNAL_FAILED_DIR = "failed";
    /** assets 安装临时拷贝目录名：cache/asset_plugin_install/ */
    public static final String ASSET_INSTALL_CACHE_DIR = "asset_plugin_install";

    /** 插件 id/版本 白名单：防路径穿越 */
    private static final String SAFE_NAME = "[A-Za-z0-9._-]{1,128}";

    private static volatile PluginManager sInstance;

    private final Gson gson = new Gson();
    private volatile Context appContext;
    /** 内存注册表：pluginId → 已安装插件 */
    private final Map<String, InstalledPlugin> installed = new LinkedHashMap<>();

    /**
     * 目录注入（root 进程无 app context 场景）：uid 0 直接以绝对路径访问宿主 app
     * 的插件目录（/data/user/0/<pkg>/app_plugins 等），四目录全部注入后可在无
     * Context 环境完成 install/load/uninstall 全流程。
     */
    private volatile File dirPluginsRoot, dirLibsRoot, dirDataRoot, dirCodeCache;

    /** 插件包签名验签公钥（secp256r1）：null = 开发模式不验签（默认）；
     *  注入后 install 强制校验 manifest.signature（内容清单 sha256 hex 字符串的
     *  SHA256withECDSA 签名，base64 DER），签名缺失/无效均拒绝安装 */
    private volatile ECPublicKey signaturePublicKey;

    /**
     * 注入插件包验签公钥（宿主 app 启动时调用，release 构建必须注入）。
     *
     * @param qxHex 公钥 Q.X（hex 小写，32 字节）
     * @param qyHex 公钥 Q.Y（hex 小写，32 字节）
     */
    public synchronized void setSignaturePublicKeyHex(String qxHex, String qyHex) {
        if (qxHex == null || qxHex.isEmpty() || qyHex == null || qyHex.isEmpty()) {
            signaturePublicKey = null;
            Log.i(TAG, "plugin signature verification disabled");
            return;
        }
        try {
            AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
            ap.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec spec = ap.getParameterSpec(ECParameterSpec.class);
            ECPoint pub = new ECPoint(
                    new BigInteger(1, hexToBytes(qxHex)),
                    new BigInteger(1, hexToBytes(qyHex)));
            signaturePublicKey = (ECPublicKey) KeyFactory.getInstance("EC")
                    .generatePublic(new ECPublicKeySpec(pub, spec));
            Log.i(TAG, "plugin signature verification enabled");
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid signature public key", e);
        }
    }

    /** manifest.signature 验签：签名对象 = 内容清单 sha256 的 hex 小写字符串（UTF-8） */
    private void verifySignature(PluginMeta meta, String contentHash) throws Exception {
        ECPublicKey pk = signaturePublicKey;
        if (pk == null) {
            return; // 开发模式：未注入公钥不验签
        }
        if (isEmpty(meta.signature)) {
            throw new SecurityException("manifest.signature missing (signature verification enabled)");
        }
        byte[] der = android.util.Base64.decode(meta.signature, android.util.Base64.DEFAULT);
        Signature v = Signature.getInstance("SHA256withECDSA");
        v.initVerify(pk);
        v.update(contentHash.getBytes(StandardCharsets.UTF_8));
        if (!v.verify(der)) {
            throw new SecurityException("manifest.signature invalid");
        }
    }

    private static byte[] hexToBytes(String hex) {
        if ((hex.length() & 1) != 0) {
            throw new IllegalArgumentException("odd hex length");
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public void setDirectoryOverrides(File pluginsRoot, File libsRoot, File dataRoot, File codeCache) {
        this.dirPluginsRoot = pluginsRoot;
        this.dirLibsRoot = libsRoot;
        this.dirDataRoot = dataRoot;
        this.dirCodeCache = codeCache;
    }

    private File pluginsRoot(Context ctx) {
        if (dirPluginsRoot != null) return dirPluginsRoot;
        return ctx != null ? ctx.getDir(PLUGINS_ROOT_DIR, Context.MODE_PRIVATE) : null;
    }

    private File libsRoot(Context ctx) {
        if (dirLibsRoot != null) return dirLibsRoot;
        return ctx != null ? ctx.getDir(PLUGIN_LIBS_ROOT_DIR, Context.MODE_PRIVATE) : null;
    }

    private File dataRoot(Context ctx) {
        if (dirDataRoot != null) return dirDataRoot;
        return ctx != null ? ctx.getDir(PLUGIN_DATA_ROOT_DIR, Context.MODE_PRIVATE) : null;
    }

    private File codeCacheDir(Context ctx) {
        if (dirCodeCache != null) {
            return dirCodeCache;
        }
        if (ctx == null) {
            return null;
        }
        File odexDir = ctx.getCodeCacheDir();
        return odexDir != null ? odexDir : ctx.getDir("plugin_odex", Context.MODE_PRIVATE);
    }

    public static synchronized PluginManager getInstance() {
        if (sInstance == null) {
            sInstance = new PluginManager();
        }
        return sInstance;
    }

    private PluginManager() {
    }

    /** 注入应用上下文（目录创建依赖；engine 自身无 app context） */
    public void setApplicationContext(Context context) {
        this.appContext = context != null ? context.getApplicationContext() : null;
    }

    // ------------------------------------------------------------------
    // 已安装插件视图
    // ------------------------------------------------------------------

    /** 已安装插件条目 */
    public static final class InstalledPlugin {
        public final PluginMeta meta;
        public final IPlugin instance;
        public final ClassLoader classLoader;
        public final File versionDir;

        InstalledPlugin(PluginMeta meta, IPlugin instance, ClassLoader classLoader, File versionDir) {
            this.meta = meta;
            this.instance = instance;
            this.classLoader = classLoader;
            this.versionDir = versionDir;
        }
    }

    /** 已安装插件快照 */
    public synchronized List<InstalledPlugin> getInstalled() {
        return new ArrayList<>(installed.values());
    }

    /** 按 id 查找；未安装返回 null */
    public synchronized InstalledPlugin getById(String pluginId) {
        return pluginId == null ? null : installed.get(pluginId);
    }

    // ------------------------------------------------------------------
    // 多 OCR 共存（路线A：中央激活选择，门面+切换）
    // ------------------------------------------------------------------

    /** 当前显式激活的 OCR 插件 id（null = 未切换过，取第一个注册的） */
    private volatile String activeOcrId;

    /** 所有 OCR 能力插件的 id 列表（安装序，可空） */
    public synchronized List<String> getOcrPluginIds() {
        List<String> ids = new ArrayList<>();
        for (InstalledPlugin p : installed.values()) {
            if (p.meta.provides("ocr") && p.instance instanceof IOcrPlugin) {
                ids.add(p.meta.id);
            }
        }
        return ids;
    }

    /**
     * 切换激活的 OCR 插件（{@code ocr.use(id)} 后端）。
     * 各 OCR 插件的 native 推理会话独立，切换互不干扰、无需重启引擎。
     *
     * @param pluginId 目标插件 id；null 恢复默认（第一个注册的）
     * @return true 切换成功；false 该 id 不是已安装（且已加载）的 OCR 插件
     */
    public synchronized boolean setActiveOcr(String pluginId) {
        if (pluginId == null) {
            activeOcrId = null;
            return true;
        }
        InstalledPlugin p = installed.get(pluginId);
        if (p == null || !p.meta.provides("ocr") || !(p.instance instanceof IOcrPlugin)) {
            return false;
        }
        activeOcrId = pluginId;
        Log.i(TAG, "active ocr plugin set to " + pluginId);
        return true;
    }

    /** 当前生效的 OCR 插件 id：显式激活者优先（已卸载/卸出则回落），无 OCR 插件返回 null */
    public synchronized String getEffectiveOcrId() {
        InstalledPlugin active = activeOcrId == null ? null : installed.get(activeOcrId);
        if (active != null && active.meta.provides("ocr") && active.instance instanceof IOcrPlugin) {
            return active.meta.id;
        }
        for (InstalledPlugin p : installed.values()) {
            if (p.meta.provides("ocr") && p.instance instanceof IOcrPlugin) {
                return p.meta.id;
            }
        }
        return null;
    }

    /** 当前生效的 OCR 插件（尊重 {@link #setActiveOcr} 切换；OcrLib 与 WS ocr 命令共用此入口） */
    public synchronized IOcrPlugin getOcrPlugin() {
        String id = getEffectiveOcrId();
        if (id == null) {
            return null;
        }
        InstalledPlugin p = installed.get(id);
        return p != null && p.instance instanceof IOcrPlugin ? (IOcrPlugin) p.instance : null;
    }

    /**
     * 插件 Lua API 贡献：遍历已加载插件，实例实现 {@link com.autolua.engine.base.LuaEnvContributor}
     * 的调用其 contribute(ctx)，把插件自定义的 Lua 全局注入该 context。
     * <p>
     * 调用时机（引擎接线处）：每个 LuaContext 创建并完成内置服务注入之后——
     * root 主引擎 {@code AutoLuaEngineOnLocal.pushLocalServices}、IDE 沙箱
     * {@code LuaEngineExecutor}（构造后由 app 层触发）。单个插件贡献失败只记日志，
     * 不影响其余插件与 context 创建。
     */
    public void contributeLua(com.autolua.engine.base.LuaContext ctx) {
        if (ctx == null) {
            return;
        }
        List<InstalledPlugin> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(installed.values());
        }
        for (InstalledPlugin p : snapshot) {
            if (p.instance instanceof com.autolua.engine.base.LuaEnvContributor) {
                try {
                    ((com.autolua.engine.base.LuaEnvContributor) p.instance).contribute(ctx);
                } catch (Throwable t) {
                    Log.w(TAG, "contributeLua from " + p.meta.id + " failed: " + t);
                }
            }
        }
    }

    /** 插件 so 根目录（getSoPath Lua 口用）；不可用返回 null */
    public synchronized File getSoRoot(String pluginId) {
        File root = libsRoot(appContext);
        return root != null ? new File(root, pluginId) : null;
    }

    // ------------------------------------------------------------------
    // 安装
    // ------------------------------------------------------------------

    /**
     * 安装插件包（zip）。流程：
     * <ol>
     *     <li>校验 zip：必须含 {@code manifest.json} + 入口 dex/jar（{@link #ENTRY_JAR} /
     *     {@link #ENTRY_DEX}）；计算内容清单 sha256 与 manifest.sha256 比对（不一致拒绝）</li>
     *     <li>解析 {@link PluginMeta}（gson）并校验必填字段</li>
     *     <li>解压 dex/jar 与 manifest 到版本目录；按 {@link Build#SUPPORTED_ABIS} 优先级
     *     匹配 {@code lib/<abi>/*.so}（包内含 so 而无一命中即失败）解压到 so 根目录</li>
     *     <li>{@code DexClassLoader(dexPath, codeCacheDir, soRoot, parentClassLoader)} →
     *     反射实例化 mainClass → 强转 IPlugin → onInit（异常回滚删除目录）</li>
     *     <li>注册：同 id 旧实例 onRelease + 旧目录延迟删除</li>
     * </ol>
     *
     * @return 已安装条目
     * @throws Exception 校验失败 / 解压失败 / 实例化或 onInit 失败（已回滚）
     */
    public synchronized InstalledPlugin install(File pluginZip) throws Exception {
        // 目录 override 注入（root 进程）时允许无 app context
        Context ctx = appContext;
        if (ctx == null && dirPluginsRoot == null) {
            throw new IllegalStateException("app context not attached (setApplicationContext)");
        }
        if (pluginZip == null || !pluginZip.isFile()) {
            throw new IllegalArgumentException("plugin zip not found: " + pluginZip);
        }
        ZipFile zip = new ZipFile(pluginZip);
        try {
            // ---- a. 内容清单 sha256 校验 + manifest 解析 + 入口检查 ----
            PluginMeta meta = verifyAndReadMeta(zip);

            // ---- c. 解压到暂存目录（dex: <version>.tmp；so: <id>.so.tmp）----
            // dex 与 so 全部先落暂存目录，onInit 成功后再 rename 转正。
            // loadAll 跳过 .tmp/.old —— 并发进程（root 注入进程启动 loadAll）永远看不到
            // 半成品安装态；旧安装（可能正被另一进程 mmap 运行）在转正前不被触碰：
            //   1) 覆盖式重写旧目录里的 so/模型文件会在 mmap 映射下原地截断，识别线程
            //      跳进被破坏的代码页 SIGSEGV（root 进程实测崩溃）；
            //   2) 旧版本目录直接 delete 会把 rename 失败/回滚场景下的旧安装一并删丢。
            File pluginsRoot = pluginsRoot(ctx);
            File idDir = new File(pluginsRoot, meta.id);
            File versionDir = new File(idDir, meta.version);
            File versionDirOld = new File(idDir, meta.version + ".old");
            File stageDir = new File(idDir, meta.version + ".tmp");
            File soRoot = libsRoot(ctx);
            File soDir = new File(soRoot, meta.id);
            File soDirTmp = new File(soRoot, meta.id + ".so.tmp");
            File soDirOld = new File(soRoot, meta.id + ".so.old");
            boolean extracted = false;
            try {
                // 清理上次失败安装可能遗留的暂存目录（旧安装的 versionDir/soDir 不动）
                deleteDir(stageDir);
                deleteDir(soDirTmp);
                deleteDir(versionDirOld);
                deleteDir(soDirOld);
                if (!stageDir.isDirectory() && !stageDir.mkdirs()) {
                    throw new IOException("mkdirs failed: " + stageDir);
                }
                ZipEntry dexEntry = findEntry(zip, ENTRY_JAR);
                if (dexEntry == null) {
                    dexEntry = findEntry(zip, ENTRY_DEX);
                }
                extractEntry(zip, dexEntry, new File(stageDir, dexEntry.getName()));
                extractEntry(zip, findEntry(zip, MANIFEST_NAME),
                        new File(stageDir, MANIFEST_NAME));
                int soCount = extractNativeLibs(zip, soDirTmp);
                extracted = true;
                Log.i(TAG, "extracted plugin " + meta.id + " v" + meta.version
                        + " dex=" + dexEntry.getName() + ", so=" + soCount);

                // ---- d/e. 类加载 → 实例化 → onInit（均发生在暂存目录内）----
                // 本进程已有该插件的运行实例时**只落盘不热重载**：native so 无法卸载，
                // 且静态链入的运行时（如 libomp）不允许进程内多副本——新 classloader 再
                // 加载一份 so 副本会直接 __kmp_fatal abort（实测）。落盘交换后运行实例
                // 保持不变（其 mmap 的旧 inode 存活），新版本进程重启后由 loadAll 生效。
                boolean hasRunningInstance = installed.containsKey(meta.id);
                IPlugin instance = null;
                if (!hasRunningInstance) {
                    instance = instantiate(ctx, meta, stageDir, dexEntry.getName(), soDir);
                }

                // ---- f. 原子转正：旧目录改名 .old → 暂存 rename 转正 → 注册后删 .old ----
                // rename 目标必须不存在；失败场景把 .old 改名回去，旧安装无损
                boolean versionSwapped = false;
                if (versionDir.isDirectory()) {
                    if (!versionDir.renameTo(versionDirOld)) {
                        Log.w(TAG, "rename version -> .old failed: " + versionDir);
                    }
                }
                if (stageDir.renameTo(versionDir)) {
                    versionSwapped = true;
                } else {
                    // 转正失败：尝试把 .old 恢复回去，再以暂存目录注册兜底
                    if (versionDirOld.isDirectory() && versionDirOld.renameTo(versionDir)) {
                        Log.w(TAG, "stage -> version rename failed, old version restored: " + versionDir);
                    } else {
                        Log.w(TAG, "stage -> version rename failed: " + stageDir);
                    }
                    versionDir = stageDir;
                }
                boolean soSwapped = false;
                if (soDirTmp.isDirectory()) {
                    if (soDir.isDirectory() && !soDir.renameTo(soDirOld)) {
                        Log.w(TAG, "rename so dir -> .old failed: " + soDir);
                    }
                    if (soDirTmp.renameTo(soDir)) {
                        soSwapped = true;
                    } else {
                        Log.w(TAG, "so tmp -> dir rename failed: " + soDirTmp);
                    }
                }
                InstalledPlugin fresh;
                if (hasRunningInstance) {
                    // 仅落盘：保留运行实例，新版本重启后由 loadAll 生效
                    fresh = installed.get(meta.id);
                    Log.i(TAG, "plugin updated on disk (running instance kept, takes effect "
                            + "after restart): " + meta.id + " v" + meta.version);
                    return fresh;
                }
                fresh = new InstalledPlugin(meta, instance,
                        instance.getClass().getClassLoader(), versionDir);
                InstalledPlugin old = installed.put(meta.id, fresh);
                if (old != null && old != fresh) {
                    releaseQuietly(old);
                }
                // 转正成功后清理 .old（so 被 mmap 时删除失败属正常，容忍）
                if (versionSwapped) {
                    deleteDir(versionDirOld);
                }
                if (soSwapped) {
                    deleteDir(soDirOld);
                }
                Log.i(TAG, "plugin installed: " + meta.id + " v" + meta.version
                        + " provides=" + Arrays.toString(meta.provides));
                return fresh;
            } catch (Exception e) {
                // 回滚：只删本次的暂存目录，旧安装（versionDir/soDir 及其 .old）原样保留
                deleteDir(stageDir);
                deleteDir(soDirTmp);
                throw e;
            }
        } finally {
            zip.close();
        }
    }

    // ------------------------------------------------------------------
    // 本地免服务端直装（assets 预置 + 外部目录扫描）
    // ------------------------------------------------------------------

    /**
     * 扫描外部免权限目录安装插件包：{@code getExternalFilesDir(null)/plugins/*.zip}
     * （即 /sdcard/Android/data/com.autolua.autolua2/files/plugins/，应用专属外部目录，
     * 无需任何存储权限，adb push 即可投放）。逐个 {@link #install}：
     * <ul>
     *     <li>成功 → zip 移入 {@code plugins/processed/}（移动失败退化为原地改名加
     *     {@code .installed} 后缀，再失败仅记日志跳过——避免下次启动重复安装；
     *     {@link #install} 对同 id 同版本是覆盖式重装，故成功后必须移走 zip）</li>
     *     <li>失败 → zip 移入 {@code plugins/failed/}（同样三层容忍）并记日志</li>
     * </ul>
     * 幂等：已处理的 zip 已不在扫描结果里，重复调用安全。
     *
     * @return 本次成功安装数
     */
    public synchronized int installFromExternalDir() {
        Context ctx = appContext;
        if (ctx == null) {
            Log.w(TAG, "installFromExternalDir: app context not attached");
            return 0;
        }
        File extRoot = ctx.getExternalFilesDir(null);
        if (extRoot == null) {
            Log.w(TAG, "installFromExternalDir: external storage unavailable");
            return 0;
        }
        File pluginDir = new File(extRoot, EXTERNAL_PLUGIN_DIR);
        File[] files = pluginDir.listFiles();
        if (files == null || files.length == 0) {
            return 0;
        }
        Arrays.sort(files); // 文件名序，安装顺序确定
        int ok = 0;
        for (File zip : files) {
            if (!zip.isFile() || !zip.getName().toLowerCase(java.util.Locale.US).endsWith(".zip")) {
                continue;
            }
            try {
                install(zip);
                ok++;
                archiveZip(zip, new File(pluginDir, EXTERNAL_PROCESSED_DIR), ".installed");
                Log.i(TAG, "installFromExternalDir: installed " + zip.getName());
            } catch (Throwable t) {
                Log.w(TAG, "installFromExternalDir: install failed for " + zip.getName() + ": " + t);
                archiveZip(zip, new File(pluginDir, EXTERNAL_FAILED_DIR), ".failed");
            }
        }
        return ok;
    }

    /**
     * 安装 assets 预置插件包（开箱即用，无需服务端/外部文件）：遍历
     * {@code assets/plugins/*.zip}，复制到 {@code cache/asset_plugin_install/<name>}
     * 后逐个 {@link #install}（临时目录用完即删）。
     * <p>
     * 防重复：安装前解析 zip 内 manifest.json 的 id+version，与已安装表比对，
     * 同 id 同版本已装则跳过（幂等）；版本目录存在但加载失败的场景不会被跳过——
     * install 为覆盖式重装，可自愈。同 id 新版本则正常覆盖升级。
     *
     * @return 本次实际安装数（跳过的不计）
     */
    public synchronized int installFromAssets() {
        Context ctx = appContext;
        if (ctx == null) {
            Log.w(TAG, "installFromAssets: app context not attached");
            return 0;
        }
        String[] names;
        try {
            names = ctx.getAssets().list(ASSET_PLUGIN_DIR);
        } catch (IOException e) {
            return 0; // 未预置 plugins/ 目录属正常
        }
        if (names == null || names.length == 0) {
            return 0;
        }
        File cacheDir = new File(ctx.getCacheDir(), ASSET_INSTALL_CACHE_DIR);
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            Log.w(TAG, "installFromAssets: mkdirs failed: " + cacheDir);
            return 0;
        }
        Arrays.sort(names); // 文件名序，安装顺序确定
        int ok = 0;
        for (String name : names) {
            if (!name.toLowerCase(java.util.Locale.US).endsWith(".zip")) {
                continue;
            }
            File tmp = new File(cacheDir, name);
            try {
                copyAssetToFile(ctx, ASSET_PLUGIN_DIR + "/" + name, tmp);
                if (installedSameVersion(tmp)) {
                    Log.i(TAG, "installFromAssets: skip already installed " + name);
                    continue;
                }
                install(tmp);
                ok++;
                Log.i(TAG, "installFromAssets: installed " + name);
            } catch (Throwable t) {
                Log.w(TAG, "installFromAssets: install failed for " + name + ": " + t);
            }
        }
        deleteDir(cacheDir); // 清理临时拷贝
        return ok;
    }

    /** 判断插件 zip 内 manifest 的 id+version 是否已在安装表中（assets 幂等跳过用；解析失败视为未装，交 install 走完整校验报错） */
    private boolean installedSameVersion(File pluginZip) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(pluginZip);
            ZipEntry entry = findEntry(zip, MANIFEST_NAME);
            if (entry == null) {
                return false;
            }
            PluginMeta meta = gson.fromJson(readText(zip.getInputStream(entry)), PluginMeta.class);
            InstalledPlugin p = meta == null ? null : installed.get(meta.id);
            return p != null && meta.version != null && meta.version.equals(p.meta.version);
        } catch (Throwable t) {
            return false;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** 复制 assets 文件到目标（流式，覆盖已存在目标） */
    private static void copyAssetToFile(Context ctx, String assetPath, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("mkdirs failed: " + parent);
        }
        InputStream in = ctx.getAssets().open(assetPath);
        try {
            FileOutputStream out = new FileOutputStream(target);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }

    /**
     * 归档已处理的插件 zip：移入 subDir；失败退化为原地改名加 suffix；再失败仅记日志。
     * 三层容忍确保归档失败不会阻断安装流程；外部目录扫描靠"移走"保证幂等。
     */
    private static void archiveZip(File zip, File subDir, String suffix) {
        if (subDir.isDirectory() || subDir.mkdirs()) {
            File target = new File(subDir, zip.getName());
            // 同名归档已存在则覆盖（用户重复放置同一包的场景）
            if ((!target.exists() || target.delete()) && zip.renameTo(target)) {
                return;
            }
        }
        // 退化 1：原地改名（xxx.zip → xxx.zip.installed/.failed），同样起"防重复扫描"作用
        File renamed = new File(zip.getParentFile(), zip.getName() + suffix);
        if ((!renamed.exists() || renamed.delete()) && zip.renameTo(renamed)) {
            Log.w(TAG, "archiveZip: move failed, renamed to " + renamed.getName());
            return;
        }
        // 退化 2：仅记日志（下次启动可能重复安装；install 为覆盖式，结果一致）
        Log.w(TAG, "archiveZip: cannot move or rename " + zip.getAbsolutePath());
    }

    // ------------------------------------------------------------------
    // 卸载
    // ------------------------------------------------------------------

    /**
     * 卸载插件：onRelease → 移表 → 目录延迟删除（容忍失败）。
     *
     * @return true 已卸载；false 插件未安装
     */
    public synchronized boolean uninstall(String pluginId) {
        InstalledPlugin p = installed.remove(pluginId);
        if (p == null) {
            return false;
        }
        releaseQuietly(p);
        deleteDir(p.versionDir);
        deleteDir(p.versionDir.getParentFile());
        File libs = libsRoot(appContext);
        if (libs != null) {
            deleteDir(new File(libs, pluginId));
        }
        File data = dataRoot(appContext);
        if (data != null) {
            deleteDir(new File(data, pluginId));
        }
        Log.i(TAG, "plugin uninstalled: " + pluginId);
        return true;
    }

    // ------------------------------------------------------------------
    // 重启后重载
    // ------------------------------------------------------------------

    /**
     * 启动时扫描安装根目录逐个重载（app 层在 onCreate 调用一次）。
     * 每个插件选最高版本目录尝试加载；失败的跳过并记日志，不影响其余插件。
     */
    public synchronized void loadAll() {
        Context ctx = appContext;
        File root = pluginsRoot(ctx);
        if (root == null) {
            Log.w(TAG, "loadAll: app context not attached and no directory override");
            return;
        }
        File[] idDirs = root.listFiles();
        if (idDirs == null || idDirs.length == 0) {
            Log.i(TAG, "loadAll: no plugin installed");
            return;
        }
        for (File idDir : idDirs) {
            if (!idDir.isDirectory()) {
                continue;
            }
            String pluginId = idDir.getName();
            if (installed.containsKey(pluginId)) {
                continue;
            }
            File[] verDirs = idDir.listFiles();
            if (verDirs == null || verDirs.length == 0) {
                continue;
            }
            List<File> candidates = new ArrayList<>(Arrays.asList(verDirs));
            Collections.sort(candidates, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    return versionCompare(b.getName(), a.getName()); // 新版本在前
                }
            });
            for (File verDir : candidates) {
                if (!verDir.isDirectory() || verDir.getName().endsWith(".tmp")
                        || verDir.getName().endsWith(".old")) {
                    continue; // .tmp/.old = 其他进程安装中的暂存目录 / 转正前旧版本，不可见
                }
                try {
                    loadInstalled(ctx, pluginId, verDir);
                    break; // 成功即停，不回退旧版本
                } catch (Throwable t) {
                    Log.w(TAG, "loadAll: skip " + pluginId + " v" + verDir.getName() + ": " + t);
                }
            }
        }
    }

    /** 从已落盘的版本目录重载单个插件（不再做 sha256 校验——安装时已验过） */
    private void loadInstalled(Context ctx, String pluginId, File verDir) throws Exception {
        File manifest = new File(verDir, MANIFEST_NAME);
        if (!manifest.isFile()) {
            throw new IOException("manifest.json missing in " + verDir);
        }
        PluginMeta meta = gson.fromJson(readText(manifest), PluginMeta.class);
        if (meta == null || !safeName(meta.id) || !safeName(meta.version)
                || isEmpty(meta.mainClass)) {
            throw new IOException("invalid manifest in " + verDir);
        }
        File dexFile = new File(verDir, ENTRY_JAR);
        if (!dexFile.isFile()) {
            dexFile = new File(verDir, ENTRY_DEX);
        }
        if (!dexFile.isFile()) {
            throw new IOException("entry dex/jar missing in " + verDir);
        }
        File soDir = libsRoot(ctx);
        if (soDir == null) {
            throw new IOException("so root unavailable");
        }
        IPlugin instance = instantiate(ctx, meta, verDir, dexFile.getName(), new File(soDir, pluginId));
        installed.put(meta.id,
                new InstalledPlugin(meta, instance, instance.getClass().getClassLoader(), verDir));
        Log.i(TAG, "plugin reloaded: " + meta.id + " v" + meta.version);
    }

    /**
     * 加载单个已安装但未加载的插件（plugin.load(id)）：取最高版本目录尝试加载。
     *
     * @return true 已在内存或本次加载成功；false 未安装/加载失败（日志有详情）
     */
    public synchronized boolean loadOne(String pluginId) {
        if (pluginId == null || installed.containsKey(pluginId)) {
            return installed.containsKey(pluginId);
        }
        Context ctx = appContext;
        File idDir = new File(pluginsRoot(ctx), pluginId);
        File[] verDirs = idDir.listFiles();
        if (verDirs == null || verDirs.length == 0) {
            return false;
        }
        List<File> candidates = new ArrayList<>(Arrays.asList(verDirs));
        Collections.sort(candidates, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return versionCompare(b.getName(), a.getName());
            }
        });
        for (File verDir : candidates) {
            if (!verDir.isDirectory()) {
                continue;
            }
            try {
                loadInstalled(ctx, pluginId, verDir);
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "loadOne: " + pluginId + " v" + verDir.getName() + " failed: " + t);
            }
        }
        return false;
    }

    /**
     * 卸出单个插件（plugin.unload(id)）：onRelease + 移表，目录保留（"已安装未加载"态）。
     *
     * @return true 已卸出；false 本就未加载
     */
    public synchronized boolean unload(String pluginId) {
        InstalledPlugin p = installed.remove(pluginId);
        if (p == null) {
            return false;
        }
        releaseQuietly(p);
        Log.i(TAG, "plugin unloaded: " + pluginId);
        return true;
    }

    // ------------------------------------------------------------------
    // 内部：校验 / 解压 / 实例化
    // ------------------------------------------------------------------

    /**
     * 内容清单 sha256 校验 + manifest 解析 + 必填字段校验 + 入口 dex/jar 存在性检查。
     * <p>
     * sha256 规则（内容清单 hash，详见 docs/04-插件包规范.md）：
     * 对除 manifest.json 外所有 zip file entry，按 entry 名排序，
     * 逐条取 {@code <name>:<uncompressedSize>:<该 entry 的 sha256 hex> + "\n"}
     * 串接成清单文本，对清单文本整体做 sha256（hex 小写）。
     */
    private PluginMeta verifyAndReadMeta(ZipFile zip) throws Exception {
        ZipEntry manifestEntry = findEntry(zip, MANIFEST_NAME);
        if (manifestEntry == null) {
            throw new SecurityException("plugin zip missing " + MANIFEST_NAME);
        }
        ZipEntry dexEntry = findEntry(zip, ENTRY_JAR);
        if (dexEntry == null) {
            dexEntry = findEntry(zip, ENTRY_DEX);
        }
        if (dexEntry == null) {
            throw new SecurityException("plugin zip missing " + ENTRY_JAR + " or " + ENTRY_DEX);
        }
        // 收集除 manifest 外的全部 file entry 并按名排序
        List<ZipEntry> entries = new ArrayList<>();
        Enumeration<? extends ZipEntry> it = zip.entries();
        while (it.hasMoreElements()) {
            ZipEntry e = it.nextElement();
            if (!e.isDirectory() && !MANIFEST_NAME.equals(e.getName())) {
                entries.add(e);
            }
        }
        Collections.sort(entries, new Comparator<ZipEntry>() {
            @Override
            public int compare(ZipEntry a, ZipEntry b) {
                return a.getName().compareTo(b.getName());
            }
        });
        StringBuilder list = new StringBuilder();
        byte[] buf = new byte[8192];
        for (ZipEntry e : entries) {
            String hex = entrySha256Hex(zip, e, buf);
            list.append(e.getName()).append(':').append(e.getSize()).append(':')
                    .append(hex).append('\n');
        }
        MessageDigest overall = MessageDigest.getInstance("SHA-256");
        byte[] listBytes = list.toString().getBytes(StandardCharsets.UTF_8);
        overall.update(listBytes);
        String computed = toHex(overall.digest());

        PluginMeta meta = gson.fromJson(readText(zip.getInputStream(manifestEntry)), PluginMeta.class);
        if (meta == null) {
            throw new SecurityException(MANIFEST_NAME + " is empty");
        }
        if (!safeName(meta.id) || !safeName(meta.version)) {
            throw new SecurityException("manifest: invalid id/version (allowed [A-Za-z0-9._-])");
        }
        if (isEmpty(meta.mainClass)) {
            throw new SecurityException("manifest: mainClass is required");
        }
        if (isEmpty(meta.sha256)) {
            throw new SecurityException("manifest: sha256 is required (content manifest hash)");
        }
        if (!computed.equalsIgnoreCase(meta.sha256.trim())) {
            // 报文带上计算值，方便打包方把正确 hash 回填进 manifest.json
            throw new SecurityException("manifest sha256 mismatch: manifest=" + meta.sha256.trim()
                    + ", computed=" + computed);
        }
        verifySignature(meta, computed);
        return meta;
    }

    /** DexClassLoader 加载 mainClass → 实例化 → 强转 IPlugin → onInit */
    private IPlugin instantiate(Context ctx, PluginMeta meta, File versionDir,
                                String dexFileName, File soDir) throws Exception {
        // 优化输出目录：codeCacheDir（API 21+ 可用）；目录 override（root）或不可用时回退 getDir("plugin_odex")
        File odexDir = codeCacheDir(ctx);
        if (odexDir == null) {
            throw new IOException("code cache dir unavailable");
        }
        // librarySearchPath：宿主 nativeLibraryDir 优先 + 插件 so 根目录。
        // 宿主目录优先是开发模拟器适配（Android 9 + libnb 转译层对 app 私有目录 so
        // 加载成功但 JNI 绑定异常；把插件 so 一并打进宿主 APK jniLibs 后经此路径可用）；
        // 真机宿主内无插件同名 so → 自然命中 soDir 内 zip 解压产物，标准路径不受影响。
        // 插件经自身 classloader 的 System.loadLibrary 加载（见插件实现），保证库与
        // 插件类同属一个类加载器——ART 按类加载器匹配 native 方法所属库，宿主侧
        // System.load 注册的库（app classloader）插件类绑定不到。
        // root 进程目录 override 模式 appContext 为 null → 无宿主目录，仅 soDir。
        String hostLibDir = null;
        if (ctx != null) {
            try {
                hostLibDir = ctx.getApplicationInfo().nativeLibraryDir;
            } catch (Throwable ignore) {
                // 目录 override 模式拿不到，走 soDir
            }
        }
        String searchPath = (soDir.isDirectory() ? soDir.getAbsolutePath() : null);
        if (hostLibDir != null && !hostLibDir.isEmpty()) {
            searchPath = (searchPath == null || searchPath.isEmpty())
                    ? hostLibDir : hostLibDir + File.pathSeparator + searchPath;
        }
        DexClassLoader cl = new DexClassLoader(
                new File(versionDir, dexFileName).getAbsolutePath(),
                odexDir.getAbsolutePath(),
                searchPath,
                PluginManager.class.getClassLoader());
        Class<?> main = cl.loadClass(meta.mainClass);
        Object obj = main.newInstance();
        if (!(obj instanceof IPlugin)) {
            throw new IllegalStateException("mainClass does not implement IPlugin: " + meta.mainClass);
        }
        IPlugin instance = (IPlugin) obj;
        File dataDir = new File(dataRoot(ctx), meta.id);
        if (!dataDir.isDirectory() && !dataDir.mkdirs()) {
            throw new IOException("mkdirs failed: " + dataDir);
        }
        instance.onInit(new PluginContext(meta.id, dataDir, soDir));
        return instance;
    }

    /**
     * 解压 {@code lib/<abi>/*.so} 到 so 根目录（平铺）。
     * ABI 匹配：按 {@link Build#SUPPORTED_ABIS} 优先级逐个匹配，取第一个命中的 ABI 的
     * 全部 so；包内含 lib/ 但无一 ABI 命中 → 抛错（so 无法在当前设备运行）。
     *
     * @return 解压出的 so 数量（包内无 lib/ 时为 0）
     */
    private int extractNativeLibs(ZipFile zip, File soDir) throws Exception {
        Map<String, List<ZipEntry>> byAbi = new LinkedHashMap<>();
        boolean hasLib = false;
        Enumeration<? extends ZipEntry> it = zip.entries();
        while (it.hasMoreElements()) {
            ZipEntry e = it.nextElement();
            if (e.isDirectory()) {
                continue;
            }
            String name = e.getName();
            if (!name.startsWith("lib/") || !name.endsWith(".so")) {
                continue;
            }
            hasLib = true;
            String[] seg = name.split("/");
            if (seg.length != 3 || seg[1].isEmpty() || seg[2].isEmpty()) {
                continue;
            }
            List<ZipEntry> list = byAbi.get(seg[1]);
            if (list == null) {
                list = new ArrayList<>();
                byAbi.put(seg[1], list);
            }
            list.add(e);
        }
        if (!hasLib) {
            return 0; // 纯 dex 插件，无 so
        }
        String matchedAbi = null;
        for (String abi : Build.SUPPORTED_ABIS) {
            List<ZipEntry> libs = byAbi.get(abi);
            if (libs == null || libs.isEmpty()) {
                continue;
            }
            matchedAbi = abi;
            if (!soDir.isDirectory() && !soDir.mkdirs()) {
                throw new IOException("mkdirs failed: " + soDir);
            }
            for (ZipEntry lib : libs) {
                // 平铺：只保留文件名（已校验无路径穿越——lib/<abi>/<file>.so 三段）
                extractEntry(zip, lib, new File(soDir, new File(lib.getName()).getName()));
            }
            Log.i(TAG, "extract native libs abi=" + matchedAbi
                    + " (SUPPORTED_ABIS[0]=" + Build.SUPPORTED_ABIS[0] + "), files=" + libs.size());
            return libs.size();
        }
        throw new SecurityException("plugin zip contains native libs but none matches device ABIs: "
                + Arrays.toString(Build.SUPPORTED_ABIS));
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 按 entry 名精确查找（zip 内不允许重名 entry） */
    private static ZipEntry findEntry(ZipFile zip, String name) {
        ZipEntry e = zip.getEntry(name);
        return (e != null && !e.isDirectory()) ? e : null;
    }

    /** 解压单个 entry 到目标文件（流式拷贝） */
    private static void extractEntry(ZipFile zip, ZipEntry entry, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("mkdirs failed: " + parent);
        }
        InputStream in = zip.getInputStream(entry);
        if (in == null) {
            throw new IOException("entry stream null: " + entry.getName());
        }
        FileOutputStream out = new FileOutputStream(target);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            out.close();
            in.close();
        }
    }

    /** 计算单个 entry 内容的 sha256 hex（流式，不整包载入内存） */
    private static String entrySha256Hex(ZipFile zip, ZipEntry entry, byte[] buf) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = zip.getInputStream(entry);
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return toHex(md.digest());
    }

    /** 读取流全部内容为 UTF-8 文本 */
    private static String readText(InputStream in) throws IOException {
        Reader r = new InputStreamReader(in, StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[2048];
        int n;
        while ((n = r.read(buf)) > 0) {
            sb.append(buf, 0, n);
        }
        r.close();
        return sb.toString();
    }

    /** 读取文件全部内容为 UTF-8 文本 */
    private static String readText(File file) throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            return readText(in);
        } finally {
            in.close();
        }
    }

    /** 名称白名单：仅 [A-Za-z0-9._-] 且不含 ".."（防路径穿越） */
    private static boolean safeName(String name) {
        return name != null && name.matches(SAFE_NAME) && !name.contains("..");
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 版本号比较（"." 分段数值比较，段数不齐补 0；非数字段退化为字符串比较） */
    static int versionCompare(String a, String b) {
        String[] as = a.split("\\.");
        String[] bs = b.split("\\.");
        int len = Math.max(as.length, bs.length);
        for (int i = 0; i < len; i++) {
            int av = i < as.length ? parseSegment(as[i]) : 0;
            int bv = i < bs.length ? parseSegment(bs[i]) : 0;
            if (av != bv) {
                return av < bv ? -1 : 1;
            }
        }
        return 0;
    }

    private static int parseSegment(String seg) {
        try {
            return Integer.parseInt(seg.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 尽力递归删除目录（容忍部分失败——dex 被 mmap 时删除失败属正常） */
    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children != null) {
            for (File c : children) {
                if (c.isDirectory()) {
                    deleteDir(c);
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    c.delete();
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    /** onRelease 包装：异常只记日志，不阻断卸载/覆盖流程 */
    private static void releaseQuietly(InstalledPlugin p) {
        try {
            p.instance.onRelease();
        } catch (Throwable t) {
            Log.w(TAG, "onRelease failed for " + p.meta.id + ": " + t);
        }
    }

    private Context requireContext() {
        Context ctx = appContext;
        if (ctx == null) {
            throw new IllegalStateException("app context not attached (setApplicationContext)");
        }
        return ctx;
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
