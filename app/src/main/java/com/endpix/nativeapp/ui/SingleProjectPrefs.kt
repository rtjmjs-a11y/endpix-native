package com.endpix.nativeapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 单项目模式: 打开/新建/模板/缓存都替换当前标签的画布, 不再新增标签.
 * 全局 object: 设置面板直接读写, 不再给 MainScreen/AdjustSettings 加参数(MainScreen 贴 64K).
 */
object SingleProjectPrefs {
    var on by mutableStateOf(false)
    var persist: ((Boolean) -> Unit)? = null

    fun set(v: Boolean) {
        on = v
        persist?.invoke(v)
    }
}
