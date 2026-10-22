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
        for (sp in listOf(1.0f, 1.15f, 1.3f, 1.5f, 1.7f, 2.0f)) {
            val (above, below) = Render.visualHeadGaps(46f, sp)
            assertTrue("spacing $sp above=$above below=$below", above >= below * 1.8f)
        }
        val body = (1..3).joinToString("\n") { i -> "## 第${i}个小标题\n" + "这是正文段落，用来检查小标题和段落之间的距离是否像阅读页那样上大下小。".repeat(3) }
        val out = File(System.getProperty("user.dir") + "/../../screenshots/v1.16.0/cards").apply { mkdirs() }
        for (r in CardRatio.entries) for (sp in listOf(1.0f, 1.7f)) {
            val pages = Render.pages("小标题间距", body, Render.styles[0], spacing = sp, width = r.w, height = r.h)
            assertTrue(pages.isNotEmpty())
            pages.forEachIndexed { i, b -> File(out, "${r.name}_sp${sp}_$i.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
    }
}
