package com.nvmex.networkhelper.iperf.server

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.nvmex.networkhelper.iperf.IperfNative
import com.nvmex.networkhelper.iperf.IperfNative.StreamCallback
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class IperfServerService : Service() {

    companion object {
        const val ACTION_START = "iperf.START"
        const val ACTION_STOP = "iperf.STOP"
        const val EXTRA_PORT = "port"

        private val _running = MutableStateFlow(false)
        val running = _running.asStateFlow()

        private val _log = MutableStateFlow("")
        val log = _log.asStateFlow()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var currentPort: Int = 5201

    override fun onCreate() {
        super.onCreate()
        startForeground(1001, buildNotification("准备中…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, 5201)
                startServer(port)
            }
            ACTION_STOP -> stopServer()
        }
        return START_STICKY
    }

    private fun startServer(port: Int) {
        if (job != null) return

        currentPort = port
        _log.value = ""
        setRunning(true)
        updateNotification("运行中：$port")

        job = scope.launch {
            val r = runCatching {
                // ✅阻塞式：iperf_run_server，会一直跑到结束/被 stop 打断
                IperfNative.runServerStream(
                    port = port,
                    cb = object : StreamCallback {
                        override fun onLine(line: String) {
                            _log.value = (_log.value + line + "\n").takeLast(25_000)
                        }
                    }
                )
            }

            // 跑完（正常结束/异常）都会到这
            val err = r.exceptionOrNull()?.message
            if (err != null) {
                _log.value = (_log.value + "server error: $err\n").takeLast(25_000)
            }

            setRunning(false)
            job = null
            updateNotification("已停止")
            stopSelf()
        }
    }

    private fun stopServer() {
        // ✅告诉 native 停（best-effort）
        runCatching { IperfNative.stop() }

        job?.cancel() // 取消 coroutine（native 仍需 stop 触发返回）
        job = null
        setRunning(false)
        updateNotification("已停止")
        stopSelf()
    }

    private fun buildNotification(text: String): Notification {
        val channelId = "iperf_server"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Iperf Server", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val stopIntent = Intent(this, IperfServerService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("内网测速服务端")
            .setContentText(text)
            .addAction(NotificationCompat.Action(0, "停止", stopPending))
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(1001, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { IperfNative.stop() }
        scope.cancel()
        super.onDestroy()
    }

    private fun setRunning(v: Boolean) {
        _running.value = v
        getSharedPreferences("iperf_server", MODE_PRIVATE)
            .edit()
            .putBoolean("running", v)
            .putInt("port", currentPort)
            .apply()
    }

}

