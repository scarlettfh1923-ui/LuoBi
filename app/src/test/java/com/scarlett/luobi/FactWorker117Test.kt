package com.scarlett.luobi

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.scarlett.luobi.shots.TestApp::class)
class FactWorker117Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val t0 = FactNet.transport; private val s0 = FactNet.sleep; private val g0 = FactNet.gapMs
    @Before fun s() { FactFixture.install(); FactPipeline.envOverride = { FactFixture.env() } }
    @After fun r() { FactPipeline.envOverride = null; FactNet.transport = t0; FactNet.sleep = s0; FactNet.gapMs = g0; Yuanbao.resetSession() }

    private fun note(): Long = runBlocking {
        val d = AppDb.get(ctx).notes(); val n = Note(title = FactFixture.title, body = FactFixture.body, tags = "", createdAt = 1L)
        val id = d.insert(n); d.update(n.copy(id = id, rootId = id)); id
    }
    private fun work(id: Long) = runBlocking {
        TestListenableWorkerBuilder<FactWorker>(ctx).setInputData(workDataOf(FactWorker.K_NOTE to id, FactWorker.K_TITLE to FactFixture.title)).build().doWork()
    }

    @Test fun workerSavesReportMarksUnseenAndNotifies() {
        val id = note()
        val res = work(id)
        assertTrue(res is ListenableWorker.Result.Success)
        val db = AppDb.get(ctx)
        runBlocking {
            val rep = FactCheck.decodeReport(db.notes().get(id)!!.factReportJson)!!
            assertTrue(rep.claims.isNotEmpty())
            val job = db.factJobs().get(id)!!
            assertEquals(FactWorker.DONE, job.status); assertFalse(job.seen)
            assertEquals(FactBadge.S.DOT, FactBadge.state(job, true, false))
            val bad = FactPipeline.count(rep, FactPipeline.BAD); val part = FactPipeline.count(rep, FactPipeline.PART)
            val nm = shadowOf(ctx.getSystemService(NotificationManager::class.java))
            val n = nm.getNotification(FactWorker.nidDone(id))!!
            assertEquals(FactWorker.doneText(FactFixture.title, bad, part), n.extras.getString("android.title"))
            assertTrue(n.extras.getString("android.title")!!.startsWith("《${FactFixture.title}》校对完成 · 有出入 "))
            assertNotNull(n.contentIntent)
            db.factJobs().markSeen(id)
            assertEquals(FactBadge.S.SEEN, FactBadge.state(db.factJobs().get(id), true, false))
        }
    }

    @Test fun workerResumesFromSavedState() {
        val id = note()
        val states = mutableListOf<FactPipeline.State>()
        runBlocking { FactPipeline.run(FactFixture.title, FactFixture.body, FactFixture.env(), save = { states += it }) }
        val partial = states.first { s -> s.claims.count { it.done } == 1 }
        runBlocking { AppDb.get(ctx).factJobs().put(FactJob(id, FactFixture.title, FactWorker.RUNNING, 1, partial.claims.size, FactPipeline.encodeState(partial))) }
        FactFixture.reset()
        assertTrue(work(id) is ListenableWorker.Result.Success)
        assertEquals("恢复时不再重新拆分", 0, FactFixture.extractCalls)
    }

    @Test fun badgeAndTexts() {
        val now = 1_000_000_000L
        val run = FactJob(1, "t", FactWorker.RUNNING, updatedAt = now)
        assertEquals(FactBadge.S.RUNNING, FactBadge.state(run, false, true, now))
        assertEquals(FactBadge.S.RUNNING, FactBadge.state(run, false, null, now + 60_000))
        assertEquals(FactBadge.S.NONE, FactBadge.state(run, false, null, now + 20 * 60_000))
        assertEquals(FactBadge.S.SEEN, FactBadge.state(null, true, null, now))
        assertEquals("正在校对《丁玲》· 3/7", FactWorker.progressText("丁玲", 3, 7))
        assertEquals("《丁玲》校对完成 · 有出入 1 · 部分对 2", FactWorker.doneText("丁玲", 1, 2))
    }

    @Test fun failureMarksFailedAndNotifies() {
        val id = note()
        FactPipeline.envOverride = { FactPipeline.Env(FactPipeline.Llm { _, _, _ -> throw java.io.IOException("断网") }, emptyList(), { "" }, "x") }
        assertTrue(work(id) is ListenableWorker.Result.Failure)
        runBlocking { assertEquals(FactWorker.FAILED, AppDb.get(ctx).factJobs().get(id)!!.status) }
    }
}
