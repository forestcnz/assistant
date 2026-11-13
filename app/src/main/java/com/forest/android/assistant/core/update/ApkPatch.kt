package com.forest.android.assistant.core.update

import com.github.sisong.HPatch
import java.io.File
import java.io.IOException
import java.io.InputStream

internal object ApkPatch {
    fun apply(base: File, patch: File, target: File, targetSize: Long) {
        validateHeader(patch, base.length(), targetSize)
        val result = try {
            HPatch.patch(base.path, patch.path, target.path, 4L * 1024 * 1024, 1, true)
        } catch (error: LinkageError) {
            throw IOException("当前设备无法加载增量更新组件", error)
        }
        if (result != 0) throw IOException("安装包合成失败 ($result)")
    }

    // HDIFFSF20 头部按大端 7-bit 变长整数编码。先限制输出和工作内存，再交给 JNI。
    internal fun validateHeader(patch: File, baseSize: Long, targetSize: Long) {
        patch.inputStream().buffered().use { input ->
            require(header.all { input.read() == it.toInt() }) { "增量包格式不受支持" }
            require(input.readSize() == targetSize && input.readSize() == baseSize) { "增量包大小不符" }
            input.readSize() // cover count
            require(input.readSize() <= 4L * 1024 * 1024) { "增量包工作内存超限" }
        }
    }

    private fun InputStream.readSize(): Long {
        var size = 0L
        repeat(9) {
            val byte = read()
            require(byte >= 0) { "增量包头部不完整" }
            size = (size shl 7) or (byte and 0x7f).toLong()
            if (byte and 0x80 == 0) return size
        }
        throw IllegalArgumentException("增量包头部无效")
    }

    private val header = "HDIFFSF20&zstd\u0000".encodeToByteArray()
}
