package com.forest.android.assistant.core.pack

import android.content.Context
import android.util.Log
import com.android.apksig.ApkSigner
import com.forest.android.assistant.core.task.TaskManager
import java.io.File
import java.io.FileOutputStream
import java.security.Security
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 任务打包：把任务（入口脚本 + 配置 + 素材）注入到**本应用 APK 作为运行时模板**的
 * `assets/task/` 下，再用内置调试证书重新签名，导出可独立安装的 APK。
 *
 * 实现要点：
 * - 运行时（QuickJS/MLKit/无障碍/悬浮窗等）直接复用已安装 APK，无需额外准备模板；
 * - 必须移除原有 v1 签名文件并重新签名（v1+v2），否则安装会失败；
 * - 打包后的 APK 启动时会识别内嵌任务（见 [EmbeddedTask]）并自动运行。
 *
 * 对应设计 docs/README.md 第 4.2 节「导出独立 APK」。
 */
object ApkPackager {

    private const val TAG = "ScriptJs"
    private const val KEYSTORE_ASSET = "pack/debug.p12"
    private const val KEYSTORE_PASS = "android"
    private const val KEY_ALIAS = "androiddebugkey"

    data class Result(val apk: File, val sizeBytes: Long)

    /** 打包任务；失败返回 null。onLog 用于向界面回传进度 */
    fun packageTask(context: Context, taskName: String, onLog: (String) -> Unit): Result? {
        val source = File(context.applicationInfo.sourceDir)
        if (!source.isFile) {
            onLog("找不到运行时模板 APK: ${source.absolutePath}")
            return null
        }
        val scriptFile = TaskManager.scriptFile(context, taskName)
        if (!scriptFile.isFile) {
            onLog("任务脚本不存在: ${scriptFile.absolutePath}")
            return null
        }

        val outDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "apk").apply { mkdirs() }
        val unsigned = File(context.cacheDir, "pack_unsigned.apk")
        val signed = File(outDir, "$taskName.apk")

        return try {
            onLog("① 读取运行时模板 (${source.length() / 1024 / 1024}MB)")
            copyWithTask(context, source, unsigned, taskName, onLog)
            onLog("② 注入任务完成，开始签名")
            sign(context, unsigned, signed)
            unsigned.delete()
            onLog("③ 导出成功: ${signed.absolutePath} (${signed.length() / 1024 / 1024}MB)")
            Result(signed, signed.length())
        } catch (e: Exception) {
            Log.w(TAG, "打包失败: ${e.message}")
            onLog("打包失败: ${e.message}")
            unsigned.delete()
            signed.delete()
            null
        }
    }

    /** 记录写入位置的输出流：用于计算 zip 条目数据偏移，实现对齐补白 */
    private class CountingOutputStream(private val delegate: java.io.OutputStream) : java.io.OutputStream() {
        var position = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            position++
        }

        override fun write(b: ByteArray) {
            delegate.write(b)
            position += b.size
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            position += len
        }

        override fun flush() = delegate.flush()

        override fun close() = delegate.close()
    }

    /** 复制原 APK 并注入任务文件；顺带剔除旧的签名文件 */
    private fun copyWithTask(
        context: Context,
        source: File,
        target: File,
        taskName: String,
        onLog: (String) -> Unit
    ) {
        ZipFile(source).use { zip ->
            val counter = CountingOutputStream(FileOutputStream(target))
            ZipOutputStream(counter).use { out ->
                val entries = zip.entries()
                var copied = 0
                var taskFiles = 0
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    // 旧签名文件必须剔除，否则重签后安装校验失败
                    if (name.startsWith("META-INF/") &&
                        (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA") ||
                                name.endsWith(".EC") || name == "META-INF/MANIFEST.MF")
                    ) {
                        continue
                    }
                    // 模板里可能已有内嵌任务（避免重复打包时叠加）
                    if (name.startsWith("assets/task/")) {
                        taskFiles++
                        continue
                    }
                    val newEntry = ZipEntry(name)
                    if (entry.method == ZipEntry.STORED) {
                        // targetSdk 30+ 要求 resources.arsc 未压缩且 4 字节对齐，
                        // 因此保留 STORED 方式并通过 extra 字段补齐偏移
                        newEntry.method = ZipEntry.STORED
                        newEntry.size = entry.size
                        newEntry.compressedSize = entry.size
                        newEntry.crc = entry.crc
                        val nameLen = name.toByteArray(Charsets.UTF_8).size
                        val base = counter.position + 30 + nameLen
                        val pad = ((4 - (base % 4)) % 4).toInt()
                        newEntry.extra = ByteArray(4 + pad)
                    }
                    out.putNextEntry(newEntry)
                    zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                    copied++
                }
                if (taskFiles > 0) onLog("   已清理模板内的旧内嵌任务 ($taskFiles 个文件)")

                // 注入任务：task.js + config.json + images/**
                addFile(out, "assets/task/task.js", TaskManager.scriptFile(context, taskName))
                val config = File(TaskManager.taskDir(context, taskName), "config.json")
                if (config.isFile) addFile(out, "assets/task/config.json", config)
                val images = TaskManager.listImages(context, taskName)
                images.forEach { img ->
                    addFile(out, "assets/task/images/${img.name}", img)
                }
                onLog("   复制 $copied 个运行时文件，注入 1 个脚本 + ${images.size} 个素材")
            }
        }
    }

    private fun addFile(out: ZipOutputStream, entryName: String, file: File) {
        out.putNextEntry(ZipEntry(entryName))
        file.inputStream().use { it.copyTo(out) }
        out.closeEntry()
    }

    /** 使用内置调试证书签名（v1 + v2） */
    private fun sign(context: Context, unsigned: File, signed: File) {
        val ksFile = File(context.cacheDir, "pack.keystore").apply {
            context.assets.open(KEYSTORE_ASSET).use { input -> outputStream().use { input.copyTo(it) } }
        }
        val currentBc = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (currentBc == null || currentBc.javaClass.name.startsWith("com.android.org.bouncycastle")) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.addProvider(BouncyCastleProvider())
        }
        // Android 裁剪版 JCE 无法还原内置 PKCS12 私钥；完整 BouncyCastle 提供该 KeyStore 和密钥翻译
        val keyStore = KeyStore.getInstance("PKCS12", BouncyCastleProvider.PROVIDER_NAME).apply {
            ksFile.inputStream().use { load(it, KEYSTORE_PASS.toCharArray()) }
        }
        val key = keyStore.getKey(KEY_ALIAS, KEYSTORE_PASS.toCharArray()) as PrivateKey
        val cert = keyStore.getCertificate(KEY_ALIAS) as X509Certificate

        val signer = ApkSigner.Builder(
            listOf(ApkSigner.SignerConfig.Builder(KEY_ALIAS, key, listOf(cert)).build())
        )
            .setInputApk(unsigned)
            .setOutputApk(signed)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .build()
        signer.sign()
        ksFile.delete()
    }
}
