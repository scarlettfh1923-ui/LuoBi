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
 * 1.12 方案 A：事实校对结果底部弹层。
 * - 顶栏「重新检测」才重跑；「关闭」只关弹层，缓存保留
 * - 每条：勾选（默认全选）+ 可编辑「建议改正」
 * - 底栏只应用勾选且未 applied 的项（用当前手改文案）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FactSheet(
    report: FactReport,
    fromCache: Boolean = false,
    onJump: (FactIssue) -> Unit,
    onApply: (List<FactIssue>) -> Unit,
    onReCheck: () -> Unit,
    onChange: (FactReport) -> Unit,
    onDismiss: () -> Unit,
) {
    // checkedAt 变=重新检测；applied 签名变=刚应用过。手改文案不进 key，避免输入时丢掉焦点。
    val syncKey = report.checkedAt.toString() + "|" + report.issues.joinToString { "${it.id}:${it.applied}" }
    val issues = remember(syncKey) {
        report.issues.map { it.copy() }.toMutableStateList()
    }
    fun push() {
        onChange(report.copy(issues = issues.map { it.copy() }))
    }
    ModalBottomSheet(
        shape = Sq.topXl,
        onDismissRequest = { push(); onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = P3.surface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = P3.borderStrong) },
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("事实校对", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = P3.text)
                    val pending = issues.count { !it.applied }
                    Text(buildString {
                        if (fromCache) append("上次结果 · ")
                        append("$pending 项")
                        if (report.skipped.isNotEmpty()) append(" · ${report.skipped.size} 条备注")
                        append(" · 可继续改")
                    }, Modifier.padding(top = 2.dp), fontSize = 12.sp, color = P3.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("重新检测", Modifier.clip(Sq.s).clickable { push(); onReCheck() }.padding(horizontal = 8.dp, vertical = 6.dp),
                    fontSize = 13.sp, color = P3.accent, fontWeight = FontWeight.SemiBold)
                Text("关闭", Modifier.clip(Sq.s).clickable { push(); onDismiss() }.padding(horizontal = 8.dp, vertical = 6.dp),
                    fontSize = 13.sp, color = P3.text2)
            }
            Text(report.searchNote, Modifier.padding(horizontal = 20.dp, vertical = 2.dp), fontSize = 11.sp, color = P3.text3, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (issues.isEmpty()) {
                    Text("没有发现可核验的硬事实错误。", Modifier.padding(16.dp), fontSize = 14.sp, color = P3.text2)
                    report.skipped.take(5).forEach { Text("· $it", Modifier.padding(horizontal = 16.dp, vertical = 2.dp), fontSize = 12.sp, color = P3.text3) }
                }
                issues.forEachIndexed { idx, iss ->
                    val sevColor = when (iss.severity) { "高" -> Color(0xFFDC2626); "中" -> Color(0xFFD97706); else -> P3.text3 }
                    val sevBg = when (iss.severity) { "高" -> Color(0xFFFEE2E2); "中" -> Color(0xFFFFEDD5); else -> P3.bg }
                    val canFix = iss.correction.isNotBlank() && iss.severity != "低" && !iss.applied
                    Card(Modifier.padding(vertical = 4.dp), pad = 12.dp, color = if (iss.applied) P3.bg else P3.surface) {
                        Row(verticalAlignment = Alignment.Top) {
                            if (canFix || iss.applied) {
                                Checkbox(
                                    checked = iss.selected && !iss.applied,
                                    onCheckedChange = {
                                        if (!iss.applied) {
                                            issues[idx] = iss.copy(selected = it)
                                            push()
                                        }
                                    },
                                    enabled = !iss.applied,
                                    colors = CheckboxDefaults.colors(checkedColor = P3.accent),
                                    modifier = Modifier.size(22.dp).padding(top = 2.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Row(Modifier.clickable { onJump(iss) }, verticalAlignment = Alignment.CenterVertically) {
                                    Text(iss.category, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = P3.text)
                                    Spacer(Modifier.width(8.dp))
                                    Text(iss.severity, Modifier.clip(Sq.full).background(sevBg).padding(horizontal = 8.dp, vertical = 2.dp),
                                        fontSize = 11.sp, color = sevColor, fontWeight = FontWeight.SemiBold)
                                    if (iss.applied) {
                                        Spacer(Modifier.width(8.dp))
                                        Text("已应用", Modifier.clip(Sq.full).background(P3.accentSoft).padding(horizontal = 8.dp, vertical = 2.dp),
                                            fontSize = 11.sp, color = P3.onAccentSoft, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                val orig = iss.span.ifBlank { iss.claim }
                                if (orig.isNotBlank()) {
                                    Text("原文：「$orig」", Modifier.padding(top = 6.dp).clickable { onJump(iss) },
                                        fontSize = 13.sp, color = P3.text, lineHeight = 19.sp)
                                }
                                val explain = iss.note.ifBlank { iss.claim.takeIf { it.isNotBlank() && it != iss.span }.orEmpty() }
                                if (explain.isNotBlank()) Text(explain, Modifier.padding(top = 4.dp), fontSize = 12.sp, color = P3.text2, lineHeight = 18.sp)
                                if (iss.source.isNotBlank()) Text(iss.source, Modifier.padding(top = 4.dp), fontSize = 11.sp, color = P3.text3)
                                if (iss.correction.isNotBlank() && iss.severity != "低") {
                                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("建议改正", fontSize = 12.sp, color = P3.text2, fontWeight = FontWeight.SemiBold)
                                        val ai = iss.aiCorrection.ifBlank { iss.correction }
                                        if (!iss.applied && iss.correction != ai) {
                                            Spacer(Modifier.width(6.dp))
                                            Text("已改", Modifier.clip(Sq.full).background(P3.accentSoft).padding(horizontal = 6.dp, vertical = 1.dp),
                                                fontSize = 10.sp, color = P3.onAccentSoft)
                                        }
                                    }
                                    if (iss.applied) {
                                        Text(iss.correction, Modifier.padding(top = 4.dp), fontSize = 13.sp, color = P3.text3)
                                    } else {
                                        InputBox(
                                            value = iss.correction,
                                            onChange = {
                                                issues[idx] = iss.copy(correction = it)
                                                push()
                                            },
                                            placeholder = "手改改正文案",
                                            modifier = Modifier.padding(top = 4.dp),
                                            minHeight = 40.dp,
                                            fontSize = 13,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            val selectable = issues.filter { it.selected && !it.applied && it.correction.isNotBlank() && it.severity != "低" }
            if (issues.any { !it.applied && it.correction.isNotBlank() && it.severity != "低" }) {
                val n = selectable.size
                PrimaryButton(
                    if (n == 0) "请先勾选要改正的项" else "应用所选改正（$n）",
                    Modifier.padding(horizontal = 16.dp).padding(top = 4.dp),
                    enabled = n > 0,
                ) {
                    push()
                    onApply(selectable.map { it.copy() })
                }
            }
        }
    }
}
