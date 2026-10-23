package com.scarlett.luobi.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.scarlett.luobi.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 1.18：后台写作卡（进行中 / 已写好 / 收起过程）、删除弹窗、微信二选一、审计前后对比 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w360dp-h780dp-xxhdpi")
class V118Shots {
    @get:Rule val rule = createComposeRule()
    private val dir = System.getProperty("user.dir") + "/../../screenshots/v1.18.0/"
    private val ctx get() = RuntimeEnvironment.getApplication()
    @After fun r() { GenCard.clear(ctx) }
    private fun idle(ms: Long = 1200) { rule.mainClock.advanceTimeBy(ms); rule.waitForIdle() }

    private var prog by androidx.compose.runtime.mutableStateOf<GenProgress?>(null)
    private fun page() = rule.setContent { AppTheme {
        Column(Modifier.fillMaxSize().background(P3.bg).padding(vertical = 24.dp)) {
            val px = Modifier.padding(horizontal = DS.Space.page)
            Text("写作", px, fontSize = 28.sp, color = P3.text); Spacer(Modifier.height(16.dp))
            GenCardSlot(prog, {}, {})
            Spacer(Modifier.height(12.dp))
            Text("灵感", px, fontSize = 20.sp, color = P3.text); Spacer(Modifier.height(10.dp))
            repeat(3) { Box(px.fillMaxWidth().height(72.dp).padding(bottom = 10.dp).background(Color.White, Sq.card26)) }
        }
    } }

    @Test fun genCardRunningDoneAndDismiss() {
        prog = GenProgress(2, 1260, "合肥的赌局，你学不会", "那一年，合肥把全部家当押在了一块屏幕上，所有人都觉得它疯了")
        page(); idle()
        rule.onNodeWithTag("genCard").assertExists(); rule.onNodeWithTag("genWords").assertExists()
        rule.onRoot().captureRoboImage("${dir}gen-1-running.png")
        prog = null; GenCard.done(ctx, 1, "合肥的赌局，你学不会", 3420); idle()
        rule.onNodeWithText("已写好").assertExists(); rule.onNodeWithText("3,420 字 · 已放进案头").assertExists()
        rule.onRoot().captureRoboImage("${dir}gen-2-done.png")
        rule.mainClock.autoAdvance = false
        GenCard.markViewed(ctx, 1)
        val y0 = rule.onNodeWithText("灵感").fetchSemanticsNode().positionInRoot.y
        val ys = mutableListOf<Float>()
        for (i in 0..20) {   // 0–800ms，每 40ms 一帧
            rule.mainClock.advanceTimeBy(if (i == 0) 0 else 40); rule.waitForIdle()
            ys += rule.onNodeWithText("灵感").fetchSemanticsNode().positionInRoot.y
            if (i % 2 == 0) rule.onRoot().captureRoboImage("${dir}dismiss/f%02d.png".format(i / 2))
        }
        println("DISMISS_Y y0=$y0 " + ys.joinToString())
        rule.mainClock.autoAdvance = true; idle()
        rule.onNodeWithTag("genCard").assertDoesNotExist()
        assertNull("收起后清掉卡片", GenCard.state.value)
        val y1 = rule.onNodeWithText("灵感").fetchSemanticsNode().positionInRoot.y
        assertTrue("灵感上移", y1 < y0)
        // 单调上移、没有跳变：40ms 内位移不超过总位移的 45%（≈每个 60Hz 帧 < 20%）
        ys.zipWithNext().forEach { (a, b) -> assertTrue("不回弹", b <= a + 0.5f); assertTrue("无跳变 $a→$b", a - b <= (y0 - y1) * .45f + 1f) }
    }

    @Test fun deleteAlertAndBefore() {
        var ok = 0; var show by androidx.compose.runtime.mutableStateOf(true)
        rule.setContent { AppTheme { Box(Modifier.fillMaxSize().background(P3.bg)) { if (show) DeleteAlert("删除这篇文章？", "合肥的赌局，你学不会", onOk = { ok++ }, onDismiss = { show = false }) } } }
        idle(); rule.onNodeWithTag("glassAlert").assertExists()
        captureScreenRoboImage("${dir}delete-alert.png"); captureScreenRoboImage("${dir}audit-after-dialog.png")
        rule.onNodeWithTag("alert:删除").performClick(); idle()
        assertEquals(1, ok); assertFalse(show)
    }

    @Test fun auditBeforeDialog() {
        rule.setContent { AppTheme { Box(Modifier.fillMaxSize().background(P3.bg)) {
            @Suppress("DEPRECATION") androidx.compose.material3.AlertDialog(onDismissRequest = {}, title = { Text("删除这篇文章？") }, text = { Text("「合肥的赌局，你学不会」删除后无法恢复") },
                confirmButton = { androidx.compose.material3.TextButton({}) { Text("删除", color = Color(0xFFC0392B)) } }, dismissButton = { androidx.compose.material3.TextButton({}) { Text("取消") } })
        } } }
        idle(); captureScreenRoboImage("${dir}audit-before-dialog.png")
    }

    @Test fun wechatChoice() {
        val act = ChannelActions(ctx, Note(title = "t", body = "b", tags = ""), { emptyList() }, readPalettes.first(), "", {})
        rule.setContent { AppTheme { Box(Modifier.fillMaxSize().background(P3.bg)) { WechatChoice(act) } } }
        idle()
        rule.onNodeWithText("以文件发送（原图不压缩）").assertExists(); rule.onNodeWithText("保存并打开微信").assertExists()
        captureScreenRoboImage("${dir}share-wechat.png")
    }
}
