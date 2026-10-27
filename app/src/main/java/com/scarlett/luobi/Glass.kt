package com.scarlett.luobi

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription

import android.os.Build
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
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
 * 1.16 方案 C：只有浮动层用玻璃（底栏、阅读页按钮、分享栏）。
 * - API 31+ 且非低内存设备：Haze 真实背景模糊（RenderEffect）+ 62% 白色调；
 * - 以下或低内存：92% 白半透明兜底，不做模糊（低端机不掉帧）。
 * 内容层用 backdropSource 标记为模糊来源；浮动层用 glass() 取样。
 */
class Backdrop(val haze: dev.chrisbanes.haze.HazeState = dev.chrisbanes.haze.HazeState())
val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }
@Composable fun rememberBackdrop(): Backdrop = remember { Backdrop() }
fun Modifier.backdropSource(b: Backdrop?): Modifier = if (b != null && Glass.blur) this.then(Modifier.haze(b.haze)) else this

object Glass {
    /** 由 MainActivity 按系统版本与内存设定 */
    var blur: Boolean = Build.VERSION.SDK_INT >= 31
    val tintLight = Color(0xFFFFFCF6).copy(alpha = .72f)
    val fallbackLight = Color(0xFFFFFCF6).copy(alpha = .93f)
    val tintDark = Color(0xFF221F1B).copy(alpha = .74f)
    val fallbackDark = Color(0xFF221F1B).copy(alpha = .94f)
    val rim = Color(0xFFFFFDF8).copy(alpha = .95f)
    fun init(ctx: android.content.Context) {
        val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        blur = Build.VERSION.SDK_INT >= 31 && am?.isLowRamDevice != true
    }
}

val Hairline = Color(0xFFE5DDCF)
/** 浮动层玻璃：阴影 + 圆角 + 背景模糊（或半透明兜底）+ 白色描边 */
fun Modifier.frosted(shape: androidx.compose.ui.graphics.Shape = Sq.full, elevation: Dp = 6.dp, tint: Color = Color.White, dark: Boolean = false): Modifier = composed {
    val b = LocalBackdrop.current
    val base = this.softShadow(shape, elevation).clip(shape)
    val filled = if (b != null && Glass.blur) base.hazeChild(b.haze, shape = shape, style = HazeStyle(
            backgroundColor = if (dark) Color(0xFF221F1B) else P3.bg,
            tint = HazeTint(if (dark) Glass.tintDark else Glass.tintLight), blurRadius = 18.dp, noiseFactor = 0f,
            fallbackTint = HazeTint(if (dark) Glass.fallbackDark else Glass.fallbackLight)))
        else base.background(if (dark) Glass.fallbackDark else Glass.fallbackLight)
    filled.hairline(shape, if (dark) Color.White.copy(alpha = .12f) else Glass.rim)
}

/** 分享栏 / 导航的统一网格（detail.png）：等宽格；图标框 24dp（图标 20dp 居中）；间距 3dp；标签行高 14dp；整组垂直居中；整格可点 */
object GridSpec { val icon = 24.dp; val glyph = 20.dp; val gap = 3.dp; val label = 14.dp }
val NavInk = Color(0xFF3A342D)
private val labelStyle = TextStyle(fontSize = 10.5.sp, lineHeight = 14.sp, textAlign = TextAlign.Center,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both))

@Composable
fun RowScope.GridCell(icon: List<String>, label: String, on: Boolean, base: Color, sub: Color, busy: Boolean = false, glyph: Dp = GridSpec.glyph, pill: Boolean = true, onClick: () -> Unit) {
    val c = if (on) P3.accent else base
    Column(Modifier.weight(1f).fillMaxHeight().clip(Sq.full).clickable(onClick = onClick).semantics { selected = on }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
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

/** 1.16：主导航——玻璃胶囊，选中项是一块白色胶囊，切换时轻弹簧滑动 */
@Composable
fun FloatingNav(sel: Int, onSel: (Int) -> Unit) {
    val items = listOf(Triple("创作", Ic.write, 0), Triple("案头", Ic.library, 1), Triple("设定", Ic.settings, 2))
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(66.dp).frosted(elevation = 10.dp)) {
            val cw = (maxWidth - 12.dp) / items.size
            val x by animateDpAsState(cw * sel, Motion.springDp(), label = "tabPill")
            // 选中胶囊（同心圆角：外 33 − 内边距 6 = 27）
            Box(Modifier.padding(6.dp).offset(x = x).width(cw).fillMaxHeight().softShadow(Sq.full, 2.dp).clip(Sq.full).background(P3.surface))
            Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                items.forEach { (l, ic, i) -> GridCell(ic, l, sel == i, P3.text3, P3.text3, pill = false) { onSel(i) } }
            }
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
fun FloatAction(icon: List<String>, tint: Color, desc: String, badge: Int = 0, progress: Float? = null, onClick: () -> Unit) {
    Box(Modifier.size(width = 44.dp, height = 44.dp).clip(Sq.full).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        // 1.17：后台校对中——图标外一圈细进度环（不确定时缓慢转动），不弹窗、不挡正文
        if (progress != null) {
            val track = tint.copy(alpha = .18f)
            if (progress < 0f && !Motion.reduced) CircularProgressIndicator(Modifier.size(34.dp).semantics { contentDescription = "校对进行中" }, color = tint, strokeWidth = 2.dp, trackColor = track)
            else CircularProgressIndicator({ progress.coerceIn(0f, 1f).coerceAtLeast(.04f) }, Modifier.size(34.dp).semantics { contentDescription = "校对进行中" }, color = tint, strokeWidth = 2.dp, trackColor = track)
        }
        SvgIcon(icon, tint, if (progress != null) 18.dp else 22.dp, desc = desc)
        // 1.16：红点弹簧出现
        if (badge < 0) {
            val sc = remember { androidx.compose.animation.core.Animatable(if (Motion.reduced) 1f else 0f) }
            LaunchedEffect(Unit) { sc.animateTo(1f, Motion.bounce()) }
            Box(Modifier.align(Alignment.TopEnd).padding(top = 9.dp, end = 9.dp).size(8.dp).graphicsLayer { scaleX = sc.value; scaleY = sc.value }
                .clip(Sq.full).background(P3.surface).padding(1.5.dp).clip(Sq.full).background(Color(0xFFA8432F)).semantics { contentDescription = "已检测" })
        }
        if (badge > 0) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp).size(if (badge > 9) 16.dp else 14.dp)
                .clip(Sq.full).background(P3.accent), contentAlignment = Alignment.Center) {
                Text(if (badge > 9) "9+" else "$badge", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}


/** 1.16：生成中——笔尖 + 一道浅蓝扫光（不用转圈）；「移除动画」时静止 */
@Composable
fun GeneratingNib(modifier: Modifier = Modifier) {
    val x = if (Motion.reduced) .5f else androidx.compose.animation.core.rememberInfiniteTransition(label = "sweep").animateFloat(-.3f, 1.3f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1400, easing = androidx.compose.animation.core.LinearEasing)), label = "x").value
    val bob = if (Motion.reduced) 0f else androidx.compose.animation.core.rememberInfiniteTransition(label = "nib").animateFloat(0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(700, easing = androidx.compose.animation.core.FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse), label = "b").value
    Column(modifier.semantics { contentDescription = "正在生成" }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.graphicsLayer { translationY = -bob * 6f; rotationZ = -8f + bob * 6f }) { SvgIcon(Ic.edit, P3.accent, 34.dp) }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.width(150.dp).height(6.dp).clip(Sq.full).background(Color(0x1A2E5E6B)).drawWithContent {
            drawContent()
            val w = size.width * .35f; val sx = size.width * x - w / 2
            drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0x002E5E6B), Color(0xAA7FA9B3), Color(0x002E5E6B)), sx, sx + w), androidx.compose.ui.geometry.Offset(sx, 0f), androidx.compose.ui.geometry.Size(w, size.height))
        })
    }
}
