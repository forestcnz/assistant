package com.forest.android.assistant.core.web

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.forest.android.assistant.MainActivity

class LanWebService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        LanWebServer.start(this)
        startForegroundCompat()
        if (!LanWebServer.state.value.running) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "局域网 Web 编辑",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "在浏览器中编辑脚本和可视化流程" }
        )
        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, LanWebService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val url = LanWebServer.state.value.url ?: "正在获取局域网地址"
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("局域网 Web 编辑运行中")
            .setContentText(url)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopIntent)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        else
            0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    override fun onDestroy() {
        LanWebServer.stop()
        Log.i(TAG, "局域网 Web 编辑服务已停止")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LanWebEditor"
        private const val CHANNEL_ID = "lan_web_editor"
        private const val NOTIFICATION_ID = 1003
        private const val ACTION_STOP = "com.forest.android.assistant.action.STOP_LAN_WEB"

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, LanWebService::class.java)
                )
            }.onFailure {
                Log.w(TAG, "启动局域网 Web 编辑服务失败: ${it.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LanWebService::class.java))
            LanWebServer.stop()
        }
    }
}
