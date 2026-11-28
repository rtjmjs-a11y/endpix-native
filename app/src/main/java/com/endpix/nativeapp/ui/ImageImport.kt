package com.endpix.nativeapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// 图像导入方案: mode 0=单图像 1=裁剪帧 2=并列帧(可叠层)
data class ImageImportPlan(
    val mode: Int,
    val cellW: Int = 0,
    val cellH: Int = 0,
    val cols: Int = 1,
    val rows: Int = 1,
    val framesAcross: Boolean = false,
    val layered: Boolean = false,
    val depth: Int = 0
)

// 所有图像文件的“先预览再决定”入口; 由 MainActivity 的加载流程填入
object ImageImportHub {
    val open = mutableStateOf(false)
    val rev = mutableIntStateOf(0)
    var name: String = ""
    var imgW: Int = 0
    var imgH: Int = 0
    var preview: ImageBitmap? = null
    var allowGrid: Boolean = true
    var onImport: ((ImageImportPlan) -> Unit)? = null
    var onCancel: (() -> Unit)? = null

    fun show(name: String, w: Int, h: Int, preview: ImageBitmap?, allowGrid: Boolean,
             onImport: (ImageImportPlan) -> Unit, onCancel: () -> Unit) {
        this.name = name
        this.imgW = w
        this.imgH = h
        this.preview = preview
        this.allowGrid = allowGrid
        this.onImport = onImport
        this.onCancel = onCancel
        open.value = true
        rev.intValue++
    }

    fun close(runCancel: Boolean) {
        val c = onCancel
        open.value = false
        name = ""
        preview = null
        onImport = null
        onCancel = null
        if (runCancel) c?.invoke()
    }
}

@Composable
fun ImageImportDialog() {
    if (!ImageImportHub.open.value) return
    val rev = ImageImportHub.rev.intValue
    val bmp = ImageImportHub.preview
    val iw = ImageImportHub.imgW
    val ih = ImageImportHub.imgH
    val allowGrid = ImageImportHub.allowGrid
    var mode by remember(rev) { mutableIntStateOf(0) }
    var cwText by remember(rev) { mutableStateOf(iw.coerceAtLeast(1).toString()) }
    var chText by remember(rev) { mutableStateOf(ih.coerceAtLeast(1).toString()) }
    var framesAcross by remember(rev) { mutableStateOf(false) }
    var layered by remember(rev) { mutableStateOf(true) }
    var depth by remember(rev) { mutableIntStateOf(3) }
    var frameIdx by remember(rev) { mutableIntStateOf(0) }
    val cw = (cwText.toIntOrNull() ?: iw).coerceIn(1, max(1, iw))
    val ch = (chText.toIntOrNull() ?: ih).coerceIn(1, max(1, ih))
    val cols = max(1, iw / cw)
    val rows = max(1, ih / ch)
    val frameCount = when (mode) {
        1 -> cols * rows
        2 -> if (framesAcross) cols else rows
        else -> 1
    }.coerceAtLeast(1)
    val layerCount = if (mode == 2 && layered) (if (framesAcross) rows else cols) else 1
    val cellsOk = frameCount in 1..512 && layerCount in 1..32
    LaunchedEffect(mode, cw, ch, framesAcross, layered) { frameIdx = 0 }
    LaunchedEffect(frameCount) { if (frameIdx >= frameCount) frameIdx = 0 }

    Dialog(
        onDismissRequest = { ImageImportHub.close(true) },
        properties = DialogProperties(usePlatformDefaultWidth = false,
            dismissOnClickOutside = false, dismissOnBackPress = true)
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 28.dp),
            contentAlignment = Alignment.Center) {
            MotionDialogCard {
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(max = 700.dp)
                    .background(Ep.Panel, RoundedCornerShape(6.dp))
                    .border(1.dp, Ep.Line, RoundedCornerShape(6.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp)
            ) {
                Text("导入图像", color = Ep.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Text("${ImageImportHub.name}  ·  ${iw}×${ih}", color = Ep.Sub, fontSize = 10.sp)
                Spacer(Modifier.height(10.dp))

                // 模式
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ImpChip("单图像", mode == 0) { mode = 0 }
                    if (allowGrid) {
                        ImpChip("裁剪帧", mode == 1) { mode = 1 }
                        ImpChip("并列帧", mode == 2) { mode = 2 }
                    }
                }
                if (!allowGrid) {
                    Spacer(Modifier.height(4.dp))
                    Text("此入口只支持单图像导入", color = Ep.Sub, fontSize = 9.sp)
                }
                Spacer(Modifier.height(10.dp))

                // 裁剪预览
                Text("裁剪预览", color = Ep.Sub, fontSize = 10.sp)
                Spacer(Modifier.height(4.dp))
                Canvas(
                    Modifier.fillMaxWidth().height(170.dp)
                        .background(Ep.Panel2, RoundedCornerShape(3.dp))
                        .pointerInput(mode, cw, ch, cols, rows, framesAcross, bmp) {
                            if (mode == 0 || bmp == null) return@pointerInput
                            detectTapGestures { p ->
                                val s = min(size.width.toFloat() / iw, size.height.toFloat() / ih)
                                val ox = (size.width - iw * s) / 2f
                                val oy = (size.height - ih * s) / 2f
                                val cx = ((p.x - ox) / s).toInt().coerceIn(0, iw - 1)
                                val cy = ((p.y - oy) / s).toInt().coerceIn(0, ih - 1)
                                val col = (cx / cw).coerceIn(0, cols - 1)
                                val row = (cy / ch).coerceIn(0, rows - 1)
                                frameIdx = if (mode == 1) row * cols + col
                                else if (framesAcross) col else row
                            }
                        }
                ) {
                    if (bmp == null) return@Canvas
                    val s = min(size.width / iw, size.height / ih)
                    val dw = iw * s
                    val dh = ih * s
                    val ox = (size.width - dw) / 2f
                    val oy = (size.height - dh) / 2f
                    drawImage(
                        bmp,
                        dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
                        dstSize = IntSize(max(1, dw.roundToInt()), max(1, dh.roundToInt())),
                        filterQuality = FilterQuality.None
                    )
                    if (mode != 0) {
                        val line = Ep.Accent.copy(alpha = 0.55f)
                        for (c in 0..cols) {
                            val x = ox + c * cw * s
                            drawLine(line, Offset(x, oy), Offset(x, oy + dh), 1f)
                        }
                        for (r in 0..rows) {
                            val y = oy + r * ch * s
                            drawLine(line, Offset(ox, y), Offset(ox + dw, y), 1f)
                        }
                        // 当前帧的格子高亮(并列帧整行/整列)
                        val fill = Ep.Accent.copy(alpha = 0.20f)
                        if (mode == 1) {
                            val c = frameIdx % cols
                            val r = frameIdx / cols
                            drawRect(fill, Offset(ox + c * cw * s, oy + r * ch * s),
                                Size(cw * s, ch * s))
                        } else if (framesAcross) {
                            drawRect(fill, Offset(ox + frameIdx * cw * s, oy),
                                Size(cw * s, dh))
                        } else {
                            drawRect(fill, Offset(ox, oy + frameIdx * ch * s),
                                Size(dw, ch * s))
                        }
                    }
                }
                Spacer(Modifier.height(9.dp))

                // 模式参数
                when (mode) {
                    0 -> Text("整张图作为单层导入（保持原尺寸）", color = Ep.Sub, fontSize = 10.sp)
                    1 -> {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumField("帧宽", cwText) { cwText = it }
                            NumField("帧高", chText) { chText = it }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("网格 ${cols}×${rows} = $frameCount 帧（按行优先）", color = Ep.Sub, fontSize = 10.sp)
                    }
                    2 -> {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumField("格宽", cwText) { cwText = it }
                            NumField("格高", chText) { chText = it }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ImpChip("横排帧·竖排层", framesAcross) { framesAcross = true }
                            ImpChip("横排层·竖排帧", !framesAcross) { framesAcross = false }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ImpChip("叠层导入", layered) { layered = !layered }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (layered) "$layerCount 层 × $frameCount 帧"
                                else "交叉轴压成 $frameCount 帧（单层）",
                                color = Ep.Sub, fontSize = 10.sp
                            )
                        }
                        if (layered) {
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("叠层高度 $depth", color = Ep.Text, fontSize = 10.sp,
                                    modifier = Modifier.width(74.dp))
                                Slider(
                                    value = depth.toFloat(),
                                    onValueChange = { depth = it.roundToInt().coerceIn(1, 20) },
                                    valueRange = 1f..20f, steps = 18,
                                    modifier = Modifier.weight(1f).height(26.dp)
                                )
                            }
                            Text("导入后同步到时间轴：2.5D 开、叠层高度 $depth", color = Ep.Sub, fontSize = 9.sp)
                        }
                    }
                }
                if (!cellsOk) {
                    Spacer(Modifier.height(4.dp))
                    Text("格子太多（帧≤512、层≤32），请加大格宽/格高", color = Ep.Danger, fontSize = 10.sp)
                }
                Spacer(Modifier.height(8.dp))

                // 单帧结果预览
                Text("单帧结果预览", color = Ep.Sub, fontSize = 10.sp)
                Spacer(Modifier.height(4.dp))
                Canvas(Modifier.fillMaxWidth().height(140.dp)
                    .background(Ep.Panel2, RoundedCornerShape(3.dp))) {
                    if (bmp == null) return@Canvas
                    if (mode == 0) {
                        val s = min(size.width / iw, size.height / ih)
                        val dw = iw * s
                        val dh = ih * s
                        drawImage(
                            bmp,
                            dstOffset = IntOffset(((size.width - dw) / 2f).roundToInt(),
                                ((size.height - dh) / 2f).roundToInt()),
                            dstSize = IntSize(max(1, dw.roundToInt()), max(1, dh.roundToInt())),
                            filterQuality = FilterQuality.None
                        )
                        return@Canvas
                    }
                    val n = if (mode == 2 && layered) layerCount else 1
                    val extra = if (mode == 2 && layered) max(0, n - 1) * depth else 0
                    val rw = cw
                    val rh = ch + extra
                    val s = min(size.width / rw, size.height / rh)
                    val ox = (size.width - rw * s) / 2f
                    val oy = (size.height - rh * s) / 2f
                    if (mode == 1) {
                        val c = frameIdx % cols
                        val r = frameIdx / cols
                        drawImage(
                            bmp, srcOffset = IntOffset(c * cw, r * ch), srcSize = IntSize(cw, ch),
                            dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
                            dstSize = IntSize(max(1, (cw * s).roundToInt()), max(1, (ch * s).roundToInt())),
                            filterQuality = FilterQuality.None
                        )
                    } else if (layered) {
                        // 从底层到顶层，每层向上错开 depth（与画布 2.5D 语义一致）
                        for (l in 0 until n) {
                            val col = if (framesAcross) frameIdx else l
                            val row = if (framesAcross) l else frameIdx
                            val dy = (n - 1 - l) * depth
                            drawImage(
                                bmp, srcOffset = IntOffset(col * cw, row * ch), srcSize = IntSize(cw, ch),
                                dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt() + (dy * s).roundToInt()),
                                dstSize = IntSize(max(1, (cw * s).roundToInt()), max(1, (ch * s).roundToInt())),
                                filterQuality = FilterQuality.None
                            )
                        }
                    } else {
                        // 非叠层: 交叉轴各格按顺序压成一帧
                        val cross = if (framesAcross) rows else cols
                        for (c in 0 until cross) {
                            val col = if (framesAcross) frameIdx else c
                            val row = if (framesAcross) c else frameIdx
                            drawImage(
                                bmp, srcOffset = IntOffset(col * cw, row * ch), srcSize = IntSize(cw, ch),
                                dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
                                dstSize = IntSize(max(1, (cw * s).roundToInt()), max(1, (ch * s).roundToInt())),
                                filterQuality = FilterQuality.None
                            )
                        }
                    }
                }
                if (mode != 0) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("第 ${frameIdx + 1}/$frameCount 帧", color = Ep.Sub, fontSize = 10.sp,
                            modifier = Modifier.weight(1f))
                        ImpChip("上一帧", false) { frameIdx = (frameIdx - 1 + frameCount) % frameCount }
                        Spacer(Modifier.width(6.dp))
                        ImpChip("下一帧", false) { frameIdx = (frameIdx + 1) % frameCount }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ImpBtn("取消", false, true) { ImageImportHub.close(true) }
                    Spacer(Modifier.width(8.dp))
                    ImpBtn("导入", true, cellsOk) {
                        val plan = ImageImportPlan(
                            mode = mode, cellW = cw, cellH = ch, cols = cols, rows = rows,
                            framesAcross = framesAcross,
                            layered = mode == 2 && layered,
                            depth = if (mode == 2 && layered) depth else 0
                        )
                        val cb = ImageImportHub.onImport
                        ImageImportHub.close(false)
                        cb?.invoke(plan)
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun ImpChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.background(if (selected) Ep.Accent else Ep.Panel2, RoundedCornerShape(3.dp))
            .border(1.dp, if (selected) Ep.Accent else Ep.Line, RoundedCornerShape(3.dp))
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Text(label, color = if (selected) Ep.Bg else Ep.Text, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun ImpBtn(label: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.background(
            if (!enabled) Ep.Disabled else if (primary) Ep.Accent else Ep.Panel2,
            RoundedCornerShape(3.dp)
        )
            .clickable(interactionSource = null, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(label, color = if (primary && enabled) Ep.Bg else Ep.Text, fontSize = 12.sp)
    }
}

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ep.Sub, fontSize = 10.sp)
        Spacer(Modifier.width(4.dp))
        BasicTextField(
            value = value,
            onValueChange = { s -> if (s.length <= 5 && s.all { it.isDigit() }) onChange(s) },
            singleLine = true,
            textStyle = TextStyle(color = Ep.Text, fontSize = 13.sp),
            cursorBrush = SolidColor(Ep.Accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(64.dp)
                .background(Ep.Panel2, RoundedCornerShape(3.dp))
                .padding(horizontal = 6.dp, vertical = 5.dp)
        )
    }
}
