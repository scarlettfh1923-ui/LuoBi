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
class Fact1151Shot {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val dir = System.getProperty("user.dir") + "/../../screenshots/v1.15.1/"

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(300)
        rule.mainClock.advanceTimeBy(800)
        // ModalBottomSheet 会出现第二个 root；取最后一个
        com.github.takahirom.roborazzi.captureScreenRoboImage("$dir$name.png")
    }

    @Test fun factDotSheetSettings() {
        val ctx = RuntimeEnvironment.getApplication()
        val wiki = FactLink("甜蜜蜜 (1996年电影) - 维基百科", "https://zh.wikipedia.org/wiki/甜蜜蜜", "维基、DeepSeek")
        val report = FactReport(
            issues = listOf(
                FactIssue("1", "由王家卫执导", "导演", "高", "王家卫执导", "由陈可辛执导", "", note = "本片导演是陈可辛。", aiCorrection = "由陈可辛执导",
                    verdict = "有出入", quote = "《甜蜜蜜》是一部1996年由陈可辛执导的香港爱情电影", links = listOf(wiki), corroboration = 2),
                FactIssue("2", "1996年上映", "年代", "低", "1996年上映", "1996年11月上映", "", note = "可写得更精确。", aiCorrection = "1996年11月上映",
                    verdict = "有出入", links = listOf(FactLink("甜蜜蜜 - 豆瓣电影", "https://movie.douban.com/subject/1305164/", "Kimi")), corroboration = 1),
                FactIssue("3", "两人最后在纽约街头重逢", "情节", "低", "纽约重逢", "", "", note = "资料里没有找到这一情节的描述。",
                    selected = false, verdict = "无法核实", corroboration = 0),
            ),
            skipped = emptyList(), searchNote = "检索：DeepSeek 搜索、维基百科（Kimi未填写 Key） · 校对模型 DeepSeek · deepseek-flash", queries = listOf("甜蜜蜜"), checkedAt = 1L)
        val body = "《甜蜜蜜》1996年上映，由王家卫执导，张曼玉和黎明主演。\n\n两人最后在纽约街头重逢。漂泊比爱情更动人。"
        runBlocking {
            val d = AppDb.get(ctx).notes()
            val n = Note(title = "甜蜜蜜：漂泊比爱情更动人", body = body, tags = "影评", templateName = "影评", model = "DeepSeek · deepseek-flash", category = "电影", factReportJson = FactCheck.encodeReport(report))
            val id = d.insert(n); d.update(n.copy(id = id, rootId = id))
        }
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        rule.onAllNodesWithText("案头", substring = false).onFirst().performClick()
        rule.mainClock.advanceTimeBy(600); rule.waitForIdle()
        rule.onAllNodesWithText("甜蜜蜜：漂泊比爱情更动人", substring = false).onFirst().performClick()
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        shot("360-reader-dot")
        rule.onAllNodesWithContentDescription("事实校对").onFirst().performClick()
        rule.mainClock.advanceTimeBy(900); rule.waitForIdle()
        shot("360-sheet-low-checked")
        rule.onAllNodes(hasScrollAction()).onLast().performScrollToNode(hasText("没有建议改正", substring = true))
        rule.mainClock.advanceTimeBy(600); rule.waitForIdle()
        shot("360-sheet-unverifiable")
    }
    @Test fun settingsSearchSection() {
        Settings(RuntimeEnvironment.getApplication()).factSearch = "deepseek,kimi"
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        rule.onAllNodesWithText("设定", substring = false).onFirst().performClick()
        rule.mainClock.advanceTimeBy(600); rule.waitForIdle()
        rule.onAllNodesWithText("事实校对", substring = false).onFirst().performClick()
        rule.mainClock.advanceTimeBy(900); rule.waitForIdle()
        shot("360-settings-search")
    }

    @Test fun relayForm() {
        val s = Settings(RuntimeEnvironment.getApplication())
        s.customs = listOf(CustomModel("r1", "我的中转站", "https://api.relay.example.com", "gpt-5.5")); s.setCustomKey("r1", "sk-demo-not-real")
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        rule.onAllNodesWithText("设定", substring = false).onFirst().performClick()
        rule.mainClock.advanceTimeBy(600); rule.waitForIdle()
        rule.onAllNodesWithText("模型与密钥", substring = false).onFirst().performClick()
        rule.mainClock.advanceTimeBy(900); rule.waitForIdle()
        rule.onAllNodes(hasScrollAction()).onLast().performScrollToNode(hasText("模型名"))
        rule.mainClock.advanceTimeBy(600); rule.waitForIdle()
        shot("360-relay-form")
    }
    @Test fun editPageKeyboard() {
        val ctx = RuntimeEnvironment.getApplication()
        runBlocking { val d = AppDb.get(ctx).notes(); val n = Note(title = "编辑页测试", body = "第一段正文。\n\n第二段正文。", tags = "a b", category = "电影")
            val id = d.insert(n); d.update(n.copy(id = id, rootId = id)) }
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        rule.onAllNodesWithText("案头", substring = false).onFirst().performClick()
        rule.mainClock.advanceTimeBy(600); rule.waitForIdle()
        rule.onAllNodesWithText("编辑页测试", substring = false).onFirst().performClick()
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        rule.onAllNodesWithContentDescription("编辑", substring = true).onFirst().performClick()
        rule.mainClock.advanceTimeBy(900); rule.waitForIdle()
        // 进入时没有任何输入框获得焦点
        rule.onAllNodes(isFocused()).assertCountEquals(0)
        shot("360-edit-enter")
        rule.onAllNodes(hasSetTextAction()).onLast().performClick()
        rule.mainClock.advanceTimeBy(600); rule.waitForIdle()
        rule.onAllNodes(isFocused()).assertCountEquals(1)
        shot("360-edit-tapped")
        rule.onAllNodesWithText("编辑文章").onFirst().performClick() // 点空白
        rule.mainClock.advanceTimeBy(600); rule.waitForIdle()
        rule.onAllNodes(isFocused()).assertCountEquals(0)
    }
}
