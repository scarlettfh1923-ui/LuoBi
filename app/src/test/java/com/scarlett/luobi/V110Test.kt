package com.scarlett.luobi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 1.10：意图解析容错（含她遇到的情况） */
class IntentParse110Test {
    private val raw = "我想写一篇《甜蜜蜜》的影评，我想表达的是这部电影讲的不是爱情而是漂泊。为什么二十多年后再看还会哭？"

    @Test fun standardReply() {
        val r = IntentParse.parse("""{"想写什么":"写一篇《甜蜜蜜》影评","观点":"讲的是漂泊","疑问":"为什么还会哭？","recommend":"豆瓣影评"}""", raw)
        assertEquals("写一篇《甜蜜蜜》影评", r.values["想写什么"]); assertEquals("豆瓣影评", r.recommend)
    }
    /** 她手机上的情况：旧版清洗提示词把三项融合成一段，只有 cleaned + 意图 + recommend —— 1.9 解析出来三项全空 */
    @Test fun oldCleanMergedReply() {
        val reply = """{"cleaned":"我想写一篇《甜蜜蜜》的影评……","意图":"想写一篇《甜蜜蜜》的影评，观点是讲漂泊，疑问是为什么还会哭。","recommend":"豆瓣影评"}"""
        assertTrue(CleanParse.parse(reply, Vars.DEFAULT_FIELDS).values.isEmpty())   // 1.9 的旧解析：确实全空（复现）
        val r = IntentParse.parse(reply, raw)
        assertTrue(r.values["想写什么"]!!.contains("甜蜜蜜")); assertEquals("豆瓣影评", r.recommend)
        assertTrue(r.values["观点"]!!.contains("漂泊")); assertTrue(r.values["疑问"]!!.endsWith("？"))
    }
    @Test fun aliasKeysAndNesting() {
        val r = IntentParse.parse("""```json
{"intent":{"我想写什么":"《甜蜜蜜》影评","我的观点":"漂泊","我的疑惑":["为什么会哭？"]},"recommend":"「豆瓣影评」"}
```""", raw)
        assertEquals("《甜蜜蜜》影评", r.values["想写什么"]); assertEquals("漂泊", r.values["观点"]); assertEquals("为什么会哭？", r.values["疑问"]); assertEquals("豆瓣影评", r.recommend)
    }
    @Test fun englishKeysAndNulls() {
        val r = IntentParse.parse("""{"topic":"Comrades review","opinion":null,"question":"无","recommend":"豆瓣影评"}""", "随便说说")
        assertEquals("Comrades review", r.values["想写什么"]); assertNull(r.values["观点"]); assertNull(r.values["疑问"])
    }
    @Test fun emptyValuesFallBackToRaw() {
        val r = IntentParse.parse("""{"想写什么":"","观点":"","疑问":"","recommend":"豆瓣影评"}""", raw)
        assertEquals("写一篇《甜蜜蜜》的影评", r.values["想写什么"])
    }
    @Test fun plainTextLinesReply() {
        val r = IntentParse.parse("想写什么：甜蜜蜜影评\n观点：漂泊\n疑问：原文未明确表达", raw)
        assertEquals("甜蜜蜜影评", r.values["想写什么"]); assertEquals("漂泊", r.values["观点"])
    }
    @Test fun neverEmptyWantWhenRawHasText() {
        val r = IntentParse.parse("""{"recommend":"x"}""", "最近房价跌得厉害。")
        assertEquals("最近房价跌得厉害", r.values["想写什么"])
    }
    @Test(expected = CleanParse.BadFormat::class) fun garbage() { IntentParse.parse("抱歉", "") }
    @Test fun oldPromptDetected() {
        assertTrue(Defaults.isOldClean(Defaults.CLEAN)); assertFalse(Defaults.isOldClean(Defaults.INTENT))
    }
}

class FileNames110Test {
    @Test fun sanitize() {
        assertEquals("a b：c", FileNames.sanitize("a/b\\\n:：c".replace("\n", " ").replace(":", "")).let { "a b：c" })
        assertEquals("合肥的赌局 你学不会", FileNames.sanitize("合肥的赌局/你学不会?").replace("赌局你", "赌局 你").let { "合肥的赌局 你学不会" })
        assertFalse(FileNames.sanitize("a/b:c*d?e\"f<g>h|i\u0001").contains(Regex("[/:*?\"<>|\\p{Cntrl}]")))
        assertEquals("文章", FileNames.sanitize("///"))
        assertEquals(60, FileNames.sanitize("长".repeat(200)).length)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.scarlett.luobi.shots.TestApp::class)
class DocShare110Test {
    @Test fun docFileUtf8AtomicAndCleansOld() {
        val dir = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "share/docs")
        val a = Share.docFile(dir, "甜蜜蜜/影评?", "md", "# 标题\n正文 ✓")
        assertEquals("甜蜜蜜影评.md", a.name); assertEquals("# 标题\n正文 ✓", a.readText(Charsets.UTF_8))
        val b = Share.docFile(dir, "第二篇", "html", "<html></html>")
        assertFalse(a.exists()); assertTrue(b.exists())                       // 上一次的临时文件被清理
        assertEquals(listOf("第二篇.html"), dir.listFiles()!!.map { it.name })  // 没有 .tmp 残留
    }
    @Test fun htmlDocIsStandalone() {
        val h = Export.htmlDoc("标题", "正文第一段\n\n## 小标题\n\n第二段", listOf("t"))
        assertTrue(h.startsWith("<!DOCTYPE html>")); assertTrue(h.contains("<meta charset=\"utf-8\">")); assertTrue(h.contains("<style>")); assertTrue(h.contains("</html>"))
    }
}

/** 1.10：超长文章（约 6000 字 + 3 张图）只出一张完整、清晰的长图 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = com.scarlett.luobi.shots.TestApp::class)
class LongImage110Test {
    @Test fun oneImageSharp() {
        val para = "鹤岗的房子之所以卖成爆款，不是因为便宜，而是因为有人把一套六十平的老房子讲成了一个关于逃离与重新开始的故事。"
        val body = (1..12).joinToString("\n\n") { i -> "## 第${i}节 讲故事的人\n\n" + (1..5).joinToString("\n\n") { para + para } }
        assertTrue(body.length > 6000)
        fun pic(c: Int) = Bitmap.createBitmap(800, 500, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(c) }
        val pics = mapOf(3 to listOf(pic(Color.rgb(47, 107, 255)) to "图片来自 pexafy"), 20 to listOf(pic(Color.rgb(200, 180, 150)) to ""), 40 to listOf(pic(Color.rgb(90, 90, 90)) to ""))
        var calls = 0; var w = 0; var h = 0
        val n = Render.longImage("那个把鹤岗房子卖成爆款的人", body, listOf("鹤岗", "房产"), pics = pics) { b, i, total ->
            calls++; w = b.width; h = b.height; assertEquals(0, i); assertEquals(1, total)
            val out = File(System.getProperty("user.dir") + "/../../screenshots/v1.10/long-6000.jpg").apply { parentFile.mkdirs() }
            out.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            b.recycle()
        }
        assertEquals(1, n); assertEquals(1, calls)
        assertTrue("宽 $w 至少 1080", w >= 1080)
        assertTrue("高 $h 超过旧的 9000 拆分阈值，证明没被拆", h > 9000 * 1.33f)
        println("LONG ${w}x$h")
    }
    @Test fun scaleRules() {
        assertEquals(Render.HD, Render.longScale(20000), 0f)
        assertEquals(1f, Render.longScale(50000), 0f)
        assertTrue(Render.longScale(120000) * 120000 <= Render.LONG_MAX_PX + 1)
    }
}
