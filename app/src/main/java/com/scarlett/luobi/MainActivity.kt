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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_NOTE = "noteId"; const val EXTRA_FACT = "openFact" }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashGuard.onLaunch(this)
        enableEdgeToEdge()
        Notif.ensureChannels(this) // 2.0.1：通知权限改到第一次生成 / 校对前再问
        if (savedInstanceState == null) take(intent)
        Share.cleanDocs(this) // 1.10：启动时清理上次分享留下的临时文件
        Motion.init(this); Glass.init(this); if (CrashGuard.recovered) Glass.blur = false
        setContent { AppTheme { App() } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); take(intent) }
    private fun take(i: Intent?) {
        val id = i?.getLongExtra(EXTRA_NOTE, 0L) ?: 0L
        if (id > 0) { if (i?.getBooleanExtra(EXTRA_FACT, false) == true) { AppState.openFact.value = id; i.removeExtra(EXTRA_FACT) }; AppState.openNote.value = id; i?.removeExtra(EXTRA_NOTE) }
        if (i?.action == Intent.ACTION_SEND) i.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { AppState.sharedText.value = it }
    }
    override fun onResume() { super.onResume(); CrashGuard.healthyLater(this) }
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

class GenErr(val f: Friendly, val retry: () -> Unit)

@Composable
fun App() {
    val ctx = LocalContext.current
    val db = remember { AppDb.get(ctx) }
    LaunchedEffect(Unit) { Defaults.ensure(db, ctx) }
    var route by remember { mutableStateOf<Route>(Route.Tab(0)) }
    val stack = remember { mutableStateListOf<Route>() }
    var navDir by remember { mutableIntStateOf(0) }
    fun go(r: Route) { navDir = 1; stack.add(route); route = r }
    fun back() { navDir = -1; route = stack.removeLastOrNull() ?: Route.Tab(0) }
    // 2.4 技能包页面都在「创作」这个 tab 里：skillView = "" 创作首页 / "run" 搜资料·确认·写作流程 / "skills" 管理；底栏始终可点
    var skillView by remember { mutableStateOf("") }
    var planEditing by remember { mutableStateOf(false) }
    fun tab(i: Int) { navDir = if (route is Route.Tab) 0 else -1; stack.clear(); skillView = ""; planEditing = false; route = Route.Tab(i) }
    val ws = remember { WriteState(ctx) }
    remember { if (SkillFlow.state.value == null) SkillFlow.load(ctx); 0 }
    val session by SkillFlow.state.collectAsState()
    LaunchedEffect(Unit) { withContext(kotlinx.coroutines.Dispatchers.IO) { Skills.list(ctx) } }   // 预热技能包（读 assets / 文件放在后台）
    var genErr by remember { mutableStateOf<GenErr?>(null) }
    var libManaging by remember { mutableStateOf(false) }
    val host = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { Snack.flow.collect { m -> host.currentSnackbarData?.dismiss(); launch { host.showSnackbar(m) } } }
    LaunchedEffect(Unit) { AppState.sharedText.collect { t -> if (t != null) { ws.reset(); ws.raw = t; ws.saveRaw(); tab(0); AppState.sharedText.value = null } } }
    BackHandler(libManaging || route != Route.Tab(0) || skillView != "") {
        when {
            libManaging -> libManaging = false
            route is Route.Tab && skillView != "" -> if (planEditing) planEditing = false else skillView = ""
            route is Route.Tab -> tab(0)
            else -> back()
        }
    }

    // 生成跑在 WorkManager（进程级），切后台 / 旋转 / 页面销毁都不会中断
    val genPrefs = remember { ctx.getSharedPreferences("gen", Context.MODE_PRIVATE) }
    val wm = remember { androidx.work.WorkManager.getInstance(ctx) }
    val infos by remember { wm.getWorkInfosForUniqueWorkFlow(CopyWorker.NAME) }.collectAsState(initial = emptyList())
    val info = infos.firstOrNull { !it.state.isFinished } ?: infos.lastOrNull()
    val running = info != null && !info.state.isFinished
    val runningKind = remember(info?.id) { if (running) (try { GenJob.parse(genPrefs.getString("job", "{}") ?: "{}").kind } catch (_: Exception) { "write" }) else "" }
    // 1.18：不再弹全屏遮罩；写作的进度在创作页卡片里，改写用底部进行中提示
    val genProg = if (running && runningKind != "rewrite" && runningKind != "skill-plan") CopyWorker.progressOf(info!!.progress) else null
    LaunchedEffect(running, runningKind) { if (running && runningKind == "rewrite") snackBusy("正在改写…可以先做别的，改好会提醒你") }
    LaunchedEffect(Unit) { GenCard.load(ctx) }
    val notifGate = rememberNotifGate()
    val startGen: (GenJob) -> Unit = { j -> genErr = null; if (running) snack("正在写一篇，写完再开始下一篇", false) else notifGate { CopyWorker.start(ctx, j) } }
    LaunchedEffect(info?.id, info?.state) {
        val i = info ?: return@LaunchedEffect
        if (!i.state.isFinished) return@LaunchedEffect
        val wid = i.id.toString()
        if (genPrefs.getString("seen", null) == wid) return@LaunchedEffect
        genPrefs.edit().putString("seen", wid).apply()
        val doneJob = try { GenJob.parse(genPrefs.getString("job", "{}") ?: "{}") } catch (_: Exception) { null }
        if (doneJob?.kind == "skill-plan" || doneJob?.kind == "skill-write") {
            // 技能写作：状态都在 SkillFlow 里（SkillRun 已经把阶段改好），这里只做提示，不走 genErr / 清输入框
            CopyWorker.clearDoneNotice(ctx)
            when (i.state) {
                androidx.work.WorkInfo.State.CANCELLED -> snack(if (doneJob.kind == "skill-plan") "已取消搜资料" else "已取消写正文，提纲还在")
                androidx.work.WorkInfo.State.FAILED -> if (skillView != "run" && route is Route.Tab) snack(if (doneJob.kind == "skill-plan") "搜资料没成功，点创作页的提示可以重试" else "正文没写成，提纲还在", false)
                else -> {}
            }
            return@LaunchedEffect
        }
        when (i.state) {
            androidx.work.WorkInfo.State.SUCCEEDED -> {
                val id = i.outputData.getLong(CopyWorker.K_NOTE, 0L)
                val rewrite = i.outputData.getString(CopyWorker.K_KIND) == "rewrite"
                // 1.18：写作完成不跳转——卡片变成「已写好」+ 通知；只有改写还在阅读页时切到新版本
                if (rewrite) { CopyWorker.clearDoneNotice(ctx); if (id > 0 && route is Route.Read) route = Route.Read(id); snack("已改写，存为新版本，可以在版本里退回") }
                else { val job = try { GenJob.parse(genPrefs.getString("job", "{}") ?: "{}") } catch (_: Exception) { null }
                    if (job != null && ws.raw.trim() == job.raw.trim()) ws.reset() }
            }
            androidx.work.WorkInfo.State.FAILED -> {
                CopyWorker.clearDoneNotice(ctx)
                val o = i.outputData
                val f = Friendly(o.getString(CopyWorker.K_ERR_TITLE) ?: "生成失败", o.getString(CopyWorker.K_ERR_DETAIL) ?: "生成没有完成，请重试。",
                    o.getString(CopyWorker.K_ERR_RAW) ?: "", o.getBoolean(CopyWorker.K_ERR_SET, false))
                val job = try { GenJob.parse(genPrefs.getString("job", "{}") ?: "{}") } catch (_: Exception) { null }
                // 2.0.2：输入框在开始生成时已清空；失败且输入框仍空着时，把原文放回去，方便改了再试
                if (job != null && job.kind == "write" && ws.raw.isBlank() && job.raw.isNotBlank()) { ws.raw = job.raw; ws.saveRaw() }
                genErr = GenErr(f) { if (job != null) startGen(job) }
                if (job?.kind == "rewrite") snack("改写失败：${f.title}", false, "重试") { startGen(job) }
            }
            androidx.work.WorkInfo.State.CANCELLED -> snack("已取消生成")
            else -> {}
        }
    }
    // 技能写作：写好后——人还在流程页就直接打开文章；不在就收起会话，创作页的「已写好」卡片接手
    val sessionDone = session?.takeIf { it.phase == "done" }
    LaunchedEffect(sessionDone?.noteId, skillView, route) {
        val d = sessionDone ?: return@LaunchedEffect
        if (skillView == "run" && route == Route.Tab(0) && d.noteId > 0) { GenCard.clear(ctx); CopyWorker.clearDoneNotice(ctx); SkillFlow.clear(ctx); skillView = ""; planEditing = false; go(Route.Read(d.noteId)) }
        else if (skillView != "run") SkillFlow.clear(ctx)
    }
    LaunchedEffect(session == null, skillView) { if (session == null && skillView == "run") skillView = "" }
    // 进行中却没有后台任务：被系统收走了（给 WorkManager 2 秒回报状态，再判定）
    var stale by remember { mutableStateOf(false) }
    LaunchedEffect(session?.phase, session?.startedAt, running) {
        stale = false
        if (session?.busy == true && !running) { kotlinx.coroutines.delay(2000); stale = true }
    }
    var confirmAsk by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    fun launchPlan(topic: String, pack: SkillPack, model: String, clearInput: Boolean) {
        notifGate {
            SkillFlow.start(ctx, topic, pack, model)
            CopyWorker.start(ctx, GenJob(kind = "skill-plan", raw = topic, templateName = pack.displayName, model = model))
            if (clearInput) { ws.raw = ""; ws.saveRaw() }
            genErr = null; planEditing = false; skillView = "run"
        }
    }
    fun startSkill(topic: String, pack: SkillPack, model: String) {
        val cur = SkillFlow.state.value
        when {
            running || cur?.busy == true -> snack("正在写一篇，写完再开始下一篇", false)
            cur != null && cur.phase == "confirm" -> confirmAsk = "开始新的会丢掉上一篇还没确认的角度和提纲，要继续吗？" to { launchPlan(topic, pack, model, true) }
            else -> launchPlan(topic, pack, model, true)
        }
    }
    fun confirmWrite(plan: SkillPlan) {
        val cur = SkillFlow.state.value ?: return
        if (running) { snack("稍等一下再点", false); return }
        notifGate {
            SkillFlow.set(ctx, cur.copy(phase = "write", plan = plan, err = null, words = 0, title = "", line = "", startedAt = System.currentTimeMillis()))
            CopyWorker.start(ctx, GenJob(kind = "skill-write", raw = cur.topic, templateName = cur.skillName, model = cur.model))
            planEditing = false
        }
    }
    fun restartPlan() {
        val cur = SkillFlow.state.value ?: return
        val pack = Skills.find(ctx, cur.skillId)
        if (pack == null) { SkillFlow.clear(ctx); skillView = ""; snack("这个技能包已被删除，请重新选一个", false); return }
        if (running) { CopyWorker.cancel(ctx) }
        launchPlan(cur.topic, pack, cur.model, false)
    }
    fun cancelSkill() {
        val cur = SkillFlow.state.value ?: return
        CopyWorker.cancel(ctx)
        if (cur.phase == "plan") {
            SkillFlow.clear(ctx); skillView = ""
            if (ws.raw.isBlank()) { ws.raw = cur.topic; ws.saveRaw() }
            ws.skillId = cur.skillId; ws.saveSkill()
        } else if (cur.phase == "write") SkillFlow.set(ctx, cur.copy(phase = "confirm", err = null))
    }
    fun retrySkill() { val cur = SkillFlow.state.value ?: return; if (cur.phase == "write") SkillFlow.set(ctx, cur.copy(phase = "confirm", err = null)) else restartPlan() }
    fun closeSkill() {
        val cur = SkillFlow.state.value
        SkillFlow.clear(ctx); skillView = ""
        if (cur != null && ws.raw.isBlank()) { ws.raw = cur.topic; ws.saveRaw(); ws.skillId = cur.skillId; ws.saveSkill() }
    }
    LaunchedEffect(Unit) {
        AppState.openNote.collect { id -> if (id > 0) { CopyWorker.clearDoneNotice(ctx); go(Route.Read(id)); AppState.openNote.value = 0L } }
    }

    Box(Modifier.fillMaxSize().background(P3.bg)) {
        AnimatedContent(route, transitionSpec = {
            // 1.10：统一动效——底栏切换淡入淡出，推入/返回水平滑动
            when { targetState is Route.Tab && initialState is Route.Tab -> Motion.tab(); navDir < 0 -> Motion.pop(); else -> Motion.push() }
        }, label = "nav") { r ->
            when (r) {
                is Route.Tab -> { val bd = rememberBackdrop(); CompositionLocalProvider(LocalBackdrop provides bd) { Scaffold(containerColor = P3.bg, bottomBar = { if (!(r.i == 1 && libManaging)) FloatingNav(r.i) { libManaging = false; tab(it) } }) { pad ->
                    Box(Modifier.fillMaxSize().backdropSource(bd).padding(top = pad.calculateTopPadding())) { CompositionLocalProvider(LocalBackdrop provides null) { // 1.18.1：模糊来源内部的玻璃不能再取样自己（Haze 递归绘制 → 闪退）
                        val bottom = pad.calculateBottomPadding()
                        when (r.i) {
                            0 -> {
                                val view = if (skillView == "run" && session != null) "run" else if (skillView == "skills") "skills" else ""
                                androidx.compose.animation.Crossfade(view, animationSpec = androidx.compose.animation.core.tween(180), label = "skill") { v ->
                                    when (v) {
                                        "skills" -> SkillManagePage(bottom, { skillView = "" }, statusPad = false)
                                        "run" -> session?.let { sx ->
                                            SkillFlowScreen(sx, stale, bottom, planEditing, { planEditing = it }, { if (planEditing) planEditing = false else skillView = "" }, ::cancelSkill, ::confirmWrite,
                                                { confirmAsk = "重新搜一遍会丢掉现在的角度和提纲（包括你改过的），要继续吗？" to { restartPlan() } }, ::retrySkill, ::closeSkill, { go(Route.Sub("models")) })
                                        }
                                        else -> WriteScreen(db, ws, bottom, genErr, { genErr = null }, { go(Route.Sub("models")) }, genProg, { CopyWorker.cancel(ctx) }, { go(Route.Read(it)) },
                                            session, { skillView = "skills" }, { skillView = "run"; planEditing = false }, ::startSkill)
                                    }
                                }
                            }
                            1 -> LibraryScreen(db, bottom, { tab(0) }, libManaging, { libManaging = it }) { go(Route.Read(it)) }
                            else -> SettingsHome(db, bottom) { go(Route.Sub(it)) }
                        }
                    } }
                } } }
                is Route.Read -> ReaderScreen(db, r.id, running, ::back, { route = Route.Read(it) }, { go(Route.Sub(it)) }, startGen)
                is Route.Sub -> SubPage(db, r, ::back) { go(it) }
            }
        }
        confirmAsk?.let { (msg, ok) -> ConfirmDialog("确认一下", msg, "继续", onOk = { confirmAsk = null; ok() }, onDismiss = { confirmAsk = null }) }
        SnackbarHost(host, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = 96.dp)) { SoftSnackbar(it) }
    }
}

fun fieldHint(k: String) = when (k) { "疑问" -> "我的疑问是什么"; "观点" -> "我想表达的观点"; "想写什么" -> "我想写一篇什么样的文章"; else -> "自定义字段" }

@Composable
fun LazyColumnFill(bottom: androidx.compose.ui.unit.Dp, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    // 1.8 修复：键盘弹出时不再预留底栏高度（之前 imePadding + 底栏留白叠加，键盘上方空出一大块）；只留主按钮的位置
    val imeOn = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(bottom = if (imeOn) 76.dp else bottom + 20.dp), content = content)
}
