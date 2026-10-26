package com.forest.android.assistant.core.runner

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
import com.forest.android.assistant.core.engine.ScriptRunner
import com.forest.android.assistant.core.gesture.GestureClient
import com.forest.android.assistant.core.image.ScreenCapture

/**
 * 脚本运行期保活前台服务（设计 docs/README.md 第 4.6 节「调度与稳定性」）。
 *
 * 职责：
 * - 任务运行期间以前台服务常驻，降低被系统回收的概率，保证长时脚本/找图轮询不中断；
 * - 低优先级常驻通知展示「任务名 + 无障碍/截屏状态自检结果」，并提供「停止」动作
 *   （通知栏即可停止脚本，与悬浮条停止等价）；
 * - 任务结束后自动 `stopSelf`（由 [ScriptRunner] 在清理阶段调用 [stop]）。
 *
 * 说明：前台服务不能阻止厂商级后台清理，配合「保活指引」中的电池优化白名单等设置效果更好。
 */
class ScriptForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "收到通知栏停止请求")
            ScriptRunner.stop()
            return START_NOT_STICKY
        }
        val taskName = intent?.getStringExtra(EXTRA_TASK) ?: ScriptRunner.state.value.taskName ?: "任务"
        startForegroundCompat(taskName)
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(taskName: String) {
        val nm = getSystemService(NotificationManager::class.java)
        // minSdk 29：通知渠道必然可用
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "脚本运行状态",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "脚本运行期间的保活通知" }
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        else
            0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(taskName), type)
            running = true
            Log.i(TAG, "脚本保活前台服务已启动: $taskName")
        } catch (e: Exception) {
            Log.w(TAG, "保活前台服务启动失败: ${e.message}")
        }
    }

    /** 通知内容含任务名与能力自检结果（无障碍 / 截屏） */
    private fun buildNotification(taskName: String): Notification {
        val a11y = if (GestureClient.available()) "无障碍✓" else "无障碍✗"
        val capture = if (ScreenCapture.isReady() || ScreenCapture.silentAvailableCached()) "截屏✓" else "截屏✗"
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScriptForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("脚本运行中：$taskName")
            .setContentText("$a11y  $capture")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopIntent)
            .build()
    }

    override fun onDestroy() {
        running = false
        Log.i(TAG, "脚本保活前台服务已停止")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScriptJs"
        private const val CHANNEL_ID = "script_runner"
        private const val NOTIFICATION_ID = 1002
        private const val EXTRA_TASK = "task_name"
        private const val ACTION_STOP = "com.forest.android.assistant.action.STOP_SCRIPT"

        /** 服务是否在运行（供状态自检/日志核对） */
        @Volatile
        var running = false
            private set

        /** 任务开始：拉起保活前台服务 */
        fun start(context: Context, taskName: String) {
            try {
                val intent = Intent(context, ScriptForegroundService::class.java)
                    .putExtra(EXTRA_TASK, taskName)
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "启动保活前台服务失败: ${e.message}")
            }
        }

        /** 任务结束：停止保活前台服务 */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, ScriptForegroundService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "停止保活前台服务失败: ${e.message}")
            }
        }
    }
}
