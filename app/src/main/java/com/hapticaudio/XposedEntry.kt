package com.hapticaudio

import android.app.AndroidAppHelper
import android.content.Context
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage

class XposedEntry : IXposedHookLoadPackage {
    private var lastPrefReloadTime = 0L
    private var isMuted = true
    private var isPkgRecorded = false

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "android") return

        val pref = XSharedPreferences("com.hapticaudio", "haptic_config")

        val writeHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val audioData = param.args[0] ?: return
                val arrayType = audioData.javaClass

                // 1. 记录当前被劫持的包名给 UI 展示 (仅执行一次)
                if (!isPkgRecorded) {
                    val context = AndroidAppHelper.currentApplication()
                    if (context != null) {
                        val hostPrefs = context.getSharedPreferences("haptic_config", Context.MODE_PRIVATE)
                        hostPrefs.edit().putString("last_hooked_pkg", lpparam.packageName).apply()
                        isPkgRecorded = true
                    }
                }

                // 2. 限流读取开关
                val now = System.currentTimeMillis()
                if (now - lastPrefReloadTime > 1000) {
                    pref.reload()
                    isMuted = pref.getBoolean("mute_speaker", true)
                    AudioProcessor.intensity = pref.getInt("intensity", 100) / 100f
                    AudioProcessor.noiseGate = pref.getInt("noise_gate", 15)
                    lastPrefReloadTime = now
                }

                // 3. 把原始数据丢进我们自研的长包缓冲引擎
                when (arrayType) {
                    ByteArray::class.java -> AudioProcessor.pushAudioData((audioData as ByteArray).clone())
                    ShortArray::class.java -> AudioProcessor.pushAudioData((audioData as ShortArray).clone())
                    FloatArray::class.java -> AudioProcessor.pushAudioData((audioData as FloatArray).clone())
                }

                // 4. 【终极物理静音】无论它传的是什么格式，当场清零，混音器根本拿不到波形
                if (isMuted) {
                    when (arrayType) {
                        ByteArray::class.java -> (audioData as ByteArray).fill(0)
                        ShortArray::class.java -> (audioData as ShortArray).fill(0)
                        FloatArray::class.java -> (audioData as FloatArray).fill(0f)
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
