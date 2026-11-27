package com.endpix.nativeapp.ui

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color as AColor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Patterns
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import java.net.URLEncoder

// ===== endpix 内置浏览器(WebView/Chromium): 虚拟标签, 不参与画布快照 =====
object BrowserHub {
    var visible by mutableStateOf(false)
        private set
    // 只看图片模式: 隐藏地址栏(禁输入/复制), 页面只保留图片
    var imageMode by mutableStateOf(false)
        private set
    var url by mutableStateOf("")
        private set
    var title by mutableStateOf("")
        private set
    var canBack by mutableStateOf(false)
        private set
    var canForward by mutableStateOf(false)
        private set
    var loading by mutableStateOf(false)
        private set
    var progress by mutableIntStateOf(0)
        private set
    var error by mutableStateOf("")
        private set
    var engine by mutableIntStateOf(1)   // 1=Bing 2=Google (0 已弃用, 读到会迁移到 Bing)
        private set
    // Pinterest 免翻墙: 公共第三方前端(镜像)在境外抓取 pinterest, 图片也走镜像的 image_proxy,
    // 本机只连镜像站, 不需要 VPN。镜像会挂, 所以列一组可切换/自动回退。
    private val pinMirrors = listOf(
        "binternet.ducks.party",
        "curate.liv.town",
        "binternet.nadeko.net",
        "binternet.utilibre.org",
        "binternet.ahwx.org"
    )
    // Painterest 是另一个前端(瀑布流/无限滚动), 搜索路径不同
    private val painterestMirrors = listOf("pain.thirtysix.pw", "pt.bloat.cat")
    var pinMirror by mutableIntStateOf(0)
        private set

    private var host: Context? = null
    private var prefs: android.content.SharedPreferences? = null

    // ── 浏览器内部多标签 ─────────────────────────────────────────────
    // 每个标签一个独立 WebView(保留前进/后退/滚动/表单), 上限 5 个.
    // 顶部标签栏把浏览器标签排在一起, 用分隔线和画布标签分开.
    class BrowserTab internal constructor(val id: Int) {
        internal lateinit var wv: WebView
        var title by mutableStateOf("")
        var url: String = ""
        var loadStarted: Boolean = false
        val label: String get() = title.trim().ifBlank { "新标签" }
    }

    val tabs = mutableStateListOf<BrowserTab>()
    var activeId by mutableIntStateOf(0)
        private set
    val activeIndex: Int get() = tabs.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
    val maxTabs: Int get() = MAX_TABS
    // 长按网页图片: 下载 / 替换当前参考图
    data class ImageMenu(val url: String, val pageUrl: String)
    var imageMenu by mutableStateOf<ImageMenu?>(null)
        private set
    fun dismissImageMenu() { imageMenu = null }
    internal val webView: WebView? get() = activeTab?.wv
    private val activeTab: BrowserTab? get() = tabs.firstOrNull { it.id == activeId } ?: tabs.firstOrNull()
    private var nextTabId = 1
    private var hostView: FrameLayout? = null
    private var attachedTabId = 0
    private const val MAX_TABS = 5

    private fun tabOf(view: WebView): BrowserTab? = tabs.firstOrNull { it.wv === view }
    private fun isActiveTab(view: WebView): Boolean = tabOf(view)?.id == activeId


    fun init(ctx: Context) {
        host = ctx
        prefs = ctx.getSharedPreferences("endpix", Context.MODE_PRIVATE)
        android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        AdBlockHub.init(ctx)
        TranslateHub.init(ctx)
        engine = (prefs?.getInt("browserEngine", 1) ?: 1).coerceIn(0, 2)
        if (engine == 0) { engine = 1; prefs?.edit()?.putInt("browserEngine", 1)?.apply() }  // 去掉百度
        pinMirror = (prefs?.getInt("pinMirror", 0) ?: 0).coerceIn(0, pinMirrors.size - 1)
    }

    fun open() {
        if (host == null) return
        ReplayHub.visible = false
        visible = true
    }

    fun open(ctx: Context) {
        if (host == null) init(ctx)
        open()
    }

    fun close() {
        visible = false
    }

    fun setSearchEngine(e: Int) {
        engine = e.coerceIn(0, 2)
        prefs?.edit()?.putInt("browserEngine", engine)?.apply()
    }

    fun selectPinMirror(i: Int) {
        pinMirror = i.coerceIn(0, pinMirrors.size - 1)
        prefs?.edit()?.putInt("pinMirror", pinMirror)?.apply()
    }

    fun pinMirrorName(): String = pinMirrors.getOrElse(pinMirror) { pinMirrors[0] }

    fun pinMirrorList(): List<String> = pinMirrors

    fun painterestHost(): String = painterestMirrors[0]

    fun pinHome() {
        navigate("https://${pinMirrorName()}/")
    }

    fun pinSearch(q: String) {
        val qq = q.trim()
        if (qq.isEmpty()) return
        val enc = try { URLEncoder.encode(qq, "UTF-8") } catch (_: Throwable) { qq }
        navigate("https://${pinMirrorName()}/search.php?q=$enc")
    }

    fun painterestSearch(q: String) {
        val qq = q.trim()
        if (qq.isEmpty()) return
        val enc = try { URLEncoder.encode(qq, "UTF-8") } catch (_: Throwable) { qq }
        navigate("https://${painterestMirrors[0]}/search/pins?q=$enc")
    }

    fun pinMirrorUrl(host: String, q: String? = null): String {
        if (q.isNullOrBlank()) return "https://$host/"
        val enc = try { URLEncoder.encode(q, "UTF-8") } catch (_: Throwable) { q }
        return "https://$host/search.php?q=$enc"
    }

    fun painterestUrl(host: String, q: String? = null): String {
        if (q.isNullOrBlank()) return "https://$host/"
        val enc = try { URLEncoder.encode(q, "UTF-8") } catch (_: Throwable) { q }
        return "https://$host/search/pins?q=$enc"
    }

    internal fun isPinMirrorHost(h: String?): Boolean =
        h != null && (pinMirrors.contains(h) || painterestMirrors.contains(h))

    // 首次打开/冷启动: 恢复上次的浏览器标签(URL+标题), 没有记录才建一个空白标签
    private fun ensureFirstTab(ctx: Context): BrowserTab {
        tabs.firstOrNull()?.let { return it }
        val raw = prefs?.getString("browserTabs", null)
        if (raw != null) {
            try {
                val arr = org.json.JSONArray(raw)
                for (i in 0 until minOf(arr.length(), MAX_TABS)) {
                    val o = arr.optJSONObject(i) ?: continue
                    val t = BrowserTab(nextTabId++)
                    t.wv = create(ctx)
                    t.url = o.optString("url", "")
                    t.title = o.optString("title", "")
                    tabs.add(t)
                }
            } catch (_: Throwable) {
                tabs.clear()
            }
            if (tabs.isNotEmpty()) {
                val ai = (prefs?.getInt("browserTabsActive", 0) ?: 0)
                    .coerceIn(0, tabs.size - 1)
                activeId = tabs[ai].id
                val t = tabs[ai]
                // 只先加载当前标签, 其余选中时再加载, 避免冷启动一次开 5 个网页
                if (t.url.isNotBlank() && needsProxy(t.url) && !ProxyHub.isProxyActive()) {
                    t.loadStarted = true   // 交给 connectAuto 连上后跳转, 选中时别再直连一次
                    autoConnectFor(t.url)
                } else {
                    startLoad(t, t.url.ifBlank { null })
                }
                return t
            }
            // 记录存在但为空: 上次把浏览器标签全关掉了, 不要再从 browserUrl 恢复旧页
            if (raw.trim() == "[]" || raw.isBlank()) {
                val t = BrowserTab(nextTabId++)
                t.wv = create(ctx)
                tabs.add(t)
                activeId = t.id
                startLoad(t, null)
                return t
            }
        }
        val t = BrowserTab(nextTabId++)
        t.wv = create(ctx)
        tabs.add(t)
        activeId = t.id
        val last = prefs?.getString("browserUrl", "") ?: ""
        // 冷启动恢复的页面如果是要代理的站点(如 Pinterest/X), 先连代理再由 connectAuto 跳转,
        // 避免先直连一次白屏/超时. 其余情况等 WebView 真正有尺寸后再加载.
        if (last.isNotBlank() && needsProxy(last) && !ProxyHub.isProxyActive()) {
            autoConnectFor(last)
        } else {
            startLoad(t, last.ifBlank { null })
        }
        return t
    }

    /** 把标签列表(URL+标题+当前下标)写进 prefs, 杀进程/重启后恢复. */
    private fun saveTabs(commit: Boolean = false) {
        val p = prefs ?: return
        try {
            val arr = org.json.JSONArray()
            tabs.forEach { t ->
                arr.put(org.json.JSONObject().apply {
                    put("url", t.url)
                    put("title", t.title)
                })
            }
            val ai = tabs.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
            val e = p.edit()
                .putString("browserTabs", arr.toString())
                .putInt("browserTabsActive", ai)
            if (commit) e.commit() else e.apply()
        } catch (_: Throwable) { }
    }

    private fun startLoad(t: BrowserTab, url: String?) {
        if (t.loadStarted) return
        t.loadStarted = true
        loadWhenSized(t.wv, url)
    }

    internal fun obtainHost(ctx: Context): FrameLayout {
        if (host == null) init(ctx)
        ensureFirstTab(ctx)
        val f = FrameLayout(ctx)
        f.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        hostView = f
        attachedTabId = 0
        attachHost(f)
        return f
    }

    internal fun syncHost(f: FrameLayout) {
        hostView = f
        attachHost(f)
    }

    internal fun releaseHost(f: FrameLayout) {
        try { activeTab?.wv?.onPause() } catch (_: Throwable) { }
        f.removeAllViews()
        if (hostView === f) hostView = null
        attachedTabId = 0
    }

    private fun attachHost(f: FrameLayout) {
        val t = activeTab ?: return
        if (attachedTabId == t.id && f.childCount == 1 && f.getChildAt(0) === t.wv) return
        f.removeAllViews()
        (t.wv.parent as? ViewGroup)?.removeView(t.wv)
        f.addView(
            t.wv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        attachedTabId = t.id
        t.wv.onResume()
    }

    fun selectTab(index: Int) {
        val t = tabs.getOrNull(index) ?: return
        if (t.id != activeId) {
            try { activeTab?.wv?.onPause() } catch (_: Throwable) { }
            activeId = t.id
            try { PdfHub.close() } catch (_: Throwable) { }
        }
        visible = true
        url = t.url
        title = t.title
        error = ""
        loading = false
        refreshHistory(t.wv)
        try { t.wv.onResume() } catch (_: Throwable) { }
        hostView?.let { attachHost(it) }
        // 恢复出来但还没加载过的标签, 选中时再加载
        startLoad(t, t.url.ifBlank { null })
        saveTabs()
    }

    fun newTab(ctx: Context): Boolean {
        if (host == null) init(ctx)
        if (tabs.size >= MAX_TABS) {
            try { Toast.makeText(ctx, "最多 $MAX_TABS 个浏览器标签", Toast.LENGTH_SHORT).show() } catch (_: Throwable) { }
            return false
        }
        val t = BrowserTab(nextTabId++)
        t.wv = create(ctx)
        tabs.add(t)
        startLoad(t, null)
        selectTab(tabs.size - 1)
        return true
    }

    fun closeTab(index: Int) {
        val t = tabs.getOrNull(index) ?: return
        val wasActive = t.id == activeId
        tabs.removeAt(index)
        (t.wv.parent as? ViewGroup)?.removeView(t.wv)
        try { t.wv.stopLoading(); t.wv.destroy() } catch (_: Throwable) { }
        if (tabs.isEmpty()) {
            // 浏览器不是画布: 最后一个标签也直接关掉, 整个浏览器层收起
            activeId = 0
            attachedTabId = 0
            visible = false
            prefs?.edit()?.remove("browserUrl")?.apply()
            saveTabs(true)
            try { PdfHub.close() } catch (_: Throwable) { }
            return
        }
        if (wasActive) selectTab(index.coerceAtMost(tabs.size - 1))
        else hostView?.let { attachHost(it) }
        saveTabs()
    }

    private fun toast(ctx: Context, msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() } catch (_: Throwable) { }
        }
    }

    /** 经当前代理(若有)抓图片原始字节; pinimg 这类被墙 CDN 必须走代理.
     *  UA/Cookie/代理端口都在 UI 线程先取好, 后台线程不碰 WebView/WebSettings. */
    private fun fetchImageBytes(
        m: ImageMenu, ua: String, cookie: String?, port: Int
    ): ByteArray? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            val u = java.net.URL(m.url)
            conn = (if (port > 0) {
                u.openConnection(
                    java.net.Proxy(java.net.Proxy.Type.HTTP,
                        java.net.InetSocketAddress("127.0.0.1", port))
                )
            } else u.openConnection()) as java.net.HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", ua)
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (m.pageUrl.isNotBlank()) conn.setRequestProperty("Referer", m.pageUrl)
            if (!cookie.isNullOrBlank()) conn.setRequestProperty("Cookie", cookie)
            val code = conn.responseCode
            if (code !in 200..299) {
                android.util.Log.e("endpixweb", "img http $code ${m.url.takeLast(80)}")
                return null
            }
            var body = conn.inputStream.readBytes()
            if (body.size >= 2 && body[0] == 0x1f.toByte() && body[1] == 0x8b.toByte()) {
                body = java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(body))
                    .use { it.readBytes() }
            }
            android.util.Log.e("endpixweb", "img ok ${body.size}B port=$port ${m.url.takeLast(60)}")
            body
        } catch (e: Throwable) {
            android.util.Log.e("endpixweb", "img fetch fail: ${e.message} ${m.url.takeLast(80)}")
            null
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) { }
        }
    }

    private fun imageExt(u: String): String {
        val p = u.substringBefore('?').lowercase()
        return when {
            p.endsWith(".png") -> "png"
            p.endsWith(".webp") -> "webp"
            p.endsWith(".gif") -> "gif"
            p.endsWith(".bmp") -> "bmp"
            else -> "jpg"
        }
    }

    private fun saveImageToDownloads(ctx: Context, bytes: ByteArray, url: String): String? {
        val ext = imageExt(url)
        val mime = "image/" + if (ext == "jpg") "jpeg" else ext
        val name = "endpix_" +
            java.text.SimpleDateFormat("MMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date()) + "." + ext
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS)
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return null
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear()
                values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                ctx.contentResolver.update(uri, values, null, null)
                name
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                if (!dir.exists()) dir.mkdirs()
                java.io.File(dir, name).writeBytes(bytes)
                name
            }
        } catch (e: Throwable) {
            android.util.Log.e("endpixweb", "img save fail: ${e.message}")
            null
        }
    }

    fun downloadImage(ctx: Context, m: ImageMenu) {
        dismissImageMenu()
        val ua = try { webView?.settings?.userAgentString } catch (_: Throwable) { null } ?: chromeUa()
        val cookie = try { CookieManager.getInstance().getCookie(m.url) } catch (_: Throwable) { null }
        val port = try { ProxyHub.activePort() } catch (_: Throwable) { 0 }
        Thread {
            val body = fetchImageBytes(m, ua, cookie, port)
            val name = if (body != null) saveImageToDownloads(ctx, body, m.url) else null
            toast(ctx, if (name != null) "已下载：$name" else "图片下载失败")
        }.start()
    }

    /** 应用分享: 分享图片文件本身(不是链接). */
    fun shareImage(ctx: Context, m: ImageMenu) {
        dismissImageMenu()
        val ua = try { webView?.settings?.userAgentString } catch (_: Throwable) { null } ?: chromeUa()
        val cookie = try { CookieManager.getInstance().getCookie(m.url) } catch (_: Throwable) { null }
        val port = try { ProxyHub.activePort() } catch (_: Throwable) { 0 }
        toast(ctx, "正在准备图片…")
        Thread {
            val body = fetchImageBytes(m, ua, cookie, port)
            if (body == null) {
                toast(ctx, "图片获取失败")
                return@Thread
            }
            try {
                val ext = imageExt(m.url)
                val f = java.io.File(ctx.cacheDir,
                    "share_${System.currentTimeMillis()}.$ext")
                f.writeBytes(body)
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    ctx, ctx.packageName + ".fileprovider", f
                )
                val mime = "image/" + if (ext == "jpg") "jpeg" else ext
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(send, "分享图片")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(chooser)
            } catch (e: Throwable) {
                toast(ctx, "分享失败：${e.message ?: "无法打开应用"}")
            }
        }.start()
    }

    /** 分享链接: 只发 URL 文本. */
    fun shareLink(ctx: Context, m: ImageMenu) {
        dismissImageMenu()
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, m.url)
            }
            val chooser = Intent.createChooser(send, "分享链接")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(chooser)
        } catch (e: Throwable) {
            toast(ctx, "分享失败：${e.message ?: "无法打开应用"}")
        }
    }

    fun useImageAsReference(ctx: Context, m: ImageMenu) {
        dismissImageMenu()
        val ua = try { webView?.settings?.userAgentString } catch (_: Throwable) { null } ?: chromeUa()
        val cookie = try { CookieManager.getInstance().getCookie(m.url) } catch (_: Throwable) { null }
        val port = try { ProxyHub.activePort() } catch (_: Throwable) { 0 }
        Thread {
            val body = fetchImageBytes(m, ua, cookie, port)
            var bmp: android.graphics.Bitmap? = null
            if (body != null) {
                try {
                    val bounds = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    android.graphics.BitmapFactory.decodeByteArray(body, 0, body.size, bounds)
                    if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                        var sample = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
                        val opts = android.graphics.BitmapFactory.Options().apply {
                            inSampleSize = sample
                            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                        }
                        bmp = android.graphics.BitmapFactory.decodeByteArray(body, 0, body.size, opts)
                    }
                } catch (_: Throwable) { }
            }
            val b = bmp
            if (b != null) {
                RefImageBridge.apply?.invoke(b)
            } else {
                toast(ctx, "参考图读取失败")
            }
        }.start()
    }


    /**
     * 等 WebView 真正拿到非零尺寸再加载.
     * Compose 的 AndroidView 在首帧布局前就调用 factory, 此时 WebView 是 0×0;
     * Chromium 会按 0 高度锁定 CSS 视口, 之后 100vh/100dvh 永远算成 0,
     * X 这类整页高度依赖 100% 的登录页会直接塌掉(输入框跑到屏幕外).
     */
    private fun loadWhenSized(wv: WebView, url: String?) {
        var left = 40
        val run = object : Runnable {
            override fun run() {
                val go = { if (url == null) loadStartPage(wv) else wv.loadUrl(url) }
                if (wv.width > 0 && wv.height > 0) go()
                else if (left-- > 0) wv.postDelayed(this, 50)
                else go()
            }
        }
        wv.post(run)
    }

    internal fun release(wv: WebView) {
        wv.onPause()
        wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false)
    }

    fun onHostPause() {
        saveTabs(true)
        tabs.forEach { t -> try { t.wv.onPause() } catch (_: Throwable) { } }
        // 登录态/站点 Cookie 落盘, 后台被回收或下次启动仍是登录态
        try { CookieManager.getInstance().flush() } catch (_: Throwable) { }
    }
    fun onHostResume() { if (visible) { activeTab?.wv?.onResume() } }
    fun onTrimMemory(level: Int) {
        if (level >= 60) {
            tabs.forEach { t -> try { t.wv.freeMemory() } catch (_: Throwable) { } }
        }
    }

    private fun destroyWebView() {
        try { CookieManager.getInstance().flush() } catch (_: Throwable) { }
        tabs.forEach { t ->
            (t.wv.parent as? ViewGroup)?.removeView(t.wv)
            try { t.wv.stopLoading(); t.wv.destroy() } catch (_: Throwable) { }
        }
        tabs.clear()
        activeId = 0
        hostView = null
        attachedTabId = 0
    }

    fun destroy() {
        saveTabs(true)
        destroyWebView()
        visible = false
        host = null
        prefs = null
    }

    fun goBack() { webView?.let { if (it.canGoBack()) it.goBack(); refreshHistory(it) } }
    fun goForward() { webView?.let { if (it.canGoForward()) it.goForward(); refreshHistory(it) } }
    fun reload() { webView?.reload() }
    fun goHome() { webView?.let { loadStartPage(it) } }
    fun stop() { webView?.stopLoading(); loading = false }

    private var enteringImage = false

    fun enterImageMode(target: String? = null) {
        imageMode = true
        if (!target.isNullOrBlank()) {
            enteringImage = true
            navigate(target)
            enteringImage = false
        } else if (url.isNotBlank()) {
            webView?.reload()
        }
    }

    fun exitImageMode() {
        imageMode = false
        webView?.reload()
    }

    // 读取当前页所有图片, 重新组成一个本地纯图浏览页(不依赖原站 DOM/脚本/广告)
    // 先滚动到底把懒加载图片全部插进 DOM(漫画站常只先插前 20 张), 再收集
    fun buildGallery() {
        val wv = webView ?: return
        wv.evaluateJavascript(GALLERY_PREP_JS, null)
        var tries = 0
        val poll = object : Runnable {
            override fun run() {
                wv.evaluateJavascript("window.__epGalleryList||''") { raw ->
                    val decoded = try {
                        org.json.JSONTokener(raw).nextValue() as? String ?: ""
                    } catch (_: Throwable) { "" }
                    if (decoded.isNotBlank() && decoded != "[]") {
                        buildGalleryPage(decoded)
                    } else if (++tries < 135) {
                        wv.postDelayed(this, 700)
                    } else {
                        statusToast("没找到可用的图片")
                    }
                }
            }
        }
        wv.postDelayed(poll, 700)
    }

    private fun buildGalleryPage(decoded: String) {
        val wv = webView ?: return
        val list = try {
            val a = org.json.JSONArray(decoded)
            (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
        } catch (_: Throwable) { emptyList() }
        if (list.isEmpty()) { statusToast("没找到可用的图片"); return }
        val sb = StringBuilder()
        sb.append("<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<style>html,body{margin:0;padding:0;background:#0b0e13}img{display:block;width:100%;height:auto;margin:0 0 6px 0}</style></head><body>")
        for (u in list) sb.append("<img src=\"").append(u.replace("\"", "%22")).append("\">")
        sb.append("</body></html>")
        imageMode = true
        url = ""
        wv.loadDataWithBaseURL(null, sb.toString(), "text/html", "utf-8", null)
        statusToast("已生成看图页：${list.size} 张")
    }

    private val GALLERY_PREP_JS = """
(function(){
try{
  if(window.__epGathering) return;
  window.__epGathering=1;
  window.__epGalleryList='';
  function collect(){
    var out=[],seen={};
    var primary=document.querySelectorAll('img[id^="page_"], img.comic_img');
    var imgs=(primary&&primary.length>0)?primary:document.querySelectorAll('img');
    for(var i=0;i<imgs.length;i++){
      var im=imgs[i];
      var ds=im.getAttribute('data-src')||im.getAttribute('data-original');
      var u=ds||im.currentSrc||im.src||'';
      if(!u||u.indexOf('data:')===0) continue;
      if(/[\/_-](ad|ads|adv|banner|promo|gg|guanggao|tuiguang|affiliate|xpicvid)[\/_.-]/i.test(u)) continue;
      var cls=((im.className||'')+' '+(im.id||'')).toLowerCase();
      if(/(^|[\s_-])(ad|ads|banner|promo|gg|guanggao)([\s_-]|$)/.test(cls)) continue;
      var w=im.naturalWidth||0,h=im.naturalHeight||0;
      if(!ds){
        if(w<200||h<200) continue;
        if(h<=420&&w/Math.max(1,h)>=3.0) continue;
      }
      if(seen[u]) continue;
      seen[u]=1;out.push(u);
    }
    return JSON.stringify(out);
  }
  var last=-1, stable=0, round=0;
  var timer=setInterval(function(){
    var n=document.querySelectorAll('img[id^="page_"], img.comic_img').length;
    if(n===last) stable++; else { stable=0; last=n; }
    round++;
    var maxp=0;
    try{ var mm=document.getElementById('maxpage'); if(mm&&mm.innerText) maxp=parseInt(mm.innerText.trim())||0; }catch(e){}
    var done = (maxp>0 && n>=maxp) || round>110;
    if(done){
      clearInterval(timer);
      try{ window.scrollTo(0,0); }catch(e){}
      window.__epGalleryList=collect();
    } else if(round%4===1){
      try{ window.scrollTo(0, document.body.scrollHeight); }catch(e){}
    } else if(round%4===3){
      try{ window.scrollTo(0,0); }catch(e){}
    }
  },700);
}catch(e){}
})()
""".trimIndent()

    private val GALLERY_COLLECT_JS = """
(function(){
try{
  var out=[],seen={};
  var primary=document.querySelectorAll('img[id^="page_"], img.comic_img');
  var imgs=(primary&&primary.length>0)?primary:document.querySelectorAll('img');
  for(var i=0;i<imgs.length;i++){
    var im=imgs[i];
    var u=im.currentSrc||im.src||im.getAttribute('data-src')||im.getAttribute('data-original')||'';
    if(!u||u.indexOf('data:')===0) continue;
    if(/[\/_-](ad|ads|adv|banner|promo|gg|guanggao|tuiguang|affiliate|xpicvid)[\/_.-]/i.test(u)) continue;
    var cls=((im.className||'')+' '+(im.id||'')).toLowerCase();
    if(/(^|[\s_-])(ad|ads|banner|promo|gg|guanggao)([\s_-]|$)/.test(cls)) continue;
    var ds=im.getAttribute('data-src')||im.getAttribute('data-original');
    var w=im.naturalWidth||0,h=im.naturalHeight||0;
    if(!ds){
      if(w<200||h<200) continue;
      if(h<=420&&w/Math.max(1,h)>=3.0) continue;
    }
    if(seen[u]) continue;
    seen[u]=1;out.push(u);
  }
  return JSON.stringify(out);
}catch(e){return '[]';}
})()
""".trimIndent()

    private val IMAGE_ONLY_JS = """
(function(){
try{
  if(window.__epImgRun) return;
  window.__epImgRun=1;
  // 先立刻强力清广告/导航/公告/固定条: 只留有图片的块
  try{
    var kill='iframe,[class^=ad-],[class*=" ad-"],[class$="-ad"],[class*=ads],[id^=ad-],[id*=ads],'+
      '[class*=banner],[id*=banner],[class*=popup],[class*=notice],[class*=announce],'+
      '[style*="position:fixed"],[style*="position: fixed"],header,footer,nav{display:none!important}';
    var st0=document.createElement('style');st0.textContent=kill;
    (document.head||document.documentElement).appendChild(st0);
    var kids=document.body.children;
    for(var z=0;z<kids.length;z++){
      var k=kids[z];
      if(k.tagName==='IMG') continue;
      if(!k.querySelector||!k.querySelector('img')) k.style.display='none';
    }
  }catch(e){}
  var tries=0;
  function build(){
    var seen={},list=[];
    var imgs=document.querySelectorAll('img');
    for(var i=0;i<imgs.length;i++){
      var im=imgs[i];
      var u=im.currentSrc||im.src||im.getAttribute('data-src')||im.getAttribute('data-original')||'';
      if(!u||u.indexOf('data:')===0) continue;
      if(/[\/_-](ad|ads|adv|banner|promo|gg|guanggao|tuiguang|affiliate|xpicvid)[\/_.-]/i.test(u)) continue;
      var cls=((im.className||'')+' '+(im.id||'')+' '+((im.parentElement&&im.parentElement.className)||'')).toLowerCase();
      if(/(^|[\s_-])(ad|ads|banner|promo|gg|guanggao)([\s_-]|$)/.test(cls)) continue;
      var w=im.naturalWidth||im.width||0,h=im.naturalHeight||im.height||0;
      if(w<80||h<80) continue;
      if(h<150&&w>500) continue;                       // 横幅广告条
      if(h<=420&&w/Math.max(1,h)>=3.0) continue;        // 极宽横幅(广告位)
      if(w<260||h<200) continue;                        // 小图标/角标
      if(seen[u]) continue;
      seen[u]=1;list.push(u);
    }
    if(list.length===0) return false;
    var css='html,body{background:#0b0e13!important;margin:0!important;padding:0!important;overflow-x:hidden!important}'+
      'img.epimg{display:block;width:100%;height:auto;margin:0 0 6px 0;user-select:none;-webkit-user-select:none;-webkit-touch-callout:none}'+
      '*{user-select:none!important;-webkit-user-select:none!important}';
    document.head.innerHTML='<meta name="viewport" content="width=device-width,initial-scale=1"><style>'+css+'</style>';
    document.body.innerHTML='';
    document.body.style.background='#0b0e13';
    for(var k=0;k<list.length;k++){var e=document.createElement('img');e.className='epimg';e.src=list[k];document.body.appendChild(e);}
    return true;
  }
  window.scrollTo(0, document.body.scrollHeight);
  var t=setInterval(function(){
    try{ window.scrollTo(0, document.body.scrollHeight); }catch(e){}
    setTimeout(function(){ try{ window.scrollTo(0,0); }catch(e){} }, 250);
    if(build() || ++tries>25) clearInterval(t);
  }, 900);
}catch(e){}
})()
""".trimIndent()

    fun navigate(inputRaw: String) {
        if (imageMode && !enteringImage) imageMode = false
        navigateInternal(inputRaw)
    }

    // 代理连上后的内部跳转: 不清图片模式
    fun navigateInternal(inputRaw: String) {
        val input = inputRaw.trim()
        if (input.isEmpty()) return
        val target = toUrl(input)
        error = ""
        pinFallbackDepth = 0
        if (needsProxy(target)) { autoConnectFor(target); return }
        if (PdfHub.isPdfUrl(target)) { host?.let { PdfHub.open(it, target) }; return }
        if (LanzouAuto.isShare(target)) LanzouAuto.begin(target)
        webView?.loadUrl(target)
    }

    // 被墙站点: 代理没开时先一键连接, 连上后再打开
    /** 只按域名判断"这站需不需要翻墙", 不考虑当前代理/VPN 状态. */
    fun hostNeedsProxy(u: String?): Boolean {
        val h = try { if (u.isNullOrBlank()) null else Uri.parse(u).host?.lowercase() }
        catch (_: Throwable) { null } ?: return false
        return h == "google.com" || h.endsWith(".google.com") ||
            h == "pinterest.com" || h.endsWith(".pinterest.com") ||
            h == "pinimg.com" || h.endsWith(".pinimg.com") ||
            h == "x.com" || h.endsWith(".x.com") ||
            h == "twitter.com" || h.endsWith(".twitter.com") ||
            h == "twimg.com" || h.endsWith(".twimg.com") ||
            h == "youtube.com" || h.endsWith(".youtube.com")
    }

    fun currentUrlNeedsProxy(): Boolean = hostNeedsProxy(url)

    private fun needsProxy(url: String): Boolean {
        // 系统 VPN 已连通时直接走 VPN, 不启动本机订阅代理; 代理已开也不再重复触发
        if (ProxyHub.isProxyActive() || ProxyHub.vpnActive) return false
        return hostNeedsProxy(url)
    }

    private fun autoConnectFor(url: String) {
        val c = host ?: return
        ProxyHub.init(c)
        statusToast("正在自动连接代理，连上后自动打开…")
        ProxyHub.dialogOpen = true      // 先弹代理窗口显示进度, 连上后自动关
        ProxyHub.connectAuto(c, url)
    }

    private fun statusToast(msg: String) {
        try { Toast.makeText(host, msg, Toast.LENGTH_SHORT).show() } catch (_: Throwable) { }
    }

    private fun toUrl(input: String): String {
        val low = input.lowercase()
        if (low.startsWith("http://") || low.startsWith("https://")) return input
        val looksUrl = !input.contains(' ') && Patterns.WEB_URL.matcher(input).matches()
        if (looksUrl) return "https://$input"
        val q = try { URLEncoder.encode(input, "UTF-8") } catch (_: Throwable) { input }
        return when (engine) {
            2 -> "https://www.google.com/search?q=$q"
            else -> "https://cn.bing.com/search?q=$q"
        }
    }

    private fun loadStartPage(wv: WebView) {
        url = ""
        error = ""
        val html = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>
body{background:#0b0e13;color:#e6e6e6;font-family:sans-serif;margin:0;padding:24px 20px}
h1{font-size:20px;margin:0 0 6px;color:#f0f2f5}
h2{font-size:15px;margin:24px 0 4px;color:#f0f2f5}
p{color:#8b93a1;font-size:12px;margin:0 0 12px;line-height:1.5}
a{display:inline-block;margin:0 8px 8px 0;padding:10px 14px;background:#161b22;border:1px solid #2a3038;
  border-radius:8px;color:#e6e6e6;text-decoration:none;font-size:14px}
</style></head><body>
<h1>浏览器</h1>
<a href="https://cn.bing.com">Bing</a>
<a href="https://www.google.com/ncr">Google</a>
<a href="https://github.com">GitHub</a>
<h2>常用</h2>
<a href="https://mail.tm/">Mail.tm 注册邮箱</a>
<a href="https://www.pinterest.com/">Pinterest（需代理）</a>
<a href="https://x.com/i/flow/login">X / Twitter 登录（需代理）</a>
<a href="http://pixelgif.cn/">PixelGIF</a>
<a href="https://www.slynyrd.com/pixelblog-catalogue">Slynyrd 像素教程</a>
<a href="https://wwd.lanzouq.com/iiwWj1tmfung">蓝奏云</a>
</body></html>""".trimIndent()
        wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun refreshHistory(wv: WebView) {
        if (!isActiveTab(wv)) return
        canBack = wv.canGoBack()
        canForward = wv.canGoForward()
    }

    private var pinFallbackDepth = 0

    private fun hostOf(u: String?): String? =
        if (u.isNullOrBlank()) null else try { Uri.parse(u).host } catch (_: Throwable) { null }

    // 把 i.pinimg.com 的图片交给镜像的 image_proxy 取回
    private fun pinimgViaMirror(src: String): WebResourceResponse? = try {
        val proxy = "https://${pinMirrorName()}/image_proxy.php?url=" + URLEncoder.encode(src, "UTF-8")
        val conn = java.net.URL(proxy).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 15000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        conn.setRequestProperty("Referer", "https://${pinMirrorName()}/")
        if (conn.responseCode !in 200..299) {
            conn.disconnect()
            null
        } else {
            val ct = (conn.contentType ?: "image/jpeg").substringBefore(';').trim()
            WebResourceResponse(ct, null, conn.inputStream)
        }
    } catch (_: Throwable) { null }

    // X/Twitter 的 JS 分块在 WebView 里并发请求时容易被免费节点拖到超时;
    // 对 abs.twimg.com 的 js/css 自己经本地 HTTP 代理抓取(限并发+重试+长超时),
    // 避免 X 的 onboarding 页面卡在某个 8KB 的 chunk 上白屏.
    private val twAssetSem = java.util.concurrent.Semaphore(4)

    private fun chromeUa() = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36"

    private fun twimgAssetViaProxy(u: String): WebResourceResponse? {
        if (!twAssetSem.tryAcquire(15, java.util.concurrent.TimeUnit.SECONDS)) return null
        try {
            repeat(3) { attempt ->
                try {
                    val port = ProxyHub.activePort()
                    if (port <= 0) return null
                    val conn = java.net.URL(u).openConnection(
                        java.net.Proxy(java.net.Proxy.Type.HTTP,
                            java.net.InetSocketAddress("127.0.0.1", port))
                    ) as java.net.HttpURLConnection
                    conn.connectTimeout = 10000
                    conn.readTimeout = 25000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", chromeUa())
                    conn.setRequestProperty("Accept-Encoding", "identity")
                    if (conn.responseCode in 200..299) {
                        var body = conn.inputStream.readBytes()
                        // 某些 CDN 即使请求 identity 也会回 gzip; 手动解掉再交给 WebView
                        if (body.size >= 2 && body[0] == 0x1f.toByte() && body[1] == 0x8b.toByte()) {
                            body = java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(body)).use { it.readBytes() }
                        }
                        val lower = u.lowercase()
                        val ct = conn.contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() }
                            ?: when {
                                lower.contains(".css") -> "text/css"
                                lower.contains(".js") -> "application/javascript"
                                lower.contains(".woff2") -> "font/woff2"
                                else -> "application/octet-stream"
                            }
                        // 手动回包必须补 CORS, 否则 <script type=module crossorigin> 会被 WebView 拦
                        val hdr = HashMap<String, String>()
                        hdr["Access-Control-Allow-Origin"] = "*"
                        hdr["Access-Control-Allow-Headers"] = "*"
                        hdr["Cross-Origin-Resource-Policy"] = "cross-origin"
                        hdr["Timing-Allow-Origin"] = "*"
                        android.util.Log.e("endpixweb", "twimg $ct ${body.size}B ${u.takeLast(80)}")
                        return WebResourceResponse(ct, null, 200, "OK", hdr,
                            java.io.ByteArrayInputStream(body))
                    }
                    conn.disconnect()
                } catch (_: Throwable) { }
                try { Thread.sleep(250L * (attempt + 1)) } catch (_: Throwable) { }
            }
            android.util.Log.e("endpixweb", "twimg asset failed: $u")
        } finally {
            twAssetSem.release()
        }
        return null
    }

    private fun create(ctx: Context): WebView {
        val wv = WebView(ctx)
        // AndroidView 默认给子 View 套 WRAP_CONTENT; WebView 在 wrap_content 下
        // 对 CSS 视口高度形成循环依赖, Chromium 直接按 0 处理(100vh=0px, 见 loadWhenSized).
        wv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        wv.setBackgroundColor(AColor.rgb(11, 14, 19))
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        @Suppress("DEPRECATION") s.databaseEnabled = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.loadsImagesAutomatically = true
        s.blockNetworkImage = false
        // 不再强制 overview/wide viewport: 后者会把无 viewport 页面缩到 36%,
        // 布局视口高度只剩 ~116px, X 这种 100% 高度的登录页会被顶到屏幕外.
        s.useWideViewPort = false
        s.loadWithOverviewMode = false
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportZoom(true)
        s.mediaPlaybackRequiresUserGesture = true
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.setGeolocationEnabled(false)
        if (Build.VERSION.SDK_INT >= 26) s.safeBrowsingEnabled = true
        if (Build.VERSION.SDK_INT >= 33) s.setAlgorithmicDarkeningAllowed(true)
        // 用干净的 Chrome UA, 避免 X/Twitter 把 WebView 判成不支持浏览器
        s.userAgentString = chromeUa()
        // X/Twitter 登录和 reCAPTCHA 需要第三方 Cookie
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        s.textZoom = (ctx.resources.configuration.fontScale * 100f).toInt().coerceIn(80, 200)
        wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        // 长按网页图片: 弹「下载图片 / 替换当前参考图」.
        // 非图片(文字/链接)返回 false, 保留 WebView 默认的文字选择行为.
        wv.setOnLongClickListener {
            val hr = wv.hitTestResult
            val u = hr?.extra
            if ((hr?.type == WebView.HitTestResult.IMAGE_TYPE ||
                    hr?.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) &&
                !u.isNullOrBlank() && (u.startsWith("http://") || u.startsWith("https://"))
            ) {
                imageMenu = ImageMenu(u, wv.url ?: "")
                true
            } else false
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url ?: return null
                val h = u.host ?: return null
                // X/Twitter 的 js/css 分块自己经代理取(限并发+重试), 见 twimgAssetViaProxy
                if (ProxyHub.isProxyActive() && h.endsWith("twimg.com")) {
                    val path = u.path?.lowercase() ?: ""
                    if (path.endsWith(".js") || path.endsWith(".css") || path.endsWith(".woff2")) {
                        twimgAssetViaProxy(u.toString())?.let { return it }
                    }
                }
                // 去广告/追踪: 只拦子资源, 主文档不拦(避免用户主动打开被墙站点时白屏)
                if (!request.isForMainFrame && AdBlockHub.shouldBlock(h)) {
                    AdBlockHub.note(true)
                    return WebResourceResponse(
                        "text/plain", "utf-8", 204, "No Content",
                        emptyMap(), java.io.ByteArrayInputStream(ByteArray(0))
                    )
                }
                // Pinterest 图片 CDN 在国内被墙; 交给镜像的 image_proxy 转发, 页面里直接引 i.pinimg.com 也能显示
                if (!h.endsWith("pinimg.com")) return null
                if (ProxyHub.isProxyActive()) return null   // 走 Xray 时让 WebView 自己经代理取
                return pinimgViaMirror(u.toString())
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url ?: return false
                val scheme = u.scheme?.lowercase() ?: return false
                if (scheme == "endpix") {
                    val target = u.getQueryParameter("u")
                    if (!target.isNullOrBlank()) view.post { enterImageMode(target) }
                    return true
                }
                if (imageMode) return true   // 图片模式内禁止跳走
                if ((scheme == "http" || scheme == "https") && LanzouAuto.isShare(u.toString())) {
                    LanzouAuto.begin(u.toString())
                }
                if ((scheme == "http" || scheme == "https") && (PdfHub.isPdfUrl(u.toString()) || u.path?.lowercase()?.endsWith(".pdf") == true)) {
                    PdfHub.open(ctx, u.toString())
                    return true
                }
                if ((scheme == "http" || scheme == "https") && needsProxy(u.toString())) {
                    autoConnectFor(u.toString())
                    return true
                }
                if (scheme == "http" || scheme == "https") return false
                return try {
                    val i = Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(i)
                    true
                } catch (_: Throwable) { true }
            }

            override fun onPageStarted(view: WebView, u: String?, favicon: android.graphics.Bitmap?) {
                val shown = if (u != null && !u.startsWith("data:") && !u.startsWith("about:")) u else ""
                tabOf(view)?.url = shown
                if (isActiveTab(view)) {
                    url = shown
                    loading = true
                    progress = 0
                    error = ""
                }
                refreshHistory(view)
            }

            override fun onPageFinished(view: WebView, u: String?) {
                val tab = tabOf(view)
                val isHttp = !u.isNullOrBlank() && (u.startsWith("http://") || u.startsWith("https://"))
                val shownTitle = if (isHttp) (view.title ?: "") else "浏览器"
                tab?.title = shownTitle
                if (isHttp) tab?.url = u
                if (isActiveTab(view)) {
                    title = shownTitle
                    if (isHttp) {
                        url = u
                        prefs?.edit()?.putString("browserUrl", u)?.apply()
                    }
                }
                if (isActiveTab(view)) {
                    loading = false
                    progress = 100
                }
                if (isPinMirrorHost(hostOf(u))) pinFallbackDepth = 0
                refreshHistory(view)
                if (imageMode) {
                    try { view.evaluateJavascript(IMAGE_ONLY_JS, null) } catch (_: Throwable) { }
                    view.postDelayed({
                        if (imageMode) try { view.evaluateJavascript(IMAGE_ONLY_JS, null) } catch (_: Throwable) { }
                    }, 3500)
                }
                if (AdBlockHub.enabled) {
                    try { view.evaluateJavascript(AdBlockHub.cosmeticJs, null) } catch (_: Throwable) { }
                    if (AdBlockHub.aggressive) {
                        try { view.evaluateJavascript(AdBlockHub.aggressiveJs, null) } catch (_: Throwable) { }
                    }
                }
                host?.let { c -> try { TranslateHub.autoTranslate(view, c) } catch (_: Throwable) { } }
                // 单窗口模式: 把 target=_blank 链接改成当前页跳转, 避免点了没反应
                view.evaluateJavascript(
                    "(function(){try{var a=document.querySelectorAll('a[target=_blank]');" +
                        "for(var i=0;i<a.length;i++)a[i].target='_self';}catch(e){}})();", null
                )
                try { LanzouAuto.onPageFinished(view, u) } catch (_: Throwable) { }
                saveTabs()
            }

            override fun doUpdateVisitedHistory(view: WebView, u: String?, isReload: Boolean) {
                refreshHistory(view)
            }

            override fun onReceivedHttpError(
                view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse
            ) {
                android.util.Log.e("endpixweb",
                    "HTTP ${errorResponse.statusCode} ${request.url}")
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                if (!request.isForMainFrame) {
                    android.util.Log.e("endpixweb",
                        "ERR ${err.errorCode} ${err.description} ${request.url}")
                }
                if (request.isForMainFrame) {
                    if (isActiveTab(view)) loading = false
                    val rh = hostOf(request.url?.toString())
                    // Pinterest 镜像失效时自动换下一个, 不把错误页甩给用户
                    if (isPinMirrorHost(rh) && pinFallbackDepth < 6) {
                        pinFallbackDepth++
                        val q = request.url?.getQueryParameter("q")
                        if (pinMirrors.contains(rh)) {
                            val next = (pinMirrors.indexOf(rh) + 1) % pinMirrors.size
                            selectPinMirror(next)
                            view.loadUrl(pinMirrorUrl(pinMirrors[next], q))
                        } else {
                            val next = painterestMirrors.indexOf(rh) + 1
                            if (next in painterestMirrors.indices) {
                                view.loadUrl(painterestUrl(painterestMirrors[next], q))
                            } else {
                                view.loadUrl(pinMirrorUrl(pinMirrorName(), q))
                            }
                        }
                        return
                    }
                    error = "无法打开网页（${err.description}）"
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, err: android.net.http.SslError) {
                handler.cancel()
                if (isActiveTab(view)) {
                    loading = false
                    error = "SSL 证书错误，已阻止加载"
                }
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                if (!isActiveTab(view)) return
                if (p == 100 || kotlin.math.abs(p - progress) >= 5) progress = p
                if (p >= 100) loading = false
            }

            override fun onReceivedTitle(view: WebView, t: String?) {
                val vu = view.url
                val shown = if (vu.isNullOrBlank() || (!vu.startsWith("http://") && !vu.startsWith("https://"))) "浏览器" else (t ?: "")
                tabOf(view)?.title = shown
                if (isActiveTab(view)) title = shown
            }

            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                android.util.Log.e("endpixweb",
                    "${m.messageLevel()} ${m.message()} @${m.sourceId()}:${m.lineNumber()}")
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?, callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, false, false)
            }
        }

        wv.setDownloadListener(DownloadListener { u, ua, cd, mime, _ ->
            try {
                val fn = URLUtil.guessFileName(u, cd, mime)
                if (LanzouAuto.shouldTakeDownload()) {
                    PdfHub.open(ctx, u, ua, referer = LanzouAuto.currentReferer())
                    return@DownloadListener
                }
                if (mime == "application/pdf" || PdfHub.isPdfUrl(u) || fn.lowercase().endsWith(".pdf")) {
                    PdfHub.open(ctx, u, ua)
                    return@DownloadListener
                }
                val req = DownloadManager.Request(Uri.parse(u))
                CookieManager.getInstance().getCookie(u)?.takeIf { it.isNotBlank() }?.let {
                    req.addRequestHeader("Cookie", it)
                }
                req.addRequestHeader("User-Agent", ua)
                req.setMimeType(mime)
                req.setTitle(fn)
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(ctx, "已开始下载：$fn", Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
                Toast.makeText(ctx, "下载失败", Toast.LENGTH_SHORT).show()
            }
        })
        CookieManager.getInstance().setAcceptCookie(true)
        AdBlockHub.applyPrivacy(wv)
        return wv
    }
}

@Composable
fun BrowserScreen() {
    BackHandler(enabled = BrowserHub.visible) {
        // PDF 界面: 关闭后回浏览器主页, 不要把应用一起退掉
        if (PdfHub.visible) {
            PdfHub.close()
            BrowserHub.goHome()
            return@BackHandler
        }
        val wv = BrowserHub.webView
        if (wv?.canGoBack() == true) wv.goBack() else BrowserHub.close()
    }
    val ctx = LocalContext.current
    var panel by remember { mutableStateOf(false) }
    var pinDialog by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0E13))) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(84.dp))   // 让出顶部标签栏
            Spacer(Modifier.height(48.dp))   // 给悬浮工具栏留位
            Box(Modifier.weight(1f)) {
                if (PdfHub.visible) {
                    PdfInTab(ctx)
                } else AndroidView(
                    factory = { c -> BrowserHub.obtainHost(c) },
                    modifier = Modifier.fillMaxSize(),
                    update = { host -> BrowserHub.syncHost(host) },
                    onRelease = { host -> BrowserHub.releaseHost(host) },
                )
                if (!PdfHub.visible && BrowserHub.error.isNotEmpty()) {
                    Column(
                        Modifier.fillMaxSize().background(Color(0xF00B0E13)),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("网页打不开", color = Ep.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(BrowserHub.error, color = Ep.Sub, fontSize = 10.sp)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BrowserBtn("重试") { BrowserHub.reload() }
                            BrowserBtn("返回") {
                                val wv = BrowserHub.webView
                                if (wv?.canGoBack() == true) wv.goBack() else BrowserHub.close()
                            }
                        }
                    }
                }
            }
        }
        // 工具栏作为覆盖层画在 WebView 之上, 避免原生 WebView 盖住 Compose 顶部栏
        BrowserTopBar(
            Modifier.align(Alignment.TopCenter).padding(top = 84.dp),
            menuOpen = panel, onMenu = { panel = !panel }
        )
        if (panel) BrowserPanel(ctx, onClose = { panel = false }, onPinSearch = {
            panel = false
            pinDialog = true
        })
    }
    if (pinDialog) PinSearchDialog(
        host = BrowserHub.pinMirrorName(),
        onDismiss = { pinDialog = false },
        onSearch = { q -> pinDialog = false; BrowserHub.pinSearch(q) },
        onPainterest = { q -> pinDialog = false; BrowserHub.painterestSearch(q) }
    )
    if (ProxyHub.dialogOpen) ProxyDialog(ctx)
    BrowserHub.imageMenu?.let { m ->
        Dialog(onDismissRequest = { BrowserHub.dismissImageMenu() }) {
            Column(
                Modifier.width(286.dp)
                    .background(Ep.Panel, RoundedCornerShape(7.dp))
                    .border(1.dp, Ep.Line, RoundedCornerShape(7.dp))
                    .padding(10.dp)
            ) {
                Text("图片", color = Ep.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    m.url.take(72),
                    color = Ep.Sub, fontSize = 8.sp, maxLines = 1,
                    modifier = Modifier.padding(top = 3.dp, bottom = 8.dp)
                )
                BrowserImageAction("下载图片") { BrowserHub.downloadImage(ctx, m) }
                Spacer(Modifier.height(6.dp))
                BrowserImageAction("应用分享（图片）") { BrowserHub.shareImage(ctx, m) }
                Spacer(Modifier.height(6.dp))
                BrowserImageAction("分享链接") { BrowserHub.shareLink(ctx, m) }
                Spacer(Modifier.height(6.dp))
                BrowserImageAction("替换当前参考图") { BrowserHub.useImageAsReference(ctx, m) }
                Spacer(Modifier.height(6.dp))
                BrowserImageAction("取消") { BrowserHub.dismissImageMenu() }
            }
        }
    }
}

@Composable
private fun BrowserImageAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(40.dp)
            .background(Ep.Panel2, RoundedCornerShape(4.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
            .clickable(interactionSource = null, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(label, color = Ep.Text, fontSize = 12.sp) }
}

@Composable
private fun BrowserTopBar(modifier: Modifier = Modifier, menuOpen: Boolean, onMenu: () -> Unit) {
    var addr by remember { mutableStateOf(TextFieldValue("")) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    LaunchedEffect(BrowserHub.url) {
        if (!focused) addr = TextFieldValue(BrowserHub.url)
    }
    LaunchedEffect(focused) {
        if (focused) {
            withFrameNanos { }
            addr = addr.copy(selection = TextRange(0, addr.text.length))
        }
    }
    Column(modifier.fillMaxWidth().background(Ep.Panel)) {
        Row(
            Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BrowserIcon("‹", BrowserHub.canBack) { BrowserHub.goBack() }
            BrowserIcon("›", BrowserHub.canForward) { BrowserHub.goForward() }
            Spacer(Modifier.width(3.dp))
            if (BrowserHub.imageMode) {
                // 只看图片: 地址栏锁死(不能输入/复制网址)
                Row(
                    Modifier.weight(1f).height(32.dp)
                        .background(Ep.Panel2, RoundedCornerShape(4.dp))
                        .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🔒", fontSize = 11.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("图片模式 · 网址已锁定", color = Ep.Sub, fontSize = 11.sp,
                        modifier = Modifier.weight(1f))
                    Text("退出", color = Ep.Accent, fontSize = 11.sp,
                        modifier = Modifier.clickable(interactionSource = null, indication = null) {
                            BrowserHub.exitImageMode()
                        })
                }
            } else Box(
                Modifier.weight(1f).height(32.dp)
                    .background(Ep.Panel2, RoundedCornerShape(4.dp))
                    .border(1.dp, if (focused) Ep.Accent else Ep.Line, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = addr,
                    onValueChange = { addr = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Ep.Text, fontSize = 11.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        focus.clearFocus()
                        BrowserHub.navigate(addr.text)
                    }),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
                )
                if (addr.text.isEmpty() && !focused) {
                    Text("搜索或输入网址", color = Ep.Sub, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.width(3.dp))
            BrowserIcon(if (BrowserHub.loading) "×" else "⟳", true) {
                if (BrowserHub.loading) BrowserHub.stop() else BrowserHub.reload()
            }
            BrowserIcon("☰", true, active = menuOpen, onClick = onMenu)
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (BrowserHub.loading || BrowserHub.progress in 1..99) {
                Box(
                    Modifier.fillMaxWidth((BrowserHub.progress.coerceIn(0, 100)) / 100f)
                        .height(2.dp).background(Ep.Accent)
                )
            }
        }
    }
}

// 浏览器功能面板: 搜索 / Pinterest / 网络 / 工具 分组, 取代原来一长串下拉菜单
@Composable
private fun BrowserPanel(ctx: Context, onClose: () -> Unit, onPinSearch: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0x77000000))
            .clickable(interactionSource = null, indication = null) { onClose() }
    ) {
        Column(
            Modifier.align(Alignment.TopEnd).padding(top = 132.dp, end = 8.dp).width(300.dp)
                .background(Ep.Panel, RoundedCornerShape(6.dp))
                .border(1.dp, Ep.Line, RoundedCornerShape(6.dp))
                .clickable(interactionSource = null, indication = null) { }
                .heightIn(max = 600.dp)
                .verticalScroll(rememberScrollState())
                .padding(10.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("浏览器功能", color = Ep.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(24.dp).clickable(interactionSource = null, indication = null) { onClose() },
                    contentAlignment = Alignment.Center
                ) { Text("×", color = Ep.Sub, fontSize = 16.sp) }
            }
            PanelLabel("搜索")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Bing" to 1, "Google" to 2).forEach { (name, i) ->
                    val on = BrowserHub.engine == i
                    Box(
                        Modifier.weight(1f).height(30.dp)
                            .background(if (on) Ep.Line else Ep.Panel2, RoundedCornerShape(4.dp))
                            .border(1.dp, if (on) Ep.Accent else Ep.Line, RoundedCornerShape(4.dp))
                            .clickable(interactionSource = null, indication = null) {
                                BrowserHub.setSearchEngine(i)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(name, color = if (on) Ep.Accent else Ep.Text, fontSize = 11.sp)
                    }
                }
            }

            PanelLabel("隐私")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AdChip(Modifier.weight(1f), if (AdBlockHub.enabled) "去广告 开" else "去广告 关", AdBlockHub.enabled) {
                    AdBlockHub.setEnabled(ctx, !AdBlockHub.enabled)
                }
                AdChip(Modifier.weight(1f), if (AdBlockHub.privacy) "隐私强化 开" else "隐私强化 关", AdBlockHub.privacy) {
                    AdBlockHub.setPrivacy(ctx, !AdBlockHub.privacy, BrowserHub.webView)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AdChip(Modifier.weight(1f), if (AdBlockHub.aggressive) "强力清广告 开" else "强力清广告 关", AdBlockHub.aggressive) {
                    AdBlockHub.setAggressive(ctx, !AdBlockHub.aggressive)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已拦截 ${AdBlockHub.blockedCount} · 规则 ${AdBlockHub.ruleCount}",
                    color = Ep.Sub, fontSize = 9.sp, modifier = Modifier.weight(1f)
                )
                Text(
                    if (AdBlockHub.updating) "更新中…" else "更新规则",
                    color = Ep.Accent, fontSize = 9.sp,
                    modifier = Modifier.clickable(interactionSource = null, indication = null) {
                        AdBlockHub.update(ctx) { msg -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
                    }
                )
            }

            PanelLabel("翻译")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AdChip(Modifier.weight(1f), if (TranslateHub.auto) "自动翻译 开" else "自动翻译 关", TranslateHub.auto) {
                    TranslateHub.setAuto(ctx, !TranslateHub.auto)
                }
                Box(
                    Modifier.weight(1f).height(30.dp)
                        .background(Ep.Panel2, RoundedCornerShape(4.dp))
                        .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
                        .clickable(interactionSource = null, indication = null) {
                            BrowserHub.webView?.let { TranslateHub.translateSmart(it, ctx) }
                        },
                    contentAlignment = Alignment.Center
                ) { Text("翻译此页", color = Ep.Text, fontSize = 10.sp) }
            }
            if (TranslateHub.busy || TranslateHub.note.isNotEmpty()) {
                Text(
                    TranslateHub.note,
                    color = Ep.Sub, fontSize = 9.sp, modifier = Modifier.padding(top = 4.dp)
                )
            }

            PanelLabel("网络")
            PanelRow(
                if (ProxyHub.isProxyActive()) "代理：开 127.0.0.1:${ProxyHub.activePort()}" else "代理（Xray）…",
                accent = true
            ) {
                ProxyHub.init(ctx)
                ProxyHub.dialogOpen = true
                onClose()
            }

            PanelLabel("工具")
            PanelRow("主页") { BrowserHub.goHome(); onClose() }
            PanelRow("清除缓存") {
                BrowserHub.webView?.clearCache(true)
                BrowserHub.webView?.clearFormData()
                Toast.makeText(ctx, "缓存已清除", Toast.LENGTH_SHORT).show()
                onClose()
            }
            PanelRow("用系统浏览器打开") {
                val u = BrowserHub.url
                if (u.isNotBlank()) {
                    try {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Throwable) { }
                }
                onClose()
            }
            PanelRow("关闭浏览器", danger = true) { BrowserHub.close() }
        }
    }
}

@Composable
private fun PanelLabel(text: String) {
    Text(text, color = Ep.Sub, fontSize = 9.sp, modifier = Modifier.padding(top = 10.dp, bottom = 3.dp))
}

@Composable
private fun AdChip(modifier: Modifier = Modifier, label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        modifier.height(30.dp)
            .background(if (on) Ep.Accent.copy(alpha = 0.16f) else Ep.Panel2, RoundedCornerShape(4.dp))
            .border(1.dp, if (on) Ep.Accent else Ep.Line, RoundedCornerShape(4.dp))
            .clickable(interactionSource = null, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(label, color = if (on) Ep.Accent else Ep.Text, fontSize = 10.sp) }
}

@Composable
private fun PanelRow(
    label: String,
    sub: Boolean = false,
    accent: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        Modifier.fillMaxWidth().height(34.dp)
            .clickable(interactionSource = null, indication = null) { onClick() },
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            label,
            color = when {
                danger -> Color(0xFFE5534B)
                accent -> Ep.Accent
                sub -> Ep.Sub
                else -> Ep.Text
            },
            fontSize = if (sub) 10.sp else 11.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun PinSearchDialog(
    host: String,
    onDismiss: () -> Unit,
    onSearch: (String) -> Unit,
    onPainterest: (String) -> Unit
) {
    var q by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(320.dp)
                .background(Ep.Panel, RoundedCornerShape(6.dp))
                .border(1.dp, Ep.Line, RoundedCornerShape(6.dp))
                .padding(14.dp)
        ) {
            Text("Pinterest 搜图（免翻墙）", color = Ep.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text("经镜像 $host 抓取，图片走镜像代理", color = Ep.Sub, fontSize = 10.sp)
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth().height(34.dp)
                    .background(Ep.Panel2, RoundedCornerShape(4.dp))
                    .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = q,
                    onValueChange = { q = it.take(60) },
                    singleLine = true,
                    textStyle = TextStyle(color = Ep.Text, fontSize = 12.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { if (q.isNotBlank()) onSearch(q) }),
                    modifier = Modifier.fillMaxWidth()
                )
                if (q.isEmpty()) Text("关键词，例如 pixel art / 像素画", color = Ep.Sub, fontSize = 11.sp)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrowserBtn("取消") { onDismiss() }
                BrowserBtn("Painterest") { if (q.isNotBlank()) onPainterest(q) }
                BrowserBtn("搜索") { if (q.isNotBlank()) onSearch(q) }
            }
        }
    }
}

@Composable
private fun BrowserIcon(label: String, enabled: Boolean, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp)
            .background(if (active) Ep.Line else Ep.Panel2, RoundedCornerShape(3.dp))
            .border(1.dp, if (active) Ep.Accent else Color.Transparent, RoundedCornerShape(3.dp))
            .clickable(interactionSource = null, indication = null, enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (enabled) Ep.Text else Ep.Sub, fontSize = 15.sp)
    }
}

@Composable
private fun BrowserBtn(label: String, onClick: () -> Unit) {
    Box(
        Modifier.height(30.dp).background(Ep.Panel2, RoundedCornerShape(4.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
            .clickable(interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Ep.Text, fontSize = 11.sp) }
}

/** 浏览器图片 → 参考图桥: MainActivity 注册回调, UI 层不依赖 Activity. */
object RefImageBridge {
    @Volatile
    var apply: ((android.graphics.Bitmap) -> Unit)? = null
}
