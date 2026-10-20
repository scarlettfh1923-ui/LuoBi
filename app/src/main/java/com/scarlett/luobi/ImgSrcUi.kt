@file:OptIn(ExperimentalLayoutApi::class)
package com.scarlett.luobi

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 设置 → 图片来源：拖动排序、开关、Key、自定义接口、张数与方向 */
@Composable
fun ImgSrcPage(back: () -> Unit, go: (String) -> Unit) {
    val ctx = LocalContext.current; val s = remember { Settings(ctx) }
    var list by remember { mutableStateOf(ImgSources.list(ctx)) }
    var open by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    fun save(l: List<ImgSource>) { list = l; ImgSources.save(ctx, l) }
    var mn by remember { mutableIntStateOf(s.picMin) }; var mx by remember { mutableIntStateOf(s.picMax) }
    var orient by remember { mutableStateOf(s.picOrient) }
    val rowH = 68.dp; val rowPx = with(LocalDensity.current) { (rowH + 8.dp).toPx() }
    var drag by remember { mutableIntStateOf(-1) }; var dy by remember { mutableFloatStateOf(0f) }
    Page("图片来源", back, action = { TextAction("＋ 添加") { go("imgsrc:new") } }) {
        item { Text("长按拖动排序：第一个找不到或失败，自动顺延下一个", Modifier.padding(bottom = 10.dp), fontSize = 13.sp, color = P3.text2) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                list.forEachIndexed { i, src ->
                    key(src.id) {
                        val ready = remember(tick, src) { ImgSources.ready(ctx, src) }
                        Column(Modifier.fillMaxWidth().zIndexIf(drag == i).graphicsLayer { translationY = if (drag == i) dy else 0f; shadowElevation = if (drag == i) 12f else 0f }
                            .clip(RoundedCornerShape(14.dp)).background(P3.surface).border(1.dp, if (drag == i) P3.accent else P3.border, RoundedCornerShape(14.dp))) {
                            Row(Modifier.fillMaxWidth().height(rowH).pointerInput(list) {
                                detectDragGesturesAfterLongPress(onDragStart = { drag = i; dy = 0f }, onDragEnd = { drag = -1; dy = 0f }, onDragCancel = { drag = -1; dy = 0f }) { ch, d ->
                                    ch.consume(); dy += d.y
                                    val cur = drag; if (cur < 0) return@detectDragGesturesAfterLongPress
                                    val to = (cur + (dy / rowPx).toInt()).coerceIn(0, list.lastIndex)
                                    if (to != cur) { save(list.toMutableList().apply { add(to, removeAt(cur)) }); dy -= (to - cur) * rowPx; drag = to }
                                }
                            }.clickable { if (src.type == "custom") go("imgsrc:${src.id}") else open = if (open == src.id) null else src.id }.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                SvgIcon(Ic.text, P3.text3, 20.dp, desc = "拖动")
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(src.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = P3.text)
                                        if (i == 0) Text(" 优先", Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background(P3.accentSoft).padding(horizontal = 6.dp, vertical = 1.dp), fontSize = 10.sp, color = P3.accent)
                                    }
                                    Text(when (src.type) { "pexafy" -> "x-api-key · 免费图库"; "pexels" -> "Authorization: Key · 免费图库"; "ai" -> "使用「模型与密钥」里的配图模型生成"; else -> "自定义 · " + src.url.substringAfter("//").substringBefore("/") },
                                        fontSize = 12.sp, color = P3.text3, maxLines = 1)
                                }
                                Text(if (ready) "已配置" else "未配置", Modifier.clip(RoundedCornerShape(50)).background(if (ready) P3.accentSoft else P3.surface2).padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 11.sp, color = if (ready) P3.accent else P3.text3)
                                Switch(src.on, { v -> save(list.map { if (it.id == src.id) it.copy(on = v) else it }) }, Modifier.padding(start = 6.dp).scale(.8f), colors = SwitchDefaults.colors(checkedTrackColor = P3.accent))
                            }
                            if (open == src.id) Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                                if (src.type == "ai") Text("复用「设置 → 模型与密钥 → 配图模型」的 Key 和模型，每次生成 1 张。生成的图只存在手机上，Notion 等外链导出会跳过。", fontSize = 12.sp, color = P3.text2, lineHeight = 17.sp)
                                else {
                                    var k by remember { mutableStateOf(ImgSources.key(ctx, src.id)) }
                                    FieldLabel("API Key", if (src.type == "pexafy") "docs.pexafy.com 申请" else "pexels.com/api 申请")
                                    InputBox(k, { k = it.trim(); Secure.set(ctx, "imgkey_${src.id}", k); tick++ }, "粘贴 ${src.name} 的 Key", singleLine = true, secret = true, mono = true)
                                    TestRow { testSearch(ctx, src) }
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Text("Key 加密存在本机，不经过任何服务器", Modifier.fillMaxWidth().padding(vertical = 10.dp), fontSize = 12.sp, color = P3.text3, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        item { GroupLabel("每篇插图张数") }
        item {
            Card {
                Stepper("最少", mn, 1..6) { mn = it; s.picMin = it; if (mx < it) { mx = it; s.picMax = it } }
                Spacer(Modifier.height(8.dp))
                Stepper("最多", mx, 1..6) { mx = it; s.picMax = it; if (mn > it) { mn = it; s.picMin = it } }
                Text("AI 通读全文后在 $mn–$mx 处给出候选，你逐张确认", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = P3.text3)
            }
        }
        item { GroupLabel("图片方向偏好") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("landscape" to "横图", "portrait" to "竖图", "any" to "不限").forEach { (k, l) -> Pill(l, orient == k) { orient = k; s.picOrient = k } } } }
    }
}

private fun Modifier.zIndexIf(b: Boolean) = if (b) this.then(Modifier.zIndex(1f)) else this
private fun Modifier.scale(f: Float) = this.graphicsLayer { scaleX = f; scaleY = f }

@Composable
private fun Stepper(label: String, v: Int, r: IntRange, on: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 15.sp, color = P3.text)
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(P3.surface2).clickable(enabled = v > r.first) { on(v - 1) }, contentAlignment = Alignment.Center) { Text("−", fontSize = 18.sp, color = P3.text) }
        Text("$v 张", Modifier.width(56.dp), fontSize = 15.sp, color = P3.text, textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontFamily = Mono)
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(P3.surface2).clickable(enabled = v < r.last) { on(v + 1) }, contentAlignment = Alignment.Center) { Text("+", fontSize = 18.sp, color = P3.accent) }
    }
}

private suspend fun testSearch(ctx: android.content.Context, src: ImgSource): String = withContext(Dispatchers.IO) {
    val r = ImgSources.search(ctx, src, "desk", 1, Settings(ctx).picOrient)
    if (r.isEmpty()) throw PublishError("接口通了，但没解析出图片，检查字段路径") else "搜索「书桌 desk」找到 ${r.size} 张 · 第一张：${r[0].url.take(60)}"
}

/** 自定义图片接口编辑 */
@Composable
fun ImgSrcEdit(id: String, back: () -> Unit) {
    val ctx = LocalContext.current
    val isNew = id == "new"
    val orig = remember { ImgSources.list(ctx).firstOrNull { it.id == id } }
    val nid = remember { orig?.id ?: "u${System.currentTimeMillis()}" }
    var name by remember { mutableStateOf(orig?.name ?: "") }
    var url by remember { mutableStateOf(orig?.url ?: "") }
    var auth by remember { mutableStateOf(orig?.auth ?: "header") }
    var authName by remember { mutableStateOf(orig?.authName ?: "") }
    var key by remember { mutableStateOf(Secure.get(ctx, "imgkey_$nid")) }
    var img by remember { mutableStateOf(orig?.imgPath ?: "") }
    var thumb by remember { mutableStateOf(orig?.thumbPath ?: "") }
    var credit by remember { mutableStateOf(orig?.creditPath ?: "") }
    var del by remember { mutableStateOf(false) }
    fun cur() = ImgSource(nid, "custom", name.trim().ifBlank { "自定义接口" }, orig?.on ?: true, url.trim(), auth, authName.trim(), img.trim(), thumb.trim(), credit.trim())
    Page(if (isNew) "添加自定义接口" else "自定义接口", back) {
        item {
            Card {
                FieldLabel("名称"); InputBox(name, { name = it }, "比如：我的 Unsplash", singleLine = true)
                FieldLabel("请求地址模板", "必须包含 {query}，可选 {page}"); InputBox(url, { url = it }, "https://api.unsplash.com/search/photos?query={query}", mono = true, fontSize = 13)
                if (url.isNotBlank() && !url.contains("{query}")) WarnBox("地址里还没有 {query}，搜索词放不进去", Modifier.padding(top = 6.dp))
                FieldLabel("鉴权方式")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Pill("Header", auth == "header") { auth = "header" }; Pill("Query 参数", auth == "query") { auth = "query" } }
                FieldLabel(if (auth == "header") "Header 名" else "参数名"); InputBox(authName, { authName = it }, if (auth == "header") "Authorization" else "client_id", singleLine = true, mono = true, fontSize = 13)
                FieldLabel("Key", "Header 时会原样作为值，需要前缀请一起填，如 Client-ID xxx"); InputBox(key, { key = it }, "粘贴 Key", singleLine = true, secret = true, mono = true)
                FieldLabel("图片地址字段路径", "数组用 []，如 results[].urls.regular"); InputBox(img, { img = it }, "results[].urls.regular", singleLine = true, mono = true, fontSize = 13)
                FieldLabel("缩略图字段路径（可选）"); InputBox(thumb, { thumb = it }, "results[].urls.thumb", singleLine = true, mono = true, fontSize = 13)
                FieldLabel("作者字段路径（可选）"); InputBox(credit, { credit = it }, "results[].user.name", singleLine = true, mono = true, fontSize = 13)
                TestRow { Secure.set(ctx, "imgkey_$nid", key); testSearch(ctx, cur()) }
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            PrimaryButton("保存", enabled = url.contains("{query}") && img.isNotBlank()) {
                Secure.set(ctx, "imgkey_$nid", key)
                val l = ImgSources.list(ctx); ImgSources.save(ctx, if (l.any { it.id == nid }) l.map { if (it.id == nid) cur() else it } else l + cur()); snack("已保存「${cur().name}」"); back()
            }
            if (!isNew) TextButton({ del = true }, Modifier.fillMaxWidth()) { Text("删除这个接口", color = P3.danger) }
        }
    }
    if (del) ConfirmDialog("删除「$name」？", "Key 也会一起清除。", "删除", true, onOk = { Secure.set(ctx, "imgkey_$nid", ""); ImgSources.save(ctx, ImgSources.list(ctx).filter { it.id != nid }); back() }) { del = false }
}
