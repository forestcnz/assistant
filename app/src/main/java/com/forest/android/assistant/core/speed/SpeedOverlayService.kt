package com.forest.android.assistant.core.speed

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.forest.android.assistant.AppSpeedActivity
import kotlinx.coroutines.*

/** A user-started foreground service owns the overlay independently of the activity. */
class SpeedOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var panel: LinearLayout? = null
    private lateinit var manager: WindowManager
    private var pending = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "close") { stopSelf(); return START_NOT_STICKY }
        val id = intent?.getStringExtra("package") ?: run { stopSelf(); return START_NOT_STICKY }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        val repository = SpeedRepository(this)
        val app = repository.app(id)
        if (app == null || !app.patched) { stopSelf(); return START_NOT_STICKY }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("app-speed", "应用变速控制", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, AppSpeedActivity::class.java).putExtra("speedPackage", id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val close = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction("close"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, "app-speed").setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("${app.label} · 悬浮调速").setContentText("点击返回设置；关闭面板会保留当前倍率")
                .setContentIntent(open).setOngoing(true).addAction(0, "关闭面板", close).build()
            ServiceCompat.startForeground(this, 1005, notification,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            manager = getSystemService(WindowManager::class.java)
            panel?.let { manager.removeView(it) }; panel = null
            val layout = WindowManager.LayoutParams(dp(246), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(100) }
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(10))
                background = GradientDrawable().apply { setColor(Color.rgb(255, 250, 244)); cornerRadius = dp(14).toFloat(); setStroke(dp(1), Color.rgb(222, 211, 198)) }
                elevation = dp(8).toFloat()
            }
            val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val title = TextView(this).apply { text = app.label; textSize = 14f; setTextColor(Color.rgb(49, 46, 43)); maxLines = 1; contentDescription = "拖动变速面板" }
            header.addView(title, LinearLayout.LayoutParams(0, dp(42), 1f))
            header.addView(Button(this).apply { text = "×"; contentDescription = "关闭变速面板"; setPadding(0, 0, 0, 0); setOnClickListener { stopSelf() } }, LinearLayout.LayoutParams(dp(44), dp(44)))
            root.addView(header)
            val status = TextView(this).apply { text = "选择倍率，1× 可恢复正常速度"; textSize = 12f; setTextColor(Color.DKGRAY) }
            root.addView(status)
            val buttons = mutableListOf<Button>()
            val row = LinearLayout(this)
            listOf(.5f, 1f, 2f, 3f, 5f).forEach { factor ->
                val button = Button(this).apply {
                    text = "${if (factor == .5f) "0.5" else factor.toInt()}×"; textSize = 12f
                    minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
                    setOnClickListener {
                        if (pending) return@setOnClickListener
                        pending = true; buttons.forEach { it.isEnabled = false }; status.text = "正在设置…"
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) { repository.setSpeed(id, factor, repository.initialNative(id)) }
                                status.text = if (result.error.isEmpty()) "已发送 ${factor}× 设置" else "调速未完成：${result.error}"
                            } catch (error: Exception) { status.text = "无法连接：${error.message}" }
                            finally { pending = false; buttons.forEach { it.isEnabled = true } }
                        }
                    }
                }
                buttons += button; row.addView(button, LinearLayout.LayoutParams(0, dp(46), 1f))
            }
            root.addView(row)
            var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
            title.setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = layout.x; startY = layout.y; true }
                    MotionEvent.ACTION_MOVE -> {
                        val metrics = resources.displayMetrics
                        layout.x = (startX + event.rawX - downX).toInt().coerceIn(0, (metrics.widthPixels - root.width).coerceAtLeast(0))
                        layout.y = (startY + event.rawY - downY).toInt().coerceIn(0, (metrics.heightPixels - root.height).coerceAtLeast(0))
                        runCatching { manager.updateViewLayout(root, layout) }; true
                    }
                    MotionEvent.ACTION_UP -> { view.performClick(); true }
                    else -> false
                }
            }
            manager.addView(root, layout); panel = root
        } catch (error: Exception) {
            Toast.makeText(this, "无法打开悬浮调速：${error.message}", Toast.LENGTH_LONG).show(); stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        scope.cancel()
        panel?.let { runCatching { manager.removeView(it) } }; panel = null
        super.onDestroy()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object {
        fun show(context: Context, packageName: String) {
            ContextCompat.startForegroundService(context, Intent(context, SpeedOverlayService::class.java).putExtra("package", packageName))
        }
    }
}
