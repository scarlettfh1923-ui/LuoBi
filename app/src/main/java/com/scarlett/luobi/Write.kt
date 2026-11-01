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
 * 创作页状态（2.4+）：一段想法 + 选中的技能包 + 写作模型。全部存进 SharedPreferences：切底栏、旋转、进程被杀都不丢。
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
fun startLabel(hasSkill: Boolean, hasText: Boolean) = when { !hasSkill -> "先选一个技能包"; !hasText -> "先输入关键词"; else -> "搜资料并写" }

/**
 * 创作页（2.4.1）：页眉 → 输入卡 → 技能包列表；开始按钮固定在底栏上方。
 * 灵感整块与导入入口已移除；导入改在设定 → 技能包。两种顺序都行：先写再选，或先选再写。
 */
@Composable
fun WriteScreen(db: AppDb, ws: WriteState, bottom: Dp, genErr: GenErr?, clearGenErr: () -> Unit, goModels: () -> Unit, genProg: GenProgress? = null, onCancelGen: () -> Unit = {}, onOpenNote: (Long) -> Unit = {},
                session: SkillSession? = null, onResume: () -> Unit = {}, onStart: (String, SkillPack, String) -> Unit = { _, _, _ -> }) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var packs by remember { mutableStateOf(Skills.builtIns(ctx)) }
    LaunchedEffect(Unit) { packs = withContext(Dispatchers.IO) { Skills.list(ctx) } }
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

    val imeOn = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    val hasText = ws.raw.isNotBlank()
    Column(Modifier.fillMaxSize().imePadding()) {
        CreateHeader()
        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 20.dp)) {
            item(key = "input") {
                var focused by remember { mutableStateOf(false) }
                val minH by androidx.compose.animation.core.animateDpAsState(if (focused || ws.raw.length > 60) 150.dp else 110.dp, Motion.springDp(), label = "expand")
                var modelMenu by remember { mutableStateOf(false) }
                val models = remember(modelMenu) { Settings(ctx).configured() }
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().testTag("inputCard")
                    .softShadow(Sq.card26, 3.dp).clip(Sq.card26).background(P3.surface)
                    .hairline(Sq.card26, if (focused) P3.accent.copy(alpha = .45f) else P3.border).padding(horizontal = 18.dp, vertical = 16.dp)) {
                    Box(Modifier.fillMaxWidth().heightIn(min = minH)) {
                        if (ws.raw.isEmpty()) Text("写几个关键词，或一两句话…", fontSize = 17.sp, lineHeight = 27.sp, color = P3.text3)
                        BasicTextField(ws.raw, { ws.raw = it; ws.saveRaw() }, Modifier.fillMaxWidth().testTag("input").onFocusChanged { focused = it.isFocused },
                            textStyle = TextStyle(fontSize = 17.sp, lineHeight = 27.sp, color = P3.text), cursorBrush = SolidColor(P3.accent))
                    }
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
                Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
                    Text("技能包", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = P3.text)
                    Text(if (hasText || sel != null) "用哪个技能包？" else "也可先点某个包再写", Modifier.weight(1f).padding(start = 10.dp, bottom = 1.dp), fontSize = 12.sp, color = P3.text3, maxLines = 1)
                }
            }
            items(packs, key = { it.id }, contentType = { "skill" }) { p -> SkillPickCard(p, p.id == sel?.id) { ws.skillId = if (ws.skillId == p.id) "" else p.id; ws.saveSkill() } }
            startErr?.let { e -> item(key = "startErr") { Box(Modifier.animateItem(Motion.itemFade(), Motion.itemPlace(), Motion.itemFade()).padding(vertical = 2.dp)) { ErrorCard(e, null, if (e.settings) goModels else null) { startErr = null } } } }
            if (session != null && session.phase != "write" && session.phase != "done") item(key = "resume") { SkillResumeBanner(session, onResume) }
            if (genErr != null) item(key = "genErr") { Box(Modifier.animateItem(Motion.itemFade(), Motion.itemPlace(), Motion.itemFade()).padding(vertical = 2.dp)) { ErrorCard(genErr.f, { clearGenErr(); genErr.retry() }, if (genErr.f.settings) goModels else null) { clearGenErr() } } }
            item(key = "genCard") { GenCardSlot(genProg, onCancelGen, onOpenNote) }
        }
        PrimaryButton(startLabel(sel != null, hasText), Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = if (imeOn) 12.dp else bottom + 8.dp).testTag("start"), enabled = sel != null && hasText) { begin() }
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
