package com.forest.android.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.forest.android.assistant.core.floaty.PickerOverlay
import com.forest.android.assistant.core.image.ScreenCapture

/** 从悬浮工具请求录屏授权时使用独立的透明任务，保留原来前台应用的任务顺序。 */
class PickerCapturePermissionActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_MODE = "picker_mode"

        fun launch(context: Context, mode: PickerOverlay.Mode) {
            context.startActivity(
                Intent(context, PickerCapturePermissionActivity::class.java)
                    .putExtra(EXTRA_MODE, mode.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private val pickerMode: PickerOverlay.Mode? by lazy {
        intent.getStringExtra(EXTRA_MODE)?.let { name ->
            runCatching { PickerOverlay.Mode.valueOf(name) }.getOrNull()
        }
    }
    private var completed = false

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            ScreenCapture.onProjectionResult(result.resultCode, result.data)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = pickerMode
        if (mode == null) {
            finish()
            return
        }
        ScreenCapture.bind(this) { request -> projectionLauncher.launch(request) }
        Thread {
            val granted = ScreenCapture.requestBlocking()
            runOnUiThread {
                if (isDestroyed || completed) return@runOnUiThread
                completed = true
                finish()
                PickerOverlay.onCapturePermissionResult(applicationContext, mode, granted)
            }
        }.start()
    }

    override fun onDestroy() {
        ScreenCapture.unbind(this)
        if (!isChangingConfigurations && !completed) {
            completed = true
            pickerMode?.let { PickerOverlay.onCapturePermissionResult(applicationContext, it, false) }
        }
        super.onDestroy()
    }
}
