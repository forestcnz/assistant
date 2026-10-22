package com.forest.android.assistant.core.engine

/** 追加一条日志并只复制保留窗口；不会改变每条日志即时发布的时机。 */
internal class BoundedLogSnapshot<T>(private val limit: Int) {
    init { require(limit > 0) }

    fun append(current: List<T>, item: T): List<T> {
        val retained = minOf(current.size, limit - 1)
        val start = current.size - retained
        return buildList(retained + 1) {
            for (index in start until current.size) add(current[index])
            add(item)
        }
    }
}
