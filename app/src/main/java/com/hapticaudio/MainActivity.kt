package com.hapticaudio

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 启动后台服务
        startService(Intent(this, HapticServerService::class.java))
        
        val tv = TextView(this)
        tv.text = "HapticAudio (0916 Turbo 优化版)\n\n后台服务已启动\n请在浏览器访问：\nhttp://localhost:8080"
        tv.setPadding(60, 60, 60, 60)
        setContentView(tv)
    }
}
