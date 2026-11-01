package com.scarlett.luobi

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* ============================ 2.4 技能包：创作页的积木、流程页、管理页 ============================
 * 这里的可组合项都只吃参数（不碰 Room / 网络），截图测试和真机共用同一份。 */

private val WarnBg = Color(0xFFF7F0DF)
private val WarnLine = Color(0xFFE6D9B8)
private val WarnText = Color(0xFF6E5A22)

/** 技能包短名：卡片上的小标签 / 流程页的「题目 · 人物传记」 */
fun skillShort(name: String): String = when {
    name.contains("人物") -> "人物传记"
    name.contains("财经") -> "财经非虚构"
    else -> name.take(8)
}

/** 圆形深黛徽章：人物 / 财经 / 通用文档 */
@Composable
fun SkillBadge(kind: String, size: Dp = 40.dp) {
    val ic = when (kind) { "bio" -> Ic.skillPerson; "fin" -> Ic.skillFinance; else -> Ic.skillDoc }
    Box(Modifier.size(size).clip(Sq.full).background(P3.onAccentSoft), contentAlignment = Alignment.Center) { SvgIcon(ic, Color.White, size * .55f) }
}

@Composable
fun VersionChip(v: String, builtIn: Boolean? = null) {
    if (v.isBlank() && builtIn == null) return
    val t = listOfNotNull(if (builtIn == true) "内置" else if (builtIn == false) "导入" else null, v.ifBlank { null }).joinToString(" · ")
    Text(t, Modifier.clip(Sq.full).background(P3.accentSoft).padding(horizontal = 8.dp, vertical = 2.dp), color = P3.onAccentSoft, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
}

/** 创作页的技能包卡：选中 = 黛青浅底 + 1px 黛青线 + 右上勾 */
@Composable
fun SkillPickCard(p: SkillPack, selected: Boolean, onClick: () -> Unit) {
    val shape = Sq.xl
    Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().softShadow(Sq.xl, 4.dp)
        .surface(shape, if (selected) P3.accentSoft else P3.surface, outline = if (selected) P3.accent else P3.border)
        .clickable(role = Role.RadioButton, onClick = onClick).semantics { contentDescription = "技能包 ${p.displayName}" + if (selected) "，已选" else "" }
        .padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        SkillBadge(p.kind, 42.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.displayName, Modifier.weight(1f, fill = false), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = P3.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp)); VersionChip(p.versionChip)
            }
            Text(p.tagline, Modifier.padding(top = 3.dp), fontSize = 13.sp, color = P3.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(22.dp).clip(Sq.full).background(if (selected) P3.accent else Color.Transparent).let { if (selected) it else it.hairline(Sq.full, P3.borderStrong) }, contentAlignment = Alignment.Center) {
            if (selected) SvgIcon(Ic.check, Color.White, 14.dp)
        }
    }
}

/** 「导入技能包文件」行：细虚线 + 黛青字 */
@Composable
fun ImportRow(busy: Boolean, onClick: () -> Unit, sub: String = "支持 .zip（含 SKILL.md）或 .md 文件") {
    val line = P3.accent.copy(alpha = .5f)
    Row(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().testTag("importRow")
        .drawBehind { drawRoundRect(line, cornerRadius = CornerRadius(DS.R.xl.toPx()), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))) }
        .clip(Sq.xl).clickable(enabled = !busy, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(Sq.full).hairline(Sq.full, P3.accent.copy(alpha = .6f)), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = P3.accent) else SvgIcon(Ic.plus, P3.accent, 18.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(if (busy) "正在导入…" else "导入技能包文件", color = P3.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, Modifier.padding(top = 1.dp), color = P3.text3, fontSize = 12.sp)
        }
    }
}

/** 创作页页眉：大标题 + 右上「管理」+ 一行说明 */
@Composable
fun CreateHeader(onManage: () -> Unit, subtitle: String = "输入想法，再选技能包写成文章") {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("创作", Modifier.weight(1f), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = P3.text)
            Row(Modifier.clip(Sq.full).clickable(role = Role.Button, onClick = onManage).padding(horizontal = 10.dp, vertical = 8.dp).testTag("manageSkills"), verticalAlignment = Alignment.CenterVertically) {
                SvgIcon(Ic.navGear, P3.accent, 18.dp); Spacer(Modifier.width(4.dp))
                Text("管理", color = P3.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
        Text(subtitle, Modifier.padding(top = 2.dp, bottom = 10.dp), fontSize = 14.sp, color = P3.text2)
    }
}

/** 流程页顶栏：不带状态栏内边距（Scaffold 已经让出）；statusPad = 子页用 */
@Composable
fun SkillBar(title: String, onBack: () -> Unit, statusPad: Boolean = false, action: (@Composable RowScope.() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().let { if (statusPad) it.statusBarsPadding() else it }.height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTap(onClick = onBack) { SvgIcon(Ic.back, P3.text, desc = "返回") }
        Text(title, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = P3.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (action != null) Row(Modifier.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically, content = action)
    }
}

/* ------------------------------ 导入（SAF） ------------------------------ */

class SkillImporter(val busy: Boolean, val launch: () -> Unit)

private fun displayNameOf(ctx: Context, uri: Uri): String? = try {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
} catch (_: Exception) { null }

/** 系统文件选择器（OpenDocument，只读、不要任何存储权限）。读取、解压都在 IO 线程，结果走 onImported；失败直接弹条 */
@Composable
fun rememberSkillImporter(onImported: (SkillPack, Boolean) -> Unit): SkillImporter {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) {
            busy = true
            scope.launch {
                try {
                    val r = withContext(Dispatchers.IO) { Skills.importUri(ctx, uri, displayNameOf(ctx, uri)) }
                    onImported(r.pack, r.replaced)
                    snack(if (r.replaced) "已更新技能包「${r.pack.displayName}」" else "已导入技能包「${r.pack.displayName}」")
                } catch (e: SkillImportError) { snack(e.text, ok = false) }
                catch (e: Exception) { snack("导入失败：" + (e.message?.take(60) ?: "未知错误"), ok = false) }
                finally { busy = false }
            }
        }
    }
    return remember(busy) { SkillImporter(busy) { if (!busy) picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "text/markdown", "text/x-markdown", "text/plain", "application/octet-stream")) } }
}

/* ------------------------------ 技能包管理页 ------------------------------ */

@Composable
fun SkillManageCard(p: SkillPack, expanded: Boolean, onToggle: () -> Unit, onDelete: (() -> Unit)?) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().softShadow(Sq.xl, 4.dp).surface(Sq.xl, P3.surface, outline = P3.border).animateContentSize().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SkillBadge(p.kind, 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.displayName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = P3.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) { VersionChip(p.versionChip, p.builtIn) }
            }
            TextAction(if (expanded) "收起" else "详情", onClick = onToggle)
            if (onDelete != null) TextAction("删除", color = P3.danger, onClick = onDelete)
        }
        if (expanded) {
            Text(p.description.ifBlank { p.tagline }, Modifier.padding(top = 10.dp), fontSize = 13.sp, lineHeight = 20.sp, color = P3.text2, maxLines = 8, overflow = TextOverflow.Ellipsis)
            Text(if (p.builtIn) "内置技能包，不能删除。" else "导入的技能包，可以随时删除；同名再次导入会覆盖它。", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = P3.text3)
        }
    }
}

/** 技能包管理（创作页右上「管理」和设定里「技能包」共用）。packs 由外部持有，导入 / 删除后调用 reload */
@Composable
fun SkillManageContent(packs: List<SkillPack>, importer: SkillImporter, onDelete: (SkillPack) -> Unit, bottom: Dp, header: @Composable () -> Unit) {
    var open by remember { mutableStateOf<String?>(null) }
    var del by remember { mutableStateOf<SkillPack?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom + 24.dp)) {
        item { header() }
        item { SectionLabel("已安装 ${packs.size}", Modifier.padding(start = 20.dp)) }
        items(packs, key = { it.id }, contentType = { "skill" }) { p -> SkillManageCard(p, open == p.id, { open = if (open == p.id) null else p.id }, if (p.builtIn) null else ({ del = p })) }
        item { SectionLabel("添加", Modifier.padding(start = 20.dp)) }
        item { ImportRow(importer.busy, importer.launch) }
        item {
            Text("技能包是一份 SKILL.md：写明这类文章怎么检索、怎么立角度、怎么落笔。导入后，回到创作页就能选用；删除只影响技能包本身，不会动已写好的文章。",
                Modifier.padding(horizontal = 24.dp, vertical = 14.dp), fontSize = 12.sp, lineHeight = 18.sp, color = P3.text3)
        }
    }
    del?.let { p -> ConfirmDialog("删除技能包？", "「${p.displayName}」会从这台手机上移除，已写好的文章不受影响。", "删除", danger = true, onOk = { onDelete(p); del = null; if (open == p.id) open = null }, onDismiss = { del = null }) }
}

/** 带数据加载的管理页：tab 内嵌（statusPad=false）或设定子页（true） */
@Composable
fun SkillManagePage(bottom: Dp, onBack: () -> Unit, statusPad: Boolean, onChanged: () -> Unit = {}) {
    val ctx = LocalContext.current.applicationContext
    var packs by remember { mutableStateOf(Skills.builtIns(ctx)) }
    LaunchedEffect(Unit) { packs = withContext(Dispatchers.IO) { Skills.list(ctx) } }
    val scope = rememberCoroutineScope()
    val importer = rememberSkillImporter { _, _ -> scope.launch { packs = withContext(Dispatchers.IO) { Skills.list(ctx) }; onChanged() } }
    SkillManageContent(packs, importer, { p ->
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Skills.delete(ctx, p.id) }
            packs = withContext(Dispatchers.IO) { Skills.list(ctx) }; onChanged()
            snack(if (ok) "已删除「${p.displayName}」" else "没能删除这个技能包", ok)
        }
    }, bottom) { SkillBar("技能包管理", onBack, statusPad) }
}

/* ------------------------------ 流程页 ------------------------------ */

@Composable
fun TopicStrip(skillName: String, topic: String) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().surface(Sq.l, P3.surface, outline = P3.border).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text("题目 · ${skillShort(skillName)}", color = P3.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(topic, Modifier.padding(top = 2.dp), color = P3.text, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StepRow(n: Int, title: String, sub: String, state: Int, last: Boolean) {   // state 0 待办 1 进行中 2 完成
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(26.dp)) {
            Box(Modifier.size(24.dp).clip(Sq.full).background(if (state == 2) P3.accent else if (state == 1) P3.accentSoft else Color.Transparent)
                .let { if (state == 2) it else it.hairline(Sq.full, if (state == 1) P3.accent else P3.borderStrong) }, contentAlignment = Alignment.Center) {
                if (state == 2) SvgIcon(Ic.check, Color.White, 14.dp)
                else if (state == 1) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.8.dp, color = P3.accent)
                else Text("$n", fontSize = 12.sp, color = P3.text3)
            }
            if (!last) Box(Modifier.width(1.dp).weight(1f).background(if (state == 2) P3.accent.copy(alpha = .5f) else P3.border))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.padding(bottom = if (last) 0.dp else 16.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = if (state == 1) FontWeight.SemiBold else FontWeight.Medium, color = if (state == 0) P3.text3 else P3.text)
            if (sub.isNotBlank()) Text(sub, Modifier.padding(top = 2.dp), fontSize = 12.sp, lineHeight = 17.sp, color = if (state == 0) P3.text3 else P3.text2)
        }
    }
}

@Composable
private fun Notice(t: String) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(Sq.l).background(WarnBg).hairline(Sq.l, WarnLine).padding(horizontal = 12.dp, vertical = 9.dp)) {
        SvgIcon(Ic.info, WarnText, 16.dp); Spacer(Modifier.width(8.dp))
        Text(t, color = WarnText, fontSize = 12.5.sp, lineHeight = 18.sp)
    }
}

/** 第 1 步：搜资料。stage 2 元宝 / 3 DeepSeek 补充 / 4 整理角度与提纲 */
@Composable
fun PlanProgressView(s: SkillSession) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        TopicStrip(s.skillName, s.topic)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().softShadow(Sq.xl, 4.dp).surface(Sq.xl, P3.surface, outline = P3.border).padding(horizontal = 16.dp, vertical = 18.dp)) {
            fun st(step: Int) = if (s.stage > step) 2 else if (s.stage == step) 1 else 0
            StepRow(1, "已选技能包", "${s.skillName}${if (s.skillVersion.isNotBlank()) " · v" + s.skillVersion.substringBefore('-') else ""}", 2, false)
            StepRow(2, if (st(2) == 2) "元宝检索完成" else "元宝检索中", if (s.yb > 0) "已找到 ${s.yb} 条" else "公开报道、一手材料与时间线", st(2), false)
            StepRow(3, if (st(3) == 2) "DeepSeek 补充完成" else "DeepSeek 补充搜索", if (s.ds > 0) "已补充 ${s.ds} 条" else "补缺口，去重后合并", st(3), false)
            StepRow(4, "整理角度与提纲", "搜完后停下来，等你确认再写", st(4), true)
        }
        if (s.line.isNotBlank() && s.stage < 5) Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(Sq.l).background(P3.accentSoft).padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.8.dp, color = P3.accent); Spacer(Modifier.width(10.dp))
            Text(s.line, color = P3.onAccentSoft, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        s.notices.forEach { Notice(it) }
        Text("资料一般要 1–2 分钟。可以先离开，回来时会停在这里。", Modifier.padding(horizontal = 24.dp, vertical = 10.dp), fontSize = 12.sp, color = P3.text3)
    }
}

/** 写正文阶段 */
@Composable
fun WritingProgressView(s: SkillSession) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        TopicStrip(s.skillName, s.topic)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().softShadow(Sq.xl, 4.dp).surface(Sq.xl, P3.surface, outline = P3.border).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            GeneratingNib(Modifier.size(56.dp))
            Text("正在写正文", Modifier.padding(top = 12.dp), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = P3.text)
            Text(if (s.words > 0) "已写 ${s.words} 字" else "正在落笔…", Modifier.padding(top = 4.dp), fontSize = 13.sp, color = P3.text2)
            if (s.title.isNotBlank()) Text("《${s.title}》", Modifier.padding(top = 8.dp), fontSize = 14.sp, color = P3.text, fontWeight = FontWeight.Medium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (s.line.isNotBlank()) Text(s.line, Modifier.padding(top = 8.dp), fontSize = 12.sp, lineHeight = 18.sp, color = P3.text3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text("长文大约要 2–4 分钟。可以先离开，写好会提醒你。", Modifier.padding(horizontal = 24.dp, vertical = 10.dp), fontSize = 12.sp, color = P3.text3)
    }
}

private fun parseOutline(t: String): List<String> = t.lines().map { it.trim().replace(Regex("^(\\d+[.、)）]|[-•·])\\s*"), "").trim() }.filter { it.isNotBlank() }.take(24)

@Composable
private fun NumDot(n: Int) = Box(Modifier.size(22.dp).clip(Sq.full).background(P3.accentSoft), contentAlignment = Alignment.Center) { Text("$n", fontSize = 11.sp, color = P3.onAccentSoft, fontWeight = FontWeight.SemiBold) }

/** 确认角度与提纲。editing 由外部持有（返回键需要先退出编辑） */
@Composable
fun ConfirmPlanView(s: SkillSession, plan: SkillPlan, editing: Boolean, onEditing: (Boolean) -> Unit, onConfirm: (SkillPlan) -> Unit, onResearch: () -> Unit,
                    onRetryWrite: () -> Unit, goModels: () -> Unit, bottom: Dp) {
    var angle by remember(plan, editing) { mutableStateOf(plan.angle) }
    var outline by remember(plan, editing) { mutableStateOf(plan.outline.joinToString("\n")) }
    var note by remember(plan, editing) { mutableStateOf(plan.note) }
    var srcOpen by remember { mutableStateOf(false) }
    var err by remember(s.err) { mutableStateOf(s.err) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(s.topic, Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp), fontSize = 13.sp, color = P3.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            err?.let { ErrorCard(it, onRetryWrite, if (it.settings) goModels else null) { err = null } }
            if (!editing) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().softShadow(Sq.xl, 4.dp).surface(Sq.xl, P3.surface, outline = P3.border).padding(16.dp)) {
                    Text("写作角度", Modifier.clip(Sq.full).background(P3.accentSoft).padding(horizontal = 9.dp, vertical = 3.dp), color = P3.onAccentSoft, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Text(plan.angle, Modifier.padding(top = 8.dp), fontSize = 16.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium, color = P3.text)
                    if (plan.alt.isNotBlank()) Text("备选：${plan.alt.trim().removePrefix("备选角度").removePrefix("备选").trimStart('：', ':', ' ')}", Modifier.padding(top = 8.dp), fontSize = 12.5.sp, lineHeight = 19.sp, color = P3.text3)
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().softShadow(Sq.xl, 4.dp).surface(Sq.xl, P3.surface, outline = P3.border).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("写作提纲", Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = P3.text)
                        if (plan.words.isNotBlank()) Text(plan.words, fontSize = 12.sp, color = P3.text3)
                    }
                    plan.outline.forEachIndexed { i, t ->
                        Row(Modifier.padding(top = 10.dp)) { NumDot(i + 1); Spacer(Modifier.width(10.dp)); Text(t, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 21.sp, color = P3.text) }
                    }
                    if (plan.note.isNotBlank()) Text("补充要求：${plan.note}", Modifier.padding(top = 12.dp), fontSize = 12.5.sp, lineHeight = 19.sp, color = P3.accent)
                }
                Column(Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().surface(Sq.l, P3.surface2, outline = P3.border).clickable { srcOpen = !srcOpen }.animateContentSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("已引用 ${s.sources.size} 条资料" + listOfNotNull(s.yb.takeIf { it > 0 }?.let { " · 元宝 $it" }, s.ds.takeIf { it > 0 }?.let { " · DeepSeek $it" }).joinToString(""), Modifier.weight(1f), fontSize = 13.sp, color = P3.text2)
                        SvgIcon(if (srcOpen) Ic.chevronDown else Ic.chevron, P3.text3, 16.dp)
                    }
                    if (srcOpen) s.sources.forEach { r ->
                        Column(Modifier.padding(top = 10.dp)) {
                            Text("[${r.n}] ${r.title}", fontSize = 13.sp, lineHeight = 19.sp, color = P3.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOf(r.site, r.date, r.via).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 11.sp, color = P3.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (plan.gaps.isNotBlank()) Text("资料缺口：${plan.gaps}", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), fontSize = 12.sp, lineHeight = 18.sp, color = P3.text3)
                s.notices.forEach { Notice(it) }
                Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) { TextAction("资料不对？重新搜一遍", color = P3.text2, onClick = onResearch) }
            } else {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    FieldLabel("写作角度")
                    InputBox(angle, { angle = it.take(400) }, "一句话说清这篇文章要讲什么", minHeight = 72.dp)
                    FieldLabel("写作提纲", "一行一项")
                    InputBox(outline, { outline = it.take(2000) }, "每行一个小节", minHeight = 160.dp)
                    FieldLabel("补充要求", "可选")
                    InputBox(note, { note = it.take(300) }, "如：篇幅 3000 字左右；多写某一段；语气克制", minHeight = 60.dp)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottom + 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!editing) {
                PrimaryButton("确认，开始写正文", Modifier.testTag("confirmWrite")) { onConfirm(plan) }
                OutlineButton({ onEditing(true) }, Modifier.fillMaxWidth().height(46.dp), border = androidx.compose.foundation.BorderStroke(1.dp, P3.accent)) { Text("改角度 / 改提纲", color = P3.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
            } else {
                PrimaryButton("保存并开始写正文") {
                    val items = parseOutline(outline)
                    if (angle.isBlank()) snack("角度不能是空的", false)
                    else if (items.size < 2) snack("提纲至少写两项，一行一项", false)
                    else onConfirm(plan.copy(angle = angle.trim(), outline = items, note = note.trim()))
                }
                OutlineButton({ onEditing(false) }, Modifier.fillMaxWidth().height(46.dp), border = androidx.compose.foundation.BorderStroke(1.dp, P3.borderStrong)) { Text("先不改了", color = P3.text2, fontSize = 15.sp) }
            }
        }
    }
}

/**
 * 流程页总入口：按阶段分发。stale = 状态是「进行中」但后台任务已经不在了（进程被杀、被系统收走）
 */
@Composable
fun SkillFlowScreen(s: SkillSession, stale: Boolean, bottom: Dp, editing: Boolean, onEditing: (Boolean) -> Unit, onBack: () -> Unit, onCancel: () -> Unit,
                    onConfirm: (SkillPlan) -> Unit, onResearch: () -> Unit, onRetry: () -> Unit, onClose: () -> Unit, goModels: () -> Unit) {
    val title = when (s.phase) { "confirm" -> if (editing) "改角度与提纲" else "确认角度与提纲"; "write" -> "写正文"; else -> "搜资料" }
    Column(Modifier.fillMaxSize()) {
        SkillBar(title, onBack) {
            when {
                s.phase == "plan" && !stale -> TextAction("取消", color = P3.text2, onClick = onCancel)
                s.phase == "write" && !stale -> TextAction("取消", color = P3.text2, onClick = onCancel)
            }
        }
        Box(Modifier.weight(1f)) {
            when {
                stale && s.busy -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    TopicStrip(s.skillName, s.topic)
                    ErrorCard(Friendly("这次被中断了", "应用在后台被系统收走，没做完。资料和进度不会保留，可以重新来一次。", "", false), onRetry, null, onClose)
                }
                s.phase == "plan" -> PlanProgressView(s)
                s.phase == "write" -> WritingProgressView(s)
                s.phase == "fail" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    TopicStrip(s.skillName, s.topic)
                    ErrorCard(s.err ?: Friendly("没能完成", "请重试一次。", "", false), onRetry, if (s.err?.settings == true) goModels else null, onClose)
                    s.notices.forEach { Notice(it) }
                }
                s.phase == "confirm" && s.plan != null -> ConfirmPlanView(s, s.plan, editing, onEditing, onConfirm, onResearch, { onConfirm(s.plan) }, goModels, bottom)
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = P3.accent) }
            }
        }
        if (s.phase != "confirm") Spacer(Modifier.height(bottom))
    }
}

/** 创作页上的「接着做」横幅：plan / confirm / fail 阶段 */
@Composable
fun SkillResumeBanner(s: SkillSession, onClick: () -> Unit) {
    val (head, sub) = when (s.phase) {
        "plan" -> "正在搜资料" to "「${s.topic.take(18)}」· 点这里查看进度"
        "confirm" -> "提纲等你确认" to "「${s.topic.take(18)}」· 点这里继续"
        else -> "上一次没完成" to "「${s.topic.take(18)}」· 点这里重试"
    }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(Sq.xl).background(P3.accentSoft).hairline(Sq.xl, P3.accent.copy(alpha = .5f))
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp).testTag("resumeBanner"), verticalAlignment = Alignment.CenterVertically) {
        if (s.phase == "plan") CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.8.dp, color = P3.accent) else SvgIcon(if (s.phase == "fail") Ic.info else Ic.navWrite, P3.accent, 18.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(head, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = P3.onAccentSoft)
            Text(sub, fontSize = 12.sp, color = P3.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SvgIcon(Ic.chevron, P3.accent, 16.dp)
    }
}
