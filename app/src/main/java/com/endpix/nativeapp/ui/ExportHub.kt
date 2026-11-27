package com.endpix.nativeapp.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// 导出对话框与 MainActivity 之间的参数桥(全局对象, 不给 MainScreen 加参数)
// mode: 0=单帧 1=精灵图 2=逐帧 3=GIF动画 4=叠层图像
object ExportHub {
    var mode by mutableIntStateOf(0)
    var cols by mutableIntStateOf(4)        // 精灵图每行帧数 1..10
    var delayMs by mutableIntStateOf(100)   // GIF 帧速 20..1000, 步进 20
    // 导出水印(只作用于本次导出): 开关 / 文字 / 透明度 0..100%
    var wmOn by mutableStateOf(false)
    var wmText by mutableStateOf("")
    var wmAlpha by mutableIntStateOf(35)
    // 来自全局水印设置(字号/颜色/默认文字), 供导出预览按成品样式绘制
    var wmSize by mutableIntStateOf(28)
    var wmDensity by mutableIntStateOf(100)   // 20..300%, 越大越密
    var wmColor by mutableIntStateOf(0xFFFFFFFF.toInt())
    var wmBaseText by mutableStateOf("Endpix")
    // 导出预览底(仿小地图背景模式): 0=棋盘格 1=棋盘色A 2=棋盘色B 3=深色
    var previewBg by mutableIntStateOf(0)
    var chkA by mutableIntStateOf(0xFFCCCCCC.toInt())
    var chkB by mutableIntStateOf(0xFFFFFFFF.toInt())
    var onPreviewBg: ((Int) -> Unit)? = null

    fun cyclePreviewBg() {
        val n = (previewBg + 1) % 4
        previewBg = n
        onPreviewBg?.invoke(n)
    }

    fun previewBgName(): String = when (previewBg) {
        1 -> "棋盘A"
        2 -> "棋盘B"
        3 -> "深色"
        else -> "棋盘格"
    }
}

object ExportOps {
    // 返回 "ok ..." / "err ..."; kind 同 ExportHub.mode(1=精灵图 2=逐帧目录 3=GIF 4=叠层)
    var run: ((path: String, kind: Int, scale: Int, cols: Int, delayMs: Int) -> String)? = null
    // w,h,frameCount,loopStart,loopEnd,layers
    var info: (() -> IntArray)? = null

    fun infoArray(): IntArray = try {
        info?.invoke()
    } catch (_: Throwable) {
        null
    } ?: intArrayOf(0, 0, 1, 0, 0, 1)
}
