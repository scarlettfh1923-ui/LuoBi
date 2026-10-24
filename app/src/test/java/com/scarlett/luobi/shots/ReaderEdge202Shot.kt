package com.scarlett.luobi.shots

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontFamily
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scarlett.luobi.*
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 2.0.2：阅读页左右边距对称、首字不被裁 —— 360dp 宽，多字体 × 多字号 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w360dp-h800dp-xxhdpi")
class ReaderEdge202Shot {
    @get:Rule val rule = createComposeRule()
    private val tag = if (File("/tmp/edge_before").exists()) "before" else "after"
    private val dir = File(System.getProperty("user.dir") + "/../../screenshots/v2.0.2/$tag/").apply { mkdirs() }
    private val body = "限时、压减，不排名，被强力整治。\n每一条单独看，都很有道理。书包确实重，睡眠确实少，竞争确实卷。\n> 可减负不是把负担消灭，而是把负担转移。\n学校少留的作业，需要有人在旁边看着补。学校不排的名次，家长会自己去比较、去焦虑。课外找时间补齐。\n- 第一条列表：「引号开头」的句子也要完整显示\n“双引号开头的一行，看看左边是否被裁。”（括号）《书名号》"
    private fun ink(b: Bitmap, x: Int, y: Int, bg: Int) = b.getPixel(x, y).let { Math.abs(Color.red(it) - Color.red(bg)) + Math.abs(Color.green(it) - Color.green(bg)) + Math.abs(Color.blue(it) - Color.blue(bg)) > 90 }

    @Test fun margins() {
        val pal = readPalettes.first { it.name == "抹茶" }
        val cases: List<Pair<Pair<String, FontFamily>, Float>> = listOf("sans" to FontFamily.Default, "serif" to Fonts.serif).flatMap { f -> listOf(15f, 18f, 22f).map { f to it } }
        var cur by androidx.compose.runtime.mutableStateOf(cases[0])
        rule.setContent {
            val f = cur.first; val size = cur.second
            Box(Modifier.fillMaxSize().background(pal.bg)) {
                ArticleView(Note(id = 1, createdAt = 0, title = "减负以后，负担去了哪里", body = body, tags = "教育"), emptyList(), size, pal, f.second, androidx.compose.ui.unit.Dp(24f), openVersions = {}, onTags = {})
            }
        }
        val report = StringBuilder()
        for (c in cases) {
            cur = c; rule.waitForIdle()
            val p = File(dir, "reader-${c.first.first}-${c.second.toInt()}.png")
            rule.onRoot().captureRoboImage(p.path)
            val b = android.graphics.BitmapFactory.decodeFile(p.path)
            val bg = b.getPixel(b.width / 2, 4)
            val y0 = (b.height * .35).toInt(); val y1 = (b.height * .95).toInt()
            val cols = (0 until b.width).filter { x -> (y0 until y1 step 2).any { ink(b, x, it, bg) } }
            val l = cols.first(); val r = b.width - 1 - cols.last()
            report.append("${c.first.first} ${c.second}sp 左=$l 右=$r\n")
            if (tag == "after") { assertTrue("左右不对称 $l/$r", Math.abs(l - r) <= 8); assertTrue("左边留白不足 $l", l >= 70) }
        }
        File(dir, "report.txt").writeText(report.toString()); println(report)
    }
}
