package com.hapticaudio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

class HapticStreamer(val sampleRate: Int) {
    private var hapticTrack: AudioTrack? = null
    // 核心：0x20000000 是 Android 底层隐藏的 CHANNEL_OUT_HAPTIC_A
    private val HAPTIC_CHANNEL_MASK = 0x20000000 
    
    init {
        try {
            // 构建一个 包含立体声音频 + 单通道马达 的混合轨道
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO or HAPTIC_CHANNEL_MASK)
                .build()

            // 必须使用 USAGE_ALARM 或 ASSISTANCE_SONIFICATION 防止被系统的媒体静音策略拦截
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM) 
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate, 
                AudioFormat.CHANNEL_OUT_STEREO or HAPTIC_CHANNEL_MASK, 
                AudioFormat.ENCODING_PCM_16BIT
            )

            hapticTrack = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .build()

            hapticTrack?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // 接收劫持到的原始数据，注入马达通道
    fun injectHapticData(originalShorts: ShortArray, intensity: Float) {
        if (hapticTrack == null) return

        // 假设原数据是双声道(Stereo)，我们要构造成 [左声道, 右声道, 马达通道] 的 3 通道数据
        val frames = originalShorts.size / 2
        val hapticBuffer = ShortArray(frames * 3)

        for (i in 0 until frames) {
            // 提取双声道的平均值作为震动基准能量
            val left = originalShorts[i * 2].toInt()
            val right = originalShorts[i * 2 + 1].toInt()
            val mixedSample = (left + right) / 2
            
            // 应用 UI 的增益倍率
            val hapticSample = (mixedSample * intensity).toInt().coerceIn(-32768, 32767).toShort()

            // 核心黑科技：左右声道强行填 0（不发声），把音乐数据全塞进马达通道！
            hapticBuffer[i * 3] = 0           // 左声道：静音
            hapticBuffer[i * 3 + 1] = 0       // 右声道：静音
            hapticBuffer[i * 3 + 2] = hapticSample // 马达通道：疯狂输出原音波形！
        }

        hapticTrack?.write(hapticBuffer, 0, hapticBuffer.size)
    }

    fun release() {
        try {
            hapticTrack?.stop()
            hapticTrack?.release()
        } catch (e: Exception) {}
    }
}
