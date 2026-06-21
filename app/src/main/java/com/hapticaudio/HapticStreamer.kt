package com.hapticaudio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * 硬件级音频转震动直通引擎
 */
class HapticStreamer(val sampleRate: Int) {
    private var hapticTrack: AudioTrack? = null

    // Android 9+ 隐藏的音频触觉通道 Flag
    private val CHANNEL_OUT_HAPTIC_A = 0x20000000 

    init {
        try {
            // 构建一个双通道 (单声道扬声器 + 单声道马达) 的音频轨道
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO or CHANNEL_OUT_HAPTIC_A)
                .build()

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setHapticChannelsMuted(false) // 强制允许音频触觉反馈
                .build()

            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate, 
                AudioFormat.CHANNEL_OUT_MONO or CHANNEL_OUT_HAPTIC_A, 
                AudioFormat.ENCODING_PCM_16BIT
            )

            hapticTrack = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize * 2)
                .build()

            hapticTrack?.play()
        } catch (e: Exception) {
            // 如果设备不支持，捕获异常防止崩溃
        }
    }

    /**
     * 将原始 PCM 声波混流并输送到马达 DSP
     */
    fun processAndWrite(originalData: ShortArray, offset: Int, size: Int, channelCount: Int, intensity: Float) {
        if (hapticTrack == null) return

        val frames = size / channelCount
        // 分配新数组：扬声器轨道 + 马达轨道
        val hapticBuffer = ShortArray(frames * 2)

        for (i in 0 until frames) {
            // 混音提取单声道基准信号
            var sum = 0
            for (c in 0 until channelCount) {
                sum += originalData[offset + i * channelCount + c]
            }
            val sample = (sum / channelCount)

            // 应用控制面板的增益倍率，并防止 PCM 溢出爆音
            val hapticSample = (sample * intensity).toInt().coerceIn(-32768, 32767).toShort()

            // 核心黑科技：左右声道分离复用
            hapticBuffer[i * 2] = 0 // Channel 0 (Mono Speaker) -> 写入0，强制静音
            hapticBuffer[i * 2 + 1] = hapticSample // Channel 1 (Haptic A Motor) -> 写入音频波形，马达按此波形震动！
        }

        // 直接冲入硬件 DSP
        hapticTrack?.write(hapticBuffer, 0, frames * 2)
    }

    fun release() {
        hapticTrack?.stop()
        hapticTrack?.release()
    }
}
