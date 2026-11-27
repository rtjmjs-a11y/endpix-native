package com.endpix.nativeapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 灰度观看: 只切换屏幕显示(着色器去饱和), 不改画布像素/不产生撤销步骤。 */
object GrayViewPrefs {
    var on by mutableStateOf(false)
    var apply: ((Boolean) -> Unit)? = null

    fun set(v: Boolean) {
        on = v
        apply?.invoke(v)
    }

    fun toggle() { set(!on) }
}
