package com.forest.android.assistant.core.image

/** 灰度、统计量和金字塔共用一个有内存上限的 LRU；不持有 Bitmap 或脚本会话。 */
internal class TemplateCache(private val maxBytes: Long = 16L * 1024 * 1024) {
    private data class Entry(val generation: Int, val template: TemplateMatcher.PreparedTemplate)
    private val entries = LinkedHashMap<Int, Entry>(16, 0.75f, true)
    private var bytes = 0L

    @Synchronized
    fun getOrPut(id: Int, generation: Int, create: () -> TemplateMatcher.PreparedTemplate): TemplateMatcher.PreparedTemplate {
        entries[id]?.let { if (it.generation == generation) return it.template }
        remove(id)
        val template = create()
        if (template.reservedBytes > maxBytes) return template
        while (bytes + template.reservedBytes > maxBytes && entries.isNotEmpty()) {
            remove(entries.entries.first().key)
        }
        entries[id] = Entry(generation, template)
        bytes += template.reservedBytes
        return template
    }

    @Synchronized
    fun remove(id: Int) {
        entries.remove(id)?.let { bytes -= it.template.reservedBytes }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        bytes = 0
    }
}
