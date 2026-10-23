package com.scarlett.luobi

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** P3 冷白 + 电光蓝（需求说明 1.5），只用一个强调色，不用渐变和光晕 */
object P3 {
    val bg = Color(0xFFF2F2F7)
    val surface = Color(0xFFFFFFFF)
    val surface2 = Color(0xFFEEF2F7)
    val border = Color(0xFFE3E4E8)   // 1.6：全 App 唯一细线色
    val borderStrong = Color(0xFFCDD5E0)
    val text = Color(0xFF111827)
    val text2 = Color(0xFF5B6576)
    val text3 = Color(0xFF8A94A6)
    val accent = DS.accent
    val accentSoft = Color(0xFFE6EEFF)
    val onAccentSoft = Color(0xFF1A3FA0)
    val warnText = Color(0xFFB45309)
    val warnBg = Color(0xFFFFF8EB)
    val warnBorder = Color(0xFFF5D49A)
    val danger = DS.danger
}
val Terracotta = P3.accent
val Mono = FontFamily.Monospace
val Serif = FontFamily.Default

private val Light = lightColorScheme(
    primary = P3.accent, onPrimary = Color.White, primaryContainer = P3.accentSoft, onPrimaryContainer = P3.onAccentSoft,
    secondary = P3.text2, secondaryContainer = P3.accentSoft, onSecondaryContainer = P3.onAccentSoft,
    tertiary = P3.text2, tertiaryContainer = P3.surface2,
    background = P3.bg, onBackground = P3.text,
    surface = P3.bg, onSurface = P3.text, surfaceVariant = P3.surface2, onSurfaceVariant = P3.text2,
    surfaceContainer = P3.surface2, surfaceContainerLow = P3.surface, surfaceContainerHigh = P3.surface2, surfaceContainerHighest = P3.surface2,
    outline = P3.borderStrong, outlineVariant = P3.border, error = P3.danger,
)

/** 暗色模式之后再定（需求说明 1.5），目前始终亮色 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = Light,
        typography = base.copy(
            headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
            bodyMedium = base.bodyMedium.copy(lineHeight = 22.sp),
        ),
        shapes = Shapes(extraSmall = RoundedCornerShape(Corner.xs), small = RoundedCornerShape(Corner.s), medium = RoundedCornerShape(Corner.l), large = RoundedCornerShape(Corner.xl), extraLarge = RoundedCornerShape(Corner.xl)),  // 仅作兜底；所有组件都显式传 Squircle
        content = {
            // 1.18：全局统一按压反馈（压暗），去掉 Material 水波纹（与玻璃 / 苹果式界面冲突）
            @OptIn(ExperimentalMaterial3Api::class)
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.LocalIndication provides SoftPress, LocalRippleConfiguration provides null, content = content)
        },
    )
}

@Composable fun t3(): Color = P3.text3

/** 阅读模式调色板：bg 页面 / surface 面板与胶囊 / border 描边 */
data class ReadPalette(val name: String, val bg: Color, val text: Color, val sub: Color, val accent: Color, val surface: Color, val border: Color, val dark: Boolean, val soft: Color) {
    fun long() = LongPalette(bg.toArgb(), text.toArgb(), sub.toArgb(), accent.toArgb(), soft.toArgb(), if (dark) text.toArgb() else 0xFF1A3FA0.toInt())
}
/** 1.16.1 方案 A：四种阅读模式（只作用于阅读页和它的玻璃栏，不影响分享图） */
val readPalettes = listOf(
    ReadPalette("素白", Color(0xFFFFFFFF), Color(0xFF1F2329), Color(0xFF6B7280), Color(0xFF2F6BFF), Color(0xFFF5F6F8), Color(0xFFE5E7EB), false, Color(0xFFE6EEFF)),
    ReadPalette("纸张", Color(0xFFF4ECDD), Color(0xFF3A3128), Color(0xFF8A7C69), Color(0xFF2F5FD0), Color(0xFFFAF5EA), Color(0xFFE3D8C3), false, Color(0xFFE3E8F5)),
    ReadPalette("抹茶", Color(0xFFE3ECDF), Color(0xFF26332A), Color(0xFF6E7F70), Color(0xFF2D62E6), Color(0xFFEEF3EB), Color(0xFFCCD9C6), false, Color(0xFFDDE6F6)),
    ReadPalette("夜读", Color(0xFF17181B), Color(0xFFD4CCBF), Color(0xFF8C877E), Color(0xFF7FA2FF), Color(0xFF212226), Color(0x1FFFFFFF), true, Color(0xFF263252)),
)
/** 旧模式下标（0 冷白 / 1 白底 / 2 纸张 / 3 石墨）→ 新下标（0 素白 / 1 纸张 / 2 抹茶 / 3 夜读） */
fun migrateReadMode(old: Int): Int = when (old) { 0, 1 -> 0; 2 -> 1; 3 -> 3; else -> 0 }
