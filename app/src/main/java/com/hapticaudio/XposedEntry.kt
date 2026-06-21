package com.hapticaudio

import android.media.AudioTrack
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.nio.ByteBuffer
import java.nio.ByteOrder

class XposedEntry : IXposedHookLoadPackage {
    private var lastPrefReloadTime = 0L
    private var isMuted = true
    private var intensity = 1.0f
    
    private var currentStreamer: HapticStreamer? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "android") return

        val pref = XSharedPreferences("com.hapticaudio", "haptic_config")

        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val track = param.thisObject as AudioTrack
                val originalData = param.args[0] ?: return
                val offset = param.args[1] as Int
                val size = param.args[2] as Int // 取决于类型，可能是 Bytes 或 Shorts 的长度
                val arrayType = originalData.javaClass

                // 1. 初始化或复用触觉音频轨道 (动态适配歌曲采样率)
                if (currentStreamer?.sampleRate != track.sampleRate) {
                    currentStreamer?.release()
                    currentStreamer = HapticStreamer(track.sampleRate)
                }

                // 2. 限流刷新配置面板数据 (每 1 秒读一次，绝不卡顿音频主线程)
                val now = System.currentTimeMillis()
                if (now - lastPrefReloadTime > 1000) {
                    pref.reload()
                    isMuted = pref.getBoolean("mute_speaker", true)
                    intensity = pref.getInt("intensity", 100) / 100f
                    lastPrefReloadTime = now
                }

                // 3. 将任何格式的 PCM 转为 16-bit ShortArray 并送入马达
                when (arrayType) {
                    ShortArray::class.java -> {
                        currentStreamer?.processAndWrite(originalData as ShortArray, offset, size, track.channelCount, intensity)
                    }
                    ByteArray::class.java -> {
                        // 将 8-bit ByteArray 还原为 16-bit PCM ShortArray
                        val bytes = originalData as ByteArray
                        val shorts = ShortArray(size / 2)
                        ByteBuffer.wrap(bytes, offset, size).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                        // 字节大小除以2就是 Short 的真实大小
                        currentStreamer?.processAndWrite(shorts, 0, shorts.size, track.channelCount, intensity)
                    }
                    FloatArray::class.java -> {
                        // Float 转 16-bit (-1.0~1.0 -> -32768~32767)
                        val floats = originalData as FloatArray
                        val shorts = ShortArray(size)
                        for (i in 0 until size) {
                            shorts[i] = (floats[offset + i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                        }
                        currentStreamer?.processAndWrite(shorts, 0, size, track.channelCount, intensity)
                    }
                }

                // 4. 执行原声扬声器静音
                if (isMuted) {
                    when (arrayType) {
                        ByteArray::class.java -> (originalData as ByteArray).fill(0, offset, offset + size)
                        ShortArray::class.java -> (originalData as ShortArray).fill(0, offset, offset + size)
                        FloatArray::class.java -> (originalData as FloatArray).fill(0f, offset, offset + size)
                    }
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod("android.media.AudioTrack", lpparam.classLoader, "write", ByteArray::class.java, Int::class.java, Int::class.java, Int::class.java, hook)
            XposedHelpers.findAndHookMethod("android.media.AudioTrack", lpparam.classLoader, "write", ShortArray::class.java, Int::class.java, Int::class.java, Int::class.java, hook)
            XposedHelpers.findAndHookMethod("android.media.AudioTrack", lpparam.classLoader, "write", FloatArray::class.java, Int::class.java, Int::class.java, Int::class.java, hook)
        } catch (e: Exception) {
            XposedBridge.log("HapticAudio Hook Error: ${e.message}")
        }
    }
}
