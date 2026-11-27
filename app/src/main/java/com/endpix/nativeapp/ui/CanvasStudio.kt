package com.endpix.nativeapp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.launch
import java.io.File

// 「画布」合并对话框: 新建画布 / 调整画布大小 / 模板库 三种内容放在同一窗口,
// 左上角用展开菜单切换; 外部各入口(设置菜单、标签栏 +、调整设置)仍打开各自对应的模式。
enum class StudioMode(val label: String) {
    NEW("新建画布"), RESIZE("调整画布大小"), TEMPLATES("模板库"), CACHE("缓存列表");
    companion object {
        fun of(index: Int) = entries.getOrElse(index) { NEW }
    }
}

data class TemplateEntry(
    val id: String,          // 内置 "a1".."a4"; 自定义 "u:<文件名>"
    val name: String,
    val w: Int,
    val h: Int,
    val builtin: Boolean,
    val preview: ImageBitmap?,
)

@Composable
fun CanvasStudioDialog(
    initialMode: StudioMode,
    defaultW: Int,
    defaultH: Int,
    currentW: Int,
    currentH: Int,
    resizeThumb: IntArray?,
    templateRev: Int,
    onDismiss: () -> Unit,
    onCreateCanvas: (Int, Int) -> Unit,
    onResizeCanvas: (Int, Int, Int, Int) -> Unit,
    onPickTemplate: (String) -> Unit,
    onImportTemplate: () -> Unit,
    onExportTemplate: (String) -> Unit,
    onDeleteTemplate: (String, Boolean) -> Unit,
) {
    var mode by remember { mutableStateOf(initialMode) }
    var menuOpen by remember { mutableStateOf(false) }
    val dens = LocalDensity.current
    val subtitle = when (mode) {
        StudioMode.NEW -> "像素尺寸 1 – 16834"
        StudioMode.RESIZE -> "当前 ${currentW} × ${currentH} · 调整不可撤销"
        StudioMode.TEMPLATES -> "点击模板创建画布 · 支持导入 / 导出 / 删除"
        StudioMode.CACHE -> "点击打开缓存项目 · 含画布状态与步骤 · 可删除"
    }
    Dialog(onDismissRequest = { }) {
        Column(
            Modifier.width(300.dp).background(Ep.Panel, RoundedCornerShape(6.dp))
                .border(1.dp, Ep.Line, RoundedCornerShape(6.dp)).padding(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Row(
                        Modifier.height(30.dp)
                            .background(Ep.Panel2, RoundedCornerShape(4.dp))
                            .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
                            .clickable(interactionSource = null, indication = null) { menuOpen = !menuOpen }
                            .padding(horizontal = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(mode.label, color = Ep.Text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(5.dp))
                        Text("▾", color = Ep.Sub, fontSize = 10.sp)
                    }
                    if (menuOpen) {
                        Popup(
                            alignment = Alignment.TopStart,
                            offset = IntOffset(0, with(dens) { 36.dp.roundToPx() }),
                            onDismissRequest = { menuOpen = false },
                            properties = PopupProperties(focusable = true)
                        ) {
                            Column(
                                Modifier.width(132.dp).background(Ep.Panel, RoundedCornerShape(5.dp))
                                    .border(1.dp, Ep.Line, RoundedCornerShape(5.dp)).padding(4.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                StudioMode.entries.forEach { m ->
                                    val on = m == mode
                                    Box(
                                        Modifier.fillMaxWidth().height(30.dp)
                                            .background(if (on) Ep.Accent.copy(alpha = 0.22f) else Color.Transparent,
                                                RoundedCornerShape(3.dp))
                                            .clickable(interactionSource = null, indication = null) {
                                                mode = m; menuOpen = false
                                            }
                                            .padding(horizontal = 8.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        Text(m.label, color = if (on) Ep.Accent else Ep.Text, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.width(9.dp))
                Text(subtitle, color = Ep.Sub, fontSize = 8.sp, maxLines = 2,
                    modifier = Modifier.weight(1f))
                DlgCloseLocal(onDismiss)
            }
            Spacer(Modifier.height(10.dp))
            when (mode) {
                StudioMode.NEW -> NewCanvasPanel(
                    defaultW = defaultW, defaultH = defaultH,
                    onDismiss = onDismiss, onCreate = onCreateCanvas
                )
                StudioMode.RESIZE -> ResizeCanvasPanel(
                    currentW = currentW, currentH = currentH,
                    onDismiss = onDismiss, onApply = onResizeCanvas,
                    thumb = resizeThumb
                )
                StudioMode.TEMPLATES -> TemplateGalleryPanel(
                    rev = templateRev,
                    onPick = onPickTemplate,
                    onImport = onImportTemplate,
                    onExport = onExportTemplate,
                    onDelete = onDeleteTemplate
                )
                StudioMode.CACHE -> CacheGalleryPanel()
            }
        }
    }
}

@Composable
private fun DlgCloseLocal(onClose: () -> Unit) {
    Box(Modifier.size(24.dp).background(Ep.Panel2, RoundedCornerShape(4.dp))
        .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
        .clickable(interactionSource = null, indication = null) { onClose() },
        contentAlignment = Alignment.Center) {
        Text("✕", color = Ep.Sub, fontSize = 12.sp)
    }
}

// ---------------- 模板库 ----------------
@Composable
fun TemplateGalleryPanel(
    rev: Int,
    onPick: (String) -> Unit,
    onImport: () -> Unit,
    onExport: (String) -> Unit,
    onDelete: (String, Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("endpix", android.content.Context.MODE_PRIVATE) }
    var hidden by remember(rev) {
        mutableStateOf(prefs.getStringSet("hiddenTemplates", emptySet()) ?: emptySet())
    }
    val items = remember(rev, hidden) { loadTemplates(ctx, hidden) }
    var pendingDelete by remember { mutableStateOf<TemplateEntry?>(null) }
    var pendingExport by remember { mutableStateOf<TemplateEntry?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 356.dp).verticalScroll(rememberScrollState())
        ) {
            items.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { t ->
                        TemplateCard(
                            t = t, modifier = Modifier.weight(1f),
                            onPick = { onPick(t.id) },
                            onExport = { pendingExport = t },
                            onDelete = { pendingDelete = t }
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
            }
            if (items.isEmpty()) {
                Text("还没有模板", color = Ep.Sub, fontSize = 9.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.height(30.dp)
                    .background(Ep.Panel2, RoundedCornerShape(4.dp))
                    .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
                    .clickable(interactionSource = null, indication = null) { onImport() }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) { Text("导入模板", color = Ep.Text, fontSize = 10.sp) }
            if (hidden.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.height(30.dp)
                        .background(Ep.Panel2, RoundedCornerShape(4.dp))
                        .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
                        .clickable(interactionSource = null, indication = null) {
                            prefs.edit().remove("hiddenTemplates").apply()
                            hidden = emptySet()
                        }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) { Text("恢复内置", color = Ep.Sub, fontSize = 9.sp) }
            }
            Spacer(Modifier.weight(1f))
            Text("${items.size} 个", color = Ep.Sub, fontSize = 8.5.sp)
        }
    }

    pendingDelete?.let { t ->
        Dialog(onDismissRequest = { }) {
            Column(
                Modifier.width(240.dp).background(Ep.Panel, RoundedCornerShape(6.dp))
                    .border(1.dp, Ep.Line, RoundedCornerShape(6.dp)).padding(14.dp)
            ) {
                Text("删除模板", color = Ep.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (t.builtin) "「${t.name}」是内置模板，删除后可用「恢复内置」找回。"
                    else "确定删除「${t.name}」？文件会从模板库移除。",
                    color = Ep.Sub, fontSize = 9.sp, lineHeight = 13.sp
                )
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    DlgBtn("取消", width = 62.dp) { pendingDelete = null }
                    Spacer(Modifier.width(8.dp))
                    DlgBtn("删除", danger = true, width = 62.dp) {
                        pendingDelete = null
                        if (t.builtin) hidden = hidden + t.id
                        onDelete(t.id, t.builtin)
                    }
                }
            }
        }
    }

    pendingExport?.let { t ->
        ExportModeDialog(
            title = "导出「${t.name}」",
            exportText = "导出模板文件",
            onExport = {
                pendingExport = null
                onExport(t.id)
            },
            onShare = {
                pendingExport = null
                StudioShareHub.shareTemplate?.invoke(t.id)
            },
            onDismiss = { pendingExport = null }
        )
    }
}

@Composable
private fun TemplateCard(
    t: TemplateEntry,
    modifier: Modifier = Modifier,
    onPick: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier
            .background(Ep.Panel2, RoundedCornerShape(4.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
            .clickable(interactionSource = null, indication = null) { onPick() }
            .padding(6.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().height(84.dp)
                .background(Color(0xFF0B0E13), RoundedCornerShape(3.dp))
        ) {
            if (t.preview != null) {
                Image(t.preview, contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(3.dp),
                    contentScale = ContentScale.Fit)
            } else {
                Text("载入失败", color = Ep.Sub, fontSize = 9.sp,
                    modifier = Modifier.align(Alignment.Center))
            }
            Row(
                Modifier.align(Alignment.TopEnd).padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                TinyAction("↑") { onExport() }
                TinyAction("✕") { onDelete() }
            }
            if (t.builtin) {
                Box(
                    Modifier.align(Alignment.BottomStart).padding(3.dp)
                        .background(Color(0xAA000000), RoundedCornerShape(2.dp))
                        .padding(horizontal = 3.dp, vertical = 1.dp)
                ) { Text("内置", color = Ep.Sub, fontSize = 7.sp) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(t.name, color = Ep.Text, fontSize = 9.5.sp, maxLines = 1)
        Text("${t.w}×${t.h}", color = Ep.Sub, fontSize = 8.sp)
    }
}

@Composable
private fun TinyAction(text: String, onClick: () -> Unit) {
    Box(
        Modifier.size(17.dp)
            .background(Color(0xCC10141A), RoundedCornerShape(3.dp))
            .border(1.dp, Ep.Line.copy(alpha = 0.7f), RoundedCornerShape(3.dp))
            .clickable(interactionSource = null, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(text, color = Ep.Sub, fontSize = 8.sp) }
}

private fun loadTemplates(ctx: android.content.Context, hidden: Set<String>): List<TemplateEntry> {
    val out = mutableListOf<TemplateEntry>()
    for (i in 1..4) {
        val id = "a$i"
        if (id in hidden) continue
        val path = "templates/t$i.webp"
        var w = 0
        var h = 0
        var bmp: android.graphics.Bitmap? = null
        try {
            val o1 = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.assets.open(path).use { android.graphics.BitmapFactory.decodeStream(it, null, o1) }
            w = o1.outWidth; h = o1.outHeight
            val o2 = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = maxOf(1, maxOf(w, h) / 240)
            }
            bmp = ctx.assets.open(path).use { android.graphics.BitmapFactory.decodeStream(it, null, o2) }
        } catch (_: Throwable) { }
        out += TemplateEntry(id, "模板$i", w, h, true, bmp?.asImageBitmap())
    }
    val dir = File(ctx.filesDir, "templates")
    dir.listFiles()?.filter { it.isFile && it.name.startsWith("u_") && it.name.endsWith(".webp") }
        ?.sortedByDescending { it.lastModified() }
        ?.forEach { f ->
            var w = 0
            var h = 0
            var bmp: android.graphics.Bitmap? = null
            try {
                val o1 = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, o1)
                w = o1.outWidth; h = o1.outHeight
                val o2 = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = maxOf(1, maxOf(w, h) / 240)
                }
                bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath, o2)
            } catch (_: Throwable) { }
            out += TemplateEntry("u:${f.name}", templateDisplayName(f.name), w, h, false, bmp?.asImageBitmap())
        }
    return out
}

// 文件名 u_<millis>_<名称>.webp → 显示名称
internal fun templateDisplayName(fileName: String): String {
    var n = fileName.removeSuffix(".webp").removePrefix("u_")
    val us = n.indexOf('_')
    if (us >= 0) n = n.substring(us + 1)
    return n.ifBlank { "自定义模板" }
}

// ---------------- 缓存项目列表(模板库同款卡片, 不显示导入按钮) ----------------
@Composable
private fun CacheGalleryPanel() {
    val ctx = LocalContext.current
    var localRev by remember { mutableIntStateOf(0) }
    val hubRev = ProjectCacheHub.rev
    val items = remember(localRev, hubRev) { loadCacheProjects(ctx) }
    val canvasItems = remember(items) { items.filter { !it.isReplay } }
    val replayItems = remember(items) { items.filter { it.isReplay } }
    val scope = rememberCoroutineScope()
    // 打开缓存列表时再补一次公共备份恢复(启动时那次可能早于 MediaStore 就绪)
    LaunchedEffect(Unit) {
        val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            CacheBackup.restore(ctx)
        }
        if (n > 0) localRev++
    }
    var pendingDelete by remember { mutableStateOf<CacheProject?>(null) }
    var pendingExport by remember { mutableStateOf<CacheProject?>(null) }
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 356.dp).verticalScroll(rememberScrollState())
        ) {
            if (items.isEmpty()) {
                Text(
                    "还没有缓存项目\n关闭画布时选「保留缓存」、设置 → 导出 → 导出为缓存项目，\n或 过程 / 回放 → 导出到缓存",
                    color = Ep.Sub, fontSize = 9.sp, lineHeight = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    textAlign = TextAlign.Center
                )
            } else {
                if (canvasItems.isNotEmpty()) {
                    CacheSectionTitle("画布缓存", canvasItems.size)
                    CacheGrid(canvasItems,
                        onOpen = { c -> ProjectCacheHub.open(c.id) },
                        onExport = { c -> pendingExport = c },
                        onDelete = { c -> pendingDelete = c })
                }
                if (replayItems.isNotEmpty()) {
                    CacheSectionTitle("回放缓存", replayItems.size)
                    CacheGrid(replayItems,
                        onOpen = { c -> ProjectCacheHub.openReplay(c.id) },
                        onExport = { c -> pendingExport = c },
                        onDelete = { c -> pendingDelete = c })
                }
            }
        }
        // Android 11+: 重装后读 Download 里的缓存备份需要「所有文件访问」; 引导一次
        if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("重装后保留缓存需允许「所有文件访问」", color = Ep.Sub, fontSize = 8.sp,
                    lineHeight = 11.sp, modifier = Modifier.weight(1f))
                CacheDlgBtn("去授权") {
                    val pkg = android.net.Uri.parse("package:" + ctx.packageName)
                    try {
                        ctx.startActivity(android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg))
                    } catch (_: Throwable) {
                        try {
                            ctx.startActivity(android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        } catch (_: Throwable) { }
                    }
                }
                Spacer(Modifier.width(6.dp))
                CacheDlgBtn("恢复") {
                    scope.launch {
                        val n = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            CacheBackup.restore(ctx)
                        }
                        if (n > 0) localRev++
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${items.size} 个 · ${formatCacheSize(items.sumOf { it.sizeBytes })}",
                color = Ep.Sub, fontSize = 8.5.sp
            )
            Spacer(Modifier.weight(1f))
        }
    }
    pendingDelete?.let { c ->
        Dialog(onDismissRequest = { }) {
            Column(
                Modifier.width(240.dp).background(Ep.Panel, RoundedCornerShape(6.dp))
                    .border(1.dp, Ep.Line, RoundedCornerShape(6.dp)).padding(14.dp)
            ) {
                Text("删除缓存项目", color = Ep.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "确定删除「${c.name}」？${if (c.isReplay) "回放文件" else "缓存快照"}和缩略图会一起删除。",
                    color = Ep.Sub, fontSize = 9.sp, lineHeight = 13.sp
                )
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    CacheDlgBtn("取消") { pendingDelete = null }
                    Spacer(Modifier.width(8.dp))
                    CacheDlgBtn("删除", danger = true) {
                        pendingDelete = null
                        ProjectCacheHub.delete(c.id)
                        localRev++
                    }
                }
            }
        }
    }
    pendingExport?.let { c ->
        ExportModeDialog(
            title = "导出「${c.name}」",
            exportText = if (c.isReplay) "导出回放文件" else "导出缓存快照",
            onExport = {
                pendingExport = null
                StudioShareHub.exportCache?.invoke(c.id)
            },
            onShare = {
                pendingExport = null
                StudioShareHub.shareCache?.invoke(c.id)
            },
            onDismiss = { pendingExport = null }
        )
    }
}

@Composable
private fun ExportModeDialog(
    title: String,
    exportText: String,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = { }) {
        Column(
            Modifier.width(250.dp).background(Ep.Panel, RoundedCornerShape(6.dp))
                .border(1.dp, Ep.Line, RoundedCornerShape(6.dp)).padding(14.dp)
        ) {
            Text(title, color = Ep.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("选择导出模式", color = Ep.Sub, fontSize = 9.sp)
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportModeRow("导出到文件", "保存到本机或网盘", exportText, onExport)
                ExportModeRow("分享到应用", "发送到微信 / QQ 等聊天软件", "分享", onShare)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                DlgBtn("取消", width = 62.dp, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun ExportModeRow(title: String, subtitle: String, action: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(Ep.Panel2, RoundedCornerShape(4.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
            .clickable(interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ep.Text, fontSize = 10.sp)
            Text(subtitle, color = Ep.Sub, fontSize = 8.sp)
        }
        Text(action, color = Ep.Accent, fontSize = 9.sp)
    }
}

@Composable
private fun CacheDlgBtn(text: String, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.height(28.dp)
            .background(if (danger) Color(0xFF3A2024) else Ep.Panel2, RoundedCornerShape(4.dp))
            .border(1.dp, if (danger) Color(0xFF8A3B44) else Ep.Line, RoundedCornerShape(4.dp))
            .clickable(interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = if (danger) Color(0xFFFF8A8A) else Ep.Text, fontSize = 10.sp) }
}

@Composable
private fun CacheSectionTitle(text: String, count: Int) {
    Text(
        "$text（$count）", color = Ep.Sub, fontSize = 10.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
    )
}

@Composable
private fun CacheGrid(
    items: List<CacheProject>,
    onOpen: (CacheProject) -> Unit,
    onExport: (CacheProject) -> Unit,
    onDelete: (CacheProject) -> Unit,
) {
    items.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { c ->
                CacheCard(c, Modifier.weight(1f), { onOpen(c) }, { onExport(c) }, { onDelete(c) })
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CacheCard(
    c: CacheProject,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val bmp = remember(c.id, c.thumb?.absolutePath) {
        try {
            val f = c.thumb
            if (f == null) null else {
                val o1 = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, o1)
                val o2 = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = maxOf(1, maxOf(o1.outWidth, o1.outHeight) / 240)
                }
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, o2)?.asImageBitmap()
            }
        } catch (_: Throwable) { null }
    }
    val date = remember(c.timeMs) {
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(c.timeMs))
    }
    Column(
        modifier.background(Ep.Panel2, RoundedCornerShape(4.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
            .clickable(interactionSource = null, indication = null) { onOpen() }
            .padding(6.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().height(84.dp)
                .background(Color(0xFF0B0E13), RoundedCornerShape(3.dp))
        ) {
            if (bmp != null) {
                Image(bmp, contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(3.dp),
                    contentScale = ContentScale.Fit)
            } else {
                Text("无预览", color = Ep.Sub, fontSize = 9.sp,
                    modifier = Modifier.align(Alignment.Center))
            }
            Row(
                Modifier.align(Alignment.TopEnd).padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                TinyAction("↑") { onExport() }
                TinyAction("✕") { onDelete() }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(c.name, color = Ep.Text, fontSize = 9.5.sp, maxLines = 1)
        if (c.isReplay) {
            Text(
                "回放 · ${c.extra.ifBlank { "${c.w}×${c.h}" }} · ${formatCacheSize(c.sizeBytes)}",
                color = Ep.Sub, fontSize = 8.sp, maxLines = 1
            )
        } else {
            Text("${c.w}×${c.h} · ${formatCacheSize(c.sizeBytes)}", color = Ep.Sub, fontSize = 8.sp, maxLines = 1)
        }
        Text(date, color = Ep.Sub, fontSize = 7.5.sp, maxLines = 1)
    }
}
