package com.forest.android.assistant.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// 全部页面沿用原有基准尺寸，统一缩小约三分之一，保持卡片、边框与裁切的圆角一致。
private const val APP_CORNER_SCALE = 0.65f

internal fun AppRoundedCornerShape(size: Dp): RoundedCornerShape =
    RoundedCornerShape(size * APP_CORNER_SCALE)

internal fun AppRoundedCornerShape(percent: Int): RoundedCornerShape =
    RoundedCornerShape((percent * APP_CORNER_SCALE).roundToInt())

internal fun AppRoundedCornerShape(
    topStart: Dp = 0.dp,
    topEnd: Dp = 0.dp,
    bottomEnd: Dp = 0.dp,
    bottomStart: Dp = 0.dp
): RoundedCornerShape = RoundedCornerShape(
    topStart = topStart * APP_CORNER_SCALE,
    topEnd = topEnd * APP_CORNER_SCALE,
    bottomEnd = bottomEnd * APP_CORNER_SCALE,
    bottomStart = bottomStart * APP_CORNER_SCALE
)
