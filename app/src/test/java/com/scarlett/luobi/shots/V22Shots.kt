package com.scarlett.luobi.shots

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.scarlett.luobi.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CountDownLatch

/** 2.2 截图：首页、灵感四态、阅读页各配色、编辑、设定、模板（8 类）、图标 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w393dp-h873dp-xxhdpi")
class V22Shots {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val dir = File(System.getProperty("user.dir") + "/../../screenshots/v2.2/").apply { mkdirs() }.path + "/"
    private val ctx get() = RuntimeEnvironment.getApplication()
    @After fun r() { HotSources.fake = null; Inspire.expandHook = null }
    private fun shot(name: String) { rule.waitForIdle(); Thread.sleep(200); rule.mainClock.advanceTimeBy(900); rule.waitForIdle(); captureScreenRoboImage("$dir$name.png") }
    private fun step(ms: Long = 700) { rule.mainClock.advanceTimeBy(ms); rule.waitForIdle() }
    private fun tap(t: String) { rule.onAllNodesWithText(t, substring = false).onFirst().performClick(); step() }
    private fun toInspire() { rule.onAllNodes(hasScrollAction()).onFirst().performTouchInput { swipeUp(startY = bottom * 0.85f, endY = bottom * 0.25f, durationMillis = 400) }; step(900) }
    private fun back() { rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; step() }

    @Test fun homeAndInspireLoaded() {
        step(1200); shot("01-home")
        toInspire(); shot("02-inspire-loaded")
    }

    @Test fun inspireLoadingAndPartial() {
        ctx.getSharedPreferences("inspire", 0).edit().clear().commit()
        val gate = CountDownLatch(1); val wb = CountDownLatch(1)
        HotSources.fake = { s -> if (s.id == "weibo") wb.await().let { """{"data":{"realtime":[{"word":"秋天第一杯热红酒","num":1234567},{"word":"城市夜跑为什么火了","num":987654},{"word":"年轻人开始攒“情绪存款”","num":765432}]}}""" }
            else { gate.await(); "" } }
        rule.activityRule.scenario.recreate(); Thread.sleep(150); step(300)
        toInspire(); captureScreenRoboImage("${dir}03-inspire-loading.png")
        wb.countDown(); Thread.sleep(1500); step(600); shot("04-inspire-partial")
        gate.countDown()
    }

    @Test fun inspireExpanding() {
        val gate = CompletableDeferred<Unit>()
        Inspire.expandHook = { t -> gate.await(); t.copy(angle = "从补偿方案看大厂的人才账", view = "离职补偿正在变成一种隐形福利") }
        step(1200); toInspire()
        rule.onAllNodesWithText(InspireSeed.topics[1].title, substring = false).onFirst().performClick(); step(400)
        shot("05-inspire-ai-expanding")
        gate.complete(Unit); step(800)
    }

    @Test fun readerThemesEditorSettings() {
        val id = runBlocking { val d = AppDb.get(ctx).notes()
            val n = Note(title = "热梗是方言，不是病毒", body = "## 一\n\n“绝绝子”火的那一年，很多人担心中文会被它吃掉。可回头看，它更像一阵方言：在一个圈子里通行，出了圈就慢慢失效。\n\n> 语言的活力，恰恰来自这种来来去去。\n\n## 二\n\n热梗的问题不在于它存在，而在于有人只剩下它。当所有情绪都用同一个词表达，被磨平的不是词，是感受本身。\n\n所以与其禁止，不如多读一点好句子。", tags = "语言 网络文化 热梗", category = "社会", createdAt = System.currentTimeMillis() + 60_000)
            val id = d.insert(n); d.update(n.copy(id = id, rootId = id)); id }
        step(800); tap("案头"); shot("06-library")
        tap("热梗是方言，不是病毒"); step(1500); shot("07-reader")
        rule.onAllNodesWithContentDescription("编辑", substring = true).onFirst().performClick(); step(900); shot("08-editor"); back(); back()
        val body = runBlocking { AppDb.get(ctx).notes().get(id)!!.body }
        for ((i, st) in Render.styles.withIndex()) {
            val b = Render.cover("热梗是方言，不是病毒", listOf("语言", "网络文化", "热梗"), st, null)
            if (i == 0) for (k in BodyKind.values()) Render.pages("热梗是方言，不是病毒", body, st, width = 1080, height = 1440, kind = k).first().let { p -> File("${dir}13-body-${k.name}.png").outputStream().use { p.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            File("${dir}12-cover-${i + 1}-${st.name}.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        Settings(ctx).style = 0
        tap("设定"); shot("09-settings")
        tap("模板管理"); shot("10-templates-8-types"); back()
    }

    @Test fun icon() {
        val d = androidx.core.content.ContextCompat.getDrawable(ctx, ctx.resources.getIdentifier("ic_launcher", "mipmap", ctx.packageName))!!
        val b = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888); val c = Canvas(b)
        d.setBounds(0, 0, 432, 432); d.draw(c)
        File("${dir}11-icon.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
