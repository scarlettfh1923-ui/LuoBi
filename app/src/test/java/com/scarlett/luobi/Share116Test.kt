package com.scarlett.luobi

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = com.scarlett.luobi.shots.TestApp::class)
class Share116Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun shareFileIsLosslessFullResolutionPng() {
        val b = Bitmap.createBitmap(1440, 3000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE); setPixel(7, 9, Color.rgb(47, 107, 255)) }
        val f = Share.writePng(ctx, b, "long_1.jpg")
        assertTrue(f.name.endsWith(".png"))
        val back = BitmapFactory.decodeFile(f.path)
        assertEquals(1440, back.width); assertEquals(3000, back.height)
        assertEquals(Color.rgb(47, 107, 255), back.getPixel(7, 9)) // 无损：像素逐个一致
    }

    @Test fun singleAndMultipleIntentsCarryStreamClipDataMimeAndGrant() {
        val fs = (1..3).map { Share.writePng(ctx, Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888), "xhs_$it.png") }
        val us = fs.map { Share.uriOf(ctx, it) }
        val one = Share.imageIntent(us.take(1))
        assertEquals(Intent.ACTION_SEND, one.action); assertEquals("image/png", one.type)
        assertTrue(one.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("content", us[0].scheme); assertEquals("com.scarlett.luobi.files", us[0].authority)
        val many = Share.imageIntent(us)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, many.action); assertEquals("image/png", many.type)
        @Suppress("DEPRECATION") assertEquals(3, many.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!.size)
        assertEquals(3, many.clipData!!.itemCount); assertEquals("image/png", many.clipData!!.description.getMimeType(0))
    }

    /** 小标题：上方空白明显大于下方（与阅读页一致），各种行距都成立；并输出 3:4 / 9:16 渲染样张 */
    @Test fun headingHugsFollowingParagraphAndSamples() {
        for (sp in listOf(1.4f, 1.5f, 1.7f, 1.8f, 2.0f)) {
            val (above, below) = Render.visualHeadGaps(46f, sp)
            assertTrue("spacing $sp above=$above below=$below", above >= below * 1.8f)
        }
        val body = (1..3).joinToString("\n") { i -> "## 第${i}个小标题\n" + "这是正文段落，用来检查小标题和段落之间的距离是否像阅读页那样上大下小。".repeat(3) }
        val out = File(System.getProperty("user.dir") + "/../../screenshots/v1.16.0/cards").apply { mkdirs() }
        for (r in CardRatio.entries) for (sp in listOf(1.4f, 1.7f, 2.0f)) {
            val pages = Render.pages("小标题间距", body, Render.styles[0], spacing = sp, width = r.w, height = r.h)
            assertTrue(pages.isNotEmpty())
            pages.forEachIndexed { i, b -> File(out, "${r.name}_sp${sp}_$i.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
    }

    /** 1.16.1：按她那篇文章的结构渲染 9:16 / 3:4，直接量 PNG 里的像素空白 */
    @Test fun measuredPixelGapsAroundHeading() {
        val body = "按时长收费的心理咨询，经常被说成是在「卖时间」。很多人第一次听到一小时几百块，就觉得不划算，觉得咨询师只是在陪聊。\n" +
            "这种印象有它的道理，但也忽略了很多看不见的成本。\n## 先说清楚：计时收费本身没毛病\n" +
            "律师、家教、健身教练都按小时收费，没有人觉得奇怪。真正让人不舒服的，是不知道这一小时里自己买到了什么。\n" +
            "所以问题不在计时，而在于服务是否透明、是否可评估。"
        val out = File(System.getProperty("user.dir") + "/../../screenshots/v1.16.1/measure").apply { mkdirs() }
        val report = StringBuilder()
        for (r in listOf(CardRatio.entries.first { it.name == "R916" }, CardRatio.R34)) for (sp in listOf(Render.LINE_MIN, Render.LINE_DEFAULT, Render.LINE_MAX)) {
            val pages = Render.pages("计时收费", body, Render.styles[0], sizePx = 46f, spacing = sp, width = r.w, height = r.h)
            val b = pages[0]
            // 逐行扫描正文区（x 100..w-100，y 230..h-170），找有字迹的行
            val bg = b.getPixel(60, 600)
            fun inkRow(y: Int) = (100 until r.w - 100 step 2).any { x -> val c = b.getPixel(x, y); kotlin.math.abs(Color.red(c) - Color.red(bg)) + kotlin.math.abs(Color.green(c) - Color.green(bg)) + kotlin.math.abs(Color.blue(c) - Color.blue(bg)) > 90 }
            val runs = mutableListOf<IntArray>(); var start = -1
            for (y in 230 until r.h - 170) { val ink = inkRow(y); if (ink && start < 0) start = y; if (!ink && start >= 0) { runs += intArrayOf(start, y); start = -1 } }
            val gaps = runs.zipWithNext { a, c -> c[0] - a[1] }
            val g = Render.lastGeometry[0]
            val hi = g.indexOfFirst { it.first }
            val rh = Render.rhythm(46f, sp, android.text.TextPaint().apply { textSize = 46f })
            // 标题前后的像素空白：标题字迹最高的一条 run（字号更大）
            val tallest = runs.indices.maxByOrNull { runs[it][1] - runs[it][0] }!!
            val above = runs[tallest][0] - runs[tallest - 1][1]; val below = runs[tallest + 1][0] - runs[tallest][1]
            val paraGaps = gaps.filterIndexed { i, _ -> i != tallest - 1 && i != tallest }.sorted()
            val lineGap = paraGaps.first(); val paraGap = paraGaps.last()
            report.append("${r.label} 行距$sp：标题上 ${above}px / 标题下 ${below}px / 段距 ${paraGap}px / 行内 ${lineGap}px / 行高 ${rh.line.toInt()}px\n")
            File(out, "gaps.txt").writeText(report.toString())
            assertTrue("$sp 下方不大于段距 below=$below para=$paraGap", below <= paraGap)
            assertTrue("$sp 上方约为 2× 段距 above=$above para=$paraGap", above > paraGap * 1.6f)
            assertTrue("$sp 下方约 0.4–0.5 行 below=$below L=${rh.line}", below in (rh.line * 0.35f).toInt()..(rh.line * 0.55f).toInt())
            assertTrue("$sp 段距收紧：比行内空白大、不超 0.85 行（不含行内空白）", paraGap >= lineGap + 4 && paraGap - lineGap <= (rh.line * 0.85f).toInt())
            assertTrue(hi >= 0)
            val y0 = (runs[tallest - 1][0] - 10).coerceAtLeast(0); val y1 = (runs[tallest + 1][1] + 10).coerceAtMost(r.h)
            val crop = Bitmap.createBitmap(b, 0, y0, r.w, y1 - y0)
            File(out, "${r.name}_sp${sp}_heading.png").outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
            pages.forEachIndexed { i, pg -> File(out, "${r.name}_sp${sp}_p$i.png").outputStream().use { pg.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        File(out, "gaps.txt").writeText(report.toString())
    }

    @Test fun oldSpacingMigratesOnce() {
        val sp = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        sp.edit().clear().putFloat("cardSpacing", 1.0f).apply()
        assertEquals(Render.LINE_DEFAULT, Settings(ctx).cardSpacing, 0.001f)
        Settings(ctx).cardSpacing = 1.5f
        assertEquals(1.5f, Settings(ctx).cardSpacing, 0.001f)  // 只迁移一次
        sp.edit().clear().putFloat("cardSpacing", 1.3f).apply()
        assertEquals(2.0f, Settings(ctx).cardSpacing, 0.001f)
    }
}
