package com.hapticaudio

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.animation.OvershootInterpolator
import android.widget.*
import java.io.File

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var scopeText: TextView
    private lateinit var pulseIndicator: ImageView

    private val statusReceiver = object : BroadcastReceiver() {
        @SuppressLint("SetTextI18n")
        override fun onReceive(context: Context?, intent: Intent?) {
            val pkg = intent?.getStringExtra("pkg") ?: "未知"
            statusText.text = "🟢 DSP 引擎：活跃直通中"
            scopeText.text = "🎯 作用域：$pkg"
            triggerPulseAnimation() // 收到底层心跳，触发视觉脉冲
        }
    }

    @SuppressLint("SetTextI18n", "WrongConstant")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val primaryColor = resources.getColor(android.R.color.system_accent1_600, theme)
        val containerColor = resources.getColor(android.R.color.system_accent1_100, theme)
        val textColor = resources.getColor(android.R.color.system_neutral1_900, theme)

        val prefs = getSharedPreferences("haptic_config", Context.MODE_PRIVATE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, IntentFilter("com.hapticaudio.STATUS_UPDATE"), Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(statusReceiver, IntentFilter("com.hapticaudio.STATUS_UPDATE"))
        }

        val scrollView = ScrollView(this)
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 60, 60, 60)
        }

        mainLayout.addView(TextView(this).apply {
            text = "HapticAudio DSP"
            textSize = 28f
            setTextColor(primaryColor)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 20, 0, 40)
        })

        // --- 分区 1：服务状态与脉冲指示器 ---
        val statusCard = createM3Card(containerColor)
        statusCard.addView(TextView(this).apply { text = "📊 DSP 通道状态"; textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(textColor) })
        
        // 动态脉冲指示器 (代替高耗能的实时波形)
        pulseIndicator = ImageView(this).apply {
            setImageResource(android.R.drawable.presence_audio_online)
            setColorFilter(primaryColor)
            layoutParams = LinearLayout.LayoutParams(100, 100).apply { setMargins(0, 20, 0, 20) }
            alpha = 0.3f
        }
        statusCard.addView(pulseIndicator)

        statusText = TextView(this).apply { text = "⚪ DSP 引擎：等待音频流"; textSize = 14f; setTextColor(textColor) }
        scopeText = TextView(this).apply { text = "🎯 作用域：暂无"; textSize = 14f; setTextColor(textColor); setPadding(0, 5, 0, 5) }
        
        statusCard.addView(statusText)
        statusCard.addView(scopeText)
        mainLayout.addView(statusCard)

        // --- 分区 2：核心控制 ---
        val controlCard = createM3Card(containerColor)
        controlCard.addView(Switch(this).apply {
            text = "全局物理静音 (仅马达唱歌)"
            textSize = 16f
            setTextColor(textColor)
            isChecked = prefs.getBoolean("mute_speaker", true)
            setOnCheckedChangeListener { _, isChecked -> prefs.edit().putBoolean("mute_speaker", isChecked).apply() }
        })
        mainLayout.addView(controlCard)

        // --- 分区 3：调音台 ---
        val mixerCard = createM3Card(containerColor)
        mixerCard.addView(TextView(this).apply { text = "🎛️ 信号放大器"; textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(textColor) })
        addM3Slider(mixerCard, "DSP 硬件增益倍率", 10, 500, prefs.getInt("intensity", 200), textColor) { v ->
            prefs.edit().putInt("intensity", v).apply()
            "${v / 100f}x"
        }
        mainLayout.addView(mixerCard)

        scrollView.addView(mainLayout)
        setContentView(scrollView)
    }

    private fun triggerPulseAnimation() {
        pulseIndicator.alpha = 1.0f
        val scaleDown = ObjectAnimator.ofPropertyValuesHolder(
            pulseIndicator,
            PropertyValuesHolder.ofFloat("scaleX", 1.5f, 1.0f),
            PropertyValuesHolder.ofFloat("scaleY", 1.5f, 1.0f),
            PropertyValuesHolder.ofFloat("alpha", 1.0f, 0.3f)
        )
        scaleDown.duration = 400
        scaleDown.interpolator = OvershootInterpolator()
        scaleDown.start()
    }

    private fun createM3Card(color: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(45, 45, 45, 45)
            background = GradientDrawable().apply { setColor(color); cornerRadius = 28f }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 35) }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun addM3Slider(card: LinearLayout, title: String, min: Int, max: Int, defaultVal: Int, textColor: Int, onUpdate: (Int) -> String) {
        val label = TextView(this).apply { text = "$title: ${onUpdate(defaultVal)}"; setPadding(0, 25, 0, 5); setTextColor(textColor); textSize = 14f }
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
        card.addView(label)
        card.addView(slider)
    }

    override fun onPause() {
        super.onPause()
        try {
            val prefFile = File(applicationInfo.dataDir, "shared_prefs/haptic_config.xml")
            if (prefFile.exists()) prefFile.setReadable(true, false)
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(statusReceiver) } catch (e: Exception) {}
    }
}
