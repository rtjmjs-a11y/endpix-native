package com.endpix.nativeapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// 抽屉: 颜色调整 / 像素描边(有选区时才可用)
object SelDrawer {
    var open by mutableStateOf(false)
    var tab by mutableIntStateOf(0)          // 0=颜色调整 1=像素描边
    var dirty by mutableStateOf(false)       // 是否已发出过预览(用于取消还原)
}

object SelOps {
    var adjust: ((Float, Float, Float, Float, Float, Boolean) -> Unit)? = null
    var stroke: ((Int, Int, Int, Boolean, Float, Int, Boolean) -> Unit)? = null
    var restore: (() -> Unit)? = null
    var cancelPreview: (() -> Unit)? = null
}

fun closeDrawerRestore() {
    if (SelDrawer.dirty) SelOps.restore?.invoke()
    SelDrawer.dirty = false
    SelDrawer.open = false
}

private fun cancelRestore() {
    if (SelDrawer.dirty) SelOps.restore?.invoke()
    SelDrawer.dirty = false
    SelDrawer.open = false
}

private val STROKE_COLORS = intArrayOf(
    0xFF111111.toInt(), 0xFFFFFFFF.toInt(), 0xFFE53935.toInt(), 0xFFFB8C00.toInt(),
    0xFFFDD835.toInt(), 0xFF43A047.toInt(), 0xFF00ACC1.toInt(), 0xFF1E88E5.toInt(),
    0xFF8E24AA.toInt(), 0xFFD81B60.toInt(), 0xFF6D4C41.toInt(), 0xFF90A4AE.toInt(),
)

@Composable
fun SelectionDrawer(
    modifier: Modifier = Modifier, selActive: Boolean, brushColor: Int,
    onBrushColor: (Int) -> Unit = {},
    alphaLock: Boolean = false, onAlphaLock: (Boolean) -> Unit = {},
) {
    if (ReplayHub.visible) return
    if (!SelDrawer.open) return
    // 旧项目语义: 无选区=添加文字; 有选区=颜色调整/像素描边
    val textMode = !selActive
    val curTab = if (textMode) 2 else if (SelDrawer.tab == 2) 0 else SelDrawer.tab
    // 只在"有选区 → 选区消失"时自动收起; 冷启动刚打开时 selActive 还没轮询到,
    // 不能在首次组合就判 false 关闭.
    var wasActive by remember { mutableStateOf(selActive) }
    LaunchedEffect(selActive) {
        if (selActive) wasActive = true
        else if (wasActive) { wasActive = false; closeDrawerRestore() }
    }
    Column(
        modifier.width(300.dp)
            .background(Ep.Panel.copy(alpha = 0.985f), RoundedCornerShape(9.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(9.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("抽屉", color = Ep.Text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(10.dp))
            if (textMode) {
                DrawerTab("添加文字", true) {}
            } else {
                DrawerTab("颜色", curTab == 0) {
                    if (SelDrawer.tab != 0) {
                        SelOps.cancelPreview?.invoke(); SelDrawer.dirty = false
                    }
                    SelDrawer.open = true; SelDrawer.tab = 0
                }
                Spacer(Modifier.width(5.dp))
                DrawerTab("描边", curTab == 1) {
                    if (SelDrawer.tab != 1) {
                        SelOps.cancelPreview?.invoke(); SelDrawer.dirty = false
                    }
                    SelDrawer.open = true; SelDrawer.tab = 1
                }
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(26.dp)
                    .background(Ep.Panel2, RoundedCornerShape(5.dp))
                    .clickable(interactionSource = null, indication = null) { cancelRestore() },
                contentAlignment = Alignment.Center
            ) { Text("×", color = Ep.Sub, fontSize = 16.sp) }
        }
        Spacer(Modifier.height(7.dp))
        Divider()
        Column(
            Modifier.fillMaxWidth().heightIn(max = 340.dp)
                .verticalScroll(rememberScrollState())
        ) {
            when (curTab) {
                0 -> AdjustTab()
                1 -> StrokeTab(brushColor)
                else -> TextTab(brushColor, onBrushColor, alphaLock, onAlphaLock)
            }
        }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ep.Line.copy(alpha = 0.4f)))
}

@Composable
private fun DrawerTab(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(26.dp)
            .background(if (on) Ep.Accent.copy(alpha = 0.16f) else Ep.Panel2, RoundedCornerShape(5.dp))
            .border(1.dp, if (on) Ep.Accent.copy(alpha = 0.9f) else Ep.Line, RoundedCornerShape(5.dp))
            .clickable(interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = if (on) Ep.Accent else Ep.Text, fontSize = 10.sp) }
}

// ===== 颜色调整: 拖动即实时预览 =====
@Composable
private fun AdjustTab() {
    var h by remember { mutableStateOf(0f) }
    var s by remember { mutableStateOf(1f) }
    var b by remember { mutableStateOf(1f) }
    var a by remember { mutableStateOf(1f) }
    var blur by remember { mutableStateOf(0f) }
    var touched by remember { mutableStateOf(false) }

    fun preview(h2: Float = h, s2: Float = s, b2: Float = b, a2: Float = a, blur2: Float = blur) {
        SelOps.adjust?.invoke(h2, s2, b2, a2, blur2, false)
        SelDrawer.dirty = true
    }

    Spacer(Modifier.height(7.dp))
    Column(
        Modifier.fillMaxWidth()
            .background(Ep.Panel2.copy(alpha = 0.30f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        SliderRow("色相", h, -180f, 180f, "${h.toInt()}°") { touched = true; h = it; preview(h2 = it) }
        SliderRow("饱和", s, 0f, 2f, String.format("%.2f", s)) { touched = true; s = it; preview(s2 = it) }
        SliderRow("亮度", b, 0f, 2f, String.format("%.2f", b)) { touched = true; b = it; preview(b2 = it) }
        SliderRow("透明", a, 0f, 1f, "${(a * 100).toInt()}%") { touched = true; a = it; preview(a2 = it) }
        SliderRow("模糊", blur, 0f, 1f, String.format("%.2f", blur)) { touched = true; blur = it; preview(blur2 = it) }
    }
    Spacer(Modifier.height(7.dp))
    Divider()
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("重置", color = Ep.Sub, fontSize = 9.5.sp,
            modifier = Modifier.clickable(interactionSource = null, indication = null) {
                touched = true
                h = 0f; s = 1f; b = 1f; a = 1f; blur = 0f
                preview(0f, 1f, 1f, 1f, 0f)
            }.padding(horizontal = 2.dp, vertical = 5.dp))
        Spacer(Modifier.weight(1f))
        OutlineBtn("取消还原") { cancelRestore() }
        Spacer(Modifier.width(6.dp))
        AccentBtn("确认应用") {
            SelOps.adjust?.invoke(h, s, b, a, blur, true)
            SelDrawer.dirty = false
            SelDrawer.open = false
        }
    }
}

// ===== 像素描边: 样式/位置/方向/线宽/颜色/深度, 改一项立刻预览 =====
@Composable
private fun StrokeTab(brushColor: Int) {
    var mode by remember { mutableIntStateOf(0) }      // 0 无 / 1 通用 / 2 彩色
    var outer by remember { mutableStateOf(false) }    // false=内描边
    var dir by remember { mutableIntStateOf(15) }      // 位掩码 15 全部 / 8 上 / 4 下 / 1 左 / 2 右
    var size by remember { mutableIntStateOf(2) }
    var depth by remember { mutableStateOf(0.3f) }
    var color by remember(brushColor) { mutableIntStateOf(brushColor) }

    fun push(m: Int = mode, o: Boolean = outer, d: Int = dir, sz: Int = size,
             dp: Float = depth, col: Int = color, commit: Boolean = false) {
        SelOps.stroke?.invoke(m, sz, d, o, dp, col, commit)
        if (!commit) SelDrawer.dirty = true
    }

    Spacer(Modifier.height(7.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.fillMaxWidth()) {
        StrokeModeCard("无", 0, mode == 0, size, color, Modifier.weight(1f)) {
            mode = 0; push(m = 0)
        }
        StrokeModeCard("通用", 1, mode == 1, size, color, Modifier.weight(1f)) {
            mode = 1; push(m = 1)
        }
        StrokeModeCard("彩色", 2, mode == 2, size, color, Modifier.weight(1f)) {
            mode = 2; push(m = 2)
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            SectionLabel("描边位置")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                MiniChip("内", !outer) { outer = false; push(o = false) }
                MiniChip("外", outer) { outer = true; push(o = true) }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("线宽", Modifier.weight(1f))
                ValuePill("$size")
            }
            EpSlider(size.toFloat(), 1f, 10f, snapInt = true) { v ->
                val nv = v.roundToInt().coerceIn(1, 10)
                if (nv != size) { size = nv; push(sz = nv) }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SectionLabel("方向")
            DirectionPad(dir) { nd -> dir = nd; push(d = nd) }
        }
    }
    if (mode == 1) {
        Spacer(Modifier.height(8.dp))
        Divider()
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("描边颜色", Modifier.weight(1f))
            BrushColorChip(brushColor, color == brushColor) { color = brushColor; push(col = brushColor) }
        }
        Spacer(Modifier.height(4.dp))
        for (row in 0 until 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (i in 0 until 6) {
                    val c = STROKE_COLORS[row * 6 + i]
                    ColorDot(c, color == c) { color = c; push(col = c) }
                }
            }
            if (row == 0) Spacer(Modifier.height(5.dp))
        }
    }
    if (mode == 2) {
        Spacer(Modifier.height(8.dp))
        Divider()
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("深度", Modifier.width(30.dp))
            EpSlider(depth, 0f, 1f, Modifier.weight(1f)) { depth = it; push(dp = it) }
            Spacer(Modifier.width(6.dp))
            ValuePill(String.format("%.2f", depth))
        }
    }
    if (mode == 0) {
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth()
                .background(Ep.Panel2.copy(alpha = 0.28f), RoundedCornerShape(5.dp))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text("选择通用或彩色后，边框沿内容边缘生成",
                color = Ep.Sub, fontSize = 8.5.sp)
        }
    }
    Spacer(Modifier.height(8.dp))
    Divider()
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        OutlineBtn("取消还原") { cancelRestore() }
        Spacer(Modifier.width(6.dp))
        AccentBtn("确认应用") {
            push(commit = true)
            SelDrawer.dirty = false
            SelDrawer.open = false
        }
    }
}

@Composable
private fun StrokeModeCard(label: String, m: Int, on: Boolean, size: Int,
                           color: Int, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(36.dp)
            .background(if (on) Ep.Accent.copy(alpha = 0.13f) else Ep.Panel2, RoundedCornerShape(5.dp))
            .border(1.dp, if (on) Ep.Accent else Ep.Line, RoundedCornerShape(5.dp))
            .clickable(interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StrokeSample(m, size, color, Modifier.size(18.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, color = if (on) Ep.Accent else Ep.Text, fontSize = 9.5.sp)
    }
}

@Composable
private fun StrokeSample(mode: Int, size: Int, color: Int, modifier: Modifier) {
    Canvas(modifier) {
        val pad = 2.5.dp.toPx()
        val w = this.size.width - pad * 2
        val h = this.size.height - pad * 2
        val r = CornerRadius(3.5.dp.toPx(), 3.5.dp.toPx())
        when (mode) {
            0 -> drawRoundRect(
                Color(0xFF6B7280), topLeft = Offset(pad, pad), size = Size(w, h),
                cornerRadius = r, style = Stroke(1.1.dp.toPx())
            )
            1 -> drawRoundRect(
                Color(color), topLeft = Offset(pad, pad), size = Size(w, h),
                cornerRadius = r, style = Stroke((1.1f + size * 0.2f).dp.toPx())
            )
            else -> drawRoundRect(
                Brush.sweepGradient(
                    listOf(Color(0xFFFF5252), Color(0xFFFFD740), Color(0xFF69F0AE),
                        Color(0xFF40C4FF), Color(0xFF7C4DFF), Color(0xFFFF4081), Color(0xFFFF5252))
                ),
                topLeft = Offset(pad, pad), size = Size(w, h), cornerRadius = r,
                style = Stroke((1.1f + size * 0.2f).dp.toPx())
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Ep.Sub, fontSize = 8.5.sp, modifier = modifier.padding(bottom = 3.dp))
}

@Composable
private fun MiniChip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(25.dp)
            .background(if (on) Ep.Accent.copy(alpha = 0.17f) else Ep.Panel2, RoundedCornerShape(4.dp))
            .border(1.dp, if (on) Ep.Accent else Ep.Line, RoundedCornerShape(4.dp))
            .clickable(interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = if (on) Ep.Accent else Ep.Text, fontSize = 9.5.sp) }
}

// 十字方向键: 可多选(位掩码), 中心「全」=四向全开
@Composable
private fun DirectionPad(dir: Int, onChange: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        DirBtn("↑", dir and 8 != 0) {
            val n = dir xor 8
            if (n != 0) onChange(n)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            DirBtn("←", dir and 1 != 0) {
                val n = dir xor 1
                if (n != 0) onChange(n)
            }
            Spacer(Modifier.width(2.dp))
            DirBtn("全", dir == 15) { onChange(15) }
            Spacer(Modifier.width(2.dp))
            DirBtn("→", dir and 2 != 0) {
                val n = dir xor 2
                if (n != 0) onChange(n)
            }
        }
        DirBtn("↓", dir and 4 != 0) {
            val n = dir xor 4
            if (n != 0) onChange(n)
        }
    }
}

@Composable
private fun DirBtn(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(23.dp)
            .background(if (on) Ep.Accent.copy(alpha = 0.2f) else Ep.Panel2, RoundedCornerShape(4.dp))
            .border(1.dp, if (on) Ep.Accent else Ep.Line, RoundedCornerShape(4.dp))
            .clickable(interactionSource = null, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(label, color = if (on) Ep.Accent else Ep.Text, fontSize = 10.sp) }
}

@Composable
private fun BrushColorChip(c: Int, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(22.dp)
            .background(if (on) Ep.Accent.copy(alpha = 0.15f) else Ep.Panel2, RoundedCornerShape(4.dp))
            .border(1.dp, if (on) Ep.Accent else Ep.Line, RoundedCornerShape(4.dp))
            .clickable(interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(12.dp).background(Color(c), CircleShape).border(1.dp, Ep.Line, CircleShape))
        Spacer(Modifier.width(4.dp))
        Text("画笔", color = if (on) Ep.Accent else Ep.Sub, fontSize = 8.5.sp)
    }
}

@Composable
private fun ColorDot(c: Int, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(18.dp)
            .background(Color(c), CircleShape)
            .border(if (on) 2.dp else 1.dp, if (on) Ep.Accent else Color.White.copy(alpha = 0.22f), CircleShape)
            .clickable(interactionSource = null, indication = null) { onClick() }
    )
}

// 自绘滑条: 细轨道 + 圆形旋钮, 拖动/点按都立即回调(实时预览)
@Composable
private fun EpSlider(value: Float, min: Float, max: Float, modifier: Modifier = Modifier,
                     snapInt: Boolean = false, onChange: (Float) -> Unit) {
    val span = (max - min).let { if (it > 0f) it else 1f }
    val frac = ((value - min) / span).coerceIn(0f, 1f)
    var widthPx by remember { mutableIntStateOf(1) }
    val widthState by rememberUpdatedState(widthPx)
    val onChangeState by rememberUpdatedState(onChange)
    val minState by rememberUpdatedState(min)
    val maxState by rememberUpdatedState(max)
    val snapState by rememberUpdatedState(snapInt)
    val padPx = with(LocalDensity.current) { 7.dp.toPx() }

    fun emit(x: Float) {
        val track = (widthState - padPx * 2).coerceAtLeast(1f)
        val f = ((x - padPx) / track).coerceIn(0f, 1f)
        var v = minState + f * (maxState - minState)
        if (snapState) v = v.roundToInt().toFloat()
        onChangeState(v.coerceIn(minState, maxState))
    }

    Box(
        modifier.height(28.dp)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    emit(down.position.x)
                    down.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        emit(ch.position.x)
                        ch.consume()
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val trackH = 4.5.dp.toPx()
            val rad = trackH / 2f
            val x0 = padPx
            val x1 = size.width - padPx
            drawRoundRect(
                Color(0xFF2A323D), Offset(x0, cy - rad), Size(x1 - x0, trackH),
                CornerRadius(rad, rad)
            )
            val tx = x0 + (x1 - x0) * frac
            val fillW = (tx - x0).coerceAtLeast(trackH)
            drawRoundRect(
                Ep.Accent, Offset(x0, cy - rad), Size(fillW, trackH),
                CornerRadius(rad, rad)
            )
            drawCircle(Color(0xFF0B1016), 6.8.dp.toPx(), Offset(tx, cy))
            drawCircle(Ep.Accent, 5.dp.toPx(), Offset(tx, cy))
            drawCircle(Color(0xFF0B1016), 1.6.dp.toPx(), Offset(tx, cy))
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, min: Float, max: Float, shown: String,
                      onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ep.Sub, fontSize = 8.5.sp, modifier = Modifier.width(28.dp))
        EpSlider(value, min, max, Modifier.weight(1f)) { onChange(it) }
        Spacer(Modifier.width(6.dp))
        ValuePill(shown)
    }
}

@Composable
private fun ValuePill(text: String) {
    Box(
        Modifier.width(38.dp).height(18.dp)
            .background(Ep.Panel2, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Ep.Text, fontSize = 9.sp, maxLines = 1) }
}

@Composable
private fun OutlineBtn(label: String, onClick: () -> Unit) {
    Box(
        Modifier.height(27.dp)
            .background(Ep.Panel2, RoundedCornerShape(4.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
            .clickable(interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Ep.Text, fontSize = 9.5.sp) }
}

@Composable
private fun AccentBtn(label: String, onClick: () -> Unit) {
    Box(
        Modifier.height(27.dp)
            .background(Ep.Accent, RoundedCornerShape(4.dp))
            .clickable(interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color(0xFF0B1016), fontSize = 9.5.sp, fontWeight = FontWeight.Medium) }
}

// ===== 添加文字（本地化旧项目：4 套像素字体 + 1..4 倍 + 8 色） =====
@Composable
private fun TextTab(
    brushColor: Int, onBrushColor: (Int) -> Unit = {},
    alphaLock: Boolean = false, onAlphaLock: (Boolean) -> Unit = {},
) {
    var text by remember { mutableStateOf("") }
    var fontIdx by remember { mutableIntStateOf(0) }
    var mult by remember { mutableIntStateOf(1) }
    var bgMode by remember { mutableIntStateOf(0) }   // 预览底: 0 棋盘 / 1 浅 / 2 深
    val ctx = LocalContext.current
    val focusMgr = LocalFocusManager.current
    val preview = remember(text, fontIdx, mult, brushColor) {
        if (text.isBlank()) null else TextTool.render(ctx, text, fontIdx, mult, brushColor)
    }

    Spacer(Modifier.height(7.dp))
    // 直接在面板内输入（不再弹二级输入框）
    BasicTextField(
        value = text,
        onValueChange = { if (it.length <= 80) text = it },
        textStyle = TextStyle(color = Ep.Text, fontSize = 10.sp, lineHeight = 13.sp),
        cursorBrush = SolidColor(Ep.Accent),
        singleLine = false,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.None),
        maxLines = 4,
        modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp, max = 78.dp)
            .background(Ep.Panel2, RoundedCornerShape(5.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(5.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        decorationBox = { inner ->
            Box {
                if (text.isEmpty()) {
                    Text("输入文字…", color = Ep.Sub, fontSize = 10.sp)
                }
                inner()
            }
        }
    )

    Spacer(Modifier.height(7.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in TextTool.FONTS.indices) {
            TextChip(TextTool.FONTS[i].label, fontIdx == i, Modifier.weight(1f)) { focusMgr.clearFocus(); fontIdx = i }
        }
    }
    Spacer(Modifier.height(5.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (m in 1..4) {
            TextChip("${m}x", mult == m, Modifier.weight(1f)) { focusMgr.clearFocus(); mult = m }
        }
    }
    Spacer(Modifier.height(3.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "输出字号 ${TextTool.FONTS[fontIdx].basePx * mult}px" +
                (preview?.let { " · ${it.width}×${it.height}px" } ?: ""),
            color = Ep.Sub, fontSize = 8.5.sp, modifier = Modifier.weight(1f)
        )
        Text("预览底", color = Ep.Sub, fontSize = 8.sp)
        Spacer(Modifier.width(4.dp))
        BgChip("棋盘", bgMode == 0) { focusMgr.clearFocus(); bgMode = 0 }
        Spacer(Modifier.width(3.dp))
        BgChip("浅", bgMode == 1) { focusMgr.clearFocus(); bgMode = 1 }
        Spacer(Modifier.width(3.dp))
        BgChip("深", bgMode == 2) { focusMgr.clearFocus(); bgMode = 2 }
    }
    Spacer(Modifier.height(6.dp))
    Divider()
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionLabel("颜色", Modifier.width(30.dp))
        Box(
            Modifier.size(20.dp).background(Color(brushColor), CircleShape)
                .border(1.dp, Ep.Line, CircleShape)
        )
        Spacer(Modifier.width(7.dp))
        // 黑白快捷色(保留当前透明度)
        val alphaBits = brushColor and 0xFF000000.toInt()
        TextQuickColor(0x00000000 or alphaBits, (brushColor and 0xFFFFFF) == 0) {
            focusMgr.clearFocus(); onBrushColor(alphaBits)
        }
        Spacer(Modifier.width(4.dp))
        TextQuickColor(0x00FFFFFF or alphaBits, (brushColor and 0xFFFFFF) == 0xFFFFFF) {
            focusMgr.clearFocus(); onBrushColor(alphaBits or 0xFFFFFF)
        }
        Spacer(Modifier.width(7.dp))
        Text("画笔颜色", color = Ep.Sub, fontSize = 9.sp)
    }
    Spacer(Modifier.height(6.dp))
    // 添加文字专用: 本层蒙版开关(和选区/裁剪同一套语义)
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionLabel("蒙版", Modifier.width(30.dp))
        TextChip(if (alphaLock) "本层蒙版 开" else "本层蒙版 关", alphaLock) {
            focusMgr.clearFocus()
            onAlphaLock(!alphaLock)
        }
        Spacer(Modifier.width(7.dp))
        Text("只落在已有像素上", color = Ep.Sub, fontSize = 8.sp)
    }
    Spacer(Modifier.height(6.dp))
    BoxWithConstraints(Modifier.fillMaxWidth().height(66.dp), contentAlignment = Alignment.Center) {
        if (preview != null) {
            // 背景只包住文字本身(按文字宽高比), 不再铺满整条导致看着像一个大框
            val availW = maxWidth - 4.dp
            val availH = maxHeight - 4.dp
            val aspect = preview.width.toFloat() / preview.height.toFloat()
            var dw = availW
            var dh = dw / aspect
            if (dh > availH) { dh = availH; dw = dh * aspect }
            Box(
                Modifier.size(dw, dh)
                    .clip(RoundedCornerShape(3.dp))
                    .drawBehind {
                        when (bgMode) {
                            1 -> drawRect(Color(0xFFE8EAED))
                            2 -> drawRect(Color(0xFF12161B))
                            else -> {
                                val cell = 7.dp.toPx()
                                var y = 0f
                                var row = 0
                                while (y < size.height) {
                                    var x = 0f
                                    var col = 0
                                    while (x < size.width) {
                                        drawRect(
                                            if ((row + col) % 2 == 0) Color(0xFF565C64) else Color(0xFF9BA1A9),
                                            topLeft = Offset(x, y), size = Size(cell, cell)
                                        )
                                        x += cell; col++
                                    }
                                    y += cell; row++
                                }
                            }
                        }
                    }
            ) {
                Image(
                    bitmap = preview.asImageBitmap(), contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit, filterQuality = FilterQuality.None
                )
            }
        } else {
            Text("预览", color = Ep.Sub, fontSize = 9.sp)
        }
    }
    Spacer(Modifier.height(7.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.weight(1f))
        OutlineBtn("取消") { cancelRestore() }
        Spacer(Modifier.width(6.dp))
        val enabled = text.isNotBlank()
        Box(
            Modifier.height(27.dp)
                .background(if (enabled) Ep.Accent else Ep.Panel2, RoundedCornerShape(4.dp))
                .border(1.dp, if (enabled) Ep.Accent else Ep.Line, RoundedCornerShape(4.dp))
                .clickable(interactionSource = null, indication = null) {
                    if (enabled) {
                        TextOps.create?.invoke(text, fontIdx, mult, brushColor)
                        SelDrawer.open = false
                    }
                }
                .padding(horizontal = 13.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("添加到画布", color = if (enabled) Color(0xFF0B1016) else Ep.Sub,
                fontSize = 9.5.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun TextChip(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(26.dp)
            .background(if (on) Ep.Accent.copy(alpha = 0.17f) else Ep.Panel2, RoundedCornerShape(4.dp))
            .border(1.dp, if (on) Ep.Accent else Ep.Line, RoundedCornerShape(4.dp))
            .clickable(interactionSource = null, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(label, color = if (on) Ep.Accent else Ep.Text, fontSize = 9.sp, maxLines = 1) }
}

@Composable
private fun TextQuickColor(color: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(20.dp)
            .background(Color(color), CircleShape)
            .border(if (selected) 2.dp else 1.dp,
                if (selected) Ep.Accent else Ep.Line, CircleShape)
            .clickable(interactionSource = null, indication = null) { onClick() }
    )
}

@Composable
private fun BgChip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(20.dp)
            .background(if (on) Ep.Accent.copy(alpha = 0.2f) else Ep.Panel2, RoundedCornerShape(4.dp))
            .border(1.dp, if (on) Ep.Accent else Ep.Line, RoundedCornerShape(4.dp))
            .clickable(interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = if (on) Ep.Accent else Ep.Text, fontSize = 8.5.sp) }
}
