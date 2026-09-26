package com.ds.localapi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.ds.localapi.server.LocalServer
import com.ds.localapi.store.ApiKey
import com.ds.localapi.store.Settings

/**
 * 前台服务：承载本地 OpenAI 兼容 HTTP 服务，防止进程被回收。
 */
class ServerService : Service() {

    private var server: LocalServer? = null

    override fun onCreate() {
        super.onCreate()
        ApiKey.init(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra(EXTRA_ACTION)) {
            ACTION_START -> startServer()
            ACTION_STOP -> stopServer()
        }
        return START_NOT_STICKY
    }

    private fun startServer() {
        val settings = Settings(this)
        val host = if (settings.lanEnabled) "0.0.0.0" else "127.0.0.1"
        val port = settings.port

        stopRanServer()
        val srv = LocalServer(host, port, settings)
        try {
            srv.start(port, true)
            server = srv
            startForeground(NOTIFICATION_ID, buildNotification("服务运行中 · 端口 $port"))
        } catch (e: Exception) {
            server = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopRanServer() {
        server?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
        }
        server = null
    }

    private fun stopServer() {
        stopRanServer()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopRanServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "本地 API 服务",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "ds_local_api_server"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_ACTION = "action"
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"

        fun start(context: Context) {
            val i = Intent(context, ServerService::class.java).putExtra(EXTRA_ACTION, ACTION_START)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, ServerService::class.java).putExtra(EXTRA_ACTION, ACTION_STOP)
            context.startService(i)
        }
    }
}