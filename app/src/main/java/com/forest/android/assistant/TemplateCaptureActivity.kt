package com.forest.android.assistant

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.forest.android.assistant.core.image.ScreenCapture
import com.forest.android.assistant.core.task.TaskManager
import com.forest.android.assistant.ui.ScriptTheme
import com.forest.android.assistant.ui.AppIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 模板截取工具：截取当前屏幕 → 拖拽框选区域 → 裁剪并保存为任务素材（images/）。
 * 与设计 docs/README.md 第 4.5 节「模板截取工具」一致，是找图素材的一键制作入口。
 */
class TemplateCaptureActivity : ComponentActivity() {

    private var bitmap by mutableStateOf<Bitmap?>(null)
    private var error by mutableStateOf<String?>(null)
    private var selection by mutableStateOf<android.graphics.Rect?>(null)   // 位图坐标
    private var showSaveDialog by mutableStateOf(false)
    private var fileName by mutableStateOf("")
    private var saving by mutableStateOf(false)
    private var backRequestedWhileSaving = false

    /** 记录截图里“点击任务运行按钮”等元素位置无关；仅用于坐标系换算 */
    private var viewSize by mutableStateOf(IntSize.Zero)

    private val taskName: String by lazy { intent.getStringExtra(EXTRA_TASK) ?: "" }

    /** 本界面自行绑定截屏授权启动器（避免被主界面拉到前台） */
    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            ScreenCapture.onProjectionResult(result.resultCode, result.data)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (saving) backRequestedWhileSaving = true else finish()
            }
        })
        ScreenCapture.bind(this) { intent -> projectionLauncher.launch(intent) }
        setContent {
            ScriptTheme {
                CaptureScreen()
            }
        }
        captureFrame()
    }

    override fun onDestroy() {
        ScreenCapture.unbind(this)
        super.onDestroy()
    }

    /** 取一帧截图：未授权时先申请（会弹系统授权窗） */
    private fun captureFrame() {
        error = null
        bitmap = null
        selection = null
        lifecycleScope.launch {
            val frame = withContext(Dispatchers.IO) {
                if (!ScreenCapture.isReady() && !ScreenCapture.requestBlocking()) {
                    null
                } else {
                    ScreenCapture.capture()
                }
            }
            if (frame == null) {
                error = "截屏不可用：请先在主界面完成「截屏授权」"
            } else {
                bitmap = frame
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun CaptureScreen() {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { if (saving) backRequestedWhileSaving = true else finish() }, enabled = !saving) {
                            AppIcon(R.drawable.ic_back, "返回")
                        }
                    },
                    title = {
                        Column {
                            Text("截取模板", style = MaterialTheme.typography.titleMedium)
                            Text(taskName, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {
                        TextButton(onClick = { captureFrame() }, enabled = !saving) { Text("重截") }
                        TextButton(
                            onClick = { openSaveDialog() },
                            enabled = selectionValid() && !saving
                        ) { Text("保存") }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFF1D2B46))
                        .onSizeChanged { viewSize = it },
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = bitmap
                    if (bmp == null) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (error == null) {
                                CircularProgressIndicator(color = Color(0xFFADC6FF))
                                Spacer(Modifier.height(16.dp))
                            }
                            Text(error ?: "正在获取屏幕截图…", color = Color.White,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "屏幕截图",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                        // 框选层：拖动定义选区（位图坐标）
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(bmp, saving) {
                                    detectDragGestures(
                                        onDragStart = { start ->
                                            if (!saving) selection = toBitmapRect(start, start, bmp)
                                        },
                                        onDrag = { change, _ ->
                                            if (!saving) {
                                                selection = toBitmapRect(
                                                    lastStart ?: change.position,
                                                    change.position,
                                                    bmp
                                                )
                                            }
                                        },
                                        onDragEnd = { lastStart = null },
                                        onDragCancel = { lastStart = null }
                                    )
                                }
                        ) {
                            val sel = selection ?: return@Canvas
                            val scale = fitScale(bmp.width, bmp.height, size.width, size.height)
                            val offX = (size.width - bmp.width * scale) / 2f
                            val offY = (size.height - bmp.height * scale) / 2f
                            val rect = androidx.compose.ui.geometry.Rect(
                                offX + sel.left * scale,
                                offY + sel.top * scale,
                                offX + sel.right * scale,
                                offY + sel.bottom * scale
                            )
                            // 选区外压暗
                            val dim = Color(0x99000000)
                            drawRect(dim, Offset(0f, 0f), Size(size.width, rect.top))
                            drawRect(dim, Offset(0f, rect.bottom), Size(size.width, size.height - rect.bottom))
                            drawRect(dim, Offset(0f, rect.top), Size(rect.left, rect.height))
                            drawRect(dim, Offset(rect.right, rect.top), Size(size.width - rect.right, rect.height))
                            // 选区边框
                            drawRect(
                                color = Color(0xFFADC6FF),
                                topLeft = rect.topLeft,
                                size = rect.size,
                                style = Stroke(width = 3f)
                            )
                        }
                    }
                }

                // 底部信息栏：选区尺寸提示
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val sel = selection
                    Text(
                        if (bitmap == null) "等待截图"
                        else if (sel == null) "在截图上拖拽，框选要作为模板的区域"
                        else if (!selectionValid()) "${sel.width()} × ${sel.height()} px\n选区至少需要 ${MIN_SIZE} × ${MIN_SIZE} px"
                        else "${sel.width()} × ${sel.height()} px\n起点 (${sel.left}, ${sel.top})",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(end = 12.dp)
                    )
                    if (bitmap != null && sel != null) {
                        Button(onClick = { openSaveDialog() }, enabled = selectionValid() && !saving) { Text("保存模板") }
                    }
                }
            }
        }

        if (showSaveDialog) {
            AlertDialog(
                onDismissRequest = { showSaveDialog = false },
                title = { Text("保存模板") },
                text = {
                    Column {
                        Text("保存到任务「$taskName」的 images/ 目录", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = fileName,
                            onValueChange = { fileName = it },
                            label = { Text("文件名") },
                            singleLine = true,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showSaveDialog = false
                            saveSelection()
                        },
                        enabled = fileName.isNotBlank() && !saving
                    ) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = { showSaveDialog = false }) { Text("取消") }
                }
            )
        }
    }

    private var lastStart: Offset? = null

    /** 拖动起点（用于 onDrag 时计算矩形） */
    private fun toBitmapRect(start: Offset, end: Offset, bmp: Bitmap): android.graphics.Rect {
        lastStart = start
        val scale = fitScale(bmp.width, bmp.height, viewSize.width.toFloat(), viewSize.height.toFloat())
        if (scale <= 0f) return android.graphics.Rect()
        val offX = (viewSize.width - bmp.width * scale) / 2f
        val offY = (viewSize.height - bmp.height * scale) / 2f
        fun bx(x: Float) = ((x - offX) / scale).roundToInt().coerceIn(0, bmp.width)
        fun by(y: Float) = ((y - offY) / scale).roundToInt().coerceIn(0, bmp.height)
        val x1 = bx(min(start.x, end.x))
        val y1 = by(min(start.y, end.y))
        val x2 = bx(max(start.x, end.x))
        val y2 = by(max(start.y, end.y))
        return android.graphics.Rect(x1, y1, x2, y2)
    }

    private fun fitScale(iw: Int, ih: Int, cw: Float, ch: Float): Float {
        if (iw <= 0 || ih <= 0 || cw <= 0f || ch <= 0f) return 0f
        return min(cw / iw, ch / ih)
    }

    private fun openSaveDialog() {
        if (saving || !selectionValid() || bitmap == null) return
        if (fileName.isBlank()) fileName = TaskManager.suggestImageName(this, taskName)
        showSaveDialog = true
    }

    private fun selectionValid(): Boolean = selection?.let { it.width() >= MIN_SIZE && it.height() >= MIN_SIZE } == true

    /** 裁剪选区并写入任务 images/ 目录 */
    private fun saveSelection() {
        if (saving) return
        val bmp = bitmap ?: return
        val selected = selection ?: return
        val sel = android.graphics.Rect(selected)
        val w = sel.width()
        val h = sel.height()
        if (w < MIN_SIZE || h < MIN_SIZE) {
            Toast.makeText(this, "选区太小（至少 ${MIN_SIZE}x$MIN_SIZE）", Toast.LENGTH_SHORT).show()
            return
        }
        val requestedName = fileName
        val name = if (requestedName.endsWith(".png", true)) requestedName else "$requestedName.png"
        val app = applicationContext
        saving = true
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val cropped = Bitmap.createBitmap(bmp, sel.left, sel.top, w, h)
                    val target = File(TaskManager.imagesDir(app, taskName), name)
                    FileOutputStream(target).use { out ->
                        cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    true
                } catch (_: Exception) {
                    false
                }
            }
            saving = false
            Toast.makeText(
                this@TemplateCaptureActivity,
                if (ok) "已保存 $name (${w}x$h)" else "保存失败",
                Toast.LENGTH_LONG
            ).show()
            if (ok || backRequestedWhileSaving) finish()
        }
    }

    companion object {
        const val EXTRA_TASK = "task_name"

        /** 最小可用模板尺寸（过小的选区无匹配意义） */
        private const val MIN_SIZE = 8
    }
}
