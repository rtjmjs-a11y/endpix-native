package com.endpix.nativeapp.ui

import android.net.Uri
import android.webkit.WebView

// 蓝奏云直链自动化:
// 分享页 -> 读取页面自己生成的 /tp/... 链接 -> 进入下载页 -> 读取页面里的 vkjxld+hyggid+lanosso 直链
// -> 交给 WebView 执行蓝奏云自己的 JS 验证 -> DownloadListener 捕获真实文件并交给 PdfHub。
// 不逆向/不伪造验证码, 只让页面按它自己的逻辑走, 也不需要用户点页面按钮。
object LanzouAuto {
    private var active = false
    private var since = 0L
    private var referer = ""
    private val fired = HashSet<String>()

    fun isLanzouHost(url: String): Boolean {
        val h = try { Uri.parse(url).host?.lowercase() } catch (_: Throwable) { null } ?: return false
        return h.contains("lanzou")
    }

    fun isShare(url: String): Boolean {
        if (!isLanzouHost(url)) return false
        val path = try { Uri.parse(url).path?.lowercase() } catch (_: Throwable) { null } ?: return true
        return !path.contains("/tp/")
    }

    fun begin(url: String) {
        active = true
        since = System.currentTimeMillis()
        referer = url
        fired.clear()
    }

    // DownloadListener 用: 只有在蓝奏云解析流程中的下载才接管
    fun shouldTakeDownload(): Boolean {
        if (!active) return false
        if (System.currentTimeMillis() - since > 180_000L) { active = false; return false }
        active = false
        return true
    }

    fun currentReferer(): String = referer

    fun onPageFinished(wv: WebView, url: String?) {
        if (!active || url.isNullOrBlank()) return
        if (System.currentTimeMillis() - since > 180_000L) { active = false; return }
        val u = url.lowercase()
        if (!u.contains("lanzou")) return
        val isTp = u.contains("/tp/")
        val key = (if (isTp) "tp:" else "share:") + url
        if (fired.contains(key)) return
        fired.add(key)
        val js = if (isTp) TP_JS else SHARE_JS
        try { wv.evaluateJavascript(js, null) } catch (_: Throwable) { }
    }

    private val SHARE_JS = """
        (function(){
          try{
            var a=document.getElementById('ddown');
            if(!a)return 'nod';
            var h=a.getAttribute('href')||a.href||'';
            if(h && h!=='#' && h!=='##' && h.indexOf('/tp/')>=0){
              location.href=h;
              return 'tp';
            }
            return 'nohref';
          }catch(e){return 'err';}
        })();
    """.trimIndent()

    private val TP_JS = """
        (function(){
          try{
            if(typeof vkjxld!=='undefined' && typeof hyggid!=='undefined'){
              location.href=vkjxld+hyggid+(typeof lanosso!=='undefined'?lanosso:'');
              return 'direct';
            }
          }catch(e){}
          try{
            var s=document.getElementById('submit');
            if(s){
              try{if(s.onmousedown)s.onmousedown();}catch(e){}
              try{if(s.onfocus)s.onfocus();}catch(e){}
              s.click();
              return 'click';
            }
          }catch(e){}
          return 'no';
        })();
    """.trimIndent()
}
