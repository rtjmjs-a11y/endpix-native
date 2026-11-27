package com.endpix.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ===== 旧项目 ReplayFormat.cs 的本地化实现 =====
// 文件格式(EPREPLAY v1/v2, little-endian):
//   magic[8]="EPREPLAY" | version:int | jsonLen:int | metaJson | frameCount:int
//   frameCount * (seq:int, offset:long, length:int) | 帧数据
//   v1: 每帧完整 PNG; v2: 每帧是画布内一个矩形(PNG/WebP), frameKey 标记完整关键帧
data class ReplayEventMeta(
    val frame: Int, val type: Int, val tool: Int, val toolName: String,
    val color: Int, val brush: Int, val layer: Int, val layerName: String,
    val docFrame: Int, val t: Int, val dur: Int,
    // x,y,dt 三元组(画布坐标, dt=距上一点ms); 旧文件可能为空
    val pts: IntArray = IntArray(0),
)

// 录制时记录的工具/面板操作(打开调色板、选色、切图层、撤销等)
data class ReplayActionMeta(val t: Int, val kind: Int, val label: String, val frame: Int)

data class ReplayMetaInfo(
    val width: Int, val height: Int, val frameCount: Int,
    val layerCount: Int, val docFrameCount: Int, val totalMs: Int,
    val frameSeqs: IntArray, val frameMs: IntArray, val events: List<ReplayEventMeta>,
    val actions: List<ReplayActionMeta> = emptyList(),
    val createdAt: String, val appVersion: String,
    // v2 增量帧: 每帧数据是画布内一个矩形(x,y,w,h), key=true 表示完整关键帧
    val delta: Boolean = false,
    val frameX: IntArray = IntArray(0),
    val frameY: IntArray = IntArray(0),
    val frameW: IntArray = IntArray(0),
    val frameH: IntArray = IntArray(0),
    val frameKey: BooleanArray = BooleanArray(0),
)

data class ReplayIndexEntry(val seq: Int, val offset: Long, val length: Int)

class ReplaySession(
    val file: File,
    val meta: ReplayMetaInfo,
    private val entries: List<ReplayIndexEntry>,
    val displayName: String = file.name,
) {
    val frameCount get() = entries.size
    private val cache = object : LruCache<Int, Bitmap>(192 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    fun frameDurationMs(i: Int): Int =
        meta.frameMs.getOrNull(i)?.coerceIn(40, 30000) ?: 120

    fun elapsedMs(i: Int): Int {
        var s = 0
        for (k in 0 until i.coerceIn(0, frameMsCount)) s += frameDurationMs(k)
        return s
    }
    private val frameMsCount get() = min(entries.size, meta.frameMs.size)

    fun totalMs(): Int {
        val n = frameMsCount
        if (n <= 0) return 0
        var s = 0
        for (k in 0 until n) s += frameDurationMs(k)
        return s
    }

    fun eventForIndex(i: Int): ReplayEventMeta? {
        if (i !in entries.indices) return null
        val seq = entries[i].seq
        return meta.events.firstOrNull { it.frame == seq }
    }

    // 只读缓存: 播放器每帧查一次(不在主线程解码)
    fun cachedFrame(i: Int): Bitmap? = synchronized(cache) { cache.get(i) }

    fun frameBitmap(i: Int, maxSide: Int = 2048): Bitmap? {
        if (i !in entries.indices) return null
        synchronized(cache) { cache.get(i) }?.let { return it }
        return if (!meta.delta) decodeEntry(i, maxSide)?.also { b ->
            synchronized(cache) { cache.put(i, b) }
        } else composeFrame(i, maxSide)
    }

    private fun isKeyFrame(i: Int): Boolean =
        i <= 0 || (meta.frameKey.getOrNull(i) ?: true)

    private fun frameBox(i: Int): IntArray {
        val x = meta.frameX.getOrNull(i) ?: 0
        val y = meta.frameY.getOrNull(i) ?: 0
        val w = meta.frameW.getOrNull(i)?.takeIf { it > 0 } ?: meta.width
        val h = meta.frameH.getOrNull(i)?.takeIf { it > 0 } ?: meta.height
        return intArrayOf(x, y, w, h)
    }

    // v2: 关键帧完整解码, 中间帧只解码变化矩形并叠加到合成图上
    private fun composeFrame(i: Int, maxSide: Int): Bitmap? {
        // 顺序播放时直接复制上一帧合成结果, 避免每帧都从关键帧重放
        if (!isKeyFrame(i)) {
            val prev = synchronized(cache) { cache.get(i - 1) }
            if (prev != null) {
                val base = prev.copy(Bitmap.Config.ARGB_8888, true) ?: return null
                if (applyDelta(base, i, maxSide)) {
                    synchronized(cache) { cache.put(i, base) }
                    return base
                }
            }
        }
        var key = i
        while (key > 0 && !isKeyFrame(key)) key--
        val keyFrame = decodeEntry(key, maxSide) ?: return null
        if (key == i) {
            synchronized(cache) { cache.put(i, keyFrame) }
            return keyFrame
        }
        val base = keyFrame.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        keyFrame.recycle()
        for (j in key + 1..i) {
            if (!applyDelta(base, j, maxSide)) return null
        }
        synchronized(cache) { cache.put(i, base) }
        return base
    }

    private fun applyDelta(base: Bitmap, i: Int, maxSide: Int): Boolean {
        val bmp = decodeEntry(i, maxSide) ?: return false
        val box = frameBox(i)
        val sx = base.width.toFloat() / max(1, meta.width)
        val sy = base.height.toFloat() / max(1, meta.height)
        try {
            val dst = android.graphics.Rect(
                (box[0] * sx).roundToInt(), (box[1] * sy).roundToInt(),
                ((box[0] + box[2]) * sx).roundToInt().coerceAtMost(base.width),
                ((box[1] + box[3]) * sy).roundToInt().coerceAtMost(base.height)
            )
            val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
            android.graphics.Canvas(base).drawBitmap(bmp, null, dst, paint)
        } finally {
            bmp.recycle()
        }
        return true
    }

    private fun decodeEntry(i: Int, maxSide: Int): Bitmap? {
        if (i !in entries.indices) return null
        val e = entries[i]
        return try {
            RandomAccessFile(file, "r").use { raf ->
                if (e.offset < 0 || e.length <= 0 || e.offset + e.length > raf.length()) return null
                val buf = ByteArray(e.length)
                raf.seek(e.offset)
                raf.readFully(buf)
                val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(buf, 0, buf.size, opt)
                var sample = 1
                val longSide = max(opt.outWidth, opt.outHeight)
                if (longSide > 0) while (longSide / sample > maxSide) sample *= 2
                val dop = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeByteArray(buf, 0, buf.size, dop)
            }
        } catch (_: Throwable) { null }
    }

    companion object {
        fun open(file: File, displayName: String = file.name): ReplaySession? {
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    val magic = ByteArray(8)
                    raf.readFully(magic)
                    if (String(magic, Charsets.US_ASCII) != "EPREPLAY") return null
                    val version = readLeInt(raf)
                    if (version != 1 && version != 2) return null
                    // 大回放支持: 元数据 256MB、200 万步、整包 8GB(仍受设备内存/存储约束)
                    if (raf.length() > 8L * 1024 * 1024 * 1024) return null
                    val jsonLen = readLeInt(raf)
                    if (jsonLen <= 0 || jsonLen > 256 * 1024 * 1024) return null
                    val json = ByteArray(jsonLen)
                    raf.readFully(json)
                    val o = JSONObject(String(json, Charsets.UTF_8))
                    val count = readLeInt(raf)
                    if (count <= 0 || count > 2_000_000) return null
                    val entries = ArrayList<ReplayIndexEntry>(min(count, 2_000_000))
                    val fileLen = raf.length()
                    for (i in 0 until count) {
                        val seq = readLeInt(raf)
                        val off = readLeLong(raf)
                        val len = readLeInt(raf)
                        if (off < 0 || len <= 0 || off + len > fileLen) return null
                        entries.add(ReplayIndexEntry(seq, off, len))
                    }
                    val seqs = o.optJSONArray("frameSeqs")
                    val ms = o.optJSONArray("frameMs")
                    val eventsJson = o.optJSONArray("events")
                    val frameSeqs = IntArray(min(count, seqs?.length() ?: 0)) {
                        seqs?.optInt(it) ?: entries[it].seq
                    }
                    val frameMs = IntArray(min(count, ms?.length() ?: 0)) {
                        (ms?.optInt(it) ?: 120).coerceIn(40, 30000)
                    }
                    val events = ArrayList<ReplayEventMeta>()
                    if (eventsJson != null) for (i in 0 until eventsJson.length()) {
                        val e = eventsJson.optJSONObject(i) ?: continue
                        events.add(
                            ReplayEventMeta(
                                frame = e.optInt("frame"),
                                type = e.optInt("type"),
                                tool = e.optInt("tool"),
                                toolName = e.optString("toolName", ""),
                                color = e.optInt("color"),
                                brush = e.optInt("brush", 1),
                                layer = e.optInt("layer"),
                                layerName = e.optString("layerName", ""),
                                docFrame = e.optInt("docFrame"),
                                t = e.optInt("t"),
                                dur = e.optInt("dur"),
                                pts = parsePts(e.optJSONArray("pts")),
                            )
                        )
                    }
                    val actions = ArrayList<ReplayActionMeta>()
                    val actJson = o.optJSONArray("actions")
                    if (actJson != null) for (i in 0 until actJson.length()) {
                        val a = actJson.optJSONObject(i) ?: continue
                        actions.add(
                            ReplayActionMeta(
                                t = a.optInt("t"),
                                kind = a.optInt("kind"),
                                label = a.optString("label", ""),
                                frame = a.optInt("frame", 1),
                            )
                        )
                    }
                    val delta = o.optBoolean("delta", false)
                    fun intArr(name: String, def: Int): IntArray {
                        if (!delta) return IntArray(0)
                        val a = o.optJSONArray(name)
                        return if (a != null && a.length() == count) IntArray(count) { a.optInt(it, def) }
                        else IntArray(count) { def }
                    }
                    fun boolArr(name: String, def: Boolean): BooleanArray {
                        if (!delta) return BooleanArray(0)
                        val a = o.optJSONArray(name)
                        return if (a != null && a.length() == count) BooleanArray(count) {
                            val v = a.opt(it)
                            when (v) {
                                is Boolean -> v
                                is Number -> v.toInt() != 0
                                is String -> v == "1" || v.equals("true", true)
                                else -> def
                            }
                        } else BooleanArray(count) { def }
                    }
                    val frameX = intArr("frameX", 0)
                    val frameY = intArr("frameY", 0)
                    val frameW = intArr("frameW", o.optInt("width", 0))
                    val frameH = intArr("frameH", o.optInt("height", 0))
                    val frameKey = boolArr("frameKey", true)
                    ReplaySession(
                        file, ReplayMetaInfo(
                            width = o.optInt("width"), height = o.optInt("height"),
                            frameCount = o.optInt("frameCount", count),
                            layerCount = o.optInt("layerCount", 1),
                            docFrameCount = o.optInt("docFrameCount", 1),
                            totalMs = o.optInt("totalMs"),
                            frameSeqs = if (frameSeqs.size == count) frameSeqs else IntArray(count) { entries[it].seq },
                            frameMs = if (frameMs.size == count) frameMs else IntArray(count) { 120 },
                            events = events, actions = actions,
                            createdAt = o.optString("createdAt", ""),
                            appVersion = o.optString("appVersion", ""),
                            delta = delta,
                            frameX = frameX, frameY = frameY,
                            frameW = frameW, frameH = frameH, frameKey = frameKey,
                        ), entries, displayName
                    )
                }
            } catch (_: Throwable) { null }
        }

        // JSON 里的 pts: [x,y,dt, x,y,dt, ...]
        private fun parsePts(a: JSONArray?): IntArray {
            if (a == null || a.length() < 3) return IntArray(0)
            val n = (a.length() / 3) * 3
            return IntArray(n) { a.optInt(it) }
        }

        private fun readLeInt(raf: RandomAccessFile): Int {
            val b = ByteArray(4); raf.readFully(b)
            return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
        }
        private fun readLeLong(raf: RandomAccessFile): Long {
            val b = ByteArray(8); raf.readFully(b)
            return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).long
        }
    }
}

object ReplayIO {
    fun isReplay(f: File): Boolean = try {
        RandomAccessFile(f, "r").use { raf ->
            if (raf.length() < 12) false else {
                val b = ByteArray(8); raf.readFully(b)
                String(b, Charsets.US_ASCII) == "EPREPLAY"
            }
        }
    } catch (_: Throwable) { false }

    // meta.csv 第9列: x:y:dt;x:y:dt;...
    private fun parsePtsCsv(s: String): IntArray {
        if (s.isBlank()) return IntArray(0)
        val out = ArrayList<Int>(64)
        for (tok in s.split(';')) {
            val q = tok.split(':')
            if (q.size < 3) continue
            out.add(q[0].toIntOrNull() ?: 0)
            out.add(q[1].toIntOrNull() ?: 0)
            out.add((q[2].toIntOrNull() ?: 0).coerceIn(0, 60000))
            if (out.size >= 120000) break
        }
        return out.toIntArray()
    }

    private fun toolName(tool: Int): String = when (tool) {
        0 -> "画笔"
        1 -> "框选"
        2 -> "橡皮"
        3 -> "油漆桶"
        4 -> "图形"
        5 -> "工具"
        else -> "工具$tool"
    }

    // 把 files/timelapse 的 r_*.png + meta.csv 打包成旧版可读的 .replay
    fun buildFromRecording(dir: File, appVersion: String, out: File): Pair<Boolean, String> {
        return try {
            val pngs = dir.listFiles { f ->
                f.isFile && f.name.startsWith("r_") && f.name.endsWith(".png")
            }?.sortedBy { it.name } ?: emptyList()
            if (pngs.isEmpty()) return false to "没有录制帧，请先在更多工具里开启记录过程并绘制"
            val metaMap = HashMap<Int, LongArray>()
            val ptsMap = HashMap<Int, IntArray>()
            val metaFile = File(dir, "meta.csv")
            if (metaFile.exists()) {
                metaFile.forEachLine { line ->
                    val p = line.trim().split(',', limit = 9)
                    if (p.size >= 8) {
                        val seq = p[0].toIntOrNull() ?: return@forEachLine
                        val vals = LongArray(7)
                        vals[0] = p[1].toLongOrNull() ?: 0L          // t
                        vals[1] = p[2].toLongOrNull() ?: 0L          // dur
                        vals[2] = p[3].toLongOrNull() ?: 0L          // tool
                        vals[3] = p[4].toLongOrNull(16) ?: 0L        // ABGR hex
                        vals[4] = p[5].toLongOrNull() ?: 1L          // brush
                        vals[5] = p[6].toLongOrNull() ?: 0L          // layer
                        vals[6] = p[7].toLongOrNull() ?: 0L          // doc frame
                        metaMap[seq] = vals
                        if (p.size >= 9) ptsMap[seq] = parsePtsCsv(p[8])
                    }
                }
            }
            val frames = ArrayList<Triple<Int, File, LongArray>>()
            for ((idx, f) in pngs.withIndex()) {
                val seq = f.name.removePrefix("r_").removeSuffix(".png").toIntOrNull() ?: (idx + 1)
                val m = metaMap[seq] ?: LongArray(7) { if (it == 0) (idx * 120).toLong() else 0L }
                if (f.length() <= 0 || f.length() > 512L * 1024 * 1024) continue
                frames.add(Triple(seq, f, m))
            }
            if (frames.isEmpty()) return false to "回放帧文件缺失或损坏"
            val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(frames[0].second.absolutePath, opt)
            val w0 = if (opt.outWidth > 0) opt.outWidth else 512
            val h0 = if (opt.outHeight > 0) opt.outHeight else 512
            var layerCount = 1
            var docFrames = 1
            val seqArr = JSONArray()
            val msArr = JSONArray()
            val evArr = JSONArray()
            val times = ArrayList<Long>(frames.size)
            var prevT = 0L
            for ((i, fr) in frames.withIndex()) {
                val (seq, f, m) = fr
                val t = max(prevT, m[0]).coerceAtLeast(0)
                val dur = if (i + 1 < frames.size) {
                    max(40L, max(frames[i + 1].third[0], t) - t)
                } else 120L
                prevT = t
                times.add(t)
                seqArr.put(seq)
                msArr.put(dur.coerceIn(40, 30000))
                val colorNative = m[3].toInt()
                // meta.csv 存的是引擎 ARGB(0xAARRGGBB)，不是 ABGR；旧代码按 ABGR 重排会把红色画成蓝色
                val packed = if ((colorNative ushr 24) != 0) colorNative
                             else colorNative or (0xFF shl 24)
                val brushPx = m[4].toInt().coerceAtLeast(1)
                val layer = m[5].toInt().coerceAtLeast(0)
                val docFrame = m[6].toInt().coerceAtLeast(0)
                layerCount = max(layerCount, layer + 1)
                docFrames = max(docFrames, docFrame + 1)
                val ptsArr = JSONArray()
                ptsMap[seq]?.forEach { ptsArr.put(it) }
                evArr.put(JSONObject().apply {
                    put("frame", seq)
                    put("type", if (m[2].toInt() == 3) 1 else 0)
                    put("tool", m[2].toInt())
                    put("toolName", toolName(m[2].toInt()))
                    put("color", packed)
                    put("brush", brushPx)
                    put("layer", layer)
                    put("layerName", "图层${layer + 1}")
                    put("docFrame", docFrame)
                    put("t", t)
                    put("dur", dur)
                    put("pts", ptsArr)
                })
            }
            // UI 操作(打开调色板/选色/切图层…): 按时间绑定到最近已完成帧
            val actArr = JSONArray()
            val uiFile = File(dir, "ui.csv")
            if (uiFile.exists()) {
                uiFile.forEachLine { line ->
                    val p = line.trim().split(',', limit = 3)
                    if (p.size >= 3) {
                        val at = p[0].toIntOrNull() ?: return@forEachLine
                        val kind = p[1].toIntOrNull() ?: 0
                        val label = p[2].trim()
                        if (label.isEmpty()) return@forEachLine
                        var bind = frames.first().first
                        for (k in frames.indices) {
                            if (k < times.size && times[k] <= at) bind = frames[k].first else break
                        }
                        actArr.put(JSONObject().apply {
                            put("t", max(0, at))
                            put("kind", kind)
                            put("label", label)
                            put("frame", bind)
                        })
                    }
                }
            }
            // v2 紧凑帧: 画笔/橡皮只存这一笔的变化矩形 + 每 60 帧一个完整关键帧;
            // 其他工具/操作存完整帧。功能(步骤/轨迹/随机拖动)与 v1 完全一致。
            val payloads = ArrayList<ByteArray>(frames.size)
            val boxX = IntArray(frames.size)
            val boxY = IntArray(frames.size)
            val boxW = IntArray(frames.size)
            val boxH = IntArray(frames.size)
            val boxKey = BooleanArray(frames.size)
            val keyEvery = 60
            for (i in frames.indices) {
                val (seq, f, m) = frames[i]
                val tool = m[2].toInt()
                val pts = ptsMap[seq]
                var useDelta = i > 0 && i % keyEvery != 0 &&
                    (tool == 0 || tool == 2) && pts != null && pts.size >= 3
                var x = 0; var y = 0; var bw = w0; var bh = h0
                if (useDelta) {
                    var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
                    var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
                    for (p in pts!!.indices step 3) {
                        val px = pts[p]; val py = pts[p + 1]
                        if (px < minX) minX = px
                        if (px > maxX) maxX = px
                        if (py < minY) minY = py
                        if (py > maxY) maxY = py
                    }
                    val brush = m[4].toInt().coerceAtLeast(1)
                    val pad = brush + 16
                    x = (minX - pad).coerceIn(0, w0 - 1)
                    y = (minY - pad).coerceIn(0, h0 - 1)
                    val x1 = (maxX + pad + 1).coerceIn(x + 1, w0)
                    val y1 = (maxY + pad + 1).coerceIn(y + 1, h0)
                    bw = x1 - x
                    bh = y1 - y
                    // 变化区域接近整张画布时不如直接存完整帧
                    if (bw.toLong() * bh > w0.toLong() * h0 * 0.70) useDelta = false
                }
                if (!useDelta) {
                    payloads.add(f.readBytes())
                    boxX[i] = 0; boxY[i] = 0; boxW[i] = w0; boxH[i] = h0
                    boxKey[i] = true
                } else {
                    val src = BitmapFactory.decodeFile(f.absolutePath)
                    val crop = if (src != null) Bitmap.createBitmap(src, x, y, bw, bh) else null
                    src?.recycle()
                    if (crop == null) {
                        payloads.add(f.readBytes())
                        boxX[i] = 0; boxY[i] = 0; boxW[i] = w0; boxH[i] = h0
                        boxKey[i] = true
                    } else {
                        val bos = ByteArrayOutputStream()
                        val fmt = if (android.os.Build.VERSION.SDK_INT >= 30)
                            Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.PNG
                        crop.compress(fmt, 100, bos)
                        crop.recycle()
                        payloads.add(bos.toByteArray())
                        boxX[i] = x; boxY[i] = y; boxW[i] = bw; boxH[i] = bh
                        boxKey[i] = false
                    }
                }
            }
            val fxArr = JSONArray(); val fyArr = JSONArray()
            val fwArr = JSONArray(); val fhArr = JSONArray()
            val fkArr = JSONArray()
            for (i in frames.indices) {
                fxArr.put(boxX[i]); fyArr.put(boxY[i])
                fwArr.put(boxW[i]); fhArr.put(boxH[i])
                fkArr.put(if (boxKey[i]) 1 else 0)
            }
            val meta = JSONObject().apply {
                put("format", "endpix-replay")
                put("version", 2)
                put("appVersion", appVersion)
                put("createdAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
                put("width", w0)
                put("height", h0)
                put("frameCount", frames.size)
                put("layerCount", layerCount)
                put("docFrameCount", docFrames)
                put("frameMaxSide", max(w0, h0))
                put("totalMs", max(0L, prevT).toInt())
                put("truncated", false)
                put("delta", true)
                put("frameSeqs", seqArr)
                put("frameMs", msArr)
                put("frameX", fxArr)
                put("frameY", fyArr)
                put("frameW", fwArr)
                put("frameH", fhArr)
                put("frameKey", fkArr)
                put("events", evArr)
                put("actions", actArr)
            }
            val json = meta.toString().toByteArray(Charsets.UTF_8)
            val lengths = IntArray(frames.size) { payloads[it].size }
            val header = 8 + 4 + 4
            val index = 4 + frames.size * 16
            var off = header.toLong() + json.size + index
            val offsets = LongArray(frames.size)
            for (i in frames.indices) { offsets[i] = off; off += lengths[i] }
            out.parentFile?.mkdirs()
            DataOutputStream(BufferedOutputStream(out.outputStream())).use { os ->
                os.write("EPREPLAY".toByteArray(Charsets.US_ASCII))
                writeLeInt(os, 2)
                writeLeInt(os, json.size)
                os.write(json)
                writeLeInt(os, frames.size)
                for (i in frames.indices) {
                    writeLeInt(os, frames[i].first)
                    writeLeLong(os, offsets[i])
                    writeLeInt(os, lengths[i])
                }
                for (p in payloads) os.write(p)
            }
            true to "已生成 ${frames.size} 帧回放"
        } catch (e: Throwable) {
            false to "生成回放失败: ${e.message}"
        }
    }

    private fun writeLeInt(os: DataOutputStream, v: Int) {
        os.write(v and 0xFF); os.write((v ushr 8) and 0xFF)
        os.write((v ushr 16) and 0xFF); os.write((v ushr 24) and 0xFF)
    }
    private fun writeLeLong(os: DataOutputStream, v: Long) {
        for (i in 0 until 8) os.write(((v ushr (i * 8)) and 0xFF).toInt())
    }
}

// 过程面板「导出到缓存」: MainActivity 注入实现(打包当前录制 → cache_projects/r_*.replay)
object ReplayCacheHub {
    var cacheNow: (() -> Unit)? = null
    fun cache() { cacheNow?.invoke() }
}

// 回放不再自己做一套画布视图: 直接跟随项目画布的 native 视口(平移/缩放/旋转)
object ReplayViewOps {
    var rect: (() -> IntArray)? = null
    var zoom: ((Float, Float, Float) -> Unit)? = null   // factor, screenX, screenY
    var pan: ((Float, Float) -> Unit)? = null
    var fit: (() -> Unit)? = null
    // 回放帧交给 native 项目画布显示(不再由 Compose 自绘一块画布)
    var setFrame: ((Bitmap?) -> Unit)? = null
    var clearFrame: (() -> Unit)? = null
}

// ===== 回放状态(全局): 标签栏/主界面/MainActivity 共用; 避免给 MainScreen 再加参数 =====
object ReplayHub {    var session by mutableStateOf<ReplaySession?>(null)
        private set
    var visible by mutableStateOf(false)
    var mode by mutableIntStateOf(0)          // 0=步骤 1=轨迹
    // 步骤回放步间隔(可调, 40..1500ms); 持久化由 MainActivity 注入
    var stepDelayMs by mutableIntStateOf(120)
        private set
    var persistStepDelay: ((Int) -> Unit)? = null
    var onVisibilityChanged: ((Boolean) -> Unit)? = null
    var logSink: ((String) -> Unit)? = null   // 录制中: UI 操作 → native ui.csv
    var previewSink: (() -> Unit)? = null     // 过程面板「预览当前录制」
    // 切回画布再切回回放时保留播放位置
    var resumeIdx by mutableIntStateOf(0)
    var resumePlaying by mutableStateOf(false)

    fun loadStepDelay(ms: Int) { stepDelayMs = ms.coerceIn(40, 1500) }
    fun setStepDelay(ms: Int, persist: Boolean = true) {
        val v = ms.coerceIn(40, 1500)
        if (v == stepDelayMs) return
        stepDelayMs = v
        if (persist) persistStepDelay?.invoke(v)
    }

    fun open(s: ReplaySession) {
        BrowserHub.close()
        session = s
        visible = true
        resumeIdx = 0
        resumePlaying = true
        onVisibilityChanged?.invoke(true)
    }
    fun close() {
        session = null
        visible = false
        onVisibilityChanged?.invoke(false)
    }
    // 同名操作 400ms 内只记一次(滑杆拖动不刷屏)
    private var lastLabel = ""
    private var lastMs = 0L
    fun log(label: String) {
        if (visible) return
        val now = System.currentTimeMillis()
        if (label == lastLabel && now - lastMs < 400) return
        lastLabel = label
        lastMs = now
        logSink?.invoke(label)
    }
}

@Composable
fun ReplayLayer(canvasSpec: String) {
    val rs = ReplayHub.session ?: return
    val d = canvasSpec.split("×").mapNotNull { it.toIntOrNull() }
    ReplayPlayer(
        rs,
        targetW = d.getOrNull(0) ?: 0,
        targetH = d.getOrNull(1) ?: 0,
        modifier = Modifier.fillMaxSize()
    )
}

// 画布内播放器: 画布工具/色板/底部编辑键全部隐藏, 只留标签栏 + 单指移动/缩放 + 回放控制
// 轨迹模式: 上一帧作底, 当前真实帧按已绘制路径做 DST_IN 遮罩, 合成一张 Bitmap 交给 native 画布
private fun buildReplayTrailFrame(
    base: Bitmap?, final: Bitmap?, ev: ReplayEventMeta?, drawn: Int,
    metaW: Int, metaH: Int,
): Bitmap? {
    if (base == null) return final
    if (final == null || ev == null || ev.pts.size < 3 || drawn <= 0) return base
    val out = base.copy(Bitmap.Config.ARGB_8888, true) ?: return base
    val n = min(drawn, ev.pts.size / 3)
    if (n <= 0) return out
    val c = android.graphics.Canvas(out)
    val save = c.saveLayer(0f, 0f, out.width.toFloat(), out.height.toFloat(), null)
    c.drawBitmap(final, null, android.graphics.Rect(0, 0, out.width, out.height),
        android.graphics.Paint())
    val sx = out.width.toFloat() / max(1, metaW)
    val sy = out.height.toFloat() / max(1, metaH)
    val mp = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.WHITE
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
    }
    val path = android.graphics.Path()
    val x0 = ev.pts[0] * sx
    val y0 = ev.pts[1] * sy
    path.moveTo(x0, y0)
    for (p in 1 until n) path.lineTo(ev.pts[p * 3] * sx, ev.pts[p * 3 + 1] * sy)
    val sw = max(1f, ev.brush * min(sx, sy) * 1.8f)
    if (n <= 1) {
        mp.style = android.graphics.Paint.Style.FILL
        c.drawCircle(x0, y0, sw * 0.5f, mp)
    } else {
        mp.style = android.graphics.Paint.Style.STROKE
        mp.strokeWidth = sw
        mp.strokeCap = android.graphics.Paint.Cap.ROUND
        mp.strokeJoin = android.graphics.Paint.Join.ROUND
        c.drawPath(path, mp)
    }
    c.restoreToCount(save)
    return out
}

@Composable
fun ReplayPlayer(
    session: ReplaySession,
    targetW: Int = 0,
    targetH: Int = 0,
    modifier: Modifier = Modifier,
) {
    val meta = session.meta
    val last = session.frameCount - 1
    val cfg = LocalConfiguration.current
    val landscape = cfg.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val screenDensity = LocalDensity.current.density
    val zoomCx = cfg.screenWidthDp * screenDensity * 0.5f
    val zoomCy = cfg.screenHeightDp * screenDensity * 0.5f
    // 播放布局完全使用项目画布 native 视口; targetW/H 保留用于兼容调用方
    val canvasW = if (targetW > 0) targetW else max(1, meta.width)
    val canvasH = if (targetH > 0) targetH else max(1, meta.height)
    BackHandler { ReplayHub.close() }
    var idx by remember(session) {
        mutableIntStateOf(ReplayHub.resumeIdx.coerceIn(0, last.coerceAtLeast(0)))
    }
    var playing by remember(session) { mutableStateOf(ReplayHub.resumePlaying) }
    val mode = ReplayHub.mode
    val stepDelay = ReplayHub.stepDelayMs
    var scale by remember(session) { mutableFloatStateOf(1f) }
    var offX by remember(session) { mutableFloatStateOf(0f) }
    var offY by remember(session) { mutableFloatStateOf(0f) }
    var oneFingerMove by remember(session) { mutableStateOf(true) }
    var decodeTick by remember(session) { mutableIntStateOf(0) }
    var seekGen by remember(session) { mutableIntStateOf(0) }
    var trailSeq by remember(session) { mutableIntStateOf(-1) }
    var trailBase by remember(session) { mutableIntStateOf(-1) }
    var trailTo by remember(session) { mutableIntStateOf(-1) }
    var trailDrawn by remember(session) { mutableIntStateOf(0) }

    // 切回画布时记住当前位置; 暂停/切模式时收起半截轨迹
    DisposableEffect(session) {
        onDispose {
            ReplayHub.resumeIdx = idx
            ReplayHub.resumePlaying = false
            ReplayViewOps.clearFrame?.invoke()
        }
    }
    LaunchedEffect(playing, mode) {
        if (!playing || mode != 1) {
            trailSeq = -1
            trailBase = -1
            trailTo = -1
            trailDrawn = 0
        }
    }

    // 后台预解码 idx..idx+4; 主线程只查 LruCache(步骤模式 60 步/秒也不卡 UI)
    LaunchedEffect(session) {
        withContext(Dispatchers.IO) {
            while (true) {
                val cur = idx
                for (k in 0..4) {
                    val f = cur + k
                    if (f in 0..last && session.cachedFrame(f) == null) {
                        session.frameBitmap(f, 2048)
                        decodeTick++
                    }
                }
                delay(12)
            }
        }
    }

    // 步骤模式: 按可调步延迟推进(默认120ms, 40..1500ms)
    LaunchedEffect(playing, mode, seekGen, session, stepDelay) {
        if (!playing || mode != 0) return@LaunchedEffect
        while (playing) {
            if (idx >= last) { playing = false; break }
            delay(stepDelay.toLong())
            if (!playing) break
            while (playing && session.cachedFrame(idx + 1) == null) delay(8)
            if (!playing) break
            idx += 1
        }
    }

    // 轨迹模式: 先等录制间隔, 再把真实帧按已绘制轨迹裁出来逐点显影(不是蓝色占位线)
    LaunchedEffect(playing, mode, seekGen, session) {
        if (!playing || mode != 1) return@LaunchedEffect
        var i = idx
        while (playing && i <= last) {
            if (i > 0) {
                val gap = session.frameDurationMs(i - 1)
                var waited = 0
                while (playing && waited < gap) { delay(16); waited += 16 }
            }
            if (!playing) break
            if (i > 0) while (playing && session.cachedFrame(i - 1) == null) delay(16)
            // 真实帧要先解好, 才能边画边显影
            while (playing && session.cachedFrame(i) == null) delay(16)
            val ev = session.eventForIndex(i)
            val pts = ev?.pts
            if (ev != null && pts != null && pts.size >= 3 && (ev.tool == 0 || ev.tool == 2)) {
                trailSeq = ev.frame
                trailBase = i - 1
                trailTo = i
                trailDrawn = 1
                val n = pts.size / 3
                for (p in 1 until n) {
                    if (!playing) break
                    val dt = pts[p * 3 + 2]
                    if (dt > 0) delay(dt.coerceAtMost(5000).toLong())
                    trailDrawn = p + 1
                }
                trailSeq = -1
                trailBase = -1
                trailTo = -1
                trailDrawn = 0
            }
            if (!playing) break
            idx = i
            i++
        }
        if (i > last && playing) playing = false
    }

    // decodeTick 让缓存填充后重组; 轨迹动画时用上一帧作底, 当前真实帧作显影源
    val tick = decodeTick
    val baseIdx = if (trailSeq >= 0 && trailBase >= 0) trailBase else idx
    val bmp = if (tick >= 0) session.cachedFrame(baseIdx) else null
    val finalBmp = if (trailSeq >= 0 && trailTo >= 0) session.cachedFrame(trailTo) else null
    val trailEv = if (trailSeq >= 0) meta.events.firstOrNull { it.frame == trailSeq } else null
    val curEv = session.eventForIndex(idx)
    val curSeq = meta.frameSeqs.getOrNull(idx) ?: 0
    val actLabel = meta.actions.lastOrNull {
        if (mode == 0) it.frame <= curSeq else it.t <= session.elapsedMs(idx)
    }?.label ?: ""

    // 把当前回放帧推给 native 项目画布; 轨迹模式在主线程外合成遮罩图
    LaunchedEffect(session, bmp, finalBmp, trailDrawn, mode, trailSeq) {
        val frame = if (mode == 1 && trailSeq >= 0) {
            withContext(Dispatchers.Default) {
                buildReplayTrailFrame(bmp, finalBmp, trailEv, trailDrawn, meta.width, meta.height)
            }
        } else bmp
        ReplayViewOps.setFrame?.invoke(frame)
    }

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                // 画布内手势: 单指移动(可关)/双指缩放平移; 双指始终可用
                .pointerInput(oneFingerMove) {
                    awaitEachGesture {
                        var active = true
                        while (active) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.count { it.pressed }
                            if (pressed == 0) { active = false; break }
                            if (pressed >= 2) {
                                val z = ev.calculateZoom()
                                val pan = ev.calculatePan()
                                if (z > 0f && z != 1f) {
                                    val c = ev.calculateCentroid()
                                    val zoomOp = ReplayViewOps.zoom
                                    if (zoomOp != null) zoomOp(z, c.x, c.y)
                                    else {
                                        scale = (scale * z).coerceIn(1f, 12f)
                                        if (scale <= 1.001f) { offX = 0f; offY = 0f }
                                    }
                                }
                                val panOp = ReplayViewOps.pan
                                if (panOp != null) panOp(pan.x, pan.y)
                                else { offX += pan.x; offY += pan.y }
                            } else if (oneFingerMove) {
                                val pan = ev.calculatePan()
                                val panOp = ReplayViewOps.pan
                                if (panOp != null) panOp(pan.x, pan.y)
                                else { offX += pan.x; offY += pan.y }
                            }
                        }
                    }
                }
                .pointerInput(session) {
                    detectTapGestures(
                        onTap = {
                            if (idx >= last) { idx = 0; seekGen++ }
                            playing = !playing
                        },
                        onDoubleTap = {
                            val f = ReplayViewOps.fit
                            if (f != null) f() else { scale = 1f; offX = 0f; offY = 0f }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            if (bmp == null) Text("正在读取回放…", color = Ep.Sub, fontSize = 11.sp)
        }

        // 回放模式(左上, 避开标签栏)
        Row(
            Modifier.align(Alignment.TopStart)
                .padding(start = 10.dp, top = if (landscape) 60.dp else 86.dp)
        ) {
            RpChip("步骤", mode == 0) { ReplayHub.mode = 0 }
            Spacer(Modifier.width(4.dp))
            RpChip("轨迹", mode == 1) { ReplayHub.mode = 1 }
        }

        // 画布内只保留移动/缩放; 横屏放右侧, 竖屏放左侧
        Column(
            Modifier.align(if (landscape) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(start = if (landscape) 0.dp else 10.dp,
                    end = if (landscape) 10.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            RpChip("移动", oneFingerMove) { oneFingerMove = !oneFingerMove }
            RpBtn("+") {
                val z = ReplayViewOps.zoom
                if (z != null) z(1.5f, zoomCx, zoomCy)
                else scale = (scale * 1.5f).coerceIn(1f, 12f)
            }
            RpBtn("−") {
                val z = ReplayViewOps.zoom
                if (z != null) z(1f / 1.5f, zoomCx, zoomCy)
                else {
                    scale = (scale / 1.5f).coerceIn(1f, 12f)
                    if (scale <= 1.001f) { offX = 0f; offY = 0f }
                }
            }
            RpBtn("⤢") {
                val f = ReplayViewOps.fit
                if (f != null) f() else { scale = 1f; offX = 0f; offY = 0f }
            }
        }

        // 画布上的播放进度
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color(0xD911151C))
                .clickable(interactionSource = null, indication = null) { }
                .padding(horizontal = if (landscape) 18.dp else 12.dp,
                    vertical = if (landscape) 4.dp else 7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (actLabel.isNotEmpty()) "操作 · $actLabel"
                    else (curEv?.toolName ?: "回放"),
                    color = Ep.Sub, fontSize = 8.5.sp, maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (mode == 0) "步骤回放" else "轨迹回放",
                    color = Ep.Accent, fontSize = 8.5.sp
                )
            }
            Box(
                Modifier.fillMaxWidth().height(24.dp).padding(vertical = 7.dp)
                    .pointerInput(session) {
                        detectTapGestures { p ->
                            val f = (p.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                            idx = (f * last).roundToInt().coerceIn(0, last)
                            playing = false
                            seekGen++
                        }
                    }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val cy = size.height / 2f
                    drawRect(Color(0xFF2A3038), Offset(0f, cy - 1.5f), Size(size.width, 3f))
                    val frac = if (last <= 0) 0f else idx.toFloat() / last
                    drawRect(Ep.Accent, Offset(0f, cy - 1.5f), Size(size.width * frac, 3f))
                    // 每一步的刻度(最多 120 根)
                    val steps = min(last, 120)
                    if (steps > 1) for (k in 0..steps) {
                        val x = size.width * k / steps
                        drawRect(Color(0x33FFFFFF), Offset(x, cy - 2.5f), Size(1f, 5f))
                    }
                    // 工具操作刻度
                    val total = max(1, session.totalMs()).toFloat()
                    for (a in meta.actions) {
                        val af = if (mode == 0) {
                            val fi = meta.frameSeqs.indexOf(a.frame)
                            if (fi < 0) -1f else fi.toFloat() / max(1, last)
                        } else a.t.toFloat() / total
                        if (af in 0f..1f) {
                            drawRect(Color(0xFF9BA3AE), Offset(size.width * af, cy - 4.5f), Size(1f, 9f))
                        }
                    }
                    drawCircle(Color.White, 3.5f, Offset(size.width * frac, cy))
                }
            }
            if (mode == 0 && !landscape) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("步延迟", color = Ep.Sub, fontSize = 8.5.sp)
                    Spacer(Modifier.width(6.dp))
                    Slider(
                        value = stepDelay.toFloat(),
                        onValueChange = { ReplayHub.setStepDelay(it.roundToInt(), persist = false) },
                        onValueChangeFinished = { ReplayHub.setStepDelay(ReplayHub.stepDelayMs) },
                        valueRange = 40f..1500f,
                        modifier = Modifier.weight(1f).height(22.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("${stepDelay}ms", color = Ep.Text, fontSize = 8.5.sp,
                        modifier = Modifier.width(46.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RpBtn("⏮") { playing = false; idx = (idx - 1).coerceAtLeast(0); seekGen++ }
                Spacer(Modifier.width(4.dp))
                RpBtn(if (playing) "⏸" else "▶") {
                    if (idx >= last) { idx = 0; seekGen++ }
                    playing = !playing
                }
                Spacer(Modifier.width(4.dp))
                RpBtn("⏭") { playing = false; idx = (idx + 1).coerceAtMost(last); seekGen++ }
                Spacer(Modifier.width(10.dp))
                Text("步 ${idx + 1}/${session.frameCount}", color = Ep.Text, fontSize = 9.sp)
                if (mode == 0 && landscape) {
                    Spacer(Modifier.width(12.dp))
                    Text("步延迟", color = Ep.Sub, fontSize = 8.5.sp)
                    Slider(
                        value = stepDelay.toFloat(),
                        onValueChange = { ReplayHub.setStepDelay(it.roundToInt(), persist = false) },
                        onValueChangeFinished = { ReplayHub.setStepDelay(ReplayHub.stepDelayMs) },
                        valueRange = 40f..1500f,
                        modifier = Modifier.width(150.dp).height(22.dp)
                    )
                    Text("${stepDelay}ms", color = Ep.Text, fontSize = 8.5.sp,
                        modifier = Modifier.width(46.dp))
                }
                Spacer(Modifier.weight(1f))
                Text("${fmt(session.elapsedMs(idx))} / ${fmt(session.totalMs())}",
                    color = Ep.Sub, fontSize = 8.5.sp)
            }
        }
    }
}

@Composable
private fun RpChip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(24.dp)
            .background(if (on) Ep.HighlightSel else Color(0xCC161B22), RoundedCornerShape(4.dp))
            .border(1.dp, if (on) Ep.HighlightSel else Ep.Line, RoundedCornerShape(4.dp))
            .clickable(interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (on) Color.Black else Ep.Text, fontSize = 9.5.sp)
    }
}

@Composable
private fun RpBtn(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(26.dp)
            .background(Ep.Panel2, RoundedCornerShape(3.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(3.dp))
            .clickable(interactionSource = null, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(label, color = Ep.Text, fontSize = 11.sp) }
}

@Composable
private fun ReplayBtn(label: String, onClick: () -> Unit) {
    Box(
        Modifier.height(30.dp).widthIn(min = 38.dp)
            .background(Ep.Panel2, RoundedCornerShape(3.dp))
            .border(1.dp, Ep.Line, RoundedCornerShape(3.dp))
            .clickable(interactionSource = null, indication = null) { onClick() }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Ep.Text, fontSize = 13.sp) }
}

private fun fmt(ms: Int): String {
    val s = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun ProcessDialog(
    recordOn: Boolean,
    frameCount: Int,
    onToggleRecord: (Boolean) -> Unit,
    onSave: () -> Unit,
    onPreview: () -> Unit = {},
    onClose: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = null, indication = null) { onClose() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.width(292.dp).background(Ep.Panel, RoundedCornerShape(6.dp))
                .border(1.dp, Ep.Line, RoundedCornerShape(6.dp)).padding(14.dp)
                .clickable(interactionSource = null, indication = null) { }
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("过程 / 回放", color = Ep.Text, fontSize = 14.sp)
                    Text("记录绘画过程并导出 .replay 回放文件", color = Ep.Sub, fontSize = 8.5.sp)
                }
                Box(
                    Modifier.size(26.dp).background(Ep.Panel2, RoundedCornerShape(3.dp))
                        .clickable(interactionSource = null, indication = null) { onClose() },
                    contentAlignment = Alignment.Center
                ) { Text("✕", color = Ep.Sub, fontSize = 12.sp) }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().background(Ep.Panel2, RoundedCornerShape(4.dp))
                    .padding(10.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(8.dp).background(
                        if (recordOn) Color(0xFF54D66A) else Ep.Sub, RoundedCornerShape(4.dp)
                    )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (recordOn) "录制中 · 已记录 $frameCount 帧" else "未录制 · 已记录 $frameCount 帧",
                    color = Ep.Text, fontSize = 10.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "开启后每笔/每次操作自动存一帧（工具、颜色、笔刷、层、时间）。\n" +
                    "导出为 EPREPLAY 回放；系统文件管理器可能显示为 .replay.txt。\n" +
                    "「预览」直接在画布上回放当前录制；外部回放文件用「加载文件」打开。",
                color = Ep.Sub, fontSize = 8.5.sp, lineHeight = 12.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.weight(1f).height(32.dp).background(Ep.Panel2, RoundedCornerShape(3.dp))
                        .border(1.dp, Ep.Line, RoundedCornerShape(3.dp))
                        .clickable(interactionSource = null, indication = null) {
                            onToggleRecord(!recordOn)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (recordOn) "停止录制" else "开始新录制", color = Ep.Text, fontSize = 10.sp)
                }
                Box(
                    Modifier.weight(1f).height(32.dp)
                        .background(if (frameCount > 0) Ep.Accent else Ep.Panel2, RoundedCornerShape(3.dp))
                        .border(1.dp, Ep.Line, RoundedCornerShape(3.dp))
                        .clickable(interactionSource = null, indication = null) {
                            if (frameCount > 0) onSave()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("保存过程 (.replay)",
                        color = if (frameCount > 0) Color.Black else Ep.Sub, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.fillMaxWidth().height(30.dp)
                    .background(Ep.Panel2, RoundedCornerShape(3.dp))
                    .border(1.dp, Ep.Line, RoundedCornerShape(3.dp))
                    .clickable(interactionSource = null, indication = null) {
                        if (frameCount > 0) onPreview()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("预览当前录制（画布回放）",
                    color = if (frameCount > 0) Ep.Text else Ep.Sub, fontSize = 10.sp)
            }
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.fillMaxWidth().height(30.dp)
                    .background(Ep.Panel2, RoundedCornerShape(3.dp))
                    .border(1.dp, Ep.Line, RoundedCornerShape(3.dp))
                    .clickable(interactionSource = null, indication = null) {
                        if (frameCount > 0) ReplayCacheHub.cache()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("导出到缓存（缓存列表可立即打开）",
                    color = if (frameCount > 0) Ep.Text else Ep.Sub, fontSize = 10.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (recordOn) "重新开始会清空本轮已记录帧" else "开始新录制会清空上一轮已记录帧",
                color = Ep.Sub, fontSize = 8.sp
            )
        }
    }
}
