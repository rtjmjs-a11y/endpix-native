package com.endpix.nativeapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 画笔预设(笔刷系统): 形状/硬度/间距/散射/长宽比/角度/流量. */
data class BrushPreset(
    val name: String,
    val shape: Int,        // 0=圆 1=方
    val hardness: Int,     // 0..100
    val spacing: Int,      // 沿笔画直径的间距 %
    val scatter: Int,      // 随机偏移(直径 %)
    val aspect: Int,       // 长宽比 %
    val angle: Int,        // 角度
    val flow: Int,         // 流量(alpha 乘数) %
)

object BrushHub {
    // 参考 MyPaint/libmypaint(ISC) 与 mypaint-brushes(CC0) 的预设概念。
    // 启用「笔刷系统」后可选；关闭时走引擎的默认硬圆笔。
    val presets = listOf(
        BrushPreset("像素", 0, 100, 10, 0, 100, 0, 100),
        BrushPreset("圆笔", 0, 60, 15, 0, 100, 0, 100),
        BrushPreset("软笔", 0, 25, 12, 0, 100, 0, 100),
        BrushPreset("马克", 1, 100, 25, 0, 35, 0, 100),
        BrushPreset("喷枪", 0, 15, 18, 0, 100, 0, 100),
        BrushPreset("铅笔", 0, 90, 18, 18, 100, 25, 80),
        BrushPreset("书法", 1, 100, 10, 0, 22, 45, 100),
        BrushPreset("干笔", 0, 70, 35, 38, 100, 12, 72),
        BrushPreset("粉笔", 0, 45, 42, 55, 100, 8, 58),
        BrushPreset("水墨", 0, 12, 9, 4, 70, 30, 42),
        BrushPreset("网点", 0, 100, 55, 0, 100, 0, 65),
        BrushPreset("霓虹", 0, 8, 14, 0, 100, 0, 34),
        BrushPreset("木刻", 1, 100, 12, 0, 18, 8, 100),
        BrushPreset("大刷", 0, 55, 20, 0, 100, 0, 78),
        BrushPreset("细线", 0, 100, 8, 0, 100, 0, 92),
        BrushPreset("点画", 0, 100, 70, 26, 100, 0, 100),
    )

    var enabled by mutableStateOf(false)   // 子菜单「启用笔刷」; 关=默认硬圆笔
        private set
    var index by mutableIntStateOf(0)
        private set
    var shape by mutableIntStateOf(0)
    var hardness by mutableIntStateOf(100)
    var spacing by mutableIntStateOf(10)
    var scatter by mutableIntStateOf(0)
    var aspect by mutableIntStateOf(100)
    var angle by mutableIntStateOf(0)
    var flow by mutableIntStateOf(100)

    var onApply: () -> Unit = {}

    fun current(): BrushPreset {
        if (!enabled) return BrushPreset("像素", 0, 100, 10, 0, 100, 0, 100)
        return BrushPreset(presets[index.coerceIn(0, presets.size - 1)].name,
            shape, hardness, spacing, scatter, aspect, angle, flow)
    }

    fun select(i: Int) {
        loadPreset(i)
        onApply()
    }

    fun loadPreset(i: Int) {
        index = i.coerceIn(0, presets.size - 1)
        val p = presets[index]
        shape = p.shape; hardness = p.hardness; spacing = p.spacing
        scatter = p.scatter; aspect = p.aspect; angle = p.angle; flow = p.flow
    }

    fun setAdvancedEnabled(v: Boolean) {
        enabled = v
        onApply()
    }

    fun updateParam(which: Int, v: Int) {
        when (which) {
            0 -> shape = if (v != 0) 1 else 0
            1 -> hardness = v.coerceIn(0, 100)
            2 -> spacing = v.coerceIn(1, 100)
            3 -> scatter = v.coerceIn(0, 100)
            4 -> aspect = v.coerceIn(5, 100)
            5 -> angle = ((v % 360) + 360) % 360
            else -> flow = v.coerceIn(1, 100)
        }
        onApply()
    }

    fun loadStyle(csv: String?, on: Boolean) {
        enabled = on
        val a = csv?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
        if (a != null && a.size >= 7) {
            shape = if (a[0] != 0) 1 else 0
            hardness = a[1].coerceIn(0, 100)
            spacing = a[2].coerceIn(1, 100)
            scatter = a[3].coerceIn(0, 100)
            aspect = a[4].coerceIn(5, 100)
            angle = ((a[5] % 360) + 360) % 360
            flow = a[6].coerceIn(1, 100)
        }
    }

    fun styleCsv(): String = "$shape,$hardness,$spacing,$scatter,$aspect,$angle,$flow"
}
