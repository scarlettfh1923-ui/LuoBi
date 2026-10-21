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
                            0 -> WriteScreen(db, ws, bottom, genErr, { genErr = null }, { go(Route.Sub("models")) })
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
        if (route == Route.Tab(0) && ws.sheet && !running) IntentSheet(db, ws, { go(Route.Sub("models")) }, { go(Route.Sub("length")) }, { go(Route.Sub("template:$it")) }) { j -> ws.sheet = false; startGen(j) }
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

fun fieldHint(k: String) = when (k) { "疑问" -> "我的疑问是什么"; "观点" -> "我想表达的观点"; "想写什么" -> "我想写一篇什么样的文章"; else -> "自定义字段" }

@Composable
fun LazyColumnFill(bottom: androidx.compose.ui.unit.Dp, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    // 1.8 修复：键盘弹出时不再预留底栏高度（之前 imePadding + 底栏留白叠加，键盘上方空出一大块）；只留主按钮的位置
    val imeOn = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(bottom = if (imeOn) 76.dp else bottom + 20.dp), content = content)
}
