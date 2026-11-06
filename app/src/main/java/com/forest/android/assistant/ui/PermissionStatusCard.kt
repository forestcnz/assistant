package com.forest.android.assistant.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.forest.android.assistant.R

/** 权限状态用底色与文字共同表达；整张卡片可点击，适配纵向列表和大字体。 */
@Composable
fun PermissionStatusCard(
    title: String,
    description: String,
    @DrawableRes iconRes: Int,
    ready: Boolean,
    status: String,
    actionLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val statusColor = if (ready) colors.primary else colors.tertiary
    val statusBackground = if (ready) colors.primaryContainer else colors.tertiaryContainer
    val shape = RoundedCornerShape(16.dp)
    // 纵向列表中的每张卡保留一个点击区域，图标、用途和状态不再依赖方卡宽度裁切。
    Row(
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceContainerLow)
            .border(1.dp, colors.outlineVariant, shape)
            .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).background(statusBackground, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(iconRes), contentDescription = null, tint = statusColor, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
            Text(description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp))
            Row(Modifier.padding(top = 8.dp).background(statusBackground, RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).background(statusColor, CircleShape))
                Spacer(Modifier.width(5.dp))
                Text(status, style = MaterialTheme.typography.labelMedium, color = statusColor)
            }
        }
        Icon(painterResource(R.drawable.ic_arrow), contentDescription = null,
            tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
    }
}
