package com.scarlett.luobi

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * 1.5：全 App 唯一的圆角形状族——连续曲率（squircle）。
 * 每个角都是同一条超椭圆曲线（指数 N），角区比名义半径大 SMOOTH 倍，过渡到直边时曲率连续（类 iOS）。
 * 所有卡片 / 胶囊 / 按钮 / 标签 / 面板 / 图片 / 对话框 / 浮动圆都用它；全圆角（胶囊、圆）= 半径取短边一半，同一曲线。
 */
class Squircle(private val tl: Dp, private val tr: Dp = tl, private val br: Dp = tl, private val bl: Dp = tl, private val full: Boolean = false) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val half = min(size.width, size.height) / 2f
        fun ext(r: Dp): Float = if (full) half else min(with(density) { r.toPx() } * SMOOTH, half)
        val p = Path()
        // 角心与起始角：右上(-90°) 右下(0°) 左下(90°) 左上(180°)
        val corners = listOf(Triple(ext(tr), 1, -90.0), Triple(ext(br), 2, 0.0), Triple(ext(bl), 3, 90.0), Triple(ext(tl), 0, 180.0))
        var first = true
        for ((e, idx, a0) in corners) {
            val cx = if (idx == 1 || idx == 2) size.width - e else e
            val cy = if (idx == 2 || idx == 3) size.height - e else e
            for (i in 0..STEPS) {
                val t = Math.toRadians(a0 + 90.0 * i / STEPS); val c = cos(t); val s = sin(t)
                val x = (cx + e * sign(c) * abs(c).pow(2.0 / N)).toFloat(); val y = (cy + e * sign(s) * abs(s).pow(2.0 / N)).toFloat()
                if (first) { p.moveTo(x, y); first = false } else p.lineTo(x, y)
            }
        }
        p.close(); return Outline.Generic(p)
    }
    override fun equals(other: Any?) = other is Squircle && other.tl == tl && other.tr == tr && other.br == br && other.bl == bl && other.full == full
    override fun hashCode() = listOf(tl, tr, br, bl, full).hashCode()
    companion object { const val N = 4.0; const val SMOOTH = 1.28f; const val STEPS = 16 }
}

/** 唯一的圆角刻度（dp）。旧 4–22dp 的零散数值统一映射到这里，整体比 1.4 更圆 */
object Corner {
    val xs = 8.dp      // 细条、进度、小徽标
    val s = 14.dp      // 标签内小块、代码块、引用块
    val m = 20.dp      // 输入框、列表行、图片、小卡
    val l = 24.dp      // 选项卡片、提示框
    val xl = 28.dp     // 卡片、面板、对话框、底部弹层
}
object Sq {
    val xs = Squircle(Corner.xs); val s = Squircle(Corner.s); val m = Squircle(Corner.m); val l = Squircle(Corner.l); val xl = Squircle(Corner.xl)
    /** 胶囊 / 圆：同一曲线，半径 = 短边一半 */
    val full = Squircle(0.dp, full = true)
    /** 只圆右侧（引用块） */
    val rightS = Squircle(0.dp, Corner.s, Corner.s, 0.dp)
    val topXl = Squircle(Corner.xl, Corner.xl, 0.dp, 0.dp)
}
