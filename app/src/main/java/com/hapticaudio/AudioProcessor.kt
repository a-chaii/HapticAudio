package com.hapticaudio

import android.annotation.SuppressLint
import android.app.AndroidAppHelper
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import de.robv.android.xposed.XposedBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlin.math.sqrt

/**
 * 实时 PCM 转震动信号处理器
 */
object AudioProcessor {
    
    // 使用 Channel 建立一个缓冲池，容量为 2（只保留最新数据）。
    // DROP_OLDEST 策略极其关键：当 AudioTrack 频繁 write 时，如果马达响应不过来，
    // 直接丢弃旧音频帧，保证“听感”和“震感”始终是0延迟同步的。
    private val audioChannel = Channel<Any>(capacity = 2, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val processorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var vibrator: Vibrator? = null
    private var isInitialized = false

    init {
        startProcessing()
    }

    /**
     * 从 Xposed Hook 处接收拷贝的音频数据
     */
    fun pushAudioData(data: Any) {
        audioChannel.trySend(data)
    }

    @SuppressLint("ServiceCast")
    private fun initVibratorIfNeeded() {
        if (isInitialized) return
        val context = AndroidAppHelper.currentApplication()?.applicationContext ?: return
        
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        isInitialized = true
    }

    private fun startProcessing() {
        processorScope.launch {
            for (data in audioChannel) {
                initVibratorIfNeeded()
                if (vibrator?.hasVibrator() != true) continue

                // 1. 计算 RMS (均方根) 获取音频当前窗口的能量强度包络
                val rms = calculateRMS(data)

                // 2. 将 RMS 映射到马达的振幅 Amplitude (0-255)
                // 假设 16-bit PCM 峰值为 32768。我们将阈值稍微调低，保证细节震动。
                val maxRms = 15000.0 
                val intensity = ((rms / maxRms) * 255).toInt().coerceIn(0, 255)

                // 3. 动态震动输出
                // 设置一个静音阈值，能量太小则不震动（滤除底噪）
                if (intensity > 15) {
                    triggerVibration(intensity)
                }
            }
        }
    }

    /**
     * 计算音频数据的 RMS (Root Mean Square)。
     * 公式: $RMS = \sqrt{\frac{1}{N}\sum_{i=1}^{N} x_i^2}$
     */
    private fun calculateRMS(data: Any): Double {
        var sum = 0.0
        var count = 0

        when (data) {
            is ByteArray -> {
                // 将 8-bit 分离的 PCM 组合为 16-bit 采样数据 (假设 Little Endian)
                val size = data.size - 1
                for (i in 0 until size step 2) {
                    val sample = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
                    // 处理符号位
                    val signedSample = if (sample > 32767) sample - 65536 else sample
                    sum += signedSample * signedSample
                    count++
                }
            }
            is ShortArray -> {
                for (sample in data) {
                    sum += sample * sample
                    count++
                }
            }
            is FloatArray -> {
                for (sample in data) {
                    // Float PCM 通常在 -1.0 到 1.0 之间，映射回 16-bit 量级便于统一处理
                    val scaledSample = sample * 32767
                    sum += scaledSample * scaledSample
                    count++
                }
            }
        }

        if (count == 0) return 0.0
        return sqrt(sum / count)
    }

    /**
     * 触发线性马达反馈
     */
    private fun triggerVibration(amplitude: Int) {
        // 由于 AudioTrack.write 会以高频率进入，我们使用很短的 duration (例如 15ms)
        // 配合动态振幅，在 X 轴线性马达上能形成细腻的连续波浪感
        val duration = 15L 

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createOneShot(duration, amplitude)
            vibrator?.vibrate(effect)
        } else {
            // 兼容老版本，无法控制振幅，只能控制开关
            @Suppress("DEPRECATION")
            vibrator?.vibrate(duration)
        }
    }
}
