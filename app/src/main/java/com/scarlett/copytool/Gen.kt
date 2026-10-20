package com.scarlett.copytool

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/** 进程级状态：App 是否在前台、通知点开要看的文章、后台协程作用域 */
object AppState {
    @Volatile var visible = 0
    val openNote = MutableStateFlow(0L)
    val sharedText = MutableStateFlow<String?>(null)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

/** 一次生成任务（写作或改写）。内容可能很长，存在 SharedPreferences 里，Worker 只拿类型 */
data class GenJob(
    val kind: String = "write", val raw: String = "", val cleaned: String = "", val values: Map<String, String> = emptyMap(),
    val templateId: Long = 0, val templateName: String = "", val model: String = "", val finalPrompt: String = "",
    val noteId: Long = 0, val instruction: String = "",
) {
    fun json(): String = JSONObject().put("kind", kind).put("raw", raw).put("cleaned", cleaned).put("values", JSONObject(values as Map<*, *>))
        .put("templateId", templateId).put("templateName", templateName).put("model", model).put("finalPrompt", finalPrompt)
        .put("noteId", noteId).put("instruction", instruction).toString()
    val label get() = if (kind == "rewrite") "改写：$instruction" else templateName
    companion object {
        fun parse(s: String): GenJob = JSONObject(s).let { j ->
            val v = j.optJSONObject("values"); val m = LinkedHashMap<String, String>()
            v?.keys()?.forEach { m[it] = v.optString(it) }
            GenJob(j.optString("kind"), j.optString("raw"), j.optString("cleaned"), m, j.optLong("templateId"), j.optString("templateName"),
                j.optString("model"), j.optString("finalPrompt"), j.optLong("noteId"), j.optString("instruction"))
        }
    }
}

/**
 * 生成放在 WorkManager（加急任务）里跑：切到后台、旋转屏幕、页面销毁都不会中断，
 * 生成完照样存进文案库；后台完成时发通知。（沿用小红书助手 Gen.kt 的做法）
 */
class CopyWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("gen", "文章生成中", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(applicationContext, "gen").setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("正在写文章…").setContentText(inputData.getString(K_LABEL) ?: "").setOngoing(true).setSilent(true).build()
        return ForegroundInfo(NID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val job = try { GenJob.parse(ctx.getSharedPreferences("gen", Context.MODE_PRIVATE).getString("job", "{}") ?: "{}") } catch (e: Exception) { return Result.failure() }
        return try {
            val id = if (job.kind == "rewrite") Ai.rewrite(ctx, job.noteId, job.instruction, job.model) else Ai.write(ctx, job)
            val title = AppDb.get(ctx).notes().get(id)?.title ?: ""
            if (AppState.visible <= 0) notify(ctx, "文章已写好：$title", "点开查看全文，已自动存进文案库", id)
            Result.success(workDataOf(K_NOTE to id, K_KIND to job.kind))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            val f = Errors.explain(e)
            if (AppState.visible <= 0) notify(ctx, "生成失败：${f.title}", f.detail, 0L)
            Result.failure(workDataOf(K_ERR_TITLE to f.title, K_ERR_DETAIL to f.detail, K_ERR_RAW to f.raw.take(400), K_ERR_SET to f.settings, K_KIND to job.kind))
        }
    }

    companion object {
        const val NAME = "copy"
        const val K_LABEL = "label"; const val K_NOTE = "note"; const val K_KIND = "kind"
        const val K_ERR_TITLE = "et"; const val K_ERR_DETAIL = "ed"; const val K_ERR_RAW = "er"; const val K_ERR_SET = "es"
        const val NID_PROGRESS = 11; const val NID_DONE = 12

        /** 同一时间只跑一篇；已有任务在跑时再点不会重复生成 */
        fun start(c: Context, job: GenJob) {
            c.getSharedPreferences("gen", Context.MODE_PRIVATE).edit().putString("job", job.json()).apply()
            val req = OneTimeWorkRequestBuilder<CopyWorker>()
                .setInputData(workDataOf(K_LABEL to job.label))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(c).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, req)
        }
        fun cancel(c: Context) { WorkManager.getInstance(c).cancelUniqueWork(NAME) }
        fun clearDoneNotice(c: Context) { NotificationManagerCompat.from(c).cancel(NID_DONE) }

        fun notify(c: Context, title: String, text: String, noteId: Long) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("done", "生成结果", NotificationManager.IMPORTANCE_DEFAULT))
            val pi = PendingIntent.getActivity(c, noteId.toInt(), Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_NOTE, noteId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(c, "done").setSmallIcon(R.drawable.ic_stat_notify).setContentTitle(title).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(pi).setAutoCancel(true).build()
            try { NotificationManagerCompat.from(c).notify(NID_DONE, n) } catch (_: SecurityException) {}
        }
    }
}
