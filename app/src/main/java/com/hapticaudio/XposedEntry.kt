package com.hapticaudio

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage

class XposedEntry : IXposedHookLoadPackage {
    private var lastPrefReloadTime = 0L
    private var isMuted = true

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "android") return

        val pref = XSharedPreferences("com.hapticaudio", "haptic_config")

        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val audioData = param.args[0] ?: return
                val offset = param.args[1] as Int
                val size = param.args[2] as Int
                val arrayType = audioData.javaClass

                // 1. 发送给流式处理器
                when (arrayType) {
                    ByteArray::class.java -> AudioProcessor.pushAudioData((audioData as ByteArray).copyOfRange(offset, offset + size))
                    ShortArray::class.java -> AudioProcessor.pushAudioData((audioData as ShortArray).copyOfRange(offset, offset + size))
                    FloatArray::class.java -> AudioProcessor.pushAudioData((audioData as FloatArray).copyOfRange(offset, offset + size))
                }

                // 2. 限流读取 Web UI 的配置 (每1秒更新一次，防止 I/O 阻塞音频线程)
                val now = System.currentTimeMillis()
                if (now - lastPrefReloadTime > 1000) {
                    pref.reload()
                    isMuted = pref.getBoolean("mute_speaker", true)
                    AudioProcessor.intensityMultiplier = pref.getInt("intensity", 100) 100f / 100f
                    lastPrefReloadTime = now
                }

                // 3. 执行静音清零
                if (isMuted) {
                    when (arrayType) {
                        ByteArray::class.java -> (audioData as ByteArray).fill(0, offset, offset + size)
                        ShortArray::class.java -> (audioData as ShortArray).fill(0, offset, offset + size)
                        FloatArray::class.java -> (audioData as FloatArray).fill(0, offset, offset + size)
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
