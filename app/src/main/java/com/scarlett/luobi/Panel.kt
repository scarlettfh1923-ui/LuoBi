@file:OptIn(ExperimentalMaterial3Api::class)
package com.scarlett.luobi

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 渠道动作：分享栏格子和「更多」面板共用 */
class ChannelActions(private val ctx: Context, private val n: Note, private val imgs: () -> List<Bitmap>, private val pal: ReadPalette, private val fontId: String, private val goSub: (String) -> Unit) {
    var openXhs: () -> Unit = {}
    /** 打开独立配图页（卡片预览 + 比例 / 配色 + 分享到小红书） */
    var openCards: () -> Unit = {}
    var busy by mutableStateOf<String?>(null)
    private val title get() = n.title
    private val tags get() = n.tagList()
    private val ui = kotlinx.coroutines.CoroutineScope(Dispatchers.Main)

    private fun bg(label: String, block: suspend () -> String) {
        if (busy != null) { snack("正在处理「$busy」，请稍候"); return }
        busy = label
        ui.launch {
            try { val r = block(); if (r.isNotBlank()) snack(r) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: ConfigError) { snack(e.message ?: "请先完成设置", false, "去设置") { goSub("channels") } }
            catch (e: PublishError) { snack("$label 失败：${e.message}", false) }
            catch (e: OutOfMemoryError) { snack("文章太长，图片生成不了", false) }
            catch (e: Throwable) { snackErr(e, "$label 失败：") }
            finally { busy = null }
        }
    }
    /** 复制全文 / 抖音用：标题 + 去掉标记的正文 + 话题 */
    private fun fullText() = Export.plain(title, Md.plainBody(n.body), tags)

    fun run(id: String, custom: List<Channel>) {
        when (id) {
            "xhs" -> openCards()
            "wechat" -> sendLong("com.tencent.mm", "微信")
            "qq" -> sendLong("com.tencent.mobileqq", "QQ")
            "weibo" -> sendLong("com.sina.weibo", "微博")
            "douyin" -> douyin()
            "long" -> saveLong()
            "html" -> bg("HTML") { Out.copyHtml(ctx, title, n.body, tags, n.pics()); val f = withContext(Dispatchers.IO) { Out.saveHtml(ctx, title, n.body, tags, n.pics()) }; "已复制 HTML 富文本，并保存到「下载/落笔/$f」" }
            "md" -> { Share.copy(ctx, "Markdown", Export.markdown(title, n.body, tags, n.pics(), Settings(ctx).showCredit)); snack("已复制 Markdown") }
            "copy" -> { Share.copy(ctx, "全文", fullText()); snack("已复制全文") }
            "notion" -> bg("Notion") { val u = Notion.upload(ctx, title, n.body, tags, n.pics()); Share.copy(ctx, "Notion", u); "已上传到 Notion，链接已复制" }
            "yuque" -> bg("语雀") { val u = Yuque.upload(ctx, title, n.body, tags, n.pics()); Share.copy(ctx, "语雀", u); "已上传到语雀，链接已复制" }
            "telegraph" -> bg("Telegraph") { val u = Telegraph.upload(ctx, title, n.body, tags, n.pics()); Share.copy(ctx, "Telegraph", u); "已发布：$u（链接已复制）" }
            else -> if (id.startsWith("c")) {
                val ch = custom.firstOrNull { "c${it.id}" == id } ?: return
                bg(ch.name) { val r = Custom.publish(ctx, ch, title, n.body, tags, n.pics()); if (r.startsWith("http")) { Share.copy(ctx, ch.name, r); "已发布到「${ch.name}」，链接已复制" } else "「${ch.name}」$r" }
            }
        }
    }
    /** 生成清晰长图（1440 宽，太长自动拆张）。save=true 时同时存进相册 */
    private fun makeLong(save: Boolean): ArrayList<Uri> {
        val fid = if (Fonts.ready(ctx, fontId)) fontId else "sans"
        val reg = Fonts.typeface(ctx, fid, false); val bold = Fonts.typeface(ctx, fid, true)
        val uris = ArrayList<Uri>(); val stamp = System.currentTimeMillis()
        val show = Settings(ctx).showCredit
        Render.longImage(title, n.body, tags, reg, bold, pal.long(), PicCache.bitmaps(ctx, n.pics(), Export.paras(n.body).size, show)) { bmp, i, _ ->
            try { if (save) Share.saveOne(ctx, bmp, "long_${stamp}_${i + 1}.jpg"); uris += Share.cacheJpeg(ctx, bmp, "long_${stamp}_${i + 1}.jpg") } finally { bmp.recycle() }
        }
        return uris
    }
    /** 长图：直接存相册，不弹选择 */
    fun saveLong() {
        if (busy != null) { snack("正在处理「$busy」，请稍候"); return }
        busy = "长图"; snackBusy("正在生成长图…")
        ui.launch {
            try {
                val u = withContext(Dispatchers.Default) { makeLong(true) }
                snack(if (u.size > 1) "已保存长图到相册（${u.size} 张）" else "已保存长图到相册", true, "查看") { Out.view(ctx, u.first()) }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: OutOfMemoryError) { snack("文章太长，图片生成不了", false) }
            catch (e: Throwable) { snackErr(e, "长图失败：") }
            finally { busy = null }
        }
    }
    /** 微信 / QQ / 微博：生成长图，按包名交给对方 APP；没装或失败弹系统菜单 */
    private fun sendLong(pkg: String, name: String) {
        if (busy != null) { snack("正在处理「$busy」，请稍候"); return }
        busy = name; snackBusy("正在生成长图…")
        ui.launch {
            try {
                val u = withContext(Dispatchers.Default) { makeLong(false) }
                Out.sendImagesTo(ctx, pkg, u, "")
                snack(if (u.size > 1) "已生成 ${u.size} 张长图，正在打开$name" else "长图已生成，正在打开$name")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: OutOfMemoryError) { snack("文章太长，图片生成不了", false) }
            catch (e: Throwable) { snackErr(e, "分享到$name 失败：") }
            finally { busy = null }
        }
    }
    /** 抖音：复制全文，再打开抖音 */
    private fun douyin() {
        Share.copy(ctx, "全文", fullText())
        if (Out.launch(ctx, "com.ss.android.ugc.aweme")) snack("已复制全文，去抖音发布页粘贴")
        else toast(ctx, "已复制全文，但未检测到抖音")
    }
}

/** 「更多」面板（列表 B）：所有不在分享栏里的渠道 + 编辑分享栏 */
@Composable
fun SharePanel(custom: List<Channel>, act: ChannelActions, editBar: () -> Unit, dismiss: () -> Unit) {
    val ctx = LocalContext()
    val bar = remember { Settings(ctx).shareBar }
    val items = ShareBar.others(ChannelIds.all(custom).map { it.id }, bar).mapNotNull { ChannelIds.def(it, custom) }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = P3.surface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = P3.borderStrong) }) {
        Column(Modifier.padding(horizontal = 18.dp).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("更多分享", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = P3.text)
                Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { dismiss(); editBar() }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SvgIcon(Ic.edit, P3.accent, 14.dp); Text(" 编辑分享栏", fontSize = 13.sp, color = P3.accent)
                }
            }
            if (items.isEmpty()) Text("所有渠道都已在分享栏里", Modifier.padding(vertical = 20.dp), fontSize = 13.sp, color = P3.text3)
            items.forEachIndexed { i, d ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 34.dp), thickness = 0.5.dp, color = P3.border)
                Row(Modifier.fillMaxWidth().clickable { if (d.id == "xhs") dismiss(); act.run(d.id, custom) }.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    SvgIcon(d.icon, P3.text, 22.dp)
                    Text(if (act.busy == d.name) "${d.name}（处理中…）" else d.name, Modifier.weight(1f).padding(start = 12.dp), fontSize = 15.sp, color = P3.text)
                    Text(ChannelIds.note(d.id), fontSize = 12.sp, color = P3.text3)
                }
            }
        }
    }
}

@Composable
fun LocalContext() = androidx.compose.ui.platform.LocalContext.current
