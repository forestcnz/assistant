package com.forest.android.assistant

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.forest.android.assistant.core.floaty.PickerOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 从悬浮截图工具打开系统保存位置选择器，并将临时 PNG 写入用户选择的位置。 */
class ScreenshotExportActivity : ComponentActivity() {
    companion object {
        const val EXTRA_SOURCE = "screenshot_source"
        const val EXTRA_NAME = "screenshot_name"
    }

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        val source = intent.getStringExtra(EXTRA_SOURCE)?.let(::File)
        if (uri == null) {
            source?.delete()
            Toast.makeText(this, "已取消保存截图", Toast.LENGTH_SHORT).show()
            finish()
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val file = source?.takeIf { it.isFile } ?: error("找不到截图文件")
                    val output = contentResolver.openOutputStream(uri, "w") ?: error("无法写入所选位置")
                    output.buffered().use { destination ->
                        file.inputStream().buffered().use { input -> input.copyTo(destination) }
                    }
                }
            }
            source?.delete()
            result.onSuccess {
                val location = uri.toString()
                PickerOverlay.onScreenshotSaved?.invoke(location)
                Toast.makeText(this@ScreenshotExportActivity, "截图已保存", Toast.LENGTH_LONG).show()
            }.onFailure { error ->
                Toast.makeText(this@ScreenshotExportActivity, "截图保存失败：${error.message}", Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = intent.getStringExtra(EXTRA_SOURCE)?.let(::File)
        val name = intent.getStringExtra(EXTRA_NAME)
        if (source?.isFile != true || name.isNullOrBlank()) {
            source?.delete()
            Toast.makeText(this, "截图文件无效", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (savedInstanceState == null) {
            window.decorView.post { createDocument.launch(name) }
        }
    }
}
