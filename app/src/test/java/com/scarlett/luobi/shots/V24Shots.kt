package com.scarlett.luobi.shots

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.scarlett.luobi.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 2.4 技能包写作：5 个页面的截图 + 行为（两种输入顺序、续做横幅、确认页、管理页）。跑真实 App，不联网 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w393dp-h852dp-xxhdpi")
class V24Shots {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val dir = System.getProperty("user.dir") + "/../../screenshots/v2.4/"
    private val topic = "张雪峰：一个把高考焦虑做成产业的人"
    private fun idle(ms: Long = 400) { rule.mainClock.advanceTimeBy(ms); rule.waitForIdle() }
    private fun shot(n: String) { idle(); captureScreenRoboImage("$dir$n.png") }

    @Before fun clean() {
        java.io.File(dir).mkdirs()
        SkillFlow.clear(ctx)
        ctx.getSharedPreferences("draft", 0).edit().clear().apply()
    }

    private fun src(n: Int, via: String, t: String) = SkillSource(n, t, "https://example.com/$n", "示例站点", "2024-0$n-01", via, "摘要 $n")
    private val plan = SkillPlan(
        "张雪峰", "不写鸡汤励志，写他把升学焦虑产品化的路径：从考研名师到志愿填报生意，再到公众情绪里的「确定性」供给。", "备选：从一个人看中国家庭的升学焦虑",
        listOf("开篇：志愿填报直播间里的「救命感」", "成名：考研焦虑如何变成可售卖服务", "扩张：从课程到机构，生意边界在哪", "争议：毒舌、阶层叙事与被放大的焦虑", "落点：教育市场里，确定性为什么值钱", "参考资料与可核验事实清单"),
        "缺少机构财务数据", "约 4500 字", listOf("教育", "焦虑", "商业"), "社会观察")

    @Test fun s01_createEmpty() { idle(1000); rule.onNodeWithTag("manageSkills").assertExists(); shot("01-create-empty") }

    @Test fun s02_createSelected_bothOrders() {
        idle(1000)
        // 顺序 A：先输入，再选技能包
        rule.onNodeWithTag("start").assertIsNotEnabled()
        rule.onNodeWithTag("input").performTextInput(topic)
        rule.onNodeWithTag("start").assertIsNotEnabled()
        rule.onAllNodesWithText("财经非虚构深度写作", substring = true).onFirst().performScrollTo().performClick()
        idle()
        rule.onNodeWithTag("start").assertIsEnabled()
        shot("02-create-selected")
        // 顺序 B：先选技能包（取消 + 重选），清空输入再输入
        rule.onAllNodesWithText("财经非虚构深度写作", substring = true).onFirst().performClick(); idle()
        rule.onNodeWithTag("start").assertIsNotEnabled()
        rule.onAllNodesWithText("人物传记与时代特稿写作", substring = true).onFirst().performClick(); idle()
        rule.onNodeWithTag("start").assertIsEnabled()
    }

    @Test fun s03_searching() {
        idle(500)
        SkillFlow.set(ctx, SkillSession("plan", topic, "finance-nonfiction-writer", "财经非虚构深度写作", "1.4.0-luobi", "", stage = 2, line = "元宝正在搜：张雪峰 峰学蔚来 公司", yb = 7, ds = 0,
            notices = listOf("没填元宝搜索 Key，这次改用 DeepSeek 搜索。填好后会优先用元宝（设定 → 模型与密钥）。"), queries = listOf("张雪峰 简介", "张雪峰 争议")))
        idle(300)
        rule.onNodeWithTag("resumeBanner").assertExists().performClick()
        idle(300); rule.onNodeWithText("元宝检索中", substring = true).assertExists()
        shot("03-searching")
    }

    @Test fun s04_confirmOutline() {
        idle(500)
        SkillFlow.set(ctx, SkillSession("confirm", topic, "biographical-longform-feature", "人物传记与时代特稿写作", "1.4.0-luobi", "", plan = plan, yb = 9, ds = 3,
            sources = (1..9).map { src(it, "元宝", "资料 $it") } + (10..12).map { src(it, "DeepSeek", "资料 $it") }))
        idle(300)
        rule.onNodeWithTag("resumeBanner").assertExists().performClick()
        idle(300)
        rule.onNodeWithTag("confirmWrite").assertExists()
        rule.onNodeWithText("不写鸡汤励志", substring = true).assertExists()
        shot("04-confirm-outline")
    }

    @Test fun s05_skillsManage() {
        idle(1000)
        rule.onNodeWithTag("manageSkills").performClick(); idle(600)
        rule.onNodeWithText("财经非虚构深度写作", substring = true).assertExists()
        rule.onNodeWithText("人物传记与时代特稿写作", substring = true).assertExists()
        rule.onNodeWithTag("importRow").assertExists()
        shot("05-skills-manage")
    }

    @Test fun staleBusySessionIsReported() {
        idle(300)
        SkillFlow.set(ctx, SkillSession("plan", topic, "finance-nonfiction-writer", "财经非虚构深度写作", "1.4.0-luobi", ""))
        idle(200)
        rule.onNodeWithTag("resumeBanner").performClick()
        idle(2600)   // 没有后台任务 → 约 2 秒后判定被中断
        rule.onNodeWithText("这次被中断了", substring = true).assertExists()
    }
}
