package com.endpix.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import kotlin.math.roundToInt

// 左侧工具栏子菜单（旧项目 ShowToolSubmenu 的画笔/橡皮部分）：大小 / 透明 / 移除像素
@Composable
private fun BrushChip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.width(38.dp).height(20.dp)
            .background(if (on) Ep.HighlightSel else Ep.Panel2, RoundedCornerShape(3.dp))
            .border(1.dp, if (on) Ep.Accent else Ep.Line, RoundedCornerShape(3.dp))
            .clickable(interactionSource = null, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (on) Color.Black else Ep.Text, fontSize = 8.sp,
            maxLines = 1, softWrap = false)
    }
}

@Composable
fun PenSubmenu(
    isPen: Boolean, color: Int,
    px: Float, maxPx: Int = 60, alpha: Int,
    onPx: (Float) -> Unit, onMaxPx: (Int) -> Unit = {},
    onAlpha: (Int) -> Unit,
    perfectLine: Boolean = false, onPerfectLine: (Boolean) -> Unit = {},
    alphaLock: Boolean = false, onAlphaLock: (Boolean) -> Unit = {},
    buildup: Boolean = false, onBuildup: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.width(210.dp).heightIn(max = 620.dp)
            .background(Ep.Panel, RoundedCornerShape(3.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(3.dp)).padding(10.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (isPen) "画笔" else "橡皮",
                color = Ep.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            // 单按钮大小切换(10px ↔ 60px), 在“画笔”右侧、颜色预览之前
            Box(
                Modifier.height(22.dp).background(Ep.Panel2, RoundedCornerShape(3.dp))
                    .border(1.dp, Ep.Line, RoundedCornerShape(3.dp))
                    .clickable(interactionSource = null, indication = null) {
                        val next = if (maxPx >= 60) 10 else 60
                        onMaxPx(next)
                        onPx(next.toFloat())
                    }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("${maxPx}px", color = Ep.Accent, fontSize = 9.sp)   // 切换按钮只显示 10/60 档位
            }
            // 橡皮没有颜色概念, 不显示色块预览
            if (isPen) {
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.size(20.dp).background(Color(color), CircleShape)
                        .border(1.dp, Ep.Line, CircleShape)
                )
            }
        }
        if (isPen) {
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().height(26.dp)
                    .background(if (BrushHub.enabled) Ep.HighlightSel else Ep.Panel2,
                        RoundedCornerShape(4.dp))
                    .border(1.dp, if (BrushHub.enabled) Ep.Accent else Ep.Line,
                        RoundedCornerShape(4.dp))
                    .clickable(interactionSource = null, indication = null) {
                        BrushHub.setAdvancedEnabled(!BrushHub.enabled)
                    }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("启用笔刷", color = if (BrushHub.enabled) Color.Black else Ep.Text,
                    fontSize = 10.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(if (BrushHub.enabled) "开" else "关",
                    color = if (BrushHub.enabled) Color.Black else Ep.Sub, fontSize = 9.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        val denom = (maxPx - 1).coerceAtLeast(1).toFloat()
        SubSlider("像素", ((px - 1f) / denom).coerceIn(0f, 1f), "${px.roundToInt()}px",
            { f ->
                var v = (1f + f * denom).roundToInt().toFloat()
                if (v < 1f) v = 1f
                if (v > maxPx.toFloat()) v = maxPx.toFloat()
                onPx(v)
            },
            gestureKey = maxPx
        ) {
            drawRect(Color(0xFF30363D))
            drawRect(Brush.horizontalGradient(listOf(Ep.Panel2, Ep.Accent)))
        }
        if (isPen) {
            SubSlider("透明", alpha / 255f, "${(alpha * 100f / 255f).roundToInt()}%",
                { f -> onAlpha((f * 255f).roundToInt().coerceIn(0, 255)) }
            ) {
                val cell = 6.dp.toPx()
                drawRect(Color.White)
                val cols = (size.width / cell).toInt() + 1
                val rows = (size.height / cell).toInt() + 1
                for (r in 0 until rows) for (c in 0 until cols) {
                    if (((r + c) and 1) == 0) drawRect(
                        Color(0xFFCCCCCC), Offset(c * cell, r * cell), Size(cell, cell)
                    )
                }
                // 透明渐变到当前色(和 HSV 对话框的透明条一致)
                drawRect(Brush.horizontalGradient(
                    listOf(Color.Transparent, Color((color and 0xFFFFFF) or (255 shl 24)))
                ))
            }
        }
        if (isPen && BrushHub.enabled) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("笔刷预设", color = Ep.Sub, fontSize = 9.sp)
                Spacer(Modifier.weight(1f))
                BrushChip("圆", BrushHub.shape == 0) { BrushHub.updateParam(0, 0) }
                Spacer(Modifier.width(3.dp))
                BrushChip("方", BrushHub.shape == 1) { BrushHub.updateParam(0, 1) }
            }
            Spacer(Modifier.height(3.dp))
            BrushHub.presets.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { p ->
                        val bi = BrushHub.presets.indexOf(p)
                        BrushChip(p.name, bi == BrushHub.index) { BrushHub.select(bi) }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(2.dp))
            Text("笔刷参数", color = Ep.Sub, fontSize = 9.sp)
            SubSlider("硬度", BrushHub.hardness / 100f, "${BrushHub.hardness}%",
                { f -> BrushHub.updateParam(1, (f * 100f).roundToInt()) },
                gestureKey = BrushHub.hardness) {
                drawRect(Color(0xFF30363D))
                drawRect(Brush.horizontalGradient(listOf(Color(0xFF8A93A0), Color.White)))
            }
            SubSlider("间距", (BrushHub.spacing - 1) / 99f, "${BrushHub.spacing}%",
                { f -> BrushHub.updateParam(2, 1 + (f * 99f).roundToInt()) },
                gestureKey = BrushHub.spacing) {
                drawRect(Color(0xFF30363D))
                drawRect(Brush.horizontalGradient(listOf(Ep.Accent, Ep.Accent.copy(alpha = 0.25f))))
            }
            SubSlider("散射", BrushHub.scatter / 100f, "${BrushHub.scatter}%",
                { f -> BrushHub.updateParam(3, (f * 100f).roundToInt()) },
                gestureKey = BrushHub.scatter) {
                drawRect(Color(0xFF30363D))
                drawRect(Brush.horizontalGradient(listOf(Ep.Accent.copy(alpha = 0.25f), Ep.Accent)))
            }
            SubSlider("长宽比", (BrushHub.aspect - 5) / 95f, "${BrushHub.aspect}%",
                { f -> BrushHub.updateParam(4, 5 + (f * 95f).roundToInt()) },
                gestureKey = BrushHub.aspect) {
                drawRect(Color(0xFF30363D))
                drawRect(Brush.horizontalGradient(listOf(Ep.Accent, Color(0xFF9B7BFF))))
            }
            SubSlider("角度", BrushHub.angle / 359f, "${BrushHub.angle}°",
                { f -> BrushHub.updateParam(5, (f * 359f).roundToInt()) },
                gestureKey = BrushHub.angle) {
                drawRect(Color(0xFF30363D))
                drawRect(Brush.horizontalGradient(listOf(Color(0xFF9B7BFF), Ep.Accent)))
            }
            SubSlider("流量", BrushHub.flow / 100f, "${BrushHub.flow}%",
                { f -> BrushHub.updateParam(6, (f * 100f).roundToInt()) },
                gestureKey = BrushHub.flow) {
                drawRect(Color(0xFF30363D))
                drawRect(Brush.horizontalGradient(listOf(Ep.Accent.copy(alpha = 0.2f), Ep.Accent)))
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SquareBtn(67, "完美线条", { onPerfectLine(!perfectLine) }, selected = perfectLine)
                SquareBtn(57, "本层蒙版", { onAlphaLock(!alphaLock) }, selected = alphaLock)
                SquareBtn(70, "叠色", { onBuildup(!buildup) }, selected = buildup)
            }
        } else if (isPen) {
            Spacer(Modifier.height(8.dp))
            Text("启用笔刷后可调整完整笔刷系统（16 种预设、硬度/间距/散射/长宽比/角度/流量）",
                color = Ep.Sub, fontSize = 8.5.sp, lineHeight = 12.sp)
        }
    }
}

@Composable
fun BucketSubmenu(
    erase: Boolean, onErase: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 旧项目油漆桶子菜单只有一项: 移除像素
    Column(
        modifier.width(210.dp).background(Ep.Panel, RoundedCornerShape(3.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(3.dp)).padding(10.dp)
    ) {
        Text("油漆桶", color = Ep.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SquareBtn(1, "移除像素", { onErase(!erase) }, selected = erase)
        }
    }
}

// 图形工具子菜单(旧 SelectShapeMenus): 直线/矩形/椭圆 + 填充
@Composable
fun ShapeSubmenu(
    shapeType: Int, filled: Boolean,
    onType: (Int) -> Unit, onFilled: (Boolean) -> Unit,
    ratio: Boolean = false, onRatio: (Boolean) -> Unit = {},
    leafW: Float = 0.33f, onLeafW: (Float) -> Unit = {},
    linePx: Float = 6f, onLinePx: (Float) -> Unit = {},
    onText: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.width(180.dp).background(Ep.Panel, RoundedCornerShape(3.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(3.dp)).padding(8.dp)
    ) {
        Text("图形", color = Ep.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        SquareGrid(listOf(
            MenuBtn(50, "直线", 0, selected = shapeType == 0),
            MenuBtn(51, "矩形", 1, selected = shapeType == 1),
            MenuBtn(52, "椭圆", 2, selected = shapeType == 2),
            MenuBtn(53, "套索填充", 3, selected = shapeType == 3),
            MenuBtn(54, "柳叶", 4, selected = shapeType == 4),
            MenuBtn(55, "测距", 6, selected = shapeType == 6),
            MenuBtn(56, "多边形套索", 7, selected = shapeType == 7),
            MenuBtn(76, "文字", 8),
            MenuBtn(77, "方形渐变", 9, selected = shapeType == 9),
            MenuBtn(78, "圆形渐变", 10, selected = shapeType == 10),
        )) { id -> if (id == 8) onText() else onType(id) }
        if (shapeType >= 9) {
            Spacer(Modifier.height(6.dp))
            Text("拖出形状后换预设，预览满意再应用", color = Ep.Sub, fontSize = 9.sp)
        } else {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SquareBtn(68, "填充", { onFilled(!filled) }, selected = filled)
                SquareBtn(69, "比例锁", { onRatio(!ratio) }, selected = ratio)
            }
            Spacer(Modifier.height(4.dp))
            SubSlider("线宽", ((linePx - 1f) / 59f).coerceIn(0f, 1f), "${linePx.roundToInt()}px",
                { f ->
                    var v = (1f + f * 59f).roundToInt().toFloat()
                    if (v < 1f) v = 1f
                    if (v > 60f) v = 60f
                    onLinePx(v)
                }
            ) {
                drawRect(Color(0xFF30363D))
                drawRect(Brush.horizontalGradient(listOf(Ep.Panel2, Ep.Accent)))
            }
            if (shapeType == 4) {
                Spacer(Modifier.height(4.dp))
                SubSlider("叶宽", ((leafW - 0.05f) / 0.95f).coerceIn(0f, 1f),
                    "${(leafW * 100f).toInt()}%",
                    { f -> onLeafW(0.05f + f * 0.95f) }
                ) {
                    drawRect(Color(0xFF30363D))
                    drawRect(Brush.horizontalGradient(listOf(Ep.Panel2, Ep.Accent)))
                }
            }
        }
    }
}

@Composable
private fun EraseToggle(erase: Boolean, onErase: (Boolean) -> Unit, label: String = "移除像素") {
    Row(
        Modifier.fillMaxWidth().height(26.dp)
            .clip(RoundedCornerShape(3.dp))
            .clickable(interactionSource = null, indication = null) { onErase(!erase) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Ep.Text, fontSize = 10.sp, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(16.dp).background(
                if (erase) Ep.Accent else Ep.Panel2,
                RoundedCornerShape(3.dp)
            ),
            contentAlignment = Alignment.Center
        ) {
            if (erase) Text("✓", color = Ep.Bg, fontSize = 10.sp)
        }
    }
}

@Composable
fun SubSlider(
    label: String, value: Float, valueText: String,
    onChange: (Float) -> Unit, gestureKey: Any? = Unit,
    track: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit,
) {
    // 手势 pointerInput(Unit) 会捕获首次组合的 lambda；用 rememberUpdatedState 保证滑杆上限切换后立即生效
    val onChangeLatest = rememberUpdatedState(onChange)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(26.dp)) {
        Text(label, color = Ep.Sub, fontSize = 9.sp, modifier = Modifier.width(28.dp))
        // 触摸区撑满 26dp(视觉轨道仍 14dp): 细轨道更好按; 消费事件防止父级 verticalScroll
        // 抢走手势, 这样长按后继续滑动也能稳定调值。
        Box(
            Modifier.weight(1f).height(26.dp)
                .pointerInput(gestureKey) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun apply(p: Offset) {
                            onChangeLatest.value((p.x / size.width).coerceIn(0f, 1f))
                        }
                        down.consume()
                        apply(down.position)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.pressed } ?: break
                            ch.consume()
                            apply(ch.position)
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(2.dp))) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    track()
                    val px = value * size.width
                    drawRect(Color.White, Offset(px - 1.5f, 0f), Size(3f, size.height),
                        style = Stroke(1.2.dp.toPx()))
                }
            }
        }
        Text(valueText, color = Ep.Accent, fontSize = 9.sp, textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp))
    }
}

// 框选子菜单：按旧 SelectShapeMenus 结构(176×392u → 137×306dp)
// 3 模式(方框/套索/魔棒, 带图标) + 框选画布/汇入图像/上次裁剪 + 本层蒙版/拉伸旋转/全部层/全部帧/复原变换
@Composable
fun SelectSubmenu(
    mode: Int,
    onMode: (Int) -> Unit,
    selActive: Boolean,
    onSelectAll: () -> Unit, onImport: () -> Unit, onPasteLastCut: () -> Unit,
    alphaLock: Boolean, onAlphaLock: (Boolean) -> Unit,
    stretchMode: Boolean, onStretchMode: (Boolean) -> Unit,
    perspectiveMode: Boolean, onPerspectiveMode: (Boolean) -> Unit,
    allLayers: Boolean, onAllLayers: (Boolean) -> Unit,
    allFrames: Boolean, onAllFrames: (Boolean) -> Unit,
    onReset: () -> Unit, onStretch: () -> Unit,
    onMore: () -> Unit = {},
    // 裁剪目标操作: 只有正在框选且已成功裁剪(浮起)时 cropReady=true 才可用
    cropReady: Boolean = false,
    onCropExport: () -> Unit = {}, onCropNewTab: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.width(180.dp).background(Ep.Panel, RoundedCornerShape(3.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(3.dp)).padding(6.dp)
    ) {
        SquareGrid(listOf(
            MenuBtn(5, "方框选择", 0, selected = mode == 0),
            MenuBtn(4, "套索选择", 1, selected = mode == 1),
            MenuBtn(3, "魔棒选择", 2, selected = mode == 2),
        )) { onMode(it) }
        Spacer(Modifier.height(6.dp))
        SquareGrid(listOf(
            MenuBtn(30, "框选画布", 0),
            MenuBtn(31, "汇入图像", 1),
            MenuBtn(32, "上次裁剪", 2),
            MenuBtn(33, "复原变换", 3, dim = !selActive),
            MenuBtn(80, "裁剪导出", 4, dim = !cropReady),
            MenuBtn(81, "新标签打开", 5, dim = !cropReady),
        )) { id ->
            when (id) {
                0 -> onSelectAll()
                1 -> onImport()
                2 -> onPasteLastCut()
                3 -> onReset()
                4 -> onCropExport()
                else -> onCropNewTab()
            }
        }
        Spacer(Modifier.height(6.dp))
        SquareGrid(listOf(
            MenuBtn(57, "本层蒙版", 0, selected = alphaLock),
            MenuBtn(60, "拉伸旋转", 1, selected = stretchMode),
            MenuBtn(83, "透视变形", 2, selected = perspectiveMode),
            MenuBtn(58, "全部层", 3, selected = allLayers),
            MenuBtn(59, "全部帧", 4, selected = allFrames),
            // 透明叠加(默认关): 关=半透明像素落定直接覆盖; 开=over 混合
            MenuBtn(82, "透明叠加", 5, selected = SelectionBlendHub.alphaOverlay),
        )) { id ->
            when (id) {
                0 -> onAlphaLock(!alphaLock)
                1 -> {
                    val next = !stretchMode
                    if (next) onPerspectiveMode(false)
                    onStretchMode(next)
                }
                2 -> onPerspectiveMode(!perspectiveMode)
                3 -> onAllLayers(!allLayers)
                4 -> onAllFrames(!allFrames)
                else -> {
                    SelectionBlendHub.toggle()
                    ReplayHub.log(
                        if (SelectionBlendHub.alphaOverlay) "透明叠加 开" else "透明叠加 关"
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeRow(icon: Int, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(25.dp)
            .background(if (selected) Ep.HighlightSel else Ep.Panel2, RoundedCornerShape(3.dp))
            .clickable(interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EpIcon(icon, if (selected) Color.Black else Ep.Text, size = 14.dp)
        Spacer(Modifier.width(5.dp))
        Text(label, color = if (selected) Color.Black else Ep.Text, fontSize = 10.sp)
    }
}

@Composable
private fun IconMenuRow(icon: Int, label: String, dim: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(25.dp)
            .background(Ep.Panel2, RoundedCornerShape(3.dp))
            .clickable(interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EpIcon(icon, if (dim) Ep.Sub else Ep.Text, size = 14.dp, enabled = !dim)
        Spacer(Modifier.width(5.dp))
        Text(label, color = if (dim) Ep.Sub else Ep.Text, fontSize = 10.sp)
    }
}

@Composable
private fun ToggleTextRow(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(25.dp)
            .clickable(interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text((if (on) "● " else "○ ") + label, color = Ep.Text, fontSize = 10.sp)
    }
}

// 拉伸旋转对话框: 百分比拉伸(最近邻) + 90°/翻转; 旧自由手柄后续接入
@Composable
fun StretchDialog(
    onApply: (Int, Int) -> Unit, onClose: () -> Unit,
    onRotate: (Int) -> Unit = {}, onFlip: (Boolean) -> Unit = {},
) {
    var wPct by remember { mutableFloatStateOf(100f) }
    var hPct by remember { mutableFloatStateOf(100f) }
    Column(
        Modifier.width(300.dp).background(Ep.Bg, RoundedCornerShape(4.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(4.dp)).padding(14.dp)
    ) {
        Text("拉伸旋转", color = Ep.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        SubSlider("宽", (wPct - 10f) / 390f, "${wPct.roundToInt()}%",
            { f -> wPct = (10f + f * 390f).roundToInt().toFloat() }) {
            drawRect(Color(0xFF30363D)); drawRect(Brush.horizontalGradient(listOf(Ep.Panel2, Ep.Accent)))
        }
        Spacer(Modifier.height(6.dp))
        SubSlider("高", (hPct - 10f) / 390f, "${hPct.roundToInt()}%",
            { f -> hPct = (10f + f * 390f).roundToInt().toFloat() }) {
            drawRect(Color(0xFF30363D)); drawRect(Brush.horizontalGradient(listOf(Ep.Panel2, Ep.Accent)))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DialogBtn("左转90°", Modifier.weight(1f)) { onRotate(3) }
            DialogBtn("右转90°", Modifier.weight(1f)) { onRotate(1) }
            DialogBtn("左右翻", Modifier.weight(1f)) { onFlip(true) }
            DialogBtn("上下翻", Modifier.weight(1f)) { onFlip(false) }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.weight(1f).height(34.dp).background(Ep.Panel2, RoundedCornerShape(3.dp))
                    .clickable(interactionSource = null, indication = null) { onClose() },
                contentAlignment = Alignment.Center
            ) { Text("取消", color = Ep.Text, fontSize = 11.sp) }
            Box(
                Modifier.weight(1f).height(34.dp).background(Ep.Accent, RoundedCornerShape(3.dp))
                    .clickable(interactionSource = null, indication = null) { onApply(wPct.roundToInt(), hPct.roundToInt()); onClose() },
                contentAlignment = Alignment.Center
            ) { Text("确认", color = Ep.Bg, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun DialogBtn(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(30.dp).background(Ep.Panel2, RoundedCornerShape(3.dp))
            .clickable(interactionSource = null, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(label, color = Ep.Text, fontSize = 9.sp) }
}

// 更多工具菜单(旧 ShowMoreToolsMenu 已接项): 重回中心/翻转横向/翻转纵向/灰度/藏色
@Composable
fun MoreToolsMenu(
    onFit: () -> Unit, onFlipH: () -> Unit, onFlipV: () -> Unit,
    onGray: () -> Unit, onHideColor: () -> Unit,
    portraitLock: Boolean = true, onPortraitLock: (Boolean) -> Unit = {},
    recordOn: Boolean = false, recordCount: Int = 0, onRecord: (Boolean) -> Unit = {},
    onLevels: () -> Unit = {},
    minimapOn: Boolean = false, onMinimapOn: (Boolean) -> Unit = {},
    refOn: Boolean = false, onRefOn: (Boolean) -> Unit = {},
    chkOn: Boolean = true, onChk: (Boolean) -> Unit = {},
    gridOn: Boolean = true, onGrid: (Boolean) -> Unit = {},
    noiseOn: Boolean = false, onNoisePanel: () -> Unit = {},
    onText: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.width(180.dp).background(Ep.Panel, RoundedCornerShape(3.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(3.dp)).padding(8.dp)
    ) {
        Text("更多工具", color = Ep.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        SquareGrid(listOf(
            MenuBtn(61, "重回中心", 0),
            MenuBtn(63, "翻转横向", 1),
            MenuBtn(64, "翻转纵向", 2),
            MenuBtn(62, "灰度", 3, selected = GrayViewPrefs.on),
            MenuBtn(65, "藏色", 4),
            MenuBtn(52, if (recordOn) "记录中" else "记录过程", 5, selected = recordOn),
            MenuBtn(45, "色阶", 6),
            MenuBtn(71, "小地图", 7, selected = minimapOn),
            MenuBtn(72, "参考图", 8, selected = refOn),
            MenuBtn(74, "棋盘格", 9, selected = chkOn),
            MenuBtn(73, "网格", 10, selected = gridOn),
            MenuBtn(75, "白噪音", 11, selected = noiseOn),
        )) { id ->
            when (id) {
                0 -> onFit()
                1 -> onFlipH()
                2 -> onFlipV()
                3 -> GrayViewPrefs.toggle()
                4 -> onHideColor()
                6 -> onLevels()
                7 -> onMinimapOn(!minimapOn)
                8 -> onRefOn(!refOn)
                9 -> onChk(!chkOn)
                10 -> onGrid(!gridOn)
                11 -> onNoisePanel()
                else -> onRecord(!recordOn)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SquareBtn(66, "竖屏锁", { onPortraitLock(!portraitLock) }, selected = portraitLock)
            // 标签栏显示开关(默认打开), 直接改全局 TabBarPrefs, 由 MainActivity 持久化
            SquareBtn(79, "标签栏", { TabBarPrefs.toggle() }, selected = TabBarPrefs.visible)
        }
    }
}

@Composable
private fun MenuRow(label: String, dim: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(30.dp)
            .background(Ep.Panel2, RoundedCornerShape(3.dp))
            .clickable(interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(label, color = if (dim) Ep.Sub else Ep.Text, fontSize = 11.sp)
    }
}
