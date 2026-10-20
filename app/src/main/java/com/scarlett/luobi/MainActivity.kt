@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package com.scarlett.luobi

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_NOTE = "noteId" }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (savedInstanceState == null) take(intent)
        setContent { AppTheme { App() } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); take(intent) }
    private fun take(i: Intent?) {
        val id = i?.getLongExtra(EXTRA_NOTE, 0L) ?: 0L
        if (id > 0) { AppState.openNote.value = id; i?.removeExtra(EXTRA_NOTE) }
        if (i?.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { AppState.sharedText.value = it }
    }
    override fun onStart() { super.onStart(); AppState.visible++ }
    override fun onStop() { super.onStop(); AppState.visible-- }
}

sealed class Route {
    data class Tab(val i: Int) : Route()
    data class Read(val id: Long) : Route()
    data class Sub(val page: String, val arg: Long = 0) : Route()
}

fun dayLabel(t: Long) = SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date(t))
fun timeLabel(t: Long) = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(t))

/** 写作页状态（切换底栏不丢） */
class WriteState(ctx: Context) {
    private val p = ctx.getSharedPreferences("draft", Context.MODE_PRIVATE)
    var raw by mutableStateOf(p.getString("raw", "") ?: "")
    var step by mutableIntStateOf(1)
    var cleaned by mutableStateOf("")
    val values = mutableStateListOf<Pair<String, String>>()
    var fields by mutableStateOf(Vars.DEFAULT_FIELDS)
    var recommendId by mutableLongStateOf(0L)
    var templateId by mutableLongStateOf(0L)
    var model by mutableStateOf("")
    var cleanBusy by mutableStateOf(false)
    /** 第 ① 步已拿到清洗结果，正在确认意图 */
    var cleanedReady by mutableStateOf(false)
    var err by mutableStateOf<Friendly?>(null)
    fun saveRaw() = p.edit().putString("raw", raw).apply()
    fun map() = LinkedHashMap<String, String>().also { out -> this.values.forEach { (k, v) -> if (v.isNotBlank()) out[k] = v.trim() } }
    fun reset() { step = 1; cleanedReady = false; cleaned = ""; values.clear(); recommendId = 0; templateId = 0; err = null }
}

class GenErr(val f: Friendly, val retry: () -> Unit)

@Composable
fun App() {
    val ctx = LocalContext.current
    val db = remember { AppDb.get(ctx) }
    LaunchedEffect(Unit) { Defaults.ensure(db) }
    var route by remember { mutableStateOf<Route>(Route.Tab(0)) }
    val stack = remember { mutableStateListOf<Route>() }
    fun go(r: Route) { stack.add(route); route = r }
    fun back() { route = stack.removeLastOrNull() ?: Route.Tab(0) }
    fun tab(i: Int) { stack.clear(); route = Route.Tab(i) }
    val ws = remember { WriteState(ctx) }
    var genErr by remember { mutableStateOf<GenErr?>(null) }
    var libManaging by remember { mutableStateOf(false) }
    val host = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { Snack.flow.collect { m -> host.currentSnackbarData?.dismiss(); launch { host.showSnackbar(m) } } }
    LaunchedEffect(Unit) { AppState.sharedText.collect { t -> if (t != null) { ws.reset(); ws.raw = t; ws.saveRaw(); tab(0); AppState.sharedText.value = null } } }
    BackHandler(libManaging || route != Route.Tab(0)) {
        when { libManaging -> libManaging = false; route is Route.Tab -> tab(0); else -> back() }
    }

    // 生成跑在 WorkManager（进程级），切后台 / 旋转 / 页面销毁都不会中断
    val genPrefs = remember { ctx.getSharedPreferences("gen", Context.MODE_PRIVATE) }
    val wm = remember { androidx.work.WorkManager.getInstance(ctx) }
    val infos by remember { wm.getWorkInfosForUniqueWorkFlow(CopyWorker.NAME) }.collectAsState(initial = emptyList())
    val info = infos.firstOrNull { !it.state.isFinished } ?: infos.lastOrNull()
    val running = info != null && !info.state.isFinished
    val startGen: (GenJob) -> Unit = { j -> genErr = null; if (!running) CopyWorker.start(ctx, j) }
    LaunchedEffect(info?.id, info?.state) {
        val i = info ?: return@LaunchedEffect
        if (!i.state.isFinished) return@LaunchedEffect
        val wid = i.id.toString()
        if (genPrefs.getString("seen", null) == wid) return@LaunchedEffect
        genPrefs.edit().putString("seen", wid).apply()
        when (i.state) {
            androidx.work.WorkInfo.State.SUCCEEDED -> {
                CopyWorker.clearDoneNotice(ctx)
                val id = i.outputData.getLong(CopyWorker.K_NOTE, 0L)
                val rewrite = i.outputData.getString(CopyWorker.K_KIND) == "rewrite"
                if (id > 0) { if (rewrite && route is Route.Read) route = Route.Read(id) else { if (!rewrite) ws.reset(); go(Route.Read(id)) }; if (rewrite) snack("已改写，存为新版本，可以在版本里退回") }
            }
            androidx.work.WorkInfo.State.FAILED -> {
                CopyWorker.clearDoneNotice(ctx)
                val o = i.outputData
                val f = Friendly(o.getString(CopyWorker.K_ERR_TITLE) ?: "生成失败", o.getString(CopyWorker.K_ERR_DETAIL) ?: "生成没有完成，请重试。",
                    o.getString(CopyWorker.K_ERR_RAW) ?: "", o.getBoolean(CopyWorker.K_ERR_SET, false))
                val job = try { GenJob.parse(genPrefs.getString("job", "{}") ?: "{}") } catch (_: Exception) { null }
                genErr = GenErr(f) { if (job != null) startGen(job) }
                if (job?.kind == "rewrite") snack("改写失败：${f.title}", false, "重试") { startGen(job) }
            }
            androidx.work.WorkInfo.State.CANCELLED -> snack("已取消生成")
            else -> {}
        }
    }
    LaunchedEffect(Unit) {
        AppState.openNote.collect { id -> if (id > 0) { CopyWorker.clearDoneNotice(ctx); go(Route.Read(id)); AppState.openNote.value = 0L } }
    }

    Box(Modifier.fillMaxSize().background(P3.bg)) {
        AnimatedContent(route, transitionSpec = {
            if (targetState is Route.Tab && initialState is Route.Tab) fadeIn(tween(200)) togetherWith fadeOut(tween(150))
            else fadeIn(tween(220)) togetherWith fadeOut(tween(160))
        }, label = "nav") { r ->
            when (r) {
                is Route.Tab -> { val bd = rememberBackdrop(); CompositionLocalProvider(LocalBackdrop provides bd) { Scaffold(containerColor = P3.bg, bottomBar = { if (!(r.i == 1 && libManaging)) FloatingNav(r.i) { libManaging = false; tab(it) } }) { pad ->
                    Box(Modifier.fillMaxSize().backdropSource(bd).padding(top = pad.calculateTopPadding())) {
                        val bottom = pad.calculateBottomPadding()
                        when (r.i) {
                            0 -> WriteScreen(db, ws, bottom, genErr, { genErr = null }, { go(Route.Sub("models")) }, { go(Route.Sub("templates")) }, startGen, { go(Route.Sub("length")) }, { go(Route.Sub("template:$it")) })
                            1 -> LibraryScreen(db, bottom, { tab(0) }, libManaging, { libManaging = it }) { go(Route.Read(it)) }
                            else -> SettingsHome(db, bottom) { go(Route.Sub(it)) }
                        }
                    }
                } } }
                is Route.Read -> ReaderScreen(db, r.id, running, ::back, { route = Route.Read(it) }, { go(Route.Sub(it)) }, startGen)
                is Route.Sub -> SubPage(db, r, ::back) { go(it) }
            }
        }
        SnackbarHost(host, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = 96.dp)) { SoftSnackbar(it) }
        AnimatedVisibility(running, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color(0x55000000)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
                Column(Modifier.padding(32.dp).clip(Sq.xl).background(P3.surface).padding(start = 32.dp, end = 32.dp, top = 28.dp, bottom = 12.dp).widthIn(max = 280.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(strokeWidth = 3.dp, color = P3.accent)
                    Spacer(Modifier.height(18.dp))
                    Text(if (GenJob.parse(genPrefs.getString("job", "{}") ?: "{}").kind == "rewrite") "正在改写…" else "正在写文章…", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = P3.text)
                    Text("通常 15–60 秒。可以切出去，写好会通知你", Modifier.padding(top = 6.dp), fontSize = 12.sp, color = P3.text3, textAlign = TextAlign.Center)
                    TextButton(onClick = { CopyWorker.cancel(ctx) }, Modifier.padding(top = 4.dp), shape = Sq.full) { Text("取消", color = P3.text2) }
                }
            }
        }
    }
}

// ---------------- 写作：三步 ----------------
@Composable
private fun StepHead(n: Int, title: String, state: Int, right: (@Composable () -> Unit)? = null) {
    // state: 0 未轮到 1 进行中 2 完成
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(Sq.full).background(when (state) { 0 -> P3.surface2; else -> P3.accent }), contentAlignment = Alignment.Center) {
            if (state == 2) SvgIcon(Ic.check, Color.White, 18.dp) else Text("$n", color = if (state == 0) P3.text3 else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = Mono)
        }
        Spacer(Modifier.width(12.dp))
        Text(title, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (state == 0) P3.text3 else P3.text)
        right?.invoke()
    }
}

/** 已完成的步骤收成一行：✓ 标题 · 摘要  修改 */
@Composable
private fun DoneRow(title: String, summary: String, edit: () -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(Sq.l).background(P3.surface).hairline(Sq.l, P3.border, 1.dp)
        .clickable(onClick = edit).padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp).clip(Sq.full).background(P3.accent), contentAlignment = Alignment.Center) { SvgIcon(Ic.check, Color.White, 13.dp) }
        Text(title, Modifier.padding(start = 10.dp), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = P3.text, maxLines = 1)
        Text("  " + summary, Modifier.weight(1f), fontSize = 13.sp, color = P3.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        TextAction("修改") { edit() }
    }
}

@Composable
private fun Locked(n: Int, title: String, sub: String) {
    Card(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).alpha(.6f), color = P3.surface) {
        StepHead(n, title, 0)
        Text(sub, Modifier.padding(start = 40.dp, top = 4.dp), fontSize = 13.sp, color = P3.text3)
    }
}

@Composable
fun WriteScreen(db: AppDb, ws: WriteState, bottom: androidx.compose.ui.unit.Dp, genErr: GenErr?, clearGenErr: () -> Unit, goModels: () -> Unit, goTemplates: () -> Unit, start: (GenJob) -> Unit, goLength: () -> Unit = {}, goTemplate: (Long) -> Unit = {}) {
    val ctx = LocalContext.current; val s = remember { Settings(ctx) }; val scope = rememberCoroutineScope()
    val templates by db.templates().all().collectAsState(initial = emptyList())
    var keyVer by remember { mutableIntStateOf(0) }
    val hasDeepseek = remember(keyVer) { s.key("deepseek").isNotBlank() }
    LaunchedEffect(Unit) { keyVer++ }

    fun clean() {
        if (ws.raw.isBlank()) { snack("先粘贴一段原文", false); return }
        ws.cleanBusy = true; ws.err = null; ws.saveRaw()
        scope.launch {
            try {
                val (r, ct) = Ai.clean(ctx, ws.raw.trim())
                ws.cleaned = r.cleaned; ws.fields = ct.fieldList()
                ws.values.clear(); r.values.forEach { (k, v) -> ws.values.add(k to v) }
                val list = db.templates().list()
                val rec = CleanParse.matchPrefer(r.recommend, list, { it.name }, { !it.builtIn }) ?: list.firstOrNull()
                ws.recommendId = rec?.id ?: 0; ws.templateId = rec?.id ?: 0
                ws.step = 1
                ws.cleanedReady = true
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: CleanParse.BadFormat) { ws.err = Friendly("清洗结果格式不对", "DeepSeek 返回的内容不是约定的 JSON。可以点「重试」，或在「设置 → 清洗模板」检查提示词的输出约定。", e.raw) }
            catch (e: Throwable) { ws.err = Errors.explain(e).let { if (e is ConfigError) it.copy(settings = true) else it } }
            finally { ws.cleanBusy = false }
        }
    }

    val tpl = templates.firstOrNull { it.id == ws.templateId }
    val configured = remember(keyVer, ws.step) { s.configured() }
    LaunchedEffect(ws.step, configured) {
        if (ws.step == 3 && configured.none { it.ref == ws.model }) {
            val pref = tpl?.defaultModel?.takeIf { d -> configured.any { it.ref == d } } ?: s.defaultModel.takeIf { d -> configured.any { it.ref == d } }
            ws.model = pref ?: configured.firstOrNull()?.ref ?: ""
        }
    }
    val length = s.length; val lenRange = s.lengthRange()
    var attachRaw by remember { mutableStateOf(s.attachRaw) }
    var preview by remember { mutableStateOf(false) }
    val vals = ws.map()
    val finalPrompt = if (tpl != null) Ai.finalPrompt(tpl.prompt, vals, ws.cleaned, attachRaw, length, lenRange, ws.fields.toSet()) else ""
    val known = ws.fields.toSet() + Vars.RAW

    Box(Modifier.fillMaxSize()) {
    LazyColumnFill(bottom + 64.dp) {
        item { BigTitle("落笔", "口播 → 文章") }
        item { InlineStepper(ws.step) { if (it < ws.step) ws.step = it } }
        // ① 清洗与意图
        item {
            if (ws.step > 1) DoneRow("清洗与意图", ws.values.filter { it.second.isNotBlank() }.joinToString(" · ") { it.first }.ifBlank { "未识别到意图" }) { ws.step = 1 }
            else Card(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                if (!ws.cleanedReady) {
                    StepHead(1, "内容清洗与意图识别", 1) { Text("DeepSeek", Modifier.clip(Sq.full).background(P3.surface2).padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 12.sp, color = P3.text2, fontFamily = Mono) }
                    Spacer(Modifier.height(12.dp))
                    // 1.5：不再卡片套卡片——步骤卡本身就是输入区
                    Column(Modifier.fillMaxWidth()) {
                        Text("粘贴你的口播 / 想说的话 / 疑问", fontSize = 13.sp, color = P3.text3)
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth().heightIn(min = 150.dp)) {
                            androidx.compose.foundation.text.BasicTextField(ws.raw, { ws.raw = it }, Modifier.fillMaxWidth(),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 27.sp, color = P3.text), cursorBrush = androidx.compose.ui.graphics.SolidColor(P3.accent))
                        }
                        HorizontalDivider(Modifier.padding(top = 8.dp), thickness = 0.5.dp, color = P3.border)
                        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${ws.raw.length} 字", Modifier.weight(1f), fontSize = 12.sp, color = P3.text3, fontFamily = Mono)
                            TextAction("粘贴") {
                                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val t = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
                                if (t.isBlank()) snack("剪贴板是空的", false) else { ws.raw = if (ws.raw.isBlank()) t else ws.raw + "\n" + t; ws.saveRaw() }
                            }
                            Text("·", color = P3.text3)
                            TextAction("清空") { ws.raw = ""; ws.saveRaw() }
                        }
                    }
                    Text("去掉口头禅、重复和错字，把句子理顺，再判断你想写哪类文章。", Modifier.padding(vertical = 10.dp), fontSize = 13.sp, color = P3.text2, lineHeight = 19.sp)
                    if (!hasDeepseek) WarnBox("清洗固定用 DeepSeek，请先到「设置 → 模型与密钥」填写 DeepSeek Key。", Modifier.padding(bottom = 10.dp).clickable { goModels() })
                } else {
                    StepHead(1, "内容清洗与意图识别", 1) { TextAction(if (ws.cleanBusy) "识别中…" else "重新识别", enabled = !ws.cleanBusy) { clean() } }
                    FieldLabel("清洗后文字", "可编辑")
                    InputBox(ws.cleaned, { ws.cleaned = it }, "清洗后的文字", minHeight = 80.dp)
                    FieldLabel("识别到的意图", "可编辑")
                    ws.values.toList().forEachIndexed { i, (k, v) ->
                        Column(Modifier.padding(bottom = 8.dp).fillMaxWidth().clip(Sq.m).background(P3.surface2).hairline(Sq.m, P3.border, 1.dp).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("{$k}", fontSize = 13.sp, color = P3.accent, fontWeight = FontWeight.SemiBold)
                                Text(fieldHint(k), Modifier.weight(1f).padding(start = 8.dp), fontSize = 12.sp, color = P3.text3, textAlign = TextAlign.End, maxLines = 1)
                                Box(Modifier.size(30.dp).clip(Sq.full).clickable { ws.values.removeAt(i) }, contentAlignment = Alignment.Center) { SvgIcon(Ic.close, P3.text3, 16.dp, desc = "删除") }
                            }
                            androidx.compose.foundation.text.BasicTextField(v, { nv -> ws.values[i] = k to nv }, Modifier.fillMaxWidth().padding(end = 8.dp),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, lineHeight = 23.sp, color = P3.text), cursorBrush = androidx.compose.ui.graphics.SolidColor(P3.accent))
                        }
                    }
                    ws.fields.filter { f -> ws.values.none { it.first == f } }.forEach { f ->
                        Row(Modifier.padding(bottom = 8.dp).fillMaxWidth().clip(Sq.m).hairline(Sq.m, P3.borderStrong, 1.dp).clickable { ws.values.add(f to "") }
                            .padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            SvgIcon(Ic.plus, P3.accent, 16.dp); Text(" 添加 {$f}", fontSize = 14.sp, color = P3.accent)
                            Text("  ${fieldHint(f)}（没识别到）", fontSize = 12.sp, color = P3.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    TextAction("返回修改原文", P3.text2) { ws.cleanedReady = false }
                }
                ws.err?.let { e -> Spacer(Modifier.height(10.dp)); ErrorCard(e, { clean() }, if (e.settings) goModels else null) { ws.err = null } }
            }
        }
        // ② 选模板
        item {
            when {
                ws.step < 2 -> Locked(2, "选择模板", "根据意图推荐模板")
                ws.step > 2 -> DoneRow("模板", (tpl?.name ?: "未选择") + (if (tpl != null && !tpl.builtIn) " · 我的" else "") + if (tpl?.id == ws.recommendId) " · 推荐" else "") { ws.step = 2 }
                else -> Card(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    StepHead(2, "选择模板", 1)
                    Spacer(Modifier.height(10.dp))
                    var all by remember { mutableStateOf(false) }
                    val sorted = templates.sortedByDescending { it.id == ws.recommendId }
                    (if (all) sorted else sorted.take(4)).forEach { t ->
                        val sel = t.id == ws.templateId
                        Column(Modifier.padding(bottom = 8.dp).fillMaxWidth().clip(Sq.l).background(if (sel) P3.accentSoft else P3.surface2)
                            .hairline(Sq.l, if (sel) P3.accent else P3.border, if (sel) 1.5.dp else 1.dp).clickable { ws.templateId = t.id }.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(t.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = P3.text)
                                if (t.id == ws.recommendId) Text("推荐", Modifier.padding(start = 8.dp).clip(Sq.full).background(P3.accent).padding(horizontal = 8.dp, vertical = 2.dp), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                if (!t.builtIn) Text("我的", Modifier.padding(start = 8.dp).clip(Sq.full).background(P3.surface).hairline(Sq.full, P3.borderStrong, 1.dp).padding(horizontal = 8.dp, vertical = 1.dp), color = P3.text2, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Text(t.desc, Modifier.padding(top = 3.dp), fontSize = 13.sp, color = P3.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        if (sorted.size > 4) TextAction(if (all) "收起" else "查看全部模板（${sorted.size}）") { all = !all }
                        TextAction("管理模板", P3.text2) { goTemplates() }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
        // ③ 选模型生成
        item {
            if (ws.step < 3) Locked(3, "选择模型并生成", "从已配置的模型里选一个")
            else Card(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                StepHead(3, "选择模型并生成", 1)
                FieldLabel("模型")
                if (configured.isEmpty()) WarnBox("还没有配置任何模型的 Key，请先到「设置 → 模型与密钥」填写。", Modifier.clickable { goModels() })
                else ChoiceList(configured.map { Triple(it.label, it.sub, ws.model == it.ref) }) { i -> ws.model = configured[i].ref }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = P3.border)
                Row(Modifier.fillMaxWidth().clickable { goLength() }.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("篇幅", Modifier.weight(1f), fontSize = 15.sp, color = P3.text)
                    Text(if (tpl != null && Lengths.inTemplate(tpl.prompt)) "模板已指定" else Lengths.label(length, lenRange).replace("（", " · ").replace("）", ""), fontSize = 14.sp, color = P3.text2)
                    Text("  ›", fontSize = 15.sp, color = P3.text3)
                }
                HorizontalDivider(color = P3.border)
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("附上清洗后的原文", fontSize = 15.sp, color = P3.text)
                        Text("作为参考交给写作模型", fontSize = 12.sp, color = P3.text3)
                    }
                    Switch(attachRaw, { attachRaw = it; s.attachRaw = it }, colors = SwitchDefaults.colors(checkedTrackColor = P3.accent))
                }
            }
        }
        if (ws.step == 3 && tpl != null) item {
            Card(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth().clickable { preview = !preview }, verticalAlignment = Alignment.CenterVertically) {
                    Text("最终提示词预览", Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = P3.text)
                    Text(if (preview) "收起" else "展开", fontSize = 14.sp, color = P3.accent)
                }
                if (preview) {
                    val v2 = vals.toMutableMap().apply { if (attachRaw) put(Vars.RAW, ws.cleaned) }
                    val segs = Vars.segmentsK(tpl.prompt, v2, known)
                    val ownLen = Lengths.inTemplate(tpl.prompt)
                    Text(buildAnnotatedString {
                        segs.forEach { (t, k) -> when (k) {
                            1 -> withStyle(SpanStyle(background = P3.accentSoft, color = P3.onAccentSoft, fontWeight = FontWeight.SemiBold)) { append(t) }
                            2 -> withStyle(SpanStyle(background = P3.warnBg, color = P3.warnText)) { append(t) }
                            else -> append(t) } }
                        if (!ownLen) { append("\n"); append(Lengths.hint(length, lenRange)) }
                    }, Modifier.padding(top = 10.dp), fontSize = 14.sp, lineHeight = 23.sp, color = P3.text)
                    val unk = Vars.unknown(tpl.prompt, v2, known)
                    if (unk.isNotEmpty()) Text("${unk.joinToString("") { "{$it}" }} 不是清洗字段，按原样发送（黄色）", Modifier.padding(top = 6.dp), fontSize = 12.sp, color = P3.warnText)
                    if (ownLen) Text("模板里已写「篇幅」，以模板为准，不再追加设置里的篇幅", Modifier.padding(top = 6.dp), fontSize = 12.sp, color = P3.text3)
                    val empties = Vars.emptyUsed(tpl.prompt, v2); val dropped = Vars.droppedLines(tpl.prompt, v2, known)
                    Text("高亮的是识别出的意图" + if (empties.isNotEmpty()) " · ${empties.joinToString("") { "{$it}" }} 为空" + (if (dropped > 0) "，去掉了 $dropped 个只含该变量的短行" else "，只去掉占位") else "", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = P3.text3)
                }
                Row(Modifier.padding(top = 10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("模板「${tpl.name}」" + (if (tpl.updatedAt > 0) "最后修改 " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(tpl.updatedAt)) else "未修改过"), Modifier.weight(1f), fontSize = 12.sp, color = P3.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("编辑", Modifier.clip(Sq.s).clickable { goTemplate(tpl.id) }.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 12.sp, color = P3.accent)
                }
            }
        }
        if (genErr != null && ws.step == 3) item { ErrorCard(genErr.f, { clearGenErr(); genErr.retry() }, if (genErr.f.settings) goModels else null) { clearGenErr() } }
    }
    // 1.5：主按钮固定在浮动导航之上，永远不会被挡
    Box(Modifier.fillMaxSize().padding(bottom = bottom + 4.dp), contentAlignment = Alignment.BottomCenter) {
        val m = Modifier.padding(horizontal = 16.dp)
        when {
            ws.step == 1 && !ws.cleanedReady -> PrimaryButton(if (ws.cleanBusy) "正在清洗…" else "清洗并识别意图", m, busy = ws.cleanBusy, enabled = ws.raw.isNotBlank()) { clean() }
            ws.step == 1 -> PrimaryButton("确认，下一步", m, enabled = ws.cleaned.isNotBlank()) { ws.step = 2 }
            ws.step == 2 -> PrimaryButton("下一步", m, enabled = tpl != null) { ws.step = 3 }
            else -> PrimaryButton("生成文章", m, enabled = tpl != null && ws.model.isNotBlank()) {
                val t = tpl ?: return@PrimaryButton
                start(GenJob("write", ws.raw, ws.cleaned, vals, t.id, t.name, ws.model, finalPrompt))
            }
        }
    }
    }
}

fun fieldHint(k: String) = when (k) { "疑问" -> "我的疑问是什么"; "观点" -> "我想表达的观点"; "想写什么" -> "我想写一篇什么样的文章"; else -> "自定义字段" }

@Composable
fun LazyColumnFill(bottom: androidx.compose.ui.unit.Dp, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(bottom = bottom + 20.dp), content = content)
}

/** 1.5：紧凑的行内步骤条  1 理清 — 2 选模板 — 3 生成 */
@Composable
fun InlineStepper(step: Int, go: (Int) -> Unit) {
    Row(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf("理清", "选模板", "生成").forEachIndexed { i, t ->
            val n = i + 1; val cur = n == step; val done = n < step
            Text("$n $t", Modifier.clip(Sq.full).clickable(enabled = done) { go(n) }.padding(horizontal = 4.dp, vertical = 2.dp), fontSize = 13.sp,
                color = if (cur) P3.accent else if (done) P3.text else P3.text3, fontWeight = if (cur) FontWeight.SemiBold else FontWeight.Normal)
            if (n < 3) Box(Modifier.padding(horizontal = 6.dp).width(16.dp).height(1.dp).background(P3.border))
        }
    }
}
