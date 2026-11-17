package com.forest.android.assistant.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import com.forest.android.assistant.ui.AppRoundedCornerShape as RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forest.android.assistant.R
import com.forest.android.assistant.core.task.RunHistory
import com.forest.android.assistant.core.web.LanWebState

/** 次级页只组合真实状态和回调，继续复用原来的文件及服务操作。 */
@Composable
fun TransferPage(taskCount: Int, busy: Boolean, canClear: Boolean, onBack: () -> Unit,
    onImport: () -> Unit, onExport: () -> Unit, onClear: () -> Unit) {
    DetailPage("导入与导出", "备份、迁移与整理", onBack) {
        ContentBlock("留一份安心的备份。") {
            Text("任务、素材和配置，随时带到另一台设备。", fontSize = 12.sp, color = Color(AppPalette.ON_SURFACE_VARIANT))
            StatusBadge("本地 ZIP · 自主保存")
        }
        SectionLabel("导入")
        SectionCard { DesignRow("从 ZIP 导入任务", "支持单任务与任务合集", R.drawable.ic_import, !busy, onClick = onImport) }
        SectionLabel("导出")
        SectionCard { DesignRow("导出全部任务", "$taskCount 个任务 · 包含分组与执行顺序", R.drawable.ic_export,
            !busy && taskCount > 0, onClick = onExport) }
        InfoNotice("导入会覆盖同名任务的脚本、素材和配置，保留本机运行历史。合集中的分组信息会按备份恢复。", warning = true)
        InfoNotice("备份包含任务文件和素材；共享流程模板不随任务导出。请把 ZIP 保存到任务目录之外。")
        SectionLabel("清理")
        SectionCard { DesignRow("清空全部任务", "删除脚本、素材、运行记录和定时", R.drawable.ic_clear_tasks, canClear, onClick = onClear) }
    }
}

@Composable
fun QueueIntervalPage(value: String, onChange: (String) -> Unit, onBack: () -> Unit, onSave: (Int) -> Unit) {
    val seconds = value.trim().toIntOrNull()?.takeIf { it >= 0 }
    DetailPage("多任务执行间隔", "适用于多选任务与分组", onBack,
        footer = { PrimaryAction("保存设置", seconds != null) { seconds?.let(onSave) } }) {
        ContentBlock("给任务之间留一点时间") {
            Text("多选任务和分组任务按顺序运行时，每个任务完成后，等待指定时间再开始下一个。", fontSize = 12.sp, lineHeight = 21.sp)
        }
        DesignField("任务间隔 · 秒", value, onChange, "默认 3 秒，设为 0 秒可立即执行下一项。", KeyboardType.Number,
            error = if (value.isNotEmpty() && seconds == null) "请输入有效的非负整数" else null)
        Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            StatusBadge("任务 A"); AppIcon(R.drawable.ic_arrow)
            StatusBadge("等待 ${seconds?.toString() ?: "—"} 秒"); AppIcon(R.drawable.ic_arrow); StatusBadge("任务 B")
        }
        InfoNotice("首个任务立即开始，最后一个结束后不再等待。保存后从下一次启动队列生效。")
    }
}

@Composable
fun LanEditorPage(state: LanWebState, onBack: () -> Unit, onStart: () -> Unit, onStop: () -> Unit, onCopy: (String) -> Unit) {
    DetailPage("局域网 Web 编辑", "把手机任务连接到浏览器", onBack,
        footer = { if (state.running) SecondaryAction("停止服务", onClick = onStop) else PrimaryAction("启动服务", icon = R.drawable.ic_play, onClick = onStart) }) {
        Row(Modifier.fillMaxWidth().padding(vertical = 35.dp), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            AppIcon(R.drawable.ic_phone, modifier = Modifier.size(49.dp))
            Text("┄┄┄", Modifier.padding(horizontal = 20.dp), color = Color(0xFF9DB6D4), fontSize = 24.sp)
            AppIcon(R.drawable.ic_monitor, modifier = Modifier.size(49.dp))
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            StatusBadge(if (state.running) "服务运行中" else "服务已停止")
            Text("大一点的屏幕，\n更自在地编排。", fontSize = 21.sp, lineHeight = 31.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 17.dp, bottom = 10.dp))
            Text("电脑、平板和手机，同一局域网即可连接。", fontSize = 12.sp, color = Color(AppPalette.ON_SURFACE_VARIANT))
        }
        state.url?.takeIf { state.running }?.let { url ->
            Text(url, Modifier.fillMaxWidth().background(Color(AppPalette.PRIMARY_CONTAINER), RoundedCornerShape(14.dp))
                .padding(vertical = 20.dp, horizontal = 8.dp), fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
                color = Color(AppPalette.PRIMARY), fontSize = 16.sp)
            SecondaryAction("复制访问地址") { onCopy(url) }
        } ?: InfoNotice(state.error ?: "启动服务后，这里会显示可在浏览器中打开的地址。", error = state.error != null)
        ContentBlock("连接后可以做什么") { Text("编辑脚本、编排流程、管理任务分组和共享模板、上传素材。", fontSize = 12.sp, lineHeight = 21.sp) }
        InfoNotice("请在可信网络使用。同一局域网内能够访问此地址的设备都可以编辑任务，用完后可停止服务。", warning = true)
    }
}

@Composable
fun KeepAlivePage(onBack: () -> Unit, onSettings: () -> Unit, onAccessibility: () -> Unit) {
    DetailPage("保活指引", "让自动化在后台也可靠运行", onBack, footer = {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { SecondaryAction("应用系统设置", onClick = onSettings) }
            Box(Modifier.weight(1f)) { PrimaryAction("重试开启无障碍", onClick = onAccessibility) }
        }
    }) {
        InfoNotice("如果无障碍服务经常关闭，或定时任务没有按时运行，可依次检查以下设置。")
        listOf("允许后台运行" to "在应用电池设置中，选择允许后台活动或不限制。",
            "允许自启动" to "部分系统需要单独开启自启动与关联启动。",
            "锁定最近任务" to "在最近任务列表中锁定雕雕助手，减少被清理。",
            "重新开启无障碍" to "确认服务开启，再返回运行准备页检查状态。").forEachIndexed { index, (title, text) ->
            ContentBlock("STEP 0${index + 1} · $title") { Text(text, fontSize = 12.sp, lineHeight = 21.sp, color = Color(AppPalette.ON_SURFACE_VARIANT)) }
        }
        InfoNotice("不同品牌的设置名称可能不同，以设备系统页面为准。长时间跑图色脚本时，可常驻前台并保持屏幕常亮。")
    }
}

/** 成功、停止和失败共用结果页，不编造旧记录里没有保存的轮次数据。 */
@Composable
fun RunResultPage(taskName: String, entry: RunHistory.Entry, onBack: () -> Unit, onEdit: () -> Unit, onAssets: () -> Unit) {
    val error = entry.outcome == RunHistory.Outcome.ERROR
    val stopped = entry.outcome == RunHistory.Outcome.STOPPED
    DetailPage("运行详情", taskName, onBack, footer = {
        if (error) PrimaryAction("打开脚本并定位", icon = R.drawable.ic_code, onClick = onEdit)
        else PrimaryAction("返回运行历史", onClick = onBack)
    }) {
        EmptyState(if (error) "任务执行遇到问题" else if (stopped) "任务已手动停止" else "这次任务，顺利完成",
            entry.message?.lineSequence()?.firstOrNull() ?: if (error) "检查错误信息与素材路径后再试。" else "结果已记录在运行历史中。",
            if (error) R.drawable.ic_tool_log else R.drawable.ic_check)
        MetricStrip(listOf(entry.durationText() to "执行耗时", entry.outcome.label to "执行结果", entry.source to "触发来源"))
        if (error) {
            Surface(color = Color(AppPalette.INVERSE_SURFACE), shape = RoundedCornerShape(12.dp)) {
                Text(listOfNotNull(entry.message, entry.stack).distinct().joinToString("\n").ifBlank { "未记录错误堆栈" },
                    Modifier.fillMaxWidth().padding(14.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    lineHeight = 19.sp, color = Color(0xFFE3A59A))
            }
            TextButton(onClick = onAssets) { Text("查看任务素材") }
        } else ContentBlock("完成时间") { Text(entry.time, fontSize = 12.sp); Text("${taskName} · ${entry.outcome.label}", fontSize = 12.sp) }
    }
}
