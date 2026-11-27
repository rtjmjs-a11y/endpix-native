package com.endpix.nativeapp.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

// 色阶: RGB / R / G / B 四通道, 每通道独立输入黑白场、gamma、输出黑白场。
// 交互按 Photoshop Levels: 直方图下方三枚输入滑块(黑场/中间调/白场) + 输出黑白滑块,
// 直接在图上拖动/点按, 不再用五根独立滑杆。
@Composable
fun LevelsDialog(
    onHistogram: (Int) -> IntArray,
    onPreview: (IntArray?) -> Unit,
    onApplyAll: (IntArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var channel by remember { mutableIntStateOf(0) }   // 0=RGB 1=R 2=G 3=B
    val hist by produceState<IntArray?>(null, channel) {
        value = withContext(Dispatchers.Default) {
            try { onHistogram(channel) } catch (_: Throwable) { IntArray(256) }
        }
    }
    val blacks = remember { mutableStateListOf(0, 0, 0, 0) }
    val whites = remember { mutableStateListOf(255, 255, 255, 255) }
    val gammas = remember { mutableStateListOf(1f, 1f, 1f, 1f) }
    val outBlacks = remember { mutableStateListOf(0, 0, 0, 0) }
    val outWhites = remember { mutableStateListOf(255, 255, 255, 255) }
    val chNames = listOf("RGB", "R", "G", "B")
    val chColors = listOf(Ep.Text, Color(0xFFFF6B6B), Color(0xFF6BE38A), Color(0xFF6BA8FF))

    fun buildLuts(): IntArray {
        val out = IntArray(1024)
        for (ch in 0..3) {
            val b = blacks[ch]; val w = whites[ch]
            val gm = gammas[ch]; val ob = outBlacks[ch]; val ow = outWhites[ch]
            val den = (w - b).toFloat().coerceAtLeast(1f)
            val invG = 1f / gm.coerceAtLeast(0.05f)
            for (i in 0..255) {
                var t = (i - b) / den
                t = t.coerceIn(0f, 1f)
                val v = t.toDouble().pow(invG.toDouble()).toFloat()
                out[ch * 256 + i] = (ob + (v * (ow - ob)).roundToInt()).coerceIn(0, 255)
            }
        }
        return out
    }

    LaunchedEffect(blacks.toList(), whites.toList(), gammas.toList(), outBlacks.toList(), outWhites.toList()) {
        onPreview(buildLuts())
    }
    DisposableEffect(Unit) {
        onDispose { onPreview(null) }
    }

    fun setInput(handle: Int, vRaw: Int) {
        val v = vRaw.coerceIn(0, 255)
        when (handle) {
            0 -> blacks[channel] = v.coerceIn(0, whites[channel] - 1)
            2 -> whites[channel] = v.coerceIn(blacks[channel] + 1, 255)
            else -> {
                val b = blacks[channel]; val w = whites[channel]
                if (w - b < 2) return
                val t = ((v - b).toFloat() / (w - b)).coerceIn(0.01f, 0.99f)
                // 中间调位置 = 输入值使输出落在 0.5: t = 0.5^gamma
                gammas[channel] = (ln(t.toDouble()) / ln(0.5)).toFloat().coerceIn(0.1f, 9.99f)
            }
        }
    }
    fun setOutput(handle: Int, vRaw: Int) {
        val v = vRaw.coerceIn(0, 255)
        if (handle == 0) outBlacks[channel] = v.coerceIn(0, outWhites[channel] - 1)
        else outWhites[channel] = v.coerceIn(outBlacks[channel] + 1, 255)
    }

    // 注意: 必须做同窗口 overlay, 不能用 Compose Dialog(独立窗口会让底层 SurfaceView 冻结, 预览看不到)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
            .clickable(interactionSource = null, indication = null) { onDismiss() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            Modifier.width(304.dp).padding(bottom = 78.dp)
                .background(Ep.Panel, RoundedCornerShape(6.dp))
                .border(1.dp, Ep.Line, RoundedCornerShape(6.dp)).padding(12.dp)
                .clickable(interactionSource = null, indication = null) { }
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("色阶", color = Ep.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("当前图层 · 可撤销", color = Ep.Sub, fontSize = 8.sp)
                }
                Box(
                    Modifier.size(24.dp).background(Ep.Panel2, RoundedCornerShape(4.dp))
                        .clickable(interactionSource = null, indication = null) { onDismiss() },
                    contentAlignment = Alignment.Center
                ) { Text("✕", color = Ep.Sub, fontSize = 12.sp) }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                chNames.forEachIndexed { i, name ->
                    val on = channel == i
                    Box(
                        Modifier.weight(1f).height(26.dp)
                            .background(if (on) Ep.HighlightSel else Ep.Panel2, RoundedCornerShape(3.dp))
                            .border(1.dp, if (on) Ep.Accent else Ep.Line, RoundedCornerShape(3.dp))
                            .clickable(interactionSource = null, indication = null) { channel = i },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(name, color = if (on) Color.Black else chColors[i], fontSize = 9.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            val histPad = 8.dp
            val histTop = 4.dp
            val histBottom = 92.dp
            val inTop = 96.dp
            val inBottom = 110.dp
            val outTop = 124.dp
            val outBottom = 138.dp
            Canvas(
                Modifier.fillMaxWidth().height(156.dp)
                    .background(Ep.Bg, RoundedCornerShape(4.dp))
                    .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
                    .pointerInput(channel) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val pad = histPad.toPx()
                            val plotW = (size.width - pad * 2).coerceAtLeast(1f)
                            fun valueAt(x: Float) =
                                ((x - pad) / plotW * 255f).roundToInt().coerceIn(0, 255)
                            val y = down.position.y
                            val outputGroup = y >= (outTop - 2.dp).toPx() && y <= (outBottom + 18.dp).toPx()
                            var sel = 0
                            val v0 = valueAt(down.position.x)
                            if (outputGroup) {
                                sel = if (abs(v0 - outBlacks[channel]) <= abs(v0 - outWhites[channel])) 0 else 1
                                setOutput(sel, v0)
                            } else {
                                val b = blacks[channel]; val w = whites[channel]
                                val mid = b + (w - b) * 0.5f.pow(gammas[channel])
                                val xs = floatArrayOf(b.toFloat(), mid, w.toFloat())
                                sel = xs.indices.minByOrNull { abs(xs[it] - v0) } ?: 0
                                setInput(sel, v0)
                            }
                            down.consume()
                            while (true) {
                                val e = awaitPointerEvent()
                                val ch = e.changes.firstOrNull { it.pressed } ?: break
                                val v = valueAt(ch.position.x)
                                if (outputGroup) setOutput(sel, v) else setInput(sel, v)
                                ch.consume()
                            }
                        }
                    }
            ) {
                val h = hist ?: IntArray(256)
                var maxV = 1
                for (i in 0 until 256) if (h[i] > maxV) maxV = h[i]
                val pad = histPad.toPx()
                val plotW = (size.width - pad * 2).coerceAtLeast(1f)
                val plotH = (histBottom - histTop).toPx()
                val top = histTop.toPx()
                val bw = plotW / 256f

                val b = blacks[channel]
                val w = whites[channel]
                val g = gammas[channel]
                val mid = (b + (w - b) * 0.5f.pow(g)).coerceIn(b.toFloat(), w.toFloat())
                fun xOf(v: Float) = pad + v / 255f * plotW
                val barTop = inTop.toPx()

                // 参考线先画, 直方图柱后画, 避免最左/最右整列被黑/白参考线盖住
                drawLine(Color.Black.copy(alpha = 0.45f), Offset(xOf(b.toFloat()), top),
                    Offset(xOf(b.toFloat()), barTop), 1.dp.toPx())
                drawLine(Color.White.copy(alpha = 0.55f), Offset(xOf(w.toFloat()), top),
                    Offset(xOf(w.toFloat()), barTop), 1.dp.toPx())
                drawLine(Ep.Accent.copy(alpha = 0.7f), Offset(xOf(mid), top),
                    Offset(xOf(mid), barTop), 1.dp.toPx())

                val tint = if (channel == 0) Color(0xFFC6D2E0) else chColors[channel]
                for (i in 0 until 256) {
                    val t = sqrt(h[i].toFloat() / maxV.toFloat())
                    val bh = t * plotH
                    if (bh <= 0f) continue
                    drawRect(tint.copy(alpha = 0.7f),
                        Offset(pad + i * bw + 0.3f, top + plotH - bh), Size(bw + 0.3f, bh))
                }

                // 输入渐变条
                drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.White)),
                    Offset(pad, barTop), Size(plotW, (inBottom - inTop).toPx()))
                drawRect(Color.White.copy(alpha = 0.25f), Offset(pad, barTop),
                    Size(plotW, (inBottom - inTop).toPx()), style = Stroke(1.dp.toPx()))

                val triH = 9.dp.toPx()
                val triW = 7.dp.toPx()
                fun tri(x: Float, fill: Color, border: Color) {
                    val yb = (inBottom).toPx() + triH
                    val path = Path().apply {
                        moveTo(x, yb - triH)
                        lineTo(x - triW, yb)
                        lineTo(x + triW, yb)
                        close()
                    }
                    drawPath(path, fill)
                    drawPath(path, border, style = Stroke(1.dp.toPx()))
                }
                tri(xOf(b.toFloat()), Color.Black, Color.White)
                tri(xOf(mid), Ep.Accent, Color.Black)
                tri(xOf(w.toFloat()), Color.White, Color.Black)

                // 输出渐变条 + 两枚方形滑块
                val oTop = outTop.toPx()
                val oH = (outBottom - outTop).toPx()
                fun gray(v: Int) = Color(0xFF000000.toInt() or (v shl 16) or (v shl 8) or v)
                drawRect(Brush.horizontalGradient(listOf(
                    gray(outBlacks[channel]), gray(outWhites[channel])
                )), Offset(pad, oTop), Size(plotW, oH))
                drawRect(Color.White.copy(alpha = 0.25f), Offset(pad, oTop),
                    Size(plotW, oH), style = Stroke(1.dp.toPx()))
                val hs = 5.dp.toPx()
                for ((idx, v) in listOf(0 to outBlacks[channel], 1 to outWhites[channel])) {
                    val cx = xOf(v.toFloat())
                    val col = if (idx == 0) Color.Black else Color.White
                    drawRect(col, Offset(cx - hs, oTop + oH * 0.5f - hs), Size(hs * 2, hs * 2))
                    drawRect(if (idx == 0) Color.White else Color.Black,
                        Offset(cx - hs, oTop + oH * 0.5f - hs), Size(hs * 2, hs * 2),
                        style = Stroke(1.dp.toPx()))
                }
            }

            Spacer(Modifier.height(3.dp))
            Text(
                "黑 ${blacks[channel]}  中 ${String.format(Locale.US, "%.2f", gammas[channel])}  白 ${whites[channel]}" +
                "    ·    输出 ${outBlacks[channel]} / ${outWhites[channel]}",
                color = Ep.Sub, fontSize = 8.5.sp, maxLines = 1
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.height(28.dp).background(Ep.Panel2, RoundedCornerShape(3.dp))
                        .clickable(interactionSource = null, indication = null) {
                            blacks[channel] = 0; whites[channel] = 255; gammas[channel] = 1f
                            outBlacks[channel] = 0; outWhites[channel] = 255
                        }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) { Text("重置当前通道", color = Ep.Sub, fontSize = 9.sp) }
                Spacer(Modifier.weight(1f))
                DlgBtn("取消", width = 64.dp) { onDismiss() }
                Spacer(Modifier.width(8.dp))
                DlgBtn("应用", accent = true, width = 64.dp) {
                    onApplyAll(buildLuts())
                    onDismiss()
                }
            }
        }
    }
}
