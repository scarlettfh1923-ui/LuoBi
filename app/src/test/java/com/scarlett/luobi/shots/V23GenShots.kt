package com.scarlett.luobi.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
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
import java.io.File

/** 2.3 两步生成：类型选择（4 方向 + 财经）、校对开关、进度阶段 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w393dp-h873dp-xxhdpi")
class V23GenShots {
    @get:Rule val rule = createComposeRule()
    private val dir = File(System.getProperty("user.dir") + "/../../screenshots/v2.3-gen/").apply { mkdirs() }.path + "/"
    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun shot(n: String) { rule.mainClock.advanceTimeBy(900); rule.waitForIdle(); rule.onRoot().captureRoboImage("$dir$n.png") }

    @Test fun settingsToggle() {
        Settings(ctx).polish23 = false
        rule.setContent { AppTheme { LengthPage {} } }
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("成文后再校对一遍"))
        shot("02-settings-polish-toggle")
    }
    @Test fun progressStages() {
        val ps = androidx.compose.runtime.mutableStateOf(GenProgress(1, stage = 1, stages = 2))
        rule.setContent { AppTheme { Box(Modifier.fillMaxWidth().background(P3.bg).padding(vertical = 24.dp)) { GenCardSlot(ps.value, {}, {}) } } }
        shot("03-progress-outline")
        ps.value = GenProgress(2, 860, "商场卖的不是货，是一个被安排好的周末", "同一个商场，两种人流。一边在排队", stage = 2, stages = 2); shot("04-progress-writing")
        ps.value = GenProgress(2, 1320, "商场卖的不是货，是一个被安排好的周末", "", stage = 3, stages = 3); shot("05-progress-polish-on")
    }
}
