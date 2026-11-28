package com.endpix.nativeapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// 图像导入方案: mode 0=单图像 1=裁剪帧 2=并列帧(可叠层)
// offX/offY: 网格起点偏移(从原图左上角移开多少像素), 方便跳过边距/角标
data class ImageImportPlan(
    val mode: Int,
    val cellW: Int = 0,
    val cellH: Int = 0,
    val cols: Int = 1,
    val rows: Int = 1,
    val offX: Int = 0,
    val offY: Int = 0,
    val framesAcross: Boolean = false,
    val layered: Boolean = false,
    val depth: Int = 0
)

// 所有图像文件的"先预览再决定"入口; 由 MainActivity 的加载流程填入
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

// 画布外观(棋盘色/网格色)跨对话框共享: 导入预览的网格线/棋盘必须和画布一致
object CanvasStyleHub {
    var style by mutableStateOf(CanvasStyleSettings())
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
    var offXText by remember(rev) { mutableStateOf("0") }
    var offYText by remember(rev) { mutableStateOf("0") }
    var framesAcross by remember(rev) { mutableStateOf(false) }
    var layered by remember(rev) { mutableStateOf(true) }
    var depth by remember(rev) { mutableIntStateOf(3) }
    var frameIdx by remember(rev) { mutableIntStateOf(0) }
    var playing by remember(rev) { mutableStateOf(false) }
    var spin by remember(rev) { mutableStateOf(false) }
    var yaw by remember(rev) { mutableFloatStateOf(0f) }
    val style = CanvasStyleHub.style
    val cw = (cwText.toIntOrNull() ?: iw).coerceIn(1, max(1, iw))
    val ch = (chText.toIntOrNull() ?: ih).coerceIn(1, max(1, ih))
    val offX = (offXText.toIntOrNull() ?: 0).coerceIn(0, max(0, iw - 1))
    val offY = (offYText.toIntOrNull() ?: 0).coerceIn(0, max(0, ih - 1))
    // 可用区域 = 原图去掉偏移后的部分
    val cols = max(1, (iw - offX) / cw)
    val rows = max(1, (ih - offY) / ch)
    // 并列帧: 一条横排/竖排的图自动选对方向(否则横条会被当成"1 帧 × N 层")
    LaunchedEffect(mode, cw, ch, offX, offY) {
        if (mode == 2) {
            if (rows == 1 && cols > 1) framesAcross = true
            else if (cols == 1 && rows > 1) framesAcross = false
        }
    }
    val frameCount = when (mode) {
        1 -> cols * rows
        2 -> if (framesAcross) cols else rows
        else -> 1
    }.coerceAtLeast(1)
    val layerCount = if (mode == 2 && layered) (if (framesAcross) rows else cols) else 1
    val cellsOk = frameCount in 1..512 && layerCount in 1..32
    LaunchedEffect(mode, cw, ch, offX, offY, framesAcross, layered) { frameIdx = 0 }
    LaunchedEffect(frameCount) { if (frameIdx >= frameCount) frameIdx = 0 }
    // 播放: 裁剪帧/并列帧都能循环预览
    LaunchedEffect(playing, frameCount, mode) {
        if (!playing || mode == 0) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(130)
            frameIdx = (frameIdx + 1) % frameCount
        }
    }
    // 叠层视角自动旋转(预览)
    LaunchedEffect(spin) {
        if (!spin) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(33)
            var y = yaw + 1.4f
            if (y > 180f) y -= 360f
            yaw = y
        }
    }

    Dialog(
        onDismissRequest = { ImageImportHub.close(true) },
        properties = DialogProperties(usePlatformDefaultWidth = false,
            dismissOnClickOutside = false, dismissOnBackPress = true)
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center) {
            MotionDialogCard {
            Column(
                Modifier.fillMaxWidth()
                    .background(Ep.Panel, RoundedCornerShape(6.dp))
                    .border(1.dp, Ep.Line, RoundedCornerShape(6.dp))
                    .padding(12.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("导入图像", color = Ep.Text, fontSize = 15.sp,
                        fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(8.dp))
                    Text("${ImageImportHub.name}  ·  ${iw}×${ih}", color = Ep.Sub,
                        fontSize = 9.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    Box(Modifier.size(24.dp)
                        .background(Ep.Panel2, RoundedCornerShape(3.dp))
                        .clickable(interactionSource = null, indication = null) {
                            ImageImportHub.close(true)
                        }, contentAlignment = Alignment.Center) {
                        Text("✕", color = Ep.Sub, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
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
                Spacer(Modifier.height(8.dp))

                // 双栏: 左=原图裁剪预览, 右=单帧结果预览(棋盘底)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(if (mode == 1) "裁剪预览 · 点格切换" else "原图预览",
                            color = Ep.Sub, fontSize = 9.sp, maxLines = 1)
                        Spacer(Modifier.height(3.dp))
                        Canvas(
                            Modifier.fillMaxWidth().height(150.dp)
                                .background(Ep.Panel2, RoundedCornerShape(3.dp))
                                .pointerInput(mode, cw, ch, cols, rows, framesAcross, offX, offY, bmp) {
                                    if (mode == 0 || bmp == null) return@pointerInput
                                    detectTapGestures { p ->
                                        val s = min(size.width.toFloat() / iw, size.height.toFloat() / ih)
                                        val ox = (size.width - iw * s) / 2f
                                        val oy = (size.height - ih * s) / 2f
                                        val cx = ((p.x - ox) / s).toInt()
                                        val cy = ((p.y - oy) / s).toInt()
                                        if (cx >= offX && cy >= offY) {
                                            val col = ((cx - offX) / cw).coerceIn(0, cols - 1)
                                            val row = ((cy - offY) / ch).coerceIn(0, rows - 1)
                                            frameIdx = if (mode == 1) row * cols + col
                                            else if (framesAcross) col else row
                                        }
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
                                // 网格线颜色/透明度 = 画布网格线设置
                                val ga = max(0.35f, style.gridAlphaPct / 100f)
                                val lineCol = Color(style.gridColor).copy(alpha = ga)
                                val lw = max(1f, style.gridLineW)
                                for (c in 0..cols) {
                                    val x = ox + (offX + c * cw) * s
                                    if (x <= ox + dw + 0.5f) drawLine(lineCol, Offset(x, oy),
                                        Offset(x, oy + dh), lw)
                                }
                                for (r in 0..rows) {
                                    val y = oy + (offY + r * ch) * s
                                    if (y <= oy + dh + 0.5f) drawLine(lineCol, Offset(ox, y),
                                        Offset(ox + dw, y), lw)
                                }
                                // 偏移跳过的边距: 压暗
                                val maskCol = Color(0x66000000)
                                if (offX > 0) drawRect(maskCol, Offset(ox, oy),
                                    Size(offX * s, dh))
                                if (offY > 0) drawRect(maskCol, Offset(ox, oy), Size(dw, offY * s))
                                // 当前帧格子高亮(并列帧整行/整列)
                                val fill = Color(style.gridColor).copy(alpha = 0.22f)
                                val sx0 = ox + offX * s
                                val sy0 = oy + offY * s
                                if (mode == 1) {
                                    val c = frameIdx % cols
                                    val r = frameIdx / cols
                                    drawRect(fill, Offset(sx0 + c * cw * s, sy0 + r * ch * s),
                                        Size(cw * s, ch * s))
                                    drawRect(Color(style.gridColor).copy(alpha = 0.9f),
                                        Offset(sx0 + c * cw * s, sy0 + r * ch * s),
                                        Size(cw * s, ch * s), style = Stroke(1.5f))
                                } else if (framesAcross) {
                                    drawRect(fill, Offset(sx0 + frameIdx * cw * s, sy0),
                                        Size(cw * s, rows * ch * s))
                                    drawRect(Color(style.gridColor).copy(alpha = 0.9f),
                                        Offset(sx0 + frameIdx * cw * s, sy0),
                                        Size(cw * s, rows * ch * s), style = Stroke(1.5f))
                                } else {
                                    drawRect(fill, Offset(sx0, sy0 + frameIdx * ch * s),
                                        Size(cols * cw * s, ch * s))
                                    drawRect(Color(style.gridColor).copy(alpha = 0.9f),
                                        Offset(sx0, sy0 + frameIdx * ch * s),
                                        Size(cols * cw * s, ch * s), style = Stroke(1.5f))
                                }
                            }
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (mode == 2 && layered) "叠层结果预览" else "单帧结果预览",
                            color = Ep.Sub, fontSize = 9.sp, maxLines = 1)
                        Spacer(Modifier.height(3.dp))
                        val stackable = mode == 2 && layered
                        Canvas(Modifier.fillMaxWidth().height(150.dp)
                            .background(Ep.Panel2, RoundedCornerShape(3.dp))
                            .pointerInput(stackable, bmp) {
                                if (!stackable || bmp == null) return@pointerInput
                                detectHorizontalDragGestures { _, dx ->
                                    var y = yaw + dx * 0.6f
                                    while (y > 180f) y -= 360f
                                    while (y < -180f) y += 360f
                                    yaw = y
                                }
                            }) {
                            if (bmp == null) return@Canvas
                            if (mode == 0) {
                                // 单图像: 棋盘底 + 原图(透明区域能直接看出来)
                                val s = min(size.width / iw, size.height / ih)
                                val dw = iw * s
                                val dh = ih * s
                                val ox = (size.width - dw) / 2f
                                val oy = (size.height - dh) / 2f
                                drawImportChecker(Rect(ox, oy, ox + dw, oy + dh), s, style)
                                drawImage(
                                    bmp,
                                    dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
                                    dstSize = IntSize(max(1, dw.roundToInt()), max(1, dh.roundToInt())),
                                    filterQuality = FilterQuality.None
                                )
                                return@Canvas
                            }
                            val n = if (stackable) layerCount else 1
                            val extra = if (stackable) max(0, n - 1) * depth else 0
                            val rad = yaw * 3.14159265f / 180f
                            val rc = abs(cos(rad))
                            val rs = abs(sin(rad))
                            val contentW = cw + 2f * extra
                            val contentH = ch + 2f * extra
                            val rotW = contentW * rc + contentH * rs
                            val rotH = contentW * rs + contentH * rc
                            val s = min(size.width / rotW, size.height / rotH)
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            // 未旋转时画布(帧)矩形左上角
                            val bx = cx - cw * s / 2f
                            val by = cy - ch * s / 2f
                            rotate(yaw, Offset(cx, cy)) {
                                // 棋盘只铺在"画布范围"内, 叠层越界部分显示在棋盘外(和主画布一致)
                                drawImportChecker(Rect(bx, by, bx + cw * s, by + ch * s),
                                    s, style)
                                val offXv = -sin(rad) * depth * s
                                val offYv = -cos(rad) * depth * s
                                if (mode == 1) {
                                    val c = frameIdx % cols
                                    val r = frameIdx / cols
                                    drawImage(
                                        bmp, srcOffset = IntOffset(offX + c * cw, offY + r * ch),
                                        srcSize = IntSize(cw, ch),
                                        dstOffset = IntOffset(bx.roundToInt(), by.roundToInt()),
                                        dstSize = IntSize(max(1, (cw * s).roundToInt()),
                                            max(1, (ch * s).roundToInt())),
                                        filterQuality = FilterQuality.None
                                    )
                                } else if (stackable) {
                                    // 底层先画, 上层按视角方向逐层错开 depth(与画布 2.5D 同向)
                                    for (l in 0 until n) {
                                        val col = if (framesAcross) frameIdx else l
                                        val row = if (framesAcross) l else frameIdx
                                        val lx = bx + offXv * l
                                        val ly = by + offYv * l
                                        drawImage(
                                            bmp, srcOffset = IntOffset(offX + col * cw, offY + row * ch),
                                            srcSize = IntSize(cw, ch),
                                            dstOffset = IntOffset(lx.roundToInt(), ly.roundToInt()),
                                            dstSize = IntSize(max(1, (cw * s).roundToInt()),
                                                max(1, (ch * s).roundToInt())),
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
                                            bmp, srcOffset = IntOffset(offX + col * cw, offY + row * ch),
                                            srcSize = IntSize(cw, ch),
                                            dstOffset = IntOffset(bx.roundToInt(), by.roundToInt()),
                                            dstSize = IntSize(max(1, (cw * s).roundToInt()),
                                                max(1, (ch * s).roundToInt())),
                                            filterQuality = FilterQuality.None
                                        )
                                    }
                                }
                                // 画布边界线
                                drawRect(Color(0xFF808080), Offset(bx, by),
                                    Size(cw * s, ch * s),
                                    style = Stroke(1f))
                            }
                        }
                    }
                }
                if (mode != 0) {
                    Spacer(Modifier.height(5.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ImpChip(if (playing) "⏸ 暂停" else "▶ 播放", playing) { playing = !playing }
                        Spacer(Modifier.width(6.dp))
                        ImpChip("◀", false) { frameIdx = (frameIdx - 1 + frameCount) % frameCount }
                        Spacer(Modifier.width(4.dp))
                        Text("第 ${frameIdx + 1}/$frameCount 帧", color = Ep.Sub, fontSize = 10.sp)
                        Spacer(Modifier.width(4.dp))
                        ImpChip("▶", false) { frameIdx = (frameIdx + 1) % frameCount }
                        Spacer(Modifier.weight(1f))
                        if (mode == 2 && layered) {
                            ImpChip("自动转", spin) { spin = !spin }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                // 参数
                when (mode) {
                    0 -> Text("整张图作为单层导入（保持原尺寸，透明区域保留）", color = Ep.Sub,
                        fontSize = 9.5.sp)
                    1 -> {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            NumField("帧宽", cwText) { cwText = it }
                            NumField("帧高", chText) { chText = it }
                            NumField("偏移X", offXText) { offXText = it }
                            NumField("偏移Y", offYText) { offYText = it }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("网格 ${cols}×${rows} = $frameCount 帧（按行优先，从偏移点开始）",
                            color = Ep.Sub, fontSize = 9.5.sp)
                    }
                    2 -> {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            NumField("格宽", cwText) { cwText = it }
                            NumField("格高", chText) { chText = it }
                            NumField("偏移X", offXText) { offXText = it }
                            NumField("偏移Y", offYText) { offYText = it }
                        }
                        Spacer(Modifier.height(5.dp))
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ImpChip("横排帧·竖排层", framesAcross) { framesAcross = true }
                            ImpChip("横排层·竖排帧", !framesAcross) { framesAcross = false }
                            Spacer(Modifier.width(2.dp))
                            ImpChip(if (layered) "叠层导入" else "压成单层", layered) { layered = !layered }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            if (layered) "→ $frameCount 帧 × $layerCount 层"
                            else "→ $frameCount 帧（单层）",
                            color = Ep.Sub, fontSize = 9.5.sp
                        )
                        if (layered) {
                            Spacer(Modifier.height(4.dp))
                            ImpSlider("叠层高度", depth.toFloat(), 1f, 20f, "$depth px") {
                                depth = it.roundToInt().coerceIn(1, 20)
                            }
                            if (yaw != 0f) {
                                ImpSlider("预览视角", yaw, -180f, 180f, "${yaw.roundToInt()}°") {
                                    yaw = it
                                }
                            }
                        }
                    }
                }
                if (!cellsOk) {
                    Spacer(Modifier.height(3.dp))
                    Text("格子太多（帧≤512、层≤32），请加大格宽/格高或调整偏移",
                        color = Ep.Danger, fontSize = 9.5.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (mode == 2 && layered) "叠层预览可左右拖动旋转"
                        else if (mode != 0) "点裁剪预览切帧，播放可循环"
                        else "预览仅供确认", color = Ep.Sub, fontSize = 9.sp,
                        modifier = Modifier.weight(1f))
                    ImpBtn("取消", false, true) { ImageImportHub.close(true) }
                    Spacer(Modifier.width(8.dp))
                    ImpBtn("导入", true, cellsOk) {
                        val plan = ImageImportPlan(
                            mode = mode, cellW = cw, cellH = ch, cols = cols, rows = rows,
                            offX = offX, offY = offY,
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

// 棋盘底(格子大小跟随缩放, 最小 4px, 与画布着色器一致)
private fun DrawScope.drawImportChecker(r: Rect, scale: Float, style: CanvasStyleSettings) {
    if (r.width <= 0f || r.height <= 0f) return
    val cell = max(4f, style.chkTile * scale)
    val ca = Color(style.chkA)
    val cb = Color(style.chkB)
    val nx = (r.width / cell).roundToInt().coerceAtLeast(1)
    val ny = (r.height / cell).roundToInt().coerceAtLeast(1)
    val cw = r.width / nx
    val ch = r.height / ny
    for (y in 0 until ny) {
        for (x in 0 until nx) {
            drawRect(
                if ((x + y) and 1 == 0) ca else cb,
                Offset(r.left + x * cw, r.top + y * ch),
                Size(cw + 0.5f, ch + 0.5f)
            )
        }
    }
}

@Composable
private fun ImpChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.background(if (selected) Ep.Accent else Ep.Panel2, RoundedCornerShape(3.dp))
            .border(1.dp, if (selected) Ep.Accent else Ep.Line, RoundedCornerShape(3.dp))
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(label, color = if (selected) Ep.Bg else Ep.Text, fontSize = 9.5.sp, maxLines = 1)
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
            .padding(horizontal = 16.dp, vertical = 7.dp)
    ) {
        Text(label, color = if (primary && enabled) Ep.Bg else Ep.Text, fontSize = 12.sp)
    }
}

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ep.Sub, fontSize = 9.sp)
        Spacer(Modifier.width(3.dp))
        BasicTextField(
            value = value,
            onValueChange = { s -> if (s.length <= 5 && s.all { it.isDigit() }) onChange(s) },
            singleLine = true,
            textStyle = TextStyle(color = Ep.Text, fontSize = 12.sp),
            cursorBrush = SolidColor(Ep.Accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(48.dp)
                .background(Ep.Panel2, RoundedCornerShape(3.dp))
                .padding(horizontal = 5.dp, vertical = 4.dp)
        )
    }
}

// 紧凑滑条: 圆角轨道 + 已选填充 + 圆形滑块 + 常显数值; 点/拖都跟手。
@Composable
private fun ImpSlider(label: String, value: Float, minV: Float, maxV: Float,
                      valueText: String, onChange: (Float) -> Unit) {
    val onLatest = androidx.compose.runtime.rememberUpdatedState(onChange)
    Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ep.Sub, fontSize = 9.5.sp, modifier = Modifier.width(46.dp))
        Box(
            Modifier.weight(1f).height(24.dp)
                .pointerInput(minV, maxV) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        fun apply(p: Float) {
                            val t = (p / w).coerceIn(0f, 1f)
                            onLatest.value(minV + t * (maxV - minV))
                        }
                        apply(down.position.x)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            apply(ch.position.x)
                            ev.changes.forEach { it.consume() }
                        }
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cy = size.height / 2f
                val trackH = 5.dp.toPx()
                val knobR = 7.dp.toPx()
                val t = ((value - minV) / (maxV - minV)).coerceIn(0f, 1f)
                val x0 = knobR
                val x1 = size.width - knobR
                val kx = x0 + (x1 - x0) * t
                drawRoundRect(Ep.Panel2, Offset(x0, cy - trackH / 2f),
                    Size(x1 - x0, trackH), androidx.compose.ui.geometry.CornerRadius(trackH / 2f))
                drawRoundRect(Ep.Accent, Offset(x0, cy - trackH / 2f),
                    Size((kx - x0).coerceAtLeast(trackH), trackH),
                    androidx.compose.ui.geometry.CornerRadius(trackH / 2f))
                drawCircle(Ep.Panel, knobR + 1.5f, Offset(kx, cy))
                drawCircle(Ep.Accent, knobR, Offset(kx, cy))
                drawCircle(Ep.Bg, knobR * 0.42f, Offset(kx, cy))
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(valueText, color = Ep.Text, fontSize = 9.5.sp, maxLines = 1,
            modifier = Modifier.width(44.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}
