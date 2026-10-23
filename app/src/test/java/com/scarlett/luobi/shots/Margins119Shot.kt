package com.scarlett.luobi.shots

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scarlett.luobi.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 1.19：正文页边距预览（蓝色标注线 + px / 占页高 %） */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class)
class Margins119Shot {
    private val dir = System.getProperty("user.dir") + "/../../mockups/v1.19/margins/"
    private val body = buildString {
        append("那一年，合肥把全部家当押在了一块屏幕上，所有人都觉得它疯了。没人想到，十年后这座城市会成为全国最重要的新型显示产业基地之一。\n")
        append("## 一场看不懂的赌局\n")
        repeat(6) { append("政府投资平台出资，引入企业落地，再通过股权退出回收资金，这套被称为“合肥模式”的打法，本质上是用财政资金做产业风险投资。它的成功依赖于对产业周期的判断，也依赖于足够的耐心和退出机制。\n") }
    }
    private fun save(b: Bitmap, name: String) { File(dir).mkdirs(); File(dir + name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }

    private fun annotate(src: Bitmap, kind: BodyKind, label: String): Bitmap {
        val w = src.width; val h = src.height; val vm = Render.vMargin(h)
        val bg = src.getPixel(w / 2, 4)
        fun ink(x: Int, y: Int) = src.getPixel(x, y).let { p -> Math.abs(Color.red(p) - Color.red(bg)) + Math.abs(Color.green(p) - Color.green(bg)) + Math.abs(Color.blue(p) - Color.blue(bg)) > 60 }
        val footTop = h - vm / 2 - 40
        val rows = (0 until footTop).filter { y -> (60 until w - 60 step 2).any { ink(it, y) } }
        val top = rows.first(); val bot = rows.last()
        val footRows = (footTop until h).filter { y -> (0 until w step 2).any { ink(it, y) } }
        val fTop = footRows.firstOrNull() ?: h; val fBot = footRows.lastOrNull() ?: h
        val cols = (80 until w).filter { x -> (top..bot step 3).any { ink(x, it) } }
        val left = cols.first(); val right = w - 1 - cols.last()
        val b = src.copy(Bitmap.Config.ARGB_8888, true); val c = Canvas(b)
        val ln = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2F6BFF.toInt(); strokeWidth = 3f }
        val dash = Paint(ln).apply { strokeWidth = 2f; pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f); alpha = 150 }
        val tx = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2F6BFF.toInt(); textSize = 30f; typeface = Typeface.create(Typeface.DEFAULT, 700, false) }
        val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF0FFFFFF.toInt() }
        fun pct(v: Int) = String.format("%.1f%%", v * 100f / h)
        fun tag(s: String, x: Float, y: Float) { val tw = tx.measureText(s); c.drawRoundRect(x - 10, y - 32, x + tw + 10, y + 10, 10f, 10f, pill); c.drawText(s, x, y, tx) }
        fun vdim(x: Float, y0: Int, y1: Int, s: String) { c.drawLine(x, y0.toFloat(), x, y1.toFloat(), ln); c.drawLine(x - 14, y0.toFloat(), x + 14, y0.toFloat(), ln); c.drawLine(x - 14, y1.toFloat(), x + 14, y1.toFloat(), ln); tag(s, x + 22, (y0 + y1) / 2f + 12) }
        c.drawLine(0f, top.toFloat(), w.toFloat(), top.toFloat(), dash); c.drawLine(0f, bot.toFloat(), w.toFloat(), bot.toFloat(), dash)
        c.drawLine(left.toFloat(), 0f, left.toFloat(), h.toFloat(), dash); c.drawLine((w - right).toFloat(), 0f, (w - right).toFloat(), h.toFloat(), dash)
        vdim(w * .62f, 0, top, "上边距 ${top}px · ${pct(top)}")
        vdim(w * .30f, bot, fTop, "末行→页脚 ${fTop - bot}px · ${pct(fTop - bot)}")
        vdim(w * .62f, bot, h, "下边距(到页边) ${h - bot}px · ${pct(h - bot)}")
        val hy = h * .55f
        c.drawLine(0f, hy, left.toFloat(), hy, ln); tag("左 ${left}px", 6f, hy - 18); tag("小标题蓝条在 x=70", 6f, hy + 40)
        c.drawLine((w - right).toFloat(), hy, w.toFloat(), hy, ln); tag("右 ${right}px", w - right - 30f - tx.measureText("右 ${right}px"), hy - 18)
        tag("页脚 ${fTop}–${fBot}", w - 300f, fTop - 14f)
        val tp = Paint(tx).apply { textSize = 34f }; tag(label, 24f, h - 22f - 0f)
        println("MARGIN $label top=$top bot=${h - bot} toFoot=${fTop - bot} left=$left right=$right")
        return b
    }

    @Test fun margins() {
        val outs = mutableListOf<Pair<String, Bitmap>>()
        for (k in BodyKind.entries) for (r in listOf(CardRatio.R34, CardRatio.entries.first { it.name == "R916" })) {
            val pages = Render.pages("合肥的赌局", body, Render.styles[0], spacing = Render.LINE_DEFAULT, width = r.w, height = r.h, kind = k)
            val tag = if (r == CardRatio.R34) "3x4" else "9x16"
            val a = annotate(pages[0], k, "${k.label} · ${if (r == CardRatio.R34) "3:4" else "9:16"} · 行距 1.7")
            save(a, "margin-${k.name.lowercase()}-$tag.png"); outs += tag to a
        }
        // 总览：两行（3:4 / 9:16），每行三种版式
        val s = 0.3f; val gap = 30
        val cw = (1080 * s).toInt(); val h34 = (1440 * s).toInt(); val h916 = (1920 * s).toInt()
        val sheet = Bitmap.createBitmap(gap + 3 * (cw + gap), 90 + h34 + gap + 60 + h916 + gap, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet); c.drawColor(0xFFECEEF2.toInt())
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; color = 0xFF14171F.toInt(); typeface = Typeface.create(Typeface.DEFAULT, 700, false) }
        c.drawText("落笔 1.19 正文页边距 · 3:4（上）/ 9:16（下）· 行距 1.7", gap.toFloat(), 56f, tp)
        val f = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        outs.filter { it.first == "3x4" }.forEachIndexed { i, (_, b) -> c.drawBitmap(b, null, RectF((gap + i * (cw + gap)).toFloat(), 90f, (gap + i * (cw + gap) + cw).toFloat(), (90 + h34).toFloat()), f) }
        val y2 = 90 + h34 + gap + 60
        outs.filter { it.first == "9x16" }.forEachIndexed { i, (_, b) -> c.drawBitmap(b, null, RectF((gap + i * (cw + gap)).toFloat(), y2.toFloat(), (gap + i * (cw + gap) + cw).toFloat(), (y2 + h916).toFloat()), f) }
        save(sheet, "overview.png")
    }
}
