package com.endpix.nativeapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 形状渐变(方形/圆形)的裁剪状态面板。
 * MainScreen 直接读这个全局对象, 避免再给 MainScreen 加参数(已有 64K 参数上限的坑)。
 * 回调由 MainActivity 在 onCreate 里挂到引擎 JNI。
 */
object GradHub {
    var active by mutableStateOf(false)   // 拖完形状、预览中
    var shape by mutableIntStateOf(0)     // 0=方形 1=圆形
    var preset by mutableIntStateOf(0)    // 方形 0..3(默认角度) / -1=自定义角度; 圆形 0..1
    var range by mutableIntStateOf(100)   // 渐变范围 5..100%
    var angle by mutableIntStateOf(90)    // 方形渐变角度 0..359°(0=→ 90=↓ 180=← 270=↑)
    var pos by mutableIntStateOf(0)       // 透明位置 0..100%(淡出起点, 0=实色端 50=中间)
    var onPreset: (Int) -> Unit = {}
    var onRange: (Int) -> Unit = {}
    var onAngle: (Int) -> Unit = {}
    var onPos: (Int) -> Unit = {}
    var onApply: () -> Unit = {}
    var onCancel: () -> Unit = {}
}
