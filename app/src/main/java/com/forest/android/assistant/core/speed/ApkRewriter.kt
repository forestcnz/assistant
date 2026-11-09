package com.forest.android.assistant.core.speed

import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32

/** Streams APKs; keeps resources aligned to 4 bytes and uncompressed native code to 16 KiB. */
internal object ApkRewriter {
    fun nextDexName(entries: List<String>): String {
        val indexes = entries.mapNotNull { Regex("classes(\\d*)\\.dex").matchEntire(it)?.groupValues?.get(1)?.let { n -> if (n.isEmpty()) 1 else n.toInt() } }
        val next = (indexes.maxOrNull() ?: 0) + 1
        return if (next == 1) "classes.dex" else "classes$next.dex"
    }
    fun isSignature(name: String): Boolean {
        val upper = name.uppercase(java.util.Locale.ROOT)
        return upper == "STAMP-CERT-SHA256" || upper.startsWith("META-INF/") &&
            (upper == "META-INF/MANIFEST.MF" || upper.substringAfterLast('/').startsWith("SIG-") ||
                listOf(".SF", ".RSA", ".DSA", ".EC").any(upper::endsWith))
    }
    fun rewrite(source: File, destination: File, additions: Map<String, () -> InputStream> = emptyMap(),
                manifest: ((ByteArray) -> ByteArray)? = null) {
        require(source.canonicalFile != destination.canonicalFile)
        ZipFile(source).use { input ->
            val entries = input.entries().toList()
            require(entries.size <= 100_000 && entries.map { it.name }.distinct().size == entries.size) { "安装包存在重复或过多文件" }
            require(additions.keys.none { name -> entries.any { it.name == name } }) { "安装包已包含变速模块，请选择原版" }
            val counter = CountingOutput(destination.outputStream().buffered())
            ZipOutputStream(counter).use { output ->
                for (original in entries) {
                    if (original.isDirectory || isSignature(original.name)) continue
                    val name = original.name
                    require(!name.startsWith('/') && name.split('/').none { it == ".." }) { "安装包文件路径异常" }
                    if (name == "AndroidManifest.xml" && manifest != null) {
                        require(original.size in 0..8_388_608) { "Manifest 文件过大" }
                        val bytes = input.getInputStream(original).use { it.readBytes() }.let(manifest)
                        output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry()
                    } else {
                        val entry = ZipEntry(name).apply { time = 315532800000L }
                        val alignment = if (name.startsWith("lib/") && name.endsWith(".so")) 16384
                            else if (name == "resources.arsc" || name.endsWith(".dex") && original.method == ZipEntry.STORED) 4 else 1
                        if (alignment > 1) {
                            entry.method = ZipEntry.STORED; entry.size = original.size; entry.compressedSize = original.size; entry.crc = original.crc
                            val base = counter.count + 30 + name.toByteArray(Charsets.UTF_8).size
                            if (base % alignment != 0L) {
                                val padding = 2 + ((alignment - (base + 6) % alignment) % alignment).toInt()
                                entry.extra = ByteArray(4 + padding).also { extra ->
                                    extra[0] = 0x35; extra[1] = 0xd9.toByte(); extra[2] = padding.toByte(); extra[3] = (padding shr 8).toByte()
                                    extra[4] = alignment.toByte(); extra[5] = (alignment shr 8).toByte()
                                }
                            }
                        }
                        output.putNextEntry(entry)
                        input.getInputStream(original).use { it.copyTo(output) }
                        output.closeEntry()
                    }
                }
                for ((name, open) in additions) {
                    val entry = ZipEntry(name).apply { time = 315532800000L }
                    if (name.matches(Regex("classes\\d*\\.dex"))) {
                        val bytes = open().use { it.readBytes() }
                        entry.method = ZipEntry.STORED; entry.size = bytes.size.toLong(); entry.compressedSize = entry.size
                        entry.crc = CRC32().apply { update(bytes) }.value
                        val base = counter.count + 30 + name.toByteArray(Charsets.UTF_8).size
                        if (base % 4 != 0L) {
                            val padding = 2 + ((4 - (base + 6) % 4) % 4).toInt()
                            entry.extra = ByteArray(4 + padding).also { it[0] = 0x35; it[1] = 0xd9.toByte(); it[2] = padding.toByte(); it[4] = 4 }
                        }
                        output.putNextEntry(entry); output.write(bytes)
                    } else { output.putNextEntry(entry); open().use { it.copyTo(output) } }
                    output.closeEntry()
                }
            }
        }
    }
    private class CountingOutput(output: OutputStream) : FilterOutputStream(output) {
        var count = 0L
        override fun write(value: Int) { out.write(value); count++ }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes, offset, length); count += length }
    }
}
