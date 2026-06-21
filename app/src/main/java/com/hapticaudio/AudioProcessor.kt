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

    private val audioChannel = Channel<Any>(capacity = 100, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val waveformChannel = Channel<IntArray>(capacity = 10, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val processorScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var vibrator: Vibrator? = null
    private var isInitialized = false

    private const val ATTACK = 0.8f   
    private const val DECAY = 0.2f    
    private var currentEnvelope = 0.0f

    private const val SAMPLES_PER_FRAME = 1024 
    private const val BATCH_SIZE = 6           
    
    private val timings = LongArray(BATCH_SIZE) { 11L }
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

        if (currentFrameSamples >= SAMPLES_PER_FRAME) {
            val rms = sqrt(currentFrameEnergy / SAMPLES_PER_FRAME)
            amplitudes[batchIndex] = mapRmsToAmplitude(rms)
            batchIndex++

            currentFrameEnergy = 0.0
            currentFrameSamples = 0

            if (batchIndex >= BATCH_SIZE) {
                waveformChannel.trySend(amplitudes.clone())
                batchIndex = 0
            }
        }
    }

    private fun mapRmsToAmplitude(rms: Double): Int {
        val normalized = (rms / 32768.0).coerceIn(0.0001, 1.0)
        val dbValue = 20.0 * log10(normalized)
        val targetIntensity = ((dbValue + 50.0) / 50.0).coerceIn(0.0, 1.0).toFloat()

        if (targetIntensity > currentEnvelope) {
            currentEnvelope += ATTACK * (targetIntensity - currentEnvelope)
        } else {
            currentEnvelope += DECAY * (targetIntensity - currentEnvelope)
        }

        val finalAmp = (currentEnvelope * 255 * intensityMultiplier).toInt().coerceIn(0, 255)
        return if (finalAmp > 8) finalAmp else 0
    }
}
