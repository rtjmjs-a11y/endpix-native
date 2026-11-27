package com.endpix.nativeapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.net.InetSocketAddress
import java.net.Socket

// 当前网络延迟(ms): 用 TCP 握手测, 避免 ICMP 权限问题; 5 秒刷新一次, 失败显示 -1
object NetPing {
    var ms by mutableIntStateOf(-1)
        private set

    private var started = false
    private val targets = listOf(
        "223.5.5.5" to 443,          // 阿里 DNS
        "www.baidu.com" to 443,
        "114.114.114.114" to 53      // 114 DNS
    )

    fun start() {
        if (started) return
        started = true
        Thread {
            while (true) {
                val v = measure()
                ms = v
                try { Thread.sleep(if (v > 0) 5000 else 3000) } catch (_: Throwable) { }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun measure(): Int {
        for ((h, p) in targets) {
            val t0 = System.nanoTime()
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(h, p), 1500)
                }
                val dt = ((System.nanoTime() - t0) / 1_000_000L).toInt()
                if (dt in 0..3000) return dt
            } catch (_: Throwable) { }
        }
        return -1
    }
}
