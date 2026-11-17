package com.forest.android.assistant.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import com.forest.android.assistant.ui.AppToast as Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.forest.android.assistant.ui.AppRoundedCornerShape as RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.forest.android.assistant.BuildConfig
import com.forest.android.assistant.R
import com.forest.android.assistant.core.market.MARKET_REPO
import com.forest.android.assistant.core.market.MarketRelease
import com.forest.android.assistant.core.market.MarketRepository
import com.forest.android.assistant.core.market.MarketSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope

/** 导入任务与进度跟随页面宿主，旋转设备不会中断文件导入或丢失结果。 */
internal class MarketImportSession : ViewModel() {
    val importing = mutableStateOf(false)
    val installed = mutableStateOf<Map<MarketSource, String>?>(null)
    val batchMode = mutableStateOf(false)
    val plan = mutableStateOf<List<MarketBatchItem>?>(null)
    val progress = mutableStateOf<MarketBatchProgress?>(null)
    val results = mutableStateOf<List<MarketBatchResult>?>(null)
}

@Composable
internal fun ScriptMarket(
    taskNames: List<String>, onBack: () -> Unit, onImported: (String) -> Unit, onOpenTask: (String) -> Unit,
    onUpdate: () -> Unit = {}
) {
    val context = LocalContext.current
    val repository = remember(context) { MarketRepository(context.applicationContext) }
    val owner = requireNotNull(androidx.activity.compose.LocalActivity.current) as androidx.lifecycle.ViewModelStoreOwner
    val session = remember(owner) { ViewModelProvider(owner)[MarketImportSession::class.java] }
    val scope = session.viewModelScope
    var catalog by remember { mutableStateOf<List<MarketRelease>?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var installed by session.installed
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmImport by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var importing by session.importing
    var importError by remember { mutableStateOf<String?>(null) }
    // 多选按脚本 ID 保存，搜索和筛选切换时保留已选项目。
    var batchMode by session.batchMode
    var batchIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var batchPlan by session.plan
    var batchProgress by session.progress
    var batchResults by session.results
    val progressHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    fun prepareBatch(releases: List<MarketRelease>) {
        // 同批名称立即预留；与其他本地任务重名时沿用单项导入的自动命名规则。
        val taken = (taskNames + installed.orEmpty().values).distinct().toMutableList()
        batchPlan = releases.map { item ->
            val local = installed?.get(item.source)
            val target = local ?: availableMarketName(item.title, taken)
            if (target !in taken) taken.add(target)
            MarketBatchItem(item, target, overwrite = local != null)
        }
    }

    fun startImport(plan: List<MarketBatchItem>) {
        batchPlan = null; confirmImport = false; importing = true
        batchProgress = MarketBatchProgress(1, plan.size, plan.first().release.title, items = plan)
        scope.launch {
            val results = mutableListOf<MarketBatchResult>()
            try {
                // 逐项复用下载、校验和覆盖检查；某项失败只记录原因，继续处理后续脚本。
                plan.forEachIndexed { index, item ->
                    batchProgress = MarketBatchProgress(index + 1, plan.size, item.release.title, completed = results.toList(), items = plan)
                    val result = try {
                        MarketBatchResult(item, taskName = repository.importTask(item.release, item.taskName, item.overwrite) { current ->
                            progressHandler.post {
                                if (importing && batchProgress?.index == index + 1) batchProgress = batchProgress?.copy(current = current)
                            }
                        })
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { MarketBatchResult(item, error = error.localizedMessage ?: "导入失败，请重试") }
                    results.add(result)
                    result.taskName?.let { taskName ->
                        installed = installed.orEmpty() + (item.release.source to taskName)
                        batchIds = batchIds - item.release.source.scriptId
                    }
                }
                batchResults = results.toList()
                batchMode = results.any { it.taskName == null }
            } finally { importing = false; batchProgress = null }
        }
    }

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
    LaunchedEffect(taskNames, importing) {
        // 导入过程中以成功回调更新状态，避免旧的任务快照覆盖批量导入结果。
        if (!importing) {
            // 最后一项刚完成时，主页面的任务扫描可能尚未刷新，合并本次真实成功项再读取来源。
            val recentlyImported = batchResults.orEmpty().mapNotNull { it.taskName }
            installed = repository.installedTasks((taskNames + recentlyImported).distinct())
        }
    }
    LaunchedEffect(batchResults) {
        batchResults?.filter { it.taskName != null }?.forEach { result ->
            batchIds = batchIds - result.item.release.source.scriptId
            onImported(result.taskName!!)
        }
    }

    batchProgress?.let { MarketBatchProgressDialog(it); return }
    batchResults?.let { results ->
        MarketBatchResultDialog(results,
            onDismiss = { batchResults = null; selectedId = null },
            onRetry = { batchResults = null; startImport(results.filter { it.taskName == null }.map { it.item }) },
            onOpenTask = onOpenTask)
        return
    }
    batchPlan?.let { plan ->
        MarketBatchConfirmDialog(plan, onDismiss = { batchPlan = null }, onConfirm = { startImport(plan) })
        return
    }

    val release = catalog.orEmpty().firstOrNull { it.source.scriptId == selectedId }
    if (release == null) {
        val visible = remember(catalog, query, filter) {
            catalog.orEmpty().filter { item ->
                (filter == 0 || (filter == 1) == (item.targetApp != null)) &&
                    listOfNotNull(item.title, item.description, item.targetApp, item.targetPackage, item.author)
                        .any { it.contains(query.trim(), ignoreCase = true) }
            }
        }
        val selected = catalog.orEmpty().filter {
            it.source.scriptId in batchIds && BuildConfig.VERSION_CODE >= it.minAppVersionCode
        }
        val selectableIds = visible.filter { BuildConfig.VERSION_CODE >= it.minAppVersionCode }.map { it.source.scriptId }
        val allVisibleSelected = selectableIds.isNotEmpty() && selectableIds.all { it in batchIds }
        DetailPage(if (batchMode) "批量导入" else "脚本市场", if (batchMode) "选择需要的社区脚本" else "好用的自动化，值得分享", onBack = {
            if (!importing) {
                if (batchMode) { batchMode = false; batchIds = emptyList() } else onBack()
            }
        }, actions = {
            TextButton(enabled = !importing && (batchMode || (!loading && installed != null && loadError == null && !catalog.isNullOrEmpty())),
                onClick = { batchMode = !batchMode; batchIds = emptyList() }) {
                Text(if (batchMode) "取消多选" else "批量导入")
            }
        }, footer = {
            if (batchMode) PrimaryAction("导入所选（${selected.size}）",
                enabled = selected.isNotEmpty() && installed != null && !loading && !importing && loadError == null) {
                prepareBatch(selected)
            }
        }) {
            // 紧凑搜索框：放大镜 + 无边框输入，贴合设计稿 42px 高度。
            // 搜索框进一步压缩至 34dp，输入文字仍保持垂直居中。
            Row(Modifier.fillMaxWidth().heightIn(min = 34.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(13.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(13.dp))
                .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_search, modifier = Modifier.size(17.dp))
                Box(Modifier.weight(1f).padding(start = 8.dp)) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        enabled = !importing,
                        textStyle = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(Color(AppPalette.PRIMARY)),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                        decorationBox = { inner ->
                            if (query.isEmpty()) Text("搜索脚本、应用或作者", fontSize = 11.sp, lineHeight = 16.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            inner()
                        }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("全部", "按应用", "通用").forEachIndexed { index, label ->
                    // 选中分类使用深蓝灰底白字，与设计稿的筛选胶囊一致。
                    val active = filter == index
                    Box(Modifier.heightIn(min = 30.dp).clip(RoundedCornerShape(50))
                        .background(if (active) Color(AppPalette.INVERSE_SURFACE) else MaterialTheme.colorScheme.surface)
                        .border(1.dp, if (active) Color(AppPalette.INVERSE_SURFACE) else MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(50))
                        .clickable(enabled = !importing) { filter = index }
                        .padding(horizontal = 14.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 11.sp, lineHeight = 16.sp, color = if (active) Color.White else Color(AppPalette.ON_SURFACE_VARIANT))
                    }
                }
            }
            if (batchMode) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("已选 ${selected.size} 个", style = MaterialTheme.typography.titleSmall)
                    TextButton(enabled = selectableIds.isNotEmpty() && !loading && !importing, onClick = {
                        batchIds = if (allVisibleSelected) batchIds - selectableIds.toSet()
                            else (batchIds + selectableIds).distinct()
                    }) { Text(if (allVisibleSelected) "取消全选" else "全选当前列表") }
                }
                Text("可跨搜索和筛选多选；需更新助手的脚本暂不可选。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (loading && catalog == null) {
                repeat(3) { MarketSkeleton() }
            } else if (loadError != null && catalog == null) {
                EmptyState("暂时连接不上市场", "请检查网络，已有任务仍可正常使用。", R.drawable.ic_market)
                InfoNotice(loadError.orEmpty(), error = true)
                PrimaryAction("重新加载") { refresh++ }
            } else if (catalog != null && visible.isEmpty()) {
                EmptyState(if (catalog.isNullOrEmpty()) "暂无上架脚本" else "没有找到相关脚本",
                    "试试其他关键词，或切换分类。", R.drawable.ic_search)
            }
            visible.forEach { item ->
                val checked = item.source.scriptId in batchIds
                val canSelect = BuildConfig.VERSION_CODE >= item.minAppVersionCode && installed != null && !loading
                val status = when {
                    installed?.containsKey(item.source) == true -> "已导入"
                    BuildConfig.VERSION_CODE < item.minAppVersionCode -> "需更新助手"
                    else -> null
                }
                    // 市场卡片：图标 + 名称/作者 + 描述 + 元信息，与设计稿一致。
                    Surface(shape = RoundedCornerShape(18.dp), color = if (batchMode && checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, if (batchMode && checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.fillMaxWidth().clickable(enabled = !importing && (!batchMode || canSelect)) {
                            if (batchMode) batchIds = if (checked) batchIds - item.source.scriptId else batchIds + item.source.scriptId
                            else selectedId = item.source.scriptId
                        }
                            .padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconTile(R.drawable.ic_code, blue = true)
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(item.title, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${item.author} · v${item.version}", fontSize = 9.sp, lineHeight = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 1.dp))
                                }
                                if (batchMode) Checkbox(checked, onCheckedChange = null, enabled = canSelect)
                                else if (status == null) AppIcon(R.drawable.ic_arrow, modifier = Modifier.size(14.dp))
                            }
                            Text(item.description, fontSize = 10.sp, lineHeight = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${item.targetApp ?: "通用"} · 含脚本与素材", fontSize = 9.sp, lineHeight = 12.sp,
                                    color = Color(AppPalette.ON_SURFACE_VARIANT))
                                Spacer(Modifier.weight(1f))
                                status?.let { StatusBadge(it) }
                            }
                        }
                    }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { openMarketLink(context, "$MARKET_REPO#投稿") }) { Text("投稿说明") }
                TextButton(enabled = !loading && !importing, onClick = { refresh++ }) { Text("刷新市场") }
            }
        }
        return
    }

    val localTask = installed?.get(release.source)
    val compatible = BuildConfig.VERSION_CODE >= release.minAppVersionCode
    val button = when {
        importing -> "正在导入…"
        !compatible -> "请先更新雕雕助手"
        localTask != null -> "更新本地任务"
        else -> "导入到我的任务"
    }
    val feedback = "$MARKET_REPO/issues/new".toUri().buildUpon()
        .appendQueryParameter("title", "[${release.title}] ")
        .appendQueryParameter("body", "请描述起始界面、操作步骤和实际结果。")
        .build().toString()

    DetailPage(
        title = "脚本说明",
        subtitle = "脚本市场",
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
                enabled = !importing && installed != null && loadError == null,
            ) {
                if (!compatible) { onUpdate(); return@PrimaryAction }
                name = localTask ?: availableMarketName(release.title, taskNames)
                importError = null
                confirmImport = true
            }
        }
    ) {
        // 市场脚本摘要：图标 + 名称 + 作者版本，与设计稿的详情首屏一致。
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = Color(AppPalette.PRIMARY_CONTAINER), contentColor = Color(AppPalette.ON_SURFACE_VARIANT),
                shape = RoundedCornerShape(20.dp)) {
                Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
                    AppIcon(R.drawable.ic_code, modifier = Modifier.size(28.dp))
                }
            }
            Column(Modifier.weight(1f).padding(start = 15.dp)) {
                Text(release.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.7).sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${release.author} · v${release.version}", fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            StatusBadge(release.targetApp ?: "通用")
            if (release.capabilities.isNotEmpty()) StatusBadge(release.capabilities.first())
            StatusBadge("需更新助手".takeIf { !compatible } ?: "可导入")
        }
        MarketSection("脚本介绍", release.description)
        MarketSection("使用步骤", release.usage)
        MarketSection("适配环境", "${release.targetApp ?: "通用"} · ${release.environment} · 最低助手版本号 ${release.minAppVersionCode}")
        MarketSection("脚本能力", release.capabilities.joinToString("、").ifEmpty { "无需额外权限" })
        MarketSection("更新说明", "${release.updatedAt}\n${release.changes}")
        SectionCard(Modifier.padding(top = 4.dp)) {
            DesignRow("查看源码和素材", "assistant-scripts · GitHub", R.drawable.ic_code) {
                openMarketLink(context, release.sourceUrl)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DesignRow("反馈问题", "描述设备环境和运行情况", R.drawable.ic_add) {
                openMarketLink(context, feedback)
            }
        }
        if (localTask != null) {
            TextButton(enabled = !importing, onClick = { onOpenTask(localTask) }) { Text("打开本地任务") }
        }
        loadError?.let { Text("市场刷新失败。$it", color = MaterialTheme.colorScheme.error) }
    }

    if (confirmImport) {
        val normalized = localTask ?: name.trim()
        val validName = localTask != null || (normalized.isNotEmpty() && normalized.length <= 50 && normalized !in taskNames &&
            normalized !in listOf(".", "..") && !normalized.startsWith(".deleted-") &&
            normalized.none { it == '/' || it == '\\' || it.isISOControl() })
        ConfirmActionSheet("导入到任务", "导入前核对任务名称与覆盖范围。", "确认导入",
            onDismiss = { confirmImport = false }, enabled = validName && compatible,
            onConfirm = { if (validName && compatible) startImport(listOf(MarketBatchItem(release, normalized, localTask != null))) }) {
                    Text("${(release.sizeBytes + 1023) / 1024} KB")
                    if (localTask != null) {
                        Text("导入后会覆盖「$localTask」的脚本、素材和配置。")
                    } else {
                        Text("本地任务名称", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = name, onValueChange = { name = it; importError = null }, singleLine = true,
                            placeholder = { Text("保留原名或输入新名称", fontSize = 13.sp) }, enabled = !importing,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                            isError = normalized.isNotEmpty() && !validName,
                            supportingText = {
                                if (normalized in taskNames) Text("该任务名称已存在")
                                else if (normalized.isNotEmpty() && !validName) Text("请输入合法任务名，最多 50 字")
                            },
                        )
                    }
                    importError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            // 导入成功后返回市场列表，保留搜索和筛选条件并同步本地任务状态。
        }
    }
}

/** 首次目录加载时提供稳定的卡片占位，减少空白和布局跳动。 */
@Composable
private fun MarketSkeleton() {
    SectionCard {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(.55f, 1f, .8f).forEach { width ->
                Box(Modifier.fillMaxWidth(width).heightIn(min = 15.dp).background(Color(AppPalette.SURFACE_CONTAINER), RoundedCornerShape(6.dp)))
            }
        }
    }
}

@Composable
private fun MarketSection(title: String, body: String) {
    // 内容块：白底圆角 + 标题 + 可选中文本。
    Surface(Modifier.fillMaxWidth().padding(top = 4.dp), color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(17.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            SelectionContainer {
                Text(body, Modifier.padding(top = 8.dp), fontSize = 11.sp, lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
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
