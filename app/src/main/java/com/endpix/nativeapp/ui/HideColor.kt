package com.endpix.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

// CIELab: 旧 MainUIController.FxHSV 的 HcRgbToLab / HcLabToRgbRaw / HcInGamut 原样搬
object LabColor {
    const val AB = 80f
    private fun s2l(c: Float) = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    private fun l2s(c: Float) = if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f
    private fun f(t: Float): Float {
        // 阈值判断的是 t 本身(标准 CIELab); 旧项目写成 t³ 会让暗色/饱和色正变换走线性分支,
        // 正逆不再互逆(品红算出 chroma 197, 滑块一动颜色就跳), 这里修正
        return if (t > 0.008856f) t.pow(1f / 3f) else 7.787f * t + 16f / 116f
    }
    private fun fi(t: Float): Float {
        val t3 = t * t * t
        return if (t3 > 0.008856f) t3 else (t - 16f / 116f) / 7.787f
    }

    fun rgbToLab(argb: Int, out: FloatArray) {
        val r = s2l(((argb shr 16) and 255) / 255f)
        val g = s2l(((argb shr 8) and 255) / 255f)
        val b = s2l((argb and 255) / 255f)
        val X = (0.4124f * r + 0.3576f * g + 0.1805f * b) * 100f
        val Y = (0.2126f * r + 0.7152f * g + 0.0722f * b) * 100f
        val Z = (0.0193f * r + 0.1192f * g + 0.9505f * b) * 100f
        val fx = f(X / 95.047f)
        val fy = f(Y / 100f)
        val fz = f(Z / 108.883f)
        out[0] = 116f * fy - 16f
        out[1] = 500f * (fx - fy)
        out[2] = 200f * (fy - fz)
    }

    // 线性 sRGB 0..1（未裁剪），out 3 个
    fun toRgbRaw(L: Float, a: Float, b: Float, out: FloatArray) {
        val fy = (L + 16f) / 116f
        val fx = fy + a / 500f
        val fz = fy - b / 200f
        val X = fi(fx) * 0.95047f
        val Y = fi(fy)
        val Z = fi(fz) * 1.08883f
        out[0] = l2s(3.2406f * X - 1.5372f * Y - 0.4986f * Z)
        out[1] = l2s(-0.9689f * X + 1.8758f * Y + 0.0415f * Z)
        out[2] = l2s(0.0557f * X - 0.2040f * Y + 1.0570f * Z)
    }

    // 三重 gamut（旧 HcInGamut）
    fun inGamut(L: Float, a: Float, b: Float, rgb: FloatArray): Boolean {
        val r = rgb[0] * 255f
        val g = rgb[1] * 255f
        val bl = rgb[2] * 255f
        if (r < 0.5f || r > 254.5f || g < 0.5f || g > 254.5f || bl < 0.5f || bl > 254.5f) return false
        val C = 1f - r / 255f
        val M = 1f - g / 255f
        val Y2 = 1f - bl / 255f
        val K = 0.65f * min(C, min(M, Y2))
        if (K >= 1f) return false
        val inv = 1f / (1f - K)
        val cp = (C - K) * inv
        val mp = (M - K) * inv
        val yp = (Y2 - K) * inv
        val chans = (if (cp >= 0.02f) 1 else 0) + (if (mp >= 0.02f) 1 else 0) + (if (yp >= 0.02f) 1 else 0)
        if (chans < 2) return false
        val chroma = sqrt(a * a + b * b)
        if (chroma > 55f) {
            val lf = 1f - abs(L - 50f) / 50f
            if (chroma > 55f + 35f * max(0f, lf)) return false
        }
        return true
    }

    fun clamped(L: Float, a: Float, b: Float, rgb: FloatArray): Int {
        val r = (rgb[0] * 255f).roundToInt().coerceIn(0, 255)
        val g = (rgb[1] * 255f).roundToInt().coerceIn(0, 255)
        val bl = (rgb[2] * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }
}

internal fun buildDisc(lockedL: Boolean, L: Float, B: Float, n: Int): ImageBitmap {
    val px = IntArray(n * n)
    val rgb = FloatArray(3)
    for (y in 0 until n) {
        val fy = (y + 0.5f) / n
        for (x in 0 until n) {
            val fx = (x + 0.5f) / n
            val aa = -LabColor.AB + fx * LabColor.AB * 2f
            val l = if (lockedL) L else fy * 100f
            val b = if (lockedL) LabColor.AB - fy * LabColor.AB * 2f else B
            LabColor.toRgbRaw(l, aa, b, rgb)
            px[y * n + x] = if (LabColor.inGamut(l, aa, b, rgb)) {
                // 边界软边: 通道离 0/1 越近 alpha 越低(配合盘底灰色背景, 消 gamut 锯齿)
                val d = min(min(rgb[0], 1f - rgb[0]), min(min(rgb[1], 1f - rgb[1]), min(rgb[2], 1f - rgb[2])))
                val cov = (d * n * 2.0f).coerceIn(0f, 1f)
                ((cov * 255f).toInt() shl 24) or (LabColor.clamped(l, aa, b, rgb) and 0xFFFFFF)
            } else 0
        }
    }
    return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
}

internal fun buildLBar(A: Float, B: Float, n: Int): ImageBitmap {
    val px = IntArray(n)
    val rgb = FloatArray(3)
    for (x in 0 until n) {
        val L = (x + 0.5f) / n * 100f
        LabColor.toRgbRaw(L, A, B, rgb)
        px[x] = if (LabColor.inGamut(L, A, B, rgb)) LabColor.clamped(L, A, B, rgb)
        else 0xFF505050.toInt()
    }
    return Bitmap.createBitmap(px, n, 1, Bitmap.Config.ARGB_8888).asImageBitmap()
}

// 旧项目“藏色”窗口：藏色盘(Lab) ↔ 色轮(环+SV)，带锁 L / 对比色 / 十六进制
@Composable
fun HideColorPanel(
    current: Int, onColor: (Int) -> Unit, onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var mode by remember { mutableIntStateOf(0) }   // 旧 _hcUiMode 默认 0=藏色盘(Lab)
    var lockL by remember { mutableStateOf(true) }
    val initLab = remember {
        FloatArray(3).also {
            LabColor.rgbToLab(current, it)
            if (it[0] < 8f || it[0] > 92f) { it[0] = 50f; it[1] = 0f; it[2] = 0f }
        }
    }
    var L by remember { mutableFloatStateOf(initLab[0]) }
    var A by remember { mutableFloatStateOf(initLab[1]) }
    var B by remember { mutableFloatStateOf(initLab[2]) }
    val initHsv = remember { FloatArray(3).also { AColor.colorToHSV(current, it) } }
    var h by remember { mutableFloatStateOf(initHsv[0]) }
    var s by remember { mutableFloatStateOf(initHsv[1]) }
    var v by remember { mutableFloatStateOf(initHsv[2]) }
    val before = remember { current }
    val tmp = remember { FloatArray(3) }

    fun labInt(): Int {
        LabColor.toRgbRaw(L, A, B, tmp)
        return LabColor.clamped(L, A, B, tmp)
    }
    fun hsvInt(): Int {
        tmp[0] = h; tmp[1] = s; tmp[2] = v
        return AColor.HSVToColor(tmp) or 0xFF000000.toInt()
    }
    fun emit() { onColor(if (mode == 0) labInt() else hsvInt()) }
    fun switchMode(m: Int) {
        if (m == mode) return
        if (m == 0) {
            LabColor.rgbToLab(hsvInt(), tmp)
            var l = tmp[0]; var aa = tmp[1]; var bb = tmp[2]
            if (l < 8f || l > 92f) { l = 50f; aa = 0f; bb = 0f }
            L = l; A = aa; B = bb
        } else {
            AColor.colorToHSV(labInt(), tmp)
            h = tmp[0]; s = tmp[1]; v = tmp[2]
        }
        mode = m
        emit()
    }

    val discKey = if (lockL) L else B
    val disc = remember(discKey, lockL) { buildDisc(lockL, L, B, 96) }
    val lbar = remember(A, B) { buildLBar(A, B, 192) }
    val hueColor = remember(h) { Color(AColor.HSVToColor(floatArrayOf(h, 1f, 1f))) }
    val cur = if (mode == 0) labInt() else hsvInt()

    Column(
        modifier.width(248.dp).background(Ep.Panel, RoundedCornerShape(3.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(3.dp)).padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("藏色", color = Ep.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f))
            Text(hexOf(cur), color = Ep.Sub, fontSize = 9.sp, modifier = Modifier.padding(end = 8.dp))
            Box(Modifier.size(22.dp).clickable(interactionSource = null, indication = null) { onClose() },
                contentAlignment = Alignment.Center) { Text("×", color = Ep.Sub, fontSize = 15.sp) }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.height(22.dp).background(Ep.Panel2, RoundedCornerShape(3.dp))
                    .clickable(interactionSource = null, indication = null) {
                        switchMode(if (mode == 0) 1 else 0)
                    }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(if (mode == 0) "藏色盘" else "色环", color = Ep.Text, fontSize = 9.sp)
            }
            Spacer(Modifier.width(6.dp))
            if (mode == 0) {
                Box(
                    Modifier.height(22.dp)
                        .background(if (lockL) Ep.Accent else Ep.Panel2, RoundedCornerShape(3.dp))
                        .clickable(interactionSource = null, indication = null) { lockL = !lockL }
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("锁 L", color = if (lockL) Ep.Bg else Ep.Sub, fontSize = 9.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Swatch(before, 22.dp, false) { onColor(before) }
            Spacer(Modifier.width(4.dp))
            Swatch(cur, 22.dp, false) { }
        }
        Spacer(Modifier.height(8.dp))

        if (mode == 0) {
            // 藏色盘: 锁 L = a(横)/b(纵) @ 固定 L; 不锁 = a(横)/L(纵) @ 固定 b
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(2.dp))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            fun apply(p: Offset) {
                                val fx = (p.x / size.width).coerceIn(0f, 1f)
                                val fy = (p.y / size.height).coerceIn(0f, 1f)
                                A = -LabColor.AB + fx * LabColor.AB * 2f
                                if (lockL) B = LabColor.AB - fy * LabColor.AB * 2f
                                else L = (1f - fy) * 100f
                                emit()
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
                    disc, null, Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Low
                )
                Canvas(Modifier.fillMaxSize()) {
                    val px = if (lockL) (A + LabColor.AB) / (LabColor.AB * 2f) * size.width
                    else (A + LabColor.AB) / (LabColor.AB * 2f) * size.width
                    val py = if (lockL) (LabColor.AB - B) / (LabColor.AB * 2f) * size.height
                    else (1f - L / 100f) * size.height
                    drawCircle(Color.White, 6.dp.toPx(), Offset(px, py), style = Stroke(2.dp.toPx()))
                    drawCircle(Color.Black, 7.5.dp.toPx(), Offset(px, py), style = Stroke(1.dp.toPx()))
                }
            }
            Spacer(Modifier.height(6.dp))
            // L 条
            Box(
                Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(2.dp))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            fun apply(p: Offset) {
                                L = (p.x / size.width).coerceIn(0f, 1f) * 100f
                                if (L < 0.5f) L = 0.5f
                                if (L > 99.5f) L = 99.5f
                                emit()
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
                    val px = (L / 100f) * size.width
                    drawRect(Color.White, Offset(px - 1.5f, 0f), Size(3f, size.height),
                        style = Stroke(1.2.dp.toPx()))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (lockL) "锁 L：拖盘只改色相/色度" else "未锁：盘=明度×色度，L 条联动",
                color = Ep.Sub, fontSize = 8.sp
            )
        } else {
            // 色环与 SV 方块分开(不叠加): 上=色相环(角度选 H), 下=独立 SV 方块
            val ringW = 16.dp
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun apply(p: Offset) {
                            val w = size.width.toFloat()
                            val hh = size.height.toFloat()
                            val cx = w / 2f
                            val cy = hh / 2f
                            // 只响应色环带(避免点中心随机改色相)
                            val rad = min(w, hh) / 2f - ringW.toPx() / 2f
                            val dx = p.x - cx
                            val dy = p.y - cy
                            val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
                            if (abs(dist - rad) > ringW.toPx()) return
                            val ang = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                            h = (ang + 360f) % 360f
                            emit()
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
                            listOf(
                                Color.Red, Color.Yellow, Color.Green, Color.Cyan,
                                Color.Blue, Color.Magenta, Color.Red
                            )
                        ),
                        rad, Offset(size.width / 2f, size.height / 2f), style = Stroke(ringW.toPx())
                    )
                    val ang = Math.toRadians(h.toDouble())
                    val cx = size.width / 2f + (rad * cos(ang)).toFloat()
                    val cy = size.height / 2f + (rad * sin(ang)).toFloat()
                    drawCircle(Color.White, 5.dp.toPx(), Offset(cx, cy), style = Stroke(2.dp.toPx()))
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(2.dp))
                    .background(Brush.horizontalGradient(listOf(Color.White, hueColor)))
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            fun apply(p: Offset) {
                                s = (p.x / size.width).coerceIn(0f, 1f)
                                v = 1f - (p.y / size.height).coerceIn(0f, 1f)
                                emit()
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
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (mode == 0) "藏色盘" else "色环", color = Ep.Sub, fontSize = 9.sp)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.height(24.dp).background(Ep.Panel2, RoundedCornerShape(3.dp))
                    .clickable(interactionSource = null, indication = null) { onClose() }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) { Text("完成", color = Ep.Accent, fontSize = 10.sp) }
        }
    }
}

private fun hexOf(c: Int): String =
    "#" + Integer.toHexString(c and 0xFFFFFF).uppercase().padStart(6, '0')
