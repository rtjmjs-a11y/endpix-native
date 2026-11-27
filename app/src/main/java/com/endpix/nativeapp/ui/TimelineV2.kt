package com.endpix.nativeapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TL_COLS = 5
private const val TL_ROWS = 7

// [pm970 全矩阵时间轴本地化] 帧×层矩阵: 窗口化5×7, 循环条拖动, 长按帧拖拽重排/交换, 长按图层行拖拽排序,
// 上下/左右滚动条与翻页, 播放锁, 播放画笔, 缩略图只在选中格刷新
@Composable
fun TimelinePanel2(
    layers: Int,
    active: Int,
    frameIdx: Int,
    frameCount: Int,
    playing: Boolean,
    speed: Int,
    loopStart: Int,
    loopEnd: Int,
    visBits: Long,
    lckBits: Long,
    onion: Boolean,
    playBrush: Boolean,
    frameThumb: (Int, Int) -> IntArray,
    thumbRev: Int,
    layerMeta: List<Pair<String, Int>> = emptyList(),
    onFrameOp: (Int, Int) -> Unit,
    onLayerOp: (Int, Int) -> Unit,
    onRenameLayer: (Int, String) -> Unit = { _, _ -> },
    onLayerOpacity: (Int, Int) -> Unit = { _, _ -> },
    onLoopRange: (Int, Int) -> Unit,
    onReorderFrame: (Int, Int) -> Unit,
    onSwapFrames: (Int, Int) -> Unit,
    onReorderLayer: (Int, Int) -> Unit,
    onOnion: (Boolean) -> Unit,
    onPlayBrush: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val d = LocalDensity.current
    val cell = 38.dp
    val cellPx = with(d) { cell.toPx() }
    val headH = 20.dp
    val layerW = 118.dp
    val maxWinF = (frameCount - TL_COLS).coerceAtLeast(0)
    val maxWinL = (layers - TL_ROWS).coerceAtLeast(0)
    var winF by remember { mutableIntStateOf(0) }
    var winL by remember { mutableIntStateOf(0) }

    // 当前帧/活动层离开窗口时最小平移(旧 RefreshTimeline970)
    LaunchedEffect(frameIdx, frameCount) {
        if (frameCount <= TL_COLS) winF = 0
        else if (frameIdx < winF) winF = frameIdx.coerceIn(0, maxWinF)
        else if (frameIdx >= winF + TL_COLS) winF = (frameIdx - TL_COLS + 1).coerceIn(0, maxWinF)
    }
    LaunchedEffect(active, layers) {
        val actRow = layers - 1 - active
        if (layers <= TL_ROWS) winL = 0
        else if (actRow < winL) winL = actRow.coerceIn(0, maxWinL)
        else if (actRow >= winL + TL_ROWS) winL = (actRow - TL_ROWS + 1).coerceIn(0, maxWinL)
    }
    LaunchedEffect(frameCount, layers) {
        winF = winF.coerceIn(0, maxWinF)
        winL = winL.coerceIn(0, maxWinL)
    }

    // 循环区间本地状态(拖动跟手, 50ms 节流下发)
    var loopA by remember { mutableIntStateOf(loopStart.coerceIn(0, (frameCount - 1).coerceAtLeast(0))) }
    var loopB by remember {
        mutableIntStateOf(
            (if (loopEnd < 0) frameCount - 1 else loopEnd)
                .coerceIn(0, (frameCount - 1).coerceAtLeast(0))
        )
    }
    var loopDragging by remember { mutableStateOf(false) }
    val loopALatest = rememberUpdatedState(loopA)
    val loopBLatest = rememberUpdatedState(loopB)
    LaunchedEffect(loopStart, loopEnd, frameCount) {
        if (!loopDragging) {
            val hi = (frameCount - 1).coerceAtLeast(0)
            loopA = loopStart.coerceIn(0, hi)
            loopB = (if (loopEnd < 0) hi else loopEnd).coerceIn(0, hi)
        }
    }
    LaunchedEffect(loopDragging) {
        if (!loopDragging) return@LaunchedEffect
        while (true) {
            onLoopRange(loopALatest.value, loopBLatest.value)
            kotlinx.coroutines.delay(50)
        }
    }
    var showLoopMenu by remember { mutableStateOf(false) }

    // 速度: 本地跟手 + 50ms 节流(与旧 SetTlSpeed970 同)
    var dragSpeed by remember { mutableFloatStateOf(speed.toFloat()) }
    var speedDragging by remember { mutableStateOf(false) }
    LaunchedEffect(speed) { if (!speedDragging) dragSpeed = speed.toFloat() }
    LaunchedEffect(speedDragging) {
        if (!speedDragging) return@LaunchedEffect
        while (true) {
            onFrameOp(5, dragSpeed.roundToInt())
            kotlinx.coroutines.delay(50)
        }
    }

    // 重排/交换开关也是工具子菜单选项, 后台回来必须保持
    val uiCtx = androidx.compose.ui.platform.LocalContext.current
    val uiPrefs = remember(uiCtx) {
        uiCtx.getSharedPreferences("endpix", android.content.Context.MODE_PRIVATE)
    }
    var swapMode by remember { mutableStateOf(uiPrefs.getBoolean("uiSwapMode", false)) }
    var settingsLayer by remember { mutableIntStateOf(-1) }
    var settingsLayerOp0 by remember { mutableIntStateOf(1000) }
    var structRev by remember { mutableIntStateOf(0) }
    LaunchedEffect(frameCount, layers) { structRev++ }

    // 拖拽状态(只在小范围读取, 避免矩阵每帧重组)
    var dragKind by remember { mutableIntStateOf(0) } // 1=帧 2=图层
    var dragFrom by remember { mutableIntStateOf(-1) }
    var dragTo by remember { mutableIntStateOf(-1) }
    var dragAccX by remember { mutableFloatStateOf(0f) }
    var dragAccY by remember { mutableFloatStateOf(0f) }
    var dragLabel by remember { mutableStateOf("") }
    val ghostPos = remember { mutableStateOf(Offset.Zero) }
    val origins = remember { mutableStateMapOf<String, Offset>() }
    var panelOrigin by remember { mutableStateOf(Offset.Zero) }
    var matrixOrigin by remember { mutableStateOf(Offset.Zero) }
    // 图层列表可视区(panel 坐标) + 拖拽自动滚动节流
    var layerViewTop by remember { mutableFloatStateOf(0f) }
    var layerViewBottom by remember { mutableFloatStateOf(0f) }
    var lastAutoScrollMs by remember { mutableLongStateOf(0L) }

    val winFLatest = rememberUpdatedState(winF)
    val winLLatest = rememberUpdatedState(winL)
    val maxFLatest = rememberUpdatedState(maxWinF)
    val maxLLatest = rememberUpdatedState(maxWinL)

    fun beginDrag(kind: Int, from: Int, label: String, key: String, local: Offset) {
        if (playing) return
        dragKind = kind
        dragFrom = from
        dragTo = from
        dragAccX = 0f
        dragAccY = 0f
        dragLabel = label
        val o = origins[key] ?: Offset.Zero
        ghostPos.value = o - panelOrigin + local
    }

    fun endDrag() {
        val kind = dragKind
        val a = dragFrom
        val b = dragTo
        dragKind = 0
        dragFrom = -1
        dragTo = -1
        if (a < 0 || b < 0 || a == b) return
        if (kind == 1) {
            if (swapMode) onSwapFrames(a, b) else onReorderFrame(a, b)
            structRev++
        } else if (kind == 2) {
            onReorderLayer(a, b)
            structRev++
        }
    }

    Box(
        modifier.fillMaxSize().onGloballyPositioned {
            panelOrigin = it.positionInRoot()
        }
    ) {
        // 遮罩只吃点击、不关闭: 时间轴只能点右上角 ✕ 关闭(防止点画布误关)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
            .clickable(interactionSource = null, indication = null) { /* 不关闭 */ })
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(440.dp)
                .background(Ep.Panel, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                .border(1.dp, Ep.Line, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                .padding(8.dp)
        ) {
            // 标题
            Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("时间轴", color = Ep.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(8.dp))
                Text("帧 ${frameIdx + 1}/$frameCount · 层 ${active + 1}/$layers",
                    color = Ep.Sub, fontSize = 9.sp, modifier = Modifier.weight(1f),
                    maxLines = 1, softWrap = false)
                if (playing) Text("播放中", color = Ep.Accent, fontSize = 9.sp)
                Spacer(Modifier.width(6.dp))
                Text("◀", color = Ep.Sub, fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                        .clickable(interactionSource = null, indication = null) {
                            winF = (winF - TL_COLS).coerceAtLeast(0)
                        })
                Text("${winF + 1}–${minOf(frameCount, winF + TL_COLS)}",
                    color = Ep.Sub, fontSize = 8.sp)
                Text("▶", color = Ep.Sub, fontSize = 10.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                        .clickable(interactionSource = null, indication = null) {
                            winF = (winF + TL_COLS).coerceAtMost(maxWinF)
                        })
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(24.dp).clickable(interactionSource = null, indication = null) { onClose() },
                    contentAlignment = Alignment.Center) { Text("✕", color = Ep.Sub, fontSize = 13.sp) }
            }
            Spacer(Modifier.height(4.dp))

            // 循环条(旧 TLLoopDrag1035): 拖两端改起终点, 拖中间整段平移
            Row(Modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("循环", color = Ep.Sub, fontSize = 9.sp)
                Spacer(Modifier.width(6.dp))
                TlLoopTrack(
                    loopA = loopA, loopB = loopB, frameCount = frameCount,
                    onA = { loopA = it }, onB = { loopB = it },
                    onDragChange = { loopDragging = true },
                    onDragEnd = { loopDragging = false; onLoopRange(loopA, loopB) },
                    modifier = Modifier.weight(1f).height(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                val all = loopA <= 0 && loopB >= frameCount - 1
                Text(
                    if (all) "全部" else "${loopA + 1}–${loopB + 1}",
                    color = Ep.Sub, fontSize = 9.sp, maxLines = 1,
                    modifier = Modifier.width(52.dp)
                        .clickable(interactionSource = null, indication = null) { showLoopMenu = true }
                )
            }
            Spacer(Modifier.height(3.dp))

            // 工具行1: 播放/帧/层/洋葱
            Row(Modifier.fillMaxWidth().height(27.dp), verticalAlignment = Alignment.CenterVertically) {
                TlBtn(if (playing) "■" else "▶", 34.dp, true) { onFrameOp(4, 0) }
                Spacer(Modifier.width(3.dp))
                TlBtn("+帧", 40.dp, !playing) { onFrameOp(0, 0) }
                Spacer(Modifier.width(3.dp))
                TlBtn("复制", 40.dp, !playing) { onFrameOp(1, 0) }
                Spacer(Modifier.width(3.dp))
                TlBtn("删帧", 40.dp, !playing) { onFrameOp(2, 0) }
                Spacer(Modifier.width(3.dp))
                TlBtn(if (onion) "洋葱开" else "洋葱", 48.dp, true, selected = onion) { onOnion(!onion) }
                Spacer(Modifier.weight(1f))
                TlBtn("+层", 38.dp, !playing) { onLayerOp(0, 0) }
                Spacer(Modifier.width(3.dp))
                TlBtn("−层", 38.dp, !playing && layers > 1) { if (layers > 1) onLayerOp(1, 0) }
            }
            Spacer(Modifier.height(3.dp))

            // 工具行2: 速度/画笔/重排交换
            Row(Modifier.fillMaxWidth().height(27.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("速度", color = Ep.Sub, fontSize = 9.sp)
                Spacer(Modifier.width(4.dp))
                androidx.compose.material3.Slider(
                    value = dragSpeed, valueRange = 1f..60f,
                    onValueChange = { speedDragging = true; dragSpeed = it },
                    onValueChangeFinished = {
                        speedDragging = false
                        onFrameOp(5, dragSpeed.roundToInt())
                    },
                    modifier = Modifier.weight(1f).height(22.dp)
                )
                Text("${dragSpeed.roundToInt()}fps", color = Ep.Sub, fontSize = 9.sp,
                    modifier = Modifier.width(42.dp))
                Spacer(Modifier.width(3.dp))
                TlBtn(if (playBrush) "画笔开" else "播放画笔", 58.dp, true, selected = playBrush) {
                    onPlayBrush(!playBrush)
                }
                Spacer(Modifier.width(3.dp))
                TlBtn(if (swapMode) "交换" else "重排", 40.dp, !playing, selected = swapMode) {
                    swapMode = !swapMode
                    uiPrefs.edit().putBoolean("uiSwapMode", swapMode).apply()
                }
            }
            Spacer(Modifier.height(4.dp))

            // 矩阵
            Row(Modifier.fillMaxWidth().weight(1f)) {
                // 图层列
                Column(Modifier.width(layerW).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().height(headH), verticalAlignment = Alignment.CenterVertically) {
                        Text("图层", color = Ep.Sub, fontSize = 9.sp, modifier = Modifier.weight(1f))
                        TlSmall("+") { onLayerOp(0, 0) }
                        Spacer(Modifier.width(2.dp))
                        TlSmall("−", enabled = layers > 1) { if (layers > 1) onLayerOp(1, 0) }
                    }
                    Box(
                        Modifier.fillMaxWidth().weight(1f)
                            .onGloballyPositioned {
                                val p = it.positionInRoot()
                                layerViewTop = p.y - panelOrigin.y
                                layerViewBottom = layerViewTop + it.size.height
                            }
                            .pointerInput(layers, maxWinL) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    var acc = 0f
                                    var startWin = winL
                                    var scrolling = false
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                        if (dragKind == 2) break   // 长按图层拖拽优先
                                        acc += ch.positionChange().y
                                        if (!scrolling && kotlin.math.abs(acc) > 8f) scrolling = true
                                        if (scrolling) {
                                            val d = (acc / cellPx).roundToInt()
                                            winL = (startWin - d).coerceIn(0, maxWinL)
                                            ch.consume()
                                        }
                                        if (!ch.pressed) break
                                    }
                                }
                            }
                    ) {
                        Column {
                            for (r in 0 until TL_ROWS) {
                                val l = layers - 1 - (winL + r)
                                if (l < 0) {
                                    Box(Modifier.fillMaxWidth().height(cell).padding(vertical = 1.dp)
                                        .background(Color(0xFF14181E), RoundedCornerShape(2.dp)))
                                } else {
                                    val on = l == active
                                    val vis = (visBits and (1L shl l)) != 0L
                                    val lck = (lckBits and (1L shl l)) != 0L
                                    Row(
                                        Modifier.fillMaxWidth().height(cell).padding(vertical = 1.dp)
                                            .background(if (on) Ep.Accent.copy(alpha = 0.32f) else Ep.Panel2,
                                                RoundedCornerShape(2.dp))
                                            .onGloballyPositioned { origins["row:$l"] = it.positionInRoot() }
                                            .border(if (on) 1.dp else 0.dp,
                                                if (on) Ep.Accent else Color.Transparent, RoundedCornerShape(2.dp))
                                            .clickable(interactionSource = null, indication = null) {
                                                onLayerOp(2, l)
                                            }
                                            .pointerInput(l, playing) {
                                                var total = Offset.Zero
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = { p ->
                                                        total = Offset.Zero
                                                        beginDrag(2, l, "图层 ${l + 1}", "row:$l", p)
                                                    },
                                                    onDrag = { ch, delta ->
                                                        if (dragKind == 2) {
                                                            ch.consume()
                                                            ghostPos.value += delta
                                                            total += delta
                                                            // 拖到列表上/下边缘自动滚动, 才能把图层拖出当前 7 行
                                                            val now = android.os.SystemClock.uptimeMillis()
                                                            if (now - lastAutoScrollMs > 110L &&
                                                                layerViewBottom > layerViewTop + 1f
                                                            ) {
                                                                val gy = ghostPos.value.y
                                                                val rowF = (gy - layerViewTop) / cellPx
                                                                if (rowF < 0.9f && winL > 0) {
                                                                    winL = (winL - 1).coerceAtLeast(0)
                                                                    lastAutoScrollMs = now
                                                                } else if (rowF > TL_ROWS - 0.9f && winL < maxWinL) {
                                                                    winL = (winL + 1).coerceAtMost(maxWinL)
                                                                    lastAutoScrollMs = now
                                                                }
                                                            }
                                                            // 按手指当前所在行换算目标层(已含滚动后的 winL)
                                                            val rowNow = ((ghostPos.value.y - layerViewTop) / cellPx)
                                                                .toInt().coerceIn(0, TL_ROWS - 1)
                                                            dragTo = (layers - 1 - (winL + rowNow))
                                                                .coerceIn(0, layers - 1)
                                                        }
                                                    },
                                                    onDragEnd = { if (dragKind == 2) endDrag() },
                                                    onDragCancel = { dragKind = 0 }
                                                )
                                            },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TlCellThumb(
                                            frameIdx, l, if (l == active) thumbRev else structRev, frameThumb,
                                            Modifier.size(20.dp).padding(start = 2.dp)
                                        )
                                        Spacer(Modifier.width(3.dp))
                                        val meta = layerMeta.getOrNull(l)
                                        val nm = meta?.first?.ifBlank { "图层 ${l + 1}" } ?: "图层 ${l + 1}"
                                        val op = meta?.second ?: 1000
                                        Text(
                                            if (op < 1000) "$nm ${op / 10}%" else nm,
                                            color = if (on) Color.White else Ep.Text,
                                            fontSize = 9.sp, maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        // 可见: 眼睛(睁开/闭眼), 16dp 点击区保持不缩小
                                        Box(
                                            Modifier.size(16.dp)
                                                .clickable(interactionSource = null, indication = null) {
                                                    onLayerOp(4, l)
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            EpIcon(if (vis) 22 else 23,
                                                if (on) Color.White else Ep.Sub, size = 11.dp)
                                        }
                                        // 锁定: 锁(锁定/解锁), 16dp 点击区保持不缩小
                                        Box(
                                            Modifier.size(16.dp)
                                                .clickable(interactionSource = null, indication = null) {
                                                    onLayerOp(5, l)
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            EpIcon(if (lck) 24 else 25,
                                                if (on) Color.White else Ep.Sub, size = 11.dp)
                                        }
                                        // 编辑/图层设置: 铅笔, 16dp 点击区保持不缩小
                                        Box(
                                            Modifier.size(16.dp)
                                                .clickable(interactionSource = null, indication = null) {
                                                    settingsLayerOp0 = layerMeta.getOrNull(l)?.second ?: 1000
                                                    settingsLayer = l
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            EpIcon(26, if (on) Color.White else Ep.Sub, size = 11.dp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.width(4.dp))

                // 帧网格
                Box(Modifier.weight(1f).fillMaxHeight()
                    .onGloballyPositioned { matrixOrigin = it.positionInRoot() - panelOrigin }) {
                    Column {
                        // 帧头
                        Row(Modifier.height(headH), verticalAlignment = Alignment.CenterVertically) {
                            for (c in 0 until TL_COLS) {
                                val f = winF + c
                                if (f >= frameCount) {
                                    Box(Modifier.width(cell).height(headH).padding(1.dp)
                                        .background(Color(0xFF14181E), RoundedCornerShape(2.dp)))
                                } else {
                                    val on = f == frameIdx
                                    Box(
                                        Modifier.width(cell).height(headH).padding(1.dp)
                                            .background(if (on) Ep.Accent else Ep.Panel2,
                                                RoundedCornerShape(2.dp))
                                            .onGloballyPositioned { origins["head:$f"] = it.positionInRoot() }
                                            .clickable(interactionSource = null, indication = null) {
                                                onFrameOp(3, f)
                                            }
                                            .pointerInput(f, playing) {
                                                var total = Offset.Zero
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = { p ->
                                                        total = Offset.Zero
                                                        beginDrag(1, f, "帧 ${f + 1}", "head:$f", p)
                                                    },
                                                    onDrag = { ch, delta ->
                                                        if (dragKind == 1) {
                                                            ch.consume()
                                                            ghostPos.value += delta
                                                            total += delta
                                                            val curWin = winFLatest.value
                                                            val dCols = (total.x / cellPx).roundToInt()
                                                            dragTo = (dragFrom + dCols).coerceIn(0, frameCount - 1)
                                                        }
                                                    },
                                                    onDragEnd = { if (dragKind == 1) endDrag() },
                                                    onDragCancel = { dragKind = 0 }
                                                )
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("${f + 1}", color = if (on) Color.Black else Ep.Sub,
                                            fontSize = 9.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        // 格子
                        Box(Modifier.weight(1f)) {
                            for (r in 0 until TL_ROWS) {
                                val l = layers - 1 - (winL + r)
                                for (c in 0 until TL_COLS) {
                                    val f = winF + c
                                    if (f >= frameCount || l < 0) {
                                        Box(
                                            Modifier.offset(x = cell * c, y = cell * r)
                                                .size(cell).padding(1.dp)
                                                .background(Color(0xFF14181E), RoundedCornerShape(2.dp))
                                        )
                                    } else {
                                        val sel = f == frameIdx && l == active
                                        val fCur = f == frameIdx
                                        TlCell(
                                            frame = f, layer = l, selected = sel, currentFrame = fCur,
                                            thumbRev = if (sel) thumbRev else structRev,
                                            frameThumb = frameThumb,
                                            onSelect = { onFrameOp(3, f); onLayerOp(2, l) },
                                            onDragStart = { p ->
                                                beginDrag(1, f, "帧 ${f + 1}", "cell:$f:$l", p)
                                            },
                                            onDrag = { dx, dy ->
                                                if (dragKind == 1) {
                                                    ghostPos.value += Offset(dx, dy)
                                                    dragAccX += dx
                                                    dragTo = (dragFrom + (dragAccX / cellPx).roundToInt())
                                                        .coerceIn(0, frameCount - 1)
                                                }
                                            },
                                            onDragEnd = { if (dragKind == 1) endDrag() },
                                            onDragCancel = { dragKind = 0 },
                                            playing = playing,
                                            modifier = Modifier.offset(x = cell * c, y = cell * r)
                                                .onGloballyPositioned {
                                                    origins["cell:${f}:$l"] = it.positionInRoot()
                                                }
                                        )
                                    }
                                }
                            }
                        }
                        // 水平滚动条
                        if (frameCount > TL_COLS) {
                            Spacer(Modifier.height(2.dp))
                            TlScrollbar(
                                vertical = false,
                                thumbFraction = TL_COLS.toFloat() / frameCount,
                                startFraction = if (maxWinF <= 0) 0f else winF.toFloat() / maxWinF,
                                onFraction = { fr ->
                                    winF = (fr * maxWinF).roundToInt().coerceIn(0, maxWinF)
                                },
                                modifier = Modifier.fillMaxWidth().height(7.dp)
                            )
                        }
                    }
                }
            }
        }

        // 拖拽目标高亮 + 幽灵标签
        if (dragKind == 1) {
            val col = dragTo - winF
            if (col in 0 until TL_COLS) {
                Box(
                    Modifier.offset {
                        androidx.compose.ui.unit.IntOffset(
                            (matrixOrigin.x + col * cellPx).roundToInt(),
                            matrixOrigin.y.roundToInt()
                        )
                    }.size(cell, headH).padding(1.dp)
                        .background(Ep.Accent.copy(alpha = 0.35f), RoundedCornerShape(2.dp))
                )
            }
        } else if (dragKind == 2) {
            val row = (layers - 1 - dragTo) - winL
            if (row in 0 until TL_ROWS) {
                Box(
                    Modifier.offset {
                        androidx.compose.ui.unit.IntOffset(
                            (matrixOrigin.x - layerW.toPx() - 4.dp.toPx()).roundToInt(),
                            (matrixOrigin.y + headH.toPx() + 2.dp.toPx() + row * cellPx).roundToInt()
                        )
                    }.size(layerW, cell).padding(vertical = 1.dp)
                        .background(Ep.Accent.copy(alpha = 0.30f), RoundedCornerShape(2.dp))
                )
            }
        }
        if (dragKind != 0) {
            TlDragGhost(ghostPos, dragLabel)
        }

        // 循环区间精确设置
        if (showLoopMenu) {
            TlLoopMenu(
                frameCount = frameCount, loopA = loopA, loopB = loopB,
                onChange = { a, b -> loopA = a; loopB = b; onLoopRange(a, b) },
                onClose = { showLoopMenu = false }
            )
        }
        if (settingsLayer >= 0 && settingsLayer < layers) {
            val sl = settingsLayer
            val meta = layerMeta.getOrNull(sl)
            LayerSettingsOverlay(
                layer = sl,
                initialName = meta?.first?.ifBlank { "图层 ${sl + 1}" } ?: "图层 ${sl + 1}",
                initialOpacity = settingsLayerOp0,
                onRename = onRenameLayer,
                onOpacity = onLayerOpacity,
                onClose = { settingsLayer = -1 }
            )
        }
    }
}

// 图层设置: 名称 + 不透明度 + 模式(正常/平铺/蒙版) + 平铺参数 + 2.5D
@Composable
private fun LayerSettingsOverlay(
    layer: Int,
    initialName: String,
    initialOpacity: Int,
    onRename: (Int, String) -> Unit,
    onOpacity: (Int, Int) -> Unit,
    onClose: () -> Unit,
) {
    val props = remember(layer) { LayerOps.propsFor(layer) }
    var name by remember(layer, initialName) { mutableStateOf(initialName) }
    var op by remember(layer, initialOpacity) { mutableFloatStateOf(initialOpacity / 1000f) }
    var lastPush by remember { mutableLongStateOf(0L) }
    // 图层模式/平铺
    var mode by remember(layer) { mutableIntStateOf(props.getOrElse(0) { 0 }) }
    var tw by remember(layer) { mutableIntStateOf(props.getOrElse(1) { 16 }) }
    var th by remember(layer) { mutableIntStateOf(props.getOrElse(2) { 16 }) }
    var offX by remember(layer) { mutableIntStateOf(props.getOrElse(3) { 0 }) }
    var offY by remember(layer) { mutableIntStateOf(props.getOrElse(4) { 0 }) }
    var showGrid by remember(layer) { mutableStateOf(props.getOrElse(5) { 0 } != 0) }
    var gridColor by remember(layer) { mutableIntStateOf(props.getOrElse(6) { 0x66FFFFFF }) }
    val indep0 = props.getOrElse(7) { 0 } != 0
    val lock0 = props.getOrElse(8) { 0 }
    var blend by remember(layer) { mutableIntStateOf(props.getOrElse(9) { 0 }) }
    // 2.5D(全局)
    val st25 = remember { LayerOps.state25D() }
    var on25 by remember { mutableStateOf(st25.getOrElse(0) { 0 } != 0) }
    var dep25 by remember { mutableIntStateOf(st25.getOrElse(1) { 5 }) }
    fun cancel() {
        onOpacity(layer, initialOpacity)
        onClose()
    }
    fun apply() {
        val nm = name.trim()
        if (nm.isNotEmpty()) onRename(layer, nm)
        onOpacity(layer, (op * 1000).roundToInt())
        LayerOps.setProps?.invoke(layer, mode, tw, th, offX, offY, showGrid, gridColor, indep0, lock0, blend)
        LayerOps.set25D?.invoke(on25, dep25, 0)
        onClose()
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
            .clickable(interactionSource = null, indication = null) { cancel() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.width(330.dp).heightIn(max = 620.dp)
                .background(Ep.Panel, RoundedCornerShape(4.dp))
                .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
                .clickable(interactionSource = null, indication = null) {}
                .padding(12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("图层设置", color = Ep.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("名称", color = Ep.Sub, fontSize = 10.sp, modifier = Modifier.width(40.dp))
                androidx.compose.foundation.text.BasicTextField(
                    value = name,
                    onValueChange = { name = it.take(24) },   // 粘贴超长名字时截断
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp),
                    modifier = Modifier.weight(1f).height(32.dp)
                        .background(Ep.Panel2, RoundedCornerShape(3.dp))
                        .padding(horizontal = 8.dp, vertical = 7.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("不透明度 ${(op * 100).roundToInt()}%", color = Ep.Sub, fontSize = 10.sp)
            androidx.compose.material3.Slider(
                value = op,
                onValueChange = {
                    op = it
                    val now = System.currentTimeMillis()
                    if (now - lastPush >= 50) {
                        lastPush = now
                        onOpacity(layer, (it * 1000).roundToInt())
                    }
                },
                onValueChangeFinished = { onOpacity(layer, (op * 1000).roundToInt()) },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth().height(26.dp)
            )
            // ---- 图层模式 ----
            LpSep()
            Text("图层模式", color = Ep.Sub, fontSize = 10.sp)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LpChip("正常", mode == 0) { mode = 0 }
                LpChip("平铺", mode == 1) { mode = 1 }
                LpChip("蒙版", mode == 2) { mode = 2 }
            }
            if (mode == 2) {
                Spacer(Modifier.height(3.dp))
                Text("蒙版层不显示, 用自身 alpha 裁剪紧邻下方图层", color = Ep.Sub, fontSize = 9.sp)
            }
            // ---- 混合模式(与正常/平铺/蒙版互不冲突) ----
            Spacer(Modifier.height(8.dp))
            Text("混合模式", color = Ep.Sub, fontSize = 10.sp)
            Spacer(Modifier.height(4.dp))
            for (row in LP_BLEND_ROWS) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((idx, label) in row) LpChip(label, blend == idx) { blend = idx }
                }
                Spacer(Modifier.height(4.dp))
            }
            if (mode == 1) {
                Spacer(Modifier.height(6.dp))
                Text("网格大小", color = Ep.Sub, fontSize = 10.sp)
                NumRow("宽", tw, 1, 512) { tw = it }
                NumRow("高", th, 1, 512) { th = it }
                Spacer(Modifier.height(4.dp))
                Text("网格偏移", color = Ep.Sub, fontSize = 10.sp)
                NumRow("X", offX, -2048, 2048) { offX = it }
                NumRow("Y", offY, -2048, 2048) { offY = it }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("显示网格", color = Ep.Sub, fontSize = 10.sp, modifier = Modifier.weight(1f))
                    LpChip(if (showGrid) "开" else "关", showGrid) { showGrid = !showGrid }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("网格颜色", color = Ep.Sub, fontSize = 10.sp, modifier = Modifier.width(52.dp))
                    for (c in LP_GRID_COLORS) {
                        Box(
                            Modifier.padding(end = 5.dp).size(18.dp)
                                .background(Color(lpToArgb(c)), RoundedCornerShape(2.dp))
                                .border(if (gridColor == c) 2.dp else 1.dp,
                                    if (gridColor == c) Ep.Accent else Ep.Line, RoundedCornerShape(2.dp))
                                .clickable(interactionSource = null, indication = null) { gridColor = c }
                        )
                    }
                }
            }
            // ---- 2.5D 叠层 ----
            LpSep()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("2.5D 叠层", color = Ep.Sub, fontSize = 10.sp, modifier = Modifier.weight(1f))
                LpChip(if (on25) "开" else "关", on25) { on25 = !on25 }
            }
            if (on25) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("叠层高度 $dep25", color = Ep.Text, fontSize = 10.sp, modifier = Modifier.width(74.dp))
                    androidx.compose.material3.Slider(
                        value = dep25.toFloat(),
                        onValueChange = { dep25 = it.roundToInt().coerceIn(1, 20) },
                        valueRange = 1f..20f, steps = 18,
                        modifier = Modifier.weight(1f).height(26.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("旋转视角", color = Ep.Sub, fontSize = 9.sp)
                    LpChip("复位", false) { LayerOps.reset25DView?.invoke() }
                    Text("单指拖动画布可环绕查看", color = Ep.Sub, fontSize = 8.5.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().height(30.dp), horizontalArrangement = Arrangement.End) {
                TlBtn("取消", 52.dp, true) { cancel() }
                Spacer(Modifier.width(6.dp))
                TlBtn("确认", 52.dp, true) { apply() }
            }
        }
    }
}

// 混合模式预设: 索引与 native raster::blendPixel 对齐
private val LP_BLEND_ROWS = listOf(
    listOf(0 to "正常", 1 to "正片叠底", 2 to "滤色", 3 to "叠加"),
    listOf(4 to "变暗", 5 to "变亮", 6 to "差值", 7 to "相加"),
    listOf(8 to "柔光", 9 to "强光")
)

// 平铺网格颜色预设(native ABGR, 半透明预乘)
private val LP_GRID_COLORS = intArrayOf(
    0x66FFFFFF, 0x66000000, 0x66000066, 0x66006600, 0x66660000, 0x66006666, 0x66666600
)

private fun lpToArgb(abgr: Int): Int {
    val a = (abgr ushr 24) and 0xFF
    val b = (abgr ushr 16) and 0xFF
    val g = (abgr ushr 8) and 0xFF
    val r = abgr and 0xFF
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

@Composable
private fun LpChip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(24.dp)
            .background(if (on) Ep.Accent.copy(alpha = 0.18f) else Ep.Panel2, RoundedCornerShape(3.dp))
            .border(1.dp, if (on) Ep.Accent else Ep.Line, RoundedCornerShape(3.dp))
            .clickable(interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = if (on) Ep.Accent else Ep.Text, fontSize = 10.sp) }
}

@Composable
private fun LpSep() {
    Spacer(Modifier.height(8.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ep.Line))
    Spacer(Modifier.height(6.dp))
}

// 数字输入: − / 输入框 / + ; 只允许数字和负号
@Composable
private fun NumRow(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    fun commit(t: String) {
        val v = t.toIntOrNull()
        if (v != null) onChange(v.coerceIn(min, max))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ep.Sub, fontSize = 10.sp, modifier = Modifier.width(22.dp))
        TlSmall("−") { val nv = (value - 1).coerceAtLeast(min); onChange(nv); text = nv.toString() }
        androidx.compose.foundation.text.BasicTextField(
            value = text,
            onValueChange = { t ->
                text = t.filter { ch -> ch.isDigit() || (ch == '-' && text.isEmpty()) }.take(5)
                commit(text)
            },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 11.sp),
            modifier = Modifier.width(52.dp).height(24.dp).padding(horizontal = 6.dp, vertical = 5.dp)
                .background(Ep.Panel2, RoundedCornerShape(2.dp))
        )
        TlSmall("+") { val nv = (value + 1).coerceAtMost(max); onChange(nv); text = nv.toString() }
        Spacer(Modifier.width(10.dp))
    }
}

@Composable
private fun TlBtn(
    label: String,
    width: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier.width(width).fillMaxHeight()
            .background(if (selected) Ep.Accent.copy(alpha = 0.30f) else Ep.Panel2, RoundedCornerShape(2.dp))
            .border(if (selected) 1.dp else 0.dp,
                if (selected) Ep.Accent else Color.Transparent, RoundedCornerShape(2.dp))
            .clickable(enabled = enabled, interactionSource = null, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (!enabled) Ep.Sub.copy(alpha = 0.5f) else if (selected) Ep.Accent else Ep.Text,
            fontSize = 9.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun TlSmall(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(18.dp).background(Ep.Panel2, RoundedCornerShape(2.dp))
            .clickable(enabled = enabled, interactionSource = null, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(label, color = if (enabled) Ep.Text else Ep.Sub, fontSize = 11.sp) }
}

@Composable
private fun TlCellThumb(
    frame: Int, layer: Int, rev: Int,
    frameThumb: (Int, Int) -> IntArray,
    modifier: Modifier = Modifier,
) {
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, frame, layer, rev) {
        value = try {
            val px = frameThumb(frame, layer)
            val argb = IntArray(48 * 48)
            for (i in 0 until minOf(px.size, argb.size)) {
                val c = px[i]
                val a = (c ushr 24) and 0xFF
                argb[i] = (a shl 24) or (c and 0xFF00FF00.toInt()) or
                    ((c and 0xFF) shl 16) or ((c ushr 16) and 0xFF)
            }
            android.graphics.Bitmap.createBitmap(argb, 48, 48,
                android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
        } catch (_: Throwable) { null }
    }
    Box(modifier.background(Color(0xFF171B21), RoundedCornerShape(2.dp)), contentAlignment = Alignment.Center) {
        bmp?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(1.dp))
        }
    }
}

@Composable
private fun TlCell(
    frame: Int, layer: Int, selected: Boolean, currentFrame: Boolean,
    thumbRev: Int,
    frameThumb: (Int, Int) -> IntArray,
    onSelect: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.size(38.dp).padding(1.dp)
            .background(if (selected) Ep.Accent.copy(alpha = 0.28f) else Color(0xFF1A1F27),
                RoundedCornerShape(2.dp))
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) Ep.Accent else if (currentFrame) Ep.Accent.copy(alpha = 0.45f) else Color(0xFF2A323D),
                RoundedCornerShape(2.dp)
            )
            .clickable(interactionSource = null, indication = null) { onSelect() }
            .pointerInput(frame, layer, playing) {
                var total = Offset.Zero
                detectDragGesturesAfterLongPress(
                    onDragStart = { p -> total = Offset.Zero; onDragStart(p) },
                    onDrag = { ch, delta ->
                        if (!playing) {
                            total += delta
                            ch.consume()
                            onDrag(delta.x, delta.y)
                        }
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragCancel
                )
            },
        contentAlignment = Alignment.Center
    ) {
        TlCellThumb(frame, layer, thumbRev, frameThumb, Modifier.fillMaxSize().padding(1.dp))
    }
}

// 循环条: 两端手柄+中间整段拖动
@Composable
private fun TlLoopTrack(
    loopA: Int, loopB: Int, frameCount: Int,
    onA: (Int) -> Unit, onB: (Int) -> Unit,
    onDragChange: () -> Unit, onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val aState = rememberUpdatedState(loopA)
    val bState = rememberUpdatedState(loopB)
    Canvas(
        modifier.pointerInput(frameCount) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (frameCount <= 1) return@awaitEachGesture
                val w = size.width.toFloat().coerceAtLeast(1f)
                val a0 = aState.value.coerceIn(0, frameCount - 1)
                val b0 = bState.value.coerceIn(a0, frameCount - 1)
                val x0 = a0.toFloat() / frameCount * w
                val x1 = (b0 + 1).toFloat() / frameCount * w
                val edge = 20.dp.toPx()
                val mode = if (abs(down.position.x - x0) <= edge) 0
                else if (abs(down.position.x - x1) <= edge) 1 else 2
                var a = a0
                var b = b0
                val downFrame = down.position.x / w * (frameCount - 1)
                onDragChange()
                down.consume()
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    val at = (ch.position.x.coerceIn(0f, w) / w * (frameCount - 1))
                        .roundToInt().coerceIn(0, frameCount - 1)
                    when (mode) {
                        0 -> a = at.coerceIn(0, b0)
                        1 -> b = at.coerceIn(a0, frameCount - 1)
                        else -> {
                            val len = b0 - a0
                            val ns = (a0 + (at - downFrame).roundToInt())
                                .coerceIn(0, (frameCount - 1 - len).coerceAtLeast(0))
                            a = ns; b = ns + len
                        }
                    }
                    onA(a); onB(b)
                    ch.consume()
                }
                onDragEnd()
            }
        }
    ) {
        val w = size.width
        val h = size.height
        drawRoundRect(Color(0xFF2A323D), Offset(0f, h * 0.35f), Size(w, h * 0.3f),
            androidx.compose.ui.geometry.CornerRadius(h * 0.15f))
        val x0 = loopA.toFloat() / frameCount * w
        val x1 = (loopB + 1).toFloat() / frameCount * w
        drawRoundRect(Color(0xFF58A6FF).copy(alpha = 0.55f),
            Offset(x0, h * 0.35f), Size((x1 - x0).coerceAtLeast(2f), h * 0.3f),
            androidx.compose.ui.geometry.CornerRadius(h * 0.15f))
        drawRoundRect(Color(0xFF58A6FF), Offset(x0 - 2f, h * 0.08f), Size(4f, h * 0.84f),
            androidx.compose.ui.geometry.CornerRadius(2f))
        drawRoundRect(Color(0xFF58A6FF), Offset(x1 - 2f, h * 0.08f), Size(4f, h * 0.84f),
            androidx.compose.ui.geometry.CornerRadius(2f))
    }
}

@Composable
private fun TlScrollbar(
    vertical: Boolean,
    thumbFraction: Float,
    startFraction: Float,
    onFraction: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fracState = rememberUpdatedState(thumbFraction)
    BoxWithConstraints(
        modifier.background(Color(0xFF20262E), RoundedCornerShape(3.dp))
            .pointerInput(vertical) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    fun apply(p: Offset) {
                        val track = if (vertical) size.height.toFloat() else size.width.toFloat()
                        val thumb = track * fracState.value.coerceIn(0.05f, 1f)
                        val travel = (track - thumb).coerceAtLeast(1f)
                        val pos = if (vertical) p.y else p.x
                        val f = ((pos - thumb * 0.5f) / travel).coerceIn(0f, 1f)
                        onFraction(f)
                    }
                    apply(down.position)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        apply(ch.position)
                        ch.consume()
                    }
                }
            }
    ) {
        val trackPx = if (vertical) constraints.maxHeight.toFloat() else constraints.maxWidth.toFloat()
        val thumbPx = (trackPx * thumbFraction.coerceIn(0.05f, 1f)).coerceAtLeast(8f)
        val startPx = startFraction.coerceIn(0f, 1f) * (trackPx - thumbPx).coerceAtLeast(0f)
        if (vertical) {
            Box(
                Modifier.width(maxWidth)
                    .height(with(LocalDensity.current) { thumbPx.toDp() })
                    .offset { androidx.compose.ui.unit.IntOffset(0, startPx.roundToInt()) }
                    .background(Ep.Accent.copy(alpha = 0.55f), RoundedCornerShape(3.dp))
            )
        } else {
            Box(
                Modifier.height(maxHeight)
                    .width(with(LocalDensity.current) { thumbPx.toDp() })
                    .offset { androidx.compose.ui.unit.IntOffset(startPx.roundToInt(), 0) }
                    .background(Ep.Accent.copy(alpha = 0.55f), RoundedCornerShape(3.dp))
            )
        }
    }
}

@Composable
private fun TlDragGhost(pos: State<Offset>, label: String) {
    Box(
        Modifier.offset {
            androidx.compose.ui.unit.IntOffset(
                (pos.value.x - 42.dp.toPx()).roundToInt(),
                (pos.value.y - 34.dp.toPx()).roundToInt()
            )
        }.height(24.dp).widthIn(min = 72.dp)
            .background(Color(0xFF152437), RoundedCornerShape(3.dp))
            .border(1.dp, Ep.Accent, RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 10.sp, maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp))
    }
}

@Composable
private fun TlLoopMenu(
    frameCount: Int, loopA: Int, loopB: Int,
    onChange: (Int, Int) -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
        .clickable(interactionSource = null, indication = null) { onClose() },
        contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(280.dp).background(Ep.Panel, RoundedCornerShape(4.dp))
                .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
                .clickable(interactionSource = null, indication = null) {}
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("循环区间", color = Ep.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f))
                Text("✕", color = Ep.Sub, fontSize = 13.sp,
                    modifier = Modifier.clickable(interactionSource = null, indication = null) { onClose() })
            }
            Spacer(Modifier.height(10.dp))
            for (isStart in listOf(true, false)) {
                val value = if (isStart) loopA else loopB
                Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (isStart) "起点 ${value + 1}" else "终点 ${value + 1}",
                        color = Ep.Text, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    TlSmall("−") {
                        if (isStart) onChange((loopA - 1).coerceIn(0, loopB), loopB)
                        else onChange(loopA, (loopB - 1).coerceIn(loopA, frameCount - 1))
                    }
                    Spacer(Modifier.width(8.dp))
                    TlSmall("+") {
                        if (isStart) onChange((loopA + 1).coerceIn(0, loopB), loopB)
                        else onChange(loopA, (loopB + 1).coerceIn(loopA, frameCount - 1))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(32.dp)
                .background(Ep.Panel2, RoundedCornerShape(3.dp))
                .clickable(interactionSource = null, indication = null) {
                    onChange(0, frameCount - 1)
                }, contentAlignment = Alignment.Center) {
                Text("重置为全部帧", color = Ep.Text, fontSize = 11.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text("导出与播放只针对这个区间", color = Ep.Sub, fontSize = 9.sp)
        }
    }
}
