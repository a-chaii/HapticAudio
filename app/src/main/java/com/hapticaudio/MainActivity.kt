package com.hapticaudio

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import java.io.File

class MainActivity : Activity() {
    private lateinit var statusText: TextView

    // 接收 Hook 端发来的当前劫持状态
    private val statusReceiver = object : BroadcastReceiver() {
        @SuppressLint("SetTextI18n")
        override fun onReceive(context: Context?, intent: Intent?) {
            val pkg = intent?.getStringExtra("pkg") ?: "未知"
            statusText.text = "🔴 运行状态: 活跃中 (正在劫持)\n🎵 当前目标应用: $pkg"
            statusText.setTextColor(Color.parseColor("#27ae60"))
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val prefs = getSharedPreferences("haptic_config", Context.MODE_PRIVATE)
        registerReceiver(statusReceiver, IntentFilter("com.hapticaudio.STATUS_UPDATE"))

        val scrollView = ScrollView(this)
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 60, 60, 60)
            setBackgroundColor(Color.parseColor("#f5f6fa"))
        }

        // --- 1. 状态展示区 ---
        val statusCard = createCardLayout()
        statusCard.addView(TextView(this).apply { text = "服务状态监控"; textSize = 20f; setTypeface(null, android.graphics.Typeface.BOLD) })
        statusText = TextView(this).apply { 
            text = "⚪ 运行状态: 等待中...\n🎵 当前目标应用: 无"
            textSize = 14f; setPadding(0, 20, 0, 0)
        }
        statusCard.addView(statusText)
        mainLayout.addView(statusCard)

        // --- 2. 核心控制区 ---
        val controlCard = createCardLayout()
        controlCard.addView(TextView(this).apply { text = "核心控制"; textSize = 20f; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0,0,0,30) })
        
        val muteSwitch = Switch(this).apply {
            text = "全局物理静音 (仅马达发声)"
            textSize = 16f
            isChecked = prefs.getBoolean("mute_speaker", true)
            setOnCheckedChangeListener { _, isChecked -> prefs.edit().putBoolean("mute_speaker", isChecked).apply() }
        }
        controlCard.addView(muteSwitch)
        mainLayout.addView(controlCard)

        // --- 3. 马达调音台 (滑块区) ---
        val sliderCard = createCardLayout()
        sliderCard.addView(TextView(this).apply { text = "0916 Turbo 专属调音台"; textSize = 20f; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0,0,0,30) })
        
        // 强度滑块
        addSliderToCard(sliderCard, "整体震动强度倍率", 10, 300, prefs.getInt("intensity", 100)) { v -> 
            prefs.edit().putInt("intensity", v).apply(); return@addSliderToCard "${v/100f}x" 
        }
        
        // 滤波器频段滑块 (决定马达震感的浑厚程度)
        addSliderToCard(sliderCard, "低通滤波 (数值越小越闷，越大越脆)", 1, 100, prefs.getInt("lpf_freq", 20)) { v -> 
            prefs.edit().putInt("lpf_freq", v).apply(); return@addSliderToCard "$v %" 
        }

        // 包络平滑度滑块 (黏合哒哒声的核心)
        addSliderToCard(sliderCard, "波形粘合度 (解决哒哒哒声)", 1, 100, prefs.getInt("smoothing", 80)) { v -> 
            prefs.edit().putInt("smoothing", v).apply(); return@addSliderToCard "$v %" 
        }
        mainLayout.addView(sliderCard)

        // --- 4. 关于页面 ---
        val aboutCard = createCardLayout()
        aboutCard.addView(TextView(this).apply { text = "关于 HapticAudio"; textSize = 20f; setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0,0,0,20) })
        aboutCard.addView(TextView(this).apply {
            text = "由纯手工打造的全自研音频触觉化引擎。\n使用提示：\n1. 调节滑块后实时生效（约延迟1秒）。\n2. 如果感觉马达有机械敲击的“哒哒”声，请调高【波形粘合度】。\n3. 静音开关在底层拦截数组，绝不会有起步漏音现象。"
            textSize = 14f; setTextColor(Color.DKGRAY)
        })
        mainLayout.addView(aboutCard)

        scrollView.addView(mainLayout)
        setContentView(scrollView)
    }

    private fun createCardLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = 8f
            setPadding(50, 50, 50, 50)
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 40)
            layoutParams = params
        }
    }

    @SuppressLint("SetTextI18n")
    private fun addSliderToCard(card: LinearLayout, title: String, min: Int, max: Int, defaultVal: Int, onUpdate: (Int) -> String) {
        val label = TextView(this).apply { text = "$title: ${onUpdate(defaultVal)}"; setPadding(0, 30, 0, 10) }
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
        // 开放权限给 Hook 端读取配置
        try {
            val prefFile = File(applicationInfo.dataDir, "shared_prefs/haptic_config.xml")
            if (prefFile.exists()) prefFile.setReadable(true, false)
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statusReceiver)
    }
}
