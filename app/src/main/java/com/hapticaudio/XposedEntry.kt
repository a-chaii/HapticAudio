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
        if (lpparam.packageName == "android") return

        val pref = XSharedPreferences("com.hapticaudio", "haptic_config")

        val writeHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val audioData = param.args[0] ?: return
                val offset = param.args[1] as Int
                val size = param.args[2] as Int
                val arrayType = audioData.javaClass

                // 1. 发送广播告诉 UI 我们正在劫持哪个应用 (限流 5 秒发一次，防卡顿)
                val now = System.currentTimeMillis()
                if (now - lastBroadcastTime > 5000) {
                    val context = AndroidAppHelper.currentApplication()
                    if (context != null) {
                        val intent = Intent("com.hapticaudio.STATUS_UPDATE")
                        intent.putExtra("pkg", lpparam.packageName)
                        intent.setPackage("com.hapticaudio") // 定向发送
                        context.sendBroadcast(intent)
                    }
                    lastBroadcastTime = now
                }

                // 2. 限流读取 UI 开关配置 (每 1 秒更新一次参数)
                if (now - lastPrefReloadTime > 1000) {
                    pref.reload()
                    isMuted = pref.getBoolean("mute_speaker", true)
                    AudioProcessor.intensity = pref.getInt("intensity", 100) / 100f
                    // 将 1-100 的数值映射到算法系数
                    AudioProcessor.lpfAlpha = pref.getInt("lpf_freq", 20) / 100f
                    AudioProcessor.decayRate = (101 - pref.getInt("smoothing", 80)) / 100f
                    lastPrefReloadTime = now
                }

                // 3. 将原汁原味的数据【深拷贝】发给自研轮子
                when (arrayType) {
                    ByteArray::class.java -> AudioProcessor.pushAudioData((audioData as ByteArray).copyOfRange(offset, offset + size))
                    ShortArray::class.java -> AudioProcessor.pushAudioData((audioData as ShortArray).copyOfRange(offset, offset + size))
                    FloatArray::class.java -> AudioProcessor.pushAudioData((audioData as FloatArray).copyOfRange(offset, offset + size))
                }

                // 4. 【核心防漏音物理隔离】：如果开启静音，直接把发给硬件的内存数组全部填 0！
                // 这比 setVolume 靠谱一万倍，一滴声音都进不去混音器。
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
