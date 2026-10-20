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
    val accent = Color(0xFF2F6BFF)
    val accentSoft = Color(0xFFE6EEFF)
    val onAccentSoft = Color(0xFF1A3FA0)
    val warnText = Color(0xFFB45309)
    val warnBg = Color(0xFFFFF8EB)
    val warnBorder = Color(0xFFF5D49A)
    val danger = Color(0xFFC0392B)
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
        content = content,
    )
}

@Composable fun t3(): Color = P3.text3

/** 阅读模式调色板：bg 页面 / surface 面板与胶囊 / border 描边 */
data class ReadPalette(val name: String, val bg: Color, val text: Color, val sub: Color, val accent: Color, val surface: Color, val border: Color, val dark: Boolean, val soft: Color) {
    fun long() = LongPalette(bg.toArgb(), text.toArgb(), sub.toArgb(), accent.toArgb(), soft.toArgb(), if (dark) text.toArgb() else 0xFF1A3FA0.toInt())
}
val readPalettes = listOf(
    ReadPalette("冷白", P3.bg, P3.text, P3.text3, P3.accent, P3.surface, P3.border, false, P3.accentSoft),
    ReadPalette("白底", Color.White, P3.text, P3.text3, P3.accent, P3.bg, P3.border, false, P3.accentSoft),
    ReadPalette("纸张", Color(0xFFF7F3EA), Color(0xFF2A2620), Color(0xFF8C8273), P3.accent, Color(0xFFFFFCF6), Color(0xFFE9E2D4), false, P3.accentSoft),
    ReadPalette("石墨", Color(0xFF1B1F27), Color(0xFFE6E9EE), Color(0xFF8A94A6), Color(0xFF6F9BFF), Color(0xFF232833), Color(0x1FFFFFFF), true, Color(0xFF2A3550)),
)
