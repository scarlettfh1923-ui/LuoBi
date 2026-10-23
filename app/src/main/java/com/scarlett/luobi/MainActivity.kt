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
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_NOTE = "noteId"; const val EXTRA_FACT = "openFact" }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (savedInstanceState == null) take(intent)
        Share.cleanDocs(this) // 1.10：启动时清理上次分享留下的临时文件
        Motion.init(this); Glass.init(this)
        setContent { AppTheme { App() } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); take(intent) }
    private fun take(i: Intent?) {
        val id = i?.getLongExtra(EXTRA_NOTE, 0L) ?: 0L
        if (id > 0) { if (i?.getBooleanExtra(EXTRA_FACT, false) == true) { AppState.openFact.value = id; i.removeExtra(EXTRA_FACT) }; AppState.openNote.value = id; i?.removeExtra(EXTRA_NOTE) }
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

class GenErr(val f: Friendly, val retry: () -> Unit)

@Composable
fun App() {
    val ctx = LocalContext.current
    val db = remember { AppDb.get(ctx) }
    LaunchedEffect(Unit) { Defaults.ensure(db) }
    var route by remember { mutableStateOf<Route>(Route.Tab(0)) }
    val stack = remember { mutableStateListOf<Route>() }
    var navDir by remember { mutableIntStateOf(0) }
    fun go(r: Route) { navDir = 1; stack.add(route); route = r }
    fun back() { navDir = -1; route = stack.removeLastOrNull() ?: Route.Tab(0) }
    fun tab(i: Int) { navDir = if (route is Route.Tab) 0 else -1; stack.clear(); route = Route.Tab(i) }
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
    val runningKind = remember(info?.id) { if (running) (try { GenJob.parse(genPrefs.getString("job", "{}") ?: "{}").kind } catch (_: Exception) { "write" }) else "" }
    // 1.18：不再弹全屏遮罩；写作的进度在创作页卡片里，改写用底部进行中提示
    val genProg = if (running && runningKind != "rewrite") CopyWorker.progressOf(info!!.progress) else null
    LaunchedEffect(running, runningKind) { if (running && runningKind == "rewrite") snackBusy("正在改写…可以先做别的，改好会提醒你") }
    LaunchedEffect(Unit) { GenCard.load(ctx) }
    val startGen: (GenJob) -> Unit = { j -> genErr = null; if (running) snack("正在写一篇，写完再开始下一篇", false) else CopyWorker.start(ctx, j) }
    LaunchedEffect(info?.id, info?.state) {
        val i = info ?: return@LaunchedEffect
        if (!i.state.isFinished) return@LaunchedEffect
        val wid = i.id.toString()
        if (genPrefs.getString("seen", null) == wid) return@LaunchedEffect
        genPrefs.edit().putString("seen", wid).apply()
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
            // 1.10：统一动效——底栏切换淡入淡出，推入/返回水平滑动
            when { targetState is Route.Tab && initialState is Route.Tab -> Motion.tab(); navDir < 0 -> Motion.pop(); else -> Motion.push() }
        }, label = "nav") { r ->
            when (r) {
                is Route.Tab -> { val bd = rememberBackdrop(); CompositionLocalProvider(LocalBackdrop provides bd) { Scaffold(containerColor = P3.bg, bottomBar = { if (!(r.i == 1 && libManaging)) FloatingNav(r.i) { libManaging = false; tab(it) } }) { pad ->
                    Box(Modifier.fillMaxSize().backdropSource(bd).padding(top = pad.calculateTopPadding())) {
                        val bottom = pad.calculateBottomPadding()
                        when (r.i) {
                            0 -> WriteScreen(db, ws, bottom, genErr, { genErr = null }, { go(Route.Sub("models")) }, genProg, { CopyWorker.cancel(ctx) }) { go(Route.Read(it)) }
                            1 -> LibraryScreen(db, bottom, { tab(0) }, libManaging, { libManaging = it }) { go(Route.Read(it)) }
                            else -> SettingsHome(db, bottom) { go(Route.Sub(it)) }
                        }
                    }
                } } }
                is Route.Read -> ReaderScreen(db, r.id, running, ::back, { route = Route.Read(it) }, { go(Route.Sub(it)) }, startGen)
                is Route.Sub -> SubPage(db, r, ::back) { go(it) }
            }
        }
        // 1.9：意图卡底部弹层画在最外层（不在 Scaffold 里），键盘留白只算一次
        if (route == Route.Tab(0)) IntentSheet(db, ws, { go(Route.Sub("models")) }, { go(Route.Sub("length")) }, { go(Route.Sub("template:$it")) }) { j -> ws.sheet = false; startGen(j) }
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
