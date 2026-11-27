package com.endpix.nativeapp.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

// ===== 应用内 PDF: 下载后直接用 PdfRenderer 逐页渲染, 不依赖外部阅读器 =====
object PdfHub {
    var visible by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf("")
        private set
    var title by mutableStateOf("PDF")
        private set
    var savedPath by mutableStateOf("")
        private set

    private var lastUrl: String = ""
    private var lastReferer: String = ""
    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    var pageCount by mutableIntStateOf(0)
        private set
    // 切换标签/离开再回来时恢复翻页进度
    var savedIndex by mutableIntStateOf(0)
    var savedOffset by mutableIntStateOf(0)

    // 页面比例(w/h)与位图缓存: 占位不跳、翻页不卡
    private val aspects = HashMap<Int, Float>()
    private val bmpCache = object : android.util.LruCache<Int, Bitmap>(8) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }
    private val cacheBytes = 96 * 1024 * 1024
    // 单线程预取队列: 避免滚动时每帧 new Thread 争抢 PdfRenderer 锁
    private val prefetchExec = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** 本地缓存校验: 必须是真 PDF, 防止把蓝奏云的 HTML 错误页当 PDF 打开. */
    private fun looksLikePdf(f: File): Boolean {
        return try {
            f.inputStream().use { ins ->
                val b = ByteArray(5)
                ins.read(b) == 5 && String(b, Charsets.US_ASCII) == "%PDF-"
            }
        } catch (_: Throwable) { false }
    }

    fun aspectOf(index: Int): Float = synchronized(this) { aspects[index] ?: 0.7071f }

    /** 缓存键用蓝奏云分享页(无签名参数, 重启后仍稳定); 没有 referer 才退回 URL path. */
    private fun cacheKey(url: String, referer: String): String {
        val src = if (referer.isNotBlank()) referer else url
        val path = try { URL(src).path } catch (_: Throwable) { src }
        return Integer.toHexString(path.hashCode())
    }

    fun isPdfUrl(u: String): Boolean {
        val path = try { URL(u).path.lowercase() } catch (_: Throwable) { u.lowercase() }
        return path.endsWith(".pdf")
    }

    // 下载并打开; 若给的是蓝奏云分享页, 先让 WebView 处理页面, 这里只负责真实文件 URL
    fun open(ctx: Context, url: String, ua: String? = null, force: Boolean = false,
             referer: String? = null) {
        if (loading) return
        if (referer != null) lastReferer = referer
        else if (url != lastUrl) lastReferer = ""
        // 已经下载过就直接打开本地文件, 不再重新下载(除非点"重新下载")
        if (!force) {
            val key = cacheKey(url, lastReferer)
            val cached = File(ctx.filesDir, "pdf").listFiles()
                ?.firstOrNull {
                    it.isFile && it.name == "doc_$key.pdf" &&
                        it.length() > 1_000_000 && looksLikePdf(it)
                }
            if (cached != null) {
                try {
                    openRenderer(cached)
                    if (pageCount > 0) {
                        savedPath = cached.absolutePath
                        title = ctx.getSharedPreferences("endpix", Context.MODE_PRIVATE)
                            .getString("pdfTitle", "PDF") ?: "PDF"
                        lastUrl = url
                        visible = true
                        loading = false
                        error = ""
                        return
                    }
                } catch (_: Throwable) { }
            }
        }
        if (force) {
            try {
                File(ctx.filesDir, "pdf").listFiles()?.forEach { old ->
                    if (old.isFile) old.delete()
                }
            } catch (_: Throwable) { }
        }
        loading = true
        lastUrl = url
        error = ""
        visible = true
        title = "PDF"
        pageCount = 0
        closeRenderer()
        Thread {
            try {
                val dir = File(ctx.filesDir, "pdf").apply { mkdirs() }
                val key = cacheKey(url, lastReferer)
                val f = File(dir, "doc_$key.pdf")
                // 同一地址已下载过就直接打开, 避免重启后又下一遍
                if (f.exists() && f.length() > 1_000_000 && looksLikePdf(f)) {
                    try {
                        savedPath = f.absolutePath
                        openRenderer(f)
                        if (pageCount > 0) { loading = false; return@Thread }
                    } catch (_: Throwable) { }
                }
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", ua ?: "Mozilla/5.0 (Linux; Android 14)")
                if (lastReferer.isNotBlank()) conn.setRequestProperty("Referer", lastReferer)
                try {
                    android.webkit.CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                        conn.setRequestProperty("Cookie", it)
                    }
                } catch (_: Throwable) { }
                try {
                    val cd = conn.getHeaderField("Content-Disposition") ?: ""
                    Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
                        .find(cd)?.groupValues?.getOrNull(1)?.let { name ->
                            try { title = URLDecoder.decode(name, "UTF-8").trim('"') } catch (_: Throwable) { }
                        }
                } catch (_: Throwable) { }
                conn.inputStream.use { input -> f.outputStream().use { out -> input.copyTo(out) } }
                conn.disconnect()
                if (!f.exists() || f.length() < 1000 || !looksLikePdf(f)) {
                    try { f.delete() } catch (_: Throwable) { }
                    throw IllegalStateException("下载到的不是 PDF（可能是网页错误页）")
                }
                savedPath = f.absolutePath
                lastUrl = url
                try {
                    ctx.getSharedPreferences("endpix", Context.MODE_PRIVATE)
                        .edit().putString("pdfTitle", title).apply()
                } catch (_: Throwable) { }
                openRenderer(f)
                if (pageCount <= 0) throw IllegalStateException("不是有效的 PDF")
                // 只留最新一份, 避免蓝奏云每次签名不同导致缓存堆积
                try {
                    File(ctx.filesDir, "pdf").listFiles()?.forEach { old ->
                        if (old.isFile && old.absolutePath != f.absolutePath) old.delete()
                    }
                } catch (_: Throwable) { }
            } catch (t: Throwable) {
                error = "PDF 打开失败：${t.message}"
            } finally {
                loading = false
            }
        }.start()
    }

    private fun openRenderer(f: File) {
        closeRenderer()
        pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = PdfRenderer(pfd!!)
        renderer = r
        pageCount = r.pageCount
        synchronized(this) {
            // 不再打开每一页算比例(大 PDF 会卡很久); 比例在真正渲染时懒填
            aspects.clear()
            bmpCache.evictAll()
        }
    }

    private fun closeRenderer() {
        try { renderer?.close() } catch (_: Throwable) { }
        renderer = null
        try { pfd?.close() } catch (_: Throwable) { }
        pfd = null
    }

    fun redownload(ctx: Context, ua: String? = null) {
        val u = lastUrl
        if (u.isNotBlank()) open(ctx, u, ua, force = true)
    }

    fun saveScroll(index: Int, offset: Int) {
        savedIndex = index
        savedOffset = offset
    }

    fun close() {
        savedIndex = 0
        savedOffset = 0
        synchronized(this) { bmpCache.evictAll() }
        visible = false
        closeRenderer()
        pageCount = 0
    }

    fun renderPageCached(index: Int, targetWidth: Int): Bitmap? {
        synchronized(this) { bmpCache.get(index)?.let { return it } }
        val b = renderPage(index, targetWidth) ?: return null
        synchronized(this) {
            if (bmpCache.size() + b.byteCount <= cacheBytes) bmpCache.put(index, b)
        }
        return b
    }

    fun prefetch(start: Int, targetWidth: Int) {
        prefetchExec.execute {
            for (k in start until (start + 4).coerceAtMost(pageCount)) {
                if (synchronized(this) { bmpCache.get(k) } == null) renderPageCached(k, targetWidth)
            }
        }
    }

    // 逐页渲染(按目标宽度缩放); 在 IO 线程调用
    fun renderPage(index: Int, targetWidth: Int): Bitmap? = synchronized(this) {
        val r = renderer ?: return null
        if (index < 0 || index >= r.pageCount) return null
        return try {
            r.openPage(index).use { p ->
                val w = targetWidth.coerceAtLeast(200)
                val h = (w.toFloat() * p.height / p.width.toFloat()).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                aspects[index] = w.toFloat() / h.toFloat()
                bmp
            }
        } catch (_: Throwable) { null }
    }
}

@Composable
fun PdfViewer(ctx: Context) {
    Dialog(
        onDismissRequest = { PdfHub.close() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(Modifier.fillMaxSize().background(ComposeColor(0xFF0B0E13))) {
            Row(
                Modifier.fillMaxWidth().background(Ep.Panel).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(PdfHub.title, color = Ep.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f), maxLines = 1)
                Text("${PdfHub.pageCount} 页", color = Ep.Sub, fontSize = 10.sp)
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.height(28.dp).background(Ep.Panel2, RoundedCornerShape(4.dp))
                        .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
                        .clickable(interactionSource = null, indication = null) { PdfHub.redownload(ctx) }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) { Text("重新下载", color = Ep.Sub, fontSize = 10.sp) }
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.height(28.dp).background(Ep.Panel2, RoundedCornerShape(4.dp))
                        .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
                        .clickable(interactionSource = null, indication = null) { PdfHub.close() }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) { Text("关闭", color = Ep.Text, fontSize = 10.sp) }
            }
            when {
                PdfHub.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("下载中…", color = Ep.Sub, fontSize = 12.sp)
                }
                PdfHub.error.isNotEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(PdfHub.error, color = ComposeColor(0xFFE5534B), fontSize = 11.sp)
                }
                else -> PdfPages()
            }
        }
    }
}

// 浏览器标签内的 PDF 视图: 保留浏览器顶栏/标签栏, 只在网页区域显示 PDF
@Composable
fun PdfInTab(ctx: Context) {
    Column(Modifier.fillMaxSize().background(ComposeColor(0xFF0B0E13))) {
        Row(
            Modifier.fillMaxWidth().background(Ep.Panel).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(PdfHub.title, color = Ep.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f), maxLines = 1)
            Text(
                if (PdfHub.loading) "下载中…" else "${PdfHub.pageCount} 页",
                color = Ep.Sub, fontSize = 10.sp
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.height(28.dp).background(Ep.Panel2, RoundedCornerShape(4.dp))
                    .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
                    .clickable(interactionSource = null, indication = null) { PdfHub.redownload(ctx) }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) { Text("重新下载", color = Ep.Sub, fontSize = 10.sp) }
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.height(28.dp).background(Ep.Panel2, RoundedCornerShape(4.dp))
                    .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
                    .clickable(interactionSource = null, indication = null) {
                        PdfHub.close()
                        BrowserHub.goHome()   // 关闭 PDF 回浏览器主页, 不退出应用
                    }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) { Text("关闭", color = Ep.Text, fontSize = 10.sp) }
        }
        when {
            PdfHub.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("下载中…", color = Ep.Sub, fontSize = 12.sp)
            }
            PdfHub.error.isNotEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(PdfHub.error, color = ComposeColor(0xFFE5534B), fontSize = 11.sp)
            }
            else -> PdfPages()
        }
    }
}

@Composable
fun PdfPages() {
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    // 用像素宽度, 保证左右占满
    val targetW = (cfg.screenWidthDp * cfg.densityDpi / 160f).toInt().coerceAtLeast(720)
    val state = androidx.compose.foundation.lazy.rememberLazyListState(
        initialFirstVisibleItemIndex = PdfHub.savedIndex,
        initialFirstVisibleItemScrollOffset = PdfHub.savedOffset
    )
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { PdfHub.saveScroll(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset) }
    }
    androidx.compose.runtime.LaunchedEffect(state) {
        androidx.compose.runtime.snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (idx, off) ->
                PdfHub.saveScroll(idx, off)
                PdfHub.prefetch(idx, targetW)
            }
    }
    LazyColumn(Modifier.fillMaxSize().background(ComposeColor(0xFF0B0E13)), state = state) {
        items((0 until PdfHub.pageCount).toList()) { idx ->
            val bmp by produceState<Bitmap?>(initialValue = null, idx) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    PdfHub.renderPageCached(idx, targetW)
                }
            }
            val b = bmp
            val aspect = PdfHub.aspectOf(idx)
            if (b != null) {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = "page ${idx + 1}",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
                )
            } else {
                // 占位高度按页面比例算, 翻页不跳
                val h = (cfg.screenWidthDp / aspect).dp
                Box(Modifier.fillMaxWidth().height(h), contentAlignment = Alignment.Center) {
                    Text("${idx + 1}", color = Ep.Sub, fontSize = 10.sp)
                }
            }
        }
    }
}
