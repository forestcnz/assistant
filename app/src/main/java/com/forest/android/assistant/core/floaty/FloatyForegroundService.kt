package com.forest.android.assistant.core.floaty

import android.app.Notification
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
import com.forest.android.assistant.core.engine.ScriptRunner

/**
 * 悬浮球启用期间的前台服务。部分模拟器会在应用切到后台时回收普通进程，
 * 该服务降低悬浮窗随进程一起消失的概率；系统重建服务时会重新挂载悬浮球。
 */
class FloatyForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) {
            FloatyManager.setEnabledByUser(this, false)
            FloatyManager.hideLauncher(stopKeepAlive = false)
            ScriptRunner.onLauncherHiddenByUser()   // 同步「工具」页悬浮球开关状态
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!startForegroundCompat()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        if (!FloatyManager.isEnabledByUser(this) || !FloatyManager.hasOverlayPermission(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // 窗口已存在时这是无操作；进程被回收后则重新创建 WindowManager 窗口。
        if (!FloatyManager.showLauncher(this, keepAlive = false)) {
            Log.w(TAG, "服务启动后悬浮球恢复失败")
        }
        return START_STICKY
    }

    private fun startForegroundCompat(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "悬浮球状态",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "悬浮球启用期间保持后台服务运行" }
            )
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            running = true
            true
        } catch (e: Exception) {
            running = false
            Log.w(TAG, "悬浮球保活服务启动失败: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val hideOverlay = PendingIntent.getService(
            this,
            1,
            Intent(this, FloatyForegroundService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("悬浮球已启用")
            .setContentText("切换到其他应用时仍保持显示")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "隐藏悬浮球", hideOverlay)
            .build()
    }

    override fun onDestroy() {
        running = false
        Log.i(TAG, "悬浮球保活前台服务已停止")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScriptJs"
        private const val CHANNEL_ID = "floaty_keep_alive"
        private const val NOTIFICATION_ID = 1003
        private const val ACTION_HIDE = "com.forest.android.assistant.action.HIDE_FLOATY"

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    Intent(context, FloatyForegroundService::class.java)
                )
            } catch (e: Exception) {
                Log.w(TAG, "启动悬浮球保活服务失败: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, FloatyForegroundService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "停止悬浮球保活服务失败: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}
