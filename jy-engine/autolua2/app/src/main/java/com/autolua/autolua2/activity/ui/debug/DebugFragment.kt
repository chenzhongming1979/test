package com.autolua.autolua2.activity.ui.debug

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.autolua.autolua2.R
import com.autolua.autolua2.databinding.FragmentDebugBinding
import com.autolua.autolua2.debug.DiscoveryManager
import com.autolua.autolua2.guide.RestrictedSettingsGuide
import com.autolua.engine.accessibility.AutoLuaAccessibilityService
import com.autolua.engine.display.projection.CaptureScreenService
import com.autolua.engine.display.projection.ScreenshotProviderManager
import com.autolua.engine.display.stream.ScreenStreamController
import com.autolua.engine.ide.IdeServerManager
import com.autolua.engine.input.inject.InjectMode
import com.autolua.engine.input.inject.InjectShellClient
import com.autolua.engine.input.inject.ShizukuInjectorChannel
import org.json.JSONObject

/** 权限引导行的状态色：绿=就绪，红=未开启 */
private const val PERM_COLOR_OK = 0xFF2E7D32.toInt()
private const val PERM_COLOR_OFF = 0xFFC62828.toInt()

/** 权限引导行标识 */
private const val ROW_PROJECTION = 0
private const val ROW_ACCESSIBILITY = 1
private const val ROW_SHIZUKU = 2

/**
 * 远程调试面板（9.11 调试通道整合：8176 老调试通道退役，统一 8080 WebSocket IDE）。
 * <p>
 * 展示：IDE 服务状态/端口、在线客户端数、本机 IP、UDP 设备发现开关、投屏快捷开关、
 * 连接提示（IDE 通过 ws://IP:端口 连接）。状态经 1s Handler 轮询刷新本页面
 * （engine 侧无状态回调）。
 * <p>
 * 退役迁移：原"调试服务开关/Token/端口输入"（老通道 DebugServiceImp 绑定逻辑）移除；
 * 投屏快捷开关仅在本页面切换会话，页面退出不停止推流（投屏独立于页面生命周期）。
 */
class DebugFragment: Fragment(){
  private var _binding : FragmentDebugBinding? = null
  private val binding get() = _binding!!
  private lateinit var debugViewModel: DebugViewModel

  /** 状态轮询（1s）：服务状态/端口/在线客户端/流状态/连接提示 */
  private val refreshHandler = Handler(Looper.getMainLooper())
  private val refreshRunnable = object : Runnable {
    override fun run() {
      if (_binding == null) return
      renderIdeState()
      refreshHandler.postDelayed(this, 1000)
    }
  }

  /** 渲染 IDE 服务状态行/客户端数/流状态/连接提示 + 权限引导行 */
  private fun renderIdeState() {
    val manager = IdeServerManager.getInstance()
    if (manager.isRunning()) {
      val port = manager.getPort()
      binding.tvIdeState.text = getString(R.string.ide_state_running)
      binding.tvIdePort.text = "端口: $port"
      binding.tvClientCount.text = manager.getClientCount().toString()
      binding.tvConnectHint.text =
        getString(R.string.ide_connect_hint_fmt, debugViewModel.myIP.value ?: "", port)
    } else {
      binding.tvIdeState.text = getString(R.string.ide_state_stopped)
      binding.tvIdePort.text = getString(R.string.ide_port_not_started)
      binding.tvClientCount.text = "0"
      binding.tvConnectHint.text = getString(R.string.ide_connect_hint_off)
    }
    binding.tvStreamState.text =
      getString(if (ScreenStreamController.getInstance().isStreaming) R.string.stream_state_on else R.string.stream_state_off)
    binding.switchScreenStream.isChecked = ScreenStreamController.getInstance().isStreaming
    renderPermissions()
  }

  /** 权限引导行状态（1s 轮询随 renderIdeState 刷新，服务被杀/授权变化实时反映） */
  private fun renderPermissions() {
    // 投屏虚拟屏：CaptureScreenService 前台采集服务运行中 = 已授权
    val projectionOk = CaptureScreenService.isRunning()
    binding.tvPermProjection.text =
      getString(if (projectionOk) R.string.perm_state_ok else R.string.perm_state_projection_off)
    binding.tvPermProjection.setTextColor(if (projectionOk) PERM_COLOR_OK else PERM_COLOR_OFF)
    // 无障碍注入：AutoLuaAccessibilityService 连接中 = 已开启
    val accessibilityOk = AutoLuaAccessibilityService.isRunning()
    binding.tvPermAccessibility.text =
      getString(if (accessibilityOk) R.string.perm_state_ok else R.string.perm_state_accessibility_off)
    binding.tvPermAccessibility.setTextColor(if (accessibilityOk) PERM_COLOR_OK else PERM_COLOR_OFF)
    // Shizuku：binder 就绪且已授权（未装/未启动为未授权态）
    val shizukuOk = try { ShizukuInjectorChannel().isAvailable() } catch (e: Throwable) { false }
    binding.tvPermShizuku.text =
      getString(if (shizukuOk) R.string.perm_state_shizuku_ok else R.string.perm_state_shizuku_off)
    binding.tvPermShizuku.setTextColor(if (shizukuOk) PERM_COLOR_OK else PERM_COLOR_OFF)
    // 注入模式（手动偏好，persist 于 SharedPreferences）
    binding.tvInjectMode.text = injectModeLabel()
  }

  /** 注入模式选项：null=自动降级链；label 与 InjectMode 一一对应 */
  private val injectModeLabels
    get() = arrayOf("自动（按可用性降级）", "ROOT（root 注入进程）", "Shizuku（shell 免 root）", "无障碍（免 root 手势）")
  private val injectModeValues
    get() = arrayOf<InjectMode?>(null, InjectMode.ROOT, InjectMode.SHIZUKU, InjectMode.ACCESSIBILITY)

  private fun injectModePrefs(): SharedPreferences =
    requireContext().getSharedPreferences("remote_debug", 0)

  private fun injectModeLabel(): String {
    val idx = injectModePrefs().getInt("inject_mode_idx", 0)
    return injectModeLabels.getOrElse(idx) { injectModeLabels[0] }
  }

  private fun applyInjectMode(idx: Int) {
    injectModePrefs().edit().putInt("inject_mode_idx", idx).apply()
    InjectShellClient.getInstance().setPreferredMode(injectModeValues[idx])
  }

  /** Android 13+ 侧载受限引导：对话框说明解除步骤，可跳应用详情页开启「允许受限设置」 */
  private fun showRestrictedSettingsGuide() {
    val ctx = requireContext()
    AlertDialog.Builder(ctx)
      .setTitle(R.string.restricted_settings_guide_title)
      .setMessage(
        getString(R.string.restricted_settings_guide_prefix) + "\n" +
        RestrictedSettingsGuide.GUIDE_TEXT)
      .setPositiveButton(R.string.restricted_settings_go_details) { _, _ ->
        try {
          startActivity(RestrictedSettingsGuide.buildAppDetailsIntent(ctx))
        } catch (e: Throwable) {
          android.util.Log.w("DebugFragment", "open app details failed: $e")
        }
      }
      .setNegativeButton(R.string.restricted_settings_go_accessibility) { _, _ ->
        try {
          startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Throwable) {
          android.util.Log.w("DebugFragment", "open accessibility settings failed: $e")
        }
      }
      .show()
  }

  /** 注入模式选择弹窗（手动指定后 start/dispatch 优先使用该通道） */
  private fun showInjectModeDialog() {
    val prefs = injectModePrefs()
    val checked = prefs.getInt("inject_mode_idx", 0)
    AlertDialog.Builder(requireContext())
      .setTitle(R.string.inject_mode_dialog_title)
      .setSingleChoiceItems(injectModeLabels, checked) { dialog, which ->
        applyInjectMode(which)
        binding.tvInjectMode.text = injectModeLabel()
        dialog.dismiss()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  /** 权限引导点击动作 */
  private fun onPermissionRowClicked(which: Int) {
    when (which) {
      // 投屏虚拟屏：重新弹出 MediaProjection 系统授权框（服务运行中点它可重授权/换屏）
      ROW_PROJECTION ->
        CaptureScreenService.startCapture(requireActivity())
      // 无障碍注入：跳系统无障碍设置（Android 13+ 侧载置灰见卡片底部提示）
      ROW_ACCESSIBILITY -> {
        // 无障碍注入：跳系统无障碍设置；Android 13+ 侧载受限候选改走「允许受限设置」引导
        if (!AutoLuaAccessibilityService.isRunning() &&
          RestrictedSettingsGuide.isRestrictedSettingsCandidate(requireContext())) {
          showRestrictedSettingsGuide()
        } else {
          startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
      }
      // Shizuku：发起授权弹框；未运行（binder 不存在）时提示启动方法
      ROW_SHIZUKU -> {
        if (!ShizukuInjectorChannel().requestPermission()) {
          Toast.makeText(requireContext(), R.string.perm_shizuku_start_failed, Toast.LENGTH_LONG).show()
        }
      }
    }
  }

  /** 投屏快捷开关：开=先确保帧源就绪（root 免授权自动 initRoot；App 模式未授权弹授权框），
   *  再对第一个在线客户端启动 jpeg 5fps/quality 60/scale 1.0 会话；关=停止 */
  private fun onStreamSwitchChanged(isChecked: Boolean) {
    if (isChecked) {
      // 帧源校验（对齐派大星 run_mode：root 环境免授权直接过；App 模式未授权弹授权框）
      val frameSourceOk = try {
        ScreenshotProviderManager.getInstance().prepare()
      } catch (e: Throwable) {
        false
      }
      if (!frameSourceOk) {
        CaptureScreenService.startCapture(requireActivity())
        Toast.makeText(requireContext(), R.string.inject_mode_need_projection, Toast.LENGTH_LONG).show()
        binding.switchScreenStream.isChecked = false
        return
      }
      val clientId = IdeServerManager.getInstance().getFirstClientId()
      if (clientId == null) {
        Toast.makeText(requireContext(), R.string.stream_no_client, Toast.LENGTH_SHORT).show()
        binding.switchScreenStream.isChecked = false
        return
      }
      try {
        val params = JSONObject()
        params.put("codec", "jpeg")
        params.put("fps", 5)
        params.put("quality", 60)
        params.put("scale", 1.0)
        ScreenStreamController.getInstance().startSession(clientId, params)
      } catch (e: Exception) {
        // 未授权投影/帧源未就绪等：提示错误并回弹开关
        Toast.makeText(requireContext(),
          getString(R.string.stream_start_failed, e.message ?: e.toString()),
          Toast.LENGTH_LONG).show()
        binding.switchScreenStream.isChecked = false
      }
    } else {
      // 关：无条件停止当前会话（stopSession(null) 语义即忽略订阅者匹配）
      ScreenStreamController.getInstance().stopSession(null)
    }
  }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    debugViewModel = ViewModelProvider(this)[DebugViewModel::class.java]
    _binding = FragmentDebugBinding.inflate(inflater, container, false)
    val root: View = binding.root

    // 本机 IP（复用既有 DebugViewModel.myIP 逻辑）
    debugViewModel.myIP.observe(viewLifecycleOwner) {
      binding.myIp.text = it
      renderIdeState()
    }

    // UDP 设备发现开关：读 Preferences 初始态，切换即写回并启停（9.11 老广播发现迁移挂新通道）
    binding.switchUdpDiscovery.isChecked = DiscoveryManager.isEnabled(requireContext())
    binding.switchUdpDiscovery.setOnCheckedChangeListener { _, isChecked ->
      DiscoveryManager.setEnable(requireContext(), isChecked)
    }

    // 投屏快捷开关
    binding.switchScreenStream.setOnCheckedChangeListener { _, isChecked ->
      onStreamSwitchChanged(isChecked)
    }

    // 权限引导行：点击触发各自引导动作
    binding.rowPermProjection.setOnClickListener { onPermissionRowClicked(ROW_PROJECTION) }
    binding.rowPermAccessibility.setOnClickListener { onPermissionRowClicked(ROW_ACCESSIBILITY) }
    binding.rowPermShizuku.setOnClickListener { onPermissionRowClicked(ROW_SHIZUKU) }

    // 注入模式行：恢复持久化的手动偏好 + 点击弹选择框
    InjectShellClient.getInstance().setPreferredMode(
      injectModeValues.getOrElse(injectModePrefs().getInt("inject_mode_idx", 0)) { null }
    )
    binding.rowInjectMode.setOnClickListener { showInjectModeDialog() }

    return root
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    renderIdeState()
    refreshHandler.postDelayed(refreshRunnable, 1000)
  }

  override fun onDestroyView() {
    super.onDestroyView()
    refreshHandler.removeCallbacks(refreshRunnable)
    _binding = null
  }
}
