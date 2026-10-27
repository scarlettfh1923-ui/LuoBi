package com.scarlett.luobi.shots

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.*
import com.scarlett.luobi.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 1.17：丁玲文章的校对结果弹层、阅读页红点、后台进行中状态 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w360dp-h780dp-xxhdpi")
class Fact117Shot {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val dir = System.getProperty("user.dir") + "/../../screenshots/v1.17.0/"
    private val t0 = FactNet.transport; private val s0 = FactNet.sleep; private val g0 = FactNet.gapMs
    private val made = mutableListOf<Long>()
    @After fun r() { runBlocking { made.forEach { AppDb.get(RuntimeEnvironment.getApplication()).notes().deleteId(it) } }; FactNet.transport = t0; FactNet.sleep = s0; FactNet.gapMs = g0; Yuanbao.resetSession() }

    private fun shot(name: String) { rule.waitForIdle(); Thread.sleep(300); rule.mainClock.advanceTimeBy(800); com.github.takahirom.roborazzi.captureScreenRoboImage("$dir$name.png") }

    private fun open(report: FactReport?, job: (Long) -> FactJob?): Long {
        val ctx = RuntimeEnvironment.getApplication()
        val id = runBlocking {
            val d = AppDb.get(ctx).notes()
            val n = Note(title = FactFixture.title, body = FactFixture.body, tags = "随笔", model = "DeepSeek · deepseek-flash", category = "随笔",
                factReportJson = report?.let { FactCheck.encodeReport(it) }.orEmpty(), createdAt = System.currentTimeMillis() + 3_600_000L)
            val id = d.insert(n); d.update(n.copy(id = id, rootId = id))
            job(id)?.let { AppDb.get(ctx).factJobs().put(it) }; made += id; id
        }
        rule.mainClock.advanceTimeBy(800); rule.waitForIdle()
        rule.onAllNodesWithText("案头", substring = false).onFirst().performClick()
        rule.mainClock.advanceTimeBy(600); rule.waitForIdle()
        rule.onAllNodesWithText(FactFixture.title, substring = false).onFirst().performClick()
        rule.mainClock.advanceTimeBy(900); rule.waitForIdle()
        return id
    }

    @Test fun dotAndSheet() {
        FactFixture.install()
        val rep = runBlocking { FactPipeline.run(FactFixture.title, FactFixture.body, FactFixture.env()) }
        open(rep) { FactJob(it, FactFixture.title, FactWorker.DONE, rep.claims.size, rep.claims.size, "", seen = false) }
        shot("360-reader-dot")
        rule.onAllNodesWithContentDescription("事实校对").onFirst().performClick()
        rule.mainClock.advanceTimeBy(900); rule.waitForIdle()
        rule.mainClock.advanceTimeBy(600); rule.waitForIdle()
        shot("360-sheet-top")
        // 2.1：新的校对页：每条说法一行
        rule.onNodeWithTag("factList").assertExists()
        rule.onAllNodesWithText("社会越复杂", substring = true).onFirst().assertExists()
    }

    @Test fun running() {
        val ctx = RuntimeEnvironment.getApplication()
        open(null) { nid ->
            // 用一个暂时满足不了的条件挂着同名任务 → WorkManager 里是「排队/进行中」
            val req = OneTimeWorkRequestBuilder<FactWorker>().setInputData(workDataOf(FactWorker.K_NOTE to nid))
                .setConstraints(Constraints.Builder().setRequiresCharging(true).setRequiresDeviceIdle(true).build()).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(FactWorker.name(nid), ExistingWorkPolicy.KEEP, req)
            FactJob(nid, FactFixture.title, FactWorker.RUNNING, 3, 7, "", seen = true)
        }
        shot("360-reader-running")
        rule.onAllNodesWithContentDescription("事实校对", substring = true).onFirst().performClick()
        rule.mainClock.advanceTimeBy(300); rule.waitForIdle()
        shot("360-reader-running-snack")
    }
}
