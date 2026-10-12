package com.autolua.autolua2.keepalive

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * 保活 JobService。
 *
 * - 持久周期 Job：jobId=1001，15 分钟周期 + 5 分钟弹性 + 网络连通触发 + 线性退避 30s，
 *   setPersisted(true) 使其进程重启后仍保留；
 * - 即时 Job：setMinimumLatency(0).setOverrideDeadline(1000)，1 秒内必触发，
 *   jobId 用时间戳防重复。
 *
 * 注：android.app.job.JobService 框架层没有 onFinishJob 可重写回调，
 * 拉活兜底覆盖在 onStartJob / onStopJob / 服务 onDestroy 三个入口上。
 */
class KeepAliveJobService : JobService() {

  override fun onStartJob(params: JobParameters?): Boolean {
    Log.d(TAG, "Keep-alive job started (id=${params?.jobId})")
    try {
      // 尝试拉起前台服务
      KeepAliveManager.startForegroundService(this)
      // 无障碍存活守护（P0.2）：Job 心跳兜底检查（FGS 启动受限时仍可触发）
      AccessibilityWatchdog.onHeartbeat(this)
      // 持久 Job 触发时顺带重排自身，防止被系统取消后不再调度
      if (params?.jobId == PERSISTENT_JOB_ID) {
        schedulePersistentJob(this)
      }
    } catch (e: Exception) {
      Log.e(TAG, "onStartJob failed", e)
    } finally {
      // 本次任务为同步逻辑，直接向系统汇报完成
      jobFinished(params, false)
    }
    return false
  }

  override fun onStopJob(params: JobParameters?): Boolean {
    Log.d(TAG, "Keep-alive job stopped (id=${params?.jobId})")
    try {
      // Job 被系统中断时同样尝试拉活兜底
      KeepAliveManager.startForegroundService(this)
    } catch (e: Exception) {
      Log.e(TAG, "onStopJob failed", e)
    }
    // 返回 true 让系统按退避策略重新调度
    return true
  }

  override fun onDestroy() {
    super.onDestroy()
    // 任务结束兜底：无论成败都尝试拉起前台服务
    try {
      KeepAliveManager.startForegroundService(this)
    } catch (e: Exception) {
      Log.e(TAG, "onDestroy keep alive failed", e)
    }
  }

  companion object {
    private const val TAG = "KeepAliveJobService"

    /** 持久周期 Job 的固定 ID */
    const val PERSISTENT_JOB_ID = 1001

    /** 周期：15 分钟 */
    private const val PERIODIC_INTERVAL_MS = 15 * 60 * 1000L

    /** 弹性窗口：5 分钟 */
    private const val PERIODIC_FLEX_MS = 5 * 60 * 1000L

    /** 退避：30 秒，线性递增 */
    private const val BACKOFF_MS = 30 * 1000L

    /**
     * 调度持久周期 Job（需 RECEIVE_BOOT_COMPLETED 权限才能 setPersisted）
     */
    fun schedulePersistentJob(context: Context) {
      try {
        val scheduler =
          context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val builder =
          JobInfo.Builder(PERSISTENT_JOB_ID, ComponentName(context, KeepAliveJobService::class.java))
            .setPersisted(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setRequiresCharging(false)
            .setRequiresDeviceIdle(false)
            .setBackoffCriteria(BACKOFF_MS, JobInfo.BACKOFF_POLICY_LINEAR)
        // setPeriodic(interval, flex) 为 API 24+，低版本退化为单参数
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
          builder.setPeriodic(PERIODIC_INTERVAL_MS, PERIODIC_FLEX_MS)
        } else {
          builder.setPeriodic(PERIODIC_INTERVAL_MS)
        }
        val result = scheduler.schedule(builder.build())
        Log.d(TAG, "Persistent keep-alive job scheduled, interval=${PERIODIC_INTERVAL_MS}ms, result=$result")
      } catch (e: Exception) {
        Log.e(TAG, "schedulePersistentJob failed", e)
      }
    }

    /**
     * 调度即时 Job：0 延迟、1 秒内必须触发，jobId 用时间戳防重复
     */
    fun scheduleImmediateJob(context: Context) {
      try {
        val scheduler =
          context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val jobId = (System.currentTimeMillis() % 10000 + 500).toInt()
        scheduler.schedule(
          JobInfo.Builder(jobId, ComponentName(context, KeepAliveJobService::class.java))
            .setMinimumLatency(0L)
            .setOverrideDeadline(1000L)
            .setPersisted(false)
            .build()
        )
        Log.d(TAG, "Immediate keep-alive job scheduled (jobId=$jobId)")
      } catch (e: Exception) {
        Log.e(TAG, "scheduleImmediateJob failed", e)
      }
    }
  }
}
