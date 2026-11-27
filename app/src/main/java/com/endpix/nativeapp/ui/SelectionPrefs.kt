package com.endpix.nativeapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * 框选手势参数。目前只有「选区内长按裁剪」的触发时长:
 * 默认 1000ms, 可调 200..3000ms (设置里用 SetCycle 循环选项)。
 */
object SelectionPrefs {
    var holdMs by mutableIntStateOf(1000)
    var onHoldMs: (Int) -> Unit = {}

    fun set(ms: Int) {
        val v = ms.coerceIn(200, 3000)
        holdMs = v
        onHoldMs(v)
    }
}
