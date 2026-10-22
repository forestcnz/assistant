package com.forest.android.assistant.core.engine

/**
 * 分辨率适配：脚本按设计基准分辨率（如 1080x1920）编写坐标，
 * 运行时按真机分辨率自动换算（对应 API 手册第 6 节 adapter）。
 *
 * 换算方向：
 * - 脚本 → 真机：坐标类 API（click/swipe/gesture 等）派发前放大；
 * - 真机 → 脚本：找图/找色返回的坐标换算回设计坐标，保证 `click(p.x, p.y)` 语义一致。
 * 每次运行开始时重置，避免脚本间状态泄漏。
 */
object ScriptAdapter {

    @Volatile
    private var designW = 0

    @Volatile
    private var designH = 0

    @Volatile
    private var realW = 0

    @Volatile
    private var realH = 0

    /** 由宿主在运行前设置真机屏幕尺寸 */
    fun setRealSize(width: Int, height: Int) {
        realW = width
        realH = height
    }

    /** 是否启用缩放 */
    fun enabled(): Boolean = designW > 0 && designH > 0 && realW > 0 && realH > 0

    fun setDesignSize(width: Int, height: Int) {
        designW = width.coerceAtLeast(0)
        designH = height.coerceAtLeast(0)
    }

    fun reset() {
        designW = 0
        designH = 0
    }

    /** 全部重置（运行结束时调用） */
    fun clear() {
        reset()
        realW = 0
        realH = 0
    }

    /** 设计坐标 → 真机坐标 */
    fun toRealX(x: Float): Float = if (enabled()) x * realW / designW else x

    fun toRealY(y: Float): Float = if (enabled()) y * realH / designH else y

    /** 真机坐标 → 设计坐标 */
    fun toDesignX(x: Int): Int = if (enabled()) (x.toLong() * designW / realW).toInt() else x

    fun toDesignY(y: Int): Int = if (enabled()) (y.toLong() * designH / realH).toInt() else y
}
