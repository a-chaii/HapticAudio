package com.hapticaudio

import android.annotation.SuppressLint
import android.app.AndroidAppHelper
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.pow
import kotlin.math.sqrt

object AudioProcessor {
    var intensity = 1.5f
    var noiseGate = 10

    private val audioQueue = LinkedBlockingQueue<Any>(200)
    private val executor = Executors.newSingleThreadExecutor()

    private var vibrator: Vibrator? = null
    private var isInitialized = false

    // === 零滤除高速时域打包器 ===
    // 为了让马达完美复刻音乐振幅的高低起伏，拒绝打点感。
    // 我们将打包长度缩减到 120ms 的超短滑窗，内部切分成 8 个极速采样点（每点 15ms）
    private const val BATCH_POINTS = 8
    private const val SAMPLES_PER_POINT = 661 // 44.1kHz 下约 15ms 的采样数量

    private val timings = LongArray(BATCH_POINTS) { 15L }
    private val amplitudes = IntArray(BATCH_POINTS)
    private var currentPointIndex = 0

    private var accumulatedEnergy = 0.0
    private var accumulatedSamples = 0

    init {
        executor.execute {
            while (true) {
                try {
                    processQueueItem(audioQueue.take())
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    fun pushAudioData(data: Any) {
        if (audioQueue.size > 150) audioQueue.poll() // 抛弃过旧帧，防止时差滞后
        audioQueue.offer(data)
    }

    @SuppressLint("ServiceCast")
    private fun initVibrator(context: Context) {
        if (isInitialized) return
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            isInitialized = true
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun processQueueItem(data: Any) {
        when (data) {
            is ByteArray -> {
                val len = data.size - 1
                for (i in 0 until len step 2) {
                    val sample = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
                    feedSample((if (sample > 32767) sample - 65536 else sample).toFloat())
                }
            }
            is ShortArray -> {
                for (s in data) feedSample(s.toFloat())
            }
            is FloatArray -> {
                for (f in data) feedSample(f * 32767f)
            }
        }
    }

    private fun feedSample(sample: Float) {
        // 完全无滤波器！直接提取最原始的物理能量
        accumulatedEnergy += (sample * sample)
        accumulatedSamples++

        // 积攒满 15ms 的原声数据
        if (accumulatedSamples >= SAMPLES_PER_CHUNK) {
            val rms = sqrt(accumulatedEnergy / SAMPLES_PER_CHUNK)
            val normalized = (rms / 32768.0).coerceIn(0.0, 1.0)

            // 【核心算法更新：动态范围爆发扩张】
            // 之前强度变化小，是因为进行了线性映射。现在我们改用 1.5 次方指数扩张！
            // 这会让弱音迅速跌落至接近静音，强音（如鼓点、高潮）呈现爆发性极强推力！
            val expandedEnvelope = normalized.pow(1.5)

            var amp = (expandedEnvelope * 255 * intensity).toInt().coerceIn(0, 255)
            if (amp < noiseGate) amp = 0 // 切断极低底噪

            amplitudes[currentPointIndex] = amp
            currentPointIndex++

            accumulatedEnergy = 0.0
            accumulatedSamples = 0

            // 攒满 8 个点（总计 120ms 连续波形），直接轰入底层马达抽象层
            if (currentPointIndex >= BATCH_POINTS) {
                commitHapticWaveform()
                currentPointIndex = 0
            }
        }
    }

    private fun commitHapticWaveform() {
        val app = AndroidAppHelper.currentApplication() ?: return
        initVibrator(app.applicationContext)

        if (vibrator?.hasVibrator() == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                // 120ms 的超短不循环长波，更新频率极快，跟随音乐旋律起伏变化
                val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                vibrator?.vibrate(effect)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }
}
