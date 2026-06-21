package com.hapticaudio

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.widget.TextView
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket

class MainActivity : Activity() {
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val textView = TextView(this).apply {
            text = "HapticAudio (0916 Turbo 专属版)\n控制后台已启动！\n\n请在浏览器访问：\nhttp://localhost:8080"
            textSize = 18f
            setPadding(60, 60, 60, 60)
        }
        setContentView(textView)
        startLocalWebServer()
    }

    private fun startLocalWebServer() {
        serverScope.launch {
            try {
                val serverSocket = ServerSocket(8080)
                while (isActive) {
                    val socket = serverSocket.accept()
                    launch {
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        val output = PrintWriter(socket.getOutputStream())
                        val requestLine = reader.readLine() ?: ""
                        val prefs = getSharedPreferences("haptic_config", Context.MODE_WORLD_READABLE)

                        // 拦截处理静音开关与强度滑块的请求
                        if (requestLine.contains("/toggle")) {
                            val currentStatus = prefs.getBoolean("mute_speaker", true)
                            prefs.edit().putBoolean("mute_speaker", !currentStatus).commit()
                        } else if (requestLine.contains("/set_intensity")) {
                            val regex = Regex("val=(\\d+)")
                            val match = regex.find(requestLine)
                            if (match != null) {
                                val newInt = match.groupValues[1].toInt()
                                prefs.edit().putInt("intensity", newInt).commit()
                            }
                        }

                        val isMuteEnabled = prefs.getBoolean("mute_speaker", true)
                        val currentIntensity = prefs.getInt("intensity", 100)

                        output.println("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n\r\n")
                        output.println("""
                            <!DOCTYPE html>
                            <html>
                            <head>
                                <meta charset='utf-8'>
                                <meta name='viewport' content='width=device-width, initial-scale=1.0'>
                                <title>HapticAudio 控制台</title>
                                <style>
                                    body { font-family: -apple-system, sans-serif; text-align: center; padding: 20px; background: #1e272e; color: #fff; }
                                    .card { background: #2d3436; max-width: 400px; margin: 0 auto; padding: 30px; border-radius: 16px; box-shadow: 0 4px 12px rgba(0,0,0,0.3); }
                                    .btn { padding: 15px 40px; font-size: 18px; color: white; border: none; border-radius: 30px; cursor: pointer; font-weight: bold; width: 100%; margin-bottom: 20px;}
                                    .mute-on { background: #ff4757; }
                                    .mute-off { background: #2ed573; }
                                    .slider-container { margin-top: 30px; text-align: left; background: #353b48; padding: 20px; border-radius: 12px;}
                                    input[type=range] { width: 100%; margin-top: 15px; accent-color: #0fb9b1; }
                                    .val-display { float: right; font-weight: bold; color: #0fb9b1; }
                                </style>
                            </head>
                            <body>
                                <div class='card'>
                                    <h2>HapticAudio</h2>
                                    <p style="color:#808e9b;">一加 0916 Turbo 专属驱动</p>
                                    
                                    <div style="margin: 25px 0; font-size: 18px;">
                                        喇叭：<strong>${if (isMuteEnabled) "<span style='color:#ff4757;'>已静音 (纯震)</span>" else "<span style='color:#2ed573;'>发声 (双开)</span>"}</strong>
                                    </div>
                                    
                                    <button class='btn ${if (isMuteEnabled) "mute-on" else "mute-off"}' onclick="location.href='/toggle'">
                                        ${if (isMuteEnabled) "恢复喇叭声音" else "开启强制静音"}
                                    </button>

                                    <div class='slider-container'>
                                        <label>马达震动强度倍率: <span class='val-display' id='val'>${currentIntensity / 100f}x</span></label>
                                        <input type='range' min='10' max='300' value='${currentIntensity}' 
                                            oninput="document.getElementById('val').innerText = (this.value/100).toFixed(1) + 'x'"
                                            onchange="fetch('/set_intensity?val=' + this.value)">
                                    </div>
                                    <p style="font-size:12px; color:#718093; margin-top:15px;">*调整滑块后自动保存，约1秒后生效</p>
                                </div>
                            </body>
                            </html>
                        """.trimIndent())
                        output.flush()
                        socket.close()
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serverScope.cancel()
    }
}
