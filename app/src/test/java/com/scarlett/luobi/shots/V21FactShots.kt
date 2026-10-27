package com.scarlett.luobi.shots

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.*
import com.scarlett.luobi.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 2.1 事实校对新流程：列表（流式）→ 弹层逐条修 → 换个改法 → 采纳 → 风格把关 → 一键还原；空 / 出错 / 停止 / 长文 / 手动改 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w360dp-h780dp-xxhdpi")
class V21FactShots {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val dir = System.getProperty("user.dir") + "/../../screenshots/v2.1/"
    private val t0 = FactNet.transport; private val s0 = FactNet.sleep; private val g0 = FactNet.gapMs
    private val made = mutableListOf<Long>()
    private val ctx get() = RuntimeEnvironment.getApplication()
    @After fun r() { runBlocking { made.forEach { AppDb.get(ctx).notes().deleteId(it) } }; FactNet.transport = t0; FactNet.sleep = s0; FactNet.gapMs = g0; Yuanbao.resetSession(); FactPipeline.llmOverride = null }

    private fun shot(name: String) { rule.waitForIdle(); Thread.sleep(200); rule.mainClock.advanceTimeBy(900); rule.waitForIdle(); com.github.takahirom.roborazzi.captureScreenRoboImage("$dir$name.png") }
    private fun step(ms: Long = 700) { rule.mainClock.advanceTimeBy(ms); rule.waitForIdle() }

    private fun open(report: FactReport?, title: String = FactFixture.title, body: String = FactFixture.body, job: (Long) -> FactJob? = { null }): Long {
        val id = runBlocking {
            val d = AppDb.get(ctx).notes()
            val n = Note(title = title, body = body, tags = "随笔", model = "DeepSeek · deepseek-flash", category = "随笔",
                factReportJson = report?.let { FactCheck.encodeReport(it) }.orEmpty(), createdAt = System.currentTimeMillis() + 30L * 86_400_000L)
            val id = d.insert(n); d.update(n.copy(id = id, rootId = id))
            job(id)?.let { AppDb.get(ctx).factJobs().put(it) }; made += id; id
        }
        step(800)
        rule.onAllNodesWithText("案头", substring = false).onFirst().performClick(); step(600)
        rule.onAllNodesWithText(title, substring = false).onFirst().performClick(); step(900)
        return id
    }
    private fun openFact() { rule.onAllNodesWithContentDescription("事实校对", substring = true).onFirst().performClick(); step(900) }
    private fun note(id: Long) = runBlocking { AppDb.get(ctx).notes().get(id)!! }

    private fun fakeLlm() {
        FactPipeline.llmOverride = FactPipeline.Llm { sys, user, _ ->
            if (sys == FactFix.ALT_SYS) {
                val span = Regex("原句：(.*)").find(user)!!.groupValues[1]
                JSONObject().put("alts", JSONArray().put(span.replaceFirst("1920年代", "二十年代后期")).put("据传记记载，" + span)).toString()
            } else {
                val ps = Regex("\\[第 (\\d+) 段] (.*)").findAll(user).toList()
                val tips = JSONArray()
                ps.take(3).forEachIndexed { i, m ->
                    val p = m.groupValues[2]; val old = p.take(10)
                    tips.put(JSONObject().put("kind", FactFix.TIP_KINDS[i % 3]).put("para", m.groupValues[1].toInt())
                        .put("why", listOf("改了时间后，后文「民国那点开放」的论点少了支撑，收一句更稳", "改动处与上一段衔接略突兀，补一个过渡", "这一句的口吻比全文更像说明书，回到作者的语气")[i % 3])
                        .put("old", old).put("new", old.dropLast(1) + "，"))
                }
                JSONObject().put("tips", tips).toString()
            }
        }
    }

    private fun report(): FactReport { FactFixture.install(); return runBlocking { FactPipeline.run(FactFixture.title, FactFixture.body, FactFixture.env()) } }

    @Test fun flow() {
        fakeLlm()
        val rep = report()
        assertTrue(rep.claims.isNotEmpty())
        // 数字 / 引语先查
        assertEquals("引语", FactPipeline.kindOf(rep.claims.first()))
        val id = open(rep) { FactJob(it, FactFixture.title, FactWorker.DONE, rep.claims.size, rep.claims.size, "", seen = false) }
        openFact()
        rule.onNodeWithTag("factList").assertExists()
        shot("02-list-done")
        rule.onNodeWithTag("factNext").performClick(); step()
        rule.onNodeWithTag("factSheet").assertExists()
        shot("03-sheet-diff")
        rule.onNodeWithTag("factAlt").performClick(); step(1200)
        shot("04-sheet-alternatives")
        rule.onNodeWithTag("factAdopt").performClick(); step(1200)
        val n1 = note(id)
        assertNotEquals(FactFixture.body, n1.body)
        val r1 = FactCheck.decodeReport(n1.factReportJson)!!
        assertNotNull("第一次采纳前自动存了校对前版本", r1.snap); assertEquals(FactFixture.body, r1.snap!!.body)
        shot("05-sheet-next-with-snapshot")
        // 把剩下的都处理掉（采纳或忽略）
        repeat(6) { if (rule.onAllNodesWithTag("factSheet").fetchSemanticsNodes().isNotEmpty()) {
            val adopt = rule.onNodeWithTag("factAdopt")
            if (runCatching { adopt.assertIsEnabled() }.isSuccess) adopt.performClick() else rule.onNodeWithTag("factIgnore").performClick(); step(1000) } }
        shot("06-list-handled")
        rule.onNodeWithTag("factStyle").performClick(); step(1500)
        shot("07-style-pass")
        rule.onAllNodes(hasTestTag("tipAdopt_s1")).onFirst().performClick(); step(1200)
        shot("08-style-adopted")
        rule.onNodeWithText("全部完成").performClick(); step(900)
        rule.onNodeWithTag("factRestore").performClick(); step(700)
        shot("09-restore-confirm")
        rule.onNodeWithText("还原").performClick(); step(1200)
        assertEquals(FactFixture.body, note(id).body)
        assertNull(FactCheck.decodeReport(note(id).factReportJson)!!.snap)
        shot("10-restored")
    }

    @Test fun running() {
        val rep = report()
        // 前两条已出结果，其余在跑 / 排队
        val half = rep.claims.mapIndexed { i, c -> if (i < 2) c else c.copy(done = false, details = c.details.map { it.copy(verdict = "") }) }
        val st = FactPipeline.encodeState(FactPipeline.State(half, emptyList(), FactTriage(half.size, 9, 14)))
        open(null) { nid ->
            val req = OneTimeWorkRequestBuilder<FactWorker>().setInputData(workDataOf(FactWorker.K_NOTE to nid))
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true).build()).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(FactWorker.name(nid), ExistingWorkPolicy.KEEP, req)
            FactJob(nid, FactFixture.title, FactWorker.RUNNING, 2, half.size, st, seen = true)
        }
        openFact()
        rule.onAllNodesWithText("核查中…").onFirst().assertExists()
        rule.onNodeWithText("停止").assertExists()
        shot("01-list-running")
        rule.onNodeWithText("停止").performClick(); step()
        shot("11-stop-confirm")
    }

    @Test fun stoppedPartial() {
        val rep = report()
        val half = rep.claims.mapIndexed { i, c -> if (i < 2) c else c.copy(done = false) }
        val st = FactPipeline.encodeState(FactPipeline.State(half, emptyList(), FactTriage(half.size, 9, 14)))
        open(null) { nid -> FactJob(nid, FactFixture.title, FactWorker.FAILED, 2, half.size, st, error = FactWorker.CANCELLED, seen = true) }
        openFact()
        shot("12-stopped-partial")
    }

    @Test fun empty() {
        val r = FactReport(emptyList(), emptyList(), "", emptyList(), triage = FactTriage(0, 5, 12))
        open(r, "只有观点的短文", "我觉得春天比秋天更适合读书。风很软。")
        openFact()
        rule.onAllNodesWithText("没有需要核查的硬事实", substring = true).onFirst().assertExists()
        shot("13-empty")
    }

    @Test fun longTextAndManualEdit() {
        val longSpan = "据国家统计局2024年发布的数据，全国规模以上文化及相关产业企业实现营业收入141510亿元，比上年增长7.1%，其中新业态特征较为明显的16个行业小类实现营业收入59521亿元，增长10.5%，快于全部规模以上文化企业3.4个百分点"
        val body = "开头一段。\n\n$longSpan。\n\n结尾一段。"
        val c = FactClaim("c1", longSpan, "文化产业营收", listOf(FactDetail("c1.d1", "数字", "营收141510亿元", verdict = FactPipeline.PART, quote = "全国规模以上文化及相关产业企业实现营业收入141510亿元，比上年增长7.3%",
            url = "https://www.stats.gov.cn/sj/zxfb/202501/t20250126.html", sourceTitle = "国家统计局", reason = "增速是 7.3%，不是 7.1%", rewrite = longSpan.replace("7.1%", "7.3%"))), done = true)
        val u = FactClaim("c2", "结尾一段", "x", listOf(FactDetail("c2.d1", "事件", "x", verdict = FactPipeline.UNK, reason = "没有找到资料")), done = true)
        val r = FactPipeline.withClaims(FactReport(emptyList(), emptyList(), "", emptyList()), listOf(c, u)).copy(triage = FactTriage(2, 0, 1))
        open(r, "长句测试", body)
        openFact()
        shot("14-long-list")
        rule.onNodeWithTag("factRow_c1").performClick(); step()
        shot("15-long-sheet")
        rule.onNodeWithTag("factManual").performClick(); step(1500)
        rule.onNodeWithTag("editBody").assertExists()
        shot("16-manual-edit-selected")
    }

    @Test fun unverifiableSoftWording() {
        val u = FactClaim("c1", "红果短剧月活已突破3亿", "x", listOf(FactDetail("c1.d1", "数字", "月活3亿", verdict = FactPipeline.UNK, reason = "两轮检索都没有找到月活数据")), done = true)
        val r = FactPipeline.withClaims(FactReport(emptyList(), emptyList(), "", emptyList()), listOf(u)).copy(triage = FactTriage(1, 2, 3))
        open(r, "短剧观察", "红果短剧月活已突破3亿，成为行业第一。")
        openFact()
        rule.onNodeWithTag("factRow_c1").performClick(); step()
        rule.onAllNodesWithText("稳妥说法", substring = true).onFirst().assertExists()
        shot("17-unverifiable-soft")
    }
}
