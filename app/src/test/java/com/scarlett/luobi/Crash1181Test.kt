package com.scarlett.luobi

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class AsyncTestApp : Application() {
    override fun onCreate() { super.onCreate(); Secure.overrideForTest(getSharedPreferences("t", 0)); InspireSeed.seed(this)
        // 模拟她手机上 1.18.0 留下的状态：进度卡已写好、guard 未记录版本
        getSharedPreferences("gen", 0).edit().putString("card", "{\"id\":-5,\"t\":null}").commit()
        runCatching { WorkManager.initialize(this, Configuration.Builder().setExecutor(Executors.newFixedThreadPool(2)).build()) } }
}

/** 1.18.1：生成闪退 / 闪退循环回归（API 34 与 35） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35], application = AsyncTestApp::class)
class Crash1181Test {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun idle(n: Int = 10) = repeat(n) { Thread.sleep(80); rule.mainClock.advanceTimeBy(200); rule.waitForIdle() }

    @Test fun manifestServiceTypeAndPermission() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val si = ctx.packageManager.getServiceInfo(ComponentName(ctx, "androidx.work.impl.foreground.SystemForegroundService"), 0)
        assertTrue(si.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0)
        val perms = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions!!.toList()
        assertTrue("android.permission.FOREGROUND_SERVICE_DATA_SYNC" in perms)
    }

    @Test fun workersForegroundInfoHasTypeAndNeverThrows() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val a = runBlocking { TestListenableWorkerBuilder<CopyWorker>(ctx).build().getForegroundInfo() }
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, a.foregroundServiceType)
        val b = runBlocking { TestListenableWorkerBuilder<FactWorker>(ctx).build().getForegroundInfo() }
        assertNotNull(b.notification)
    }

    @Test fun staleStateClearedOnLaunchAndGuardActive() {
        // 首次以 1.18.1 启动：坏卡片被清掉、恢复模式关闭模糊
        assertTrue(CrashGuard.recovered)
        assertNull(GenCard.state.value)
        assertNull(rule.activity.getSharedPreferences("gen", 0).getString("card", null))
    }

    @Test fun generateWhileOnWritePageDoesNotCrash() {
        Glass.blur = true // 真机路径：Android 12+ 开着真实模糊
        val ctx = rule.activity.applicationContext
        Settings(ctx).setKey("deepseek", "sk-test")
        val latch = CountDownLatch(1)
        Ai.chatTransport = { _, _ -> latch.await(); 500 to "x" }
        CopyWorker.start(ctx, GenJob(raw = "合肥", cleaned = "合肥", model = "p:deepseek", finalPrompt = "写"))
        idle()
        latch.countDown(); idle()
        GenCard.done(ctx, 1, "标题", 100); idle()
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasTestTag("genCard")); rule.onNodeWithTag("genCard").assertExists()
        Ai.chatTransport = null
    }
}
