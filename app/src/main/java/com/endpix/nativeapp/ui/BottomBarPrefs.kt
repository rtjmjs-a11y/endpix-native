package com.endpix.nativeapp.ui

import androidx.compose.runtime.mutableStateOf

// 底部中间「单指移动 / 放大缩小」按钮的显示开关(调整设置里切换, 与底栏按钮联动)
object BottomBarPrefs {
    val showMove = mutableStateOf(true)
    val showZoom = mutableStateOf(true)
}
