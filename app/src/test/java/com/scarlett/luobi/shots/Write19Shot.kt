package com.scarlett.luobi.shots

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.scarlett.luobi.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w360dp-h780dp-xxhdpi")
class Write19Shot {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val dir = System.getProperty("user.dir") + "/../../screenshots/v1.10/"
    private fun idle() { rule.mainClock.advanceTimeBy(1500); rule.waitForIdle(); Thread.sleep(200); rule.mainClock.advanceTimeBy(500); rule.waitForIdle() }
    private fun shot(n: String) { idle(); rule.onRoot().captureRoboImage("$dir$n.png") }
    private val raw = "就是那个李海啊，在鹤岗卖房子直播，六十多平才几万块，然后爆了。我觉得这事儿其实不是房子便宜，是他会讲故事。"

    @Test fun flow() {
        val p = RuntimeEnvironment.getApplication().getSharedPreferences("draft", Context.MODE_PRIVATE)
        p.edit().clear().apply()
        shot("w1-empty")
        // 原文 + 没有任何 Key → 提取失败，显示可重试的错误卡
        p.edit().putString("raw", raw).apply(); rule.activityRule.scenario.recreate(); idle()
        shot("w2-input")
        rule.onNodeWithText("提取意图").performClick(); idle(); Thread.sleep(800); idle()
        shot("w3-error-retry")
        // 已提取（观点/疑惑为空）、弹层打开——用 recreate 同时验证草稿在重建后还在
        p.edit().putString("intent", """{"想写什么":"鹤岗卖房爆款背后：会讲故事比便宜重要","观点":"","疑问":""}""")
            .putString("extractedFor", raw).putBoolean("sheet", true).apply()
        rule.activityRule.scenario.recreate(); idle()
        shot("w4-sheet-empty-fields")
        rule.onNodeWithText("查看最终提示词").performClick(); idle()
        rule.onNodeWithText("收起最终提示词").performScrollTo(); shot("w5-sheet-preview")
        // 关闭后重新打开
        rule.onNodeWithContentDescription("关闭").performClick(); idle()
        shot("w6-closed-reopen")
        rule.onNodeWithText("查看意图卡").performClick(); idle()
        rule.onNodeWithText("意图卡").assertExists()
        // 旋转（Activity 声明了 configChanges，不重建；这里用 recreate 模拟最坏情况）
        rule.activityRule.scenario.recreate(); idle()
        rule.onNodeWithText("意图卡").assertExists()
        shot("w7-after-recreate")
    }
}
