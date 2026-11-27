package com.endpix.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 自动检查更新开关(默认开): 进入应用时检查 GitHub Releases, 有更新自动弹提示. */
object UpdatePrefs {
    var autoCheck by mutableStateOf(true)
    var onAutoCheck: (Boolean) -> Unit = {}
}

/** 自动更新提示(全局): MainActivity 检查到新版本时 show, 避免给 MainScreen 加参数. */
object UpdatePromptHub {
    var visible by mutableStateOf(false)
    var tag by mutableStateOf("")
    var current by mutableStateOf("")

    fun show(tag: String, current: String) {
        this.tag = tag
        this.current = current
        visible = true
    }

    fun hide() { visible = false }
}

/** 发现新版本弹窗(GitHub 下载, 不走 endpix.com). */
@Composable
fun UpdatePromptHost() {
    if (!UpdatePromptHub.visible) return
    val ctx = LocalContext.current
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = null, indication = null) { UpdatePromptHub.hide() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.width(286.dp).background(Ep.Panel, RoundedCornerShape(6.dp))
                .border(1.dp, Ep.Line, RoundedCornerShape(6.dp))
                .clickable(interactionSource = null, indication = null) { }
                .padding(16.dp)
        ) {
            Text("发现新版本", color = Ep.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "最新 ${UpdatePromptHub.tag}（当前 ${UpdatePromptHub.current}）",
                color = Ep.Text, fontSize = 11.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f).height(34.dp)
                        .background(Ep.Panel2, RoundedCornerShape(4.dp))
                        .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
                        .clickable(interactionSource = null, indication = null) {
                            UpdatePromptHub.hide()
                        },
                    contentAlignment = Alignment.Center
                ) { Text("稍后", color = Ep.Text, fontSize = 11.sp) }
                Box(
                    Modifier.weight(1f).height(34.dp)
                        .background(Ep.Accent, RoundedCornerShape(4.dp))
                        .clickable(interactionSource = null, indication = null) {
                            UpdatePromptHub.hide()
                            UpdateHub.downloadAndInstall(ctx)
                        },
                    contentAlignment = Alignment.Center
                ) { Text("立即更新", color = Ep.Bg, fontSize = 11.sp) }
            }
            Spacer(Modifier.height(9.dp))
            Text(
                "可在设置 → 软件声明 中关闭提醒",
                color = Ep.Sub, fontSize = 8.sp, lineHeight = 11.sp
            )
        }
    }
}
