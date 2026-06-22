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
import kotlin.math.sqrt

object AudioProcessor {
    var intensity = 1.0f
    var noiseGate = 15

    // 禁用 Kotlin 协程，改用原生 Java 并发队列，杜绝宿主缺少协程库导致的闪退/失效
    private val audioQueue = LinkedBlockingQueue<Any>(100)
    private val executor = Executors.newSingleThreadExecutor()

    private var vibrator: Vibrator? = null
    private var isInitialized = false

    // === 超长波形打包器 ===
    // 我们将把碎音频打包成 500 毫秒的超长连续波形
    // 500ms 切分成 25 块，每块 20ms
    private const val BATCH_CHUNKS = 25 
    private const val SAMPLES_PER_CHUNK = 882 // 约 20ms 的采样数 (44.1kHz)
    
    // 生成系统需要的连续震动长数组
    private val timings = LongArray(BATCH_CHUNKS) { 20L } 
    private val amplitudes = IntArray(BATCH_CHUNKS)
    private var currentChunkIndex = 0

    private var currentFrameEnergy = 0.0
    private var currentFrameSamples = 0
    private var currentEnvelope = 0.0f

    init {
        startProcessingThread()
    }

    fun pushAudioData(data: Any) {
        // 防止队列积压造成内存泄漏
        if (audioQueue.size > 80) audioQueue.poll()
        audioQueue.offer(data)
    }

    @SuppressLint("ServiceCast")
    private fun initVibratorIfNeeded() {
        if (isInitialized) return
        val context = AndroidAppHelper.currentApplication()?.applicationContext ?: return
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            isInitialized = true
        } catch (e: Exception) {
            // 防止部分 App 没有震动权限导致崩溃
        }
    }

    private fun startProcessingThread() {
        executor.execute {
            while (true) {
                try {
                    val data = audioQueue.take() // 阻塞直到有音频数据进来
                    processChunk(data)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun processChunk(data: Any) {
        when (data) {
            is ByteArray -> {
                val size = data.size - 1
                for (i in 0 until size step 2) {
                    val sample = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
                    accumulateSample((if (sample > 32767) sample - 65536 else sample).toFloat())
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
        currentFrameEnergy += (sample * sample)
        currentFrameSamples++

        // 积攒够 20ms 的数据，就算作波形数组中的 1 个点
        if (currentFrameSamples >= SAMPLES_PER_CHUNK) {
            val rms = sqrt(currentFrameEnergy / SAMPLES_PER_CHUNK)
            
            // 能量映射 (简单粗暴提取包络)
            val normalized = (rms / 32768.0).coerceIn(0.0, 1.0).toFloat()
            
            // 包络粘合处理 (缓解哒哒声的关键：衰减不许太快)
            if (normalized > currentEnvelope) {
                currentEnvelope += 0.5f * (normalized - currentEnvelope)
            } else {
                currentEnvelope += 0.2f * (normalized - currentEnvelope)
            }

            // 计算最终给马达的推力
            var finalAmp = (currentEnvelope * 255 * intensity).toInt().coerceIn(0, 255)
            if (finalAmp < noiseGate) finalAmp = 0 // 干掉烦人的底噪
            
            amplitudes[currentChunkIndex] = finalAmp
            currentChunkIndex++

            currentFrameEnergy = 0.0
            currentFrameSamples = 0

            // 【核心爆发点】积攒够 500ms（25个点），将这半秒的长波一次性轰入底层！
            if (currentChunkIndex >= BATCH_CHUNKS) {
                fireBatchWaveform()
                currentChunkIndex = 0
            }
        }
    }

    private fun fireBatchWaveform() {
        initVibratorIfNeeded()
        if (vibrator?.hasVibrator() != true) return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                // 生成一个持续 500 毫秒、连绵起伏的波浪，马达绝对不会断触急刹车
                val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                vibrator?.vibrate(effect)
            } catch (e: Exception) {
                // 如果目标应用因为没有 <uses-permission VIBRATE> 权限被系统拒绝
                e.printStackTrace()
            }
        }
    }
}
