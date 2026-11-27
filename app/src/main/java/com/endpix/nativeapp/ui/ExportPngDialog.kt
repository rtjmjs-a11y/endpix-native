package com.endpix.nativeapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.roundToInt

// 导出 PNG/WebP(旧项目 ShowExportPNGDialog 本地化):
// 上半屏大预览, 下半屏: 倍率 + 5 种导出模式 + 模式参数 + 输出信息 + 按钮。
@Composable
fun ExportPngDialog(
    currentW: Int,
    currentH: Int,
    thumb: IntArray?,
    onDismiss: () -> Unit,
    onExport: (Int) -> Unit,
    format: String = "PNG",
    allowMultiModes: Boolean = true,
) {
    var scale by remember { mutableIntStateOf(1) }
    var mode by remember { mutableIntStateOf(if (allowMultiModes) 0 else 0) }
    var cols by remember { mutableIntStateOf(4) }
    var delayMs by remember { mutableIntStateOf(100) }
    var delayText by remember { mutableStateOf("100") }
    val cropMode = CropExportHub.exportOpen
    val info = remember(mode) { ExportOps.infoArray() }
    // 2.5D 叠层状态: 叠层图导出会按当前叠层高度/方向错位
    val d25 = remember { LayerOps.state25D().getOrElse(0) { 0 } != 0 }
    // 裁剪导出: 范围固定为裁剪目标(currentW/H), 不再读整张画布的 info
    val w = if (cropMode) currentW else if (info.getOrElse(0) { 0 } > 0) info[0] else currentW
    val h = if (cropMode) currentH else if (info.getOrElse(1) { 0 } > 0) info[1] else currentH
    // 预览图: 裁剪导出用裁剪像素按比例重采样(不再是 64² 方形缩略图 → 拉伸糊掉);
    // 普通导出沿用引擎 384² 合成缩略图。
    val img = remember(thumb, cropMode, w, h, CropExportHub.pixels) {
        if (cropMode) cropPreviewImage(w, h) else thumbSquareToImage(thumb)
    }
    val frameCount = if (cropMode) 1 else info.getOrElse(2) { 1 }.coerceAtLeast(1)
    val loopA = if (cropMode) 0 else info.getOrElse(3) { 0 }
    val loopB = if (cropMode) 0 else info.getOrElse(4) { frameCount - 1 }
    val frames = if (cropMode) 1 else (loopB - loopA + 1).coerceIn(1, frameCount)
    val layers = if (cropMode) 1 else info.getOrElse(5) { 1 }.coerceAtLeast(1)
    val cellW = w.toLong() * scale
    val cellH = h.toLong() * scale
    val gifOk = cellW * cellH <= 64_000_000L
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            // ---- 预览区(上 42%) ----
            Box(Modifier.weight(0.36f).fillMaxWidth().background(Color(0xFF1E1E1E))) {
                if (img != null) {
                    Canvas(Modifier.fillMaxSize().padding(16.dp)) {
                        val sc = minOf(size.width / w, size.height / h).coerceAtLeast(0.0001f)
                        val dw = w * sc
                        val dh = h * sc
                        val dx = (size.width - dw) * 0.5f
                        val dy = (size.height - dh) * 0.5f
                        // 预览底(仿小地图背景模式): 0=棋盘格 1=棋盘色A 2=棋盘色B 3=深色
                        when (ExportHub.previewBg) {
                            1 -> drawRect(Color(ExportHub.chkA), Offset(dx, dy), Size(dw, dh))
                            2 -> drawRect(Color(ExportHub.chkB), Offset(dx, dy), Size(dw, dh))
                            3 -> drawRect(Color(0xFF1E1E1E), Offset(dx, dy), Size(dw, dh))
                            else -> {
                                val cell = 8.dp.toPx()
                                var yy = dy
                                var row = 0
                                while (yy < dy + dh) {
                                    val hh = minOf(cell, dy + dh - yy)
                                    var xx = dx
                                    var col = 0
                                    while (xx < dx + dw) {
                                        val ww = minOf(cell, dx + dw - xx)
                                        drawRect(
                                            Color(if ((row + col) % 2 == 0) ExportHub.chkA else ExportHub.chkB),
                                            Offset(xx, yy), Size(ww, hh)
                                        )
                                        xx += cell; col++
                                    }
                                    yy += cell; row++
                                }
                            }
                        }
                        drawImage(
                            img,
                            dstOffset = IntOffset(dx.toInt(), dy.toInt()),
                            dstSize = IntSize(maxOf(2, dw.toInt()), maxOf(2, dh.toInt())),
                            filterQuality = FilterQuality.None
                        )
                        // 导出水印预览: 在成品输出像素坐标系里平铺, 与导出烘焙完全一致
                        if (ExportHub.wmOn && ExportHub.wmAlpha > 0) {
                            val outW = w.toFloat() * scale
                            val outH = h.toFloat() * scale
                            if (outW > 0f && outH > 0f && dw > 0f && dh > 0f) {
                                val text = ExportHub.wmText.ifBlank {
                                    ExportHub.wmBaseText.ifBlank { "Endpix" }
                                }
                                val nc = drawContext.canvas.nativeCanvas
                                clipRect(dx, dy, dx + dw, dy + dh) {
                                    val save = nc.save()
                                    nc.translate(dx, dy)
                                    nc.scale(dw / outW, dh / outH)
                                    drawExportWatermarkText(
                                        nc, outW, outH,
                                        text, ExportHub.wmAlpha,
                                        (ExportHub.wmSize.coerceIn(8, 96) * scale).toFloat(),
                                        ExportHub.wmColor, scale.toFloat(),
                                        densityPct = ExportHub.wmDensity
                                    )
                                    nc.restoreToCount(save)
                                }
                            }
                        }
                    }
                } else {
                    Text("无预览", color = Ep.Sub, fontSize = 10.sp, modifier = Modifier.align(Alignment.Center))
                }
                // 预览底切换(仿小地图: 棋盘格/A/B/深色), 点按循环
                Row(
                    Modifier.align(Alignment.TopStart).padding(10.dp)
                        .background(Ep.Panel2, RoundedCornerShape(4.dp))
                        .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
                        .clickable(interactionSource = null, indication = null) {
                            ExportHub.cyclePreviewBg()
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Canvas(Modifier.size(14.dp)) {
                        val half = size.width / 2f
                        val ca = Color(ExportHub.chkA)
                        val cb = Color(ExportHub.chkB)
                        when (ExportHub.previewBg) {
                            1 -> drawRect(ca)
                            2 -> drawRect(cb)
                            3 -> drawRect(Color(0xFF1E1E1E))
                            else -> {
                                drawRect(ca, Offset.Zero, Size(half, half))
                                drawRect(cb, Offset(half, 0f), Size(half, half))
                                drawRect(cb, Offset(0f, half), Size(half, half))
                                drawRect(ca, Offset(half, half), Size(half, half))
                            }
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("预览底: " + ExportHub.previewBgName(), color = Ep.Text, fontSize = 9.sp)
                }
            }
            // ---- 选项区(下 58%) ----
            Column(
                Modifier.weight(0.64f).fillMaxWidth()
                    .background(Ep.Panel)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)
            ) {
                Text("导出 $format", color = Ep.Text, fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("放大倍率", color = Ep.Sub, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    Text("${scale}x", color = Ep.Text, fontSize = 13.sp)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ExpStepBtn("−", enabled = scale > 1) { if (scale > 1) scale-- }
                    Spacer(Modifier.width(8.dp))
                    Row(Modifier.weight(1f).height(10.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        repeat(10) { i ->
                            Box(Modifier.weight(1f).fillMaxHeight().background(
                                if (i < scale) Ep.Accent else Color(0xFF2A3038), RoundedCornerShape(1.dp)))
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    ExpStepBtn("+", enabled = scale < 10) { if (scale < 10) scale++ }
                }
                Spacer(Modifier.height(4.dp))
                Text("导出模式", color = Ep.Sub, fontSize = 11.sp)
                Spacer(Modifier.height(3.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val names = listOf("单帧", "精灵图", "逐帧", "GIF", "叠层图")
                    for (i in names.indices) {
                        ExpModeChip(names[i], on = mode == i, enabled = allowMultiModes) {
                            if (allowMultiModes) mode = i
                        }
                    }
                }
                // ---- 模式参数 ----
                when (mode) {
                    1 -> {
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("每行帧数", color = Ep.Sub, fontSize = 11.sp)
                            Spacer(Modifier.weight(1f))
                            ExpStepBtn("−", enabled = cols > 1) { cols = (cols - 1).coerceAtLeast(1) }
                            Text("$cols", color = Ep.Text, fontSize = 12.sp,
                                textAlign = TextAlign.Center, modifier = Modifier.width(34.dp))
                            ExpStepBtn("+", enabled = cols < 10) { cols = (cols + 1).coerceAtMost(10) }
                        }
                    }
                    3 -> {
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("帧延迟", color = Ep.Sub, fontSize = 11.sp)
                            Spacer(Modifier.weight(1f))
                            Box(
                                Modifier.width(92.dp).height(30.dp)
                                    .background(Ep.Panel2, RoundedCornerShape(4.dp))
                                    .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                BasicTextField(
                                    value = delayText,
                                    onValueChange = { t ->
                                        val d = t.filter { it.isDigit() }.take(5)
                                        delayText = d
                                        d.toIntOrNull()?.let {
                                            delayMs = it.coerceIn(20, 60000)
                                        }
                                    },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    textStyle = TextStyle(
                                        color = Ep.Text, fontSize = 12.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    ),
                                    cursorBrush = SolidColor(Ep.Accent),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text("ms", color = Ep.Sub, fontSize = 10.sp)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                val outDesc = when (mode) {
                    1 -> {
                        val rows = (frames + cols - 1) / cols
                        "输出: ${cellW * cols}×${cellH * rows}（${cols}列 × ${rows}行，共${frames}帧）"
                    }
                    2 -> "输出: ${cellW}×${cellH} × ${frames} 张 PNG（打包为 zip）"
                    3 -> "输出: ${cellW}×${cellH} GIF（${delayMs}ms/帧，共${frames}帧）"
                    4 -> "输出: ${cellW * layers}×${cellH * frames}（${layers}层 × ${frames}帧）"
                    else -> "输出: ${cellW}×${cellH}"
                }
                Text(
                    "画布: ${w}×${h}  帧: ${frames}/${frameCount}  图层: ${layers}\n$outDesc",
                    color = Ep.Sub, fontSize = 9.5.sp, lineHeight = 13.sp
                )
                Text(
                    when (mode) {
                        1 -> "精灵图: 按当前循环区间逐帧铺进一张 PNG，透明区保留。"
                        2 -> "逐帧: 每帧一张 PNG，全部打包成一个 zip 保存。"
                        3 -> "GIF: 每帧独立 256 色量化 + LZW，透明背景保留；帧延迟可直接输入 20–60000ms。"
                        4 -> if (d25) "叠层图: 左右按图层、上下按帧；已按当前 2.5D 叠层高度/视角错位导出。"
                        else "叠层图: 左右按图层、上下按帧，每格是该层原始像素。"
                        else -> if (cropMode) "按裁剪目标区域导出 PNG（保留透明像素）。"
                        else if (format == "WebP") "无损 WebP（保留透明）；上限 16383×16383。"
                        else "按完整画布、实际尺寸导出 PNG（不裁剪透明边）。"
                    },
                    color = Ep.Sub, fontSize = 8.sp, lineHeight = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (mode == 3 && !gifOk) {
                    Text("单帧超过 6400 万像素，GIF 内存不足，请降低倍率。",
                        color = Color(0xFFE07070), fontSize = 8.sp, modifier = Modifier.padding(top = 2.dp))
                }
                // ---- 导出水印: 只在本次导出生效(不写回画布/项目) ----
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("导出水印", color = Ep.Sub, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    Text(if (ExportHub.wmOn) "开" else "关",
                        color = if (ExportHub.wmOn) Ep.Accent else Ep.Sub, fontSize = 10.sp)
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier.width(44.dp).height(22.dp)
                            .background(if (ExportHub.wmOn) Ep.Accent else Ep.Panel2,
                                RoundedCornerShape(9.dp))
                            .border(1.dp, if (ExportHub.wmOn) Ep.Accent else Ep.Line,
                                RoundedCornerShape(9.dp))
                            .clickable(interactionSource = null, indication = null) {
                                ExportHub.wmOn = !ExportHub.wmOn
                            }
                            .padding(2.dp),
                        contentAlignment = if (ExportHub.wmOn) Alignment.CenterEnd
                                           else Alignment.CenterStart
                    ) {
                        Box(Modifier.size(16.dp).background(Color.White, RoundedCornerShape(8.dp)))
                    }
                }
                if (ExportHub.wmOn) {
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier.fillMaxWidth().height(26.dp)
                            .background(Ep.Panel2, RoundedCornerShape(4.dp))
                            .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (ExportHub.wmText.isEmpty()) {
                            Text("水印文字（默认 Endpix）", color = Ep.Sub, fontSize = 9.sp)
                        }
                        BasicTextField(
                            value = ExportHub.wmText,
                            onValueChange = { ExportHub.wmText = it.take(24) },
                            singleLine = true,
                            textStyle = TextStyle(color = Ep.Text, fontSize = 10.sp),
                            cursorBrush = SolidColor(Ep.Accent),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    SubSlider("透明", (ExportHub.wmAlpha / 100f).coerceIn(0f, 1f),
                        "${ExportHub.wmAlpha}%",
                        { f ->
                            val v = (f * 100f).roundToInt().coerceIn(0, 100)
                            if (v != ExportHub.wmAlpha) ExportHub.wmAlpha = v
                        }, gestureKey = ExportHub.wmAlpha) {
                        drawRect(Color(0xFF30363D))
                        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Ep.Accent)))
                    }
                    SubSlider("大小", ((ExportHub.wmSize - 8) / 88f).coerceIn(0f, 1f),
                        "${ExportHub.wmSize}px",
                        { f ->
                            val v = (8f + f * 88f).roundToInt().coerceIn(8, 96)
                            if (v != ExportHub.wmSize) ExportHub.wmSize = v
                        }, gestureKey = ExportHub.wmSize) {
                        drawRect(Color(0xFF30363D))
                        drawRect(Brush.horizontalGradient(
                            listOf(Ep.Accent.copy(alpha = 0.35f), Ep.Accent)))
                    }
                    SubSlider("密度", ((ExportHub.wmDensity - 20) / 280f).coerceIn(0f, 1f),
                        "${ExportHub.wmDensity}%",
                        { f ->
                            val v = (20f + f * 280f).roundToInt().coerceIn(20, 300)
                            if (v != ExportHub.wmDensity) ExportHub.wmDensity = v
                        }, gestureKey = ExportHub.wmDensity) {
                        drawRect(Color(0xFF30363D))
                        drawRect(Brush.horizontalGradient(
                            listOf(Ep.Accent.copy(alpha = 0.25f), Ep.Accent)))
                    }
                    if (mode == 3) {
                        Text("GIF 导出暂不支持水印（其余模式生效）",
                            color = Ep.Sub, fontSize = 8.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.width(96.dp).height(34.dp)
                            .background(if (mode == 3 && !gifOk) Ep.Panel2 else Ep.Accent, RoundedCornerShape(4.dp))
                            .clickable(interactionSource = null, indication = null) {
                                if (!(mode == 3 && !gifOk)) {
                                    ExportHub.mode = mode
                                    ExportHub.cols = cols
                                    ExportHub.delayMs = delayMs
                                    onExport(scale)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("导出", color = if (mode == 3 && !gifOk) Ep.Sub else Ep.Bg,
                            fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.width(96.dp).height(34.dp)
                            .background(Ep.Panel2, RoundedCornerShape(4.dp))
                            .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
                            .clickable(interactionSource = null, indication = null) { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) { Text("取消", color = Ep.Text, fontSize = 13.sp) }
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ExpStepBtn(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp, 28.dp).background(Ep.Panel2, RoundedCornerShape(4.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, interactionSource = null, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(text, color = if (enabled) Ep.Text else Ep.Sub, fontSize = 15.sp) }
}

@Composable
private fun RowScope.ExpModeChip(text: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).height(24.dp)
            .background(if (on) Ep.Accent else Ep.Panel2, RoundedCornerShape(3.dp))
            .border(1.dp, if (on) Ep.Accent else Ep.Line, RoundedCornerShape(3.dp))
            .clickable(enabled = enabled, interactionSource = null, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = when {
            on -> Ep.Bg
            enabled -> Ep.Text
            else -> Ep.Sub.copy(alpha = 0.55f)
        }, fontSize = 8.sp, maxLines = 1, softWrap = false)
    }
}

/**
 * 裁剪目标 → 比例正确的高分辨率预览位图。
 * 旧实现用 64×64 方形缩略图拉成裁剪宽高比, 细节丢失且采样糊;
 * 这里直接读裁剪像素(预乘 ABGR), 最长边≤1024, 最近邻采样保持像素锐利。
 */
private fun cropPreviewImage(w: Int, h: Int): ImageBitmap? {
    val px = CropExportHub.pixels ?: return null
    if (w <= 0 || h <= 0 || px.size < w * h) return null
    val maxSide = 1024
    val k = minOf(1.0, maxSide.toDouble() / maxOf(w, h))
    val pw = maxOf(1, (w * k).toInt())
    val ph = maxOf(1, (h * k).toInt())
    val argb = IntArray(pw * ph)
    for (y in 0 until ph) {
        val sy = (y.toLong() * h / ph).toInt().coerceIn(0, h - 1)
        for (x in 0 until pw) {
            val sx = (x.toLong() * w / pw).toInt().coerceIn(0, w - 1)
            val c = px[sy * w + sx]
            val a = (c ushr 24) and 255
            argb[y * pw + x] = if (a == 0) 0 else {
                val r = (((c and 255) * 255 + a / 2) / a).coerceAtMost(255)
                val g = ((((c ushr 8) and 255) * 255 + a / 2) / a).coerceAtMost(255)
                val b = ((((c ushr 16) and 255) * 255 + a / 2) / a).coerceAtMost(255)
                (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }
    return try {
        android.graphics.Bitmap.createBitmap(
            argb, pw, ph, android.graphics.Bitmap.Config.ARGB_8888
        ).asImageBitmap()
    } catch (_: Throwable) { null }
}
