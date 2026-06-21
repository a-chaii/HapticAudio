package com.hapticaudio

import android.media.AudioTrack
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class XposedEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 不 Hook 系统核心框架，只 Hook 普通应用进程
        if (lpparam.packageName == "android") return

        try {
            hookAudioTrackWrite(lpparam, ByteArray::class.java)
            hookAudioTrackWrite(lpparam, ShortArray::class.java)
            hookAudioTrackWrite(lpparam, FloatArray::class.java)
            XposedBridge.log("HapticAudio: Hooked AudioTrack in ${lpparam.packageName}")
        } catch (e: Exception) {
            XposedBridge.log("HapticAudio Error: Failed to hook AudioTrack in ${lpparam.packageName} - ${e.message}")
        }
    }

    /**
     * 通用 Hook 方法，适配 byte[], short[], float[] 等不同重载
     */
    private fun hookAudioTrackWrite(lpparam: XC_LoadPackage.LoadPackageParam, arrayType: Class<*>) {
        XposedHelpers.findAndHookMethod(
            "android.media.AudioTrack",
            lpparam.classLoader,
            "write",
            arrayType, // audioData
            Int::class.java, // offsetInBytes/offsetInShorts/offsetInFloats
            Int::class.java, // sizeInBytes/sizeInShorts/sizeInFloats
            Int::class.java, // writeMode
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val audioData = param.args[0] ?: return
                    val offset = param.args[1] as Int
                    val size = param.args[2] as Int

                    // 1. 深度拷贝数据给马达处理器
                    // 注意：必须深拷贝，否则在子线程处理时数据可能已被原主线程覆写
                    when (arrayType) {
                        ByteArray::class.java -> {
                            val original = audioData as ByteArray
                            val copy = original.copyOfRange(offset, offset + size)
                            AudioProcessor.pushAudioData(copy)
                            // 2. 强制静音 (Zero-out buffer)
                            original.fill(0, offset, offset + size)
                        }
                        ShortArray::class.java -> {
                            val original = audioData as ShortArray
                            val copy = original.copyOfRange(offset, offset + size)
                            AudioProcessor.pushAudioData(copy)
                            original.fill(0, offset, offset + size)
                        }
                        FloatArray::class.java -> {
                            val original = audioData as FloatArray
                            val copy = original.copyOfRange(offset, offset + size)
                            AudioProcessor.pushAudioData(copy)
                            original.fill(0.0f, offset, offset + size)
                        }
                    }
                    // 注：通过数组置零，系统 AudioMixer 最终混合的是零能量数据，扬声器绝对不会发声。
                    // 这种方式比 setVolume(0f) 更底层，防止应用层自己又将音量设回来。
                }
            }
        )
    }
}
