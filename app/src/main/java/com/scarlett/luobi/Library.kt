@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
package com.scarlett.luobi

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.absoluteValue

private val SortLabels = listOf("最新", "最早", "字数", "收藏")
private fun shortDate(t: Long) = SimpleDateFormat("M月d日", Locale.CHINA).format(Date(t))

@Composable
fun LibraryScreen(
    db: AppDb,
    bottom: Dp,
    goWrite: () -> Unit,
    managing: Boolean,
    setManaging: (Boolean) -> Unit,
    open: (Long) -> Unit,
) {
    val ctx = LocalContext.current
    val s = remember { Settings(ctx) }
    val cs = MaterialTheme.colorScheme
    val notes by db.notes().latest().collectAsState(initial = null)
    val templates by db.templates().all().collectAsState(initial = emptyList())
    var tplFilter by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) }
    var sort by remember { mutableIntStateOf(s.libSort.coerceIn(0, 3)) }
    var sortMenu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var confirmBatch by remember { mutableStateOf(false) }
    var confirmOne by remember { mutableStateOf<Note?>(null) }
    var openSwipeId by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    val all = notes ?: emptyList()

    BackHandler(managing) { setManaging(false); selected = emptySet() }

    val match = remember(all, q) {
        all.filter { n -> q.isBlank() || listOf(n.title, n.body, n.tags).any { it.contains(q, true) } }
    }
    val counts = listOf(match.size, match.count { it.favorite })
    val filtered = match.filter { n ->
        (filter != 1 || n.favorite) && (tplFilter.isBlank() || n.templateName == tplFilter)
    }
    val shown = when (sort) {
        1 -> filtered.sortedBy { it.createdAt }
        2 -> filtered.sortedByDescending { it.body.length }
        3 -> filtered.sortedWith(compareByDescending<Note> { it.favorite }.thenByDescending { it.createdAt })
        else -> filtered.sortedByDescending { it.createdAt }
    }
    val barBottom = if (managing) 84.dp else bottom

    fun deleteNotes(ids: List<Long>, undo: Note? = null) {
        scope.launch {
            if (undo != null) {
                db.notes().deleteRoots(listOf(undo.root))
                snack("已删除「${undo.title.take(12)}」")
            } else {
                db.notes().deleteRoots(all.filter { it.id in ids }.map { it.root })
                snack("已删除 ${ids.size} 篇")
            }
            selected = emptySet()
            openSwipeId = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(bottom = barBottom + 16.dp)) {
            item {
                if (managing) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { setManaging(false); selected = emptySet() }) { Text("完成", color = t3(), fontSize = 15.sp) }
                        Text("已选 ${selected.size} 篇", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        Spacer(Modifier.width(64.dp))
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text("文案库", fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp)
                            Text("共 ${all.size} 篇 · 收藏 ${all.count { it.favorite }}", Modifier.padding(top = 2.dp), fontSize = 13.sp, color = P3.text2)
                        }
                        Text("管理", Modifier.padding(top = 8.dp).clip(RoundedCornerShape(8.dp)).clickable {
                            openSwipeId = null; setManaging(true); selected = emptySet()
                        }.padding(horizontal = 6.dp, vertical = 6.dp), fontSize = 14.sp, color = cs.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(42.dp).clip(RoundedCornerShape(50)).background(P3.surface).border(1.dp, P3.border, RoundedCornerShape(50)).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    SvgIcon(Ic.search, t3(), 18.dp); Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f)) {
                        BasicTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = TextStyle(fontSize = 15.sp, color = cs.onSurface), cursorBrush = SolidColor(cs.primary))
                        if (q.isEmpty()) Text("搜索标题、正文、标签", fontSize = 15.sp, color = t3())
                    }
                    if (q.isNotEmpty()) Box(Modifier.size(28.dp).clip(CircleShape).clickable { q = "" }, contentAlignment = Alignment.Center) { SvgIcon(Ic.close, t3(), 16.dp, desc = "清空") }
                }
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        listOf("全部", "收藏").forEachIndexed { i, l ->
                            Column(Modifier.clickable { filter = i }, horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$l ${counts[i]}", fontSize = 14.sp, color = if (filter == i) cs.onSurface else t3(), fontWeight = if (filter == i) FontWeight.Bold else FontWeight.Normal)
                                Box(Modifier.padding(top = 4.dp).width(16.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(if (filter == i) cs.primary else Color.Transparent))
                            }
                        }
                    }
                    Box {
                        Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { sortMenu = true }.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            SvgIcon(Ic.swap, t3(), 14.dp); Spacer(Modifier.width(2.dp))
                            Text(SortLabels[sort], fontSize = 12.sp, color = t3())
                        }
                        DropdownMenu(sortMenu, { sortMenu = false }) {
                            SortLabels.forEachIndexed { i, l ->
                                DropdownMenuItem(
                                    text = { Text(l, fontWeight = if (i == sort) FontWeight.Bold else FontWeight.Normal, color = if (i == sort) cs.primary else cs.onSurface) },
                                    onClick = { sort = i; s.libSort = i; sortMenu = false })
                            }
                        }
                    }
                }
            }
            item {
                val names = all.map { it.templateName }.filter { it.isNotBlank() }.distinct()
                if (names.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("全部模板", tplFilter.isBlank()) { tplFilter = "" }
                    names.forEach { nm -> Pill(nm, tplFilter == nm) { tplFilter = if (tplFilter == nm) "" else nm } }
                }
            }
            if (notes != null && shown.isEmpty()) item {
                if (all.isEmpty()) EmptyState(R.drawable.ic_book, "还没有文章", "这里会保存你生成的每一篇文章，\n包括原始输入、意图、模板和最终提示词。", "去写第一篇", goWrite)
                else EmptyState(R.drawable.ic_search, "没有符合条件的文章", "换个关键词或筛选试试", null)
            }
            if (sort >= 2) {
                items(shown, key = { it.id }) { n ->
                    LibraryRow(n, managing, n.id in selected, openSwipeId == n.id,
                        onOpen = {
                            if (managing) selected = if (n.id in selected) selected - n.id else selected + n.id
                            else { openSwipeId = null; open(n.id) }
                        },
                        onToggleFav = { scope.launch { db.notes().setFav(n.root, !n.favorite) } },
                        onReveal = { openSwipeId = if (it) n.id else if (openSwipeId == n.id) null else openSwipeId },
                        onTapDelete = { confirmOne = n })
                }
            } else {
                shown.groupBy { dayLabel(it.createdAt) }.forEach { (d, list) ->
                    item(key = "d$d$sort") { Text(d, Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp), fontSize = 12.sp, color = t3()) }
                    items(list, key = { it.id }) { n ->
                        LibraryRow(n, managing, n.id in selected, openSwipeId == n.id,
                            onOpen = {
                                if (managing) selected = if (n.id in selected) selected - n.id else selected + n.id
                                else { openSwipeId = null; open(n.id) }
                            },
                            onToggleFav = { scope.launch { db.notes().setFav(n.root, !n.favorite) } },
                            onReveal = { openSwipeId = if (it) n.id else if (openSwipeId == n.id) null else openSwipeId },
                            onTapDelete = { confirmOne = n })
                    }
                }
            }
        }

        if (managing) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(cs.background.copy(alpha = 0f), cs.background, cs.background))
            )) {
                Surface(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp).height(60.dp),
                    shape = RoundedCornerShape(50), color = P3.text, shadowElevation = 8.dp) {
                    Row(Modifier.fillMaxSize().padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("全选", Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                            selected = if (selected.size == shown.size) emptySet() else shown.map { it.id }.toSet()
                        }.padding(vertical = 8.dp, horizontal = 4.dp), color = Color.White, fontSize = 14.sp)
                        Spacer(Modifier.weight(1f))
                        Text("已选 ${selected.size}", color = P3.text3, fontSize = 13.sp)
                        Spacer(Modifier.width(12.dp))
                        Row(Modifier.height(44.dp).clip(RoundedCornerShape(50)).background(if (selected.isEmpty()) Color(0xFF4B5563) else P3.danger)
                            .clickable(enabled = selected.isNotEmpty()) { confirmBatch = true }.padding(horizontal = 18.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            SvgIcon(Ic.trash, Color.White, 18.dp); Spacer(Modifier.width(6.dp))
                            Text("删除 (${selected.size})", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }

    if (confirmBatch) {
        AlertDialog(onDismissRequest = { confirmBatch = false },
            title = { Text("删除 ${selected.size} 篇文章？") },
            text = { Text("包括所有版本，删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selected.toList(); confirmBatch = false
                    deleteNotes(ids); setManaging(false)
                }) { Text("删除", color = P3.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmBatch = false }) { Text("取消") } })
    }
    confirmOne?.let { n ->
        AlertDialog(onDismissRequest = { confirmOne = null; openSwipeId = null },
            title = { Text("删除这篇文章？") },
            text = { Text("「${n.title}」删除后无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmOne = null
                    deleteNotes(listOf(n.id), undo = n)
                }) { Text("删除", color = P3.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmOne = null; openSwipeId = null }) { Text("取消") } })
    }
}

@Composable
private fun ElevCard(modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().border(1.dp, P3.border, shape).then(
            if (selected) Modifier.border(1.5.dp, cs.primary.copy(alpha = .4f), shape) else Modifier
        ),
        shape = shape,
        color = if (selected) P3.accentSoft else P3.surface,
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        border = null,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), content = content)
    }
}

@Composable
private fun NoteTitleBlock(n: Note, showHeart: Boolean, onToggleFav: () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Text(n.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 23.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = P3.text)
    Row(Modifier.padding(top = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (n.templateName.isNotBlank()) Text(n.templateName, fontSize = 11.sp, color = P3.onAccentSoft,
            modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(50)).background(P3.accentSoft).padding(horizontal = 8.dp, vertical = 2.dp))
        Text(shortDate(n.createdAt) + " · " + "%,d".format(n.body.length) + " 字" + if (n.version > 1) " · v${n.version}" else "", fontSize = 12.sp, color = t3(), fontFamily = Mono)
        Spacer(Modifier.weight(1f))
        if (showHeart) Box(Modifier.size(32.dp).clip(CircleShape).clickable { onToggleFav() }, contentAlignment = Alignment.Center) {
            SvgIcon(Ic.heart, if (n.favorite) P3.accent else t3(), 18.dp, if (n.favorite) P3.accent else null, "收藏")
        }
    }
}

@Composable
private fun LibraryRow(
    n: Note, managing: Boolean, selected: Boolean, revealed: Boolean,
    onOpen: () -> Unit, onToggleFav: () -> Unit, onReveal: (Boolean) -> Unit, onTapDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val actionPx = with(density) { 80.dp.toPx() }
    val scope = rememberCoroutineScope()
    val offset = remember(n.id) { Animatable(0f) }

    LaunchedEffect(revealed, managing) {
        if (managing) offset.snapTo(0f)
        else offset.animateTo(if (revealed) actionPx else 0f, spring())
    }

    if (managing) {
        ElevCard(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), selected = selected, onClick = onOpen) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.padding(top = 2.dp).size(22.dp).clip(CircleShape)
                    .background(if (selected) cs.primary else Color.Transparent)
                    .border(1.5.dp, if (selected) cs.primary else cs.outline, CircleShape), contentAlignment = Alignment.Center) {
                    if (selected) SvgIcon(Ic.check, Color.White, 12.dp)
                }
                Column(Modifier.weight(1f)) { NoteTitleBlock(n, showHeart = false) }
            }
        }
    } else {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))) {
            // Left reveal: red delete — TAP required
            Box(Modifier.matchParentSize().background(P3.danger), contentAlignment = Alignment.CenterStart) {
                Column(
                    Modifier.width(80.dp).fillMaxHeight().clickable { onTapDelete() },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    SvgIcon(Ic.trash, Color.White, 20.dp)
                    Text("删除", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            ElevCard(
                modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset(offset.value.toInt(), 0) }
                    .pointerInput(n.id) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                scope.launch {
                                    val open = offset.value > actionPx * 0.4f
                                    offset.animateTo(if (open) actionPx else 0f, spring())
                                    onReveal(open)
                                }
                            },
                            onHorizontalDrag = { _, dx ->
                                // right swipe (positive) reveals left button
                                scope.launch {
                                    offset.snapTo((offset.value + dx).coerceIn(0f, actionPx))
                                }
                            }
                        )
                    },
                onClick = {
                    if (offset.value > actionPx * 0.3f) {
                        scope.launch { offset.animateTo(0f, spring()); onReveal(false) }
                    } else onOpen()
                }
            ) {
                NoteTitleBlock(n, showHeart = true, onToggleFav = onToggleFav)
            }
        }
    }
}
