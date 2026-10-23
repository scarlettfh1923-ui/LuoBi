package com.scarlett.luobi

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp

/** 1.11：阅读页顶部进度胶囊（不挡正文） */
@Composable
fun FactProgressChip(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, modifier = modifier, enter = Motion.fadeIn + scaleIn(initialScale = .92f), exit = Motion.fadeOut + scaleOut(targetScale = .92f)) {
        Row(Modifier.clip(Sq.full).background(P3.text).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
            Spacer(Modifier.width(8.dp))
            Text("正在核对事实…", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * 1.17 方案 A（说法 → 细节）：
 * - 顶部：关闭 · 事实校对（文章名）· 重新检测；一行「实际用到的检索源」
 * - 计数：有出入 / 部分对 / 已印证；其余（无法核实 / 渲染无出处 / 观点不校）收进「其他」一行
 * - 每条说法一张卡：原文片段 + 逐行细节（裁决标签、资料原句、来源链接、改写 + 应用勾选）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FactSheet(
    report: FactReport,
    fromCache: Boolean = false,
    articleTitle: String = "",
    onJump: (FactIssue) -> Unit,
    onApply: (List<FactIssue>) -> Unit,
    onReCheck: () -> Unit,
    onChange: (FactReport) -> Unit,
    onDismiss: () -> Unit,
) {
    val syncKey = report.checkedAt.toString() + "|" + report.issues.joinToString { "${it.id}:${it.applied}" }
    val claims = remember(syncKey) { FactPipeline.claimsOf(report).toMutableStateList() }
    var otherOpen by remember(syncKey) { mutableStateOf(false) }
    var noteOpen by remember { mutableStateOf(false) }
    val main = listOf(FactPipeline.BAD, FactPipeline.PART, FactPipeline.OK)
    val others = listOf(FactPipeline.UNK, FactPipeline.RHET, FactPipeline.OPN)
    fun worst(c: FactClaim) = FactPipeline.VERDICTS.firstOrNull { v -> c.details.any { it.verdict == v } } ?: FactPipeline.UNK
    var tab by remember(syncKey) { mutableStateOf(main.firstOrNull { g -> claims.any { worst(it) == g } } ?: FactPipeline.BAD) }
    val scroll = rememberScrollState()
    // 弹层滑入时输入框可能把内容往上带一截；打开后回到顶部，保证计数卡可见
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(syncKey) {
        snapshotFlow { sheetState.currentValue == SheetValue.Expanded && sheetState.targetValue == SheetValue.Expanded }.collect { if (it) scroll.scrollTo(0) }
    }
    val anchors = remember { mutableStateMapOf<String, Int>() }
    val scope = rememberCoroutineScope()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    fun push() { onChange(FactPipeline.withClaims(report, claims.toList())) }
    fun cnt(v: String) = claims.sumOf { c -> c.details.count { it.verdict == v } }
    fun jump(g: String) { tab = g; anchors[g]?.let { y -> scope.launch { scroll.animateScrollTo(y, Motion.enter()) } } }

    ModalBottomSheet(
        shape = Sq.topXl, onDismissRequest = { push(); onDismiss() },
        sheetState = sheetState,
        containerColor = P3.bg, dragHandle = { BottomSheetDefaults.DragHandle(color = P3.borderStrong) },
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(bottom = 12.dp)) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Box(Modifier.align(Alignment.CenterStart).size(40.dp).clip(Sq.full).background(P3.surface).clickable { push(); onDismiss() }, contentAlignment = Alignment.Center) {
                    SvgIcon(Ic.back, P3.text, 20.dp, desc = "关闭")
                }
                Column(Modifier.align(Alignment.Center).padding(horizontal = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("事实校对", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = P3.text)
                    if (articleTitle.isNotBlank()) Text("《${articleTitle}》", fontSize = 11.sp, color = P3.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("重新检测", Modifier.align(Alignment.CenterEnd).clip(Sq.s).clickable { push(); onReCheck() }.padding(horizontal = 8.dp, vertical = 6.dp),
                    fontSize = 13.sp, color = P3.accent, fontWeight = FontWeight.SemiBold)
            }
            // 实际用到的检索源
            Column(Modifier.fillMaxWidth().clickable { noteOpen = !noteOpen }.padding(horizontal = 24.dp, vertical = 8.dp)) {
                if (report.sources.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("资料来源", fontSize = 11.sp, color = P3.text3); Spacer(Modifier.width(6.dp))
                    report.sources.forEach { src -> Text(src, Modifier.padding(end = 4.dp).clip(Sq.full).background(P3.accent.copy(alpha = .10f)).padding(horizontal = 7.dp, vertical = 2.dp), fontSize = 10.sp, color = P3.accent, maxLines = 1) }
                }
                Text((if (fromCache) "上次结果 · " else "") + report.searchNote, Modifier.padding(top = 3.dp), fontSize = 11.sp, color = P3.text3,
                    maxLines = if (noteOpen) 6 else 1, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(scroll).padding(horizontal = 16.dp)) {
                Column(Modifier.fillMaxWidth().clip(Sq.l).background(P3.surface)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        main.forEachIndexed { i, g ->
                            if (i > 0) Box(Modifier.width(Dp.Hairline).height(32.dp).background(P3.border))
                            val on = tab == g
                            Column(Modifier.weight(1f).clip(Sq.s).clickable { jump(g) }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${cnt(g)}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (cnt(g) > 0) verdictColor(g) else P3.text)
                                Text(g, fontSize = 11.sp, color = if (on) verdictColor(g) else P3.text2, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                                Box(Modifier.padding(top = 4.dp).width(28.dp).height(3.dp).clip(Sq.full).background(if (on) P3.accent else Color.Transparent))
                            }
                        }
                    }
                    val otherN = others.sumOf { cnt(it) }
                    if (otherN > 0) {
                        Box(Modifier.fillMaxWidth().height(Dp.Hairline).background(P3.border))
                        Row(Modifier.fillMaxWidth().clickable { otherOpen = !otherOpen }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("其他 · $otherN", Modifier.weight(1f), fontSize = 12.sp, color = P3.text2, fontWeight = FontWeight.SemiBold)
                            if (!otherOpen) Text(others.filter { cnt(it) > 0 }.joinToString("  ") { "$it ${cnt(it)}" }, fontSize = 11.sp, color = P3.text3, maxLines = 1)
                            Text(if (otherOpen) "  ⌃" else "  ›", fontSize = 12.sp, color = P3.text3)
                        }
                        if (otherOpen) Row(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
                            others.filter { cnt(it) > 0 }.forEach { g -> Text("$g ${cnt(g)}", Modifier.padding(end = 6.dp).clip(Sq.full).background(P3.bg).clickable { jump(g) }.padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 12.sp, color = verdictColor(g)) }
                        }
                    }
                }
                if (claims.isEmpty()) Text("没有找到可核验的说法。", Modifier.padding(16.dp), fontSize = 14.sp, color = P3.text2)
                (main + others).forEach { g ->
                    val idxs = claims.indices.filter { worst(claims[it]) == g }
                    if (idxs.isEmpty()) return@forEach
                    if (g in others && !otherOpen) { Box(Modifier.onGloballyPositioned { anchors[g] = it.positionInParent().y.toInt() }); return@forEach }
                    Text("$g · ${idxs.size} 条说法", Modifier.fillMaxWidth().onGloballyPositioned { anchors[g] = it.positionInParent().y.toInt() }.padding(start = 8.dp, top = 18.dp, bottom = 8.dp),
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = P3.text2)
                    idxs.forEach { ci ->
                        val c = claims[ci]
                        Column(Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(Sq.l).background(P3.surface).padding(14.dp)) {
                            Text(c.span.ifBlank { c.claim }, Modifier.clickable { onJump(FactPipeline.issueOf(c, c.details.first())) }, fontSize = 15.sp, lineHeight = 23.sp, color = P3.text, fontWeight = FontWeight.Medium)
                            c.details.forEachIndexed { di, d ->
                                Box(Modifier.padding(vertical = 10.dp).fillMaxWidth().height(Dp.Hairline).background(P3.border))
                                FactDetailRow(d, onToggle = { v -> claims[ci] = c.copy(details = c.details.toMutableList().also { it[di] = d.copy(selected = v) }); push() },
                                    onEdit = { t -> claims[ci] = c.copy(details = c.details.toMutableList().also { it[di] = d.copy(rewrite = t, selected = t.isNotBlank()) }); push() },
                                    openUrl = { runCatching { uri.openUri(it) } })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            val selectable = claims.flatMap { c -> c.details.filter { it.selected && !it.applied && it.rewrite.isNotBlank() && c.span.isNotBlank() }.map { FactPipeline.issueOf(c, it) } }
                .distinctBy { it.span }
            if (claims.any { c -> c.details.any { it.rewrite.isNotBlank() && !it.applied } }) {
                val n = selectable.size
                PrimaryButton(if (n == 0) "请先勾选要应用的改写" else "应用所选改写（$n）", Modifier.padding(horizontal = 16.dp).padding(top = 4.dp), enabled = n > 0) {
                    focus.clearFocus(); push(); onApply(selectable)
                }
            }
        }
    }
}

fun verdictColor(v: String): Color = when (v) {
    FactPipeline.BAD -> Color(0xFFDC2626); FactPipeline.PART -> Color(0xFFD97706); FactPipeline.OK -> Color(0xFF16A34A)
    FactPipeline.UNK -> Color(0xFF64748B); else -> Color(0xFF94A3B8)
}

@Composable
private fun FactDetailRow(d: FactDetail, onToggle: (Boolean) -> Unit, onEdit: (String) -> Unit, openUrl: (String) -> Unit) {
    val col = verdictColor(d.verdict)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.verdict.ifBlank { "待核" }, Modifier.clip(Sq.full).background(col.copy(alpha = .12f)).padding(horizontal = 8.dp, vertical = 3.dp)
                .semantics { contentDescription = "裁决 ${d.verdict}" }, fontSize = 11.sp, color = col, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(d.aspect, fontSize = 11.sp, color = P3.text3)
            Spacer(Modifier.width(6.dp))
            Text(d.text, Modifier.weight(1f), fontSize = 13.sp, color = P3.text, lineHeight = 19.sp)
        }
        if (d.quote.isNotBlank()) Row(Modifier.padding(top = 8.dp).height(IntrinsicSize.Min)) {
            Box(Modifier.width(2.5.dp).fillMaxHeight().clip(Sq.full).background(col.copy(alpha = .5f))); Spacer(Modifier.width(10.dp))
            Text("「${d.quote}」", fontSize = 12.sp, color = P3.text2, lineHeight = 18.sp)
        }
        if (d.reason.isNotBlank()) Text(d.reason, Modifier.padding(top = 6.dp), fontSize = 12.sp, color = P3.text2, lineHeight = 18.sp)
        if (d.url.isNotBlank()) Text("来源 · ${d.sourceTitle.ifBlank { d.url }}" + if (d.via.isNotBlank()) "（${d.via}）" else "", Modifier.padding(top = 4.dp).clickable { openUrl(d.url) },
            fontSize = 11.sp, color = P3.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (d.verdict == FactPipeline.UNK && d.tried.isNotEmpty()) Text("试过的查询：" + d.tried.joinToString("；"), Modifier.padding(top = 4.dp), fontSize = 11.sp, color = P3.text3, lineHeight = 16.sp)
        if (d.rewrite.isNotBlank() || d.verdict == FactPipeline.BAD || d.verdict == FactPipeline.PART) {
            val can = d.rewrite.isNotBlank() && !d.applied
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 10.dp).size(20.dp).clip(Sq.full)
                    .background(if (d.selected && can) P3.accent else Color.Transparent)
                    .hairline(Sq.full, if (d.selected && can) P3.accent else P3.borderStrong, 1.5.dp)
                    .clickable(enabled = can) { onToggle(!d.selected) }
                    .semantics { contentDescription = if (can) (if (d.selected) "应用此改写：已勾选" else "应用此改写：未勾选") else "不可勾选" },
                    contentAlignment = Alignment.Center) { if (d.selected && can) SvgIcon(Ic.check, Color.White, 12.dp) }
                Spacer(Modifier.width(8.dp))
                if (d.applied) Text("已应用 · " + d.rewrite, Modifier.padding(top = 10.dp), fontSize = 13.sp, color = P3.text3)
                else InputBox(d.rewrite, onEdit, "写上改写后即可勾选应用", Modifier.weight(1f), minHeight = 40.dp, fontSize = 13)
            }
        }
    }
}
