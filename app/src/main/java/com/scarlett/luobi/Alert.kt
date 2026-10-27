package com.scarlett.luobi

import android.os.Build
import android.view.WindowManager
import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.delay

/** 一个按钮：文字 + 颜色角色 */
data class AlertAction(val label: String, val role: Role = Role.Default, val onClick: () -> Unit) { enum class Role { Default, Destructive, Cancel } }

/**
 * 1.18 方案 B：苹果式居中玻璃弹窗（全 App 唯一的确认 / 输入对话框）。
 * - 标题居中半粗 17sp；说明居中 13sp；可选内容槽（输入框）
 * - 按钮上下排开、细线分隔：危险操作红色，取消强调蓝半粗，其它强调蓝
 * - 进场：遮罩淡入 + 面板 0.92→1 弹簧缩放；退场 180ms 淡出缩小，结束后才真正关闭
 * - Android 12+ 窗口背景真模糊（blurBehind），以下为 86% 白底
 */
@Composable
fun GlassAlert(title: String, message: String? = null, actions: List<AlertAction>, onDismiss: () -> Unit, quote: String? = null, content: (@Composable ColumnScope.() -> Unit)? = null) {
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    var after by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun close(then: () -> Unit) { if (after != null) return; after = then; state.targetState = false }
    LaunchedEffect(after) { val a = after ?: return@LaunchedEffect; delay(if (Motion.reduced) 0L else DS.Dur.SHORT.toLong()); onDismiss(); a() }
    Dialog(onDismissRequest = { close {} }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val w = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            w?.setDimAmount(0f); w?.setWindowAnimations(0)
            if (w != null && Glass.blur && Build.VERSION.SDK_INT >= 31) { w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND); w.attributes = w.attributes.apply { blurBehindRadius = 40 } }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AnimatedVisibility(state, enter = Motion.fadeIn, exit = Motion.fadeOut) {
                Box(Modifier.fillMaxSize().background(DS.scrim).clickable(remember { MutableInteractionSource() }, null) { close {} })
            }
            AnimatedVisibility(state, enter = Motion.alertIn, exit = Motion.alertOut) {
                Column(Modifier.testTag("glassAlert").padding(horizontal = 40.dp).widthIn(max = 300.dp).fillMaxWidth().imePadding()
                    .softShadow(Sq.alert, DS.Elev.alert).clip(Sq.alert).background(if (Glass.blur) DS.Glassy.alertTint else Color(0xFFFAF6EE))
                    .hairline(Sq.alert, Glass.rim), horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(title, fontSize = DS.Type.headline, fontWeight = FontWeight.SemiBold, color = P3.text, textAlign = TextAlign.Center, lineHeight = 22.sp17)
                        if (quote != null) Text("「$quote」", Modifier.padding(top = 6.dp), fontSize = DS.Type.sub, color = P3.text2, textAlign = TextAlign.Center, lineHeight = 19.sp13)
                        if (message != null) Text(message, Modifier.padding(top = if (quote != null) 4.dp else 6.dp), fontSize = DS.Type.sub, color = if (quote != null) P3.text3 else P3.text2, textAlign = TextAlign.Center, lineHeight = 19.sp13)
                        if (content != null) Column(Modifier.fillMaxWidth().padding(top = 12.dp)) { content() }
                    }
                    actions.forEach { a ->
                        HorizontalDivider(thickness = Dp.Hairline, color = Color(0x2E3C3428))
                        Box(Modifier.fillMaxWidth().heightIn(min = 50.dp).clickable { close(a.onClick) }.testTag("alert:${a.label}"), contentAlignment = Alignment.Center) {
                            Text(a.label, fontSize = DS.Type.headline, color = if (a.role == AlertAction.Role.Destructive) DS.danger else DS.accent,
                                fontWeight = if (a.role == AlertAction.Role.Default) FontWeight.Normal else FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
private val Int.sp17 get() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)
private val Int.sp13 get() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)

/** 删除类确认：标题 +「标题」+ 删除后无法恢复；删除（红）/ 取消（蓝） */
@Composable
fun DeleteAlert(title: String, name: String?, message: String = "删除后无法恢复", ok: String = "删除", onOk: () -> Unit, onDismiss: () -> Unit) =
    GlassAlert(title, message, listOf(AlertAction(ok, AlertAction.Role.Destructive, onOk), AlertAction("取消", AlertAction.Role.Cancel) {}), onDismiss, quote = name?.takeIf { it.isNotBlank() })
