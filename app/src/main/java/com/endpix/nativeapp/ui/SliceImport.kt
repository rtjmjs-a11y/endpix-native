package com.endpix.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// 叠层切片导入: zip 内每张图一层(按文件顺序), 图内纵向按用户输入的帧高切帧。
// 数据由 MainActivity 的“加载文件”流程填入; 确认后回调帧高。
object SliceImportHub {
    val open = mutableStateOf(false)
    var names: List<String> = emptyList()
    var widths: IntArray = IntArray(0)
    var heights: IntArray = IntArray(0)
    var pixels: IntArray? = null
    var maxW: Int = 0
    var minH: Int = 1
    var defaultFrameH: Int = 20
    var onConfirm: ((Int) -> Unit)? = null

    fun show(nm: List<String>, w: IntArray, h: IntArray, px: IntArray, defH: Int,
             cb: (Int) -> Unit) {
        names = nm
        widths = w
        heights = h
        pixels = px
        maxW = w.maxOrNull() ?: 0
        minH = (h.minOrNull() ?: 1).coerceAtLeast(1)
        defaultFrameH = defH.coerceIn(1, minH)
        onConfirm = cb
        open.value = true
    }

    fun close(clear: Boolean) {
        open.value = false
        if (clear) {
            names = emptyList()
            widths = IntArray(0)
            heights = IntArray(0)
            pixels = null
            onConfirm = null
        }
    }
}

@Composable
fun SliceImportDialog() {
    if (!SliceImportHub.open.value) return
    val names = SliceImportHub.names
    val widths = SliceImportHub.widths
    val heights = SliceImportHub.heights
    val minH = SliceImportHub.minH
    var txt by remember(SliceImportHub.open.value, SliceImportHub.defaultFrameH) {
        mutableStateOf(SliceImportHub.defaultFrameH.toString())
    }
    val fh = (txt.toIntOrNull() ?: SliceImportHub.defaultFrameH).coerceIn(1, minH)
    val counts = heights.map { (it / fh).coerceAtLeast(1) }
    val totalFrames = counts.maxOrNull() ?: 1
    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false)
    ) {
        Column(
            Modifier.width(310.dp)
                .background(Ep.Panel, RoundedCornerShape(6.dp))
                .border(1.dp, Ep.Line, RoundedCornerShape(6.dp))
                .padding(16.dp)
        ) {
            Text("叠层切片导入", color = Ep.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Text(
                "${names.size} 层 · 画布 ${SliceImportHub.maxW}×$fh · $totalFrames 帧",
                color = Ep.Sub, fontSize = 11.sp
            )
            Spacer(Modifier.height(6.dp))
            for (i in 0 until minOf(names.size, 5)) {
                Text(
                    "$i  ${names[i]}  ${widths.getOrElse(i) { 0 }}×${heights.getOrElse(i) { 0 }} → ${counts.getOrElse(i) { 1 }} 帧",
                    color = Ep.Sub, fontSize = 10.sp
                )
            }
            if (names.size > 5) Text("…", color = Ep.Sub, fontSize = 10.sp)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("每帧高度", color = Ep.Text, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = txt,
                    onValueChange = { s -> if (s.length <= 4 && s.all { it.isDigit() }) txt = s },
                    singleLine = true,
                    textStyle = TextStyle(color = Ep.Text, fontSize = 14.sp),
                    cursorBrush = SolidColor(Ep.Accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(76.dp)
                        .background(Ep.Panel2, RoundedCornerShape(3.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("1–$minH", color = Ep.Sub, fontSize = 10.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text("第 0 层=第一张切片；层内纵向按帧高切帧，帧数不足保持最后一帧。",
                color = Ep.Sub, fontSize = 10.sp)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SliceBtn("取消", false) { SliceImportHub.close(true) }
                Spacer(Modifier.width(8.dp))
                SliceBtn("导入", true) {
                    val cb = SliceImportHub.onConfirm
                    SliceImportHub.close(true)
                    cb?.invoke(fh)
                }
            }
        }
    }
}

@Composable
private fun SliceBtn(label: String, primary: Boolean, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier
            .background(if (primary) Ep.Accent else Ep.Panel2, RoundedCornerShape(3.dp))
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(label, color = if (primary) Ep.Bg else Ep.Text, fontSize = 12.sp)
    }
}
