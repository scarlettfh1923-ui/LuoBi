package com.scarlett.luobi.shots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.scarlett.luobi.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w360dp-h780dp-xxhdpi")
class DsGen1162Shot {
    @get:Rule val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<MainActivity>()
    private val dir = System.getProperty("user.dir") + "/../../screenshots/v1.16.2/"
    private fun shot(think: Boolean, name: String) {
        val s = Settings(RuntimeEnvironment.getApplication()); s.dsThink = think
        rule.runOnUiThread { rule.activity.setContent { Column(Modifier.fillMaxWidth().background(P3.bg).padding(16.dp)) { Column(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color.White, androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).padding(16.dp)) { DsGenBox(s) } } } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("$dir$name.png")
        rule.onNodeWithText("0.7").assertExists()
        rule.onNodeWithText("思考模式下温度不生效").let { if (think) it.assertExists() else it.assertDoesNotExist() }
    }
    @Test fun off() = shot(false, "deepseek-think-off")
    @Test fun on() = shot(true, "deepseek-think-on")
}
