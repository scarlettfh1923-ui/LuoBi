package com.scarlett.luobi

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawOutline
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
    // 1.18：与 Sq 统一，取自 DS.R（以前 Corner 比 Sq 大 2–4dp，Material 兜底组件的圆角和自绘组件对不上）
    val xs = DS.R.xs; val s = DS.R.s; val m = DS.R.m; val l = DS.R.l; val xl = DS.R.xl
}
/**
 * 1.7：全部改为系统 RoundedCornerShape（Outline.Rounded，凸形）。
 * 1.5/1.6 的自定义连续曲线是 Outline.Generic，部分 Android 版本/ROM 对它画阴影、描边会退化成毛边，
 * 所以 1.7 不再在任何自定义路径上画阴影或描边。Squircle 类保留但不再被界面使用。
 */
object Sq {
    val card26 = RoundedCornerShape(DS.R.card)
    val alert = RoundedCornerShape(DS.R.alert)
    val xs = RoundedCornerShape(DS.R.xs); val s = RoundedCornerShape(DS.R.s); val m = RoundedCornerShape(DS.R.m); val l = RoundedCornerShape(DS.R.l); val xl = RoundedCornerShape(DS.R.xl)
    val full = RoundedCornerShape(50)
    val rightS = RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp)
    val topXl = RoundedCornerShape(topStart = DS.R.xl, topEnd = DS.R.xl)
}

/**
 * 1.6：squircle 的描边 / 阴影都自己画，和裁切用同一条 Path。
 * 原因：Compose 的 Modifier.border 对 Outline.Generic 会先光栅成位图遮罩再着色，
 * 1.5 的文案库卡片还把 border 放在裁切之外，曲线处半透明边缘在她手机上变成暗红色毛边。
 */
/** 1.7：细线始终为整数物理像素（默认 1px），只用于凸形 RoundedCornerShape */
fun androidx.compose.ui.Modifier.hairline(shape: Shape, color: androidx.compose.ui.graphics.Color, width: Dp = Dp.Hairline): androidx.compose.ui.Modifier =
    this.drawWithCache {
        val w = if (width == Dp.Hairline) 1f else kotlin.math.max(1f, kotlin.math.round(width.toPx()))
        val outline = shape.createOutline(androidx.compose.ui.geometry.Size(size.width - w, size.height - w), layoutDirection, this)
        onDrawWithContent {
            drawContent()
            translate(w / 2, w / 2) { drawOutline(outline, color, style = androidx.compose.ui.graphics.drawscope.Stroke(w)) }
        }
    }

/** 1.7：只给凸形胶囊（浮动条）用的极柔阴影；卡片一律不用 */
fun androidx.compose.ui.Modifier.softShadow(shape: Shape, elevation: Dp = 6.dp): androidx.compose.ui.Modifier =
    if (elevation.value <= 0f || shape !is RoundedCornerShape) this else this.shadow(elevation, shape, clip = false, ambientColor = androidx.compose.ui.graphics.Color(0x0F000000), spotColor = androidx.compose.ui.graphics.Color(0x14000000))

/**
 * 1.7 唯一的表面助手：纯色白 + 24dp 圆角裁切，无阴影无描边（Apple inset-grouped 做法，靠 #F2F2F7 底色分层）。
 * selected 时加 1px 强调色线。
 */
fun androidx.compose.ui.Modifier.surface(shape: Shape = Sq.xl, color: androidx.compose.ui.graphics.Color = P3.surface, elevation: Dp = 0.dp, outline: androidx.compose.ui.graphics.Color? = null): androidx.compose.ui.Modifier =
    this.clip(shape).background(color).let { if (outline != null) it.hairline(shape, outline) else it }
