package com.endpix.nativeapp.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * 应用内更新：从 GitHub Releases 取最新 tag + 固定附件 endpix_native_latest.apk。
 * 不走 endpix.com。下载后做包名校验，再交给系统安装器。
 */
object UpdateHub {
    private const val REPO = "rtjmjs-a11y/endpix-native-releases"
    private const val ASSET = "endpix_native_latest.apk"
    private val main = Handler(Looper.getMainLooper())

    var checking by mutableStateOf(false)
        private set
    var downloading by mutableStateOf(false)
        private set
    var progress by mutableIntStateOf(-1)
        private set
    var status by mutableStateOf("点「检查更新」获取最新版本")
        private set
    var latestTag by mutableStateOf("")
        private set
    var hasUpdate by mutableStateOf(false)
        private set
    private var apkUrl = ""

    private fun ui(block: () -> Unit) { main.post(block) }

    private fun tagNumber(tag: String): Int =
        tag.trim().removePrefix("v").substringAfterLast('.').removePrefix("pn").toIntOrNull() ?: 0

    private fun conn(u: String, follow: Boolean, useProxy: Boolean): HttpURLConnection {
        val port = try { ProxyHub.activePort() } catch (_: Throwable) { 0 }
        val c = if (useProxy && port > 0) {
            URL(u).openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
        } else URL(u).openConnection()
        val h = c as HttpURLConnection
        h.connectTimeout = 12000
        h.readTimeout = 20000
        h.instanceFollowRedirects = follow
        h.setRequestProperty("User-Agent", "endpix-updater")
        h.setRequestProperty("Accept", "application/vnd.github+json")
        return h
    }

    /** GitHub 的 latest 会 302 到 /releases/tag/<tag>; 不跟随重定向直接读 Location。 */
    private fun latestTagOnce(useProxy: Boolean): String {
        val h = conn("https://github.com/$REPO/releases/latest", false, useProxy)
        return try {
            val code = h.responseCode
            if (code in 300..399) {
                val loc = h.getHeaderField("Location") ?: ""
                loc.substringAfterLast("/tag/").substringBefore('?').substringBefore('/')
            } else if (code == 200) {
                // 没重定向时用 API 兜底
                ""
            } else ""
        } finally {
            h.disconnect()
        }
    }

    private fun latestTagFromApi(useProxy: Boolean): String {
        val h = conn("https://api.github.com/repos/$REPO/releases/latest", true, useProxy)
        return try {
            if (h.responseCode !in 200..299) return ""
            val txt = h.inputStream.bufferedReader().use { it.readText() }
            val m = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(txt) ?: return ""
            m.groupValues[1]
        } finally {
            h.disconnect()
        }
    }

    private fun findLatestTag(): String {
        val port = try { ProxyHub.activePort() } catch (_: Throwable) { 0 }
        val order = if (port > 0) listOf(true, false) else listOf(false)
        for (p in order) {
            try { latestTagOnce(p).takeIf { it.isNotBlank() }?.let { return it } } catch (_: Throwable) { }
        }
        for (p in order) {
            try { latestTagFromApi(p).takeIf { it.isNotBlank() }?.let { return it } } catch (_: Throwable) { }
        }
        return ""
    }

    fun check(ctx: Context, onResult: ((Boolean) -> Unit)? = null) {
        if (checking || downloading) return
        checking = true
        hasUpdate = false
        status = "正在检查更新…"
        Thread {
            val tag = try { findLatestTag() } catch (_: Throwable) { "" }
            val cur = try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
            } catch (_: Throwable) { "" }
            ui {
                checking = false
                latestTag = tag
                var found = false
                if (tag.isBlank()) {
                    status = "检查失败：请检查网络或代理"
                } else if (tagNumber(tag) > tagNumber(cur)) {
                    found = true
                    hasUpdate = true
                    apkUrl = "https://github.com/$REPO/releases/download/$tag/$ASSET"
                    status = "发现新版本 $tag（当前 $cur）"
                } else {
                    status = "已是最新版本 $cur"
                }
                onResult?.invoke(found)
            }
        }.start()
    }

    fun downloadAndInstall(ctx: Context) {
        if (downloading || checking) return
        if (apkUrl.isBlank()) {
            status = "请先检查更新"
            return
        }
        downloading = true
        progress = 0
        status = "正在下载更新…"
        Thread {
            val out = File(ctx.cacheDir, "endpix_update.apk")
            var ok = false
            try {
                if (out.exists()) out.delete()
                val port = try { ProxyHub.activePort() } catch (_: Throwable) { 0 }
                // 直连 GitHub + 国内可达的 GitHub 镜像兜底(手机直连资产经常被重置)
                val urls = listOf(
                    apkUrl,
                    "https://ghproxy.net/$apkUrl",
                    "https://ghfast.top/$apkUrl",
                    "https://gh-proxy.com/$apkUrl"
                ).distinct()
                var h: HttpURLConnection? = null
                var used = ""
                for ((idx, u) in urls.withIndex()) {
                    ui {
                        status = if (idx == 0) "正在下载更新…"
                        else "正在下载更新（备用线路 $idx）…"
                    }
                    val order = if (u == apkUrl && port > 0) listOf(true, false) else listOf(false)
                    var got = false
                    for (p in order) {
                        try {
                            val c = conn(u, true, p)
                            if (c.responseCode in 200..299) { h = c; used = u; got = true; break }
                            c.disconnect()
                        } catch (_: Throwable) { }
                    }
                    if (got) break
                }
                if (h == null) throw RuntimeException("无法连接下载服务器（含备用线路）")
                android.util.Log.e("endpixupdate", "download via ${used.take(80)}")
                val total = h.contentLengthLong
                h.inputStream.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val pct = ((read * 100) / total).toInt().coerceIn(0, 100)
                                if (pct != lastPct) { lastPct = pct; ui { progress = pct } }
                            }
                        }
                    }
                }
                h.disconnect()
                if (out.length() < 1_000_000L) throw RuntimeException("下载文件不完整")
                val info = ctx.packageManager.getPackageArchiveInfo(out.absolutePath, 0)
                if (info == null || info.packageName != ctx.packageName) {
                    throw RuntimeException("安装包校验失败")
                }
                ok = true
                ui { progress = 100; status = "下载完成，正在打开安装器…" }
                install(ctx, out)
            } catch (e: Throwable) {
                ui { status = "下载失败：${e.message ?: "网络错误"}" }
            } finally {
                ui {
                    downloading = false
                    if (!ok) {
                        progress = -1
                        try { out.delete() } catch (_: Throwable) { }
                    }
                }
            }
        }.start()
    }

    private fun install(ctx: Context, f: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                ctx, ctx.packageName + ".fileprovider", f
            )
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            ui { status = "已打开系统安装器，请确认安装" }
        } catch (e: Throwable) {
            ui { status = "无法打开安装器：${e.message ?: "请允许安装未知应用"}" }
        }
    }
}
