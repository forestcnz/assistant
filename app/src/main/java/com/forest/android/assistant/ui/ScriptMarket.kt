package com.forest.android.assistant.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.forest.android.assistant.BuildConfig
import com.forest.android.assistant.R
import com.forest.android.assistant.core.market.MARKET_REPO
import com.forest.android.assistant.core.market.MarketRelease
import com.forest.android.assistant.core.market.MarketRepository
import com.forest.android.assistant.core.market.MarketSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ScriptMarket(taskNames: List<String>, onBack: () -> Unit, onOpenTask: (String) -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { MarketRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf<List<MarketRelease>?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var installed by remember { mutableStateOf<Map<MarketSource, String>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmImport by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refresh) {
        loading = true
        loadError = null
        try {
            catalog = repository.load()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            loadError = error.localizedMessage ?: "请检查网络后重试"
        } finally {
            loading = false
        }
    }
    LaunchedEffect(taskNames) { installed = repository.installedTasks(taskNames) }

    val release = catalog.orEmpty().firstOrNull { it.source.scriptId == selectedId }
    if (release == null) {
        val visible = remember(catalog, query, filter) {
            catalog.orEmpty().filter { item ->
                (filter == 0 || (filter == 1) == (item.targetApp != null)) &&
                    listOfNotNull(item.title, item.description, item.targetApp, item.targetPackage, item.author)
                        .any { it.contains(query.trim(), ignoreCase = true) }
            }
        }
        DetailPage("脚本市场", onBack = onBack, actions = {
            TextButton(enabled = !loading, onClick = { refresh++ }) { Text("刷新") }
        }) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text("搜索脚本、应用或作者") }, modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全部", "按应用", "通用").forEachIndexed { index, label ->
                    FilterChip(selected = filter == index, onClick = { filter = index }, label = { Text(label) })
                }
            }
            loadError?.let {
                Text("市场加载失败。$it", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { refresh++ }) { Text("重试") }
            }
            if (loading && catalog == null) CircularProgressIndicator()
            if (catalog != null && visible.isEmpty()) {
                Text(if (catalog.isNullOrEmpty()) "暂无上架脚本" else "没有匹配的脚本")
            }
            visible.forEach { item ->
                val status = when {
                    installed?.containsKey(item.source) == true -> "已导入"
                    BuildConfig.VERSION_CODE < item.minAppVersionCode -> "需更新助手"
                    else -> null
                }
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                    DesignRow(
                        item.title, "${item.description}\n${item.targetApp ?: "通用"} · ${item.author}",
                        R.drawable.ic_code, trailing = status?.let { label -> { StatusBadge(label) } }
                    ) {
                        selectedId = item.source.scriptId
                    }
                }
            }
            TextButton(onClick = { openMarketLink(context, "$MARKET_REPO#投稿") }) { Text("投稿说明") }
        }
        return
    }

    val localTask = installed?.get(release.source)
    val compatible = BuildConfig.VERSION_CODE >= release.minAppVersionCode
    val button = when {
        importing -> "正在导入…"
        localTask != null -> "打开本地任务"
        !compatible -> "请先更新雕雕助手"
        else -> "导入到任务"
    }
    val feedback = "$MARKET_REPO/issues/new".toUri().buildUpon()
        .appendQueryParameter("title", "[${release.title}] ")
        .appendQueryParameter("body", "请描述起始界面、操作步骤和实际结果。")
        .build().toString()

    DetailPage(
        title = release.title,
        subtitle = release.author,
        onBack = {
            if (!importing) {
                selectedId = null
                confirmImport = false
                importError = null
            }
        },
        footer = {
            PrimaryAction(
                button,
                enabled = !importing && installed != null && (localTask != null || (compatible && loadError == null))
            ) {
                if (localTask != null) onOpenTask(localTask)
                else {
                    name = availableMarketName(release.title, taskNames)
                    importError = null
                    confirmImport = true
                }
            }
        }
    ) {
        Text(release.description, style = MaterialTheme.typography.bodyLarge)
        MarketSection("使用步骤", release.usage)
        MarketSection("适配环境", "${release.targetApp ?: "通用"}\n${release.environment}\n最低助手版本号 ${release.minAppVersionCode}")
        MarketSection("脚本能力", release.capabilities.joinToString("、").ifEmpty { "无需额外权限" })
        MarketSection("更新说明", "${release.updatedAt}\n${release.changes}")
        Row {
            TextButton(onClick = { openMarketLink(context, release.sourceUrl) }) { Text("查看源码和素材") }
            TextButton(onClick = { openMarketLink(context, feedback) }) { Text("反馈问题") }
        }
        loadError?.let { Text("市场刷新失败。$it", color = MaterialTheme.colorScheme.error) }
    }

    if (confirmImport) {
        val normalized = name.trim()
        val validName = normalized.isNotEmpty() && normalized.length <= 50 && normalized !in taskNames &&
            normalized !in listOf(".", "..") && !normalized.startsWith(".deleted-") &&
            normalized.none { it == '/' || it == '\\' || it.isISOControl() }
        AlertDialog(
            onDismissRequest = { if (!importing) confirmImport = false },
            title = { Text("导入到任务") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${(release.sizeBytes + 1023) / 1024} KB")
                    OutlinedTextField(
                        value = name, onValueChange = { name = it; importError = null }, singleLine = true,
                        label = { Text("本地任务名称") }, enabled = !importing,
                        isError = normalized.isNotEmpty() && !validName,
                        supportingText = {
                            if (normalized in taskNames) Text("该任务名称已存在")
                            else if (normalized.isNotEmpty() && !validName) Text("请输入合法任务名，最多 50 字")
                        }
                    )
                    importError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = validName && !importing && compatible, onClick = {
                    importing = true
                    importError = null
                    scope.launch {
                        try {
                            val taskName = repository.importTask(release, normalized)
                            confirmImport = false
                            onOpenTask(taskName)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            importError = error.localizedMessage ?: "导入失败，请重试"
                        } finally {
                            importing = false
                        }
                    }
                }) { Text(if (importing) "正在下载和校验…" else if (importError != null) "重试" else "导入") }
            },
            dismissButton = { TextButton(enabled = !importing, onClick = { confirmImport = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun MarketSection(title: String, body: String) {
    SectionLabel(title)
    SelectionContainer { Text(body, Modifier.padding(horizontal = 3.dp), style = MaterialTheme.typography.bodyMedium) }
}

private fun availableMarketName(base: String, taken: List<String>): String {
    val clean = base.replace('/', '_').replace('\\', '_').filterNot { it.isISOControl() }.trim().take(50)
    val initial = clean.takeUnless { it.isBlank() || it == "." || it == ".." || it.startsWith(".deleted-") } ?: "市场脚本"
    if (initial !in taken) return initial
    return generateSequence(2) { it + 1 }.map {
        val suffix = " ($it)"
        initial.take(50 - suffix.length) + suffix
    }.first { it !in taken }
}

private fun openMarketLink(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "没有可打开链接的浏览器", Toast.LENGTH_SHORT).show()
    }
}
