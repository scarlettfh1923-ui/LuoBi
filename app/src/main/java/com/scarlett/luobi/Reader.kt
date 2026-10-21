@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
package com.scarlett.luobi

import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester

// ---------------- 结果页：阅读 & 配图（结构照搬小红书助手） ----------------
@Composable
fun ReaderScreen(db: AppDb, id: Long, running: Boolean, back: () -> Unit, switchTo: (Long) -> Unit, goSub: (String) -> Unit, start: (GenJob) -> Unit) {
    val ctx = LocalContext.current; val s = remember { Settings(ctx) }; val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf(false) }
    val noteState by remember(id) { db.notes().byId(id).map { it to true } }.collectAsState(initial = null to false)
    LaunchedEffect(noteState) { if (!deleting && noteState.second && noteState.first == null) { snack("这篇文章已不存在（可能已被删除）"); back() } }
    var font by remember { mutableFloatStateOf(s.fontSize) }
    var mode by remember { mutableIntStateOf(s.readMode.coerceIn(0, readPalettes.lastIndex)) }
    var fontId by remember { mutableStateOf(s.readFont) }
    var tab by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var showFont by remember { mutableStateOf(false) }
    var xhsSheet by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    var verMenu by remember { mutableStateOf(false) }
    var confirmDel by remember { mutableStateOf(false) }
    var style by remember { mutableIntStateOf(s.style.coerceIn(0, Render.styles.lastIndex)) }
    var ratio by remember { mutableIntStateOf(s.cardRatio.coerceIn(0, CardRatio.entries.lastIndex)) }
    var aiBg by remember { mutableStateOf<Bitmap?>(null) }
    var imgs by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var bgBusy by remember { mutableStateOf(false) }
    val pal = readPalettes[mode]
    val n = noteState.first ?: run { Box(Modifier.fillMaxSize().background(pal.bg)); return }
    val versions by remember(n.root) { db.notes().versions(n.root) }.collectAsState(initial = emptyList())
    val channels by db.channels().all().collectAsState(initial = emptyList())
    var spacing by remember { mutableFloatStateOf(s.cardSpacing) }
    var cardErr by remember { mutableStateOf<String?>(null) }
    var cardBusy by remember { mutableStateOf(true) }
    val fontReady = Fonts.ready(ctx, fontId)
    LaunchedEffect(n.title, n.body, n.tags, style, aiBg, fontId, fontReady, font, spacing, ratio) {
        cardBusy = true; cardErr = null
        delay(180)
        try {
            imgs = withContext(Dispatchers.Default) {
                val fid = if (fontReady) fontId else "sans"
                val reg = Fonts.typeface(ctx, fid, false); val bold = Fonts.typeface(ctx, fid, true)
                val r = CardRatio.fromIndex(ratio)
                listOf(Render.cover(n.title, n.tagList(), Render.styles[style], aiBg, bold, r.w, r.h)) +
                    Render.pages(n.title, n.body, Render.styles[style], reg, font * 46f / 18f, spacing, bold, r.w, r.h)
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
        } catch (e: OutOfMemoryError) {
            cardErr = "文章太长，配图生成不了（手机内存不够）。建议调小字号或行距，或把文章拆成两篇。"
        } catch (e: Exception) {
            val f = Errors.explain(e); cardErr = f.title + "\n" + f.detail
        } finally { cardBusy = false }
    }
    val family = remember(fontId, Fonts.progress[fontId]) { Fonts.family(ctx, fontId) ?: FontFamily.Default }
    fun genBg() {
        bgBusy = true
        scope.launch {
            try { aiBg = Ai.image(ctx, "文章封面背景图，主题：${n.title}。简洁、高级、冷色调，留白充足，画面中不要出现任何文字", if (ratio == 1) "9:16" else "3:4") }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { snackErr(e, "封面背景生成失败：", "重试") { genBg() } }
            bgBusy = false
        }
    }
    fun rewrite(ins: String) { if (!running) start(GenJob(kind = "rewrite", noteId = n.id, instruction = ins, model = s.defaultModel)) }
    val il = remember { Illustrator(ctx, db, scope, goSub) }; il.note = n
    var capEdit by remember { mutableStateOf<Pic?>(null) }
    var barTick by remember { mutableIntStateOf(0) }
    val act = remember(n, pal, fontId) { ChannelActions(ctx, n, { imgs }, pal, fontId, goSub) }
    act.openCards = { panel = false; tab = 1 }
    androidx.activity.compose.BackHandler(enabled = tab == 1) { tab = 0 }
    act.openXhs = { if (imgs.isNotEmpty()) xhsSheet = true else snack("配图还在生成，请稍候") }

    // 1.11 事实校对：不挡阅读；顶部胶囊进度；结果底部弹层；文中高亮；一键改正
    var factBusy by remember { mutableStateOf(false) }
    var factReport by remember { mutableStateOf<FactReport?>(null) }
    var factSheet by remember { mutableStateOf(false) }
    var factFocus by remember { mutableStateOf<String?>(null) }
    fun runFactCheck() {
        if (factBusy) return
        factBusy = true; factSheet = false
        scope.launch {
            try {
                factReport = withContext(Dispatchers.IO) { FactCheck.run(ctx, n.title, n.body, n.model) }
                factSheet = true
                if (factReport!!.issues.isEmpty()) snack("没有发现硬事实问题")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: CleanParse.BadFormat) { snack("校对结果格式不对，请重试", false, "重试") { runFactCheck() } }
            catch (e: Throwable) { snackErr(e, "事实校对失败：", "重试") { runFactCheck() } }
            finally { factBusy = false }
        }
    }
    fun applyFactFixes(list: List<FactIssue>) {
        val body2 = FactCheck.applyFixes(n.body, list)
        val title2 = list.fold(n.title) { t, iss -> if (iss.span.isNotBlank() && t.contains(iss.span) && iss.correction.isNotBlank()) t.replaceFirst(iss.span, iss.correction) else t }
        if (body2 == n.body && title2 == n.title) { snack("没有可应用的改正", false); return }
        scope.launch { db.notes().update(n.copy(title = title2, body = body2)); snack("已改正 ${list.size} 处") }
    }

    val backdrop = rememberBackdrop()
    CompositionLocalProvider(LocalBackdrop provides backdrop) {
    Scaffold(containerColor = pal.bg, topBar = {
        // 1.5：浮动返回圆 + 右侧浮动动作胶囊；正文在它们下面滚动
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            FloatCircle(Ic.back, pal.text, "返回", pal.dark) { if (tab == 1) tab = 0 else back() }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (tab == 1) Text("配图", Modifier.frosted(elevation = 6.dp, dark = pal.dark).padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = pal.text)
            }
            if (tab == 0) FloatGroup(pal.dark) {
                FloatAction(Ic.search, if (factBusy) pal.accent else pal.text, "事实校对") { runFactCheck() }
                FloatAction(Ic.imgplus, pal.text, "文中插图") { il.start() }
                FloatAction(Ic.text, if (showFont) pal.accent else pal.text, "阅读设置") { showFont = !showFont }
                FloatAction(Ic.write, pal.text, "编辑") { editing = true }
            } else Spacer(Modifier.width(44.dp))
        }
    }, bottomBar = {
        // 1.4：毛玻璃悬浮胶囊（方案 B）。正文在胶囊下面滚动；ArticleView 底部留出 bottomBar 高度 + 余量
        val glass = (if (pal.dark) pal.surface else Color.White).copy(alpha = .86f)
        val rim = if (pal.dark) Color.White.copy(alpha = .10f) else Color.Black.copy(alpha = .07f)
        if (tab == 0 && il.props.isNotEmpty()) Capsule(glass, rim, pal.bg) {
                Text("已插 ${il.inserted} · 待选 ${il.props.size}", Modifier.weight(1f).padding(start = 8.dp), fontSize = 13.sp, color = pal.sub, maxLines = 1, softWrap = false)
                Text("全部插入", Modifier.clip(Sq.full).clickable { il.insertAll() }.padding(horizontal = 14.dp, vertical = 10.dp), fontSize = 14.sp, color = pal.text, fontWeight = FontWeight.Medium)
                Box(Modifier.height(44.dp).clip(Sq.full).background(pal.accent).clickable { il.done() }.padding(horizontal = 22.dp), contentAlignment = Alignment.Center) { Text("完成", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold) }
        } else if (tab == 1) Capsule(glass, rim, pal.bg) {
                Row(Modifier.fillMaxHeight().clip(Sq.full).clickable { scope.launch { if (imgs.isEmpty()) snack("配图还在生成，请稍候") else { val c = withContext(Dispatchers.IO) { Share.save(ctx, imgs) }; snack("已保存 $c 张图片到相册「落笔」") } } }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    SvgIcon(Ic.download, pal.text, 20.dp); Text(" 存相册", fontSize = 14.sp, color = pal.text, maxLines = 1, softWrap = false)
                }
                Spacer(Modifier.width(6.dp))
                Row(Modifier.weight(1f).height(44.dp).clip(Sq.full).background(pal.accent).clickable { act.openXhs() }, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    SvgIcon(Ic.send, Color.White, 18.dp); Spacer(Modifier.width(6.dp)); Text("分享到小红书", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                }
        } else FloatingBar(dark = pal.dark) {
                // 分享栏：所选渠道（1–5）+ 固定最右「更多」；detail.png 网格；选中态只换颜色
                val bar = remember(barTick) { s.shareBar }
                (bar.mapNotNull { ChannelIds.def(it, channels) } + ChannelIds.more).forEach { d ->
                    GridCell(d.icon, d.name, d.id == "xhs", if (pal.dark) pal.text else NavInk, if (pal.dark) pal.sub else NavInk, busy = act.busy == d.name) { if (d.id == ShareBar.MORE) panel = true else act.run(d.id, channels) }
                }
        }
    }) { pad ->
        Box(Modifier.fillMaxSize()) {
            Crossfade(tab, Modifier.fillMaxSize().backdropSource(backdrop), label = "tab") { t ->
                if (t == 0) Box(Modifier.fillMaxSize()) {
                    ArticleView(n, versions, font, pal, family, pad.calculateBottomPadding() + 16.dp, top = pad.calculateTopPadding(), openVersions = { verMenu = true }, onTags = { tags -> scope.launch { db.notes().update(n.copy(tags = tags.joinToString(" "))) } }, il = il, editCaption = { capEdit = it },
                        highlights = factReport?.issues?.map { it.span }?.filter { it.isNotBlank() } ?: emptyList(), focusSpan = factFocus)
                    FactProgressChip(factBusy, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp))
                }
                else Box(Modifier.padding(top = pad.calculateTopPadding())) { Gallery(imgs, style, { style = it; s.style = it }, spacing, { spacing = it; s.cardSpacing = it }, ratio, { ratio = it; s.cardRatio = it }, s.imgOn, bgBusy, aiBg != null, pal, pad.calculateBottomPadding(),
                    cardBusy = cardBusy, cardErr = cardErr, onAiBg = { genBg() }, clearBg = { aiBg = null },
                    save = { scope.launch { val c = withContext(Dispatchers.IO) { Share.save(ctx, imgs) }; snack("已保存 $c 张图片到相册「落笔」") } }) }
            }
            Column(Modifier.padding(top = pad.calculateTopPadding())) {
                if (tab == 0) AnalyzingCard(il)
                AnimatedVisibility(showFont && tab == 0, enter = Motion.cardIn, exit = Motion.cardOut) { AaPanel(font, { font = it; s.fontSize = it }, fontId, { fontId = it; s.readFont = it }, mode, { mode = it; s.readMode = it }, pal) }
            }
        }
    }
    }
    if (verMenu) ModalBottomSheet(shape = Sq.topXl, onDismissRequest = { verMenu = false }, containerColor = P3.surface) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("版本记录", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("每改写一次存一个版本，点一下切换 / 退回", Modifier.padding(top = 2.dp, bottom = 10.dp), fontSize = 13.sp, color = P3.text2)
            versions.reversed().forEach { v ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(Sq.m).background(if (v.id == n.id) P3.accentSoft else P3.surface2).clickable { verMenu = false; switchTo(v.id) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("第 ${v.version} 版", fontFamily = Mono, fontSize = 13.sp, color = if (v.id == n.id) P3.onAccentSoft else P3.text, fontWeight = FontWeight.SemiBold)
                    Text("  " + (v.instruction.ifBlank { "初稿" }) + " · ${timeLabel(v.createdAt)}", Modifier.weight(1f), fontSize = 13.sp, color = P3.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (v.id == n.id) Text("当前", fontSize = 12.sp, color = P3.accent)
                }
            }
            if (versions.size > 1 && n.version > 1) TextButton(onClick = { verMenu = false; confirmDel = true }, shape = Sq.full) { Text("删除当前版本", color = P3.danger) }
        }
    }
    if (confirmDel) ConfirmDialog("删除第 ${n.version} 版？", "其它版本保留，删除后会退回到上一版。", "删除", true, onOk = {
        val prev = versions.lastOrNull { it.version < n.version } ?: versions.firstOrNull { it.id != n.id }
        deleting = true; scope.launch { db.notes().deleteId(n.id); if (prev != null) switchTo(prev.id) else back() }
    }) { confirmDel = false }
    if (custom) {
        var ins by remember { mutableStateOf("") }
        AlertDialog(shape = Sq.xl, onDismissRequest = { custom = false }, containerColor = P3.surface, title = { Text("自定义改写") },
            text = { InputBox(ins, { ins = it }, "比如：开头更抓人；加一个真实例子；改成第一人称", minHeight = 90.dp) },
            confirmButton = { TextButton(onClick = { custom = false; if (ins.isNotBlank()) rewrite(ins.trim()) }, shape = Sq.full) { Text("改写", color = P3.accent) } },
            dismissButton = { TextButton(onClick = { custom = false }, shape = Sq.full) { Text("取消", color = P3.text2) } })
    }
    capEdit?.let { pic ->
        var c by remember(pic) { mutableStateOf(pic.caption) }
        AlertDialog(shape = Sq.xl, onDismissRequest = { capEdit = null }, containerColor = P3.surface, title = { Text("编辑图注") },
            text = { InputBox(c, { c = it }, "一句话说明这张图（可留空）", minHeight = 60.dp) },
            confirmButton = { TextButton(onClick = { il.caption(pic, c.trim()); capEdit = null }, shape = Sq.full) { Text("保存", color = P3.accent) } },
            dismissButton = { TextButton(onClick = { capEdit = null }, shape = Sq.full) { Text("取消", color = P3.text2) } })
    }
    if (xhsSheet) XhsSheet(n, imgs, pal) { xhsSheet = false }
    if (panel) SharePanel(channels, act, { panel = false; goSub("sharebar") }, onFactCheck = { panel = false; runFactCheck() }) { panel = false; barTick++ }
    if (factSheet && factReport != null) FactSheet(factReport!!, onJump = { factFocus = it.span; factSheet = false; scope.launch { kotlinx.coroutines.delay(400); factSheet = true } }, onApply = { applyFactFixes(it) }, onDismiss = { factSheet = false })
    if (editing) EditScreen(n, { editing = false }) { scope.launch { db.notes().update(it) }; editing = false; snack("已保存修改") }
}

@Composable
fun ArticleView(n: Note, versions: List<Note>, font: Float, pal: ReadPalette, family: FontFamily, bottom: Dp, top: Dp = 0.dp, openVersions: () -> Unit, onTags: (List<String>) -> Unit,
                il: Illustrator? = null, editCaption: (Pic) -> Unit = {}, highlights: List<String> = emptyList(), focusSpan: String? = null) {
    val paras = Export.paras(n.body)
    val ctx = LocalContext.current
    val byPara = Pics.byPara(n.pics(), paras.size)
    val showCredit = remember { Settings(ctx).showCredit }
    var paraMenu by remember { mutableStateOf(-1) }
    val scroll = rememberScrollState()
    val bringers = remember(paras.size) { List(paras.size) { BringIntoViewRequester() } }
    LaunchedEffect(focusSpan) {
        val span = focusSpan ?: return@LaunchedEffect
        val i = paras.indexOfFirst { it.contains(span) }
        if (i >= 0) { kotlinx.coroutines.delay(80); bringers[i].bringIntoView() }
    }
    @Composable fun after(i: Int) {
        byPara[i]?.forEach { pic -> PicView(ctx, pic, pal, showCredit, { il?.here(pic.after, paras.getOrElse(pic.after) { "" }, pic) }, { editCaption(pic) }, { il?.remove(pic) }) }
        val ps = il?.props ?: return
        ps.filter { it.after == i }.forEach { p ->
            val head = (i downTo 0).firstOrNull { isHead(paras[it], paras.size) }?.let { headText(paras[it]).replace("**", "") } ?: paras[i].take(14)
            ProposalCard(ctx, p, head, ps.indexOf(p) + 1, ps.size, il, pal)
        }
    }
    @Composable fun menu(i: Int, p: String) {
        val cm = androidx.compose.ui.platform.LocalClipboardManager.current
        DropdownMenu(paraMenu == i, { paraMenu = -1 }, shape = Sq.m, containerColor = P3.surface) {
            DropdownMenuItem({ Text("在此插图") }, { paraMenu = -1; il?.here(i, p) }, leadingIcon = { SvgIcon(Ic.image, P3.accent, 18.dp) })
            DropdownMenuItem({ Text("复制段落") }, { paraMenu = -1; cm.setText(androidx.compose.ui.text.AnnotatedString(headText(p).replace("**", ""))); snack("已复制段落") }, leadingIcon = { SvgIcon(Ic.copy, P3.text2, 18.dp) })
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 24.dp)) {
        Spacer(Modifier.height(top + 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(Sq.s).clickable { openVersions() }) {
            Text(listOf(n.templateName.ifBlank { "文章" }, n.model.substringBefore(" · ").ifBlank { "" } + n.model.substringAfter(" · ", "").let { if (it.isNotBlank()) " $it" else "" }, "第 ${n.version} 版")
                .filter { it.isNotBlank() }.joinToString("  ·  "), color = pal.sub, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (versions.size > 1) Text("  共 ${versions.size} 版 ›", color = pal.accent, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text(n.title, color = pal.text, fontFamily = family, fontWeight = FontWeight.Bold, fontSize = (font + 8).sp, lineHeight = ((font + 8) * 1.38f).sp)
        Spacer(Modifier.height(10.dp))
        Text("${timeLabel(n.createdAt)} · ${"%,d".format(n.body.length)} 字 · 约 ${readMinutes(n.body.length)} 分钟", color = pal.sub, fontSize = 13.sp, fontFamily = Mono)
        Spacer(Modifier.height(12.dp))
        TagEditor(n.tagList(), onTags, pal.accent, pal.bg, pal.text, pal.dark, collapsible = true)
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(thickness = Dp.Hairline, color = pal.border)
        Spacer(Modifier.height(18.dp))
        paras.forEachIndexed { i, p ->
          val hit = highlights.firstOrNull { it.isNotBlank() && p.contains(it) }
          val focused = focusSpan != null && focusSpan.isNotBlank() && p.contains(focusSpan)
          Box(Modifier.fillMaxWidth().bringIntoViewRequester(bringers[i]).clip(Sq.s)
              .background(when { focused || hit != null -> Color(0xFFFFF3CD); paraMenu == i -> pal.soft; else -> Color.Transparent })
              .combinedClickable(onClick = {}, onLongClick = { paraMenu = i }).padding(vertical = if (hit != null) 4.dp else 0.dp)) {
           Column {
            val ml = Md.line(p, paras.size)
            val nextKind = paras.getOrNull(i + 1)?.let { Md.line(it, paras.size).kind }
            ArticleBlock(ml, nextKind, font, pal, family)
           }
           menu(i, p)
          }
          after(i)
        }
        Spacer(Modifier.height(16.dp))
        Text("— 完 —", Modifier.fillMaxWidth(), color = pal.sub, textAlign = TextAlign.Center, fontSize = 13.sp)
        Spacer(Modifier.height(bottom + 24.dp))
    }
}

/** 分享到小红书：流程和小红书助手 ShareSheet + Share.sendImages 完全一样（先依次复制 标题 / 标签 / 正文，再把卡片交给小红书） */
@Composable
fun XhsSheet(n: Note, imgs: List<Bitmap>, pal: ReadPalette, dismiss: () -> Unit) {
    val ctx = LocalContext.current
    val plainBody = n.body.lines().joinToString("\n") { if (it.trim().startsWith("#")) headText(it.trim()) else it }.replace("**", "")
    val items = listOf(Triple("标题", "粘贴到标题栏", n.title), Triple("标签", "粘贴到正文末尾或话题栏", n.tagList().joinToString(" ") { "#$it" }), Triple("正文", "粘贴到正文栏", plainBody))
    var copied by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { for ((i, it) in items.withIndex()) { Share.copy(ctx, it.first, it.third); copied = i + 1; if (i < items.lastIndex) delay(1000) } }
    ModalBottomSheet(shape = Sq.topXl, onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = P3.surface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = P3.borderStrong) }, contentWindowInsets = { WindowInsets(0) }) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 12.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).clip(Sq.full).background(P3.accentSoft), contentAlignment = Alignment.Center) {
                        if (copied < 3) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = P3.accent) else SvgIcon(Ic.check, P3.accent, 20.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(if (copied < 3) "正在复制 $copied / 3…" else "已复制 3 条到剪贴板", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = P3.text)
                        Text("进入小红书后，在输入法剪贴板里依次点选粘贴", fontSize = 12.sp, color = P3.text2)
                    }
                }
                items.forEachIndexed { i, (h, where, text) ->
                    Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(Sq.m).background(P3.surface2).hairline(Sq.m, P3.border)
                        .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) { Dot(if (copied > i) P3.accent else P3.text3.copy(alpha = .5f)); Text("  ${i + 1} · $h → $where", fontSize = 12.sp, color = P3.accent, fontWeight = FontWeight.SemiBold) }
                            Text(text, Modifier.padding(top = 4.dp), fontSize = if (i == 0) 15.sp else 13.sp, color = P3.text, fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
                        }
                        IconButton(onClick = { Share.copy(ctx, h, text); snack("已重新复制$h") }, enabled = copied >= 3) { SvgIcon(Ic.copy, if (copied >= 3) P3.accent else P3.text3, 20.dp, desc = "重新复制$h") }
                    }
                }
                Text("按 标题 → 标签 → 正文 顺序、每条间隔约 1 秒写入，输入法剪贴板历史（如搜狗/百度/Gboard）会同时保留。", Modifier.padding(top = 10.dp), fontSize = 12.sp, lineHeight = 18.sp, color = P3.text2)
            }
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = dismiss, Modifier.weight(1f), shape = Sq.full) { Text("返回", color = P3.text2, fontSize = 15.sp) }
                Button(onClick = { Share.sendImages(ctx, n, imgs); dismiss() }, Modifier.weight(2f).height(48.dp), enabled = copied >= 3, shape = Sq.full,
                    colors = ButtonDefaults.buttonColors(containerColor = P3.accent)) { Text("去小红书发布", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
fun AaPanel(font: Float, setFont: (Float) -> Unit, fontId: String, setFontId: (String) -> Unit, mode: Int, setMode: (Int) -> Unit, pal: ReadPalette) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).shadow(6.dp, Sq.xl).clip(Sq.xl).background(pal.surface).hairline(Sq.xl, pal.border).padding(16.dp)) {
        Text("字号 · ${font.toInt()}", fontSize = 12.sp, color = pal.sub)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("A", color = pal.sub, fontSize = 13.sp)
            SoftSlider(font, setFont, 14f..26f, 1f, pal.accent, pal.sub.copy(alpha = .25f), pal.surface, Modifier.weight(1f).padding(horizontal = 10.dp))
            Text("A", color = pal.sub, fontSize = 22.sp)
        }
        Text("字体", fontSize = 12.sp, color = pal.sub, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Fonts.list.forEach { f ->
                val sel = f.id == fontId; val ready = Fonts.ready(ctx, f.id); val prog = Fonts.progress[f.id]
                val fam = remember(f.id, ready, prog) { Fonts.family(ctx, f.id) }
                Column(Modifier.weight(1f).clip(Sq.m).background(if (sel) pal.accent.copy(alpha = .12f) else pal.bg)
                    .hairline(Sq.m, if (sel) pal.accent else pal.border, if (sel) 2.dp else 1.dp)
                    .clickable {
                        if (ready) setFontId(f.id)
                        else if (prog == null || prog < 0f) scope.launch {
                            try { Fonts.download(ctx, f); setFontId(f.id); snack("${f.name} 已下载") } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { snackErr(e, "字体下载失败：") }
                        }
                    }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("永和", Modifier.padding(top = 8.dp), fontFamily = fam ?: FontFamily.Default, fontSize = 22.sp, color = pal.text.copy(alpha = if (fam == null) .45f else 1f))
                    val label = when { ready -> f.name; prog != null && prog >= 0f -> "${(prog * 100).toInt()}%"; prog != null -> "失败·重试"; else -> "下载 ${f.sizeMb}MB" }
                    Text(label, Modifier.padding(bottom = 8.dp), fontSize = 12.sp, maxLines = 1, color = if (sel) pal.accent else pal.sub)
                    if (prog != null && prog >= 0f) LinearProgressIndicator({ prog }, Modifier.fillMaxWidth().height(3.dp), color = pal.accent, trackColor = pal.sub.copy(alpha = .2f))
                }
            }
        }
        Text("阅读模式", fontSize = 12.sp, color = pal.sub, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            readPalettes.forEachIndexed { i, p ->
                Box(Modifier.weight(1f).height(36.dp).clip(Sq.full).background(p.bg)
                    .hairline(Sq.full, if (mode == i) pal.accent else pal.border, if (mode == i) 2.dp else 1.dp)
                    .clickable { setMode(i) }, contentAlignment = Alignment.Center) { Text(p.name, color = p.text, fontSize = 13.sp) }
            }
        }
    }
}

@Composable
fun Gallery(imgs: List<Bitmap>, style: Int, setStyle: (Int) -> Unit, spacing: Float, setSpacing: (Float) -> Unit, ratio: Int, setRatio: (Int) -> Unit, imgOn: Boolean, bgBusy: Boolean, hasBg: Boolean,
            pal: ReadPalette, bottom: androidx.compose.ui.unit.Dp, cardBusy: Boolean = false, cardErr: String? = null, onAiBg: () -> Unit, clearBg: () -> Unit, save: () -> Unit) {
    var liveSpacing by remember(spacing) { mutableFloatStateOf(spacing) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Render.styles.forEachIndexed { i, st ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(Sq.m).clickable { setStyle(i) }.padding(2.dp)) {
                    Box(Modifier.size(46.dp).hairline(Sq.full, if (style == i) pal.accent else Color.Transparent, 2.dp).padding(4.dp).clip(Sq.full).background(Color(st.bg2)).hairline(Sq.full, pal.border), contentAlignment = Alignment.Center) {
                        if (style == i) SvgIcon(Ic.check, Color(st.accent), 16.dp)
                    }
                    Text(st.name, Modifier.padding(top = 2.dp), fontSize = 12.sp, color = if (style == i) pal.accent else pal.sub)
                }
            }
        }
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(Sq.xl).background(pal.surface).hairline(Sq.xl, pal.border).padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("正文排版", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.text)
                val many = imgs.size > 18
                Text("共 ${imgs.size} 张", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (many) Color(0xFFC0392B) else pal.accent,
                    modifier = Modifier.clip(Sq.full).background((if (many) Color(0xFFC0392B) else pal.accent).copy(alpha = .12f)).padding(horizontal = 10.dp, vertical = 3.dp))
            }
            Text("画面比例", fontSize = 12.sp, color = pal.sub, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CardRatio.entries.forEachIndexed { i, r ->
                    val sel = ratio == i
                    val label = "${r.label} ${r.sub}"
                    Box(Modifier.weight(1f).height(40.dp).clip(Sq.m)
                        .background(if (sel) pal.accent.copy(alpha = .14f) else pal.bg)
                        .hairline(Sq.m, if (sel) pal.accent else pal.border, if (sel) 1.5.dp else 1.dp)
                        .clickable { setRatio(i) }, contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 13.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, color = if (sel) pal.accent else pal.sub, maxLines = 1)
                    }
                }
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("行距 ${"%.1f".format(liveSpacing)}", fontSize = 12.sp, color = pal.sub, modifier = Modifier.width(56.dp))
                SoftSlider(liveSpacing, { liveSpacing = it }, 1.0f..1.3f, 0.05f, pal.accent, pal.sub.copy(alpha = .25f), pal.surface, Modifier.weight(1f),
                    onChangeFinished = { setSpacing(it) })
            }
            Text(if (imgs.size > 18) "小红书单篇最多 18 张，可调小行距或字号" else "换比例后封面与正文图会重新分页", fontSize = 12.sp, color = if (imgs.size > 18) Color(0xFFC0392B) else pal.sub)
        }
        Spacer(Modifier.height(14.dp))
        if (imgs.isEmpty()) Box(Modifier.fillMaxWidth().height(400.dp), contentAlignment = Alignment.Center) {
            if (cardErr != null) Text(cardErr, color = Color(0xFFC0392B), fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
            else CircularProgressIndicator(color = pal.accent)
        }
        else {
            val ps = rememberPagerState { imgs.size }
            HorizontalPager(ps, contentPadding = PaddingValues(horizontal = 48.dp), pageSpacing = 12.dp) { i ->
                Image(imgs[i].asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(CardRatio.fromIndex(ratio).aspect).hairline(Sq.m, pal.border).clip(Sq.m), contentScale = ContentScale.Fit)
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                val cur = ps.currentPage; val win = if (imgs.size <= 9) 0 until imgs.size else (cur - 4).coerceIn(0, imgs.size - 9).let { it until it + 9 }
                win.forEach { i -> Box(Modifier.padding(horizontal = 3.dp).height(6.dp).width(if (i == cur) 16.dp else 6.dp).clip(Sq.xs).background(if (i == cur) pal.accent else pal.sub.copy(alpha = .3f))) }
            }
            Text("${ps.currentPage + 1} / ${imgs.size}  ·  ${if (ps.currentPage == 0) "封面" else "正文"}  ·  ${CardRatio.fromIndex(ratio).label}", fontFamily = Mono, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), textAlign = TextAlign.Center, color = pal.sub, fontSize = 12.sp)
        }
        Column(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            OutlinedButton(onClick = save, enabled = imgs.isNotEmpty(), shape = Sq.full, border = null, modifier = Modifier.hairline(Sq.full, pal.border)) {
                SvgIcon(Ic.download, pal.accent, 18.dp); Spacer(Modifier.width(6.dp)); Text("保存到相册", color = pal.text)
            }
            if (imgOn) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onAiBg, enabled = !bgBusy, shape = Sq.full) { SvgIcon(Ic.wand, pal.accent, 16.dp); Spacer(Modifier.width(4.dp)); Text(if (bgBusy) "生成中…" else if (hasBg) "换一张背景" else "AI 生成封面背景", color = pal.accent) }
                if (hasBg) TextButton(onClick = clearBg, shape = Sq.full) { Text("去掉背景", color = pal.sub) }
            }
        }
        Spacer(Modifier.height(bottom + 16.dp))
    }
}

@Composable
fun EditScreen(n: Note, dismiss: () -> Unit, save: (Note) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var t by remember { mutableStateOf(n.title) }; var b by remember { mutableStateOf(n.body) }; var g by remember { mutableStateOf(n.tagList()) }
    val changed = t != n.title || b != n.body || g != n.tagList()
    var confirm by remember { mutableStateOf(false) }
    val close = { if (changed) confirm = true else dismiss() }
    androidx.compose.ui.window.Dialog(onDismissRequest = close, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = close) { SvgIcon(Ic.close, cs.onSurface, desc = "关闭") }
                    Text("编辑文章", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Button(onClick = { save(n.copy(title = t.trim(), body = b, tags = g.joinToString(" "))) }, enabled = changed && t.isNotBlank(), shape = Sq.full,
                        contentPadding = PaddingValues(horizontal = 18.dp), modifier = Modifier.padding(end = 8.dp).height(36.dp)) { Text("保存", fontSize = 15.sp) }
                }
                Column(Modifier.padding(horizontal = 20.dp).weight(1f)) {
                    BasicTextField(t, { t = it.replace("\n", "") }, Modifier.fillMaxWidth().padding(top = 8.dp),
                        textStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 31.sp, color = cs.onSurface), cursorBrush = SolidColor(cs.primary),
                        decorationBox = { inner -> Box { if (t.isEmpty()) Text("标题", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = t3()); inner() } })
                    HorizontalDivider(Modifier.padding(vertical = 12.dp), thickness = Dp.Hairline, color = cs.outlineVariant)
                    TagEditor(g, { g = it }, cs.primary, cs.background, cs.onSurface, false, collapsible = false)
                    HorizontalDivider(Modifier.padding(vertical = 12.dp), thickness = Dp.Hairline, color = cs.outlineVariant)
                    BasicTextField(b, { b = it }, Modifier.fillMaxWidth().weight(1f),
                        textStyle = TextStyle(fontSize = 16.sp, lineHeight = 27.sp, color = cs.onSurface), cursorBrush = SolidColor(cs.primary),
                        decorationBox = { inner -> Box { if (b.isEmpty()) Text("正文", fontSize = 16.sp, color = t3()); inner() } })
                    Text("正文 ${b.length} 字" + if (changed) " · 未保存" else "", Modifier.fillMaxWidth().padding(vertical = 8.dp), textAlign = TextAlign.End, fontSize = 12.sp, color = t3())
                }
            }
            if (confirm) AlertDialog(shape = Sq.xl, onDismissRequest = { confirm = false }, title = { Text("放弃修改？") }, text = { Text("你有未保存的修改，离开后将丢失。") },
                confirmButton = { TextButton(onClick = { confirm = false; dismiss() }, shape = Sq.full) { Text("放弃", color = Color(0xFFC0392B)) } },
                dismissButton = { TextButton(onClick = { confirm = false }, shape = Sq.full) { Text("继续编辑") } })
        }
    }
}

/** 行内粗体 → AnnotatedString（金句 **加粗**） */
fun mdAnnotated(t: String) = androidx.compose.ui.text.buildAnnotatedString {
    Md.spans(t).forEach { (x, b) -> if (b) { pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)); append(x); pop() } else append(x) }
}

/** 阅读排版 A：段距大于行距；H2 / H3 加粗大于正文；引用左竖线 + 浅底 + 弱色；列表与分隔线 */
@Composable
fun ArticleBlock(ml: MdLine, next: MdKind?, font: Float, pal: ReadPalette, family: FontFamily) {
    val body = androidx.compose.ui.text.TextStyle(color = pal.text, fontSize = font.sp, lineHeight = (font * 1.8f).sp, letterSpacing = 0.2.sp, fontFamily = family)
    val paraGap = (font * 1.0f).dp
    when (ml.kind) {
        MdKind.H2, MdKind.H3 -> if (Md.sectionNo(ml.text) != null) Row(Modifier.padding(top = (font * 0.9f).dp, bottom = (font * 0.45f).dp), verticalAlignment = Alignment.CenterVertically) {
            // 1.4：AI 偶尔只写「## 1」，显示成小号蓝色序号「01」+ 短线
            Text("%02d".format(Md.sectionNo(ml.text)), color = pal.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, letterSpacing = 1.sp)
            Box(Modifier.padding(start = 10.dp).width(28.dp).height(1.5.dp).background(pal.accent.copy(alpha = .7f)))
        } else if (ml.kind == MdKind.H2) { val hs = font * 1.24f; Text(mdAnnotated(ml.text), Modifier.padding(top = (font * 0.9f).dp, bottom = (font * 0.5f).dp), color = pal.text, fontWeight = FontWeight.Bold, fontSize = hs.sp, lineHeight = (hs * 1.45f).sp, fontFamily = family) }
          else { val hs = font * 1.08f; Text(mdAnnotated(ml.text), Modifier.padding(top = (font * 0.6f).dp, bottom = (font * 0.4f).dp), color = pal.text, fontWeight = FontWeight.Bold, fontSize = hs.sp, lineHeight = (hs * 1.45f).sp, fontFamily = family) }
        MdKind.QUOTE -> Row(Modifier.padding(top = 4.dp, bottom = paraGap).fillMaxWidth().height(IntrinsicSize.Min).clip(Sq.rightS).background(if (pal.dark) pal.soft else Color(0xFFF1F4F8))) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(if (pal.dark) pal.sub else Color(0xFFCBD5E1)))
            Text(mdAnnotated(ml.text), Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = body.copy(color = if (pal.dark) pal.sub else Color(0xFF5B6576), fontSize = (font * 0.95f).sp))
        }
        MdKind.HR -> Box(Modifier.padding(vertical = (font * 0.8f).dp).fillMaxWidth().height(1.dp).background(pal.border))
        MdKind.BULLET, MdKind.NUM -> Row(Modifier.padding(bottom = if (next == MdKind.BULLET || next == MdKind.NUM) (font * 0.35f).dp else paraGap)) {
            Text(if (ml.kind == MdKind.BULLET) "•" else ml.num + ".", Modifier.width(if (ml.kind == MdKind.BULLET) (font * 1.1f).dp else (font * 1.6f).dp), style = body)
            Text(mdAnnotated(ml.text), style = body)
        }
        MdKind.P -> Text(mdAnnotated(ml.text), Modifier.padding(bottom = paraGap), style = body)
    }
}
