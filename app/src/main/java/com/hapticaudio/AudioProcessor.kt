package com.hapticaudio

import android.annotation.SuppressLint
import android.app.AndroidAppHelper
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlin.math.log10
import kotlin.math.sqrt

object AudioProcessor {
    // 动态调音参数
    var intensity = 1.0f
    var lpfAlpha = 0.2f
    var decayRate = 0.2f

    private val audioChannel = Channel<Any>(capacity = 50, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val processorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var vibrator: Vibrator? = null
    private var isInitialized = false

    // 无缝波形拼接器：每次积攒 5 帧（每帧 10ms），合并成一个 50ms 的长波形发给马达
    private const val SAMPLES_PER_FRAME = 441 // 约 10ms (假设44.1kHz采样率)
    private const val BATCH_SIZE = 5          // 50ms 批处理
    
    private val timings = LongArray(BATCH_SIZE) { 10L }
    private val amplitudes = IntArray(BATCH_SIZE)
    private var batchIndex = 0

    private var currentFrameEnergy = 0.0
    private var currentFrameSamples = 0
    private var lastFilteredSample = 0.0f
    private var currentEnvelope = 0.0f

    init {
        startProcessing()
    }

    fun pushAudioData(data: Any) {
        audioChannel.trySend(data)
    }

    @SuppressLint("ServiceCast")
    private fun initVibratorIfNeeded() {
        if (isInitialized) return
        val context = AndroidAppHelper.currentApplication()?.applicationContext ?: return
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        isInitialized = true
    }

    private fun startProcessing() {
        processorScope.launch {
            for (data in audioChannel) {
                processAudioChunk(data)
            }
        }
    }

    private fun processAudioChunk(data: Any) {
        when (data) {
            is ByteArray -> {
                val size = data.size - 1
                for (i in 0 until size step 2) {
                    val sample = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
                    val signedSample = (if (sample > 32767) sample - 65536 else sample).toFloat()
                    accumulateSample(signedSample)
                }
            }
            is ShortArray -> {
                for (sample in data) { accumulateSample(sample.toFloat()) }
            }
            is FloatArray -> {
                for (sample in data) { accumulateSample(sample * 32767f) }
            }
        }
    }

    private fun accumulateSample(sample: Float) {
        // 1. 低通滤波器 (干掉刺耳高频，保留浑厚质感)
        lastFilteredSample = lpfAlpha * sample + (1f - lpfAlpha) * lastFilteredSample
        
        currentFrameEnergy += lastFilteredSample * lastFilteredSample
        currentFrameSamples++

        // 当积攒够 10ms 的数据时
        if (currentFrameSamples >= SAMPLES_PER_FRAME) {
            val rms = sqrt(currentFrameEnergy / SAMPLES_PER_FRAME)
            amplitudes[batchIndex] = mapRmsToAmplitude(rms)
            batchIndex++

            currentFrameEnergy = 0.0
            currentFrameSamples = 0

            // 2. 当波形数组填满 50ms 时，统一发射给硬件！
            if (batchIndex >= BATCH_SIZE) {
                fireWaveform(amplitudes.clone())
                batchIndex = 0
            }
        }
    }

    private fun mapRmsToAmplitude(rms: Double): Int {
        val normalized = (rms / 32768.0).coerceIn(0.0001, 1.0)
        val dbValue = 20.0 * log10(normalized)
        
        // 动态范围映射门限
        val targetIntensity = ((dbValue + 45.0) / 45.0).coerceIn(0.0, 1.0).toFloat()

        // 包络追踪器：Attack 极快(跟手)，Decay 受 UI 滑块控制(决定粘合度)
        if (targetIntensity > currentEnvelope) {
            currentEnvelope += 0.8f * (targetIntensity - currentEnvelope) // 秒起
        } else {
            currentEnvelope += decayRate * (targetIntensity - currentEnvelope) // 缓慢释放粘合
        }

        val finalAmp = (currentEnvelope * 255 * intensity).toInt().coerceIn(0, 255)
        return if (finalAmp > 5) finalAmp else 0 // 过滤底噪死区
    }

    private fun fireWaveform(amps: IntArray) {
        initVibratorIfNeeded()
        if (vibrator?.hasVibrator() != true) return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                // -1 代表不循环，播放一遍这 50ms 的波形
                val effect = VibrationEffect.createWaveform(timings, amps, -1)
                vibrator?.vibrate(effect)
            } catch (e: Exception) { }
        }
    }
}
