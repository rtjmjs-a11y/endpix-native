package com.endpix.nativeapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.clipRect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

// 水印/个性签名配置(旧项目: 水印 -30° 平铺, 签名四角带背景板)
data class WatermarkSettings(
    val wmOn: Boolean = false,
    val wmText: String = "Endpix",
    val wmAlpha: Int = 20,              // 5..30 %
    val wmColor: Int = 0xFFFFFFFF.toInt(),
    val wmSize: Int = 28,               // px(旧项目默认 28)
    val sigOn: Boolean = false,
    // 品牌签名 = 图标 + 最多两行文本(默认取当前"浸没制造"水印内容)
    val sigText: String = "Immersion technology\n浸没制造",
    val sigSize: Int = 14,              // px(旧项目默认 14)
    val sigCorner: Int = 3,             // 0左上 1右上 2左下 3右下
    val sigBg: Int = 0xB21A1A1A.toInt(),// ARGB: 旧项目 #1A1A1A × 70%
    val sigIconPath: String = "",       // 自定义图标(空=内置品牌线稿)
    val sigPortrait: Boolean = true,    // 竖屏右下角是否显示品牌签名(横屏/导出不受影响)
    val sigDate: Boolean = true,        // 横屏/导出签名底板内是否显示日期(横屏左下角菜单可切换)
    val sigDistPct: Int = 0,            // 签名向屏幕中心收拢的距离 0..100%(0=贴角, 100=居中)
)

fun cornerName(c: Int): String = when (c) {
    0 -> "左上"
    1 -> "右上"
    2 -> "左下"
    else -> "右下"
}

/**
 * 导出水印文字平铺(-30°): W/H 为输出像素尺寸, sizePx 为文字像素大小。
 * 导出烘焙和导出对话框预览共用, 保证预览与成品一致。
 */
fun drawExportWatermarkText(
    cv: android.graphics.Canvas, W: Float, H: Float,
    textRaw: String, alphaPct: Int, sizePx: Float, color: Int,
    padScale: Float = 1f,
    densityPct: Int = 100,
) {
    if (W <= 0f || H <= 0f || sizePx <= 0f) return
    val alpha = (alphaPct.coerceIn(0, 100) * 255 / 100)
    if (alpha <= 0) return
    val density = (densityPct.coerceIn(20, 300) / 100f)
    val text = textRaw.ifBlank { "Endpix" }
    val lines = text.lines()
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.alpha = alpha
        textSize = sizePx
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    var maxW = 0f
    for (ln in lines) maxW = maxOf(maxW, p.measureText(ln))
    val lineH = p.textSize * 1.1f
    val blockH = lineH * lines.size
    val cellW = maxW + 80f * padScale / density
    val cellH = blockH + 70f * padScale / density
    val cols = maxOf(2, (W / cellW).toInt())
    val rows = maxOf(2, (H / cellH).toInt())
    val stepX = W / cols
    val stepY = H / rows
    for (row in 0 until rows) for (col in 0 until cols) {
        val cx = stepX * (col + 0.5f)
        val cy = stepY * (row + 0.5f)
        val save = cv.save()
        cv.rotate(-30f, cx, cy)
        var ty = cy - blockH * 0.5f - p.fontMetrics.ascent
        for (ln in lines) {
            cv.drawText(ln, cx, ty, p)
            ty += lineH
        }
        cv.restoreToCount(save)
    }
}

// 品牌图标(线稿) → Android Canvas: 竖屏水印/签名/导出共用
fun drawBrandIconNative(cv: android.graphics.Canvas, left: Float, top: Float, size: Float, alpha: Int) {
    val k = size / 100f
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        this.alpha = alpha
        style = Paint.Style.STROKE
        strokeWidth = 2.9f * k * 1.2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val seg = floatArrayOf(
        64.21f,10.41f, 34.89f,35.56f, 34.89f,35.56f, 47.86f,48.56f,
        64.21f,10.41f, 84.84f,40.78f,
        63.62f,37.08f, 37.46f,59.50f, 37.46f,59.50f, 51.36f,73.48f,
        35.44f,89.59f, 68.53f,59.46f,
        7.21f,48.37f, 16.36f,32.25f, 16.36f,32.25f, 25.51f,48.02f,
        7.21f,48.37f, 25.51f,48.02f,
        3.48f,53.58f, 33.84f,53.08f,
        66.54f,43.31f, 96.52f,42.33f,
        73.16f,48.37f, 91.46f,47.98f,
        73.16f,48.56f, 82.31f,64.13f,
        91.46f,48.17f, 82.31f,64.13f
    )
    val path = android.graphics.Path()
    var i = 0
    while (i + 3 < seg.size) {
        path.moveTo(left + seg[i] * k, top + seg[i + 1] * k)
        path.lineTo(left + seg[i + 2] * k, top + seg[i + 3] * k)
        i += 4
    }
    var px = 12.86f
    var py = 53.58f
    val c1x = 20.16f; val c1y = 65.67f; val c2x = 27.8f; val c2y = 77.72f
    val p1x = 35.44f; val p1y = 89.59f
    for (j in 1..8) {
        val t = j / 8f; val u = 1f - t
        val bx = u*u*u*12.86f + 3f*u*u*t*c1x + 3f*u*t*t*c2x + t*t*t*p1x
        val by = u*u*u*53.58f + 3f*u*u*t*c1y + 3f*u*t*t*c2y + t*t*t*p1y
        path.moveTo(left + px * k, top + py * k)
        path.lineTo(left + bx * k, top + by * k)
        px = bx; py = by
    }
    cv.drawPath(path, p)
}

// 品牌签名(图标 + 最多两行文本 + 日期) → Android Canvas: 横屏覆盖 / 导出共用
// 日期只到天(时分秒不再显示), 且放在签名底板内部, 不再是没有背景的悬浮小字
fun currentStamp(): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        .format(java.util.Date())

fun drawSignatureNative(
    cv: android.graphics.Canvas,
    s: WatermarkSettings,
    icon: android.graphics.Bitmap?,
    x0: Float, y0: Float, x1: Float, y1: Float,
    scale: Float = 1f,
    timestamp: String? = null,
) {
    val lines = s.sigText.ifEmpty { "Immersion technology\n浸没制造" }
        .replace("\r", "").split("\n").filter { it.isNotEmpty() }.take(2)
    if (lines.isEmpty()) return
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        alpha = 242
        textSize = (s.sigSize.coerceIn(8, 48) * scale).toFloat()
        typeface = Typeface.DEFAULT
        textAlign = Paint.Align.LEFT
    }
    val fm = p.fontMetrics
    val lineH = (fm.descent - fm.ascent) * 1.15f
    var maxW = 0f
    for (ln in lines) maxW = maxOf(maxW, p.measureText(ln))
    val blockH = lineH * lines.size
    val iconH = blockH
    val iconW = iconH
    val gap = iconH * 0.22f
    val padX = 8f * scale
    val padY = 4f * scale
    // 日期(只到天)放进签名底板: 底板高度多留一行, 不再画在底板外面的悬浮小字
    val tsSize = if (s.sigDate && !timestamp.isNullOrBlank())
        (s.sigSize * 0.62f * scale).coerceAtLeast(7f) else 0f
    val dateH = if (tsSize > 0f) tsSize * 1.35f else 0f
    val bw = iconW + gap + maxW + padX * 2
    val bh = blockH + padY * 2 + dateH
    val margin = 16f * scale
    var left = if (s.sigCorner == 1 || s.sigCorner == 3) x1 - margin - bw else x0 + margin
    var top = when (s.sigCorner) {
        0, 1 -> y0 + margin
        2 -> y1 - 72f * scale - bh
        else -> y1 - margin - bh
    }
    // 向屏幕中心收拢: 0% 贴角, 100% 签名中心正好在屏幕中心
    val distPct = (s.sigDistPct.coerceIn(0, 100)) / 100f
    if (distPct > 0f) {
        val cx = (x0 + x1) * 0.5f
        val cy = (y0 + y1) * 0.5f
        left += (cx - (left + bw * 0.5f)) * distPct
        top += (cy - (top + bh * 0.5f)) * distPct
    }
    // 旧项目签名底色不透明度固定 70%, 设置里只调 RGB
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (s.sigBg and 0x00FFFFFF) or 0xB2000000.toInt()
    }
    cv.drawRoundRect(RectF(left, top, left + bw, top + bh), 2f * scale, 2f * scale, bg)
    val iconLeft = left + padX
    val iconTop = top + padY
    if (icon != null) {
        val ratio = icon.width.toFloat() / icon.height.coerceAtLeast(1)
        var dw = iconW
        var dh = iconH
        if (ratio >= 1f) dh = iconW / ratio else dw = iconH * ratio
        val dl = iconLeft + (iconW - dw) * 0.5f
        val dt = iconTop + (iconH - dh) * 0.5f
        val ip = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        cv.drawBitmap(icon, null, RectF(dl, dt, dl + dw, dt + dh), ip)
    } else {
        drawBrandIconNative(cv, iconLeft, iconTop, iconH, 242)
    }
    var ty = top + padY - fm.ascent
    val tx = iconLeft + iconW + gap
    for (ln in lines) {
        cv.drawText(ln, tx, ty, p)
        ty += lineH
    }
    // 日期画在签名底板内部右下角(有背景), 只显示到天
    if (!timestamp.isNullOrBlank() && tsSize > 0f) {
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            alpha = 190
            textSize = tsSize
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.RIGHT
        }
        val maxTw = bw - padX * 2
        val tw = tp.measureText(timestamp)
        if (tw > maxTw) tp.textSize = tsSize * maxTw / tw
        val baseline = top + padY + blockH + tsSize * 1.05f
        cv.drawText(timestamp, left + bw - padX, baseline, tp)
    }
}

// 画布区域屏幕矩形来自引擎 stats: cv=x0,y0,x1,y1
@Composable
fun WatermarkOverlay(
    rect: IntArray,
    s: WatermarkSettings,
    sigIcon: android.graphics.Bitmap? = null,
    modifier: Modifier = Modifier,
) {
    if ((!s.wmOn && !s.sigOn) || rect.size < 4) return
    // 横屏/截图签名底板内显示日期(只到天, 不显示时分秒)
    val tsFmt = androidx.compose.runtime.remember {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
    }
    val tsState = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(tsFmt.format(java.util.Date()))
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            tsState.value = tsFmt.format(java.util.Date())
        }
    }
    Canvas(modifier.fillMaxSize()) {
        val x0 = rect[0].toFloat()
        val y0 = rect[1].toFloat()
        val x1 = rect[2].toFloat()
        val y1 = rect[3].toFloat()
        if (x1 <= x0 || y1 <= y0) return@Canvas
        val native = drawContext.canvas.nativeCanvas
        clipRect(x0, y0, x1, y1) {
            if (s.wmOn) {
                val text = s.wmText.ifBlank { "Endpix" }
                val lines = text.split("\n")
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = s.wmColor
                    alpha = (s.wmAlpha.coerceIn(0, 100) * 255 / 100)
                    textSize = s.wmSize.coerceIn(12, 72).toFloat()
                    typeface = Typeface.DEFAULT_BOLD
                    textAlign = Paint.Align.CENTER
                }
                var maxW = 0f
                for (ln in lines) maxW = maxOf(maxW, p.measureText(ln))
                val lineH = p.textSize * 1.1f
                val blockH = lineH * lines.size
                val cellW = maxW + 80f
                val cellH = blockH + 70f
                val cols = maxOf(2, ((x1 - x0) / cellW).toInt())
                val rows = maxOf(2, ((y1 - y0) / cellH).toInt())
                val stepX = (x1 - x0) / cols
                val stepY = (y1 - y0) / rows
                for (row in 0 until rows) for (col in 0 until cols) {
                    val cx = x0 + stepX * (col + 0.5f)
                    val cy = y0 + stepY * (row + 0.5f)
                    val save = native.save()
                    native.rotate(-30f, cx, cy)
                    var ty = cy - blockH * 0.5f - p.fontMetrics.ascent
                    for (ln in lines) {
                        native.drawText(ln, cx, ty, p)
                        ty += lineH
                    }
                    native.restoreToCount(save)
                }
            }
            if (s.sigOn) {
                drawSignatureNative(native, s, sigIcon, x0, y0, x1, y1,
                    timestamp = tsState.value)
            }
        }
    }
}
