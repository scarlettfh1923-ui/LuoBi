package com.scarlett.luobi

import androidx.compose.animation.core.*
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 1.18 唯一的设计令牌文件。所有界面的圆角、玻璃、阴影、间距、字号、颜色、动效都从这里取。
 * 旧的 Sq / Corner / P3 / Motion / Glass 保留名字（调用点太多），但数值全部指向这里，不再各写各的。
 */
object DS {
    // ---- 颜色：只有一个强调色 + 一个危险红 ----
    val accent = Color(0xFF2E5E6B)
    val danger = Color(0xFFA8432F)           // 删除、错误（对话框里的「删除」、错误卡）
    val dangerSoft = Color(0xFFF6E6E0)
    val scrim = Color(0x472A2016)             // 弹层 / 对话框遮罩，全 App 同一个
    val ink = Color(0xFF26221D)               // 深色提示条底色

    // ---- 圆角（dp） ----
    object R { val xs = 10.dp; val s = 14.dp; val m = 18.dp; val l = 22.dp; val xl = 24.dp; val card = 26.dp; val alert = 26.dp }

    // ---- 间距刻度（4 的倍数） ----
    object Space { val x1 = 4.dp; val x2 = 8.dp; val x3 = 12.dp; val x4 = 16.dp; val x5 = 20.dp; val x6 = 24.dp; val page = 16.dp; val text = 24.dp }

    // ---- 阴影 ----
    object Elev { val none = 0.dp; val float = 6.dp; val bar = 10.dp; val card = 8.dp; val alert = 18.dp }

    // ---- 玻璃材质 ----
    object Glassy { val blur = 18.dp; val tintAlpha = .74f; val fallbackAlpha = .92f; val rimAlpha = .95f; val alertTint = Color(0xFFFAF6EE).copy(alpha = .86f) }

    // ---- 字号 ----
    object Type {
        val largeTitle = 32.sp; val title = 20.sp; val headline = 17.sp; val body = 15.sp; val callout = 14.sp; val sub = 13.sp; val caption = 12.sp; val micro = 11.sp
    }

    // ---- 动效 ----
    object Dur { const val PRESS = 120; const val SHORT = 180; const val FADE_OUT = 200; const val MEDIUM = 280; const val COLLAPSE = 280; const val LONG = 380 }
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Exit = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    /** 1.18 高度收起：两头缓，避免第一帧就塌下去一大截 */
    val Collapse = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    /** 按下：整块压暗 6%，进 120ms、出 180ms；不做水波纹 */
    val pressOverlay = Color(0x0F000000)
    const val PRESS_SPRING_DAMPING = 0.75f; const val PRESS_SPRING_STIFF = 420f
}

/** 1.18：全 App 统一按压反馈（替代 Material 水波纹）。系统「移除动画」时直接显示 / 消失 */
@Immutable
object SoftPress : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource)
    override fun hashCode() = -1
    override fun equals(other: Any?) = other === this
    private class Node(val src: InteractionSource) : Modifier.Node(), DrawModifierNode {
        val a = Animatable(0f)
        override fun onAttach() {
            coroutineScope.launch {
                src.interactions.collect { i ->
                    when (i) {
                        is PressInteraction.Press -> launch { if (Motion.reduced) a.snapTo(1f) else a.animateTo(1f, tween(DS.Dur.PRESS, easing = DS.Standard)) }
                        is PressInteraction.Release, is PressInteraction.Cancel -> launch { if (Motion.reduced) a.snapTo(0f) else a.animateTo(0f, tween(DS.Dur.SHORT, easing = DS.Exit)) }
                    }
                }
            }
        }
        override fun ContentDrawScope.draw() {
            drawContent()
            if (a.value > 0f) drawRect(DS.pressOverlay.copy(alpha = DS.pressOverlay.alpha * a.value))
        }
    }
}
