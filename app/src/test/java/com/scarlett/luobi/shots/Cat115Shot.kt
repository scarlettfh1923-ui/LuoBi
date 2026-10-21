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
class Cat115Shot {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val dir = System.getProperty("user.dir") + "/../../screenshots/v1.15/"

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(300)
        rule.mainClock.advanceTimeBy(800)
        // ModalBottomSheet 会出现第二个 root；取最后一个
        captureScreenRoboImage("$dir$name.png")
    }

    @Test fun deskAndReader() {
        val ctx = RuntimeEnvironment.getApplication()
        val now = System.currentTimeMillis(); val day = 86_400_000L
        val seed = listOf(
            Triple("甜蜜蜜：漂泊比爱情更动人，那些在香港街头错过的人", "电影", now - 3_600_000L),
            Triple("为什么现代人越来越孤独", "社会学与现代性", now - 2 * 3_600_000L),
            Triple("读《局外人》：荒诞与诚实", "文学", now - day),
            Triple("卡片笔记法的三个误区", "学习方法与知识管理", now - 20 * day),
            Triple("没有分类的旧文章", "", now - 45 * day),
        )
        runBlocking {
            val d = AppDb.get(ctx).notes()
            seed.forEach { (t, c, at) -> val n = Note(title = t, body = "正文。".repeat(400), tags = "影评 爱情观 香港 九十年代", templateName = "豆瓣影评", model = "DeepSeek · deepseek-flash", category = c, categoryAi = c, createdAt = at)
                val id = d.insert(n); d.update(n.copy(id = id, rootId = id)) }
        }
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        rule.onAllNodesWithText("案头", substring = false).onFirst().performClick()
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        shot("360-desk")
        rule.onAllNodesWithText("全部分类").onFirst().performClick()
        rule.mainClock.advanceTimeBy(900); rule.waitForIdle()
        shot("360-desk-menu")
        rule.onAllNodesWithText("电影", substring = false).onLast().performClick()
        rule.mainClock.advanceTimeBy(900); rule.waitForIdle()
        shot("360-desk-filtered")
        rule.onAllNodesWithText(seed[0].first, substring = false).onFirst().performClick()
        rule.mainClock.advanceTimeBy(900); rule.waitForIdle()
        shot("360-reader-header")
        rule.onAllNodesWithText("电影", substring = false).onFirst().performClick()
        rule.mainClock.advanceTimeBy(900); rule.waitForIdle()
        shot("360-category-sheet")
    }
}
