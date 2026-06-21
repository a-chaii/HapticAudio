package com.hapticaudio

import android.media.AudioTrack
import android.media.audiofx.HapticGenerator
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.WeakHashMap

class XposedEntry : IXposedHookLoadPackage {
    private var lastPrefReloadTime = 0L
    private var isMuted = true
    
    // 使用 WeakHashMap 储存已挂载的特效，防止内存泄漏，且不会重复创建
    private val hapticGenerators = WeakHashMap<AudioTrack, HapticGenerator>()

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "android") return

        val pref = XSharedPreferences("com.hapticaudio", "haptic_config")

        // 【钩子 1】：在音乐准备开始播放时，把系统的触觉生成器挂载上去
        XposedHelpers.findAndHookMethod(
            "android.media.AudioTrack",
            lpparam.classLoader,
            "play",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val track = param.thisObject as AudioTrack
                    
                    // 如果已经挂载过，直接跳过
                    if (hapticGenerators.containsKey(track)) return

                    try {
                        // 检查一加系统是否支持原生触觉生成器
                        if (HapticGenerator.isAvailable()) {
                            val generator = HapticGenerator.create(track.audioSessionId)
                            if (generator != null) {
                                generator.enabled = true
                                hapticGenerators[track] = generator
                                XposedBridge.log("HapticAudio: 成功挂载一加底层 HapticGenerator!")
                            }
                        } else {
                            XposedBridge.log("HapticAudio: 警告！该系统不支持原生的 HapticGenerator。")
                        }
                    } catch (e: Exception) {
                        XposedBridge.log("HapticAudio 挂载失败: ${e.message}")
                    }
                }
            }
        )

        // 【钩子 2】：拦截写入数据的瞬间，实现“偷天换日”的静音
        val writeHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val track = param.thisObject as AudioTrack

                // 限流读取 UI 开关配置
                val now = System.currentTimeMillis()
                if (now - lastPrefReloadTime > 1000) {
                    pref.reload()
                    isMuted = pref.getBoolean("mute_speaker", true)
                    lastPrefReloadTime = now
                }

                // 核心黑科技：我们【绝不】破坏 param.args[0] 里的真实音乐数据。
                // 我们直接在 AudioTrack 级别把扬声器音量调成 0。
                // 这样底层的 HapticGenerator 能吃满音乐波形，而喇叭发不出任何声音。
                if (isMuted) {
                    try {
                        track.setVolume(0f)
                    } catch (e: Exception) {
                        XposedBridge.log("HapticAudio 静音失败: ${e.message}")
                    }
                }
            }
        }

        // 统一拦截所有格式的 write 方法
        try {
            XposedHelpers.findAndHookMethod("android.media.AudioTrack", lpparam.classLoader, "write", ByteArray::class.java, Int::class.java, Int::class.java, Int::class.java, writeHook)
            XposedHelpers.findAndHookMethod("android.media.AudioTrack", lpparam.classLoader, "write", ShortArray::class.java, Int::class.java, Int::class.java, Int::class.java, writeHook)
            XposedHelpers.findAndHookMethod("android.media.AudioTrack", lpparam.classLoader, "write", FloatArray::class.java, Int::class.java, Int::class.java, Int::class.java, writeHook)
        } catch (e: Exception) {
            XposedBridge.log("HapticAudio Hook 失败: ${e.message}")
        }

        // 【钩子 3】：音乐结束时，优雅地释放特效引擎，防止底噪震动
        XposedHelpers.findAndHookMethod(
            "android.media.AudioTrack",
            lpparam.classLoader,
            "release",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val track = param.thisObject as AudioTrack
                    val generator = hapticGenerators.remove(track)
                    try {
                        generator?.release()
                    } catch (e: Exception) {}
                }
            }
        )
    }
}
