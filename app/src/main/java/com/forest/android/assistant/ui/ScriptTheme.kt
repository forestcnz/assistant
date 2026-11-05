package com.forest.android.assistant.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ScriptColors = lightColorScheme(
    primary = Color(AppPalette.PRIMARY),
    onPrimary = Color(AppPalette.ON_PRIMARY),
    primaryContainer = Color(AppPalette.PRIMARY_CONTAINER),
    onPrimaryContainer = Color(AppPalette.ON_PRIMARY_CONTAINER),
    secondary = Color(0xFF5B6777),
    onSecondary = Color.White,
    secondaryContainer = Color(AppPalette.SECONDARY_CONTAINER),
    onSecondaryContainer = Color(0xFF33414F),
    tertiary = Color(AppPalette.TERTIARY),
    onTertiary = Color.White,
    tertiaryContainer = Color(AppPalette.TERTIARY_CONTAINER),
    onTertiaryContainer = Color(0xFF5E3C08),
    background = Color(AppPalette.BACKGROUND),
    onBackground = Color(AppPalette.ON_SURFACE),
    surface = Color(AppPalette.SURFACE),
    surfaceTint = Color(AppPalette.PRIMARY),
    surfaceDim = Color(0xFFE0E3E7),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color(AppPalette.SURFACE),
    surfaceContainerLow = Color(AppPalette.SURFACE_CONTAINER_LOW),
    surfaceContainer = Color(AppPalette.SURFACE_CONTAINER),
    surfaceContainerHigh = Color(0xFFEAEDF1),
    surfaceContainerHighest = Color(0xFFE2E6EA),
    onSurface = Color(AppPalette.ON_SURFACE),
    surfaceVariant = Color(AppPalette.SECONDARY_CONTAINER),
    onSurfaceVariant = Color(AppPalette.ON_SURFACE_VARIANT),
    inverseSurface = Color(AppPalette.INVERSE_SURFACE),
    inverseOnSurface = Color(AppPalette.INVERSE_ON_SURFACE),
    inversePrimary = Color(AppPalette.INVERSE_ACCENT),
    outline = Color(AppPalette.OUTLINE),
    outlineVariant = Color(AppPalette.OUTLINE_VARIANT),
    error = Color(AppPalette.ERROR),
    onError = Color(AppPalette.ON_ERROR),
    errorContainer = Color(AppPalette.ERROR_CONTAINER),
    onErrorContainer = Color(0xFF842C32)
)

/** 所有原生页面共用配色、文字层级与圆角；不依赖动态壁纸色。 */
@Composable
fun ScriptTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ScriptColors,
        typography = Typography(
            headlineLarge = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
            headlineSmall = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
            titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
            labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(24.dp)
        ),
        content = content
    )
}

@Composable
fun AppIcon(@DrawableRes resource: Int, description: String? = null, modifier: Modifier = Modifier.size(22.dp)) {
    // 调用方可为装饰图标和紧凑按钮指定尺寸，默认仍为 22dp。
    Icon(painterResource(resource), contentDescription = description, modifier = modifier)
}

@Composable
fun WorkCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content
    )
}
