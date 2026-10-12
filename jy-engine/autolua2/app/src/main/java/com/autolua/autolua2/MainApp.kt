package com.autolua.autolua2

import android.content.Intent
import com.autolua.autolua2.extension.UserInterface
import com.autolua.autolua2.extension.imp.UserInterfaceImp
import com.autolua.autolua2.extension.ui.IUiMgr
import com.autolua.autolua2.extension.ui.UiMgrImp
import com.autolua.autolua2.activity.DebugActivity
import com.autolua.autolua2.base.Configure
import com.autolua.autolua2.debug.DiscoveryManager
import com.autolua.autolua2.mln.ActivityLifecycleMonitor
import com.autolua.autolua2.mln.AutoLuaEngineResultCode
import com.autolua.autolua2.mln.AutoLuaEngineState
import com.autolua.autolua2.mln.GlobalStateListener
import com.autolua.autolua2.project.ProjectManagerImp
import com.autolua.autolua2.mln.provider.GlideImageProvider
import com.autolua.autolua2.mln.ud.UI
import com.autolua.autolua2.view.FloatController
import com.autolua.autolua2.view.imp.FloatControllerImp
import com.autolua.engine.core.AutoLuaEngine
import com.autolua.engine.core.AutoLuaEngineOnLocal
import com.autolua.engine.core.AutoLuaEngineProxy
import com.autolua.engine.core.root.Client
import com.autolua.engine.accessibility.AccessibilityLaunchHelper
import com.autolua.engine.ide.IdeServerManager
import com.autolua.engine.ide.LuaEngineExecutor
import com.autolua.engine.ide.UiDebugDelegate
import com.autolua.engine.plugin.PluginManager
import com.autolua.autolua2.keepalive.KeepAliveManager
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.immomo.luanative.hotreload.HotReloadServer
import com.immomo.mls.MLSBundleUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.immomo.mls.MLSBuilder
import com.immomo.mls.MLSEngine
import com.immomo.mls.`fun`.lt.SIApplication
import com.immomo.mls.global.LVConfigBuilder

class MainApp: android.app.Application() {
  private val TAG = "MainApp"
  private var sdCardDir:String = ""
    get() = field

  private fun ensureDir(dir: String) {
    val file = java.io.File(dir)
    if (!file.exists()) {
      file.mkdirs()
    }
  }

  override fun onCreate() {
    super.onCreate()
    instance = this
    sdCardDir = getRootPath()
    if(!sdCardDir.endsWith("/")){
      sdCardDir += "/"
    }
    val uiRootDir = sdCardDir + "ui/"

    ProjectManagerImp.instance.init(this.applicationContext, sdCardDir + "project")

    // 贴牌自动导入（brand.json 打包流水线）：pack.py 把壳工程 zip 放进 assets/brand/，
    // 启动时逐个 addProject 投队列异步导入（zip 文件名即工程名，根目录 package.json 提供版本；
    // 同 version 重复导入=覆盖，天然幂等）。默认构建无 assets/brand，assets.list 抛 IOException 跳过。
    try {
      assets.list("brand")?.filter { it.endsWith(".zip") }?.forEach { zip ->
        ProjectManagerImp.instance.addProject(zip.removeSuffix(".zip"),
            assets.open("brand/$zip").readBytes())
        android.util.Log.i(TAG, "brand project import queued: $zip")
      }
    } catch (t: Throwable) {
      android.util.Log.i(TAG, "brand assets import skipped: $t")
    }

    // 脚本日志设备端落盘（/sdcard/Pictures/autolua_logs 优先，无权限回退应用专属目录，按天滚动）
    com.autolua.engine.ide.ScriptLogPersister.getInstance().init(this)

    /// -----------配合 Application 使用------------
    SIApplication.isColdBoot = true
    registerActivityLifecycleCallbacks(ActivityLifecycleMonitor())

    ensureDir(uiRootDir+"cache")

    val config = LVConfigBuilder(this).
            setRootDir(uiRootDir).
            setCacheDir(uiRootDir + "cache").
            setImageDir(uiRootDir + "image").
            setGlobalResourceDir(uiRootDir + "res").
            build()
    MLSEngine.init(this, true).
            setLVConfig(config).
            setImageProvider(GlideImageProvider()).
            setGlobalStateListener(GlobalStateListener()).
            setDefaultLazyLoadImage(false).
            registerSC().
            registerUD().
            registerSingleInsance(
              MLSBuilder.SIHolder(UI.LUA_CLASS_NAME,UI::class.java)
//              MLSBuilder.SIHolder(UIDebuggerUD.LUA_CLASS_NAME,UIDebuggerUD::class.java)
            ).
            registerConstants(
              AutoLuaEngineState::class.java,
              AutoLuaEngineResultCode::class.java
            ).
            buildWhenReady();
    UserInterfaceImp.instance.init(this)
    UiMgrImp.instance.init(this)
    FloatControllerImp.instance.init(this, 50)
    FloatControllerImp.instance.setClickListener {
      if(it == FloatController.State.IDLE){
        AutoLuaEngineProxy.instance.startTask()
      }else{
        AutoLuaEngineProxy.instance.interrupt()
      }
    }

    AutoLuaEngineProxy.instance.addObserver(AutoLuaEngine.Target.WORKER.value) {
      FloatControllerImp.instance.updateState(
        if(it == AutoLuaEngine.State.IDLE) FloatController.State.IDLE else FloatController.State.RUNNING)
      // IDE 运行状态广播：主引擎脚本起/停实时同步到 VSCode 插件（运行/停止按钮切换）
      IdeServerManager.getInstance().broadcastScriptState(
        if(it == AutoLuaEngine.State.IDLE) "idle" else "running")
      // 动态 UI 容器化兜底（app 侧 IDLE 观察者）：只回收残留弹窗+清事件队列。
      // 窗口由脚本 Lua OnStop 链主动关闭——IDLE 状态通知跨进程分发有延迟，
      // 在此关窗会误杀通知滞后期间已启动的新容器窗口（实测面板消失）
      if (it == AutoLuaEngine.State.IDLE) {
        UiMgrImp.instance.releaseDialogs()
      }
    }

    AutoLuaEngineProxy.instance.setEngineCreator {
      val builder = Client.Builder(this@MainApp)
      builder.addLocalService("UI", UserInterfaceImp.instance, UserInterface::class.java)
      builder.addLocalService("FloatController", FloatControllerImp.instance, FloatController::class.java)
      // 主引擎脚本输出回传（老 8176 通道退役后 run_script print 的回传路径）
      // 第三参必须是接口：root 进程 Proxy.newProxyInstance 重建代理要求 interface
      builder.addLocalService("IdeLog", IdeLogService(), IdeLog::class.java)
      // 动态 UI 服务（app 主进程实例：悬浮窗权限所在进程；root Lua 经跨进程代理调用）
      builder.addLocalService("uimgr", UiMgrImp.instance, IUiMgr::class.java)
      // 无 root 降级（虚拟屏任务书 §2）：root 进程拉起失败（无 su）时回退 app 进程内沙箱引擎
      builder.build() ?: buildLocalSandboxEngine()
    }
    AutoLuaEngineProxy.instance.start()

    // IDE 调试通道（方案六二期）：启动 WebSocket 服务并注入 Lua 沙箱执行器，
    // 8080 占用自动回退 8081..8085；执行命令经 EngineExecutorHolder 分发到 LuaEngineExecutor
    // 三期：把主引擎同款服务实例注册进沙箱（execute 的脚本可寻址 UI/FloatController 等全局 API），
    // 注入 ROOT_DIR 对齐主引擎项目根；pause/resume 经 delegate 透传 AutoLuaEngineProxy
    val ideExecutor = LuaEngineExecutor()
    ideExecutor.registerService("UI", UserInterfaceImp.instance, UserInterface::class.java)
    ideExecutor.registerService("FloatController", FloatControllerImp.instance, FloatController::class.java)
    // 基础能力三件套（沙箱本地注册；http 网络请求、luajava 反射桥、LuaEngine java 桥）
    ideExecutor.registerService("http", com.autolua.engine.extension.net.NetLib(),
      com.autolua.engine.extension.net.INetLib::class.java)
    ideExecutor.registerService("luajava", com.autolua.engine.extension.bridge.JavaBridgeLib(),
      com.autolua.engine.extension.bridge.IJavaBridge::class.java)
    ideExecutor.registerService("LuaEngine", com.autolua.engine.extension.bridge.LuaEngineLib(this),
      com.autolua.engine.extension.bridge.ILuaEngineLib::class.java)
    // 商业引擎兼容域库（对象式 API + contribute 全局别名）
    ideExecutor.registerService("file", com.autolua.engine.extension.file.FileLib(),
      com.autolua.engine.extension.file.FileLib::class.java)
    ideExecutor.registerService("device", com.autolua.engine.extension.device.DeviceLib(this),
      com.autolua.engine.extension.device.DeviceLib::class.java)
    ideExecutor.registerService("crypt", com.autolua.engine.extension.crypto.CryptoLib(),
      com.autolua.engine.extension.crypto.CryptoLib::class.java)
    // Lua 兼容层（utf8/splitStr/图色封装/math/frandom/time/setTimer/auto.* 工厂）——必须在以上服务之后
    ideExecutor.registerService("compat", com.autolua.engine.extension.compat.CompatLib(),
      com.autolua.engine.extension.compat.CompatLib::class.java)
    // 云控通道（Java 下沉）：WS 9300 + PSK 帧加解密在 Java 层，Lua 只见明文 JSON 收发。
    // 必须在 compat 之后——CLOUD_LUA 的 OnStop 包装（cloud.close+pending 清理）依赖
    // compat 的 OnStop 先装载，否则被覆盖导致 pendingObs 跨运行累积
    ideExecutor.registerService("cloudServer", com.autolua.engine.cloud.CloudService(),
      com.autolua.engine.cloud.ICloudService::class.java)
    ideExecutor.registerService("app", com.autolua.engine.extension.app.AppLib(),
      com.autolua.engine.extension.app.AppLib::class.java)
    // 插件运行时 + 音频（app 进程：PluginManager 已在 onCreate loadAll，OCR/插件实例直接可用）
    ideExecutor.registerService("plugin", com.autolua.engine.extension.plugin.PluginLib(),
      com.autolua.engine.extension.plugin.PluginLib::class.java)
    ideExecutor.registerService("ocr", com.autolua.engine.extension.ocr.OcrLib(),
      com.autolua.engine.extension.ocr.OcrLib::class.java)
    ideExecutor.registerService("media", com.autolua.engine.extension.media.MediaLib(),
      com.autolua.engine.extension.media.MediaLib::class.java)
    ideExecutor.registerService("ime", com.autolua.engine.extension.input.ImeLib(),
      com.autolua.engine.extension.input.ImeLib::class.java)
    ideExecutor.registerService("mysql", com.autolua.engine.extension.db.MySqlLib(),
      com.autolua.engine.extension.db.MySqlLib::class.java)
    ideExecutor.registerService("event", com.autolua.engine.extension.event.EventLib(),
      com.autolua.engine.extension.event.EventLib::class.java)
    // 虚拟屏（Shizuku 路径，任务书 §4/§5）：virtualDisplay 表 + vd 对象；三环境一致注册，compat 之前
    ideExecutor.registerService("virtualDisplay", com.autolua.engine.extension.virtualdisplay.VirtualDisplayLib(),
      com.autolua.engine.extension.virtualdisplay.VirtualDisplayLib::class.java)
    // 动态 UI：uimgr（app 进程同实例）+ uilib（ui_* Lua API 层）
    ideExecutor.registerService("uimgr", UiMgrImp.instance, IUiMgr::class.java)
    ideExecutor.registerService("uilib", com.autolua.engine.extension.ui.UiLib(),
      com.autolua.engine.extension.ui.UiLib::class.java)
    ideExecutor.setRootDir(sdCardDir + "project")
    ideExecutor.setLifecycleDelegate(object : LuaEngineExecutor.LifecycleDelegate {
      override fun pause() {
        AutoLuaEngineProxy.instance.pause()
      }

      override fun resume() {
        AutoLuaEngineProxy.instance.resume()
      }
    })
    IdeServerManager.getInstance().setApplicationContext(this)
    IdeServerManager.getInstance().attachEngine(ideExecutor)
    IdeServerManager.getInstance().start()
    // UDP 设备发现（9.11 调试通道整合）：随 IDE 通道启停，按 Preferences 决定开/关
    DiscoveryManager.apply(this)

    // 远程启动脚本（方案八 9.5）：run_script 经 ScriptRunDelegate 走主引擎完整环境
    // （root 进程注入服务 + C++ 图色绑定）。注意必须在 app 进程读文件内容后 execute：
    // root 引擎进程（uid 0 app_process）无法访问 /storage/emulated 的 FUSE 视图，
    // loadfile('<absPath>') 会报 file not found（懒人/派大星同为内容直传方案）
    IdeServerManager.getInstance().setRootDir(sdCardDir + "project")
    IdeServerManager.getInstance().setScriptRunDelegate { absPath ->
      try {
        val content = java.io.File(absPath).readText(Charsets.UTF_8)
        // 脚本目录注入：__SCRIPT_DIR__ = 本脚本所在目录（工程根），脚本无需自行探测定位
        // （cloud.lua 等 dofile 加载、找图相对路径的基准）。路径统一 / 分隔并转义引号。
        val scriptDir = java.io.File(absPath).parent?.replace('\\', '/') ?: "."
        val dirLiteral = scriptDir.replace("\\", "\\\\").replace("\"", "\\\"")
        // print 桥接：临时重定义 print 指向 IdeLog LocalService（跨进程回传 IDE log 帧），
        // 仅对 IDE 提交的脚本生效；原 print 行为保留（__p 透传，App 内日志不受影响）。
        // 注意服务方法必须冒号调用（self 占 arg#1，见 lua_context.cpp callMethod 约定），
        // 点语法 __ide_log.log(s) 会把 s 当 self 报 "expected LuaObjectAdapter"。
        // 主引擎 Lua state 跨 execute 保留：若每次直接 __p = print 会把上次包装后的函数
        // 当原始 print，包装层层叠加（运行 N 次输出 N 行）。故首次包装前把原始 print
        // 存入全局 __ide_orig_print，后续运行从该键恢复，保证幂等。
        // 引擎进程的 PluginManager 只在其启动时 loadAll 一次——app 进程此后新装的插件
        // 它感知不到（list=0/旧快照）。run_script 前在 Lua 层幂等刷新一次（已加载的跳过）
        val wrapped = """
          |rawset(_G, '__SCRIPT_DIR__', "$dirLiteral")
          |pcall(function() if plugin and plugin.loadAll then plugin.loadAll() end end)
          |local __ide_log = IdeLog
          |local __p = rawget(_G, '__ide_orig_print')
          |if __p == nil then
          |  __p = print
          |  rawset(_G, '__ide_orig_print', __p)
          |end
          |if __ide_log then
          |  print = function(...)
          |    local n = select('#', ...)
          |    if n > 0 then
          |      local t = {}
          |      for i = 1, n do t[i] = tostring(select(i, ...)) end
          |      __ide_log:log(table.concat(t, '  '))
          |    end
          |    __p(...)
          |  end
          |end
          |__ide_no_hook = function() print = __p end
        """.trimMargin() + "\n" + content
        AutoLuaEngineProxy.instance.execute(wrapped)
        true
      } catch (e: Throwable) {
        android.util.Log.e("MainApp", "run_script read/submit failed: $absPath", e)
        false
      }
    }

    // UI 热重载触发（9.11 调试通道整合）：老 8176 通道退役，ui_debug_start/stop 经此委托执行。
    // 逻辑自 DebugServiceImp.startUIDebugActivity/finishUIDebugActivity 原样迁移
    IdeServerManager.getInstance().setUiDebugDelegate(object : UiDebugDelegate {
      override fun start() {
        CoroutineScope(Dispatchers.Main).launch {
          val initData = MLSBundleUtils.createInitData(
            com.immomo.mls.Constants.ASSETS_PREFIX + "hotreload.lua?ct=" + HotReloadServer.NET_CONNECTION)
          initData.extras = HashMap<Any, Any>()
          initData.extras["ROOT_DIR"] = Configure.rootDir
          initData.extras["RESOURCE_DIR"] = Configure.resDir
          val intent = Intent(this@MainApp, DebugActivity::class.java)
          intent.putExtras(MLSBundleUtils.createBundle(initData))
          intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          startActivity(intent)
        }
      }

      override fun stop() {
        LocalBroadcastManager.getInstance(this@MainApp)
          .sendBroadcast(Intent(DebugActivity.CLOSE_BROADCAST))
      }
    })

    // 无障碍自拉活（方案八 9.6）：app 被杀后 system_server 自动重建无障碍服务进程时，
    // 顺带拉起保活体系与 IDE WebSocket 服务器（远程唤醒第一重保险）。
    // helper 侧已 try-catch(Throwable) 全包裹 + 后台线程执行，异常不影响系统绑定
    AccessibilityLaunchHelper.setDelegate { ctx ->
      try {
        KeepAliveManager.start(ctx)
      } catch (t: Throwable) {
        android.util.Log.w("MainApp", "keep-alive self start failed: $t")
      }
      try {
        IdeServerManager.getInstance().start()
        // IDE 通道重启场景同步 UDP 设备发现（9.11）
        DiscoveryManager.apply(ctx)
      } catch (t: Throwable) {
        android.util.Log.w("MainApp", "ide server self start failed: $t")
      }
    }

    // 插件动态加载（方案七）：注入上下文并扫描重载已安装插件
    // （安装本身落盘即持久，重启后由此恢复；OCR 等插件经 WS plugin_push_* 或本地直装安装）
    PluginManager.getInstance().setApplicationContext(this)
    // 插件包验签公钥（ECDSA P-256，配套 plugins/sign-plugin.ps1；注入后 install 强制
    // 校验 manifest.signature，签名缺失/无效拒绝安装。换签发密钥时同步更新此对值）
    PluginManager.getInstance().setSignaturePublicKeyHex(
        "7c3fbbba0c109fbdcdb497d3b9e00a7a266bb938e8b4bc9f0a4b7395f8ee4f41",
        "3d6d952953a26a86b8b2e8adaef3e95f897f8a1f403bfb174620f4b3cf8cfda3"
    )
    PluginManager.getInstance().loadAll()
    // 插件 Lua API 贡献（沙箱侧）：loadAll 同步完成后，实现 LuaEnvContributor 的插件
    // 向 IDE 沙箱注入自定义全局（主引擎侧由 pushLocalServices 在建 context 时贡献）；
    // 协程内的直装若装到新贡献插件，装完后补偿触发一次
    ideExecutor.contributePlugins()
    CoroutineScope(Dispatchers.IO).launch {
      PluginManager.getInstance().installFromAssets()
      PluginManager.getInstance().installFromExternalDir()
      ideExecutor.contributePlugins()
    }

    startService(android.content.Intent(this,
      MainService::class.java))
  }

  private fun getRootPath(): String {
    return getExternalFilesDir(null)?.absolutePath!!
  }

  /**
   * 无 root 进程内沙箱引擎（虚拟屏任务书 §2 无 root 图色地基）：Client.Builder root 进程
   * 拉起失败（无 su）时，在 app 主进程以 AutoLuaEngineOnLocal.Builder 构建引擎。
   * 图色经 SharedMemoryDisplay 桥接 CaptureScreenService 共享内存帧管线。
   * 无条件注入（不判断采集服务运行状态）：SharedMemoryDisplay.update 自带映射自愈，
   * 未授权时无可读帧 → hasRealFrame=false → 图色统一报可读错误；引擎构建后用户才完成
   * 投屏授权的场景自动恢复（若按服务状态条件注入，Engine 只建一次会把 EmptyDisplay 固化）。
   * 服务注册对齐 root 进程 Server.kt initEngine 的 CLASS 型全集 + IDE 沙箱 OBJECT 型四件，
   * 保证跨环境 Lua 全局名一致（01-架构总览 §8 三执行环境铁律）。
   */
  private fun buildLocalSandboxEngine(): AutoLuaEngine {
    val builder = AutoLuaEngineOnLocal.Builder()
    builder.setDisplay(com.autolua.engine.extension.display.SharedMemoryDisplay())
    // OBJECT 型（app 主进程现成实例，与 IDE 沙箱注册同款）
    builder.addLocalService("UI", UserInterfaceImp.instance, UserInterface::class.java)
    builder.addLocalService("FloatController", FloatControllerImp.instance, FloatController::class.java)
    builder.addLocalService("IdeLog", IdeLogService(), IdeLog::class.java)
    builder.addLocalService("uimgr", UiMgrImp.instance, IUiMgr::class.java)
    // CLASS 型（进程内反射实例化，对齐 root 进程 Server.kt initEngine 注册全集）
    builder.addLocalService("http", com.autolua.engine.extension.net.NetLib::class.java,
      com.autolua.engine.extension.net.INetLib::class.java)
    builder.addLocalService("luajava", com.autolua.engine.extension.bridge.JavaBridgeLib::class.java,
      com.autolua.engine.extension.bridge.IJavaBridge::class.java)
    builder.addLocalService("LuaEngine", com.autolua.engine.extension.bridge.LuaEngineLib::class.java,
      com.autolua.engine.extension.bridge.ILuaEngineLib::class.java)
    // 商业引擎兼容域库（file/device/crypt/app 对象 + 各自 contribute 的懒人风格全局函数）
    builder.addLocalService("file", com.autolua.engine.extension.file.FileLib::class.java,
      com.autolua.engine.extension.file.FileLib::class.java)
    builder.addLocalService("device", com.autolua.engine.extension.device.DeviceLib::class.java,
      com.autolua.engine.extension.device.DeviceLib::class.java)
    builder.addLocalService("crypt", com.autolua.engine.extension.crypto.CryptoLib::class.java,
      com.autolua.engine.extension.crypto.CryptoLib::class.java)
    // Lua 兼容层——必须在 cloudServer 之前（CLOUD_LUA 的 OnStop 包装依赖 compat 先装载）
    builder.addLocalService("compat", com.autolua.engine.extension.compat.CompatLib::class.java,
      com.autolua.engine.extension.compat.CompatLib::class.java)
    // 云控通道（Java 下沉）：WS 9300 + PSK 帧加解密在 Java 层，Lua 只见明文 JSON 收发
    builder.addLocalService("cloudServer", com.autolua.engine.cloud.CloudService::class.java,
      com.autolua.engine.cloud.ICloudService::class.java)
    builder.addLocalService("app", com.autolua.engine.extension.app.AppLib::class.java,
      com.autolua.engine.extension.app.AppLib::class.java)
    // 插件运行时 + 音频（app 进程：PluginManager 已在 onCreate loadAll，OCR/插件实例直接可用）
    builder.addLocalService("plugin", com.autolua.engine.extension.plugin.PluginLib::class.java,
      com.autolua.engine.extension.plugin.PluginLib::class.java)
    builder.addLocalService("ocr", com.autolua.engine.extension.ocr.OcrLib::class.java,
      com.autolua.engine.extension.ocr.OcrLib::class.java)
    builder.addLocalService("media", com.autolua.engine.extension.media.MediaLib::class.java,
      com.autolua.engine.extension.media.MediaLib::class.java)
    builder.addLocalService("ime", com.autolua.engine.extension.input.ImeLib::class.java,
      com.autolua.engine.extension.input.ImeLib::class.java)
    builder.addLocalService("mysql", com.autolua.engine.extension.db.MySqlLib::class.java,
      com.autolua.engine.extension.db.MySqlLib::class.java)
    builder.addLocalService("event", com.autolua.engine.extension.event.EventLib::class.java,
      com.autolua.engine.extension.event.EventLib::class.java)
    // 虚拟屏（Shizuku 路径，任务书 §4/§5）：virtualDisplay 表 + vd 对象；三环境一致注册，compat 之前
    builder.addLocalService("virtualDisplay", com.autolua.engine.extension.virtualdisplay.VirtualDisplayLib::class.java,
      com.autolua.engine.extension.virtualdisplay.VirtualDisplayLib::class.java)
    // 动态 UI Lua 层（ui_* API 全集；uimgr 已注册 app 进程同实例，惰性寻址不依赖注册顺序）
    builder.addLocalService("uilib", com.autolua.engine.extension.ui.UiLib::class.java,
      com.autolua.engine.extension.ui.UiLib::class.java)
    builder.isRoot(false)
    return builder.build()!!
  }

  companion object {
    private lateinit var instance: MainApp
    fun getInstance(): MainApp {
      return instance
    }

    fun getPackageNameImpl(): String {
      var sPackageName = instance.packageName
      if (sPackageName.contains(":")) {
        sPackageName = sPackageName.substring(0, sPackageName.lastIndexOf(":"))
      }
      return sPackageName
    }
  }
}