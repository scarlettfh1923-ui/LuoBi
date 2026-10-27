package com.scarlett.luobi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*

/**
 * 1.17：后台事实校对。点「事实校对 / 重新检测」→ 加急 + 前台任务；
 * 进度通知「正在校对《标题》· n/N」；每核完一条就存进 Room（进程被杀后接着跑）；
 * 完成通知「《标题》校对完成 · 有出入 x · 部分对 y」，点开直达这篇的结果。
 */
class FactWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    private val noteId get() = inputData.getLong(K_NOTE, 0L)

    override suspend fun getForegroundInfo(): ForegroundInfo = try { info(applicationContext, noteId, inputData.getString(K_TITLE).orEmpty(), 0, 0) } catch (_: Throwable) { Notif.fallback(applicationContext, nidProgress(noteId), CH_PROGRESS, "正在校对…") }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val db = AppDb.get(ctx); val dao = db.factJobs()
        val note = db.notes().get(noteId) ?: return Result.failure()
        val title = note.title
        runCatching { setForeground(info(ctx, noteId, title, 0, 0)) }
        val prev = dao.get(noteId)
        val resume = prev?.takeIf { it.status == RUNNING }?.let { FactPipeline.decodeState(it.stateJson) }
        dao.put(FactJob(noteId, title, RUNNING, resume?.claims?.count { it.done } ?: 0, resume?.claims?.size ?: 0, prev?.stateJson.orEmpty().takeIf { resume != null }.orEmpty(), seen = prev?.seen ?: true))
        return try {
            val env = FactPipeline.env(ctx, note.model)
            val report = FactPipeline.run(title, note.body, env, resume,
                save = { st -> dao.progress(noteId, st.claims.count { it.done }, st.claims.size, FactPipeline.encodeState(st)) },
                progress = { d, n -> runCatching { setForeground(info(ctx, noteId, title, d, n)) } })
            // 2.1：上一轮已存的「校对前版本」带过来，重查之后还能一键还原到最初
            val keep = FactCheck.decodeReport(db.notes().get(noteId)?.factReportJson.orEmpty())?.snap
            db.notes().setFactReport(noteId, FactCheck.encodeReport(report.copy(snap = keep)))
            dao.put(FactJob(noteId, title, DONE, report.claims.size, report.claims.size, "", seen = false))
            val bad = FactPipeline.count(report, FactPipeline.BAD); val part = FactPipeline.count(report, FactPipeline.PART)
            notifyDone(ctx, noteId, doneText(title, bad, part), "点开查看每条细节的依据和改法")
            Result.success(workDataOf(K_BAD to bad, K_PART to part))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Throwable) {
            val f = Errors.explain(e)
            dao.put((dao.get(noteId) ?: FactJob(noteId, title)).copy(status = FAILED, error = f.title, updatedAt = System.currentTimeMillis()))
            notifyDone(ctx, noteId, "《$title》校对没完成", f.title + if (f.detail.isNotBlank()) "：" + f.detail else "")
            Result.failure(workDataOf(K_ERR to f.title))
        }
    }

    companion object {
        const val K_NOTE = "note"; const val K_TITLE = "title"; const val K_BAD = "bad"; const val K_PART = "part"; const val K_ERR = "err"
        const val RUNNING = "running"; const val DONE = "done"; const val FAILED = "failed"
        const val CH_PROGRESS = "fact"; const val CH_DONE = "fact_done"
        const val TAG = "fact"
        fun name(id: Long) = "fact-$id"
        fun nidProgress(id: Long) = 3000 + (id % 1000).toInt()
        fun nidDone(id: Long) = 5000 + (id % 1000).toInt()
        fun progressText(title: String, d: Int, n: Int) = "正在校对《$title》" + if (n > 0) "· $d/$n" else "· 拆分说法中"
        fun doneText(title: String, bad: Int, part: Int) = "《$title》校对完成 · 有出入 $bad · 部分对 $part"

        fun info(c: Context, id: Long, title: String, d: Int, n: Int): ForegroundInfo {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "事实校对进行中", NotificationManager.IMPORTANCE_LOW))
            val b = NotificationCompat.Builder(c, CH_PROGRESS).setSmallIcon(R.drawable.ic_stat_notify).setContentTitle(progressText(title, d, n))
                .setOngoing(true).setSilent(true).setOnlyAlertOnce(true).setProgress(n.coerceAtLeast(0), d, n <= 0)
            return ForegroundInfo(nidProgress(id), b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }

        fun openIntent(c: Context, id: Long): PendingIntent = PendingIntent.getActivity(c, 7000 + (id % 1000).toInt(),
            Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_NOTE, id).putExtra(MainActivity.EXTRA_FACT, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        fun notifyDone(c: Context, id: Long, title: String, text: String) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_DONE, "事实校对结果", NotificationManager.IMPORTANCE_DEFAULT))
            val n = NotificationCompat.Builder(c, CH_DONE).setSmallIcon(R.drawable.ic_stat_notify).setContentTitle(title).setContentText(text)
                .setContentIntent(openIntent(c, id)).setAutoCancel(true).build()
            try { NotificationManagerCompat.from(c).notify(nidDone(id), n) } catch (_: SecurityException) {}
        }

        /** 每篇同一时间只跑一个（KEEP）；fresh=true 是「重新检测」：丢掉上次的中间进度 */
        suspend fun start(c: Context, id: Long, title: String, fresh: Boolean) {
            val dao = AppDb.get(c).factJobs()
            val cur = dao.get(id)
            if (!(cur?.status == RUNNING && isActive(c, id))) {
                dao.put(FactJob(id, title, RUNNING, 0, 0, if (fresh) "" else cur?.stateJson.orEmpty().takeIf { cur?.status == RUNNING }.orEmpty(), seen = cur?.seen ?: true))
            }
            val req = OneTimeWorkRequestBuilder<FactWorker>().setInputData(workDataOf(K_NOTE to id, K_TITLE to title)).addTag(TAG)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            // 1.19：重新检测用 REPLACE（顶掉卡住的旧任务）；续跑仍用 KEEP
            WorkManager.getInstance(c).enqueueUniqueWork(name(id), policy(fresh), req)
        }
        fun policy(fresh: Boolean) = if (fresh) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        const val CANCELLED = "已取消"
        /** 1.19：用户点「取消校对」 */
        suspend fun cancel(c: Context, id: Long) {
            runCatching { WorkManager.getInstance(c).cancelUniqueWork(name(id)) }
            val dao = AppDb.get(c).factJobs()
            dao.get(id)?.let { dao.put(it.copy(status = FAILED, error = CANCELLED, updatedAt = System.currentTimeMillis())) }
        }
        fun isActive(c: Context, id: Long): Boolean = runCatching {
            WorkManager.getInstance(c).getWorkInfosForUniqueWork(name(id)).get().any { !it.state.isFinished }
        }.getOrDefault(false)
        fun clearDone(c: Context, id: Long) { NotificationManagerCompat.from(c).cancel(nidDone(id)) }
    }
}

/** 入口按钮状态：跑着 → 进度环；有没看过的结果 → 红点 */
object FactBadge {
    enum class S { NONE, RUNNING, DOT, SEEN }
    /** workActive=null 表示还没拿到 WorkManager 状态（按任务行 15 分钟内有更新算在跑） */
    fun state(job: FactJob?, hasReport: Boolean, workActive: Boolean?, now: Long = System.currentTimeMillis()): S = when {
        job?.status == FactWorker.RUNNING && (workActive ?: (now - job.updatedAt < 15 * 60_000)) -> S.RUNNING
        hasReport && job != null && !job.seen -> S.DOT
        hasReport -> S.SEEN
        else -> S.NONE
    }
}
