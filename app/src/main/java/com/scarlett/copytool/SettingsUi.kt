@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
package com.scarlett.copytool

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
        item { BigTitle("设置") }
        item {
            Card(Modifier.padding(horizontal = 16.dp), pad = 0.dp) {
                SettingRow(Ic.text, "模板管理", "内置 ${templates.count { it.builtIn }} 个 · 我的 ${templates.count { !it.builtIn }} 个") { go("templates") }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = P3.border)
                SettingRow(Ic.wand, "清洗模板", "当前：${active?.name ?: "未选择"} · 第 ① 步的提示词") { go("cleans") }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = P3.border)
                SettingRow(Ic.key, "模型与密钥", "已配置 ${s.configured().size} 个 · 清洗固定用 DeepSeek，需要 DeepSeek Key") { go("models") }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = P3.border)
                SettingRow(Ic.send, "发布渠道", (listOf("Notion", "语雀", "Telegraph") + channels.map { it.name }).joinToString("、")) { go("channels") }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = P3.border)
                SettingRow(Ic.pin, "常用渠道排序", (listOf("小红书") + s.pinned.map { ChannelIds.short(it, channels) }).joinToString(" · ")) { go("pinned") }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = P3.border)
                SettingRow(Ic.image, "出图与默认", "卡片比例、配色、长图配色、默认模型和篇幅") { go("style") }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = P3.border)
                SettingRow(Ic.info, "关于", "文案台 v${BuildConfig.VERSION_NAME} · 名字暂定") { go("about") }
            }
        }
        item {
            Text("所有 Key 只加密存在这台手机上，不经过任何服务器。", Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P3.surface2).padding(14.dp), fontSize = 13.sp, color = P3.text2)
        }
    }
}

/** 设置子页面路由：page 形如 "templates" / "template:12" / "clean:3" / "channel:0" */
@Composable
fun SubPage(db: AppDb, r: Route.Sub, back: () -> Unit, go: (Route) -> Unit) {
    val name = r.page.substringBefore(":"); val arg = r.page.substringAfter(":", "0").toLongOrNull() ?: 0L
    val sub: (String) -> Unit = { go(Route.Sub(it)) }
    Scaffold(containerColor = P3.bg, bottomBar = { BottomNav(2) { back() } }) { pad ->
        Box(Modifier.padding(bottom = pad.calculateBottomPadding())) {
            when (name) {
                "templates" -> TemplatesPage(db, back, sub)
                "template" -> TemplateEdit(db, arg, back)
                "cleans" -> CleansPage(db, back)
                "models" -> ModelsPage(back)
                "channels" -> ChannelsPage(db, back, sub)
                "channel" -> ChannelEdit(db, arg, back)
                "pinned" -> PinnedPage(db, back)
                "style" -> StylePage(back)
                else -> AboutPage(back)
            }
        }
    }
}

@Composable
private fun Page(title: String, back: () -> Unit, action: (@Composable RowScope.() -> Unit)? = null, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxSize().imePadding()) {
        SubBar(title, back, action)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), content = content)
    }
}

@Composable
private fun NewButton(onClick: () -> Unit) {
    Row(Modifier.padding(end = 12.dp).clip(RoundedCornerShape(50)).background(P3.accentSoft).border(1.dp, P3.accent, RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        SvgIcon(Ic.plus, P3.onAccentSoft, 16.dp); Text(" 新建", color = P3.onAccentSoft, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------- 模板管理 ----------------
@Composable
fun TemplatesPage(db: AppDb, back: () -> Unit, go: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val list by db.templates().all().collectAsState(initial = emptyList())
    val active by db.cleans().activeFlow().collectAsState(initial = null)
    val fields = active?.fieldList() ?: Vars.DEFAULT_FIELDS
    var del by remember { mutableStateOf<Template?>(null) }
    Page("模板管理", back, { NewButton { scope.launch { val id = db.templates().insert(Template(name = "新模板", desc = "", builtIn = false, prompt = "你是一名作者。\n我的观点：{观点}\n读者的疑问：{疑问}\n要写成：{想写什么}\n写作要求：\n参考原文：{原文}", sort = 100)); go("template:$id") } } }) {
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
                            if (t.builtIn) Text("内置", Modifier.clip(RoundedCornerShape(50)).background(P3.surface2).padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 11.sp, color = P3.text2)
                        }
                        Text(t.desc.ifBlank { "（无说明）" }, Modifier.padding(top = 2.dp), fontSize = 13.sp, color = P3.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Vars.referenced(t.prompt).forEach { v -> Text("{$v}", Modifier.clip(RoundedCornerShape(6.dp)).background(if (v in miss) P3.warnBg else P3.accentSoft).padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 12.sp, color = if (v in miss) P3.warnText else P3.onAccentSoft) }
                        }
                        Row(Modifier.padding(top = 6.dp)) {
                            Spacer(Modifier.weight(1f))
                            TextAction("复制") { scope.launch { db.templates().insert(t.copy(id = 0, name = t.name + "（副本）", builtIn = false, sort = 100)); snack("已复制为「${t.name}（副本）」") } }
                            if (t.builtIn && Defaults.builtinPrompt(t.name) != null && Defaults.builtinPrompt(t.name) != t.prompt) TextAction("恢复默认", P3.text2) { scope.launch { db.templates().update(t.copy(prompt = Defaults.builtinPrompt(t.name)!!)); snack("已恢复默认") } }
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
    val fields = active?.fieldList() ?: Vars.DEFAULT_FIELDS
    val known = (fields + Vars.RAW).toSet()
    val miss = Vars.missing(prompt.text, fields)
    fun save() { scope.launch { db.templates().update(tpl.copy(name = name.trim().ifBlank { tpl.name }, desc = desc.trim(), prompt = prompt.text, defaultModel = dm)); snack("已保存"); back() } }
    Page("编辑模板", back, { TextAction("保存") { save() } }) {
        if (tpl.builtIn) item { Text("内置模板可以直接修改，也可以在模板管理里「恢复默认」；不能删除。", Modifier.padding(bottom = 8.dp), fontSize = 12.sp, color = P3.text3) }
        item {
            Card(pad = 0.dp) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("名称", Modifier.width(56.dp), fontSize = 14.sp, color = P3.text2)
                    BasicTextField(name, { name = it }, Modifier.weight(1f), singleLine = true, textStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = P3.text), cursorBrush = SolidColor(P3.accent))
                }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = P3.border)
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
            WarnBox("${miss.joinToString("") { "{$it}" }} 不是当前清洗模板「${active?.name ?: ""}」的输出字段，生成时会是空的，这一行会被去掉。", Modifier.padding(top = 10.dp))
        }
        item { GroupLabel("可插入变量 · 来自清洗模板「${active?.name ?: ""}」") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (fields + Vars.RAW).forEach { v ->
                    Row(Modifier.clip(RoundedCornerShape(50)).background(P3.surface).border(1.dp, P3.borderStrong, RoundedCornerShape(50)).clickable {
                        val ins = "{$v}"; val sel = prompt.selection
                        val txt = prompt.text.replaceRange(sel.min, sel.max, ins)
                        prompt = TextFieldValue(txt, TextRange(sel.min + ins.length))
                    }.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { SvgIcon(Ic.plus, P3.text2, 14.dp); Text(" {$v}", fontSize = 14.sp, color = P3.text) }
                }
            }
        }
        item { GroupLabel("默认模型（可选）") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("不指定", dm.isBlank()) { dm = "" }
                s.configured().forEach { m -> Pill("${m.label} · ${m.sub}", dm == m.ref) { dm = m.ref } }
            }
        }
    }
}

// ---------------- 清洗模板 ----------------
@Composable
fun CleansPage(db: AppDb, back: () -> Unit) {
    val scope = rememberCoroutineScope()
    val list by db.cleans().all().collectAsState(initial = emptyList())
    val templates by db.templates().all().collectAsState(initial = emptyList())
    var editId by remember { mutableLongStateOf(0L) }
    val cur = list.firstOrNull { it.id == editId } ?: list.firstOrNull { it.active } ?: list.firstOrNull()
    var prompt by remember(cur?.id) { mutableStateOf(TextFieldValue(cur?.prompt ?: "")) }
    var name by remember(cur?.id) { mutableStateOf(cur?.name ?: "") }
    var newField by remember { mutableStateOf<String?>(null) }
    var del by remember { mutableStateOf<CleanTpl?>(null) }
    var affected by remember { mutableStateOf<String?>(null) }
    fun impact(fields: List<String>): String? {
        val bad = templates.mapNotNull { t -> Vars.missing(t.prompt, fields).takeIf { it.isNotEmpty() }?.let { "「${t.name}」${it.joinToString("") { v -> "{$v}" }}" } }
        return if (bad.isEmpty()) null else "这些写作模板引用了当前清洗模板不输出的变量，生成时那一行会被去掉：\n" + bad.joinToString("\n")
    }
    Page("清洗模板", back, { NewButton { scope.launch { val id = db.cleans().insert(CleanTpl(name = "新清洗模板", prompt = Defaults.CLEAN, fields = Vars.DEFAULT_FIELDS.joinToString("\n"), builtIn = false, active = false)); editId = id } } }) {
        item {
            Card(pad = 0.dp) {
                list.forEachIndexed { i, c ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = P3.border)
                    Row(Modifier.fillMaxWidth().clickable { editId = c.id }.background(if (c.id == cur?.id) P3.surface2.copy(alpha = .5f) else Color.Transparent).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(c.active, { scope.launch { db.cleans().activate(c.id); affected = impact(c.fieldList()) } }, colors = RadioButtonDefaults.colors(selectedColor = P3.accent))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(c.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = P3.text)
                                if (c.builtIn) Text("内置", Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background(P3.surface2).padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 11.sp, color = P3.text2)
                            }
                            Text("字段：" + c.fieldList().joinToString(" / "), fontSize = 12.sp, color = P3.text2)
                        }
                        if (c.active) Text("当前使用", fontSize = 13.sp, color = P3.accent) else TextAction("设为当前", P3.text3) { scope.launch { db.cleans().activate(c.id); affected = impact(c.fieldList()) } }
                    }
                }
            }
        }
        val c = cur ?: return@Page
        item { GroupLabel("输出字段 · 即写作模板变量") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                c.fieldList().forEach { f ->
                    Row(Modifier.clip(RoundedCornerShape(50)).background(P3.surface).border(1.dp, P3.accent, RoundedCornerShape(50)).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(f, fontSize = 14.sp, color = P3.onAccentSoft)
                        Box(Modifier.size(28.dp).clip(CircleShape).clickable {
                            val nf = c.fieldList() - f
                            scope.launch { db.cleans().update(c.copy(fields = nf.joinToString("\n"))); if (c.active) affected = impact(nf) }
                        }, contentAlignment = Alignment.Center) { SvgIcon(Ic.close, P3.accent, 14.dp, desc = "删除字段") }
                    }
                }
                Row(Modifier.clip(RoundedCornerShape(50)).border(1.dp, P3.borderStrong, RoundedCornerShape(50)).clickable { newField = "" }.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SvgIcon(Ic.plus, P3.text2, 14.dp); Text(" 字段", fontSize = 14.sp, color = P3.text)
                }
            }
            Text("JSON 键名 = 字段名，写作模板用 ${c.fieldList().joinToString(" ") { "{$it}" }} 引用。增删字段后记得同步修改下方提示词里的 JSON 示例。", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = P3.text2, lineHeight = 18.sp)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GroupLabel("编辑 · ${c.name}", Modifier.weight(1f))
                if (c.builtIn) TextAction("恢复默认") { scope.launch { db.cleans().update(c.copy(prompt = Defaults.CLEAN, fields = Vars.DEFAULT_FIELDS.joinToString("\n"), name = Defaults.CLEAN_NAME)); prompt = TextFieldValue(Defaults.CLEAN); snack("已恢复默认") } }
            }
        }
        if (!c.builtIn) item { InputBox(name, { name = it }, "名称", singleLine = true); Spacer(Modifier.height(8.dp)) }
        item {
            Card {
                BasicTextField(prompt, { prompt = it }, Modifier.fillMaxWidth().heightIn(min = 240.dp), textStyle = TextStyle(fontSize = 14.sp, lineHeight = 23.sp, color = P3.text),
                    cursorBrush = SolidColor(P3.accent), visualTransformation = VarHighlight(setOf("模板列表", "原始文本")))
            }
            Text("变量：{原始文本} 是粘贴的原文，{模板列表} 由程序自动填入全部写作模板的名称和说明。输出须是一个 JSON 对象：cleaned / 各字段 / recommend。", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = P3.text2, lineHeight = 18.sp)
            if (!prompt.text.contains("{原始文本}")) WarnBox("提示词里没有 {原始文本}，DeepSeek 将看不到你粘贴的原文。", Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 10.dp)) {
                if (!c.builtIn) TextAction("删除", P3.danger) { del = c }
                Spacer(Modifier.weight(1f))
                PrimaryButton("保存", Modifier.width(140.dp), enabled = prompt.text != c.prompt || (!c.builtIn && name != c.name)) {
                    scope.launch { db.cleans().update(c.copy(prompt = prompt.text, name = if (c.builtIn) c.name else name.trim().ifBlank { c.name })); snack("已保存清洗模板") }
                }
            }
        }
    }
    if (newField != null) AlertDialog(onDismissRequest = { newField = null }, containerColor = P3.surface, title = { Text("添加输出字段") },
        text = { Column { InputBox(newField!!, { newField = it.replace(Regex("[^\\p{IsHan}\\w]"), "") }, "比如：读者", singleLine = true); Text("只能用汉字、字母、数字，写作模板里用 {字段名} 引用。", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = P3.text3) } },
        confirmButton = { TextButton(onClick = {
            val f = newField!!.trim(); newField = null
            val c = cur ?: return@TextButton
            if (f.isNotBlank() && f !in c.fieldList() && f != Vars.RAW) scope.launch { db.cleans().update(c.copy(fields = (c.fieldList() + f).joinToString("\n"))) }
        }) { Text("添加", color = P3.accent) } }, dismissButton = { TextButton(onClick = { newField = null }) { Text("取消", color = P3.text2) } })
    affected?.let { a -> AlertDialog(onDismissRequest = { affected = null }, containerColor = P3.surface, title = { Text("有写作模板会受影响") }, text = { Text(a, color = P3.warnText) },
        confirmButton = { TextButton(onClick = { affected = null }) { Text("知道了", color = P3.accent) } }) }
    del?.let { c -> ConfirmDialog("删除「${c.name}」？", if (c.active) "它是当前使用的清洗模板，删除后会改用「${Defaults.CLEAN_NAME}」。" else "删除后无法恢复。", "删除", true, onOk = {
        scope.launch { db.cleans().delete(c); if (c.active) list.firstOrNull { it.builtIn }?.let { db.cleans().activate(it.id) }; editId = 0 }
    }) { del = null } }
}

// ---------------- 模型与密钥 ----------------
@Composable
fun ModelsPage(back: () -> Unit) {
    val ctx = LocalContext.current; val s = remember { Settings(ctx) }; val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<String?>(null) }
    var ver by remember { mutableIntStateOf(0) }
    var customs by remember { mutableStateOf(s.customs) }
    Page("模型与密钥", back) {
        item { Text("清洗固定用 DeepSeek，必须先配置 DeepSeek Key。Key 只加密存在这台手机上。", Modifier.padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P3.surface2).padding(14.dp), fontSize = 13.sp, color = P3.text2, lineHeight = 19.sp) }
        item {
            Card(pad = 0.dp) {
                Providers.text.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = P3.border)
                    val has = remember(ver) { s.key(p.id).isNotBlank() }
                    Column {
                        Row(Modifier.fillMaxWidth().clickable { open = if (open == p.id) null else p.id }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Dot(if (has) P3.accent else P3.borderStrong)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = P3.text)
                                    if (p.overseas) Text("需海外网络", Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background(P3.surface2).padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 11.sp, color = P3.text2)
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
        item { GroupLabel("自定义模型（OpenAI 兼容）") }
        customs.forEach { cm -> item(key = cm.id) { CustomModelCard(cm, s, { customs = s.customs; ver++ }) } }
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, P3.accent.copy(alpha = .6f), RoundedCornerShape(16.dp)).clickable {
                s.customs = s.customs + CustomModel(System.currentTimeMillis().toString(36), "自定义模型", "", ""); customs = s.customs
            }.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { SvgIcon(Ic.plus, P3.accent, 18.dp); Text(" 自定义模型（OpenAI 兼容）", fontSize = 16.sp, color = P3.accent, fontWeight = FontWeight.SemiBold) }
                Text("填 Base URL、Key、模型 ID，可以加多个", Modifier.padding(top = 4.dp), fontSize = 13.sp, color = P3.text2)
            }
        }
        item { GroupLabel("配图模型（可选，用于 AI 生成封面背景）") }
        item { ImageProviderCard(s) }
    }
}

@Composable
private fun TestRow(test: suspend () -> String) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var res by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            busy = true; res = null
            scope.launch { res = try { true to test() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: ConfigError) { false to (e.message ?: "") } catch (e: PublishError) { false to (e.message ?: "") } catch (e: Throwable) { false to Errors.explain(e).text }; busy = false }
        }, enabled = !busy, shape = RoundedCornerShape(50), border = BorderStroke(1.dp, P3.borderStrong)) {
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
        FieldLabel("名称"); InputBox(name, { name = it; save(); changed() }, "比如：我的中转", singleLine = true)
        FieldLabel("Base URL"); InputBox(url, { url = it.trim(); save() }, "https://example.com/v1（会请求 /chat/completions）", singleLine = true, mono = true, fontSize = 13)
        FieldLabel("Key"); InputBox(key, { key = it.trim(); s.setCustomKey(cm.id, key); changed() }, "API Key", singleLine = true, secret = true, mono = true)
        FieldLabel("模型 ID"); InputBox(model, { model = it.trim(); save(); changed() }, "比如：gpt-4o-mini", singleLine = true, mono = true)
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
                FieldLabel("作者名"); InputBox(name, { name = it; s.telegraphName = it }, "文案台", singleLine = true)
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
        scope.launch { db.channels().delete(c); Secure.set(ctx, "ch_${c.id}", ""); val s = Settings(ctx); s.pinned = s.pinned - "c${c.id}"; back() }
    }) { del = false }
}

// ---------------- 常用渠道 ----------------
@Composable
fun PinnedPage(db: AppDb, back: () -> Unit) {
    val ctx = LocalContext.current; val s = remember { Settings(ctx) }
    val channels by db.channels().all().collectAsState(initial = emptyList())
    var pinned by remember { mutableStateOf(s.pinned) }
    fun set(l: List<String>) { pinned = l.take(4); s.pinned = pinned }
    Page("常用渠道排序", back) {
        item { Text("结果页底栏最多放 4 个常用渠道（「分享到小红书」主按钮始终在右侧）。点「上移」调整顺序。", Modifier.padding(bottom = 10.dp), fontSize = 13.sp, color = P3.text2, lineHeight = 19.sp) }
        item { GroupLabel("已置顶 ${pinned.size} / 4") }
        pinned.forEachIndexed { i, id ->
            val d = ChannelIds.all(channels).firstOrNull { it.id == id } ?: return@forEachIndexed
            item(key = "p$id") {
                Row(Modifier.padding(bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P3.surface).border(1.dp, P3.accent, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
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
                Row(Modifier.padding(bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P3.surface).border(1.dp, P3.border, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    SvgIcon(d.icon, P3.text2, 20.dp); Spacer(Modifier.width(10.dp))
                    Text(d.name, Modifier.weight(1f), fontSize = 15.sp)
                    TextAction(if (pinned.size >= 4) "已满" else "置顶", enabled = pinned.size < 4) { set(pinned + d.id) }
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
        item { GroupLabel("默认篇幅") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("短", "中", "长").forEachIndexed { i, l -> Pill(l, len == i) { len = i; s.length = i } } } }
    }
}

@Composable
fun AboutPage(back: () -> Unit) {
    Page("关于", back) {
        item {
            Card {
                Text("文案台", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("v${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}） · 名字暂定", Modifier.padding(top = 2.dp), fontSize = 13.sp, color = P3.text2, fontFamily = Mono)
                Text("把一段话（口播、想表达的意思、疑问）变成一篇完整文章，再一键变成小红书图、长图、HTML，或者同步到 Notion、语雀、Telegraph、你的博客。", Modifier.padding(top = 12.dp), fontSize = 14.sp, lineHeight = 22.sp)
                Text("· 所有 Key / Token 用 EncryptedSharedPreferences 加密存在本机，不经过任何服务器。\n· 小红书、知乎、抖音、微博、公众号都不自动发布，最后一步由你自己点，避免账号被限流。\n· 文章和模板只存在本机数据库里。", Modifier.padding(top = 12.dp), fontSize = 13.sp, lineHeight = 21.sp, color = P3.text2)
            }
        }
    }
}
