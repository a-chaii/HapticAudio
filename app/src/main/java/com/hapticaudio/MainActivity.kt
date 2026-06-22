package com.hapticaudio

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
import android.widget.*
import java.io.File

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var scopeText: TextView

    // 接收来自 Hook 进程的真实活跃状态
    private val statusReceiver = object : BroadcastReceiver() {
        @SuppressLint("SetTextI18n")
        override fun onReceive(context: Context?, intent: Intent?) {
            val pkg = intent?.getStringExtra("pkg") ?: "未知"
            statusText.text = "🟢 引擎状态：底层共振中 (Active)"
            scopeText.text = "🎯 作用域：$pkg"
        }
    }

    @SuppressLint("SetTextI18n", "WrongConstant")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 引入安卓 12+ 莫奈动态取色（核心色与表面色）
        val primaryColor = resources.getColor(android.R.color.system_accent1_600, theme)
        val containerColor = resources.getColor(android.R.color.system_accent1_100, theme)
        val textColor = resources.getColor(android.R.color.system_neutral1_900, theme)

        val prefs = getSharedPreferences("haptic_config", Context.MODE_PRIVATE)

        // 适配安卓 14/16 的跨进程广播安全策略 (RECEIVER_EXPORTED)
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

        // 大标题
        mainLayout.addView(TextView(this).apply {
            text = "HapticAudio"
            textSize = 28f
            setTextColor(primaryColor)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 20, 0, 40)
        })

        // --- 分区 1：服务状态监控 (Material 3 Card) ---
        val statusCard = createM3Card(containerColor)
        statusCard.addView(TextView(this).apply { text = "📊 服务状态监控"; textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(textColor) })
        
        statusText = TextView(this).apply { text = "⚪ 引擎状态：等待音频流 (Idle)"; textSize = 14f; setPadding(0, 15, 0, 5); setTextColor(textColor) }
        scopeText = TextView(this).apply { text = "🎯 作用域：暂无"; textSize = 14f; setPadding(0, 0, 0, 5); setTextColor(textColor) }
        
        val hasVibratePermission = checkSelfPermission(android.Manifest.permission.VIBRATE) == PackageManager.PERMISSION_GRANTED
        val permText = TextView(this).apply { 
            text = if (hasVibratePermission) "🛡️ 马达权限：已授予" else "⚠️ 马达权限：未授予"
            textSize = 14f; setTextColor(textColor) 
        }
        
        statusCard.addView(statusText)
        statusCard.addView(scopeText)
        statusCard.addView(permText)
        mainLayout.addView(statusCard)

        // --- 分区 2：核心控制 ---
        val controlCard = createM3Card(containerColor)
        val muteSwitch = Switch(this).apply {
            text = "全局物理静音 (原始音频斩断入卡)"
            textSize = 16f
            setTextColor(textColor)
            isChecked = prefs.getBoolean("mute_speaker", true)
            setOnCheckedChangeListener { _, isChecked -> prefs.edit().putBoolean("mute_speaker", isChecked).apply() }
        }
        controlCard.addView(muteSwitch)
        mainLayout.addView(controlCard)

        // --- 分区 3：共振微调台 ---
        val mixerCard = createM3Card(containerColor)
        mixerCard.addView(TextView(this).apply { text = "🎛️ 0916 Turbo 原声微调"; textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(textColor) })
        
        // 缩放滑块
        addM3Slider(mixerCard, "共振推力倍率", 10, 400, prefs.getInt("intensity", 150), textColor) { v ->
            prefs.edit().putInt("intensity", v).apply()
            "${v / 100f}x"
        }
        // 截止门限
        addM3Slider(mixerCard, "微弱声噪过滤门限", 0, 100, prefs.getInt("noise_gate", 10), textColor) { v ->
            prefs.edit().putInt("noise_gate", v).apply()
            "Gate: $v"
        }
        mainLayout.addView(mixerCard)

        // --- 分区 4：关于 ---
        val aboutCard = createM3Card(containerColor)
        aboutCard.addView(TextView(this).apply { text = "✨ 关于音乐共振"; textSize = 16f; setTypeface(null, android.graphics.Typeface.BOLD); setTextColor(textColor) })
        aboutCard.addView(TextView(this).apply {
            text = "本版本全面撤销滤波器，采用 15ms 零延迟采样窗直接映射音频振幅。拉动滑块可实时改变马达推力。界面配色已完美适配安卓 16 莫奈动态引擎。"
            textSize = 13f; setTextColor(textColor); setPadding(0, 10, 0, 0)
        })
        mainLayout.addView(aboutCard)

        scrollView.addView(mainLayout)
        setContentView(scrollView)
    }

    private fun createM3Card(color: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(45, 45, 45, 45)
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = 28f // M3 标准大圆角
            }
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 35)
            layoutParams = params
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
