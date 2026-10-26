package com.forest.android.assistant

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.forest.android.assistant.core.image.AssetCropper
import com.forest.android.assistant.core.image.CropImageFit
import com.forest.android.assistant.core.image.CropRegion
import com.forest.android.assistant.ui.AppIcon
import com.forest.android.assistant.ui.ScriptTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** 只编辑任务已有图片，不依赖截屏授权。 */
class AssetCropActivity : ComponentActivity() {
    private lateinit var model: AssetCropModel
    private val taskName get() = intent.getStringExtra(EXTRA_TASK).orEmpty()
    private val imageName get() = intent.getStringExtra(EXTRA_IMAGE).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[AssetCropModel::class.java]
        if (model.preview == null) {
            savedInstanceState?.getIntArray("crop_region")?.takeIf { it.size == 4 }?.let {
                model.region = CropRegion(it[0], it[1], it[2], it[3])
            }
        }
        model.load(taskName, imageName)
        setContent {
            ScriptTheme {
                BackHandler(enabled = model.saving) { }
                LaunchedEffect(model.savedFile) {
                    model.savedFile?.let { file ->
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_TASK, taskName).putExtra(EXTRA_IMAGE, file.name))
                        finish()
                    }
                }
                CropScreen()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        model.region?.let { outState.putIntArray("crop_region", intArrayOf(it.left, it.top, it.right, it.bottom)) }
        super.onSaveInstanceState(outState)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun CropScreen() {
        var exactDialog by rememberSaveable { mutableStateOf(false) }
        val preview = model.preview
        val region = model.region
        val valid = preview != null && region?.isValid(preview.width, preview.height) == true
        Scaffold(topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("裁剪图片", style = MaterialTheme.typography.titleMedium)
                        Text(imageName, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { finish() }, enabled = !model.saving) { AppIcon(R.drawable.ic_back, "返回") }
                },
                actions = {
                    TextButton(enabled = preview != null && !model.saving, onClick = { model.region = null }) { Text("重选") }
                }
            )
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF192234)), contentAlignment = Alignment.Center) {
                    if (preview == null) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (model.error == null) CircularProgressIndicator()
                            Text(model.error ?: "正在读取图片…", color = Color.White, modifier = Modifier.padding(top = 12.dp))
                            if (model.error != null) TextButton(onClick = { model.load(taskName, imageName) }) { Text("重试") }
                        }
                    } else {
                        CropCanvas(preview, model.region, !model.saving) { model.region = it }
                    }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (preview != null) {
                        Text("原图 ${preview.width} × ${preview.height} px", style = MaterialTheme.typography.bodySmall)
                        Text(if (valid) "选区 ${region!!.width} × ${region.height} px · 起点 (${region.left}, ${region.top})"
                            else "拖动框选要保留的区域", style = MaterialTheme.typography.bodyMedium)
                        Text("拖动选区可移动，拖动四角可调整大小", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("保存后替换当前素材，原图移入历史素材", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        model.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall) }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(enabled = !model.saving, onClick = {
                                model.region = CropRegion(0, 0, preview.width, preview.height)
                            }) { Text("全选") }
                            TextButton(enabled = valid && !model.saving, onClick = { exactDialog = true }) { Text("精确调整") }
                            Spacer(Modifier.weight(1f))
                            Button(enabled = valid && !model.saving, onClick = {
                                model.save(taskName, imageName, region!!)
                            }) { Text(if (model.saving) "保存中…" else "保存裁剪") }
                        }
                    }
                }
            }
        }
        if (exactDialog && preview != null && region != null) {
            ExactRegionDialog(region, preview.width, preview.height,
                onDismiss = { exactDialog = false },
                onApply = { model.region = it; exactDialog = false })
        }
    }

    companion object {
        const val EXTRA_TASK = "task_name"
        const val EXTRA_IMAGE = "image_name"
    }
}

/** 旋转时保留预览、选区和正在执行的保存，避免重复落盘。 */
class AssetCropModel(application: Application) : AndroidViewModel(application) {
    var preview by mutableStateOf<AssetCropper.Preview?>(null)
        private set
    var region by mutableStateOf<CropRegion?>(null)
    var error by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)
        private set
    var savedFile by mutableStateOf<File?>(null)
        private set
    private var loading = false

    fun load(task: String, image: String) {
        if (loading || preview != null) return
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    AssetCropper.load(getApplication(), task, image)
                }
                preview = result
                if (region?.isValid(result.width, result.height) == false) region = null
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                error = failure.message ?: "图片读取失败"
            } catch (_: OutOfMemoryError) {
                error = "图片过大，内存不足"
            } finally {
                loading = false
            }
        }
    }

    fun save(task: String, image: String, region: CropRegion) {
        if (saving || savedFile != null) return
        saving = true
        error = null
        viewModelScope.launch {
            try {
                savedFile = withContext(Dispatchers.IO + NonCancellable) {
                    AssetCropper.save(getApplication(), task, image, region)
                }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                error = failure.message ?: "保存失败，请重试"
            } catch (_: OutOfMemoryError) {
                error = "内存不足，请缩小裁剪区域后重试"
            } finally {
                saving = false
            }
        }
    }
}

@Composable
private fun CropCanvas(preview: AssetCropper.Preview, region: CropRegion?, enabled: Boolean, onChange: (CropRegion?) -> Unit) {
    val currentRegion by rememberUpdatedState(region)
    val updateRegion by rememberUpdatedState(onChange)
    Image(preview.bitmap.asImageBitmap(), "待裁剪图片", contentScale = ContentScale.FillBounds,
        modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.Center).aspectRatio(preview.width.toFloat() / preview.height))
    Canvas(Modifier.fillMaxSize().pointerInput(preview, enabled) {
        if (!enabled) return@pointerInput
        val fit = CropImageFit(preview.width, preview.height, size.width.toFloat(), size.height.toFloat())
        var start = Offset.Zero
        var initial: CropRegion? = null
        var corner = -1
        var moving = false
        var active = false
        var anchorX = 0
        var anchorY = 0
        detectDragGestures(
            onDragStart = { point ->
                start = point
                initial = currentRegion
                corner = -1
                moving = false
                val selected = initial
                if (selected != null && selected.width > 0 && selected.height > 0) {
                    val corners = listOf(selected.left to selected.top, selected.right to selected.top,
                        selected.left to selected.bottom, selected.right to selected.bottom)
                    corner = corners.indices.minByOrNull { i ->
                        val (x, y) = corners[i]
                        (Offset(fit.left + x * fit.scale, fit.top + y * fit.scale) - point).getDistance()
                    }?.takeIf { i ->
                        val (x, y) = corners[i]
                        (Offset(fit.left + x * fit.scale, fit.top + y * fit.scale) - point).getDistance() <= 24.dp.toPx()
                    } ?: -1
                    moving = corner < 0 && fit.contains(point.x, point.y) &&
                        fit.imageX(point.x) in selected.left..selected.right && fit.imageY(point.y) in selected.top..selected.bottom
                }
                active = corner >= 0 || fit.contains(point.x, point.y)
                if (active && !moving) {
                    anchorX = if (corner >= 0) { if (corner % 2 == 0) selected!!.right else selected!!.left } else fit.imageX(point.x)
                    anchorY = if (corner >= 0) { if (corner < 2) selected!!.bottom else selected!!.top } else fit.imageY(point.y)
                    if (corner < 0) updateRegion(CropRegion(anchorX, anchorY, anchorX, anchorY))
                }
            },
            onDrag = { change, _ ->
                if (active) {
                    change.consume()
                    updateRegion(if (moving) initial!!.moved(
                        ((change.position.x - start.x) / fit.scale).roundToInt(),
                        ((change.position.y - start.y) / fit.scale).roundToInt(), preview.width, preview.height)
                    else CropRegion.between(anchorX, anchorY, fit.imageX(change.position.x), fit.imageY(change.position.y)))
                }
            },
            onDragEnd = { active = false },
            onDragCancel = { if (active) updateRegion(initial); active = false }
        )
    }) {
        val selected = region ?: return@Canvas
        val fit = CropImageFit(preview.width, preview.height, size.width, size.height)
        val left = fit.left + selected.left * fit.scale
        val top = fit.top + selected.top * fit.scale
        val right = fit.left + selected.right * fit.scale
        val bottom = fit.top + selected.bottom * fit.scale
        val dim = Color(0x99000000)
        drawRect(dim, Offset.Zero, Size(size.width, top))
        drawRect(dim, Offset(0f, bottom), Size(size.width, size.height - bottom))
        drawRect(dim, Offset(0f, top), Size(left, bottom - top))
        drawRect(dim, Offset(right, top), Size(size.width - right, bottom - top))
        drawRect(Color(0xFFADC6FF), Offset(left, top), Size(right - left, bottom - top), style = Stroke(2.dp.toPx()))
        for (step in 1..2) {
            val x = left + (right - left) * step / 3f
            val y = top + (bottom - top) * step / 3f
            drawLine(Color.White.copy(alpha = 0.4f), Offset(x, top), Offset(x, bottom), 1.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.4f), Offset(left, y), Offset(right, y), 1.dp.toPx())
        }
        listOf(Offset(left, top), Offset(right, top), Offset(left, bottom), Offset(right, bottom)).forEach {
            drawCircle(Color(0xFFADC6FF), 6.dp.toPx(), it)
            drawCircle(Color.White, 6.dp.toPx(), it, style = Stroke(1.dp.toPx()))
        }
    }
}

@Composable
private fun ExactRegionDialog(region: CropRegion, imageWidth: Int, imageHeight: Int, onDismiss: () -> Unit, onApply: (CropRegion) -> Unit) {
    var x by rememberSaveable { mutableStateOf(region.left.toString()) }
    var y by rememberSaveable { mutableStateOf(region.top.toString()) }
    var width by rememberSaveable { mutableStateOf(region.width.toString()) }
    var height by rememberSaveable { mutableStateOf(region.height.toString()) }
    val numbers = listOf(x, y, width, height).map { it.toIntOrNull() }
    val parsed = if (numbers.all { it != null && it in 0..maxOf(imageWidth, imageHeight) })
        CropRegion(numbers[0]!!, numbers[1]!!, numbers[0]!! + numbers[2]!!, numbers[1]!! + numbers[3]!!) else null
    val valid = parsed?.isValid(imageWidth, imageHeight) == true
    AlertDialog(onDismissRequest = onDismiss, title = { Text("精确调整选区") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("以原图像素为单位", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelField("X", x, { x = it }, Modifier.weight(1f))
                PixelField("Y", y, { y = it }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelField("宽度", width, { width = it }, Modifier.weight(1f))
                PixelField("高度", height, { height = it }, Modifier.weight(1f))
            }
            if (!valid) Text("选区需在原图范围内，宽高至少为 1 px", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextButton(enabled = valid, onClick = { onApply(parsed!!) }) { Text("应用") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun PixelField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = modifier)
}
