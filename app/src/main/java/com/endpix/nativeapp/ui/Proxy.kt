package com.endpix.nativeapp.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// ===== Pinterest 代理: 内置 Xray 内核 + 机场订阅(免费公共源/手动) + 只给浏览器走代理 =====
// Xray 为 MPL-2.0; 二进制以 libxray.so 放进 jniLibs, 运行期从 nativeLibraryDir 执行
object ProxyHub {
    data class Node(
        val uri: String,
        val name: String,
        val proto: String,
        val host: String,
        val port: Int
    )

    // 经该节点的实测吞吐(Kbps), 不是线路标称上限; 免费节点会浮动
    data class Speed(val downKbps: Int, val upKbps: Int, val ts: Long)

    val builtinSources = listOf(
        "https://gh-proxy.com/https://raw.githubusercontent.com/0xRadikal/Free-v2ray-Configs/main/top100.txt",
        "https://gh-proxy.com/https://raw.githubusercontent.com/Au1rxx/free-vpn-subscriptions/main/output/v2ray-base64.txt",
        "https://gh-proxy.com/https://raw.githubusercontent.com/peasoft/NoMoreWalls/master/list.txt",
        "https://gh-proxy.com/https://raw.githubusercontent.com/MatinGhanbari/v2ray-configs/main/subscriptions/v2ray/all_sub.txt",
        "https://gh-proxy.com/https://raw.githubusercontent.com/rtwo2/FastNodes/main/sub/top.txt",
        "https://gh-proxy.com/https://raw.githubusercontent.com/rtwo2/FastNodes/main/sub/verified.txt"
    )

    private const val BASE_PORT = 10800
    private const val MAX_NODES = 520

    var nodes by mutableStateOf<List<Node>>(emptyList())
        private set
    var pings by mutableStateOf<Map<Int, Int>>(emptyMap())
        private set
    // 每个节点上次实测的上下行吞吐(按 node.uri 缓存, 重启可恢复)
    var speeds by mutableStateOf<Map<String, Speed>>(emptyMap())
        private set
    var speedTesting by mutableStateOf<Set<String>>(emptySet())
        private set
    // 测速后只保留可用节点: 记录可用下标并落盘, 下次启动直接加载这批
    var goodIdx by mutableStateOf<Set<Int>>(emptySet())
        private set
    // 自动寻找来的订阅源(机场): 联网搜索结果验证后落盘, 参与后续每次抓取
    var autoSourceCount by mutableIntStateOf(0)
        private set
    var discovering by mutableStateOf(false)
        private set
    var autoDiscoverOn by mutableStateOf(true)
        private set
    var selected by mutableIntStateOf(-1)
        private set
    var running by mutableStateOf(false)
        private set
    var enabled by mutableStateOf(false)
        private set
    // 系统 VPN 已连通且有效: 关掉本机订阅代理走 VPN, 避免双重代理
    var vpnActive by mutableStateOf(false)
        private set
    private var vpnWatchStarted = false
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("未启动")
        private set
    var dialogOpen by mutableStateOf(false)
    // 首次解锁条件: 长按代理按钮 10 秒; 解锁后写入 prefs, 之后不再拦
    var unlocked by mutableStateOf(false)
        private set
    var unlockHoldMs by mutableStateOf(0L)   // 当前长按进度(UI 用)
    // 自动连接完成后要打开的地址(为空则打开 Pinterest)
    private var pendingNav: String? = null

    private var host: Context? = null
    private var inited = false
    private var proc: Process? = null
    private var manualSubs: MutableList<String> = mutableListOf()
    private var autoSources: MutableList<String> = mutableListOf()
    private var lastDiscoveryMs = 0L
    private var lastConnectFailed = false
    private val DISCOVER_GAP_MS = 6L * 60 * 60 * 1000
    private val GOOD_TTL_MS = 7L * 24 * 60 * 60 * 1000
    // 可用节点最后可用时间(uri -> 毫秒); 超过 7 天没再可用就从保存列表移除
    private val goodSince = HashMap<String, Long>()
    private var coreLog = StringBuilder()
    private val mainExec = java.util.concurrent.Executor { r ->
        Handler(Looper.getMainLooper()).post(r)
    }

    // 新装/清数据后没有代理缓存: 用打包的默认可用节点+自动源垫底,
    // 这样"自动连接"不用先跑一遍慢订阅抓取, 直接测这两个节点即可.
    private fun seedDefaultProxyFiles(ctx: Context) {
        try {
            val dir = ctx.filesDir
            val good = File(dir, "proxy_good.txt")
            val nodesFile = File(dir, "proxy_nodes.txt")
            if ((good.exists() && good.length() > 0) || (nodesFile.exists() && nodesFile.length() > 0)) return
            val uris = ctx.assets.open("default_proxy_good.txt").bufferedReader().use { it.readLines() }
                .map { it.trim() }.filter { it.contains("://") }
            if (uris.isNotEmpty()) {
                val now = System.currentTimeMillis()
                good.writeText(uris.joinToString("\n") { "$it\t$now" } + "\n")
            }
            val auto = ctx.assets.open("default_proxy_auto_sources.txt").bufferedReader().use { it.readText() }
            if (auto.isNotBlank()) File(dir, "proxy_auto_sources.txt").writeText(auto)
        } catch (_: Throwable) { }
    }

    fun init(ctx: Context) {
        host = ctx
        startVpnWatch(ctx.applicationContext)
        if (inited) return
        inited = true
        seedDefaultProxyFiles(ctx)
        val gf = File(ctx.filesDir, "proxy_good.txt")
        val now = System.currentTimeMillis()
        goodSince.clear()
        if (gf.exists() && gf.length() > 0) {
            // 保存格式: uri<TAB>最后可用毫秒; 旧文件没有时间列, 用文件修改时间兜底
            val legacyTs = gf.lastModified().coerceAtLeast(1L)
            val kept = ArrayList<Node>()
            for (line in gf.readLines()) {
                val uri = line.substringBefore('\t').trim()
                if (uri.isEmpty()) continue
                val ts = line.substringAfter('\t', "").toLongOrNull() ?: legacyTs
                if (now - ts > GOOD_TTL_MS) continue   // 超过 7 天没可用: 直接丢弃
                val n = parseLine(uri) ?: continue
                if (kept.none { nodeKey(it) == nodeKey(n) }) {
                    kept.add(n)
                    goodSince[n.uri] = ts
                }
            }
            nodes = kept.take(MAX_NODES)
            if (nodes.isNotEmpty()) goodIdx = nodes.indices.toSet()
        } else {
            val f = nodeFile(ctx)
            if (f.exists()) nodes = f.readLines().mapNotNull { parseLine(it) }.take(MAX_NODES)
        }
        loadSpeeds(ctx)
        val p = ctx.getSharedPreferences("endpix", Context.MODE_PRIVATE)
        manualSubs = (p.getString("proxySubs", "") ?: "").split('\n')
            .filter { it.isNotBlank() }.toMutableList()
        autoSources = try {
            File(ctx.filesDir, "proxy_auto_sources.txt").readLines()
                .map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
        } catch (_: Throwable) { mutableListOf() }
        autoSourceCount = autoSources.size
        autoDiscoverOn = p.getBoolean("proxyAutoDiscover", true)
        lastDiscoveryMs = p.getLong("proxyLastDiscovery", 0L)
        lastConnectFailed = p.getBoolean("proxyLastConnectFailed", false)
        selected = p.getInt("proxySel", -1).coerceIn(-1, nodes.size - 1)
        unlocked = p.getBoolean("proxyUnlocked", false)
        // 上次进程可能留下 WebView 代理覆盖(指向已退出的内核), 新进程先清掉, 避免所有网页都卡死
        running = false
        enabled = false
        applyBrowserProxy(false, 0)
        // 备用自愈: 上次一键连接确认全部机场不可用、且距上次搜索超过 6 小时 → 启动后自动找新源
        if (autoDiscoverOn && goodIdx.isEmpty() && lastConnectFailed &&
            System.currentTimeMillis() - lastDiscoveryMs > DISCOVER_GAP_MS
        ) {
            Thread {
                try { Thread.sleep(6000) } catch (_: Throwable) { }
                if (busy || nodes.isNotEmpty() && goodIdx.isNotEmpty()) return@Thread
                autoDiscoverHeal(ctx)
            }.start()
        }
    }

    fun manualSubList(): List<String> = manualSubs

    fun addManualSub(url: String) {
        val u = url.trim()
        if (u.isEmpty()) return
        if (!manualSubs.contains(u)) {
            manualSubs.add(u)
            saveSubs()
        }
    }

    fun removeManualSub(url: String) {
        manualSubs.remove(url)
        saveSubs()
    }

    private fun saveSubs() {
        host?.getSharedPreferences("endpix", Context.MODE_PRIVATE)
            ?.edit()?.putString("proxySubs", manualSubs.joinToString("\n"))?.apply()
    }

    private fun nodeFile(ctx: Context) = File(ctx.filesDir, "proxy_nodes.txt")

    fun isProxyActive(): Boolean = enabled && running && selected >= 0

    fun activePort(): Int = if (isProxyActive()) BASE_PORT + selected else 0

    // ---------- 订阅 ----------

    // 同步抓订阅(给一键连接用), 返回去重后的节点表。
    // fresh=true: 禁缓存 + 给源 URL 加时间戳, 保证"换下一批"拿到的是这次新拉的订阅内容。
    // exclude: 本批已经试过的 host:port, 下一批跳过它们, 从而真正轮换到后面的节点。
    private fun nodeKey(n: Node): String = n.host.lowercase() + ":" + n.port

    private fun cacheBust(u: String): String {
        val sep = if (u.contains('?')) '&' else '?'
        return u + sep + "t=" + System.currentTimeMillis()
    }

    private fun fetchSync(ctx: Context, fresh: Boolean = false, exclude: Set<String> = emptySet()): List<Node> {
        val urls = builtinSources.map { if (fresh) cacheBust(it) else it } +
            autoSources.map { if (fresh) cacheBust(it) else it } + manualSubs
        // 多源并发拉取: 逐源串行时, 一个超时源会拖 20 秒, 40 个自动源能把一批拖到十分钟
        val fetched = arrayOfNulls<List<Node>>(urls.size)
        val pool = Executors.newFixedThreadPool(minOf(8, urls.size.coerceAtLeast(1)))
        for ((i, u) in urls.withIndex()) {
            pool.execute {
                fetched[i] = try {
                    decodeSub(httpGet(u, noCache = fresh)).mapNotNull { parseLine(it) }
                } catch (_: Throwable) { emptyList() }
            }
        }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        val perSource = fetched.filterNotNull()
        val uniq = LinkedHashMap<String, Node>()
        val perSrc = IntArray(perSource.size)
        var round = 0
        var progressed = true
        // 池子比单批上限大, 第二/第三批才有"后面的新节点"可换; 每源最多 200 个
        val poolCap = MAX_NODES * 4
        while (uniq.size < poolCap && progressed) {
            progressed = false
            for ((si, list) in perSource.withIndex()) {
                if (round >= list.size || perSrc[si] >= 200) continue
                val n = list[round]
                val k = nodeKey(n)
                if (!uniq.containsKey(k)) {
                    uniq[k] = n
                    perSrc[si]++
                    progressed = true
                    if (uniq.size >= poolCap) break
                }
            }
            round++
        }
        val filtered = uniq.entries.asSequence()
            .filter { it.key !in exclude }
            .map { it.value }
            .toList()
        return filtered.take(MAX_NODES)
    }

    private fun persistNodes(ctx: Context, list: List<Node>) {
        try {
            nodeFile(ctx).writeText(list.joinToString("" + 10.toChar()) { it.uri })
        } catch (_: Throwable) { }
    }

    // ---------- 自动寻找新机场(订阅源) ----------
    // 思路: GitHub 仓库搜索(按最近更新) → 抓各仓库 README → 提取订阅链接 → 逐个验证能否解析出 ≥3 个节点
    // → 验证通过的存进 proxy_auto_sources.txt, 以后每次抓订阅都带上; 全程 gh-proxy 镜像, 不需要先有代理。
    private val DISCOVER_QUERIES = listOf(
        "free v2ray subscription",
        "v2ray free nodes",
        "free vmess subscription",
        "v2ray configs subscription"
    )

    private fun normSource(u: String): String = u.trim().trimEnd('/')

    private fun persistAutoSources() {
        val ctx = host ?: return
        try {
            File(ctx.filesDir, "proxy_auto_sources.txt").writeText(autoSources.joinToString("\n"))
        } catch (_: Throwable) { }
        mainExec.execute { autoSourceCount = autoSources.size }
    }

    fun setAutoDiscover(on: Boolean) {
        autoDiscoverOn = on
        host?.getSharedPreferences("endpix", Context.MODE_PRIVATE)
            ?.edit()?.putBoolean("proxyAutoDiscover", on)?.apply()
    }

    fun clearAutoSources() {
        if (busy) return
        autoSources.clear()
        persistAutoSources()
        status = "已清空自动源（内置源仍保留）"
    }

    private fun httpGetShort(u: String, ct: Int, rt: Int): String {
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout = ct
        conn.readTimeout = rt
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun normalizeCandidate(u0: String): String? {
        var u = u0.trim().trimEnd('.', ',', ';', ')', ']', '}', '"', '\'')
        if (!u.startsWith("http://") && !u.startsWith("https://")) return null
        // github.com/user/repo/blob/branch/path → raw.githubusercontent.com
        Regex("^https?://github\\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+)$").matchEntire(u)?.let { m ->
            val g = m.groupValues
            u = "https://raw.githubusercontent.com/${g[1]}/${g[2]}/${g[3]}/${g[4]}"
        }
        val l = u.lowercase()
        if (l.contains("shields.io") || l.contains("badgen") || l.contains("api.github.com") ||
            l.contains("/issues") || l.contains("/stargazers") || l.contains("/forks") ||
            l.contains("/releases") || l.contains("/actions") || l.contains("/sponsors") ||
            l.contains("/login") || l.contains("/blob/") ||
            l.endsWith(".svg") || l.endsWith(".png") || l.endsWith(".jpg") ||
            l.endsWith(".jpeg") || l.endsWith(".gif") || l.endsWith(".md")
        ) return null
        return u
    }

    private fun looksLikeSub(u: String): Boolean {
        val l = u.lowercase()
        if (l.contains("raw.githubusercontent.com")) return true
        val hasExt = l.endsWith(".txt") || l.endsWith(".yaml") || l.endsWith(".yml") ||
            l.endsWith(".json") || l.endsWith(".conf") || l.endsWith(".base64") ||
            l.endsWith(".sub") || l.endsWith(".ini")
        val kw = l.contains("sub") || l.contains("node") || l.contains("v2ray") ||
            l.contains("vmess") || l.contains("proxy") || l.contains("config")
        return hasExt || (kw && l.contains("gh-proxy.com"))
    }

    private fun variantUrls(u: String): List<String> {
        val out = ArrayList<String>()
        if (u.startsWith("https://gh-proxy.com/")) {
            out.add(u)
            out.add(u.removePrefix("https://gh-proxy.com/"))
        } else {
            if (u.contains("raw.githubusercontent.com")) out.add("https://gh-proxy.com/$u")
            out.add(u)
            if (!u.contains("raw.githubusercontent.com")) out.add("https://gh-proxy.com/$u")
        }
        return out.distinct()
    }

    // 返回新找到的源数量; deadline 到点就收手, 避免一键连接时卡太久
    private fun discoverSourcesBlocking(ctx: Context, deadlineMs: Long, statusCb: (String) -> Unit): Int {
        val seen = HashSet<String>()
        builtinSources.forEach { seen.add(normSource(it)) }
        manualSubs.forEach { seen.add(normSource(it)) }
        autoSources.forEach { seen.add(normSource(it)) }
        val repos = LinkedHashMap<String, String>()   // full_name -> default_branch
        val apiMirrors = listOf("https://gh-proxy.com/", "https://ghproxy.net/", "https://mirror.ghproxy.com/")
        for (q in DISCOVER_QUERIES) {
            if (System.currentTimeMillis() > deadlineMs) break
            val path = "https://api.github.com/search/repositories?q=" +
                URLEncoder.encode(q, "UTF-8") + "&sort=updated&order=desc&per_page=6"
            var body: String? = null
            for (m in apiMirrors) {
                body = try { httpGetShort(m + path, 6000, 12000) } catch (_: Throwable) { null }
                if (!body.isNullOrBlank() && body!!.trimStart().startsWith("{")) break
                body = null
            }
            if (body == null) continue
            try {
                val items = JSONObject(body).optJSONArray("items") ?: continue
                for (i in 0 until items.length()) {
                    val it = items.optJSONObject(i) ?: continue
                    val full = it.optString("full_name")
                    val br = it.optString("default_branch", "main").ifBlank { "main" }
                    if (full.isNotBlank()) repos[full] = br
                }
            } catch (_: Throwable) { }
        }
        if (repos.isEmpty()) {
            statusCb("搜索不到新机场仓库（网络被拦？）")
            return 0
        }
        statusCb("找到 ${repos.size} 个候选仓库，检查订阅链接…")
        val cands = LinkedHashSet<String>()
        var checked = 0
        for ((full, br) in repos) {
            if (System.currentTimeMillis() > deadlineMs || checked >= 18 || cands.size >= 60) break
            checked++
            var text: String? = null
            for (rn in listOf("README.md", "readme.md", "README_EN.md", "README-en.md")) {
                text = try {
                    httpGetShort("https://gh-proxy.com/https://raw.githubusercontent.com/$full/$br/$rn", 6000, 10000)
                } catch (_: Throwable) { null }
                if (!text.isNullOrBlank()) break
            }
            if (text.isNullOrBlank()) continue
            for (m in Regex("https?://[^\\s\"'<>\\)\\]]+").findAll(text)) {
                val u = normalizeCandidate(m.value) ?: continue
                if (seen.contains(normSource(u))) continue
                if (!looksLikeSub(u)) continue
                cands.add(u)
                if (cands.size >= 60) break
            }
        }
        if (cands.isEmpty()) {
            statusCb("仓库里没有发现订阅链接")
            return 0
        }
        statusCb("验证 ${cands.size} 个候选订阅…")
        val valid = ArrayList<Pair<String, Int>>()
        for (u in cands) {
            if (System.currentTimeMillis() > deadlineMs) break
            var nodesN = 0
            for (v in variantUrls(u)) {
                val body = try { httpGetShort(v, 6000, 10000) } catch (_: Throwable) { continue }
                val n = try { decodeSub(body).count { parseLine(it) != null } } catch (_: Throwable) { 0 }
                if (n > nodesN) nodesN = n
                if (nodesN >= 6) break
            }
            // 备用源宁缺毋滥: 至少能解析出 6 个节点才算数; 最后按节点数取质量最好的几个
            if (nodesN >= 6) valid.add(u to nodesN)
        }
        valid.sortByDescending { it.second }
        var added = 0
        for ((u, n) in valid) {
            if (autoSources.size >= 40 || added >= 6) break
            val ns = normSource(u)
            // 同仓库同路径只留一份(不同镜像前缀算重复)
            val key = ns.substringAfter("raw.githubusercontent.com/", ns).trimEnd('/')
            if (autoSources.any { old ->
                    old.substringAfter("raw.githubusercontent.com/", old).trimEnd('/') == key
                }
            ) continue
            autoSources.add(ns)
            seen.add(ns)
            added++
        }
        if (added > 0) {
            statusCb("验证通过 ${valid.size} 个，取节点最多的 $added 个加入自动源")
            persistAutoSources()
            lastDiscoveryMs = System.currentTimeMillis()
            host?.getSharedPreferences("endpix", Context.MODE_PRIVATE)
                ?.edit()?.putLong("proxyLastDiscovery", lastDiscoveryMs)?.apply()
        }
        return added
    }

    // 手动按钮: 找源 + 刷新节点
    fun discoverAndFetch(ctx: Context) {
        if (busy) return
        busy = true
        discovering = true
        status = "正在自动寻找新机场…"
        Thread {
            try {
                val added = discoverSourcesBlocking(ctx, System.currentTimeMillis() + 150_000) { m ->
                    mainExec.execute { status = m }
                }
                val list = try { fetchSync(ctx, fresh = true) } catch (_: Throwable) { emptyList() }
                persistNodes(ctx, list)
                mainExec.execute {
                    nodes = list
                    pings = emptyMap()
                    goodIdx = emptySet()
                    selected = -1
                    autoSourceCount = autoSources.size
                    status = when {
                        added > 0 -> "自动找到 $added 个新机场，共 ${list.size} 个节点"
                        list.isNotEmpty() -> "没找到新机场，订阅刷新为 ${list.size} 个节点"
                        else -> "自动寻找失败：网络不可达"
                    }
                }
            } catch (t: Throwable) {
                mainExec.execute { status = "自动寻找失败：${t.message}".take(120) }
            } finally {
                mainExec.execute { discovering = false; busy = false }
            }
        }.start()
    }

    // 启动时自愈: 没有可用节点才跑, 只找源+刷新, 不自动起内核
    private fun autoDiscoverHeal(ctx: Context) {
        if (busy) return
        busy = true
        discovering = true
        mainExec.execute { status = "没有可用节点，自动寻找新机场…" }
        try {
            val added = discoverSourcesBlocking(ctx, System.currentTimeMillis() + 90_000) { m ->
                mainExec.execute { status = m }
            }
            val list = try { fetchSync(ctx, fresh = true) } catch (_: Throwable) { emptyList() }
            persistNodes(ctx, list)
            mainExec.execute {
                nodes = list
                pings = emptyMap()
                goodIdx = emptySet()
                selected = -1
                autoSourceCount = autoSources.size
                status = when {
                    added > 0 -> "自动补充 $added 个新机场，共 ${list.size} 个节点"
                    list.isNotEmpty() -> "订阅已刷新 ${list.size} 个节点"
                    else -> "自动寻找失败：网络不可达"
                }
            }
        } catch (t: Throwable) {
            mainExec.execute { status = "自动寻找失败：${t.message}".take(120) }
        } finally {
            mainExec.execute { discovering = false; busy = false }
        }
    }

    // 调试: 连续拉两批, 验证第二批是否排除了第一批并拿到新节点
    fun debugBatchInfo(ctx: Context, log: File) {
        Thread {
            try {
                val b1 = fetchSync(ctx, fresh = true)
                val k1 = b1.map { nodeKey(it) }.toSet()
                val b2 = fetchSync(ctx, fresh = true, exclude = k1)
                val overlap = b2.count { nodeKey(it) in k1 }
                log.appendText("proxybatch b1=${b1.size} b2=${b2.size} overlap=$overlap\n")
            } catch (t: Throwable) {
                log.appendText("proxybatch err=${t.message}\n")
            }
        }.start()
    }

    fun fetchAll(ctx: Context) {
        if (busy) return
        busy = true
        status = "更新订阅中…"
        Thread {
            val list = try { fetchSync(ctx, fresh = true) } catch (_: Throwable) { emptyList() }
            persistNodes(ctx, list)
            mainExec.execute {
                nodes = list
                pings = emptyMap()
                goodIdx = emptySet()
                selected = -1
                status = if (list.isEmpty()) "订阅为空或网络不可达" else "已更新 ${list.size} 个节点"
                busy = false
            }
        }.start()
    }

    /** 首次解锁: 长按代理按钮满 10 秒后调用; 写入 prefs, 之后永久解锁. */
    fun unlock(ctx: Context) {
        if (unlocked) return
        unlocked = true
        unlockHoldMs = 0L
        status = "代理已解锁"
        try {
            ctx.getSharedPreferences("endpix", Context.MODE_PRIVATE).edit()
                .putBoolean("proxyUnlocked", true).apply()
        } catch (_: Throwable) { }
    }

    // 一键连接: 没节点就先抓订阅 -> 起内核 -> 真实 HTTPS 测速 -> 自动选最快 -> 开浏览器代理
    // 一批全死就自动换一批再试一次, 不用手动选节点
    fun connectAuto(ctx: Context, target: String? = null) {
        if (!unlocked) { status = "请先长按「长按 10 秒解锁代理」"; return }
        if (target != null) pendingNav = target
        if (busy) return
        busy = true
        Thread {
            var connected = false
            try {
                var list = nodes
                var triedFresh = false
                var triedDiscover = false
                var batchNo = 0
                val triedKeys = HashSet<String>()
                fun remember(l: List<Node>) { for (n in l) triedKeys.add(nodeKey(n)) }
                fun pushList(l: List<Node>) {
                    persistNodes(ctx, l)
                    mainExec.execute { nodes = l; pings = emptyMap(); goodIdx = emptySet(); selected = -1 }
                }
                // 换批: 每次强制刷新订阅(禁缓存+时间戳), 并排除本批已测过的 host:port,
                // 这样下一批是订阅里"后面的新节点", 而不是同一份列表再测一遍。
                fun nextBatch(msg: String): List<Node> {
                    batchNo++
                    mainExec.execute { status = msg }
                    var l = fetchSync(ctx, fresh = true, exclude = triedKeys)
                    if (l.isEmpty() && triedKeys.isNotEmpty()) {
                        mainExec.execute { status = "订阅池已试完，重新拉取最新订阅…" }
                        l = fetchSync(ctx, fresh = true)
                    }
                    if (l.isNotEmpty()) {
                        remember(l)
                        pushList(l)
                    }
                    return l
                }
                remember(list)
                while (!connected && batchNo < 4) {
                    if (list.isEmpty()) list = nextBatch("获取订阅…")
                    if (list.isEmpty()) {
                        if (!triedDiscover && autoDiscoverOn) {
                            triedDiscover = true
                            mainExec.execute { status = "订阅不可达，自动寻找新机场…" }
                            discoverSourcesBlocking(ctx, System.currentTimeMillis() + 75_000) { m ->
                                mainExec.execute { status = m }
                            }
                            list = nextBatch("新机场已加入，拉取订阅…")
                            continue
                        }
                        mainExec.execute { status = "订阅不可达，检查网络后重试" }
                        break
                    }
                    mainExec.execute { status = if (batchNo <= 1) "启动内核（第1批）…" else "启动内核（第${batchNo}批）…" }
                    if (startCore(ctx, list)) {
                        val usable = runTest(list, fast = true)
                        if (usable > 0) { connected = true; break }
                    }
                    if (!triedFresh) {
                        triedFresh = true
                        list = nextBatch("这批节点都不可用，刷新订阅并换下一批…")
                        continue
                    }
                    if (!triedDiscover && autoDiscoverOn) {
                        triedDiscover = true
                        mainExec.execute { status = "节点全失效，自动寻找新机场…" }
                        discoverSourcesBlocking(ctx, System.currentTimeMillis() + 75_000) { m ->
                            mainExec.execute { status = m }
                        }
                        list = nextBatch("新机场已加入，刷新订阅换下一批…")
                        continue
                    }
                    break
                }
                lastConnectFailed = !connected
                host?.getSharedPreferences("endpix", Context.MODE_PRIVATE)
                    ?.edit()?.putBoolean("proxyLastConnectFailed", lastConnectFailed)?.apply()
                if (!connected) {
                    val wasAuto = pendingNav != null
                    mainExec.execute {
                        status = "暂时没有可用节点，稍后再试一键连接"
                        if (wasAuto) {
                            dialogOpen = false
                            try {
                                android.widget.Toast.makeText(
                                    host, "自动连接失败：没有可用节点，稍后再试", android.widget.Toast.LENGTH_SHORT
                                ).show()
                            } catch (_: Throwable) { }
                        }
                    }
                }
            } catch (t: Throwable) {
                mainExec.execute { status = "连接失败：${t.message}".take(120) }
            } finally {
                mainExec.execute { busy = false }
            }
        }.start()
    }

    private fun httpGet(u: String, noCache: Boolean = false): String {
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
        if (noCache) {
            conn.useCaches = false
            conn.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
            conn.setRequestProperty("Pragma", "no-cache")
        }
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun decodeSub(body: String): List<String> {
        val text = body.trim()
        if (!text.contains("://")) {
            return try {
                val cleaned = text.filterNot { it == '\n' || it == '\r' || it == ' ' }
                String(Base64.decode(cleaned, Base64.DEFAULT), Charsets.UTF_8)
                    .split('\n').map { it.trim() }
            } catch (_: Throwable) {
                emptyList()
            }
        }
        return text.split('\n').map { it.trim() }
    }

    private fun b64(s: String): ByteArray? = try {
        var b = s
        while (b.length % 4 != 0) b += "="
        Base64.decode(b, Base64.DEFAULT)
    } catch (_: Throwable) {
        try {
            var b = s
            while (b.length % 4 != 0) b += "="
            Base64.decode(b, Base64.URL_SAFE or Base64.NO_WRAP)
        } catch (_: Throwable) { null }
    }

    private fun b64json(s: String): JSONObject? {
        val bytes = b64(s) ?: return null
        return try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseLine(lineRaw: String): Node? {
        val line = lineRaw.trim()
        val n = try {
            when {
                line.startsWith("vmess://") -> parseVmess(line)
                line.startsWith("vless://") -> parseUrlLike(line, "vless")
                line.startsWith("trojan://") -> parseUrlLike(line, "trojan")
                line.startsWith("ss://") -> parseSs(line)
                else -> null
            }
        } catch (_: Throwable) { null } ?: return null
        // 过滤明显的诱饵/广告节点(免费公开源里常见), 避免它们占着列表又被测速批量超时
        val nm = n.name
        if (nm.contains("防范") || nm.contains("境外势力") || nm.contains("渗透") ||
            nm.contains("净网") || nm.contains("非法") || nm.contains("举报")
        ) return null
        if (n.host.isBlank() || n.host.any { it.isWhitespace() } || !n.host.contains('.') && !n.host.contains(':'))
            return null
        return n
    }

    private fun parseVmess(line: String): Node? {
        val j = b64json(line.removePrefix("vmess://")) ?: return null
        val add = j.optString("add")
        val port = j.optString("port").toIntOrNull() ?: 0
        if (add.isBlank() || port <= 0) return null
        val name = j.optString("ps").ifBlank { "$add:$port" }
        return Node(line, name, "vmess", add, port)
    }

    private val urlLike = Regex("^(vless|trojan)://([^@/]+)@\\[?([^\\]/?#]+)\\]?:(\\d+)(\\?[^#]*)?(#.*)?$")

    private fun parseUrlLike(line: String, proto: String): Node? {
        val m = urlLike.find(line) ?: return null
        val h = m.groupValues[3]
        val p = m.groupValues[4].toIntOrNull() ?: 443
        val frag = m.groupValues[6].removePrefix("#")
        val name = try { URLDecoder.decode(frag, "UTF-8") } catch (_: Throwable) { frag }
        return Node(line, name.ifBlank { "$h:$p" }, proto, h, p)
    }

    private fun parseSs(line: String): Node? {
        val body = line.removePrefix("ss://")
        val frag = body.substringAfter('#', "")
        val noFrag = body.substringBefore('#')
        val at = noFrag.lastIndexOf('@')
        if (at < 0) {
            val dec = b64(noFrag)?.toString(Charsets.UTF_8) ?: return null
            val m = Regex("([^:]+):([^@]+)@([^:]+):(\\d+)").find(dec) ?: return null
            return Node(line, frag.ifBlank { "${m.groupValues[3]}:${m.groupValues[4]}" }, "ss", m.groupValues[3], m.groupValues[4].toInt())
        }
        val hostport = noFrag.substring(at + 1)
        val hp = hostport.split(':')
        if (hp.size < 2) return null
        val p = hp.last().toIntOrNull() ?: return null
        return Node(line, frag.ifBlank { "${hp.first()}:$p" }, "ss", hp.first(), p)
    }

    private fun dec(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (_: Throwable) { s }

    private fun queryMap(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.removePrefix("?").split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else try {
                URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
            } catch (_: Throwable) { null }
        }.toMap()
    }

    private fun partsOf(node: Node): Triple<String, String, Map<String, String>> {
        // userInfo, fragment, query
        val m = urlLike.find(node.uri)
        if (m != null) {
            return Triple(m.groupValues[2], m.groupValues[6], queryMap(m.groupValues[5]))
        }
        return Triple("", "", emptyMap())
    }

    // ---------- Xray 配置 ----------

    private fun streamSettings(node: Node): JSONObject? {
        val ss = JSONObject()
        when (node.proto) {
            "vmess" -> {
                val j = b64json(node.uri.removePrefix("vmess://")) ?: return null
                val net0 = j.optString("net", "tcp").ifBlank { "tcp" }
                val net = if (net0 == "raw") "tcp" else net0
                val tls = j.optString("tls").equals("tls", true)
                ss.put("network", net)
                if (tls) {
                    val sni = j.optString("sni").ifBlank { j.optString("host") }.ifBlank { node.host }
                    ss.put("security", "tls")
                    ss.put("tlsSettings", JSONObject().put("serverName", sni.replace("%3d", "=")))
                } else ss.put("security", "none")
                when (net) {
                    "ws" -> {
                        val ws = JSONObject().put("path", j.optString("path", "/").ifBlank { "/" })
                        val hh = j.optString("host")
                        if (hh.isNotBlank()) ws.put("headers", JSONObject().put("Host", hh))
                        ss.put("wsSettings", ws)
                    }
                    "grpc" -> ss.put("grpcSettings", JSONObject().put("serviceName", j.optString("path").trimStart('/')))
                    "tcp" -> if (j.optString("type") == "http") {
                        ss.put("tcpSettings", JSONObject().put("header", JSONObject()
                            .put("type", "http")
                            .put("request", JSONObject()
                                .put("path", JSONArray().put(j.optString("path", "/").ifBlank { "/" }))
                                .put("headers", JSONObject().put("Host", JSONArray().put(j.optString("host", node.host)))))))
                    }
                }
            }
            "vless" -> {
                val (_, _, q) = partsOf(node)
                val net0 = q["type"] ?: "tcp"
                val net = if (net0 == "raw") "tcp" else net0
                val sec = q["security"] ?: "none"
                ss.put("network", net)
                if (sec == "reality") {
                    val rnet = if (net == "tcp") "raw" else net
                    if (rnet != "raw" && rnet != "xhttp" && rnet != "grpc") return null
                    ss.put("network", rnet)
                    val sni = q["sni"] ?: q["host"] ?: node.host
                    ss.put("security", "reality")
                    val rs = JSONObject().put("serverName", sni)
                    q["fp"]?.let { rs.put("fingerprint", it) }
                    q["pbk"]?.let { rs.put("publicKey", it) }
                    q["sid"]?.let { rs.put("shortId", it) }
                    q["spx"]?.let { rs.put("spiderX", it) }
                    ss.put("realitySettings", rs)
                } else if (sec == "tls") {
                    val sni = q["sni"] ?: q["host"] ?: node.host
                    ss.put("security", "tls")
                    val tls = JSONObject().put("serverName", sni)
                    q["fp"]?.let { tls.put("fingerprint", it) }
                    q["alpn"]?.let { tls.put("alpn", JSONArray().put(it)) }
                    ss.put("tlsSettings", tls)
                } else return null   // Xray 26: vless 无 TLS/REALITY 会被拒绝启动
                when (net) {
                    "ws" -> {
                        val ws = JSONObject().put("path", q["path"] ?: "/")
                        q["host"]?.let { ws.put("headers", JSONObject().put("Host", it)) }
                        ss.put("wsSettings", ws)
                    }
                    "grpc" -> ss.put("grpcSettings", JSONObject().put("serviceName", q["serviceName"] ?: ""))
                }
            }
            "trojan" -> {
                val (_, _, q) = partsOf(node)
                val net0 = q["type"] ?: "tcp"
                val net = if (net0 == "raw") "tcp" else net0
                ss.put("network", net)
                ss.put("security", "tls")
                ss.put("tlsSettings", JSONObject().put("serverName", q["sni"] ?: q["host"] ?: node.host))
                if (net == "ws") {
                    val ws = JSONObject().put("path", q["path"] ?: "/")
                    q["host"]?.let { ws.put("headers", JSONObject().put("Host", it)) }
                    ss.put("wsSettings", ws)
                } else if (net == "grpc") {
                    ss.put("grpcSettings", JSONObject().put("serviceName", q["serviceName"] ?: ""))
                }
            }
            "ss" -> ss.put("network", "tcp").put("security", "none")
            else -> return null
        }
        return ss
    }

    private fun outbound(node: Node, tag: String): JSONObject? {
        val ob = JSONObject().put("tag", tag)
        when (node.proto) {
            "vmess" -> {
                val j = b64json(node.uri.removePrefix("vmess://")) ?: return null
                val user = JSONObject()
                    .put("id", j.optString("id"))
                    .put("alterId", j.optString("aid", "0").toIntOrNull() ?: 0)
                    .put("security", j.optString("scy", "auto").ifBlank { "auto" })
                ob.put("protocol", "vmess")
                ob.put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject()
                    .put("address", node.host).put("port", node.port)
                    .put("users", JSONArray().put(user)))))
            }
            "vless" -> {
                val (userInfo, _, q) = partsOf(node)
                val id = dec(userInfo.substringBefore(':'))
                if (id.isBlank()) return null
                val user = JSONObject().put("id", id).put("encryption", "none")
                val netNow = q["type"] ?: "tcp"
                if (netNow == "tcp" || netNow == "raw") {
                    q["flow"]?.takeIf { it.isNotBlank() }?.let { user.put("flow", it) }
                }
                ob.put("protocol", "vless")
                ob.put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject()
                    .put("address", node.host).put("port", node.port)
                    .put("users", JSONArray().put(user)))))
            }
            "trojan" -> {
                val (userInfo, _, _) = partsOf(node)
                val pass = dec(userInfo.substringBefore(':'))
                if (pass.isBlank()) return null
                ob.put("protocol", "trojan")
                ob.put("settings", JSONObject().put("servers", JSONArray().put(JSONObject()
                    .put("address", node.host).put("port", node.port).put("password", pass))))
            }
            "ss" -> {
                val body = node.uri.removePrefix("ss://").substringBefore('#')
                val at = body.lastIndexOf('@')
                if (at < 0) return null
                val raw = body.substring(0, at)
                val methodPass = b64(raw)?.toString(Charsets.UTF_8) ?: raw
                if (!methodPass.contains(':')) return null
                val method = methodPass.substringBefore(':')
                if (!ssCiphers.contains(method)) return null   // Xray 26 已移除 rc4-md5 等旧加密
                ob.put("protocol", "shadowsocks")
                ob.put("settings", JSONObject().put("servers", JSONArray().put(JSONObject()
                    .put("address", node.host).put("port", node.port)
                    .put("method", methodPass.substringBefore(':'))
                    .put("password", methodPass.substringAfter(':')))))
            }
            else -> return null
        }
        // streamSettings 为空说明参数不被 Xray 接受(如 vless 无 TLS/REALITY), 整条丢弃
        ob.put("streamSettings", streamSettings(node) ?: return null)
        return ob
    }

    private val ssCiphers = setOf(
        "aes-128-gcm", "aes-256-gcm", "chacha20-poly1305", "chacha20-ietf-poly1305",
        "xchacha20-poly1305", "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm",
        "2022-blake3-chacha20-poly1305", "none", "plain"
    )

    private fun buildConfig(list: List<Node>): String {
        val inbounds = JSONArray()
        val outbounds = JSONArray()
        val rules = JSONArray()
        list.forEachIndexed { i, n ->
            inbounds.put(JSONObject()
                .put("tag", "in$i").put("listen", "127.0.0.1")
                .put("port", BASE_PORT + i).put("protocol", "http").put("settings", JSONObject()))
            val ob = outbound(n, "out$i")
            if (ob != null) {
                outbounds.put(ob)
                rules.put(JSONObject().put("type", "field")
                    .put("inboundTag", JSONArray().put("in$i")).put("outboundTag", "out$i"))
            }
        }
        val cfg = JSONObject()
        cfg.put("log", JSONObject().put("loglevel", "warning"))
        cfg.put("dns", JSONObject().put("servers", JSONArray().put("https://cloudflare-dns.com/dns-query")))
        cfg.put("inbounds", inbounds)
        outbounds.put(JSONObject().put("protocol", "freedom").put("tag", "direct"))
        cfg.put("outbounds", outbounds)
        cfg.put("routing", JSONObject().put("domainStrategy", "UseIPv4").put("rules", rules))
        return cfg.toString()
    }

    // ---------- 内核 ----------

    private fun startCore(ctx: Context, list: List<Node>): Boolean {
        stopCore()
        if (list.isEmpty()) return false
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libxray.so")
        if (!bin.exists()) { mainExec.execute { status = "缺少 Xray 内核" }; return false }
        val cfg = File(ctx.filesDir, "xray_cfg.json")
        val working = list.toMutableList()
        var attempt = 0
        while (attempt < 40) {
            attempt++
            try {
                cfg.writeText(buildConfig(working))
            } catch (t: Throwable) {
                mainExec.execute { status = "配置生成失败：${t.message}".take(120) }
                return false
            }
            try {
                val pb = ProcessBuilder(bin.absolutePath, "run", "-c", cfg.absolutePath)
                pb.redirectErrorStream(true)
                val p = pb.start()
                proc = p
                try {
                    Runtime.getRuntime().addShutdownHook(Thread { try { p.destroy() } catch (_: Throwable) { } })
                } catch (_: Throwable) { }
                coreLog = StringBuilder()
                Thread {
                    try {
                        p.inputStream.bufferedReader().forEachLine { line ->
                            synchronized(coreLog) {
                                coreLog.append(line).append('\n')
                                if (coreLog.length > 8000) coreLog.delete(0, coreLog.length - 8000)
                            }
                        }
                    } catch (_: Throwable) { }
                }.start()
                var ok = false
                for (k in 0 until 40) {
                    if (!p.isAlive) break
                    try {
                        Socket().use { it.connect(InetSocketAddress("127.0.0.1", BASE_PORT), 200) }
                        ok = true; break
                    } catch (_: Throwable) { Thread.sleep(150) }
                }
                if (ok) {
                    mainExec.execute { running = true }
                    return true
                }
                val log = synchronized(coreLog) { coreLog.toString() }
                // 错误里带 outN 就剔除该节点再试: 一个坏配置不该拖垮全部节点
                val bad = Regex("tag out(\\d+)").find(log)?.groupValues?.getOrNull(1)?.toIntOrNull()
                stopCore()
                if (bad != null && bad in working.indices) {
                    working.removeAt(bad)
                    mainExec.execute { status = "跳过不兼容节点 #$bad，重试…" }
                    continue
                }
                val err = log.lines().firstOrNull { it.contains("Failed") || it.contains("failed") } ?: "未知错误"
                mainExec.execute { status = ("内核启动失败：" + err).take(160); running = false }
                return false
            } catch (t: Throwable) {
                stopCore()
                mainExec.execute { status = "内核启动异常：${t.message}" }
                return false
            }
        }
        return false
    }

    private fun stopCore() {
        try { proc?.destroy() } catch (_: Throwable) { }
        proc = null
        running = false
    }

    // 真·数据面探测: 经节点做 HTTPS GET(CONNECT+TLS+读一个字节), 只看 CONNECT 200 会被"只握手不通数据"的假节点骗
    private fun probe(port: Int, timeoutMs: Int = 4000): Int {
        val urls = listOf(
            "https://www.gstatic.com/generate_204",
            "https://www.cloudflare.com/cdn-cgi/trace"
        )
        for (u in urls) {
            val t0 = System.currentTimeMillis()
            try {
                val c = URL(u).openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
                val hc = c as java.net.HttpURLConnection
                hc.connectTimeout = 3500
                hc.readTimeout = timeoutMs
                hc.instanceFollowRedirects = false
                hc.useCaches = false
                val code = hc.responseCode
                var ok = code in 200..399
                if (ok && code != 204) {
                    // 非 204 的端点要求真读到数据, 防"只握手不通数据"的假节点
                    var got = -1
                    try { got = hc.inputStream.read() } catch (_: Throwable) { }
                    ok = got >= 0
                }
                hc.disconnect()
                if (ok) return (System.currentTimeMillis() - t0).toInt().coerceAtLeast(1)
            } catch (_: Throwable) { }
        }
        return -1
    }

    // ---------- 节点实测吞吐(下行/上行) ----------
    private fun speedFile(ctx: Context) = File(ctx.filesDir, "proxy_speed.txt")

    private fun loadSpeeds(ctx: Context) {
        try {
            val map = HashMap<String, Speed>()
            for (line in speedFile(ctx).readLines()) {
                val parts = line.split('	', limit = 4)
                if (parts.size < 4) continue
                val d = parts[0].toIntOrNull() ?: continue
                val u = parts[1].toIntOrNull() ?: continue
                val ts = parts[2].toLongOrNull() ?: 0L
                if (parts[3].isNotBlank()) map[parts[3]] = Speed(d, u, ts)
            }
            speeds = map
        } catch (_: Throwable) { }
    }

    private fun persistSpeeds() {
        val ctx = host ?: return
        try {
            val lines = speeds.entries.take(800).map {
                "${it.value.downKbps}\t${it.value.upKbps}\t${it.value.ts}\t${it.key}"
            }
            speedFile(ctx).writeText(lines.joinToString("\n"))
        } catch (_: Throwable) { }
    }

    fun fmtKbps(k: Int): String = when {
        k <= 0 -> "—"
        k >= 1000 -> String.format(java.util.Locale.US, "%.1fM", k / 1000f)
        else -> "${k}K"
    }

    fun speedLabel(uri: String): String {
        val s = speeds[uri] ?: return "↓— ↑—"
        return "↓${fmtKbps(s.downKbps)} ↑${fmtKbps(s.upKbps)}"
    }

    fun isSpeedTesting(uri: String): Boolean = speedTesting.contains(uri)

    fun testSpeed(ctx: Context, idx: Int) {
        if (busy) { status = "正在连接，稍后再测速"; return }
        val n = nodes.getOrNull(idx) ?: return
        if (speedTesting.contains(n.uri) || speedTesting.size >= 2) return
        Thread { testSpeedInternal(ctx, idx) }.start()
    }

    // 经节点本地 HTTP 入站测真实吞吐; 同一时间最多 2 条, 避免互相抢带宽
    private fun testSpeedInternal(ctx: Context, idx: Int) {
        val n = nodes.getOrNull(idx) ?: return
        if (speedTesting.contains(n.uri) || speedTesting.size >= 2) return
        mainExec.execute { speedTesting = speedTesting + n.uri }
        try {
            if (!running) {
                mainExec.execute { status = "启动内核测速…" }
                if (!startCore(ctx, nodes)) return
            }
            val port = BASE_PORT + idx
            val down = measureDown(port, 4_000_000)
            val up = measureUp(port, 512 * 1024)
            val sp = Speed(down, up, System.currentTimeMillis())
            mainExec.execute {
                speeds = speeds + (n.uri to sp)
                persistSpeeds()
            }
        } catch (_: Throwable) { }
        finally {
            mainExec.execute { speedTesting = speedTesting - n.uri }
        }
    }

    private fun measureDown(port: Int, wantBytes: Int): Int {
        val urls = listOf(
            "https://speed.cloudflare.com/__down?bytes=$wantBytes",
            "https://cachefly.cachefly.net/1mb.test"
        )
        for (u in urls) {
            try {
                val c = URL(u).openConnection(
                    Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
                ) as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 20000
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                val code = c.responseCode
                if (code !in 200..299) { c.disconnect(); continue }
                val input = c.inputStream
                val buf = ByteArray(64 * 1024)
                val t0 = System.nanoTime()
                var total = 0L
                var end = t0
                while (total < wantBytes) {
                    val r = input.read(buf)
                    if (r < 0) break
                    total += r
                    end = System.nanoTime()
                    if (end - t0 > 20_000_000_000L) break
                }
                try { input.close() } catch (_: Throwable) { }
                c.disconnect()
                val sec = (end - t0) / 1_000_000_000.0
                if (total > 100_000 && sec > 0.05) {
                    return ((total * 8.0 / 1000.0) / sec).toInt().coerceAtLeast(1)
                }
            } catch (_: Throwable) { }
        }
        return 0
    }

    private fun measureUp(port: Int, bytes: Int): Int {
        return try {
            val c = URL("https://speed.cloudflare.com/__up").openConnection(
                Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
            ) as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 20000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setFixedLengthStreamingMode(bytes)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
            val payload = ByteArray(64 * 1024) { (it * 31).toByte() }
            val out = c.outputStream
            val t0 = System.nanoTime()
            var sent = 0
            while (sent < bytes) {
                val n = minOf(payload.size, bytes - sent)
                out.write(payload, 0, n)
                sent += n
            }
            out.flush()
            try { c.responseCode } catch (_: Throwable) { }
            val sec = (System.nanoTime() - t0) / 1_000_000_000.0
            try { out.close() } catch (_: Throwable) { }
            c.disconnect()
            if (sent > 100_000 && sec > 0.05) ((sent * 8.0 / 1000.0) / sec).toInt().coerceAtLeast(1) else 0
        } catch (_: Throwable) {
            0
        }
    }

    fun startAndTest(ctx: Context) {
        if (busy) return
        val list = nodes
        if (list.isEmpty()) { status = "先更新订阅"; return }
        busy = true
        status = "启动内核…"
        Thread {
            try {
                if (!startCore(ctx, list)) { mainExec.execute { busy = false }; return@Thread }
                runTest(list)
            } catch (t: Throwable) {
                mainExec.execute { status = "测速异常：${t.message}".take(120) }
            } finally {
                mainExec.execute { busy = false }
            }
        }.start()
    }

    private fun writeGoodFile(ctx: Context) {
        val now = System.currentTimeMillis()
        goodSince.entries.removeIf { now - it.value > GOOD_TTL_MS }
        try {
            File(ctx.filesDir, "proxy_good.txt").writeText(
                goodSince.entries.sortedBy { it.key }
                    .joinToString("" + 10.toChar()) { "${it.key}\t${it.value}" }
            )
        } catch (_: Throwable) { }
    }

    // fast=true: 24 路并发, 找到 3 个可用节点就停, 给自动连接快速出结果;
    // 找到的节点会带"最后可用时间"保存, 超过 7 天没再可用的会在读写时被移除。
    private fun runTest(list: List<Node>, fast: Boolean = false): Int {
        mainExec.execute { status = if (fast) "快速检测中 0/${list.size}" else "测速中 0/${list.size}" }
        val results = java.util.concurrent.ConcurrentHashMap<Int, Int>()
        val queue = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        for (i in list.indices) queue.add(i)
        val done = AtomicInteger(0)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val workers = minOf(24, list.size.coerceAtLeast(1))
        val pool = Executors.newFixedThreadPool(workers)
        repeat(workers) {
            pool.execute {
                while (!stop.get()) {
                    val i = queue.poll() ?: break
                    val ms = probe(BASE_PORT + i, if (fast) 4000 else 5000)
                    results[i] = ms
                    val usableNow = results.values.count { it > 0 }
                    if (fast && usableNow >= 3) stop.set(true)
                    val d = done.incrementAndGet()
                    if (d % 5 == 0 || d == list.size || (fast && usableNow >= 3)) {
                        val snapshot = HashMap(results)
                        val usable = snapshot.values.count { it > 0 }
                        mainExec.execute {
                            pings = snapshot
                            status = (if (fast) "快速检测 " else "测速中 ") +
                                "$d/${list.size}（可用 $usable）"
                        }
                    }
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(if (fast) 2 else 8, TimeUnit.MINUTES)
        val snapshot = HashMap(results)
        val goodMap = snapshot.filterValues { it > 0 }
        val best = goodMap.entries.minByOrNull { it.value }?.key ?: -1
        mainExec.execute { pings = snapshot }
        if (best >= 0) {
            val now = System.currentTimeMillis()
            for (i in goodMap.keys) list.getOrNull(i)?.let { goodSince[it.uri] = now }
            selected = best
            host?.getSharedPreferences("endpix", Context.MODE_PRIVATE)
                ?.edit()?.putInt("proxySel", best)?.apply()
            val goodSet = goodMap.keys
            val usable = goodSet.size
            host?.let { writeGoodFile(it) }
            mainExec.execute {
                goodIdx = goodSet
                pings = snapshot.filterKeys { it in goodSet }
                status = "可用 $usable 个（已保存），已选最快：${list[best].name}"
            }
            applyBrowserProxy(true, BASE_PORT + best)
            // 自动测一次选中节点的实测上下行(结果缓存并显示在节点行)
            val h = host
            if (h != null) Thread {
                try { Thread.sleep(1200) } catch (_: Throwable) { }
                testSpeedInternal(h, best)
            }.start()
            return usable
        } else {
            mainExec.execute { status = "全部超时：免费公共节点已被墙，换订阅源或稍后再试" }
            applyBrowserProxy(false, 0)
            return 0
        }
    }

    fun selectAndUse(ctx: Context, idx: Int) {
        if (!unlocked) { status = "请先长按解锁代理"; return }
        if (idx !in nodes.indices) return
        selected = idx
        host?.getSharedPreferences("endpix", Context.MODE_PRIVATE)?.edit()?.putInt("proxySel", idx)?.apply()
        if (!running) { status = "已选 ${nodes[idx].name}，点“启动并测速”"; return }
        val ms = pings[idx] ?: probe(BASE_PORT + idx)
        pings = pings + (idx to ms)
        if (ms > 0) {
            applyBrowserProxy(true, BASE_PORT + idx)
            status = "已切换到 ${nodes[idx].name}（${ms}ms）"
        } else {
            applyBrowserProxy(false, 0)
            status = "该节点不可用"
        }
    }

    /** 系统 VPN 是否已连通且有效(有 INTERNET 能力). */
    private fun vpnConnectedNow(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            for (n in cm.allNetworks) {
                val c = cm.getNetworkCapabilities(n) ?: continue
                if (!c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue
                if (c.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) return true
            }
            false
        } catch (_: Throwable) { false }
    }

    /**
     * VPN 联动: 系统 VPN 连通时自动停掉本机订阅代理(避免双重代理);
     * VPN 断开且当前网页需要代理时, 自动重新开启订阅.
     */
    private fun startVpnWatch(app: Context) {
        if (vpnWatchStarted) return
        vpnWatchStarted = true
        Thread {
            var last = false
            while (true) {
                try {
                    val now = vpnConnectedNow(app)
                    val changed = now != last
                    last = now
                    mainExec.execute {
                        if (now != vpnActive) vpnActive = now
                        if (now) {
                            if (isProxyActive() || running) {
                                stop(app)
                                status = "系统 VPN 已连通，代理订阅已自动关闭"
                            }
                        } else if (changed && BrowserHub.visible && !isProxyActive() &&
                            BrowserHub.url.isNotBlank() && BrowserHub.currentUrlNeedsProxy()
                        ) {
                            status = "系统 VPN 已断开，自动开启代理订阅…"
                            connectAuto(app, BrowserHub.url)
                        }
                    }
                } catch (_: Throwable) { }
                try { Thread.sleep(1500L) } catch (_: InterruptedException) { return@Thread }
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop(ctx: Context) {
        applyBrowserProxy(false, 0)
        stopCore()
        status = "已停止"
    }

    fun testAgain(ctx: Context) {
        if (busy) return
        if (!running) { startAndTest(ctx); return }
        busy = true
        Thread {
            try {
                runTest(nodes)
            } catch (t: Throwable) {
                mainExec.execute { status = "测速异常：${t.message}".take(120) }
            } finally {
                mainExec.execute { busy = false }
            }
        }.start()
    }

    // ---------- WebView 代理 ----------

    fun applyBrowserProxy(on: Boolean, port: Int) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            status = "当前 WebView 不支持代理覆盖"
            return
        }
        try {
            if (on && port > 0) {
                val cfg = ProxyConfig.Builder()
                    .addProxyRule("http://127.0.0.1:$port")
                    .build()
                ProxyController.getInstance().setProxyOverride(cfg, mainExec) {
                    enabled = true
                    status = "浏览器已走代理 127.0.0.1:$port"
                    val t = pendingNav
                    pendingNav = null
                    if (t != null) dialogOpen = false   // 自动连接不占着窗口
                    BrowserHub.navigateInternal(t ?: "https://www.pinterest.com/")
                }
            } else {
                ProxyController.getInstance().clearProxyOverride(mainExec) {
                    enabled = false
                }
            }
        } catch (t: Throwable) {
            status = "设置代理失败：${t.message}"
        }
    }

    // 只展示可用节点(测速后 goodIdx 非空), 下标保持与内核端口一致的原始下标
    fun visibleNodes(): List<Pair<Int, Node>> =
        nodes.indices.filter { goodIdx.isEmpty() || it in goodIdx }.map { it to nodes[it] }

    fun coreLogText(): String = synchronized(coreLog) { coreLog.toString() }
}

@Composable
fun ProxyDialog(ctx: Context) {
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val vis = ProxyHub.visibleNodes()
    Dialog(onDismissRequest = { }) {
        Column(
            Modifier.fillMaxWidth(0.94f)
                .heightIn(max = (cfg.screenHeightDp * 0.9f).dp)
                .background(Ep.Panel, RoundedCornerShape(8.dp))
                .border(1.dp, Ep.Line, RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Pinterest 代理", color = Ep.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (ProxyHub.running) "内核运行中" else "未启动",
                    color = if (ProxyHub.running) Ep.Accent else Ep.Sub, fontSize = 10.sp
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(24.dp).clickable(interactionSource = null, indication = null) {
                        ProxyHub.dialogOpen = false
                    },
                    contentAlignment = Alignment.Center
                ) { Text("×", color = Ep.Sub, fontSize = 16.sp) }
            }
            Spacer(Modifier.height(4.dp))
            Text(ProxyHub.status, color = Ep.Sub, fontSize = 10.sp, maxLines = 3)
            Spacer(Modifier.height(12.dp))
            if (!ProxyHub.unlocked) {
                // 首次解锁: 长按主按钮满 10 秒才允许使用代理; 进度条从左侧填充
                var holdStart by remember { mutableStateOf(0L) }
                LaunchedEffect(holdStart) {
                    if (holdStart == 0L) { ProxyHub.unlockHoldMs = 0L; return@LaunchedEffect }
                    while (true) {
                        val el = android.os.SystemClock.elapsedRealtime() - holdStart
                        ProxyHub.unlockHoldMs = el
                        if (el >= 10_000L) { ProxyHub.unlock(ctx); break }
                        kotlinx.coroutines.delay(50)
                    }
                }
                val frac = (ProxyHub.unlockHoldMs / 10000f).coerceIn(0f, 1f)
                Box(
                    Modifier.fillMaxWidth().height(46.dp)
                        .background(Ep.Accent.copy(alpha = 0.10f), RoundedCornerShape(5.dp))
                        .border(1.dp, Ep.Accent, RoundedCornerShape(5.dp))
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                holdStart = android.os.SystemClock.elapsedRealtime()
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    if (ev.changes.none { it.pressed }) break
                                }
                                holdStart = 0L
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier.fillMaxWidth(frac).fillMaxHeight()
                            .align(Alignment.CenterStart)
                            .background(Ep.Accent.copy(alpha = 0.35f), RoundedCornerShape(5.dp))
                    )
                    Text(
                        if (ProxyHub.unlockHoldMs > 0L)
                            "继续长按解锁 ${(frac * 100).toInt()}%"
                        else "长按 10 秒解锁代理",
                        color = Ep.Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text("首次使用需解锁；解锁后自动连接和手动选节点可用",
                    color = Ep.Sub, fontSize = 8.5.sp)
            } else Box(
                Modifier.fillMaxWidth().height(46.dp)
                    .background(Ep.Accent.copy(alpha = 0.18f), RoundedCornerShape(5.dp))
                    .border(1.dp, Ep.Accent, RoundedCornerShape(5.dp))
                    .clickable(interactionSource = null, indication = null) { ProxyHub.connectAuto(ctx) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    when {
                        ProxyHub.discovering -> "正在搜索新机场…"
                        ProxyHub.busy -> "正在自动连接…"
                        else -> "自动连接"
                    },
                    color = Ep.Accent, fontSize = 14.sp, fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("节点（${vis.size}）", color = Ep.Text, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text("延迟 / 实测 ↓↑", color = Ep.Sub, fontSize = 9.sp)
            }
            Spacer(Modifier.height(5.dp))
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 460.dp)
                    .background(Ep.Panel2, RoundedCornerShape(4.dp))
                    .border(1.dp, Ep.Line, RoundedCornerShape(4.dp))
                    .padding(3.dp)
            ) {
                if (vis.isEmpty()) {
                    item {
                        Text(
                            "点「自动连接」自动找可用节点",
                            color = Ep.Sub, fontSize = 9.sp,
                            modifier = Modifier.fillMaxWidth().padding(10.dp)
                        )
                    }
                }
                itemsIndexed(vis) { _, item ->
                    val (idx, n) = item
                    val ms = ProxyHub.pings[idx]
                    val sel = idx == ProxyHub.selected
                    val testing = ProxyHub.isSpeedTesting(n.uri)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .background(
                                if (sel) Ep.Accent.copy(alpha = 0.16f) else Ep.Panel,
                                RoundedCornerShape(4.dp)
                            )
                            .border(
                                1.dp,
                                if (sel) Ep.Accent else Ep.Line,
                                RoundedCornerShape(4.dp)
                            )
                            .clickable { ProxyHub.selectAndUse(ctx, idx) }
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                n.name,
                                color = Ep.Text, fontSize = 10.sp, maxLines = 1
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    when {
                                        ms == null -> "延迟 —"
                                        ms > 0 -> "延迟 ${ms}ms"
                                        else -> "延迟 超时"
                                    },
                                    color = when {
                                        ms != null && ms > 0 -> Ep.Accent
                                        ms != null -> Color(0xFFE07070)
                                        else -> Ep.Sub
                                    },
                                    fontSize = 8.5.sp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    ProxyHub.speedLabel(n.uri),
                                    color = if ((ProxyHub.speeds[n.uri]?.downKbps ?: 0) > 0) Ep.Accent else Ep.Sub,
                                    fontSize = 8.5.sp, maxLines = 1
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (testing) "测速中…" else "测速",
                            color = if (testing) Ep.Sub else Ep.Accent,
                            fontSize = 9.sp,
                            modifier = Modifier.clickable(
                                enabled = !testing,
                                interactionSource = null,
                                indication = null
                            ) { ProxyHub.testSpeed(ctx, idx) }
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            when {
                                !ProxyHub.unlocked -> "未解锁"
                                sel -> "使用中"
                                else -> "连接"
                            },
                            color = if (sel) Ep.Accent else Ep.Sub,
                            fontSize = 9.sp
                        )
                    }
                }
            }
        }
    }
}
