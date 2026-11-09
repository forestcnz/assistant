package com.forest.android.assistant.core.speed

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Adds our provider without decoding/recompiling resources or changing existing indexes. */
internal object BinaryManifest {
    const val PROVIDER = "com.forest.android.speed.SpeedInitProvider"
    const val PATCH_MARKER = "forest.speed.patch.version"
    private const val ANDROID = "http://schemas.android.com/apk/res/android"
    private fun ByteArray.int(at: Int) = ByteBuffer.wrap(this, at, 4).order(ByteOrder.LITTLE_ENDIAN).int
    private fun ByteArray.short(at: Int) = ByteBuffer.wrap(this, at, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 65535
    private fun ByteArray.put(at: Int, value: Int) { ByteBuffer.wrap(this, at, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value) }
    private fun ByteArray.putShort(at: Int, value: Int) { ByteBuffer.wrap(this, at, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()) }

    private class Pool(private val original: ByteArray, resourceMap: ByteArray?) {
        val strings = mutableListOf<String>()
        private val offsets = mutableListOf<Int>()
        val ids = mutableListOf<Int>()
        private val header = original.short(2)
        private val count = original.int(8)
        private val styles = original.int(12)
        private val utf8 = original.int(16) and 256 != 0
        private val start = original.int(20)
        private val styleStart = original.int(24)
        init {
            require(header >= 28 && count in 0..1_000_000 && styles in 0..count) { "无效的 Manifest 字符串池" }
            require(start in header..original.size && header.toLong() + (count.toLong() + styles) * 4 <= start)
            repeat(count) { index ->
                val offset = original.int(header + index * 4)
                offsets += offset
                var position = start + offset
                fun length8(): Int { val n = original[position++].toInt() and 255; return if (n and 128 == 0) n else ((n and 127) shl 8) or (original[position++].toInt() and 255) }
                fun length16(): Int { val n = original.short(position); position += 2; return if (n and 32768 == 0) n else ((n and 32767) shl 16) or original.short(position).also { position += 2 } }
                val length = if (utf8) { length8(); length8() } else length16() * 2
                require(length >= 0 && position.toLong() + length <= original.size)
                strings += String(original, position, length, if (utf8) Charsets.UTF_8 else Charsets.UTF_16LE)
            }
            val mapSize = resourceMap?.let { (it.size - it.short(2)) / 4 } ?: 0
            repeat(count) { ids += if (it < mapSize) resourceMap!!.int(resourceMap.short(2) + it * 4) else 0 }
        }
        fun string(value: String): Int = strings.indexOf(value).takeIf { it >= 0 } ?: strings.size.also { strings += value; ids += 0 }
        fun attribute(value: String, id: Int): Int {
            val existing = strings.indices.firstOrNull { strings[it] == value && (ids[it] == id || ids[it] == 0) }
            return (existing ?: strings.size.also { strings += value; ids += 0 }).also { ids[it] = id }
        }
        fun resourceMap(): ByteArray {
            val count = ids.indexOfLast { it != 0 } + 1
            return ByteArray(8 + count * 4).also { bytes ->
                bytes.putShort(0, 0x180); bytes.putShort(2, 8); bytes.put(4, bytes.size)
                repeat(count) { bytes.put(8 + it * 4, ids[it]) }
            }
        }
        fun encode(): ByteArray {
            val dataEnd = if (styleStart == 0) original.size else styleStart
            require(dataEnd in start..original.size)
            val data = ByteArrayOutputStream().apply { write(original, start, dataEnd - start) }
            fun length8(value: Int) { require(value <= 32767); if (value > 127) data.write((value shr 8) or 128); data.write(value and 255) }
            fun length16(value: Int) { if (value > 32767) { data.write((value shr 16) and 255); data.write(((value shr 24) or 128) and 255) }; data.write(value and 255); data.write((value shr 8) and 255) }
            for (index in count until strings.size) {
                offsets += data.size()
                val text = strings[index]
                if (utf8) {
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    length8(text.length); length8(bytes.size); data.write(bytes); data.write(0)
                } else { length16(text.length); data.write(text.toByteArray(Charsets.UTF_16LE)); data.write(0); data.write(0) }
            }
            while (data.size() % 4 != 0) data.write(0)
            val stringStart = header + (strings.size + styles) * 4
            val newStyleStart = if (styleStart == 0) 0 else stringStart + data.size()
            val styleBytes = if (styleStart == 0) byteArrayOf() else original.copyOfRange(styleStart, original.size)
            return ByteArray(stringStart + data.size() + styleBytes.size).also { out ->
                original.copyInto(out, 0, 0, header)
                out.put(4, out.size); out.put(8, strings.size); out.put(16, original.int(16) and 1.inv())
                out.put(20, stringStart); out.put(24, newStyleStart)
                offsets.forEachIndexed { index, offset -> out.put(header + index * 4, offset) }
                original.copyInto(out, header + strings.size * 4, header + count * 4, header + (count + styles) * 4)
                data.toByteArray().copyInto(out, stringStart)
                if (styleBytes.isNotEmpty()) styleBytes.copyInto(out, newStyleStart)
            }
        }
    }

    fun patch(input: ByteArray, packageName: String, hostPackage: String): ByteArray {
        require(input.size in 8..8_388_608 && input.short(0) == 3 && input.short(2) == 8 && input.int(4) == input.size) { "无法识别 AndroidManifest.xml" }
        val chunks = mutableListOf<ByteArray>()
        var position = 8
        while (position < input.size) {
            require(position + 8 <= input.size)
            val size = input.int(position + 4)
            require(size >= 8 && position.toLong() + size <= input.size) { "Manifest 区块损坏" }
            chunks += input.copyOfRange(position, position + size); position += size
        }
        val poolChunk = chunks.single { it.short(0) == 1 }
        val pool = Pool(poolChunk, chunks.firstOrNull { it.short(0) == 0x180 })
        val ns = pool.string(ANDROID)
        val androidPrefix = pool.string("android")
        val name = pool.attribute("name", 0x01010003)
        val exported = pool.attribute("exported", 0x01010010)
        val permission = pool.attribute("permission", 0x01010006)
        val authorities = pool.attribute("authorities", 0x01010018)
        val initOrder = pool.attribute("initOrder", 0x0101001a)
        val value = pool.attribute("value", 0x01010024)
        data class Attribute(val name: Int, val type: Int, val data: Int)
        fun stringAttribute(key: Int, text: String) = Attribute(key, 3, pool.string(text))
        fun start(tag: String, attributes: List<Attribute> = emptyList()): ByteArray = ByteArray(36 + attributes.size * 20).also { out ->
            out.putShort(0, 0x102); out.putShort(2, 16); out.put(4, out.size); out.put(12, -1)
            out.put(16, -1); out.put(20, pool.string(tag)); out.putShort(24, 20); out.putShort(26, 20); out.putShort(28, attributes.size)
            // Compiled Android attributes are ordered by their resource IDs.
            attributes.sortedBy { pool.ids[it.name] }.forEachIndexed { index, attr ->
                val at = 36 + index * 20
                out.put(at, ns); out.put(at + 4, attr.name); out.put(at + 8, if (attr.type == 3) attr.data else -1)
                out.putShort(at + 12, 8); out[at + 15] = attr.type.toByte(); out.put(at + 16, attr.data)
            }
        }
        fun end(tag: String) = ByteArray(24).also { out ->
            out.putShort(0, 0x103); out.putShort(2, 16); out.put(4, 24); out.put(12, -1); out.put(16, -1); out.put(20, pool.string(tag))
        }
        fun namespace(type: Int) = ByteArray(24).also { out ->
            out.putShort(0, type); out.putShort(2, 16); out.put(4, 24); out.put(12, -1); out.put(16, androidPrefix); out.put(20, ns)
        }
        val provider = listOf(start("provider", listOf(stringAttribute(name, PROVIDER),
            stringAttribute(permission, ""),
            stringAttribute(authorities, "$packageName.forest.speed"), Attribute(exported, 0x12, -1),
            Attribute(initOrder, 0x10, 2147483646))), end("provider"),
            start("meta-data", listOf(stringAttribute(name, PATCH_MARKER), Attribute(value, 0x10, 1))), end("meta-data"))
        val query = listOf(start("package", listOf(stringAttribute(name, hostPackage))), end("package"))
        val hasQueries = chunks.any { it.short(0) == 0x102 && pool.strings[it.int(20)] == "queries" }
        val hasNamespace = chunks.any { it.short(0) == 0x100 && pool.strings[it.int(20)] == ANDROID }
        val output = mutableListOf<ByteArray>()
        if (!hasNamespace) output += namespace(0x100)
        var injected = false
        for (chunk in chunks) {
            val type = chunk.short(0)
            if (type == 1 || type == 0x180) continue
            val tag = if (type == 0x102 || type == 0x103) pool.strings[chunk.int(20)] else ""
            if (type == 0x102 && tag == "application") {
                if (!hasQueries) { output += start("queries"); output += query; output += end("queries") }
                val attributeSize = chunk.short(26)
                repeat(chunk.short(28)) { index ->
                    val at = 16 + chunk.short(24) + index * attributeSize
                    val id = pool.ids.getOrElse(chunk.int(at + 4)) { 0 }
                    if (id == 0x0101000c) { chunk.put(at + 8, -1); chunk[at + 15] = 0x12; chunk.put(at + 16, -1) }
                }
            }
            if (type == 0x103 && tag == "queries") output += query
            if (type == 0x103 && tag == "application") { require(!injected); output += provider; injected = true }
            output += chunk
        }
        require(injected) { "安装包缺少 application 节点" }
        if (!hasNamespace) output += namespace(0x101)
        val body = listOf(pool.encode(), pool.resourceMap()) + output
        return ByteArrayOutputStream().apply {
            write(ByteArray(8).also { it.putShort(0, 3); it.putShort(2, 8); it.put(4, 8 + body.sumOf { chunk -> chunk.size }) })
            body.forEach { write(it) }
        }.toByteArray()
    }
}
