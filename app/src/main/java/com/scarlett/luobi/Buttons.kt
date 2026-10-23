package com.scarlett.luobi

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * 1.18：替代 Material 的 Button / TextButton / OutlinedButton / IconButton。
 * 全部用同一种按压反馈（SoftPress：压暗 6%），不出现水波纹；尺寸、圆角、内边距与原来一致。
 */
@Composable
private fun BaseButton(onClick: () -> Unit, modifier: Modifier, enabled: Boolean, shape: Shape, bg: Color, fg: Color, border: BorderStroke?, contentPadding: PaddingValues, minH: androidx.compose.ui.unit.Dp, content: @Composable RowScope.() -> Unit) {
    Row(modifier.defaultMinSize(minHeight = minH).clip(shape).background(bg).let { if (border != null) it.border(border, shape) else it }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(contentPadding),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        CompositionLocalProvider(LocalContentColor provides fg, LocalTextStyle provides MaterialTheme.typography.labelLarge) { content() }
    }
}

@Composable
fun FilledButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = Sq.full, colors: ButtonColors = ButtonDefaults.buttonColors(containerColor = DS.accent, contentColor = Color.White),
                 contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 8.dp), content: @Composable RowScope.() -> Unit) =
    BaseButton(onClick, modifier, enabled, shape, if (enabled) colors.containerColor else colors.disabledContainerColor, if (enabled) colors.contentColor else colors.disabledContentColor, null, contentPadding, 40.dp, content)

@Composable
fun PlainButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = Sq.full, contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp), content: @Composable RowScope.() -> Unit) =
    BaseButton(onClick, modifier.alpha(if (enabled) 1f else .45f), enabled, shape, Color.Transparent, DS.accent, null, contentPadding, 40.dp, content)

@Composable
fun OutlineButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape = Sq.full, border: BorderStroke? = null, contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 8.dp), content: @Composable RowScope.() -> Unit) =
    BaseButton(onClick, modifier.alpha(if (enabled) 1f else .45f), enabled, shape, Color.Transparent, DS.accent, border, contentPadding, 40.dp, content)

@Composable
fun IconTap(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) =
    Box(modifier.size(48.dp).clip(Sq.full).clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) { content() }

/**
 * 1.18：全 App 唯一的底部弹层。圆角、底色、遮罩（DS.scrim）、拖动条、展开方式统一；
 * 以前 5 处各写各的（有的半展开、有的遮罩偏黑）。参数名与 ModalBottomSheet 一致，shape / 遮罩不可改。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(onDismissRequest: () -> Unit, modifier: Modifier = Modifier, sheetState: androidx.compose.material3.SheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
             @Suppress("UNUSED_PARAMETER") shape: Shape = Sq.topXl, containerColor: Color = P3.surface,
             dragHandle: (@Composable () -> Unit)? = { androidx.compose.material3.BottomSheetDefaults.DragHandle(color = P3.borderStrong) },
             contentWindowInsets: @Composable () -> WindowInsets = { androidx.compose.material3.BottomSheetDefaults.windowInsets },
             content: @Composable ColumnScope.() -> Unit) =
    androidx.compose.material3.ModalBottomSheet(onDismissRequest, modifier, sheetState, shape = Sq.topXl, containerColor = containerColor, scrimColor = DS.scrim,
        dragHandle = dragHandle, contentWindowInsets = contentWindowInsets, content = content)
