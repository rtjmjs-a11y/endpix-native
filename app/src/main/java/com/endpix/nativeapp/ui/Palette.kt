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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlin.math.roundToInt

// 调色板数据: 名称 + 颜色(最多 60 格)。旧版格式只有 6/18/32, 新格式按实际色数保存。
data class PalettePreset(val name: String, val colors: IntArray)

val EP_SIDE_DEFAULT: IntArray = intArrayOf(
    0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(),
    0xFFFF8800.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(),
)

// 18 色后备默认: 前 6 色保持旧默认不变, 后 12 色补齐像素画常用色
val EP_SIDE_EXT18: IntArray = intArrayOf(
    0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(),
    0xFFFF8800.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(),
    0xFF5F574F.toInt(), 0xFFC2C3C7.toInt(), 0xFF00FFFF.toInt(),
    0xFF29ADFF.toInt(), 0xFF0000FF.toInt(), 0xFF83769C.toInt(),
    0xFFFF00FF.toInt(), 0xFFFF77A8.toInt(), 0xFF7E2553.toInt(),
    0xFFAB5236.toInt(), 0xFF1D2B53.toInt(), 0xFF008751.toInt(),
)

// 32 色: 前 18 色沿用 EXT18, 后 14 色补暗部/皮肤/土色/亮部, 右侧色板以滚动方式显示
val EP_SIDE_EXT32: IntArray = EP_SIDE_EXT18 + intArrayOf(
    0xFF2E222F.toInt(), 0xFF3E3546.toInt(), 0xFF625565.toInt(),
    0xFF966C6C.toInt(), 0xFFAB947A.toInt(), 0xFFEAD4AA.toInt(),
    0xFFB86F50.toInt(), 0xFFE4A672.toInt(), 0xFFEF7D57.toInt(),
    0xFFFFCD75.toInt(), 0xFFA7F070.toInt(), 0xFF38B764.toInt(),
    0xFF257179.toInt(), 0xFF29366F.toInt(),
)

// 60 色: 前 32 色沿用 EXT32, 再补 28 色(避免与前面重复), 供滚动盘输入到 60 格
val EP_SIDE_EXT60: IntArray = EP_SIDE_EXT32 + intArrayOf(
    0xFF1A1C2C.toInt(), 0xFF5D275D.toInt(), 0xFFB13E53.toInt(),
    0xFF41A6F6.toInt(), 0xFF73EFF7.toInt(), 0xFF94B0C2.toInt(),
    0xFF566C86.toInt(), 0xFF333C57.toInt(), 0xFF9B5DE5.toInt(),
    0xFFF15BB5.toInt(), 0xFFFEE440.toInt(), 0xFF00BBF9.toInt(),
    0xFF00F5D4.toInt(), 0xFF7F0622.toInt(), 0xFFD62411.toInt(),
    0xFFFF8426.toInt(), 0xFFFFD100.toInt(), 0xFFA8E72E.toInt(),
    0xFF0B6E4F.toInt(), 0xFF0E7C7B.toInt(), 0xFF4D65B4.toInt(),
    0xFF9D4E15.toInt(), 0xFFE8B796.toInt(), 0xFFC28569.toInt(),
    0xFFD9BD8A.toInt(), 0xFF8F563B.toInt(), 0xFF45283C.toInt(),
    0xFFDFF6E4.toInt(),
)

// 调色板弹窗与主 Activity 之间的回调(避免继续给 MainScreen 加参数触发 64K 方法上限)
object PaletteHub {
    var onSetSlot: ((Int, Int) -> Unit)? = null      // (槽位, ARGB) 覆盖色格
    var onRenamePreset: ((Int, String) -> Unit)? = null
    var onCountPersist: ((Int) -> Unit)? = null      // 色格数量变化后立即落盘
}

@Composable
fun ColorOptionsMenu(modifier: Modifier = Modifier, onPick: () -> Unit, onMix: () -> Unit, onHsv: () -> Unit) {
    // 旧项目 ShowColorOptionsMenu: 色块左侧并排图标按钮 [两色混合][取色器][HSV]
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TwoColorMixButton(onMix)
        ColorMenuBtn(20, onPick)
        ColorMenuBtn(21, onHsv)
    }
}

@Composable
private fun ColorMenuBtn(icon: Int, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).background(Ep.Panel, RoundedCornerShape(3.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(3.dp))
            .clickable(interactionSource = null, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        EpIcon(icon, Ep.Text, size = 24.dp)
    }
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(2.dp))
            .clickable(interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(label, color = Ep.Text, fontSize = 11.sp)
    }
}

@Composable
fun Swatch(color: Int, size: Dp, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(size)
            .then(alphaChecker(color))
            .background(Color(color))
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) Color.White else Ep.Line,
                RoundedCornerShape(2.dp)
            )
            .clickable(interactionSource = null, indication = null) { onClick() }
    )
}

// 半透明色块底下画棋盘格, 不透明色不走这条路径(避免每帧画很多小方块)
private fun alphaChecker(color: Int, cell: Float = 6f): Modifier =
    if (((color ushr 24) and 0xFF) < 255) {
        Modifier.drawBehind {
            val a = Color(0xFF3A3F48)
            val b = Color(0xFF2A2E35)
            var y = 0f
            var row = 0
            while (y < size.height) {
                var x = 0f
                var col = 0
                while (x < size.width) {
                    drawRect(
                        if ((row + col) and 1 == 0) a else b,
                        Offset(x, y),
                        Size(minOf(cell, size.width - x), minOf(cell, size.height - y))
                    )
                    x += cell; col++
                }
                y += cell; row++
            }
        }
    } else Modifier

// 长按色板弹出的调色板管理窗: 6–60 色 + 槽位编辑 + 预设管理
// 可见数量放全局状态, 避免 MainScreen 参数继续膨胀(MainActivity 负责落盘)
object SidePalettePrefs {
    val count = androidx.compose.runtime.mutableIntStateOf(6)
}

private const val PAL_MIN = 6
private const val PAL_MAX = 60

private fun clampPalCount(n: Int): Int = n.coerceIn(PAL_MIN, PAL_MAX)

private fun hex8(c: Int): String =
    Integer.toHexString(c).uppercase().padStart(8, '0')

// 中间色算法已移到 MixPick.kt(mixMidpointColor)，由色板子菜单的两色混合使用

@Composable
fun PaletteDialog(
    palette: IntArray,
    brushColor: Int,
    selectedIdx: Int,
    count: Int,
    onCount: (Int) -> Unit,
    onColor: (Int, Int) -> Unit,
    presets: List<PalettePreset>,
    onPresetSave: (String) -> Unit,
    onPresetApply: (Int) -> Unit,
    onPresetDelete: (Int) -> Unit,
    onPresetImport: () -> Unit,
    onPresetExport: () -> Unit,
    onClose: () -> Unit,
) {
    val curCount = clampPalCount(count)
    var countText by remember { mutableStateOf(curCount.toString()) }
    var editing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    var renaming by remember { mutableIntStateOf(-1) }
    var renameText by remember { mutableStateOf("") }
    val nameFocus = remember { FocusRequester() }

    fun applyCount(n: Int) {
        val c = clampPalCount(n)
        countText = c.toString()
        if (c != curCount) onCount(c)
    }

    fun nextPresetName(): String {
        var n = presets.size + 1
        while (presets.any { it.name == "预设$n" }) n++
        return "预设$n"
    }

    fun setSlot(i: Int) {
        if (i !in palette.indices) return
        PaletteHub.onSetSlot?.invoke(i, brushColor)
        // 借用取色回调把选中格移到刚编辑的槽位(颜色相同, 不会改变画笔)
        onColor(i, brushColor)
    }

    LaunchedEffect(curCount) {
        if (countText.toIntOrNull() != curCount) countText = curCount.toString()
    }
    // 打开保存/改名输入时把焦点从色数框移到名称框, 避免继续输入数字改到色数
    LaunchedEffect(saving, renaming) {
        if (saving || renaming >= 0) {
            withFrameNanos { }
            runCatching { nameFocus.requestFocus() }
        }
    }

    Dialog(onDismissRequest = { }) {
        Column(
            Modifier.width(340.dp).heightIn(max = 620.dp)
                .background(Ep.Panel, RoundedCornerShape(6.dp))
                .border(1.dp, Ep.Line, RoundedCornerShape(6.dp))
                .verticalScroll(rememberScrollState())
        ) {
            // 固定页眉: 滚动内容时标题/关闭按钮不会跑掉
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("调色板", color = Ep.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("色格数量 · 槽位编辑 · 预设管理", color = Ep.Sub, fontSize = 8.5.sp)
                }
                Box(
                    Modifier.height(26.dp)
                        .background(Ep.Panel2, RoundedCornerShape(3.dp))
                        .border(1.dp, Ep.Line, RoundedCornerShape(3.dp))
                        .clickable(interactionSource = null, indication = null) { onClose() }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) { Text("关闭", color = Ep.Sub, fontSize = 9.sp) }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ep.Line))

            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                // ---- 色格数量: 快捷 6/18/32/60 + 数字输入 + 滑杆, 19–60 自动滚动小格 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("色格数量", color = Ep.Sub, fontSize = 10.sp, modifier = Modifier.width(60.dp))
                    PalChoice("6", curCount == 6) { applyCount(6) }
                    Spacer(Modifier.width(4.dp))
                    PalChoice("18", curCount == 18) { applyCount(18) }
                    Spacer(Modifier.width(4.dp))
                    PalChoice("32", curCount == 32) { applyCount(32) }
                    Spacer(Modifier.width(4.dp))
                    PalChoice("60", curCount == 60) { applyCount(60) }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PalStep("−") { applyCount(curCount - 1) }
                    Spacer(Modifier.width(5.dp))
                    Box(
                        Modifier.width(48.dp).height(28.dp)
                            .background(Ep.Panel2, RoundedCornerShape(3.dp))
                            .border(1.dp, Ep.Line, RoundedCornerShape(3.dp))
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        BasicTextField(
                            value = countText,
                            onValueChange = { s ->
                                val d = s.filter { it.isDigit() }.take(2)
                                countText = d
                                d.toIntOrNull()?.let { if (it in PAL_MIN..PAL_MAX) applyCount(it) }
                            },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = Ep.Text, fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            ),
                            cursorBrush = SolidColor(Ep.Accent),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                                .onFocusChanged { if (!it.isFocused) countText = curCount.toString() }
                        )
                    }
                    Spacer(Modifier.width(5.dp))
                    PalStep("+") { applyCount(curCount + 1) }
                    Spacer(Modifier.width(10.dp))
                    PalCountSlider(
                        value = curCount,
                        onChange = { applyCount(it) },
                        modifier = Modifier.weight(1f).height(28.dp)
                    )
                }
                Text(
                    "6 色=整格 · 7–18 色=半格 · 19–60 色=右侧滚动小格",
                    color = Ep.Sub, fontSize = 8.sp,
                    modifier = Modifier.padding(top = 3.dp, start = 2.dp)
                )
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ep.Line))
                Spacer(Modifier.height(8.dp))

                // ---- 当前色板: 点格取色; 编辑模式下点格=用当前画笔色覆盖 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("当前色板 · ${palette.size} 色", color = Ep.Sub, fontSize = 10.sp,
                        modifier = Modifier.weight(1f))
                    PalEditSwitch(editing) {
                        editing = !editing
                    }
                }
                Spacer(Modifier.height(5.dp))
                PalGrid(
                    palette = palette,
                    selectedIdx = selectedIdx,
                    editing = editing,
                    onTap = { i, c ->
                        when {
                            editing -> setSlot(i)
                            else -> onColor(i, c)
                        }
                    }
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(20.dp)
                            .then(alphaChecker(brushColor))
                            .background(Color(brushColor))
                            .border(1.dp, Ep.Line, RoundedCornerShape(2.dp))
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("当前画笔色 #${hex8(brushColor)}", color = Ep.Text, fontSize = 9.sp,
                        modifier = Modifier.weight(1f))
                    PalBtn("填入选中", true) { setSlot(selectedIdx) }
                }
                Text(
                    when {
                        editing -> "编辑模式：点任意色格 = 用当前画笔色覆盖该格"
                        else -> "取色模式：点色格应用颜色；开启“编辑色板”可修改槽位颜色"
                    },
                    color = if (editing) Ep.Accent else Ep.Sub, fontSize = 8.sp,
                    modifier = Modifier.padding(top = 3.dp, start = 2.dp)
                )
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ep.Line))
                Spacer(Modifier.height(8.dp))

                // ---- 调色板预设 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("调色板预设", color = Ep.Sub, fontSize = 10.sp,
                        modifier = Modifier.weight(1f))
                    PalBtn("保存当前", true) {
                        saveName = nextPresetName()
                        renaming = -1
                        saving = true
                    }
                    Spacer(Modifier.width(4.dp))
                    PalBtn("导入", true) { onPresetImport() }
                    Spacer(Modifier.width(4.dp))
                    PalBtn("导出", true) { onPresetExport() }
                }
                if (saving) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PalNameField(saveName, { saveName = it }, Modifier.weight(1f), nameFocus)
                        Spacer(Modifier.width(5.dp))
                        PalBtn("保存", true) {
                            onPresetSave(saveName.trim())
                            saving = false
                        }
                        Spacer(Modifier.width(4.dp))
                        PalBtn("取消", false) { saving = false }
                    }
                    Text("同名预设会被更新；名称最长 24 字", color = Ep.Sub, fontSize = 7.5.sp,
                        modifier = Modifier.padding(top = 3.dp, start = 2.dp))
                }
                Spacer(Modifier.height(5.dp))
                if (presets.isEmpty()) {
                    Text("暂无预设。保存当前色板后可一键切换；导出时会自动带上当前色板。",
                        color = Ep.Sub, fontSize = 8.5.sp)
                } else {
                    presets.forEachIndexed { i, p ->
                        Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("${p.name} · ${p.colors.size} 色",
                                    color = Ep.Text, fontSize = 10.sp, maxLines = 1,
                                    modifier = Modifier.weight(1f))
                                PalBtn("应用", true) { onPresetApply(i) }
                                Spacer(Modifier.width(4.dp))
                                PalBtn("改名", true) {
                                    renaming = i
                                    renameText = p.name
                                    saving = false
                                }
                                Spacer(Modifier.width(4.dp))
                                PalBtn("删除", true) { onPresetDelete(i) }
                            }
                            if (renaming == i) {
                                Row(
                                    Modifier.fillMaxWidth().padding(top = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    PalNameField(renameText, { renameText = it }, Modifier.weight(1f), nameFocus)
                                    Spacer(Modifier.width(5.dp))
                                    PalBtn("确定", true) {
                                        PaletteHub.onRenamePreset?.invoke(i, renameText.trim())
                                        renaming = -1
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    PalBtn("取消", false) { renaming = -1 }
                                }
                            } else {
                                PalStrip(p.colors, Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("预设记住自己的色数；导入支持旧版导出的文本格式。",
                    color = Ep.Sub, fontSize = 8.sp, lineHeight = 11.sp)
            }
        }
    }
}

// 色板网格: 6 色 6 列大格, 7–18 色 6 列中格, 19–60 色 10 列小格
@Composable
private fun PalGrid(
    palette: IntArray,
    selectedIdx: Int,
    editing: Boolean,
    onTap: (Int, Int) -> Unit,
) {
    val cols = when {
        palette.size <= 18 -> 6
        palette.size <= 32 -> 8
        else -> 10
    }
    val cell = when {
        palette.size <= 6 -> 34.dp
        palette.size <= 18 -> 28.dp
        palette.size <= 32 -> 24.dp
        else -> 21.dp
    }
    val gap = if (cols >= 10) 2.dp else 3.dp
    var gi = 0
    palette.asList().chunked(cols).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            row.forEach { c ->
                val i = gi++
                val sel = i == selectedIdx
                Box(
                    Modifier.size(cell)
                        .then(alphaChecker(c))
                        .background(Color(c), RoundedCornerShape(2.dp))
                        .border(
                            if (sel) 2.dp else 1.dp,
                            when {
                                sel -> Color.White
                                editing -> Ep.Accent.copy(alpha = 0.55f)
                                else -> Ep.Line
                            },
                            RoundedCornerShape(2.dp)
                        )
                        .clickable(interactionSource = null, indication = null) { onTap(i, c) }
                )
            }
        }
        Spacer(Modifier.height(gap))
    }
}

@Composable
private fun PalStrip(colors: IntArray, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(12.dp)
            .clip(RoundedCornerShape(2.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(2.dp))
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val n = colors.size
            if (n <= 0) return@Canvas
            val w = size.width / n
            for (i in 0 until n) {
                drawRect(
                    Color(colors[i]),
                    Offset(i * w, 0f),
                    Size(w + 0.5f, size.height)
                )
            }
        }
    }
}

@Composable
private fun PalEditSwitch(on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(24.dp)
            .background(if (on) Ep.Panel2 else Ep.Panel, RoundedCornerShape(3.dp))
            .border(1.dp, if (on) Ep.Accent else Ep.Line, RoundedCornerShape(3.dp))
            .clickable(interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("编辑色板", color = if (on) Ep.Accent else Ep.Sub, fontSize = 8.5.sp)
        Spacer(Modifier.width(5.dp))
        Box(
            Modifier.width(28.dp).height(14.dp)
                .background(if (on) Ep.Accent else Ep.Line, RoundedCornerShape(2.dp))
                .padding(2.dp)
        ) {
            Box(
                Modifier.size(10.dp)
                    .align(if (on) Alignment.CenterEnd else Alignment.CenterStart)
                    .background(if (on) Ep.Bg else Ep.Sub, RoundedCornerShape(1.dp))
            )
        }
    }
}

@Composable
private fun PalCountSlider(value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val latest by rememberUpdatedState(onChange)
    Box(modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            fun apply(x: Float) {
                val f = (x / size.width).coerceIn(0f, 1f)
                latest((PAL_MIN + f * (PAL_MAX - PAL_MIN)).roundToInt())
            }
            down.consume()
            apply(down.position.x)
            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.pressed } ?: break
                ch.consume()
                apply(ch.position.x)
            }
        }
    }) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val h = 4.dp.toPx()
            val r = CornerRadius(2.dp.toPx())
            drawRoundRect(Ep.Line, Offset(0f, cy - h / 2f), Size(size.width, h), r)
            val f = (value - PAL_MIN).toFloat() / (PAL_MAX - PAL_MIN)
            drawRoundRect(Ep.Accent, Offset(0f, cy - h / 2f), Size(size.width * f, h), r)
            val x = size.width * f
            drawRect(
                Color.White,
                Offset(x - 1.dp.toPx(), cy - 7.dp.toPx()),
                Size(2.dp.toPx(), 14.dp.toPx())
            )
        }
    }
}

@Composable
private fun PalStep(text: String, onClick: () -> Unit) {
    Box(
        Modifier.size(26.dp)
            .background(Ep.Panel2, RoundedCornerShape(3.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(3.dp))
            .clickable(interactionSource = null, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Ep.Text, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun PalNameField(
    value: String, onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val fr = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
    Box(
        modifier.height(26.dp)
            .background(Ep.Panel2, RoundedCornerShape(3.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(3.dp))
            .padding(horizontal = 7.dp, vertical = 5.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = { onChange(it.take(24)) },
            singleLine = true,
            textStyle = TextStyle(color = Ep.Text, fontSize = 10.sp),
            cursorBrush = SolidColor(Ep.Accent),
            modifier = fr.fillMaxWidth()
        )
    }
}

@Composable
private fun PalChoice(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(24.dp)
            .background(if (on) Ep.Accent else Ep.Panel2, RoundedCornerShape(3.dp))
            .border(1.dp, if (on) Ep.Accent else Ep.Line, RoundedCornerShape(3.dp))
            .clickable(interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (on) Color.Black else Ep.Text, fontSize = 9.5.sp, maxLines = 1)
    }
}

@Composable
private fun PalBtn(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(24.dp)
            .background(if (enabled) Ep.Panel2 else Ep.Panel, RoundedCornerShape(3.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(3.dp))
            .clickable(interactionSource = null, indication = null, enabled = enabled) {
                onClick()
            }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (enabled) Ep.Text else Ep.Sub, fontSize = 9.sp, maxLines = 1)
    }
}
