package com.endpix.nativeapp.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.roundToInt

// [本地化旧项目] 添加文字：4 套像素字体 + 整数倍放大 + alpha 二值化 1:1 像素
object TextTool {
    data class FontSpec(val label: String, val asset: String, val basePx: Int)

    val FONTS = listOf(
        FontSpec("方舟12", "fonts/ArkPixel12.otf", 12),
        FontSpec("丁卯9", "fonts/DingMao9.ttf", 9),
        FontSpec("薇望12", "fonts/Vonwaon12.ttf", 12),
        FontSpec("天王19", "fonts/Tianwang19.ttf", 19),
    )

    private val typefaces = HashMap<String, Typeface>()

    private fun typeface(ctx: Context, asset: String): Typeface {
        typefaces[asset]?.let { return it }
        val tf = try {
            Typeface.createFromAsset(ctx.assets, asset)
        } catch (_: Throwable) {
            Typeface.DEFAULT
        }
        typefaces[asset] = tf
        return tf
    }

    /**
     * 按"1 字体像素 = 1 画布像素"渲染，再整数倍最近邻放大。
     * 关抗锯齿/子像素/线性采样，最后 alpha 二值化，得到硬边像素字。
     */
    fun render(ctx: Context, text: String, fontIdx: Int, mult: Int, color: Int): Bitmap? {
        if (text.isBlank()) return null
        val spec = FONTS.getOrNull(fontIdx) ?: FONTS[0]
        val paint = Paint().apply {
            isAntiAlias = false
            isSubpixelText = false
            isLinearText = false
            isFilterBitmap = false
            isDither = false
            isFakeBoldText = false
            typeface = typeface(ctx, spec.asset)
            textSize = spec.basePx.toFloat()
            this.color = 0xFFFFFFFF.toInt()
            textAlign = Paint.Align.LEFT
            hinting = Paint.HINTING_ON
        }
        val fm = paint.fontMetrics
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        // 裁剪框按字体单元格走（字号×倍数），不再按字形墨迹收紧：
        // 这样 12px 的 4x 文字选区高度≈48，和界面上的字号对得上。
        val glyphH = ceil((fm.descent - fm.ascent).toDouble()).toInt().coerceAtLeast(1)
        val lineAdvance = glyphH + 2
        val widths = IntArray(lines.size) { i ->
            ceil(paint.measureText(lines[i]).toDouble()).toInt().coerceAtLeast(1)
        }
        val w = widths.maxOrNull() ?: 1
        val h = glyphH + (lines.size - 1) * lineAdvance
        if (w <= 0 || h <= 0) return null

        val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(raw)
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        val firstBase = (-fm.ascent).roundToInt()
        for (i in lines.indices) {
            c.drawText(lines[i], 0f, (firstBase + i * lineAdvance).toFloat(), paint)
        }

        // alpha 二值化 + 上色
        val px = IntArray(w * h)
        raw.getPixels(px, 0, w, 0, 0, w, h)
        val rgb = color and 0x00FFFFFF
        var ink = false
        for (i in px.indices) {
            val a = (px[i] ushr 24) and 0xFF
            if (a >= 96) {
                px[i] = (0xFF shl 24) or rgb
                ink = true
            } else {
                px[i] = 0
            }
        }
        raw.setPixels(px, 0, w, 0, 0, w, h)
        if (!ink) {
            raw.recycle()
            return null
        }

        val m = mult.coerceIn(1, 4)
        if (m == 1) return raw
        val scaled = Bitmap.createScaledBitmap(raw, w * m, h * m, false)
        raw.recycle()
        return scaled
    }
}

// MainActivity 注入：文字 -> 浮层选区（复用汇入图像/框选那套拖动确认）
object TextOps {
    var create: ((String, Int, Int, Int) -> Unit)? = null
}
