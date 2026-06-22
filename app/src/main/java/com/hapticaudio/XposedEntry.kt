package com.hapticaudio

import android.app.AndroidAppHelper
import android.content.Intent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage

class XposedEntry : IXposedHookLoadPackage {
    private var lastPrefReloadTime = 0L
    private var isMuted = true
    private var lastBroadcastTime = 0L

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "android" || lpparam.packageName == "com.hapticaudio") return

        val pref = XSharedPreferences("com.hapticaudio", "haptic_config")

        val writeHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val audioData = param.args[0] ?: return
                val offset = param.args[1] as Int
                val size = param.args[2] as Int
                val arrayType = audioData.javaClass

                val now = System.currentTimeMillis()
                
                // 1. 每 3 秒向 UI 进程定向投递心跳广播，刷新劫持状态
                if (now - lastBroadcastTime > 3000) {
                    val context = AndroidAppHelper.currentApplication()
                    context?.let {
                        val intent = Intent("com.hapticaudio.STATUS_UPDATE").apply {
                            putExtra("pkg", lpparam.packageName)
                            setPackage("com.hapticaudio")
                            addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND)
                        }
                        it.sendBroadcast(intent)
                    }
                    lastBroadcastTime = now
                }

                // 2. 跨进程重新装载用户调音参数
                if (now - lastPrefReloadTime > 1000) {
                    pref.reload()
                    isMuted = pref.getBoolean("mute_speaker", true)
                    AudioProcessor.intensity = pref.getInt("intensity", 150) / 100f
                    AudioProcessor.noiseGate = pref.getInt("noise_gate", 10)
                    lastPrefReloadTime = now
                }

                // 3. 原声未删减流深拷贝，直接轰入高频队列
                when (arrayType) {
                    ByteArray::class.java -> AudioProcessor.pushAudioData((audioData as ByteArray).copyOfRange(offset, offset + size))
                    ShortArray::class.java -> AudioProcessor.pushAudioData((audioData as ShortArray).copyOfRange(offset, offset + size))
                    FloatArray::class.java -> AudioProcessor.pushAudioData((audioData as FloatArray).copyOfRange(offset, offset + size))
                }

                // 4. 彻底斩断原始音频进声卡，实现 100% 物理级绝对静音
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
