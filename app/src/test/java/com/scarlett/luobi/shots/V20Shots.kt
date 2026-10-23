package com.scarlett.luobi.shots

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.scarlett.luobi.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 2.0：分享页 A、5 种封面、3 种正文版式 × 2 比例，并做边距检查 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w360dp-h780dp-xxhdpi")
class V20Shots {
    @get:Rule val rule = createComposeRule()
    private val dir = File(System.getProperty("user.dir") + "/../../screenshots/v2.0/").apply { mkdirs() }
    private val title = "合肥的赌局，你学不会"
    private val tags = listOf("城市", "产业", "长文")
    private val body = "那一年，合肥把全部家当押在了一块屏幕上，所有人都觉得它疯了。\n" +
        (1..4).joinToString("\n") { i -> "## 第${i}步：押注\n" + "地方政府拿出真金白银，和企业一起承担风险。投资不是赌博，而是一套可以复盘的方法，京东方、蔚来都是这样落地的。".repeat(3) + "\n" + "很多城市想学，却学不会。差别不在胆子，而在耐心和专业团队。".repeat(2) }
    private fun save(b: Bitmap, name: String) = File(dir, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }

    @Test fun coversAndBodyLayouts() {
        CoverKind.entries.forEach { k -> save(Covers.render(k, title, tags, body, 1080, 1440), "cover-${k.ordinal + 1}-${k.name}.png") }
        val report = StringBuilder()
        for (r in CardRatio.entries) for (k in BodyKind.entries) {
            val pages = Render.pages(title, body, Render.styles[0], width = r.w, height = r.h, kind = k)
            pages.take(2).forEachIndexed { i, b -> save(b, "body-${k.name}-${r.name}-p${i + 1}.png") }
            // 边距检查：正文字迹左边缘（x≥80，避开小标题色条）与第一行顶部
            val b = pages[0]; val bg = b.getPixel(5, 5)
            fun ink(c: Int) = kotlin.math.abs(Color.red(c) - Color.red(bg)) + kotlin.math.abs(Color.green(c) - Color.green(bg)) + kotlin.math.abs(Color.blue(c) - Color.blue(bg)) > 90
            val left = (40 until r.w / 2).first { x -> (200 until r.h - 200 step 3).any { y -> ink(b.getPixel(x, y)) } && x >= 70 }
            val top = (0 until r.h / 2).first { y -> (80 until r.w - 80 step 3).any { x -> ink(b.getPixel(x, y)) } }
            val right = (r.w - 1 downTo r.w / 2).first { x -> (200 until r.h - 200 step 3).any { y -> ink(b.getPixel(x, y)) } }
            report.append("${k.name} ${r.label}：左 $left / 右 ${r.w - 1 - right} / 顶 $top / 每页字数 ${Render.lastChars}\n")
            assertTrue("左边距 $left", left in 70..80); assertTrue("右边距 ${r.w - 1 - right}", r.w - 1 - right in 66..110); assertTrue("顶 $top", top in 90..130)
        }
        File(dir, "margins.txt").writeText(report.toString())
    }

    @Test fun shareScreen() {
        val imgs = listOf(Covers.render(CoverKind.BIG, title, tags, body, 1080, 1440)) + Render.pages(title, body, Render.styles[0], width = 1080, height = 1440).take(3)
        val thumbs = Covers.thumbs(title, tags, body)
        rule.setContent { AppTheme {
            var cover by remember { mutableIntStateOf(0) }; var bk by remember { mutableIntStateOf(0) }
            Gallery(imgs, thumbs, cover, { cover = it }, bk, { bk = it }, 1.7f, {}, 0, {}, true, false, false, readPalettes[0], 24.dp, onAiBg = {}, clearBg = {}, save = {})
        } }
        rule.mainClock.advanceTimeBy(1500); rule.waitForIdle()
        rule.onNodeWithTag("shareA").assertExists()
        rule.onRoot().captureRoboImage(File(dir, "share-1-cover-big.png").path)
        rule.onNodeWithTag("cover_2").performClick(); rule.onNodeWithTag("body_1").performClick()
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        rule.onRoot().captureRoboImage(File(dir, "share-2-selected.png").path)
    }
}
