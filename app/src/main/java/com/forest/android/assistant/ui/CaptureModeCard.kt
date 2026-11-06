package com.forest.android.assistant.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forest.android.assistant.R
import com.forest.android.assistant.core.image.ScreenCapture

/** 等宽方形选择卡，整张卡片可点击，文字在窄屏下使用紧凑尺寸。 */
@Composable
fun CaptureModeCard(
    mode: ScreenCapture.Mode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val systemCapture = mode == ScreenCapture.Mode.MEDIAPROJECTION
    BoxWithConstraints(
        modifier = modifier
            .clip(shape)
            .background(if (selected) colors.primaryContainer else colors.surfaceContainerLow)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    ) {
        val compact = maxWidth < 148.dp
        Column(
            modifier = Modifier.fillMaxSize().padding(if (compact) 10.dp else 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier.size(if (compact) 32.dp else 40.dp)
                    .background(if (selected) colors.primary.copy(alpha = 0.10f) else colors.secondaryContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(if (systemCapture) R.drawable.ic_capture_system else R.drawable.ic_capture_privileged),
                    contentDescription = null,
                    modifier = Modifier.size(if (compact) 22.dp else 26.dp),
                    tint = if (selected) colors.primary else colors.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(if (compact) 8.dp else 10.dp))
            Text(
                text = mode.label,
                style = if (compact) MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, lineHeight = 18.sp)
                    else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) colors.primary else colors.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (systemCapture) "系统弹窗授权\n适合普通设备" else "需超级用户权限\n免录屏授权弹窗",
                style = if (compact) MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 15.sp)
                    else MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
        Box(
            modifier = Modifier.align(Alignment.TopEnd).padding(9.dp).size(18.dp)
                .background(if (selected) colors.primary else colors.surface, CircleShape)
                .border(if (selected) 0.dp else 1.5.dp, if (selected) colors.primary else colors.outline, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    painter = painterResource(R.drawable.ic_capture_selected),
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = colors.onPrimary
                )
            }
        }
    }
}
