package com.scarlett.luobi

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.unit.IntOffset

/**
 * 1.10 全局动效规范（参照 iOS：快进、柔和收尾）。所有转场都从这里取，保证一致。
 * - 时长：短 180ms（淡入淡出、小元素）、中 280ms（页面推入/返回）、长 380ms（底部弹层）
 * - 曲线：Standard = cubic(0.2, 0, 0, 1)（减速收尾，接近 iOS 默认）；退出用 Exit = cubic(0.3, 0, 1, 1)
 * - 弹层与可拖动元素：临界阻尼弹簧（无回弹）
 */
object Motion {
    const val SHORT = 180; const val MEDIUM = 280; const val LONG = 380
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Exit = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    fun <T> enter(ms: Int = MEDIUM) = tween<T>(ms, easing = Standard)
    fun <T> exit(ms: Int = SHORT) = tween<T>(ms, easing = Exit)
    val sheetSpring = spring<IntOffset>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f)
    val dragSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    val fadeIn = fadeIn(enter(SHORT)); val fadeOut = fadeOut(exit(SHORT))
    /** 底部弹层：从底部滑入 + 回到底部 */
    val sheetIn = slideInVertically(sheetSpring) { it }
    val sheetOut = slideOutVertically(tween(MEDIUM, easing = Exit)) { it }
    /** 页面推入 / 返回（右进左出，带一点视差） */
    fun push(): ContentTransform = (slideInHorizontally(enter()) { it / 3 } + fadeIn(enter())) togetherWith (slideOutHorizontally(enter()) { -it / 8 } + fadeOut(exit()))
    fun pop(): ContentTransform = (slideInHorizontally(enter()) { -it / 8 } + fadeIn(enter())) togetherWith (slideOutHorizontally(enter()) { it / 3 } + fadeOut(exit()))
    fun tab(): ContentTransform = fadeIn(enter(SHORT)) togetherWith fadeOut(exit(SHORT))
    /** 卡片出现 / 消失（错误卡、提示等） */
    val cardIn = fadeIn(enter(SHORT)) + expandVertically(enter())
    val cardOut = fadeOut(exit(SHORT)) + shrinkVertically(exit(MEDIUM))
}
