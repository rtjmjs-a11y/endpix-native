package com.endpix.nativeapp.ui

import android.graphics.Color as AColor
import androidx.compose.runtime.mutableIntStateOf

// 两色混合: 从色板子菜单进入, 连续在画布上吸两色, 第二色落下后合成并应用
// stage = -1 关闭 / 0 等待第一色 / 1 等待第二色
object MixPickHub {
    val stage = mutableIntStateOf(-1)
    val colorA = mutableIntStateOf(0)
    var onStart: (() -> Unit)? = null

    fun reset() {
        stage.intValue = -1
        colorA.intValue = 0
    }
}

// 中间色: HSV 三要素取中, 不用 RGB 平均。色相走圆周最短路径, S/V/Alpha 取算术平均
fun mixMidpointColor(a: Int, b: Int): Int {
    val hsvA = FloatArray(3)
    val hsvB = FloatArray(3)
    AColor.colorToHSV(a, hsvA)
    AColor.colorToHSV(b, hsvB)
    val dh = ((hsvB[0] - hsvA[0] + 540f) % 360f) - 180f
    val h = (hsvA[0] + dh / 2f + 360f) % 360f
    val s = (hsvA[1] + hsvB[1]) / 2f
    val v = (hsvA[2] + hsvB[2]) / 2f
    val alpha = (((a ushr 24) and 0xFF) + ((b ushr 24) and 0xFF)) / 2
    return AColor.HSVToColor(alpha, floatArrayOf(h, s, v))
}
