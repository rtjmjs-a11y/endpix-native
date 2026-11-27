package com.endpix.nativeapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt

private val NOISE_ANIM_SPEEDS = floatArrayOf(0.125f, 0.25f, 0.5f, 1f, 2f, 4f, 8f)

// 白噪音面板(精简版): 五条声轨 + 动效/频谱 + 带速 + 差频
@Composable
fun NoisePanel(
    mix: NoiseMix,
    onChange: (NoiseMix) -> Unit,
    onClose: () -> Unit,
) {
    // 不展示的项固定为默认值，避免旧设置残留影响
    fun clean(m: NoiseMix) = m.copy(
        ieq = 0, width = 2, tone = 0, fx = 0, speechBlock = false,
        animCount = 1, animSpeed = 3, animLow = 0.5f, animHigh = 1.25f,
        sleepMin = 0, scene = -1,
    )
    LaunchedEffect(Unit) { onChange(clean(mix)) }

    // 频谱滑杆动画显示(柔和/硬切 + Low/High 双档)
    var dispBands by remember { mutableStateOf(mix.bands) }
    LaunchedEffect(mix.bands) { if (!mix.animOn) dispBands = mix.bands }
    LaunchedEffect(mix.animOn, mix.animMode, mix.animCount, mix.animSpeed,
                  mix.animLow, mix.animHigh, mix.bands) {
        if (!mix.animOn) { dispBands = mix.bands; return@LaunchedEffect }
        val rnd = java.util.Random()
        val n = mix.animCount.coerceIn(1, 4)
        val spd = NOISE_ANIM_SPEEDS[mix.animSpeed.coerceIn(0, 6)]
        val intervalMs = (2800f / spd).toLong().coerceAtLeast(120L)
        var lastSel = 0L
        var target = List(8) { mix.bands[it] * mix.animLow }
        while (true) {
            val now = System.currentTimeMillis()
            if (now - lastSel >= intervalMs) {
                lastSel = now
                val used = BooleanArray(8)
                var c = 0
                while (c < n) {
                    val k = rnd.nextInt(8)
                    if (!used[k]) { used[k] = true; c++ }
                }
                target = mix.bands.mapIndexed { i, v ->
                    v * (if (used[i]) mix.animHigh else mix.animLow)
                }
            }
            val k = if (mix.animMode == 1) 0.5f else (0.06f * spd).coerceIn(0.01f, 0.4f)
            dispBands = dispBands.mapIndexed { i, v -> v + (target[i] - v) * k }
            kotlinx.coroutines.delay(120)
        }
    }

    Dialog(onDismissRequest = { }) {
        Column(
            Modifier.width(312.dp).heightIn(max = 620.dp)
                .background(Ep.Panel, RoundedCornerShape(6.dp))
                .border(1.dp, Ep.Line, RoundedCornerShape(6.dp))
        ) {
            Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("白噪音", color = Ep.Text, fontSize = 14.sp,
                            fontWeight = FontWeight.Bold)
                    }
                    Box(Modifier.size(28.dp)
                        .background(Ep.Panel2, RoundedCornerShape(3.dp))
                        .clickable(interactionSource = null, indication = null) { onClose() },
                        contentAlignment = Alignment.Center) {
                        Text("✕", color = Ep.Sub, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.height(30.dp)
                            .background(if (mix.on) Ep.Accent else Ep.Panel2, RoundedCornerShape(4.dp))
                            .border(1.dp, if (mix.on) Ep.Accent else Ep.Line, RoundedCornerShape(4.dp))
                            .clickable(interactionSource = null, indication = null) {
                                onChange(clean(mix.copy(on = !mix.on)))
                            }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (mix.on) "停止" else "播放", color = if (mix.on) Ep.Bg else Ep.Text,
                            fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(if (mix.on) "播放中" else "已停止", color = Ep.Sub, fontSize = 9.sp)
                }
                Spacer(Modifier.height(8.dp))
                MixSlider("总音量", mix.master) { onChange(clean(mix.copy(master = it))) }
                Spacer(Modifier.height(5.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ep.Line))
                Spacer(Modifier.height(6.dp))

                // ---- 五条轨道 ----
                MixSlider("雨", mix.rain) { onChange(clean(mix.copy(rain = it))) }
                MixSlider("风", mix.wind) { onChange(clean(mix.copy(wind = it))) }
                MixSlider("海浪", mix.waves) { onChange(clean(mix.copy(waves = it))) }
                MixSlider("篝火", mix.fire) { onChange(clean(mix.copy(fire = it))) }
                MixSlider("基础噪声", mix.colorLevel) { onChange(clean(mix.copy(colorLevel = it))) }
                Spacer(Modifier.height(5.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ep.Line))
                Spacer(Modifier.height(6.dp))

                // ---- 频谱 + 动效 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("频谱", color = Ep.Sub, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    Text(if (mix.animOn) "动效中 · 上下拖动" else "60Hz–8kHz · 上下拖动",
                        color = Ep.Sub, fontSize = 8.sp)
                }
                BandBars(if (mix.animOn) dispBands else mix.bands) { i, v ->
                    val nb = mix.bands.toMutableList()
                    nb[i] = v
                    onChange(clean(mix.copy(bands = nb)))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("动效", color = Ep.Sub, fontSize = 10.sp, modifier = Modifier.width(52.dp))
                    NoiseChip(if (mix.animOn) "开" else "关", mix.animOn) {
                        onChange(clean(mix.copy(animOn = !mix.animOn)))
                    }
                    if (mix.animOn) {
                        Spacer(Modifier.width(5.dp))
                        NoiseChip("柔和", mix.animMode == 0) { onChange(clean(mix.copy(animMode = 0))) }
                        NoiseChip("硬切", mix.animMode == 1) { onChange(clean(mix.copy(animMode = 1))) }
                    }
                }
                Spacer(Modifier.height(5.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ep.Line))
                Spacer(Modifier.height(6.dp))

                // ---- 带速 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("带速", color = Ep.Sub, fontSize = 10.sp, modifier = Modifier.width(52.dp))
                    NoiseChip("慢", mix.speed == 0) { onChange(clean(mix.copy(speed = 0))) }
                    NoiseChip("标准", mix.speed == 1) { onChange(clean(mix.copy(speed = 1))) }
                    NoiseChip("快", mix.speed == 2) { onChange(clean(mix.copy(speed = 2))) }
                    NoiseChip("交替", mix.speed == 3) { onChange(clean(mix.copy(speed = 3))) }
                }
                Spacer(Modifier.height(5.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ep.Line))
                Spacer(Modifier.height(6.dp))

                // ---- 差频(双耳/等时) ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("差频", color = Ep.Sub, fontSize = 10.sp, modifier = Modifier.width(52.dp))
                    NoiseChip("关", mix.binaural == 0) { onChange(clean(mix.copy(binaural = 0))) }
                    NoiseChip("双耳", mix.binaural == 1) { onChange(clean(mix.copy(binaural = 1))) }
                    NoiseChip("等时", mix.binaural == 2) { onChange(clean(mix.copy(binaural = 2))) }
                    if (mix.binaural != 0) {
                        Spacer(Modifier.width(4.dp))
                        Text(if (mix.binaural == 1) "需耳机" else "可外放",
                            color = Ep.Sub, fontSize = 7.5.sp)
                    }
                }
                if (mix.binaural != 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("频率", color = Ep.Sub, fontSize = 9.sp, modifier = Modifier.width(52.dp))
                        for (hz in intArrayOf(2, 6, 10, 18, 40)) {
                            NoiseChip("${hz}Hz", mix.beatHz == hz) {
                                onChange(clean(mix.copy(beatHz = hz)))
                            }
                        }
                    }
                    MixSlider("节拍音量", mix.binauralLevel) {
                        onChange(clean(mix.copy(binauralLevel = it)))
                    }
                }
            }
        }
    }
}

@Composable
private fun BandBars(bands: List<Float>, onChange: (Int, Float) -> Unit) {
    val latest = rememberUpdatedState(onChange)
    Row(Modifier.fillMaxWidth().height(74.dp).padding(top = 4.dp)) {
        for (i in 0 until 8) {
            val v = bands.getOrElse(i) { 1f }
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.weight(1f).width(16.dp).clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF2A3038))
                        .pointerInput(i) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                fun apply(p: Offset) {
                                    val h = size.height.coerceAtLeast(1)
                                    latest.value(i, ((1f - p.y / h) * 1.5f).coerceIn(0f, 1.5f))
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
                        val fh = size.height * (v / 1.5f)
                        drawRect(
                            Brush.verticalGradient(listOf(Ep.Accent, Ep.Accent.copy(alpha = 0.5f))),
                            topLeft = Offset(0f, size.height - fh),
                            size = Size(size.width, fh)
                        )
                        drawRect(Color.White.copy(alpha = 0.6f),
                            topLeft = Offset(0f, size.height - fh - 1f),
                            size = Size(size.width, 2f))
                    }
                }
                Text(NatureNoise.BAND_LABELS[i], color = Ep.Sub, fontSize = 6.5.sp,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun NoiseChip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(end = 3.dp).height(20.dp)
            .background(if (on) Ep.HighlightSel else Ep.Panel2, RoundedCornerShape(3.dp))
            .border(1.dp, if (on) Ep.HighlightSel else Ep.Line, RoundedCornerShape(3.dp))
            .clickable(interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (on) Color.Black else Ep.Sub, fontSize = 8.sp, maxLines = 1)
    }
}

@Composable
private fun MixSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    val latest = rememberUpdatedState(onChange)
    Row(Modifier.fillMaxWidth().height(25.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ep.Sub, fontSize = 10.sp, modifier = Modifier.width(52.dp))
        Box(
            Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(2.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun apply(p: Offset) {
                            latest.value((p.x / size.width).coerceIn(0f, 1f))
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
                drawRect(Color(0xFF30363D))
                drawRect(Brush.horizontalGradient(listOf(Ep.Panel2, Ep.Accent)))
                val px = value * size.width
                drawRect(Color.White, Offset(px - 1.5f, 0f), Size(3f, size.height),
                    style = Stroke(1.2.dp.toPx()))
            }
        }
        Text("${(value * 100f).roundToInt()}%", color = Ep.Accent, fontSize = 9.sp,
            textAlign = TextAlign.End, modifier = Modifier.width(38.dp))
    }
}
