package com.forest.android.assistant

import android.graphics.Bitmap
import android.os.Bundle
import com.forest.android.assistant.ui.AppToast as Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.navigationBarsPadding
import com.forest.android.assistant.ui.PrimaryAction
import com.forest.android.assistant.ui.AppBackButton
import androidx.compose.material3.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.forest.android.assistant.core.image.ScreenCapture
import com.forest.android.assistant.core.task.TaskManager
import com.forest.android.assistant.ui.ScriptTheme
import com.forest.android.assistant.ui.AppPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import com.forest.android.assistant.ui.*
import com.forest.android.assistant.core.image.AssetCropper
import com.forest.android.assistant.core.image.CropRegion
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * 模板截取工具：截取当前屏幕 → 拖拽框选区域 → 裁剪并保存为任务素材（images/）。
 * 与设计 docs/README.md 第 4.5 节「模板截取工具」一致，是找图素材的一键制作入口。
 */
class TemplateCaptureActivity : ComponentActivity() {

    private val model by lazy { ViewModelProvider(this)[TemplateCaptureModel::class.java] }
    private val bitmap get() = model.bitmap
    private val error get() = model.error
    private var selection: android.graphics.Rect?   // 位图坐标
        get() = model.selection
        set(value) { model.selection = value }
    private var showSaveDialog by mutableStateOf(false)
    private var fileName: String
        get() = model.fileName
        set(value) { model.fileName = value }
    private val saving get() = model.saving
    private var showOverwrite by mutableStateOf(false)

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
                if (!saving) finish()
            }
        })
        ScreenCapture.bind(this) { intent -> projectionLauncher.launch(intent) }
        setContent {
            ScriptTheme {
                LaunchedEffect(model.savedFile) {
                    model.savedFile?.let { file ->
                        setResult(RESULT_OK)
                        Toast.makeText(this@TemplateCaptureActivity, "已保存 ${file.name}", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
                CaptureScreen()
            }
        }
        if (bitmap == null && !model.capturing) captureFrame()
    }

    override fun onDestroy() {
        ScreenCapture.unbind(this)
        super.onDestroy()
    }

    /** 取一帧截图：未授权时先申请（会弹系统授权窗） */
    private fun captureFrame() {
        model.capture()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun CaptureScreen() {
        var exact by rememberSaveable { mutableStateOf(false) }
        val preview = bitmap?.let { AssetCropper.Preview(it, it.width, it.height) }
        val region = selection?.let { CropRegion(it.left, it.top, it.right, it.bottom) }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.navigationBarsPadding().padding(horizontal = 18.dp)) {
                        PrimaryAction(if (saving) "保存中…" else "保存到任务素材", enabled = selectionValid() && !saving) { openSaveDialog() }
                    }
                }
            },
            topBar = {
                TopAppBar(
                    colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                    navigationIcon = {
                        AppBackButton(onClick = { if (!saving) finish() }, enabled = !saving)
                    },
                    title = {
                        Column {
                            Text("截取模板", style = MaterialTheme.typography.titleMedium)
                            Text(taskName, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {
                        TextButton(onClick = { captureFrame() }, enabled = !saving && !model.capturing) { Text("重截") }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding).verticalScroll(rememberScrollState()).padding(18.dp)
            ) {
                InfoNotice("在截图上框选按钮或文字，保存后即可用于找图。")
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp).clip(MaterialTheme.shapes.medium)
                        .checkerboardBackground(),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = bitmap
                    if (bmp == null) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (error == null) {
                                CircularProgressIndicator(color = Color(AppPalette.INVERSE_ACCENT))
                                Spacer(Modifier.height(16.dp))
                            }
                             Text(error ?: "正在获取屏幕截图…", color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                         // 框选层：拖动定义选区（位图坐标）
                         // 选区外压暗
                         // 选区边框
                         // 共用裁剪画布同时支持选区移动与四角调整。
                         CropCanvas(preview!!, region, !saving) { selected ->
                             selection = selected?.let { android.graphics.Rect(it.left, it.top, it.right, it.bottom) }
                         }
                    }
                }

                // 选区尺寸与坐标信息卡
                SectionLabel("当前选区")
                CropValues(region)
                TextButton(onClick = { exact = true }, enabled = preview != null && region != null && !saving) { Text("精确调整") }
                if (region != null && !selectionValid()) InfoNotice("选区至少需要 $MIN_SIZE × $MIN_SIZE px", warning = true)
                model.saveError?.let { InfoNotice(it, error = true) }
            }
        }

        if (exact && preview != null && region != null) ExactRegionPage(region, preview, MIN_SIZE,
            onDismiss = { exact = false }, onApply = { selection = android.graphics.Rect(it.left, it.top, it.right, it.bottom); exact = false })
        if (showSaveDialog) {
            val checkedName = runCatching { AssetCropper.templateName(fileName) }
            ConfirmActionSheet("保存模板", "保存到任务「$taskName」的 images/ 目录。", "保存模板",
                onDismiss = { showSaveDialog = false }, busy = saving, enabled = checkedName.isSuccess,
                onConfirm = {
                    checkedName.getOrNull()?.let { name ->
                        runCatching { AssetCropper.templateTarget(this, taskName, name).exists() }
                            .onSuccess { exists -> if (exists) showOverwrite = true else saveSelection() }
                            .onFailure { model.saveError = it.message }
                    }
                }) {
                DesignField("文件名", fileName, { fileName = it }, "自动保存为 PNG 格式。",
                    error = if (fileName.isNotEmpty()) checkedName.exceptionOrNull()?.message else "请输入文件名")
                model.saveError?.let { InfoNotice(it, error = true) }
            }
        }
        if (showOverwrite) ConfirmActionSheet("覆盖同名素材？", "「${AssetCropper.templateName(fileName)}」已存在。旧图片将保留在历史素材中。",
            "覆盖并保存", onDismiss = { showOverwrite = false }, busy = saving,
            onConfirm = { showOverwrite = false; saveSelection(overwrite = true) })
    }

    private fun openSaveDialog() {
        if (saving || !selectionValid() || bitmap == null) return
        if (fileName.isBlank()) fileName = TaskManager.suggestImageName(this, taskName)
        showSaveDialog = true
    }

    private fun selectionValid(): Boolean = selection?.let { it.width() >= MIN_SIZE && it.height() >= MIN_SIZE } == true

    /** 裁剪选区并写入任务 images/ 目录 */
    private fun saveSelection(overwrite: Boolean = false) {
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
        model.save(taskName, CropRegion(sel.left, sel.top, sel.right, sel.bottom), overwrite)
    }

    companion object {
        const val EXTRA_TASK = "task_name"

        /** 最小可用模板尺寸（过小的选区无匹配意义） */
        private const val MIN_SIZE = 8
    }
}

/** 截图和保存跟随 ViewModel，旋转后继续显示同一帧及选区，避免重复写入。 */
class TemplateCaptureModel(application: android.app.Application) : AndroidViewModel(application) {
    var bitmap by mutableStateOf<Bitmap?>(null)
    var error by mutableStateOf<String?>(null)
    var selection by mutableStateOf<android.graphics.Rect?>(null)
    var fileName by mutableStateOf("")
    var saving by mutableStateOf(false)
    var capturing by mutableStateOf(false)
    var saveError by mutableStateOf<String?>(null)
    var savedFile by mutableStateOf<File?>(null)

    fun capture() {
        if (capturing || saving) return
        capturing = true; error = null; bitmap = null; selection = null
        viewModelScope.launch {
            try {
                bitmap = withContext(Dispatchers.IO) {
                    if (!ScreenCapture.captureAvailable(getApplication()) && !ScreenCapture.requestBlocking()) null else ScreenCapture.capture()
                }
                if (bitmap == null) error = "截屏不可用：请先选择截屏方式，并完成所需授权"
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                error = failure.message ?: "截屏失败，请重试"
            } finally { capturing = false }
        }
    }

    fun save(task: String, region: CropRegion, overwrite: Boolean) {
        val frame = bitmap ?: return
        if (saving || savedFile != null) return
        val name = fileName
        saving = true; saveError = null
        viewModelScope.launch {
            try {
                savedFile = withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                    AssetCropper.saveTemplate(getApplication(), task, name, frame, region, overwrite)
                }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                saveError = failure.message ?: "保存失败，请重试"
            } catch (_: OutOfMemoryError) { saveError = "内存不足，请缩小选区后重试" }
            finally { saving = false }
        }
    }
}
