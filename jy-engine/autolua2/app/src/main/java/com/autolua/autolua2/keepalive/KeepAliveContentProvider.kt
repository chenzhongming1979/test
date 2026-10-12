package com.autolua.autolua2.keepalive

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log

/**
 * 保活 ContentProvider。
 *
 * Provider 初始化先于 Application.onCreate：进程一旦被系统拉起即执行 onCreate，
 * 借此尽早触发保活调度；同时任意组件对 provider 的访问也会拉起本进程。
 * 内容实现保持为空（query/insert/update/delete 全部返回空值）。
 */
class KeepAliveContentProvider : ContentProvider() {

  override fun onCreate(): Boolean {
    Log.d(TAG, "Keep-alive content provider created")
    try {
      val context = context ?: return true
      // 进程被拉起即排即时 Job，并尝试恢复前台服务；
      // 后台启动 FGS 受限时（Android 12+）会抛异常，由 Job/Alarm 兜底
      KeepAliveJobService.scheduleImmediateJob(context)
      KeepAliveManager.startForegroundService(context)
    } catch (e: Exception) {
      Log.e(TAG, "Keep-alive provider onCreate failed", e)
    }
    return true
  }

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?
  ): Cursor? = null

  override fun getType(uri: Uri): String? = null

  override fun insert(uri: Uri, values: ContentValues?): Uri? = null

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?
  ): Int = 0

  companion object {
    private const val TAG = "KeepAliveProvider"
  }
}
