package com.endpix.nativeapp.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

// 白噪音混合设置: 四个自然声层各自独立音量 + 总音量, 可混合使用。
data class NoiseMix(
    val on: Boolean = false,
    val master: Float = 0.65f,
    val rain: Float = 0.5f,
    val wind: Float = 0f,
    val waves: Float = 0f,
    val fire: Float = 0f,
    // 基础噪声(myNoise 思路): 8 段倍频程频谱 + 语音遮蔽 + 音量
    val colorLevel: Float = 0f,
    val bands: List<Float> = List(8) { 1f },   // 60/125/250/500/1k/2k/4k/8k 增益
    val ieq: Int = 0,              // 0关 1均衡 2全补偿(等响近似)
    val width: Int = 2,            // 0单声道 1窄 2标准 3宽
    val speed: Int = 1,            // 0慢 1标准 2快
    val speechBlock: Boolean = false,
    val scene: Int = -1,           // 当前场景预设索引(-1=自定义)
    // 动效: 各层音量缓慢变化(柔和随机漂移 / 硬切跳变)
    val animOn: Boolean = false,
    val animMode: Int = 0,              // 0柔和 1硬切
    val animCount: Int = 2,             // 参与层数 1..4
    val animSpeed: Int = 3,             // 0..6 = ÷8 ÷4 ÷2 1x ×2 ×4 ×8
    val animLow: Float = 0.5f,          // 动画下限(相对滑杆值)
    val animHigh: Float = 1.25f,        // 动画上限
    // 双耳/等时节拍(需耳机; 等时可外放)
    val binaural: Int = 0,              // 0关 1双耳 2等时
    val beatHz: Int = 10,               // 差频/脉冲频率
    val binauralLevel: Float = 0f,
    // 睡眠定时: N 分钟后淡出停止(0=关)
    val sleepMin: Int = 0,
    // 更多本地音效发生器
    val fx: Int = 0,               // 0关 1交通 2火车 3虫鸣 4雷声 5电流 6空调 7洗衣机 8瀑布
    val fxLevel: Float = 0f,
    val tone: Int = 0,             // 0关 1Solfeggio 2Shepard 3 40Hz 4脉冲 5扫描 6神经调制
    val toneLevel: Float = 0f,
    val solfeggioHz: Int = 528,
)

// 自然白噪音 V4(纯本地实时合成, 无音频文件):
// 参考 soundbox.fun 的 rain+coffee 环境声录音做频谱对齐(用真实录音分析出各倍频程能量分布再反推合成参数):
//  · 雨: 100Hz 低频雨幕(粉噪两级 55Hz 高通 + 100Hz 宽 Q 带通) + 500Hz 起 -4dB/oct 的柔和宽带沙沙
//        + 极轻的宽带雨点颗粒(2.5k 低通, 无谐振, 不会像敲金属)
//  · 风: 粉噪双带通(中心1/f随机漂移) + 阵风包络 + 低频风体
//  · 海浪: 双周期涌浪 + 浪峰泡沫(上升沿门控) + 退浪水声
//  · 篝火: 低频轰鸣 + 细碎嘶声 + 尖锐/沉闷两类爆裂(噪声共振脉冲)
//  · 咖啡厅: 150-800Hz 人声嘈杂 + 3-5Hz 起伏 + 极低房间底噪(隔墙听人说话的嗡嗡感)
//  · 全局 Freeverb(Schroeder 8 comb + 4 allpass) 空间混响把各层粘成"一段录音"
// 开关 1.2s 淡入淡出; 层音量 0.35s 平滑; AudioTrack MODE_STREAM 实时推流(无循环接缝)。
object NatureNoise {
    private const val SR = 44100
    private const val BLOCK = 1024
    private const val FADE_SEC = 1.2f

    // 8 段倍频程中心频率(与 myNoise 的频段布局一致)
    val BAND_FC = floatArrayOf(60f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f)
    val BAND_LABELS = arrayOf("60", "125", "250", "500", "1k", "2k", "4k", "8k")
    // myNoise 动画速度档: ÷8 ÷4 ÷2 1x ×2 ×4 ×8
    private val ANIM_SPEEDS = floatArrayOf(0.125f, 0.25f, 0.5f, 1f, 2f, 4f, 8f)
    // 双耳节拍载波
    private val BIN_CARRIERS = doubleArrayOf(200.0, 320.0)

    val FX_NAMES = arrayOf("关", "交通", "火车", "虫鸣", "雷声", "电流", "空调", "洗衣机", "瀑布")
    val TONE_NAMES = arrayOf("关", "音阶", "Shepard", "40Hz", "脉冲", "扫描", "神经")
    val SOLFEGGIO = intArrayOf(396, 417, 528, 639, 741, 852, 963)

    data class Scene(
        val name: String,
        val bands: List<Float>,
        val speech: Boolean,
        val base: Float,
        val rain: Float = 0f,
        val wind: Float = 0f,
        val waves: Float = 0f,
        val fire: Float = 0f,
    )

    // 本地缝合的场景预设: 噪声颜色 4 种 + 生活场景 4 种 + 语音遮蔽 + 4 个自然声
    val SCENES: List<Scene> = listOf(
        Scene("白噪", listOf(0.45f, 0.6f, 0.8f, 0.95f, 1.1f, 1.25f, 1.45f, 1.65f), false, 0.55f),
        Scene("粉噪", List(8) { 1f }, false, 0.55f),
        Scene("棕噪", listOf(1.9f, 1.5f, 1.15f, 0.9f, 0.7f, 0.55f, 0.45f, 0.38f), false, 0.55f),
        Scene("灰噪", listOf(1.5f, 1.2f, 1f, 0.9f, 0.9f, 1f, 1.25f, 1.5f), false, 0.55f),
        Scene("机舱", listOf(1.7f, 1.5f, 1f, 0.65f, 0.55f, 0.7f, 0.9f, 0.95f), false, 0.5f),
        Scene("风扇", listOf(1.3f, 1.4f, 1.2f, 1f, 0.9f, 0.85f, 0.9f, 0.95f), false, 0.5f),
        Scene("数据中心", listOf(1f, 1.1f, 1.3f, 1.15f, 1f, 1.1f, 1.25f, 1.15f), false, 0.5f),
        Scene("办公室", listOf(0.7f, 0.8f, 0.95f, 1.35f, 1.45f, 1.3f, 1f, 0.8f), false, 0.45f),
        Scene("语音遮蔽", listOf(0.6f, 0.7f, 0.9f, 1.5f, 1.6f, 1.5f, 1.1f, 0.7f), true, 0.5f),
        Scene("雨", List(8) { 1f }, false, 0f, rain = 1f),
        Scene("风", List(8) { 1f }, false, 0f, wind = 1f),
        Scene("海浪", List(8) { 1f }, false, 0f, waves = 1f),
        Scene("篝火", List(8) { 1f }, false, 0f, fire = 1f),
    )

    fun applyScene(m: NoiseMix, idx: Int): NoiseMix {
        if (idx < 0 || idx >= SCENES.size) return m
        val sc = SCENES[idx]
        return m.copy(
            bands = sc.bands, speechBlock = sc.speech, colorLevel = sc.base,
            rain = sc.rain, wind = sc.wind, waves = sc.waves, fire = sc.fire,
            master = m.master, on = m.on, scene = idx
        )
    }

    @Volatile private var engineMix = NoiseMix()
    private var thread: Thread? = null
    private val lock = Any()

    fun apply(m: NoiseMix) {
        engineMix = m
        if (m.on) startThread()
    }

    fun suspendEngine() {
        engineMix = engineMix.copy(on = false)
    }

    fun resumeEngine(m: NoiseMix) {
        engineMix = m
        if (m.on) startThread()
    }

    private fun startThread() {
        synchronized(lock) {
            if (thread != null) return
            val t = Thread({ runLoop() }, "nature-noise")
            t.isDaemon = true
            t.priority = Thread.NORM_PRIORITY + 1
            thread = t
            t.start()
        }
    }

    private fun runLoop() {
        val minBuf = try {
            AudioTrack.getMinBufferSize(
                SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
        } catch (_: Throwable) {
            -1
        }
        val bufBytes = maxOf(if (minBuf > 0) minBuf else BLOCK * 8, BLOCK * 4 * 2)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SR)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufBytes)
                .build()
        } catch (_: Throwable) {
            null
        }
        if (track == null) {
            synchronized(lock) { thread = null }
            return
        }
        val synth = Synth(SR)
        val out = ShortArray(BLOCK * 2)
        try {
            track.play()
            while (true) {
                val alive = synth.render(out, BLOCK, engineMix)
                var written = 0
                while (written < BLOCK) {
                    val w = track.write(out, written * 2, (BLOCK - written) * 2)
                    if (w <= 0) break
                    written += w
                }
                if (!alive) break
            }
        } catch (_: Throwable) {
        } finally {
            try { track.stop() } catch (_: Throwable) { }
            try { track.release() } catch (_: Throwable) { }
            synchronized(lock) { thread = null }
        }
    }

    // ---------- 合成器 ----------
    private class Synth(val sr: Int) {
        private var rng = 0x9E3779B9u.toInt()
        private fun rnd(): Float {
            var x = rng
            x = x xor (x shl 13)
            x = x xor (x ushr 17)
            x = x xor (x shl 5)
            rng = x
            return x.toFloat() / 2147483648f
        }
        private fun rnd01(): Float = (rnd() + 1f) * 0.5f

        private var t = 0.0
        private val dt = 1.0 / sr
        private var fade = 0f
        private var fadeTarget = 0f

        // 0 master 1 rain 2 wind 3 waves 4 fire 5 基础噪声 6 双耳 7 环境音效 8 音调
        private val gain = FloatArray(9)
        private val gainT = FloatArray(9)

        // ---- 动效状态: 8 根频谱滑杆在 Low/High 两档之间移动 ----
        private val animBand = FloatArray(8) { 1f }
        private val animTgtB = FloatArray(8) { 1f }
        private var animClock = 0f
        private var animNextSel = 0f
        private val animRng = java.util.Random(0x5EED5EEDL)
        // ---- 双耳/等时节拍相位: [载波][左右] ----
        private val binPhase = DoubleArray(4)
        // ---- 睡眠定时 ----
        private var playSec = 0f
        private var prevOn = false

        // ---- 更多音效发生器状态 ----
        private var fxT = 0f
        private var clackT = 0.2f
        private var chirpT = 0f
        private var thunderT = 6f
        private var thunderEnv = 0f
        private var washerT = 0f
        private var sweepPh = 0.0
        private var impulseT = 0.3f
        private var impulseEnv = 0f
        private val tonePh = DoubleArray(8)
        private val tonePh2 = DoubleArray(8)
        private var fmPh = 0.0

        // ---- 噪声源 ----
        private val pb = Array(2) { FloatArray(7) }
        private fun pink(ch: Int, x: Float): Float {
            val b = pb[ch]
            b[0] = 0.99886f * b[0] + x * 0.0555179f
            b[1] = 0.99332f * b[1] + x * 0.0750759f
            b[2] = 0.96900f * b[2] + x * 0.1538520f
            b[3] = 0.86650f * b[3] + x * 0.3104856f
            b[4] = 0.55000f * b[4] + x * 0.5329522f
            b[5] = -0.7616f * b[5] - x * 0.0168980f
            val p = b[0] + b[1] + b[2] + b[3] + b[4] + b[5] + b[6] + x * 0.5362f
            b[6] = x * 0.115926f
            return p * 0.14f
        }
        private val brown = FloatArray(2)
        private fun brownNoise(ch: Int): Float {
            brown[ch] = (brown[ch] + 0.015f * rnd()) * 0.997f
            return brown[ch] * 3.4f
        }

        // 基础噪声: 8 段倍频程滤波器组(左右独立), 逐段增益塑形
        private val bandL = Array(8) { Svf() }
        private val bandR = Array(8) { Svf() }
        // 频谱增益平滑: 拖滑杆/切场景/动效变化时做 ~0.3s 的过渡, 不瞬间跳变
        private val bandSmooth = FloatArray(8) { -1f }

        // ---- 滤波 ----
        private val lpState = FloatArray(192)
        private fun lp(idx: Int, x: Float, fc: Float): Float {
            val a = (2.0 * PI * fc.coerceIn(10f, 12000f) / sr).toFloat().coerceIn(0.00005f, 0.85f)
            lpState[idx] += (x - lpState[idx]) * a
            return lpState[idx]
        }
        private fun hp(idx: Int, x: Float, fc: Float): Float = x - lp(idx, x, fc)

        private class Svf { var low = 0f; var band = 0f }
        private fun bp(s: Svf, x: Float, fc: Float, q: Float): Float {
            val f = (2.0 * sin(PI * fc.coerceIn(30f, sr * 0.42f) / sr)).toFloat().coerceIn(0.001f, 0.88f)
            s.low += f * s.band
            val high = x - s.low - q * s.band
            s.band += f * high
            return s.band
        }

        // ---- 平滑随机(1/f 感) ----
        private class Walk(seed: Int) {
            var value = 0.5f
            var target = 0.5f
            var timer = 0f
            val rng = java.util.Random(seed.toLong())
            fun step(dt: Float, minI: Float, maxI: Float, rate: Float) {
                timer -= dt
                if (timer <= 0f) {
                    timer = minI + rng.nextFloat() * (maxI - minI)
                    target = 0.08f + rng.nextFloat() * 0.92f
                }
                value += (target - value) * rate
            }
        }
        private val gustW = Walk(7)
        private val windFcW = Walk(11)
        private val rainAmtW = Walk(17)

        // ---- 声部(雨滴/爆裂/汩汩) ----
        private val rainRumBp = Array(2) { Svf() }   // 雨的低频雨幕带通
        private val dropBp = Array(32) { Svf() }
        private val dropEnv = FloatArray(32)
        private val dropInc = FloatArray(32)
        private val dropFc = FloatArray(32)
        private val dropPan = FloatArray(32)
        private val dropAmp = FloatArray(32)
        private val dropFar = BooleanArray(32)

        private val crackBp = Array(24) { Svf() }
        private val crackEnv = FloatArray(24)
        private val crackInc = FloatArray(24)
        private val crackFc = FloatArray(24)
        private val crackPan = FloatArray(24)
        private val crackAmp = FloatArray(24)
        private val crackQ = FloatArray(24)

        // ---- Freeverb (8 comb + 4 allpass 每声道) ----
        private class Comb(size: Int) {
            val buf = FloatArray(size)
            var idx = 0
            var store = 0f
            fun process(x: Float, fb: Float, damp: Float): Float {
                val y = buf[idx]
                store = y * (1f - damp) + store * damp
                buf[idx] = x + store * fb
                idx = if (idx + 1 >= buf.size) 0 else idx + 1
                return y
            }
        }
        private class Allpass(size: Int) {
            val buf = FloatArray(size)
            var idx = 0
            fun process(x: Float, fb: Float): Float {
                val b = buf[idx]
                val y = -x + b
                buf[idx] = x + b * fb
                idx = if (idx + 1 >= buf.size) 0 else idx + 1
                return y
            }
        }
        private val combL: Array<Comb>
        private val combR: Array<Comb>
        private val apL = arrayOf(Allpass(sc(556)), Allpass(sc(441)), Allpass(sc(341)), Allpass(sc(225)))
        private val apR = arrayOf(Allpass(sc(579)), Allpass(sc(464)), Allpass(sc(364)), Allpass(sc(248)))
        private val revL = FloatArray(1)
        private val revR = FloatArray(1)

        init {
            val tun = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
            combL = Array(8) { Comb(sc(tun[it])) }
            combR = Array(8) { Comb(sc(tun[it] + 23)) }
        }
        private fun sc(n: Int): Int = maxOf(8, n * sr / 44100)

        // 每秒内 sample 索引
        fun render(out: ShortArray, frames: Int, m: NoiseMix): Boolean {
            gainT[0] = m.master
            gainT[1] = m.rain
            gainT[2] = m.wind
            gainT[3] = m.waves
            gainT[4] = m.fire
            gainT[5] = m.colorLevel
            gainT[6] = m.binauralLevel
            gainT[7] = m.fxLevel
            gainT[8] = if (m.tone == 0) 0f else m.toneLevel
            fadeTarget = if (m.on) 1f else 0f

            // myNoise 动效: 滑杆在 Low/High 两档之间移动;
            // Solo/Duo/Trio/Quad = 有 1/2/3/4 根滑杆到达 High, 其余停在 Low; 选择随时间轮换。
            val animSpd = ANIM_SPEEDS[m.animSpeed.coerceIn(0, 6)]
            animClock += frames.toFloat() / sr
            if (m.animOn) {
                if (animClock >= animNextSel) {
                    animNextSel = animClock + (2.8f / animSpd) * (0.75f + animRng.nextFloat() * 0.6f)
                    val nSel = m.animCount.coerceIn(1, 4)
                    for (i in 0 until 8) animTgtB[i] = m.animLow
                    val used = BooleanArray(8)
                    var got = 0
                    while (got < nSel) {
                        val idx = animRng.nextInt(8)
                        if (!used[idx]) { used[idx] = true; animTgtB[idx] = m.animHigh; got++ }
                    }
                }
                for (i in 0 until 8) {
                    val k = if (m.animMode == 1) 0.5f
                            else (0.05f * animSpd).coerceIn(0.01f, 0.35f)
                    animBand[i] += (animTgtB[i] - animBand[i]) * k
                }
            } else {
                for (i in 0 until 8) animBand[i] += (1f - animBand[i]) * 0.08f
            }

            // 睡眠定时: 最后 20s 淡出, 到点停
            if (m.on && !prevOn) playSec = 0f
            prevOn = m.on
            if (m.on) playSec += frames.toFloat() / sr
            var timerMul = 1f
            var timerDone = false
            if (m.sleepMin > 0) {
                val rem = m.sleepMin * 60f - playSec
                timerMul = (rem / 20f).coerceIn(0f, 1f)
                if (rem <= 0f) timerDone = true
            }

            // 带速: 整体时间基缩放; 交替=随时间缓慢加减速(Shepard 式)
            val speedMul = when (m.speed) {
                0 -> 0.8f
                2 -> 1.25f
                3 -> (1.0 + 0.2 * sin(t * 2.0 * PI / 40.0)).toFloat()
                else -> 1f
            }
            val dtEff = dt * speedMul

            // 8 段有效增益 = 滑杆 × iEQ 校准 ×(语音遮蔽抬 500Hz-4kHz)
            val ieqCurve = when (m.ieq) {
                1 -> floatArrayOf(1.25f, 1.15f, 1.05f, 1f, 0.98f, 1f, 1.15f, 1.25f)
                2 -> floatArrayOf(1.5f, 1.35f, 1.15f, 1f, 0.95f, 1.05f, 1.35f, 1.55f)
                else -> null
            }
            val bandsEff = FloatArray(8) { bi ->
                var g = (m.bands.getOrElse(bi) { 1f }).coerceIn(0f, 1.5f)
                if (ieqCurve != null) g *= ieqCurve[bi]
                if (m.speechBlock && bi in 3..6) g *= 1.6f
                g *= animBand[bi]
                g.coerceIn(0f, 2.5f)
            }
            // 一阶平滑(每个音频块逼近目标), 频谱变化有自然过渡
            for (bi in 0 until 8) {
                if (bandSmooth[bi] < 0f) bandSmooth[bi] = bandsEff[bi]
                bandSmooth[bi] += (bandsEff[bi] - bandSmooth[bi]) * 0.10f
            }
            val gainStep = 1f / (sr * 0.35f)
            val fadeStep = 1f / (sr * FADE_SEC)
            for (i in 0 until frames) {
                for (g in 0 until 9) {
                    val d = gainT[g] - gain[g]
                    gain[g] += d.coerceIn(-gainStep, gainStep)
                }
                fade += (fadeTarget - fade).coerceIn(-fadeStep, fadeStep)
                val ti = t.toFloat()
                t += dtEff

                // 慢速随机走子(所有层共用时间基)
                gustW.step(dtEff.toFloat(), 1.4f, 4.2f, 0.0009f)
                windFcW.step(dtEff.toFloat(), 0.8f, 2.6f, 0.0016f)
                rainAmtW.step(dtEff.toFloat(), 0.6f, 2.0f, 0.002f)

                var l = 0f
                var r = 0f

                // ================= 雨 =================
                // 参考真实雨声录音的频谱(100Hz 低频主体 + 500Hz 起 -4dB/oct 的柔和宽带沙沙),
                // 不是"高频雨滴敲铁皮": 低频来自屋顶/远处雨幕, 高频只是薄薄一层空气感。
                val rainAmt = gain[1]
                if (rainAmt > 0.0005f) {
                    val mod = (0.88f + 0.12f * rainAmtW.value) *
                        (1f + 0.03f * sin(ti * 2f * PI.toFloat() * 0.9f))
                    // 低频雨幕主体: 粉噪两级 55Hz 高通后 100Hz 带通(宽 Q), 立体声基本相关
                    var rumL = hp(0, pink(0, rnd()), 55f)
                    rumL = hp(1, rumL, 55f)
                    var rumR = hp(2, pink(1, rnd()), 55f)
                    rumR = hp(3, rumR, 55f)
                    rumL = bp(rainRumBp[0], rumL, 100f, 0.5f)
                    rumR = bp(rainRumBp[1], rumR, 100f, 0.5f)
                    // 宽带沙沙: 450Hz 高通 + 两级低通(3k/4k), 相对低频主体约 -17dB@500Hz
                    var hisL = hp(4, pink(0, rnd()), 450f)
                    var hisR = hp(5, pink(1, rnd()), 450f)
                    hisL = lp(7, lp(6, hisL, 3000f), 4000f)
                    hisR = lp(9, lp(8, hisR, 3000f), 4000f)
                    l += (rumL + hisL * 0.757f) * mod * rainAmt
                    r += (rumR + hisR * 0.757f) * mod * rainAmt
                    // 细小雨点: 极短宽带噪声颗粒, 2.5k 低通去"金属感", 只加轻微沙粒感
                    val rate = 300f + 420f * rainAmt
                    if (rnd01() < rate / sr) {
                        val k = (0 until dropEnv.size).firstOrNull { dropEnv[it] <= 0.001f } ?: 0
                        dropEnv[k] = 0.35f + rnd01() * 0.65f
                        dropPan[k] = 0.18f + rnd01() * 0.64f
                        dropAmp[k] = 0.012f + rnd01() * 0.025f
                        dropInc[k] = exp(-1f / ((0.002f + rnd01() * 0.006f) * sr))
                    }
                    var dl = 0f
                    var dr = 0f
                    for (k in 0 until dropEnv.size) {
                        val e = dropEnv[k]
                        if (e <= 0.001f) continue
                        dropEnv[k] = e * dropInc[k]
                        val v = rnd() * e * dropAmp[k]
                        dl += v * (1f - dropPan[k])
                        dr += v * dropPan[k]
                    }
                    dl = hp(26, lp(24, dl, 2500f), 350f)
                    dr = hp(27, lp(25, dr, 2500f), 350f)
                    l += dl * rainAmt
                    r += dr * rainAmt
                }

                // ================= 风 =================
                val windAmt = gain[2]
                if (windAmt > 0.0005f) {
                    val gust = gustW.value
                    val lfo1 = sin(ti * 2f * PI.toFloat() * 0.047f + 0.6f)
                    val lfo2 = sin(ti * 2f * PI.toFloat() * 0.081f + 2.4f)
                    val fcL = 180f + 420f * windFcW.value + 90f * lfo1
                    val fcR = fcL * 1.05f
                    val srcL = pink(0, rnd())
                    val srcR = pink(1, rnd())
                    val bodyL = bp(windBp1[0], srcL, fcL, 0.72f)
                    val bodyR = bp(windBp1[1], srcR, fcR, 0.72f)
                    val airL = bp(windBp2[0], srcL, fcL * 2.4f, 1.0f)
                    val airR = bp(windBp2[1], srcR, fcR * 2.4f, 1.0f)
                    val rum = lp(12, brownNoise(0), 110f) * 0.28f
                    val amp = (0.25f + 0.75f * gust + 0.08f * lfo2)
                    val pan = 0.13f * sin(ti * 2f * PI.toFloat() * 0.029f)
                    l += (bodyL * 2.4f + airL * (0.5f + 0.9f * gust) * 0.8f + rum) * amp * (1f - pan) * windAmt
                    r += (bodyR * 2.4f + airR * (0.5f + 0.9f * gust) * 0.8f + rum) * amp * (1f + pan) * windAmt
                }

                // ================= 海浪 =================
                val waveAmt = gain[3]
                if (waveAmt > 0.0005f) {
                    // 两个不同周期涌浪叠加, 避免规律感
                    val s1 = 0.5f + 0.5f * sin(ti * 2f * PI.toFloat() / 9.3f)
                    val s2 = 0.5f + 0.5f * sin(ti * 2f * PI.toFloat() / 15.7f + 1.1f)
                    val swell = (s1 * 0.62f + s2 * 0.38f)
                    val surf = swell * swell
                    val rising = maxOf(0f, sin(ti * 2f * PI.toFloat() / 9.3f + 0.35f))
                    val bedL = lp(30, brownNoise(0), 620f)
                    val bedR = lp(31, brownNoise(1), 660f)
                    val washL = bp(waveBp[0], pink(0, rnd()), 900f + 350f * swell, 0.7f)
                    val washR = bp(waveBp[1], pink(1, rnd()), 950f + 350f * swell, 0.7f)
                    val foamL = hp(32, pink(0, rnd()), 2100f) * rising * rising * 0.20f
                    val foamR = hp(33, pink(1, rnd()), 2300f) * rising * rising * 0.20f
                    l += (bedL * 1.5f * (0.15f + 0.85f * surf) + washL * 0.9f * swell + foamL) * waveAmt
                    r += (bedR * 1.5f * (0.15f + 0.85f * surf) + washR * 0.9f * swell + foamR) * waveAmt
                }

                // ================= 篝火 =================
                val fireAmt = gain[4]
                if (fireAmt > 0.0005f) {
                    val rum = lp(16, brownNoise(0), 140f) * 0.55f
                    val sizzle = lp(17, hp(18, pink(1, rnd()), 2300f), 7000f) * 0.12f
                    l += (rum + sizzle) * fireAmt
                    r += (rum * 0.92f + sizzle) * fireAmt
                    val rate = 8f + 16f * fireAmt
                    if (rnd01() < rate / sr) {
                        val k = (0 until crackEnv.size).firstOrNull { crackEnv[it] <= 0.001f } ?: 0
                        val kind = rnd01()
                        val sharp = kind > 0.22f
                        crackEnv[k] = 0.4f + rnd01() * 0.6f
                        crackFc[k] = if (sharp) 900f + rnd01() * 3000f else 90f + rnd01() * 180f
                        crackQ[k] = if (sharp) 0.55f else 0.9f
                        crackPan[k] = rnd01()
                        crackAmp[k] = 0.25f + rnd01() * 0.5f
                        val dec = if (sharp) 0.003f + rnd01() * 0.012f else 0.03f + rnd01() * 0.05f
                        crackInc[k] = exp(-1f / (dec * sr))
                        crackBp[k].low = 0f; crackBp[k].band = 0f
                        // 25% 概率补一个紧跟的短爆裂(爆裂簇, 更像真火)
                        if (rnd01() < 0.25f) {
                            val k2 = (0 until crackEnv.size).firstOrNull {
                                it != k && crackEnv[it] <= 0.001f
                            }
                            if (k2 != null) {
                                crackEnv[k2] = 0.3f + rnd01() * 0.5f
                                crackFc[k2] = 700f + rnd01() * 2600f
                                crackQ[k2] = 0.6f
                                crackPan[k2] = rnd01()
                                crackAmp[k2] = 0.15f + rnd01() * 0.35f
                                crackInc[k2] = exp(-1f / ((0.003f + rnd01() * 0.008f) * sr))
                                crackBp[k2].low = 0f; crackBp[k2].band = 0f
                            }
                        }
                    }
                    var cl = 0f
                    var cr = 0f
                    for (k in 0 until crackEnv.size) {
                        val e = crackEnv[k]
                        if (e <= 0.001f) continue
                        crackEnv[k] = e * crackInc[k]
                        val v = bp(crackBp[k], rnd(), crackFc[k], crackQ[k]) * e * crackAmp[k] * 2.6f
                        cl += v * (1f - crackPan[k])
                        cr += v * crackPan[k]
                    }
                    l += cl * fireAmt
                    r += cr * fireAmt
                }

                // ================= 基础噪声(8 段频谱塑形) =================
                val baseAmt = gain[5]
                if (baseAmt > 0.0005f) {
                    val wl = rnd() * 0.5f
                    val wr = rnd() * 0.5f
                    var bl = 0f
                    var br = 0f
                    for (bi in 0 until 8) {
                        val g = bandSmooth[bi]
                        if (g < 0.001f) continue
                        bl += bp(bandL[bi], wl, BAND_FC[bi], 1.1f) * g
                        br += bp(bandR[bi], wr, BAND_FC[bi], 1.1f) * g
                    }
                    l += bl * baseAmt * 0.5f
                    r += br * baseAmt * 0.5f
                }

                // ================= 双耳拍频 / 等时脉冲 =================
                val binAmt = if (m.binaural != 0) gain[6] else 0f
                if (binAmt > 0.0005f) {
                    val beat = m.beatHz.coerceIn(1, 40).toDouble()
                    val lvl = binAmt * 0.09f
                    for (c in 0 until 2) {
                        val f = BIN_CARRIERS[c]
                        binPhase[c * 2] += 2.0 * PI * f / sr
                        binPhase[c * 2 + 1] += 2.0 * PI * (f + beat) / sr
                        val pl = sin(binPhase[c * 2]).toFloat()
                        val pr = sin(binPhase[c * 2 + 1]).toFloat()
                        if (m.binaural == 1) {
                            l += pl * lvl
                            r += pr * lvl
                        } else {
                            // 等时: 升余弦脉冲门控(外放也能听到节拍)
                            val pulse = (0.5 + 0.5 * sin(t * 2.0 * PI * beat + PI / 2)).toFloat()
                            val gate = pulse * pulse
                            l += pl * lvl * gate
                            r += pr * lvl * gate
                        }
                    }
                }

                // ================= 更多音效: 环境纹理 =================
                val fxAmt = gain[7]
                if (fxAmt > 0.0005f) {
                    val d = dtEff.toFloat()
                    fxT += d
                    var fl = 0f
                    var fr = 0f
                    when (m.fx) {
                        1 -> {   // 交通: 低频轰鸣 + 驶过起伏
                            val pass = 0.55f + 0.45f * sin(fxT * 0.35f).toFloat()
                            fl = (lp(70, brownNoise(0), 220f) * 1.5f +
                                  lp(71, hp(72, rnd(), 300f), 1400f) * 0.35f) * pass
                            fr = (lp(73, brownNoise(1), 220f) * 1.5f +
                                  lp(74, hp(75, rnd(), 300f), 1400f) * 0.35f) * pass
                        }
                        2 -> {   // 火车: 铁轨轰鸣 + 规律双响
                            clackT -= d
                            if (clackT <= 0f) clackT += 0.68f
                            val ph = (0.68f - clackT) / 0.68f
                            val c1 = if (ph < 0.04f) (1f - ph / 0.04f) else 0f
                            val c2 = if (ph > 0.12f && ph < 0.16f) (1f - (ph - 0.12f) / 0.04f) else 0f
                            val click = (c1 + c2 * 0.7f) * lp(76, hp(77, rnd(), 900f), 3800f)
                            fl = lp(70, brownNoise(0), 180f) * 1.2f + click
                            fr = lp(73, brownNoise(1), 180f) * 1.2f + click * 0.85f
                        }
                        3 -> {   // 虫鸣: 4kHz 短促脉冲串
                            chirpT += d
                            if (chirpT >= 0.42f) chirpT -= 0.42f
                            val ph = chirpT / 0.42f
                            val env = if (ph < 0.55f) sin(ph / 0.55f * PI).toFloat() else 0f
                            val chirp = bp(bandL[0], rnd(), 4200f, 3.5f) * env
                            fl = chirp
                            fr = bp(bandR[0], rnd(), 4600f, 3.5f) * env * 0.9f
                        }
                        4 -> {   // 雷声: 低频涌浪 + 随机炸裂
                            thunderT -= d
                            if (thunderT <= 0f) {
                                thunderT = 5f + rnd01() * 12f
                                thunderEnv = 1f
                            }
                            thunderEnv = (thunderEnv - d * 0.45f).coerceAtLeast(0f)
                            val rum = lp(70, brownNoise(0), 150f) * 1.8f * thunderEnv
                            val crack = lp(76, hp(77, rnd(), 200f), 900f) * thunderEnv * 0.6f
                            fl = rum + crack
                            fr = lp(73, brownNoise(1), 150f) * 1.8f * thunderEnv + crack
                        }
                        5 -> {   // 电流: 50Hz 电源嗡鸣 + 谐波 + 细碎放电
                            tonePh[6] += 2.0 * PI * 50.0 / sr
                            tonePh[7] += 2.0 * PI * 100.0 / sr
                            val hum = sin(tonePh[6]).toFloat() * 0.5f +
                                      sin(tonePh[7]).toFloat() * 0.25f +
                                      bp(bandL[1], rnd(), 3000f, 2.0f) * 0.06f
                            fl = hum
                            fr = sin(tonePh[6] + 0.4).toFloat() * 0.5f +
                                 sin(tonePh[7] + 0.2).toFloat() * 0.25f
                        }
                        6 -> {   // 空调: 风道 + 压缩机周期
                            val comp = (0.5f + 0.5f * sin(fxT * 0.08f).toFloat())
                            val air = lp(71, hp(72, rnd(), 400f), 3500f) * (0.35f + 0.25f * comp)
                            tonePh[6] += 2.0 * PI * 120.0 / sr
                            fl = air + sin(tonePh[6]).toFloat() * 0.12f * comp
                            fr = lp(74, hp(75, rnd(), 400f), 3500f) * (0.35f + 0.25f * comp) +
                                 sin(tonePh[6] + 0.3).toFloat() * 0.12f * comp
                        }
                        7 -> {   // 洗衣机: 4 秒周期搅动 + 电机
                            washerT += d
                            if (washerT >= 4f) washerT -= 4f
                            val ph = washerT / 4f
                            val swish = sin(ph * 2f * PI).toFloat().let { it * it } *
                                        bp(bandL[0], rnd(), 900f, 1.2f)
                            tonePh[6] += 2.0 * PI * 90.0 / sr
                            fl = swish + sin(tonePh[6]).toFloat() * 0.1f
                            fr = bp(bandR[0], rnd(), 1100f, 1.2f) *
                                 sin(ph * 2f * PI + 1f).toFloat().let { it * it } +
                                 sin(tonePh[6] + 0.5).toFloat() * 0.1f
                        }
                        8 -> {   // 瀑布: 宽频水幕
                            fl = lp(70, pink(0, rnd()), 6000f) * 1.1f +
                                 hp(72, rnd(), 1200f) * 0.35f
                            fr = lp(73, pink(1, rnd()), 6000f) * 1.1f +
                                 hp(75, rnd(), 1200f) * 0.35f
                        }
                    }
                    l += fl * fxAmt * 0.8f
                    r += fr * fxAmt * 0.8f
                }

                // ================= 更多音效: 音调发生器 =================
                val toneAmt = gain[8]
                if (toneAmt > 0.0005f) {
                    var tl = 0f
                    var tr = 0f
                    when (m.tone) {
                        1 -> {   // Solfeggio 音阶: 双耳微失谐 + 呼吸
                            val f = m.solfeggioHz.toDouble()
                            tonePh[0] += 2.0 * PI * f / sr
                            tonePh2[0] += 2.0 * PI * (f * 1.003) / sr
                            val env = 0.65f + 0.35f * sin(t * 2.0 * PI * 0.08).toFloat()
                            tl = sin(tonePh[0]).toFloat() * env
                            tr = sin(tonePh2[0]).toFloat() * env
                        }
                        2 -> {   // Shepard: 八度堆叠 + 高斯窗循环 => 无限上升错觉
                            val cyc = (t / 14.0) % 1.0
                            var sl = 0.0
                            var sr2 = 0.0
                            for (k in 0 until 8) {
                                val f = 55.0 * Math.pow(2.0, k + cyc)
                                if (f > sr * 0.45) continue
                                tonePh[k] += 2.0 * PI * f / sr
                                val w = Math.exp(-Math.pow(k - 4.0, 2.0) / 2.5)
                                sl += sin(tonePh[k]) * w
                                sr2 += sin(tonePh[k] + 0.25) * w
                            }
                            tl = (sl * 0.22).toFloat()
                            tr = (sr2 * 0.22).toFloat()
                        }
                        3 -> {   // 40Hz 伽马脉冲(等时)
                            tonePh[0] += 2.0 * PI * 200.0 / sr
                            val pulse = (0.5 + 0.5 * sin(t * 2.0 * PI * 40.0 + PI / 2)).toFloat()
                            val g = pulse * pulse
                            tl = sin(tonePh[0]).toFloat() * g
                            tr = sin(tonePh[0] + 0.3).toFloat() * g
                        }
                        4 -> {   // 脉冲噪声: 随机短脉冲
                            impulseT -= dtEff.toFloat()
                            if (impulseT <= 0f) {
                                impulseT = 0.05f + rnd01() * 0.11f
                                impulseEnv = 1f
                            }
                            impulseEnv = (impulseEnv - dtEff.toFloat() * 26f).coerceAtLeast(0f)
                            tl = bp(bandL[0], rnd(), 2400f, 2.0f) * impulseEnv
                            tr = bp(bandR[0], rnd(), 2800f, 2.0f) * impulseEnv
                        }
                        5 -> {   // Aural Scan: 带通中心缓慢扫过 100Hz..8kHz
                            sweepPh += dtEff.toDouble() * 2.0 * PI / 24.0
                            val fc = (120.0 * Math.pow(66.0, 0.5 + 0.5 * sin(sweepPh))).toFloat()
                            tl = bp(bandL[0], rnd(), fc, 1.4f)
                            tr = bp(bandR[0], rnd(), fc * 1.02f, 1.4f)
                        }
                        6 -> {   // 神经调制: 两个低频失谐 + 慢速 FM/AM
                            fmPh += 2.0 * PI * (0.11 + 0.05 * sin(t * 0.21)) / sr
                            val fm = sin(fmPh).toFloat() * 8f
                            tonePh[0] += 2.0 * PI * (62.0 + fm) / sr
                            tonePh[1] += 2.0 * PI * (67.0 - fm) / sr
                            val am = 0.6f + 0.4f * sin(t * 2.0 * PI * 0.13).toFloat()
                            tl = (sin(tonePh[0]) * 0.5 + sin(tonePh[1]) * 0.3).toFloat() * am
                            tr = (sin(tonePh[0] + 0.5) * 0.5 + sin(tonePh[1] + 0.3) * 0.3).toFloat() * am
                        }
                    }
                    l += tl * toneAmt * 0.5f
                    r += tr * toneAmt * 0.5f
                }

                // ================= 空间混响(Freeverb) =================
                val dryL = l
                val dryR = r
                var wetL = 0f
                var wetR = 0f
                val fb = 0.86f      // room size
                val damp = 0.35f
                for (k in 0 until 8) {
                    wetL += combL[k].process(dryL * 0.015f, fb, damp)
                    wetR += combR[k].process(dryR * 0.015f, fb, damp)
                }
                for (ap in apL) wetL = ap.process(wetL, 0.5f)
                for (ap in apR) wetR = ap.process(wetR, 0.5f)
                // 混响湿声压一点高频, 避免 Freeverb 的"薄板感"
                wetL = lp(36, wetL, 5500f)
                wetR = lp(37, wetR, 5500f)
                val wet = 0.30f
                l = dryL * (1f - wet * 0.5f) + wetL * wet
                r = dryR * (1f - wet * 0.5f) + wetR * wet

                val master = gain[0] * fade * timerMul
                val wf = when (m.width) { 0 -> 0f; 1 -> 0.55f; 3 -> 1.55f; else -> 1f }
                val mid = (l + r) * 0.5f
                val side = (l - r) * 0.5f
                val lw = mid + side * wf
                val rw = mid - side * wf
                out[i * 2] = (softClip(lw * master) * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                out[i * 2 + 1] = (softClip(rw * master) * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
            return !(fadeTarget == 0f && fade <= 0.0005f) && !timerDone
        }

        private val windBp1 = arrayOf(Svf(), Svf())
        private val windBp2 = arrayOf(Svf(), Svf())
        private val waveBp = arrayOf(Svf(), Svf())

        private fun softClip(x: Float): Float {
            if (abs(x) <= 0.75f) return x
            val s = if (x > 0) 1f else -1f
            return s * (0.75f + 0.25f * (1f - exp(-(abs(x) - 0.75f) * 2.5f)))
        }
    }
}
