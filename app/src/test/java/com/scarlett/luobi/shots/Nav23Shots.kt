package com.scarlett.luobi.shots

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.scarlett.luobi.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 2.3 底部导航玻璃对比：密集案头 / 创作 / 设定。-Dnav.tag=before|after */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w360dp-h780dp-xxhdpi")
class Nav23Shots {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val tag = System.getProperty("nav.tag") ?: "after"
    private val dir = System.getProperty("user.dir") + "/../../screenshots/v2.3-nav/"
    private fun idle() { rule.mainClock.advanceTimeBy(1200); rule.waitForIdle() }
    private fun shot(n: String) { idle(); Thread.sleep(200); captureScreenRoboImage("$dir$tag-$n.png") }

    @Test fun nav() {
        runBlocking {
            val d = AppDb.get(RuntimeEnvironment.getApplication()).notes(); d.latest().first().forEach { d.deleteId(it.id) }
            val now = System.currentTimeMillis()
            repeat(24) { i -> val n = Note(title = "第${i + 1}篇：为什么越忙的人，越需要留白和独处的时间", body = "正文。".repeat(100), tags = "", templateName = "通用",
                category = listOf("社会观察", "影视评论", "思想人文", "历史非虚构")[i % 4], createdAt = now - i * 86_400_000L * 2)
                val id = d.insert(n); d.update(n.copy(id = id, rootId = id)) }
        }
        idle(); shot("write")
        rule.onAllNodesWithText("案头").onFirst().performClick(); shot("desk")
        rule.onRoot().performTouchInput { swipeUp() }; shot("desk-scrolled")
        rule.onAllNodesWithText("设定").onFirst().performClick(); shot("settings")
    }
}
