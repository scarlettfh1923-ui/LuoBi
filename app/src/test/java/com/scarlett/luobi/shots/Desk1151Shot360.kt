package com.scarlett.luobi.shots

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.scarlett.luobi.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 1.12：阅读页事实校对角标 + 结果弹层（带缓存） */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w360dp-h780dp-xxhdpi")
class Desk1151Shot360 {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val dir = System.getProperty("user.dir") + "/../../screenshots/v1.15.1/"

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(300)
        rule.mainClock.advanceTimeBy(800)
        // ModalBottomSheet 会出现第二个 root；取最后一个
        captureScreenRoboImage("$dir$name.png")
    }

    @Test fun desk() {
        val ctx = RuntimeEnvironment.getApplication()
        val now = System.currentTimeMillis(); val day = 86_400_000L
        val cats = listOf("电影", "社会学与现代性", "文学", "学习方法与知识管理", "", "历史", "心理与关系", "写作与表达")
        runBlocking {
            val d = AppDb.get(ctx).notes()
            cats.forEachIndexed { i, c -> val n = Note(title = "第${i + 1}篇：为什么越忙的人，越需要留白和独处的时间", body = "正文。".repeat(300 + i * 50), tags = "a b", templateName = "通用", model = "m", category = c, createdAt = now - i * 4 * day - 3_600_000L)
                val id = d.insert(n); d.update(n.copy(id = id, rootId = id)) }
        }
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        rule.onAllNodesWithText("案头", substring = false).onFirst().performClick()
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        shot("360-desk-top")
        rule.onAllNodesWithText("全部分类").onFirst().performClick()
        rule.mainClock.advanceTimeBy(900); rule.waitForIdle()
        shot("360-desk-menu")
        rule.onAllNodesWithText("全部", substring = false).onLast().performClick()
        rule.mainClock.advanceTimeBy(900); rule.waitForIdle()
        repeat(6) { rule.onAllNodesWithText("第1篇", substring = true).onFirst().let { } ; rule.onRoot().performTouchInput { swipeUp() }; rule.mainClock.advanceTimeBy(1500); rule.waitForIdle() }
        shot("360-desk-bottom")
    }
}
