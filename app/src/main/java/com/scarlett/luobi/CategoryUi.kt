package com.scarlett.luobi

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 1.15 方案 A：阅读页标题上方的分类标签「电影 ›」；为空时「选择分类 ›」 */
@Composable
fun CategoryLabel(category: String, accent: Color, sub: Color, onClick: () -> Unit) {
    val empty = category.isBlank()
    Row(Modifier.clip(Sq.s).clickable(onClick = onClick).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (empty) "选择分类" else category, color = if (empty) sub else accent, fontSize = 14.sp, fontWeight = if (empty) FontWeight.Normal else FontWeight.Bold)
        Text(" ›", color = if (empty) sub else accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** 1.15 方案 A：iOS 列表式分类选择（单选、对勾、AI 推荐小字）；选中即生效，「完成」关闭 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategorySheet(current: String, aiPick: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var sel by remember(current) { mutableStateOf(current) }
    ModalBottomSheet(shape = Sq.topXl, onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = P3.bg, dragHandle = { BottomSheetDefaults.DragHandle(color = P3.borderStrong) }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("选择分类", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = P3.text)
                Text("完成", Modifier.clip(Sq.s).clickable(onClick = onDismiss).padding(6.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = P3.accent)
            }
            Text(if (aiPick.isNotBlank()) "AI 根据正文推荐了「$aiPick」，可改为其他分类" else "给这篇文章选一个分类",
                Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 12.dp), fontSize = 13.sp, color = P3.text3)
            Column(Modifier.padding(horizontal = 16.dp).clip(Sq.l).background(P3.surface).verticalScroll(rememberScrollState())) {
                Categories.LIST.forEachIndexed { i, c ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 16.dp), thickness = Dp.Hairline, color = P3.border)
                    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).clickable { sel = c; onPick(c) }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(c, fontSize = 16.sp, color = P3.text, fontWeight = if (c == sel) FontWeight.Bold else FontWeight.Normal)
                        if (c == aiPick) Text("  AI 推荐", fontSize = 11.sp, color = P3.text3)
                        Spacer(Modifier.weight(1f))
                        if (c == sel) SvgIcon(Ic.check, P3.accent, 20.dp)
                    }
                }
            }
        }
    }
}

/** 1.15 案头方案 A：白底圆角按钮「全部分类 ⌄」 */
@Composable
fun CategoryMenuButton(label: String, hidden: Boolean = false, onClick: () -> Unit) {
    // 1.16：在搜索框里；菜单展开时按钮淡出（菜单从按钮处长出来）
    val a by androidx.compose.animation.core.animateFloatAsState(if (hidden) 0f else 1f, Motion.enter(Motion.SHORT), label = "catBtn")
    Row(Modifier.graphicsLayer { alpha = a }.clip(Sq.full).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = P3.accent, maxLines = 1, modifier = Modifier.widthIn(max = 120.dp), overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Spacer(Modifier.width(3.dp)); SvgIcon(Ic.chevronDown, P3.accent, 14.dp)
    }
}

/**
 * 1.15 案头方案 A：iOS 下拉菜单（全部 + 15 个分类，计数，当前项 ✓）。
 * 动效用共享规范（Motion.enter/exit，从按钮左上角缩放淡入）；高度避开底部 tab 栏，放不下就在菜单里滚动。
 */
@Composable
fun CategoryMenu(open: Boolean, counts: List<Pair<String, Int>>, current: String, bottomBar: Dp, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val vis = remember { MutableTransitionState(false) }
    vis.targetState = open
    if (!vis.currentState && !vis.targetState) return
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    // 工具条下沿约在 190dp；预留底部 tab 栏 + 16dp
    val maxH = (screenH - 200.dp - bottomBar - 16.dp).coerceAtLeast(200.dp)
    // 1.16：按钮原地「长成」菜单——从按钮右上角弹簧展开，收起时缩回按钮
    Popup(alignment = Alignment.TopEnd, offset = androidx.compose.ui.unit.IntOffset(0, 0), onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        AnimatedVisibility(vis, enter = fadeIn(Motion.enter(Motion.SHORT)) + androidx.compose.animation.expandIn(Motion.soft(), expandFrom = Alignment.TopEnd) { androidx.compose.ui.unit.IntSize(it.width / 3, it.height / 12) },
            exit = fadeOut(Motion.exit()) + androidx.compose.animation.shrinkOut(Motion.soft(), shrinkTowards = Alignment.TopEnd) { androidx.compose.ui.unit.IntSize(it.width / 3, it.height / 12) }) {
            Column(Modifier.padding(top = 2.dp).width(230.dp).heightIn(max = maxH).shadowCard().clip(Sq.l).background(P3.surface).hairline(Sq.l, P3.borderStrong).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                counts.forEachIndexed { i, (c, n) ->
                    if (i > 0) HorizontalDivider(Modifier.padding(start = 42.dp, end = 10.dp), thickness = Dp.Hairline, color = P3.border)
                    val sel = c == current
                    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable { onPick(c) }.padding(start = 12.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(30.dp)) { if (sel) SvgIcon(Ic.check, P3.accent, 18.dp) }
                        Text(c.ifBlank { "全部" }, Modifier.weight(1f), fontSize = 15.sp, color = if (sel) P3.accent else P3.text, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                        Text("$n", fontSize = 14.sp, color = P3.text3)
                    }
                }
            }
        }
    }
}

private fun Modifier.shadowCard() = this.then(Modifier.shadow(16.dp, Sq.l, ambientColor = Color(0x22000000), spotColor = Color(0x33000000)))
