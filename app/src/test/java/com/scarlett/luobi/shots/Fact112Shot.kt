package com.scarlett.luobi.shots

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
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
class Fact112Shot {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val dir = System.getProperty("user.dir") + "/../../screenshots/v1.12/"

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(300)
        rule.mainClock.advanceTimeBy(800)
        // ModalBottomSheet 会出现第二个 root；取最后一个
        rule.onAllNodes(isRoot()).onLast().captureRoboImage("$dir$name.png")
    }

    @Test fun factCacheAndSheet() {
        val ctx = RuntimeEnvironment.getApplication()
        val report = FactReport(
            issues = listOf(
                FactIssue("1", "由王家卫执导", "导演", "高", "王家卫执导", "由陈可辛执导", "维基百科", note = "本片导演是陈可辛，不是王家卫。", selected = true, aiCorrection = "由陈可辛执导"),
                FactIssue("2", "梁朝伟主演", "主演", "中", "梁朝伟主演", "黎明主演", "维基百科", note = "本片男主是黎明；梁朝伟未出演。", selected = true, aiCorrection = "黎明主演"),
            ),
            skipped = emptyList(),
            searchNote = "检索：DeepSeek（推荐） · 校对模型 DeepSeek · deepseek-flash",
            queries = listOf("甜蜜蜜"),
            checkedAt = 1L,
        )
        val body = "《甜蜜蜜》是1996年的经典港片，由王家卫执导，张曼玉和梁朝伟主演。\n\n漂泊比爱情更动人。"
        runBlocking {
            val d = AppDb.get(ctx).notes()
            val n = Note(title = "甜蜜蜜：漂泊比爱情更动人", body = body, tags = "影评", templateName = "影评", model = "DeepSeek · deepseek-flash", factReportJson = FactCheck.encodeReport(report))
            val id = d.insert(n); d.update(n.copy(id = id, rootId = id))
        }
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        rule.onAllNodesWithText("案头", substring = false).onFirst().performClick()
        rule.mainClock.advanceTimeBy(600); rule.waitForIdle()
        rule.onAllNodesWithText("甜蜜蜜：漂泊比爱情更动人", substring = false).onFirst().performClick()
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        shot("360-fact-badge")
        rule.onAllNodesWithContentDescription("事实校对").onFirst().performClick()
        rule.mainClock.advanceTimeBy(900); rule.waitForIdle()
        shot("360-fact-sheet")
    }
}
