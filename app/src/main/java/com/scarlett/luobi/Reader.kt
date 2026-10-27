@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
package com.scarlett.luobi

import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription

import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.semantics.selected

import androidx.compose.ui.graphics.toArgb

import androidx.compose.ui.graphics.nativeCanvas

import androidx.compose.ui.focus.focusProperties

import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.ui.composed

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.testTag
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
    // 1.18：看过刚写好的这篇 → 创作页的「已写好」卡回去时淡出收起；通知也一并收掉
    LaunchedEffect(id) { if (GenCard.state.value?.noteId == id) { GenCard.markViewed(ctx, id); CopyWorker.clearDoneNotice(ctx) } }
    val noteState by remember(id) { db.notes().byId(id).map { it to true } }.collectAsState(initial = null to false)
    LaunchedEffect(noteState) { if (!deleting && noteState.second && noteState.first == null) { snack("这篇文章已不存在（可能已被删除）"); back() } }
    var font by remember { mutableFloatStateOf(s.fontSize) }
    var mode by remember { mutableIntStateOf(s.readMode.coerceIn(0, readPalettes.lastIndex)) }
    var fontId by remember { mutableStateOf(s.readFont) }
    var tab by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    // 2.1：从事实校对「手动改」进来时要选中的原句；存完回到校对页
    var editSel by remember { mutableStateOf<String?>(null) }
    var factReturn by remember { mutableStateOf(false) }
    var showFont by remember { mutableStateOf(false) }
    var xhsSheet by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    var verMenu by remember { mutableStateOf(false) }
    var confirmDel by remember { mutableStateOf(false) }
    var style by remember { mutableIntStateOf(s.style.coerceIn(0, Render.styles.lastIndex)) }
    var coverK by remember { mutableIntStateOf(s.coverKind.coerceIn(0, CoverKind.entries.lastIndex)) }
    var bodyK by remember { mutableIntStateOf(s.bodyKind.coerceIn(0, BodyKind.entries.lastIndex)) }
    var thumbs by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
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
    LaunchedEffect(n.title, n.body, n.tags, style, aiBg, fontId, fontReady, font, spacing, ratio, coverK, bodyK) {
        cardBusy = true; cardErr = null
        delay(180)
        try {
            imgs = withContext(Dispatchers.Default) {
                val fid = if (fontReady) fontId else "sans"
                val reg = Fonts.typeface(ctx, fid, false); val bold = Fonts.typeface(ctx, fid, true)
                val r = CardRatio.fromIndex(ratio)
                // 1.19：AI 背景沿用旧封面（深色叠字）；否则按所选封面风格
                val cov = if (aiBg != null) Render.cover(n.title, n.tagList(), Render.styles[style], aiBg, bold, r.w, r.h)
                    else Covers.render(CoverKind.of(coverK), n.title, n.tagList(), n.body, r.w, r.h)
                listOf(cov) +
                    Render.pages(n.title, n.body, Render.styles[style], reg, font * 46f / 18f, spacing, bold, r.w, r.h,
                        pics = PicCache.bitmaps(ctx, n.pics(), Export.paras(n.body).size, Settings(ctx).showCredit, maxPx = 2048), kind = BodyKind.of(bodyK)).also { CardHint.dropped = Render.lastDroppedPics }
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
        } catch (e: OutOfMemoryError) {
            cardErr = "文章太长，配图生成不了（手机内存不够）。建议调小字号或行距，或把文章拆成两篇。"
        } catch (e: Exception) {
            val f = Errors.explain(e); cardErr = f.title + "\n" + f.detail
        } finally { cardBusy = false }
    }
    LaunchedEffect(n.title, n.body, n.tags) {
        thumbs = withContext(Dispatchers.Default) { runCatching { Covers.thumbs(n.title, n.tagList(), n.body) }.getOrDefault(emptyList()) }
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
    // 2.2：上次生成被截断（finish_reason=length）→ 文末提示「续写」
    var truncated by remember(n.id) { mutableStateOf(Ai.Truncated.has(ctx, n.id)) }
    fun rewrite(ins: String) { if (!running) start(GenJob(kind = "rewrite", noteId = n.id, instruction = ins, model = s.defaultModel)) }
    val il = remember { Illustrator(ctx, db, scope, goSub) }; il.note = n
    var capEdit by remember { mutableStateOf<Pic?>(null) }
    var barTick by remember { mutableIntStateOf(0) }
    val act = remember(n, pal, fontId) { ChannelActions(ctx, n, { imgs }, pal, fontId, goSub) }
    act.openCards = { panel = false; tab = 1 }
    androidx.activity.compose.BackHandler(enabled = tab == 1) { tab = 0 }
    act.openXhs = { if (imgs.isNotEmpty()) xhsSheet = true else snack("配图还在生成，请稍候") }

    // 1.12 事实校对：缓存上次结果；点图标重开不重跑；「重新检测」才重跑；建议可手改；勾选应用
    // 1.17：校对在后台跑（WorkManager 前台任务）；这里只看任务行和 WorkManager 状态
    val factJob by remember(n.id) { db.factJobs().flow(n.id) }.collectAsState(initial = null)
    val factWork by remember(n.id) { androidx.work.WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(FactWorker.name(n.id)) }.collectAsState(initial = null)
    val factActive = factWork?.let { l -> l.any { !it.state.isFinished } }
    var factReport by remember(n.id) { mutableStateOf(FactCheck.decodeReport(n.factReportJson)) }
    LaunchedEffect(n.id, n.factReportJson) { factReport = FactCheck.decodeReport(n.factReportJson) }
    val factState = FactBadge.state(factJob, factReport != null, factActive)
    val factBusy = factState == FactBadge.S.RUNNING
    var factSheet by remember { mutableStateOf(false) }
    var factFromCache by remember { mutableStateOf(false) }
    var factFocus by remember { mutableStateOf<String?>(null) }
    var catSheet by remember { mutableStateOf(false) }
    var factPreErr by remember { mutableStateOf<String?>(null) }
    val notifGate = rememberNotifGate()
    fun persistFact(r: FactReport?) {
        factReport = r
        val json = r?.let { FactCheck.encodeReport(it) }.orEmpty()
        scope.launch { db.notes().setFactReport(n.id, json) }
    }
    fun openFactSheet(fromCache: Boolean) {
        if (factReport == null) return
        factFromCache = fromCache
        factSheet = true
        scope.launch { db.factJobs().markSeen(n.id) }
        FactWorker.clearDone(ctx, n.id)
    }
    fun runFactCheck() {
        if (factBusy) { snack("正在后台校对，完成后会通知你"); return }
        factSheet = false
        // 2.0.1：第一次校对前才问通知权限；拒绝也照常校对
        notifGate { scope.launch {
            try {
                val pre = withContext(Dispatchers.IO) { FactPreflight.check(ctx, n.model) }
                if (pre.fatal != null) { factPreErr = pre.fatal; return@launch }
                withContext(Dispatchers.IO) { FactWorker.start(ctx, n.id, n.title, fresh = true) }
                factSheet = true  // 2.1：结果逐条流进列表
                snack(pre.warn?.let { "已开始后台校对 · $it" } ?: "已开始后台校对，可以继续阅读，完成后会通知你") }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { snackErr(e, "事实校对没能开始：") }
        } }
    }
    fun onFactIcon() {
        if (factBusy) { factSheet = true; return }
        if (factReport != null) openFactSheet(fromCache = true)
        else runFactCheck()
    }
    // 从「校对完成」通知点进来：直接弹出这篇的结果
    val openFact by AppState.openFact.collectAsState()
    LaunchedEffect(openFact, factReport) { if (openFact == n.id && factReport != null) { AppState.openFact.value = 0L; openFactSheet(fromCache = false) } }
    fun applyFactFixes(list: List<FactIssue>) {
        val body2 = FactCheck.applyFixes(n.body, list)
        val title2 = list.fold(n.title) { t, iss -> if (iss.span.isNotBlank() && t.contains(iss.span) && iss.correction.isNotBlank()) t.replaceFirst(iss.span, iss.correction) else t }
        if (body2 == n.body && title2 == n.title) { snack("没有可应用的改正", false); return }
        val appliedIds = list.filter { iss ->
            (iss.span.isNotBlank() && (n.body.contains(iss.span) || n.title.contains(iss.span)))
        }.map { it.id }.toSet()
        val next = factReport?.let { FactCheck.afterApply(it, appliedIds) }
        val json = next?.let { FactCheck.encodeReport(it) }.orEmpty()
        factReport = next
        scope.launch {
            db.notes().update(n.copy(title = title2, body = body2, factReportJson = json))
            snack("已改正 ${appliedIds.size} 处")
            // 仍有未应用项时保持弹层；否则关掉
            if (next == null || next.issues.none { !it.applied }) factSheet = false
        }
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
                FloatAction(Ic.search, if (factBusy) pal.accent else pal.text, if (factBusy) "事实校对（进行中）" else "事实校对",
                    badge = if (factState == FactBadge.S.DOT) -1 else 0,
                    progress = if (factBusy) factJob?.let { j -> if (j.total > 0) j.done.toFloat() / j.total else -1f } ?: -1f else null) { onFactIcon() } // 1.17：没看过的结果才显示红点；跑着时显示细进度环
                FloatAction(Ic.imgplus, pal.text, "文中插图") { il.start() }
                FloatAction(Ic.text, if (showFont) pal.accent else pal.text, "阅读设置") { showFont = !showFont }
                FloatAction(Ic.write, pal.text, "编辑") { editing = true }
            } else Spacer(Modifier.width(44.dp))
        }
    }, bottomBar = {
        // 1.4：毛玻璃悬浮胶囊（方案 B）。正文在胶囊下面滚动；ArticleView 底部留出 bottomBar 高度 + 余量
        val glass = (if (pal.dark) pal.surface else pal.bg).copy(alpha = .86f)
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
                    GridCell(d.icon, d.name, false /* 1.16：分享栏是动作按钮，永不显示选中态（之前把小红书硬编码成选中） */, if (pal.dark) pal.text else NavInk, if (pal.dark) pal.sub else NavInk, busy = act.busy == d.name) { if (d.id == ShareBar.MORE) panel = true else act.run(d.id, channels) }
                }
        }
    }) { pad ->
        Box(Modifier.fillMaxSize()) {
            Crossfade(tab, Modifier.fillMaxSize().backdropSource(backdrop), label = "tab") { t -> CompositionLocalProvider(LocalBackdrop provides null) { // 1.18.1：来源内部不取样
                if (t == 0) Box(Modifier.fillMaxSize()) {
                    ArticleView(n, versions, font, pal, family, pad.calculateBottomPadding() + 16.dp, top = pad.calculateTopPadding(), truncated = truncated, onContinue = { if (running) snack("正在生成，稍后再续写", false) else { Ai.Truncated.clear(ctx, n.id); truncated = false; rewrite(Ai.CONTINUE) } }, onIgnore = { Ai.Truncated.clear(ctx, n.id); truncated = false }, openVersions = { verMenu = true }, onTags = { tags -> scope.launch { db.notes().update(n.copy(tags = tags.joinToString(" "))) } }, il = il, editCaption = { capEdit = it },
                        highlights = emptyList() /* 1.15.1：阅读优先，不在正文上画黄色高亮 */, focusSpan = factFocus, onCategory = { catSheet = true })
                    // 1.16：正文铺满全屏，状态栏下一道柔和渐隐
                    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(with(androidx.compose.ui.platform.LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() } + 28.dp)
                        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(pal.bg, pal.bg.copy(alpha = .85f), pal.bg.copy(alpha = 0f)))))
                }
                else Box(Modifier.padding(top = pad.calculateTopPadding())) { Gallery(imgs, thumbs, coverK, { coverK = it; s.coverKind = it; aiBg = null }, bodyK, { bodyK = it; s.bodyKind = it }, spacing, { spacing = it; s.cardSpacing = it }, ratio, { ratio = it; s.cardRatio = it }, s.imgOn, bgBusy, aiBg != null, pal, pad.calculateBottomPadding(),
                    cardBusy = cardBusy, cardErr = cardErr, onAiBg = { genBg() }, clearBg = { aiBg = null },
                    save = { scope.launch { val c = withContext(Dispatchers.IO) { Share.save(ctx, imgs) }; snack("已保存 $c 张图片到相册「落笔」") } }) }
            } }
            Column(Modifier.padding(top = pad.calculateTopPadding())) {
                if (tab == 0) AnalyzingCard(il)
                AnimatedVisibility(showFont && tab == 0, enter = Motion.cardIn, exit = Motion.cardOut) { AaPanel(font, { font = it; s.fontSize = it }, fontId, { fontId = it; s.readFont = it }, mode, { mode = it; s.readMode = it }, pal) }
            }
        }
    }
    }
    if (verMenu) AppSheet(shape = Sq.topXl, onDismissRequest = { verMenu = false }, containerColor = P3.surface) {
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
            if (versions.size > 1 && n.version > 1) PlainButton(onClick = { verMenu = false; confirmDel = true }, shape = Sq.full) { Text("删除当前版本", color = P3.danger) }
        }
    }
    if (confirmDel) ConfirmDialog("删除第 ${n.version} 版？", "其它版本保留，删除后会退回到上一版。", "删除", true, onOk = {
        val prev = versions.lastOrNull { it.version < n.version } ?: versions.firstOrNull { it.id != n.id }
        deleting = true; scope.launch { db.notes().deleteId(n.id); if (prev != null) switchTo(prev.id) else back() }
    }) { confirmDel = false }
    if (custom) {
        var ins by remember { mutableStateOf("") }
        GlassAlert("自定义改写", null, listOf(AlertAction("改写") { if (ins.isNotBlank()) rewrite(ins.trim()) }, AlertAction("取消", AlertAction.Role.Cancel) {}), { custom = false }) {
            InputBox(ins, { ins = it }, "比如：开头更抓人；加一个真实例子；改成第一人称", minHeight = 90.dp)
        }
    }
    capEdit?.let { pic ->
        var c by remember(pic) { mutableStateOf(pic.caption) }
        GlassAlert("编辑图注", null, listOf(AlertAction("保存") { il.caption(pic, c.trim()) }, AlertAction("取消", AlertAction.Role.Cancel) {}), { capEdit = null }) {
            InputBox(c, { c = it }, "一句话说明这张图（可留空）", minHeight = 60.dp)
        }
    }
    if (act.askWechat) WechatChoice(act)
    if (xhsSheet) XhsSheet(n, imgs, pal) { xhsSheet = false }
    if (panel) SharePanel(channels, act, { panel = false; goSub("sharebar") }, onFactCheck = { panel = false; onFactIcon() },
        info = ArticleInfo.line(n), versionsLabel = if (versions.size > 1) "共 ${versions.size} 版 ›" else "", onVersions = { panel = false; verMenu = true }) { panel = false; barTick++ }
    factPreErr?.let { m -> GlassAlert("事实校对没能开始", m, listOf(AlertAction("知道了", AlertAction.Role.Default) {}), { factPreErr = null }) }
    if (catSheet) CategorySheet(n.category, n.categoryAi, onPick = { c -> scope.launch { db.notes().setCategory(n.root, c) } }) { catSheet = false }
    if (factSheet) {
        val st = remember(factJob?.stateJson) { FactPipeline.decodeState(factJob?.stateJson.orEmpty()) }
        val j = factJob
        val view = FactView(
            claims = if (factBusy) st?.claims.orEmpty() else factReport?.claims ?: st?.claims.orEmpty(),
            triage = if (factBusy) st?.triage else factReport?.triage ?: st?.triage,
            running = factBusy,
            stopped = if (!factBusy && factReport == null && j != null && j.status == FactWorker.FAILED)
                (j.error.ifBlank { "已停止" } + if (j.total > 0) " · 已查 ${j.done}/${j.total}" else "") else null,
            report = if (factBusy) null else factReport)
        FactScreen(view, n.title, n.body,
            onBack = { factSheet = false },
            onStop = { scope.launch { withContext(Dispatchers.IO) { FactWorker.cancel(ctx, n.id) } } },
            onReCheck = { runFactCheck() },
            onReport = { persistFact(it) },
            onApply = { t, b, r -> factReport = r; val json = FactCheck.encodeReport(r); scope.launch { db.notes().update(n.copy(title = t, body = b, factReportJson = json)) } },
            onManual = { c -> factSheet = false; editSel = c.span; factReturn = true; editing = true },
            llm = { sys, user -> FactPipeline.llm(ctx, n.model).ask(sys, user, true) },
            openClaim = factFocus.also { factFocus = null })
    }
    if (editing) EditScreen(n, { editing = false; editSel = null; if (factReturn) { factReturn = false; factSheet = true } }, select = editSel) {
        scope.launch { db.notes().update(it) }; editing = false; editSel = null; snack("已保存修改"); if (factReturn) { factReturn = false; factSheet = true } }
}

@Composable
fun ArticleView(n: Note, versions: List<Note>, font: Float, pal: ReadPalette, family: FontFamily, bottom: Dp, top: Dp = 0.dp, openVersions: () -> Unit, onTags: (List<String>) -> Unit,
                il: Illustrator? = null, editCaption: (Pic) -> Unit = {}, highlights: List<String> = emptyList(), focusSpan: String? = null, onCategory: () -> Unit = {},
                truncated: Boolean = false, onContinue: () -> Unit = {}, onIgnore: () -> Unit = {}) {
    val paras = Export.paras(n.body)
    val ctx = LocalContext.current
    val byPara = Pics.byPara(n.pics(), paras.size)
    val showCredit = remember { Settings(ctx).showCredit }
    var paraMenu by remember { mutableStateOf(-1) }
    val scroll = rememberScrollState()
    // 1.16：刚生成好的文章，段落依次淡入上浮（0.38s 间隔、上浮 10px）；平时打开不动
    val fresh = remember(n.id) { System.currentTimeMillis() - n.createdAt < 90_000 && !Motion.reduced }
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
    // 2.0.2：正文按「整字格」排版——可用宽度取单字步进（字号 + 字距）的整数倍，余量左右平分，
    // 这样每行首字和末字到屏幕边的距离一致（以前左边固定 24dp、右边剩下零头，最多差 15dp，看起来像左边被切）
    val dens = androidx.compose.ui.platform.LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val side = remember(maxWidth, font, dens.fontScale) { readerSide(maxWidth.value, font, dens.fontScale).dp }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = side)) {
        Spacer(Modifier.height(top + 12.dp))
        // 1.15 方案 A：去掉灰色模板/模型/版本行（移到「更多」面板）；标题上方是分类标签
        CategoryLabel(n.category, pal.accent, pal.sub, onCategory)
        Spacer(Modifier.height(6.dp))
        Text(n.title, color = pal.text, fontFamily = family, fontWeight = FontWeight.Bold, fontSize = (font + 8).sp, lineHeight = ((font + 8) * 1.38f).sp)
        Spacer(Modifier.height(10.dp))
        // 1.15 复查：窄屏时整段换行，不把「约 1 / 分钟」拆开
        Text(listOf(timeLabel(n.createdAt), "${"%,d".format(n.body.length)} 字", "约 ${readMinutes(n.body.length)} 分钟").joinToString(" · ") { it.replace(' ', '\u00A0') }, color = pal.sub, fontSize = 13.sp, fontFamily = Mono)
        Spacer(Modifier.height(12.dp))
        TagEditor(n.tagList(), onTags, pal.accent, pal.bg, pal.text, pal.dark, collapsible = true)
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(thickness = Dp.Hairline, color = pal.border)
        Spacer(Modifier.height(18.dp))
        paras.forEachIndexed { i, p ->
          val hit = highlights.firstOrNull { it.isNotBlank() && p.contains(it) }
          val focused = focusSpan != null && focusSpan.isNotBlank() && p.contains(focusSpan)
          Box(Modifier.fillMaxWidth().riseIn(i, fresh).bringIntoViewRequester(bringers[i])
              .background(when { focused || hit != null -> Color(0xFFF5E9C8); paraMenu == i -> pal.soft; else -> Color.Transparent }, Sq.s)
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
        if (truncated) TruncatedNote(pal, onContinue, onIgnore)
        else Text("— 完 —", Modifier.fillMaxWidth(), color = pal.sub, textAlign = TextAlign.Center, fontSize = 13.sp)
        Spacer(Modifier.height(bottom + 24.dp))
    }
    }
}

/** 2.0.2：阅读页左右留白（dp）。最少 24dp，正文宽度取单字步进的整数倍，零头左右平分 */
fun readerSide(widthDp: Float, font: Float, fontScale: Float, min: Float = 24f): Float {
    val adv = (font + 0.2f) * fontScale
    val n = kotlin.math.floor((widthDp - 2 * min) / adv).coerceAtLeast(1f)
    // +0.5dp 容差，避免浮点误差让最后一个字被挤到下一行
    return ((widthDp - n * adv - 0.5f) / 2f).coerceAtLeast(min - 0.25f)
}

/** 分享到小红书：流程和小红书助手 ShareSheet + Share.sendImages 完全一样（先依次复制 标题 / 标签 / 正文，再把卡片交给小红书） */
@Composable
fun XhsSheet(n: Note, imgs: List<Bitmap>, pal: ReadPalette, dismiss: () -> Unit) {
    val ctx = LocalContext.current
    val plainBody = n.body.lines().joinToString("\n") { if (it.trim().startsWith("#")) headText(it.trim()) else it }.replace("**", "")
    val items = listOf(Triple("标题", "粘贴到标题栏", n.title), Triple("标签", "粘贴到正文末尾或话题栏", n.tagList().joinToString(" ") { "#$it" }), Triple("正文", "粘贴到正文栏", plainBody))
    var copied by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { for ((i, it) in items.withIndex()) { Share.copy(ctx, it.first, it.third); copied = i + 1; if (i < items.lastIndex) delay(1000) } }
    AppSheet(shape = Sq.topXl, onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = P3.surface,
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
                        IconTap(onClick = { Share.copy(ctx, h, text); snack("已重新复制$h") }, enabled = copied >= 3) { SvgIcon(Ic.copy, if (copied >= 3) P3.accent else P3.text3, 20.dp, desc = "重新复制$h") }
                    }
                }
                Text("按 标题 → 标签 → 正文 顺序、每条间隔约 1 秒写入，输入法剪贴板历史（如搜狗/百度/Gboard）会同时保留。", Modifier.padding(top = 10.dp), fontSize = 12.sp, lineHeight = 18.sp, color = P3.text2)
            }
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                PlainButton(onClick = dismiss, Modifier.weight(1f), shape = Sq.full) { Text("返回", color = P3.text2, fontSize = 15.sp) }
                FilledButton(onClick = { Share.sendImages(ctx, n, imgs); dismiss() }, Modifier.weight(2f).height(48.dp), enabled = copied >= 3, shape = Sq.full,
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
                Column(Modifier.weight(1f).height(78.dp).clip(Sq.m).background(if (sel) pal.accent.copy(alpha = .12f) else pal.bg)
                    .hairline(Sq.m, if (sel) pal.accent else pal.border, if (sel) 2.dp else 1.dp)
                    .clickable {
                        if (ready) setFontId(f.id)
                        else if (prog == null || prog < 0f) scope.launch {
                            try { Fonts.download(ctx, f); setFontId(f.id); snack("${f.name} 已下载") } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { snackErr(e, "字体下载失败：") }
                        }
                    }, horizontalAlignment = Alignment.CenterHorizontally) {
                    // 1.16.1：「永和」按实测字形边界居中（各字体字面框不同，按行高居中会上下偏）
                    val tf = remember(f.id, ready, prog) { if (ready) runCatching { Fonts.typeface(ctx, f.id, false) }.getOrNull() else null }
                    GlyphCentered("永和", tf, 22.sp, (if (sel) pal.accent else pal.text).copy(alpha = if (tf == null) .45f else 1f), Modifier.fillMaxWidth().weight(1f))
                    val label = when { ready -> f.name; prog != null && prog >= 0f -> "${(prog * 100).toInt()}%"; prog != null -> "失败·重试"; else -> "下载 ${f.sizeMb}MB" }
                    Text(label, Modifier.padding(bottom = 8.dp), fontSize = 11.sp, maxLines = 1, color = if (sel) pal.accent else pal.sub)
                    if (prog != null && prog >= 0f) LinearProgressIndicator({ prog }, Modifier.fillMaxWidth().height(3.dp), color = pal.accent, trackColor = pal.sub.copy(alpha = .2f))
                }
            }
        }
        Text("阅读模式", fontSize = 12.sp, color = pal.sub, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            readPalettes.forEachIndexed { i, p ->
                // 1.16.1 方案 A：色卡（Aa + 两道色条），选中描边主色
                Column(Modifier.weight(1f).semantics { selected = mode == i }.clickable { setMode(i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.fillMaxWidth().height(56.dp).clip(Sq.m).background(p.bg).hairline(Sq.m, if (mode == i) pal.accent else p.border, if (mode == i) 2.dp else 1.dp).padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text("Aa", color = p.text, fontSize = 16.sp, fontFamily = FontFamily.Serif, lineHeight = 18.sp)
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(18.dp).height(3.dp).clip(Sq.full).background(p.accent)); Spacer(Modifier.width(4.dp))
                            Box(Modifier.width(12.dp).height(3.dp).clip(Sq.full).background(p.sub))
                        }
                    }
                    Text(p.name, Modifier.padding(top = 4.dp), color = if (mode == i) pal.accent else pal.sub, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun Gallery(imgs: List<Bitmap>, thumbs: List<Bitmap>, cover: Int, setCover: (Int) -> Unit, body: Int, setBody: (Int) -> Unit, spacing: Float, setSpacing: (Float) -> Unit, ratio: Int, setRatio: (Int) -> Unit, imgOn: Boolean, bgBusy: Boolean, hasBg: Boolean,
            pal: ReadPalette, bottom: androidx.compose.ui.unit.Dp, cardBusy: Boolean = false, cardErr: String? = null, onAiBg: () -> Unit, clearBg: () -> Unit, save: () -> Unit) {
    var liveSpacing by remember(spacing) { mutableFloatStateOf(spacing) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("shareA")) {
        Spacer(Modifier.height(8.dp))
        PagerBlock(imgs, ratio, pal, cardErr)
        // 1.19 方案 A：预览在上，设置收进一张圆角面板
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth().clip(Sq.xl).background(pal.surface).hairline(Sq.xl, pal.border).padding(vertical = 14.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("封面风格", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.text)
                Text(if (hasBg) "AI 背景" else CoverKind.of(cover).label, fontSize = 12.sp, color = pal.sub)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CoverKind.entries.forEachIndexed { i, k ->
                    val sel = cover == i && !hasBg
                    Column(Modifier.width(64.dp).clip(Sq.m).clickable { setCover(i) }.testTag("cover_$i"), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(64.dp, 85.dp).clip(Sq.s).background(pal.bg).hairline(Sq.s, if (sel) pal.accent else pal.border, if (sel) 2.dp else 1.dp)) {
                            thumbs.getOrNull(i)?.let { Image(it.asImageBitmap(), k.label, Modifier.fillMaxSize().padding(if (sel) 2.dp else 0.dp).clip(Sq.xs), contentScale = ContentScale.Crop) }
                        }
                        Text(k.label, Modifier.padding(top = 4.dp), fontSize = 11.sp, maxLines = 1, color = if (sel) pal.accent else pal.sub, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
            Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().height(Dp.Hairline).background(pal.border))
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("正文版式", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.text)
                val many = imgs.size > 18
                Text("共 ${imgs.size} 张", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (many) P3.danger else pal.accent,
                    modifier = Modifier.clip(Sq.full).background((if (many) P3.danger else pal.accent).copy(alpha = .12f)).padding(horizontal = 10.dp, vertical = 3.dp))
            }
            Row(Modifier.padding(horizontal = 16.dp).padding(top = 10.dp).fillMaxWidth().height(36.dp).clip(Sq.m).background(pal.bg).padding(3.dp)) {
                BodyKind.entries.forEachIndexed { i, k ->
                    val sel = body == i
                    Box(Modifier.weight(1f).fillMaxHeight().clip(Sq.s).background(if (sel) pal.surface else Color.Transparent).then(if (sel) Modifier.hairline(Sq.s, pal.border) else Modifier)
                        .clickable { setBody(i) }.testTag("body_$i"), contentAlignment = Alignment.Center) {
                        Text(k.label, fontSize = 12.sp, maxLines = 1, color = if (sel) pal.accent else pal.sub, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                CardRatio.entries.forEachIndexed { i, r ->
                    val sel = ratio == i
                    Text(r.label, Modifier.padding(end = 8.dp).clip(Sq.full).background(if (sel) pal.accent.copy(alpha = .12f) else pal.bg)
                        .hairline(Sq.full, if (sel) pal.accent else Color.Transparent).clickable { setRatio(i) }.padding(horizontal = 14.dp, vertical = 6.dp),
                        fontSize = 12.sp, color = if (sel) pal.accent else pal.sub, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                }
                Spacer(Modifier.width(6.dp))
                Text("行距 ${String.format(java.util.Locale.US, "%.1f", liveSpacing)}", fontSize = 12.sp, color = pal.sub, fontFamily = Mono, modifier = Modifier.width(64.dp))
                SoftSlider(liveSpacing, { liveSpacing = it }, Render.LINE_MIN..Render.LINE_MAX, 0.1f, pal.accent, pal.sub.copy(alpha = .25f), pal.surface, Modifier.weight(1f),
                    onChangeFinished = { setSpacing(it) })
            }
            if (imgs.size > 18 || CardHint.dropped > 0) Text(if (imgs.size > 18) "小红书单篇最多 18 张，可调小行距或字号" else "小红书单篇最多 18 张，已省略后面 ${CardHint.dropped} 张文中配图",
                Modifier.padding(horizontal = 16.dp).padding(top = 8.dp), fontSize = 12.sp, color = if (imgs.size > 18) P3.danger else pal.sub)
        }
        Column(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // 1.16：「保存到相册」已在底部操作栏（存相册），这里不再重复，避免从底栏下面露出来
            if (imgOn) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PlainButton(onClick = onAiBg, enabled = !bgBusy, shape = Sq.full) { SvgIcon(Ic.wand, pal.accent, 16.dp); Spacer(Modifier.width(4.dp)); Text(if (bgBusy) "生成中…" else if (hasBg) "换一张背景" else "AI 生成封面背景", color = pal.accent) }
                if (hasBg) PlainButton(onClick = clearBg, shape = Sq.full) { Text("去掉背景", color = pal.sub) }
            }
        }
        Spacer(Modifier.height(bottom + 16.dp))
    }
}


@Composable
private fun PagerBlock(imgs: List<Bitmap>, ratio: Int, pal: ReadPalette, cardErr: String?) = Column(Modifier.fillMaxWidth()) {
        if (imgs.isEmpty()) Box(Modifier.fillMaxWidth().height(400.dp), contentAlignment = Alignment.Center) {
            if (cardErr != null) Text(cardErr, color = P3.danger, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp))
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
}

@Composable
fun EditScreen(n: Note, dismiss: () -> Unit, select: String? = null, save: (Note) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var t by remember { mutableStateOf(n.title) }
    // 2.1：「手动改」进来时选中那句原文
    var bv by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(n.body,
        select?.let { s -> n.body.indexOf(s).takeIf { it >= 0 }?.let { androidx.compose.ui.text.TextRange(it, it + s.length) } } ?: androidx.compose.ui.text.TextRange(0))) }
    val b = bv.text
    val bodyFocus = remember { androidx.compose.ui.focus.FocusRequester() }; var g by remember { mutableStateOf(n.tagList()) }
    val changed = t != n.title || b != n.body || g != n.tagList()
    var confirm by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(select != null && n.body.contains(select)) }
    // 1.15.1：进入时不弹键盘；点空白 / 返回 / 保存都收起键盘
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    fun hideKb() { focus.clearFocus(force = true); kb?.hide() }
    val close = { hideKb(); if (changed) confirm = true else dismiss() }
    androidx.compose.ui.window.Dialog(onDismissRequest = close, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Dialog 窗口默认的软键盘状态会在打开时把键盘带出来：改为「进入时隐藏 + adjustResize」
        // 焦点 / 键盘要取 Dialog 自己窗口里的，外层 Activity 的清不掉这里的焦点
        val focus = androidx.compose.ui.platform.LocalFocusManager.current
        val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        fun hideKb() { focus.clearFocus(force = true); kb?.hide() }
        val dv = androidx.compose.ui.platform.LocalView.current
        SideEffect {
            (dv.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        // 1.16：新版 Compose 的 Dialog 打开时会把初始焦点给第一个输入框，进来就清掉（不弹键盘）
        LaunchedEffect(Unit) { if (armed) { delay(200); runCatching { bodyFocus.requestFocus() } } else { focus.clearFocus(force = true); kb?.hide() } }
        Surface(Modifier.fillMaxSize(), color = cs.background) {
            // 1.16：用户第一次按下屏幕之前，输入框不可获得焦点（挡住 Dialog 的自动初始焦点）
            Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding()
                .pointerInput(Unit) { awaitPointerEventScope { while (true) { val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial); if (e.changes.any { it.pressed }) armed = true } } }
                .pointerInput(Unit) { detectTapGestures { hideKb() } }) {
                Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTap(onClick = { hideKb(); close() }) { SvgIcon(Ic.close, cs.onSurface, desc = "关闭") }
                    Text("编辑文章", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    FilledButton(onClick = { hideKb(); save(n.copy(title = t.trim(), body = b, tags = g.joinToString(" "))) }, enabled = changed && t.isNotBlank(), shape = Sq.full,
                        contentPadding = PaddingValues(horizontal = 18.dp), modifier = Modifier.padding(end = 8.dp).height(36.dp)) { Text("保存", fontSize = 15.sp) }
                }
                Column(Modifier.padding(horizontal = 20.dp).weight(1f)) {
                    BasicTextField(t, { t = it.replace("\n", "") }, Modifier.fillMaxWidth().padding(top = 8.dp).focusProperties { canFocus = armed },
                        textStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 31.sp, color = cs.onSurface), cursorBrush = SolidColor(cs.primary),
                        decorationBox = { inner -> Box { if (t.isEmpty()) Text("标题", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = t3()); inner() } })
                    HorizontalDivider(Modifier.padding(vertical = 12.dp), thickness = Dp.Hairline, color = cs.outlineVariant)
                    TagEditor(g, { g = it }, cs.primary, cs.background, cs.onSurface, false, collapsible = false)
                    HorizontalDivider(Modifier.padding(vertical = 12.dp), thickness = Dp.Hairline, color = cs.outlineVariant)
                    // 1.18.2：正文用 BasicTextField 自带的内部滚动（不再外套 verticalScroll）。
                    // 外套 verticalScroll 时输入框被量成全文高度，光标移动不会触发滚动，键盘弹出后光标被挡住。
                    // 内部滚动会自动把光标行滚到可见区（区域高度 = 屏幕 − 键盘，由外层 imePadding 决定）。
                    BasicTextField(bv, { bv = it }, Modifier.fillMaxWidth().weight(1f).testTag("editBody").focusRequester(bodyFocus).focusProperties { canFocus = armed },
                        textStyle = TextStyle(fontSize = 16.sp, lineHeight = 27.sp, color = cs.onSurface), cursorBrush = SolidColor(cs.primary),
                        decorationBox = { inner -> Box { if (b.isEmpty()) Text("正文", fontSize = 16.sp, color = t3()); inner() } })
                    Text("正文 ${b.length} 字" + if (changed) " · 未保存" else "", Modifier.fillMaxWidth().padding(vertical = 8.dp), textAlign = TextAlign.End, fontSize = 12.sp, color = t3())
                }
            }
            if (confirm) GlassAlert("放弃修改？", "你有未保存的修改，离开后将丢失。", listOf(AlertAction("放弃", AlertAction.Role.Destructive) { dismiss() }, AlertAction("继续编辑", AlertAction.Role.Cancel) {}), { confirm = false })
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
        MdKind.QUOTE -> Row(Modifier.padding(top = 4.dp, bottom = paraGap).fillMaxWidth().height(IntrinsicSize.Min).clip(Sq.rightS).background(if (pal.dark) pal.soft else Color(0xFFF2ECE1))) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(if (pal.dark) pal.sub else Color(0xFFD3C8B6)))
            Text(mdAnnotated(ml.text), Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = body.copy(color = if (pal.dark) pal.sub else Color(0xFF5C5348), fontSize = (font * 0.95f).sp))
        }
        MdKind.HR -> Box(Modifier.padding(vertical = (font * 0.8f).dp).fillMaxWidth().height(1.dp).background(pal.border))
        MdKind.BULLET, MdKind.NUM -> Row(Modifier.padding(bottom = if (next == MdKind.BULLET || next == MdKind.NUM) (font * 0.35f).dp else paraGap)) {
            Text(if (ml.kind == MdKind.BULLET) "•" else ml.num + ".", Modifier.width(if (ml.kind == MdKind.BULLET) (font * 1.1f).dp else (font * 1.6f).dp), style = body)
            Text(mdAnnotated(ml.text), style = body)
        }
        MdKind.P -> Text(mdAnnotated(ml.text), Modifier.padding(bottom = paraGap), style = body)
    }
}


/** 1.16：段落依次淡入上浮 */
fun Modifier.riseIn(i: Int, on: Boolean): Modifier = if (!on || i > 12) this else composed {
    val a = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { if (Motion.reduced) { a.snapTo(1f); return@LaunchedEffect }; kotlinx.coroutines.delay(380L * i); a.animateTo(1f, Motion.enter(420)) }
    graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 10f * density }
}


/** 1.16.1：配图页提示（省略了几张文中配图） */
object CardHint { var dropped by mutableStateOf(0) }


/** 按字形实测边界（Paint.getTextBounds）把文字放在正中 */
@Composable
fun GlyphCentered(text: String, tf: android.graphics.Typeface?, size: androidx.compose.ui.unit.TextUnit, color: Color, modifier: Modifier = Modifier) {
    val d = androidx.compose.ui.platform.LocalDensity.current
    val px = with(d) { size.toPx() }
    androidx.compose.foundation.Canvas(modifier.semantics { contentDescription = text }) {
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = px; typeface = tf ?: android.graphics.Typeface.DEFAULT; this.color = color.toArgb() }
        val r = android.graphics.Rect(); paint.getTextBounds(text, 0, text.length, r)
        val x = this.size.width / 2f - r.exactCenterX(); val y = this.size.height / 2f - r.exactCenterY()
        drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
    }
}

/** 2.2：输出被截断时的文末提示（暖琥珀，与全局 warn 一致；夜读用半透明） */
@Composable
fun TruncatedNote(pal: ReadPalette, onContinue: () -> Unit, onIgnore: () -> Unit) {
    val bg = if (pal.dark) Color(0x338A5513) else P3.warnBg
    val fg = if (pal.dark) Color(0xFFE8C48A) else P3.warnText
    Column(Modifier.fillMaxWidth().clip(Sq.l).background(bg).hairline(Sq.l, if (pal.dark) fg.copy(alpha = .35f) else P3.warnBorder).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text("这篇没写完", color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("模型的输出额度用完了，文章在这里被截断。可以让它接着写完，续写会存成新版本。", color = fg, fontSize = 13.sp, lineHeight = 19.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("续写", Modifier.clip(Sq.m).background(pal.accent).clickable(onClick = onContinue).padding(horizontal = 18.dp, vertical = 8.dp), color = if (pal.dark) Color(0xFF1B1916) else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("忽略", Modifier.clip(Sq.m).clickable(onClick = onIgnore).padding(horizontal = 12.dp, vertical = 8.dp), color = fg, fontSize = 14.sp)
        }
    }
}
