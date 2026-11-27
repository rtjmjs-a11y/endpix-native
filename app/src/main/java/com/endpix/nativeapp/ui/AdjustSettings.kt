package com.endpix.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

data class UiTab(val id: Int, val name: String)

// 顶部画布标签栏(旧 BuildTabBar 适配): 缩略图 + 标签 + 新建; 长按标签重命名
@Composable
fun CanvasTabBar(
    tabs: List<UiTab>,
    activeId: Int,
    defaultW: Int,
    defaultH: Int,
    tabThumb: (Int) -> IntArray?,
    tabSize: (Int) -> Pair<Int, Int>? = { null },
    onSelect: (Int) -> Unit,
    onAdd: (Int, Int) -> Unit,
    onClose: (Int) -> Unit,
    onRename: (Int, String) -> Unit,
    onLoadProject: () -> Unit = {},
    onLoadFile: () -> Unit = {},
    onNewCanvas: () -> Unit = {},
    onTemplate: () -> Unit = {},
    // 回放虚拟标签(有回放会话时显示; 长按卸载)
    replayAvailable: Boolean = false,
    replaySelected: Boolean = false,
    onReplaySelect: () -> Unit = {},
    onReplayClose: () -> Unit = {},
    // 内置浏览器虚拟标签(常驻)
    browserSelected: Boolean = false,
    onBrowserSelect: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var renameTab by remember { mutableStateOf<UiTab?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var nonce by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(2500); nonce++ }
    }
    Row(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xE6161B22), RoundedCornerShape(4.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(4.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tabCtx = androidx.compose.ui.platform.LocalContext.current
        // 浏览器标签组: 只有真的建了网页标签才显示; 一开始一个都没有。
        // 独立滚动区最多占 132dp, 画布标签保留自己的位置, 不会被挤没。
        if (BrowserHub.tabs.isNotEmpty()) {
            Row(
                Modifier.widthIn(max = 132.dp).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BrowserHub.tabs.forEachIndexed { bi, bt ->
                    val on = browserSelected && BrowserHub.activeIndex == bi
                    Row(
                        Modifier.padding(horizontal = 1.dp)
                            .background(
                                if (on) Ep.HighlightSel else Color.Transparent,
                                RoundedCornerShape(3.dp)
                            )
                            .pointerInput(bt.id) {
                                detectTapGestures(onTap = {
                                    BrowserHub.selectTab(bi)
                                    onBrowserSelect()
                                })
                            }
                            .padding(
                                start = 6.dp,
                                end = 2.dp,
                                top = 2.dp, bottom = 2.dp
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("◎", color = if (on) Color.Black else Ep.Accent, fontSize = 10.sp)
                        Spacer(Modifier.width(4.dp))
                        Column(verticalArrangement = Arrangement.Center) {
                            Text(
                                bt.label.take(8),
                                color = if (on) Color.Black else Ep.Text,
                                fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1, softWrap = false
                            )
                            Text(
                                if (on && NetPing.ms >= 0) "延迟 ${NetPing.ms}ms" else "网页",
                                color = if (on) Color.Black.copy(alpha = 0.72f) else Ep.Sub,
                                fontSize = 7.sp, lineHeight = 8.sp, maxLines = 1, softWrap = false
                            )
                        }
                        // 每个浏览器标签都能单独关闭; 只剩一个也显示 ✕(浏览器不是画布)
                        Text("✕", color = if (on) Color.Black.copy(alpha = 0.75f) else Ep.Sub,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(start = 4.dp)
                                .pointerInput(bt.id) {
                                    detectTapGestures(onTap = { BrowserHub.closeTab(bi) })
                                })
                    }
                }
            }
            // 浏览器组 | 画布组 分隔
            Box(
                Modifier.padding(horizontal = 4.dp).width(1.dp).height(18.dp)
                    .background(Ep.Line)
            )
        }
        // 画布标签组: 独立滚动区; fill=false 让整条标签栏随内容自适应长度,
        // 只有内容超出屏幕时才吃掉剩余宽度并滚动。
        Row(
            Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {

            if (replayAvailable) {
                Row(
                    Modifier.padding(horizontal = 1.dp)
                        .background(
                            if (replaySelected) Ep.HighlightSel else Color.Transparent,
                            RoundedCornerShape(3.dp)
                        )
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { onReplaySelect() },
                                onLongPress = { onReplayClose() }
                            )
                        }
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("▶", color = if (replaySelected) Color.Black else Ep.Accent,
                        fontSize = 9.sp)
                    Spacer(Modifier.width(4.dp))
                    Column(verticalArrangement = Arrangement.Center) {
                        Text("回放", color = if (replaySelected) Color.Black else Ep.Text,
                            fontSize = 9.sp, lineHeight = 10.sp, maxLines = 1, softWrap = false)
                        Text("过程回放",
                            color = if (replaySelected) Color.Black.copy(alpha = 0.72f) else Ep.Sub,
                            fontSize = 7.sp, lineHeight = 8.sp, maxLines = 1, softWrap = false)
                    }
                }
            }
            tabs.forEach { tab ->
                val on = tab.id == activeId
                Row(
                    Modifier
                        .padding(horizontal = 1.dp)
                        .background(if (on) Ep.HighlightSel else Color.Transparent,
                            RoundedCornerShape(3.dp))
                        .pointerInput(tab.id) {
                            detectTapGestures(
                                onTap = { onSelect(tab.id) },
                                onLongPress = { renameTab = tab }
                            )
                        }
                        .padding(start = 4.dp, end = if (on && tabs.size > 1) 3.dp else 7.dp,
                            top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TabThumb(tab.id, tabThumb, nonce)
                    Spacer(Modifier.width(4.dp))
                    val sz = tabSize(tab.id)
                    Column(verticalArrangement = Arrangement.Center) {
                        Text(tab.name, color = if (on) Color.Black else Ep.Text, fontSize = 9.sp,
                            lineHeight = 10.sp, maxLines = 1, softWrap = false)
                        if (sz != null && sz.first > 0 && sz.second > 0) {
                            Text("${sz.first}×${sz.second}",
                                color = if (on) Color.Black.copy(alpha = 0.72f)
                                        else Ep.Sub,
                                fontSize = 7.sp, lineHeight = 8.sp, maxLines = 1, softWrap = false)
                        }
                    }
                    if (on && tabs.size > 1) {
                        Text("✕", color = Color.Black.copy(alpha = 0.75f), fontSize = 9.sp,
                            modifier = Modifier.padding(start = 5.dp, end = 2.dp)
                                .pointerInput(tab.id) {
                                    detectTapGestures(onTap = {
                                        ReplayHub.log("关闭标签 ${tab.id}")
                                        CloseTabHub.request(tab.id, tab.name)
                                    })
                                })
                    }
                }
            }
        }
        // "+" 区: 浏览器新建标签 + 画布新建/打开菜单, 统一排在标签行末尾
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (BrowserHub.tabs.isNotEmpty() && BrowserHub.tabs.size < BrowserHub.maxTabs) {
                Text("+", color = Ep.Accent, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { BrowserHub.newTab(tabCtx) })
                        })
            }
        // 画布 "+" → 新建/打开菜单(自绘卡片: 图标+标题+说明, 带弹入动画, 与整体扁平风格一致)
        Box {
            Text("+", color = Ep.Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 6.dp, end = 7.dp)
                    .clickable(interactionSource = null, indication = null) { addMenu = !addMenu })
            if (addMenu) {
                val dens = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.ui.window.Popup(
                    alignment = Alignment.TopEnd,
                    offset = IntOffset(-with(dens) { 2.dp.roundToPx() },
                        with(dens) { 46.dp.roundToPx() }),
                    onDismissRequest = { addMenu = false },
                    properties = androidx.compose.ui.window.PopupProperties(focusable = true)
                ) {
                    val sc = remember { androidx.compose.animation.core.Animatable(0.94f) }
                    val al = remember { androidx.compose.animation.core.Animatable(0f) }
                    LaunchedEffect(Unit) {
                        launch {
                            sc.animateTo(1f, androidx.compose.animation.core.tween(120))
                            al.animateTo(1f, androidx.compose.animation.core.tween(100))
                        }
                    }
                    Column(
                        Modifier.width(172.dp)
                            .graphicsLayer {
                                scaleX = sc.value; scaleY = sc.value; alpha = al.value
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
                            }
                            .shadow(10.dp, RoundedCornerShape(7.dp))
                            .background(Ep.Panel, RoundedCornerShape(7.dp))
                            .border(1.dp, Ep.Line, RoundedCornerShape(7.dp))
                            .padding(4.dp)
                    ) {
                        TabMenuRow(46, "加载文件", "项目 / 图片 / PSD / ASE") {
                            addMenu = false; onLoadFile()
                        }
                        TabMenuRow(47, "创建画布", "输入尺寸新建空白画布") {
                            addMenu = false; onNewCanvas()
                        }
                        TabMenuRow(88, "浏览器", "新建网页标签") {
                            addMenu = false
                            // 打开浏览器: 有保存的标签就恢复, 不覆盖成一张新标签
                            BrowserHub.open(tabCtx)
                            onBrowserSelect()
                        }
                        TabMenuRow(48, "加载模板库", "从预置模板快速开始") {
                            addMenu = false; onTemplate()
                        }
                        TabMenuRow(42, "缓存列表", "已保留的完整画布与步骤") {
                            addMenu = false; StudioHub.mode = 3
                        }
                    }
                }
            }
        }
        }
    }
    renameTab?.let { t ->
        RenameTabDialog(t, onDismiss = { renameTab = null },
            onOk = { name -> renameTab = null; onRename(t.id, name) },
            onCloseTab = { renameTab = null; onClose(t.id) })
    }
}

@Composable
private fun TabThumb(id: Int, loader: (Int) -> IntArray?, nonce: Int) {
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, id, nonce) {
        value = try {
            val px = loader(id) ?: return@produceState
            val argb = IntArray(48 * 48)
            for (i in px.indices) {
                val c = px[i]
                val a = (c ushr 24) and 0xFF
                argb[i] = (a shl 24) or (c and 0xFF00FF00.toInt()) or
                    ((c and 0xFF) shl 16) or ((c ushr 16) and 0xFF)
            }
            android.graphics.Bitmap.createBitmap(argb, 48, 48,
                android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
        } catch (_: Throwable) { null }
    }
    Box(Modifier.size(14.dp).background(Color(0xFFDDDDDD), RoundedCornerShape(2.dp))) {
        bmp?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit) }
    }
}

internal fun tabThumbToImage(px: IntArray?): ImageBitmap? {
    if (px == null || px.size < 48 * 48) return null
    return try {
        val argb = IntArray(48 * 48)
        for (i in argb.indices) {
            val c = px[i]
            val a = (c ushr 24) and 0xFF
            argb[i] = (a shl 24) or (c and 0xFF00FF00.toInt()) or
                ((c and 0xFF) shl 16) or ((c ushr 16) and 0xFF)
        }
        android.graphics.Bitmap.createBitmap(argb, 48, 48,
            android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    } catch (_: Throwable) { null }
}

// 任意边长正方形缩略图(调整画布预览用 192²) → ImageBitmap
internal fun thumbSquareToImage(px: IntArray?): ImageBitmap? {
    if (px == null || px.size < 4) return null
    val n = kotlin.math.sqrt(px.size.toFloat()).toInt()
    if (n < 2 || n * n != px.size) return null
    return try {
        val argb = IntArray(px.size)
        for (i in argb.indices) {
            val c = px[i]
            val a = (c ushr 24) and 0xFF
            argb[i] = (a shl 24) or (c and 0xFF00FF00.toInt()) or
                ((c and 0xFF) shl 16) or ((c ushr 16) and 0xFF)
        }
        android.graphics.Bitmap.createBitmap(argb, n, n,
            android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    } catch (_: Throwable) { null }
}

@Composable
private fun TabMenuRow(icon: Int, title: String, sub: String, onClick: () -> Unit) {
    val inter = remember { MutableInteractionSource() }
    val pressed by inter.collectIsPressedAsState()
    Row(
        Modifier.fillMaxWidth().height(37.dp)
            .background(if (pressed) Ep.Panel2 else Color.Transparent, RoundedCornerShape(4.dp))
            .clickable(interactionSource = inter, indication = null) { onClick() }
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(24.dp).background(Ep.Panel2, RoundedCornerShape(3.dp))
                .border(1.dp, Ep.Line, RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center
        ) { EpIcon(icon, Color.White, size = 14.dp) }
        Spacer(Modifier.width(7.dp))
        Column {
            Text(title, color = Ep.Text, fontSize = 9.5.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, softWrap = false)
            Text(sub, color = Ep.Sub, fontSize = 7.2.sp, lineHeight = 9.sp,
                maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun RenameTabDialog(tab: UiTab, onDismiss: () -> Unit, onOk: (String) -> Unit,
                            onCloseTab: () -> Unit) {
    var name by remember { mutableStateOf(tab.name) }
    // 长按标签 = 画布信息 + 重命名; 信息由 MainActivity 通过 TabInfoHub 提供
    val info = remember(tab.id) { TabInfoHub.provider(tab.id) }
    Dialog(onDismissRequest = { }) {
        Column(Modifier.width(296.dp).background(Ep.Panel, RoundedCornerShape(6.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(6.dp)).padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("画布信息", color = Ep.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("长按画布标签打开 · 可重命名", color = Ep.Sub, fontSize = 8.5.sp)
                }
                DlgClose(onDismiss)
            }
            if (info != null && info.fields.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Column(
                    Modifier.fillMaxWidth()
                        .background(Ep.Panel2, RoundedCornerShape(4.dp))
                        .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
                        .padding(horizontal = 9.dp, vertical = 7.dp)
                ) {
                    info.fields.forEachIndexed { i, f ->
                        if (i > 0) Spacer(Modifier.height(3.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(f.label, color = Ep.Sub, fontSize = 8.5.sp,
                                modifier = Modifier.width(52.dp))
                            Text(f.value, color = Ep.Text, fontSize = 8.8.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("名称（最多 12 个字）", color = Ep.Sub, fontSize = 8.5.sp)
            Spacer(Modifier.height(5.dp))
            // 粘贴/输入超过 12 字时截断而不是整段丢弃(否则从剪贴板粘贴长名字会看起来"没反应")
            DialogField(name, { name = it.take(12) }, numeric = false, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DlgBtn("关闭标签", danger = true, width = 84.dp) { onCloseTab() }
                Spacer(Modifier.weight(1f))
                DlgBtn("取消", width = 62.dp) { onDismiss() }
                Spacer(Modifier.width(8.dp))
                DlgBtn("确定", accent = true, width = 62.dp) { onOk(name) }
            }
        }
    }
}

// 新建画布面板(内容部分, 供「画布」合并对话框使用)
@Composable
fun NewCanvasPanel(defaultW: Int, defaultH: Int, onDismiss: () -> Unit,
                   onCreate: (Int, Int) -> Unit) {
    var w by remember { mutableStateOf(defaultW.coerceIn(1, 16834).toString()) }
    var h by remember { mutableStateOf(defaultH.coerceIn(1, 16834).toString()) }
    val presets = listOf(64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384)
    Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text("宽度", color = Ep.Sub, fontSize = 9.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.width(96.dp))
                Spacer(Modifier.width(28.dp))
                Text("高度", color = Ep.Sub, fontSize = 9.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.width(96.dp))
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically) {
                DialogField(w, { if (it.length <= 5) w = it.filter(Char::isDigit) },
                    numeric = true, fontSize = 20.sp, center = true,
                    modifier = Modifier.width(96.dp))
                Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                    Text("×", color = Ep.Sub, fontSize = 14.sp)
                }
                DialogField(h, { if (it.length <= 5) h = it.filter(Char::isDigit) },
                    numeric = true, fontSize = 20.sp, center = true,
                    modifier = Modifier.width(96.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text("快捷尺寸", color = Ep.Sub, fontSize = 8.5.sp)
            Spacer(Modifier.height(4.dp))
            val sel = w.toIntOrNull()
            val selH = h.toIntOrNull()
            presets.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEach { p ->
                        PresetChip(p.toString(), sel == p && selH == p, Modifier.weight(1f)) {
                            w = p.toString(); h = p.toString()
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                DlgBtn("取消", width = 62.dp) { onDismiss() }
                Spacer(Modifier.width(8.dp))
                DlgBtn("创建", accent = true, width = 62.dp) {
                    val ww = (w.toIntOrNull() ?: defaultW).coerceIn(1, 16834)
                    val hh = (h.toIntOrNull() ?: defaultH).coerceIn(1, 16834)
                    onCreate(ww, hh)
                }
            }
    }
}

@Composable
private fun DlgClose(onClose: () -> Unit) {
    Box(Modifier.size(22.dp).background(Ep.Panel2, RoundedCornerShape(4.dp))
        .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
        .clickable(interactionSource = null, indication = null) { onClose() },
        contentAlignment = Alignment.Center) {
        Text("✕", color = Ep.Sub, fontSize = 12.sp)
    }
}

@Composable
private fun DialogField(
    value: String,
    onValue: (String) -> Unit,
    numeric: Boolean = true,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
    center: Boolean = false,
    modifier: Modifier = Modifier,
    multiline: Boolean = false,
) {
    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(if (multiline) 96.dp else 32.dp),
            contentAlignment = if (multiline) Alignment.TopStart else Alignment.Center) {
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = !multiline,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = Ep.Text, fontSize = fontSize,
                    textAlign = if (center) TextAlign.Center else TextAlign.Start
                ),
                cursorBrush = SolidColor(Ep.Accent),
                keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number)
                                  else KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp)
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ep.Line))
    }
}

@Composable
private fun PresetChip(text: String, selected: Boolean, modifier: Modifier = Modifier,
                       onClick: () -> Unit) {
    Box(
        modifier.height(24.dp)
            .background(if (selected) Ep.Accent.copy(alpha = 0.32f) else Ep.Panel2, RoundedCornerShape(4.dp))
            .border(1.dp, if (selected) Ep.Accent else Ep.Line, RoundedCornerShape(4.dp))
            .clickable(interactionSource = null, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (selected) Ep.Accent else Ep.Text, fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
    }
}

@Composable
internal fun androidx.compose.foundation.layout.RowScope.DlgBtn(
    text: String, accent: Boolean = false, danger: Boolean = false,
    width: androidx.compose.ui.unit.Dp = 80.dp, onClick: () -> Unit
) {
    Box(Modifier.width(width).height(28.dp)
        .background(if (accent) Ep.Accent else Ep.Panel2, RoundedCornerShape(4.dp))
        .border(1.dp, if (danger) Color(0xFFE05A5A) else if (accent) Ep.Accent else Ep.Line,
            RoundedCornerShape(4.dp))
        .clickable(interactionSource = null, indication = null) { onClick() },
        contentAlignment = Alignment.Center) {
        Text(text, color = if (accent) Color.Black else if (danger) Color(0xFFE05A5A) else Ep.Text,
            fontSize = 9.5.sp, fontWeight = if (accent) FontWeight.Medium else FontWeight.Normal)
    }
}

// 调整设置·画布外观(旧 CanvasGrid*/BgChecker*/CamBg 本地化)
data class CanvasStyleSettings(
    val gridW: Int = 10,
    val gridH: Int = 10,
    val gridColor: Int = 0xFFFF0000.toInt(),
    val gridAlphaPct: Int = 60,
    val gridLineW: Float = 0.75f,
    val chkTile: Int = 10,
    val chkA: Int = 0xFFCCCCCC.toInt(),
    val chkB: Int = 0xFFFFFFFF.toInt(),
    val outsideBg: Int = 0xFF0D1117.toInt(),
    val magnifier: Boolean = true,
)

// 调整设置(旧双栏 ShowAdjustSettingsDialog 适配): 左分类标签栏 + 右内容滚动
@Composable
fun AdjustSettingsDialog(
    versionName: String,
    canvasLabel: String,
    chkOn: Boolean,
    gridOn: Boolean,
    portraitLock: Boolean,
    autoSaveMin: Int,
    projectFolder: String = "",
    onPickProjectFolder: () -> Unit = {},
    onClearProjectFolder: () -> Unit = {},
    recordOn: Boolean,
    perfectLine: Boolean = false,
    buildup: Boolean = false,
    alphaLock: Boolean = false,
    eraseMode: Boolean = false,
    pinchUndoMs: Int = 3000,
    onPinchUndoMs: (Int) -> Unit = {},
    onPerfectLine: (Boolean) -> Unit = {},
    onBuildup: (Boolean) -> Unit = {},
    onAlphaLock: (Boolean) -> Unit = {},
    onEraseMode: (Boolean) -> Unit = {},
    onChk: (Boolean) -> Unit,
    onGrid: (Boolean) -> Unit,
    onPortraitLock: (Boolean) -> Unit,
    onAutoSaveMin: (Int) -> Unit,
    onRecord: (Boolean) -> Unit,
    onClearCanvas: () -> Unit,
    onResizeCanvas: (Int, Int, Int, Int) -> Unit = { _, _, _, _ -> },
    onSaveNow: () -> Unit,
    onZoomFit: () -> Unit,
    onClose: () -> Unit,
    thumb: IntArray? = null,
    watermark: WatermarkSettings = WatermarkSettings(),
    onWatermark: (WatermarkSettings) -> Unit = {},
    sigIcon: android.graphics.Bitmap? = null,
    onPickSigIcon: () -> Unit = {},
    onClearSigIcon: () -> Unit = {},
    canvasStyle: CanvasStyleSettings = CanvasStyleSettings(),
    onCanvasStyle: (CanvasStyleSettings) -> Unit = {},
    uiScalePct: Int = 100,
    onUiScalePct: (Int) -> Unit = {},
    pngCompress: Int = 1,
    onPngCompress: (Int) -> Unit = {},
    toolYOffset: Int = 0,
    onToolYOffset: (Int) -> Unit = {},
    sfxOn: Boolean = true,
    onSfxOn: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val settingsCtx = androidx.compose.ui.platform.LocalContext.current
    var cat by remember { mutableIntStateOf(0) }
    var editWmText by remember { mutableStateOf(false) }
    var editSigText by remember { mutableStateOf(false) }
    var colorTarget by remember { mutableIntStateOf(0) }   // 1水印 2签名 3网格 4棋盘A 5棋盘B 6画布外
    var editNum by remember { mutableIntStateOf(0) }       // 1网格宽 2网格高 3棋盘格大小 4网格线宽
    var confirmClear by remember { mutableStateOf(false) }
    // 颜色改动实时预览; 未点“保存”关闭时恢复打开前的颜色
    val origCs = remember { canvasStyle }
    val origWm = remember { watermark }
    fun restoreColors() {
        onCanvasStyle(canvasStyle.copy(
            gridColor = origCs.gridColor, chkA = origCs.chkA,
            chkB = origCs.chkB, outsideBg = origCs.outsideBg
        ))
        onWatermark(watermark.copy(
            wmColor = origWm.wmColor, sigBg = origWm.sigBg
        ))
    }
    fun cancelColors() { restoreColors(); onClose() }
    val cats = listOf("导出与保存", "绘画与操作", "文件与抽屉", "画布外观", "水印与横屏", "软件声明")
    val catIcons = listOf(41, 0, 31, 13, 37, 44)
    val catDescs = listOf(
        "自动保存、导出与项目文件",
        "界面操作、画笔与输入反馈",
        "外部文件与底部抽屉",
        "网格线、棋盘格与画布背景",
        "横屏水印、方向与画布签名",
        "版本、权限与免责声明"
    )
    val catOrder = listOf(1, 3, 0, 2, 4, 5)   // 旧项目分类显示顺序
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = null, indication = null) { cancelColors() })
        Column(
            Modifier.align(Alignment.Center)
                .fillMaxWidth().padding(horizontal = 12.dp).height(520.dp)
                .background(Ep.Panel, RoundedCornerShape(5.dp))
                .border(1.dp, Ep.Line, RoundedCornerShape(5.dp))
        ) {
            Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(cats[cat], color = Ep.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(catDescs[cat], color = Ep.Sub, fontSize = 9.sp)
                }
                // 右上角不再放"保存"按钮: 统一用底部"保存更改"
                Box(Modifier.size(30.dp)
                    .background(Ep.Panel2, RoundedCornerShape(3.dp))
                    .clickable(interactionSource = null, indication = null) { cancelColors() },
                    contentAlignment = Alignment.Center) {
                    Text("✕", color = Ep.Sub, fontSize = 13.sp)
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ep.Line))
            Row(Modifier.weight(1f)) {
                Column(Modifier.width(72.dp).fillMaxHeight()
                    .verticalScroll(rememberScrollState()).padding(5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    catOrder.forEach { i ->
                        val on = i == cat
                        Column(
                            Modifier.fillMaxWidth().height(56.dp)
                                .background(if (on) Ep.HighlightSel else Ep.Panel2, RoundedCornerShape(4.dp))
                                .border(1.dp, if (on) Ep.Accent else Ep.Line, RoundedCornerShape(4.dp))
                                .clickable(interactionSource = null, indication = null) {
                                    cat = i; confirmClear = false
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            EpIcon(catIcons[i], if (on) Color.Black else Color.White, size = 18.dp)
                            Spacer(Modifier.height(2.dp))
                            Text(cats[i], color = if (on) Color.Black else Ep.Text,
                                fontSize = 9.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(Ep.Line))
                Column(Modifier.weight(1f).fillMaxHeight()
                    .verticalScroll(rememberScrollState()).padding(12.dp)) {
                    when (cat) {
                        0 -> {
                            SetRow("定时保存", "自动缓存当前标签") {
                                SetCycle(autoSaveLabel(autoSaveMin)) {
                                    val opts = listOf(0, 1, 5, 10, 30)
                                    val i = opts.indexOf(autoSaveMin).coerceAtLeast(0)
                                    onAutoSaveMin(opts[(i + 1) % opts.size])
                                }
                            }
                            SetRow("PNG 压缩", "导出文件大小与质量（保存时生效）") {
                                val names = listOf("关闭", "无损最大", "量化压缩")
                                SetCycle(names[pngCompress.coerceIn(0, 2)]) {
                                    onPngCompress((pngCompress + 1) % 3)
                                }
                            }
                            SetRow("项目文件夹",
                                if (projectFolder.isBlank()) "默认（系统上次位置）"
                                else "加载/导出项目时直接打开：$projectFolder") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    SetAction("选择") { onPickProjectFolder() }
                                    if (projectFolder.isNotBlank()) {
                                        Spacer(Modifier.width(4.dp))
                                        SetAction("清除") { onClearProjectFolder() }
                                    }
                                }
                            }
                            Text("缓存位置: 应用私有 files/ 目录", color = Ep.Sub, fontSize = 9.sp,
                                modifier = Modifier.padding(top = 8.dp))
                        }
                        1 -> {
                            // 旧项目同款入口: 绘画与操作里直接改左下角快捷键
                            SetRow(
                                "快捷键",
                                "短按 ${ShortcutPrefs.label(ShortcutPrefs.shortId)} · 长按 ${ShortcutPrefs.label(ShortcutPrefs.longId)}"
                            ) {
                                SetAction("更改") { ShortcutPrefs.dialogOpen = true }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("界面", color = Color(0xFF8CA6D9), fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold)
                            SetRow("UI 大小", "整体放大缩小界面（80~125%）") {
                                Row(
                                    Modifier.height(26.dp)
                                        .background(Ep.Panel2, RoundedCornerShape(4.dp))
                                        .border(1.dp, Ep.Line, RoundedCornerShape(4.dp)),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier.size(26.dp).clickable(
                                            interactionSource = null, indication = null
                                        ) { onUiScalePct((uiScalePct - 5).coerceIn(80, 125)) },
                                        contentAlignment = Alignment.Center
                                    ) { Text("−", color = Ep.Accent, fontSize = 14.sp) }
                                    Text(
                                        "${uiScalePct}%", color = Ep.Accent, fontSize = 10.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.width(44.dp)
                                    )
                                    Box(
                                        Modifier.size(26.dp).clickable(
                                            interactionSource = null, indication = null
                                        ) { onUiScalePct((uiScalePct + 5).coerceIn(80, 125)) },
                                        contentAlignment = Alignment.Center
                                    ) { Text("+", color = Ep.Accent, fontSize = 14.sp) }
                                }
                            }
                            SetRow("工具栏高度", "上下位置；6 色调色盘同步移动，随界面缩放保持比例") {
                                Text(
                                    when {
                                        toolYOffset == 0 -> "居中"
                                        toolYOffset > 0 -> "下移${toolYOffset}%"
                                        else -> "上移${-toolYOffset}%"
                                    },
                                    color = Ep.Accent, fontSize = 10.sp
                                )
                            }
                            androidx.compose.material3.Slider(
                                value = toolYOffset.toFloat().coerceIn(-50f, 50f),
                                onValueChange = { onToolYOffset(it.roundToInt()) },
                                valueRange = -50f..50f,
                                modifier = Modifier.fillMaxWidth().height(34.dp)
                                    .padding(horizontal = 2.dp)
                            )
                            Spacer(Modifier.height(4.dp))
                            SetRow("单指移动按钮", "显示底部中间的移动键（与屏幕按钮联动）") {
                                SetToggle(BottomBarPrefs.showMove.value) {
                                    val v = !BottomBarPrefs.showMove.value
                                    BottomBarPrefs.showMove.value = v
                                    settingsCtx.getSharedPreferences("endpix", android.content.Context.MODE_PRIVATE)
                                        .edit().putBoolean("uiShowMove", v).apply()
                                }
                            }
                            SetRow("缩放按钮", "显示底部中间的放大/缩小键") {
                                SetToggle(BottomBarPrefs.showZoom.value) {
                                    val v = !BottomBarPrefs.showZoom.value
                                    BottomBarPrefs.showZoom.value = v
                                    settingsCtx.getSharedPreferences("endpix", android.content.Context.MODE_PRIVATE)
                                        .edit().putBoolean("uiShowZoom", v).apply()
                                }
                            }
                            // 旧项目「信息栏高度」: TopBarCenterY, 0=自动(挖孔高*0.66), 10..320=手动像素
                            SetRow("信息栏高度", "0=自动按前摄比例，或输入像素手动微调") {
                                var yTxt by remember {
                                    mutableStateOf(if (TopBarPrefs.centerY > 0) TopBarPrefs.centerY.toString() else "")
                                }
                                Box(
                                    Modifier.width(64.dp).height(24.dp)
                                        .background(Ep.Panel2, RoundedCornerShape(3.dp))
                                        .border(1.dp, Ep.Line, RoundedCornerShape(3.dp))
                                        .padding(horizontal = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (yTxt.isEmpty()) Text("自动", color = Ep.Accent, fontSize = 10.sp)
                                    BasicTextField(
                                        value = yTxt,
                                        onValueChange = { s ->
                                            val d = s.filter { it.isDigit() }.take(3)
                                            yTxt = d
                                            val v = d.toIntOrNull() ?: 0
                                            val cv = if (v <= 0) 0 else v.coerceIn(10, 320)
                                            if (cv != TopBarPrefs.centerY) {
                                                TopBarPrefs.centerY = cv
                                                TopBarPrefs.onCenterY(cv)
                                            }
                                        },
                                        singleLine = true,
                                        textStyle = androidx.compose.ui.text.TextStyle(
                                            color = Ep.Accent, fontSize = 10.sp, textAlign = TextAlign.Center
                                        ),
                                        cursorBrush = SolidColor(Ep.Accent),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.fillMaxWidth().onFocusChanged { st ->
                                            if (!st.isFocused)
                                                yTxt = if (TopBarPrefs.centerY > 0) TopBarPrefs.centerY.toString() else ""
                                        }
                                    )
                                }
                            }
                            SetRow("性能栏", "隐藏顶部左右信息板") {
                                SetToggle(TopBarPrefs.hidePerf) {
                                    val v = !TopBarPrefs.hidePerf
                                    TopBarPrefs.hidePerf = v
                                    TopBarPrefs.onHidePerf(v)
                                }
                            }
                            SetRow("电量显示", "前摄挖孔电量环") {
                                SetToggle(TopBarPrefs.hideBattery) {
                                    val v = !TopBarPrefs.hideBattery
                                    TopBarPrefs.hideBattery = v
                                    TopBarPrefs.onHideBattery(v)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("输入与反馈", color = Color(0xFF8CA6D9), fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold)
                            SetRow("UI 音效", "按钮点击的合成音") {
                                SetToggle(sfxOn) { onSfxOn(!sfxOn) }
                            }
                            SetRow("双指回滚", "第二指落下时回滚首指笔画的时限") {
                                val label = if (pinchUndoMs <= 0) "关闭" else "${pinchUndoMs / 1000}秒"
                                SetCycle(label) {
                                    val opts = listOf(0, 1000, 2000, 3000, 5000)
                                    val i = opts.indexOf(pinchUndoMs).coerceAtLeast(0)
                                    onPinchUndoMs(opts[(i + 1) % opts.size])
                                }
                            }
                            val holdOpts = listOf(200, 300, 500, 800, 1000, 1500, 2000, 3000)
                            val holdMs = SelectionPrefs.holdMs
                            val holdLabel = if (holdMs % 1000 == 0) "${holdMs / 1000}秒"
                                            else String.format(Locale.US, "%.1f秒", holdMs / 1000f)
                            SetRow("长按裁剪", "选区内按住多久浮起裁剪（0.2–3 秒）") {
                                SetCycle(holdLabel) {
                                    val i = holdOpts.indexOf(holdMs)
                                    SelectionPrefs.set(
                                        if (i >= 0) holdOpts[(i + 1) % holdOpts.size] else holdOpts.first()
                                    )
                                }
                            }
                            Text("画笔大小与透明度仍在左侧工具子菜单里调整。",
                                color = Ep.Sub, fontSize = 9.sp, modifier = Modifier.padding(top = 8.dp))
                            Spacer(Modifier.height(4.dp))
                            Text("选取手势：选区内长按 $holdLabel 裁剪拖动（上方可调）；选区外长按 1 秒瞬移；双指缩放会取消未完成框选。",
                                color = Ep.Sub, fontSize = 9.sp, lineHeight = 13.sp)
                        }
                        2 -> {
                            Text("抽屉", color = Color(0xFF8CA6D9), fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold)
                            SetRow("抽屉内容", "原生版固定底部工具栏") {
                                Text("固定", color = Ep.Accent, fontSize = 10.sp)
                            }
                            SetRow("文件管理器", "使用系统文件选择器（SAF）") {
                                Text("系统", color = Ep.Accent, fontSize = 10.sp)
                            }
                            SetRow("单项目模式", "打开/新建/模板/缓存都替换当前画布，不新增标签") {
                                SetToggle(SingleProjectPrefs.on) {
                                    val v = !SingleProjectPrefs.on
                                    SingleProjectPrefs.set(v)
                                    android.widget.Toast.makeText(settingsCtx,
                                        if (v) "单项目模式：打开和新建会覆盖当前画布" else "已关闭单项目模式",
                                        android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("抽屉内容固定在底部工具栏；标签栏最多 8 个画布；单项目模式下始终只在当前标签替换。",
                                color = Ep.Sub, fontSize = 9.sp, modifier = Modifier.padding(top = 8.dp))
                        }
                        3 -> {
                            val cs = canvasStyle
                            Text("网格线", color = Color(0xFF8CA6D9), fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold)
                            SetRow("网格宽度", "横向间距（画布像素）") {
                                SetAction("${cs.gridW}") { editNum = 1 }
                            }
                            SetRow("网格高度", "纵向间距（画布像素）") {
                                SetAction("${cs.gridH}") { editNum = 2 }
                            }
                            SetRow("网格颜色", "画布网格线颜色") {
                                ColorAction(cs.gridColor, wmColorName(cs.gridColor)) { colorTarget = 3 }
                            }
                            SetRow("淡化度", "网格线不透明度") {
                                val opts = listOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100)
                                SetCycle("${cs.gridAlphaPct}%") {
                                    val i = opts.indexOf(cs.gridAlphaPct).coerceAtLeast(0)
                                    onCanvasStyle(cs.copy(gridAlphaPct = opts[(i + 1) % opts.size]))
                                }
                            }
                            SetRow("线宽", "网格线粗细（屏幕px）") {
                                SetAction(String.format(Locale.US, "%.2f", cs.gridLineW)) { editNum = 4 }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("棋盘格", color = Color(0xFF8CA6D9), fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold)
                            SetRow("网格大小", "棋盘格格子边长（1–64）") {
                                SetAction("${cs.chkTile}") { editNum = 3 }
                            }
                            SetRow("网格颜色 A", "棋盘格第一色") {
                                ColorAction(cs.chkA, wmColorName(cs.chkA)) { colorTarget = 4 }
                            }
                            SetRow("网格颜色 B", "棋盘格第二色") {
                                ColorAction(cs.chkB, wmColorName(cs.chkB)) { colorTarget = 5 }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("其他", color = Color(0xFF8CA6D9), fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold)
                            SetRow("画布外背景色", "画布外围区域颜色") {
                                ColorAction(cs.outsideBg, wmColorName(cs.outsideBg)) { colorTarget = 6 }
                            }
                            SetRow("屏幕放大镜", "测距/HSV 时手指旁放大窗") {
                                SetToggle(cs.magnifier) {
                                    onCanvasStyle(cs.copy(magnifier = !cs.magnifier))
                                }
                            }
                        }
                        4 -> {
                            val wm = watermark
                            Text("横屏水印", color = Color(0xFF8CA6D9), fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold)
                            SetRow("水印名称", "横屏铺满水印文字（当前: ${wm.wmText.replace("\n", " / ").take(16)}）") {
                                SetAction("编辑") { editWmText = true }
                            }
                            SetRow("水印透明度", "5% – 30%") {
                                SetCycle("${wm.wmAlpha}%") {
                                    val vals = listOf(5, 10, 15, 20, 25, 30)
                                    val i = vals.indexOf(wm.wmAlpha).coerceAtLeast(0)
                                    onWatermark(wm.copy(wmAlpha = vals[(i + 1) % vals.size]))
                                }
                            }
                            SetRow("水印颜色", "横屏水印文字颜色") {
                                ColorAction(wm.wmColor, wmColorName(wm.wmColor)) { colorTarget = 1 }
                            }
                            SetRow("水印大小", "水印文字字号（像素 12–72）") {
                                SetAction("${wm.wmSize}px") { editNum = 5 }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("画布签名", color = Color(0xFF8CA6D9), fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold)
                            SetRow("签名大小", "签名文字字号（像素 8–48）") {
                                SetAction("${wm.sigSize}px") { editNum = 6 }
                            }
                            SetRow("签名位置", cornerName(wm.sigCorner)) {
                                SetCycle(cornerName(wm.sigCorner)) {
                                    onWatermark(wm.copy(sigCorner = (wm.sigCorner + 1) % 4))
                                }
                            }
                            SetRow("签名向中心收拢", "横屏/导出生效：距屏幕中心 0–100%（0=贴角，100=居中）") {
                                SetAction("${wm.sigDistPct.coerceIn(0, 100)}%") { editNum = 7 }
                            }
                            SetRow("签名背景颜色", "签名底板颜色") {
                                ColorAction(wm.sigBg, sigBgName(wm.sigBg)) { colorTarget = 2 }
                            }
                            SetRow("签名图标", if (sigIcon != null) "自定义图标（按文本高度适配）" else "内置品牌图标") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    SigIconThumb(sigIcon, 24.dp)
                                    Spacer(Modifier.width(5.dp))
                                    SetAction("选择") { onPickSigIcon() }
                                }
                            }
                            if (sigIcon != null) {
                                SetRow("恢复内置图标", "移除自定义签名图标") {
                                    SetAction("清除") { onClearSigIcon() }
                                }
                            }
                                        val sigLineCount = if (wm.sigText.isEmpty()) 1 else
                                wm.sigText.replace("\r", "").split("\n").count { it.isNotEmpty() }.coerceIn(1, 2)
                            SetRow("签名文字", "最多两行（当前 $sigLineCount 行）") {
                                SetAction("编辑") { editSigText = true }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("签名预览", color = Color(0xFF8CA6D9), fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(5.dp))
                            Box(Modifier.fillMaxWidth().height(96.dp)
                                .background(Ep.Bg, RoundedCornerShape(4.dp))
                                .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))) {
                                SignaturePreviewRow(wm, sigIcon,
                                    Modifier.align(Alignment.TopStart).padding(10.dp))
                            }
                            Text("竖屏：品牌签名在右下角，可用上方开关隐藏，不显示日期时间；横屏与导出由左下角 ☰ 的「个性签名」开关控制，日期只到天并位于签名底板内。",
                                color = Ep.Sub, fontSize = 8.5.sp, lineHeight = 12.sp,
                                modifier = Modifier.padding(top = 6.dp))
                        }
                        5 -> {
                            Text("版本", color = Color(0xFF8CA6D9), fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold)
                            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("endpix", color = Ep.Text, fontSize = 12.sp,
                                    modifier = Modifier.weight(1f))
                                Text(versionName, color = Ep.Accent, fontSize = 11.sp)
                            }
                            // 自动检查更新: 每次进入应用查 GitHub Releases, 有更新弹提示(可关)
                            SetRow("自动检查更新", "进入应用时检查新版本并弹提示") {
                                SetToggle(UpdatePrefs.autoCheck) {
                                    val v = !UpdatePrefs.autoCheck
                                    UpdatePrefs.autoCheck = v
                                    UpdatePrefs.onAutoCheck(v)
                                    if (!v) UpdatePromptHub.hide()
                                }
                            }
                            // 应用内更新: GitHub Releases, 不走 endpix.com
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        when {
                                            UpdateHub.downloading -> "正在下载更新"
                                            UpdateHub.hasUpdate -> "发现新版本 ${UpdateHub.latestTag}"
                                            else -> "软件更新"
                                        },
                                        color = Ep.Text, fontSize = 12.sp
                                    )
                                    Text(
                                        UpdateHub.status,
                                        color = Ep.Sub, fontSize = 8.sp, lineHeight = 11.sp,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                    if (UpdateHub.downloading) {
                                        Spacer(Modifier.height(4.dp))
                                        Box(Modifier.fillMaxWidth().height(3.dp)
                                            .background(Ep.Panel2, RoundedCornerShape(2.dp))) {
                                            Box(Modifier
                                                .fillMaxWidth((UpdateHub.progress.coerceIn(0, 100)) / 100f)
                                                .height(3.dp)
                                                .background(Ep.Accent, RoundedCornerShape(2.dp)))
                                        }
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                val busy = UpdateHub.checking || UpdateHub.downloading
                                Box(
                                    Modifier.height(30.dp)
                                        .background(
                                            if (busy) Ep.Panel2 else Ep.Accent,
                                            RoundedCornerShape(4.dp)
                                        )
                                        .clickable(enabled = !busy) {
                                            if (UpdateHub.hasUpdate)
                                                UpdateHub.downloadAndInstall(settingsCtx)
                                            else UpdateHub.check(settingsCtx)
                                        }
                                        .padding(horizontal = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        when {
                                            UpdateHub.downloading ->
                                                "${UpdateHub.progress.coerceAtLeast(0)}%"
                                            UpdateHub.hasUpdate -> "下载更新"
                                            else -> "检查更新"
                                        },
                                        color = if (busy) Ep.Sub else Ep.Bg,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                            Text("引擎", color = Color(0xFF8CA6D9), fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold)
                            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("C++20 / NEON / GLES3", color = Ep.Text, fontSize = 12.sp,
                                    modifier = Modifier.weight(1f))
                                Text("v2", color = Ep.Accent, fontSize = 11.sp)
                            }
                            Text("权限", color = Color(0xFF8CA6D9), fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold)
                            Text("文件导入/导出使用系统文件选择器（SAF），只在您选择的文件上读写；不申请网络、存储、相机、麦克风等运行时权限。",
                                color = Ep.Sub, fontSize = 9.sp, lineHeight = 13.sp,
                                modifier = Modifier.padding(top = 5.dp))
                            Spacer(Modifier.height(10.dp))
                            Text("免责声明", color = Color(0xFF8CA6D9), fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold)
                            Text("本应用由非专业开发者借助 AI 辅助工具制作，仅供个人学习使用，不用于任何商业用途。\n本应用按「原样」提供，不做任何保证。因使用本应用造成的任何损失、数据丢失、设备损坏或其他问题，开发者不承担任何责任。\n使用本应用即表示您已阅读并同意以上条款。",
                                color = Ep.Sub, fontSize = 9.sp, lineHeight = 14.sp,
                                modifier = Modifier.padding(top = 5.dp))
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ep.Line))
            Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("颜色实时预览；点保存后保留", color = Ep.Sub, fontSize = 8.5.sp)
                Spacer(Modifier.weight(1f))
                Box(Modifier.width(112.dp).height(30.dp)
                    .background(Ep.Accent, RoundedCornerShape(4.dp))
                    .clickable(interactionSource = null, indication = null) { onClose() },
                    contentAlignment = Alignment.Center) {
                    Text("保存更改", color = Ep.Bg, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        // 快捷键设置浮窗(短按/长按功能选择)
        ShortcutSettingsDialogHost()
        if (colorTarget != 0) {
            val target = colorTarget
            val cur = when (target) {
                1 -> watermark.wmColor
                2 -> watermark.sigBg
                3 -> canvasStyle.gridColor
                4 -> canvasStyle.chkA
                5 -> canvasStyle.chkB
                else -> canvasStyle.outsideBg
            }
            HsvDialog(
                current = cur,
                onColor = { c ->
                    when (target) {
                        1 -> onWatermark(watermark.copy(wmColor = (c and 0xFFFFFF) or 0xFF000000.toInt()))
                        2 -> onWatermark(watermark.copy(sigBg =
                            (c and 0xFFFFFF) or (watermark.sigBg and 0xFF000000.toInt())))
                        3 -> onCanvasStyle(canvasStyle.copy(gridColor = (c and 0xFFFFFF) or 0xFF000000.toInt()))
                        4 -> onCanvasStyle(canvasStyle.copy(chkA = (c and 0xFFFFFF) or 0xFF000000.toInt()))
                        5 -> onCanvasStyle(canvasStyle.copy(chkB = (c and 0xFFFFFF) or 0xFF000000.toInt()))
                        else -> onCanvasStyle(canvasStyle.copy(outsideBg = (c and 0xFFFFFF) or 0xFF000000.toInt()))
                    }
                },
                onClose = { colorTarget = 0 }
            )
        }
        if (editNum != 0) {
            val target = editNum
            val (title, init, maxLen) = when (target) {
                1 -> Triple("网格宽度", "${canvasStyle.gridW}", 4)
                2 -> Triple("网格高度", "${canvasStyle.gridH}", 4)
                3 -> Triple("棋盘格大小", "${canvasStyle.chkTile}", 2)
                5 -> Triple("水印大小", "${watermark.wmSize}", 2)
                6 -> Triple("签名大小", "${watermark.sigSize}", 2)
                7 -> Triple("签名向中心收拢", "${watermark.sigDistPct.coerceIn(0, 100)}", 3)
                else -> Triple("网格线宽", String.format(Locale.US, "%.2f", canvasStyle.gridLineW), 5)
            }
            TextEditDialog(title, init, maxLen,
                onDismiss = { editNum = 0 },
                onOk = { v ->
                    if (target == 4) {
                        v.trim().toFloatOrNull()?.let { x ->
                            onCanvasStyle(canvasStyle.copy(gridLineW = x.coerceIn(0.25f, 20f)))
                        }
                    } else {
                        v.trim().toIntOrNull()?.let { x ->
                            when (target) {
                                1 -> onCanvasStyle(canvasStyle.copy(gridW = x.coerceIn(1, 2000)))
                                2 -> onCanvasStyle(canvasStyle.copy(gridH = x.coerceIn(1, 2000)))
                                3 -> onCanvasStyle(canvasStyle.copy(chkTile = x.coerceIn(1, 64)))
                                5 -> onWatermark(watermark.copy(wmSize = x.coerceIn(12, 72)))
                                6 -> onWatermark(watermark.copy(sigSize = x.coerceIn(8, 48)))
                                7 -> onWatermark(watermark.copy(sigDistPct = x.coerceIn(0, 100)))
                            }
                        }
                    }
                    editNum = 0
                })
        }
        if (editWmText) {
            TextEditDialog("水印文字", watermark.wmText, 40, multiline = false,
                onDismiss = { editWmText = false },
                onOk = { onWatermark(watermark.copy(wmText = it)); editWmText = false })
        }
        if (editSigText) {
            TextEditDialog("签名文字（最多两行）", watermark.sigText, 80, multiline = true,
                onDismiss = { editSigText = false },
                onOk = { onWatermark(watermark.copy(sigText = it)); editSigText = false })
        }
    }
}

@Composable
private fun SignaturePreviewRow(
    wm: WatermarkSettings,
    sigIcon: android.graphics.Bitmap?,
    modifier: Modifier = Modifier,
) {
    val lines = remember(wm.sigText) {
        val t = wm.sigText.replace("\r", "")
        val l = t.split("\n").filter { it.isNotEmpty() }.take(2)
        if (t.isEmpty()) listOf("Immersion technology", "浸没制造") else l
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
            if (sigIcon != null) {
                Image(sigIcon.asImageBitmap(), contentDescription = null,
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                Canvas(Modifier.fillMaxSize()) {
                    drawBrandIconNative(drawContext.canvas.nativeCanvas, 0f, 0f,
                        this.size.width, 242)
                }
            }
        }
        Spacer(Modifier.width(9.dp))
        Column {
            lines.forEach { ln ->
                Text(ln, color = Color.White, fontSize = 12.sp)
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

// 调整画布大小面板(内容部分, 供「画布」合并对话框使用)
@Composable
fun ResizeCanvasPanel(
    currentW: Int, currentH: Int,
    onDismiss: () -> Unit, onApply: (Int, Int, Int, Int) -> Unit,
    thumb: IntArray? = null,
) {
    var w by remember { mutableStateOf(currentW.toString()) }
    var h by remember { mutableStateOf(currentH.toString()) }
    var ax by remember { mutableIntStateOf(1) }
    var ay by remember { mutableIntStateOf(1) }
    val thumbImg = remember(thumb) { thumbSquareToImage(thumb) }
    val presets = listOf(64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384)
    Column(Modifier.fillMaxWidth()) {
        ResizePreview(currentW, currentH, w, h, ax, ay, thumbImg)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text("宽度", color = Ep.Sub, fontSize = 9.sp, textAlign = TextAlign.Center,
                modifier = Modifier.width(96.dp))
            Spacer(Modifier.width(28.dp))
            Text("高度", color = Ep.Sub, fontSize = 9.sp, textAlign = TextAlign.Center,
                modifier = Modifier.width(96.dp))
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            DialogField(w, { if (it.length <= 5) w = it.filter(Char::isDigit) },
                numeric = true, fontSize = 20.sp, center = true,
                modifier = Modifier.width(96.dp))
            Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                Text("×", color = Ep.Sub, fontSize = 14.sp)
            }
            DialogField(h, { if (it.length <= 5) h = it.filter(Char::isDigit) },
                numeric = true, fontSize = 20.sp, center = true,
                modifier = Modifier.width(96.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text("快捷尺寸", color = Ep.Sub, fontSize = 8.5.sp)
        Spacer(Modifier.height(4.dp))
        val sel = w.toIntOrNull()
        val selH = h.toIntOrNull()
        presets.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { p ->
                    PresetChip(p.toString(), sel == p && selH == p, Modifier.weight(1f)) {
                        w = p.toString(); h = p.toString()
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text("锚点", color = Ep.Sub, fontSize = 8.5.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                for (yy in 0..2) {
                    Row {
                        for (xx in 0..2) {
                            val on = ax == xx && ay == yy
                            Box(
                                Modifier.padding(2.dp).size(26.dp)
                                    .background(if (on) Ep.Accent.copy(alpha = 0.35f) else Ep.Panel2,
                                        RoundedCornerShape(3.dp))
                                    .border(1.dp, if (on) Ep.Accent else Ep.Line, RoundedCornerShape(3.dp))
                                    .clickable(interactionSource = null, indication = null) {
                                        ax = xx; ay = yy
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(Modifier.size(if (on) 8.dp else 5.dp)
                                    .background(if (on) Ep.Accent else Ep.Sub, RoundedCornerShape(4.dp)))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("内容对齐", color = Ep.Sub, fontSize = 8.5.sp)
                Spacer(Modifier.height(3.dp))
                Text(anchorName(ax, ay), color = Ep.Accent, fontSize = 11.sp)
                Text("超出部分裁切，新增区域透明", color = Ep.Sub, fontSize = 8.sp)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            DlgBtn("取消", width = 62.dp) { onDismiss() }
            Spacer(Modifier.width(8.dp))
            DlgBtn("应用", accent = true, width = 62.dp) {
                val ww = (w.toIntOrNull() ?: currentW).coerceIn(4, 16834)
                val hh = (h.toIntOrNull() ?: currentH).coerceIn(4, 16834)
                onApply(ww, hh, ax, ay)
            }
        }
    }
}

@Composable
private fun ResizePreview(
    curW: Int, curH: Int, wText: String, hText: String,
    ax: Int, ay: Int, thumb: ImageBitmap?,
) {
    val ww = (wText.toIntOrNull() ?: curW).coerceIn(4, 16834)
    val hh = (hText.toIntOrNull() ?: curH).coerceIn(4, 16834)
    Column {
        Canvas(
            Modifier.fillMaxWidth().height(110.dp)
                .background(Ep.Bg, RoundedCornerShape(4.dp))
                .border(1.dp, Ep.Line, RoundedCornerShape(4.dp)).padding(3.dp)
        ) {
            val cell = 8.dp.toPx()
            var yy = 0f
            var row = 0
            while (yy < size.height) {
                var xx = 0f
                var col = 0
                while (xx < size.width) {
                    if (((row + col) and 1) == 0)
                        drawRect(Color(0xFF191D23), Offset(xx, yy), Size(cell, cell))
                    xx += cell; col++
                }
                yy += cell; row++
            }
            val pad = 7.dp.toPx()
            val sc = minOf((size.width - pad * 2) / maxOf(curW, ww),
                (size.height - pad * 2) / maxOf(curH, hh)).coerceAtLeast(0.0001f)
            val nw = ww * sc
            val nh = hh * sc
            val nx = (size.width - nw) * 0.5f
            val ny = (size.height - nh) * 0.5f
            val ow = curW * sc
            val oh = curH * sc
            val ox = nx + when (ax) { 0 -> 0f; 1 -> (nw - ow) * 0.5f; else -> nw - ow }
            val oy = ny + when (ay) { 0 -> 0f; 1 -> (nh - oh) * 0.5f; else -> nh - oh }
            if (thumb != null) {
                val dstO = IntOffset(ox.toInt(), oy.toInt())
                val dstS = IntSize(maxOf(2, ow.toInt()), maxOf(2, oh.toInt()))
                // 1) 整张旧画布先半透明画出来: 缩小时"会被裁掉的区域"仍然可见(变暗), 不是直接消失
                drawImage(thumb, dstOffset = dstO, dstSize = dstS,
                    alpha = 0.32f, filterQuality = FilterQuality.None)
                // 2) 落进新画布红框内的保留部分全不透明
                clipRect(nx, ny, nx + nw, ny + nh) {
                    drawImage(thumb, dstOffset = dstO, dstSize = dstS,
                        alpha = 1f, filterQuality = FilterQuality.None)
                }
                // 3) 旧画布范围细灰框, 方便看清新旧尺寸差
                drawRect(Color(0x55E6EDF3), Offset(ox, oy), Size(ow, oh),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            } else {
                drawRect(Color(0x66888888), Offset(ox, oy), Size(maxOf(2f, ow), maxOf(2f, oh)))
            }
            drawRect(Color(0xFFF24A4A), Offset(nx, ny), Size(nw, nh),
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        }
        Spacer(Modifier.height(3.dp))
        Text("${curW}×${curH} → ${ww}×${hh} · ${anchorName(ax, ay)}",
            color = Ep.Sub, fontSize = 8.5.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth())
    }
}

private fun anchorName(ax: Int, ay: Int): String {
    if (ax == 1 && ay == 1) return "居中"
    val v = when (ay) { 0 -> "上"; 1 -> ""; else -> "下" }
    val h = when (ax) { 0 -> "左"; 1 -> ""; else -> "右" }
    return v + h
}

private val WM_COLORS = listOf(
    0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF9AA4B2.toInt(),
    0xFFFF5555.toInt(), 0xFF55A0FF.toInt()
)
private val SIG_BGS = listOf(
    0x99000000.toInt(), 0xCC000000.toInt(), 0x33FFFFFF.toInt(), 0x00000000
)
private fun wmColorName(c: Int): String {
    val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
    val hsl = FloatArray(3)
    android.graphics.Color.RGBToHSV(r, g, b, hsl)
    if (hsl[1] < 0.12f) {
        val gray = (r + g + b) / 3
        return when {
            gray >= 240 -> "白色"
            gray <= 25 -> "黑色"
            gray >= 150 -> "浅灰"
            else -> "灰色"
        }
    }
    return when (hsl[0]) {
        in 0f..14f, in 345f..360f -> "红色"
        in 15f..44f -> "橙色"
        in 45f..69f -> "黄色"
        in 70f..164f -> "绿色"
        in 165f..194f -> "青色"
        in 195f..254f -> "蓝色"
        in 255f..284f -> "紫色"
        else -> "品红"
    }
}

private fun sigBgName(c: Int): String {
    return when (c and 0x00FFFFFF) {
        0x1A1A1A -> "深灰 70%"
        0x000000 -> "黑色 70%"
        0xFFFFFF -> "白色 70%"
        else -> "自定义 70%"
    }
}

@Composable
private fun DlgBtn(
    text: String,
    accent: Boolean = false,
    danger: Boolean = false,
    width: androidx.compose.ui.unit.Dp = 70.dp,
    onClick: () -> Unit,
) {
    Box(
        Modifier.width(width).height(30.dp)
            .background(if (accent) Ep.Accent else Ep.Panel2, RoundedCornerShape(4.dp))
            .border(1.dp, when {
                danger -> Color(0xFFE05A5A)
                accent -> Ep.Accent
                else -> Ep.Line
            }, RoundedCornerShape(4.dp))
            .clickable(interactionSource = null, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = when {
            accent -> Ep.Bg
            danger -> Color(0xFFE05A5A)
            else -> Ep.Text
        }, fontSize = 10.5.sp)
    }
}

@Composable
private fun TextEditDialog(
    title: String,
    value: String,
    maxLen: Int,
    onDismiss: () -> Unit,
    onOk: (String) -> Unit,
    multiline: Boolean = false,
) {
    var text by remember { mutableStateOf(value) }
    Dialog(onDismissRequest = { }) {
        Column(Modifier.width(280.dp).background(Ep.Panel, RoundedCornerShape(6.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(6.dp)).padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = Ep.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("最多 $maxLen 字", color = Ep.Sub, fontSize = 8.5.sp)
                }
                DlgClose(onDismiss)
            }
            Spacer(Modifier.height(10.dp))
            DialogField(text, { text = it.take(maxLen) },   // 粘贴超长文本时截断, 不整段丢弃
                numeric = false, fontSize = 14.sp, multiline = multiline)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                DlgBtn("取消", width = 62.dp) { onDismiss() }
                Spacer(Modifier.width(8.dp))
                DlgBtn("确定", accent = true, width = 62.dp) { onOk(text) }
            }
        }
    }
}

private fun autoSaveLabel(min: Int): String = when (min) {
    0 -> "关闭"
    1 -> "1分钟"
    5 -> "5分钟"
    10 -> "10分钟"
    30 -> "30分钟"
    else -> "${min}分钟"
}

@Composable
private fun SetRow(label: String, sub: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(label, color = Ep.Text, fontSize = 12.sp)
            if (sub.isNotEmpty()) Text(sub, color = Ep.Sub, fontSize = 9.sp)
        }
        control()
    }
}

@Composable
private fun SetToggle(on: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier.size(34.dp, 19.dp)
            .background(if (on) Ep.Accent else Ep.Panel2, RoundedCornerShape(10.dp))
            .border(1.dp, if (on) Ep.Accent else Ep.Line, RoundedCornerShape(10.dp))
            .clickable(interactionSource = null, indication = null) { onToggle() }
            .padding(2.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart
    ) { Box(Modifier.size(14.dp).background(Color.White, RoundedCornerShape(7.dp))) }
}

@Composable
private fun SetAction(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.height(26.dp).background(Ep.Panel2, RoundedCornerShape(4.dp))
            .border(1.dp, if (danger) Color(0xFFE05A5A) else Ep.Line, RoundedCornerShape(4.dp))
            .clickable(interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = if (danger) Color(0xFFE05A5A) else Ep.Text, fontSize = 10.sp) }
}

@Composable
private fun ColorAction(c: Int, text: String, onClick: () -> Unit) {
    Row(
        Modifier.height(26.dp).background(Ep.Panel2, RoundedCornerShape(4.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
            .clickable(interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(14.dp).background(Color(c), RoundedCornerShape(2.dp))
            .border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(5.dp))
        Text(text, color = Ep.Text, fontSize = 10.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun SetCycle(text: String, onClick: () -> Unit) {
    Box(
        Modifier.height(26.dp).background(Ep.Panel2, RoundedCornerShape(4.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
            .clickable(interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Ep.Accent, fontSize = 10.sp) }
}

@Composable
private fun SigIconThumb(icon: android.graphics.Bitmap?, size: androidx.compose.ui.unit.Dp) {
    if (icon != null) {
        Image(
            icon.asImageBitmap(), contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size)
        )
    } else {
        Canvas(Modifier.size(size)) {
            drawBrandIconNative(drawContext.canvas.nativeCanvas, 0f, 0f,
                this.size.width, 255)
        }
    }
}

// 画布签名设置(横屏☰快捷入口): 与「调整设置 → 水印与横屏 → 画布签名」相同的设置项
@Composable
fun SignatureSettingsDialog(
    wm: WatermarkSettings,
    onWatermark: (WatermarkSettings) -> Unit,
    sigIcon: android.graphics.Bitmap?,
    onPickSigIcon: () -> Unit,
    onClearSigIcon: () -> Unit,
    onDismiss: () -> Unit,
) {
    var editNum by remember { mutableIntStateOf(0) }   // 6=签名大小 7=向中心收拢
    var editText by remember { mutableStateOf(false) }
    var colorOpen by remember { mutableStateOf(false) }
    val latestWm by rememberUpdatedState(wm)
    // 子窗(调色板/文字/数字)打开时不要再叠一个 Dialog: 否则设置面板会压在调色板上面
    val subOpen = colorOpen || editNum != 0 || editText
    if (!subOpen) Dialog(onDismissRequest = { }) {
        Column(
            Modifier.width(300.dp).background(Ep.Panel, RoundedCornerShape(6.dp))
                .border(1.dp, Ep.Line, RoundedCornerShape(6.dp)).padding(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("画布签名", color = Ep.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("与调整设置里的签名设置相同", color = Ep.Sub, fontSize = 8.5.sp)
                }
                DlgClose(onDismiss)
            }
            Spacer(Modifier.height(4.dp))
            Column(
                Modifier.fillMaxWidth().heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                SetRow("签名大小", "签名文字字号（像素 8–48）") {
                    SetAction("${wm.sigSize}px") { editNum = 6 }
                }
                SetRow("签名位置", cornerName(wm.sigCorner)) {
                    SetCycle(cornerName(wm.sigCorner)) {
                        onWatermark(latestWm.copy(sigCorner = (wm.sigCorner + 1) % 4))
                    }
                }
                SetRow("签名向中心收拢", "横屏/导出生效：距屏幕中心 0–100%（0=贴角，100=居中）") {
                    SetAction("${wm.sigDistPct.coerceIn(0, 100)}%") { editNum = 7 }
                }
                SetRow("签名背景颜色", "签名底板颜色") {
                    ColorAction(wm.sigBg, sigBgName(wm.sigBg)) { colorOpen = true }
                }
                SetRow("签名图标", if (sigIcon != null) "自定义图标（按文本高度适配）" else "内置品牌图标") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SigIconThumb(sigIcon, 24.dp)
                        Spacer(Modifier.width(5.dp))
                        SetAction("选择") { onPickSigIcon() }
                    }
                }
                if (sigIcon != null) {
                    SetRow("恢复内置图标", "移除自定义签名图标") {
                        SetAction("清除") { onClearSigIcon() }
                    }
                }
                SetRow("签名日期", "横屏/导出签名底板内显示日期") {
                    SetToggle(wm.sigDate) {
                        onWatermark(latestWm.copy(sigDate = !wm.sigDate))
                    }
                }
                val sigLineCount = if (wm.sigText.isEmpty()) 1 else
                    wm.sigText.replace("\r", "").split("\n").count { it.isNotEmpty() }.coerceIn(1, 2)
                SetRow("签名文字", "最多两行（当前 $sigLineCount 行）") {
                    SetAction("编辑") { editText = true }
                }
            }
        }
    }
    if (colorOpen) {
        HsvDialog(
            current = wm.sigBg,
            onColor = { c ->
                onWatermark(latestWm.copy(sigBg = (c and 0xFFFFFF) or (wm.sigBg and 0xFF000000.toInt())))
            },
            onClose = { colorOpen = false }
        )
    }
    if (editNum != 0) {
        val target = editNum
        val (title, init, maxLen) = when (target) {
            6 -> Triple("签名大小", "${wm.sigSize}", 2)
            else -> Triple("签名向中心收拢", "${wm.sigDistPct.coerceIn(0, 100)}", 3)
        }
        TextEditDialog(title, init, maxLen,
            onDismiss = { editNum = 0 },
            onOk = { v ->
                v.trim().toIntOrNull()?.let { x ->
                    when (target) {
                        6 -> onWatermark(latestWm.copy(sigSize = x.coerceIn(8, 48)))
                        else -> onWatermark(latestWm.copy(sigDistPct = x.coerceIn(0, 100)))
                    }
                }
                editNum = 0
            })
    }
    if (editText) {
        TextEditDialog("签名文字（最多两行）", wm.sigText, 80, multiline = true,
            onDismiss = { editText = false },
            onOk = { onWatermark(latestWm.copy(sigText = it)); editText = false })
    }
}

// 模板库(旧项目模板图片, 不做旧模板系统): 4 张预置模板, 点击=新建对应尺寸画布并导入
private data class TemplateItem(
    val index: Int, val name: String, val w: Int, val h: Int,
    val bmp: androidx.compose.ui.graphics.ImageBitmap?,
)

@Composable
fun TemplateGalleryDialog(onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val items = remember {
        (1..4).map { i ->
            val path = "templates/t$i.webp"
            try {
                val o1 = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.assets.open(path).use { android.graphics.BitmapFactory.decodeStream(it, null, o1) }
                val w = o1.outWidth
                val h = o1.outHeight
                val o2 = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = maxOf(1, maxOf(w, h) / 360)
                }
                val bmp = ctx.assets.open(path).use {
                    android.graphics.BitmapFactory.decodeStream(it, null, o2)
                }
                TemplateItem(i, "模板$i", w, h, bmp?.asImageBitmap())
            } catch (_: Throwable) {
                TemplateItem(i, "模板$i", 0, 0, null)
            }
        }
    }
    Dialog(onDismissRequest = { }) {
        Column(
            Modifier.width(300.dp).background(Ep.Panel, RoundedCornerShape(6.dp))
                .border(1.dp, Ep.Line, RoundedCornerShape(6.dp)).padding(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("模板库", color = Ep.Text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("内置模板 · 点击创建对应尺寸的新画布", color = Ep.Sub, fontSize = 8.5.sp)
                }
                DlgClose(onDismiss)
            }
            Spacer(Modifier.height(10.dp))
            items.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { t ->
                        Column(
                            Modifier.weight(1f)
                                .background(Ep.Panel2, RoundedCornerShape(4.dp))
                                .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
                                .clickable(interactionSource = null, indication = null) {
                                    onPick(t.index)
                                }
                                .padding(6.dp)
                        ) {
                            Box(
                                Modifier.fillMaxWidth().height(92.dp)
                                    .background(Color(0xFF0B0E13), RoundedCornerShape(3.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (t.bmp != null) {
                                    Image(t.bmp, contentDescription = null,
                                        modifier = Modifier.fillMaxSize().padding(3.dp),
                                        contentScale = ContentScale.Fit)
                                } else {
                                    Text("载入失败", color = Ep.Sub, fontSize = 9.sp)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(t.name, color = Ep.Text, fontSize = 10.sp, maxLines = 1)
                            Text("${t.w}×${t.h}", color = Ep.Sub, fontSize = 8.sp)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
