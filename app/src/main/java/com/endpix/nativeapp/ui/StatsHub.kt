package com.endpix.nativeapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// 顶栏性能/状态显示的全局状态。
// MainScreen 的参数里不再读这些高频变化的值(stats 500ms / 内存 2s / 自动保存 1s),
// 只让 InfoPanel 自己订阅: 否则整个 MainScreen 每 0.5~2 秒重组一次, 空闲时 UI 线程也要烧 ~20% CPU。
object StatsHub {
    var fpsText by mutableStateOf("--fps")
    var compText by mutableStateOf("--")
    var memPct by mutableIntStateOf(-1)
    var autoSaveMin by mutableIntStateOf(0)
    var autoSaveRemain by mutableIntStateOf(-1)
    var autoSaveSaving by mutableStateOf(false)
}
