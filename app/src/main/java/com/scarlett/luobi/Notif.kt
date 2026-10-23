package com.scarlett.luobi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.Settings as SysSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo

/** 2.0.1：所有通知渠道集中创建（启动时 + 每次用前），兜底通知绝不会因渠道不存在而闪退 */
object Notif {
    const val CH_GEN = "gen"; const val CH_GEN_DONE = "done"
    fun ensureChannels(c: Context) = runCatching {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return@runCatching
        nm.createNotificationChannels(listOf(
            NotificationChannel(CH_GEN, "文章生成中", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CH_GEN_DONE, "生成结果", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(FactWorker.CH_PROGRESS, "事实校对进行中", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(FactWorker.CH_DONE, "事实校对结果", NotificationManager.IMPORTANCE_DEFAULT)))
    }
    /** 最简兜底：渠道先建好，带 dataSync 类型 */
    fun fallback(c: Context, id: Int, channel: String, title: String): ForegroundInfo {
        ensureChannels(c)
        val n = NotificationCompat.Builder(c, channel).setSmallIcon(R.drawable.ic_stat_notify).setContentTitle(title).setOngoing(true).setSilent(true).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(id, n)
    }
    fun granted(c: Context) = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(c, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun openSettings(c: Context) { runCatching { c.startActivity(Intent(SysSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(SysSettings.EXTRA_APP_PACKAGE, c.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

/**
 * 2.0.1：通知权限只在第一次生成 / 校对前请求，不在启动时弹。
 * 返回的函数：已授权或系统 < 13 → 直接执行；否则先弹系统授权，不论同意与否都继续执行任务（拒绝只提示一次，可去设置开启）。
 */
@Composable
fun rememberNotifGate(): (() -> Unit) -> Unit {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("notif", Context.MODE_PRIVATE)
    var pending: (() -> Unit)? = null
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok && !prefs.getBoolean("deniedHint", false)) {
            prefs.edit().putBoolean("deniedHint", true).apply()
            snack("通知已关闭：进度仍会显示在应用里，完成后不会弹提醒", false, "去开启") { Notif.openSettings(ctx) }
        }
        pending?.invoke(); pending = null
    }
    return { task ->
        if (Notif.granted(ctx) || prefs.getBoolean("asked", false)) task()
        else { prefs.edit().putBoolean("asked", true).apply(); pending = task
            try { launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS) } catch (_: Throwable) { pending = null; task() } }
    }
}
