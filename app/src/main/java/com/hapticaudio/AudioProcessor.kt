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

/**
 * 实时高保真音频转震动信号处理器 (旋律纹理优化版)
 */
object AudioProcessor {
    
    // 适当加大通道容量，允许保留一小段平滑连贯的音频流
    private val audioChannel = Channel<Any>(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val processorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var vibrator: Vibrator? = null
    private var isInitialized = false

    // ==========================================
    // 核心算法调音台参数 (你可以根据个人喜好微调)
    // ==========================================
    
    // 1. 低通滤波器系数 (1st order IIR LPF)
    // 0.01 到 0.03 之间。值越小，高频滤得越干净，震动越厚实；值越大，能保留更多中频人声细节。
    private const val ALPHA = 0.02f 
    private var lastFilteredSample = 0.0f

    // 2. 触觉包络释放系数 (Envelope Decay)
    // 核心参数！值越小(如0.05)，震动淡出越慢，余音越长，能把断续的鼓点黏合为连续的丝滑旋律波浪。
    // 值变大(如0.3)则会变得紧凑清脆。
    private const val ATTACK = 0.4f   // 能量上升响应速度
    private const val DECAY = 0.08f   // 能量下降衰减速度
    private var currentEnvelope = 0.0f

    // 3. 硬件控制节流
    // 线性马达物理响应极限大约在 15-20ms 左右，发送过快会产生瞬态硬件噪音。
    private var lastVibrateTime = 0L
    private const val MOTOR_UPDATE_INTERVAL_MS = 20L // 限制为最高 50Hz 刷新率

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

                // 步骤 1: 提取经过低通滤波器的音频信号均方根 (RMS)
                val rawRms = calculateFilteredRMS(data)

                // 步骤 2: 对数动态范围压缩 (Logarithmic Scaling)
                // 16-bit PCM 理论最大值为 32768。
                val normalized = (rawRms / 32768.0).coerceIn(0.0001, 1.0)
                val dbValue = 20.0 * log10(normalized) // 转换为 dB 量级，范围大约在 -80dB 到 0dB
                
                // 映射门限：把 -45dB 到 0dB 映射到 0.0 到 1.0 区间。
                // 调低门限(-45dB)能拉高副歌和弱音的存在感，让旋律在马达上“浮现”出来
                val targetIntensity = ((dbValue + 45.0) / 45.0).coerceIn(0.0, 1.0).toFloat()

                // 步骤 3: 经过 Attack/Decay 模拟物理阻尼 (平滑黏合器)
                if (targetIntensity > currentEnvelope) {
                    currentEnvelope += ATTACK * (targetIntensity - currentEnvelope)
                } else {
                    currentEnvelope += DECAY * (targetIntensity - currentEnvelope) // 缓慢释放，黏合鼓点
                }

                // 转换为最终马达振幅
                val finalAmplitude = (currentEnvelope * 255).toInt().coerceIn(0, 255)

                // 步骤 4: 节流控制与首尾衔接
                val now = System.currentTimeMillis()
                if (now - lastVibrateTime >= MOTOR_UPDATE_INTERVAL_MS) {
                    // 过滤底噪，防止没音乐时马达隐隐作痛
                    if (finalAmplitude > 12) {
                        triggerVibration(finalAmplitude)
                    }
                    lastVibrateTime = now
                }
            }
        }
    }

    /**
     * 带一阶 IIR 低通滤波的积分器
     * 公式：Y(n) = α * X(n) + (1 - α) * Y(n-1)
     */
    private fun calculateFilteredRMS(data: Any): Double {
        var sum = 0.0
        var count = 0

        when (data) {
            is ByteArray -> {
                val size = data.size - 1
                for (i in 0 until size step 2) {
                    val sample = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
                    val signedSample = (if (sample > 32767) sample - 65536 else sample).toFloat()

                    // 低通滤波核心
                    lastFilteredSample = ALPHA * signedSample + (1f - ALPHA) * lastFilteredSample
                    sum += lastFilteredSample * lastFilteredSample
                    count++
                }
            }
            is ShortArray -> {
                for (sample in data) {
                    val signedSample = sample.toFloat()
                    lastFilteredSample = ALPHA * signedSample + (1f - ALPHA) * lastFilteredSample
                    sum += lastFilteredSample * lastFilteredSample
                    count++
                }
            }
            is FloatArray -> {
                for (sample in data) {
                    val scaledSample = sample * 32767f
                    lastFilteredSample = ALPHA * scaledSample + (1f - ALPHA) * lastFilteredSample
                    sum += lastFilteredSample * lastFilteredSample
                    count++
                }
            }
        }

        if (count == 0) return 0.0
        return sqrt(sum / count)
    }

    /**
     * 激发连续波浪震动
     */
    private fun triggerVibration(amplitude: Int) {
        // 关键点：我们将每次震动的时长（duration）设为 35ms，长于我们 20ms 的刷新节流。
        // 这样下一次更新会直接覆盖并平滑过渡到上一次的震动尾巴，马达内部就不会因为突然彻底停机产生“哒哒”的断点感。
        val duration = 35L 

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createOneShot(duration, amplitude)
            vibrator?.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(duration)
        }
    }
}
