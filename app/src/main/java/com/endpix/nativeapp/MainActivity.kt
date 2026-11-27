package com.endpix.nativeapp

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import androidx.activity.result.contract.ActivityResultContracts

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.endpix.nativeapp.ui.EndpixTheme
import com.endpix.nativeapp.ui.MainScreen
import com.endpix.nativeapp.ui.Ep
import com.endpix.nativeapp.ui.EpLayout
import com.endpix.nativeapp.ui.EP_SIDE_EXT18
import com.endpix.nativeapp.ui.EP_SIDE_EXT32
import com.endpix.nativeapp.ui.EP_SIDE_EXT60
import com.endpix.nativeapp.ui.MixPickHub
import com.endpix.nativeapp.ui.mixMidpointColor
import com.endpix.nativeapp.ui.PaletteHub
import com.endpix.nativeapp.ui.PalettePreset
import com.endpix.nativeapp.ui.SelectionPerspectiveHub
import com.endpix.nativeapp.ui.SidePalettePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity(), SurfaceHolder.Callback {
    private lateinit var surfaceView: SurfaceView
    private var composeViewRef: androidx.compose.ui.platform.ComposeView? = null
    private var uiTouchRouting = false          // 小地图/参考图内的触摸只走 Compose, 不落画布
    private var forceRotateHandle = false       // 选区旋转手柄优先: 无视按钮/面板拦截整段手势
    private var minimapBoundsPx: android.graphics.RectF? = null
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var canvasW = 512   // 冷启动默认画布; 上限见 MAX_CANVAS
    private var canvasH = 512
    private var canvasLayers = 2
    private var benchMode = false
    private var transforming = false
    // 进入回放前保存项目画布视口, 退出恢复; 回放中自动适配整张画布
    private var replaySavedView: FloatArray? = null
    private var panMode = false
    private var prevPanX = 0f
    private var prevPanY = 0f
    private var prevSpan = 0f
    private var gestureStartSpan = 0f
    private var smoothSpan = 0f
    private var smoothFx = 0f
    private var smoothFy = 0f
    private var surfaceGestureActive = false
    private var forcedMultiGesture = false
    private var gestureDownMs = 0L
    private var suppressSingleUntilUp = false   // 双指结束后剩下的手指哑到抬起
    private var suppressPanUntilUp = false      // 双指手势后剩下的手指不再平移(否则用旧prevPan算出差值, 松手跳视角)
    private var prevFx = 0f
    private var prevFy = 0f
    private var importLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null
    private var exportPngLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null
    private var exportGifLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null
    private var exportZipLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null
    private var exportWebpLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null
    private var exportProjectLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>? = null
    private var exportPsdLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null
    private var loadProjectLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>? = null
    private var loadFileLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>? = null
    private var projectFolderLauncher: androidx.activity.result.ActivityResultLauncher<android.net.Uri?>? = null
    private val snapshotPath by lazy { java.io.File(filesDir, "canvas.snap").absolutePath }
    // ---- 标签栏(多画布) ----
    data class CanvasTab(val id: Int, val name: String)
    private val tabsState = mutableStateOf<List<CanvasTab>>(emptyList())
    private val activeTabState = mutableIntStateOf(1)
    // 标签栏显示的画布尺寸缓存(id -> 宽×高); 切换/新建/载入后刷新
    private val tabSizesState = androidx.compose.runtime.mutableStateMapOf<Int, Pair<Int, Int>>()
    private fun noteTabSize(id: Int, w: Int, h: Int) {
        if (id > 0 && w > 0 && h > 0) tabSizesState[id] = w to h
    }
    private fun noteActiveTabSizeLater(delayMs: Long = 500) {
        val id = activeTabState.intValue
        handler.postDelayed({
            if (activeTabState.intValue == id) {
                val (w, h) = currentCanvasDims()
                noteTabSize(id, w, h)
            }
        }, delayMs)
    }
    private fun refreshTabSizesAsync() {
        val list = tabsState.value
        Thread {
            for (t in list) {
                try {
                    val v = nativeSnapshotDims(tabFile(t.id).absolutePath)
                    if (v != null && v.size >= 2 && v[0] > 0 && v[1] > 0) {
                        val w = v[0]; val h = v[1]
                        runOnUiThread { noteTabSize(t.id, w, h) }
                    }
                } catch (_: Throwable) { }
            }
        }.start()
    }

    // ---- 标签创建时间/使用时长(长按标签的画布信息) ----
    private var usageTrackedTab = 0
    private var usageSinceMs = 0L
    private fun beginTabUsage(id: Int) {
        if (id <= 0) return
        if (usageTrackedTab == id && usageSinceMs > 0L) return
        pauseTabUsage()
        usageTrackedTab = id
        usageSinceMs = android.os.SystemClock.elapsedRealtime()
    }
    private fun pauseTabUsage() {
        if (usageTrackedTab <= 0 || usageSinceMs <= 0L) return
        val d = (android.os.SystemClock.elapsedRealtime() - usageSinceMs).coerceAtLeast(0L)
        val p = prefs
        if (p != null && d > 0L) {
            val k = "tabUsage_$usageTrackedTab"
            p.edit().putLong(k, p.getLong(k, 0L) + d).apply()
        }
        usageSinceMs = 0L
    }
    private fun tabUsageMs(id: Int): Long {
        val stored = prefs?.getLong("tabUsage_$id", 0L) ?: 0L
        val live = if (id == usageTrackedTab && usageSinceMs > 0L)
            (android.os.SystemClock.elapsedRealtime() - usageSinceMs).coerceAtLeast(0L) else 0L
        return stored + live
    }
    // 老版本没有创建时间: 用快照修改时间兜底, 一次性补齐并落盘
    private fun ensureTabMeta() {
        val p = prefs ?: return
        val e = p.edit()
        val now = System.currentTimeMillis()
        for (t in tabsState.value) {
            if (!p.contains("tabCreated_${t.id}")) {
                val f = tabFile(t.id)
                val ts = if (f.exists() && f.lastModified() > 0L) f.lastModified() else now
                e.putLong("tabCreated_${t.id}", ts)
            }
            if (!p.contains("tabUsage_${t.id}")) e.putLong("tabUsage_${t.id}", 0L)
        }
        e.apply()
    }
    private fun noteTabCreated(id: Int) {
        prefs?.edit()?.putLong("tabCreated_$id", System.currentTimeMillis())
            ?.putLong("tabUsage_$id", 0L)?.apply()
    }
    private fun fmtTabTime(ms: Long): String =
        if (ms <= 0L) "未知"
        else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(ms))
    private fun fmtUsage(ms: Long): String {
        if (ms < 60_000L) return "${ms / 1000} 秒"
        val totalMin = ms / 60_000L
        val d = totalMin / 1440L
        val h = (totalMin % 1440L) / 60L
        val m = totalMin % 60L
        return when {
            d > 0L -> "${d} 天 ${h} 小时"
            h > 0L -> "${h} 小时 ${m} 分"
            else -> "${m} 分"
        }
    }
    private fun fmtBytes(b: Long): String = when {
        b >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)
        b >= 1024L -> String.format(java.util.Locale.US, "%.1f KB", b / 1024.0)
        else -> "$b B"
    }
    /** 长按标签弹窗的信息行: 大小/创建/位置/最后保存/使用时间/图层帧/快照. */
    private fun buildTabInfo(id: Int): com.endpix.nativeapp.ui.TabInfo {
        val list = tabsState.value
        val idx = list.indexOfFirst { it.id == id }
        val tab = if (idx >= 0) list[idx] else CanvasTab(id, "画布$id")
        var w = tabSizesState[id]?.first ?: 0
        var h = tabSizesState[id]?.second ?: 0
        val snap = tabFile(id)
        if ((w <= 0 || h <= 0) && snap.exists()) {
            try {
                val v = nativeSnapshotDims(snap.absolutePath)
                if (v != null && v.size >= 2) { w = v[0]; h = v[1] }
            } catch (_: Throwable) { }
        }
        val active = id == activeTabState.intValue
        val layers = if (active && started) try { nativeLayerCount() } catch (_: Throwable) { 0 } else 0
        val frames = if (active && started) try { nativeFrameCount() } catch (_: Throwable) { 0 } else 0
        val created = prefs?.getLong("tabCreated_$id", 0L) ?: 0L
        val saved = if (snap.exists()) snap.lastModified() else 0L
        val f = ArrayList<com.endpix.nativeapp.ui.TabField>(8)
        f += com.endpix.nativeapp.ui.TabField("大小",
            if (w > 0 && h > 0) "$w × $h（${"%,d".format(w.toLong() * h)} 像素）" else "未知")
        f += com.endpix.nativeapp.ui.TabField("创建", fmtTabTime(if (created > 0L) created else saved))
        f += com.endpix.nativeapp.ui.TabField("位置",
            if (idx >= 0) "标签 ${idx + 1} / ${list.size}" else "标签 ${list.size + 1}")
        f += com.endpix.nativeapp.ui.TabField("最后保存", if (saved > 0L) fmtTabTime(saved) else "未保存")
        f += com.endpix.nativeapp.ui.TabField("使用时间", fmtUsage(tabUsageMs(id)))
        if (layers > 0) f += com.endpix.nativeapp.ui.TabField("图层 / 帧", "$layers 层 · $frames 帧")
        f += com.endpix.nativeapp.ui.TabField("快照",
            if (snap.exists()) "${fmtBytes(snap.length())} · ${snap.name}" else "无快照文件")
        return com.endpix.nativeapp.ui.TabInfo(
            com.endpix.nativeapp.ui.UiTab(tab.id, tab.name), f)
    }
    private var autoSaveMin = 0
    private var lastAutoSaveMs = 0L
    private val autoSaveRemainState = mutableIntStateOf(-1)   // 距下次定时保存的秒数
    private val autoSaveSavingState = mutableStateOf(false)
    // ---- 持久化自动保存(不依赖用户的“定时保存”设置) ----
    // 目标: 立即被杀/掉电/后台被回收/快速切换时, 最多丢最近约 1 秒的操作; 快照本身 fsync+原子 rename.
    private var lastDurableSaveMs = 0L
    private val durableSaveRunnable = object : Runnable {
        override fun run() {
            try {
                if (started && !autoSaveSavingState.value) {
                    val (cw, ch) = currentCanvasDims()
                    val area = cw.toLong() * ch
                    val interval = when {
                        area <= 1_000_000L -> 500L
                        area <= 4_000_000L -> 1200L
                        area <= 16_000_000L -> 3000L
                        else -> 7000L
                    }
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastDurableSaveMs >= interval &&
                        !nativeIsStrokeDown() && !nativePreviewActive() && nativeInputIdle(700) &&
                        nativeSnapshotDirty()
                    ) {
                        lastDurableSaveMs = now
                        nativeSaveSnapshotAsync(activeTabFile().absolutePath)
                    }
                }
            } catch (_: Throwable) { }
            handler.postDelayed(this, 400L)
        }
    }
    // 抬手 350ms 后立刻补存一次(笔画刚落完, 不等周期轮询)
    private val durableAfterInput = object : Runnable {
        override fun run() {
            try {
                if (started && !nativeIsStrokeDown() && !nativePreviewActive() &&
                    nativeSnapshotDirty()
                ) {
                    lastDurableSaveMs = android.os.SystemClock.elapsedRealtime()
                    nativeSaveSnapshotAsync(activeTabFile().absolutePath)
                }
            } catch (_: Throwable) { }
        }
    }
    private val versionName by lazy {
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0" } catch (_: Throwable) { "1.0" }
    }
    private fun tabFile(id: Int) =
        if (id == 1) java.io.File(filesDir, "canvas.snap")
        else java.io.File(java.io.File(filesDir, "tabs").apply { mkdirs() }, "tab_$id.snap")
    private fun activeTabFile() = tabFile(activeTabState.intValue)
    private fun loadTabPrefs() {
        val raw = prefs?.getString("tabs", "") ?: ""
        val list = raw.split(',').mapNotNull { ent ->
            val p = ent.split('|')
            if (p.size == 2) CanvasTab(p[0].toIntOrNull() ?: return@mapNotNull null, p[1]) else null
        }
        if (list.isEmpty()) {
            tabsState.value = listOf(CanvasTab(1, "画布1"))
            activeTabState.intValue = 1
        } else {
            tabsState.value = list
            val act = prefs?.getInt("activeTab", list.first().id) ?: list.first().id
            activeTabState.intValue = if (list.any { it.id == act }) act else list.first().id
        }
        autoSaveMin = prefs?.getInt("autoSaveMin", 0) ?: 0
        lastAutoSaveMs = System.currentTimeMillis()
        ensureTabMeta()
    }
    private fun saveTabPrefs() {
        prefs?.edit()
            ?.putString("tabs", tabsState.value.joinToString(",") { "${it.id}|${it.name}" })
            ?.putInt("activeTab", activeTabState.intValue)
            ?.commit()   // 同步落盘: 立即被杀也要保住标签列表
    }
    private fun saveActiveTab() {
        if (!started) return
        nativeSaveSnapshot(activeTabFile().absolutePath)
    }
    // 每标签独立视角: 切换标签前存当前标签的 zoom/pan, 切回时在 snapshot 载入后恢复
    private fun tabViewKey(id: Int) = "tabView_$id"
    private fun readTabView(id: Int): FloatArray? {
        val s = prefs?.getString(tabViewKey(id), "") ?: return null
        val p = s.split(',')
        if (p.size != 3) return null
        val z = p[0].toFloatOrNull() ?: return null
        val px = p[1].toFloatOrNull() ?: return null
        val py = p[2].toFloatOrNull() ?: return null
        if (z <= 0f) return null
        return floatArrayOf(z, px, py)
    }
    private fun writeTabView(id: Int, v: FloatArray) {
        if (v.size < 3 || v[0] <= 0f) return
        prefs?.edit()?.putString(tabViewKey(id), "${v[0]},${v[1]},${v[2]}")?.apply()
    }
    private fun currentView(): FloatArray? {
        if (!started) return null
        return try {
            val v = nativeGetView()
            if (v != null && v.size >= 3 && v[0] > 0f) v else null
        } catch (_: Throwable) { null }
    }
    private fun rememberActiveView() {
        val v = currentView() ?: return
        writeTabView(activeTabState.intValue, v)
    }
    private fun applyTabView(id: Int) {
        if (!started) return
        val v = readTabView(id)
        if (v != null) nativeSetView(v[0], v[1], v[2])
        else nativeResetView()   // 从未打开过的标签: 适应窗口
    }
    // ---- 右侧色板: 60 格后备存储, 可见数量 6–60 ----
    private fun persistSidePalette() {
        val pal = sidePaletteState.value
        prefs?.edit()?.apply {
            for (i in 0 until minOf(60, pal.size)) putInt("sidePal$i", pal[i])
            putInt("sidePalCount", SidePalettePrefs.count.intValue.coerceIn(6, 60))
        }?.apply()
    }
    private fun palHex(c: Int): String =
        Integer.toHexString(c).uppercase().padStart(8, '0')
    // 调色板数组补齐到至少 6 格, 最多 60 格
    private fun padPalette(cols: List<Int>): IntArray {
        val n = cols.size.coerceIn(6, 60)
        return IntArray(n) { i -> if (i < cols.size) cols[i] else EP_SIDE_EXT60[i] }
    }
    // 色板去重: 保留用户前 6 格, 扩展槽撞色时换备用色, 避免两格同色
    private fun dedupePalette18(p: IntArray): IntArray {
        val fallback = EP_SIDE_EXT60.copyOf()
        val seen = HashSet<Int>(24)
        for (i in 0 until minOf(6, p.size)) seen.add(p[i] and 0xFFFFFF)
        var fi = 0
        for (i in 6 until p.size) {
            var c = p[i]
            if ((c and 0xFFFFFF) in seen) {
                while (fi < fallback.size && (fallback[fi] and 0xFFFFFF) in seen) fi++
                if (fi < fallback.size) c = fallback[fi]
            }
            seen.add(c and 0xFFFFFF)
            p[i] = c
        }
        return p
    }

    private fun loadPalettePresets() {
        val raw = prefs?.getString("palettePresets", "") ?: ""
        palettePresetsState.value = raw.split(';').mapNotNull { ent ->
            val p = ent.split('|', limit = 2)
            if (p.size != 2 || p[0].isBlank()) return@mapNotNull null
            val cols = p[1].split(',').mapNotNull { it.trim().toLongOrNull(16)?.toInt() }
            if (cols.isEmpty()) null else PalettePreset(p[0].trim().take(24), padPalette(cols))
        }
    }
    private fun persistPalettePresets() {
        val raw = palettePresetsState.value.joinToString(";") { pr ->
            pr.name.replace('|', ' ').replace('\n', ' ') + "|" +
                pr.colors.joinToString(",") { palHex(it) }
        }
        prefs?.edit()?.putString("palettePresets", raw)?.apply()
    }
    private fun savePalettePreset(name: String? = null) {
        val cur = palettePresetsState.value
        var nm = name?.trim()?.replace('|', ' ')?.take(24).orEmpty()
        if (nm.isBlank()) {
            var n = cur.size + 1
            while (cur.any { it.name == "预设$n" }) n++
            nm = "预设$n"
        }
        val n = SidePalettePrefs.count.intValue.coerceIn(6, 60)
        val cols = sidePaletteState.value.copyOf(n)
        val idx = cur.indexOfFirst { it.name == nm }
        palettePresetsState.value = if (idx >= 0) {
            cur.toMutableList().also { it[idx] = PalettePreset(nm, cols) }
        } else cur + PalettePreset(nm, cols)
        persistPalettePresets()
        android.widget.Toast.makeText(
            this,
            if (idx >= 0) "已更新 $nm（${cols.size} 色）" else "已保存 $nm（${cols.size} 色）",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }
    private fun renamePalettePreset(i: Int, name: String) {
        val cur = palettePresetsState.value.toMutableList()
        if (i !in cur.indices) return
        var nm = name.trim().replace('|', ' ').replace('\n', ' ').take(24)
        if (nm.isBlank()) return
        var k = 2
        while (cur.indices.any { idx -> idx != i && cur[idx].name == nm }) {
            nm = name.trim().replace('|', ' ').take(20) + " $k"
            k++
        }
        cur[i] = cur[i].copy(name = nm)
        palettePresetsState.value = cur
        persistPalettePresets()
        android.widget.Toast.makeText(this, "已改名为 $nm",
            android.widget.Toast.LENGTH_SHORT).show()
    }
    private fun applyPalettePreset(i: Int) {
        val pr = palettePresetsState.value.getOrNull(i) ?: return
        val n = pr.colors.size.coerceIn(6, 60)
        val full = sidePaletteState.value.copyOf()
        for (k in 0 until minOf(n, pr.colors.size)) full[k] = pr.colors[k]
        sidePaletteState.value = full
        SidePalettePrefs.count.intValue = n
        persistSidePalette()
        android.widget.Toast.makeText(this, "已应用 ${pr.name}（${n} 色）",
            android.widget.Toast.LENGTH_SHORT).show()
    }
    private fun deletePalettePreset(i: Int) {
        val cur = palettePresetsState.value.toMutableList()
        if (i !in cur.indices) return
        cur.removeAt(i)
        palettePresetsState.value = cur
        persistPalettePresets()
    }
    private fun exportPresetsTo(uri: android.net.Uri) {
        try {
            // 永远带上当前色板: 即使一个预设都没保存, 导出文件也不会是空的
            val n = SidePalettePrefs.count.intValue.coerceIn(6, 60)
            val curName = if (palettePresetsState.value.any { it.name == "当前色板" })
                "当前色板(导出)" else "当前色板"
            val list = ArrayList<PalettePreset>(palettePresetsState.value.size + 1)
            list.add(PalettePreset(curName, sidePaletteState.value.copyOf(n)))
            palettePresetsState.value.forEach { if (it.name != curName) list.add(it) }
            val txt = buildString {
                append('\uFEFF')   // UTF-8 BOM: Windows 记事本直接打开中文不乱码
                append("# Endpix 调色板 v2\n")
                append("# 每行: 名称|RRGGBBAA,...（最多 60 色）\n")
                list.forEach { pr ->
                    val nm = pr.name.replace('|', ' ').replace('\n', ' ').trim()
                        .ifBlank { "未命名" }
                    append(nm).append('|')
                    append(pr.colors.take(60).joinToString(",") { palHex(it) })
                    append('\n')
                }
            }
            val bytes = txt.toByteArray(Charsets.UTF_8)
            val out = contentResolver.openOutputStream(uri, "wt")
                ?: throw IllegalStateException("无法打开导出文件")
            out.use { it.write(bytes); it.flush() }
            // 有些 SAF provider 只创建空文件; 写完再确认一次大小, 不再静默报成功
            val size = runCatching {
                contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
            }.getOrNull() ?: -1L
            if (size == 0L) throw IllegalStateException("导出文件为空")
            android.widget.Toast.makeText(this,
                "已导出 ${list.size} 个调色板（${bytes.size} 字节）",
                android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            android.widget.Toast.makeText(this, "导出失败：${e.message ?: "写入错误"}",
                android.widget.Toast.LENGTH_LONG).show()
        }
    }
    private fun importPresetsFrom(uri: android.net.Uri) {
        try {
            val txt = contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return
            val list = palettePresetsState.value.toMutableList()
            var add = 0
            var upd = 0
            txt.removePrefix("\uFEFF").lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                val p = line.split('|', limit = 2)
                if (p.size != 2) return@forEach
                val cols = p[1].split(',', ';')
                    .mapNotNull { it.trim().removePrefix("#").toLongOrNull(16)?.toInt() }
                if (cols.isEmpty()) return@forEach
                val nm = p[0].trim().replace('|', ' ').take(24).ifBlank { "导入预设" }
                val pr = PalettePreset(nm, padPalette(cols))
                val idx = list.indexOfFirst { it.name == nm }
                if (idx >= 0) { list[idx] = pr; upd++ } else { list.add(pr); add++ }
            }
            if (add + upd == 0) {
                android.widget.Toast.makeText(this, "没有找到可导入的调色板",
                    android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            palettePresetsState.value = list
            persistPalettePresets()
            val extra = if (upd > 0) "，更新 $upd 个" else ""
            android.widget.Toast.makeText(this, "已导入 $add 个预设$extra",
                android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            android.widget.Toast.makeText(this, "导入失败：${e.message ?: "读取错误"}",
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun recordDir(id: Int = activeTabState.intValue): java.io.File =
        java.io.File(filesDir, "timelapse/tab_$id").apply { mkdirs() }

    private fun countRecordedFrames(dir: java.io.File = recordDir()): Int {
        return dir.listFiles { f -> f.isFile && f.name.startsWith("r_") && f.name.endsWith(".png") }?.size ?: 0
    }

    /** 旧版录制帧直接放在 files/timelapse; 有旧帧且当前标签目录为空时搬进标签目录. */
    private fun migrateLegacyRecordDir() {
        try {
            val legacy = java.io.File(filesDir, "timelapse")
            val old = legacy.listFiles { f -> f.isFile } ?: return
            if (old.none { it.name.startsWith("r_") }) return
            val dest = recordDir()
            if (dest.listFiles { f -> f.name.startsWith("r_") }?.isNotEmpty() == true) return
            old.forEach { it.renameTo(java.io.File(dest, it.name)) }
        } catch (_: Throwable) { }
    }

    private fun resumeNativeRecording(dir: java.io.File = recordDir()) {
        val n = countRecordedFrames(dir)
        var elapsed = 0
        try {
            val mf = java.io.File(dir, "meta.csv")
            if (mf.exists()) mf.forEachLine { line ->
                val p = line.trim().split(',')
                if (p.size >= 8) p[1].toIntOrNull()?.let { elapsed = it }
            }
        } catch (_: Throwable) { }
        nativeResumeRecord(dir.absolutePath, n, elapsed)
    }

    private fun stopRecordingOnTabSwitch() {
        if (!recordOnState.value) return
        recordOnState.value = false
        // 记住这个标签正在录, 切回来时自动续录(不重新开始, 不清帧)
        recordTabs[activeTabState.intValue] = true
        nativeSetRecord(0, recordDir().absolutePath)
    }

    private fun addTab(w: Int = 0, h: Int = 0): Boolean {
        if (w > 0 && h > 0) {
            val err = canvasLimitError(w, h)
            if (err != null) {
                android.widget.Toast.makeText(this, err, android.widget.Toast.LENGTH_LONG).show()
                return false
            }
        }
        stopRecordingOnTabSwitch()
        if (started) nativeConfirmSel()
        if (started) nativeCancelShapePreview()
        // 单项目模式: 不新建标签, 直接清空并重建当前标签的画布(旧内容/撤销栈被覆盖)
        if (com.endpix.nativeapp.ui.SingleProjectPrefs.on) {
            val id = activeTabState.intValue
            if (started) nativeCancelSel()
            if (started) nativeDropTabHistory(id)
            prefs?.edit()?.remove(tabViewKey(id))?.apply()
            tabsState.value = tabsState.value.map { if (it.id == id) it.copy(name = "画布$id") else it }
            saveTabPrefs()
            if (started) {
                nativeNewDocument(w, h)
                nativeSetTabHistory(id)           // 替换后的画布从空撤销栈开始
                nativeSaveSnapshotAsync(tabFile(id).absolutePath)   // 不阻塞 UI(空画布快照后台落盘)
                nativeResetView()
                refitTimelineViewSoon()
                val msg = if (w > 0 && h > 0) "已替换当前画布（${w}×${h}）" else "已清空当前画布"
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
            }
            if (w > 0 && h > 0) noteTabSize(id, w, h) else noteActiveTabSizeLater(400)
            return true
        }
        if (tabsState.value.size >= 8) {
            android.widget.Toast.makeText(this, "标签栏已达上限(8)", android.widget.Toast.LENGTH_SHORT).show()
            return false
        }
        rememberActiveView()
        saveActiveTab()
        if (started) nativeParkTabHistory()   // 新标签前先把当前标签的撤销栈停放
        val id = (tabsState.value.maxOfOrNull { it.id } ?: 0) + 1
        tabsState.value = tabsState.value + CanvasTab(id, "画布$id")
        activeTabState.intValue = id
        saveTabPrefs()
        prefs?.edit()?.remove(tabViewKey(id))?.apply()   // 复用过的标签编号不带旧视角
        noteTabCreated(id)
        beginTabUsage(id)
        if (started) {
            nativeNewDocument(w, h)
            nativeSetTabHistory(id)           // 新标签: 空撤销栈
            nativeSaveSnapshotAsync(tabFile(id).absolutePath)   // 不阻塞 UI
            refitTimelineViewSoon()
            if (w > 0 && h > 0) noteTabSize(id, w, h) else noteActiveTabSizeLater(400)
        }
        return true
    }

    // 画布尺寸保护: 超限内容直接拒绝, 不做任何标签/画布破坏性操作
    private fun canvasLimitError(w: Int, h: Int): String? {
        if (w < 1 || h < 1) return "画布尺寸无效"
        if (w > MAX_CANVAS || h > MAX_CANVAS)
            return "画布最大 ${MAX_CANVAS}×${MAX_CANVAS}（当前 ${w}×${h}），已取消加载"
        if (w.toLong() * h > MAX_CANVAS.toLong() * MAX_CANVAS)
            return "画布像素过多（${w}×${h}），已取消加载"
        return null
    }
    private fun renameTab(id: Int, name: String) {
        val nm = name.trim().take(12)
        if (nm.isEmpty()) return
        tabsState.value = tabsState.value.map { if (it.id == id) it.copy(name = nm) else it }
        saveTabPrefs()
    }

    // 模板库: 内置 assets/templates/tN.webp + 用户导入 files/templates/u_*.webp
    private fun loadTemplate(id: String) {
        if (!started) return
        try {
            val builtin = id.startsWith("a")
            var name: String
            val bmp: android.graphics.Bitmap?
            if (builtin) {
                val n = id.substring(1).toIntOrNull() ?: 1
                name = "模板$n"
                bmp = assets.open("templates/t$n.webp").use {
                    android.graphics.BitmapFactory.decodeStream(it)
                }
            } else {
                val f = java.io.File(filesDir, "templates/" + id.removePrefix("u:"))
                name = com.endpix.nativeapp.ui.templateDisplayName(f.name)
                bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath)
            }
            if (bmp == null) {
                android.widget.Toast.makeText(this, "模板载入失败", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            val w = bmp.width
            val h = bmp.height
            if (w < 1 || h < 1 || w > 32767 || h > 32767) {
                bmp.recycle()
                android.widget.Toast.makeText(this, "模板尺寸超限", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            val argb = IntArray(w * h)
            bmp.getPixels(argb, 0, w, 0, 0, w, h)
            for (i in argb.indices) {   // ARGB → 原生 ABGR
                val c = argb[i]
                argb[i] = (c and 0xFF00FF00.toInt()) or ((c and 0xFF) shl 16) or ((c ushr 16) and 0xFF)
            }
            bmp.recycle()
            // 标签没建成(上限8/尺寸超限)就不要再往当前标签里导入: 否则当前画布会被改名/改尺寸标签, 内容却是空的
            if (!addTab(w, h)) return
            val tid = activeTabState.intValue
            renameTab(tid, name)
            noteTabSize(tid, w, h)
            // 模板是整张画布内容: 直接写当前层, 不经过"浮层+确认"
            // (浮层路径在锁定透明像素/确认时序异常时会整张落空, 表现出来就是同尺寸的空画布)
            nativeImportRegion(0, 0, w, h, argb)
            handler.postDelayed({ saveActiveTab() }, 600)   // 导入落层后补存快照
            android.widget.Toast.makeText(this, "$name 已创建 ${w}×${h}",
                android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            android.widget.Toast.makeText(this, "模板载入失败", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchTemplateImport() {
        templateImportLauncher?.launch(arrayOf("image/*"))
    }

    private fun launchTemplateExport(id: String) {
        pendingTemplateExportId = id
        templateExportLauncher?.launch("template.webp")
    }

    private fun importTemplate(uri: android.net.Uri) {
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("读不到文件")
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                var bmp: android.graphics.Bitmap? = null
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    val opt2 = android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / 4096)
                    }
                    bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt2)
                }
                if (bmp == null) {
                    bmp = com.endpix.nativeapp.ui.ExtraImageDecoders.decode(bytes, 4096)
                }
                if (bmp == null) {
                    runOnUiThread {
                        android.widget.Toast.makeText(this, "不是可识别的图片", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
                val w = bmp.width
                val h = bmp.height
                val dir = java.io.File(filesDir, "templates").apply { mkdirs() }
                val rawName = displayName(uri).substringBeforeLast('.').take(24)
                    .replace(Regex("[\\/:*?\"<>|]"), "_").ifBlank { "模板" }
                val f = java.io.File(dir, "u_${System.currentTimeMillis()}_$rawName.webp")
                f.outputStream().use { out ->
                    val fmt = if (android.os.Build.VERSION.SDK_INT >= 30)
                        android.graphics.Bitmap.CompressFormat.WEBP_LOSSLESS
                    else android.graphics.Bitmap.CompressFormat.WEBP
                    bmp.compress(fmt, 100, out)
                }
                bmp.recycle()
                templateRevState.intValue++
                runOnUiThread {
                    android.widget.Toast.makeText(this, "已导入模板 ${w}×${h}",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    android.widget.Toast.makeText(this, "导入模板失败: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun exportTemplate(id: String, uri: android.net.Uri) {
        Thread {
            val ok = try {
                val bytes = if (id.startsWith("a"))
                    assets.open("templates/t${id.substring(1)}.webp").use { it.readBytes() }
                else
                    java.io.File(filesDir, "templates/" + id.removePrefix("u:")).readBytes()
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
            } catch (_: Throwable) { false }
            runOnUiThread {
                android.widget.Toast.makeText(this,
                    if (ok) "模板已导出" else "模板导出失败",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    // ---- 模板/缓存: 导出模式(保存文件 / 分享到应用) ----
    private fun safeStem(s: String): String = s.map {
        if (it == '/' || it == '\\' || it == ':' || it == '*' || it == '?' ||
            it == '"' || it == '<' || it == '>' || it == '|' || it == '\n' || it == '\r') '_' else it
    }.joinToString("").trim().take(60).ifBlank { "endpix" }

    private fun shareFileToApps(f: java.io.File, mime: String, title: String) {
        if (!f.exists()) {
            android.widget.Toast.makeText(this, "分享文件不存在", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", f
            )
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = mime
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, title)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(contentResolver, title, uri)
            }
            startActivity(android.content.Intent.createChooser(send, "分享到应用"))
        } catch (e: Throwable) {
            android.widget.Toast.makeText(this, "分享失败: ${e.message}",
                android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareTemplate(id: String) {
        Thread {
            try {
                val dir = java.io.File(cacheDir, "share").apply { mkdirs() }
                val name: String
                val dst: java.io.File
                if (id.startsWith("a")) {
                    name = "模板${id.removePrefix("a")}.webp"
                    dst = java.io.File(dir, name)
                    assets.open("templates/t${id.substring(1)}.webp").use { i ->
                        dst.outputStream().use { o -> i.copyTo(o) }
                    }
                } else {
                    val src = java.io.File(filesDir, "templates/" + id.removePrefix("u:"))
                    name = safeStem(com.endpix.nativeapp.ui.templateDisplayName(src.name)) + ".webp"
                    dst = java.io.File(dir, name)
                    src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                }
                runOnUiThread { shareFileToApps(dst, "image/webp", name) }
            } catch (e: Throwable) {
                runOnUiThread {
                    android.widget.Toast.makeText(this, "分享模板失败: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun launchCacheExport(id: String) {
        val c = com.endpix.nativeapp.ui.loadCacheProjects(this).firstOrNull { it.id == id } ?: return
        pendingCacheExportId = id
        cacheExportLauncher?.launch(safeStem(c.name) + if (c.isReplay) ".replay" else ".snap")
    }

    private fun exportCacheFile(id: String, uri: android.net.Uri) {
        Thread {
            val c = com.endpix.nativeapp.ui.loadCacheProjects(this).firstOrNull { it.id == id }
            val ok = try {
                if (c == null) false else c.snap.inputStream().use { i ->
                    contentResolver.openOutputStream(uri)?.use { o -> i.copyTo(o) } != null
                }
            } catch (_: Throwable) { false }
            runOnUiThread {
                android.widget.Toast.makeText(this,
                    if (ok) "缓存文件已导出" else "缓存文件导出失败",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun shareCache(id: String) {
        val c = com.endpix.nativeapp.ui.loadCacheProjects(this).firstOrNull { it.id == id } ?: return
        Thread {
            try {
                val dir = java.io.File(cacheDir, "share").apply { mkdirs() }
                val suffix = if (c.isReplay) ".replay" else ".snap"
                val name = safeStem(c.name) + suffix
                val dst = java.io.File(dir, name)
                c.snap.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                runOnUiThread { shareFileToApps(dst, "application/octet-stream", c.name) }
            } catch (e: Throwable) {
                runOnUiThread {
                    android.widget.Toast.makeText(this, "分享缓存失败: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun deleteTemplate(id: String, builtin: Boolean) {
        if (builtin) {
            val cur = prefs?.getStringSet("hiddenTemplates", emptySet()) ?: emptySet()
            prefs?.edit()?.putStringSet("hiddenTemplates", cur + id)?.apply()
        } else {
            java.io.File(filesDir, "templates/" + id.removePrefix("u:")).delete()
        }
        templateRevState.intValue++
    }

    private fun tabThumb(id: Int): IntArray? {
        return try {
            val f = java.io.File(tabFile(id).absolutePath + ".thumb")
            if (!f.exists()) return null
            val b = f.readBytes()
            if (b.size < 48 * 48 * 4) return null
            val arr = IntArray(48 * 48)
            java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(arr)
            arr
        } catch (_: Throwable) { null }
    }
    private fun selectTab(id: Int) {
        if (id == activeTabState.intValue) return
        stopRecordingOnTabSwitch()
        rememberActiveView()
        if (started) nativeConfirmSel()
        if (started) nativeCancelShapePreview()   // 未完成形状的预览像素不能存进标签
        saveActiveTab()
        if (started) nativeParkTabHistory()       // 停放当前标签的撤销/重做栈
        activeTabState.intValue = id
        saveTabPrefs()
        beginTabUsage(id)
        if (!started) return
        val f = tabFile(id)
        if (f.exists()) nativeLoadSnapshot(f.absolutePath)
        else { nativeNewDocument(0, 0); nativeSaveSnapshot(f.absolutePath) }
        nativeSetTabHistory(id)                   // 挂回该标签自己的撤销/重做栈
        applyTabView(id)   // 排在 snapshot load 之后: 恢复该标签自己的缩放/平移
        refitTimelineViewSoon()
        noteActiveTabSizeLater(400)
        // 这个标签之前在录: 切回来自动续录, 不清已录帧
        if (recordTabs[id] == true) {
            recordOnState.value = true
            resumeNativeRecording(recordDir(id))
        }
    }
    private fun closeTab(id: Int) {
        if (tabsState.value.size <= 1) {
            android.widget.Toast.makeText(this, "至少保留一个标签", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        stopRecordingOnTabSwitch()
        val wasActive = id == activeTabState.intValue
        if (wasActive) pauseTabUsage()
        val idx = tabsState.value.indexOfFirst { it.id == id }.coerceAtLeast(0)
        val f = tabFile(id)
        if (f.exists()) f.delete()
        java.io.File(f.absolutePath + ".thumb").delete()
        recordTabs.remove(id)
        try { recordDir(id).deleteRecursively() } catch (_: Throwable) { }
        prefs?.edit()?.remove(tabViewKey(id))?.remove("tabCreated_$id")?.remove("tabUsage_$id")?.apply()
        tabSizesState.remove(id)
        val rest = tabsState.value.filterNot { it.id == id }
        tabsState.value = rest
        if (!wasActive) { saveTabPrefs(); return }
        val next = rest[idx.coerceIn(0, rest.size - 1)]
        activeTabState.intValue = next.id
        saveTabPrefs()
        beginTabUsage(next.id)
        if (started) {
            nativeCancelSel()
            nativeDropTabHistory(id)   // 关闭的标签: 丢弃它的撤销栈
            val nf = tabFile(next.id)
            if (nf.exists()) nativeLoadSnapshot(nf.absolutePath) else nativeNewDocument(0, 0)
            nativeSetTabHistory(next.id)
            applyTabView(next.id)
        }
    }
    private fun closeActiveTab() = closeTab(activeTabState.intValue)

    // ---- 缓存项目: 完整快照(画布/图层/帧/撤销步骤) 保留到 files/cache_projects ----
    private fun thumbPxToBitmap(px: IntArray, n: Int): android.graphics.Bitmap? = try {
        val argb = IntArray(n * n)
        for (i in 0 until minOf(px.size, argb.size)) {
            val c = px[i]
            val a = (c ushr 24) and 0xFF
            argb[i] = (a shl 24) or (c and 0xFF00FF00.toInt()) or
                ((c and 0xFF) shl 16) or ((c ushr 16) and 0xFF)
        }
        android.graphics.Bitmap.createBitmap(argb, n, n, android.graphics.Bitmap.Config.ARGB_8888)
    } catch (_: Throwable) { null }

    /** 把当前标签完整快照复制进缓存列表; onDone 在主线程回调是否成功. */
    private fun saveActiveTabToCache(onDone: (Boolean) -> Unit) {
        if (!started) { onDone(false); return }
        saveActiveTab()   // 同步等待引擎写完 tab_<id>.snap
        val tabId = activeTabState.intValue
        val tabName = tabsState.value.firstOrNull { it.id == tabId }?.name ?: "画布$tabId"
        val snap = tabFile(tabId)
        if (!snap.exists() || snap.length() < 64) {
            android.widget.Toast.makeText(this, "缓存失败：快照为空", android.widget.Toast.LENGTH_SHORT).show()
            onDone(false); return
        }
        val dims = try { nativeSnapshotDims(snap.absolutePath) } catch (_: Throwable) { null }
        val w = dims?.getOrNull(0) ?: 0
        val h = dims?.getOrNull(1) ?: 0
        val thumb: android.graphics.Bitmap? = try {
            val mm = nativeMinimapThumb()
            if (mm != null && mm.size >= 192 * 192) thumbPxToBitmap(mm, 192)
            else tabThumb(tabId)?.let { thumbPxToBitmap(it, 48) }
        } catch (_: Throwable) { null }
        Thread {
            var ok = false
            try {
                val dir = java.io.File(filesDir, "cache_projects").apply { mkdirs() }
                val base = "c_" + System.currentTimeMillis()
                snap.inputStream().use { i -> java.io.File(dir, "$base.snap").outputStream().use { o -> i.copyTo(o) } }
                thumb?.let { b ->
                    java.io.File(dir, "$base.webp").outputStream().use { o ->
                        b.compress(android.graphics.Bitmap.CompressFormat.WEBP, 90, o)
                    }
                }
                java.io.File(dir, "$base.meta").writeText(
                    "$tabName\n$w\n$h\n${System.currentTimeMillis()}"
                )
                // 公共下载目录留一份: 卸载/重装后启动时自动恢复缓存列表
                com.endpix.nativeapp.ui.CacheBackup.backup(applicationContext, base)
                ok = true
            } catch (_: Throwable) { ok = false }
            runOnUiThread {
                if (ok) {
                    com.endpix.nativeapp.ui.ProjectCacheHub.bump()
                    android.widget.Toast.makeText(this, "已保留到缓存列表", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(this, "缓存失败（空间不足？）", android.widget.Toast.LENGTH_SHORT).show()
                }
                onDone(ok)
            }
        }.start()
    }

    /** 过程面板「导出到缓存」: 当前录制 → cache_projects/r_<millis>.replay (+webp/meta), 缓存列表单独分区. */
    private fun doCacheReplay() {
        val dir = recordDir()
        val frames = countRecordedFrames(dir)
        if (frames <= 0) {
            android.widget.Toast.makeText(this, "没有录制帧，先开启记录过程并绘制",
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val tabName = tabsState.value.firstOrNull { it.id == activeTabState.intValue }?.name ?: "画布"
        val (cw, ch) = currentCanvasDims()
        val thumb: android.graphics.Bitmap? = try {
            val mm = nativeMinimapThumb()
            if (mm != null && mm.size >= 192 * 192) thumbPxToBitmap(mm, 192)
            else tabThumb(activeTabState.intValue)?.let { thumbPxToBitmap(it, 48) }
        } catch (_: Throwable) { null }
        Thread {
            var ok = false
            try {
                val tmp = java.io.File(cacheDir, "endpix_cache_replay.replay")
                val (made, _) = com.endpix.nativeapp.ui.ReplayIO.buildFromRecording(
                    dir, versionName, tmp
                )
                if (made) {
                    val cdir = java.io.File(filesDir, "cache_projects").apply { mkdirs() }
                    val base = "r_" + System.currentTimeMillis()
                    tmp.inputStream().use { i ->
                        java.io.File(cdir, "$base.replay").outputStream().use { o -> i.copyTo(o) }
                    }
                    thumb?.let { b ->
                        java.io.File(cdir, "$base.webp").outputStream().use { o ->
                            b.compress(android.graphics.Bitmap.CompressFormat.WEBP, 90, o)
                        }
                    }
                    // meta: 名称/宽/高/时间/类型(1=回放)/附加信息
                    java.io.File(cdir, "$base.meta").writeText(
                        "${tabName.take(12)} · 回放\n$cw\n$ch\n${System.currentTimeMillis()}\n1\n${frames}帧"
                    )
                    com.endpix.nativeapp.ui.CacheBackup.backup(applicationContext, base)
                    ok = true
                }
            } catch (_: Throwable) { ok = false }
            runOnUiThread {
                if (ok) {
                    com.endpix.nativeapp.ui.ProjectCacheHub.bump()
                    android.widget.Toast.makeText(this, "已缓存回放，可在缓存列表打开",
                        android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(this, "回放缓存失败（空间不足？）",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** 打开回放缓存: 直接进画布播放器(不新建画布标签). */
    private fun openCachedReplay(cacheId: String) {
        val dir = java.io.File(filesDir, "cache_projects")
        val file = java.io.File(dir, "$cacheId.replay")
        if (!file.exists() || file.length() < 16L) {
            android.widget.Toast.makeText(this, "回放缓存文件不存在",
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val name = try {
            java.io.File(dir, "$cacheId.meta").readLines().getOrNull(0)?.ifBlank { null }
        } catch (_: Throwable) { null } ?: "缓存回放"
        Thread {
            val sess = com.endpix.nativeapp.ui.ReplaySession.open(file, name)
            runOnUiThread {
                if (sess != null) {
                    com.endpix.nativeapp.ui.ReplayHub.open(sess)
                    com.endpix.nativeapp.ui.StudioHub.mode = -1
                } else {
                    android.widget.Toast.makeText(this, "回放读取失败",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** 打开缓存项目: 新建标签并载入完整快照(含撤销/重做步骤). */
    private fun openCachedProject(cacheId: String) {
        if (!started) return
        val dir = java.io.File(filesDir, "cache_projects")
        val snap = java.io.File(dir, "$cacheId.snap")
        if (!snap.exists()) {
            android.widget.Toast.makeText(this, "缓存文件不存在", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (!com.endpix.nativeapp.ui.SingleProjectPrefs.on && tabsState.value.size >= 8) {
            android.widget.Toast.makeText(this, "标签栏已达上限(8)", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val lines = try { java.io.File(dir, "$cacheId.meta").readLines() } catch (_: Throwable) { emptyList() }
        val name = (lines.getOrNull(0) ?: "").ifBlank { "缓存" }.take(12)
        val dims = try { nativeSnapshotDims(snap.absolutePath) } catch (_: Throwable) { null }
        val cw = dims?.getOrNull(0) ?: lines.getOrNull(1)?.toIntOrNull() ?: 0
        val ch = dims?.getOrNull(1) ?: lines.getOrNull(2)?.toIntOrNull() ?: 0
        if (cw < 1 || ch < 1) {
            android.widget.Toast.makeText(this, "缓存损坏", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val limit = canvasLimitError(cw, ch)
        if (limit != null) {
            android.widget.Toast.makeText(this, limit, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        // 单项目模式: 直接替换当前标签的画布, 不新增标签
        if (com.endpix.nativeapp.ui.SingleProjectPrefs.on) {
            val tid = activeTabState.intValue
            val dst = tabFile(tid)
            try {
                snap.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            } catch (_: Throwable) {
                android.widget.Toast.makeText(this, "缓存复制失败", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            stopRecordingOnTabSwitch()
            nativeCancelSel()
            nativeCancelShapePreview()
            prefs?.edit()?.remove(tabViewKey(tid))?.apply()
            renameTab(tid, name)
            nativeLoadSnapshot(dst.absolutePath)
            nativeSetTabHistory(tid)
            nativeResetView()
            noteTabSize(tid, cw, ch)
            tabSizesState[tid] = cw to ch
            refitTimelineViewSoon()
            android.widget.Toast.makeText(this, "已替换当前画布「$name」", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        stopRecordingOnTabSwitch()
        nativeConfirmSel()
        nativeCancelShapePreview()
        rememberActiveView()
        saveActiveTab()
        nativeParkTabHistory()
        val nid = (tabsState.value.maxOfOrNull { it.id } ?: 0) + 1
        tabsState.value = tabsState.value + CanvasTab(nid, name)
        activeTabState.intValue = nid
        saveTabPrefs()
        prefs?.edit()?.remove(tabViewKey(nid))?.apply()
        noteTabCreated(nid)
        beginTabUsage(nid)
        val dst = tabFile(nid)
        try {
            snap.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
        } catch (_: Throwable) {
            android.widget.Toast.makeText(this, "缓存复制失败", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        nativeLoadSnapshot(dst.absolutePath)
        nativeSetTabHistory(nid)
        noteTabSize(nid, cw, ch)
        tabSizesState[nid] = cw to ch
        saveTabPrefs()
        refitTimelineViewSoon()
        android.widget.Toast.makeText(this, "已打开缓存「$name」", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun deleteCacheProject(cacheId: String) {
        val dir = java.io.File(filesDir, "cache_projects")
        val primary = if (cacheId.startsWith("r_")) ".replay" else ".snap"
        java.io.File(dir, "$cacheId$primary").delete()
        java.io.File(dir, "$cacheId.webp").delete()
        java.io.File(dir, "$cacheId.meta").delete()
        com.endpix.nativeapp.ui.CacheBackup.delete(applicationContext, cacheId)
        com.endpix.nativeapp.ui.ProjectCacheHub.bump()
    }

    private fun setAutoSaveMin(v: Int) {
        autoSaveMin = v
        lastAutoSaveMs = System.currentTimeMillis()
        prefs?.edit()?.putInt("autoSaveMin", v)?.apply()
    }
    // 选图期间 Surface 可能被销毁(引擎释放), 回调先暂存, 引擎起来后再导入
    private var pendingPngScale = 1
    private var pendingWebpScale = 1
    private val templateRevState = mutableIntStateOf(0)   // 模板库增删后通知界面刷新
    private var pendingTemplateExportId: String? = null
    private var pendingCacheExportId: String? = null
    private var templateImportLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null
    private var templateExportLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null
    private var cacheExportLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null
    private var pendingImportW = 0
    private var pendingImportH = 0
    private var pendingImportPx: IntArray? = null
    // 超过画布的图片先等比缩小到画布内(最近邻, 像素画不糊), 避免被画布边界裁掉
    private fun fitToCanvas(src: android.graphics.Bitmap): android.graphics.Bitmap {
        val (mw, mh) = currentCanvasDims()
        if (src.width <= mw && src.height <= mh) return src
        val sc = minOf(mw.toFloat() / src.width, mh.toFloat() / src.height)
        val nw = maxOf(1, (src.width * sc).toInt())
        val nh = maxOf(1, (src.height * sc).toInt())
        val out = android.graphics.Bitmap.createScaledBitmap(src, nw, nh, false)
        src.recycle()
        return out
    }

    private fun flushPendingImport() {
        val px = pendingImportPx ?: return
        if (!started) return
        val w = pendingImportW
        val h = pendingImportH
        pendingImportPx = null
        try {
            nativeImportImage(w, h, px)
            android.widget.Toast.makeText(this, "已导入 ${w}×${h}", android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
            pendingImportPx = px; pendingImportW = w; pendingImportH = h
        }
    }

    // ---- 设置菜单: 导出/加载 ----
    private fun copyToUri(src: java.io.File, uri: android.net.Uri): Boolean = try {
        contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } } != null
    } catch (_: Throwable) { false }

    private fun projectFolderLabel(raw: String): String = try {
        val uri = android.net.Uri.parse(raw)
        android.provider.DocumentsContract.getTreeDocumentId(uri)
            .substringAfter(':').ifBlank { "内部存储" }
    } catch (_: Throwable) { "内部存储" }

    private fun projectFolderDocUri(): android.net.Uri? = try {
        val raw = projectFolderUriState.value ?: return null
        val tree = android.net.Uri.parse(raw)
        android.provider.DocumentsContract.buildDocumentUriUsingTree(
            tree, android.provider.DocumentsContract.getTreeDocumentId(tree)
        )
    } catch (_: Throwable) { null }

    private fun withProjectFolder(i: android.content.Intent): android.content.Intent {
        projectFolderDocUri()?.let {
            i.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, it)
        }
        return i
    }

    private fun pickProjectExport() {
        val i = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE)
            .setType("application/octet-stream")
            .putExtra(android.content.Intent.EXTRA_TITLE, "endpix_project.snap")
        exportProjectLauncher?.launch(withProjectFolder(i))
    }

    private fun pickLoadProject() {
        val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        loadProjectLauncher?.launch(withProjectFolder(i))
    }

    private fun pickLoadFile() {
        val i = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        loadFileLauncher?.launch(withProjectFolder(i))
    }

    private fun pickProjectFolder() {
        projectFolderLauncher?.launch(projectFolderDocUri())
    }

    private fun clearProjectFolder() {
        projectFolderUriState.value = null
        projectFolderLabelState.value = ""
        prefs?.edit()?.remove("projectFolderUri")?.apply()
    }

    private fun doExportPng(uri: android.net.Uri) {
        // 裁剪导出: 直接从「上次裁剪」像素出图(默认范围=裁剪目标), 不走整张画布导出
        val cropScale = com.endpix.nativeapp.ui.CropExportHub.consumeExportScale()
        if (cropScale > 0) {
            val px = com.endpix.nativeapp.ui.CropExportHub.pixels
            val cw = com.endpix.nativeapp.ui.CropExportHub.w
            val ch = com.endpix.nativeapp.ui.CropExportHub.h
            if (px != null && cw > 0 && ch > 0) {
                persistExportWm()
                android.util.Log.i("EndpixCrop", "export cropScale=$cropScale px=${px.size} ${cw}x$ch")
                Thread {
                    var ok = false
                    try {
                        val srcBmp = cropPixelsToBitmap(px, cw, ch, cropScale)
                        android.util.Log.i("EndpixCrop", "bitmap=$srcBmp")
                        // createBitmap/createScaledBitmap 可能返回 immutable, 水印 Canvas 不能改;
                        // 导出水印开启时先复制成可写位图(与 applyExportWmToFile 同坑)。
                        val bmp = if (srcBmp != null && exportWmActive()) {
                            val mut = srcBmp.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                            if (mut != null && mut !== srcBmp) srcBmp.recycle()
                            mut
                        } else srcBmp
                        if (bmp != null) {
                            if (exportWmActive()) bakeExportWatermark(bmp, cropScale)
                            ok = contentResolver.openOutputStream(uri)?.use {
                                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                            } != null
                            android.util.Log.i("EndpixCrop", "compress=$ok size=${bmp.width}x${bmp.height}")
                            bmp.recycle()
                        }
                    } catch (e: Throwable) {
                        android.util.Log.i("EndpixCrop", "err $e")
                        ok = false
                    }
                    val outW = cw * cropScale; val outH = ch * cropScale
                    runOnUiThread {
                        android.widget.Toast.makeText(
                            this@MainActivity,
                            if (ok) "已导出裁剪图 ${outW}×${outH}" else "裁剪导出失败",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }.start()
                return
            }
        }
        val mode = com.endpix.nativeapp.ui.ExportHub.mode
        val scale = pendingPngScale
        persistExportWm()
        android.util.Log.i("EndpixExportWm", "doExportPng active=${exportWmActive()} on=${com.endpix.nativeapp.ui.ExportHub.wmOn} alpha=${com.endpix.nativeapp.ui.ExportHub.wmAlpha}")
        Thread {
            var ok = false
            var detail = ""
            if (mode == 1 || mode == 4) {
                // 精灵图 / 叠层图: 一次生成整张 PNG
                val f = java.io.File(cacheDir, if (mode == 1) "endpix_spritesheet.png" else "endpix_overlay.png")
                if (f.exists()) f.delete()
                val r = try {
                    nativeExportMode(f.absolutePath, if (mode == 1) 2 else 5, scale,
                        com.endpix.nativeapp.ui.ExportHub.cols, com.endpix.nativeapp.ui.ExportHub.delayMs)
                } catch (e: Throwable) { "err ${e.message}" }
                if (r.startsWith("ok") && f.exists()) applyExportWmToFile(f, scale)
                ok = r.startsWith("ok") && f.exists() && copyToUri(f, uri)
                detail = r
            } else {
                val f = java.io.File(cacheDir, "endpix_export.png")
                val r = try { nativeExportPng(f.absolutePath, scale) } catch (e: Throwable) { "err ${e.message}" }
                if (f.exists()) applyExportWmToFile(f, scale)
                ok = f.exists() && copyToUri(f, uri)
                detail = r
            }
            runOnUiThread {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    if (ok) (if (mode == 1) "已导出精灵图" else if (mode == 4) "已导出叠层图" else "已导出 PNG")
                    else "导出失败: $detail",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    // GIF 动画: 原生逐帧编码(每帧 256 色 + LZW)
    private fun doExportGif(uri: android.net.Uri) {
        val scale = pendingPngScale
        persistExportWm()   // GIF 编码器原生完成, 水印不参与(对话框已注明)
        Thread {
            val f = java.io.File(cacheDir, "endpix.gif")
            if (f.exists()) f.delete()
            val r = try {
                nativeExportMode(f.absolutePath, 4, scale,
                    com.endpix.nativeapp.ui.ExportHub.cols, com.endpix.nativeapp.ui.ExportHub.delayMs)
            } catch (e: Throwable) { "err ${e.message}" }
            val ok = r.startsWith("ok") && f.exists() && copyToUri(f, uri)
            runOnUiThread {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    if (ok) "已导出 GIF" else "GIF 导出失败: $r",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    // 逐帧导出: 原生写 PNG 到缓存目录, 这里打包成 zip 再交给系统保存
    private fun doExportFrames(uri: android.net.Uri) {
        val scale = pendingPngScale
        persistExportWm()
        Thread {
            val dir = java.io.File(cacheDir, "endpix_frames")
            dir.deleteRecursively()
            dir.mkdirs()
            val r = try {
                nativeExportMode(dir.absolutePath, 3, scale,
                    com.endpix.nativeapp.ui.ExportHub.cols, com.endpix.nativeapp.ui.ExportHub.delayMs)
            } catch (e: Throwable) { "err ${e.message}" }
            var ok = false
            if (r.startsWith("ok")) {
                if (exportWmActive()) {
                    dir.listFiles()?.filter { it.isFile && it.name.endsWith(".png") }?.forEach {
                        applyExportWmToFile(it, scale)
                    }
                }
                val zip = java.io.File(cacheDir, "endpix_frames.zip")
                if (zip.exists()) zip.delete()
                try {
                    java.util.zip.ZipOutputStream(zip.outputStream().buffered()).use { zos ->
                        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
                        for (fi in files) {
                            zos.putNextEntry(java.util.zip.ZipEntry(fi.name))
                            fi.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                    ok = zip.exists() && copyToUri(zip, uri)
                } catch (_: Throwable) { ok = false }
            }
            runOnUiThread {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    if (ok) "已导出逐帧 zip" else "逐帧导出失败: $r",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    // 点「导出」时先检查尺寸, 不合格直接提示, 不再打开保存对话框
    private fun startWebpExport(scale: Int) {
        persistExportWm()
        val (curW, curH) = currentCanvasDims()
        val outW = curW.toLong() * scale
        val outH = curH.toLong() * scale
        android.util.Log.i("EndpixLoad", "webp precheck cur=${curW}x${curH} out=${outW}x${outH} scale=$scale")
        if (outW > 16383 || outH > 16383) {
            android.widget.Toast.makeText(this,
                "WebP 上限 16383×16383（当前 ${outW}×${outH}），请改用 PNG 导出",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (outW * outH > 16_000_000L) {
            android.widget.Toast.makeText(this,
                "WebP 需要整张位图，${outW}×${outH} 内存不足，请改用 PNG 导出",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        pendingWebpScale = scale
        exportWebpLauncher?.launch("endpix.webp")
    }

    private fun doExportWebp(uri: android.net.Uri) {
        val png = java.io.File(cacheDir, "endpix_export_webp.png")
        val webp = java.io.File(cacheDir, "endpix_export.webp")
        val scale = pendingWebpScale
        // WebP 单文件上限 16383×16383; 更大或像素太多时整张位图放不进手机内存
        val (curW, curH) = currentCanvasDims()
        val outW = curW.toLong() * scale
        val outH = curH.toLong() * scale
        android.util.Log.i("EndpixLoad", "webp export dims cur=${curW}x${curH} out=${outW}x${outH} scale=$scale")
        if (outW > 16383 || outH > 16383) {
            android.widget.Toast.makeText(this,
                "WebP 上限 16383×16383（当前 ${outW}×${outH}），请改用 PNG 导出",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (outW * outH > 16_000_000L) {
            android.widget.Toast.makeText(this,
                "WebP 需要整张位图，${outW}×${outH} 内存不足，请改用 PNG 导出",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            val r = try { nativeExportPng(png.absolutePath, scale) } catch (e: Throwable) { "err ${e.message}" }
            var ok = false
            var err = r
            try {
                val src = android.graphics.BitmapFactory.decodeFile(png.absolutePath)
                if (src != null) {
                    val bmp = if (exportWmActive()) {
                        // 解码结果是 immutable, Canvas 不能改
                        val m = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true) ?: src
                        if (m !== src) src.recycle()
                        bakeExportWatermark(m, scale)
                        m
                    } else src
                    webp.outputStream().use { out ->
                        // API30+ 用无损 WebP(像素画不糊且保留透明), 更老系统退化为最高质量有损
                        val fmt = if (android.os.Build.VERSION.SDK_INT >= 30)
                            android.graphics.Bitmap.CompressFormat.WEBP_LOSSLESS
                        else android.graphics.Bitmap.CompressFormat.WEBP
                        ok = bmp.compress(fmt, 100, out)
                    }
                    bmp.recycle()
                } else {
                    err = if (r.startsWith("ok")) "导出图解码失败" else r
                }
                ok = ok && webp.exists() && copyToUri(webp, uri)
                android.util.Log.i("EndpixLoad", "webp export r=$r enc=$ok size=${webp.length()} uri=$uri")
            } catch (e: Throwable) {
                err = "err ${e.message}"; ok = false
                android.util.Log.i("EndpixLoad", "webp export threw $e")
            }
            runOnUiThread {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    if (ok) "已导出 WebP" else "WebP 导出失败: $err",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    private fun bakeWatermark(bmp: android.graphics.Bitmap, s: com.endpix.nativeapp.ui.WatermarkSettings, scale: Int, sigIcon: android.graphics.Bitmap? = null, densityPct: Int = 100) {
        val cv = android.graphics.Canvas(bmp)
        val W = bmp.width.toFloat()
        val H = bmp.height.toFloat()
        if (s.wmOn) {
            com.endpix.nativeapp.ui.drawExportWatermarkText(
                cv, W, H,
                s.wmText, s.wmAlpha, (s.wmSize.coerceIn(8, 96) * scale).toFloat(),
                s.wmColor, scale.toFloat(), densityPct = densityPct
            )
        }
        if (s.sigOn) {
            com.endpix.nativeapp.ui.drawSignatureNative(
                cv, s, sigIcon, 0f, 0f, W, H, scale.toFloat(),
                timestamp = com.endpix.nativeapp.ui.currentStamp()
            )
        }
    }

    // ---- 导出水印(ExportPngDialog 里开关/文字/透明度; 只改导出文件, 不写回画布/项目) ----
    private fun exportWmActive(): Boolean =
        com.endpix.nativeapp.ui.ExportHub.wmOn &&
        com.endpix.nativeapp.ui.ExportHub.wmAlpha > 0

    private fun persistExportWm() {
        prefs?.edit()
            ?.putBoolean("exportWmOn", com.endpix.nativeapp.ui.ExportHub.wmOn)
            ?.putString("exportWmText", com.endpix.nativeapp.ui.ExportHub.wmText.take(24))
            ?.putInt("exportWmAlpha", com.endpix.nativeapp.ui.ExportHub.wmAlpha.coerceIn(0, 100))
            ?.putInt("exportWmSize", com.endpix.nativeapp.ui.ExportHub.wmSize.coerceIn(8, 96))
            ?.putInt("exportWmDensity", com.endpix.nativeapp.ui.ExportHub.wmDensity.coerceIn(20, 300))
            ?.apply()
    }

    private fun bakeExportWatermark(bmp: android.graphics.Bitmap, scale: Int) {
        val base = watermarkState.value
        val text = com.endpix.nativeapp.ui.ExportHub.wmText.ifBlank {
            base.wmText.ifBlank { "Endpix" }
        }
        bakeWatermark(
            bmp,
            base.copy(
                wmOn = true, sigOn = false, wmText = text,
                wmAlpha = com.endpix.nativeapp.ui.ExportHub.wmAlpha.coerceIn(0, 100),
                wmSize = com.endpix.nativeapp.ui.ExportHub.wmSize.coerceIn(8, 96)
            ),
            scale.coerceAtLeast(1),
            null,
            densityPct = com.endpix.nativeapp.ui.ExportHub.wmDensity
        )
    }

    /** 给导出的 PNG 文件加水印(就地重写); 太大/失败返回 false, 不影响原导出. */
    private fun applyExportWmToFile(f: java.io.File, scale: Int): Boolean {
        if (!exportWmActive()) return true
        return try {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
            android.util.Log.i("EndpixExportWm", "file=$f bounds=${o.outWidth}x${o.outHeight} scale=$scale alpha=${com.endpix.nativeapp.ui.ExportHub.wmAlpha} text=${com.endpix.nativeapp.ui.ExportHub.wmText}")
            if (o.outWidth <= 0 || o.outHeight <= 0 ||
                o.outWidth.toLong() * o.outHeight > 24_000_000L) return false
            val src = android.graphics.BitmapFactory.decodeFile(f.absolutePath) ?: return false
            // decodeFile 出来的是 immutable, Canvas 不能改; 复制成可写位图
            val bmp = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true) ?: src
            if (bmp !== src) src.recycle()
            bakeExportWatermark(bmp, scale)
            f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            android.util.Log.i("EndpixExportWm", "baked bytes=${f.length()}")
            true
        } catch (e: Throwable) {
            android.util.Log.i("EndpixExportWm", "err $e")
            false
        }
    }

    // ---- 裁剪目标: 上次裁剪像素 -> 导出 / 新标签 ----
    private fun lastCutData(): Triple<Int, Int, IntArray>? {
        val a = try { nativeLastCutPixels() } catch (_: Throwable) { null } ?: return null
        if (a.size < 3) return null
        val w = a[0]; val h = a[1]
        if (w <= 0 || h <= 0 || a.size.toLong() < 2L + w.toLong() * h) return null
        val px = IntArray(w * h)
        System.arraycopy(a, 2, px, 0, w * h)
        return Triple(w, h, px)
    }

    private fun cropThumb(px: IntArray, w: Int, h: Int): IntArray {
        val n = 64
        val out = IntArray(n * n)
        for (y in 0 until n) {
            val sy = (y.toLong() * h / n).toInt().coerceIn(0, h - 1)
            for (x in 0 until n) {
                val sx = (x.toLong() * w / n).toInt().coerceIn(0, w - 1)
                out[y * n + x] = px[sy * w + sx]
            }
        }
        return out
    }

    private fun requestCropExport() {
        val d = lastCutData()
        if (d == null) {
            android.widget.Toast.makeText(this, "裁剪目标为空（先框选并点「裁剪」）",
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        com.endpix.nativeapp.ui.CropExportHub.w = d.first
        com.endpix.nativeapp.ui.CropExportHub.h = d.second
        com.endpix.nativeapp.ui.CropExportHub.pixels = d.third
        com.endpix.nativeapp.ui.CropExportHub.thumb = cropThumb(d.third, d.first, d.second)
        com.endpix.nativeapp.ui.CropExportHub.exportOpen = true
    }

    private fun openCropAsNewTab() {
        val d = lastCutData()
        if (d == null) {
            android.widget.Toast.makeText(this, "裁剪目标为空（先框选并点「裁剪」）",
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val (w, h, px) = d
        if (w > MAX_CANVAS || h > MAX_CANVAS || w.toLong() * h > 16_000_000L) {
            android.widget.Toast.makeText(this, "裁剪内容过大（${w}×${h}），已取消",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (!addTab(w, h)) return
        val id = activeTabState.intValue
        nativeImportRegion(0, 0, w, h, px)
        renameTab(id, "裁剪${w}×${h}".take(12))
        noteTabSize(id, w, h)
        handler.postDelayed({ saveActiveTab() }, 400)
        android.widget.Toast.makeText(this, "已在新标签打开裁剪内容 ${w}×${h}",
            android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 裁剪像素(预乘 ABGR) -> 可写 Bitmap(ARGB straight), 按倍率最近邻放大. */
    private fun cropPixelsToBitmap(px: IntArray, w: Int, h: Int, scale: Int): android.graphics.Bitmap? {
        if (w <= 0 || h <= 0 || px.size < w * h) return null
        val argb = IntArray(w * h)
        for (i in 0 until w * h) {
            val c = px[i]
            val a = (c ushr 24) and 255
            if (a == 0) { argb[i] = 0; continue }
            val r = (((c and 255) * 255 + a / 2) / a).coerceAtMost(255)
            val g = ((((c ushr 8) and 255) * 255 + a / 2) / a).coerceAtMost(255)
            val b = ((((c ushr 16) and 255) * 255 + a / 2) / a).coerceAtMost(255)
            argb[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        var bmp = try {
            android.graphics.Bitmap.createBitmap(argb, w, h, android.graphics.Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) { return null }
        if (scale > 1) {
            val sc = try {
                android.graphics.Bitmap.createScaledBitmap(bmp, w * scale, h * scale, false)
            } catch (_: Throwable) { null }
            if (sc != null) { bmp.recycle(); bmp = sc }
        }
        return bmp
    }

    private fun doExportPsd(uri: android.net.Uri) {
        val f = java.io.File(cacheDir, "endpix_export.psd")
        Thread {
            val r = try { nativeExportPsd(f.absolutePath) } catch (e: Throwable) { "err ${e.message}" }
            val ok = r.startsWith("ok") && f.exists() && copyToUri(f, uri)
            runOnUiThread {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    if (ok) "已导出 PSD" else "PSD 导出失败: $r",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    // 预览当前录制: 后台把 files/timelapse 打成临时 .replay, 直接进画布回放(不必先导出)
    private fun doPreviewRecording() {
        Thread {
            val dir = recordDir()
            val tmp = java.io.File(cacheDir, "endpix_preview.replay")
            val (made, msg) = com.endpix.nativeapp.ui.ReplayIO.buildFromRecording(dir, versionName, tmp)
            if (!made) {
                runOnUiThread {
                    android.widget.Toast.makeText(this@MainActivity, msg,
                        android.widget.Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            val sess = com.endpix.nativeapp.ui.ReplaySession.open(tmp, "预览 · 当前录制")
            runOnUiThread {
                if (sess != null) com.endpix.nativeapp.ui.ReplayHub.open(sess)
                else android.widget.Toast.makeText(this@MainActivity, "回放预览读取失败",
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun doExportReplay() {
        Thread {
            val dir = recordDir()
            val tmp = java.io.File(cacheDir, "endpix_export.replay")
            val (made, msg) = com.endpix.nativeapp.ui.ReplayIO.buildFromRecording(
                dir, versionName, tmp
            )
            if (!made) {
                runOnUiThread {
                    android.widget.Toast.makeText(this@MainActivity, msg, android.widget.Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            val name = "endpix_process_" +
                java.text.SimpleDateFormat("MMdd_HHmmss", java.util.Locale.US).format(java.util.Date()) + ".replay"
            var where = "下载/$name"
            var ok = false
            try {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                        // text/plain 让系统文件选择器可见; 内容仍是 EPREPLAY
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_DOWNLOADS)
                        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                    )
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            tmp.inputStream().use { it.copyTo(out) }
                        }
                        values.clear()
                        values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                        contentResolver.update(uri, values, null, null)
                        ok = true
                    }
                }
            } catch (_: Throwable) { }
            if (!ok) {
                try {
                    val pub = java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS), name
                    )
                    tmp.copyTo(pub, overwrite = true)
                    where = pub.absolutePath
                    ok = true
                } catch (_: Throwable) { }
            }
            runOnUiThread {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    if (ok) "回放已保存到 $where" else "回放保存失败",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun doExportProject(uri: android.net.Uri) {
        saveActiveTab()
        val f = activeTabFile()
        Thread {
            val ok = f.exists() && copyToUri(f, uri)
            runOnUiThread {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    if (ok) "项目已导出" else "项目导出失败",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    private fun displayName(uri: android.net.Uri): String {
        var name = uri.lastPathSegment ?: "file"
        try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        } catch (_: Throwable) { }
        return name
    }

    private fun isSnapFile(f: java.io.File): Boolean = try {
        f.inputStream().use { ins ->
            val h = ByteArray(4)
            ins.read(h) == 4 &&
                ((h[0] == 'X'.code.toByte() && h[1] == 'P'.code.toByte() &&
                    h[2] == 'N'.code.toByte() && h[3] == 'S'.code.toByte()) ||
                 (h[0] == 'P'.code.toByte() && h[1] == 'N'.code.toByte() &&
                    h[2] == 'S'.code.toByte() && h[3] == '2'.code.toByte()))
        }
    } catch (_: Throwable) { false }

    private fun decodeImageDownsampled(uri: android.net.Uri, maxDim: Int = 4096): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try { contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) } }
        catch (_: Throwable) { return null }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        return try {
            contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Throwable) { null }
    }

    private fun setReferenceImageFromUri(uri: android.net.Uri) {
        Thread {
            val bmp = decodeImageDownsampled(uri, 1024)
            if (bmp != null) {
                runOnUiThread { applyReferenceBitmap(bmp) }
            } else {
                runOnUiThread {
                    android.widget.Toast.makeText(this, "参考图读取失败", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** 浏览器长按图片「替换当前参考图」也走这里. */
    private fun applyReferenceBitmap(bmp: android.graphics.Bitmap) {
        try {
            java.io.File(filesDir, "ref_image.png").outputStream().use {
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } catch (_: Throwable) { }
        refBitmapState.value = bmp
        refOnState.value = true
        prefs?.edit()?.putBoolean("uiRefOn", true)?.apply()
        android.widget.Toast.makeText(this, "已设为参考图", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun setSigIconFromUri(uri: android.net.Uri) {
        Thread {
            val bmp = decodeImageDownsampled(uri, 512)
            if (bmp != null) {
                val f = java.io.File(filesDir, "sig_icon.png")
                try {
                    f.outputStream().use {
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                } catch (_: Throwable) { }
                runOnUiThread {
                    sigIconBitmapState.value = bmp
                    saveWatermark(watermarkState.value.copy(sigIconPath = f.absolutePath))
                    android.widget.Toast.makeText(this, "已设置签名图标", android.widget.Toast.LENGTH_SHORT).show()
                }
            } else {
                runOnUiThread {
                    android.widget.Toast.makeText(this, "图标读取失败", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun clearSigIcon() {
        java.io.File(filesDir, "sig_icon.png").delete()
        sigIconBitmapState.value = null
        saveWatermark(watermarkState.value.copy(sigIconPath = ""))
        android.widget.Toast.makeText(this, "已恢复内置品牌图标", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun loadSavedSigIcon() {
        val f = java.io.File(filesDir, "sig_icon.png")
        if (!f.exists()) return
        Thread {
            val bmp = try { android.graphics.BitmapFactory.decodeFile(f.absolutePath) } catch (_: Throwable) { null }
            if (bmp != null) runOnUiThread { sigIconBitmapState.value = bmp }
        }.start()
    }

    private fun loadSavedReferenceImage() {
        val f = java.io.File(filesDir, "ref_image.png")
        if (!f.exists()) return
        Thread {
            val bmp = try { android.graphics.BitmapFactory.decodeFile(f.absolutePath) } catch (_: Throwable) { null }
            if (bmp != null) runOnUiThread { refBitmapState.value = bmp }
        }.start()
    }

    // ---- 横屏额外图片(可缩放/旋转/拖动): 独立文件, 不覆盖竖屏参考图 ----
    private fun setLandscapeImageFromUri(uri: android.net.Uri) {
        Thread {
            val bmp = decodeImageDownsampled(uri, 2048)
            if (bmp != null) {
                try {
                    java.io.File(filesDir, "landscape_image.png").outputStream().use { o ->
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o)
                    }
                } catch (_: Throwable) { }
                val img = bmp.asImageBitmap()
                runOnUiThread {
                    com.endpix.nativeapp.ui.LandscapeRefHub.setBitmap(img, true)
                    android.widget.Toast.makeText(this,
                        "已放置横屏图片：单指拖动，双指缩放/旋转", android.widget.Toast.LENGTH_SHORT).show()
                }
            } else {
                runOnUiThread {
                    android.widget.Toast.makeText(this, "图片读取失败", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun loadSavedLandscapeImage() {
        val f = java.io.File(filesDir, "landscape_image.png")
        if (!f.exists()) {
            com.endpix.nativeapp.ui.LandscapeRefHub.visible = false
            return
        }
        Thread {
            val bmp = try { android.graphics.BitmapFactory.decodeFile(f.absolutePath) } catch (_: Throwable) { null }
            if (bmp != null) runOnUiThread {
                com.endpix.nativeapp.ui.LandscapeRefHub.bitmap = bmp.asImageBitmap()
            } else runOnUiThread { com.endpix.nativeapp.ui.LandscapeRefHub.visible = false }
        }.start()
    }

    // 时间轴专注模式: 画布缩放到时间轴上方剩余区域(旧 AnimateViewStateFree)
    private fun fitCanvasAboveTimeline(): Boolean {
        try {
            val m = Regex("""(\d+)x(\d+)""").find(statsState.value) ?: return false
            val cw = m.groupValues[1].toFloat()
            val ch = m.groupValues[2].toFloat()
            if (cw < 1f || ch < 1f) return false
            val dm = resources.displayMetrics
            val density = dm.density * (uiScalePctState.intValue / 100f)
            val panelPx = 440f * density
            // 顶到性能栏下沿(挖孔圆心 + 信息板半高 + 留白), 底到时间轴上沿
            val cut = window?.decorView?.rootWindowInsets?.displayCutout
            val cutRect = cut?.boundingRects?.maxByOrNull { it.width() * it.height() }
            val camCenterY = if (cutRect != null && cutRect.height() > 0)
                cutRect.top + cutRect.height() * 0.66f
            else 59f * dm.density
            val infoH = 23f * density
            val topInset = (camCenterY + infoH * 0.5f + 8f * density)
                .coerceAtLeast(36f * density)
            val panelTop = (dm.heightPixels - panelPx).coerceAtLeast(topInset + 120f)
            val availH = (panelTop - topInset - 6f * density).coerceAtLeast(120f)
            val availW = (dm.widthPixels - 12f * density).coerceAtLeast(120f)
            val z = minOf(availW / cw, availH / ch).coerceIn(0.02f, 64f)
            val areaCenterY = (topInset + panelTop) * 0.5f
            val px = cw * 0.5f - dm.widthPixels * 0.5f / z
            val py = ch * 0.5f - areaCenterY / z
            nativeAnimateViewState(z, px, py)
            return true
        } catch (_: Throwable) { return false }
    }

    // 时间轴开着时, 换标签/启动恢复/新建画布后都要重新把画布摆到"性能栏下-时间轴上"的中心
    // (这些路径恢复的是标签自己的全屏视角, 不重摆会伸到时间轴背景下面)
    private fun refitTimelineViewSoon() {
        if (!timelineOpenState.value || !started) return
        surfaceView.postDelayed({ refitTimelineView(3) }, 120)
    }

    private fun refitTimelineView(retries: Int) {
        if (!timelineOpenState.value || !started) return
        if (!fitCanvasAboveTimeline() && retries > 0) {
            surfaceView.postDelayed({ refitTimelineView(retries - 1) }, 350)
        }
    }

    private fun minimapNavigateToCanvas(cx: Float, cy: Float, animate: Boolean = false) {
        // 点击=引擎内平滑动画(旧 PanToCanvasPixelAnimated), 拖动=即时跟手
        try { nativeAnimateViewCenter(cx, cy, if (animate) 1 else 0) } catch (_: Throwable) { }
    }

    private fun doLoadFile(uri: android.net.Uri) {
        val name = displayName(uri)
        Thread {
            try {
                val tmp = java.io.File(cacheDir, "endpix_load_new.snap")
                val copied = contentResolver.openInputStream(uri)?.use { ins ->
                    tmp.outputStream().use { out -> ins.copyTo(out) }
                } != null
                android.util.Log.i("EndpixLoad", "doLoadFile name=$name copied=$copied size=${tmp.length()}")
                if (copied && com.endpix.nativeapp.ui.ReplayIO.isReplay(tmp)) {
                    // 回放唯一入口: 加载文件(旧 .replay 与新导出的 EPREPLAY 都走这里)
                    val stable = java.io.File(cacheDir, "endpix_replay_view.replay")
                    tmp.copyTo(stable, overwrite = true)
                    val sess = com.endpix.nativeapp.ui.ReplaySession.open(stable, name)
                    runOnUiThread {
                        if (sess != null) {
                            com.endpix.nativeapp.ui.ReplayHub.open(sess)
                        } else {
                            android.widget.Toast.makeText(this@MainActivity, "回放文件无法读取或版本不支持",
                                android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                } else if (copied && isSnapFile(tmp)) {
                    runOnUiThread { openProjectAsNewTab(tmp, name) }
                } else {
                    val b = imageBounds(uri)
                    if (b != null) {
                        val (iw, ih, _) = b
                        if (iw > MAX_CANVAS || ih > MAX_CANVAS) {
                            runOnUiThread {
                                android.widget.Toast.makeText(this@MainActivity,
                                    "图像尺寸超限 $iw×$ih（最大 ${MAX_CANVAS}×${MAX_CANVAS}），已取消加载",
                                    android.widget.Toast.LENGTH_LONG).show()
                            }
                        } else if (iw <= 4096 && ih <= 4096 && iw.toLong() * ih <= 6_000_000L) {
                            // 普通大小: 原尺寸解码(不再降到 4096), 直接落层
                            var bmp = decodeImageFull(uri)
                            if (bmp == null) {
                                val bytes = try { tmp.readBytes() } catch (_: Throwable) { null }
                                if (bytes != null)
                                    bmp = com.endpix.nativeapp.ui.ExtraImageDecoders.decode(bytes)
                            }
                            if (bmp != null) {
                                runOnUiThread { openImageAsNewTab(bmp, name) }
                            } else {
                                runOnUiThread {
                                    android.widget.Toast.makeText(this@MainActivity, "不是可识别的图像",
                                        android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            // 超大图: 分块按原尺寸导入(不缩水、不进裁剪浮层)
                            runOnUiThread { openImageRegions(uri, iw, ih, name) }
                        }
                    } else {
                        // PSD / ASE 等系统解码不了的格式
                        val bytes = try { tmp.readBytes() } catch (_: Throwable) { null }
                        val bmp = if (bytes != null)
                            com.endpix.nativeapp.ui.ExtraImageDecoders.decode(bytes) else null
                        if (bmp == null) {
                            runOnUiThread {
                                android.widget.Toast.makeText(this@MainActivity, "不是可识别的项目或图像",
                                    android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            runOnUiThread { openImageAsNewTab(bmp, name) }
                        }
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    android.widget.Toast.makeText(this@MainActivity, "加载失败: ${e.message}",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun canAddTab(): Boolean {
        if (!com.endpix.nativeapp.ui.SingleProjectPrefs.on && tabsState.value.size >= 8) {
            android.widget.Toast.makeText(this, "标签栏已达上限(8)", android.widget.Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun openProjectAsNewTab(f: java.io.File, name: String) {
        if (!canAddTab()) return
        // 先读项目快照的画布尺寸/内容量, 按项目原尺寸建标签; 超限项目在切标签前就拒绝, 不碰当前画布
        val dims = try { nativeSnapshotDims(f.absolutePath) } catch (_: Throwable) { null }
        val srcW = dims?.getOrNull(0) ?: 0
        val srcH = dims?.getOrNull(1) ?: 0
        val tiles = dims?.getOrNull(3) ?: 0
        if (srcW > 0 && srcH > 0) {
            val err = canvasLimitError(srcW, srcH)
            if (err != null) {
                android.widget.Toast.makeText(this, "项目画布 ${srcW}×${srcH} 超出上限，已取消加载",
                    android.widget.Toast.LENGTH_LONG).show()
                return
            }
        }
        if (tiles > 8192) {
            android.widget.Toast.makeText(this,
                "项目内容过大（${tiles} 个图块，快照上限 8192），已取消加载",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val dw = if (srcW in 1..MAX_CANVAS) srcW else 4
        val dh = if (srcH in 1..MAX_CANVAS) srcH else 4
        if (!addTab(dw, dh)) return
        val id = activeTabState.intValue
        renameTab(id, name.substringBeforeLast('.').take(12).ifBlank { "项目" })
        noteTabSize(id, dw, dh)
        // 等新文档动作先落地, 再载入内容; 落盘再等载入完成, 避免 4×4 空画布覆盖项目
        handler.postDelayed({
            nativeLoadSnapshot(f.absolutePath)
            handler.postDelayed({ nativeForceSaveSnapshot(tabFile(id).absolutePath) }, 700)
        }, 250)
        android.widget.Toast.makeText(this, "项目已加载到新画布 ${dw}×${dh}",
            android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun openImageAsNewTab(bmp: android.graphics.Bitmap, name: String) {
        if (!canAddTab()) { bmp.recycle(); return }
        val w = bmp.width
        val h = bmp.height
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        for (i in argb.indices) {
            val c = argb[i]
            argb[i] = (c and 0xFF00FF00.toInt()) or ((c and 0xFF) shl 16) or ((c ushr 16) and 0xFF)
        }
        if (!addTab(w, h)) { bmp.recycle(); return }
        val id = activeTabState.intValue
        renameTab(id, name.substringBeforeLast('.').take(12).ifBlank { "图像" })
        noteTabSize(id, w, h)
        nativeImportImage(w, h, argb)
        nativeConfirmSel()   // 加载文件=直接落层(同模板), 不进入裁剪/浮层待确认
        bmp.recycle()
        handler.postDelayed({ saveActiveTab() }, 500)
        android.widget.Toast.makeText(this, "图像已加载到新画布 ${w}×${h}",
            android.widget.Toast.LENGTH_SHORT).show()
    }

    // 只读图片尺寸(不分配像素)
    private fun imageBounds(uri: android.net.Uri): Triple<Int, Int, Int>? {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, o)
            }
        } catch (_: Throwable) { return null }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        return Triple(o.outWidth, o.outHeight, o.outMimeType?.hashCode() ?: 0)
    }

    // 原尺寸解码(不做 4096 降采样), 供加载文件用
    private fun decodeImageFull(uri: android.net.Uri): android.graphics.Bitmap? = try {
        contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null,
                android.graphics.BitmapFactory.Options().apply {
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                })
        }
    } catch (_: Throwable) { null }

    // 超大图片: 分块解码 + 直接写画布(原尺寸, 不缩水, 不经过裁剪浮层)
    private fun openImageRegions(uri: android.net.Uri, w: Int, h: Int, name: String) {
        if (!canAddTab()) return
        if (!addTab(w, h)) return
        val id = activeTabState.intValue
        renameTab(id, name.substringBeforeLast('.').take(12).ifBlank { "图像" })
        noteTabSize(id, w, h)
        Thread {
            var ok = false
            try {
                contentResolver.openInputStream(uri)?.use { ins ->
                    val dec = android.graphics.BitmapRegionDecoder.newInstance(ins, false)
                    if (dec != null) {
                        val opts = android.graphics.BitmapFactory.Options().apply {
                            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                        }
                        val tile = 1024
                        var ty = 0
                        while (ty < h) {
                            val th = minOf(tile, h - ty)
                            var tx = 0
                            while (tx < w) {
                                val tw = minOf(tile, w - tx)
                                val bmp = dec.decodeRegion(android.graphics.Rect(tx, ty, tx + tw, ty + th), opts)
                                if (bmp != null) {
                                    val argb = IntArray(tw * th)
                                    bmp.getPixels(argb, 0, tw, 0, 0, tw, th)
                                    bmp.recycle()
                                    for (i in argb.indices) {
                                        val c = argb[i]
                                        argb[i] = (c and 0xFF00FF00.toInt()) or
                                            ((c and 0xFF) shl 16) or ((c ushr 16) and 0xFF)
                                    }
                                    nativeImportRegion(tx, ty, tw, th, argb)
                                }
                                tx += tw
                            }
                            ty += th
                        }
                        dec.recycle()
                        ok = true
                    }
                }
            } catch (_: Throwable) { ok = false }
            runOnUiThread {
                if (ok) {
                    handler.postDelayed({ saveActiveTab() }, 600)
                    android.widget.Toast.makeText(this, "图像已加载到新画布 ${w}×${h}",
                        android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(this,
                        "图片尺寸 ${w}×${h} 过大或格式不支持分块解码", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun doLoadProject(uri: android.net.Uri) {
        Thread {
            val tmp = java.io.File(cacheDir, "endpix_load.snap")
            val okCopy = try {
                contentResolver.openInputStream(uri)?.use { ins ->
                    tmp.outputStream().use { out -> ins.copyTo(out) }
                } != null
            } catch (_: Throwable) { false }
            runOnUiThread {
                var ok = false
                if (okCopy) {
                    try {
                        nativeLoadSnapshot(tmp.absolutePath)
                        nativeForceSaveSnapshot(activeTabFile().absolutePath)
                        ok = true
                    } catch (_: Throwable) { }
                }
                android.widget.Toast.makeText(
                    this@MainActivity,
                    if (ok) "项目已加载" else "项目加载失败",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }.start()
    }

    private fun saveWatermark(s: com.endpix.nativeapp.ui.WatermarkSettings) {
        watermarkState.value = s
        com.endpix.nativeapp.ui.ExportHub.wmColor = s.wmColor
        com.endpix.nativeapp.ui.ExportHub.wmBaseText = s.wmText
        prefs?.edit()?.apply {
            putBoolean("wmOn", s.wmOn)
            putString("wmText", s.wmText)
            putInt("wmAlpha", s.wmAlpha)
            putInt("wmColor", s.wmColor)
            putInt("wmSize", s.wmSize)
            putBoolean("sigOn", s.sigOn)
            putString("sigText", s.sigText)
            putInt("sigSize", s.sigSize)
            putInt("sigCorner", s.sigCorner)
            putInt("sigBg", s.sigBg)
            putString("sigIconPath", s.sigIconPath)
            putBoolean("sigPortrait", s.sigPortrait)
            putBoolean("sigDate", s.sigDate)
            putInt("sigDistPct", s.sigDistPct)
        }?.apply()
    }

    private fun saveCanvasStyle(s: com.endpix.nativeapp.ui.CanvasStyleSettings) {
        canvasStyleState.value = s
        // 导出预览的棋盘色跟随画布外观
        com.endpix.nativeapp.ui.ExportHub.chkA = s.chkA
        com.endpix.nativeapp.ui.ExportHub.chkB = s.chkB
        prefs?.edit()?.apply {
            putInt("csGridW", s.gridW)
            putInt("csGridH", s.gridH)
            putInt("csGridColor", s.gridColor)
            putInt("csGridAlpha", s.gridAlphaPct)
            putFloat("csGridLineW", s.gridLineW)
            putInt("csChkTile", s.chkTile)
            putInt("csChkA", s.chkA)
            putInt("csChkB", s.chkB)
            putInt("csOutsideBg", s.outsideBg)
            putBoolean("csMagnifier", s.magnifier)
        }?.apply()
        applyCanvasStyle()
    }

    // 后台/进程被杀恢复: 所有工具与界面设置落盘
    private fun saveUiSessionPrefs() {
        prefs?.edit()?.apply {
            putInt("uiTool", toolState.intValue)
            putInt("uiBrushColor", brushColorState.intValue)
            putInt("uiBrushAlpha", brushAlphaState.intValue)
            putFloat("uiBrushPx", brushPxState.floatValue)
            putInt("uiBrushMax", brushMaxState.intValue)
            putFloat("uiEraserPx", eraserPxState.floatValue)
            putInt("uiEraserMax", eraserMaxState.intValue)
            putBoolean("uiEraseMode", eraseModeState.value)
            putBoolean("uiPerfectLine", perfectLineState.value)
            putBoolean("uiBuildup", buildupState.value)
            putBoolean("uiAlphaLock", alphaLockState.value)
            putInt("uiShapeType", shapeTypeState.intValue)
            putBoolean("uiShapeFill", shapeFillState.value)
            putBoolean("uiShapeRatio", shapeRatioState.value)
            putFloat("uiShapePx", shapePxState.floatValue)
            putFloat("uiLeafW", leafWState.floatValue)
            putBoolean("uiSelAllLayers", selAllLayersState.value)
            putBoolean("uiSelAllFrames", selAllFramesState.value)
            putBoolean("uiStretch", stretchModeState.value)
            putBoolean("uiPerspective", SelectionPerspectiveHub.on)
            putBoolean("uiPlayBrush", playBrushState.value)
            putInt("uiSelMode", selModeState.intValue)
            putBoolean("uiChk", chkOnState.value)
            putBoolean("uiGrid", gridOnState.value)
            putBoolean("uiTimelineOpen", timelineOpenState.value)
            putBoolean("uiMinimapOn", minimapOnState.value)
            putInt("uiMinimapBg", minimapBgState.intValue)
            putBoolean("uiRefOn", refOnState.value)
            putFloat("uiMinimapScale", minimapScaleState.floatValue)
            putFloat("uiRefScale", refScaleState.floatValue)
            putFloat("uiMinimapX", minimapXState.floatValue)
            putFloat("uiMinimapY", minimapYState.floatValue)
            putFloat("uiRefX", refXState.floatValue)
            putFloat("uiRefY", refYState.floatValue)
            putBoolean("uiRecordOn", recordOnState.value)
            for (i in 0 until minOf(60, sidePaletteState.value.size))
                putInt("sidePal$i", sidePaletteState.value[i])
            putInt("sidePalCount", SidePalettePrefs.count.intValue.coerceIn(6, 60))
        }?.apply()
    }

    // 原生引擎重建后: 把全部已保存设置重新下发(否则后台回来设置界面还亮着但引擎已默认)
    private fun applyEngineUiSettings() {
        nativeSetMinimapActive(if (minimapOnState.value) 1 else 0)
        nativeSetTool(toolState.intValue)
        nativeSetSelShape(selModeState.intValue)
        nativeSetBrushBuildup(if (buildupState.value) 1 else 0)
        nativeSetPerfectLine(if (perfectLineState.value) 1 else 0)
        nativeSetEraseMode(if (toolState.intValue == 3 && eraseModeState.value) 1 else 0)
        nativeSetShape(shapeTypeState.intValue, if (shapeFillState.value) 1 else 0)
        nativeSetShapeRatio(if (shapeRatioState.value) 1 else 0)
        nativeSetShapeSize(shapePxState.floatValue)
        nativeSetLeafWidth(leafWState.floatValue)
        nativeSetAlphaLock(if (alphaLockState.value) 1 else 0)
        nativeSetSelectionAlphaOverlay(
            if (com.endpix.nativeapp.ui.SelectionBlendHub.alphaOverlay) 1 else 0
        )
        nativeSetSelAllLayers(if (selAllLayersState.value) 1 else 0)
        nativeSetStretch(if (stretchModeState.value) 1 else 0)
        nativeSetPerspective(if (SelectionPerspectiveHub.on) 1 else 0)
        nativeSetPlayBrush(if (playBrushState.value) 1 else 0)
        nativeSetOverlay(if (chkOnState.value) 1 else 0, if (gridOnState.value) 1 else 0)
        nativeSetOnion(if (prefs?.getBoolean("onion", false) == true) 1 else 0)
        applyCanvasStyle()
        applyBrush()
    }

    private fun applyCanvasStyle() {
        val s = canvasStyleState.value
        nativeSetCanvasStyle(s.gridW, s.gridH, s.gridColor, s.gridAlphaPct,
            (s.gridLineW * 100f).toInt(), s.chkTile, s.chkA, s.chkB, s.outsideBg)
        nativeSetMagnifier(if (s.magnifier) 1 else 0)
        nativeSetPngCompress(pngCompressState.intValue)
    }

    // ---- UI 状态(Compose) ----
    private val statsState = mutableStateOf("")
    private val layerMetaState = mutableStateOf<List<Pair<String, Int>>>(emptyList())
    private val toolState = mutableIntStateOf(0)
    private val layersState = mutableIntStateOf(8)
    private val activeState = mutableIntStateOf(0)
    private val brushColorState = mutableIntStateOf(0xFF111111.toInt())
    private val brushPxState = mutableFloatStateOf(6f)
    private val brushMaxState = mutableIntStateOf(60)   // 画笔滑杆上限(10/60 两档)
    private val eraserPxState = mutableFloatStateOf(6f)
    private val eraserMaxState = mutableIntStateOf(60)  // 橡皮独立大小/上限
    private val brushAlphaState = mutableIntStateOf(255)
    private val eraseModeState = mutableStateOf(false)
    private val pinchUndoMsState = mutableIntStateOf(3000)   // 双指落下时回滚首指笔画的时限(0=关闭)
    private val shapeTypeState = mutableIntStateOf(0)
    private val measureFixedState = mutableStateOf(false)
    private val polyVertsState = mutableIntStateOf(0)
    private val measurePendingState = mutableStateOf(false)
    // 测距点标签/读数 60Hz 轻量轮询(不是 500ms stats): 起点/中心/终点窗口跟手
    private val measUiState = mutableStateOf(IntArray(12))
    private val shapeFillState = mutableStateOf(false)
    private val perfectLineState = mutableStateOf(false)
    private val leafWState = mutableFloatStateOf(0.33f)
    private val shapeRatioState = mutableStateOf(false)
    private val shapePxState = mutableFloatStateOf(6f)
    private val selAllLayersState = mutableStateOf(false)
    private val selAllFramesState = mutableStateOf(false)
    private val selModeState = mutableIntStateOf(0)
    private val alphaLockState = mutableStateOf(false)
    private val buildupState = mutableStateOf(false)
    private val portraitLockState = mutableStateOf(true)
    private val selRectState = mutableStateOf(intArrayOf(0, 0, 0, 0, 0))
    private val selFlagState = mutableIntStateOf(0)
    private val stretchModeState = mutableStateOf(false)
    private val recordOnState = mutableStateOf(false)
    private val recordTabs = HashMap<Int, Boolean>()
    private val recordedFramesState = mutableIntStateOf(0)
    private val timelineOpenState = mutableStateOf(false)
    private val playBrushState = mutableStateOf(false)
    private var timelinePrevView: FloatArray? = null
    private val minimapOnState = mutableStateOf(false)
    private val minimapBgState = mutableIntStateOf(3)
    private val thumbRevState = mutableIntStateOf(0)
    private val refOnState = mutableStateOf(false)
    private val projectFolderUriState = mutableStateOf<String?>(null)
    private val projectFolderLabelState = mutableStateOf("")
    private val minimapScaleState = mutableFloatStateOf(1f)
    private val refScaleState = mutableFloatStateOf(1f)
    private val minimapXState = mutableFloatStateOf(8f)
    private val minimapYState = mutableFloatStateOf(68f)
    private val refXState = mutableFloatStateOf(-1f)   // <0 = 默认右上角
    private val refYState = mutableFloatStateOf(68f)
    private val refBitmapState = mutableStateOf<android.graphics.Bitmap?>(null)
    private val sigIconBitmapState = mutableStateOf<android.graphics.Bitmap?>(null)
    private val batteryPctState = mutableIntStateOf(-1)
    private val chargingState = mutableStateOf(false)
    private val memPctState = mutableIntStateOf(-1)
    private val batteryRc = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i == null) return
            val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            batteryPctState.intValue = if (lvl >= 0 && scale > 0) lvl * 100 / scale else -1
            chargingState.value = st == BatteryManager.BATTERY_STATUS_CHARGING ||
                st == BatteryManager.BATTERY_STATUS_FULL
        }
    }
    private var prefs: android.content.SharedPreferences? = null

    // 锁屏/息屏 = 可能随时掉电/被杀: 立即同步存当前标签(快照内部 fsync+原子 rename)
    private val screenOffRc = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action != Intent.ACTION_SCREEN_OFF) return
            try {
                if (started) saveActiveTab()
                saveTabPrefs()
                lastDurableSaveMs = android.os.SystemClock.elapsedRealtime()
            } catch (_: Throwable) { }
        }
    }

    // 首次安装(或清数据后)用打包的默认设置初始化; 老用户已有设置不动.
    // 默认值取自 2026-11-22 USB 主机的当前设置, 已剔除签名图片/签名(sig*)与标签页/浏览器会话.
    private fun seedDefaultSettingsIfEmpty(sp: android.content.SharedPreferences) {
        if (sp.all.isNotEmpty()) return
        try {
            val raw = assets.open("default_settings.json").bufferedReader().use { it.readText() }
            val root = org.json.JSONObject(raw)
            val e = sp.edit()
            for (type in arrayOf("int", "float", "boolean", "string", "long")) {
                val obj = root.optJSONObject(type) ?: continue
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    when (type) {
                        "int" -> e.putInt(k, obj.getInt(k))
                        "float" -> e.putFloat(k, obj.getDouble(k).toFloat())
                        "boolean" -> e.putBoolean(k, obj.getBoolean(k))
                        "string" -> e.putString(k, obj.getString(k))
                        "long" -> e.putLong(k, obj.getLong(k))
                    }
                }
            }
            e.apply()
        } catch (_: Throwable) {
            // 默认设置读失败不影响正常启动
        }
    }
    private val panModeState = mutableStateOf(false)
    private val chkOnState = mutableStateOf(true)
    private val sidePaletteState = mutableStateOf(EP_SIDE_EXT60.copyOf())
    private val palettePresetsState = mutableStateOf<List<PalettePreset>>(emptyList())
    private var presetExportLauncher: androidx.activity.result.ActivityResultLauncher<String>? = null
    private var presetImportLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null
    private var refImageLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null
    private var landscapeImageLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null
    private var sigIconLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null
    private val pickingState = mutableStateOf(false)
    private var lastPickSeq = 0
    private val gridOnState = mutableStateOf(true)
    private val watermarkState = mutableStateOf(com.endpix.nativeapp.ui.WatermarkSettings())
    private val canvasStyleState = mutableStateOf(com.endpix.nativeapp.ui.CanvasStyleSettings())
    private val uiScalePctState = mutableIntStateOf(100)
    private val pngCompressState = mutableIntStateOf(1)
    private val toolYOffsetState = mutableIntStateOf(0)
    private val sfxOnState = mutableStateOf(true)
    private val noiseMixState = mutableStateOf(com.endpix.nativeapp.ui.NoiseMix())   // 白噪音混音
    private val landscapeModeState = mutableStateOf(false)
    private var screenCaptureCallback: Any? = null
    private val landWmOnState = mutableStateOf(false)
    private val landSigOnState = mutableStateOf(false)
    private var savedViewZoom = 0f
    private var savedViewPanX = 0f
    private var savedViewPanY = 0f
    private var savedViewCenterX = 0f
    private var savedViewCenterY = 0f
    private var preserveViewOnRotate = false
    private var savedViewTabId = -1
    private var hasSavedView = false
    private var fitViewOnNextSurface = false
    private var uiSoundPool: android.media.SoundPool? = null
    private var uiTapSound = 0
    private fun initUiSound() {
        if (uiSoundPool != null) return
        try {
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val sp = android.media.SoundPool.Builder()
                .setMaxStreams(4).setAudioAttributes(attrs).build()
            uiTapSound = sp.load(this, R.raw.ui_tap, 1)
            uiSoundPool = sp
        } catch (_: Throwable) { }
    }

    // Compose/ARGB → 原生画布字节序(A<<24|B<<16|G<<8|R)
    private fun toNativeColor(c: Int): Int =
        (c and 0xFF00FF00.toInt()) or ((c and 0xFF) shl 16) or ((c ushr 16) and 0xFF)

    // 把 RGB(画板色) + alpha(子菜单) + 大小 一次性下发给引擎
    private fun applyBrush() {
        val rgb = toNativeColor(brushColorState.intValue) and 0xFFFFFF
        val native = rgb or (brushAlphaState.intValue shl 24)
        val px = if (toolState.intValue == 2) eraserPxState.floatValue else brushPxState.floatValue
        nativeSetBrush(px, native)
        applyBrushStyle()
    }

    private fun applyBrushStyle() {
        val p = com.endpix.nativeapp.ui.BrushHub.current()
        try {
            nativeSetBrushStyle(p.shape, p.hardness, p.spacing, p.scatter, p.aspect, p.angle, p.flow)
        } catch (_: Throwable) { }
    }

    private fun spanOf(ev: MotionEvent): Float {
        if (ev.pointerCount < 2) return 0f
        val dx = ev.getX(0) - ev.getX(1)
        val dy = ev.getY(0) - ev.getY(1)
        return kotlin.math.hypot(dx, dy)
    }

    private fun focusOf(ev: MotionEvent): Pair<Float, Float> {
        if (ev.pointerCount < 2) return Pair(ev.x, ev.y)
        return Pair((ev.getX(0) + ev.getX(1)) * 0.5f, (ev.getY(0) + ev.getY(1)) * 0.5f)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("endpix", MODE_PRIVATE)
        // 系统截屏: 立即收起横屏菜单/☰(Android 14+ 有系统回调; 三指截图另有触摸预判)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            try {
                val cb = object : android.app.Activity.ScreenCaptureCallback {
                    override fun onScreenCaptured() {
                        com.endpix.nativeapp.ui.LandscapeRefHub.hideChromeTemporarily(1800L)
                    }
                }
                screenCaptureCallback = cb
                registerScreenCaptureCallback(mainExecutor, cb)
            } catch (_: Throwable) { }
        }
        // 灰度观看: 只切屏幕显示, 持久化开关
        com.endpix.nativeapp.ui.GrayViewPrefs.on =
            prefs?.getBoolean("grayViewOn", false) ?: false
        com.endpix.nativeapp.ui.GrayViewPrefs.apply = { v ->
            prefs?.edit()?.putBoolean("grayViewOn", v)?.apply()
            try { if (started) nativeSetGrayView(if (v) 1 else 0) } catch (_: Throwable) { }
        }
        // 标签栏显示开关(默认打开): 更多工具「标签栏」直接改全局状态, 这里负责读档+落盘
        com.endpix.nativeapp.ui.TabBarPrefs.visible = prefs?.getBoolean("tabBarVisible", true) ?: true
        com.endpix.nativeapp.ui.TabBarPrefs.persist = { v ->
            prefs?.edit()?.putBoolean("tabBarVisible", v)?.apply()
        }
        initUiSound()   // 预加载 UI 点击音, 第一次点击不延迟
        seedDefaultSettingsIfEmpty(prefs!!)
        recordedFramesState.intValue = countRecordedFrames()
        loadTabPrefs()
        refreshTabSizesAsync()
        com.endpix.nativeapp.ui.TabInfoHub.provider = { id -> buildTabInfo(id) }
        // 缓存列表跨卸载保留: 内部目录为空时从公共 Download/Endpix/cache_projects 恢回复制;
        // 老版本已有缓存没有公共备份的, 启动时补一份
        Thread {
            val n = try { com.endpix.nativeapp.ui.CacheBackup.restore(applicationContext) }
                    catch (_: Throwable) { 0 }
            try { com.endpix.nativeapp.ui.CacheBackup.backupAll(applicationContext) }
            catch (_: Throwable) { }
            if (n > 0) runOnUiThread { com.endpix.nativeapp.ui.ProjectCacheHub.bump() }
        }.start()
        prefs?.let { p ->
            watermarkState.value = com.endpix.nativeapp.ui.WatermarkSettings(
                wmOn = p.getBoolean("wmOn", false),
                wmText = p.getString("wmText", "Endpix") ?: "Endpix",
                wmAlpha = p.getInt("wmAlpha", 20),
                wmColor = p.getInt("wmColor", 0xFFFFFFFF.toInt()),
                wmSize = p.getInt("wmSize", 28),
                sigOn = p.getBoolean("sigOn", false),
                sigText = (p.getString("sigText", "") ?: "").let { st ->
                    if (st.isEmpty() || st == "Endpix") "Immersion technology\n浸没制造" else st
                },
                sigSize = p.getInt("sigSize", 14),
                sigCorner = p.getInt("sigCorner", 3),
                sigBg = p.getInt("sigBg", 0xB21A1A1A.toInt()),
                sigIconPath = p.getString("sigIconPath", "") ?: "",
                sigPortrait = p.getBoolean("sigPortrait", true),
                sigDate = p.getBoolean("sigDate", true),
                sigDistPct = p.getInt("sigDistPct", 0).coerceIn(0, 100)
            )
            com.endpix.nativeapp.ui.ExportHub.wmSize = watermarkState.value.wmSize.coerceIn(8, 96)
            com.endpix.nativeapp.ui.ExportHub.wmColor = watermarkState.value.wmColor
            com.endpix.nativeapp.ui.ExportHub.wmBaseText = watermarkState.value.wmText
            uiScalePctState.intValue = p.getInt("uiScalePct", 100).coerceIn(80, 125)
            com.endpix.nativeapp.ui.TopBarPrefs.centerY = p.getInt("TopBarCenterY", 0).coerceIn(0, 320)
            com.endpix.nativeapp.ui.TopBarPrefs.onCenterY = { v ->
                prefs?.edit()?.putInt("TopBarCenterY", v)?.apply()
            }
            com.endpix.nativeapp.ui.TopBarPrefs.hidePerf = p.getBoolean("hidePerfBar", false)
            com.endpix.nativeapp.ui.TopBarPrefs.hideBattery = p.getBoolean("hideBatteryRing", false)
            com.endpix.nativeapp.ui.TopBarPrefs.onHidePerf = { v ->
                prefs?.edit()?.putBoolean("hidePerfBar", v)?.apply()
            }
            com.endpix.nativeapp.ui.TopBarPrefs.onHideBattery = { v ->
                prefs?.edit()?.putBoolean("hideBatteryRing", v)?.apply()
            }
            com.endpix.nativeapp.ui.SelectionPrefs.holdMs =
                p.getInt("selHoldMs", 1000).coerceIn(200, 3000)
            com.endpix.nativeapp.ui.SelectionPrefs.onHoldMs = { ms ->
                prefs?.edit()?.putInt("selHoldMs", ms)?.apply()
                if (started) {
                    try { nativeSetSelHoldMs(ms) } catch (_: Throwable) { }
                }
            }
            pngCompressState.intValue = p.getInt("pngCompress", 1).coerceIn(0, 2)
            // 旧 toolYOffset(px 档位 0/30/60/90) → 新百分比 -50..50 (0=居中)
            val oldToolY = p.getInt("toolYOffset", 0)
            val migToolY = if (oldToolY != 0) (oldToolY / 6).coerceIn(-50, 50) else 0
            toolYOffsetState.intValue = p.getInt("toolYOffsetPct", migToolY).coerceIn(-50, 50)
            sfxOnState.value = p.getBoolean("sfxOn", true)
            noiseMixState.value = com.endpix.nativeapp.ui.NoiseMix(
                on = p.getBoolean("noiseOn", false),
                master = p.getFloat("noiseMaster", 0.65f).coerceIn(0f, 1f),
                rain = p.getFloat("noiseRain", 0.5f).coerceIn(0f, 1f),
                wind = p.getFloat("noiseWind", 0f).coerceIn(0f, 1f),
                waves = p.getFloat("noiseWaves", 0f).coerceIn(0f, 1f),
                fire = p.getFloat("noiseFire", 0f).coerceIn(0f, 1f),
                colorLevel = p.getFloat("noiseColorLevel", 0f).coerceIn(0f, 1f),
                bands = run {
                    val raw = p.getString("noiseBands", "") ?: ""
                    val v = raw.split(',').mapNotNull { it.toFloatOrNull() }
                    if (v.size >= 8) v.map { it.coerceIn(0f, 1.5f) }
                    else List(8) { 1f }
                },
                ieq = p.getInt("noiseIeq", 0).coerceIn(0, 2),
                width = p.getInt("noiseWidth", 2).coerceIn(0, 3),
                speed = p.getInt("noiseSpeed", 1).coerceIn(0, 2),
                scene = p.getInt("noiseScene", -1),
                speechBlock = p.getBoolean("noiseSpeechBlock", false),
                animOn = p.getBoolean("noiseAnimOn", false),
                animMode = p.getInt("noiseAnimMode", 0).coerceIn(0, 1),
                animCount = p.getInt("noiseAnimCount", 2).coerceIn(1, 4),
                animSpeed = p.getInt("noiseAnimSpeed", 3).coerceIn(0, 6),
                animLow = p.getFloat("noiseAnimLow", 0.5f).coerceIn(0f, 2f),
                animHigh = p.getFloat("noiseAnimHigh", 1.25f).coerceIn(0f, 2f),
                binaural = p.getInt("noiseBinaural", 0).coerceIn(0, 2),
                beatHz = p.getInt("noiseBeatHz", 10).coerceIn(1, 40),
                binauralLevel = p.getFloat("noiseBinLevel", 0f).coerceIn(0f, 1f),
                sleepMin = p.getInt("noiseSleepMin", 0).coerceIn(0, 180),
                fx = p.getInt("noiseFx", 0).coerceIn(0, 8),
                fxLevel = p.getFloat("noiseFxLevel", 0f).coerceIn(0f, 1f),
                tone = p.getInt("noiseTone", 0).coerceIn(0, 6),
                toneLevel = p.getFloat("noiseToneLevel", 0f).coerceIn(0f, 1f),
                solfeggioHz = p.getInt("noiseSolfeggio", 528),
            )
            canvasStyleState.value = com.endpix.nativeapp.ui.CanvasStyleSettings(
                gridW = p.getInt("csGridW", 10),
                gridH = p.getInt("csGridH", 10),
                gridColor = p.getInt("csGridColor", 0xFFFF0000.toInt()),
                gridAlphaPct = p.getInt("csGridAlpha", 60),
                gridLineW = p.getFloat("csGridLineW", 0.75f),
                chkTile = p.getInt("csChkTile", 10),
                chkA = p.getInt("csChkA", 0xFFCCCCCC.toInt()),
                chkB = p.getInt("csChkB", 0xFFFFFFFF.toInt()),
                outsideBg = p.getInt("csOutsideBg", 0xFF0D1117.toInt()),
                magnifier = p.getBoolean("csMagnifier", true)
            )
            // 后台/进程恢复: 工具、笔刷、叠色、图形、选择与时间轴等全部 UI 状态
            toolState.intValue = p.getInt("uiTool", 0).coerceIn(0, 5)
            brushColorState.intValue = p.getInt("uiBrushColor", 0xFF111111.toInt())
            brushAlphaState.intValue = p.getInt("uiBrushAlpha", 255).coerceIn(0, 255)
            brushPxState.floatValue = p.getFloat("uiBrushPx", 6f).coerceIn(0.5f, 100f)
            brushMaxState.intValue = p.getInt("uiBrushMax", 60).coerceIn(10, 200)
            eraserPxState.floatValue = p.getFloat("uiEraserPx", 6f).coerceIn(0.5f, 100f)
            eraserMaxState.intValue = p.getInt("uiEraserMax", 60).coerceIn(10, 200)
            eraseModeState.value = p.getBoolean("uiEraseMode", false)
            perfectLineState.value = p.getBoolean("uiPerfectLine", false)
            buildupState.value = p.getBoolean("uiBuildup", false)
            alphaLockState.value = p.getBoolean("uiAlphaLock", false)
            // 裁剪工具栏「透明叠加」默认关; 记住上次选择并下发引擎
            com.endpix.nativeapp.ui.SelectionBlendHub.alphaOverlay =
                p.getBoolean("selAlphaOverlay", false)
            com.endpix.nativeapp.ui.SelectionBlendHub.onSet = { on ->
                prefs?.edit()?.putBoolean("selAlphaOverlay", on)?.apply()
                try { nativeSetSelectionAlphaOverlay(if (on) 1 else 0) } catch (_: Throwable) { }
            }
            // 框选「透视变形」: 与拉伸旋转互斥; 记住上次模式并下发引擎
            SelectionPerspectiveHub.on = p.getBoolean("uiPerspective", false)
            if (SelectionPerspectiveHub.on) stretchModeState.value = false
            SelectionPerspectiveHub.onSet = { on ->
                if (on) {
                    stretchModeState.value = false
                    nativeSetStretch(0)
                    prefs?.edit()?.putBoolean("uiStretch", false)?.apply()
                }
                nativeSetPerspective(if (on) 1 else 0)
                prefs?.edit()?.putBoolean("uiPerspective", on)?.apply()
            }
                            shapeTypeState.intValue = p.getInt("uiShapeType", 0).coerceIn(0, 10)
            shapeFillState.value = p.getBoolean("uiShapeFill", false)
            shapeRatioState.value = p.getBoolean("uiShapeRatio", false)
            shapePxState.floatValue = p.getFloat("uiShapePx", 6f).coerceIn(0.5f, 100f)
            leafWState.floatValue = p.getFloat("uiLeafW", 0.33f).coerceIn(0.05f, 1f)
            selAllLayersState.value = p.getBoolean("uiSelAllLayers", false)
            selAllFramesState.value = p.getBoolean("uiSelAllFrames", false)
            stretchModeState.value = p.getBoolean("uiStretch", false)
            if (SelectionPerspectiveHub.on) stretchModeState.value = false
            playBrushState.value = p.getBoolean("uiPlayBrush", false)
            selModeState.intValue = p.getInt("uiSelMode", 0).coerceIn(0, 2)
            chkOnState.value = p.getBoolean("uiChk", true)
            gridOnState.value = p.getBoolean("uiGrid", true)
            // 左下快捷键: 旧项目 key ScShort/ScLong; 默认短按=网格线, 长按=重回中心
            com.endpix.nativeapp.ui.ShortcutPrefs.shortId = com.endpix.nativeapp.ui.ShortcutPrefs.normalize(
                p.getString("ScShort", null), "grid")
            com.endpix.nativeapp.ui.ShortcutPrefs.longId = com.endpix.nativeapp.ui.ShortcutPrefs.normalize(
                p.getString("ScLong", null), "recenter")
            com.endpix.nativeapp.ui.ShortcutPrefs.persist = { s, l ->
                prefs?.edit()?.putString("ScShort", s)?.putString("ScLong", l)?.apply()
            }
            timelineOpenState.value = p.getBoolean("uiTimelineOpen", false)
            minimapOnState.value = p.getBoolean("uiMinimapOn", false)
            minimapBgState.intValue = p.getInt("uiMinimapBg", 3).let { if (it in 1..3) it else 3 }
            refOnState.value = p.getBoolean("uiRefOn", false)
            minimapScaleState.floatValue = p.getFloat("uiMinimapScale", 1f).coerceIn(0.8f, 1.8f)
            refScaleState.floatValue = p.getFloat("uiRefScale", 1f).coerceIn(0.8f, 1.8f)
            minimapXState.floatValue = p.getFloat("uiMinimapX", 8f)
            minimapYState.floatValue = p.getFloat("uiMinimapY", 68f)
            refXState.floatValue = p.getFloat("uiRefX", -1f)
            refYState.floatValue = p.getFloat("uiRefY", 68f)
            recordOnState.value = p.getBoolean("uiRecordOn", false)
            p.getString("projectFolderUri", null)?.let {
                projectFolderUriState.value = it
                projectFolderLabelState.value = projectFolderLabel(it)
            }
        }
        migrateLegacyRecordDir()
        loadSavedReferenceImage()
        loadSavedSigIcon()
        // 横屏额外图片: 选择/显示开关持久化(横屏 ☰ 的「放置图片」「显示图片」直接读全局 hub)
        com.endpix.nativeapp.ui.LandscapeRefHub.pick = {
            landscapeImageLauncher?.launch(arrayOf("image/*"))
        }
        com.endpix.nativeapp.ui.LandscapeRefHub.persistVisible = { v ->
            prefs?.edit()?.putBoolean("landscapeRefOn", v)?.apply()
        }
        com.endpix.nativeapp.ui.LandscapeRefHub.visible =
            prefs?.getBoolean("landscapeRefOn", false) ?: false
        loadSavedLandscapeImage()
        // 单项目模式: 打开/新建/模板/缓存都替换当前标签的画布
        com.endpix.nativeapp.ui.SingleProjectPrefs.on =
            prefs?.getBoolean("singleProjectMode", false) ?: false
        com.endpix.nativeapp.ui.SingleProjectPrefs.persist = { v ->
            prefs?.edit()?.putBoolean("singleProjectMode", v)?.apply()
        }
        // 自动检查更新(默认开): 每次进入应用检查 GitHub Releases, 有更新弹提示
        com.endpix.nativeapp.ui.UpdatePrefs.autoCheck =
            prefs?.getBoolean("autoUpdateCheck", true) ?: true
        com.endpix.nativeapp.ui.UpdatePrefs.onAutoCheck = { v ->
            prefs?.edit()?.putBoolean("autoUpdateCheck", v)?.apply()
        }
        // 导出水印(只作用于导出, 不写回画布): 记住上次开关/文字/透明度
        com.endpix.nativeapp.ui.ExportHub.wmOn =
            prefs?.getBoolean("exportWmOn", false) ?: false
        com.endpix.nativeapp.ui.ExportHub.wmText =
            prefs?.getString("exportWmText", "") ?: ""
        com.endpix.nativeapp.ui.ExportHub.wmAlpha =
            (prefs?.getInt("exportWmAlpha", 35) ?: 35).coerceIn(0, 100)
        com.endpix.nativeapp.ui.ExportHub.wmSize =
            (prefs?.getInt("exportWmSize", com.endpix.nativeapp.ui.ExportHub.wmSize)
                ?: com.endpix.nativeapp.ui.ExportHub.wmSize).coerceIn(8, 96)
        com.endpix.nativeapp.ui.ExportHub.wmDensity =
            (prefs?.getInt("exportWmDensity", 100) ?: 100).coerceIn(20, 300)
        // 导出预览底(仿小地图背景模式): 0=棋盘格 1=A色 2=B色 3=深色
        com.endpix.nativeapp.ui.ExportHub.previewBg =
            (prefs?.getInt("exportPreviewBg", 0) ?: 0).coerceIn(0, 3)
        com.endpix.nativeapp.ui.ExportHub.chkA = canvasStyleState.value.chkA
        com.endpix.nativeapp.ui.ExportHub.chkB = canvasStyleState.value.chkB
        com.endpix.nativeapp.ui.ExportHub.onPreviewBg = { v ->
            prefs?.edit()?.putInt("exportPreviewBg", v.coerceIn(0, 3))?.apply()
        }
        // 裁剪目标: 裁剪导出 / 新标签打开
        com.endpix.nativeapp.ui.CropExportHub.onRequest = { requestCropExport() }
        com.endpix.nativeapp.ui.CropExportHub.onNewTab = { openCropAsNewTab() }
        com.endpix.nativeapp.ui.CropExportHub.onStartExport = { scale ->
            pendingPngScale = scale
            exportPngLauncher?.launch("endpix_crop.png")
        }
        // 画笔预设/顶级笔刷系统: 启用状态、参数持久化并下发引擎
        com.endpix.nativeapp.ui.BrushHub.loadPreset(
            (prefs?.getInt("brushPreset", 0) ?: 0).coerceIn(
                0, com.endpix.nativeapp.ui.BrushHub.presets.size - 1)
        )
        com.endpix.nativeapp.ui.BrushHub.loadStyle(
            prefs?.getString("brushStyle", null),
            prefs?.getBoolean("brushAdvanced", false) ?: false
        )
        com.endpix.nativeapp.ui.BrushHub.onApply = {
            prefs?.edit()
                ?.putInt("brushPreset", com.endpix.nativeapp.ui.BrushHub.index)
                ?.putBoolean("brushAdvanced", com.endpix.nativeapp.ui.BrushHub.enabled)
                ?.putString("brushStyle", com.endpix.nativeapp.ui.BrushHub.styleCsv())
                ?.apply()
            applyBrushStyle()
        }
        registerReceiver(batteryRc, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        androidx.core.content.ContextCompat.registerReceiver(
            this, screenOffRc, IntentFilter(Intent.ACTION_SCREEN_OFF),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
        // 调试脚本广播(adb): am broadcast -a com.endpix.DEBUG_SCRIPT -p com.endpix.nativeapp
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
                    try {
                        val f = java.io.File(getExternalFilesDir(null), "debug.txt")
                        if (!f.exists()) return
                        val txt = f.readText()
                        if (txt.isBlank()) return
                        val logFile = java.io.File(getExternalFilesDir(null), "debug.log")
                        if (txt.trim() == "proxybatch") {
                            logFile.appendText("proxybatch start\n")
                            com.endpix.nativeapp.ui.ProxyHub.debugBatchInfo(ctx, logFile)
                            f.writeText("")
                            return
                        }
                        if (txt.trim() == "tabdims") {                            val sb = StringBuilder()
                            for (t in tabsState.value) {
                                val v = try { nativeSnapshotDims(tabFile(t.id).absolutePath) } catch (_: Throwable) { null }
                                val f2 = tabFile(t.id)
                                sb.append("tabdims id=").append(t.id)
                                    .append(" exists=").append(if (f2.exists()) 1 else 0)
                                    .append(" len=").append(if (f2.exists()) f2.length() else 0)
                                    .append(" dims=").append(v?.joinToString(",") ?: "null")
                                    .append(" cached=").append(tabSizesState[t.id]?.let { "${it.first}x${it.second}" } ?: "-")
                                    .append("\n")
                            }
                            logFile.appendText(sb.toString())
                            f.writeText("")
                            return
                        }
                        val jscmd = txt.trim()
                        if (jscmd.startsWith("openreplay ")) {
                            val id = jscmd.removePrefix("openreplay ").trim()
                            logFile.appendText("openreplay $id\n")
                            f.writeText("")
                            openCachedReplay(id)
                            return
                        }
                        if (jscmd.startsWith("js ")) {
                            val code = jscmd.removePrefix("js ")
                            com.endpix.nativeapp.ui.BrowserHub.webView?.evaluateJavascript(code) { r ->
                                try { logFile.appendText("js=" + r + (13.toChar()).toString() + (10.toChar()).toString()) } catch (_: Throwable) { }
                            }
                            f.writeText("")
                            return
                        }
                        if (txt.trim() == "browserurl") {
                            logFile.appendText(
                                "browserurl=" + com.endpix.nativeapp.ui.BrowserHub.url +
                                    " err=" + com.endpix.nativeapp.ui.BrowserHub.error + "\n"
                            )
                            f.writeText("")
                            return
                        }
                        val bcmd = txt.trim()
                        if (bcmd.startsWith("browser ")) {
                            val u = bcmd.removePrefix("browser ").trim()
                            com.endpix.nativeapp.ui.BrowserHub.open(this@MainActivity)
                            com.endpix.nativeapp.ui.BrowserHub.navigate(u)
                            logFile.appendText("browser -> $u\n")
                            f.writeText("")
                            return
                        }
                        if (nativeDebugScript(txt, logFile.absolutePath)) f.writeText("")
                    } catch (_: Throwable) { }
                }
            },
            IntentFilter("com.endpix.DEBUG_SCRIPT"),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED
        )
        // 形状渐变面板(方形/圆形)回调: 换预设/应用/取消
        com.endpix.nativeapp.ui.GradHub.onPreset = { p -> try { nativeGradPreset(p) } catch (_: Throwable) { } }
        com.endpix.nativeapp.ui.GradHub.onRange = { p -> try { nativeGradRange(p) } catch (_: Throwable) { } }
        com.endpix.nativeapp.ui.GradHub.onAngle = { d -> try { nativeGradAngle(d) } catch (_: Throwable) { } }
        com.endpix.nativeapp.ui.GradHub.onPos = { p -> try { nativeGradPos(p) } catch (_: Throwable) { } }
        com.endpix.nativeapp.ui.GradHub.onApply = { try { nativeGradApply() } catch (_: Throwable) { } }
        com.endpix.nativeapp.ui.GradHub.onCancel = { try { nativeGradCancel() } catch (_: Throwable) { } }
        // 回放: 直接复用项目画布的 native 视口(平移/缩放/旋转跟随)
        com.endpix.nativeapp.ui.ReplayViewOps.rect = {
            try { if (started) nativeCanvasRect() else intArrayOf() } catch (_: Throwable) { intArrayOf() }
        }
        com.endpix.nativeapp.ui.ReplayViewOps.zoom = { f, x, y ->
            try { if (started) nativeZoom(f, x, y) } catch (_: Throwable) { }
        }
        com.endpix.nativeapp.ui.ReplayViewOps.pan = { dx, dy ->
            try { if (started) nativePan(dx, dy) } catch (_: Throwable) { }
        }
        com.endpix.nativeapp.ui.ReplayViewOps.fit = {
            try { if (started) nativeZoomFit() } catch (_: Throwable) { }
        }
        com.endpix.nativeapp.ui.ReplayViewOps.setFrame = { bmp ->
            try {
                if (started) {
                    if (bmp == null || bmp.width <= 0 || bmp.height <= 0) {
                        nativeReplaySetFrame(1, 1, intArrayOf(0))
                    } else {
                        val w = bmp.width; val h = bmp.height
                        val px = IntArray(w * h)
                        bmp.getPixels(px, 0, w, 0, 0, w, h)
                        nativeReplaySetFrame(w, h, px)
                    }
                }
            } catch (_: Throwable) { }
        }
        com.endpix.nativeapp.ui.ReplayViewOps.clearFrame = {
            try { if (started) nativeReplayClear() } catch (_: Throwable) { }
        }
        // 缓存项目: 关闭标签/设置导出时保留完整快照, 缓存列表里打开
        com.endpix.nativeapp.ui.ProjectCacheHub.saveActive = { cb -> saveActiveTabToCache(cb) }
        com.endpix.nativeapp.ui.ProjectCacheHub.open = { id -> openCachedProject(id) }
        com.endpix.nativeapp.ui.ProjectCacheHub.openReplay = { id -> openCachedReplay(id) }
        com.endpix.nativeapp.ui.ProjectCacheHub.delete = { id -> deleteCacheProject(id) }
        // 模板库/缓存列表: 导出模式(保存文件 / 分享到微信QQ等应用)
        com.endpix.nativeapp.ui.StudioShareHub.shareTemplate = { id -> shareTemplate(id) }
        com.endpix.nativeapp.ui.StudioShareHub.exportCache = { id -> launchCacheExport(id) }
        com.endpix.nativeapp.ui.StudioShareHub.shareCache = { id -> shareCache(id) }
        // 过程 / 回放 → 导出到缓存: 当前录制打包为 r_*.replay 进缓存列表
        com.endpix.nativeapp.ui.ReplayCacheHub.cacheNow = { doCacheReplay() }
        com.endpix.nativeapp.ui.CloseTabHub.close = { id -> closeTab(id) }
        com.endpix.nativeapp.ui.CloseTabHub.export = { pickProjectExport() }
        com.endpix.nativeapp.ui.CloseTabHub.keep = { id -> saveActiveTabToCache { ok -> if (ok) closeTab(id) } }
        // 回放: 步骤延迟持久化; 播放中解锁旋转(支持横屏播放), 退出恢复竖屏/横屏模式
        com.endpix.nativeapp.ui.ReplayHub.loadStepDelay(prefs?.getInt("replayStepMs", 120) ?: 120)
        com.endpix.nativeapp.ui.ReplayHub.persistStepDelay = { ms ->
            prefs?.edit()?.putInt("replayStepMs", ms)?.apply()
        }
        com.endpix.nativeapp.ui.ReplayHub.onVisibilityChanged = { vis ->
            if (vis) {
                replaySavedView = try { nativeGetView() } catch (_: Throwable) { null }
                landscapeModeState.value = false
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                try { nativeZoomFit() } catch (_: Throwable) { }
            } else {
                replaySavedView?.let { v ->
                    if (v.size >= 3) try { nativeSetView(v[0], v[1], v[2]) } catch (_: Throwable) { }
                }
                replaySavedView = null
                val land = !portraitLockState.value &&
                    resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
                requestedOrientation = if (portraitLockState.value)
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                landscapeModeState.value = land
            }
        }
        pinchUndoMsState.intValue = prefs?.getInt("pinchUndoMs", 3000) ?: 3000
        portraitLockState.value = prefs?.getBoolean("fixedPortrait", true) ?: true
        landscapeModeState.value = !portraitLockState.value &&
            resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (portraitLockState.value)
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        presetExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) exportPresetsTo(uri)
        }
        presetImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importPresetsFrom(uri)
        }
        refImageLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) setReferenceImageFromUri(uri)
        }
        landscapeImageLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) setLandscapeImageFromUri(uri)
        }
        sigIconLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) setSigIconFromUri(uri)
        }
        exportPngLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
            if (uri != null) doExportPng(uri)
        }
        exportGifLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("image/gif")) { uri ->
            if (uri != null) doExportGif(uri)
        }
        exportZipLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) doExportFrames(uri)
        }
        exportWebpLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("image/webp")) { uri ->
            if (uri != null) doExportWebp(uri)
        }
        templateImportLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importTemplate(uri)
        }
        templateExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("image/webp")) { uri ->
            if (uri != null) pendingTemplateExportId?.let { exportTemplate(it, uri) }
        }
        cacheExportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            if (uri != null) pendingCacheExportId?.let { exportCacheFile(it, uri) }
        }
        exportProjectLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { res ->
            if (res.resultCode == android.app.Activity.RESULT_OK) {
                res.data?.data?.let { doExportProject(it) }
            }
        }
        exportPsdLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("image/vnd.adobe.photoshop")) { uri ->
            if (uri != null) doExportPsd(uri)
        }
        loadProjectLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { res ->
            if (res.resultCode == android.app.Activity.RESULT_OK) {
                res.data?.data?.let { doLoadProject(it) }
            }
        }
        loadFileLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { res ->
            if (res.resultCode == android.app.Activity.RESULT_OK) {
                res.data?.data?.let { doLoadFile(it) }
            }
        }
        projectFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Throwable) { }
                projectFolderUriState.value = uri.toString()
                projectFolderLabelState.value = projectFolderLabel(uri.toString())
                prefs?.edit()?.putString("projectFolderUri", uri.toString())?.apply()
            }
        }
        importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                try {
                    contentResolver.openInputStream(uri).use { ins ->
                        val bmp = BitmapFactory.decodeStream(ins)
                        if (bmp == null) {
                            android.widget.Toast.makeText(
                                this@MainActivity, "图片解码失败", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        if (bmp != null) {
                            val w = bmp.width; val h = bmp.height
                            val argb = IntArray(w * h)
                            bmp.getPixels(argb, 0, w, 0, 0, w, h)
                            for (i in argb.indices) {
                                val c = argb[i]
                                argb[i] = (c and 0xFF00FF00.toInt()) or
                                    ((c and 0xFF) shl 16) or ((c ushr 16) and 0xFF)
                            }
                            pendingImportW = w
                            pendingImportH = h
                            pendingImportPx = argb
                            flushPendingImport()
                            bmp.recycle()
                        }
                    }
                } catch (_: Exception) { }
            }
        }
        // 旧项目右侧色板是持久化的; 60 格后备, 可见 6–60 色(19 色起右侧滚动)
        run {
            val sp = getSharedPreferences("endpix", MODE_PRIVATE)
            val p = IntArray(60) { i -> sp.getInt("sidePal$i", EP_SIDE_EXT60[i]) }
            sidePaletteState.value = dedupePalette18(p)
            SidePalettePrefs.count.intValue =
                sp.getInt("sidePalCount", 6).coerceIn(6, 60)
            loadPalettePresets()
            // 调色板弹窗 → Activity: 槽位覆盖 / 预设改名 / 色数落盘
            PaletteHub.onSetSlot = { i, c ->
                val cur = sidePaletteState.value
                if (i in cur.indices) {
                    val full = cur.copyOf()
                    full[i] = c
                    sidePaletteState.value = full
                    persistSidePalette()
                }
            }
            PaletteHub.onRenamePreset = { i, n -> renamePalettePreset(i, n) }
            PaletteHub.onCountPersist = { persistSidePalette() }
            // 色板子菜单「两色混合」: 连续吸两色, 第二色落下后合成并应用
            MixPickHub.onStart = {
                MixPickHub.stage.intValue = 0
                MixPickHub.colorA.intValue = 0
                MixPickHub.startSeq.intValue = lastPickSeq
                pickingState.value = true
                nativeSetPickMode(2)
            }
            brushColorState.intValue = p[0]
            com.endpix.nativeapp.ui.BottomBarPrefs.showMove.value = sp.getBoolean("uiShowMove", true)
            com.endpix.nativeapp.ui.BottomBarPrefs.showZoom.value = sp.getBoolean("uiShowZoom", true)
        }
        // 回放中枢: 录制中把工具/面板操作写入 ui.csv; 过程面板可直接预览当前录制
        com.endpix.nativeapp.ui.ReplayHub.logSink = { label ->
            if (recordOnState.value && !com.endpix.nativeapp.ui.ReplayHub.visible) {
                try { nativeRecordUiEvent(0, label) } catch (_: Throwable) { }
            }
        }
        com.endpix.nativeapp.ui.ReplayHub.previewSink = { doPreviewRecording() }
        com.endpix.nativeapp.ui.BrowserHub.init(this)
        com.endpix.nativeapp.ui.RefImageBridge.apply = { bmp ->
            runOnUiThread { applyReferenceBitmap(bmp) }
        }
        com.endpix.nativeapp.ui.NetPing.start()
        com.endpix.nativeapp.ui.SelOps.adjust = { h, s, b, a, blur, commit ->
            nativeSelAdjust(h, s, b, a, blur, commit)
        }
        com.endpix.nativeapp.ui.SelOps.stroke = { mode, size, dir, outer, depth, color, commit ->
            // Compose ARGB → 引擎 ABGR; 之前直接传会把红/蓝互换(选青色画出黄色)
            nativeSelStroke(mode, size, dir, outer, depth, toNativeColor(color), commit)
        }
        com.endpix.nativeapp.ui.SelOps.restore = { nativeSelRestore() }
        com.endpix.nativeapp.ui.SelOps.cancelPreview = { nativeSelCancelPreview() }
        com.endpix.nativeapp.ui.TextOps.create = { text, fontIdx, mult, color ->
            val bmp = com.endpix.nativeapp.ui.TextTool.render(this, text, fontIdx, mult, color)
            if (bmp == null) {
                android.widget.Toast.makeText(this, "文字为空，未添加", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                val fit = fitToCanvas(bmp)
                val w = fit.width
                val h = fit.height
                val argb = IntArray(w * h)
                fit.getPixels(argb, 0, w, 0, 0, w, h)
                for (i in argb.indices) {
                    val c = argb[i]
                    argb[i] = (c and 0xFF00FF00.toInt()) or ((c and 0xFF) shl 16) or ((c ushr 16) and 0xFF)
                }
                fit.recycle()
                if (!started) {
                    android.widget.Toast.makeText(this, "引擎未就绪，请稍后再试",
                        android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    // 文字走"裁剪中复制浮层": 不是普通汇入, 落点后可拖动/确认
                    nativeImportTextImage(w, h, argb)
                    // 文字浮层需要框选工具的手势来拖动/确认
                    toolState.intValue = 1
                    nativeSetTool(1)
                    android.widget.Toast.makeText(this, "文字已放到画布，拖动调整位置",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        com.endpix.nativeapp.ui.LayerOps.setProps = { layer, mode, tw, th, ox, oy, sg, col, indep, lk, blend ->
            nativeSetLayerProps(layer, mode, tw, th, ox, oy, sg, col, indep, lk, blend)
        }
        com.endpix.nativeapp.ui.LayerOps.getProps = { nativeLayerProps() }
        com.endpix.nativeapp.ui.LayerOps.set25D = { on, depth, dir -> nativeSet25D(on, depth, dir) }
        com.endpix.nativeapp.ui.LayerOps.reset25DView = { nativeReset25DView() }
        com.endpix.nativeapp.ui.LayerOps.get25D = { nativeGet25D() }
        com.endpix.nativeapp.ui.ExportOps.run = { path, kind, scale, cols, delayMs ->
            nativeExportMode(path, kind, scale, cols, delayMs)
        }
        com.endpix.nativeapp.ui.ExportOps.info = { nativeExportInfo() }
        com.endpix.nativeapp.ui.ProxyHub.init(this)
        installShortcutHandlers()   // 左下快捷键短按/长按动作表

        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)

        // 配置(adb 触发): files/canvas 尺寸, files/layers 层数, files/bench 跑基准
        try {
            val cf = java.io.File(getExternalFilesDir(null), "canvas")
            if (cf.exists()) {
                cf.readText().trim().toIntOrNull()?.let { if (it > 0) canvasW = it }
            }
        } catch (_: Throwable) { }
        canvasH = intent.getIntExtra("canvasH", canvasW)
        canvasW = canvasW.coerceIn(16, MAX_CANVAS)
        canvasH = canvasH.coerceIn(16, MAX_CANVAS)
        try {
            val lf = java.io.File(getExternalFilesDir(null), "layers")
            if (lf.exists()) lf.readText().trim().toIntOrNull()?.let { if (it in 1..32) canvasLayers = it }
        } catch (_: Throwable) { }
        layersState.value = canvasLayers
        activeState.value = 0
        benchMode = java.io.File(getExternalFilesDir(null), "bench").exists() ||
                intent.getBooleanExtra("bench", false)

        val root = android.widget.FrameLayout(this)
        root.addView(surfaceView, android.widget.FrameLayout.LayoutParams(-1, -1))
        val composeView = ComposeView(this)
        composeViewRef = composeView
        composeView.setContent {
            EndpixTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                if (landscapeModeState.value) {
                    com.endpix.nativeapp.ui.LandscapeScreen(
                        watermark = watermarkState.value,
                        wmOn = landWmOnState.value,
                        sigOn = landSigOnState.value,
                        sigDate = watermarkState.value.sigDate,
                        sigIcon = sigIconBitmapState.value,
                        onToggleWm = { landWmOnState.value = !landWmOnState.value },
                        onToggleSig = { landSigOnState.value = !landSigOnState.value },
                        onToggleSigDate = {
                            saveWatermark(watermarkState.value.copy(sigDate = !watermarkState.value.sigDate))
                        },
                        onScreenshot = { captureLandscapeScreenshot() },
                        onWatermark = { w -> saveWatermark(w) },
                        onPickSigIcon = { sigIconLauncher?.launch(arrayOf("image/*")) },
                        onClearSigIcon = { clearSigIcon() }
                    )
                } else if (benchMode) {
                    val report = remember { mutableStateOf("bench running…") }
                    LaunchedEffect(Unit) {
                        report.value = withContext(Dispatchers.IO) { nativeCanvasBench() }
                    }
                    Text(
                        report.value,
                        Modifier.fillMaxSize().padding(16.dp),
                        color = Ep.Text, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                    )
                } else {
                    LaunchedEffect(Unit) {
                        while (true) {
                            val s = try { nativeStats() } catch (_: Throwable) { "" }
                            statsState.value = s
                            parseStats(s)
                            try {
                                val lm = parseLayerMeta(nativeLayerMeta())
                                if (lm != layerMetaState.value) layerMetaState.value = lm
                            } catch (_: Throwable) { }
                            // 缩略图版本: 小地图只在内容/帧变化时重建位图(避免每 300ms 无谓 JNI+Bitmap)
                            try {
                                val r = nativeThumbRevision()
                                if (r != thumbRevState.intValue) thumbRevState.intValue = r
                            } catch (_: Throwable) { }
                            delay(500)   // 2Hz: 全屏 stats 重组更少, 悬浮窗拖动/绘制更稳
                        }
                    }
                    // 选区面板跟手: 60Hz 轻量轮询(不是 stats)
                    LaunchedEffect(Unit) {
                        while (true) {
                            try {
                                val r = nativeSelRect()
                                // 只在选区真正变化时写 State, 否则每 16ms 一次数组+重组会拖累整个界面
                                if (!r.contentEquals(selRectState.value)) selRectState.value = r
                                if (r[4] != selFlagState.intValue) selFlagState.intValue = r[4]
                                // 裁剪目标可用性: bit16=浮起且确实是裁剪出的目标(复制浮层不算)
                                val cropTr = (r[4] and 16) != 0
                                if (cropTr != com.endpix.nativeapp.ui.CropExportHub.targetReady)
                                    com.endpix.nativeapp.ui.CropExportHub.targetReady = cropTr
                                // 形状渐变裁剪状态(方形/圆形): 拖完形状后显示预设面板
                                try {
                                    val g = nativeGradUi()
                                    val ga = g.size >= 4 && g[0] != 0
                                    if (ga != com.endpix.nativeapp.ui.GradHub.active)
                                        com.endpix.nativeapp.ui.GradHub.active = ga
                                    if (g.size >= 4 && g[1] != com.endpix.nativeapp.ui.GradHub.shape)
                                        com.endpix.nativeapp.ui.GradHub.shape = g[1]
                                    if (g.size >= 4 && g[2] != com.endpix.nativeapp.ui.GradHub.preset)
                                        com.endpix.nativeapp.ui.GradHub.preset = g[2]
                                    if (g.size >= 4 && g[3] != com.endpix.nativeapp.ui.GradHub.range)
                                        com.endpix.nativeapp.ui.GradHub.range = g[3]
                                    if (g.size >= 7 && g[5] != com.endpix.nativeapp.ui.GradHub.angle)
                                        com.endpix.nativeapp.ui.GradHub.angle = g[5]
                                    if (g.size >= 7 && g[6] != com.endpix.nativeapp.ui.GradHub.pos)
                                        com.endpix.nativeapp.ui.GradHub.pos = g[6]
                                } catch (_: Throwable) { }
                                // 测距起/中/终标签同样 60Hz(旧值不变不写 State)
                                val m = nativeMeasUi()
                                if (!m.contentEquals(measUiState.value)) measUiState.value = m
                            } catch (_: Throwable) { }
                            val fastPoll = (selFlagState.intValue and 1) != 0 ||
                                (toolState.intValue == 4 && shapeTypeState.intValue == 6) ||
                                com.endpix.nativeapp.ui.GradHub.active
                            delay(if (fastPoll) 16 else 50)
                        }
                    }
                    LaunchedEffect(Unit) {
                        while (true) {
                            kotlinx.coroutines.delay(1000)
                            val m = autoSaveMin
                            if (m <= 0) {
                                autoSaveRemainState.intValue = -1
                                autoSaveSavingState.value = false
                                continue
                            }
                            val now = System.currentTimeMillis()
                            val total = m * 60_000L
                            val left = (lastAutoSaveMs + total - now + 999) / 1000
                            autoSaveRemainState.intValue = left.coerceAtLeast(0L).toInt()
                            if (started && !autoSaveSavingState.value && now - lastAutoSaveMs >= total) {
                                autoSaveSavingState.value = true
                                lastAutoSaveMs = now
                                try {
                                    withContext(Dispatchers.IO) { saveActiveTab() }
                                } catch (_: Throwable) { }
                                autoSaveSavingState.value = false
                                autoSaveRemainState.intValue = m * 60
                            }
                        }
                    }
                    LaunchedEffect(Unit) {
                        val am = getSystemService(ActivityManager::class.java)
                        while (true) {
                            try {
                                val mi = ActivityManager.MemoryInfo()
                                am.getMemoryInfo(mi)
                                memPctState.intValue =
                                    ((mi.totalMem - mi.availMem) * 100 / mi.totalMem).toInt()
                            } catch (_: Throwable) { }
                            delay(2000)
                        }
                    }
                    // 无界面导出触发(调试): files/export 里写倍率, 导出到 files/export.png 并写 export.log
                    LaunchedEffect(Unit) {
                        while (true) {
                            try {
                                val f = java.io.File(getExternalFilesDir(null), "export")
                                if (f.exists()) {
                                    val sc = f.readText().trim().toIntOrNull() ?: 1
                                    f.delete()
                                    withContext(Dispatchers.IO) {
                                        val out = java.io.File(getExternalFilesDir(null), "export.png").absolutePath
                                        val r = nativeExportPng(out, sc)
                                        java.io.File(getExternalFilesDir(null), "export.log").writeText(r)
                                    }
                                }
                            } catch (_: Throwable) { }
                            delay(400)
                        }
                    }
                    val baseDensity = androidx.compose.ui.platform.LocalDensity.current
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(
                            baseDensity.density * (uiScalePctState.intValue / 100f),
                            baseDensity.fontScale
                        )
                    ) {
                    MainScreen(
                        stats = statsState.value,
                        tool = toolState.intValue,
                        layers = layersState.intValue,
                        activeLayer = activeState.intValue,
                        brushColor = brushColorState.intValue,
                        onTool = { t ->
                            toolState.intValue = t
                            com.endpix.nativeapp.ui.ToolHub.current = t
                            nativeSetTool(t)
                            // 移除像素只属于油漆桶: 画笔/橡皮/图形不继承桶的擦除模式
                            nativeSetEraseMode(if (t == 3 && eraseModeState.value) 1 else 0)
                            applyBrush()
                            if (t != 1) {
                                // 切离框选: 结束选区并取消取色, 子模式不跨操作存在
                                nativeDeselect()
                                pickingState.value = false
                                nativeSetPickMode(0)
                                MixPickHub.reset()
                            }
                        },
                        onDeselect = { nativeDeselect() },
                        onSelectAll = { nativeSelectAll() },
                        onZoomFit = { nativeZoomFit() },
                        onFlip = { h -> nativeFlip(if (h) 1 else 0) },
                        onGrayscale = { nativeGrayscale() },
                        brushPx = brushPxState.floatValue,
                        brushMax = brushMaxState.intValue,
                        eraserPx = eraserPxState.floatValue,
                        eraserMax = eraserMaxState.intValue,
                        brushAlpha = brushAlphaState.intValue,
                        eraseMode = eraseModeState.value,
                        pinchUndoMs = pinchUndoMsState.intValue,
                        onPinchUndoMs = { v ->
                            pinchUndoMsState.intValue = v
                            prefs?.edit()?.putInt("pinchUndoMs", v)?.apply()
                        },
                        onBrushPx = { v -> brushPxState.floatValue = v; applyBrush() },
                        onBrushMax = { m -> brushMaxState.intValue = m },
                        onEraserPx = { v -> eraserPxState.floatValue = v; applyBrush() },
                        onEraserMax = { m -> eraserMaxState.intValue = m },
                        onBrushAlpha = { a ->
                            brushAlphaState.intValue = a
                            brushColorState.intValue = (brushColorState.intValue and 0x00FFFFFF) or (a shl 24)
                            brushColorState.intValue =
                                (brushColorState.intValue and 0xFFFFFF) or (a shl 24)
                            applyBrush()
                        },
                        onEraseMode = { on ->
                            eraseModeState.value = on
                            nativeSetEraseMode(if (on) 1 else 0)
                        },
                        shapeType = shapeTypeState.intValue,
                        shapeFill = shapeFillState.value,
                        perfectLine = perfectLineState.value,
                        onPerfectLine = { on ->
                            perfectLineState.value = on
                            nativeSetPerfectLine(if (on) 1 else 0)
                        },
                        leafW = leafWState.floatValue,
                        onLeafW = { w -> leafWState.floatValue = w; nativeSetLeafWidth(w) },
                        shapePx = shapePxState.floatValue,
                        onShapePx = { px -> shapePxState.floatValue = px; nativeSetShapeSize(px) },
                        measureFixed = measureFixedState.value,
                        measurePending = measurePendingState.value,
                        onMeasureSetStart = {
                            measurePendingState.value = true
                            nativeMeasureSetStartPending()
                        },
                        onMeasureCenterStart = {
                            measureFixedState.value = true
                            measurePendingState.value = false
                            nativeMeasureCenterStart()
                        },
                        onMeasureFingerStart = {
                            measureFixedState.value = false
                            measurePendingState.value = false
                            nativeSetMeasureFixed(0)
                        },
                        polyVerts = polyVertsState.intValue,
                        onPolyUndo = { nativePolyOp(0) },
                        onPolyReset = { nativePolyOp(1) },
                        onPolyFinish = { nativePolyOp(2) },
                        onPolyCancel = { nativePolyOp(3) },
                        onSelShape = { sh -> nativeSetSelShape(sh) },
                        selAllLayers = selAllLayersState.value,
                        onSelAllLayers = { on ->
                            selAllLayersState.value = on
                            nativeSetSelAllLayers(if (on) 1 else 0)
                        },
                        onStretch = { w, h -> nativeScaleSel(w, h) },
                        shapeRatio = shapeRatioState.value,
                        onShapeRatio = { on ->
                            shapeRatioState.value = on
                            nativeSetShapeRatio(if (on) 1 else 0)
                        },
                        alphaLock = alphaLockState.value,
                        buildup = buildupState.value,
                        onBuildup = { on ->
                            buildupState.value = on
                            nativeSetBrushBuildup(if (on) 1 else 0)
                        },
                        onAlphaLock = { on ->
                            alphaLockState.value = on
                            nativeSetAlphaLock(if (on) 1 else 0)
                        },
                        batteryPct = batteryPctState.intValue,
                        charging = chargingState.value,
                        memPct = memPctState.intValue,
                        portraitLock = portraitLockState.value,
                        onPortraitLock = { on ->
                            portraitLockState.value = on
                            prefs?.edit()?.putBoolean("fixedPortrait", on)?.apply()
                            requestedOrientation = if (on)
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        },
                        onPasteLastCut = { nativePasteLastCut() },
                        selRect = selRectState,
                        selFlag = selFlagState,
                        measUi = measUiState,
                        stretchMode = stretchModeState.value,
                        onSetStretch = { on ->
                            if (on) SelectionPerspectiveHub.set(false)   // 与透视变形互斥
                            stretchModeState.value = on
                            nativeSetStretch(if (on) 1 else 0)
                            prefs?.edit()?.putBoolean("uiStretch", on)?.apply()
                        },
                        onSelReset = { nativeSelReset() },
                        onFrameOp = { op, arg ->
                            // 加帧/复制帧: 先按本机内存与画布尺寸检查上限
                            if ((op == 0 || op == 1) && !checkAddFrame()) { }
                            else nativeFrameOp(op, arg)
                        },
                        onOnion = { on ->
                            nativeSetOnion(if (on) 1 else 0)
                            prefs?.edit()?.putBoolean("onion", on)?.apply()
                        },
                        tabs = tabsState.value.map { it.id to it.name }, activeTab = activeTabState.intValue,
                        tabSize = { id -> tabSizesState[id] },
                        onTabSelect = { id -> selectTab(id) },
                        onTabAdd = { w, h -> addTab(w, h) },
                        onTabRename = { id, name -> renameTab(id, name) },
                        tabThumb = { id -> tabThumb(id) },
                        palettePresets = palettePresetsState.value,
                        onPresetSave = { name -> savePalettePreset(name) },
                        onPresetApply = { i -> applyPalettePreset(i) },
                        onPresetDelete = { i -> deletePalettePreset(i) },
                        onPresetImport = { presetImportLauncher?.launch(arrayOf("text/plain", "application/octet-stream")) },
                        onPresetExport = { presetExportLauncher?.launch("endpix-palettes.txt") },
                        onTabClose = { id -> closeTab(id) },
                        layerMeta = layerMetaState.value,
                        onRenameLayer = { idx, n -> nativeSetLayerName(idx, n) },
                        onLayerOpacity = { idx, p -> nativeSetLayerOpacity(idx, p) },
                        projectFolder = projectFolderLabelState.value,
                        onPickProjectFolder = { pickProjectFolder() },
                        onClearProjectFolder = { clearProjectFolder() },
                        autoSaveMin = autoSaveMin,
                        autoSaveRemain = autoSaveRemainState.intValue,
                        autoSaveSaving = autoSaveSavingState.value,
                        onAutoSaveMin = { m -> setAutoSaveMin(m) },
                        onClearCanvas = { nativeNewDocument(0, 0) },
                        onResizeCanvas = { w, h, ax, ay ->
                            // 当前帧×图层在新尺寸下会超本机预算时: 立即取消调整并弹窗
                            if (resizeAllowed(w, h)) {
                                nativeResizeCanvas(w, h, ax, ay)
                                noteTabSize(activeTabState.intValue, w, h)
                            }
                        },
                        onSaveNow = {
                            saveActiveTab()
                            android.widget.Toast.makeText(this@MainActivity, "已保存当前标签", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        versionName = versionName,
                        recordOn = recordOnState.value,
                        recordedFrames = recordedFramesState.intValue,
                        timelineOpen = timelineOpenState.value,
                        onTimelineOpen = { open ->
                            timelineOpenState.value = open
                            prefs?.edit()?.putBoolean("uiTimelineOpen", open)?.apply()
                            if (open) {
                                timelinePrevView = try { nativeGetView() } catch (_: Throwable) { null }
                                timelinePrevView?.let { pv ->
                                    if (pv.size >= 3 && pv[0] > 0f) {
                                        prefs?.edit()?.putFloat("tlPrevZ", pv[0])
                                            ?.putFloat("tlPrevPX", pv[1])
                                            ?.putFloat("tlPrevPY", pv[2])?.apply()
                                    }
                                }
                                fitCanvasAboveTimeline()
                            } else {
                                var pv = timelinePrevView
                                timelinePrevView = null
                                // 进程被杀后"时间轴开着"的状态会被恢复, 但打开前视角字段没了:
                                // 从落盘的上次打开前视角恢复, 没有就退回整画布适应
                                if (pv == null || pv.size < 3 || pv[0] <= 0f) {
                                    val z = prefs?.getFloat("tlPrevZ", 0f) ?: 0f
                                    if (z > 0f) pv = floatArrayOf(
                                        z,
                                        prefs?.getFloat("tlPrevPX", 0f) ?: 0f,
                                        prefs?.getFloat("tlPrevPY", 0f) ?: 0f
                                    )
                                }
                                if (pv != null && pv.size >= 3 && pv[0] > 0f) {
                                    nativeAnimateViewState(pv[0], pv[1], pv[2])
                                } else {
                                    nativeZoomFit()
                                }
                            }
                        },
                        playBrush = playBrushState.value,
                        onPlayBrush = { on ->
                            playBrushState.value = on
                            nativeSetPlayBrush(if (on) 1 else 0)
                            prefs?.edit()?.putBoolean("uiPlayBrush", on)?.apply()
                        },
                        selAllFrames = selAllFramesState.value,
                        onSelAllFrames = { on ->
                            selAllFramesState.value = on
                            prefs?.edit()?.putBoolean("uiSelAllFrames", on)?.apply()
                        },
                        onLoopRange = { a, b -> nativeSetLoopRange(a, b) },
                        onReorderFrame = { a, b -> nativeReorderFrame(a, b) },
                        onSwapFrames = { a, b -> nativeSwapFrames(a, b) },
                        onReorderLayer = { a, b -> nativeReorderLayer(a, b) },
                        selModeInit = selModeState.intValue,
                        onSelMode = { m ->
                            selModeState.intValue = m.coerceIn(0, 2)
                            prefs?.edit()?.putInt("uiSelMode", selModeState.intValue)?.apply()
                        },
                        minimapOn = minimapOnState.value,
                        thumbRev = thumbRevState.intValue,
                        onMinimapOn = { on ->
                            minimapOnState.value = on
                            nativeSetMinimapActive(if (on) 1 else 0)
                            prefs?.edit()?.putBoolean("uiMinimapOn", on)?.apply()
                        },
                        minimapScale = minimapScaleState.floatValue,
                        onMinimapScale = { v ->
                            minimapScaleState.floatValue = v.coerceIn(0.8f, 1.8f)
                            prefs?.edit()?.putFloat("uiMinimapScale", minimapScaleState.floatValue)?.apply()
                        },
                        minimapX = minimapXState.floatValue,
                        minimapY = minimapYState.floatValue,
                        onMinimapPos = { x, y ->
                            minimapXState.floatValue = x
                            minimapYState.floatValue = y
                            prefs?.edit()?.putFloat("uiMinimapX", x)?.putFloat("uiMinimapY", y)?.apply()
                        },
                        onMinimapNavigate = { cx, cy, anim -> minimapNavigateToCanvas(cx, cy, anim) },
                        onMinimapBounds = { r ->
                            minimapBoundsPx = android.graphics.RectF(r.left, r.top, r.right, r.bottom)
                        },
                        minimapViewport = { out -> nativeMinimapViewport(out) },
                        minimapBg = minimapBgState.intValue,
                        onMinimapBg = { m ->
                            minimapBgState.intValue = if (m in 1..3) m else 3
                            prefs?.edit()?.putInt("uiMinimapBg", minimapBgState.intValue)?.apply()
                        },
                        refOn = refOnState.value,
                        onRefOn = { on ->
                            refOnState.value = on
                            prefs?.edit()?.putBoolean("uiRefOn", on)?.apply()
                        },
                        refScale = refScaleState.floatValue,
                        onRefScale = { v ->
                            refScaleState.floatValue = v.coerceIn(0.8f, 1.8f)
                            prefs?.edit()?.putFloat("uiRefScale", refScaleState.floatValue)?.apply()
                        },
                        refX = refXState.floatValue,
                        refY = refYState.floatValue,
                        onRefPos = { x, y ->
                            refXState.floatValue = x
                            refYState.floatValue = y
                            prefs?.edit()?.putFloat("uiRefX", x)?.putFloat("uiRefY", y)?.apply()
                        },
                        refBitmap = refBitmapState.value,
                        onPickRefImage = { refImageLauncher?.launch(arrayOf("image/*")) },
                        onRefBounds = { r ->
                            refBoundsPx = android.graphics.RectF(r.left, r.top, r.right, r.bottom)
                        },
                        onRecord = { on ->
                            recordOnState.value = on
                            val d = recordDir()
                            d.mkdirs()
                            if (on) {
                                // 新录制会话: 只清当前标签自己的帧和元数据
                                d.listFiles()?.forEach { it.delete() }
                                recordedFramesState.intValue = 0
                                recordTabs[activeTabState.intValue] = true
                            } else {
                                recordedFramesState.intValue = countRecordedFrames(d)
                                recordTabs[activeTabState.intValue] = false
                            }
                            nativeSetRecord(if (on) 1 else 0, d.absolutePath)
                        },
                        frameThumb = { f, l -> nativeFrameThumb(f, l) },
                        minimapThumb = { nativeMinimapThumb() ?: IntArray(0) },
                        compositeThumb = { nativeCompositeThumb() ?: IntArray(0) },
                        resizeThumb = { nativeResizeThumb() ?: IntArray(0) },
                        exportThumb = { nativeExportThumb() ?: IntArray(0) },
                        noiseMix = noiseMixState.value,
                        onNoiseMix = { m ->
                            noiseMixState.value = m
                            prefs?.edit()?.apply {
                                putBoolean("noiseOn", m.on)
                                putFloat("noiseMaster", m.master)
                                putFloat("noiseRain", m.rain)
                                putFloat("noiseWind", m.wind)
                                putFloat("noiseWaves", m.waves)
                                putFloat("noiseFire", m.fire)
                                putFloat("noiseColorLevel", m.colorLevel)
                                putString("noiseBands", m.bands.joinToString(",") { it.toString() })
                                putInt("noiseIeq", m.ieq)
                                putInt("noiseWidth", m.width)
                                putInt("noiseSpeed", m.speed)
                                putInt("noiseScene", m.scene)
                                putBoolean("noiseSpeechBlock", m.speechBlock)
                                putBoolean("noiseAnimOn", m.animOn)
                                putInt("noiseAnimMode", m.animMode)
                                putInt("noiseAnimCount", m.animCount)
                                putInt("noiseAnimSpeed", m.animSpeed)
                                putFloat("noiseAnimLow", m.animLow)
                                putFloat("noiseAnimHigh", m.animHigh)
                                putInt("noiseBinaural", m.binaural)
                                putInt("noiseBeatHz", m.beatHz)
                                putFloat("noiseBinLevel", m.binauralLevel)
                                putInt("noiseSleepMin", m.sleepMin)
                                putInt("noiseFx", m.fx)
                                putFloat("noiseFxLevel", m.fxLevel)
                                putInt("noiseTone", m.tone)
                                putFloat("noiseToneLevel", m.toneLevel)
                                putInt("noiseSolfeggio", m.solfeggioHz)
                            }?.apply()
                            com.endpix.nativeapp.ui.NatureNoise.apply(m)
                        },
                        onPickTemplate = ::loadTemplate,
                        templateRev = templateRevState.intValue,
                        onImportTemplate = ::launchTemplateImport,
                        onExportTemplate = ::launchTemplateExport,
                        onDeleteTemplate = ::deleteTemplate,
                        onCopySel = { nativeDuplicateSel() },
                        onDeleteSel = { nativeDeleteSel() },
                        onConfirmSel = { nativeConfirmSel() },
                        onCancelSel = { nativeCancelSel() },
                        onRotateSel = { q -> nativeRotateSel(q) },
                        onFlipSel = { h -> nativeFlipSel(if (h) 1 else 0) },
                        onImportImage = { importLauncher?.launch(arrayOf("image/*")) },
                        onExportPng = { scale ->
                            pendingPngScale = scale
                            when (com.endpix.nativeapp.ui.ExportHub.mode) {
                                2 -> exportZipLauncher?.launch("endpix_frames.zip")
                                3 -> exportGifLauncher?.launch("endpix.gif")
                                1 -> exportPngLauncher?.launch("endpix_spritesheet.png")
                                4 -> exportPngLauncher?.launch("endpix_overlay.png")
                                else -> exportPngLauncher?.launch("endpix.png")
                            }
                        },
                        onExportWebp = { scale -> startWebpExport(scale) },
                        onExportProject = { pickProjectExport() },
                        onExportPsd = { exportPsdLauncher?.launch("endpix_export.psd") },
                        onExportReplay = { doExportReplay() },
                        onLoadProject = { pickLoadProject() },
                        onLoadFile = { pickLoadFile() },
                        onLevelsHistogram = { ch -> nativeLevelsHistogram(ch) },
                        onPreviewLevels = { luts -> nativeSetLevelsPreview(luts != null, luts) },
                        onApplyLevelsAll = { luts -> nativeApplyLevelsAll(luts) },
                        watermark = watermarkState.value,
                        onWatermark = { s -> saveWatermark(s) },
                        sigIcon = sigIconBitmapState.value,
                        onPickSigIcon = { sigIconLauncher?.launch(arrayOf("image/*")) },
                        onClearSigIcon = { clearSigIcon() },
                        canvasStyle = canvasStyleState.value,
                        onCanvasStyle = { s -> saveCanvasStyle(s) },
                        onPreset = { which ->
                            val pal = if (which == 0) intArrayOf(
                                0xFF000000.toInt(), 0xFF1D2B53.toInt(), 0xFF7E2553.toInt(),
                                0xFF008751.toInt(), 0xFFAB5236.toInt(), 0xFF5F574F.toInt()
                            ) else intArrayOf(
                                0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(),
                                0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFF00.toInt()
                            )
                            val full = sidePaletteState.value.copyOf()
                            for (i in 0 until 6) full[i] = pal[i]
                            sidePaletteState.value = full
                            SidePalettePrefs.count.intValue = 6
                            persistSidePalette()
                        },
                        onShapeType = { t ->
                            shapeTypeState.intValue = t
                            nativeSetShape(t, if (shapeFillState.value) 1 else 0)
                        },
                        onShapeFill = { f ->
                            shapeFillState.value = f
                            nativeSetShape(shapeTypeState.intValue, if (f) 1 else 0)
                        },
                        onCancelPick = {
                            pickingState.value = false
                            nativeSetPickMode(0)
                            MixPickHub.reset()
                        },
                        onColor = { c ->
                            val a = (c ushr 24) and 0xFF
                            if (a in 0..254) {
                                // 调色板里的半透明色: 连同透明度一起应用(当前色也变成半透明)
                                brushAlphaState.intValue = a
                                brushColorState.intValue = (c and 0x00FFFFFF) or (a shl 24)
                            } else {
                                // 不透明调色板色: 只换 RGB, 保留当前画笔透明度
                                brushColorState.intValue = (c and 0x00FFFFFF) or
                                    (brushAlphaState.intValue shl 24)
                            }
                            applyBrush()
                        },
                        onHsvColor = { c ->
                            // HSV: 颜色和 A 滑块一起生效
                            brushColorState.intValue = c
                            brushAlphaState.intValue = c ushr 24
                            applyBrush()
                        },
                        onLayerOp = { op, arg ->
                            if (op == 0 && !checkAddLayer()) { }
                            else nativeLayerOp(op, arg)
                        },
                        // 旧项目 PixelCanvasManager.ZoomIn: currentZoom *= 2(相对适配大小), 保持屏幕中心点不动
                        onZoomIn = {
                            nativeZoom(2.0f, resources.displayMetrics.widthPixels * 0.5f,
                                resources.displayMetrics.heightPixels * 0.5f)
                        },
                        onCrop = { nativeCrop() },
                        onUndo = { nativeUndo() },
                        onRedo = { nativeRedo() },
                        panMode = panModeState.value,
                        onMove = {
                            panMode = !panMode
                            panModeState.value = panMode
                        },
                        sidePalette = sidePaletteState.value,
                        picking = pickingState.value,
                        onPickRequested = {
                            MixPickHub.reset()
                            pickingState.value = true
                            nativeSetPickMode(1)
                        },
                        overlayChk = chkOnState.value,
                        overlayGrid = gridOnState.value,
                        onOverlayChk = {
                            chkOnState.value = it
                            nativeSetOverlay(if (it) 1 else 0, if (gridOnState.value) 1 else 0)
                        },
                        onOverlayGrid = {
                            gridOnState.value = it
                            nativeSetOverlay(if (chkOnState.value) 1 else 0, if (it) 1 else 0)
                        },
                        // 旧项目 PixelCanvasManager.ZoomOut: currentZoom *= 0.5
                        onZoomOut = {
                            nativeZoom(0.5f, resources.displayMetrics.widthPixels * 0.5f,
                                resources.displayMetrics.heightPixels * 0.5f)
                        },
                        uiScalePct = uiScalePctState.intValue,
                        pngCompress = pngCompressState.intValue,
                        toolYOffset = toolYOffsetState.intValue,
                        sfxOn = sfxOnState.value,
                        onSfxOn = { on ->
                            sfxOnState.value = on
                            prefs?.edit()?.putBoolean("sfxOn", on)?.apply()
                        },
                        onToolYOffset = { v ->
                            toolYOffsetState.intValue = v.coerceIn(-50, 50)
                            prefs?.edit()?.putInt("toolYOffsetPct", toolYOffsetState.intValue)
                                ?.remove("toolYOffset")?.apply()
                        },
                        onPngCompress = { v ->
                            pngCompressState.intValue = v.coerceIn(0, 2)
                            prefs?.edit()?.putInt("pngCompress", pngCompressState.intValue)?.apply()
                            nativeSetPngCompress(pngCompressState.intValue)
                        },
                        onUiScalePct = { v ->
                            uiScalePctState.intValue = v.coerceIn(80, 125)
                            prefs?.edit()?.putInt("uiScalePct", uiScalePctState.intValue)?.apply()
                        },
                    )
                    }
                }
                }
            }
        }
        root.addView(composeView, android.widget.FrameLayout.LayoutParams(-1, -1))
        setContentView(root)


        surfaceView.setOnTouchListener { _, ev ->
            if (com.endpix.nativeapp.ui.BrowserHub.visible) return@setOnTouchListener true  // 浏览器标签独占触摸
            if (com.endpix.nativeapp.ui.ReplayHub.visible) return@setOnTouchListener true  // 回放层自行处理缩放/平移
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) surfaceGestureActive = true
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL)
                surfaceGestureActive = false
            // 横屏三指下压通常是系统截屏手势: 先收起菜单/☰, 让截图干净
            if (landscapeModeState.value && ev.pointerCount >= 3 &&
                (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN ||
                    ev.actionMasked == MotionEvent.ACTION_DOWN)) {
                com.endpix.nativeapp.ui.LandscapeRefHub.hideChromeTemporarily(1800L)
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    transforming = false
                    suppressSingleUntilUp = false
                    suppressPanUntilUp = false
                    if (panMode) { prevPanX = ev.x; prevPanY = ev.y }
                    else if (!landscapeModeState.value) nativeTouch(0, ev.x, ev.y, ev.eventTime)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (ev.pointerCount >= 2) {
                        beginPinch(ev)
                        // 单指移动模式下也进入双指手势: 抬起一指后不许再用旧prevPan驱动平移
                        suppressPanUntilUp = true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (transforming && ev.pointerCount >= 2) {
                        updatePinch(ev)
                    } else if (panMode) {
                        if (!suppressPanUntilUp) nativePan(ev.x - prevPanX, ev.y - prevPanY)
                        prevPanX = ev.x; prevPanY = ev.y
                    } else if (!transforming && !suppressSingleUntilUp && !landscapeModeState.value) {
                        for (h in 0 until ev.historySize) {
                            nativeTouch(1, ev.getHistoricalX(h), ev.getHistoricalY(h), ev.getHistoricalEventTime(h))
                        }
                        nativeTouch(1, ev.x, ev.y, ev.eventTime)
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (transforming) {
                        nativeEndZoom()
                        transforming = false
                        suppressSingleUntilUp = true
                    }
                    // 双指结束后: 剩余手指位置作为新的平移基准, 且本段手势不再平移
                    suppressPanUntilUp = true
                    if (ev.pointerCount - 1 == 1) {
                        val keep = if (ev.actionIndex == 0) 1 else 0
                        prevFx = ev.getX(keep); prevFy = ev.getY(keep); prevSpan = 0f
                        prevPanX = ev.getX(keep); prevPanY = ev.getY(keep)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (transforming) nativeEndZoom()
                    if (!transforming && !panMode && !suppressSingleUntilUp) nativeTouch(2, ev.x, ev.y, ev.eventTime)
                    suppressSingleUntilUp = false
                    suppressPanUntilUp = false
                    transforming = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (transforming) nativeEndZoom()
                    if (!transforming && !panMode && !suppressSingleUntilUp) nativeTouch(3, ev.x, ev.y, ev.eventTime)
                    suppressSingleUntilUp = false
                    suppressPanUntilUp = false
                    transforming = false
                }
            }
            true
        }
        hideSystemBars()
        requestHighRefresh()
    }

    private fun beginPinch(ev: MotionEvent) {
        // 必须先回滚再发 up: nativeTouch(3) 会先 endStrokeRecord 把笔画提交进撤销栈,
        // 之后再回滚时 canvas 已不在 recording 状态, 就撤销不掉了。
        // 只在时限内回滚: 画了一段时间后再落第二指不应删掉首指已画内容。
        val limit = pinchUndoMsState.intValue
        val within = limit > 0 && android.os.SystemClock.uptimeMillis() - gestureDownMs <= limit
        if (within) nativeCancelFirstStroke()
        nativeCancelSelectionGesture()
        nativeTouch(3, ev.x, ev.y, ev.eventTime)
        transforming = true
        prevSpan = spanOf(ev)
        gestureStartSpan = prevSpan
        smoothSpan = prevSpan
        val f = focusOf(ev); prevFx = f.first; prevFy = f.second
        smoothFx = f.first; smoothFy = f.second
        nativeBeginZoom(f.first, f.second)
    }

    private fun updatePinch(ev: MotionEvent) {
        val f = focusOf(ev)
        val s = spanOf(ev)
        // 旧 ProcessTwoFingerFrame303: 指距/中点直接用原始值, 只过滤单帧尖峰(>60%跳变)。
        // 原低通0.35+2%死区会让慢速捏合"卡住不动→越过阈值跳一下", 手感差, 已移除。
        if (prevSpan > 1f && kotlin.math.abs(s - prevSpan) > prevSpan * 0.6f) {
            prevSpan = s; prevFx = f.first; prevFy = f.second
            return
        }
        val ratio = if (gestureStartSpan > 1f) s / gestureStartSpan else 1f
        nativeZoomGesture(ratio, f.first, f.second)
        prevFx = f.first; prevFy = f.second; prevSpan = s
    }

    // 小地图/参考图面板在窗口内的实际矩形(Compose 布局上报, px)
    private var refBoundsPx: android.graphics.RectF? = null

    // 双指手势如果从工具栏/按钮上开始, Compose 会抢走触摸目标; 这里直接截获并按画布缩放处理,
    // 同时取消 UI 手势, 避免双指缩放时误触底部工具栏。
    // 旋转手柄命中区: 只要手指落在蓝点附近, 就直接交给引擎, 不让任何 Compose 按钮抢走
    private fun nearRotateHandle(x: Float, y: Float): Boolean {
        if (landscapeModeState.value || timelineOpenState.value) return false
        if (!stretchModeState.value) return false
        val f = selFlagState.intValue
        if ((f and 2) == 0) return false          // 必须处于浮起态
        val v = selRectState.value
        if (v.size < 15) return false
        val hx = v[13].toFloat(); val hy = v[14].toFloat()
        if (hx == 0f && hy == 0f) return false
        val dx = x - hx; val dy = y - hy
        return dx * dx + dy * dy <= 42f * 42f     // 引擎旋转柄命中半径 48px, 这里取内圈
    }

    // 透视四角可能正好压在工具栏/色板/底栏上: 手柄附近优先给引擎, 否则按钮会抢走拖拽
    private fun nearPerspectiveHandle(x: Float, y: Float): Boolean {
        if (landscapeModeState.value || timelineOpenState.value) return false
        if (!SelectionPerspectiveHub.on) return false
        val f = selFlagState.intValue
        if ((f and 3) != 3) return false          // 必须有选区且已浮起
        val v = selRectState.value
        if (v.size < 15) return false
        for (i in 0..3) {
            val dx = x - v[5 + i * 2]
            val dy = y - v[6 + i * 2]
            if (dx * dx + dy * dy <= 42f * 42f) return true
        }
        return false
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 浏览器标签: 触摸全部交给 Compose/WebView, 不送画布引擎
        if (com.endpix.nativeapp.ui.BrowserHub.visible) {
            composeViewRef?.dispatchTouchEvent(ev)
            return true
        }
        // 旋转手柄优先于所有按钮/小地图/参考图: 从按到抬整段手势直接给画布
        if (forceRotateHandle) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> nativeTouch(0, ev.x, ev.y, ev.eventTime)
                MotionEvent.ACTION_MOVE -> {
                    if (ev.pointerCount <= 1) {
                        for (h in 0 until ev.historySize)
                            nativeTouch(1, ev.getHistoricalX(h), ev.getHistoricalY(h), ev.getHistoricalEventTime(h))
                        nativeTouch(1, ev.x, ev.y, ev.eventTime)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    nativeTouch(2, ev.x, ev.y, ev.eventTime)
                    forceRotateHandle = false
                    surfaceGestureActive = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    nativeTouch(3, ev.x, ev.y, ev.eventTime)
                    forceRotateHandle = false
                    surfaceGestureActive = false
                }
            }
            return true
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN &&
            !com.endpix.nativeapp.ui.ReplayHub.visible &&
            !com.endpix.nativeapp.ui.BrowserHub.visible &&
            (nearRotateHandle(ev.x, ev.y) || nearPerspectiveHandle(ev.x, ev.y))
        ) {
            forceRotateHandle = true
            forcedMultiGesture = false
            uiTouchRouting = false
            surfaceGestureActive = true
            nativeTouch(0, ev.x, ev.y, ev.eventTime)
            return true
        }
        // 从小地图/参考图面板内开始的触摸: 整段手势只转发给 Compose, 画布完全不参与
        // (否则画布会同时收到双指, 抢掉参考图内部的缩放)
        if (uiTouchRouting) {
            composeViewRef?.dispatchTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                ev.actionMasked == MotionEvent.ACTION_CANCEL
            ) uiTouchRouting = false
            return true
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            forcedMultiGesture = false
            gestureDownMs = android.os.SystemClock.uptimeMillis()
            val x = ev.x
            val y = ev.y
            val inPanel = (refOnState.value && refBoundsPx?.contains(x, y) == true) ||
                (minimapOnState.value && minimapBoundsPx?.contains(x, y) == true)
            if (inPanel) {
                uiTouchRouting = true
                composeViewRef?.dispatchTouchEvent(ev)
                return true
            }
        }
        if (!forcedMultiGesture && ev.pointerCount >= 2 && !surfaceGestureActive) {
            forcedMultiGesture = true
            val cancel = MotionEvent.obtain(ev)
            cancel.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            beginPinch(ev)
            return true
        }
        if (forcedMultiGesture) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> if (ev.pointerCount >= 2) beginPinch(ev)
                MotionEvent.ACTION_MOVE -> if (ev.pointerCount >= 2 && transforming) updatePinch(ev)
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (transforming) nativeEndZoom()
                    transforming = false
                    if (ev.actionMasked != MotionEvent.ACTION_POINTER_UP) forcedMultiGesture = false
                }
            }
            return true
        }
        if ((ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL)
            && sfxOnState.value && !surfaceGestureActive && ev.pointerCount <= 1) {
            try {
                if (uiSoundPool == null) initUiSound()
                val sp = uiSoundPool
                if (sp != null && uiTapSound != 0) sp.play(uiTapSound, 0.5f, 0.5f, 1, 0, 1f)
            } catch (_: Throwable) { }
        }
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            // 笔画/手势结束: 350ms 后立刻落盘一次, 缩小"抬手即被杀"的丢失窗口
            handler.removeCallbacks(durableAfterInput)
            handler.postDelayed(durableAfterInput, 350L)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun parseLayerMeta(raw: String): List<Pair<String, Int>> {
        if (raw.isBlank()) return emptyList()
        return raw.split('').mapNotNull { ent ->
            val p = ent.lastIndexOf('')
            if (p < 0) return@mapNotNull null
            ent.substring(0, p) to ((ent.substring(p + 1).toIntOrNull() ?: 1000).coerceIn(0, 1000))
        }
    }

    private fun parseStats(s: String) {
        fun grab(pattern: String): Int? = Regex(pattern).find(s)?.groupValues?.get(1)?.toIntOrNull()
        grab("""tool=(\d+)""")?.let { toolState.intValue = it }
        grab("""polyv=(\d+)""")?.let { polyVertsState.intValue = it }
        grab("""measf=(\d+)""")?.let { measureFixedState.value = it != 0 }
        grab("""meassp=(\d+)""")?.let { measurePendingState.value = it != 0 }
        grab("""L(\d+) A""")?.let { layersState.intValue = it }
        Regex("""L\d+ A(\d+)""").find(s)?.groupValues?.get(1)?.toIntOrNull()?.let { activeState.intValue = it }
        val pseq = grab("""pseq=(\d+)""") ?: 0
        if (pseq != lastPickSeq) {
            lastPickSeq = pseq
            val mixN = pseq - MixPickHub.startSeq.intValue
            val pick = Regex("""pick=([0-9A-Fa-f]{8})""").find(s)?.groupValues?.get(1)
                ?.toLongOrNull(16)?.toInt()
            val pickPrev = Regex("""pickprev=([0-9A-Fa-f]{8})""").find(s)?.groupValues?.get(1)
                ?.toLongOrNull(16)?.toInt()
            if (pick != null) {
                val c = toNativeColor(pick)   // 原生字节序 ↔ ARGB 对称交换
                when (MixPickHub.stage.intValue) {
                    0 -> {
                        // 两色混合第一色: 只记录, 不改画笔; 继续等待第二色。
                        // 若两次吸色都发生在 2Hz stats 轮询间隔内, 用 pickprev 补上第一色。
                        if (mixN >= 2 && pickPrev != null) {
                            applyMixColor(toNativeColor(pickPrev), c)
                        } else {
                            MixPickHub.colorA.intValue = c
                            MixPickHub.stage.intValue = 1
                            pickingState.value = true
                        }
                    }
                    1 -> applyMixColor(MixPickHub.colorA.intValue, c)
                    else -> {
                        brushColorState.intValue = c
                        nativeSetBrush(brushPxState.floatValue, toNativeColor(c))
                        pickingState.value = false
                    }
                }
            } else if (MixPickHub.stage.intValue < 0) {
                pickingState.value = false
            }
        }
    }

    // 两色混合第二色落下: HSV 中间值合成并应用
    private fun applyMixColor(a: Int, b: Int) {
        val mid = mixMidpointColor(a, b)
        brushColorState.intValue = mid
        brushAlphaState.intValue = (mid ushr 24) and 0xFF
        nativeSetBrush(brushPxState.floatValue, toNativeColor(mid))
        MixPickHub.stage.intValue = -1
        pickingState.value = false
        android.widget.Toast.makeText(
            this, String.format("混合色 #%06X 已应用", mid and 0xFFFFFF),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun requestHighRefresh() {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            val d = wm.defaultDisplay
            val best = d.supportedModes.maxByOrNull { it.refreshRate } ?: return
            val lp = window.attributes
            lp.preferredDisplayModeId = best.modeId
            window.attributes = lp
        } catch (_: Throwable) { }
    }

    private fun hideSystemBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
    }

    // 音量键还给系统调音量(旧原型曾把 音量+/− 接成切工具/裁剪, 已移除)
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        // 横屏截屏常见组合=音量下+电源: 先收起菜单/☰, 尽量让截图干净
        if (landscapeModeState.value &&
            (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP)) {
            com.endpix.nativeapp.ui.LandscapeRefHub.hideChromeTemporarily(2200L)
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        try { if (started) saveActiveTab() } catch (_: Throwable) { }   // 退场前最后落盘
        try { unregisterReceiver(batteryRc) } catch (_: Throwable) { }
        try { unregisterReceiver(screenOffRc) } catch (_: Throwable) { }
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            try {
                (screenCaptureCallback as? android.app.Activity.ScreenCaptureCallback)?.let {
                    unregisterScreenCaptureCallback(it)
                }
            } catch (_: Throwable) { }
        }
        com.endpix.nativeapp.ui.BrowserHub.destroy()
        com.endpix.nativeapp.ui.ProxyHub.stop(this)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.endpix.nativeapp.ui.BrowserHub.onTrimMemory(level)
        // 后台内存吃紧随时可能被杀: 先落盘
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            try {
                if (started) saveActiveTab()
                saveTabPrefs()
            } catch (_: Throwable) { }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 快速切到别的应用时, onPause 可能晚到; 失焦先存一次
        if (!hasFocus && started) {
            try { saveActiveTab(); saveTabPrefs() } catch (_: Throwable) { }
            lastDurableSaveMs = android.os.SystemClock.elapsedRealtime()
        }
    }

    // ---- SurfaceHolder.Callback ----
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 横竖屏: 保留当前缩放和视口中心(不再强制 fit)
        fitViewOnNextSurface = false
        try {
            val v = nativeGetView()
            val sw = surfaceView.width
            val sh = surfaceView.height
            if (v != null && v.size >= 3 && v[0] > 0f && sw > 0 && sh > 0) {
                savedViewZoom = v[0]
                savedViewCenterX = v[1] + sw * 0.5f / v[0]
                savedViewCenterY = v[2] + sh * 0.5f / v[0]
                savedViewPanX = v[1]
                savedViewPanY = v[2]
                savedViewTabId = activeTabState.intValue
                hasSavedView = true
                preserveViewOnRotate = true
            }
        } catch (_: Throwable) { }
        val land = !portraitLockState.value &&
            newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        // HSV 打开时旋转到横屏: 保持 MainScreen, 用紧凑横屏布局显示调色盘
        if (com.endpix.nativeapp.ui.HsvHub.open) {
            landscapeModeState.value = false
            return
        }
        // 回放中允许横屏播放, 但不启用横屏模式(不隐藏 UI/不停笔/不截横屏图)
        if (com.endpix.nativeapp.ui.ReplayHub.visible) {
            landscapeModeState.value = false
            return
        }
        if (land != landscapeModeState.value) {
            if (land && started) {
                // 旧项目横屏: 隐藏 UI、停止绘制。进横屏前把当前画布(含浮起选区/变换)存进快照:
                // 不落定选区——否则转回竖屏框选状态会被吃掉; surface 若被重建也能从快照原样恢复。
                saveActiveTab()
            }
            landscapeModeState.value = land
            com.endpix.nativeapp.ui.LandscapeRefHub.resetChrome()
        }
    }

    private fun captureLandscapeScreenshot() {
        try {
            val dm = resources.displayMetrics
            val w = dm.widthPixels
            val h = dm.heightPixels
            if (w <= 0 || h <= 0) return
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val sv = surfaceView ?: return
            android.view.PixelCopy.request(sv, bmp, { result ->
                if (result == android.view.PixelCopy.SUCCESS) {
                    // 截图必须同时带水印+签名: 不看横屏显示开关, 一律烘焙
                    val wm = watermarkState.value.copy(wmOn = true, sigOn = true)
                    bakeWatermark(bmp, wm, 1, sigIconBitmapState.value)
                    Thread {
                        try {
                            val name = "endpix_landscape_" + System.currentTimeMillis() + ".png"
                            var where = ""
                            if (android.os.Build.VERSION.SDK_INT >= 29) {
                                val values = android.content.ContentValues().apply {
                                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                                        android.os.Environment.DIRECTORY_PICTURES + "/endpix")
                                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                                }
                                val uri = contentResolver.insert(
                                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                                if (uri != null) {
                                    contentResolver.openOutputStream(uri)?.use {
                                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                                    }
                                    values.clear()
                                    values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                                    contentResolver.update(uri, values, null, null)
                                    where = "相册/Pictures/endpix/$name"
                                }
                            }
                            if (where.isEmpty()) {
                                val dir = java.io.File(getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "endpix")
                                dir.mkdirs()
                                val f = java.io.File(dir, name)
                                java.io.FileOutputStream(f).use {
                                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                                }
                                where = f.absolutePath
                            }
                            runOnUiThread {
                                android.widget.Toast.makeText(this, "截图已保存: $where",
                                    android.widget.Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Throwable) {
                            runOnUiThread {
                                android.widget.Toast.makeText(this, "截图保存失败", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }.start()
                } else {
                    android.widget.Toast.makeText(this, "截图失败($result)", android.widget.Toast.LENGTH_SHORT).show()
                }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (_: Throwable) {
            android.widget.Toast.makeText(this, "截图失败", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val r = holder.surfaceFrame
        started = nativeStart(holder.surface, r.width(), r.height(), canvasW, canvasH, canvasLayers)
        if (started) {
            applyCanvasStyle()
            applyBrush()   // 新引擎: 恢复笔刷大小/颜色/预设
            try { nativeSetGrayView(if (com.endpix.nativeapp.ui.GrayViewPrefs.on) 1 else 0) } catch (_: Throwable) { }
            try { nativeSetSelHoldMs(com.endpix.nativeapp.ui.SelectionPrefs.holdMs) } catch (_: Throwable) { }
            nativeLoadSnapshot(activeTabFile().absolutePath)   // 新引擎: 恢复当前标签的画布
            nativeSetTabHistory(activeTabState.intValue)       // 撤销栈按标签归位
            noteActiveTabSizeLater(600)                        // 冷启动活动标签的尺寸(快照解析失败也能显示)
            if (preserveViewOnRotate && hasSavedView && savedViewZoom > 0f &&
                savedViewTabId == activeTabState.intValue
            ) {
                // 横竖屏重建 Surface: 保持缩放, 视口中心落在同一画布点
                val z = savedViewZoom
                val nw = r.width().toFloat()
                val nh = r.height().toFloat()
                nativeSetView(z, savedViewCenterX - nw * 0.5f / z,
                    savedViewCenterY - nh * 0.5f / z)
                preserveViewOnRotate = false
            } else if (fitViewOnNextSurface) {
                nativeResetView()
                fitViewOnNextSurface = false
            } else {
                // 先恢复该标签自己的视角; 没有记录时沿用后台返回的全局视角, 再不行就 fit
                val tv = readTabView(activeTabState.intValue)
                if (tv != null) nativeSetView(tv[0], tv[1], tv[2])
                else if (hasSavedView && savedViewZoom > 0f && savedViewTabId == activeTabState.intValue)
                    nativeSetView(savedViewZoom, savedViewPanX, savedViewPanY)
                else nativeResetView()
            }
            flushPendingImport()               // 选图期间引擎被释放时, 这里补导入
            // 启动/回前台时如果时间轴是开着的: 刚才恢复的是标签全屏视角, 重新摆到性能栏-时间轴中间
            refitTimelineViewSoon()
            // 调试: files/import_test.png 存在则直接导入(验证引擎导入链路, 用完自动删)
            try {
                val f = java.io.File(filesDir, "import_test.png")
                if (f.exists()) {
                    val bmp = BitmapFactory.decodeFile(f.absolutePath)
                    if (bmp != null) {
                        val w = bmp.width; val h = bmp.height
                        val argb = IntArray(w * h)
                        bmp.getPixels(argb, 0, w, 0, 0, w, h)
                        for (i in argb.indices) {
                            val c = argb[i]
                            argb[i] = (c and 0xFF00FF00.toInt()) or
                                ((c and 0xFF) shl 16) or ((c ushr 16) and 0xFF)
                        }
                        nativeImportImage(w, h, argb)
                        bmp.recycle()
                    }
                    f.delete()
                }
            } catch (_: Throwable) { }
        }
        try {
            if (started && java.io.File(getExternalFilesDir(null), "stress").exists()) nativeSetStress(true)
            if (started) {
                nativeSetInsets(0, 0, 0, 0)   // 旧项目布局: 画布全屏, 按钮浮在上面
                applyEngineUiSettings()
                if (recordOnState.value) resumeNativeRecording()
            }
        } catch (_: Throwable) { }
        // 异步初始化失败(running_ 已变 false)时, 2.5 秒后自愈重建一次
        surfaceView.postDelayed({
            if (started && !nativeIsAlive()) ensureEngineStarted()
        }, 2500)
    }

    // 长时间后台/GL context 丢失后引擎线程可能已经退出: 只要 Surface 有效就重建一次
    private fun ensureEngineStarted() {
        if (started && nativeIsAlive()) return
        val h = surfaceView.holder
        if (h == null || !h.surface.isValid) return
        val r = h.surfaceFrame
        nativeStop()
        started = nativeStart(h.surface, r.width(), r.height(), canvasW, canvasH, canvasLayers)
        if (started) {
            applyCanvasStyle()
            applyBrush()   // 新引擎: 恢复笔刷大小/颜色/预设
            try { nativeSetGrayView(if (com.endpix.nativeapp.ui.GrayViewPrefs.on) 1 else 0) } catch (_: Throwable) { }
            try { nativeSetSelHoldMs(com.endpix.nativeapp.ui.SelectionPrefs.holdMs) } catch (_: Throwable) { }
            nativeLoadSnapshot(activeTabFile().absolutePath)
            nativeSetTabHistory(activeTabState.intValue)
            nativeRequestRedraw()
            flushPendingImport()
            refitTimelineViewSoon()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        nativeResize(width, height)
        nativeRequestRedraw()   // 旋转/Surface 改尺寸后立即整屏重绘, 不等下一次输入
        fitViewOnNextSurface = false   // 横竖屏若没重建 Surface, 这里消费掉标记
        preserveViewOnRotate = false   // 引擎 onSize 已按中心保留视角
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (started && !fitViewOnNextSurface) rememberActiveView()
        nativeStop()
        started = false
    }

    private var dbgLoadDone = false
    // 自动更新只在本次进程首次启动时检查/弹窗; 从后台回来不再重复提示
    private var updatePromptForeground = false

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(durableSaveRunnable)
        handler.postDelayed(durableSaveRunnable, 600)   // 前台持续自动落盘
        com.endpix.nativeapp.ui.BrowserHub.onHostResume()
        beginTabUsage(activeTabState.intValue)   // 前台使用时长从回到应用开始累计
        flushPendingImport()
        // 自动检查更新只在本次进程第一次启动时做一次; 后台返回不重复检查/弹窗
        updatePromptForeground = true
        if (!updateAutoCheckedProcess && com.endpix.nativeapp.ui.UpdatePrefs.autoCheck) {
            updateAutoCheckedProcess = true
            handler.postDelayed({
                com.endpix.nativeapp.ui.UpdateHub.check(this) { found ->
                    if (found && com.endpix.nativeapp.ui.UpdatePrefs.autoCheck &&
                        updatePromptForeground) {
                        com.endpix.nativeapp.ui.UpdatePromptHub.show(
                            com.endpix.nativeapp.ui.UpdateHub.latestTag, versionName
                        )
                    }
                }
            }, 1500)
        }
        if (!started || !nativeIsAlive()) ensureEngineStarted() else nativeRequestRedraw()
        com.endpix.nativeapp.ui.NatureNoise.resumeEngine(noiseMixState.value)
        // DEBUG 自动化: adb 拉起时用 --es dbgLoadPath <路径> 直接走"加载文件"流程(测试 PSD/ASE 用)
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        android.util.Log.i("EndpixLoad", "onResume debuggable=$debuggable done=$dbgLoadDone dbg=${intent?.getStringExtra("dbgLoadPath")}")
        if (debuggable && !dbgLoadDone) {
            dbgLoadDone = true
            intent?.getStringExtra("dbgLoadPath")?.let { path ->
                handler.postDelayed({
                    try { doLoadFile(android.net.Uri.fromFile(java.io.File(path))) } catch (_: Throwable) { }
                }, 1200)
            }
            intent?.getStringExtra("dbgImportTemplate")?.let { path ->
                handler.postDelayed({
                    try { importTemplate(android.net.Uri.fromFile(java.io.File(path))) } catch (_: Throwable) { }
                }, 1500)
            }
            intent?.getStringExtra("dbgExportPng")?.let { out ->
                handler.postDelayed({
                    Thread {
                        val r = try { nativeExportPng(out, 1) } catch (e: Throwable) { "err ${e.message}" }
                        android.util.Log.i("EndpixLoad", "dbg export png r=$r bytes=${java.io.File(out).length()}")
                    }.start()
                }, 2000)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        pauseTabUsage()   // 离开应用先结算当前标签使用时长
        com.endpix.nativeapp.ui.BrowserHub.onHostPause()
        // 更新弹窗只属于本次启动: 退到后台时收起, 回来不再弹
        updatePromptForeground = false
        com.endpix.nativeapp.ui.UpdatePromptHub.hide()
        handler.removeCallbacks(durableSaveRunnable)
        com.endpix.nativeapp.ui.NatureNoise.suspendEngine()   // 白噪音只在前台响(淡出)
        val v = currentView()
        if (v != null) {
            savedViewZoom = v[0]; savedViewPanX = v[1]; savedViewPanY = v[2]
            savedViewTabId = activeTabState.intValue
            hasSavedView = true
            writeTabView(activeTabState.intValue, v)
        }
        // 不再落定选区: 快照 v3 会把裁剪浮层/变换/选区一起存盘, 回前台继续操作
        // 未完成的形状/渐变预览不能存进快照(渐变会改图层像素), 存盘前先还原
        if (started) nativeCancelShapePreview()
        if (started) saveActiveTab()   // 离开应用立即缓存(同步等待写盘)
        lastDurableSaveMs = android.os.SystemClock.elapsedRealtime()
        recordedFramesState.intValue = countRecordedFrames()
        saveUiSessionPrefs()
    }

    override fun onStop() {
        super.onStop()
        if (started) {
            rememberActiveView()
            saveActiveTab()
        }
        saveUiSessionPrefs()
    }

    private external fun nativeStart(surface: Surface, vw: Int, vh: Int, cw: Int, ch: Int, layers: Int): Boolean
    private external fun nativeSaveSnapshot(path: String)
    private external fun nativeSaveSnapshotAsync(path: String)
    private external fun nativeSnapshotDirty(): Boolean
    private external fun nativeIsStrokeDown(): Boolean
    private external fun nativePreviewActive(): Boolean
    private external fun nativeInputIdle(ms: Int): Boolean
    private external fun nativeForceSaveSnapshot(path: String)
    private external fun nativeLevelsHistogram(channel: Int): IntArray
    private external fun nativeApplyLevels(channel: Int, black: Int, white: Int, gamma: Float, outBlack: Int, outWhite: Int)
    private external fun nativeSetLevelsPreview(on: Boolean, luts: IntArray?)
    private external fun nativeApplyLevelsAll(luts: IntArray)
    private external fun nativeLoadSnapshot(path: String)
    private external fun nativeStop()
    private external fun nativeResize(vw: Int, vh: Int)
    private external fun nativeRequestRedraw()
    private external fun nativeImportRegion(x: Int, y: Int, w: Int, h: Int, px: IntArray)
    private external fun nativeCanvasSize(): IntArray?
    private external fun nativeSnapshotDims(path: String): IntArray?
    private external fun nativeFrameCount(): Int
    private external fun nativeLayerCount(): Int
    private external fun nativeReadRegion(x: Int, y: Int, w: Int, h: Int): IntArray?
    private external fun nativeLastCutPixels(): IntArray?

    // 当前引擎里的真实画布尺寸(切换标签/新建后 canvasW/H 字段会过期, 不能直接用)
    private fun currentCanvasDims(): Pair<Int, Int> {
        try {
            val v = nativeCanvasSize()
            if (v != null && v.size >= 2 && v[0] > 0 && v[1] > 0) return v[0] to v[1]
        } catch (_: Throwable) { }
        return canvasW to canvasH
    }

    // ---------- 帧/图层动态上限(按本机内存与当前画布大小估算) ----------
    private fun memBudgetBytes(): Long {
        return try {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            (mi.totalMem / 5).coerceIn(256L shl 20, 1536L shl 20)   // 约 20% 物理内存, 256MB~1.5GB
        } catch (_: Throwable) {
            512L shl 20
        }
    }

    // 一个"帧×图层"单元按画布全尺寸 25% 的内容量估算(帧只存非空 tile, 全尺寸太保守)
    private fun cellBytes(w: Int, h: Int): Long =
        maxOf(1L, w.toLong() * h.toLong() * 4L / 4L)

    private fun frameLimitHard() = 200
    private fun layerLimitHard() = 64

    private fun showCanvasLimitDialog(title: String, msg: String) {
        try {
            android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .show()
        } catch (_: Throwable) {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun checkAddFrame(): Boolean {
        val (w, h) = currentCanvasDims()
        val fr = try { nativeFrameCount() } catch (_: Throwable) { 1 }
        val ly = try { nativeLayerCount() } catch (_: Throwable) { 1 }
        if (fr + 1 > frameLimitHard()) {
            showCanvasLimitDialog("帧数已达上限", "当前最多 ${frameLimitHard()} 帧，请先删除不用的帧。")
            return false
        }
        val need = (fr + 1).toLong() * ly.coerceAtLeast(1) * cellBytes(w, h)
        val budget = memBudgetBytes()
        if (need > budget) {
            showCanvasLimitDialog(
                "帧数已达本机上限",
                "画布 ${w}×${h}，当前 $fr 帧 × $ly 层。\n" +
                    "再加 1 帧预计需要 ${need shr 20} MB，本机可用预算 ${budget shr 20} MB。\n" +
                    "请先删除帧或图层，或缩小画布后再试。"
            )
            return false
        }
        return true
    }

    private fun checkAddLayer(): Boolean {
        val (w, h) = currentCanvasDims()
        val fr = try { nativeFrameCount() } catch (_: Throwable) { 1 }
        val ly = try { nativeLayerCount() } catch (_: Throwable) { 1 }
        if (ly + 1 > layerLimitHard()) {
            showCanvasLimitDialog("图层数已达上限", "当前最多 ${layerLimitHard()} 层，请先删除不用的图层。")
            return false
        }
        val need = fr.coerceAtLeast(1).toLong() * (ly + 1) * cellBytes(w, h)
        val budget = memBudgetBytes()
        if (need > budget) {
            showCanvasLimitDialog(
                "图层数已达本机上限",
                "画布 ${w}×${h}，当前 $fr 帧 × $ly 层。\n" +
                    "再加 1 层预计需要 ${need shr 20} MB，本机可用预算 ${budget shr 20} MB。\n" +
                    "请先删除帧或图层，或缩小画布后再试。"
            )
            return false
        }
        return true
    }

    // 左下快捷键按钮的动作表(旧项目 BtnShortcuts 短按/长按)
    private fun installShortcutHandlers() {
        com.endpix.nativeapp.ui.ShortcutHub.handlers = mapOf(
            "grid" to {
                gridOnState.value = !gridOnState.value
                nativeSetOverlay(if (chkOnState.value) 1 else 0, if (gridOnState.value) 1 else 0)
                prefs?.edit()?.putBoolean("uiGrid", gridOnState.value)?.apply()
            },
            "recenter" to { nativeResetView() },
            "fit" to { nativeZoomFit() },
            "brush" to { shortcutSetTool(0) },
            "eraser" to { shortcutSetTool(2) },
            "select" to { shortcutSetTool(1) },
            "bucket" to { shortcutSetTool(3) },
            "shape" to { shortcutSetTool(4) },
            "undo" to { nativeUndo() },
            "redo" to { nativeRedo() },
            "frame_add" to { if (checkAddFrame()) nativeFrameOp(0, 0) },
            "frame_copy" to { if (checkAddFrame()) nativeFrameOp(1, 0) },
            "layer_add" to { if (checkAddLayer()) nativeLayerOp(0, 0) },
            "save" to {
                saveActiveTab()
                android.widget.Toast.makeText(this, "已保存当前标签", android.widget.Toast.LENGTH_SHORT).show()
            },
            "minimap" to {
                val on = !minimapOnState.value
                minimapOnState.value = on
                nativeSetMinimapActive(if (on) 1 else 0)
                prefs?.edit()?.putBoolean("uiMinimapOn", on)?.apply()
            },
            "ref" to {
                val on = !refOnState.value
                refOnState.value = on
                prefs?.edit()?.putBoolean("uiRefOn", on)?.apply()
            },
            "play" to { nativeFrameOp(4, 0) },
            "zoom_in" to {
                nativeZoom(2.0f, resources.displayMetrics.widthPixels * 0.5f,
                    resources.displayMetrics.heightPixels * 0.5f)
            },
            "zoom_out" to {
                nativeZoom(0.5f, resources.displayMetrics.widthPixels * 0.5f,
                    resources.displayMetrics.heightPixels * 0.5f)
            },
        )
    }

    private fun shortcutSetTool(t: Int) {
        toolState.intValue = t
        com.endpix.nativeapp.ui.ToolHub.current = t
        nativeSetTool(t)
        nativeSetEraseMode(if (t == 3 && eraseModeState.value) 1 else 0)
        applyBrush()
        if (t != 1) {
            nativeDeselect()
            pickingState.value = false
            nativeSetPickMode(0)
            MixPickHub.reset()
        }
        panModeState.value = false
    }

    private fun resizeAllowed(newW: Int, newH: Int): Boolean {
        val fr = try { nativeFrameCount() } catch (_: Throwable) { 1 }.coerceAtLeast(1)
        val ly = try { nativeLayerCount() } catch (_: Throwable) { 1 }.coerceAtLeast(1)
        val (cw, ch) = currentCanvasDims()
        if (newW <= cw && newH <= ch) return true
        val need = fr.toLong() * ly * cellBytes(newW, newH)
        val budget = memBudgetBytes()
        if (need <= budget) return true
        showCanvasLimitDialog(
            "无法调整画布大小",
            "当前 $fr 帧 × $ly 层，调整到 ${newW}×${newH} 预计需要 ${need shr 20} MB，\n" +
                "超过本机可用预算 ${budget shr 20} MB。\n" +
                "请先减少帧或图层，或改用更小的目标尺寸。"
        )
        return false
    }
    private external fun nativeGetView(): FloatArray
    private external fun nativeSetView(zoom: Float, panX: Float, panY: Float)
    private external fun nativeResetView()
    private external fun nativeTouch(action: Int, x: Float, y: Float, timeMs: Long)
    private external fun nativeZoom(factor: Float, fx: Float, fy: Float)
    private external fun nativeCancelFirstStroke()
    private external fun nativeCancelSelectionGesture()
    private external fun nativeBeginZoom(fx: Float, fy: Float)
    private external fun nativeZoomGesture(factor: Float, fx: Float, fy: Float)
    private external fun nativeEndZoom()
    private external fun nativePan(dx: Float, dy: Float)
    private external fun nativeStats(): String
    private external fun nativeCanvasBench(): String
    private external fun nativeSetStress(on: Boolean)
    private external fun nativeSetInsets(l: Int, t: Int, r: Int, b: Int)
    private external fun nativeSetTool(t: Int)
    private external fun nativeSetBrushBuildup(on: Int)
    private external fun nativeSetBrushStyle(shape: Int, hardness: Int, spacing: Int,
                                             scatter: Int, aspect: Int, angle: Int, flow: Int)
    private external fun nativeSetBrush(px: Float, argb: Int)
    private external fun nativeLayerOp(op: Int, arg: Int)
    private external fun nativeLayerMeta(): String
    private external fun nativeSetLayerName(idx: Int, name: String)
    private external fun nativeSetLayerOpacity(idx: Int, permille: Int)
    private external fun nativeCrop()
    private external fun nativeGradPreset(p: Int)
    private external fun nativeGradRange(pct: Int)
    private external fun nativeGradAngle(deg: Int)
    private external fun nativeGradPos(pct: Int)
    private external fun nativeGradApply()
    private external fun nativeGradCancel()
    private external fun nativeGradUi(): IntArray
    private external fun nativeUndo()
    private external fun nativeRedo()
    private external fun nativeSetOverlay(chk: Int, grid: Int)
    private external fun nativeSetCanvasStyle(gw: Int, gh: Int, gcol: Int, gal: Int, lw100: Int, chk: Int, ca: Int, cb: Int, bg: Int)
    private external fun nativeSetMagnifier(on: Int)
    private external fun nativeSetPngCompress(mode: Int)
    private external fun nativeExportPng(path: String, scale: Int): String
    private external fun nativeExportMode(path: String, kind: Int, scale: Int, cols: Int, delayMs: Int): String
    private external fun nativeExportInfo(): IntArray
    private external fun nativeExportPsd(path: String): String
    private external fun nativeSetPickMode(on: Int)
    private external fun nativeDeselect()
    private external fun nativeSetEraseMode(on: Int)
    private external fun nativeSetShape(type: Int, filled: Int)
    private external fun nativeSetPerfectLine(on: Int)
    private external fun nativeSetLeafWidth(w: Float)
    private external fun nativeSetShapeRatio(on: Int)
    private external fun nativeSetShapeSize(px: Float)
    private external fun nativeSetSelAllLayers(on: Int)
    private external fun nativeSetSelShape(shape: Int)
    private external fun nativeSetOnion(on: Int)
    private external fun nativeNewDocument(w: Int, h: Int)
    private external fun nativeResizeCanvas(w: Int, h: Int, ax: Int, ay: Int)
    private external fun nativeDebugScript(script: String, logPath: String): Boolean
    private external fun nativeScaleSel(pw: Int, ph: Int)
    private external fun nativeSetAlphaLock(on: Int)
    private external fun nativeSetSelectionAlphaOverlay(on: Int)
    private external fun nativeSetSelHoldMs(ms: Int)
    private external fun nativePasteLastCut()
    private external fun nativeSelRect(): IntArray
    private external fun nativeMeasUi(): IntArray
    private external fun nativeSetStretch(on: Int)
    private external fun nativeSetPerspective(on: Int)
    private external fun nativeFrameOp(op: Int, arg: Int)
    private external fun nativeSetRecord(on: Int, path: String)
    private external fun nativeResumeRecord(path: String, count: Int, elapsedMs: Int)
    private external fun nativeIsAlive(): Boolean
    private external fun nativeRecordUiEvent(kind: Int, label: String)
    private external fun nativeFrameThumb(frame: Int, layer: Int): IntArray
    private external fun nativeThumbRevision(): Int
    private external fun nativeMinimapThumb(): IntArray?
    private external fun nativeCompositeThumb(): IntArray?
    private external fun nativeResizeThumb(): IntArray?
    private external fun nativeExportThumb(): IntArray?
    private external fun nativeSetMinimapActive(on: Int)
    private external fun nativeMinimapViewport(out: FloatArray)
    private external fun nativeAnimateViewCenter(cx: Float, cy: Float, animate: Int)
    private external fun nativeSetLoopRange(start: Int, end: Int)
    private external fun nativeReorderFrame(from: Int, to: Int)
    private external fun nativeSwapFrames(a: Int, b: Int)
    private external fun nativeReorderLayer(from: Int, to: Int)
    private external fun nativeSetPlayBrush(on: Int)
    private external fun nativeAnimateViewState(z: Float, px: Float, py: Float)
    private external fun nativeSelReset()
    private external fun nativeCopySel()
    private external fun nativeDuplicateSel()
    private external fun nativeDeleteSel()
    private external fun nativeConfirmSel()
    private external fun nativeCancelSel()
    private external fun nativeSelAdjust(h: Float, s: Float, b: Float, a: Float, blur: Float, commit: Boolean)
    private external fun nativeSelStroke(mode: Int, size: Int, dir: Int, outer: Boolean, depth: Float, color: Int, commit: Boolean)
    private external fun nativeSelRestore()
    private external fun nativeSelCancelPreview()
    private external fun nativeFloatToViewCenter()
    private external fun nativeSetLayerProps(layer: Int, mode: Int, tw: Int, th: Int, ox: Int, oy: Int,
                                             showGrid: Boolean, gridColor: Int, indep: Boolean, lockFrame: Int,
                                             blend: Int)
    private external fun nativeLayerProps(): IntArray
    private external fun nativeSet25D(on: Boolean, depth: Int, dir: Int)
    private external fun nativeReset25DView()
    private external fun nativeGet25D(): IntArray
    private external fun nativeRotateSel(quarter: Int)
    private external fun nativeSetMeasureFixed(on: Int)
    private external fun nativeMeasureCenterStart()
    private external fun nativeMeasureSetStartPending()
    private external fun nativePolyOp(op: Int)
    private external fun nativeCancelShapePreview()
    private external fun nativeParkTabHistory()
    private external fun nativeSetTabHistory(id: Int)
    private external fun nativeDropTabHistory(id: Int)
    private external fun nativeFlipSel(horizontal: Int)
    private external fun nativeImportImage(w: Int, h: Int, px: IntArray)
    private external fun nativeReplaySetFrame(w: Int, h: Int, px: IntArray)
    private external fun nativeReplayClear()
    private external fun nativeImportTextImage(w: Int, h: Int, px: IntArray)
    private external fun nativeSelectAll()
    private external fun nativeZoomFit()
    private external fun nativeFlip(horizontal: Int)
    private external fun nativeGrayscale()
    private external fun nativeSetGrayView(on: Int)
    private external fun nativeCanvasRect(): IntArray

    companion object {
        private const val MAX_CANVAS = 16834   // 硬上限 16834: 66×66 tile=4356 < 快照 8192 tile
        // (内存仍按实际分配 tile 计, 空白大画布不会预分配像素)
        private var updateAutoCheckedProcess = false   // 自动更新每进程只查一次
        init { System.loadLibrary("endpixcore") }
    }
}
