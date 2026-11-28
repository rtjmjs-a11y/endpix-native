package com.endpix.nativeapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// 小地图归一化坐标 → 画布坐标。2.5D 开启时缩略图是"旋转后的画布 bbox 铺满格子",
// 点击/拖动必须做同样的逆旋转；由 MainActivity 注入原生实现，避免给 MainScreen 加参数。
object MinimapNavHub {
    var convert: ((Float, Float) -> FloatArray)? = null
    // 2.5D 拖动时 30Hz 更新；只让 MinimapPanel 重组，不走 MainScreen 的 thumbRev 参数
    val rev = mutableIntStateOf(0)
    // 2.5D 是否开启: 决定小地图上是否显示 ⟲/⟳ 旋转按钮
    var mode25On by mutableStateOf(false)
    // 旋转按钮: 传角度增量(度)给原生
    var rotate: ((Float) -> Unit)? = null
}
