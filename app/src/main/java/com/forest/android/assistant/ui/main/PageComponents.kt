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
import com.forest.android.assistant.R
import com.forest.android.assistant.ui.*

/** 主界面各页共用的标题栏与小部件。 */

@Composable
internal fun MainActivity.PageHeading(title: String, subtitle: String, compact: Boolean = false, actions: @Composable () -> Unit = {}) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 14.dp,
                top = if (compact) 10.dp else 16.dp, bottom = if (compact) 10.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 23.sp, lineHeight = if (compact) 28.sp else 31.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = (-1).sp)
                    Text(subtitle, fontSize = 10.sp, lineHeight = if (compact) 14.sp else 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = if (compact) 2.dp else 5.dp))
                }
                actions()
            }
        }
    }
}

/** 图片信息行：左标签右值，与设计稿的 key-value 一致。 */
// 两列等宽，数值从右半列起点左对齐，避免文件名、尺寸和格式各自贴右边。
@Composable
internal fun MainActivity.KeyValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        Text(value, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
    }
}

/** 工具行：名称 + 用途 + 尾部箭头 */
@Composable
internal fun MainActivity.ToolRow(title: String, desc: String, icon: Int = R.drawable.ic_nav_tools, onClick: () -> Unit) {
    DesignRow(title, desc, icon, onClick = onClick)
}
