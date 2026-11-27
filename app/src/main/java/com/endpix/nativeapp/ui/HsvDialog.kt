package com.endpix.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin


// 三角形几何: 内接于色环内圈（单位=内半径Rc；右/左上/左下三个顶点都在内圈上）
private fun triBary(nx: Float, ny: Float, out: FloatArray) {
    val v1x = -0.5f; val v1y = -0.8660254f   // 白（左上）
    val v2x = -0.5f; val v2y = 0.8660254f    // 黑（左下）
    val v3x = 1f;    val v3y = 0f            // 色相（右）
    val den = (v2y - v3y) * (v1x - v3x) + (v3x - v2x) * (v1y - v3y)
    out[0] = ((v2y - v3y) * (nx - v3x) + (v3x - v2x) * (ny - v3y)) / den
    out[1] = ((v3y - v1y) * (nx - v3x) + (v1x - v3x) * (ny - v3y)) / den
    out[2] = 1f - out[0] - out[1]
}
// HSV 打开时禁止切到横屏模式: 让对话框留在 MainScreen 里按横屏紧凑布局显示
object HsvHub {
    var open by mutableStateOf(false)
}

// 旧项目 HSV 对话框（MainUIController.cs:5361+）：方块/色环二选一 + H/S/V/A 滑块 + HEX + 取消/确认
@Composable
fun HsvDialog(
    current: Int, onColor: (Int) -> Unit, onClose: () -> Unit,
    startMode: Int = 0,
) {
    DisposableEffect(Unit) {
        HsvHub.open = true
        onDispose { HsvHub.open = false }
    }
    val init = remember { FloatArray(3).also { AColor.colorToHSV(current, it) } }
    var h by remember { mutableFloatStateOf(init[0]) }
    var s by remember { mutableFloatStateOf(init[1]) }
    var v by remember { mutableFloatStateOf(init[2]) }
    var a by remember { mutableFloatStateOf(((current ushr 24) and 255) / 255f) }
    var mode by remember { mutableIntStateOf(startMode.coerceIn(0, 2)) }   // 0=方块 1=色环 2=藏色（旧 _hsvMode）
    var shape by remember { mutableIntStateOf(0) }  // 0=□ 1=△（旧 _hsvWheelShape）
    var lockL by remember { mutableStateOf(true) }
    val lab0 = remember { FloatArray(3).also { LabColor.rgbToLab(current, it) } }
    var lL by remember { mutableFloatStateOf(if (lab0[0] < 8f || lab0[0] > 92f) 50f else lab0[0]) }
    var lA by remember { mutableFloatStateOf(if (lab0[0] < 8f || lab0[0] > 92f) 0f else lab0[1]) }
    var lB by remember { mutableFloatStateOf(if (lab0[0] < 8f || lab0[0] > 92f) 0f else lab0[2]) }
    val labTmp = remember { FloatArray(3) }
    val before = remember { current }
    val tmp = remember { FloatArray(3) }
    val clip = LocalClipboardManager.current
    var hexText by remember { mutableStateOf("") }
    var magOn by remember { mutableStateOf(false) }
    var magVisible by remember { mutableStateOf(false) }
    // 放大镜跟随手指: 优先放在手指上方, 贴边时自动翻到下方并夹在屏幕内
    var magX by remember { mutableFloatStateOf(0f) }
    var magY by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(magOn) {
        if (magOn) { delay(300); magVisible = true } else magVisible = false
    }

    fun argb(): Int {
        tmp[0] = h; tmp[1] = s; tmp[2] = v
        val c = AColor.HSVToColor(tmp)
        return (c and 0x00FFFFFF) or ((a * 255f).roundToInt().coerceIn(0, 255) shl 24)
    }
    val pushT = remember { longArrayOf(0L) }
    fun pushColor(c: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - pushT[0] < 40L) return
        pushT[0] = now
        onColor(c)
        hexText = String.format("%02X%02X%02X%02X",
            (c shr 16) and 255, (c shr 8) and 255, c and 255, (c ushr 24) and 255)
    }
    fun push() { pushColor(argb()) }
    // 藏色模式下改 H/S/V(或 HEX)时, 回写 Lab, 让盘面指针跟着走
    fun syncLabFromHsv() {
        if (mode != 2) return
        LabColor.rgbToLab(argb(), tmp)
        lL = tmp[0]; lA = tmp[1]; lB = tmp[2]
    }
    // 藏色盘结果(保留 A 滑块的 alpha); 同步 H/S/V 让下方滑块跟随
    fun applyLab() {
        LabColor.toRgbRaw(lL, lA, lB, labTmp)
        val rgb = LabColor.clamped(lL, lA, lB, labTmp) and 0xFFFFFF
        val c = rgb or ((a * 255f).roundToInt().coerceIn(0, 255) shl 24)
        pushColor(c)
        AColor.colorToHSV(c, tmp)
        h = tmp[0]; s = tmp[1]; v = tmp[2]
    }
    fun syncHex() {
        val c = argb()
        hexText = String.format("%02X%02X%02X%02X",
            (c shr 16) and 255, (c shr 8) and 255, c and 255, (c ushr 24) and 255)
    }
    LaunchedEffect(Unit) { syncHex() }

    fun hsvOf(c: Int) {
        AColor.colorToHSV(c, tmp)
        h = tmp[0]; s = tmp[1]; v = tmp[2]
        a = ((c ushr 24) and 255) / 255f
        syncLabFromHsv()
        onColor(argb()); syncHex()
    }

    val hue = remember(h) { Color(AColor.HSVToColor(floatArrayOf(h, 1f, 1f))) }
    val cur = argb()
    // 横屏高度只有 ~410dp: 面板限高+可滚动, 否则底部确认按钮在屏幕外
    val cfg = LocalConfiguration.current
    val panelMaxH = (cfg.screenHeightDp * 0.92f).dp

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    magX = down.position.x
                    magY = down.position.y
                    magOn = true
                    while (true) {
                        val e = awaitPointerEvent()
                        e.changes.firstOrNull { it.pressed }?.let {
                            magX = it.position.x
                            magY = it.position.y
                        }
                        if (e.changes.none { it.pressed }) break
                    }
                    magOn = false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val landscape =
            cfg.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val pickerH = if (landscape) 118.dp else 300.dp
        Column(
            Modifier.width(if (landscape) 340.dp else 360.dp).heightIn(max = panelMaxH)
                .background(Ep.Bg, RoundedCornerShape(3.dp))
                .border(1.dp, Ep.Line, RoundedCornerShape(3.dp)).padding(14.dp)
                .then(if (landscape) Modifier else Modifier.verticalScroll(rememberScrollState()))
        ) {
            // 标题(横屏把色环形状/藏色锁 L 放进标题行, 不额外占高度)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("HSV", color = Ep.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f))
                if (landscape && mode == 1) {
                    ModeChip(if (shape == 0) "□ 方块" else "△ 三角", false) { shape = 1 - shape }
                }
                if (landscape && mode == 2) {
                    ModeChip(if (lockL) "锁 L：开" else "锁 L：关", lockL) { lockL = !lockL }
                }
            }
            Spacer(Modifier.height(if (landscape) 3.dp else 8.dp))
            // 模式: 方块 / 色环（旧 _hsvMode，二选一）
            // 一级: 取色模式三选一(等分)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip("方块", mode == 0, Modifier.weight(1f)) { mode = 0 }
                ModeChip("色环", mode == 1, Modifier.weight(1f)) { mode = 1 }
                ModeChip("藏色", mode == 2, Modifier.weight(1f)) {
                    if (mode != 2) {
                        LabColor.rgbToLab(argb(), tmp)
                        var l = tmp[0]; var aa = tmp[1]; var bb = tmp[2]
                        if (l < 8f || l > 92f) { l = 50f; aa = 0f; bb = 0f }
                        lL = l; lA = aa; lB = bb
                    }
                    mode = 2
                }
            }
            // 二级: 当前模式的专属选项(横屏隐藏, 省高度)
            if (!landscape) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (mode) {
                        1 -> {
                            ModeChip(if (shape == 0) "□ 方块" else "△ 三角", false) { shape = 1 - shape }
                            Spacer(Modifier.weight(1f))
                            Text("外环=色相，内部=SV", color = Ep.Sub, fontSize = 8.sp)
                        }
                        2 -> {
                            ModeChip(if (lockL) "锁 L：开" else "锁 L：关", lockL) { lockL = !lockL }
                            Spacer(Modifier.weight(1f))
                            Text("锁 L 后只调 a/b（色相/色度）", color = Ep.Sub, fontSize = 8.sp)
                        }
                        else -> {
                            Text("左侧色相条 + 右侧 SV 方块", color = Ep.Sub, fontSize = 8.sp)
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            Spacer(Modifier.height(if (landscape) 2.dp else 6.dp))
            // 颜色对比: 放在模式行(右上角藏色按钮)下面
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("原色", color = Ep.Sub, fontSize = 9.sp)
                Spacer(Modifier.width(4.dp))
                Swatch(before, 22.dp, false) { onColor(before) }
                Spacer(Modifier.width(10.dp))
                Text("新色", color = Ep.Sub, fontSize = 9.sp)
                Spacer(Modifier.width(4.dp))
                Swatch(cur, 22.dp, false) { }
                Spacer(Modifier.weight(1f))
                Text("#" + hexText, color = Ep.Sub, fontSize = 8.sp)
            }
            Spacer(Modifier.height(if (landscape) 3.dp else 8.dp))

            // 取色区
            if (mode == 0) {
                // 旧布局: 左竖色相条 36×380 + 右独立 SV 方块 370×380
                Row(Modifier.fillMaxWidth().height(pickerH)) {
                    HueStrip(h, Modifier.width(30.dp).fillMaxHeight()) {
                        h = it; push()
                    }
                    Spacer(Modifier.width(8.dp))
                    SvSquare(s, v, hue, Modifier.weight(1f).fillMaxHeight()) { ns, nv ->
                        s = ns; v = nv; push()
                    }
                }
            } else if (mode == 2) {
                // 藏色盘(Lab): 锁 L=a/b 平面, 未锁=a/明度; 下方 L 条(旧 FxHSV 语义)
                // 位图按 4 单位量化并在后台线程生成: 拖 L 条不再卡 UI
                val dKey = if (lockL) (lL / 4f).roundToInt() * 4f else (lB / 4f).roundToInt() * 4f
                val discState = produceState<ImageBitmap?>(null, dKey, lockL) {
                    value = withContext(Dispatchers.Default) { buildDisc(lockL, dKey, lB, 224) }
                }
                val lbar = remember(lA, lB) { buildLBar(lA, lB, 192) }
                Column(Modifier.fillMaxWidth().height(pickerH)) {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        Box(
                            Modifier.align(Alignment.Center)
                                .size(if (landscape) 94.dp else 250.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(0xFF505050))
                                .pointerInput(Unit) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown()
                                        fun apply(p: Offset) {
                                            val fx = (p.x / size.width).coerceIn(0f, 1f)
                                            val fy = (p.y / size.height).coerceIn(0f, 1f)
                                            lA = -80f + fx * 160f
                                            if (lockL) lB = 80f - fy * 160f else lL = (1f - fy) * 100f
                                            applyLab()
                                        }
                                        apply(down.position)
                                        while (true) {
                                            val ev = awaitPointerEvent()
                                            val ch = ev.changes.firstOrNull { it.pressed } ?: break
                                            apply(ch.position)
                                        }
                                    }
                                }
                        ) {
                            discState.value?.let { bmp ->
                                Image(
                                    bmp, null, Modifier.fillMaxSize(),
                                    contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Low
                                )
                            }
                            Canvas(Modifier.fillMaxSize()) {
                                val px = (lA + 80f) / 160f * size.width
                                val py = if (lockL) (80f - lB) / 160f * size.height
                                else (1f - lL / 100f) * size.height
                                drawCircle(Color.White, 6.dp.toPx(), Offset(px, py), style = Stroke(2.dp.toPx()))
                                drawCircle(Color.Black, 7.5.dp.toPx(), Offset(px, py), style = Stroke(1.dp.toPx()))
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    // 和下面 H/S/V 滑块轨道同宽(左 34dp 标签位, 右 42dp 数值位)
                    Row(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.width(34.dp))
                        Box(
                            Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(2.dp))
                                .pointerInput(Unit) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    fun apply(p: Offset) {
                                        lL = (p.x / size.width).coerceIn(0f, 1f) * 100f
                                        applyLab()
                                    }
                                    apply(down.position)
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull { it.pressed } ?: break
                                        apply(ch.position)
                                    }
                                }
                            }
                    ) {
                        Image(
                            lbar, null, Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Low
                        )
                        Canvas(Modifier.fillMaxSize()) {
                            val px = (lL / 100f) * size.width
                            drawRect(Color.White, Offset(px - 1.5f, 0f), Size(3f, size.height),
                                style = Stroke(1.2.dp.toPx()))
                        }
                        }
                        Spacer(Modifier.width(42.dp))
                    }
                }
            } else {
                // 固定与方块模式同高(300dp), 切模式面板体积不变; 色环按 min(宽,高) 居中绘制
                WheelPick(h, s, v, hue, shape, Modifier.fillMaxWidth().height(pickerH)) { nh, ns, nv ->
                    h = nh; s = ns; v = nv; push()
                }
            }

            Spacer(Modifier.height(if (landscape) 2.dp else 10.dp))
            // H/S/V/A 滑块（旧: 色相/饱和/明度/透明度 + 数值）
            HsvSlider(
                "色相",
                if (mode == 2) (((Math.toDegrees(atan2(lB.toDouble(), lA.toDouble())).toFloat() + 360f) % 360f) / 360f) else h / 360f,
                if (mode == 2) "${(Math.toDegrees(atan2(lB.toDouble(), lA.toDouble())).toFloat() + 360f).mod(360f).roundToInt()}°" else "${(h).roundToInt()}°",
                compact = landscape,
                { f ->
                    if (mode == 2) {
                        val c0 = kotlin.math.sqrt(lA * lA + lB * lB)
                        val ang = f * 2f * Math.PI.toFloat()
                        lA = c0 * cos(ang); lB = c0 * sin(ang)
                        applyLab()
                    } else {
                        h = f * 360f; syncLabFromHsv(); push()
                    }
                }
            ) {
                drawRect(Brush.horizontalGradient(
                    listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan,
                        Color.Blue, Color.Magenta, Color.Red)))
            }
            HsvSlider(
                "饱和",
                if (mode == 2) (kotlin.math.sqrt(lA * lA + lB * lB) / 80f).coerceIn(0f, 1f) else s,
                if (mode == 2) "${(kotlin.math.sqrt(lA * lA + lB * lB) / 80f * 100f).roundToInt()}%" else "${(s * 100).roundToInt()}%",
                compact = landscape,
                { f ->
                    if (mode == 2) {
                        val c0 = f * 80f
                        val ang = atan2(lB, lA)
                        lA = c0 * cos(ang); lB = c0 * sin(ang)
                        applyLab()
                    } else {
                        s = f; syncLabFromHsv(); push()
                    }
                }
            ) {
                drawRect(Brush.horizontalGradient(
                    listOf(Color(AColor.HSVToColor(floatArrayOf(h, 0f, v))),
                        Color(AColor.HSVToColor(floatArrayOf(h, 1f, v))))))
            }
            HsvSlider(
                "明度",
                if (mode == 2) lL / 100f else v,
                if (mode == 2) "${lL.roundToInt()}%" else "${(v * 100).roundToInt()}%",
                compact = landscape,
                { f ->
                    if (mode == 2) {
                        lL = f * 100f
                        if (lL < 0.5f) lL = 0.5f
                        if (lL > 99.5f) lL = 99.5f
                        applyLab()
                    } else {
                        v = f; syncLabFromHsv(); push()
                    }
                }
            ) {
                drawRect(Brush.horizontalGradient(
                    listOf(Color.Black, Color(AColor.HSVToColor(floatArrayOf(h, s, 1f))))))
            }
            HsvSlider("透明", a, "${(a * 100).roundToInt()}%",
                compact = landscape,
                { a = it; push() }) {
                // 整齐的棋盘: 7dp 格(14dp 条高正好两行), 从左上对齐, 右/下由父级 clip 裁齐
                val cell = 7.dp.toPx()
                drawRect(Color.White)
                val cols = (size.width / cell).toInt() + 1
                val rows = (size.height / cell).toInt() + 1
                for (row in 0 until rows) {
                    for (col in 0 until cols) {
                        if (((row + col) and 1) == 0) {
                            drawRect(
                                Color(0xFFCCCCCC),
                                Offset(col * cell, row * cell),
                                Size(cell, cell)
                            )
                        }
                    }
                }
                drawRect(Brush.horizontalGradient(
                    listOf(Color.Transparent, Color(AColor.HSVToColor(floatArrayOf(h, s, v))))))
            }

            Spacer(Modifier.height(if (landscape) 2.dp else 10.dp))
            // HEX + 填入/复制
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.weight(1f).height(if (landscape) 24.dp else 28.dp).background(Ep.Panel2, RoundedCornerShape(3.dp))
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = hexText,
                        onValueChange = { t ->
                            hexText = t.uppercase().filter { it.isDigit() || it in 'A'..'F' }.take(8)
                            val t2 = hexText
                            if (t2.length == 6 || t2.length == 8) {
                                val r = t2.substring(0, 2).toIntOrNull(16) ?: return@BasicTextField
                                val g = t2.substring(2, 4).toIntOrNull(16) ?: return@BasicTextField
                                val b = t2.substring(4, 6).toIntOrNull(16) ?: return@BasicTextField
                                val al = if (t2.length == 8) t2.substring(6, 8).toIntOrNull(16) ?: 255 else 255
                                hsvOf((al shl 24) or (r shl 16) or (g shl 8) or b)
                            }
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = Ep.Text, fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                        cursorBrush = SolidColor(Ep.Accent),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.width(6.dp))
                SmallBtn("填入") {
                    val t = clip.getText()?.text?.trim()?.removePrefix("#") ?: return@SmallBtn
                    val t2 = t.uppercase().filter { it.isDigit() || it in 'A'..'F' }
                    if (t2.length >= 6) {
                        val r = t2.substring(0, 2).toIntOrNull(16) ?: return@SmallBtn
                        val g = t2.substring(2, 4).toIntOrNull(16) ?: return@SmallBtn
                        val b = t2.substring(4, 6).toIntOrNull(16) ?: return@SmallBtn
                        val al = if (t2.length >= 8) t2.substring(6, 8).toIntOrNull(16) ?: 255 else 255
                        hsvOf((al shl 24) or (r shl 16) or (g shl 8) or b)
                    }
                }
                Spacer(Modifier.width(4.dp))
                SmallBtn("复制") {
                    clip.setText(androidx.compose.ui.text.AnnotatedString("#" + hexText))
                }
            }

            Spacer(Modifier.height(if (landscape) 2.dp else 10.dp))
            // 确认 / 取消（主人要求：确认在左，取消在右）
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("确认", Ep.Accent, Ep.Bg, Modifier.weight(1f), compact = landscape) {
                    onColor(argb())
                    onClose()
                }
                Btn("取消", Ep.Panel2, Ep.Text, Modifier.weight(1f), compact = landscape) {
                    onColor(before)
                    onClose()
                }
            }
        }
        LoupeLayer(
            visible = magVisible,
            pos = { Offset(magX, magY) },
            h = h, s = s, v = v
        )
    }
}

// 放大镜覆盖层: 位置只在 layout 阶段读取, 拖动时不触发整块 HSV 重组, 跟随更顺
@Composable
private fun LoupeLayer(
    visible: Boolean,
    pos: () -> Offset,
    h: Float, s: Float, v: Float,
) {
    if (!visible) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val loupePx = with(density) { 96.dp.roundToPx() }
        val gapPx = with(density) { 40.dp.roundToPx() }
        val margin = with(density) { 8.dp.roundToPx() }
        val maxW = with(density) { maxWidth.roundToPx() }
        val maxH = with(density) { maxHeight.roundToPx() }
        HsvLoupe(
            h, s, v,
            Modifier.align(Alignment.TopStart).offset {
                val p = pos()
                val maxX = (maxW - loupePx - margin).coerceAtLeast(margin)
                val maxY = (maxH - loupePx - margin).coerceAtLeast(margin)
                val x = (p.x - loupePx / 2f).roundToInt().coerceIn(margin, maxX)
                var y = (p.y - loupePx - gapPx).roundToInt()   // 手指上方 40dp
                if (y < margin) y = (p.y + gapPx).roundToInt()  // 顶部放不下翻到下方
                y = y.coerceIn(margin, maxY)
                androidx.compose.ui.unit.IntOffset(x, y)
            }
        )
    }
}

// HSV 放大镜: 当前颜色周围的 H/S/V 邻域色块 + 中心准心(旧 MagWindow 的本地化实现)
@Composable
private fun HsvLoupe(h: Float, s: Float, v: Float, modifier: Modifier) {
    Column(
        modifier.size(96.dp).background(Ep.Panel, RoundedCornerShape(4.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(4.dp)).padding(3.dp)
    ) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val n = 9
            val cw = size.width / n
            val ch = size.height / n
            for (yy in 0 until n) for (xx in 0 until n) {
                val hh = (h + (xx - 4) * 4f + 360f) % 360f
                val ss = (s + (yy - 4) * 0.05f).coerceIn(0f, 1f)
                val c = Color(AColor.HSVToColor(floatArrayOf(hh, ss, v)))
                drawRect(c, Offset(xx * cw, yy * ch), Size(cw, ch))
            }
            val cx = 4 * cw
            val cy = 4 * ch
            drawRect(Color.White, Offset(cx, cy), Size(cw, ch), style = Stroke(2f))
            drawRect(Color.Black, Offset(cx - 1, cy - 1), Size(cw + 2, ch + 2), style = Stroke(1f))
        }
        Text("H${h.roundToInt()} S${(s * 100).roundToInt()} V${(v * 100).roundToInt()}",
            color = Ep.Sub, fontSize = 7.5.sp, maxLines = 1)
    }
}

@Composable
private fun ModeChip(
    label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    Box(
        modifier.height(24.dp)
            .background(if (on) Ep.Accent else Ep.Panel2, RoundedCornerShape(3.dp))
            .clickable(interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (on) Ep.Bg else Ep.Text, fontSize = 10.sp)
    }
}

@Composable
private fun SmallBtn(label: String, onClick: () -> Unit) {
    Box(
        Modifier.height(28.dp).width(46.dp).background(Ep.Panel2, RoundedCornerShape(3.dp))
            .clickable(interactionSource = null, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(label, color = Ep.Text, fontSize = 9.sp) }
}

@Composable
private fun Btn(
    label: String, bg: Color, fg: Color, modifier: Modifier,
    compact: Boolean = false, onClick: () -> Unit,
) {
    Box(
        modifier.height(if (compact) 32.dp else 40.dp).background(bg, RoundedCornerShape(3.dp))
            .clickable(interactionSource = null, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(label, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium) }
}

// 竖色相条（方块模式，旧 36×380）
@Composable
private fun HueStrip(h: Float, modifier: Modifier, onChange: (Float) -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(2.dp))
            .background(
                Brush.verticalGradient(
                    listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan,
                        Color.Blue, Color.Magenta, Color.Red)
                )
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun apply(p: Offset) {
                        val f = (p.y / size.height).coerceIn(0f, 0.9999f)
                        onChange(f * 360f)
                    }
                    apply(down.position)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.pressed } ?: break
                        apply(ch.position)
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val py = (h / 360f) * size.height
            drawRect(Color.White, Offset(0f, py - 1.5f), Size(size.width, 3f),
                style = Stroke(1.2.dp.toPx()))
        }
    }
}

// 独立 SV 方块（方块模式，旧 370×380）
@Composable
private fun SvSquare(
    s: Float, v: Float, hue: Color, modifier: Modifier,
    onChange: (Float, Float) -> Unit,
) {
    Box(
        modifier.clip(RoundedCornerShape(2.dp))
            .background(Brush.horizontalGradient(listOf(Color.White, hue)))
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun apply(p: Offset) {
                        onChange(
                            (p.x / size.width).coerceIn(0f, 1f),
                            1f - (p.y / size.height).coerceIn(0f, 1f)
                        )
                    }
                    apply(down.position)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.pressed } ?: break
                        apply(ch.position)
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val px = s * size.width
            val py = (1f - v) * size.height
            drawCircle(Color.White, 6.dp.toPx(), Offset(px, py), style = Stroke(2.dp.toPx()))
            drawCircle(Color.Black, 7.5.dp.toPx(), Offset(px, py), style = Stroke(1.dp.toPx()))
        }
    }
}

// 色环模式（旧: 外环角度=H，内部 SV 方块；按下锁定区域，移动全程跟手）
@Composable
private fun WheelPick(
    h: Float, s: Float, v: Float, hue: Color, shape: Int, modifier: Modifier,
    onChange: (Float, Float, Float) -> Unit,
) {
    val ringW = 18.dp
    // pointerInput(Unit) 只创建一次: 必须读最新 h/s/v, 否则拖环会把 s/v 重置成打开时的值
    val hNow = rememberUpdatedState(h)
    val sNow = rememberUpdatedState(s)
    val vNow = rememberUpdatedState(v)
    val tmpW = FloatArray(3)
    Box(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                var zone = -1   // 0未定 1外环 2内部（旧 wZone）
                fun apply(p: Offset) {
                    val w = size.width.toFloat()
                    val hh = size.height.toFloat()
                    val cx = w / 2f
                    val cy = hh / 2f
                    val outerR = min(w, hh) / 2f
                    val dx = p.x - cx
                    val dy = p.y - cy
                    val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    if (zone < 0) zone = if (dist > outerR - ringW.toPx()) 1 else 2
                    if (zone == 1) {
                        val ang = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                        onChange((ang + 360f) % 360f, sNow.value, vNow.value)
                    } else if (shape == 0) {
                        val half = 173.dp.toPx() / 2f
                        val nx = dx.coerceIn(-half, half)
                        val ny = dy.coerceIn(-half, half)
                        onChange(hNow.value, nx / half * 0.5f + 0.5f, 1f - (ny / half * 0.5f + 0.5f))
                    } else {
                        val rc = outerR - ringW.toPx()   // 色环内半径
                        triBary(dx / rc, dy / rc, tmpW)
                        val a = tmpW[0].coerceIn(0f, 1f)
                        val b = tmpW[1].coerceIn(0f, 1f)
                        val c = (1f - a - b).coerceIn(0f, 1f)
                        val vv = (a + c).coerceIn(0f, 1f)
                        val ss = if (vv > 0.001f) (c / vv).coerceIn(0f, 1f) else 0f
                        onChange(hNow.value, ss, vv)
                    }
                }
                apply(down.position)
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.pressed } ?: break
                    apply(ch.position)
                }
            }
        }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val rad = size.minDimension / 2f - ringW.toPx() / 2f
            drawCircle(
                Brush.sweepGradient(
                    listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan,
                        Color.Blue, Color.Magenta, Color.Red)
                ),
                rad, Offset(size.width / 2f, size.height / 2f), style = Stroke(ringW.toPx())
            )
            val ang = Math.toRadians(h.toDouble())
            drawCircle(
                Color.White, 5.dp.toPx(),
                Offset(size.width / 2f + (rad * cos(ang)).toFloat(),
                    size.height / 2f + (rad * sin(ang)).toFloat()),
                style = Stroke(2.dp.toPx())
            )
        }
        // 内部显示（交互统一交给上面的手势）
        if (shape == 0) {
            Box(
                Modifier.align(Alignment.Center).size(173.dp).clip(RoundedCornerShape(2.dp))
                    .background(Brush.horizontalGradient(listOf(Color.White, hue)))
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val px = s * size.width
                    val py = (1f - v) * size.height
                    drawCircle(Color.White, 6.dp.toPx(), Offset(px, py), style = Stroke(2.dp.toPx()))
                    drawCircle(Color.Black, 7.5.dp.toPx(), Offset(px, py), style = Stroke(1.dp.toPx()))
                }
            }
        } else {
            // 色相按 2° 量化: 拖动时贴图只在跨 2° 时重建, 避免每个触摸事件重算 256²
            val triHue = (h / 2f).roundToInt() * 2f
            val tri = remember(triHue) {
                buildTri(AColor.HSVToColor(floatArrayOf(triHue % 360f, 1f, 1f)), 256)
            }
            // 三角形外接圆 = 色环内圈（300dp 高 - 2*18dp 环宽 → 内径 264dp）
            Box(Modifier.align(Alignment.Center).size(264.dp)) {
                androidx.compose.foundation.Image(
                    tri, null, Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Low
                )
                Canvas(Modifier.fillMaxSize()) {
                    // size = 2Rc，归一化坐标 [-1,1]
                    val v1x = -0.5f; val v1y = -0.8660254f
                    val v2x = -0.5f; val v2y = 0.8660254f
                    val v3x = 1f;    val v3y = 0f
                    val c = s * v
                    val a = (v - c).coerceIn(0f, 1f)
                    val b = (1f - a - c).coerceIn(0f, 1f)
                    val px = (a * v1x + b * v2x + c * v3x) * size.width / 2f + size.width / 2f
                    val py = (a * v1y + b * v2y + c * v3y) * size.height / 2f + size.height / 2f
                    drawCircle(Color.White, 6.dp.toPx(), Offset(px, py), style = Stroke(2.dp.toPx()))
                    drawCircle(Color.Black, 7.5.dp.toPx(), Offset(px, py), style = Stroke(1.dp.toPx()))
                }
            }
        }
    }
}

@Composable
private fun HsvSlider(
    label: String, value: Float, valueText: String,
    compact: Boolean = false,
    onChange: (Float) -> Unit, track: DrawScope.() -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(if (compact) 18.dp else 26.dp)) {
        Text(label, color = Ep.Sub, fontSize = if (compact) 8.sp else 9.sp,
            modifier = Modifier.width(if (compact) 30.dp else 34.dp))
        Box(
            Modifier.weight(1f).height(if (compact) 8.dp else 14.dp).clip(RoundedCornerShape(2.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun apply(p: Offset) {
                            onChange((p.x / size.width).coerceIn(0f, 1f))
                        }
                        apply(down.position)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.pressed } ?: break
                            apply(ch.position)
                        }
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                track()
                val px = value * size.width
                drawRect(Color.White, Offset(px - 1.5f, 0f), Size(3f, size.height),
                    style = Stroke(1.2.dp.toPx()))
            }
        }
        Text(valueText, color = Ep.Accent, fontSize = if (compact) 8.sp else 9.sp,
            textAlign = TextAlign.End,
            modifier = Modifier.width(if (compact) 38.dp else 42.dp))
    }
}

// 等边三角形取色图（旧 UpdateWheelInnerTexture 的语义）: 左上白 / 左下黑 / 右中纯色相
private fun buildTri(hueInt: Int, n: Int): ImageBitmap {
    val px = IntArray(n * n)
    val hr = (hueInt shr 16) and 255
    val hg = (hueInt shr 8) and 255
    val hb = hueInt and 255
    val w = FloatArray(3)
    val edgeScale = n * 0.75f   // 1 个重心单位 = 三角形高度 1.5Rc，占整图 2Rc 的 3/4
    for (y in 0 until n) {
        val ny = (y + 0.5f) / n * 2f - 1f
        for (x in 0 until n) {
            val nx = (x + 0.5f) / n * 2f - 1f
            triBary(nx, ny, w)
            val mn = min(w[0], min(w[1], w[2]))
            val cov = (mn * edgeScale + 0.5f).coerceIn(0f, 1f)
            if (cov <= 0f) continue
            val a = w[0].coerceAtLeast(0f)
            val c = w[2].coerceAtLeast(0f)
            val r = (a * 255f + c * hr).toInt().coerceIn(0, 255)
            val g = (a * 255f + c * hg).toInt().coerceIn(0, 255)
            val bb = (a * 255f + c * hb).toInt().coerceIn(0, 255)
            px[y * n + x] = ((cov * 255f).toInt() shl 24) or (r shl 16) or (g shl 8) or bb
        }
    }
    return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
}
