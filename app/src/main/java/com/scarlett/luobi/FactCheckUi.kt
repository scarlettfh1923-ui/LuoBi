package com.scarlett.luobi

import androidx.compose.ui.platform.testTag
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp

/** 1.11：阅读页顶部进度胶囊（不挡正文） */
@Composable
fun FactProgressChip(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, modifier = modifier, enter = Motion.fadeIn + scaleIn(initialScale = .92f), exit = Motion.fadeOut + scaleOut(targetScale = .92f)) {
        Row(Modifier.clip(Sq.full).background(P3.text).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
            Spacer(Modifier.width(8.dp))
            Text("正在核对事实…", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}


// 2.1：旧的 FactSheet / verdictColor（红橙绿三色）已由 FactScreen.kt 取代
