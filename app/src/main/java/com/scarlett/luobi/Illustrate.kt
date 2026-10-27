@file:OptIn(ExperimentalFoundationApi::class)
package com.scarlett.luobi

import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一处待选插图 */
class Proposal(val after: Int, val query: String, val replace: Pic? = null) {
    var page by mutableIntStateOf(1)
    var cands by mutableStateOf<List<Cand>>(emptyList())
    var sel by mutableIntStateOf(0)
    var loading by mutableStateOf(true)
    var err by mutableStateOf<String?>(null)
    var source by mutableStateOf("")
}

/** 文中插图的状态与动作（阅读页持有） */
class Illustrator(private val ctx: Context, private val db: AppDb, private val scope: CoroutineScope, private val goSub: (String) -> Unit) {
    val props = mutableStateListOf<Proposal>()
    var analyzing by mutableStateOf(false)
    var inserted by mutableIntStateOf(0)
    var note: Note? = null
    private val s get() = Settings(ctx)

    private fun modelRef() = s.defaultModel.ifBlank { s.configured().firstOrNull()?.ref ?: "" }

    fun start() {
        val n = note ?: return
        if (ImgSources.usable(ctx).isEmpty()) { snack("还没有可用的图片来源", false, "去设定") { goSub("imgsrc") }; return }
        if (modelRef().isBlank()) { snack("需要一个文字模型来通读全文，请先配置模型", false, "去设定") { goSub("models") }; return }
        if (analyzing) return
        analyzing = true; inserted = 0
        scope.launch {
            try {
                val paras = Export.paras(n.body)
                val mn = s.picMin.coerceIn(1, 6); val mx = s.picMax.coerceIn(mn, 6)
                val txt = Ai.chatText(Ai.target(ctx, modelRef()), "你是图文编辑。只输出一个合法 JSON 对象。" + Prompts22.NO_FAB_LINE, Pics.planPrompt(paras, mn, mx, s.picOrient), true)
                val have = n.pics().map { it.after }.toSet()
                val plans = Pics.parsePlan(txt, paras.size, mn, mx).filter { it.after !in have }
                if (plans.isEmpty()) snack("AI 没给出合适的插图位置，可以长按段落「在此插图」", false)
                props.clear(); plans.forEach { add(Proposal(it.after, it.query)) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { snackErr(e, "分析失败：", "重试") { start() } }
            finally { analyzing = false }
        }
    }
    fun add(p: Proposal) { props.removeAll { it.after == p.after && it.replace == p.replace }; props += p; props.sortBy { it.after }; load(p) }
    fun load(p: Proposal) {
        p.loading = true; p.err = null
        scope.launch {
            try { val (l, src) = withContext(Dispatchers.IO) { ImgSources.searchChain(ctx, p.query, p.page, s.picOrient) }; p.cands = l; p.sel = 0; p.source = src }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: ConfigError) { p.err = e.message; snack(e.message ?: "", false, "去设定") { goSub("imgsrc") } }
            catch (e: Throwable) { p.err = Errors.explain(e).let { it.title + "：" + it.detail }.take(160) }
            finally { p.loading = false }
        }
    }
    fun next(p: Proposal) { p.page++; load(p) }
    fun skip(p: Proposal) { props.remove(p) }
    private suspend fun save(n: Note, l: List<Pic>) { val nn = n.copy(images = Pics.encode(l)); note = nn; db.notes().update(nn) }
    fun insert(p: Proposal) {
        val c = p.cands.getOrNull(p.sel) ?: return; val n = note ?: return
        props.remove(p)
        scope.launch {
            try {
                val f = withContext(Dispatchers.IO) { PicCache.fetch(ctx, c.url) }
                val cur = (note ?: n).pics().filter { it != p.replace }
                save(note ?: n, cur + Pic(p.after, c.url, c.thumb, p.replace?.caption.orEmpty(), c.credit, c.source, f.path)); inserted++
            } catch (e: Throwable) { snackErr(e, "插图失败：") }
        }
    }
    fun insertAll() { props.toList().forEach { if (it.cands.isNotEmpty()) insert(it) } }
    fun done() { props.clear() }
    fun remove(pic: Pic) { val n = note ?: return; scope.launch { save(n, n.pics().filter { it != pic }) } }
    fun caption(pic: Pic, c: String) { val n = note ?: return; scope.launch { save(n, n.pics().map { if (it == pic) it.copy(caption = c) else it }) } }
    /** 长按段落「在此插图」/ 换图：先让 AI 把该段写成英文描述，没有模型就直接用该段文字 */
    fun here(after: Int, para: String, replace: Pic? = null) {
        if (ImgSources.usable(ctx).isEmpty()) { snack("还没有可用的图片来源", false, "去设定") { goSub("imgsrc") }; return }
        scope.launch {
            val q = try {
                if (modelRef().isBlank()) para.take(60)
                else Ai.chatText(Ai.target(ctx, modelRef()), null, "Write ONE English scene description (6-12 words) for a stock photo that illustrates this paragraph. Output only the description.\n\n$para", false).lines().first { it.isNotBlank() }.trim().trim('"').take(200)
            } catch (_: Throwable) { para.take(60) }
            add(Proposal(after, q, replace))
        }
    }
}

@Composable
fun rememberBitmap(key: String, load: suspend () -> android.graphics.Bitmap?): ImageBitmap? {
    var b by remember(key) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(key) { b = withContext(Dispatchers.IO) { try { load()?.asImageBitmap() } catch (_: Throwable) { null } } }
    return b
}

/** 已插入的图片：图 + 图注 + 来源小字；长按出菜单 */
@Composable
fun PicView(ctx: Context, pic: Pic, pal: ReadPalette, showCredit: Boolean, onReplace: () -> Unit, onCaption: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val bmp = rememberBitmap(pic.local + pic.url) { PicCache.load(ctx, pic, 1080) }
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Box {
            Box(Modifier.fillMaxWidth().clip(Sq.m).background(pal.soft).combinedClickable(onClick = {}, onLongClick = { menu = true }).then(if (bmp == null) Modifier.height(180.dp) else Modifier), contentAlignment = Alignment.Center) {
                if (bmp != null) androidx.compose.foundation.Image(bmp, pic.caption, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                else Text("图片加载中…", fontSize = 12.sp, color = pal.sub)
            }
            DropdownMenu(menu, { menu = false }, shape = Sq.m, containerColor = P3.surface) {
                DropdownMenuItem({ Text("换图") }, { menu = false; onReplace() }, leadingIcon = { SvgIcon(Ic.refresh, P3.text2, 18.dp) })
                DropdownMenuItem({ Text("编辑图注") }, { menu = false; onCaption() }, leadingIcon = { SvgIcon(Ic.write, P3.text2, 18.dp) })
                DropdownMenuItem({ Text("删除", color = P3.danger) }, { menu = false; onDelete() }, leadingIcon = { SvgIcon(Ic.trash, P3.danger, 18.dp) })
            }
        }
        if (pic.caption.isNotBlank()) Text(pic.caption, Modifier.fillMaxWidth().padding(top = 6.dp), fontSize = 13.sp, color = pal.text, textAlign = TextAlign.Center)
        if (showCredit && pic.creditLine().isNotBlank()) Text(pic.creditLine(), Modifier.fillMaxWidth().padding(top = 4.dp), fontSize = 11.sp, color = pal.sub.copy(alpha = .75f), textAlign = TextAlign.Center)
    }
}

/** 段落之间的候选卡片：横滑候选、换一批、跳过、插入 */
@Composable
fun ProposalCard(ctx: Context, p: Proposal, heading: String, idx: Int, total: Int, il: Illustrator, pal: ReadPalette) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp).clip(Sq.l).background(P3.surface).hairline(Sq.l, P3.accent.copy(alpha = .45f), 1.5.dp).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (p.replace != null) "换图" else "为「${heading.take(14)}」配图", Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = P3.text)
            Text("$idx / $total", fontSize = 12.sp, color = P3.text3, fontFamily = Mono)
        }
        Text("搜索描述：" + p.query, Modifier.padding(top = 2.dp, bottom = 8.dp), fontSize = 12.sp, color = P3.text2, maxLines = 2)
        when {
            p.loading -> Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), color = P3.accent, strokeWidth = 2.dp) }
            p.err != null -> Text(p.err ?: "", Modifier.padding(vertical = 10.dp), fontSize = 13.sp, color = P3.danger)
            p.cands.isEmpty() -> Text("没有找到合适的图，换一批试试", Modifier.padding(vertical = 10.dp), fontSize = 13.sp, color = P3.text2)
            else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(p.cands) { i, c ->
                    val b = rememberBitmap(c.thumb) { PicCache.thumb(ctx, c.thumb) }
                    Box(Modifier.size(120.dp, 90.dp).clip(Sq.m).background(P3.surface2).hairline(Sq.m, if (p.sel == i) P3.accent else P3.surface2, if (p.sel == i) 2.dp else 0.dp).clickable { p.sel = i }) {
                        if (b != null) androidx.compose.foundation.Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        if (p.sel == i) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(20.dp).clip(Sq.m).background(P3.accent), contentAlignment = Alignment.Center) { SvgIcon(Ic.check, androidx.compose.ui.graphics.Color.White, 14.dp) }
                    }
                }
            }
        }
        if (p.source.isNotBlank() && p.cands.isNotEmpty()) Text("来源：${p.source}", Modifier.padding(top = 6.dp), fontSize = 11.sp, color = P3.text3)
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineButton({ il.next(p) }, enabled = !p.loading, contentPadding = PaddingValues(horizontal = 12.dp), shape = Sq.full, border = null, modifier = Modifier.hairline(Sq.full, Hairline)) { SvgIcon(Ic.refresh, P3.text2, 16.dp); Text(" 换一批", fontSize = 13.sp, color = P3.text) }
            OutlineButton({ il.skip(p) }, contentPadding = PaddingValues(horizontal = 12.dp), shape = Sq.full, border = null, modifier = Modifier.hairline(Sq.full, Hairline)) { Text("跳过", fontSize = 13.sp, color = P3.text) }
            Spacer(Modifier.weight(1f))
            FilledButton({ il.insert(p) }, enabled = p.cands.isNotEmpty() && !p.loading, colors = ButtonDefaults.buttonColors(containerColor = P3.accent), shape = Sq.full) { Text("插入", fontSize = 14.sp) }
        }
    }
}

/** 分析中提示卡（顶栏下方） */
@Composable
fun AnalyzingCard(il: Illustrator) {
    if (!il.analyzing) return
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(Sq.l).background(P3.surface).hairline(Sq.l, P3.border).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), color = P3.accent, strokeWidth = 2.dp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("文中插图", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = P3.text)
            Text("AI 正在通读全文，寻找适合插图的位置…", fontSize = 12.sp, color = P3.text2)
        }
    }
}
