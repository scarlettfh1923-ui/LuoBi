package com.scarlett.luobi.shots

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.scarlett.luobi.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 2.3 案头方案 B：一行一篇 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w360dp-h780dp-xxhdpi")
class Desk23Shots {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val dir = System.getProperty("user.dir") + "/../../screenshots/v2.3-desk/"
    private fun idle() { rule.mainClock.advanceTimeBy(1200); rule.waitForIdle() }
    private fun shot(n: String) { idle(); Thread.sleep(200); captureScreenRoboImage("$dir$n.png") }
    private val titles = listOf("税务追缴社保：一笔二十年旧账的算法", "结婚登记跌破七百万：这笔账，年轻人算得比谁都清楚", "你按揭三十年的房子，是给一个AI时代的自己准备的吗",
        "还在给十八岁的孩子攒首付，你算的是哪一笔账", "《晚潮》：一部关于“没走成”的电影", "个税汇算背后：谁在多交，谁在少交（以及为什么）", "“内卷”这个词，为什么解释不了你的疲惫",
        "1978年的一张粮票", "县城婚礼的彩礼清单", "重看《漫长的季节》：那列火车开往哪里", "地方债的另一面", "福柯的“规训”与打卡")
    private val cats = listOf("经济与制度", "社会观察", "社会观察", "社会观察", "影视评论", "经济与制度", "思想人文", "历史非虚构", "社会观察", "影视评论", "经济与制度", "思想人文")
    private fun clear() = runBlocking { val d = AppDb.get(RuntimeEnvironment.getApplication()).notes(); d.latest().first().forEach { d.deleteId(it.id) } }
    private fun seed(n: Int) = runBlocking {
        clear(); val d = AppDb.get(RuntimeEnvironment.getApplication()).notes()
        val now = System.currentTimeMillis(); val day = 86_400_000L
        repeat(n) { i -> val note = Note(title = titles[i % 12] + if (i >= 12) "（续 ${i - 11}）" else "", body = "正文。".repeat(200), tags = "", templateName = "通用",
            category = cats[i % 12], favorite = i % 5 == 1, createdAt = now - i * 2 * day - 3_600_000L)
            val id = d.insert(note); d.update(note.copy(id = id, rootId = id)) }
    }
    private fun desk() { idle(); rule.onAllNodesWithText("案头").onFirst().performClick(); idle() }

    @Test fun list() {
        seed(24); desk(); shot("b-1-list")
        rule.onRoot().performTouchInput { swipeUp() }; shot("b-2-list-scrolled")
    }
    @Test fun empty() { clear(); desk(); shot("b-3-empty") }
    @Test fun search() {
        seed(24); desk()
        rule.onNodeWithText("搜索").performClick(); rule.onAllNodes(hasSetTextAction()).onFirst().performTextInput("账"); shot("b-4-search")
    }
    @Test fun manage() {
        seed(24); desk()
        rule.onNodeWithText("管理").performClick(); idle()
        rule.onAllNodesWithText("县城婚礼", substring = true).onFirst().performClick()
        rule.onAllNodesWithText("1978", substring = true).onFirst().performClick(); shot("b-5-manage")
        rule.onNodeWithText("删除 (2)").performClick(); idle(); rule.onAllNodesWithText("删除").onLast().performClick(); idle()
        Thread.sleep(400); idle()
        assertEquals(22, runBlocking { AppDb.get(RuntimeEnvironment.getApplication()).notes().latest().first().size })
    }
    @Test fun longPressFav() {
        seed(3); desk()
        rule.onAllNodesWithText("税务追缴", substring = true).onFirst().performTouchInput { longClick() }; shot("b-6-longpress")
        rule.onNodeWithText("收藏").performClick(); idle(); Thread.sleep(300); idle()
        assertEquals(true, runBlocking { AppDb.get(RuntimeEnvironment.getApplication()).notes().latest().first().first { it.title.startsWith("税务") }.favorite })
        shot("b-7-fav")
    }
}
