package com.forest.android.assistant.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 权限状态用边框颜色和文字共同表达；窄屏优先保证名称与状态完整显示。 */
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
    val borderColor = if (ready) Color(0xFF278665) else Color(0xFFC18225)
    val statusColor = if (ready) Color(0xFF166347) else Color(0xFF8A5010)
    val shape = RoundedCornerShape(16.dp)
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(
        modifier = modifier
            .clip(shape)
            .background(borderColor.copy(alpha = 0.055f))
            .border(2.dp, borderColor, shape)
            .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onClick)
    ) {
        val tiny = maxWidth < 112.dp
        val compact = maxWidth < 164.dp || fontScale > 1.2f
        val showIcon = maxWidth >= 92.dp && (fontScale <= 1.2f || maxWidth >= 148.dp)
        val inset = when {
            maxWidth < 92.dp -> 4.dp
            maxWidth < 128.dp -> 8.dp
            else -> 12.dp
        }
        Column(
            modifier = Modifier.fillMaxSize().padding(inset),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (showIcon) {
                Box(
                    modifier = Modifier.size(if (tiny) 24.dp else if (compact) 28.dp else 40.dp)
                        .background(borderColor.copy(alpha = 0.10f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(if (tiny) 18.dp else if (compact) 20.dp else 26.dp),
                        tint = statusColor
                    )
                }
                Spacer(Modifier.height(if (tiny) 6.dp else if (compact) 8.dp else 10.dp))
            }
            Text(
                text = title,
                style = if (tiny) MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp, lineHeight = 16.sp)
                    else if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            if (!compact) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(if (tiny) 6.dp else if (compact) 8.dp else 10.dp))
            Box(
                modifier = Modifier.background(borderColor.copy(alpha = 0.12f), RoundedCornerShape(50))
                    .padding(horizontal = if (tiny) 6.dp else 8.dp, vertical = if (tiny) 2.dp else 3.dp)
            ) {
                Text(
                    text = status,
                    style = if (tiny) MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp, lineHeight = 14.sp)
                        else if (compact) MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp, lineHeight = 16.sp)
                        else MaterialTheme.typography.labelMedium,
                    color = statusColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}
