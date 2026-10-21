
package com.scarlett.luobi

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

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
 * 事实校对结果底部弹层（方案 A）。
 * - 高/中/低 事实问题；点一条 → onJump(span)
 * - 勾选后「一键改正」→ onApply(selected)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FactSheet(
    report: FactReport,
    onJump: (FactIssue) -> Unit,
    onApply: (List<FactIssue>) -> Unit,
    onDismiss: () -> Unit,
) {
    val issues = remember(report) { report.issues.map { it.copy() }.toMutableStateList() }
    ModalBottomSheet(shape = Sq.topXl, onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = P3.surface, dragHandle = { BottomSheetDefaults.DragHandle(color = P3.borderStrong) }, contentWindowInsets = { WindowInsets(0) }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("事实校对", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = P3.text)
                Text("关闭", Modifier.clip(Sq.s).clickable(onClick = onDismiss).padding(6.dp), fontSize = 13.sp, color = P3.text2)
            }
            val high = issues.count { it.severity == "高" }; val mid = issues.count { it.severity == "中" }
            Text(buildString {
                append("找到 ${issues.size} 个事实问题")
                if (report.skipped.isNotEmpty()) append(" · ${report.skipped.size} 条备注")
                if (high + mid > 0) append("（高 $high · 中 $mid）")
            }, Modifier.padding(horizontal = 24.dp, vertical = 4.dp), fontSize = 12.sp, color = P3.text3)
            Text(report.searchNote, Modifier.padding(horizontal = 24.dp), fontSize = 11.sp, color = P3.text3, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp)) {
                if (issues.isEmpty()) {
                    Text("没有发现可核验的硬事实错误。", Modifier.padding(16.dp), fontSize = 14.sp, color = P3.text2)
                    report.skipped.take(5).forEach { Text("· $it", Modifier.padding(horizontal = 16.dp, vertical = 2.dp), fontSize = 12.sp, color = P3.text3) }
                }
                issues.forEachIndexed { idx, iss ->
                    val sevColor = when (iss.severity) { "高" -> Color(0xFFDC2626); "中" -> Color(0xFFD97706); else -> P3.text3 }
                    val sevBg = when (iss.severity) { "高" -> Color(0xFFFEE2E2); "中" -> Color(0xFFFFEDD5); else -> P3.bg }
                    Card(Modifier.padding(vertical = 4.dp).clickable { onJump(iss) }, pad = 12.dp, color = if (iss.severity == "低") P3.bg else P3.surface) {
                        Row(verticalAlignment = Alignment.Top) {
                            if (iss.correction.isNotBlank() && iss.severity != "低") {
                                Checkbox(iss.selected, { issues[idx] = iss.copy(selected = it) }, colors = CheckboxDefaults.colors(checkedColor = P3.accent),
                                    modifier = Modifier.size(22.dp).padding(top = 2.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(iss.category, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = P3.text)
                                    Spacer(Modifier.width(8.dp))
                                    Text(iss.severity, Modifier.clip(Sq.full).background(sevBg).padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 11.sp, color = sevColor, fontWeight = FontWeight.SemiBold)
                                }
                                if (iss.claim.isNotBlank()) Text(iss.claim, Modifier.padding(top = 6.dp), fontSize = 13.sp, color = P3.text, lineHeight = 19.sp)
                                if (iss.correction.isNotBlank()) Text("改正：${iss.correction}", Modifier.padding(top = 4.dp), fontSize = 13.sp, color = P3.accent, lineHeight = 19.sp)
                                if (iss.note.isNotBlank()) Text(iss.note, Modifier.padding(top = 4.dp), fontSize = 12.sp, color = P3.text3)
                                if (iss.source.isNotBlank()) Text(iss.source, Modifier.padding(top = 6.dp), fontSize = 11.sp, color = P3.text3)
                            }
                        }
                    }
                }
            }
            val selectable = issues.filter { it.correction.isNotBlank() && it.severity != "低" }
            if (selectable.isNotEmpty()) {
                val n = selectable.count { it.selected }
                PrimaryButton(if (n == 0) "请先勾选要改正的项" else "一键改正（$n）",
                    Modifier.padding(horizontal = 16.dp).padding(top = 4.dp), enabled = n > 0) {
                    onApply(issues.filter { it.selected && it.correction.isNotBlank() }); onDismiss()
                }
            }
        }
    }
}
