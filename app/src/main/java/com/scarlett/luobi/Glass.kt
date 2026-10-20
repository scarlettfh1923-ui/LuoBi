package com.scarlett.luobi

import android.os.Build
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 1.6 浮动磨砂：改用成熟的 Haze 库（dev.chrisbanes.haze）做背景模糊。
 * 内容区 hazeSource，浮动件 hazeEffect；约 90% 白 + 0.5dp 细线。
 * 设备不支持模糊时 Haze 用 fallbackTint：94% 白，绝不透出文字。
 */
typealias Backdrop = HazeState
val LocalBackdrop = staticCompositionLocalOf<HazeState?> { null }
@Composable fun rememberBackdrop(): HazeState = remember { HazeState() }
fun Modifier.backdropSource(b: HazeState?): Modifier = if (b == null) this else this.hazeSource(b)

val Hairline = Color(0xFFE2E5EA)
private fun glassStyle(dark: Boolean) = if (dark) HazeStyle(backgroundColor = Color(0xFF1E1E22), tints = listOf(HazeTint(Color(0xFF1E1E22).copy(alpha = .88f))), blurRadius = 24.dp, noiseFactor = 0f, fallbackTint = HazeTint(Color(0xFF1E1E22).copy(alpha = .96f)))
    else HazeStyle(backgroundColor = Color.White, tints = listOf(HazeTint(Color.White.copy(alpha = .90f))), blurRadius = 24.dp, noiseFactor = 0f, fallbackTint = HazeTint(Color.White.copy(alpha = .94f)))

/** 磨砂外观：中性柔阴影（同一 Squircle）+ 裁切 + Haze 模糊 + 细线（自己画，不用 Compose border） */
fun Modifier.frosted(shape: androidx.compose.ui.graphics.Shape = Sq.full, elevation: Dp = 10.dp, tint: Color = Color.White, dark: Boolean = false): Modifier = composed {
    val b = LocalBackdrop.current
    val base = this.softShadow(shape, elevation).clip(shape)
    val glass = if (b != null) base.hazeEffect(b, glassStyle(dark)) else base.drawBehind { drawRect(if (dark) Color(0xFF1E1E22).copy(alpha = .96f) else Color.White.copy(alpha = .94f)) }
    glass.hairline(shape, if (dark) Color.White.copy(alpha = .10f) else Hairline)
}

/** 分享栏 / 导航的统一网格（detail.png）：等宽格；图标框 24dp（图标 20dp 居中）；间距 3dp；标签行高 14dp；整组垂直居中；整格可点 */
object GridSpec { val icon = 24.dp; val glyph = 20.dp; val gap = 3.dp; val label = 14.dp }
val NavInk = Color(0xFF374151)
private val labelStyle = TextStyle(fontSize = 10.5.sp, lineHeight = 14.sp, textAlign = TextAlign.Center,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))

@Composable
fun RowScope.GridCell(icon: List<String>, label: String, on: Boolean, base: Color, sub: Color, busy: Boolean = false, glyph: Dp = GridSpec.glyph, onClick: () -> Unit) {
    val c = if (on) P3.accent else base
    Column(Modifier.weight(1f).fillMaxHeight().clip(Sq.full).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        // 1.6 选中态：图标后面一块浅蓝胶囊（不改变网格尺寸）
        Box(Modifier.size(width = 48.dp, height = GridSpec.icon + 4.dp).clip(Sq.full).background(if (on) P3.accentSoft else Color.Transparent), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = P3.accent, strokeWidth = 2.dp) else SvgIcon(icon, c, glyph)
        }
        Spacer(Modifier.height(GridSpec.gap - 2.dp))
        Box(Modifier.height(GridSpec.label).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(label, style = labelStyle, color = if (on) P3.accent else sub, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
        }
    }
}

/** 浮动胶囊容器：左右 16dp，离底 12dp + 导航条 */
@Composable
fun FloatingBar(height: Dp = 60.dp, dark: Boolean = false, content: @Composable RowScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(height).frosted(dark = dark).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}
/** 浮动导航 + 主按钮所需的底部留白 */
val FloatingBarSpace = 60.dp + 8.dp + 12.dp

/** 主导航：写作 / 文案库 / 设置，浮动胶囊 */
@Composable
fun FloatingNav(sel: Int, onSel: (Int) -> Unit) {
    FloatingBar {
        listOf(Triple("写作", Ic.write, 0), Triple("文案库", Ic.library, 1), Triple("设置", Ic.settings, 2)).forEach { (l, ic, i) ->
            GridCell(ic, l, sel == i, NavInk, NavInk) { onSel(i) }
        }
    }
}

/** 浮动圆（返回）与浮动动作组（插图 / 阅读设置 / 编辑） */
@Composable
fun FloatCircle(icon: List<String>, tint: Color, desc: String, dark: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).frosted(elevation = 8.dp, dark = dark).clickable(onClick = onClick), contentAlignment = Alignment.Center) { SvgIcon(icon, tint, 22.dp, desc = desc) }
}
@Composable
fun FloatGroup(dark: Boolean = false, content: @Composable RowScope.() -> Unit) {
    Row(Modifier.height(44.dp).frosted(elevation = 8.dp, dark = dark).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}
@Composable
fun FloatAction(icon: List<String>, tint: Color, desc: String, onClick: () -> Unit) {
    Box(Modifier.size(width = 44.dp, height = 44.dp).clip(Sq.full).clickable(onClick = onClick), contentAlignment = Alignment.Center) { SvgIcon(icon, tint, 22.dp, desc = desc) }
}
