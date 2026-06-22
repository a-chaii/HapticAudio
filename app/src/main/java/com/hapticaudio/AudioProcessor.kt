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
        accumulatedEnergy += (sample * sample)
        accumulatedSamples++

        // 关键修复点：替换为正确的常量名 SAMPLES_PER_POINT
        if (accumulatedSamples >= SAMPLES_PER_POINT) {
            val rms = sqrt(accumulatedEnergy / SAMPLES_PER_POINT)
            val normalized = (rms / 32768.0).coerceIn(0.0, 1.0)

            // 指数范围扩张映射
            val expandedEnvelope = normalized.pow(1.5)

            var amp = (expandedEnvelope * 255 * intensity).toInt().coerceIn(0, 255)
            if (amp < noiseGate) amp = 0 // 切断极低底噪

            amplitudes[currentPointIndex] = amp
            currentPointIndex++

            accumulatedEnergy = 0.0
            accumulatedSamples = 0

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
                val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                vibrator?.vibrate(effect)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }
}
