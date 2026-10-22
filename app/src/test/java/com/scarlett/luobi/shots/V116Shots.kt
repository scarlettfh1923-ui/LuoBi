package com.scarlett.luobi.shots

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.scarlett.luobi.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

abstract class V116Base(private val w: String) {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val dir = System.getProperty("user.dir") + "/../../screenshots/v1.16.0/"
    private fun step(ms: Long = 900) { rule.mainClock.advanceTimeBy(ms); rule.waitForIdle() }
    private fun shot(name: String) { step(); Thread.sleep(200); captureScreenRoboImage("$dir$w-$name.png") }

    @Test fun all() {
        rule.mainClock.autoAdvance = false  // 呼吸 / 打字机是无限动画，手动推进时钟
        val ctx = RuntimeEnvironment.getApplication()
        val now = System.currentTimeMillis(); val day = 86_400_000L
        runBlocking {
            val d = AppDb.get(ctx).notes()
            listOf("电影", "社会学与现代性", "文学", "").forEachIndexed { i, c ->
                val n = Note(title = "第${i + 1}篇：短剧为什么越来越“降智”？", body = "## 一、注意力经济\n" + "短剧的节奏越来越快，观众的耐心越来越少。".repeat(12) + "\n## 二、算法\n" + "推荐算法奖励的是三秒钟内的刺激。".repeat(10),
                    tags = "短剧 注意力经济", templateName = "通用", model = "m", category = c, favorite = i == 0, createdAt = now + 60_000L - i * 3 * day)
                val id = d.insert(n); d.update(n.copy(id = id, rootId = id))
            }
        }
        step()
        shot("write")
        rule.onAllNodesWithText("案头", substring = false).onFirst().performClick(); step()
        shot("desk")
        rule.onAllNodesWithText("全部分类").onFirst().performClick(); step(1200)
        shot("desk-menu")
        rule.onAllNodesWithText("全部", substring = false).onLast().performClick(); step()
        rule.onAllNodesWithText("第1篇", substring = true).onFirst().performClick(); step(1500)
        // 分享栏是动作按钮：打开时一个都不应显示选中
        val sel = rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true), useUnmergedTree = true).fetchSemanticsNodes()
        val shareSel = sel.filter { n -> n.children.any { c -> c.config.getOrNull(SemanticsProperties.Text)?.any { it.text in setOf("小红书", "微信", "QQ", "微博", "长图", "复制全文", "更多") } == true } }
        assertTrue("分享栏有按钮显示成选中：${shareSel.size}", shareSel.isEmpty())
        rule.onAllNodesWithText("小红书", substring = true).onFirst().assertExists()
        shot("reader")
    }
}

@RunWith(AndroidJUnit4::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w360dp-h780dp-xxhdpi")
class V116Shot360 : V116Base("360")

@RunWith(AndroidJUnit4::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class, qualifiers = "w412dp-h915dp-xxhdpi")
class V116Shot412 : V116Base("412")
