package com.forest.android.assistant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModelProvider
import com.forest.android.assistant.core.speed.*
import com.forest.android.assistant.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppSpeedActivity : ComponentActivity() {
    private lateinit var model: SpeedViewModel
    private val uninstall = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { model.refresh() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[SpeedViewModel::class.java]
        openInstallerConfirmation(intent)
        setContent { ScriptTheme { SpeedPage() } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); openInstallerConfirmation(intent) }
    private fun openInstallerConfirmation(intent: Intent) {
        @Suppress("DEPRECATION")
        val confirmation = intent.getParcelableExtra<Intent>("installerConfirmation") ?: return
        intent.removeExtra("installerConfirmation")
        runCatching { startActivity(confirmation) }.onFailure { model.error("无法打开系统安装确认：${it.message}") }
    }
    override fun onResume() { super.onResume(); if (::model.isInitialized) model.refresh() }

    private fun launchTarget(id: String, overlay: Boolean) {
        runCatching {
            val launch = packageManager.getLaunchIntentForPackage(id) ?: error("应用尚未安装或没有启动入口")
            if (overlay) {
                if (!Settings.canDrawOverlays(this)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    model.error("开启悬浮窗权限后，再点悬浮调速")
                    return
                }
                SpeedOverlayService.show(this, id)
            }
            startActivity(launch)
        }.onFailure { model.error(it.message ?: "无法启动应用") }
    }
    private fun install(original: Boolean) {
        if (!packageManager.canRequestPackageInstalls()) {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                .onFailure { model.error("请在系统设置中允许雕雕助手安装应用") }
        } else model.install(original)
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable private fun SpeedPage() {
        val state by model.state.collectAsState()
        val installChange by SpeedInstaller.changes.collectAsState()
        var query by rememberSaveable { mutableStateOf("") }
        var confirmUninstall by remember { mutableStateOf(false) }
        val app = state.selected
        LaunchedEffect(installChange) { model.refresh() }
        LaunchedEffect(state.apps) {
            val requested = intent.getStringExtra("speedPackage")
            if (requested != null && app == null) state.apps.firstOrNull { it.packageName == requested }?.let {
                model.select(it); intent.removeExtra("speedPackage")
            }
        }
        BackHandler(enabled = app != null || state.busy) { if (!state.busy) model.select(null) }
        Scaffold(topBar = {
            Surface {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(enabled = !state.busy, onClick = { if (app == null) finish() else model.select(null) }) { AppIcon(R.drawable.ic_back, "返回") }
                    Text(if (app == null) "应用变速" else app.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (app == null) TextButton(onClick = model::refresh, enabled = !state.loading) { Text("刷新") }
                }
            }
        }) { padding ->
            if (app == null) {
                Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp)) {
                    Text("选择应用，生成免 Root 的变速安装包。", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索应用名称或包名") })
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                    if (state.error.isNotEmpty()) Text(state.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 12.dp))
                    val filtered = remember(state.apps, query) { state.apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) } }
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!state.loading && filtered.isEmpty()) item { Text("没有找到应用，请尝试其他名称或包名。", Modifier.padding(16.dp)) }
                        items(filtered, key = { it.packageName }) { entry ->
                            WorkCard(Modifier.fillMaxWidth().clickable { model.select(entry) }) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    AppIconImage(entry)
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                        Text(entry.label, style = MaterialTheme.typography.titleSmall)
                                        Text(entry.packageName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(when { entry.patched -> "变速版已安装"; entry.prepared -> "安装包已准备"; entry.system -> "系统应用 · 无法替换安装"; else -> entry.version }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    AppIcon(R.drawable.ic_arrow)
                                }
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(app.packageName + " · " + app.version, style = MaterialTheme.typography.bodySmall)
                    Text("变速会影响兼容的界面动画和本地运行节奏，实际效果因应用而异。", style = MaterialTheme.typography.bodyMedium)
                    SectionLabel("选择倍率")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(.25f, .5f, 1f, 1.5f, 2f, 3f, 5f, 10f).forEach { value ->
                            FilterChip(selected = state.factor == value, onClick = { model.factor(value) }, enabled = !state.busy,
                                label = { Text(if (value == 1f) "1× 正常" else "${formatFactor(value)}×") })
                        }
                    }
                    WorkCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                Text("同时加速本地运行节奏", style = MaterialTheme.typography.titleSmall)
                                Text("应用出现计时或运行异常时，可关闭后重试。", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(state.nativeEnabled, model::nativeEnabled, enabled = !state.busy)
                        }
                    }
                    if (state.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(state.message, style = MaterialTheme.typography.bodySmall)
                    } else if (state.message.isNotEmpty()) Text(state.message, style = MaterialTheme.typography.bodySmall)
                    if (state.error.isNotEmpty()) Text(state.error, color = MaterialTheme.colorScheme.error)
                    val installMessage = SpeedInstaller.lastMessage(this@AppSpeedActivity, app.packageName)
                    if (installMessage.isNotEmpty()) Text(installMessage, style = MaterialTheme.typography.bodySmall)
                    state.status?.let { status ->
                        WorkCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(if (status.loaded && status.javaReady) "变速模块已连接" else "变速模块尚未就绪", style = MaterialTheme.typography.titleSmall)
                                Text("当前设置 ${formatFactor(status.factor)}× · ${if (status.nativeEnabled) "本地计时已开启" else "仅界面动画"}", style = MaterialTheme.typography.bodySmall)
                                Text(if (status.nativeCalls > 0) "已检测到兼容的本地计时调用" else "本地计时兼容性尚未确认，请在应用中观察效果", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (app.patched) {
                        PrimaryAction("应用倍率设置", !state.busy) { model.control() }
                        OutlinedButton(onClick = { model.control(readOnly = true) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("读取模块状态") }
                        OutlinedButton(onClick = { launchTarget(app.packageName, true) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("悬浮调速并打开应用") }
                        TextButton(onClick = { launchTarget(app.packageName, false) }, enabled = !state.busy) { Text("打开应用") }
                    }
                    if (!app.system && app.installed) {
                        PrimaryAction(if (app.prepared) "重新生成变速安装包" else "生成变速安装包", !state.busy) { model.prepare() }
                    } else if (app.system) Text("系统应用无法通过补丁重装，请返回选择自行安装的应用。", color = MaterialTheme.colorScheme.error)
                    if (app.prepared) {
                        SectionLabel("安装与恢复")
                        Text("重新签名的安装包通常需要先卸载当前版本。卸载会清除该应用的本地数据，请先使用应用自身的备份功能。这里只备份安装包，不包含账号和应用数据。", style = MaterialTheme.typography.bodySmall)
                        if (app.installed) OutlinedButton(onClick = { confirmUninstall = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("卸载当前版本…") }
                        OutlinedButton(onClick = { install(false) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("安装变速版") }
                        OutlinedButton(onClick = { install(true) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("安装备份的原版") }
                    }
                    Spacer(Modifier.height(18.dp))
                }
            }
        }
        if (confirmUninstall && app != null) AlertDialog(onDismissRequest = { confirmUninstall = false },
            title = { Text("卸载${app.label}？") },
            text = { Text("卸载会清除目标应用的本地数据。已生成的变速安装包和原版安装包会保留在雕雕助手中。请确认已备份需要的数据。") },
            confirmButton = { TextButton(onClick = {
                confirmUninstall = false
                runCatching { uninstall.launch(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))) }
                    .onFailure { model.error("无法打开卸载页面：${it.message}") }
            }) { Text("打开系统卸载确认") } }, dismissButton = { TextButton(onClick = { confirmUninstall = false }) { Text("取消") } })
    }
    @Composable private fun AppIconImage(app: SpeedApp) {
        val bitmap by produceState<android.graphics.Bitmap?>(null, app.packageName, app.installed) {
            value = withContext(Dispatchers.IO) { runCatching { packageManager.getApplicationIcon(app.packageName).toBitmap(48, 48) }.getOrNull() }
        }
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.size(42.dp)) }
            ?: AppIcon(R.drawable.ic_code, modifier = Modifier.size(42.dp))
    }
    private fun formatFactor(value: Float) = if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()
}
