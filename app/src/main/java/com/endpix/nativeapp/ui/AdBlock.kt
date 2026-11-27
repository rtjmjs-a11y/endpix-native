package com.endpix.nativeapp.ui

import android.content.Context
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// ===== 内置去广告 + 隐私强化 =====
// WebView 不支持扩展, 这里用 shouldInterceptRequest 做域名级拦截(hosts 规则) + 页面级 CSS/弹窗清理,
// 再关掉第三方 Cookie。规则包随 APK 内置(约 7.6 万条), 也可在线更新。
object AdBlockHub {
    private const val UPDATE_URL =
        "https://gh-proxy.com/https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"

    var enabled by mutableStateOf(true)
        private set
    var privacy by mutableStateOf(true)
        private set
    // 强力清广告: 通用 CSS 杀广告位/悬浮条/弹窗(更激进, 默认开)
    var aggressive by mutableStateOf(true)
        private set
    var blockedCount by mutableLongStateOf(0L)
        private set
    var requestCount by mutableLongStateOf(0L)
        private set
    var ruleCount by mutableIntStateOf(0)
        private set
    var updating by mutableStateOf(false)
        private set

    private val domains = HashSet<String>(1 shl 18)
    private var host: Context? = null
    @Volatile private var loaded = false

    fun init(ctx: Context) {
        if (host == null) host = ctx
        val p = ctx.getSharedPreferences("endpix", Context.MODE_PRIVATE)
        enabled = p.getBoolean("adBlock", true)
        privacy = p.getBoolean("adPrivacy", true)
        aggressive = p.getBoolean("adAggressive", true)
        if (!loaded) {
            Thread { loadRules(ctx) }.start()
        }
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        enabled = on
        ctx.getSharedPreferences("endpix", Context.MODE_PRIVATE)
            .edit().putBoolean("adBlock", on).apply()
    }

    fun setAggressive(ctx: Context, on: Boolean) {
        aggressive = on
        ctx.getSharedPreferences("endpix", Context.MODE_PRIVATE)
            .edit().putBoolean("adAggressive", on).apply()
    }

    fun setPrivacy(ctx: Context, on: Boolean, wv: WebView?) {
        privacy = on
        ctx.getSharedPreferences("endpix", Context.MODE_PRIVATE)
            .edit().putBoolean("adPrivacy", on).apply()
        applyPrivacy(wv)
    }

    fun applyPrivacy(wv: WebView?) {
        try {
            android.webkit.CookieManager.getInstance()
                .setAcceptThirdPartyCookies(wv ?: return, !privacy)
        } catch (_: Throwable) { }
    }

    private fun loadRules(ctx: Context) {
        val set = HashSet<String>(1 shl 18)
        try {
            ctx.assets.open("adhosts.txt").bufferedReader().useLines { lines ->
                lines.forEach { l -> if (l.isNotBlank()) set.add(l.trim()) }
            }
        } catch (_: Throwable) { }
        try {
            val f = File(ctx.filesDir, "adhosts_user.txt")
            if (f.exists()) f.forEachLine { if (it.isNotBlank()) set.add(it.trim()) }
        } catch (_: Throwable) { }
        synchronized(domains) {
            domains.clear()
            domains.addAll(set)
        }
        ruleCount = set.size
        loaded = true
    }

    // 后缀匹配: a.b.ads.com 会命中 ads.com / b.ads.com / a.b.ads.com
    fun shouldBlock(h: String?): Boolean {
        if (!enabled || h.isNullOrBlank()) return false
        var host = h.lowercase().trimEnd('.')
        if (host.isEmpty()) return false
        synchronized(domains) {
            var guard = 0
            while (guard++ < 8) {
                if (domains.contains(host)) return true
                val i = host.indexOf('.')
                if (i < 0) break
                host = host.substring(i + 1)
                if (!host.contains('.')) break
            }
        }
        return false
    }

    fun note(blocked: Boolean) {
        requestCount++
        if (blocked) blockedCount++
    }

    // 强力模式: 通用选择器杀广告位/悬浮条/弹窗/联盟 iframe
    val aggressiveJs: String = """
(function(){
try{
  if(window.__epAggressive) return;
  window.__epAggressive=1;
  var css='[class^="ad-"],[class*=" ad-"],[class$="-ad"],[class*="ads"],[id^="ad-"],[id*="ads"],'+
    '[class*="banner"],[id*="banner"],[class*="popup"],[id*="popup"],[class*="float-ad"],'+
    '[class*="gg"],[id*="gg"],[class*="guanggao"],[id*="guanggao"],'+
    'iframe[src*="ad"],[style*="position:fixed"][style*="z-index:9"]{display:none!important}';
  var st=document.createElement('style');st.id='__ep_aggr';st.textContent=css;
  (document.head||document.documentElement).appendChild(st);
  var ifr=document.querySelectorAll('iframe');
  for(var i=0;i<ifr.length;i++){
    var s=(ifr[i].src||'');
    if(/doubleclick|googlesyndication|adservice|adsystem|adnxs|taboola|outbrain|popads|propeller/i.test(s)) ifr[i].style.display='none';
  }
}catch(e){}
})();
""".trimIndent()

    // 页面级清理: 隐藏常见广告位 / 去掉 ping 追踪 / 禁掉广告弹窗
    val cosmeticJs: String = """
(function(){
try{
  var css='[id^="google_ads"],[id^="div-gpt-ad"],[class^="adsbygoogle"],[class*=" adsbygoogle"],'+
    'iframe[src*="doubleclick.net"],iframe[src*="googlesyndication.com"],'+
    '[id^="taboola"],[class^="taboola"],[id^="outbrain"],[class^="outbrain"]{display:none!important}';
  if(!document.getElementById('__ep_adcss')){var st=document.createElement('style');st.id='__ep_adcss';st.textContent=css;(document.head||document.documentElement).appendChild(st);}
  var p=document.querySelectorAll('[ping]');for(var i=0;i<p.length;i++)p[i].removeAttribute('ping');
  if(!window.__epNoPopup){window.__epNoPopup=1;window.open=function(u){try{if(u&&typeof u==='string')location.href=u;}catch(e){} return null;};}
}catch(e){}
})();
""".trimIndent()

    fun update(ctx: Context, done: (String) -> Unit) {
        if (updating) return
        updating = true
        Thread {
            val msg = try {
                val conn = URL(UPDATE_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                val added = HashSet<String>(1 shl 17)
                conn.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val s = line.trim()
                        if (s.isEmpty() || s.startsWith("#") || s.startsWith("!")) return@forEach
                        val parts = s.split(Regex("\\s+"))
                        if (parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1")) {
                            val d = parts[1].lowercase().trimEnd('.')
                            if (d.isNotEmpty() && d != "localhost" && !d.endsWith(".local")) added.add(d)
                        }
                    }
                }
                conn.disconnect()
                File(ctx.filesDir, "adhosts_user.txt")
                    .writeText(added.joinToString("" + 10.toChar()))
                loadRules(ctx)
                "规则已更新：${added.size} 条"
            } catch (t: Throwable) {
                "更新失败：${t.message}"
            }
            updating = false
            done(msg)
        }.start()
    }
}
