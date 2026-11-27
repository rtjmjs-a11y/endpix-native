package com.endpix.nativeapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 顶栏/电量环垂直中心(像素, 0=自动按前摄挖孔比例 0.66)。
 * 对应旧项目设置「信息栏高度」的 PlayerPrefs: TopBarCenterY。
 * MainScreen 直接读这里, 避免再加参数(MainScreen 已有 64K 参数上限)。
 */
object TopBarPrefs {
    var centerY by mutableIntStateOf(0)   // 0=自动; 10..320=手动像素
    var onCenterY: (Int) -> Unit = {}
    // 设置里可隐藏顶部性能栏 / 前摄电量环
    var hidePerf by mutableStateOf(false)
    var hideBattery by mutableStateOf(false)
    var onHidePerf: (Boolean) -> Unit = {}
    var onHideBattery: (Boolean) -> Unit = {}
}
