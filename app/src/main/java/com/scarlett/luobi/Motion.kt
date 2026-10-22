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
    /** 1.16：系统「移除动画」（动画时长缩放 = 0）时关闭动效 */
    var reduced: Boolean = false
    fun init(ctx: android.content.Context) {
        reduced = runCatching { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    /** 轻弹簧（约 0.35 秒，阻尼 0.75）；减少动画时直接到位 */
    fun <T> soft(): FiniteAnimationSpec<T> = if (reduced) snap() else spring(dampingRatio = 0.75f, stiffness = 420f)
    fun springDp(): FiniteAnimationSpec<androidx.compose.ui.unit.Dp> = soft()
    /** 心形 / 红点这种「弹一下」 */
    fun <T> bounce(): FiniteAnimationSpec<T> = if (reduced) snap() else spring(dampingRatio = 0.45f, stiffness = 500f)
    const val SHORT = 180; const val MEDIUM = 280; const val LONG = 380
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Exit = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    fun <T> enter(ms: Int = MEDIUM) = tween<T>(if (reduced) 0 else ms, easing = Standard)
    fun <T> exit(ms: Int = SHORT) = tween<T>(if (reduced) 0 else ms, easing = Exit)
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
