package com.scarlett.luobi

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 1.18 方案 C：创作页「灵感」上方的后台生成卡。
 * 进行中：写作 · 第 n 步 / 共 3 步、实时字数、正在写：标题、一行打字（左侧渐隐 + 蓝色光标）、细进度线、× 取消
 * 写好：已写好 · 3,420 字 · 已放进案头、标题、去看看
 * 看过这篇再回来：先淡出（200ms）再收起高度（280ms，标准曲线），下面的「灵感」平滑上移
 */
@Composable
fun GenCardSlot(prog: GenProgress?, onCancel: () -> Unit, onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    val card by GenCard.state.collectAsState()
    var lastProg by remember { mutableStateOf(prog) }
    if (prog != null) lastProg = prog
    val vs = remember { MutableTransitionState(prog != null || card != null) }
    LaunchedEffect(prog != null, card) {
        val c = card
        when {
            prog != null -> vs.targetState = true
            c == null -> vs.targetState = false
            c.viewed -> { delay(if (Motion.reduced) 0L else 320L); vs.targetState = false }  // 回到创作页先停一下，让她看到它离开
            else -> vs.targetState = true
        }
    }
    LaunchedEffect(vs.isIdle, vs.currentState) { if (vs.isIdle && !vs.currentState && card?.viewed == true) GenCard.clear(ctx) }
    AnimatedVisibility(vs, enter = Motion.cardIn, exit = Motion.collapseOut) {
        Column(Modifier.testTag("genCard").padding(horizontal = DS.Space.page, vertical = 6.dp).fillMaxWidth().frosted(Sq.card26, DS.Elev.card)) {
            val c = card
            AnimatedContent(if (prog == null && c != null) 1 else 0, transitionSpec = { Motion.fadeIn togetherWith Motion.fadeOut using SizeTransform(clip = false) }, label = "gen") { st ->
                if (st == 1 && c != null) GenDone(c) { GenCard.clear(ctx); onOpen(c.noteId) }
                else GenRunning(prog ?: lastProg ?: GenProgress(), onCancel)
            }
        }
    }
}

@Composable
private fun GenRunning(p: GenProgress, onCancel: () -> Unit) {
    Column(Modifier.padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(Sq.full).background(DS.accent))
            Text("写作", Modifier.padding(start = 6.dp), fontSize = DS.Type.sub, fontWeight = FontWeight.SemiBold, color = DS.accent)
            // 2.3：阶段条 定提纲 · 成文 [· 校对]，当前阶段高亮
            Text(" · ", fontSize = DS.Type.caption, color = P3.text3, maxLines = 1)
            p.stageNames.forEachIndexed { i, n ->
                if (i > 0) Text(" · ", fontSize = DS.Type.caption, color = P3.text3, maxLines = 1)
                val on = i + 1 == p.stage && p.step < 3
                Text((if (i + 1 < p.stage || p.step >= 3) "✓" else "") + n, fontSize = DS.Type.caption, maxLines = 1,
                    color = if (on) P3.accent else P3.text3, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
            Spacer(Modifier.weight(1f))
            Text("${CopyWorker.fmtWords(p.words)} 字", Modifier.testTag("genWords"), fontSize = DS.Type.callout, fontWeight = FontWeight.SemiBold, color = P3.text)
            Box(Modifier.padding(start = 6.dp).size(32.dp).clip(Sq.full).clickable(onClick = onCancel).testTag("genCancel"), contentAlignment = Alignment.Center) {
                Box(Modifier.size(24.dp).clip(Sq.full).background(Color(0x142A2016)), contentAlignment = Alignment.Center) { Text("×", fontSize = 15.sp, color = P3.text2, lineHeight = 15.sp) }
            }
        }
        val head = when { p.step >= 3 -> "正在整理，放进案头…"; p.stage == 1 -> "正在定提纲…"; p.stage == 3 -> "正在校对：${p.title}"; p.title.isNotBlank() -> "正在写：${p.title}"; else -> "正在构思标题…" }
        Text(head, Modifier.padding(top = 2.dp, end = 8.dp), fontSize = DS.Type.headline, fontWeight = FontWeight.SemiBold, color = P3.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = 6.dp, end = 8.dp).height(20.dp), verticalAlignment = Alignment.CenterVertically) {
            // 只显示最后打出来的那一截：右对齐 + 左侧渐隐
            Box(Modifier.weight(1f, fill = false).graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
                drawContent(); drawRect(Brush.horizontalGradient(0f to Color.Transparent, .18f to Color.Black), blendMode = BlendMode.DstIn)
            }) {
                Text(if (p.line.isBlank()) "……" else "……" + p.line.takeLast(40), fontSize = DS.Type.sub, color = P3.text3, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, textAlign = TextAlign.End)
            }
            Cursor()
        }
        val target = when { p.step >= 3 -> .96f; p.stage == 1 -> .08f; p.stage == 3 -> .9f
            else -> { val span = if (p.stages == 3) .65f else .75f; .15f + span * (p.words / 3000f).coerceAtMost(1f) } }
        val f by animateFloatAsState(target, if (Motion.reduced) snap() else tween(DS.Dur.LONG, easing = DS.Standard), label = "genP")
        Box(Modifier.padding(top = 10.dp, end = 8.dp).fillMaxWidth().height(3.dp).clip(Sq.full).background(Color(0x122A2016))) {
            Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(Sq.full).background(DS.accent))
        }
    }
}

@Composable
private fun Cursor() {
    val a = if (Motion.reduced) 1f else {
        val t = rememberInfiniteTransition(label = "cur")
        t.animateFloat(1f, 0f, infiniteRepeatable(tween(530, easing = { if (it < .5f) 0f else 1f }), RepeatMode.Reverse), label = "blink").value
    }
    Box(Modifier.padding(start = 2.dp).size(2.dp, 16.dp).graphicsLayer { alpha = a }.clip(Sq.full).background(DS.accent))
}

@Composable
private fun GenDone(c: GenCardState, onOpen: () -> Unit) {
    Column {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已写好", fontSize = DS.Type.sub, fontWeight = FontWeight.SemiBold, color = DS.accent)
                Spacer(Modifier.weight(1f))
                Text("${CopyWorker.fmtWords(c.words)} 字 · 已放进案头", fontSize = DS.Type.caption, color = P3.text3, maxLines = 1)
            }
            Text(c.title, Modifier.padding(top = 4.dp), fontSize = DS.Type.headline, fontWeight = FontWeight.SemiBold, color = P3.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(thickness = Dp.Hairline, color = Color(0x1F3C3428))
        Box(Modifier.fillMaxWidth().height(44.dp).clickable(onClick = onOpen).testTag("genOpen"), contentAlignment = Alignment.Center) {
            Text("去看看", fontSize = DS.Type.callout, fontWeight = FontWeight.SemiBold, color = DS.accent)
        }
    }
}
