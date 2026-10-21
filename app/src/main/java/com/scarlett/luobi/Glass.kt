package com.scarlett.luobi

import android.os.Build
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
 * 1.7：去掉 Haze。浮动条一律实色（≥97% 白）+ 1px #E3E4E8 胶囊描边 + 极柔阴影（仅凸形胶囊），清晰度不依赖模糊。
 * Backdrop 相关 API 保留为空实现，调用方不用改。
 */
class Backdrop
val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }
@Composable fun rememberBackdrop(): Backdrop = remember { Backdrop() }
@Suppress("UNUSED_PARAMETER") fun Modifier.backdropSource(b: Backdrop?): Modifier = this

val Hairline = Color(0xFFE3E4E8)
fun Modifier.frosted(shape: androidx.compose.ui.graphics.Shape = Sq.full, elevation: Dp = 6.dp, tint: Color = Color.White, dark: Boolean = false): Modifier =
    this.softShadow(shape, elevation).clip(shape).background(if (dark) Color(0xFF1E1E22) else Color.White.copy(alpha = .98f)).hairline(shape, if (dark) Color.White.copy(alpha = .12f) else Hairline)

/** 分享栏 / 导航的统一网格（detail.png）：等宽格；图标框 24dp（图标 20dp 居中）；间距 3dp；标签行高 14dp；整组垂直居中；整格可点 */
object GridSpec { val icon = 24.dp; val glyph = 20.dp; val gap = 3.dp; val label = 14.dp }
val NavInk = Color(0xFF374151)
private val labelStyle = TextStyle(fontSize = 10.5.sp, lineHeight = 14.sp, textAlign = TextAlign.Center,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))

@Composable
fun RowScope.GridCell(icon: List<String>, label: String, on: Boolean, base: Color, sub: Color, busy: Boolean = false, glyph: Dp = GridSpec.glyph, pill: Boolean = true, onClick: () -> Unit) {
    val c = if (on) P3.accent else base
    Column(Modifier.weight(1f).fillMaxHeight().clip(Sq.full).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        // 1.6 选中态：图标后面一块浅蓝胶囊（不改变网格尺寸）
        Box(Modifier.size(width = 56.dp, height = GridSpec.icon + 8.dp).clip(Sq.full).background(if (on && pill) P3.accentSoft else Color.Transparent), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = P3.accent, strokeWidth = 2.dp) else SvgIcon(icon, c, glyph, if (on && !pill) c else null)
        }
        Spacer(Modifier.height(GridSpec.gap - 2.dp))
        Box(Modifier.heightIn(min = GridSpec.label).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(label, style = if (on && !pill) labelStyle.copy(fontWeight = FontWeight.Bold) else labelStyle, color = if (on) P3.accent else sub, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
        }
    }
}

/** 浮动胶囊容器：左右 16dp，离底 12dp + 导航条 */
@Composable
fun FloatingBar(height: Dp = 64.dp, dark: Boolean = false, content: @Composable RowScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(height).frosted(dark = dark).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}
/** 浮动导航 + 主按钮所需的底部留白 */
val FloatingBarSpace = 64.dp + 8.dp + 12.dp + 12.dp

/** 主导航：写作 / 文案库 / 设置，浮动胶囊 */
@Composable
fun FloatingNav(sel: Int, onSel: (Int) -> Unit) {
    FloatingBar {
        listOf(Triple("创作", Ic.write, 0), Triple("案头", Ic.library, 1), Triple("设定", Ic.settings, 2)).forEach { (l, ic, i) ->
            // 1.8 方案 A：选中不加底块，实心蓝图标 + 加粗字；未选中浅灰
            GridCell(ic, l, sel == i, P3.text3, P3.text3, pill = false) { onSel(i) }
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
