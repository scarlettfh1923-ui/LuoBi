package com.scarlett.luobi

import android.os.Build
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
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
 * 1.5 浮动磨砂：内容区把自己录进一个 GraphicsLayer（backdropSource），
 * Android 12+ 再录一份带 RenderEffect 模糊的副本；浮动胶囊在自己位置画这份模糊副本 + 半透明白 = 真·背景模糊。
 * Android 11 及以下（或没有 Backdrop）回退到 1.4 的半透明白。
 */
class Backdrop {
    internal var src: GraphicsLayer? = null
    internal var blur: GraphicsLayer? = null
    internal var origin = Offset.Zero
    internal var radiusPx = 40f
}
val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }
val realBlur get() = Build.VERSION.SDK_INT >= 31

@Composable
fun rememberBackdrop(): Backdrop {
    val b = remember { Backdrop() }
    b.src = rememberGraphicsLayer(); b.blur = rememberGraphicsLayer()
    b.radiusPx = with(LocalDensity.current) { 24.dp.toPx() }
    return b
}

fun Modifier.backdropSource(b: Backdrop?): Modifier = if (b == null || !realBlur) this else
    this.onGloballyPositioned { b.origin = it.positionInRoot() }.drawWithContent {
        val src = b.src ?: run { drawContent(); return@drawWithContent }
        src.record { this@drawWithContent.drawContent() }
        drawLayer(src)
        b.blur?.let { bl -> bl.renderEffect = BlurEffect(b.radiusPx, b.radiusPx, TileMode.Clamp); bl.record { drawLayer(src) } }
    }

/** 磨砂外观：阴影 + 同一 Squircle 裁切 + 模糊背景 + 半透明白 + 0.5dp 描边 */
fun Modifier.frosted(shape: androidx.compose.ui.graphics.Shape = Sq.full, elevation: Dp = 10.dp, tint: Color = Color.White, dark: Boolean = false): Modifier = composed {
    val b = LocalBackdrop.current
    var pos by remember { mutableStateOf(Offset.Zero) }
    val real = b != null && realBlur && b.blur != null
    val fill = if (dark) Color(0xFF1E1E22) else tint
    this.onGloballyPositioned { pos = it.positionInRoot() }
        .shadow(elevation, shape, ambientColor = Color(0x22101828), spotColor = Color(0x33101828))
        .clip(shape)
        .drawBehind {
            if (real) { translate(b!!.origin.x - pos.x, b.origin.y - pos.y) { drawLayer(b.blur!!) }; drawRect(fill.copy(alpha = if (dark) .70f else .66f)) }
            else drawRect(fill.copy(alpha = if (dark) .94f else .90f))
        }
        .border(0.5.dp, if (dark) Color.White.copy(alpha = .10f) else Color(0x14000000), shape)
}

/** 分享栏 / 导航的统一网格（detail.png）：等宽格；图标框 24dp（图标 20dp 居中）；间距 3dp；标签行高 14dp；整组垂直居中；整格可点 */
object GridSpec { val icon = 24.dp; val glyph = 20.dp; val gap = 3.dp; val label = 14.dp }
private val labelStyle = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, textAlign = TextAlign.Center,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))

@Composable
fun RowScope.GridCell(icon: List<String>, label: String, on: Boolean, base: Color, sub: Color, busy: Boolean = false, glyph: Dp = GridSpec.glyph, onClick: () -> Unit) {
    val c = if (on) P3.accent else base
    Column(Modifier.weight(1f).fillMaxHeight().clip(Sq.full).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(GridSpec.icon), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = P3.accent, strokeWidth = 2.dp) else SvgIcon(icon, c, glyph)
        }
        Spacer(Modifier.height(GridSpec.gap))
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
            GridCell(ic, l, sel == i, P3.text, P3.text2) { onSel(i) }
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
