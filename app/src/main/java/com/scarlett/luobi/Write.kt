package com.scarlett.luobi

import androidx.compose.animation.animateContentSize
import kotlinx.coroutines.delay
import androidx.compose.animation.core.animateFloat

import androidx.compose.runtime.getValue

import androidx.compose.ui.focus.onFocusChanged

import androidx.compose.ui.graphics.graphicsLayer

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.testTag
import org.json.JSONObject

/**
 * 创作页状态（2.4）：一段想法 + 选中的技能包 + 写作模型。全部存进 SharedPreferences：切底栏、旋转、进程被杀都不丢。
 * 1.9–2.3 的意图卡字段（values / extractedFor / templateId …）保留为旧草稿的兼容读写，界面不再使用。
 */
class WriteState(ctx: Context) {
    private val p = ctx.getSharedPreferences("draft", Context.MODE_PRIVATE)
    var raw by mutableStateOf(p.getString("raw", "") ?: "")
    /** 选中的技能包 id（「先点技能包再输入」「先输入再点技能包」两种顺序都靠它） */
    var skillId by mutableStateOf(p.getString("skill", "") ?: "")
    /** 意图三项，键 = 占位名（想写什么 / 观点 / 疑问）——旧版草稿兼容 */
    val values = mutableStateMapOf<String, String>().apply { loadMap(p.getString("intent", "") ?: "").forEach { (k, v) -> put(k, v) } }
    var extractedFor by mutableStateOf(p.getString("extractedFor", "") ?: "")
    var recommendId by mutableLongStateOf(p.getLong("recommendId", 0))
    var templateId by mutableLongStateOf(p.getLong("templateId", 0))
    var model by mutableStateOf(p.getString("model", "") ?: "")
    var sheet by mutableStateOf(false)
    var extracting by mutableStateOf(false)
    var err by mutableStateOf<Friendly?>(null)

    val extracted get() = extractedFor.isNotEmpty() && extractedFor == raw.trim()
    fun saveRaw() = p.edit().putString("raw", raw).apply()
    fun saveSkill() = p.edit().putString("skill", skillId).apply()
    fun save() = p.edit().putString("raw", raw).putString("skill", skillId).putString("intent", JSONObject(values.toMap() as Map<*, *>).toString()).putString("extractedFor", extractedFor)
        .putLong("recommendId", recommendId).putLong("templateId", templateId).putString("model", model).putBoolean("sheet", false).apply()
    fun map() = LinkedHashMap<String, String>().also { out -> Compose.FIELDS.forEach { (k, _) -> values[k]?.trim()?.takeIf { it.isNotEmpty() }?.let { out[k] = it } } }
    fun reset() { values.clear(); extractedFor = ""; recommendId = 0; templateId = 0; sheet = false; err = null; save() }
    /** 开始写之后，输入框回到最初状态；技能包选择保留（连写几篇同类文章不用重选） */
    fun clearAfterSubmit() { raw = ""; reset() }

    companion object {
        fun loadMap(s: String): Map<String, String> = try { val j = JSONObject(s); j.keys().asSequence().associateWith { j.optString(it) } } catch (_: Exception) { emptyMap() }
    }
}

/** 按钮文案：没选包 / 没输入 / 可以开始（三种状态，和设计稿一致） */
fun startLabel(hasSkill: Boolean, hasText: Boolean) = when { !hasSkill -> "先选一个技能包"; !hasText -> "先输入关键词"; else -> "开始搜资料" }

/**
 * 创作页（2.4）：页眉（创作 + 管理）→ 输入卡 → 技能包 → 灵感；开始按钮固定在底栏上方。
 * 两种顺序都行：先写再选，或先选再写；两者都齐了按钮才亮。
 */
@Composable
fun WriteScreen(db: AppDb, ws: WriteState, bottom: Dp, genErr: GenErr?, clearGenErr: () -> Unit, goModels: () -> Unit, genProg: GenProgress? = null, onCancelGen: () -> Unit = {}, onOpenNote: (Long) -> Unit = {},
                session: SkillSession? = null, onManage: () -> Unit = {}, onResume: () -> Unit = {}, onStart: (String, SkillPack, String) -> Unit = { _, _, _ -> }) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var packs by remember { mutableStateOf(Skills.builtIns(ctx)) }
    LaunchedEffect(Unit) { packs = withContext(Dispatchers.IO) { Skills.list(ctx) } }
    val importer = rememberSkillImporter { p, _ -> scope.launch { packs = withContext(Dispatchers.IO) { Skills.list(ctx) }; ws.skillId = p.id; ws.saveSkill() } }
    val sel = packs.firstOrNull { it.id == ws.skillId }
    var startErr by remember { mutableStateOf<Friendly?>(null) }

    fun begin() {
        val pack = sel ?: return
        val text = ws.raw.trim()
        if (text.isEmpty()) return
        val s = Settings(ctx)
        val models = s.configured()
        if (models.isEmpty()) { startErr = Friendly("还没有配置写作模型", "技能包要用一个大模型来整理提纲、写正文。先去「设定 → 模型」填一个 Key。", "", true); return }
        if (s.wsaKey.isBlank() && s.key("deepseek").isBlank()) { startErr = Friendly("还没有可用的搜索", "技能包要先搜资料：请填「元宝搜索」的 Key，或填一个 DeepSeek Key 作为备用搜索。", "", true); return }
        val model = models.firstOrNull { it.ref == ws.model }?.ref ?: s.defaultModel.takeIf { d -> models.any { it.ref == d } } ?: models.first().ref
        ws.model = model; startErr = null; ws.save()
        onStart(text, pack, model)
    }

    // 2.2 灵感：打开先显示缓存（x 分钟前更新），旧了就后台刷新；刷新只抓原始热榜（不调 AI），哪个来源先到先显示；
    // 点一条再用 AI 现场展开成角度 / 观点 / 疑惑并填进输入框
    var insp by remember { mutableStateOf(Inspire.cached(ctx)) }
    var inspBusy by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var inspErr by remember { mutableStateOf<Friendly?>(null) }
    var expanding by remember { mutableStateOf<String?>(null) }
    fun loadInspire() {
        if (inspBusy) return
        inspBusy = true; inspErr = null
        val hadCache = insp != null
        scope.launch {
            var first = true
            try { insp = Inspire.refresh(ctx) { part -> if (!hadCache || !first || part.topics.size >= 10) { insp = part; first = false } } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { if (insp == null) inspErr = Errors.explain(e) else snack("热榜刷新失败，先显示上次的结果", false) }
            finally { inspBusy = false }
        }
    }
    fun aiPick() {
        if (picking) return
        picking = true
        scope.launch {
            try { insp = Inspire.aiPick(ctx); snack("AI 挑好了 ${insp?.topics?.size ?: 0} 个选题") }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: CleanParse.BadFormat) { inspErr = Friendly("选题结果格式不对", "模型返回的不是约定的 JSON。可以再试一次，或在「设定 → 每日选题提示词」恢复默认。", e.raw) }
            catch (e: Throwable) { inspErr = Errors.explain(e) }
            finally { picking = false }
        }
    }
    fun pickTopic(t: Topic) {
        if (t.angle.isNotBlank() || t.view.isNotBlank()) { ws.raw = Inspire.fillText(t); ws.saveRaw(); snack("已填入选题，可以修改后再选技能包"); return }
        if (expanding != null) return
        if (Inspire.expandHook == null && Inspire.target(ctx) == null) { ws.raw = Inspire.fillText(t); ws.saveRaw(); snack("已填入热点。填写 DeepSeek Key 后，点热点会自动生成角度和观点", false); return }
        expanding = t.title
        scope.launch {
            try { val r = Inspire.expand(ctx, t); insp = insp?.let { c -> c.copy(topics = c.topics.map { if (it.title == t.title && it.source == t.source) r else it }) }
                ws.raw = Inspire.fillText(r); ws.saveRaw(); snack("已填入选题，可以修改后再选技能包") }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Throwable) { ws.raw = Inspire.fillText(t); ws.saveRaw(); snackErr(e, "角度没生成出来，先填入了热点标题：") }
            finally { expanding = null }
        }
    }
    LaunchedEffect(Unit) { val c = insp; if (c == null || System.currentTimeMillis() - c.updatedAt > Inspire.STALE_MS) loadInspire() }

    val imeOn = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    val hasText = ws.raw.isNotBlank()
    Column(Modifier.fillMaxSize().imePadding()) {
        CreateHeader(onManage)
        @OptIn(ExperimentalMaterial3Api::class)
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(isRefreshing = false, onRefresh = { loadInspire() }, modifier = Modifier.weight(1f).fillMaxWidth()) {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item(key = "input") {
                    var focused by remember { mutableStateOf(false) }
                    val minH by androidx.compose.animation.core.animateDpAsState(if (focused || ws.raw.length > 60) 150.dp else 96.dp, Motion.springDp(), label = "expand")
                    var modelMenu by remember { mutableStateOf(false) }
                    val models = remember(modelMenu) { Settings(ctx).configured() }
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().testTag("inputCard")
                        .softShadow(Sq.card26, 6.dp).clip(Sq.card26).background(P3.surface)
                        .hairline(Sq.card26, if (focused) P3.accent.copy(alpha = .45f) else P3.border).padding(horizontal = 18.dp, vertical = 14.dp)) {
                        Box(Modifier.fillMaxWidth().heightIn(min = minH)) {
                            if (ws.raw.isEmpty()) Text("写几个关键词，或一两句话…", fontSize = 17.sp, lineHeight = 27.sp, color = P3.text3)
                            BasicTextField(ws.raw, { ws.raw = it; ws.saveRaw() }, Modifier.fillMaxWidth().testTag("input").onFocusChanged { focused = it.isFocused },
                                textStyle = TextStyle(fontSize = 17.sp, lineHeight = 27.sp, color = P3.text), cursorBrush = SolidColor(P3.accent))
                        }
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                val label = models.firstOrNull { it.ref == ws.model }?.label ?: models.firstOrNull { it.ref == Settings(ctx).defaultModel }?.label ?: models.firstOrNull()?.label
                                Text(if (label == null) "未配置模型" else "写作模型 · $label  ▾", Modifier.clip(Sq.s).clickable { if (models.isEmpty()) goModels() else modelMenu = true }.padding(vertical = 6.dp, horizontal = 2.dp),
                                    fontSize = 12.sp, color = P3.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                DropdownMenu(modelMenu, { modelMenu = false }, shape = Sq.m) {
                                    models.forEach { m -> DropdownMenuItem(text = { Text(m.label, color = if (m.ref == ws.model) P3.accent else P3.text) }, onClick = { ws.model = m.ref; ws.save(); modelMenu = false }) }
                                }
                            }
                            if (hasText) { Text("${ws.raw.length} 字", fontSize = 12.sp, color = P3.text3, fontFamily = Mono); Text(" · ", color = P3.text3, fontSize = 12.sp); TextAction("清空") { ws.raw = ""; ws.saveRaw() } }
                            else TextAction("粘贴") {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val t = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                                if (t.isBlank()) snack("剪贴板是空的", false) else { ws.raw = t; ws.saveRaw() }
                            }
                        }
                    }
                }
                item(key = "skillHead") {
                    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
                        Text("技能包", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = P3.text)
                        Text(if (hasText || sel != null) "用哪个技能包？" else "也可先点某个包再写", Modifier.weight(1f).padding(start = 10.dp, bottom = 1.dp), fontSize = 12.sp, color = P3.text3, maxLines = 1)
                    }
                }
                items(packs, key = { it.id }, contentType = { "skill" }) { p -> SkillPickCard(p, p.id == sel?.id) { ws.skillId = if (ws.skillId == p.id) "" else p.id; ws.saveSkill() } }
                item(key = "import") { ImportRow(importer.busy, importer.launch) }
                startErr?.let { e -> item(key = "startErr") { Box(Modifier.animateItem(Motion.itemFade(), Motion.itemPlace(), Motion.itemFade()).padding(vertical = 2.dp)) { ErrorCard(e, null, if (e.settings) goModels else null) { startErr = null } } } }
                if (session != null && session.phase != "write" && session.phase != "done") item(key = "resume") { SkillResumeBanner(session, onResume) }
                if (genErr != null) item(key = "genErr") { Box(Modifier.animateItem(Motion.itemFade(), Motion.itemPlace(), Motion.itemFade()).padding(vertical = 2.dp)) { ErrorCard(genErr.f, { clearGenErr(); genErr.retry() }, if (genErr.f.settings) goModels else null) { clearGenErr() } } }
                // 1.18：后台生成卡（方案 C），收起时整项高度动画，灵感平滑上移
                item(key = "genCard") { GenCardSlot(genProg, onCancelGen, onOpenNote) }
                // ---- 灵感 ----
                item(key = "inspHead") {
                    Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("灵感", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = P3.text)
                        val sub = remember(insp, inspBusy) { when { inspBusy && insp == null -> "正在抓热榜…"; inspBusy -> "正在刷新…"; else -> insp?.let { Inspire.ago(it.updatedAt) } ?: "" } }
                        Text(sub, Modifier.weight(1f).padding(start = 10.dp, top = 2.dp), fontSize = 12.sp, color = P3.text3, maxLines = 1)
                        TextAction(if (picking) "AI 挑选中…" else "AI 帮我挑", enabled = !picking && insp != null) { aiPick() }
                        TextAction(if (inspBusy) "刷新中…" else "刷新", enabled = !inspBusy) { loadInspire() }
                    }
                }
                inspErr?.let { e -> item(key = "inspErr") { Box(Modifier.animateItem(Motion.itemFade(), Motion.itemPlace(), Motion.itemFade())) { ErrorCard(e, { loadInspire() }, if (e.settings) goModels else null) { inspErr = null } } } }
                val r = insp
                if (r == null && inspBusy) item(key = "inspLoading") {
                    Card(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).animateItem(Motion.itemFade(), Motion.itemPlace(), Motion.itemFade())) {
                        repeat(5) { i -> if (i > 0) Spacer(Modifier.height(16.dp)); Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(16.dp).height(12.dp).clip(Sq.full).background(P3.surface2)); Spacer(Modifier.width(12.dp))
                            Box(Modifier.fillMaxWidth(if (i % 2 == 0) .8f else .55f).height(14.dp).clip(Sq.full).background(P3.surface2)) } }
                    }
                }
                if (r != null) {
                    val note = listOf(r.note, if (r.failed.isNotEmpty()) "${r.failed.joinToString("、")} 暂时没抓到，已跳过" else "", if (!r.ai && r.topics.isNotEmpty()) "点一条，AI 帮你想角度和观点" else "").filter { it.isNotBlank() }.joinToString("；")
                    if (note.isNotBlank()) item(key = "inspNote") { Text(note, Modifier.padding(horizontal = 24.dp, vertical = 2.dp), fontSize = 12.sp, color = P3.text3, lineHeight = 18.sp) }
                    item(key = "inspList") {
                        Card(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).animateItem(Motion.itemFade(), Motion.itemPlace(), Motion.itemFade()).animateContentSize(), pad = 0.dp) {
                            r.topics.forEachIndexed { i, t ->
                                if (i > 0) HorizontalDivider(Modifier.padding(start = 48.dp), thickness = Dp.Hairline, color = P3.border)
                                val busy = expanding == t.title
                                Row(Modifier.fillMaxWidth().clickable(enabled = expanding == null) { pickTopic(t) }.padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
                                    Text("${i + 1}", Modifier.width(24.dp), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (i < 3) P3.accent else P3.text3, fontFamily = Mono)
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.Top) {
                                            Text(t.title, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = P3.text, lineHeight = 21.sp)
                                            if (t.source.isNotBlank()) Text(t.source, Modifier.padding(start = 8.dp, top = 1.dp).clip(Sq.full).background(P3.accentSoft).padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 11.sp, color = P3.onAccentSoft, fontWeight = FontWeight.SemiBold)
                                        }
                                        if (t.heat.isNotBlank()) Text("热度 " + t.heat, Modifier.padding(top = 3.dp), fontSize = 12.sp, color = P3.text3, fontFamily = Mono)
                                        if (busy) Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            androidx.compose.material3.CircularProgressIndicator(Modifier.size(12.dp), color = P3.accent, strokeWidth = 1.5.dp)
                                            Text("AI 正在想角度…", Modifier.padding(start = 8.dp), fontSize = 13.sp, color = P3.accent)
                                        }
                                        if (t.angle.isNotBlank()) Text("可以写：" + t.angle, Modifier.padding(top = 4.dp), fontSize = 13.sp, color = P3.text2, lineHeight = 19.sp)
                                        if (t.view.isNotBlank()) Text("观点：" + t.view, Modifier.padding(top = 2.dp), fontSize = 13.sp, color = P3.text3, lineHeight = 19.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // 固定在底栏上方的主按钮：按钮文案随「有没有选包 / 有没有输入」变化
        PrimaryButton(startLabel(sel != null, hasText), Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = if (imeOn) 10.dp else bottom + 6.dp).testTag("start"), enabled = sel != null && hasText) { begin() }
    }
}

/** 1.16：占位「把想说的话，留在这里」，停一会儿后删掉、打出两条示例、再回到它（16 字/秒打、34 字/秒删）。聚焦或「移除动画」时固定显示 */
const val WRITE_PLACEHOLDER = "把想说的话，留在这里"
val PLACEHOLDER_EXAMPLES = listOf("短剧为什么越来越“降智”？", "《甜蜜蜜》里，婚约之外的爱情")
@Composable
fun TypewriterPlaceholder(focused: Boolean) {
    var shown by remember { mutableStateOf(WRITE_PLACEHOLDER) }
    if (!focused && !Motion.reduced) LaunchedEffect(Unit) {
        val seq = listOf(WRITE_PLACEHOLDER) + PLACEHOLDER_EXAMPLES
        var i = 0
        while (true) {
            kotlinx.coroutines.delay(if (i % seq.size == 0) 2600 else 1400)
            val cur = seq[i % seq.size]; val next = seq[(i + 1) % seq.size]
            for (k in cur.length downTo 0) { shown = cur.take(k); kotlinx.coroutines.delay(30) }
            kotlinx.coroutines.delay(200)
            for (k in 0..next.length) { shown = next.take(k); kotlinx.coroutines.delay(62) }
            i++
        }
    } else LaunchedEffect(focused) { shown = WRITE_PLACEHOLDER }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(shown, fontSize = 17.sp, lineHeight = 27.sp, color = P3.text3, maxLines = 1)
        if (!focused) {
            val blink = if (Motion.reduced) 1f else androidx.compose.animation.core.rememberInfiniteTransition(label = "caret").animateFloat(1f, .15f,
                androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(450), androidx.compose.animation.core.RepeatMode.Reverse), label = "c").value
            Box(Modifier.padding(start = 2.dp).width(2.dp).height(20.dp).graphicsLayer { alpha = blink }.background(P3.accent))
        }
    }
}
