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
    secondary = Color(0xFF536582),
    onSecondary = Color.White,
    secondaryContainer = Color(AppPalette.SECONDARY_CONTAINER),
    onSecondaryContainer = Color(0xFF34455F),
    tertiary = Color(AppPalette.TERTIARY),
    onTertiary = Color.White,
    tertiaryContainer = Color(AppPalette.TERTIARY_CONTAINER),
    onTertiaryContainer = Color(0xFF175861),
    background = Color(AppPalette.BACKGROUND),
    onBackground = Color(AppPalette.ON_SURFACE),
    surface = Color(AppPalette.SURFACE),
    surfaceTint = Color(AppPalette.PRIMARY),
    surfaceDim = Color(0xFFDCE2EE),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color(AppPalette.SURFACE),
    surfaceContainerLow = Color(AppPalette.SURFACE_CONTAINER_LOW),
    surfaceContainer = Color(AppPalette.SURFACE_CONTAINER),
    surfaceContainerHigh = Color(0xFFE7ECF5),
    surfaceContainerHighest = Color(0xFFDDE5F2),
    onSurface = Color(AppPalette.ON_SURFACE),
    surfaceVariant = Color(AppPalette.SECONDARY_CONTAINER),
    onSurfaceVariant = Color(AppPalette.ON_SURFACE_VARIANT),
    inverseSurface = Color(0xFF1D2B46),
    inverseOnSurface = Color.White,
    inversePrimary = Color(0xFFADC6FF),
    outline = Color(AppPalette.OUTLINE),
    outlineVariant = Color(AppPalette.OUTLINE_VARIANT),
    error = Color(AppPalette.ERROR),
    onError = Color(AppPalette.ON_ERROR),
    errorContainer = Color(AppPalette.ERROR_CONTAINER),
    onErrorContainer = Color(0xFF8C2533)
)

/** 所有原生页面共用配色、文字层级与圆角；不依赖动态壁纸色。 */
@Composable
fun ScriptTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ScriptColors,
        typography = Typography(
            headlineLarge = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
            headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
            titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
            titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
            labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(6.dp),
            medium = RoundedCornerShape(8.dp),
            large = RoundedCornerShape(10.dp),
            extraLarge = RoundedCornerShape(12.dp)
        ),
        content = content
    )
}

@Composable
fun AppIcon(@DrawableRes resource: Int, description: String? = null, modifier: Modifier = Modifier) {
    Icon(painterResource(resource), contentDescription = description, modifier = modifier.size(22.dp))
}

@Composable
fun WorkCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content
    )
}
