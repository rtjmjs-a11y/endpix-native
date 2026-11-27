package com.endpix.nativeapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 画布标签栏显示开关(更多工具 → 标签栏), 默认打开.
 * 全局 object: MainScreen 已贴 JVM 64K, 不再给 MainScreen/MoreToolsMenu 加参数.
 */
object TabBarPrefs {
    var visible by mutableStateOf(true)
    var persist: ((Boolean) -> Unit)? = null

    fun toggle() {
        visible = !visible
        persist?.invoke(visible)
    }
}
