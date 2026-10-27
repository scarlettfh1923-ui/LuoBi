package com.scarlett.luobi

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 线条图标：1.5px 描边、圆角端点；selected 时加 22% 不透明度的填充 */
@Composable
fun SvgIcon(paths: List<String>, tint: Color, size: Dp = 24.dp, fill: Color? = null, desc: String? = null) {
    val stroke = remember(paths) {
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            paths.forEach { addPath(PathParser().parsePathString(it).toNodes(), stroke = SolidColor(Color.Black), strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) }
        }.build()
    }
    val filled = remember(paths, fill != null) {
        if (fill == null) null else ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
            paths.forEach { addPath(PathParser().parsePathString(it).toNodes(), fill = SolidColor(Color.Black)) }
        }.build()
    }
    Box(Modifier.size(size)) {
        if (filled != null && fill != null) Icon(rememberVectorPainter(filled), null, Modifier.fillMaxSize(), tint = fill.copy(alpha = .22f))
        Icon(rememberVectorPainter(stroke), desc, Modifier.fillMaxSize(), tint = tint)
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, pad: Dp = 16.dp, color: Color = P3.surface, content: @Composable ColumnScope.() -> Unit) {
    // 1.6：统一表面——无描边，极柔中性阴影，同一 squircle
    val m = modifier.fillMaxWidth().surface(Sq.xl, color)
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(pad)
    Column(m, content = content)
}

/** 底部导航：写作 / 文案库 / 设置 */
@Composable
fun BottomNav(sel: Int, onSel: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().background(P3.surface)) {
        HorizontalDivider(thickness = Dp.Hairline, color = P3.border)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp)) {
            listOf(Triple("创作", Ic.write, 0), Triple("案头", Ic.library, 1), Triple("设定", Ic.settings, 2)).forEach { (l, ic, i) ->
                val on = sel == i
                Column(Modifier.weight(1f).fillMaxHeight().clickable { onSel(i) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    SvgIcon(ic, if (on) P3.accent else P3.text3, 24.dp, if (on) P3.accent else null)
                    Text(l, Modifier.padding(top = 3.dp), color = if (on) P3.accent else P3.text2, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
fun BigTitle(title: String, sub: String = "") {
    // 1.5：紧凑标题，一行
    Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = P3.text, lineHeight = 30.sp)
        if (sub.isNotEmpty()) Text(sub, Modifier.padding(start = 10.dp, bottom = 3.dp), fontSize = 13.sp, color = P3.text2)
    }
}

/** 子页面顶栏：‹ 标题 [动作] */
@Composable
fun SubBar(title: String, back: () -> Unit, action: (@Composable RowScope.() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTap(onClick = back) { SvgIcon(Ic.back, P3.text, desc = "返回") }
        Text(title, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = P3.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (action != null) Row(verticalAlignment = Alignment.CenterVertically, content = action)
    }
}

/** 分组小标题（等宽字体点缀） */
@Composable
fun GroupLabel(t: String, modifier: Modifier = Modifier) {
    SectionLabel(t, modifier)
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, busy: Boolean = false, onClick: () -> Unit) {
    FilledButton(onClick, modifier.fillMaxWidth().height(52.dp), enabled = enabled && !busy, shape = Sq.full,
        colors = ButtonDefaults.buttonColors(containerColor = P3.accent, contentColor = Color.White, disabledContainerColor = Color(0xFFE5DDCF), disabledContentColor = Color(0xFF6B6155))) {
        if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = P3.accent); Spacer(Modifier.width(8.dp)) }
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 胶囊选择 */
@Composable
fun Pill(text: String, sel: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(text, modifier.clip(Sq.full).background(if (sel) P3.accentSoft else P3.surface)
        .hairline(Sq.full, if (sel) P3.accent else P3.borderStrong).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        color = if (sel) P3.onAccentSoft else P3.text, fontSize = 14.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
}

/** 输入框：次级表面底 + 描边 */
@Composable
fun InputBox(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, minHeight: Dp = 0.dp, singleLine: Boolean = false,
             secret: Boolean = false, mono: Boolean = false, fontSize: Int = 15) {
    var show by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().clip(Sq.m).background(P3.surface2).hairline(Sq.m, P3.border)
        .padding(horizontal = 12.dp, vertical = 10.dp).heightIn(min = minHeight), verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top) {
        Box(Modifier.weight(1f)) {
            BasicTextField(value, onChange, Modifier.fillMaxWidth(), singleLine = singleLine,
                textStyle = TextStyle(fontSize = fontSize.sp, lineHeight = (fontSize * 1.6).sp, color = P3.text, fontFamily = if (mono) Mono else null),
                visualTransformation = if (secret && !show) PasswordVisualTransformation() else VisualTransformation.None, cursorBrush = SolidColor(P3.accent))
            if (value.isEmpty()) Text(placeholder, fontSize = fontSize.sp, lineHeight = (fontSize * 1.6).sp, color = P3.text3)
        }
        if (secret && value.isNotEmpty()) Text(if (show) "隐藏" else "显示", Modifier.padding(start = 8.dp).clickable { show = !show }, fontSize = 12.sp, color = P3.accent)
    }
}

@Composable
fun FieldLabel(t: String, sub: String = "") {
    Row(Modifier.padding(top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(t, fontSize = 13.sp, color = P3.text2, fontWeight = FontWeight.SemiBold)
        if (sub.isNotEmpty()) Text("  ·  $sub", fontSize = 12.sp, color = P3.text3)
    }
}

/** 黄色警告条 */
@Composable
fun WarnBox(t: String, modifier: Modifier = Modifier) {
    Text(t, modifier.fillMaxWidth().clip(Sq.m).background(P3.warnBg).hairline(Sq.m, P3.warnBorder).padding(12.dp),
        color = P3.warnText, fontSize = 13.sp, lineHeight = 19.sp)
}

/** 设置列表行：图标底 + 标题 + 说明 + › */
@Composable
fun SettingRow(icon: List<String>, title: String, sub: String, onClick: () -> Unit) {
    // 1.5 统一行网格：18dp 线性图标列（16+18+16）| 标题 | 右侧弱化值（单行）| ›
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { SvgIcon(icon, P3.text, 18.dp) }
        Spacer(Modifier.width(16.dp))
        Text(title, fontSize = 15.sp, color = P3.text, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Text(sub, Modifier.padding(start = 12.dp).widthIn(max = 150.dp), fontSize = 13.sp, color = P3.text3, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        Spacer(Modifier.width(6.dp))
        SvgIcon(Ic.chevron, P3.text3, 14.dp)
    }
}

/** 分组：小标题 + 一张卡；行之间细线从文字起点开始（缩进 50dp） */
@Composable
fun RowGroup(label: String?, rows: List<@Composable () -> Unit>) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        if (label != null) SectionLabel(label)
        Card(pad = 0.dp) { rows.forEachIndexed { i, r -> if (i > 0) RowDivider(); r() } }
    }
}
@Composable fun RowDivider() = HorizontalDivider(Modifier.padding(start = 50.dp), thickness = Dp.Hairline, color = P3.border)

/** 变量块 {疑问} */
@Composable
fun VarChip(name: String, onClick: (() -> Unit)? = null) {
    Text("{$name}", Modifier.clip(Sq.s).background(P3.accentSoft).let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 10.dp, vertical = 6.dp),
        color = P3.onAccentSoft, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
fun TextAction(t: String, color: Color = P3.accent, enabled: Boolean = true, onClick: () -> Unit) {
    Text(t, Modifier.clip(Sq.s).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp), color = if (enabled) color else P3.text3, fontSize = 14.sp, fontWeight = FontWeight.Medium)
}

/** 1.18：所有确认框都走 GlassAlert（方案 B）；danger = 删除类（红色按钮） */
@Composable
fun ConfirmDialog(title: String, text: String, ok: String, danger: Boolean = false, onOk: () -> Unit, onDismiss: () -> Unit) =
    GlassAlert(title, text, listOf(AlertAction(ok, if (danger) AlertAction.Role.Destructive else AlertAction.Role.Default, onOk), AlertAction("取消", AlertAction.Role.Cancel) {}), onDismiss)

@Composable
fun Dot(color: Color, size: Dp = 7.dp) = Box(Modifier.size(size).clip(Sq.full).background(color))

/** 单选列表：一行一个，右侧勾选圆 */
@Composable
fun ChoiceList(items: List<Triple<String, String, Boolean>>, pick: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(Sq.m).hairline(Sq.m, P3.border)) {
        items.forEachIndexed { i, (a, b, sel) ->
            if (i > 0) HorizontalDivider(Modifier.padding(start = 14.dp), thickness = Dp.Hairline, color = P3.border)
            Row(Modifier.fillMaxWidth().clickable { pick(i) }.padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(a, fontSize = 15.sp, color = P3.text, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                Text("  $b", Modifier.weight(1f), fontSize = 13.sp, color = P3.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sel) Box(Modifier.size(20.dp).clip(Sq.full).background(P3.accent), contentAlignment = Alignment.Center) { SvgIcon(Ic.check, Color.White, 13.dp) }
                else Box(Modifier.size(20.dp).hairline(Sq.full, P3.borderStrong, 1.5.dp))
            }
        }
    }
}

/** 分段胶囊（1.6）：选中 = 浅蓝底 + 蓝字；未选 = 白底细线 */
@Composable
fun SegChip(text: String, sel: Boolean, onClick: () -> Unit) {
    Text(text, Modifier.heightIn(min = 32.dp).clip(Sq.full).background(if (sel) P3.accentSoft else P3.surface).let { if (sel) it else it.hairline(Sq.full, P3.border) }
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp), fontSize = 13.sp, color = if (sel) P3.accent else P3.text,
        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
}

/** 1.6 统一分组小标题：12sp 半粗 #6B7280，左缩进与卡内文字对齐 */
@Composable
fun SectionLabel(t: String, modifier: Modifier = Modifier) {
    Text(t, modifier.padding(start = 12.dp, top = 16.dp, bottom = 6.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF6B6155))
}
