package com.endpix.nativeapp.ui

import android.content.Context
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ===== 网页翻译: 非中文页面 -> 中文 =====
// WebView 装不了扩展, 这里用"抽文本 -> 逐段翻译 -> 回填"的方式实现;
// 翻译接口用 MyMemory(免 key、国内可直连), 单段 500 字节以内, 有每日额度。
object TranslateHub {
    var auto by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var progress by mutableIntStateOf(0)
        private set
    var total by mutableIntStateOf(0)
        private set
    var note by mutableStateOf("")
        private set

    fun init(ctx: Context) {
        auto = ctx.getSharedPreferences("endpix", Context.MODE_PRIVATE)
            .getBoolean("autoTranslate", false)
    }

    fun setAuto(ctx: Context, on: Boolean) {
        auto = on
        ctx.getSharedPreferences("endpix", Context.MODE_PRIVATE)
            .edit().putBoolean("autoTranslate", on).apply()
    }

    private val COLLECT_JS = """
(function(){
try{
  if(window.__epTrBusy) return '[]';
  var isCJK=function(s){var m=s.match(/[\u4e00-\u9fff]/g);return m?m.length:0;};
  var nodes=document.querySelectorAll('p,li,h1,h2,h3,h4,h5,h6,blockquote,figcaption,td,th,dd,dt,caption,span');
  var out=[];var idx=0;
  for(var i=0;i<nodes.length;i++){
    var el=nodes[i];
    if(!el||el.getAttribute('data-eptr')==='1') continue;
    if(el.closest('.skiptranslate')) continue;
    if(el.querySelector('p,li,h1,h2,h3,h4,h5,h6')) continue;
    var t=(el.innerText||el.textContent||'').replace(/\s+/g,' ').trim();
    if(t.length<2) continue;
    if(!/[A-Za-z]/.test(t)) continue;
    if(isCJK(t)/t.length>0.3) continue;
    el.setAttribute('data-eptr','1');
    el.setAttribute('data-eptri',String(idx));
    out.push(t.length>480?t.slice(0,480):t);
    idx++;
    if(idx>=80) break;
  }
  return JSON.stringify(out);
}catch(e){return '[]';}
})()
""".trimIndent()

    // 谷歌网页翻译(整页原位替换, 快): 需要代理能访问 translate.google.com
    private val GOOGLE_JS = """
(function(){
try{
  if(window.__epGtLoaded) return;
  window.__epGtLoaded=1;
  var st=document.createElement('style');
  st.textContent='.goog-te-banner-frame{display:none!important}.skiptranslate>iframe{display:none!important}body{top:0!important}';
  (document.head||document.documentElement).appendChild(st);
  window.googleTranslateElementInit=function(){
    try{
      new google.translate.TranslateElement({pageLanguage:'',includedLanguages:'zh-CN',autoDisplay:true},'ep_gt_host');
      var tries=0;
      var timer=setInterval(function(){
        var c=document.querySelector('.goog-te-combo');
        if(c){ c.value='zh-CN'; c.dispatchEvent(new Event('change')); clearInterval(timer); }
        if(++tries>40) clearInterval(timer);
      },250);
    }catch(e){}
  };
  var host=document.createElement('div');host.id='ep_gt_host';host.style.display='none';
  document.body.appendChild(host);
  var s=document.createElement('script');
  s.src='https://translate.google.com/translate_a/element.js?cb=googleTranslateElementInit';
  document.body.appendChild(s);
}catch(e){}
})()
""".trimIndent()

    // 有代理用谷歌翻译接口(快), 没代理退回 MyMemory
    fun translateSmart(wv: WebView, ctx: Context) {
        translatePage(wv, ctx)
    }

    fun translatePage(wv: WebView, ctx: Context) {
        if (busy) return
        busy = true
        progress = 0
        total = 0
        note = "正在提取文本…"
        wv.evaluateJavascript(COLLECT_JS) { raw ->
            val arr = try {
                val decoded = org.json.JSONTokener(raw).nextValue() as? String ?: ""
                if (decoded.isBlank() || decoded == "[]") emptyList() else {
                    val a = JSONArray(decoded)
                    (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
                }
            } catch (_: Throwable) { emptyList() }
            if (arr.isEmpty()) {
                busy = false
                note = "没有需要翻译的文本"
                return@evaluateJavascript
            }
            total = arr.size
            note = "翻译中 0/$total"
            Thread {
                val useGoogle = ProxyHub.isProxyActive()
                val res = arrayOfNulls<String>(arr.size)
                val okCount = java.util.concurrent.atomic.AtomicInteger(0)
                val done = java.util.concurrent.atomic.AtomicInteger(0)
                val pool = java.util.concurrent.Executors.newFixedThreadPool(if (useGoogle) 6 else 3)
                for (i in arr.indices) {
                    val txt = arr[i]
                    pool.execute {
                        val tr = try {
                            if (useGoogle) callGoogle(txt) else translateOne(txt)
                        } catch (_: Throwable) { "" }
                        res[i] = tr.ifBlank { txt }
                        if (tr.isNotBlank()) okCount.incrementAndGet()
                        val d = done.incrementAndGet()
                        progress = d
                        note = "翻译中 $d/$total${if (useGoogle) "（谷歌）" else ""}"
                    }
                }
                pool.shutdown()
                pool.awaitTermination(3, java.util.concurrent.TimeUnit.MINUTES)
                val payload = JSONArray(res.map { it ?: "" }).toString()
                val js = "(function(r){for(var i=0;i<r.length;i++){var el=document.querySelector('[data-eptri=\"'+i+'\"]');if(el&&r[i])el.textContent=r[i];}})((function(){return $payload;})());"
                wv.post {
                    wv.evaluateJavascript(js, null)
                    busy = false
                    note = if (okCount.get() == 0) "翻译失败（接口限流？）" else "已翻译 ${okCount.get()} 段"
                }
            }.start()
        }
    }

    // 自动翻译: 页面语言不是中文才翻
    fun autoTranslate(wv: WebView, ctx: Context) {
        if (!auto || busy) return
        wv.evaluateJavascript(
            "(function(){try{var l=(document.documentElement.lang||'').toLowerCase();" +
                "if(l.indexOf('zh')===0)return 'zh';" +
                "var t=(document.body?document.body.innerText:'').slice(0,1200);" +
                "var m=t.match(/[\\u4e00-\\u9fff]/g);var c=m?m.length:0;" +
                "return (t.length>0&&c/t.length>0.2)?'zh':'other';}catch(e){return 'other';}})()"
        ) { r ->
            val lang = r?.trim()?.removeSurrounding("\"") ?: "other"
            if (lang != "zh") translateSmart(wv, ctx)
        }
    }

    private fun translateOne(text: String): String {
        val q = text.trim()
        if (q.isEmpty()) return ""
        // 接口单次上限约 500 字节, 超长按句子切块再拼
        if (q.toByteArray(Charsets.UTF_8).size <= 480) return callApi(q)
        val parts = splitChunks(q, 450)
        val sb = StringBuilder()
        for (p in parts) {
            val r = callApi(p)
            if (r.isBlank()) return ""
            sb.append(r)
        }
        return sb.toString()
    }

    private fun splitChunks(s: String, maxBytes: Int): List<String> {
        val out = ArrayList<String>()
        var start = 0
        while (start < s.length) {
            var end = start
            var bytes = 0
            while (end < s.length) {
                val b = s[end].toString().toByteArray(Charsets.UTF_8).size
                if (bytes + b > maxBytes) break
                bytes += b
                end++
            }
            if (end == start) end = start + 1
            // 尽量在句号/空格处切
            var cut = end
            for (k in end - 1 downTo start + 1) {
                val ch = s[k]
                if (ch == '.' || ch == '!' || ch == '?' || ch == ' ' || ch == ';' || ch == ',') {
                    cut = k + 1; break
                }
            }
            out.add(s.substring(start, cut))
            start = cut
        }
        return out
    }

    private fun callGoogle(q: String): String {
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=zh-CN&dt=t&q=" +
            URLEncoder.encode(q, "UTF-8")
        val conn = if (ProxyHub.isProxyActive()) {
            URL(url).openConnection(
                java.net.Proxy(java.net.Proxy.Type.HTTP,
                    java.net.InetSocketAddress("127.0.0.1", ProxyHub.activePort()))
            ) as HttpURLConnection
        } else {
            URL(url).openConnection() as HttpURLConnection
        }
        conn.connectTimeout = 8000
        conn.readTimeout = 12000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
        return try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONArray(body)
            val segs = root.optJSONArray(0) ?: return ""
            val sb = StringBuilder()
            for (i in 0 until segs.length()) {
                val seg = segs.optJSONArray(i) ?: continue
                val t = seg.optString(0)
                if (t.isNotBlank()) sb.append(t)
            }
            sb.toString()
        } catch (_: Throwable) {
            ""
        } finally {
            conn.disconnect()
        }
    }

    private fun callApi(q: String): String {
        val url = "https://api.mymemory.translated.net/get?q=" +
            URLEncoder.encode(q, "UTF-8") + "&langpair=en%7Czh-CN"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
        return try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val j = org.json.JSONObject(body)
            val rd = j.optJSONObject("responseData")
            val t = rd?.optString("translatedText") ?: ""
            if (t.contains("INVALID") || t.contains("QUERY LENGTH")) "" else t
        } catch (_: Throwable) {
            ""
        } finally {
            conn.disconnect()
        }
    }
}
