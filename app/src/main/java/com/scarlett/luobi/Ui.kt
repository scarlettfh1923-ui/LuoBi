@file:OptIn(ExperimentalLayoutApi::class)
package com.scarlett.luobi

import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.math.roundToInt

// ---------- 全局提示（Snackbar） ----------
class SnackMsg(override val message: String, val ok: Boolean, override val actionLabel: String? = null, val onAction: (() -> Unit)? = null, val sub: String? = null, val busy: Boolean = false) : SnackbarVisuals {
    override val withDismissAction = false
    override val duration = if (busy) SnackbarDuration.Indefinite else if (actionLabel != null || !ok || message.length > 30) SnackbarDuration.Long else SnackbarDuration.Short
}
object Snack { val flow = MutableSharedFlow<SnackMsg>(extraBufferCapacity = 8) }
private val errWords = listOf("失败", "错误", "请先", "无效", "超时", "不通", "已存在", "未检测")
fun toast(c: Context, s: String) { Snack.flow.tryEmit(SnackMsg(s, errWords.none { s.contains(it) })) }
fun snack(s: String, ok: Boolean = true, action: String? = null, onAction: (() -> Unit)? = null) { Snack.flow.tryEmit(SnackMsg(s, ok, action, onAction)) }
/** 进行中提示（转圈），下一条提示出现时自动消失 */
fun snackBusy(s: String) { Snack.flow.tryEmit(SnackMsg(s, true, busy = true)) }
/** 失败提示：中文说明 + 小字原始信息 */
fun snackErr(e: Throwable, prefix: String = "", action: String? = null, onAction: (() -> Unit)? = null) {
    val f = Errors.explain(e)
    Snack.flow.tryEmit(SnackMsg(prefix + f.title + "\n" + f.detail, false, action, onAction, f.raw.takeIf { it.isNotBlank() }?.let { "原始信息：" + it.take(160) }))
}

/** 提示样式 A：底部深色条 + 白字 + 浅蓝动作 */
@Composable
fun SoftSnackbar(d: SnackbarData) {
    val m = d.visuals as? SnackMsg
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF111827)).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        when {
            m?.busy == true -> androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
            m?.ok != false -> SvgIcon(Ic.check, Color.White, 20.dp)
            else -> SvgIcon(Ic.close, Color(0xFFF2A190), 20.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(d.visuals.message, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
            m?.sub?.let { Text(it, Modifier.padding(top = 3.dp), color = Color(0xFF9CA3AF), fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        }
        d.visuals.actionLabel?.let { a -> Text(a, Modifier.padding(start = 10.dp).clickable { m?.onAction?.invoke(); d.performAction() }, color = Color(0xFF9DB8FF), fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
    }
}

@Composable
fun Ico(res: Int, tint: Color, size: Int = 24, desc: String? = null) = Icon(painterResource(res), desc, Modifier.size(size.dp), tint = tint)

// ---------- 自定义细滑杆 ----------
@Composable
fun SoftSlider(value: Float, onChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, step: Float, accent: Color, track: Color, thumb: Color, modifier: Modifier = Modifier, onChangeFinished: ((Float) -> Unit)? = null) {
    var w by remember { mutableIntStateOf(1) }
    val cb by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onChangeFinished)
    var last by remember { mutableFloatStateOf(value) }
    fun at(x: Float): Float {
        val f = (x / w).coerceIn(0f, 1f); val raw = range.start + f * (range.endInclusive - range.start)
        val v = ((raw - range.start) / step).roundToInt() * step + range.start
        val snapped = (v * 100).roundToInt() / 100f
        last = snapped; cb(snapped); return snapped
    }
    val frac = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    Canvas(modifier.height(32.dp).fillMaxWidth().onSizeChanged { w = it.width.coerceAtLeast(1) }
        .pointerInput(range) { detectTapGestures { val v = at(it.x); done?.invoke(v) } }
        .pointerInput(range) {
            detectHorizontalDragGestures(
                onDragEnd = { done?.invoke(last) },
                onDragCancel = { done?.invoke(last) },
                onHorizontalDrag = { ch, _ -> at(ch.position.x) },
            )
        }) {
        val y = size.height / 2; val r = 10.dp.toPx(); val x = r + frac * (size.width - 2 * r)
        drawLine(track, Offset(r, y), Offset(size.width - r, y), 4.dp.toPx(), StrokeCap.Round)
        drawLine(accent, Offset(r, y), Offset(x, y), 4.dp.toPx(), StrokeCap.Round)
        drawCircle(Color(0x22000000), r + 1.dp.toPx(), Offset(x, y + 1.dp.toPx()))
        drawCircle(thumb, r, Offset(x, y))
        drawCircle(accent, r - 1.dp.toPx(), Offset(x, y), style = Stroke(2.dp.toPx()))
    }
}

// ---------- 分段控件 ----------
@Composable
fun Segmented(options: List<String>, sel: Int, onSel: (Int) -> Unit, track: Color, on: Color, accent: Color, sub: Color) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(track).padding(3.dp)) {
        options.forEachIndexed { i, l ->
            val bg by animateColorAsState(if (i == sel) on else Color.Transparent, label = "seg")
            Text(l, Modifier.clip(RoundedCornerShape(50)).background(bg).clickable { onSel(i) }.padding(horizontal = 18.dp, vertical = 6.dp),
                color = if (i == sel) accent else sub, fontSize = 15.sp, fontWeight = if (i == sel) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

// ---------- 悬浮胶囊 ----------
@Composable
fun Capsule(container: Color, border: Color, fade: Color, content: @Composable RowScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(fade.copy(alpha = 0f), fade, fade)))) {
        Surface(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp).height(60.dp),
            shape = RoundedCornerShape(50), color = container, shadowElevation = 8.dp, border = BorderStroke(1.dp, border)) {
            Row(Modifier.fillMaxSize().padding(start = 10.dp, end = 7.dp), verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
}

@Composable
fun TagEditor(tags: List<String>, onTags: (List<String>) -> Unit, accent: Color, bg: Color, text: Color, dark: Boolean, collapsible: Boolean) {
    val ctx = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    var newTag by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(!collapsible) }
    var editTags by remember { mutableStateOf(!collapsible) }
    fun commit() {
        val add = newTag.split(" ", ",", "，", "#").map { it.trim() }.filter { it.isNotEmpty() }
        val dup = add.filter { it in tags }; val fresh = add.filter { it !in tags }.distinct()
        if (dup.isNotEmpty()) toast(ctx, "「${dup.joinToString("、")}」已存在")
        if (fresh.isNotEmpty()) onTags(tags + fresh)
        newTag = ""
    }
    val show = if (expanded || tags.size <= 6) tags else tags.take(6)
    val chipBg = accent.copy(alpha = if (dark) .2f else .12f)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        show.forEach { t ->
            // 1.4：轻量标签——平时是纯蓝字，进入编辑（点「编辑」）才显示删除
            Row(Modifier.height(28.dp).clip(RoundedCornerShape(50)).then(if (editTags) Modifier.background(chipBg) else Modifier).padding(start = if (editTags) 10.dp else 2.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("#$t", fontSize = 13.sp, color = accent)
                if (editTags) Box(Modifier.size(26.dp).clip(CircleShape).clickable { onTags(tags - t) }, contentAlignment = Alignment.Center) { Ico(R.drawable.ic_x, accent, 12, "删除标签") }
            }
        }
        if (!expanded && tags.size > 6) {
            Text("+${tags.size - 6}", Modifier.height(28.dp).clip(RoundedCornerShape(50)).border(1.dp, accent.copy(alpha = .5f), RoundedCornerShape(50)).clickable { expanded = true }.padding(horizontal = 12.dp, vertical = 5.dp), fontSize = 12.sp, color = accent)
        } else if (adding) {
            Row(Modifier.height(28.dp).clip(RoundedCornerShape(50)).background(bg).border(1.5.dp, accent, RoundedCornerShape(50)).padding(start = 10.dp, end = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("#", color = accent, fontSize = 13.sp)
                val fr = remember { FocusRequester() }
                LaunchedEffect(Unit) { fr.requestFocus() }
                BasicTextField(newTag, { newTag = it.trimStart('#') }, Modifier.widthIn(min = 60.dp, max = 170.dp).focusRequester(fr), singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = text), cursorBrush = SolidColor(accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { commit(); adding = false }))
                Box(Modifier.padding(start = 4.dp).size(22.dp).clip(CircleShape).background(accent).clickable { commit(); adding = false }, contentAlignment = Alignment.Center) { Ico(R.drawable.ic_enter, Color.White, 14, "添加") }
            }
        } else if (!editTags) {
            Text("编辑", Modifier.height(28.dp).clip(RoundedCornerShape(50)).clickable { editTags = true }.padding(horizontal = 6.dp, vertical = 5.dp), fontSize = 13.sp, color = text.copy(alpha = .45f))
        } else {
            Row(Modifier.height(28.dp).clip(RoundedCornerShape(50)).border(1.dp, accent.copy(alpha = .6f), RoundedCornerShape(50)).clickable { adding = true }.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Ico(R.drawable.ic_plus, accent, 13); Spacer(Modifier.width(3.dp)); Text("添加标签", fontSize = 12.sp, color = accent)
            }
            if (collapsible) Text("完成", Modifier.height(28.dp).clip(RoundedCornerShape(50)).clickable { editTags = false }.padding(horizontal = 8.dp, vertical = 5.dp), fontSize = 13.sp, color = accent, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---------- 状态组件 ----------
@Composable
fun SkeletonCard(widths: Pair<Float, Float>) {
    val a by rememberInfiniteTransition(label = "sk").animateFloat(.45f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    val c = MaterialTheme.colorScheme
    Card(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
        Box(Modifier.alpha(a).fillMaxWidth(widths.first).height(14.dp).clip(RoundedCornerShape(7.dp)).background(c.surfaceContainerHigh))
        Spacer(Modifier.height(10.dp))
        Box(Modifier.alpha(a).fillMaxWidth(widths.second).height(11.dp).clip(RoundedCornerShape(6.dp)).background(c.surfaceContainer))
    }
}

@Composable
fun ErrorCard(f: Friendly, onRetry: (() -> Unit)?, onSettings: (() -> Unit)?, onClose: () -> Unit) {
    val red = Color(0xFFA23A2C)
    val title = f.title; val detail = f.detail
    var rawOpen by remember(f) { mutableStateOf(false) }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFFFDF1EE)).border(1.dp, Color(0xFFF2C9C0), RoundedCornerShape(20.dp)).padding(14.dp)) {
        Ico(R.drawable.ic_xcircle, red, 22)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = red, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(detail, color = Color(0xFF8A4A3E), fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 2.dp))
            if (f.raw.isNotBlank()) Text("原始信息：${f.raw}", Modifier.padding(top = 6.dp).clickable { rawOpen = !rawOpen }, color = Color(0xFF9A8A7C), fontSize = 11.sp, lineHeight = 15.sp,
                maxLines = if (rawOpen) 12 else 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onRetry != null) Text("重试", Modifier.clip(RoundedCornerShape(50)).background(red).clickable { onRetry() }.padding(horizontal = 14.dp, vertical = 6.dp), color = Color.White, fontSize = 13.sp)
                if (onSettings != null) Text("去设置", Modifier.clip(RoundedCornerShape(50)).border(1.dp, Color(0xFFE7B3A8), RoundedCornerShape(50)).clickable { onSettings() }.padding(horizontal = 14.dp, vertical = 5.dp), color = red, fontSize = 13.sp)
            }
        }
        Box(Modifier.size(28.dp).clip(CircleShape).clickable { onClose() }, contentAlignment = Alignment.Center) { Ico(R.drawable.ic_x, red.copy(alpha = .6f), 16, "关闭") }
    }
}

@Composable
fun EmptyState(icon: Int, title: String, sub: String, button: String?, onClick: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Ico(icon, MaterialTheme.colorScheme.outline, 56)
        Text(title, Modifier.padding(top = 10.dp), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(sub, Modifier.padding(top = 4.dp), fontSize = 13.sp, color = t3(), textAlign = TextAlign.Center, lineHeight = 19.sp)
        if (button != null) Button(onClick, Modifier.padding(top = 14.dp), shape = RoundedCornerShape(50)) { Text(button) }
    }
}

fun isHead(p: String, total: Int): Boolean = p.startsWith("#") || Export.isHeadLine(p, total)
fun headText(p: String) = p.trimStart('#', ' ').trim()
fun readMinutes(len: Int) = (len / 300).coerceAtLeast(1)
