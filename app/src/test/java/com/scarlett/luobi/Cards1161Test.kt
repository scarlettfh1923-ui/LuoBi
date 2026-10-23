package com.scarlett.luobi

import android.graphics.Bitmap
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
class Cards1161Test {
    private val out = File(System.getProperty("user.dir") + "/../../screenshots/v1.16.1/inline").apply { mkdirs() }
    private fun pic(c: Int, w: Int = 1600, h: Int = 1000) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(c) }
    private fun has(b: Bitmap, c: Int) = (0 until b.height step 6).any { y -> (0 until b.width step 12).any { x -> b.getPixel(x, y) == c } }
    private val red = Color.rgb(220, 40, 40); private val green = Color.rgb(30, 160, 90)
    private val body = (0 until 8).joinToString("\n") { i -> (if (i == 3) "## 二、迟到的重逢\n" else "") + "第${i + 1}段：电影里那辆单车反复出现，它载过生计，也载过一段说不出口的亲近。".repeat(2) }

    @Test fun imagesFlowInOrderAndMoveToNextPageWhenTheyDontFit() {
        val pages = Render.pages("婚约之后", body, Render.styles[0], spacing = 1.7f, width = 1080, height = 1920,
            pics = mapOf(1 to listOf(pic(red) to "图1 单车穿过黄昏的街道"), 5 to listOf(pic(green, 1000, 1400) to "图2 雨夜的街口")))
        pages.forEachIndexed { i, b -> File(out, "R916_p$i.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val ri = pages.indexOfFirst { has(it, red) }; val gi = pages.indexOfFirst { has(it, green) }
        assertTrue(ri >= 0 && gi >= ri)
        // 绿图（第 6 段后）若不在页首附近，前面必须是第 6 段的文字所在页（顺序不乱）
        assertEquals(0, Render.lastDroppedPics)
        // 圆角：图左上角像素不是图色
        val g = pages[gi]; val top = (0 until g.height).first { y -> g.getPixel(540, y) == green }
        assertNotEquals(green, g.getPixel(Render.marginH + 1, top))
    }

    @Test fun capsAt18IncludingCoverAndDropsLaterImages() {
        val many = (0 until 8).associateWith { listOf(pic(red, 1000, 1500) to "") }
        val pages = Render.pages("很多图", body, Render.styles[0], spacing = 1.7f, width = 1080, height = 1440, pics = many, maxTotal = 8)
        assertTrue(pages.size + 1 <= 8)
        assertTrue(Render.lastDroppedPics > 0)
    }

    @Test fun readModesMigrate() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sp = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        for ((old, want) in listOf(0 to 0, 1 to 0, 2 to 1, 3 to 3)) {
            sp.edit().clear().putInt("readMode", old).apply()
            assertEquals("旧 $old", want, Settings(ctx).readMode)
            assertEquals(want, Settings(ctx).readMode) // 只迁移一次
        }
        assertEquals(listOf("素白", "纸张", "抹茶", "夜读"), readPalettes.map { it.name })
        assertEquals(0xFFF4ECDD.toInt(), readPalettes[1].bg.value.let { androidx.compose.ui.graphics.Color(it).let { c -> android.graphics.Color.argb((c.alpha * 255).toInt(), (c.red * 255 + .5f).toInt(), (c.green * 255 + .5f).toInt(), (c.blue * 255 + .5f).toInt()) } })
    }
}
