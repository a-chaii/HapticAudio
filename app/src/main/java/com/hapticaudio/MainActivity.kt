package com.hapticaudio

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import java.io.File

class MainActivity : Activity() {

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val prefs = getSharedPreferences("haptic_config", Context.MODE_PRIVATE)

        // 纯代码手搓原生 UI，拒绝臃肿
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(80, 100, 80, 100)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val title = TextView(this).apply {
            text = "HapticAudio\nDSP 硬件直通引擎"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 60)
        }

        val muteSwitch = Switch(this).apply {
            text = "强制静音 (仅马达发声)"
            textSize = 18f
            setTextColor(Color.LTGRAY)
            isChecked = prefs.getBoolean("mute_speaker", true)
            setPadding(0, 0, 0, 60)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("mute_speaker", isChecked).apply()
            }
        }

        val intensityLabel = TextView(this).apply {
            text = "马达震感增益倍率: ${prefs.getInt("intensity", 100) / 100f}x"
            textSize = 16f
            setTextColor(Color.LTGRAY)
        }

        val intensitySlider = SeekBar(this).apply {
            max = 300
            progress = prefs.getInt("intensity", 100)
            setPadding(0, 30, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val safeProgress = if (progress < 10) 10 else progress
                    intensityLabel.text = "马达震感增益倍率: ${safeProgress / 100f}x"
                    prefs.edit().putInt("intensity", safeProgress).apply()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        layout.addView(title)
        layout.addView(muteSwitch)
        layout.addView(intensityLabel)
        layout.addView(intensitySlider)
        
        setContentView(layout)
    }

    override fun onPause() {
        super.onPause()
        // 【核心魔法】：退出界面时，赋予配置文件全局读取权限，让 LSPosed Hook 能够顺利读到配置
        try {
            val prefFile = File(applicationInfo.dataDir, "shared_prefs/haptic_config.xml")
            if (prefFile.exists()) {
                prefFile.setReadable(true, false)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
