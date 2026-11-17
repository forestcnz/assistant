package com.forest.android.assistant.ui

/** APP 与原生悬浮窗共用的浅色配色；颜色值与 ScriptTheme 的 Material 色阶一致。 */
object AppPalette {
    // 方案二「雾蓝暖白」共享色板，同时供 Compose 和 WindowManager 悬浮窗使用。
    // 雾蓝用于主按钮与选中态，深蓝灰用于标题与品牌区域，暖灰白作为页面底色。
    const val PRIMARY = 0xFF3566A0.toInt()
    const val ON_PRIMARY = 0xFFFFFFFF.toInt()
    const val PRIMARY_CONTAINER = 0xFFEDF2F8.toInt()
    const val ON_PRIMARY_CONTAINER = 0xFF284F7D.toInt()
    const val SECONDARY_CONTAINER = 0xFFEDF5F1.toInt()
    const val TERTIARY = 0xFF94601F.toInt()
    const val TERTIARY_CONTAINER = 0xFFFBF2DF.toInt()
    const val BACKGROUND = 0xFFFAF9F6.toInt()
    const val SURFACE = 0xFFFFFFFF.toInt()
    const val SURFACE_CONTAINER_LOW = 0xFFF7F8FA.toInt()
    const val SURFACE_CONTAINER = 0xFFEEF1F5.toInt()
    const val ON_SURFACE = 0xFF223047.toInt()
    const val ON_SURFACE_VARIANT = 0xFF616E80.toInt()
    const val OUTLINE = 0xFFA7B1BE.toInt()
    const val OUTLINE_VARIANT = 0xFFE2E5EA.toInt()
    const val SUCCESS = 0xFF36735B.toInt()
    const val BLUE = 0xFF5C7894.toInt()
    const val BLUE_CONTAINER = 0xFFEAF0F5.toInt()
    const val ERROR = 0xFFAE4647.toInt()
    const val ON_ERROR = 0xFFFFFFFF.toInt()
    const val ERROR_CONTAINER = 0xFFF9E9E6.toInt()

    // 品牌沙色点缀，呼应雕雕形象；只作少量强调，不承担状态语义。
    const val SAND_CONTAINER = 0xFFF7EEDC.toInt()
    const val ON_SAND_CONTAINER = 0xFF92713E.toInt()
    const val AMBER_CONTAINER = 0xFFFBF2DF.toInt()

    // 深色标题栏与图片工作区使用独立前景色，避免浅色主题的文字在深底上失去对比。
    const val INVERSE_SURFACE = 0xFF223047.toInt()
    const val INVERSE_ON_SURFACE = 0xFFD1DEEE.toInt()
    const val INVERSE_SUBTLE = 0xFFB7C8DD.toInt()
    const val INVERSE_ACCENT = 0xFFEBD9B4.toInt()
}
