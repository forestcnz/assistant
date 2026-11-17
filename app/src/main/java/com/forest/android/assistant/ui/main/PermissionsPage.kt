package com.forest.android.assistant.ui.main

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.forest.android.assistant.ui.AppRoundedCornerShape as RoundedCornerShape
import com.forest.android.assistant.MainActivity
import com.forest.android.assistant.openCaptureDialog
import com.forest.android.assistant.R
import com.forest.android.assistant.ui.*
import android.content.Intent
import android.provider.Settings
import com.forest.android.assistant.core.floaty.FloatyManager

/** 运行准备页：无障碍、悬浮窗与截屏能力状态。 */

/** 三项权限纵向显示为状态卡，状态底色与文字同步反映授权情况。 */
@Composable
private fun MainActivity.PermissionCard() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PermissionStatusRow(
            title = "无障碍服务",
            desc = "用于点击、滑动、手势和录制操作。",
            icon = R.drawable.ic_permission_gesture,
            granted = a11yEnabled,
            action = if (a11yEnabled) "管理" else "去开启"
        ) {
            if (a11yEnabled) keepAliveDialog = true
            else startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        PermissionStatusRow(
            title = "悬浮窗权限",
            desc = "让任务面板和快捷工具出现在其他应用上。",
            icon = R.drawable.ic_floaty,
            granted = overlayGranted,
            action = if (overlayGranted) "管理" else "去开启"
        ) {
            if (overlayGranted) mainTab = 3 else FloatyManager.requestOverlayPermission(this@PermissionCard)
        }
        PermissionStatusRow(
            title = "截屏能力",
            desc = "用于找图、找色、OCR 和截图。",
            icon = R.drawable.ic_tool_shot,
            granted = captureReady,
            action = "设置"
        ) { openCaptureDialog() }
    }
}

/** 权限卡片：标题、状态胶囊、说明和右上角入口。 */
@Composable
private fun MainActivity.PermissionStatusRow(title: String, desc: String, icon: Int, granted: Boolean,
    action: String, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(19.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        // 标题和状态并排，说明置于同一内容列，减少卡片的纵向占用。
        // 状态与操作入口放入同一尾部行并垂直居中，避免状态跟随标题而与按钮上下错位。
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon)
            Column(Modifier.weight(1f).padding(start = 10.dp, end = 8.dp)) {
                Text(title, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(desc, fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusBadge(if (granted) "已开启" else "待设置", warning = !granted)
                TextButton(onClick = onClick, modifier = Modifier.width(60.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text(action, fontSize = 12.sp, lineHeight = 16.sp, color = Color(AppPalette.PRIMARY))
                }
            }
        }
    }
}

/** 权限页：三项能力与截屏通道设置。 */
@Composable
internal fun MainActivity.PermissionsTab() {
    val readyCount = listOf(a11yEnabled, overlayGranted, captureReady).count { it }
    Column(Modifier.fillMaxSize()) {
        PageHeading("运行准备", "能力按需开启，状态一目了然", compact = true)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 深色就绪概览，右侧显示已开启数量。
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(23.dp))
                .background(Color(AppPalette.INVERSE_SURFACE)).padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("READY WHEN YOU ARE", color = Color(AppPalette.INVERSE_SUBTLE), fontSize = 9.sp, lineHeight = 12.sp,
                        letterSpacing = 1.7.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (readyCount == 3) "一切就绪，放心开始" else "再一步，就准备好了",
                        color = Color.White, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium,
                        letterSpacing = (-0.5).sp, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
                    Text("按任务所需，开启对应能力。", color = Color(0xFFC7D1DF), fontSize = 10.sp, lineHeight = 16.sp)
                }
                Box(Modifier.size(52.dp).border(3.dp, Color(0xFF88A6CA), androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center) {
                    Text("$readyCount/3", color = Color(0xFFD1DEEE), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            PermissionCard()
            // 权限说明改为普通小字，与列表提示保持同一层级。
            Text("先开启权限，再执行依赖该能力的任务；截屏方式需要手动确认并测试。",
                Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
                fontSize = 10.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { keepAliveDialog = true }) { Text("服务容易中断？查看保活指引", fontSize = 12.sp) }
        }
    }
}
