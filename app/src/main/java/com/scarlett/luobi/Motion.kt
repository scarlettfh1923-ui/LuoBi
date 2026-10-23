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
    fun <T> soft(): FiniteAnimationSpec<T> = if (reduced) snap() else spring(dampingRatio = DS.PRESS_SPRING_DAMPING, stiffness = DS.PRESS_SPRING_STIFF)
    fun springDp(): FiniteAnimationSpec<androidx.compose.ui.unit.Dp> = soft()
    /** 心形 / 红点这种「弹一下」 */
    fun <T> bounce(): FiniteAnimationSpec<T> = if (reduced) snap() else spring(dampingRatio = 0.45f, stiffness = 500f)
    const val SHORT = DS.Dur.SHORT; const val MEDIUM = DS.Dur.MEDIUM; const val LONG = DS.Dur.LONG
    val Standard = DS.Standard
    val Exit = DS.Exit
    fun <T> enter(ms: Int = MEDIUM) = tween<T>(if (reduced) 0 else ms, easing = Standard)
    fun <T> exit(ms: Int = SHORT) = tween<T>(if (reduced) 0 else ms, easing = Exit)
    val sheetSpring = spring<IntOffset>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f)
    val dragSpring = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    val fadeIn get() = fadeIn(enter(SHORT)); val fadeOut get() = fadeOut(exit(SHORT))
    /** 底部弹层：从底部滑入 + 回到底部 */
    val sheetIn get() = if (reduced) EnterTransition.None else slideInVertically(sheetSpring) { it }
    val sheetOut get() = if (reduced) ExitTransition.None else slideOutVertically(tween(MEDIUM, easing = Exit)) { it }
    /** 页面推入 / 返回（右进左出，带一点视差） */
    fun push(): ContentTransform = (slideInHorizontally(enter()) { it / 3 } + fadeIn(enter())) togetherWith (slideOutHorizontally(enter()) { -it / 8 } + fadeOut(exit()))
    fun pop(): ContentTransform = (slideInHorizontally(enter()) { -it / 8 } + fadeIn(enter())) togetherWith (slideOutHorizontally(enter()) { it / 3 } + fadeOut(exit()))
    fun tab(): ContentTransform = fadeIn(enter(SHORT)) togetherWith fadeOut(exit(SHORT))
    /** 卡片出现 / 消失（错误卡、提示等） */
    val cardIn get() = fadeIn(enter(SHORT)) + expandVertically(enter())
    val cardOut get() = collapseOut  // 1.18：所有卡片消失都是「先淡出、再收起」
    /** 1.18「已写好」卡收起：先淡出 200ms，再高度收起 280ms（标准曲线），下面的内容平稳上移 */
    val collapseOut get() = if (reduced) ExitTransition.None else fadeOut(tween(DS.Dur.FADE_OUT, easing = Exit)) + shrinkVertically(tween(DS.Dur.COLLAPSE, delayMillis = DS.Dur.FADE_OUT, easing = DS.Collapse), shrinkTowards = androidx.compose.ui.Alignment.Top)
    /** 对话框：遮罩淡入 + 面板 0.92→1 缩放淡入；退出 180ms 淡出 */
    val alertIn get() = fadeIn(enter(SHORT)) + scaleIn(if (reduced) snap() else spring(dampingRatio = 0.8f, stiffness = 520f), initialScale = .92f)
    val alertOut get() = fadeOut(exit(SHORT)) + scaleOut(exit(SHORT), targetScale = .96f)
    /** 列表项插入 / 移除（LazyColumn animateItem 统一用这三条） */
    fun <T> itemFade(): FiniteAnimationSpec<T> = tween(if (reduced) 0 else SHORT, easing = Standard)
    fun <T> itemPlace(): FiniteAnimationSpec<T> = if (reduced) snap() else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
}
