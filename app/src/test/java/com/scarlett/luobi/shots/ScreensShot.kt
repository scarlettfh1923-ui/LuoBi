package com.scarlett.luobi.shots

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

/** 1.7 UI 审查：JVM 上渲染各屏（Robolectric 原生图形 + Roborazzi），不需要模拟器 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = TestApp::class)
abstract class ShotBase(private val q: String, private val tag: String) {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val dir = System.getProperty("user.dir") + "/../../screenshots/v1.10/"
    fun shot(name: String) { rule.waitForIdle(); Thread.sleep(300); rule.mainClock.advanceTimeBy(800); rule.onRoot().captureRoboImage("$dir$tag-$name.png") }
    fun tap(t: String) { rule.onAllNodesWithText(t, substring = false).onFirst().performClick(); rule.mainClock.advanceTimeBy(600); rule.waitForIdle() }
    @Test fun screens() {
        shot("01-write")
        tap("案头"); shot("02-library-empty")
        val ctx = RuntimeEnvironment.getApplication()
        runBlocking {
            val d = AppDb.get(ctx).notes()
            d.insert(Note(title = "为什么越忙的人，越需要留白", body = "## 01\n\n**留白不是浪费，而是给大脑做「整理」的时间。**\n\n当日程被填满，我们以为自己在高效运转，其实只是在不停地接收，从未真正消化。\n\n## 02\n\n真正的休息，是允许自己什么都不做。", tags = "成长 时间管理", favorite = true, templateName = "干货清单"))
            d.insert(Note(title = "一个人的周末，也可以很丰盛，从早上的一杯手冲开始把时间还给自己", body = "从早上的一杯手冲开始，把时间还给自己。", tags = "生活", templateName = "生活随笔"))
        }
        rule.mainClock.advanceTimeBy(1500); rule.waitForIdle(); shot("03-library")
        tap("为什么越忙的人，越需要留白"); shot("04-reading")
        runCatching { tap("更多"); shot("05-more-sheet") }
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        runCatching { tap("设定"); shot("06-settings") }
        for ((i, s) in listOf("模型与密钥", "每日选题提示词", "意图提取提示词", "模板管理", "篇幅", "分享栏", "发布渠道", "图片来源", "出图与默认", "关于落笔").withIndex()) runCatching {
            tap(s); shot("1${i}-set-$s")
            rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle()
        }
    }
}
@Config(qualifiers = "w360dp-h780dp-xxhdpi") class Shot360 : ShotBase("", "360")
@Config(qualifiers = "w411dp-h891dp-xxhdpi") class Shot411 : ShotBase("", "411")
@Config(qualifiers = "w360dp-h780dp-xxhdpi", fontScale = 1.3f) class Shot360Big : ShotBase("", "360-fs1.3")
@Config(qualifiers = "w411dp-h891dp-xxhdpi", fontScale = 1.3f) class Shot411Big : ShotBase("", "411-fs1.3")
