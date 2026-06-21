package com.hapticaudio

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket

class HapticServerService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null

    override fun onCreate() {
        super.onCreate()
        startServer()
    }

    private fun startServer() {
        serviceScope.launch {
            try {
                serverSocket = ServerSocket(8080)
                while (isActive) {
                    val socket = serverSocket?.accept() ?: break
                    launch {
                        val input = socket.getInputStream()
                        val reader = BufferedReader(InputStreamReader(input))
                        val output = PrintWriter(socket.getOutputStream())
                        val requestLine = reader.readLine() ?: ""
                        
                        val prefs = getSharedPreferences("haptic_config", Context.MODE_WORLD_READABLE)

                        if (requestLine.contains("/toggle")) {
                            val currentStatus = prefs.getBoolean("mute_speaker", true)
                            prefs.edit().putBoolean("mute_speaker", !currentStatus).apply()
                        } else if (requestLine.contains("/set_intensity")) {
                            val regex = Regex("val=(\\d+)")
                            val match = regex.find(requestLine)
                            if (match != null) {
                                prefs.edit().putInt("intensity", match.groupValues[1].toInt()).apply()
                            }
                        }

                        val isMuteEnabled = prefs.getBoolean("mute_speaker", true)
                        val currentIntensity = prefs.getInt("intensity", 100)

                        output.println("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n\r\n")
                        output.println("""
                            <html><body>
                            <h1>Haptic Control</h1>
                            <p>喇叭状态: ${if(isMuteEnabled) "已静音" else "运行中"}</p>
                            <a href='/toggle'>[切换静音状态]</a>
                            <br><br>
                            <p>震动强度: $currentIntensity%</p>
                            <input type='range' min='10' max='300' value='$currentIntensity' 
                            onchange='fetch("/set_intensity?val="+this.value).then(()=>location.reload())'>
                            </body></html>
                        """.trimIndent())
                        output.flush()
                        socket.close()
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    override fun onDestroy() {
        serverSocket?.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
