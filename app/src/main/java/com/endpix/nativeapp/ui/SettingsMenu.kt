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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 旧项目 ShowSettingsMenu 复刻: 设置⚙点开的 7 项主菜单, 导出/加载带右侧子菜单
@Composable
fun SettingsMenu(
    onSaveProject: () -> Unit,
    onExportPng: () -> Unit,
    onExportProject: () -> Unit,
    onExportCache: () -> Unit = {},
    onExportPsd: () -> Unit,
    onExportWebp: () -> Unit,
    onExportReplay: () -> Unit,
    onLoadFile: () -> Unit,
    onCreateCanvas: () -> Unit,
    onResizeCanvas: () -> Unit,
    onAdjustSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sub by remember { mutableIntStateOf(0) }   // 0=无 1=导出 2=加载
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Top) {
        Column(
            Modifier.width(156.dp)
                .background(Ep.Panel, RoundedCornerShape(4.dp))
                .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
                .padding(5.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            SettingsMenuRow(41, "保存项目") { onSaveProject() }
            SettingsMenuRow(42, "导出项目", arrow = true) { sub = if (sub == 1) 0 else 1 }
            SettingsMenuRow(31, "加载文件") { onLoadFile() }
            SettingsMenuRow(17, "创建画布") { onCreateCanvas() }
            SettingsMenuRow(43, "调整画布大小") { onResizeCanvas() }
            SettingsMenuRow(10, "调整设置") { onAdjustSettings() }
        }
        if (sub == 1) {
            SettingsSubColumn(
                topPad = 5.dp + (30.dp + 1.dp),
                items = listOf(
                    SettingsSubItem("导出 PNG", dim = false, onExportPng),
                    SettingsSubItem("导出 WebP", dim = false, onExportWebp),
                    SettingsSubItem("导出项目", dim = false, onExportProject),
                    SettingsSubItem("导出为缓存项目", dim = false, onExportCache),
                    SettingsSubItem("导出 PSD", dim = false, onExportPsd),
                    SettingsSubItem("过程 / 回放", dim = false, onExportReplay),
                )
            )
        }
    }
}

private data class SettingsSubItem(val label: String, val dim: Boolean, val action: () -> Unit)

@Composable
private fun SettingsSubColumn(topPad: androidx.compose.ui.unit.Dp, items: List<SettingsSubItem>) {
    Column(
        Modifier.padding(top = topPad).width(104.dp)
            .background(Ep.Panel, RoundedCornerShape(4.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
            .padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        items.forEach { item ->
            Box(
                Modifier.fillMaxWidth().height(26.dp)
                    .background(Ep.Panel2, RoundedCornerShape(3.dp))
                    .clickable(interactionSource = null, indication = null) { item.action() }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    item.label,
                    color = if (item.dim) Ep.Sub else Ep.Text,
                    fontSize = 9.sp, maxLines = 1, softWrap = false
                )
            }
        }
    }
}

@Composable
private fun SettingsMenuRow(icon: Int, label: String, arrow: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(30.dp)
            .background(Ep.Panel2, RoundedCornerShape(3.dp))
            .clickable(interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EpIcon(icon, Ep.Text, size = 14.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            label, color = Ep.Text, fontSize = 9.5.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f), maxLines = 1, softWrap = false
        )
        if (arrow) Text("▸", color = Ep.Sub, fontSize = 9.sp, textAlign = TextAlign.Center)
    }
}
