package com.forest.android.assistant.ui

/** APP 与原生悬浮窗共用的浅色配色；颜色值与 ScriptTheme 的 Material 色阶一致。 */
object AppPalette {
    // 暖白与深海军蓝形成统一的工作台配色，金色作为次级强调；与雕雕品牌图标一致。
    const val PRIMARY = 0xFF1D3557.toInt()
    const val ON_PRIMARY = 0xFFFFFFFF.toInt()
    const val PRIMARY_CONTAINER = 0xFFE3ECF7.toInt()
    const val ON_PRIMARY_CONTAINER = 0xFF17324F.toInt()
    const val SECONDARY_CONTAINER = 0xFFEEEDE7.toInt()
    const val TERTIARY = 0xFF9A6A1B.toInt()
    const val TERTIARY_CONTAINER = 0xFFFBEDD2.toInt()
    const val BACKGROUND = 0xFFF6F4EE.toInt()
    const val SURFACE = 0xFFFFFDF8.toInt()
    const val SURFACE_CONTAINER_LOW = 0xFFF9F7F1.toInt()
    const val SURFACE_CONTAINER = 0xFFF0EEE7.toInt()
    const val ON_SURFACE = 0xFF1F2A37.toInt()
    const val ON_SURFACE_VARIANT = 0xFF5F6B78.toInt()
    const val OUTLINE = 0xFF7B8794.toInt()
    const val OUTLINE_VARIANT = 0xFFDDE2E6.toInt()
    const val ERROR = 0xFFB34045.toInt()
    const val ON_ERROR = 0xFFFFFFFF.toInt()
    const val ERROR_CONTAINER = 0xFFFFE9E8.toInt()

    // 深色标题栏与图片工作区使用独立前景色，避免浅色主题的文字在深底上失去对比。
    const val INVERSE_SURFACE = 0xFF13264B.toInt()
    const val INVERSE_ON_SURFACE = 0xFFFFF9EF.toInt()
    const val INVERSE_SUBTLE = 0xFFC3D0E3.toInt()
    const val INVERSE_ACCENT = 0xFFA9C6E8.toInt()
    const val RECORDING_INDICATOR = 0xFFFF9E96.toInt()
}
