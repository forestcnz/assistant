package com.forest.android.assistant.core.floaty

/** 有界日志窗口：按唯一序号保留重叠行，返回需移除的头部和需追加的尾部。 */
internal class LogWindow<T>(private val limit: Int, private val id: (T) -> Long) {
    data class Change<T>(val removeCount: Int, val append: List<T>)
    private var visible = emptyList<T>()

    fun update(lines: List<T>): Change<T> {
        val next = lines.takeLast(limit)
        val start = next.firstOrNull()?.let { first -> visible.indexOfFirst { id(it) == id(first) } } ?: -1
        val overlap = if (start >= 0) visible.size - start else 0
        val canReuse = overlap <= next.size && (0 until overlap).all {
            id(visible[start + it]) == id(next[it])
        }
        val change = if (start >= 0 && canReuse) Change(start, next.drop(overlap))
            else Change(visible.size, next)
        visible = next
        return change
    }

    fun clear() { visible = emptyList() }
}

/** 只在不可变日志快照或筛选条件变化时刷新悬浮控制台。 */
internal class LogSnapshotGate<T> {
    private var initialized = false
    private var snapshot: List<T>? = null
    private var filter: Char? = null

    fun shouldRefresh(next: List<T>, nextFilter: Char?): Boolean {
        if (initialized && snapshot === next && filter == nextFilter) return false
        snapshot = next
        filter = nextFilter
        initialized = true
        return true
    }

    fun invalidate() {
        initialized = false
        snapshot = null
    }
}
