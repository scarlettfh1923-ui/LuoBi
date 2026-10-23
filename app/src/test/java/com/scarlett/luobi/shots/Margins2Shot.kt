package com.scarlett.luobi.shots

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scarlett.luobi.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 1.19：边距方案对比（旧 / 紧凑 / 适中），只留页码版式 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class)
class Margins2Shot {
    private val dir = System.getProperty("user.dir") + "/../../mockups/v1.19/margins2/"
    private val body = buildString {
        append("那一年，合肥把全部家当押在了一块屏幕上，所有人都觉得它疯了。没人想到，十年后这座城市会成为全国最重要的新型显示产业基地之一。\n")
        append("这笔钱在当时相当于全市一年的财政收入，决策者几乎没有退路。\n")
        append("## 一场看不懂的赌局\n")
        append("政府投资平台出资，引入企业落地，再通过股权退出回收资金，这套被称为“合肥模式”的打法，本质上是用财政资金做产业风险投资。\n")
        append("它的成功依赖于对产业周期的判断，也依赖于足够的耐心和退出机制。很多城市想复制，却只学到了表面。\n")
        append("## 为什么别人学不会\n")
        repeat(14) { append("第一，合肥有中科大和一批科研院所，人才和技术储备是长期积累的结果；第二，决策链条短，敢于在低谷期加码；第三，退出之后的资金继续滚动投入下一个产业，形成了循环。\n") }
    }
    data class V(val key: String, val name: String, val h: Int, val v: Int?, val tight: Boolean, val cap: Float)
    private val vs = listOf(V("old", "旧（上一版）", 100, null, false, .5f), V("compact", "紧凑 56/72", 56, 72, true, 0f), V("medium", "适中 72/96", 72, 96, true, 0f))
    @After fun reset() { Render.marginH = 72; Render.marginV = null; Render.tightRhythm = true; Render.justifyCap = 0f }
    private fun save(b: Bitmap, name: String) { File(dir).mkdirs(); File(dir + name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }

    private fun annotate(src: Bitmap, label: String, mh: Int): Bitmap {
        val w = src.width; val h = src.height; val vm = Render.vMargin(h)
        val bg = src.getPixel(w / 2, 2)
        fun ink(x: Int, y: Int) = src.getPixel(x, y).let { p -> Math.abs(Color.red(p) - Color.red(bg)) + Math.abs(Color.green(p) - Color.green(bg)) + Math.abs(Color.blue(p) - Color.blue(bg)) > 60 }
        val footTop = h - vm / 2 - 30
        val inkRow = BooleanArray(h) { y -> y < footTop && (mh - 34 until w - 20 step 2).any { ink(it, y) } }
        val runs = mutableListOf<IntArray>(); var st = -1
        for (y in 0 until h) { if (inkRow[y] && st < 0) st = y; if (!inkRow[y] && st >= 0) { runs += intArrayOf(st, y - 1); st = -1 } }
        val isHead = runs.map { r -> (r[0]..r[1]).any { y -> (mh - 30 until mh - 20).any { x -> src.getPixel(x, y).let { Color.blue(it) > 200 && Color.red(it) < 90 } } } }
        val top = runs.first()[0]; val bot = runs.last()[1]
        val footRows = (footTop until h).filter { y -> (0 until w step 2).any { ink(it, y) } }
        val fTop = footRows.firstOrNull() ?: h
        val cols = (mh - 8 until w).filter { x -> (top..bot step 3).any { ink(x, it) } }
        val left = cols.first(); val right = w - 1 - cols.last()
        // 行内空白 = 段内相邻行的间隔里最常见的那个
        val gaps = (1 until runs.size).map { Triple(it, runs[it][0] - runs[it - 1][1] - 1, isHead[it] to isHead[it - 1]) }
        val lineGap = gaps.filter { !it.third.first && !it.third.second }.groupingBy { it.second }.eachCount().maxByOrNull { it.value }?.key ?: 0
        val b = src.copy(Bitmap.Config.ARGB_8888, true); val c = Canvas(b)
        val ln = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2F6BFF.toInt(); strokeWidth = 3f }
        val dash = Paint(ln).apply { strokeWidth = 2f; pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f); alpha = 140 }
        val tx = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2F6BFF.toInt(); textSize = 28f; typeface = Typeface.create(Typeface.DEFAULT, 700, false) }
        val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt() }
        fun pct(v: Int) = String.format("%.1f%%", v * 100f / h)
        fun tag(s: String, x: Float, y: Float) { val tw = tx.measureText(s); val xx = x.coerceAtMost(w - tw - 14); c.drawRoundRect(xx - 8, y - 28, xx + tw + 8, y + 9, 9f, 9f, pill); c.drawText(s, xx, y, tx) }
        fun vdim(x: Float, y0: Int, y1: Int, s: String) { c.drawLine(x, y0.toFloat(), x, y1.toFloat(), ln); c.drawLine(x - 12, y0.toFloat(), x + 12, y0.toFloat(), ln); c.drawLine(x - 12, y1.toFloat(), x + 12, y1.toFloat(), ln); tag(s, x + 18, (y0 + y1) / 2f + 10) }
        c.drawLine(0f, top.toFloat(), w.toFloat(), top.toFloat(), dash); c.drawLine(0f, bot.toFloat(), w.toFloat(), bot.toFloat(), dash)
        c.drawLine(left.toFloat(), 0f, left.toFloat(), h.toFloat(), dash); c.drawLine((w - right).toFloat(), 0f, (w - right).toFloat(), h.toFloat(), dash)
        vdim(w * .55f, 0, top, "上 ${top}px · ${pct(top)}")
        vdim(w * .55f, bot, h, "下(到页边) ${h - bot}px · ${pct(h - bot)}")
        vdim(w * .22f, bot, fTop, "末行→页脚 ${fTop - bot}")
        val hy = h * .5f
        c.drawLine(0f, hy, left.toFloat(), hy, ln); tag("左 $left", 4f, hy - 14)
        c.drawLine((w - right).toFloat(), hy, w.toFloat(), hy, ln); tag("右 $right", w - right - 20f - tx.measureText("右 $right"), hy - 14)
        val out = mutableMapOf<String, Int>("行内" to lineGap)
        gaps.firstOrNull { it.third.first }?.let { g -> out["小标题上"] = g.second; vdim(w - 150f, runs[g.first - 1][1], runs[g.first][0], "标题上 ${g.second}") }
        gaps.firstOrNull { it.third.second }?.let { g -> out["小标题下"] = g.second; vdim(w - 150f, runs[g.first - 1][1], runs[g.first][0], "标题下 ${g.second}") }
        // 段距：比行内空白明显大、且不挨着小标题
        gaps.firstOrNull { !it.third.first && !it.third.second && it.second > lineGap + 6 }?.let { g -> out["段距"] = g.second; vdim(w - 150f, runs[g.first - 1][1], runs[g.first][0], "段距 ${g.second}") }
        tag("$label · 行内空白 $lineGap", 16f, h - 14f)
        stats[label] = out + mapOf("top" to top, "bottom" to h - bot, "toFoot" to fTop - bot, "left" to left, "right" to right)
        return b
    }
    private val stats = linkedMapOf<String, Map<String, Int>>()

    @Test fun compare() {
        val outs = mutableListOf<Triple<String, String, Bitmap>>()
        val report = StringBuilder()
        for (r in listOf(CardRatio.R34, CardRatio.entries.first { it.name == "R916" })) for (v in vs) {
            Render.marginH = v.h; Render.marginV = v.v; Render.tightRhythm = v.tight; Render.justifyCap = v.cap
            val rt = if (r == CardRatio.R34) "3x4" else "9x16"
            val pages = Render.pages("合肥的赌局", body, Render.styles[0], spacing = Render.LINE_DEFAULT, width = r.w, height = r.h, kind = BodyKind.PLAIN)
            val ch = Render.lastChars
            val full = ch.dropLast(1)
            val label = "${v.name} · ${if (r == CardRatio.R34) "3:4" else "9:16"}"
            val a = annotate(pages[0], label, v.h)
            val a2 = annotate(pages[1], "$label p2", v.h)
            save(a, "${v.key}-$rt-p1.png"); save(a2, "${v.key}-$rt-p2.png")
            outs += Triple(rt, v.key, a2)
            report.append("$label | pages=${pages.size} | p1=${ch[0]} p2=${ch.getOrNull(1)} | avgFull=${if (full.isEmpty()) 0 else full.average().toInt()} | ${stats[label]} | p2 ${stats["$label p2"]}\n")
        }
        File(dir + "numbers.txt").writeText(report.toString()); println(report)
        val s = 0.32f; val gap = 28
        val cw = (1080 * s).toInt(); val h34 = (1440 * s).toInt(); val h916 = (1920 * s).toInt()
        val sheet = Bitmap.createBitmap(gap + 3 * (cw + gap), 100 + h34 + 70 + h916 + gap, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet); c.drawColor(0xFFECEEF2.toInt())
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 28f; color = 0xFF14171F.toInt(); typeface = Typeface.create(Typeface.DEFAULT, 700, false) }
        c.drawText("第 2 页（满页）　左→右：旧（上一版） / 紧凑 56·72 / 适中 72·96　上：3:4　下：9:16　行距 1.7", gap.toFloat(), 56f, tp)
        val f = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        outs.filter { it.first == "3x4" }.forEachIndexed { i, t -> val x = (gap + i * (cw + gap)).toFloat(); c.drawBitmap(t.third, null, RectF(x, 90f, x + cw, 90f + h34), f) }
        val y2 = 90f + h34 + 60
        outs.filter { it.first == "9x16" }.forEachIndexed { i, t -> val x = (gap + i * (cw + gap)).toFloat(); c.drawBitmap(t.third, null, RectF(x, y2, x + cw, y2 + h916), f) }
        save(sheet, "overview.png")
    }
}
