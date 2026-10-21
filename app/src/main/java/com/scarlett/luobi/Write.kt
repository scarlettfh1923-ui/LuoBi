package com.scarlett.luobi

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 1.9 创作页状态：一段口播 → 提取意图 → 意图卡（底部弹层）确认 → 生成。
 * 原文、意图、模板、模型都存进 SharedPreferences：切底栏、旋转、进程被杀都不丢。
 */
class WriteState(ctx: Context) {
    private val p = ctx.getSharedPreferences("draft", Context.MODE_PRIVATE)
    var raw by mutableStateOf(p.getString("raw", "") ?: "")
    /** 意图三项，键 = 占位名（想写什么 / 观点 / 疑问） */
    val values = mutableStateMapOf<String, String>().apply { loadMap(p.getString("intent", "") ?: "").forEach { (k, v) -> put(k, v) } }
    /** 意图是针对哪段原文提取的；原文改了就要重新提取 */
    var extractedFor by mutableStateOf(p.getString("extractedFor", "") ?: "")
    var recommendId by mutableLongStateOf(p.getLong("recommendId", 0))
    var templateId by mutableLongStateOf(p.getLong("templateId", 0))
    var model by mutableStateOf(p.getString("model", "") ?: "")
    var sheet by mutableStateOf(p.getBoolean("sheet", false))
    var extracting by mutableStateOf(false)
    var err by mutableStateOf<Friendly?>(null)

    val extracted get() = extractedFor.isNotEmpty() && extractedFor == raw.trim()
    fun saveRaw() = p.edit().putString("raw", raw).apply()
    fun save() = p.edit().putString("raw", raw).putString("intent", JSONObject(values.toMap() as Map<*, *>).toString()).putString("extractedFor", extractedFor)
        .putLong("recommendId", recommendId).putLong("templateId", templateId).putString("model", model).putBoolean("sheet", sheet).apply()
    fun map() = LinkedHashMap<String, String>().also { out -> Compose.FIELDS.forEach { (k, _) -> values[k]?.trim()?.takeIf { it.isNotEmpty() }?.let { out[k] = it } } }
    fun reset() { values.clear(); extractedFor = ""; recommendId = 0; templateId = 0; sheet = false; err = null; save() }

    companion object {
        fun loadMap(s: String): Map<String, String> = try { val j = JSONObject(s); j.keys().asSequence().associateWith { j.optString(it) } } catch (_: Exception) { emptyMap() }
    }
}

@Composable
fun WriteScreen(db: AppDb, ws: WriteState, bottom: Dp, genErr: GenErr?, clearGenErr: () -> Unit, goModels: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()

    fun extract() {
        if (ws.raw.isBlank()) { snack("先粘贴一段口播", false); return }
        ws.extracting = true; ws.err = null; ws.saveRaw()
        scope.launch {
            try {
                val r = Ai.extract(ctx, ws.raw.trim(), ws.model)
                ws.values.clear(); Compose.FIELDS.forEach { (k, _) -> ws.values[k] = r.values[k] ?: "" }
                val list = db.templates().list()
                val rec = CleanParse.matchPrefer(r.recommend, list, { it.name }, { !it.builtIn }) ?: list.firstOrNull()
                ws.recommendId = rec?.id ?: 0; ws.templateId = rec?.id ?: 0
                ws.extractedFor = ws.raw.trim(); ws.sheet = true; ws.save()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: CleanParse.BadFormat) { ws.err = Friendly("意图提取结果格式不对", "模型返回的内容不是约定的 JSON。可以点「重试」，或在「设定 → 意图提取提示词」恢复默认。", e.raw) }
            catch (e: Throwable) { ws.err = Errors.explain(e).let { if (e is ConfigError) it.copy(settings = true) else it } }
            finally { ws.extracting = false }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumnFill(bottom + 64.dp) {
            item { Text("今天写什么", Modifier.padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 14.dp), fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp, color = P3.text) }
            item {
                Card(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 240.dp)) {
                        if (ws.raw.isEmpty()) Text("粘贴你的口播 / 想说的话 / 疑问", fontSize = 16.sp, lineHeight = 27.sp, color = P3.text3)
                        BasicTextField(ws.raw, { ws.raw = it; ws.saveRaw() }, Modifier.fillMaxWidth(),
                            textStyle = TextStyle(fontSize = 16.sp, lineHeight = 27.sp, color = P3.text), cursorBrush = SolidColor(P3.accent))
                    }
                    HorizontalDivider(Modifier.padding(top = 8.dp), thickness = Dp.Hairline, color = P3.border)
                    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${ws.raw.length} 字", Modifier.weight(1f), fontSize = 12.sp, color = P3.text3, fontFamily = Mono)
                        TextAction("粘贴") {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                            if (t.isBlank()) snack("剪贴板是空的", false) else { ws.raw = t; ws.saveRaw() }
                        }
                        Text("·", color = P3.text3)
                        TextAction("清空") { ws.raw = ""; ws.saveRaw() }
                    }
                }
            }
            if (ws.extracted && !ws.sheet) item {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("意图已提取：" + (ws.values["想写什么"]?.takeIf { it.isNotBlank() } ?: "（未填写想写什么）"), Modifier.weight(1f), fontSize = 13.sp, color = P3.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextAction(if (ws.extracting) "提取中…" else "重新提取", enabled = !ws.extracting) { extract() }
                }
            }
            ws.err?.let { e -> item { Box(Modifier.padding(vertical = 2.dp)) { ErrorCard(e, { extract() }, if (e.settings) goModels else null) { ws.err = null } } } }
            if (genErr != null) item { Box(Modifier.padding(vertical = 2.dp)) { ErrorCard(genErr.f, { clearGenErr(); genErr.retry() }, if (genErr.f.settings) goModels else null) { clearGenErr() } } }
        }
        // 主按钮固定在浮动导航之上；键盘弹出时跟到键盘上方（不叠加导航高度）
        val imeB = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
        Box(Modifier.fillMaxSize().padding(bottom = maxOf(bottom + 4.dp, imeB + 8.dp)), contentAlignment = Alignment.BottomCenter) {
            val m = Modifier.padding(horizontal = 16.dp)
            when {
                ws.extracting -> PrimaryButton("正在提取意图…", m, busy = true) {}
                ws.extracted -> PrimaryButton("查看意图卡", m) { ws.sheet = true; ws.save() }
                else -> PrimaryButton("提取意图", m, enabled = ws.raw.isNotBlank()) { extract() }
            }
        }
    }
}

/**
 * 意图卡底部弹层。画在 App 最外层（不在 Scaffold 里，没有任何已垫的底部留白），
 * 只用一次 ime ∪ navigationBars 作为底部留白：键盘弹出时弹层整体在键盘上方，中间可滚动，「生成文章」始终可点。
 */
@Composable
fun IntentSheet(db: AppDb, ws: WriteState, goModels: () -> Unit, goLength: () -> Unit, goTemplate: (Long) -> Unit, onGenerate: (GenJob) -> Unit) {
    val ctx = LocalContext.current; val s = remember { Settings(ctx) }
    val templates by remember(db) { db.templates().all() }.collectAsState(initial = emptyList())
    val configured = remember { s.configured() }
    val tpl = templates.firstOrNull { it.id == ws.templateId } ?: templates.firstOrNull { it.id == ws.recommendId } ?: templates.firstOrNull()
    LaunchedEffect(configured, tpl?.id) {
        if (configured.none { it.ref == ws.model }) {
            ws.model = tpl?.defaultModel?.takeIf { d -> configured.any { it.ref == d } } ?: s.defaultModel.takeIf { d -> configured.any { it.ref == d } } ?: configured.firstOrNull()?.ref ?: ""
        }
    }
    fun close() { ws.sheet = false; ws.save() }
    BackHandler { close() }
    val length = s.length; val lenRange = s.lengthRange()
    var tplMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    val vals = ws.map()
    val finalPrompt = tpl?.let { Compose.prompt(it.name, it.desc, it.prompt, vals, ws.raw, length, lenRange) } ?: ""

    Box(Modifier.fillMaxSize().background(Color(0x52000000)).clickable(remember { MutableInteractionSource() }, null) { close() })
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 48.dp), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().clip(Sq.topXl).background(P3.surface).clickable(remember { MutableInteractionSource() }, null) {}
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
            Box(Modifier.fillMaxWidth().clickable { close() }.padding(top = 8.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 5.dp).clip(Sq.full).background(P3.borderStrong))
            }
            Row(Modifier.padding(start = 24.dp, end = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("意图卡", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = P3.text)
                Text("AI 提取，可修改", fontSize = 12.sp, color = P3.text3)
                Box(Modifier.padding(start = 6.dp).size(36.dp).clip(Sq.full).clickable { close() }, contentAlignment = Alignment.Center) { SvgIcon(Ic.close, P3.text3, 18.dp, desc = "关闭") }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                Compose.FIELDS.forEach { (k, label) ->
                    val opt = k != "想写什么"
                    Row(Modifier.padding(top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), fontSize = 13.sp, color = P3.text2, fontWeight = FontWeight.SemiBold)
                        if (opt) Text("可不填", fontSize = 12.sp, color = P3.text3)
                    }
                    Box(Modifier.fillMaxWidth().clip(Sq.s).background(P3.bg).padding(horizontal = 14.dp, vertical = 11.dp)) {
                        val v = ws.values[k] ?: ""
                        if (v.isEmpty()) Text(if (opt) "没有提到，可补充" else "想写一篇什么样的文章", fontSize = 15.sp, lineHeight = 22.sp, color = P3.text3)
                        BasicTextField(v, { ws.values[k] = it; ws.save() }, Modifier.fillMaxWidth(), textStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = P3.text), cursorBrush = SolidColor(P3.accent))
                    }
                }
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(thickness = Dp.Hairline, color = P3.border)
                Box {
                    SheetRow("模板", (tpl?.name ?: "未选择") + if (tpl != null && tpl.id == ws.recommendId) " · 推荐" else "") { tplMenu = true }
                    DropdownMenu(tplMenu, { tplMenu = false }, shape = Sq.m) {
                        templates.sortedByDescending { it.id == ws.recommendId }.forEach { t ->
                            DropdownMenuItem(text = { Text(t.name + (if (t.id == ws.recommendId) " · 推荐" else "") + if (!t.builtIn) " · 我的" else "", color = if (t.id == tpl?.id) P3.accent else P3.text) },
                                onClick = { ws.templateId = t.id; ws.save(); tplMenu = false })
                        }
                    }
                }
                HorizontalDivider(thickness = Dp.Hairline, color = P3.border)
                Box {
                    SheetRow("模型", configured.firstOrNull { it.ref == ws.model }?.label ?: "去配置模型") { if (configured.isEmpty()) goModels() else modelMenu = true }
                    DropdownMenu(modelMenu, { modelMenu = false }, shape = Sq.m) {
                        configured.forEach { m -> DropdownMenuItem(text = { Text(m.label, color = if (m.ref == ws.model) P3.accent else P3.text) }, onClick = { ws.model = m.ref; ws.save(); modelMenu = false }) }
                    }
                }
                HorizontalDivider(thickness = Dp.Hairline, color = P3.border)
                SheetRow("篇幅", if (tpl != null && Lengths.inTemplate(tpl.prompt)) "模板已指定" else Lengths.label(length, lenRange).replace("（", " · ").replace("）", "")) { goLength() }
                HorizontalDivider(thickness = Dp.Hairline, color = P3.border)
                if (tpl != null) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextAction(if (preview) "收起最终提示词" else "查看最终提示词", P3.text2) { preview = !preview }
                        Spacer(Modifier.weight(1f))
                        TextAction("编辑模板", P3.text2) { goTemplate(tpl.id) }
                    }
                    if (preview) Text(finalPrompt, Modifier.fillMaxWidth().clip(Sq.s).background(P3.bg).padding(12.dp), fontSize = 13.sp, lineHeight = 20.sp, color = P3.text2)
                }
                Spacer(Modifier.height(12.dp))
            }
            PrimaryButton("生成文章", Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                enabled = tpl != null && ws.model.isNotBlank() && !ws.values["想写什么"].isNullOrBlank()) {
                val t = tpl ?: return@PrimaryButton
                onGenerate(GenJob("write", ws.raw.trim(), "", vals, t.id, t.name, ws.model, finalPrompt))
            }
        }
    }
}

@Composable
private fun SheetRow(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 15.sp, color = P3.text2)
        Text(value, fontSize = 15.sp, color = P3.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("  ›", fontSize = 15.sp, color = P3.text3)
    }
}
