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

/** 渠道动作：常用渠道图标和分享面板共用 */
class ChannelActions(private val ctx: Context, private val n: Note, private val imgs: () -> List<Bitmap>, private val pal: ReadPalette, private val fontId: String, private val goSub: (String) -> Unit) {
    var openXhs: () -> Unit = {}
    /** 当前弹出的二级选择：social / text / save */
    var choice by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null)
    var socialPkg: String? = null
    /** 微信 / QQ：只发正文全文纯文字，不带图 */
    fun sendText(id: String) {
        val pkg = if (id == "wechat") "com.tencent.mm" else "com.tencent.mobileqq"
        Out.sendTextTo(ctx, pkg, Export.plain(title, plainBody(), tags))
    }
    private val title get() = n.title
    private val tags get() = n.tagList()
    private val ui = kotlinx.coroutines.CoroutineScope(Dispatchers.Main)

    fun sub(id: String, s: Settings): String = when (id) {
        "xhs" -> "配图 + 文案已复制 · 交给小红书"
        "wechat", "qq" -> "发送正文文字"
        "weibo", "douyin" -> "长图或配图"
        "html" -> "粘贴进公众号后台、知乎、语雀"
        "text" -> "标题 + 正文 + 标签"
        "notion" -> if (Secure.get(ctx, "notion_token").isBlank()) "未配置 · 点「上传」去设置" else (if (s.notionType == "database") "数据库" else "页面") + "「${s.notionLabel.ifBlank { Notion.idOf(s.notionTarget).take(8) }}」"
        "yuque" -> if (s.yuqueNs.isBlank()) "未配置 · 点「上传」去设置" else "知识库 ${s.yuqueNs}"
        "telegraph" -> "发布成网页，返回链接"
        "long" -> "存到相册 · 太长自动拆开"
        "htmlfile" -> "下载/落笔"
        else -> "自定义"
    }
    fun verb(id: String) = when (id) { "xhs" -> "发送"; "wechat", "qq" -> "发送"; "weibo", "douyin" -> "选择"; "html", "text" -> "复制"; "notion", "yuque" -> "上传"; "telegraph" -> "发布"; "long" -> "保存"; "htmlfile" -> "导出"; else -> "发布" }

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
    private fun plainBody() = n.body.lines().joinToString("\n") { if (it.trim().startsWith("#")) headText(it.trim()) else it }.replace("**", "")

    fun run(id: String, custom: List<Channel>) {
        when (id) {
            "xhs" -> openXhs()
            "wechat", "qq" -> sendText(id)
            "weibo", "douyin" -> { socialPkg = Out.socialApps.first { it.first == (if (id == "weibo") "微博" else "抖音") }.second; choice = "social" }
            "text" -> choice = id
            "long" -> choice = "save"
            "html" -> { Out.copyHtml(ctx, title, n.body, tags); snack("已复制 HTML 富文本，可以粘贴进公众号后台、知乎、语雀") }
            "htmlfile" -> bg(".html 导出") { "已保存到「下载/落笔/" + withContext(Dispatchers.IO) { Out.saveHtml(ctx, title, n.body, tags) } + "」" }
            "notion" -> bg("Notion") { val u = Notion.upload(ctx, title, n.body, tags); Share.copy(ctx, "Notion", u); "已上传到 Notion，链接已复制" }
            "yuque" -> bg("语雀") { val u = Yuque.upload(ctx, title, n.body, tags); Share.copy(ctx, "语雀", u); "已上传到语雀，链接已复制" }
            "telegraph" -> bg("Telegraph") { val u = Telegraph.upload(ctx, title, n.body, tags); Share.copy(ctx, "Telegraph", u); "已发布：$u（链接已复制）" }
            else -> if (id.startsWith("c")) {
                val ch = custom.firstOrNull { "c${it.id}" == id } ?: return
                bg(ch.name) { val r = Custom.publish(ctx, ch, title, n.body, tags); if (r.startsWith("http")) { Share.copy(ctx, ch.name, r); "已发布到「${ch.name}」，链接已复制" } else "「${ch.name}」$r" }
            }
        }
    }
    fun copyText(md: Boolean) {
        Share.copy(ctx, if (md) "Markdown" else "文案", if (md) Export.markdown(title, n.body, tags) else Export.plain(title, plainBody(), tags))
        snack(if (md) "已复制 Markdown" else "已复制纯文本")
    }
    private fun makeLong(): ArrayList<Uri> {
        val fid = if (Fonts.ready(ctx, fontId)) fontId else "sans"
        val reg = Fonts.typeface(ctx, fid, false); val bold = Fonts.typeface(ctx, fid, true)
        val uris = ArrayList<Uri>(); val stamp = System.currentTimeMillis()
        Render.longImage(title, n.body, tags, reg, bold, pal.long()) { bmp, i, _ ->
            try { Share.saveOne(ctx, bmp, "long_${stamp}_${i + 1}.jpg"); uris += Share.cacheJpeg(ctx, bmp, "long_${stamp}_${i + 1}.jpg") } finally { bmp.recycle() }
        }
        return uris
    }
    fun saveLong() = bg("长图") { val u = withContext(Dispatchers.Default) { makeLong() }; if (u.size > 1) "文章较长，已拆成 ${u.size} 张长图存进相册「落笔」" else "长图已存进相册「落笔」" }
    fun saveCards() = bg("配图") { val l = imgs(); if (l.isEmpty()) "配图还在生成，请稍候" else "已保存 ${withContext(Dispatchers.IO) { Share.save(ctx, l) }} 张配图到相册「落笔」" }
    fun social(pkg: String?, long: Boolean) = bg("分享") {
        val uris = if (long) withContext(Dispatchers.Default) { makeLong() } else {
            val l = imgs(); if (l.isEmpty()) return@bg "配图还在生成，请稍候"
            withContext(Dispatchers.IO) { ArrayList(l.mapIndexed { i, b -> Share.cacheJpeg(ctx, b, "card_${System.currentTimeMillis()}_$i.jpg") }) }
        }
        Share.copy(ctx, "文案", Export.plain(title, plainBody(), tags))
        Out.sendImagesTo(ctx, pkg, uris, title); "文案已复制，可在对方 APP 里粘贴"
    }
}

/** 分享面板：四组列表行（图标 + 标题 + 一句说明 + 右侧动作） */
@Composable
fun SharePanel(custom: List<Channel>, act: ChannelActions, addCustom: () -> Unit, dismiss: () -> Unit) {
    val ctx = LocalContext()
    val s = remember { Settings(ctx) }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = P3.bg,
        dragHandle = { BottomSheetDefaults.DragHandle(color = P3.borderStrong) }) {
        Column(Modifier.padding(horizontal = 18.dp).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("分享与导出", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = P3.text)
                Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = addCustom).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SvgIcon(Ic.plus, P3.accent, 16.dp); Text(" 添加自定义渠道", fontSize = 14.sp, color = P3.accent)
                }
            }
            Text("小红书、微博、抖音不自动发布，最后一步由你点", Modifier.padding(top = 2.dp), fontSize = 13.sp, color = P3.text2)
            val all = ChannelIds.all(custom)
            ChannelIds.groups.forEachIndexed { gi, g ->
                val items = all.filter { it.group == gi }
                if (items.isEmpty()) return@forEachIndexed
                GroupLabel(g)
                if (gi == 0) Text("微信 / QQ：发送正文文字（不带图）· 微博 / 抖音：发长图或配图", Modifier.padding(bottom = 6.dp), fontSize = 12.sp, color = P3.text3)
                items.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                        row.forEach { d ->
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { if (d.id == "xhs") dismiss(); act.run(d.id, custom) }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(P3.surface).border(1.dp, P3.border, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { SvgIcon(d.icon, P3.text2, 22.dp) }
                                Text(if (act.busy == d.name) "处理中…" else d.name, Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp), fontSize = 12.sp, color = P3.text, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 15.sp)
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
    ChoiceDialogs(act)
}

@Composable
fun LocalContext() = androidx.compose.ui.platform.LocalContext.current

/** 二级选择：社交分享 / 复制格式 / 保存类型 */
@Composable
fun ChoiceDialogs(act: ChannelActions) {
    val c = act.choice ?: return
    var long by remember { mutableStateOf(true) }
    AlertDialog(onDismissRequest = { act.choice = null }, containerColor = P3.surface,
        title = { Text(when (c) { "social" -> if (act.socialPkg == "com.sina.weibo") "分享到微博" else "分享到抖音"; "text" -> "复制格式"; else -> "存到相册" }, fontSize = 18.sp) },
        text = {
            Column {
                when (c) {
                    "social" -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Pill("长图", long) { long = true }; Pill("配图卡片", !long) { long = false } }
                        Spacer(Modifier.height(12.dp))
                        (listOf("发送" to act.socialPkg) + ("其他（系统菜单）" to null)).forEach { (name, pkg) ->
                            Text(name, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { act.choice = null; act.social(pkg, long) }.padding(vertical = 12.dp, horizontal = 4.dp), fontSize = 16.sp, color = P3.text)
                        }
                        Text("抖音能否直接接收图片取决于对方 APP 版本，需要真机测试。", fontSize = 12.sp, color = P3.text3)
                    }
                    "text" -> listOf("纯文本" to false, "Markdown" to true).forEach { (l, md) ->
                        Text(l, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { act.choice = null; act.copyText(md) }.padding(vertical = 12.dp, horizontal = 4.dp), fontSize = 16.sp, color = P3.text)
                    }
                    else -> {
                        Text("保存长图（太长自动拆成几张）", Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { act.choice = null; act.saveLong() }.padding(vertical = 12.dp, horizontal = 4.dp), fontSize = 16.sp, color = P3.text)
                        Text("保存小红书配图卡片", Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { act.choice = null; act.saveCards() }.padding(vertical = 12.dp, horizontal = 4.dp), fontSize = 16.sp, color = P3.text)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { act.choice = null }) { Text("取消", color = P3.text2) } })
}
