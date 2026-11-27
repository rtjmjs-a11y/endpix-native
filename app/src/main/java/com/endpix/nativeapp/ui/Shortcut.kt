package com.endpix.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * 左下角快捷键按钮(旧项目 BtnShortcuts): 短按/长按各触发一个可配置功能。
 * 默认沿用旧项目: 短按=显示/隐藏网格线, 长按=重回中心; 阈值 0.45s。
 */
data class ShortcutAction(val id: String, val label: String)

val SHORTCUT_ACTIONS = listOf(
    ShortcutAction("grid", "显示/隐藏网格线"),
    ShortcutAction("recenter", "重回中心"),
    ShortcutAction("fit", "适配画布"),
    ShortcutAction("brush", "画笔"),
    ShortcutAction("eraser", "橡皮"),
    ShortcutAction("select", "框选"),
    ShortcutAction("bucket", "油漆桶"),
    ShortcutAction("shape", "图形"),
    ShortcutAction("undo", "撤销"),
    ShortcutAction("redo", "重做"),
    ShortcutAction("frame_add", "加一帧"),
    ShortcutAction("frame_copy", "复制当前帧"),
    ShortcutAction("layer_add", "添加图层"),
    ShortcutAction("save", "保存项目"),
    ShortcutAction("minimap", "小地图开关"),
    ShortcutAction("ref", "参考图开关"),
    ShortcutAction("play", "播放/暂停"),
    ShortcutAction("zoom_in", "放大"),
    ShortcutAction("zoom_out", "缩小"),
)

object ShortcutPrefs {
    var shortId by mutableStateOf("grid")
    var longId by mutableStateOf("recenter")
    var dialogOpen by mutableStateOf(false)
    var persist: ((String, String) -> Unit)? = null

    fun label(id: String): String =
        SHORTCUT_ACTIONS.firstOrNull { it.id == id }?.label ?: id

    // 兼容旧项目 ScShort/ScLong 的 "menu:xxx" 字符串
    fun normalize(id: String?, fallback: String): String {
        val mapped = when (id) {
            "menu:显示网格线" -> "grid"
            "menu:重回中心" -> "recenter"
            else -> id
        }
        return if (SHORTCUT_ACTIONS.any { it.id == mapped }) mapped!! else fallback
    }

    fun setShort(id: String) {
        shortId = id
        persist?.invoke(shortId, longId)
    }

    fun setLong(id: String) {
        longId = id
        persist?.invoke(shortId, longId)
    }

    fun reset() {
        shortId = "grid"
        longId = "recenter"
        persist?.invoke(shortId, longId)
    }
}

object ShortcutHub {
    var handlers: Map<String, () -> Unit> = emptyMap()
    fun invoke(id: String) {
        handlers[id]?.invoke()
    }
}

private const val SHORTCUT_LONG_MS = 450L

@Composable
fun ShortcutButton(modifier: Modifier = Modifier) {
    var progress by remember { mutableFloatStateOf(0f) }
    var pressing by remember { mutableStateOf(false) }
    var pressStart by remember { mutableLongStateOf(0L) }
    var fired by remember { mutableStateOf(false) }
    var canceled by remember { mutableStateOf(false) }

    LaunchedEffect(pressing, pressStart) {
        if (!pressing) return@LaunchedEffect
        while (pressing) {
            withFrameNanos { }
            val held = (System.nanoTime() - pressStart) / 1_000_000L
            progress = (held / SHORTCUT_LONG_MS.toFloat()).coerceIn(0f, 1f)
            if (held >= SHORTCUT_LONG_MS) {
                fired = true
                pressing = false
                progress = 0f
                ShortcutHub.invoke(ShortcutPrefs.longId)
                break
            }
        }
    }

    Box(
        modifier.size(EpLayout.btn)
            .background(Ep.Panel2, RoundedCornerShape(3.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fired = false
                    canceled = false
                    pressStart = System.nanoTime()
                    pressing = true
                    while (true) {
                        val ev = awaitPointerEvent()
                        ev.changes.forEach { it.consume() }
                        if (ev.changes.all { !it.pressed }) break
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: ev.changes.lastOrNull()
                        if (c != null && (c.position - down.position).getDistance() > 24f) {
                            canceled = true
                            break
                        }
                    }
                    pressing = false
                    progress = 0f
                    if (!fired && !canceled) ShortcutHub.invoke(ShortcutPrefs.shortId)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        EpIcon(15, Color.White, size = 22.dp)
        if (progress > 0.01f) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val stroke = 2.2.dp.toPx()
                val inset = stroke * 0.5f + 1.5.dp.toPx()
                drawArc(
                    color = Ep.Accent,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                    style = Stroke(width = stroke)
                )
            }
        }
    }
}

@Composable
fun ShortcutSettingsDialogHost() {
    if (!ShortcutPrefs.dialogOpen) return
    var target by remember { mutableIntStateOf(0) }   // 0=短按 1=长按
    var shortTmp by remember { mutableStateOf(ShortcutPrefs.shortId) }
    var longTmp by remember { mutableStateOf(ShortcutPrefs.longId) }
    Dialog(onDismissRequest = { }) {
        Column(
            Modifier.width(320.dp).heightIn(max = 520.dp)
                .background(Ep.Panel, RoundedCornerShape(6.dp))
                .border(1.dp, Ep.Line, RoundedCornerShape(6.dp))
                .padding(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("快捷键设置", color = Ep.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("点「短按/长按」再选功能，确认后保存", color = Ep.Sub, fontSize = 9.sp)
                }
                Box(Modifier.size(28.dp)
                    .background(Ep.Panel2, RoundedCornerShape(3.dp))
                    .clickable(interactionSource = null, indication = null) {
                        ShortcutPrefs.dialogOpen = false
                    },
                    contentAlignment = Alignment.Center) {
                    Text("✕", color = Ep.Sub, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BindButton("短按", ShortcutPrefs.label(shortTmp), target == 0) { target = 0 }
                BindButton("长按", ShortcutPrefs.label(longTmp), target == 1) { target = 1 }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (target == 0) "为「短按」选择功能" else "为「长按」选择功能",
                color = Ep.Accent, fontSize = 9.5.sp
            )
            Spacer(Modifier.height(5.dp))
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SHORTCUT_ACTIONS.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        pair.forEach { a ->
                            val boundShort = a.id == shortTmp
                            val boundLong = a.id == longTmp
                            val on = if (target == 0) boundShort else boundLong
                            Box(
                                Modifier.weight(1f).height(34.dp)
                                    .background(if (on) Ep.HighlightSel else Ep.Panel2,
                                        RoundedCornerShape(3.dp))
                                    .border(1.dp,
                                        if (on) Ep.Accent else if (boundShort || boundLong) Color(0xFF6C8FC7) else Ep.Line,
                                        RoundedCornerShape(3.dp))
                                    .clickable(interactionSource = null, indication = null) {
                                        if (target == 0) shortTmp = a.id else longTmp = a.id
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(a.label, color = if (on) Color.Black else Ep.Text,
                                    fontSize = 9.5.sp, maxLines = 1)
                                if (boundShort || boundLong) {
                                    Text(if (boundShort && boundLong) "短/长" else if (boundShort) "短" else "长",
                                        color = if (on) Color.Black.copy(alpha = 0.65f) else Ep.Accent,
                                        fontSize = 7.sp,
                                        modifier = Modifier.align(Alignment.TopEnd).padding(2.dp))
                                }
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.height(28.dp)
                    .background(Ep.Panel2, RoundedCornerShape(4.dp))
                    .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
                    .clickable(interactionSource = null, indication = null) {
                        shortTmp = "grid"; longTmp = "recenter"
                    }
                    .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center) {
                    Text("恢复默认", color = Ep.Text, fontSize = 9.5.sp)
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.height(28.dp)
                    .background(Ep.Panel2, RoundedCornerShape(4.dp))
                    .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
                    .clickable(interactionSource = null, indication = null) {
                        ShortcutPrefs.dialogOpen = false
                    }
                    .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center) {
                    Text("取消", color = Ep.Text, fontSize = 10.sp)
                }
                Spacer(Modifier.width(6.dp))
                Box(Modifier.height(28.dp)
                    .background(Ep.Accent, RoundedCornerShape(4.dp))
                    .clickable(interactionSource = null, indication = null) {
                        ShortcutPrefs.setShort(shortTmp)
                        ShortcutPrefs.setLong(longTmp)
                        ShortcutPrefs.dialogOpen = false
                    }
                    .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center) {
                    Text("确认", color = Ep.Bg, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun RowScope.BindButton(label: String, value: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).height(44.dp)
            .background(if (selected) Ep.HighlightSel else Ep.Panel2, RoundedCornerShape(4.dp))
            .border(1.dp, if (selected) Ep.Accent else Ep.Line, RoundedCornerShape(4.dp))
            .clickable(interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(label, color = if (selected) Color.Black else Ep.Sub, fontSize = 8.5.sp)
        Text(value, color = if (selected) Color.Black else Ep.Text, fontSize = 10.sp,
            maxLines = 1, fontWeight = FontWeight.Medium)
    }
}
