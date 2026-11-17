package com.forest.android.assistant.ui.main

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forest.android.assistant.MainActivity

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
