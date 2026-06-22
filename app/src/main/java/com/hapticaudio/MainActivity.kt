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

        val scrollView = ScrollView(this)
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 80, 60, 80)
        }

        // --- 1. 状态展示区 ---
        mainLayout.addView(TextView(this).apply { 
            text = "HapticAudio (长波版)"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 40)
        })

        // 通过读取 Xposed 端写下的文件来判断当前劫持目标 (绝对安全，不崩)
        val targetApp = prefs.getString("last_hooked_pkg", "暂无，请播放音乐后重进此页")
        mainLayout.addView(TextView(this).apply {
            text = "🎵 当前劫持的音频应用:\n$targetApp"
            textSize = 14f
            setTextColor(Color.parseColor("#009432"))
            setPadding(0, 0, 0, 60)
        })

        // --- 2. 物理静音开关 ---
        val muteSwitch = Switch(this).apply {
            text = "开启全局物理静音"
            textSize = 18f
            isChecked = prefs.getBoolean("mute_speaker", true)
            setPadding(0, 0, 0, 60)
            setOnCheckedChangeListener { _, isChecked -> 
                prefs.edit().putBoolean("mute_speaker", isChecked).apply() 
            }
        }
        mainLayout.addView(muteSwitch)

        // --- 3. 调音台滑块 ---
        mainLayout.addView(TextView(this).apply { text = "⚙️ 马达调音台"; textSize = 18f; setTypeface(null, android.graphics.Typeface.BOLD) })
        
        // 强度滑块
        addSlider(mainLayout, "马达震动总强度", 10, 300, prefs.getInt("intensity", 100)) { v -> 
            prefs.edit().putInt("intensity", v).apply()
            "${v / 100f}x"
        }

        // 敏感度门限 (干掉微小的电流声/底噪)
        addSlider(mainLayout, "底噪过滤门限", 0, 50, prefs.getInt("noise_gate", 15)) { v -> 
            prefs.edit().putInt("noise_gate", v).apply()
            "过滤强度: $v"
        }

        // --- 4. 关于页面 ---
        mainLayout.addView(TextView(this).apply { text = "📝 关于说明"; textSize = 18f; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0, 60, 0, 20) })
        mainLayout.addView(TextView(this).apply {
            text = "1. 物理静音：在声卡前拦截填0，彻底消灭暂停漏音。\n2. 长波引擎：将音频切分为500ms的长波进行发送，解决由于马达频繁急刹车导致的“哒哒哒”异响。\n3. 安卓16兼容：移除协程与广播，改用Java底层多线程。"
            textSize = 14f
            setTextColor(Color.GRAY)
        })

        scrollView.addView(mainLayout)
        setContentView(scrollView)
    }

    @SuppressLint("SetTextI18n")
    private fun addSlider(layout: LinearLayout, title: String, min: Int, max: Int, defaultVal: Int, onUpdate: (Int) -> String) {
        val label = TextView(this).apply { text = "$title: ${onUpdate(defaultVal)}"; setPadding(0, 40, 0, 10) }
        val slider = SeekBar(this).apply {
            this.max = max - min
            progress = defaultVal - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, prog: Int, fromUser: Boolean) {
                    label.text = "$title: ${onUpdate(prog + min)}"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        layout.addView(label)
        layout.addView(slider)
    }

    override fun onPause() {
        super.onPause()
        // 给配置文件提权，让音乐应用里的 Hook 代码能够读取
        try {
            val prefFile = File(applicationInfo.dataDir, "shared_prefs/haptic_config.xml")
            if (prefFile.exists()) prefFile.setReadable(true, false)
        } catch (e: Exception) { e.printStackTrace() }
    }
}
