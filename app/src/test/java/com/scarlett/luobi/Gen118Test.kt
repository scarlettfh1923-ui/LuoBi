package com.scarlett.luobi

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.scarlett.luobi.shots.TestApp::class)
class Gen118Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    @After fun r() { Ai.chatTransport = null; GenCard.clear(ctx) }

    private val article = JSONObject().put("title", "合肥的赌局").put("body", "第一段正文内容。\n\n第二段正文内容。").put("tags", org.json.JSONArray(listOf("财经"))).put("category", "财经").toString()
    private fun sse(s: String) = s.chunked(7).joinToString("") { "data: " + JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("delta", JSONObject().put("content", it)))) + "\n\n" } + "data: [DONE]\n"

    private fun run(body: String): ListenableWorker.Result {
        Settings(ctx).setKey("deepseek", "sk-test")
        Ai.chatTransport = { _, _ -> 200 to body }
        ctx.getSharedPreferences("gen", Context.MODE_PRIVATE).edit().putString("job", GenJob(raw = "合肥", cleaned = "合肥", model = "p:deepseek", finalPrompt = "写").json()).commit()
        return runBlocking { TestListenableWorkerBuilder<CopyWorker>(ctx).build().doWork() }
    }

    @Test fun workerStreamsSavesCardAndNotifies() {
        val res = run(sse(article))
        assertTrue(res is ListenableWorker.Result.Success)
        val id = res.outputData.getLong(CopyWorker.K_NOTE, 0)
        val note = runBlocking { AppDb.get(ctx).notes().get(id)!! }
        assertEquals("合肥的赌局", note.title)
        val c = GenCard.state.value!!
        assertEquals(id, c.noteId); assertEquals("合肥的赌局", c.title); assertFalse(c.viewed)
        assertEquals(StreamPeek.words(note.body), c.words)
        val n = shadowOf(ctx.getSystemService(NotificationManager::class.java)).getNotification(CopyWorker.NID_DONE)!!
        assertEquals("文章写好了", n.extras.getString("android.title"))
        assertEquals("《合肥的赌局》${CopyWorker.fmtWords(c.words)} 字，已放进案头", n.extras.getString("android.text"))
        val pi = shadowOf(n.contentIntent).savedIntent
        assertEquals(id, pi.getLongExtra(MainActivity.EXTRA_NOTE, -1))
    }

    @Test fun plainJsonFallbackAlsoWorks() {
        val body = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("content", article)))).toString()
        assertTrue(run(body) is ListenableWorker.Result.Success)
        assertNotNull(GenCard.state.value)
    }

    @Test fun failureLeavesNoCard() {
        assertTrue(run("not json") is ListenableWorker.Result.Failure)
        assertNull(GenCard.state.value)
    }

    @Test fun cardDismissal() {
        GenCard.done(ctx, 7, "t", 3420)
        GenCard.load(ctx); assertEquals(7L, GenCard.state.value!!.noteId)
        GenCard.markViewed(ctx, 7); assertTrue(GenCard.state.value!!.viewed)
        GenCard.load(ctx); assertTrue("重启后仍记得已看过", GenCard.state.value!!.viewed)
        GenCard.clear(ctx); GenCard.load(ctx); assertNull(GenCard.state.value)
    }

    @Test fun streamPeekAndTexts() {
        val acc = "{\"title\":\"合肥的\\\"赌局\\\"\",\"body\":\"# 开头\\n第一句话。\\n> 第二"
        assertEquals("合肥的\"赌局\"", StreamPeek.field(acc, "title"))
        assertEquals("# 开头\n第一句话。\n> 第二", StreamPeek.field(acc, "body"))
        assertNull(StreamPeek.field(acc, "tags"))
        val p = StreamPeek.progress(acc)
        assertEquals("合肥的\"赌局\"", p.title); assertTrue(p.step >= 2); assertTrue(p.words > 0)
        assertEquals(4, StreamPeek.words("# 你好 **世界**"))
        assertEquals("3,420", CopyWorker.fmtWords(3420))
        assertEquals("《a》3,420 字，已放进案头", CopyWorker.doneText("a", 3420))
    }

    @Test fun wechatIntents() {
        val u = arrayListOf(android.net.Uri.parse("content://x/1.png"), android.net.Uri.parse("content://x/2.png"))
        val i = Out.fileIntent(u)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, i.action)
        assertEquals("application/octet-stream", i.type)
        assertTrue(i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(2, i.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!.size)
        val one = Out.fileIntent(arrayListOf(u[0])); assertEquals(Intent.ACTION_SEND, one.action)
        assertEquals("已存相册，在聊天里点＋选图并勾选原图", ChannelActions.WECHAT_TIP)
    }

    /** 设计一致性：源码里不再有系统对话框、M3 默认弹层、硬编码红色、波纹 */
    @Test fun tokenUsage() {
        val src = File(System.getProperty("user.dir") + "/src/main/java/com/scarlett/luobi").listFiles()!!.filter { it.extension == "kt" }
        val bad = mutableListOf<String>()
        src.forEach { f -> f.readLines().forEachIndexed { n, l ->
            val code = l.substringBefore("//")
            listOf("AlertDialog(", "Color(0xFFC0392B)", "rememberRipple", "ripple(").forEach { if (code.contains(it)) bad += "${f.name}:${n + 1} $it" }
            if (code.contains("ModalBottomSheet(") && f.name != "Buttons.kt") bad += "${f.name}:${n + 1} ModalBottomSheet"
            if (Regex("""\b(Button|TextButton|OutlinedButton|IconButton)\(""").containsMatchIn(code) && f.name != "Buttons.kt") bad += "${f.name}:${n + 1} M3 button"
            if (code.contains("RoundedCornerShape(") && f.name !in setOf("Theme.kt", "Tokens.kt", "Shapes.kt")) bad += "${f.name}:${n + 1} RoundedCornerShape"
        } }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }
}
