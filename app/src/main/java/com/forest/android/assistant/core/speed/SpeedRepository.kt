package com.forest.android.assistant.core.speed

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile
import javax.security.auth.x500.X500Principal

data class SpeedApp(val packageName: String, val label: String, val version: String,
                    val installed: Boolean, val system: Boolean, val patched: Boolean, val prepared: Boolean)
data class SpeedStatus(val factor: Float, val nativeEnabled: Boolean, val loaded: Boolean,
                       val javaReady: Boolean, val nativeCalls: Long, val error: String)

class SpeedRepository(context: Context) {
    private val context = context.applicationContext
    private val pm = context.packageManager
    private val root = File(context.filesDir, "app-speed").apply { mkdirs() }
    private val indexes = File(root, "records").apply { mkdirs() }
    private val preferences = context.getSharedPreferences("app-speed", Context.MODE_PRIVATE)

    fun listApps(): List<SpeedApp> {
        val packages = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.filter { it != context.packageName }.toSet()
        val apps = packages.mapNotNull { app(it) }.associateBy { it.packageName }.toMutableMap()
        indexes.listFiles { file -> file.extension == "json" }?.forEach { index ->
            runCatching {
                val record = JSONObject(index.readText())
                val id = record.getString("packageName")
                if (!apps.containsKey(id)) apps[id] = app(id) ?: SpeedApp(id, record.getString("label"),
                    record.getString("version"), false, false, false, true)
            }
        }
        return apps.values.sortedWith(compareBy<SpeedApp> { !it.prepared }.thenBy { it.label.lowercase(Locale.ROOT) })
    }
    fun app(id: String): SpeedApp? = runCatching {
        val info = pm.getPackageInfo(id, PackageManager.GET_META_DATA)
        val application = requireNotNull(info.applicationInfo)
        SpeedApp(id, application.loadLabel(pm).toString(), info.versionName.orEmpty(), true,
            application.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            application.metaData?.getInt(BinaryManifest.PATCH_MARKER, 0) == 1,
            record(id) != null)
    }.getOrNull()

    private fun index(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))) { "应用包名不合法" }
        return File(indexes, "$id.json")
    }
    private fun record(id: String): JSONObject? = runCatching { JSONObject(index(id).readText()) }.getOrNull()
    private fun recordDirectory(record: JSONObject): File {
        val directory = File(root, record.getString("directory")).canonicalFile
        require(directory.parentFile == root.canonicalFile) { "安装包备份路径异常" }
        return directory
    }
    fun artifacts(id: String, original: Boolean = false): List<File> {
        val record = record(id) ?: error("请先生成变速安装包")
        val directory = File(recordDirectory(record), if (original) "original" else "patched")
        val names = record.getJSONArray("files")
        return (0 until names.length()).map { i ->
            val name = names.getString(i)
            require(name.matches(Regex("(?:base|split_\\d+)\\.apk")))
            File(directory, name).also { require(it.isFile) { "安装包文件丢失，请重新生成" } }
        }
    }
    fun initialFactor(id: String) = preferences.getFloat("factor:$id", 2f)
    fun initialNative(id: String) = preferences.getBoolean("native:$id", true)
    fun saveSelection(id: String, factor: Float, nativeEnabled: Boolean) {
        require(factor.isFinite() && factor in .25f..10f)
        preferences.edit().putFloat("factor:$id", factor).putBoolean("native:$id", nativeEnabled).apply()
    }

    fun prepare(id: String, factor: Float, nativeEnabled: Boolean, progress: (String) -> Unit) {
        require(factor.isFinite() && factor in .25f..10f)
        val installed = pm.getPackageInfo(id, PackageManager.GET_META_DATA)
        val application = requireNotNull(installed.applicationInfo)
        require(application.flags and ApplicationInfo.FLAG_SYSTEM == 0) { "系统应用不能通过补丁重装，请选择自行安装的应用" }
        val existing = record(id)
        val alreadyPatched = application.metaData?.getInt(BinaryManifest.PATCH_MARKER, 0) == 1
        val sourceFiles = if (alreadyPatched) {
            require(existing != null) { "此应用已经带有变速补丁，未找到原版备份；请先安装原版再生成" }
            artifacts(id, original = true)
        } else listOf(File(application.sourceDir)) + application.splitSourceDirs.orEmpty().map(::File)
        val payloadAbis = context.assets.list("speed-runtime/gadget").orEmpty().map { it.removeSuffix(".so") }
        require(payloadAbis.isNotEmpty()) { "安装包缺少变速模块，请重新编译助手" }
        val nativeAbis = sourceFiles.flatMap { file -> ZipFile(file).use { zip -> zip.entries().toList()
            .mapNotNull { Regex("lib/([^/]+)/.+\\.so").matchEntire(it.name)?.groupValues?.get(1) } } }.toSet()
        val abis = if (nativeAbis.isEmpty()) payloadAbis else payloadAbis.filter { it in nativeAbis }
        require(abis.isNotEmpty()) { "目标应用的处理器架构暂不支持变速" }
        val payloadBytes = abis.sumOf { context.assets.open("speed-runtime/gadget/$it.so").use { stream -> stream.available().toLong() } }
        require(StatFs(root.path).availableBytes > sourceFiles.sumOf { it.length() } * 3 + payloadBytes * 2 + 32L * 1024 * 1024) { "可用存储空间不足，请释放空间后重试" }
        val directory = File(root, "$id-${System.currentTimeMillis()}").apply { check(mkdirs()) }
        val original = File(directory, "original").apply { mkdirs() }
        val patched = File(directory, "patched").apply { mkdirs() }
        var committed = false
        try {
            progress("正在备份原版安装包…")
            val names = sourceFiles.mapIndexed { i, source ->
                val name = if (i == 0) "base.apk" else "split_$i.apk"
                source.copyTo(File(original, name)); name
            }
            val hostInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val certificate = requireNotNull(hostInfo.signingInfo).apkContentsSigners.first().toByteArray()
            val config = JSONObject().put("version", 1).put("hostPackage", context.packageName)
                .put("hostCertificate", sha256(certificate)).put("factor", factor).put("nativeEnabled", nativeEnabled)
                .toString().toByteArray(Charsets.UTF_8)
            val base = File(original, "base.apk")
            val dex = ZipFile(base).use { ApkRewriter.nextDexName(it.entries().toList().map { entry -> entry.name }) }
            val additions = linkedMapOf<String, () -> InputStream>(
                dex to { context.assets.open("speed-runtime/runtime.dex") },
                "assets/forest-speed/config.json" to { config.inputStream() },
                "assets/forest-speed/hook.js" to { context.assets.open("speed-runtime/hook.js") },
                "assets/forest-speed/THIRD_PARTY_NOTICES.md" to { context.assets.open("speed-runtime/THIRD_PARTY_NOTICES.md") },
            )
            abis.forEach { abi -> additions["assets/forest-speed/gadget/$abi.so"] = { context.assets.open("speed-runtime/gadget/$abi.so") } }
            context.assets.list("speed-runtime/licenses").orEmpty().forEach { name ->
                additions["assets/forest-speed/licenses/$name"] = { context.assets.open("speed-runtime/licenses/$name") }
            }
            val signer = patchSigner()
            for ((i, name) in names.withIndex()) {
                progress("正在处理安装包 ${i + 1}/${names.size}…")
                val unsigned = File(directory, "unsigned.apk")
                ApkRewriter.rewrite(File(original, name), unsigned, if (i == 0) additions else emptyMap(),
                    if (i == 0) { bytes -> BinaryManifest.patch(bytes, id, context.packageName) } else null)
                progress("正在签名并检查安装包 ${i + 1}/${names.size}…")
                val output = File(patched, name)
                ApkSigner.Builder(listOf(signer)).setInputApk(unsigned).setOutputApk(output)
                    .setMinSdkVersion(29).setV1SigningEnabled(false).setV2SigningEnabled(true)
                    .setV3SigningEnabled(true).setV4SigningEnabled(false).build().sign()
                val verification = ApkVerifier.Builder(output).setMinCheckedPlatformVersion(29).build().verify()
                check(verification.isVerified) { "补丁签名验证失败：${verification.errors.joinToString()}" }
                check(unsigned.delete())
            }
            val result = JSONObject().put("packageName", id).put("label", application.loadLabel(pm).toString())
                .put("version", installed.versionName.orEmpty()).put("versionCode", installed.longVersionCode)
                .put("directory", directory.name).put("files", JSONArray(names))
            val atomic = AtomicFile(index(id)); val output = atomic.startWrite()
            try { output.write(result.toString().toByteArray()); atomic.finishWrite(output) }
            catch (error: Throwable) { atomic.failWrite(output); throw error }
            committed = true
            saveSelection(id, factor, nativeEnabled)
            progress("安装包已生成，原版已备份")
        } finally { if (!committed) directory.deleteRecursively() }
    }

    private fun patchSigner(): ApkSigner.SignerConfig {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "forest-speed-patch-v1"
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048).setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setCertificateSubject(X500Principal("CN=Forest App Speed Patch"))
                    .setCertificateSerialNumber(BigInteger.ONE).setCertificateNotAfter(Date(4_102_444_800_000L)).build())
                generateKeyPair()
            }
        }
        return ApkSigner.SignerConfig.Builder("forest-speed", store.getKey(alias, null) as PrivateKey,
            listOf(store.getCertificate(alias) as X509Certificate)).build()
    }
    fun status(id: String): SpeedStatus = call(id, "status", null)
    fun setSpeed(id: String, factor: Float, nativeEnabled: Boolean): SpeedStatus {
        require(factor.isFinite() && factor in .25f..10f)
        val result = call(id, "set_speed", Bundle().apply { putFloat("factor", factor); putBoolean("nativeEnabled", nativeEnabled) })
        if (result.error.isEmpty()) saveSelection(id, factor, nativeEnabled)
        return result
    }
    private fun call(id: String, method: String, extras: Bundle?): SpeedStatus {
        val result = context.contentResolver.call(Uri.parse("content://$id.forest.speed"), method, null, extras)
            ?: error("变速模块未返回状态，请确认已安装变速版")
        return SpeedStatus(result.getFloat("factor", 1f), result.getBoolean("nativeEnabled", true),
            result.getBoolean("loaded"), result.getBoolean("javaReady"), result.getLong("nativeCalls"), result.getString("error").orEmpty())
    }
    companion object {
        internal fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
