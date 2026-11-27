package com.endpix.nativeapp.ui

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

// PSD / ASE(Aseprite) 图像解码(本地解析, 不依赖系统 BitmapFactory):
//  · PSD: 读合并图像(Image Data), 支持 8/16bit、RGB/灰度/索引色, 原始/RLE 压缩
//  · ASE: 读第 1 帧, 支持 RGBA/灰度/索引色, raw/压缩 cel, 图层可见性/不透明度/调色板
// 输出 Bitmap(必要时按 maxDim 最近邻降采样)。
object ExtraImageDecoders {
    private const val MAX_PIXELS = 16_000_000L   // 单张图上限(约 67MB int 缓冲)

    fun decode(bytes: ByteArray, maxDim: Int = 4096): Bitmap? {
        if (bytes.size < 64) return null
        return try {
            when {
                bytes[0] == '8'.code.toByte() && bytes[1] == 'B'.code.toByte() &&
                    bytes[2] == 'P'.code.toByte() && bytes[3] == 'S'.code.toByte() ->
                    decodePsd(bytes, maxDim).also {
                        android.util.Log.i("EndpixLoad", "PSD decode -> ${it?.width}x${it?.height}")
                    }
                (bytes[4].toInt() and 0xFF) == 0xE0 && (bytes[5].toInt() and 0xFF) == 0xA5 ->
                    decodeAse(bytes, maxDim).also {
                        android.util.Log.i("EndpixLoad", "ASE decode -> ${it?.width}x${it?.height}")
                    }
                else -> null
            }
        } catch (e: Throwable) {
            android.util.Log.i("EndpixLoad", "extra decode failed: ${e.message}")
            null
        }
    }

    // ---------------- PSD ----------------
    private class PSDReader(val b: ByteArray) {
        var p = 0
        fun u8(): Int = b[p++].toInt() and 0xFF
        fun u16be(): Int {
            val v = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF); p += 2; return v
        }
        fun u32be(): Long {
            var v = 0L
            for (i in 0 until 4) v = (v shl 8) or (b[p + i].toLong() and 0xFF)
            p += 4
            return v
        }
        fun skip(n: Int) { p = minOf(b.size, p + n) }
    }

    private fun decodePsd(b: ByteArray, maxDim: Int): Bitmap? {
        val r = PSDReader(b)
        if (r.u32be() != 0x38425053L) return null      // '8BPS'
        if (r.u16be() != 1) return null                // PSD v1
        r.skip(6)
        val channels = r.u16be()
        val h = r.u32be().toInt()
        val w = r.u32be().toInt()
        val depth = r.u16be()
        val mode = r.u16be()                            // 1=Gray 2=RGB 3=Indexed 4=CMYK
        if (w <= 0 || h <= 0 || w > 16834 || h > 16834) return null
        if (w.toLong() * h > MAX_PIXELS) return null
        if (depth != 8 && depth != 16) return null
        if (mode != 1 && mode != 2 && mode != 3) return null

        // Color Mode Data (索引色调色板)
        val cmLen = r.u32be().toInt()
        val cmStart = r.p
        val palette = IntArray(256) { 0xFF000000.toInt() }
        if (mode == 3 && cmLen >= 768) {
            for (i in 0 until 256) {
                val rr = b[cmStart + i].toInt() and 0xFF
                val gg = b[cmStart + 256 + i].toInt() and 0xFF
                val bb = b[cmStart + 512 + i].toInt() and 0xFF
                palette[i] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }
        r.skip(cmLen)
        r.skip(r.u32be().toInt())                       // Image Resources
        r.skip(r.u32be().toInt())                       // Layer & Mask Info
        val comp = r.u16be()                            // 0 raw / 1 RLE

        val bps = depth / 8
        val plane = w * h
        val planes = Array(channels) { ByteArray(plane) }
        if (comp == 0) {
            for (c in 0 until channels) {
                if (r.p + plane * bps > b.size) return null
                for (i in 0 until plane) {
                    planes[c][i] = if (bps == 1) b[r.p + i] else b[r.p + i * 2]  // 16bit 取高字节
                }
                r.p += plane * bps
            }
        } else if (comp == 1) {
            val rowCounts = IntArray(channels * h)
            for (i in rowCounts.indices) rowCounts[i] = r.u16be()
            val row = ByteArray(w * bps + 8)
            for (c in 0 until channels) {
                for (y in 0 until h) {
                    val cnt = rowCounts[c * h + y]
                    var src = r.p
                    val end = minOf(b.size, r.p + cnt)
                    var dx = 0
                    while (src < end && dx < w) {
                        val n = b[src++].toInt()
                        if (n >= 0) {
                            val lit = n + 1
                            for (k in 0 until lit) {
                                if (src >= end || dx >= w) break
                                if (bps == 1) planes[c][y * w + dx] = b[src] else planes[c][y * w + dx] = b[src]
                                src += bps
                                dx++
                            }
                        } else if (n != -128) {
                            val rep = 1 - n
                            val v = if (src < end) b[src] else 0
                            src += bps
                            for (k in 0 until rep) {
                                if (dx >= w) break
                                planes[c][y * w + dx] = v
                                dx++
                            }
                        }
                    }
                    r.p += cnt
                }
            }
        } else {
            return null   // ZIP(2/3) 暂不支持
        }

        val step = maxOf(1, (maxOf(w, h) + maxDim - 1) / maxDim)
        val ow = (w + step - 1) / step
        val oh = (h + step - 1) / step
        val out = IntArray(ow * oh)
        for (oy in 0 until oh) {
            val sy = minOf(h - 1, oy * step)
            for (ox in 0 until ow) {
                val sx = minOf(w - 1, ox * step)
                val i = sy * w + sx
                val rgb: Int = when (mode) {
                    2 -> (0xFF shl 24) or
                        ((planes[0][i].toInt() and 0xFF) shl 16) or
                        ((planes[1][i].toInt() and 0xFF) shl 8) or
                        (planes[2][i].toInt() and 0xFF)
                    1 -> {
                        val g = planes[0][i].toInt() and 0xFF
                        (0xFF shl 24) or (g shl 16) or (g shl 8) or g
                    }
                    else -> palette[planes[0][i].toInt() and 0xFF]
                }
                val a = when {
                    mode == 2 && channels >= 4 -> planes[3][i].toInt() and 0xFF
                    mode == 1 && channels >= 2 -> planes[1][i].toInt() and 0xFF
                    else -> 255
                }
                out[oy * ow + ox] = (a shl 24) or (rgb and 0x00FFFFFF)
            }
        }
        if (ow <= 0 || oh <= 0) return null
        return Bitmap.createBitmap(out, ow, oh, Bitmap.Config.ARGB_8888)
    }

    // ---------------- ASE (Aseprite) ----------------
    private fun u16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun i16(b: ByteArray, o: Int): Int = u16(b, o).let { if (it >= 0x8000) it - 0x10000 else it }

    private fun u32(b: ByteArray, o: Int): Long =
        (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)

    private class AseLayer(val flags: Int, val opacity: Int, val w: Int, val h: Int)

    private fun decodeAse(b: ByteArray, maxDim: Int): Bitmap? {
        if (b.size < 128) return null
        if (u16(b, 4) != 0xA5E0) return null
        val w = u16(b, 8)
        val h = u16(b, 10)
        val depth = u16(b, 12)          // 32 RGBA / 16 gray / 8 indexed
        if (w <= 0 || h <= 0 || w > 16834 || h > 16834) return null
        if (w.toLong() * h > MAX_PIXELS) return null
        val pb = when (depth) { 32 -> 4; 16 -> 2; 8 -> 1; else -> return null }

        var p = 128
        if (p + 16 > b.size) return null
        if (u16(b, p + 4) != 0xF1FA) return null
        val oldChunks = u16(b, p + 6)
        val nChunks = if (oldChunks == 0xFFFF) u32(b, p + 12).toInt() else oldChunks
        var cp = p + 16

        val layers = ArrayList<AseLayer>()
        val palette = IntArray(256) { 0xFF000000.toInt() }
        val cels = ArrayList<IntArray>()   // [layer,x,y,opacity,type,z,w,h,dataStart,dataEnd]
        val celDataEnd = ArrayList<Int>()

        var ci = 0
        while (ci < nChunks && cp + 6 <= b.size) {
            val csize = u32(b, cp).toInt()
            val ctype = u16(b, cp + 4)
            val d0 = cp + 6
            if (csize < 6) break
            val cend = minOf(b.size, cp + csize)
            when (ctype) {
                0x2004 -> {  // Layer
                    val flags = u16(b, d0)
                    val type = u16(b, d0 + 2)
                    val dw = u16(b, d0 + 6)
                    val dh = u16(b, d0 + 8)
                    val op = b[d0 + 12].toInt() and 0xFF
                    layers.add(AseLayer(flags, op, dw, dh))
                    if (type == 1) { /* group layer */ }
                }
                0x2005 -> {  // Cel
                    val li = u16(b, d0)
                    val x = i16(b, d0 + 2)
                    val y = i16(b, d0 + 4)
                    val op = b[d0 + 6].toInt() and 0xFF
                    val type = u16(b, d0 + 7)
                    // 现代格式: type 后有 z-index(2), 压缩 cel 再跟 w/h(2+2)
                    var dp = d0 + 9
                    var cw = 0
                    var ch = 0
                    if (type == 2) {
                        dp += 2                     // z-index
                        cw = u16(b, dp); ch = u16(b, dp + 2); dp += 4
                    } else {
                        dp += 2                     // z-index
                        if (li < layers.size) { cw = layers[li].w; ch = layers[li].h }
                    }
                    if (cw <= 0 || ch <= 0) continue
                    cels.add(intArrayOf(li, x, y, op, type, 0, cw, ch, dp))
                    celDataEnd.add(cend)
                }
                0x2019 -> {  // Palette
                    // 结构: size(4) first(4) last(4) 然后 entries
                    var idx = u32(b, d0 + 4).toInt()
                    val last = u32(b, d0 + 8).toInt()
                    var ep = d0 + 12
                    while (idx <= last && ep + 6 <= cend && idx < 256) {
                        ep += 2                      // flags
                        val rr = b[ep].toInt() and 0xFF
                        val gg = b[ep + 1].toInt() and 0xFF
                        val bb = b[ep + 2].toInt() and 0xFF
                        val aa = b[ep + 3].toInt() and 0xFF
                        palette[idx] = (aa shl 24) or (rr shl 16) or (gg shl 8) or bb
                        ep += 4
                        idx++
                    }
                }
            }
            cp += csize
            ci++
        }

        // 合成第 1 帧
        val out = IntArray(w * h)   // ARGB
        for (k in cels.indices) {
            val c = cels[k]
            val li = c[0]
            val lx = c[1]
            val ly = c[2]
            val celOp = c[3]
            val type = c[4]
            val cw = c[6]
            val ch = c[7]
            val dataStart = c[8]
            val dataEnd = celDataEnd[k]
            if (li >= layers.size) continue
            val lay = layers[li]
            if ((lay.flags and 1) == 0) continue          // 隐藏层
            var px: IntArray? = null
            if (depth == 32) px = IntArray(cw * ch)
            else if (depth == 16) px = IntArray(cw * ch)
            else px = IntArray(cw * ch)
            val got = if (type == 2) {
                inflateCel(b, dataStart, dataEnd, cw, ch, pb, depth, px, palette)
            } else if (type == 0) {
                rawCel(b, dataStart, dataEnd, cw, ch, pb, depth, px, palette)
            } else false
            if (!got) continue
            val lOp = lay.opacity
            for (yy in 0 until ch) {
                val ty = ly + yy
                if (ty < 0 || ty >= h) continue
                for (xx in 0 until cw) {
                    val tx = lx + xx
                    if (tx < 0 || tx >= w) continue
                    val s = px[yy * cw + xx]
                    val sa = (s ushr 24) and 0xFF
                    if (sa == 0) continue
                    val a = sa * celOp * lOp / (255 * 255)
                    if (a <= 0) continue
                    val di = ty * w + tx
                    blendOver(out, di, s, a)
                }
            }
        }

        val step = maxOf(1, (maxOf(w, h) + maxDim - 1) / maxDim)
        val ow = (w + step - 1) / step
        val oh = (h + step - 1) / step
        val dst = IntArray(ow * oh)
        for (oy in 0 until oh) {
            val sy = minOf(h - 1, oy * step)
            for (ox in 0 until ow) {
                val sx = minOf(w - 1, ox * step)
                dst[oy * ow + ox] = out[sy * w + sx]
            }
        }
        return Bitmap.createBitmap(dst, ow, oh, Bitmap.Config.ARGB_8888)
    }

    private fun blendOver(dst: IntArray, di: Int, src: Int, aIn: Int) {
        val sa = aIn.coerceIn(0, 255)
        if (sa == 0) return
        val d = dst[di]
        val da = (d ushr 24) and 0xFF
        val outA = sa + da * (255 - sa) / 255
        if (outA <= 0) { dst[di] = 0; return }
        fun ch(s: Int, dd: Int): Int {
            return (s * sa + dd * da * (255 - sa) / 255) / outA
        }
        val rr = ch((src ushr 16) and 0xFF, (d ushr 16) and 0xFF).coerceIn(0, 255)
        val gg = ch((src ushr 8) and 0xFF, (d ushr 8) and 0xFF).coerceIn(0, 255)
        val bb = ch(src and 0xFF, d and 0xFF).coerceIn(0, 255)
        dst[di] = (outA shl 24) or (rr shl 16) or (gg shl 8) or bb
    }

    private fun inflateCel(
        b: ByteArray, start: Int, end: Int, w: Int, h: Int, pb: Int, depth: Int,
        out: IntArray, palette: IntArray,
    ): Boolean {
        val c = ByteArrayOutputStream()
        val inf = Inflater()
        inf.setInput(b, start, end - start)
        val buf = ByteArray(8192)
        try {
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n <= 0) break
                c.write(buf, 0, n)
            }
        } catch (_: Throwable) {
            return false
        } finally {
            inf.end()
        }
        val d = c.toByteArray()
        var p = 0
        for (y in 0 until h) {
            var x = 0
            while (x < w && p < d.size) {
                val n = d[p++].toInt()
                if (n > 0) {
                    for (k in 0 until n) {
                        if (p + pb > d.size || x >= w) break
                        out[y * w + x] = pixel(d, p, depth, palette)
                        p += pb
                        x++
                    }
                } else if (n < 0) {
                    val rep = -n
                    if (p + pb > d.size) break
                    val v = pixel(d, p, depth, palette)
                    p += pb
                    for (k in 0 until rep) {
                        if (x >= w) break
                        out[y * w + x] = v
                        x++
                    }
                } else break
            }
        }
        return true
    }

    private fun rawCel(
        b: ByteArray, start: Int, end: Int, w: Int, h: Int, pb: Int, depth: Int,
        out: IntArray, palette: IntArray,
    ): Boolean {
        if (end - start < w * h * pb) return false
        var p = start
        for (i in 0 until w * h) {
            out[i] = pixel(b, p, depth, palette)
            p += pb
        }
        return true
    }

    private fun pixel(d: ByteArray, o: Int, depth: Int, palette: IntArray): Int {
        return when (depth) {
            32 -> {
                val rr = d[o].toInt() and 0xFF
                val gg = d[o + 1].toInt() and 0xFF
                val bb = d[o + 2].toInt() and 0xFF
                val aa = d[o + 3].toInt() and 0xFF
                (aa shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
            16 -> {
                val g = d[o].toInt() and 0xFF
                val aa = d[o + 1].toInt() and 0xFF
                (aa shl 24) or (g shl 16) or (g shl 8) or g
            }
            else -> palette[d[o].toInt() and 0xFF]
        }
    }
}
