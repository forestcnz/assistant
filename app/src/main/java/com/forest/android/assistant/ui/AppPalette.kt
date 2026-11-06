package com.forest.android.assistant.ui

/** APP 与原生悬浮窗共用的浅色配色；颜色值与 ScriptTheme 的 Material 色阶一致。 */
object AppPalette {
    // 共享色板，同时供 Compose 和 WindowManager 悬浮窗使用。
    const val PRIMARY = 0xFFEF754D.toInt()
    const val ON_PRIMARY = 0xFFFFFFFF.toInt()
    const val PRIMARY_CONTAINER = 0xFFFFF0E7.toInt()
    const val ON_PRIMARY_CONTAINER = 0xFFC94E30.toInt()
    const val SECONDARY_CONTAINER = 0xFFE2F3E9.toInt()
    const val TERTIARY = 0xFFA95632.toInt()
    const val TERTIARY_CONTAINER = 0xFFFFF0E7.toInt()
    const val BACKGROUND = 0xFFF4F3EE.toInt()
    const val SURFACE = 0xFFFFFEFA.toInt()
    const val SURFACE_CONTAINER_LOW = 0xFFF7F7F2.toInt()
    const val SURFACE_CONTAINER = 0xFFE9EDE8.toInt()
    const val ON_SURFACE = 0xFF1B2A30.toInt()
    const val ON_SURFACE_VARIANT = 0xFF75817F.toInt()
    const val OUTLINE = 0xFF89948F.toInt()
    const val OUTLINE_VARIANT = 0xFFE3E6DF.toInt()
    const val SUCCESS = 0xFF207959.toInt()
    const val BLUE = 0xFF4C6685.toInt()
    const val BLUE_CONTAINER = 0xFFE8EEF7.toInt()
    const val ERROR = 0xFFBD4948.toInt()
    const val ON_ERROR = 0xFFFFFFFF.toInt()
    const val ERROR_CONTAINER = 0xFFFFE9E8.toInt()

    // 深色标题栏与图片工作区使用独立前景色，避免浅色主题的文字在深底上失去对比。
    const val INVERSE_SURFACE = 0xFF202E33.toInt()
    const val INVERSE_ON_SURFACE = 0xFFD5E2DD.toInt()
    const val INVERSE_SUBTLE = 0xFFB5C5BF.toInt()
    const val INVERSE_ACCENT = 0xFFFFAB7E.toInt()
    const val RECORDING_INDICATOR = 0xFFEF754D.toInt()
}
