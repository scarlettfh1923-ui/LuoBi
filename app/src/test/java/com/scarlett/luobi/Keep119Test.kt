package com.scarlett.luobi

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = com.scarlett.luobi.shots.TestApp::class)
class Keep119Test {
    /** 小标题不落在页底；留在本页时后面至少跟 2 行正文 */
    @Test fun headingKeepsWithTwoBodyLines() {
        assertEquals(72, Render.marginH); assertNull(Render.marginV); assertEquals(0f, Render.justifyCap); assertTrue(Render.tightRhythm)
        var checked = 0
        for (n in 0 until 60 step 3) {
            val body = "开头一段" + "字".repeat(n * 7) + "。\n\n" + (1..5).joinToString("\n\n") { "## 小标题$it\n\n" + "这是一段正文，用来测试分页时小标题与后文的关系。".repeat(6) }
            for (h in listOf(1440, 1920)) {
                Render.pages("t", body, Render.styles[0], width = 1080, height = h)
                Render.lastShape.forEachIndexed { pi, pg ->
                    assertFalse("第${pi + 1}页以小标题结尾 n=$n", pg.last().first && pi < Render.lastShape.size - 1)
                    pg.forEachIndexed { i, (head, _) -> if (head && i + 1 < pg.size && i + 2 == pg.size && pi < Render.lastShape.size - 1) {
                        assertTrue("小标题后只有 ${pg[i + 1].second} 行 n=$n", pg[i + 1].second >= 2); checked++ } }
                }
            }
        }
        assertTrue(checked > 0)
    }
}
