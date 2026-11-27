package com.endpix.nativeapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 裁剪工具栏「透明叠加」(裁剪浮层落定时的半透明像素处理).
 * 默认关闭: 半透明像素直接覆盖目标(保留源 alpha), 不与目标底色混合.
 * 开启: 按 over 混合(旧行为).
 * 全局 object, 不给 MainScreen 加参数.
 */
object SelectionBlendHub {
    var alphaOverlay by mutableStateOf(false)
    var onSet: ((Boolean) -> Unit)? = null

    fun set(on: Boolean) {
        if (alphaOverlay == on) return
        alphaOverlay = on
        onSet?.invoke(on)
    }

    fun toggle() = set(!alphaOverlay)
}
