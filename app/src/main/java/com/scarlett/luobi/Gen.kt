package com.scarlett.luobi

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/** 进程级状态：App 是否在前台、通知点开要看的文章、后台协程作用域 */
object AppState {
    @Volatile var visible = 0
    val openNote = MutableStateFlow(0L)
    /** 1.17：点「校对完成」通知 → 打开这篇并弹出结果 */
    val openFact = MutableStateFlow(0L)
    val sharedText = MutableStateFlow<String?>(null)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

/**
 * 1.18：创作页「灵感」上方的进度卡状态（方案 C）。写完后存在 prefs 里，App 被杀也还在；
 * 看过这篇（阅读页打开过）→ viewed；回到创作页时卡片先淡出再收起高度，然后 clear。
 */
data class GenCardState(val noteId: Long, val title: String, val words: Int, val viewed: Boolean = false)
object GenCard {
    val state = MutableStateFlow<GenCardState?>(null)
    private fun p(c: Context) = c.getSharedPreferences("gen", Context.MODE_PRIVATE)
    fun load(c: Context) {
        val j = p(c).getString("card", null)
        val s = if (j == null) null else try { JSONObject(j).let { GenCardState(it.getLong("id"), it.optString("t"), it.optInt("w").coerceAtLeast(0), it.optBoolean("v")) } } catch (_: Exception) { null }
        // 1.18.1：坏的 / 无效的卡片状态直接清掉
        if (j != null && (s == null || s.noteId <= 0)) { p(c).edit().remove("card").apply(); state.value = null } else state.value = s
    }
    private fun save(c: Context, s: GenCardState?) {
        state.value = s
        p(c).edit().apply { if (s == null) remove("card") else putString("card", JSONObject().put("id", s.noteId).put("t", s.title).put("w", s.words).put("v", s.viewed).toString()) }.apply()
    }
    fun done(c: Context, id: Long, title: String, words: Int) = save(c, GenCardState(id, title, words))
    /** 阅读页打开了这篇 */
    fun markViewed(c: Context, id: Long) { val s = state.value ?: return; if (s.noteId == id && !s.viewed) save(c, s.copy(viewed = true)) }
    fun clear(c: Context) = save(c, null)
}

/** 生成进度（Worker → UI）：第几步、已写字数、标题、正在打的那一行 */
/** 2.3：stage = 当前阶段（1 定提纲 / 2 成文 / 3 校对），stages = 共几段（2 或 3） */
data class GenProgress(val step: Int = 1, val words: Int = 0, val title: String = "", val line: String = "", val stage: Int = 1, val stages: Int = 2) {
    val stageNames get() = Ai.Stage.names(stages)
}
object StreamPeek {
    /** 从还没写完的 JSON 里取出某个字符串字段（容忍截断、转义） */
    fun field(acc: String, name: String): String? {
        val key = "\"" + name + "\""
        var k = acc.indexOf(key); if (k < 0) return null
        k += key.length
        while (k < acc.length && (acc[k].isWhitespace() || acc[k] == ':')) k++
        if (k >= acc.length || acc[k] != '"') return if (k >= acc.length) "" else null
        val sb = StringBuilder(); var i = k + 1
        while (i < acc.length) {
            val ch = acc[i]
            if (ch == '"') break
            if (ch == '\\') {
                if (i + 1 >= acc.length) break
                when (val e = acc[i + 1]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> {}
                    'u' -> { if (i + 5 < acc.length) { acc.substring(i + 2, i + 6).toIntOrNull(16)?.let { sb.append(it.toChar()) }; i += 4 } else break }
                    else -> sb.append(e)
                }
                i += 2; continue
            }
            sb.append(ch); i++
        }
        return sb.toString()
    }
    /** 正文最后一行（去掉 Markdown 记号），用于卡片里的打字行 */
    fun lastLine(body: String): String = body.trimEnd().substringAfterLast('\n').trimStart('#', '>', '-', '*', ' ').replace("**", "").trim()
    /** 字数：不算空白与 Markdown 记号 */
    fun words(body: String): Int = body.count { !it.isWhitespace() && it !in "#*>-" }
    fun progress(acc: String): GenProgress {
        val body = field(acc, "body")
        return GenProgress(if (body == null) 1 else 2, body?.let(::words) ?: 0, field(acc, "title").orEmpty(), body?.let(::lastLine).orEmpty())
    }
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
    val label get() = if (kind == "rewrite") (if (instruction == Ai.CONTINUE) "续写" else "改写：$instruction") else templateName
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
    /** 1.18.1：只有 Android 11 及以下的加急任务才会走到这里；任何异常都退回不带类型的通知，绝不抛出 */
    override suspend fun getForegroundInfo(): ForegroundInfo = try { fgInfo() } catch (_: Throwable) { Notif.fallback(applicationContext, NID_PROGRESS, Notif.CH_GEN, "正在写文章…") }
    private fun fgInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("gen", "文章生成中", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(applicationContext, "gen").setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("正在写文章…").setContentText(inputData.getString(K_LABEL) ?: "").setOngoing(true).setSilent(true).build()
        return ForegroundInfo(NID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val job = try { GenJob.parse(ctx.getSharedPreferences("gen", Context.MODE_PRIVATE).getString("job", "{}") ?: "{}") } catch (e: Exception) { return Result.failure() }
        // 2.0.1：前台 dataSync 通知，锁屏 / 切应用也不被系统停掉；不允许（后台启动限制等）时照常以加急任务继续
        runCatching { setForeground(fgInfo()) }
        return try {
            val stages = if (Settings(ctx).polish23) 3 else 2
            if (job.kind != "rewrite") { GenCard.clear(ctx); report(GenProgress(1, stages = stages)) }
            var last = 0L
            var stage = 1; var lastP = GenProgress(1, stages = stages)
            // 2.0.1：流式回调里只放最新进度（不阻塞读流），另一个协程每 250ms 异步上报
            val latest = kotlinx.coroutines.flow.MutableStateFlow<GenProgress?>(null)
            val id = kotlinx.coroutines.coroutineScope {
                val reporter = launch { latest.collect { p -> if (p != null) { report(p); kotlinx.coroutines.delay(250) } } }
                try {
                    if (job.kind == "rewrite") Ai.rewrite(ctx, job.noteId, job.instruction, job.model) else Ai.write(ctx, job, { acc ->
                        val now = System.currentTimeMillis()
                        if (now - last >= 250) { last = now; lastP = StreamPeek.progress(acc).copy(stage = stage, stages = stages); latest.value = lastP }
                    }) { st, n -> stage = st
                        // 校对阶段：字数和标题保留成文时的，进度条停在后段
                        lastP = if (st == Ai.Stage.POLISH) lastP.copy(step = 2, stage = st, stages = n, line = "") else GenProgress(1, stage = st, stages = n)
                        latest.value = lastP }
                } finally { reporter.cancel() }
            }
            val note = AppDb.get(ctx).notes().get(id)
            val title = note?.title ?: ""
            if (job.kind == "rewrite") { if (AppState.visible <= 0) notify(ctx, "改写好了", "《$title》已存为新版本", id) }
            else {
                val w = StreamPeek.words(note?.body.orEmpty())
                report(GenProgress(3, w, title, stage = stages, stages = stages))
                GenCard.done(ctx, id, title, w)
                notify(ctx, DONE_TITLE, doneText(title, w) + (if (Ai.Truncated.has(ctx, id)) "（没写完，被截断了，打开可以续写）" else ""), id)
            }
            Result.success(workDataOf(K_NOTE to id, K_KIND to job.kind))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            val f = Errors.explain(e)
            if (AppState.visible <= 0) notify(ctx, "生成失败：${f.title}", f.detail, 0L)
            Result.failure(workDataOf(K_ERR_TITLE to f.title, K_ERR_DETAIL to f.detail, K_ERR_RAW to f.raw.take(400), K_ERR_SET to f.settings, K_KIND to job.kind))
        }
    }

    private suspend fun report(p: GenProgress) = try { setProgress(workDataOf(P_STEP to p.step, P_WORDS to p.words, P_TITLE to p.title, P_LINE to p.line.takeLast(60), P_STAGE to p.stage, P_STAGES to p.stages)) } catch (_: Exception) {}

    companion object {
        const val P_STEP = "ps"; const val P_STAGE = "pg"; const val P_STAGES = "pn"; const val P_WORDS = "pw"; const val P_TITLE = "pt"; const val P_LINE = "pl"
        const val DONE_TITLE = "文章写好了"
        fun doneText(title: String, w: Int) = "《$title》${fmtWords(w)} 字，已放进案头"
        fun progressOf(d: Data) = GenProgress(d.getInt(P_STEP, 1).coerceIn(1, 3), d.getInt(P_WORDS, 0).coerceAtLeast(0), d.getString(P_TITLE).orEmpty(), d.getString(P_LINE).orEmpty(), d.getInt(P_STAGE, 1).coerceIn(1, 3), d.getInt(P_STAGES, 2).coerceIn(2, 3))
        fun fmtWords(n: Int) = String.format(java.util.Locale.US, "%,d", n)
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
            try { WorkManager.getInstance(c).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, req) } catch (_: Throwable) {}
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
