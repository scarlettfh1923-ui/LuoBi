package com.scarlett.luobi.shots

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureScreenRoboImage
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.scarlett.luobi.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 1.16.1：标签行末尾的「编辑」与标签同一垂直中心 */
abstract class Tags1161Base(private val w: String) {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val dir = System.getProperty("user.dir") + "/../../screenshots/v1.16.1/"
    private fun step(ms: Long = 900) { rule.mainClock.advanceTimeBy(ms); rule.waitForIdle() }

    private fun open(title: String, tags: String, name: String) {
        val ctx = RuntimeEnvironment.getApplication()
        runBlocking { val d = AppDb.get(ctx).notes(); val n = Note(title = title, body = "正文第一段。\n\n正文第二段。", tags = tags, category = "文学", createdAt = System.currentTimeMillis() + 120_000)
            val id = d.insert(n); d.update(n.copy(id = id, rootId = id)) }
        step()
        rule.onAllNodesWithText("案头", substring = false).onFirst().performClick(); step()
        rule.onAllNodesWithText(title, substring = false).onFirst().performClick(); step(1500)
        val edit = rule.onAllNodesWithText("编辑", substring = false, useUnmergedTree = true).fetchSemanticsNodes().first { it.boundsInRoot.top > 100 }.boundsInRoot
        val tag = rule.onAllNodesWithText(tags.split(" ").last().let { "#$it" }, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
        val tag0 = rule.onAllNodesWithText(tags.split(" ").first().let { "#$it" }, useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
        val h = tag.height
        // 同字号同行高 → 文字框一样高；同一行时垂直中心一致；换行时也与上一行标签同样的中心偏移
        assertEquals("行高一致", h, edit.height, 1f)
        val sameLine = kotlin.math.abs(edit.center.y - tag.center.y) < h
        if (sameLine) assertEquals("同一行垂直居中", tag.center.y, edit.center.y, 1.5f)
        else assertTrue("换行后在下一行、左对齐到行首", edit.top > tag.bottom && kotlin.math.abs(edit.left - tag0.left) < 30f)
        captureScreenRoboImage("$dir$w-$name.png")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; step()
    }

    @Test fun four() = open("四个标签的文章", "经典文学 魔幻现实主义 拉美 孤独", "tags4")
    @Test fun five() = open("五个标签的文章", "经典文学 魔幻现实主义 拉丁美洲文学 孤独 家族史诗", "tags5")
}

@RunWith(AndroidJUnit4::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w360dp-h780dp-xxhdpi")
class Tags1161Shot360 : Tags1161Base("360")

@RunWith(AndroidJUnit4::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w412dp-h915dp-xxhdpi")
class Tags1161Shot412 : Tags1161Base("412")
