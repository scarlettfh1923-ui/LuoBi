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
 * 1.15.1 方案 A：计数筛选 + 分组卡片，点开看依据。
 * - 顶部：返回 · 事实校对（文章名）· 重新检测；一行检索状态（点开看全文）
 * - 计数卡：有出入 / 无法核实 / 已印证，点按跳到该组
 * - 分组卡片：每条都有勾选（没有改正文案时禁用并提示）；收起时「→ 改正」；点开看资料原文、来源链接、几个来源印证，并可编辑「建议改正」
 * - 底栏只应用勾选且未应用的项（用当前手改文案）
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
    // checkedAt 变=重新检测；applied 签名变=刚应用过。手改文案不进 key，避免输入时丢掉焦点。
    val syncKey = report.checkedAt.toString() + "|" + report.issues.joinToString { "${it.id}:${it.applied}" }
    val issues = remember(syncKey) { report.issues.map { it.copy() }.toMutableStateList() }
    var open by remember(syncKey) { mutableStateOf(setOf<String>()) }
    var noteOpen by remember { mutableStateOf(false) }
    val groups = listOf("有出入", "无法核实", "已印证")
    var tab by remember(syncKey) { mutableStateOf(groups.firstOrNull { g -> issues.any { it.verdict == g } } ?: "有出入") }
    val scroll = rememberScrollState()
    val anchors = remember { mutableStateMapOf<String, Int>() }
    val scope = rememberCoroutineScope()
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    fun push() { onChange(report.copy(issues = issues.map { it.copy() })) }
    val red = Color(0xFFDC2626); val amber = Color(0xFFD97706)

    ModalBottomSheet(
        shape = Sq.topXl, onDismissRequest = { push(); onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = P3.bg, dragHandle = { BottomSheetDefaults.DragHandle(color = P3.borderStrong) },
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(bottom = 12.dp)) {
            // 顶栏
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
            // 检索状态（一行，点开看全文）
            Row(Modifier.fillMaxWidth().clickable { noteOpen = !noteOpen }.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text((if (fromCache) "上次结果 · " else "") + report.searchNote, Modifier.weight(1f), fontSize = 11.sp, color = P3.text3,
                    maxLines = if (noteOpen) 6 else 1, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
                Text(if (noteOpen) " ⌃" else " ›", fontSize = 11.sp, color = P3.text3)
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(scroll).padding(horizontal = 16.dp)) {
                // 计数卡
                Row(Modifier.fillMaxWidth().clip(Sq.l).background(P3.surface).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    groups.forEachIndexed { i, g ->
                        if (i > 0) Box(Modifier.width(Dp.Hairline).height(32.dp).background(P3.border))
                        val n = issues.count { it.verdict == g }; val on = tab == g
                        Column(Modifier.weight(1f).clip(Sq.s).clickable { tab = g; anchors[g]?.let { y -> scope.launch { scroll.animateScrollTo(y, Motion.enter()) } } },
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$n", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = P3.text)
                            Text(g, fontSize = 11.sp, color = if (on) (if (g == "有出入") red else if (g == "无法核实") amber else P3.text2) else P3.text2, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                            Box(Modifier.padding(top = 4.dp).width(28.dp).height(3.dp).clip(Sq.full).background(if (on) P3.accent else Color.Transparent))
                        }
                    }
                }
                if (issues.isEmpty()) {
                    Text("没有发现可核验的硬事实错误。", Modifier.padding(16.dp), fontSize = 14.sp, color = P3.text2)
                    report.skipped.take(5).forEach { Text("· $it", Modifier.padding(horizontal = 16.dp, vertical = 2.dp), fontSize = 12.sp, color = P3.text3) }
                }
                groups.forEach { g ->
                    val idxs = issues.indices.filter { issues[it].verdict == g }
                    if (idxs.isEmpty()) return@forEach
                    Row(Modifier.fillMaxWidth().onGloballyPositioned { anchors[g] = it.positionInParent().y.toInt() }.padding(start = 8.dp, end = 8.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("$g · ${idxs.size}", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = P3.text2)
                        val sel = idxs.filter { !issues[it].applied && issues[it].correction.isNotBlank() }
                        if (sel.isNotEmpty()) {
                            val all = sel.all { issues[it].selected }
                            Text(if (all) "全不选" else "全选", Modifier.clip(Sq.s).clickable { sel.forEach { i -> issues[i] = issues[i].copy(selected = !all) }; push() }.padding(4.dp),
                                fontSize = 13.sp, color = P3.accent)
                        }
                    }
                    idxs.forEach { idx ->
                        val iss = issues[idx]
                        val expanded = iss.id in open
                        val canFix = iss.correction.isNotBlank() && !iss.applied
                        val sevColor = when (iss.severity) { "高" -> red; "中" -> amber; else -> P3.text3 }
                        val tone = if (iss.verdict == "有出入") red else if (iss.verdict == "无法核实") amber else P3.text2
                        Row(Modifier.padding(bottom = 10.dp).fillMaxWidth().clip(Sq.l).background(P3.surface)
                            .clickable { focus.clearFocus(); open = if (expanded) open - iss.id else open + iss.id }.padding(14.dp), verticalAlignment = Alignment.Top) {
                            // 每一项都有勾选：没有改正文案时禁用
                            Box(Modifier.padding(top = 1.dp).size(22.dp).clip(Sq.full)
                                .background(if (iss.selected && canFix) P3.accent else Color.Transparent)
                                .hairline(Sq.full, if (iss.selected && canFix) P3.accent else P3.borderStrong, 1.5.dp)
                                .clickable(enabled = canFix) { issues[idx] = iss.copy(selected = !iss.selected); push() }
                                .semantics { contentDescription = if (canFix) (if (iss.selected) "已勾选" else "未勾选") else "不可勾选" },
                                contentAlignment = Alignment.Center) { if (iss.selected && canFix) SvgIcon(Ic.check, Color.White, 13.dp) }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(iss.category, Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = P3.text2, maxLines = 1)
                                    if (iss.applied) Text("已应用  ", fontSize = 11.sp, color = P3.accent, fontWeight = FontWeight.SemiBold)
                                    Text("影响" + iss.severity, fontSize = 11.sp, color = sevColor, fontWeight = FontWeight.SemiBold)
                                }
                                Text(iss.span.ifBlank { iss.claim }, Modifier.padding(top = 6.dp).clickable { onJump(iss) }, fontSize = 16.sp, lineHeight = 24.sp, color = P3.text,
                                    textDecoration = if (iss.verdict == "有出入" && !iss.applied) androidx.compose.ui.text.style.TextDecoration.Underline else null)
                                val why = iss.note.ifBlank { if (iss.verdict == "无法核实") "没有找到可靠来源，无法核实。" else iss.claim }
                                if (why.isNotBlank()) Row(Modifier.padding(top = 6.dp)) {
                                    Box(Modifier.padding(top = 6.dp).size(6.dp).clip(Sq.full).background(tone)); Spacer(Modifier.width(6.dp))
                                    Text(why, fontSize = 12.sp, color = tone, lineHeight = 18.sp)
                                }
                                if (!expanded) {
                                    Text("→ " + iss.correction.ifBlank { "暂无建议改正" }, Modifier.padding(top = 8.dp), fontSize = 13.sp,
                                        color = if (iss.correction.isBlank()) P3.text3 else P3.accent, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                } else {
                                    val uri = androidx.compose.ui.platform.LocalUriHandler.current
                                    Row(Modifier.padding(top = 10.dp).height(IntrinsicSize.Min)) {
                                        Box(Modifier.width(2.5.dp).fillMaxHeight().clip(Sq.full).background(P3.border)); Spacer(Modifier.width(10.dp))
                                        Column {
                                            if (iss.quote.isNotBlank()) Text("「${iss.quote}」", fontSize = 12.sp, color = P3.text2, lineHeight = 18.sp)
                                            else Text("资料里没有可引用的原文", fontSize = 12.sp, color = P3.text3)
                                            iss.links.forEach { l -> Text("来源 · ${l.title.ifBlank { l.url }}", Modifier.padding(top = 3.dp).clickable { runCatching { uri.openUri(l.url) } },
                                                fontSize = 11.sp, color = P3.accent, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                            if (iss.links.isEmpty() && iss.source.isNotBlank()) Text("来源 · ${iss.source}", Modifier.padding(top = 3.dp), fontSize = 11.sp, color = P3.text3)
                                            Text(iss.corroborationLabel(), Modifier.padding(top = 2.dp), fontSize = 11.sp, color = P3.text3)
                                        }
                                    }
                                    Text("建议改正", Modifier.padding(top = 10.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = P3.text2)
                                    if (iss.applied) Text(iss.correction, Modifier.padding(top = 4.dp), fontSize = 13.sp, color = P3.text3)
                                    else InputBox(iss.correction, { issues[idx] = iss.copy(correction = it, selected = it.isNotBlank() && (iss.selected || iss.correction.isBlank())); push() },
                                        "没有建议改正，可在这里写上再勾选", Modifier.padding(top = 4.dp), minHeight = 44.dp, fontSize = 14)
                                }
                                if (!iss.applied && iss.correction.isBlank()) Text("没有建议改正 · 点开写上改正后即可勾选", Modifier.padding(top = 4.dp), fontSize = 11.sp, color = P3.text3)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            val selectable = issues.filter { it.selected && !it.applied && it.correction.isNotBlank() }
            if (issues.any { !it.applied }) {
                val n = selectable.size
                PrimaryButton(if (n == 0) "请先勾选要改正的项" else "应用所选改正（$n）", Modifier.padding(horizontal = 16.dp).padding(top = 4.dp), enabled = n > 0) {
                    focus.clearFocus(); push(); onApply(selectable.map { it.copy() })
                }
            }
        }
    }
}
