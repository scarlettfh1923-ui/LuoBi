@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
package com.scarlett.luobi

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// ---------------- 设置首页 ----------------
@Composable
fun SettingsHome(db: AppDb, bottom: Dp, go: (String) -> Unit) {
    val ctx = LocalContext.current; val s = remember { Settings(ctx) }
    val templates by db.templates().all().collectAsState(initial = emptyList())
    val active by db.cleans().activeFlow().collectAsState(initial = null)
    val channels by db.channels().all().collectAsState(initial = emptyList())
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 20.dp)) {
        item { BigTitle("设定") }
        item { RowGroup("创作", listOf(
            { SettingRow(Ic.key, "模型与密钥", "${s.configured().size} 个") { go("models") } },
            { SettingRow(Ic.tpl, "模板管理", "${templates.size} 个") { go("templates") } },
            { SettingRow(Ic.search, "事实校对", run {
                val ss = Settings(LocalContext.current)
                val search = FactCheck.SearchProvider.many(ss.factSearch).joinToString("+") { it.label.replace("（推荐）", "") }
                val check = ss.factModel.takeIf { it.isNotBlank() }?.let { ref -> ss.configured().firstOrNull { it.ref == ref } }?.let { "${it.label}校对" } ?: "校对与写作相同"
                "${search}检索 · $check"
            }) { go("factcheck") } },
            { SettingRow(Ic.wand, "每日选题提示词", if (Settings(LocalContext.current).inspirePrompt.trim() == Inspire.DEFAULT_PROMPT.trim()) "默认" else "已修改") { go("inspire") } },
            { SettingRow(Ic.wand, "意图提取提示词", if (active?.prompt?.trim() == Defaults.INTENT.trim()) "默认" else "已修改") { go("cleans") } },
            { SettingRow(Ic.ruler, "篇幅", Lengths.NAMES[s.length.coerceIn(0, 2)] + " · " + s.lengthRange().let { "${it.first}–${it.second}" }) { go("length") } })) }
        item { RowGroup("分享与图片", listOf(
            { SettingRow(Ic.share, "分享栏", "${s.shareBar.size} 项") { go("sharebar") } },
            { SettingRow(Ic.send, "发布渠道", "${3 + channels.size} 个") { go("channels") } },
            { SettingRow(Ic.image, "图片来源", "${ImgSources.list(ctx).count { it.on }} 个来源") { go("imgsrc") } },
            { SettingRow(Ic.palette, "出图与默认", "卡片 · 长图") { go("style") } })) }
        item { RowGroup("其他", listOf({ SettingRow(Ic.info, "关于落笔", "v${BuildConfig.VERSION_NAME}") { go("about") } })) }
        item {
            Text("所有 Key 只加密存在这台手机上，不经过任何服务器。", Modifier.padding(horizontal = 28.dp, vertical = 14.dp).fillMaxWidth(), fontSize = 12.sp, color = P3.text3)
        }
    }
}

/** 设置子页面路由：page 形如 "templates" / "template:12" / "clean:3" / "channel:0" */
@Composable
fun SubPage(db: AppDb, r: Route.Sub, back: () -> Unit, go: (Route) -> Unit) {
    val name = r.page.substringBefore(":"); val arg = r.page.substringAfter(":", "0").toLongOrNull() ?: 0L
    val sub: (String) -> Unit = { go(Route.Sub(it)) }
    Scaffold(containerColor = P3.bg, bottomBar = { FloatingNav(2) { back() } }) { pad ->
        // 1.8 修复：底栏留白已经垫过了，告诉子页面这段已被消耗，imePadding 只补超出的部分（之前叠加 = 键盘上方一块空白）
        Box(Modifier.padding(bottom = pad.calculateBottomPadding()).consumeWindowInsets(PaddingValues(bottom = pad.calculateBottomPadding()))) {
            when (name) {
                "templates" -> TemplatesPage(db, back, sub)
                "template" -> TemplateEdit(db, arg, back)
                "cleans" -> CleansPage(db, back)
                "inspire" -> InspirePage(back)
                "factcheck" -> FactCheckPage(back)
                "models" -> ModelsPage(back)
                "channels" -> ChannelsPage(db, back, sub)
                "channel" -> ChannelEdit(db, arg, back)
                "pinned", "sharebar" -> ShareBarPage(db, back)
                "style" -> StylePage(back)
                "length" -> LengthPage(back)
                "imgsrc" -> if (r.page.contains(":")) ImgSrcEdit(r.page.substringAfter(":"), back) else ImgSrcPage(back, sub)
                else -> AboutPage(back)
            }
        }
    }
}

@Composable
fun Page(title: String, back: () -> Unit, action: (@Composable RowScope.() -> Unit)? = null, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxSize().imePadding()) {
        SubBar(title, back, action)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), content = content)
    }
}

@Composable
private fun NewButton(onClick: () -> Unit) {
    Row(Modifier.padding(end = 12.dp).clip(Sq.full).background(P3.accentSoft).hairline(Sq.full, P3.accent).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        SvgIcon(Ic.plus, P3.onAccentSoft, 16.dp); Text(" 新建", color = P3.onAccentSoft, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------- 模板管理 ----------------
@Composable
fun TemplatesPage(db: AppDb, back: () -> Unit, go: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val list by db.templates().all().collectAsState(initial = emptyList())
    val active by db.cleans().activeFlow().collectAsState(initial = null)
    val fields = Vars.DEFAULT_FIELDS // 1.9：意图卡固定三项
    var del by remember { mutableStateOf<Template?>(null) }
    Page("模板管理", back, { NewButton { scope.launch { val id = db.templates().insert(Template(name = Names.unique("新模板", list.map { it.name }), updatedAt = System.currentTimeMillis(), desc = "", builtIn = false, prompt = TplHeader.header("新模板") + "写作要求：\n", sort = 100)); go("template:$id") } } }) {
        listOf(true to "内置模板", false to "我的模板").forEach { (b, label) ->
            item { GroupLabel(label) }
            val l = list.filter { it.builtIn == b }
            if (l.isEmpty()) item { Text("还没有。可以「新建」，或复制一个内置模板再改。", Modifier.padding(4.dp), fontSize = 13.sp, color = P3.text3) }
            l.forEach { t ->
                item(key = t.id) {
                    val miss = Vars.missing(t.prompt, fields)
                    Card(Modifier.padding(bottom = 8.dp), onClick = { go("template:${t.id}") }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t.name, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = P3.text)
                            if (t.builtIn) Text("内置", Modifier.clip(Sq.full).background(P3.surface2).padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 11.sp, color = P3.text2)
                            else Text("我的", Modifier.clip(Sq.full).background(P3.accentSoft).padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 11.sp, color = P3.onAccentSoft)
                        }
                        Text(t.desc.ifBlank { "（无说明）" }, Modifier.padding(top = 2.dp), fontSize = 13.sp, color = P3.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Vars.referenced(t.prompt).forEach { v -> Text("{$v}", Modifier.clip(Sq.s).background(if (v in miss) P3.warnBg else P3.accentSoft).padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 12.sp, color = if (v in miss) P3.warnText else P3.onAccentSoft) }
                        }
                        Row(Modifier.padding(top = 6.dp)) {
                            Spacer(Modifier.weight(1f))
                            TextAction("复制") { scope.launch { val nm = Names.unique(t.name + "（副本）", list.map { it.name }); db.templates().insert(t.copy(id = 0, name = nm, builtIn = false, sort = 100, updatedAt = System.currentTimeMillis())); snack("已复制为「$nm」") } }
                            if (t.builtIn && Defaults.builtinPrompt(t.name) != null && Defaults.builtinPrompt(t.name) != t.prompt) TextAction("恢复默认", P3.text2) { scope.launch { val nn = Defaults.builtinName(t.name).takeIf { n -> n == t.name || list.none { it.name == n } } ?: t.name; db.templates().update(t.copy(name = nn, desc = Defaults.builtinDesc(t.name) ?: t.desc, prompt = Defaults.builtinPrompt(t.name)!!, updatedAt = System.currentTimeMillis())); snack("已恢复默认") } }
                            if (!t.builtIn) TextAction("删除", P3.danger) { del = t }
                        }
                    }
                }
            }
        }
    }
    del?.let { t -> ConfirmDialog("删除「${t.name}」？", "删除后无法恢复，已生成的文章不受影响。", "删除", true, onOk = { scope.launch { db.templates().delete(t) } }) { del = null } }
}

/** 变量高亮：已知变量蓝底，未知变量黄底 */
private class VarHighlight(val known: Set<String>) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val b = AnnotatedString.Builder(text)
        Regex("\\{([\\p{IsHan}\\w]{1,20})\\}").findAll(text.text).forEach { m ->
            val ok = m.groupValues[1] in known
            b.addStyle(SpanStyle(background = if (ok) P3.accentSoft else P3.warnBg, color = if (ok) P3.onAccentSoft else P3.warnText), m.range.first, m.range.last + 1)
        }
        return TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
    }
}

@Composable
fun TemplateEdit(db: AppDb, id: Long, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    var t by remember { mutableStateOf<Template?>(null) }
    val active by db.cleans().activeFlow().collectAsState(initial = null)
    val ctx = LocalContext.current; val s = remember { Settings(ctx) }
    LaunchedEffect(id) { t = db.templates().get(id) }
    val tpl = t ?: return
    var name by remember(tpl.id) { mutableStateOf(tpl.name) }
    var desc by remember(tpl.id) { mutableStateOf(tpl.desc) }
    var prompt by remember(tpl.id) { mutableStateOf(TextFieldValue(tpl.prompt)) }
    var dm by remember(tpl.id) { mutableStateOf(tpl.defaultModel) }
    val fields = Vars.DEFAULT_FIELDS // 1.9：意图卡固定三项
    val known = (fields + Vars.RAW).toSet()
    val miss = Vars.missing(prompt.text, fields)
    // 1.4：自动保存。改动 600ms 后写库；离开页面（返回键 / 手势 / 左上角）时再兜底写一次并提示「已保存」
    var saved by remember(tpl.id) { mutableStateOf(tpl) }
    fun draft(): Template = tpl.copy(name = name.trim().ifBlank { tpl.name }, desc = desc.trim(), prompt = prompt.text, defaultModel = dm)
    suspend fun persist(): Boolean {
        val d0 = draft(); if (d0.name == saved.name && d0.desc == saved.desc && d0.prompt == saved.prompt && d0.defaultModel == saved.defaultModel) return false
        val others = db.templates().list().filter { it.id != tpl.id }.map { it.name }
        val d = d0.copy(name = Names.unique(d0.name, others), updatedAt = System.currentTimeMillis())
        db.templates().update(d); saved = d; return true
    }
    LaunchedEffect(name, desc, prompt.text, dm) { kotlinx.coroutines.delay(600); persist() }
    val latest by rememberUpdatedState(::persist)
    DisposableEffect(tpl.id) { onDispose { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { latest(); } } }
    fun save() { scope.launch { val renamed = draft().name; persist(); snack(if (saved.name != renamed) "已保存（重名，已改为「${saved.name}」）" else "已保存"); back() } }
    fun leave() { scope.launch { persist(); snack(if (saved.name != draft().name.trim()) "已保存（重名，已改为「${saved.name}」）" else "已保存"); back() } }
    androidx.activity.compose.BackHandler { leave() }
    Page("编辑模板", { leave() }, { TextAction("完成") { save() } }) {
        item { Text("改动会自动保存" + if (saved.updatedAt > 0) "，最后修改 ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(saved.updatedAt))}" else "", Modifier.padding(bottom = 4.dp), fontSize = 12.sp, color = P3.text3) }
        if (tpl.builtIn) item { Text("内置模板可以直接修改，也可以在模板管理里「恢复默认」；不能删除。", Modifier.padding(bottom = 8.dp), fontSize = 12.sp, color = P3.text3) }
        item {
            Card(pad = 0.dp) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("名称", Modifier.width(56.dp), fontSize = 14.sp, color = P3.text2)
                    BasicTextField(name, { name = it }, Modifier.weight(1f), singleLine = true, textStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = P3.text), cursorBrush = SolidColor(P3.accent))
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = Dp.Hairline, color = P3.border)
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("说明", Modifier.width(56.dp), fontSize = 14.sp, color = P3.text2)
                    Box(Modifier.weight(1f)) {
                        BasicTextField(desc, { desc = it }, Modifier.fillMaxWidth(), textStyle = TextStyle(fontSize = 15.sp, color = P3.text), cursorBrush = SolidColor(P3.accent))
                        if (desc.isEmpty()) Text("一句话说明，AI 推荐模板时按它匹配", fontSize = 15.sp, color = P3.text3)
                    }
                }
            }
        }
        item { GroupLabel("提示词") }
        item {
            Card {
                BasicTextField(prompt, { prompt = it }, Modifier.fillMaxWidth().heightIn(min = 200.dp), textStyle = TextStyle(fontSize = 15.sp, lineHeight = 26.sp, color = P3.text),
                    cursorBrush = SolidColor(P3.accent), visualTransformation = VarHighlight(known))
            }
        }
        if (miss.isNotEmpty()) item {
            WarnBox("${miss.joinToString("") { "{$it}" }} 不是意图卡的字段（{想写什么}{观点}{疑问}）也不是 {原文}，会原样发送。", Modifier.padding(top = 10.dp))
        }
        item { GroupLabel("可插入变量 · 来自意图卡") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (fields + Vars.RAW).forEach { v ->
                    Row(Modifier.clip(Sq.full).background(P3.surface).hairline(Sq.full, P3.borderStrong).clickable {
                        val ins = "{$v}"; val sel = prompt.selection
                        val txt = prompt.text.replaceRange(sel.min, sel.max, ins)
                        prompt = TextFieldValue(txt, TextRange(sel.min + ins.length))
                    }.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { SvgIcon(Ic.plus, P3.text2, 14.dp); Text(" {$v}", fontSize = 14.sp, color = P3.text) }
                }
            }
        }
        item { GroupLabel("默认模型（可选）") }
        item {
            val cm = s.configured()
            ChoiceList(listOf(Triple("不指定", "用写作页选的模型", dm.isBlank())) + cm.map { Triple(it.label, it.sub, dm == it.ref) }) { i -> dm = if (i == 0) "" else cm[i - 1].ref }
        }
    }
}

// ---------------- 意图提取提示词（1.9，替代清洗模板） ----------------
@Composable
fun CleansPage(db: AppDb, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val active by remember(db) { db.cleans().activeFlow() }.collectAsState(initial = null)
    val allCleans by remember(db) { db.cleans().all() }.collectAsState(initial = emptyList())
    val c = active
    var prompt by remember(c?.id) { mutableStateOf(TextFieldValue(c?.prompt ?: "")) }
    Page("意图提取提示词", back) {
        item { Text("粘贴口播后点「提取意图」时发给模型的提示词。输出须是一个 JSON 对象：想写什么 / 观点 / 疑问 / recommend。{原始文本} 是你粘贴的口播，{模板列表} 由程序自动填入全部写作模板的名称和说明。", Modifier.padding(bottom = 12.dp), fontSize = 13.sp, color = P3.text2, lineHeight = 19.sp) }
        if (c == null) return@Page
        val backup = allCleans.firstOrNull { !it.builtIn && it.name.startsWith("旧清洗提示词") }
        if (backup != null) item {
            var show by remember { mutableStateOf(false) }
            Card(Modifier.padding(bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("你之前改过的清洗提示词已备份（1.10 起提取意图改用上面的提示词）", Modifier.weight(1f), fontSize = 13.sp, color = P3.text2, lineHeight = 19.sp)
                    TextAction(if (show) "收起" else "查看") { show = !show }
                }
                if (show) Text(backup.prompt, Modifier.padding(top = 8.dp), fontSize = 13.sp, lineHeight = 20.sp, color = P3.text2)
            }
        }
        item {
            Card {
                BasicTextField(prompt, { prompt = it }, Modifier.fillMaxWidth().heightIn(min = 240.dp), textStyle = TextStyle(fontSize = 14.sp, lineHeight = 23.sp, color = P3.text),
                    cursorBrush = SolidColor(P3.accent), visualTransformation = VarHighlight(setOf("模板列表", "原始文本")))
            }
            if (!prompt.text.contains("{原始文本}")) WarnBox("提示词里没有 {原始文本}，模型将看不到你粘贴的口播。", Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                TextAction("恢复默认", P3.text2) { scope.launch { db.cleans().update(c.copy(name = Defaults.INTENT_NAME, prompt = Defaults.INTENT, fields = Vars.DEFAULT_FIELDS.joinToString("\n"))); prompt = TextFieldValue(Defaults.INTENT); snack("已恢复默认") } }
                Spacer(Modifier.weight(1f))
                PrimaryButton("保存", Modifier.width(140.dp), enabled = prompt.text != c.prompt) {
                    scope.launch { db.cleans().update(c.copy(prompt = prompt.text)); snack("已保存意图提取提示词") }
                }
            }
        }
    }
}

// ---------------- 事实校对（1.11） ----------------
@Composable
fun FactCheckPage(back: () -> Unit) {
    val ctx = LocalContext.current; val s = remember { Settings(ctx) }
    var prompt by remember { mutableStateOf(TextFieldValue(s.factPrompt)) }
    var saved by remember { mutableStateOf(s.factPrompt) }
    var provider by remember { mutableStateOf(FactCheck.SearchProvider.many(s.factSearch)) }
    var kimi by remember { mutableStateOf(s.key("kimi")) }
    var checkModel by remember { mutableStateOf(s.factModel) }
    var bocha by remember { mutableStateOf(s.bochaKey) }
    var wsa by remember { mutableStateOf(s.wsaKey) }
    var wsaMsg by remember { mutableStateOf("") }; var wsaBusy by remember { mutableStateOf(false) }
    val wsaScope = rememberCoroutineScope()
    val hasDs = remember { s.key("deepseek").isNotBlank() }
    val configured = remember { s.configured() }
    Page("事实校对", back) {
        item { Text("核对文章里的硬事实：片名、情节、作者名、引用来源等。不做语法或文笔润色。\n「事实检索」决定去哪搜资料；「校对模型」决定用哪个 AI 判断对错——这两项是分开的。", Modifier.padding(bottom = 10.dp), fontSize = 13.sp, color = P3.text2, lineHeight = 19.sp) }
        item { GroupLabel("事实检索 · 去哪搜资料") }
        item {
            Card(pad = 0.dp) {
                FactCheck.SearchProvider.entries.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = Dp.Hairline, color = P3.border)
                    // 1.15.1：多选，交叉印证；至少保留一个
                    val toggle = { val next = if (p in provider) provider - p else provider + p
                        if (next.isNotEmpty()) { provider = next; s.factSearch = FactCheck.SearchProvider.csv(next) } }
                    Row(Modifier.fillMaxWidth().clickable { toggle() }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(p in provider, { toggle() }, colors = CheckboxDefaults.colors(checkedColor = P3.accent))
                        Column(Modifier.weight(1f).padding(start = 4.dp)) {
                            Text(p.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = P3.text)
                            Text(when (p) {
                                FactCheck.SearchProvider.DEEPSEEK -> "用 DeepSeek Key 调官方 /v1/tools/search" + if (hasDs) " · Key 已配置" else " · 还没填 Key"
                                FactCheck.SearchProvider.KIMI -> "Kimi 联网搜索 + 网页抓取（情节核对会读作品页全文，约 ¥0.01/次）" + if (kimi.isNotBlank()) " · Key 已配置" else " · 还没填 Key"
                                FactCheck.SearchProvider.WIKI -> "免 Key，适合片名、作者、作品基本信息"
                                FactCheck.SearchProvider.BOCHA -> "第三方网页搜索，需单独填写博查 Key"
                                FactCheck.SearchProvider.YUANBAO -> "腾讯元宝搜索，中文资料优先走它；每月 2000 次免费" + if (wsa.isNotBlank()) " · Key 已配置" else " · 还没填 Key"
                            }, fontSize = 12.sp, color = P3.text2, lineHeight = 17.sp)
                        }
                    }
                }
            }
            Text("可以多选：每个选中的源都会去搜，结果里标出「几个来源印证」；某个源失败不影响其它源。这只影响「搜资料」，不会改下面的校对模型。", Modifier.padding(top = 8.dp, start = 4.dp), fontSize = 12.sp, color = P3.text3, lineHeight = 18.sp)
        }
        if (FactCheck.SearchProvider.KIMI in provider || kimi.isNotBlank()) item {
            GroupLabel("Kimi API Key")
            InputBox(kimi, { kimi = it.trim(); s.setKey("kimi", kimi) }, "粘贴 Moonshot Key，加密存在本机（与 Kimi 写作模型共用）", singleLine = true, secret = true, mono = true)
        }
        if (FactCheck.SearchProvider.YUANBAO in provider || wsa.isNotBlank()) item {
            GroupLabel("元宝搜索 API Key")
            InputBox(wsa, { wsa = it.trim(); s.wsaKey = wsa }, "粘贴元宝搜索（WSA）API Key，加密存在本机", singleLine = true, secret = true, mono = true)
            Row(Modifier.padding(top = 6.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val used = Yuanbao.Usage.label(ctx); val near = Yuanbao.Usage.nearLimit(ctx)
                Text(used + if (near) " · 快到每月免费额度了" else " · 每月 2000 次免费", Modifier.weight(1f), fontSize = 12.sp, color = if (near) Color(0xFFC0392B) else P3.text3)
                TextAction(if (wsaBusy) "测试中…" else "测试连接", enabled = wsa.isNotBlank() && !wsaBusy) {
                    wsaBusy = true; wsaScope.launch { wsaMsg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Yuanbao.resetSession(); Yuanbao.testReal(ctx, wsa) }; wsaBusy = false }
                }
            }
            if (wsaMsg.isNotBlank()) Text(wsaMsg, Modifier.padding(start = 4.dp, top = 2.dp), fontSize = 12.sp, color = P3.text2)
        }
        if (FactCheck.SearchProvider.BOCHA in provider || bocha.isNotBlank()) item {
            GroupLabel("博查 API Key")
            InputBox(bocha, { bocha = it.trim(); s.bochaKey = bocha }, "粘贴博查 Key，加密存在本机", singleLine = true, secret = true, mono = true)
        }
        item { GroupLabel("校对模型 · 用哪个 AI 判断对错") }
        item {
            Card(pad = 0.dp) {
                Row(Modifier.fillMaxWidth().clickable { checkModel = ""; s.factModel = "" }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(checkModel.isBlank(), { checkModel = ""; s.factModel = "" }, colors = RadioButtonDefaults.colors(selectedColor = P3.accent))
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text("与写作相同（默认）", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = P3.text)
                        Text("用这篇文章生成时的模型；没有记录时用默认写作模型", fontSize = 12.sp, color = P3.text2, lineHeight = 17.sp)
                    }
                }
                configured.forEach { m ->
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = Dp.Hairline, color = P3.border)
                    Row(Modifier.fillMaxWidth().clickable { checkModel = m.ref; s.factModel = m.ref }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(checkModel == m.ref, { checkModel = m.ref; s.factModel = m.ref }, colors = RadioButtonDefaults.colors(selectedColor = P3.accent))
                        Column(Modifier.weight(1f).padding(start = 4.dp)) {
                            Text("${m.label} · ${m.sub}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = P3.text)
                            if (m.ref == "p:deepseek") Text("与事实检索的 DeepSeek Key 是同一把", fontSize = 12.sp, color = P3.text2)
                        }
                    }
                }
            }
            Text("请只选你 Key 有权限的模型。选了 OpenAI 却只有 DeepSeek Key，会报 403。", Modifier.padding(top = 8.dp, start = 4.dp), fontSize = 12.sp, color = P3.text3, lineHeight = 18.sp)
        }
        item { GroupLabel("校对提示词") }
        item {
            Card {
                BasicTextField(prompt, { prompt = it }, Modifier.fillMaxWidth().heightIn(min = 200.dp), textStyle = TextStyle(fontSize = 14.sp, lineHeight = 23.sp, color = P3.text),
                    cursorBrush = SolidColor(P3.accent), visualTransformation = VarHighlight(setOf("搜索结果", "标题", "正文")))
            }
            if (!prompt.text.contains("{搜索结果}")) WarnBox("提示词里没有 {搜索结果}，模型看不到参考资料。", Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                TextAction("恢复默认", P3.text2) { s.factPrompt = FactCheck.DEFAULT_PROMPT; prompt = TextFieldValue(FactCheck.DEFAULT_PROMPT); saved = FactCheck.DEFAULT_PROMPT; snack("已恢复默认") }
                Spacer(Modifier.weight(1f))
                PrimaryButton("保存", Modifier.width(140.dp), enabled = prompt.text != saved) { s.factPrompt = prompt.text; saved = prompt.text; snack("已保存") }
            }
        }
    }
}

// ---------------- 每日选题提示词（1.10 灵感） ----------------
@Composable
fun InspirePage(back: () -> Unit) {
    val ctx = LocalContext.current; val s = remember { Settings(ctx) }
    var prompt by remember { mutableStateOf(TextFieldValue(s.inspirePrompt)) }
    var saved by remember { mutableStateOf(s.inspirePrompt) }
    var srcs by remember { mutableStateOf(s.inspireSources) }
    Page("每日选题提示词", back) {
        item { Text("每天第一次打开「创作」时，抓取下面这些热榜，用 DeepSeek 按你的文章和模板挑出 10 个选题。{热榜}{我的文章}{我的模板} 由程序自动填入。", Modifier.padding(bottom = 12.dp), fontSize = 13.sp, color = P3.text2, lineHeight = 19.sp) }
        item {
            Card {
                BasicTextField(prompt, { prompt = it }, Modifier.fillMaxWidth().heightIn(min = 240.dp), textStyle = TextStyle(fontSize = 14.sp, lineHeight = 23.sp, color = P3.text),
                    cursorBrush = SolidColor(P3.accent), visualTransformation = VarHighlight(setOf("热榜", "我的文章", "我的模板")))
            }
            if (!prompt.text.contains("{热榜}")) WarnBox("提示词里没有 {热榜}，模型将看不到今天的热榜。", Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                TextAction("恢复默认", P3.text2) { s.inspirePrompt = Inspire.DEFAULT_PROMPT; prompt = TextFieldValue(Inspire.DEFAULT_PROMPT); saved = Inspire.DEFAULT_PROMPT; snack("已恢复默认") }
                Spacer(Modifier.weight(1f))
                PrimaryButton("保存", Modifier.width(140.dp), enabled = prompt.text != saved) { s.inspirePrompt = prompt.text; saved = prompt.text; snack("已保存，下拉「灵感」即可按新提示词刷新") }
            }
        }
        item { GroupLabel("热榜来源") }
        item {
            Card(pad = 0.dp) {
                HotSources.all.forEachIndexed { i, src ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = Dp.Hairline, color = P3.border)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(src.name, Modifier.weight(1f), fontSize = 15.sp, color = P3.text)
                        Switch(src.id in srcs, { on -> srcs = if (on) srcs + src.id else srcs - src.id; s.inspireSources = srcs }, colors = SwitchDefaults.colors(checkedTrackColor = P3.accent))
                    }
                }
            }
            Text("小红书没有可以免登录访问的公开热榜，暂不支持。某个来源抓取失败时会自动跳过。", Modifier.padding(top = 8.dp, start = 4.dp), fontSize = 12.sp, color = P3.text3, lineHeight = 18.sp)
        }
    }
}

// ---------------- 模型与密钥 ----------------
@Composable
fun ModelsPage(back: () -> Unit) {
    val ctx = LocalContext.current; val s = remember { Settings(ctx) }; val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<String?>(null) }
    var ver by remember { mutableIntStateOf(0) }
    var customs by remember { mutableStateOf(s.customs) }
    Page("模型与密钥", back) {
        item { Text("意图提取优先用 DeepSeek；没填 DeepSeek Key 时用你在意图卡里选的模型。Key 只加密存在这台手机上。", Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(Sq.l).background(P3.surface2).padding(14.dp), fontSize = 13.sp, color = P3.text2, lineHeight = 19.sp) }
        item {
            Card(pad = 0.dp) {
                Providers.text.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = Dp.Hairline, color = P3.border)
                    val has = remember(ver) { s.key(p.id).isNotBlank() }
                    Column {
                        Row(Modifier.fillMaxWidth().clickable { open = if (open == p.id) null else p.id }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Dot(if (has) P3.accent else P3.borderStrong)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = P3.text)
                                    if (p.overseas) Text("需海外网络", Modifier.padding(start = 6.dp).clip(Sq.full).background(P3.surface2).padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 11.sp, color = P3.text2)
                                }
                                Text(if (has) s.model(p.id) + " · " + p.note else p.note, fontSize = 13.sp, color = P3.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(if (has) "已配置" else "填 Key", fontSize = 13.sp, color = if (has) P3.text2 else P3.accent)
                            SvgIcon(Ic.chevron, P3.text3, 16.dp)
                        }
                        if (open == p.id) ProviderForm(p, s) { ver++ }
                    }
                }
            }
        }
        item { GroupLabel("自定义中转站（OpenAI 兼容）") }
        item { Text(Relay.HINT, Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp), fontSize = 12.sp, color = P3.text3, lineHeight = 18.sp) }
        customs.forEach { cm -> item(key = cm.id) { CustomModelCard(cm, s, { customs = s.customs; ver++ }) } }
        item {
            Column(Modifier.fillMaxWidth().clip(Sq.l).hairline(Sq.l, P3.accent.copy(alpha = .6f)).clickable {
                s.customs = s.customs + CustomModel(System.currentTimeMillis().toString(36), "中转站", "", ""); customs = s.customs
            }.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { SvgIcon(Ic.plus, P3.accent, 18.dp); Text(" 自定义中转站（OpenAI 兼容）", fontSize = 16.sp, color = P3.accent, fontWeight = FontWeight.SemiBold) }
                Text("填接口地址、API Key、模型名；同一个中转站的多个模型可以各加一个", Modifier.padding(top = 4.dp), fontSize = 13.sp, color = P3.text2)
            }
        }
        item { GroupLabel("配图模型（可选，用于 AI 生成封面背景）") }
        item { ImageProviderCard(s) }
    }
}

@Composable
fun TestRow(test: suspend () -> String) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var res by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            busy = true; res = null
            scope.launch { res = try { true to test() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: ConfigError) { false to (e.message ?: "") } catch (e: PublishError) { false to (e.message ?: "") } catch (e: Throwable) { false to Errors.explain(e).text }; busy = false }
        }, enabled = !busy, shape = Sq.full, border = null, modifier = Modifier.hairline(Sq.full, P3.borderStrong)) {
            if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = P3.accent) else SvgIcon(Ic.refresh, P3.accent, 16.dp)
            Text(if (busy) "  测试中…" else "  测试连接", color = P3.text, fontSize = 14.sp)
        }
    }
    res?.let { (ok, m) -> Text(m, Modifier.padding(top = 6.dp), fontSize = 13.sp, color = if (ok) P3.accent else P3.danger, lineHeight = 18.sp) }
}

@Composable
private fun ProviderForm(p: Provider, s: Settings, changed: () -> Unit) {
    var key by remember { mutableStateOf(s.key(p.id)) }
    var model by remember { mutableStateOf(s.model(p.id)) }
    var url by remember { mutableStateOf(s.url(p.id)) }
    var adv by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
        if (p.hint.isNotBlank()) Text(p.hint, Modifier.padding(bottom = 6.dp), fontSize = 12.sp, color = P3.text3, lineHeight = 17.sp)
        FieldLabel("API Key")
        InputBox(key, { key = it.trim(); s.setKey(p.id, key); changed() }, "粘贴 ${p.name} 的 Key", singleLine = true, secret = true, mono = true)
        FieldLabel("模型")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            p.models.forEach { (id, label) -> Pill(label.substringBefore(" · ").ifBlank { id }, model == id) { model = id; s.setModel(p.id, id); changed() } }
        }
        Spacer(Modifier.height(8.dp))
        InputBox(model, { model = it.trim(); s.setModel(p.id, model); changed() }, "或手动填模型 ID", singleLine = true, mono = true, fontSize = 14)
        Text(if (adv) "收起高级" else "高级：接口地址", Modifier.padding(top = 8.dp).clickable { adv = !adv }, fontSize = 13.sp, color = P3.accent)
        if (adv) {
            Spacer(Modifier.height(6.dp))
            InputBox(url, { url = it.trim(); s.setUrl(p.id, url) }, p.url, singleLine = true, mono = true, fontSize = 13)
            TextAction("恢复默认地址", P3.text2) { url = p.url; s.setUrl(p.id, p.url) }
        }
        TestRow { Ai.test(Ai.target(ctx, "p:${p.id}")).let { (ms, m) -> "$m · ${ms}ms" } }
    }
}

@Composable
private fun CustomModelCard(cm: CustomModel, s: Settings, changed: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(cm.name) }; var url by remember { mutableStateOf(cm.url) }; var model by remember { mutableStateOf(cm.model) }
    var key by remember { mutableStateOf(s.customKey(cm.id)) }
    var del by remember { mutableStateOf(false) }
    fun save() { s.customs = s.customs.map { if (it.id == cm.id) CustomModel(cm.id, name.trim(), url.trim(), model.trim()) else it } }
    Card(Modifier.padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name.ifBlank { "自定义模型" }, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            TextAction("删除", P3.danger) { del = true }
        }
        val scope = rememberCoroutineScope()
        var models by remember { mutableStateOf<List<String>>(emptyList()) }; var listMsg by remember { mutableStateOf("") }; var listing by remember { mutableStateOf(false) }
        FieldLabel("名称"); InputBox(name, { name = it; save(); changed() }, "比如：我的中转站", singleLine = true)
        FieldLabel("接口地址"); InputBox(url, { url = it.trim(); save() }, "https://你的中转站.com（带不带 /v1 都可以）", singleLine = true, mono = true, fontSize = 13)
        if (url.isNotBlank()) Text("实际请求：" + Relay.base(url) + "/chat/completions", Modifier.padding(top = 4.dp, start = 2.dp), fontSize = 11.sp, color = P3.text3)
        FieldLabel("API Key"); InputBox(key, { key = it.trim(); s.setCustomKey(cm.id, key); changed() }, "粘贴中转站的 Key，加密存在本机", singleLine = true, secret = true, mono = true)
        FieldLabel("模型名"); InputBox(model, { model = it.trim(); save(); changed() }, "比如：gpt-5.5、claude-sonnet-4.5、deepseek-chat", singleLine = true, mono = true)
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextAction(if (listing) "获取中…" else "获取模型列表", P3.accent) {
                if (!listing && url.isNotBlank() && key.isNotBlank()) { listing = true; listMsg = ""
                    scope.launch { val r = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Ai.listModels(url, key) } }
                        listing = false; r.onSuccess { models = it; listMsg = if (it.isEmpty()) "中转站没有返回模型列表，请手动填写模型名" else "共 ${it.size} 个，点一下填入" }
                            .onFailure { listMsg = "获取失败：" + (it.message ?: "未知错误").take(60) } } }
                else if (url.isBlank() || key.isBlank()) listMsg = "先填接口地址和 API Key"
            }
            if (listMsg.isNotBlank()) Text(listMsg, Modifier.padding(start = 8.dp), fontSize = 12.sp, color = P3.text3)
        }
        if (models.isNotEmpty()) Column(Modifier.padding(top = 4.dp).heightIn(max = 220.dp).clip(Sq.m).background(P3.surface2).verticalScroll(rememberScrollState())) {
            models.forEach { m -> Text(m, Modifier.fillMaxWidth().clickable { model = m; save(); changed(); models = emptyList() }.padding(horizontal = 12.dp, vertical = 9.dp),
                fontSize = 13.sp, color = if (m == model) P3.accent else P3.text, fontFamily = Mono) }
        }
        TestRow { save(); Ai.test(Ai.target(ctx, "c:${cm.id}")).let { (ms, m) -> "$m · ${ms}ms" } }
    }
    if (del) ConfirmDialog("删除「${name}」？", "Key 也会一起删除。", "删除", true, onOk = { s.customs = s.customs.filter { it.id != cm.id }; s.setCustomKey(cm.id, ""); changed() }) { del = false }
}

@Composable
private fun ImageProviderCard(s: Settings) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(s.imgOn) }
    var pid by remember { mutableStateOf(s.imgProv) }
    var key by remember(pid) { mutableStateOf(s.imgKey(pid)) }
    var model by remember(pid) { mutableStateOf(s.imgModel(pid)) }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("启用 AI 封面背景", fontSize = 15.sp, fontWeight = FontWeight.SemiBold); Text("配图页会出现「AI 生成封面背景」", fontSize = 12.sp, color = P3.text3) }
            Switch(on, { on = it; s.imgOn = it }, colors = SwitchDefaults.colors(checkedTrackColor = P3.accent))
        }
        if (on) {
            FieldLabel("服务商")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Providers.image.forEach { p -> Pill(p.name, pid == p.id) { pid = p.id; s.imgProv = p.id } } }
            val p = Providers.image(pid)
            if (p.hint.isNotBlank()) Text(p.hint, Modifier.padding(top = 6.dp), fontSize = 12.sp, color = P3.text3)
            FieldLabel("Key"); InputBox(key, { key = it.trim(); s.setImgKey(pid, key) }, "粘贴 ${p.name} 的 Key", singleLine = true, secret = true, mono = true)
            FieldLabel("模型")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { p.models.forEach { (id, l) -> Pill(l.substringBefore(" · "), model == id) { model = id; s.setImgModel(pid, id) } } }
            TestRow { val t0 = System.currentTimeMillis(); val b = Ai.image(ctx, "冷色调简约渐变背景"); "已生成 ${b.width}×${b.height} 测试图（计 1 次出图费用）· ${System.currentTimeMillis() - t0}ms" }
        }
    }
}

// ---------------- 发布渠道 ----------------
@Composable
fun ChannelsPage(db: AppDb, back: () -> Unit, go: (String) -> Unit) {
    val ctx = LocalContext.current; val s = remember { Settings(ctx) }; val scope = rememberCoroutineScope()
    val channels by db.channels().all().collectAsState(initial = emptyList())
    Page("发布渠道", back) {
        item { GroupLabel("Notion · 官方 API") }
        item {
            Card {
                var token by remember { mutableStateOf(Secure.get(ctx, "notion_token")) }
                var target by remember { mutableStateOf(s.notionTarget) }
                var type by remember { mutableStateOf(s.notionType) }
                var label by remember { mutableStateOf(s.notionLabel) }
                var tp by remember { mutableStateOf(s.notionTitleProp) }; var tg by remember { mutableStateOf(s.notionTagsProp) }
                Text("在 notion.so/my-integrations 创建集成拿到 Token，再在目标页面 / 数据库右上角「连接」里添加这个集成。", fontSize = 12.sp, color = P3.text3, lineHeight = 17.sp)
                FieldLabel("集成 Token"); InputBox(token, { token = it.trim(); Secure.set(ctx, "notion_token", token) }, "ntn_… 或 secret_…", singleLine = true, secret = true, mono = true)
                FieldLabel("目标类型")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Pill("页面（建子页面）", type == "page") { type = "page"; s.notionType = "page" }; Pill("数据库（新增一行）", type == "database") { type = "database"; s.notionType = "database" } }
                FieldLabel("目标链接或 ID"); InputBox(target, { target = it.trim(); s.notionTarget = target }, "粘贴页面 / 数据库链接", singleLine = true, mono = true, fontSize = 13)
                FieldLabel("显示名称", "可选"); InputBox(label, { label = it; s.notionLabel = it }, "比如：文案归档", singleLine = true)
                if (type == "database") {
                    FieldLabel("属性映射", "可选")
                    InputBox(tp, { tp = it.trim(); s.notionTitleProp = tp }, "标题属性名（留空自动找 title 类型）", singleLine = true)
                    Spacer(Modifier.height(6.dp))
                    InputBox(tg, { tg = it.trim(); s.notionTagsProp = tg }, "标签属性名（Multi-select，留空不写）", singleLine = true)
                }
                TestRow { Notion.test(ctx) }
            }
        }
        item { GroupLabel("语雀 · 开放 API") }
        item {
            Card {
                var token by remember { mutableStateOf(Secure.get(ctx, "yuque_token")) }
                var ns by remember { mutableStateOf(s.yuqueNs) }
                var repos by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
                var pub by remember { mutableStateOf(s.yuquePublic) }
                WarnBox("个人调用语雀 API 可能需要语雀会员，请先在语雀「账户设置 → Token」确认能创建 Token。")
                FieldLabel("个人 Token"); InputBox(token, { token = it.trim(); Secure.set(ctx, "yuque_token", token) }, "语雀 Token（需勾选知识库读写权限）", singleLine = true, secret = true, mono = true)
                FieldLabel("知识库 namespace"); InputBox(ns, { ns = it.trim(); s.yuqueNs = ns }, "比如 scarlett/wenan", singleLine = true, mono = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextAction("从账号读取知识库") { scope.launch { try { repos = Yuque.repos(ctx); if (repos.isEmpty()) snack("没有找到知识库", false) } catch (e: Exception) { snack("读取失败：${e.message ?: Errors.explain(e).text}", false) } } }
                    Spacer(Modifier.weight(1f))
                    Text("公开", fontSize = 13.sp, color = P3.text2); Switch(pub, { pub = it; s.yuquePublic = it }, colors = SwitchDefaults.colors(checkedTrackColor = P3.accent))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { repos.forEach { (n, label) -> Pill(label, ns == n) { ns = n; s.yuqueNs = n } } }
                TestRow { val l = Yuque.repos(ctx); "Token 有效，共 ${l.size} 个知识库" + if (ns.isNotBlank() && l.none { it.first == ns }) "（注意：没找到 $ns）" else "" }
            }
        }
        item { GroupLabel("Telegraph") }
        item {
            Card {
                var name by remember { mutableStateOf(s.telegraphName) }
                Text("不用注册：首次发布时自动创建匿名账号，令牌加密存在本机。发布后返回链接并自动复制。", fontSize = 12.sp, color = P3.text3, lineHeight = 17.sp)
                FieldLabel("作者名"); InputBox(name, { name = it; s.telegraphName = it }, "落笔", singleLine = true)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GroupLabel("自定义渠道", Modifier.weight(1f))
                TextAction("+ 添加") { go("channel:0") }
            }
        }
        if (channels.isEmpty()) item { Text("可以接 WordPress、Halo、Ghost 等有公开发布 API 的平台。", Modifier.padding(4.dp), fontSize = 13.sp, color = P3.text3) }
        channels.forEach { c -> item(key = c.id) { Card(Modifier.padding(bottom = 8.dp), onClick = { go("channel:${c.id}") }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SvgIcon(Ic.globe, P3.text2, 20.dp); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) { Text(c.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold); Text("${c.method} · ${c.format.uppercase()} · ${c.url}", fontSize = 12.sp, color = P3.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = Mono) }
                SvgIcon(Ic.chevron, P3.text3, 16.dp)
            }
        } } }
    }
}

@Composable
fun ChannelEdit(db: AppDb, id: Long, back: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var ch by remember { mutableStateOf<Channel?>(if (id == 0L) Channel(name = "我的博客", url = "") else null) }
    LaunchedEffect(id) { if (id != 0L) ch = db.channels().get(id) }
    val c = ch ?: return
    var secret by remember(c.id) { mutableStateOf(if (c.id != 0L) Secure.get(ctx, "ch_${c.id}") else "") }
    var del by remember { mutableStateOf(false) }
    fun persist(after: (Channel) -> Unit = {}) {
        scope.launch {
            val saved = if (c.id == 0L) c.copy(id = db.channels().insert(c)) else c.also { db.channels().update(it) }
            Secure.set(ctx, "ch_${saved.id}", secret); ch = saved; after(saved)
        }
    }
    Page(if (id == 0L) "添加自定义渠道" else "编辑自定义渠道", back, { TextAction("保存") { persist { snack("已保存，会出现在分享面板「一键上传」里"); back() } } }) {
        item {
            Card {
                FieldLabel("名称"); InputBox(c.name, { ch = c.copy(name = it) }, "比如：我的博客", singleLine = true)
                FieldLabel("说明", "可选"); InputBox(c.note, { ch = c.copy(note = it) }, "比如：Halo 草稿", singleLine = true)
                FieldLabel("接口地址"); InputBox(c.url, { ch = c.copy(url = it.trim()) }, "https://example.com/wp-json/wp/v2/posts", singleLine = true, mono = true, fontSize = 13)
                FieldLabel("请求方式")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("POST", "PUT").forEach { m -> Pill(m, c.method == m) { ch = c.copy(method = m) } } }
                FieldLabel("鉴权")
                InputBox(c.authHeader, { ch = c.copy(authHeader = it.trim()) }, "请求头名，如 Authorization", singleLine = true, mono = true, fontSize = 13)
                Spacer(Modifier.height(6.dp))
                InputBox(c.authPrefix, { ch = c.copy(authPrefix = it) }, "前缀，如 Bearer 或 Basic ", singleLine = true, mono = true, fontSize = 13)
                Spacer(Modifier.height(6.dp))
                InputBox(secret, { secret = it.trim() }, "密钥 / Token（加密存本机）", singleLine = true, secret = true, mono = true, fontSize = 13)
                FieldLabel("内容格式", "{body} 的格式")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("markdown" to "Markdown", "html" to "HTML", "json" to "纯文本").forEach { (k, l) -> Pill(l, c.format == k) { ch = c.copy(format = k) } } }
                FieldLabel("请求体 JSON 模板", "变量 {title}{body}{tags}")
                InputBox(c.bodyTpl, { ch = c.copy(bodyTpl = it) }, "{\"title\":\"{title}\",\"content\":\"{body}\"}", minHeight = 90.dp, mono = true, fontSize = 13)
                Text("变量会按 JSON 字符串转义后替换。WordPress 示例：{\"title\":\"{title}\",\"content\":\"{body}\",\"status\":\"draft\"}，鉴权用 Basic + base64(用户名:应用密码)。", Modifier.padding(top = 6.dp), fontSize = 12.sp, color = P3.text3, lineHeight = 17.sp)
                TestRow { Custom.test(ctx, c.copy()).also { Secure.set(ctx, "ch_${c.id}", secret) } }
                Text("测试连接只发 GET 请求检查地址和鉴权，不会真的发布。", Modifier.padding(top = 4.dp), fontSize = 12.sp, color = P3.text3)
            }
        }
        if (c.id != 0L) item { TextAction("删除这个渠道", P3.danger) { del = true } }
    }
    if (del) ConfirmDialog("删除「${c.name}」？", "密钥一起删除；常用渠道里的它也会移除。", "删除", true, onOk = {
        scope.launch { db.channels().delete(c); Secure.set(ctx, "ch_${c.id}", ""); val s = Settings(ctx); s.shareBar = s.shareBar - "c${c.id}"; back() }
    }) { del = false }
}

// ---------------- 常用渠道 ----------------
@Composable
fun PinnedPage(db: AppDb, back: () -> Unit) {
    val ctx = LocalContext.current; val s = remember { Settings(ctx) }
    val channels by db.channels().all().collectAsState(initial = emptyList())
    var pinned by remember { mutableStateOf(s.pinned) }
    fun set(l: List<String>) { pinned = l.take(8); s.pinned = pinned }
    Page("常用渠道排序", back) {
        item { Text("结果页底栏最多放 8 个常用渠道，「小红书」固定在最左，放不下时可左右滑动。点「上移」调整顺序。", Modifier.padding(bottom = 10.dp), fontSize = 13.sp, color = P3.text2, lineHeight = 19.sp) }
        item { GroupLabel("已置顶 ${pinned.size} / 8") }
        pinned.forEachIndexed { i, id ->
            val d = ChannelIds.all(channels).firstOrNull { it.id == id } ?: return@forEachIndexed
            item(key = "p$id") {
                Row(Modifier.padding(bottom = 8.dp).fillMaxWidth().clip(Sq.l).background(P3.surface).hairline(Sq.l, P3.accent).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", fontFamily = Mono, color = P3.accent, fontSize = 14.sp); Spacer(Modifier.width(10.dp))
                    SvgIcon(d.icon, P3.accent, 20.dp, P3.accent); Spacer(Modifier.width(10.dp))
                    Text(d.name, Modifier.weight(1f), fontSize = 15.sp)
                    if (i > 0) TextAction("上移") { set(pinned.toMutableList().apply { add(i - 1, removeAt(i)) }) }
                    TextAction("移除", P3.text2) { set(pinned - id) }
                }
            }
        }
        item { GroupLabel("全部渠道") }
        ChannelIds.all(channels).filter { it.id != "xhs" && it.id !in pinned }.forEach { d ->
            item(key = "a${d.id}") {
                Row(Modifier.padding(bottom = 8.dp).fillMaxWidth().clip(Sq.l).background(P3.surface).hairline(Sq.l, P3.border).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SvgIcon(d.icon, P3.text2, 20.dp); Spacer(Modifier.width(10.dp))
                    Text(d.name, Modifier.weight(1f), fontSize = 15.sp)
                    TextAction(if (pinned.size >= 8) "已满" else "置顶", enabled = pinned.size < 8) { set(pinned + d.id) }
                }
            }
        }
    }
}

// ---------------- 出图样式与默认 ----------------
@Composable
fun StylePage(back: () -> Unit) {
    val ctx = LocalContext.current; val s = remember { Settings(ctx) }
    var ratio by remember { mutableIntStateOf(s.cardRatio) }; var style by remember { mutableIntStateOf(s.style) }
    var mode by remember { mutableIntStateOf(s.readMode) }; var font by remember { mutableStateOf(s.readFont) }
    var dm by remember { mutableStateOf(s.defaultModel) }; var len by remember { mutableIntStateOf(s.length) }
    Page("出图与默认", back) {
        item { GroupLabel("小红书卡片比例") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { CardRatio.entries.forEachIndexed { i, r -> Pill("${r.label} ${r.sub}", ratio == i) { ratio = i; s.cardRatio = i } } } }
        item { GroupLabel("卡片配色") }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Render.styles.forEachIndexed { i, st -> Pill(st.name, style == i) { style = i; s.style = i } } } }
        item { GroupLabel("字体（阅读、卡片、长图）") }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Fonts.list.forEach { f -> Pill(f.name + if (!Fonts.ready(ctx, f.id)) "（结果页 Aa 里下载）" else "", font == f.id) { if (Fonts.ready(ctx, f.id)) { font = f.id; s.readFont = f.id } else snack("请在结果页右上角 Aa 里下载这个字体") } } } }
        item { GroupLabel("阅读 / 长图配色") }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { readPalettes.forEachIndexed { i, p -> Pill(p.name, mode == i) { mode = i; s.readMode = i } } } }
        item { GroupLabel("默认模型（改写和第 ③ 步默认选中）") }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Pill("自动", dm.isBlank()) { dm = ""; s.defaultModel = "" }; s.configured().forEach { m -> Pill("${m.label} · ${m.sub}", dm == m.ref) { dm = m.ref; s.defaultModel = m.ref } } } }
    }
}

@Composable
fun AboutPage(back: () -> Unit) {
    Page("关于", back) {
        item {
            Card {
                Text("落笔", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}） · 名字暂定", Modifier.padding(top = 2.dp), fontSize = 13.sp, color = P3.text2, fontFamily = Mono)
                Text("把一段话（口播、想表达的意思、疑问）变成一篇完整文章，再一键变成小红书图、长图、HTML，或者同步到 Notion、语雀、Telegraph、你的博客。", Modifier.padding(top = 12.dp), fontSize = 14.sp, lineHeight = 22.sp)
                Text("· 所有 Key / Token 用 EncryptedSharedPreferences 加密存在本机，不经过任何服务器。\n· 小红书、知乎、抖音、微博、公众号都不自动发布，最后一步由你自己点，避免账号被限流。\n· 文章和模板只存在本机数据库里。", Modifier.padding(top = 12.dp), fontSize = 13.sp, lineHeight = 21.sp, color = P3.text2)
            }
        }
    }
}

/** 篇幅设置：短 / 中 / 长 各自最少–最多字数 + 默认篇幅 */
@Composable
fun LengthPage(back: () -> Unit) {
    val ctx = LocalContext.current; val s = remember { Settings(ctx) }
    val mins = remember { mutableStateListOf(*s.lengths.map { it.first.toString() }.toTypedArray()) }
    val maxs = remember { mutableStateListOf(*s.lengths.map { it.second.toString() }.toTypedArray()) }
    var def by remember { mutableIntStateOf(s.length) }
    fun save() { if ((0..2).all { Lengths.validate(mins[it], maxs[it]) == null }) s.lengths = (0..2).map { mins[it].trim().toInt() to maxs[it].trim().toInt() } }
    Page("篇幅设置", back) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.End) {
                TextAction("恢复默认") { Lengths.DEFAULT.forEachIndexed { i, r -> mins[i] = r.first.toString(); maxs[i] = r.second.toString() }; def = 1; s.length = 1; save(); snack("已恢复默认篇幅") }
            }
        }
        (0..2).forEach { i ->
            item {
                val err = Lengths.validate(mins[i], maxs[i])
                Card(Modifier.padding(bottom = 10.dp).then(if (def == i) Modifier.hairline(Sq.xl, P3.accent, 1.5.dp) else Modifier)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Lengths.NAMES[i] + "篇", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = P3.text)
                        if (def == i) Text("默认", Modifier.clip(Sq.full).background(P3.accentSoft).padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 11.sp, color = P3.accent)
                    }
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("最少 ", fontSize = 14.sp, color = P3.text)
                        NumBox(mins[i]) { mins[i] = it.filter(Char::isDigit).take(5); save() }
                        Text(" 字 至 最多 ", fontSize = 14.sp, color = P3.text)
                        NumBox(maxs[i]) { maxs[i] = it.filter(Char::isDigit).take(5); save() }
                        Text(" 字", fontSize = 14.sp, color = P3.text)
                    }
                    if (err != null) Text(err + "，修正前不会保存", Modifier.padding(top = 6.dp), fontSize = 12.sp, color = P3.danger)
                }
            }
        }
        item { GroupLabel("默认篇幅") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Lengths.NAMES.forEachIndexed { i, l -> Pill(l, def == i) { def = i; s.length = i } } } }
        item {
            Text("篇幅如何影响生成：第 ③ 步不再单独选篇幅，生成时一律使用这里的默认篇幅，并在最终提示词末尾追加一行「篇幅：中篇，正文字数控制在 400–800 字之间。」模型据此控制长度；数字只是约束提示，实际字数可能有少量偏差。",
                Modifier.padding(top = 14.dp).fillMaxWidth().clip(Sq.l).background(P3.surface2).padding(14.dp), fontSize = 13.sp, color = P3.text2, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun NumBox(v: String, on: (String) -> Unit) {
    androidx.compose.foundation.text.BasicTextField(v, on, Modifier.width(64.dp).clip(Sq.m).background(P3.surface).hairline(Sq.m, P3.borderStrong).padding(horizontal = 8.dp, vertical = 8.dp),
        singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = P3.text, textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontFamily = Mono),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
}
