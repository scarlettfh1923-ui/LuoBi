package com.scarlett.luobi

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 2.1 事实校对页（方案 A）：分拣 → 逐条流式结果 → 底部弹层逐条修 → 风格把关 */
class FactView(
    val claims: List<FactClaim>, val triage: FactTriage?, val running: Boolean,
    /** 停下 / 出错时的说明；null = 正常 */
    val stopped: String?, val report: FactReport?,
)

private val Pale = Color(0xFFB8BFCC)
private val EvBg = Color(0xFFF1F2F5)
private val StrikeBg = Color(0x14000000)

@Composable
fun FactScreen(
    v: FactView, title: String, body: String,
    onBack: () -> Unit, onStop: () -> Unit, onReCheck: () -> Unit,
    /** 只改报告 */
    onReport: (FactReport) -> Unit,
    /** 改正文 + 报告（一起写库） */
    onApply: (String, String, FactReport) -> Unit,
    onManual: (FactClaim) -> Unit,
    llm: suspend (String, String) -> String,
    startStyle: Boolean = false, openClaim: String? = null,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onBack, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CompositionLocalProvider(LocalBackdrop provides null) {
            FactScreenBody(v, title, body, onBack, onStop, onReCheck, onReport, onApply, onManual, llm, startStyle, openClaim)
        }
    }
}

@Composable
fun FactScreenBody(
    v: FactView, title: String, body: String,
    onBack: () -> Unit, onStop: () -> Unit, onReCheck: () -> Unit,
    onReport: (FactReport) -> Unit, onApply: (String, String, FactReport) -> Unit, onManual: (FactClaim) -> Unit,
    llm: suspend (String, String) -> String, startStyle: Boolean = false, openClaim: String? = null,
) {
    val scope = rememberCoroutineScope()
    var page by remember { mutableIntStateOf(if (startStyle) 1 else 0) }
    var sheet by remember { mutableStateOf(openClaim) }
    var toast by remember { mutableStateOf<String?>(null) }
    var restoreAsk by remember { mutableStateOf(false) }
    var stopAsk by remember { mutableStateOf(false) }
    // 本地副本：连续采纳时不等 Room 回流
    var curTitle by remember { mutableStateOf(title) }; var curBody by remember { mutableStateOf(body) }
    LaunchedEffect(title, body) { curTitle = title; curBody = body }
    var rep by remember { mutableStateOf(v.report) }
    LaunchedEffect(v.report) { rep = v.report }
    LaunchedEffect(toast) { if (toast != null) { delay(2600); toast = null } }
    val claims = rep?.takeIf { !v.running }?.claims ?: v.claims
    fun say(s: String) { toast = s }
    fun commitReport(r: FactReport) { rep = r; onReport(r) }
    fun commitApply(t: String, b: String, r: FactReport) { curTitle = t; curBody = b; rep = r; onApply(t, b, r) }
    fun nextProblem(after: String?): String? {
        val ps = (rep?.claims ?: claims).filter { FactFix.isProblem(it) }
        val i = ps.indexOfFirst { it.id == after }
        return (ps.drop(i + 1) + ps.take(i + 1)).firstOrNull { it.id != after && FactFix.pending(it) }?.id
    }
    androidx.activity.compose.BackHandler { when { sheet != null -> sheet = null; page == 1 -> page = 0; else -> onBack() } }

    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFEEF1F6), Color(0xFFF6F7F9), Color(0xFFECEFF4))))) {
        AnimatedContent(page, transitionSpec = { if (targetState > initialState) Motion.push() else Motion.pop() }, label = "factPage") { pg ->
            if (pg == 0) FactList(v, claims, rep, onBack = onBack, onStop = { stopAsk = true }, onReCheck = onReCheck, onOpen = { sheet = it.id },
                onStyle = { page = 1 }, onRestore = { restoreAsk = true })
            else rep?.let { r -> StylePass(r, curTitle, curBody, llm, onBack = { page = 0 }, onReport = ::commitReport, onApply = ::commitApply, say = ::say, onRestore = { restoreAsk = true }) }
        }
        // ---- 底部弹层 ----
        val open = claims.firstOrNull { it.id == sheet }
        AnimatedVisibility(open != null, enter = Motion.fadeIn, exit = Motion.fadeOut) {
            Box(Modifier.fillMaxSize().background(DS.scrim).clickable(remember { MutableInteractionSource() }, null) { sheet = null })
        }
        var last by remember { mutableStateOf<FactClaim?>(null) }
        if (open != null) last = open
        AnimatedVisibility(open != null, Modifier.align(Alignment.BottomCenter), enter = Motion.sheetIn, exit = Motion.sheetOut) {
            last?.let { c ->
                val ps = claims.filter { FactFix.isProblem(it) }
                FixSheet(c, ps.indexOfFirst { it.id == c.id }, ps.size, rep, v.running, llm,
                    onClose = { sheet = null },
                    onAdopt = { text ->
                        val r = rep ?: return@FixSheet
                        val res = FactFix.adopt(r, c, curTitle, curBody, text)
                        if (res == null) say("原句已经改过，找不到了 · 可以点「手动改」")
                        else { commitApply(res.first.title, res.first.body, res.second); sheet = nextProblem(c.id); if (sheet == null) say("问题都处理完了 · 可以做风格把关") }
                    },
                    onIgnore = { rep?.let { r -> commitReport(FactFix.withClaim(r, c.copy(fix = FactFix.IGNORED))) }; sheet = nextProblem(c.id) },
                    onAlts = { c2 -> rep?.let { r -> commitReport(FactFix.withClaim(r, c2)) } },
                    onManual = { rep?.let { r -> commitReport(FactFix.withClaim(r, c.copy(fix = FactFix.MANUAL))) }; sheet = null; onManual(c) },
                    onRestore = { restoreAsk = true }, say = ::say)
            }
        }
        AnimatedVisibility(toast != null, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp), enter = Motion.fadeIn, exit = Motion.fadeOut) {
            Text(toast.orEmpty(), Modifier.padding(horizontal = 24.dp).clip(Sq.full).background(DS.ink.copy(alpha = .92f)).padding(horizontal = 16.dp, vertical = 10.dp).testTag("factToast"), color = Color.White, fontSize = 13.sp)
        }
        if (stopAsk) GlassAlert("停止校对？", "已查完的 ${claims.count { it.done }}/${claims.size} 条会保留在列表里。", listOf(
            AlertAction("停止", AlertAction.Role.Destructive) { onStop() }, AlertAction("继续校对", AlertAction.Role.Cancel) {}), { stopAsk = false })
        if (restoreAsk) rep?.snap?.let { s ->
            GlassAlert("还原到校对前？", "回到 ${hhmm(s.at)} 校对前的版本，这次校对后的改动都会撤掉。", listOf(
                AlertAction("还原", AlertAction.Role.Destructive) {
                    val r = rep ?: return@AlertAction
                    FactFix.restore(r)?.let { (a, r2) -> commitApply(a.title, a.body, r2); page = 0; sheet = null; say("已还原到校对前的版本") } },
                AlertAction("取消", AlertAction.Role.Cancel) {}), { restoreAsk = false })
        }
    }
}

fun hhmm(t: Long): String {
    val now = java.util.Calendar.getInstance(); val c = java.util.Calendar.getInstance().apply { timeInMillis = t }
    val same = now.get(java.util.Calendar.YEAR) == c.get(java.util.Calendar.YEAR) && now.get(java.util.Calendar.DAY_OF_YEAR) == c.get(java.util.Calendar.DAY_OF_YEAR)
    return java.text.SimpleDateFormat(if (same) "HH:mm" else "M月d日 HH:mm", java.util.Locale.CHINA).format(java.util.Date(t))
}

@Composable
private fun Header(title: String, onBack: () -> Unit, right: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().height(52.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTap(onClick = onBack) { SvgIcon(Ic.back, P3.text, 22.dp, desc = "返回") }
        Text(title, Modifier.weight(1f), fontSize = DS.Type.headline, fontWeight = FontWeight.SemiBold, color = P3.text)
        right()
    }
}

@Composable
private fun Chip(t: String, filled: Boolean = false) =
    Text(t, Modifier.clip(Sq.s).background(if (filled) P3.accent else Color(0xFFEDEFF3)).padding(horizontal = 8.dp, vertical = 3.dp),
        color = if (filled) Color.White else P3.text2, fontSize = DS.Type.micro, fontWeight = FontWeight.Medium, maxLines = 1)

@Composable
private fun Progress(label: String, d: Int, n: Int) = Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(label, fontSize = DS.Type.sub, color = P3.text2)
    val f by animateFloatAsState(if (n == 0) 0f else d.toFloat() / n, Motion.enter(), label = "p")
    Box(Modifier.padding(horizontal = 10.dp).weight(1f).height(4.dp).clip(Sq.full).background(Color(0xFFE3E6EC))) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(Sq.full).background(P3.accent))
    }
    Text("$d/$n", fontSize = DS.Type.sub, color = P3.text2, fontWeight = FontWeight.Medium)
}

private fun Modifier.glass(shape: androidx.compose.ui.graphics.Shape = Sq.l) = this.frosted(shape, DS.Elev.card)

/** 一行的状态文字 */
data class RowStat(val text: String, val color: Color, val bold: Boolean)
fun rowStat(c: FactClaim, waitIdx: Int, running: Boolean): RowStat = when {
    !c.done && running && waitIdx < 4 -> RowStat("核查中…", P3.text2, false)
    !c.done && running -> RowStat("排队", Pale, false)
    !c.done -> RowStat("未查完", Pale, false)
    c.fix == FactFix.APPLIED -> RowStat("✓ 已采纳", P3.accent, false)
    c.fix == FactFix.IGNORED -> RowStat("已忽略", P3.text3, false)
    c.fix == FactFix.MANUAL -> RowStat("手动改", P3.text3, false)
    else -> when (FactFix.verdictOf(c)) {
        FactPipeline.BAD -> RowStat("● 有出入", P3.accent, true)
        FactPipeline.PART -> RowStat("● 部分对", P3.accent, true)
        FactPipeline.UNK -> RowStat("○ 无法核实", P3.text2, false)
        else -> RowStat("✓ 一致", P3.text3, false)
    }
}

@Composable
private fun FactList(v: FactView, claims: List<FactClaim>, rep: FactReport?, onBack: () -> Unit, onStop: () -> Unit, onReCheck: () -> Unit,
                     onOpen: (FactClaim) -> Unit, onStyle: () -> Unit, onRestore: () -> Unit) {
    val done = claims.count { it.done }
    val problems = claims.filter { FactFix.isProblem(it) }
    val left = problems.count { FactFix.pending(it) }
    Column(Modifier.fillMaxSize()) {
        Header("事实校对", onBack) {
            if (v.running) TextAction("停止") { onStop() } else TextAction("重新检测") { onReCheck() }
        }
        LazyColumn(Modifier.weight(1f).testTag("factList"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("tri") {
                Column(Modifier.fillMaxWidth().glass().padding(horizontal = 18.dp, vertical = 16.dp)) {
                    val t = v.triage ?: rep?.triage
                    if (t == null && claims.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                        if (v.running) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = P3.accent); Spacer(Modifier.width(10.dp)) }
                        Text(if (v.running) "正在分拣句子：硬事实 / 观点 / 描写…" else "没有结果", fontSize = DS.Type.callout, color = P3.text2)
                    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = if (t != null && t.opinions + t.descriptions > 0) Arrangement.SpaceBetween else Arrangement.Start) {
                        // 硬事实以实际列表为准（模型分拣数可能和最终说法数对不上）
                        Tri(claims.size.takeIf { it > 0 } ?: t?.facts ?: 0, "条硬事实", true)
                        if (t != null && t.opinions + t.descriptions > 0) { Tri(t.opinions, "句观点", false); Tri(t.descriptions, "句描写", false) }
                    }
                    Text("观点和描写不查 · 数字、引语优先", Modifier.padding(top = 10.dp), fontSize = DS.Type.caption, color = P3.text3)
                }
            }
            v.stopped?.let { m -> item("stop") {
                Row(Modifier.fillMaxWidth().glass().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(m, Modifier.weight(1f), fontSize = DS.Type.sub, color = P3.text2)
                    TextAction("继续") { onReCheck() }
                }
            } }
            if (claims.isNotEmpty()) item("prog") { Progress("已查", done, claims.size) }
            if (claims.isNotEmpty()) item("list") {
                var waitIdx = 0
                Column(Modifier.fillMaxWidth().glass()) {
                    claims.forEachIndexed { i, c ->
                        val st = rowStat(c, if (!c.done) waitIdx++ else 0, v.running)
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = Hairline)
                        Row(Modifier.fillMaxWidth().clickable(enabled = c.done) { onOpen(c) }.padding(horizontal = 16.dp, vertical = 13.dp).testTag("factRow_${c.id}"), verticalAlignment = Alignment.CenterVertically) {
                            Chip(FactPipeline.kindOf(c))
                            Text(c.span, Modifier.weight(1f).padding(horizontal = 10.dp), fontSize = DS.Type.callout, color = if (c.done) P3.text else P3.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(st.text, fontSize = DS.Type.caption, color = st.color, fontWeight = if (st.bold) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                        }
                    }
                }
            }
            if (!v.running && rep != null && claims.isEmpty()) item("empty") {
                Text("这篇没有需要核查的硬事实（数字、日期、人物、机构、引语、事件）", Modifier.fillMaxWidth().padding(24.dp), fontSize = DS.Type.callout, color = P3.text2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            item("foot") { Text("每条结论附来源原文；查不到证据的标为无法核实", Modifier.fillMaxWidth().padding(top = 4.dp), fontSize = DS.Type.caption, color = P3.text3, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
        // ---- 底栏：处理进度 / 风格把关 / 还原 ----
        if (!v.running && rep != null && (problems.isNotEmpty() || rep.snap != null)) Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (left > 0) PrimaryButton("逐条处理 · 还有 $left 条", Modifier.testTag("factNext")) { problems.firstOrNull { FactFix.pending(it) }?.let(onOpen) }
            else if (rep.edits.isNotEmpty()) PrimaryButton(if (rep.styleRan) "风格把关 · 查看建议" else "风格把关 · 通读改过的地方", Modifier.testTag("factStyle")) { onStyle() }
            rep.snap?.let { SnapLine(it, onRestore) }
        } else Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun Tri(n: Int, label: String, strong: Boolean) = Row(verticalAlignment = Alignment.Bottom) {
    Text("$n", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = if (strong) P3.accent else P3.text)
    Text(" $label", Modifier.padding(bottom = 3.dp), fontSize = DS.Type.sub, color = P3.text2)
}

@Composable
private fun SnapLine(s: FactFix.Snap, onRestore: () -> Unit) = Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    SvgIcon(Ic.undo, P3.text3, 15.dp)
    Text("已存校对前版本 · ${hhmm(s.at)}", Modifier.weight(1f).padding(start = 6.dp), fontSize = DS.Type.caption, color = P3.text3)
    Text("一键还原", Modifier.clip(Sq.s).clickable(onClick = onRestore).padding(horizontal = 6.dp, vertical = 4.dp).testTag("factRestore"), fontSize = DS.Type.caption, color = P3.accent, fontWeight = FontWeight.Medium)
}

/** 原句 → 新写法：删的灰底划线，加的强调色加粗浅底 */
@Composable
fun DiffText(old: String, new: String, size: androidx.compose.ui.unit.TextUnit = DS.Type.callout, context: Int = 18) {
    val d0 = FactFix.diff(old, new)
    // 长句只留改动前后一小段上下文
    val d = d0.copy(head = if (d0.head.length > context) "…" + d0.head.takeLast(context) else d0.head, tail = if (d0.tail.length > context) d0.tail.take(context) + "…" else d0.tail)
    Text(buildAnnotatedString {
        append(d.head)
        if (d.old.isNotEmpty()) withStyle(SpanStyle(color = P3.text3, background = StrikeBg, textDecoration = TextDecoration.LineThrough)) { append(d.old) }
        if (d.new.isNotEmpty()) withStyle(SpanStyle(color = P3.accent, background = P3.accentSoft, fontWeight = FontWeight.Bold)) { append(d.new) }
        append(d.tail)
    }, fontSize = size, lineHeight = (size.value * 1.6f).sp, color = P3.text)
}

@Composable
private fun Label(t: String) = Text(t, Modifier.padding(top = 14.dp, bottom = 4.dp), fontSize = DS.Type.caption, color = P3.text3, fontWeight = FontWeight.Medium)

@Composable
private fun GlassBtn(t: String, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) =
    Box(modifier.height(44.dp).clip(Sq.m).background(Color.White).border(0.5.dp, Hairline, Sq.m).clickable(enabled = enabled && !busy, onClick = onClick), contentAlignment = Alignment.Center) {
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = P3.accent)
        else Text(t, fontSize = DS.Type.callout, fontWeight = FontWeight.Medium, color = if (enabled) P3.text else P3.text3)
    }

@Composable
private fun AccentBtn(t: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    Box(modifier.height(44.dp).clip(Sq.m).background(if (enabled) P3.accent else Color(0xFFE5E7EB)).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(t, fontSize = DS.Type.callout, fontWeight = FontWeight.SemiBold, color = if (enabled) Color.White else P3.text3)
    }

@Composable
private fun FixSheet(c: FactClaim, idx: Int, total: Int, rep: FactReport?, running: Boolean, llm: suspend (String, String) -> String,
                     onClose: () -> Unit, onAdopt: (String) -> Unit, onIgnore: () -> Unit, onAlts: (FactClaim) -> Unit, onManual: () -> Unit,
                     onRestore: () -> Unit, say: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val v = FactFix.verdictOf(c)
    val d = FactFix.keyDetail(c)
    val ev = FactFix.evidence(c)
    val problem = FactFix.isProblem(c)
    var pick by remember(c.id) { mutableStateOf(FactFix.suggestion(c)) }
    LaunchedEffect(c.id, c.fixText, c.alts) { if (pick.isBlank()) pick = FactFix.suggestion(c) }
    var busy by remember(c.id) { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    Column(Modifier.fillMaxWidth().offset { IntOffset(0, drag.roundToInt().coerceAtLeast(0)) }.clip(Sq.topXl).background(P3.surface)
        .clickable(remember { MutableInteractionSource() }, null) {}.navigationBarsPadding().testTag("factSheet")) {
        Box(Modifier.fillMaxWidth().height(22.dp).draggable(rememberDraggableState { drag += it }, Orientation.Vertical,
            onDragStopped = { if (drag > 120f) onClose(); drag = 0f }), contentAlignment = Alignment.Center) {
            Box(Modifier.size(36.dp, 5.dp).clip(Sq.full).background(Color(0xFFD5D9E0)))
        }
        val maxH = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * .86f).dp
        Column(Modifier.fillMaxWidth().heightIn(max = maxH)) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip(FactPipeline.kindOf(c))
                val st = rowStat(c, 0, false)
                Text(st.text, Modifier.padding(start = 8.dp).weight(1f), fontSize = DS.Type.sub, color = st.color, fontWeight = if (st.bold) FontWeight.SemiBold else FontWeight.Normal)
                if (problem && total > 0) Text("${idx + 1} / $total 问题", fontSize = DS.Type.caption, color = P3.text3)
            }
            Label("原句")
            Text(c.span, fontSize = DS.Type.body, lineHeight = 24.sp, color = P3.text)
            if (problem) {
                Label(if (v == FactPipeline.UNK) "为什么查不到" else "错在哪")
                Text(d?.reason?.ifBlank { null } ?: if (v == FactPipeline.UNK) "没有找到能直接印证这句的公开资料" else "资料与原句不一致",
                    fontSize = DS.Type.callout, lineHeight = 22.sp, color = P3.text)
            }
            if (ev != null) Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(Sq.m).background(EvBg).padding(12.dp)) {
                Text("“${ev.quote}”", fontSize = DS.Type.sub, lineHeight = 20.sp, color = P3.text2)
                Text("来源 · ${ev.sourceTitle.ifBlank { android.net.Uri.parse(ev.url).host.orEmpty() }} ↗", Modifier.padding(top = 6.dp).clip(Sq.s)
                    .clickable { runCatching { uri.openUri(ev.url) } }.padding(vertical = 2.dp).testTag("factLink"), fontSize = DS.Type.caption, color = P3.accent, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (problem) {
                Label(if (v == FactPipeline.UNK) "稳妥说法 · 加限定或去掉精确数字" else "建议改法 · 只改有问题的字")
                if (pick.isBlank()) Text(if (FactFix.verdictOf(c) == FactPipeline.UNK) "这句没有现成的稳妥说法，可以换个改法、手动改或忽略" else "模型没给出改法，可以换个改法或手动改", fontSize = DS.Type.sub, color = P3.text3)
                else DiffText(c.span, pick)
                if (c.alts.isNotEmpty()) Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (listOf(FactFix.suggestion(c.copy(fixText = ""))).filter { it.isNotBlank() } + c.alts).distinct().forEach { a ->
                        val sel = a == pick
                        Box(Modifier.fillMaxWidth().clip(Sq.s).border(if (sel) 1.5.dp else 0.5.dp, if (sel) P3.accent else Hairline, Sq.s).clickable { pick = a }.padding(10.dp)) { DiffText(c.span, a, DS.Type.sub) }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
            if (problem) {
                if (running) Text("校对完成后可以逐条修改", Modifier.padding(top = 12.dp), fontSize = DS.Type.caption, color = P3.text3)
                if (c.fix.isNotBlank()) Text(when (c.fix) { FactFix.APPLIED -> "这条已采纳"; FactFix.IGNORED -> "这条已忽略"; else -> "这条已转去手动改" }, Modifier.padding(top = 12.dp), fontSize = DS.Type.caption, color = P3.text3)
                val can = !running && rep != null && c.fix != FactFix.APPLIED
                Row(Modifier.padding(top = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AccentBtn("采纳", Modifier.weight(1.4f).testTag("factAdopt"), enabled = can && pick.isNotBlank()) { onAdopt(pick) }
                    GlassBtn("换个改法", Modifier.weight(1f).testTag("factAlt"), enabled = can, busy = busy) {
                        busy = true
                        scope.launch {
                            try {
                                val r = withContext(Dispatchers.IO) { llm(FactFix.ALT_SYS, FactFix.altPrompt(c.span, d?.reason.orEmpty(), ev?.quote.orEmpty(), pick)) }
                                val alts = FactFix.parseAlts(r, c.span, pick)
                                if (alts.isEmpty()) say("没想出别的改法，可以手动改") else { onAlts(c.copy(alts = alts)); pick = alts.first() }
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Throwable) { say("换个改法失败：" + Errors.explain(e).title) }
                            finally { busy = false }
                        }
                    }
                }
                Row(Modifier.padding(top = 10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassBtn("手动改", Modifier.weight(1.4f).testTag("factManual"), enabled = !running && rep != null) { onManual() }
                    Box(Modifier.weight(1f).height(44.dp).clip(Sq.m).clickable(enabled = !running && rep != null && c.fix.isBlank()) { onIgnore() }.testTag("factIgnore"), contentAlignment = Alignment.Center) {
                        Text("忽略", fontSize = DS.Type.callout, color = P3.text2)
                    }
                }
            } else GlassBtn("关闭", Modifier.padding(top = 16.dp).fillMaxWidth()) { onClose() }
            rep?.snap?.let { SnapLine(it, onRestore) }
        }
        }
    }
}

@Composable
private fun StylePass(r: FactReport, title: String, body: String, llm: suspend (String, String) -> String, onBack: () -> Unit,
                      onReport: (FactReport) -> Unit, onApply: (String, String, FactReport) -> Unit, say: (String) -> Unit, onRestore: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    val scope0 = remember(r.edits, body) { FactFix.styleScope(body, r.edits) }
    fun run() {
        if (loading) return
        loading = true; err = null
        scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) { llm(FactFix.STYLE_SYS, FactFix.stylePrompt(title, body, r.edits, scope0.ifEmpty { listOf(1).filter { FactFix.paragraphs(body).isNotEmpty() } })) }
                onReport(r.copy(style = FactFix.parseTips(reply, body), styleRan = true))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { err = Errors.explain(e).title }
            finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { if (!r.styleRan) run() }
    val tips = r.style
    val handled = tips.count { it.state.isNotBlank() }
    Column(Modifier.fillMaxSize()) {
        Header("风格把关", onBack) { TextAction("全部完成") { onBack(); if (tips.isNotEmpty()) say("风格把关完成 · 已处理 $handled/${tips.size}") } }
        LazyColumn(Modifier.weight(1f).testTag("styleList"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("p") {
                Column {
                    if (tips.isNotEmpty()) Progress("已处理", handled, tips.size)
                    Text("只看改过的 ${r.edits.size} 处及前后段落", Modifier.padding(top = 8.dp, start = 4.dp), fontSize = DS.Type.caption, color = P3.text3)
                }
            }
            when {
                loading -> item("l") { Row(Modifier.fillMaxWidth().glass().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = P3.accent)
                    Text("正在通读改过的段落：论点、衔接、语气…", Modifier.padding(start = 10.dp), fontSize = DS.Type.callout, color = P3.text2) } }
                err != null -> item("e") { Row(Modifier.fillMaxWidth().glass().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("没能完成风格把关：$err", Modifier.weight(1f), fontSize = DS.Type.sub, color = P3.text2); TextAction("重试") { run() } } }
                r.styleRan && tips.isEmpty() -> item("n") { Text("改过的地方读起来没问题：论点、衔接、语气都和原文一致", Modifier.fillMaxWidth().glass().padding(18.dp), fontSize = DS.Type.callout, color = P3.text2) }
            }
            items(tips, key = { it.id }) { t -> TipCard(t, r, title, body, llm, onReport, onApply, say) }
        }
        r.snap?.let { Box(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) { SnapLine(it, onRestore) } }
    }
}

@Composable
private fun TipCard(t: FactFix.Tip, r: FactReport, title: String, body: String, llm: suspend (String, String) -> String,
                    onReport: (FactReport) -> Unit, onApply: (String, String, FactReport) -> Unit, say: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pick by remember(t.id) { mutableStateOf(t.new) }
    fun upd(t2: FactFix.Tip) = r.copy(style = r.style.map { if (it.id == t.id) t2 else it })
    Column(Modifier.fillMaxWidth().frosted(Sq.l, DS.Elev.card).padding(16.dp).testTag("tip_${t.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip(t.kind, filled = true)
            if (t.para > 0) Text("第 ${t.para} 段", Modifier.padding(start = 8.dp), fontSize = DS.Type.caption, color = P3.text3)
            Spacer(Modifier.weight(1f))
            if (t.state.isNotBlank()) Text(if (t.state == FactFix.APPLIED) "已采纳" else "已忽略", fontSize = DS.Type.caption, color = if (t.state == FactFix.APPLIED) P3.accent else P3.text3)
        }
        Text(t.why, Modifier.padding(top = 8.dp), fontSize = DS.Type.callout, lineHeight = 22.sp, color = P3.text)
        Box(Modifier.padding(top = 8.dp)) { DiffText(t.old, if (t.state == FactFix.APPLIED) t.new else pick, DS.Type.sub) }
        if (t.alts.isNotEmpty() && t.state.isBlank()) Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf(t.new) + t.alts).distinct().forEach { a -> val sel = a == pick
                Text(a, Modifier.fillMaxWidth().clip(Sq.s).border(if (sel) 1.5.dp else 0.5.dp, if (sel) P3.accent else Hairline, Sq.s).clickable { pick = a }.padding(10.dp), fontSize = DS.Type.sub, color = P3.text) }
        }
        if (t.state.isBlank()) Row(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AccentBtn("采纳", Modifier.weight(1.2f).testTag("tipAdopt_${t.id}")) {
                val a = FactFix.applyOne(title, body, t.old, pick)
                if (a == null) say("这段已经改过，找不到原文了")
                else onApply(a.title, a.body, upd(t.copy(state = FactFix.APPLIED, new = pick)).copy(snap = r.snap ?: FactFix.Snap(title, body, System.currentTimeMillis())))
            }
            GlassBtn("换个改法", Modifier.weight(1f), busy = busy) {
                busy = true
                scope.launch {
                    try {
                        val rep = withContext(Dispatchers.IO) { llm(FactFix.ALT_SYS, FactFix.altPrompt(t.old, t.why, "", pick)) }
                        val alts = FactFix.parseAlts(rep, t.old, pick)
                        if (alts.isEmpty()) say("没想出别的改法") else { onReport(upd(t.copy(alts = alts))); pick = alts.first() }
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Throwable) { say("换个改法失败：" + Errors.explain(e).title) }
                    finally { busy = false }
                }
            }
            Box(Modifier.weight(.8f).height(44.dp).clip(Sq.m).clickable { onReport(upd(t.copy(state = FactFix.IGNORED))) }, contentAlignment = Alignment.Center) { Text("忽略", fontSize = DS.Type.callout, color = P3.text2) }
        }
    }
}
