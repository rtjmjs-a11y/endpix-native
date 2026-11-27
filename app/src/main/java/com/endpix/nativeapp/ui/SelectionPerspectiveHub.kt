package com.endpix.nativeapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 框选子菜单「透视变形」模式。
 * 与「拉伸旋转」互斥: 开启一个时关闭另一个。
 * 全局 object, 不给 MainScreen 加参数(避免 64K 方法上限)。
 */
object SelectionPerspectiveHub {
    var on by mutableStateOf(false)
    var onSet: ((Boolean) -> Unit)? = null

    fun set(on: Boolean) {
        if (this.on == on) return
        this.on = on
        onSet?.invoke(on)
    }

    fun toggle() = set(!on)
}
