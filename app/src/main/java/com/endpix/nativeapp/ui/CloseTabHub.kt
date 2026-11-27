package com.endpix.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * 关闭画布标签的确认弹窗: 关闭 / 导出 / 保留缓存(完整快照)。
 * 独立文件是为了不再往 MainScreen 方法里加代码(已到 JVM 64K 方法大小上限)。
 */
object CloseTabHub {
    var pendingId by mutableIntStateOf(-1)
    var pendingName by mutableStateOf("")
    var close: (Int) -> Unit = {}
    var export: () -> Unit = {}
    var keep: (Int) -> Unit = {}

    fun request(id: Int, name: String) {
        pendingId = id
        pendingName = name
    }
}

@Composable
fun CloseTabDialogHost() {
    if (CloseTabHub.pendingId < 0) return
    val id = CloseTabHub.pendingId
    Dialog(onDismissRequest = { }) {
        Column(
            Modifier.width(272.dp).background(Ep.Panel, RoundedCornerShape(6.dp))
                .border(1.dp, Ep.Line, RoundedCornerShape(6.dp)).padding(14.dp)
        ) {
            Text("关闭画布", color = Ep.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "「${CloseTabHub.pendingName}」关闭后内容会删除。可以先导出项目，或保留到缓存列表（含全部画布状态与步骤）。",
                color = Ep.Sub, fontSize = 9.sp, lineHeight = 13.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                CloseTabBtn("取消") { CloseTabHub.pendingId = -1 }
                CloseTabBtn("关闭") {
                    CloseTabHub.pendingId = -1
                    CloseTabHub.close(id)
                }
                Spacer(Modifier.weight(1f))
                CloseTabBtn("导出") {
                    CloseTabHub.pendingId = -1
                    CloseTabHub.export()
                }
                CloseTabBtn("保留缓存", accent = true) {
                    CloseTabHub.pendingId = -1
                    CloseTabHub.keep(id)
                }
            }
        }
    }
}

@Composable
private fun CloseTabBtn(text: String, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.height(30.dp)
            .background(if (accent) Ep.Accent.copy(alpha = 0.24f) else Ep.Panel2, RoundedCornerShape(4.dp))
            .border(1.dp, if (accent) Ep.Accent else Ep.Line, RoundedCornerShape(4.dp))
            .clickable(interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (accent) Ep.Accent else Ep.Text, fontSize = 9.5.sp, maxLines = 1)
    }
}
