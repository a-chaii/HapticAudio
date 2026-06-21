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
    var intensityMultiplier = 1.0f

    // 核心缓冲区：分离音频提取和震动触发，绝不阻塞音频主线程
    private val audioChannel = Channel<Any>(capacity = 100, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val waveformChannel = Channel<IntArray>(capacity = 10, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val processorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var vibrator: Vibrator? = null
    private var isInitialized = false

    // === 一加 0916 Turbo (CSA0916) 专属算法参数 ===
    // 彻底抛弃低通滤波，保留 50Hz-500Hz 全频段音乐纹理
    private const val ATTACK = 0.8f   // 极速启动（0916 的 1ms 响应能完美接住瞬态）
    private const val DECAY = 0.2f    // 适中衰减，把人声和弦乐的余音“黏合”成连续的波浪
    private var currentEnvelope = 0.0f

    // === 流式同步波形批处理 (Stream-Synced Batching) ===
    // 这是消除“哒哒震”的秘密武器：我们将散落的振幅拼成一整段连续的波形
    private const val SAMPLES_PER_FRAME = 1024 // 约 11ms 的逻辑帧
    private const val BATCH_SIZE = 6           // 每次积攒 6 帧 (约 66ms 的连续波段)
    
    private val timings = LongArray(BATCH_SIZE) { 11L } // 每一帧的持续时间
    private val amplitudes = IntArray(BATCH_SIZE)
    private var batchIndex = 0

    private var currentFrameEnergy = 0.0
    private var currentFrameSamples = 0

    init {
        startProcessing()
        startVibrating()
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

    private fun startVibrating() {
        processorScope.launch {
            for (amps in waveformChannel) {
                initVibratorIfNeeded()
                if (vibrator?.hasVibrator() != true) continue
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        // 提交一整段 66ms 的波形。马达会连贯地起伏，底层不会触发互相 Cancel 的“哒哒”声
                        val effect = VibrationEffect.createWaveform(timings, amps, -1)
                        vibrator?.vibrate(effect)
                    } catch (e: Exception) { }
                }
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
        currentFrameEnergy += sample * sample
        currentFrameSamples++

        // 当积攒够一个逻辑帧 (约11ms)
        if (currentFrameSamples >= SAMPLES_PER_FRAME) {
            val rms = sqrt(currentFrameEnergy / SAMPLES_PER_FRAME)
            amplitudes[batchIndex] = mapRmsToAmplitude(rms)
            batchIndex++

            currentFrameEnergy = 0.0
            currentFrameSamples = 0

            // 如果波形数组填满了 (约66ms)
            if (batchIndex >= BATCH_SIZE) {
                waveformChannel.trySend(amplitudes.clone()) // 拷贝一份发给震动协程
                batchIndex = 0
            }
        }
    }

    private fun mapRmsToAmplitude(rms: Double): Int {
        val normalized = (rms / 32768.0).coerceIn(0.0001, 1.0)
        val dbValue = 20.0 * log10(normalized)

        // 极宽动态映射：把 -50dB 到 0dB 映射到 0.0 ~ 1.0
        // 这能放过绝大多数人声和弦乐的微小起伏
        val targetIntensity = ((dbValue + 50.0) / 50.0).coerceIn(0.0, 1.0).toFloat()

        // 包络追踪器：平滑锯齿，塑造连贯波浪
        if (targetIntensity > currentEnvelope) {
            currentEnvelope += ATTACK * (targetIntensity - currentEnvelope)
        } else {
            currentEnvelope += DECAY * (targetIntensity - currentEnvelope)
        }

        // 应用来自 Web 端的滑块强度倍率
        val finalAmp = (currentEnvelope * 255 * intensityMultiplier).toInt().coerceIn(0, 255)
        
        // 切除底噪门限（防止没声时隐隐作痛）
        return if (finalAmp > 8) finalAmp else 0
    }
}
