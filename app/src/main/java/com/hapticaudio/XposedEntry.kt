package com.hapticaudio

import android.app.AndroidAppHelper
import android.content.Intent
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
    private var intensity = 2.0f
    private var lastBroadcastTime = 0L
    
    private var streamer: HapticStreamer? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "android" || lpparam.packageName == "com.hapticaudio") return

        val pref = XSharedPreferences("com.hapticaudio", "haptic_config")

        val writeHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val track = param.thisObject as AudioTrack
                val audioData = param.args[0] ?: return
                val offset = param.args[1] as Int
                val size = param.args[2] as Int
                val arrayType = audioData.javaClass

                val now = System.currentTimeMillis()
                
                // 1. 每 1.5 秒发射一次心跳脉冲给 UI
                if (now - lastBroadcastTime > 1500) {
                    AndroidAppHelper.currentApplication()?.let {
                        val intent = Intent("com.hapticaudio.STATUS_UPDATE").apply {
                            putExtra("pkg", lpparam.packageName)
                            setPackage("com.hapticaudio")
                            addFlags(0x01000000) 
                        }
                        it.sendBroadcast(intent)
                    }
                    lastBroadcastTime = now
                }

                // 2. 限流更新调音台参数
                if (now - lastPrefReloadTime > 1000) {
                    pref.reload()
                    isMuted = pref.getBoolean("mute_speaker", true)
                    intensity = pref.getInt("intensity", 200) / 100f
                    lastPrefReloadTime = now
                }

                // 3. 初始化或匹配宿主歌曲采样率的 DSP 通道
                if (streamer?.sampleRate != track.sampleRate) {
                    streamer?.release()
                    streamer = HapticStreamer(track.sampleRate)
                }

                // 4. 将任何格式的音频统一转为 16-bit ShortArray 并注射进马达
                val shortArrayToInject = when (arrayType) {
                    ShortArray::class.java -> (audioData as ShortArray).copyOfRange(offset, offset + size)
                    ByteArray::class.java -> {
                        val bytes = audioData as ByteArray
                        val shorts = ShortArray(size / 2)
                        ByteBuffer.wrap(bytes, offset, size).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                        shorts
                    }
                    FloatArray::class.java -> {
                        val floats = audioData as FloatArray
                        val shorts = ShortArray(size)
                        for (i in 0 until size) shorts[i] = (floats[offset + i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                        shorts
                    }
                    else -> return
                }
                
                // 喂给底层 DSP！
                streamer?.injectHapticData(shortArrayToInject, intensity)

                // 5. 绝对物理静音原始声卡
                if (isMuted) {
                    when (arrayType) {
                        ByteArray::class.java -> (audioData as ByteArray).fill(0, offset, offset + size)
                        ShortArray::class.java -> (audioData as ShortArray).fill(0, offset, offset + size)
                        FloatArray::class.java -> (audioData as FloatArray).fill(0f, offset, offset + size)
                    }
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod("android.media.AudioTrack", lpparam.classLoader, "write", ByteArray::class.java, Int::class.java, Int::class.java, Int::class.java, writeHook)
            XposedHelpers.findAndHookMethod("android.media.AudioTrack", lpparam.classLoader, "write", ShortArray::class.java, Int::class.java, Int::class.java, Int::class.java, writeHook)
            XposedHelpers.findAndHookMethod("android.media.AudioTrack", lpparam.classLoader, "write", FloatArray::class.java, Int::class.java, Int::class.java, Int::class.java, writeHook)
        } catch (e: Exception) {
            XposedBridge.log("HapticAudio Hook 失败: ${e.message}")
        }
    }
}
