package com.scarlett.luobi.shots

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
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

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w360dp-h780dp-xxhdpi")
class DeleteUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val dir = System.getProperty("user.dir") + "/../../screenshots/v1.11/"
    private fun idle() { rule.mainClock.advanceTimeBy(1500); rule.waitForIdle() }
    private fun shot(n: String) { idle(); rule.onRoot().captureRoboImage("$dir$n.png") }
    private fun seed() = runBlocking {
        AppDb.get(RuntimeEnvironment.getApplication()).notes().let { d0 -> d0.latest().first().forEach { d0.deleteId(it.id) } }
        val d = AppDb.get(RuntimeEnvironment.getApplication()).notes()
        listOf("合肥的赌局，你学不会", "那个把鹤岗房子卖成爆款的人").forEach { t ->
            val n = Note(title = t, body = "正文 $t", tags = "", templateName = "财经非虚构")
            val id = d.insert(n); d.update(n.copy(id = id, rootId = id))   // 与 Ai.write 完全相同的写入路径
        }
    }
    private fun dbCount() = runBlocking { AppDb.get(RuntimeEnvironment.getApplication()).notes().latest().first().size }

    @Test fun swipeDelete() {
        seed(); rule.onAllNodesWithText("案头").onFirst().performClick(); idle()
        rule.onNodeWithText("那个把鹤岗房子卖成爆款的人").performTouchInput { swipeRight(startX = centerX - 200f, endX = centerX + 400f) }; idle()
        shot("del-1-swiped")
        rule.onAllNodesWithText("删除").onFirst().performClick(); idle()
        rule.onAllNodesWithText("删除").onLast().performClick(); idle()
        shot("del-2-after")
        runBlocking { println("LATEST " + AppDb.get(RuntimeEnvironment.getApplication()).notes().latest().first().map { it.id to it.title }); println("ROWS " + AppDb.get(RuntimeEnvironment.getApplication()).query("SELECT id,rootId,version,title FROM notes", null).let { c -> buildString { while (c.moveToNext()) append("[${c.getLong(0)},${c.getLong(1)},${c.getInt(2)},${c.getString(3)}]") } }) }
        Thread.sleep(500); idle(); assertEquals(1, dbCount())
        rule.onNodeWithText("那个把鹤岗房子卖成爆款的人").assertDoesNotExist()
        rule.onNodeWithText("1 篇").assertExists()
    }

    @Test fun manageDelete() {
        seed(); rule.onAllNodesWithText("案头").onFirst().performClick(); idle()
        rule.onNodeWithText("管理").performClick(); idle()
        rule.onNodeWithText("那个把鹤岗房子卖成爆款的人").performClick(); idle()
        rule.onNodeWithText("删除 (1)").performClick(); idle()
        rule.onAllNodesWithText("删除").onLast().performClick(); idle()
        shot("del-3-manage-after")
        Thread.sleep(500); idle(); assertEquals(1, dbCount())
        rule.onNodeWithText("那个把鹤岗房子卖成爆款的人").assertDoesNotExist()
    }
}
