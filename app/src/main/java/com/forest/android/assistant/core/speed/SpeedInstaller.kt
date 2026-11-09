package com.forest.android.assistant.core.speed

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.forest.android.assistant.AppSpeedActivity
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow

object SpeedInstaller {
    val changes = MutableStateFlow(0L)
    fun install(context: Context, packageName: String, apks: List<File>) {
        require(apks.isNotEmpty())
        check(context.packageManager.canRequestPackageInstalls()) { "请先允许雕雕助手安装应用" }
        val installer = context.packageManager.packageInstaller
        val parameters = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setSize(apks.sumOf { it.length() })
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(parameters)
        try {
            installer.openSession(id).use { session ->
                apks.forEach { apk ->
                    session.openWrite(apk.name, 0, apk.length()).use { output ->
                        apk.inputStream().use { it.copyTo(output) }; session.fsync(output)
                    }
                }
                val callback = PendingIntent.getBroadcast(context, id,
                    Intent(context, SpeedInstallReceiver::class.java).putExtra("targetPackage", packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(callback.intentSender)
            }
        } catch (error: Throwable) { installer.abandonSession(id); throw error }
    }
    fun lastMessage(context: Context, id: String) = context.getSharedPreferences("speed-install-results", Context.MODE_PRIVATE).getString(id, "").orEmpty()
}

class SpeedInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("targetPackage") ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            try {
                context.startActivity(Intent(context, AppSpeedActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("installerConfirmation", confirmation))
            } catch (error: Exception) { save(context, id, "请返回应用变速页面重试安装：${error.message}") }
        } else {
            val message = when (status) {
                PackageInstaller.STATUS_SUCCESS -> "安装完成"
                PackageInstaller.STATUS_FAILURE_ABORTED -> "已取消安装，生成的安装包仍保留"
                PackageInstaller.STATUS_FAILURE_CONFLICT -> "安装签名冲突，请先备份应用数据并卸载当前版本，再安装"
                PackageInstaller.STATUS_FAILURE_STORAGE -> "安装空间不足，请释放存储空间后重试"
                else -> "安装未完成：${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()}"
            }
            save(context, id, message)
        }
    }
    private fun save(context: Context, id: String, message: String) {
        context.getSharedPreferences("speed-install-results", Context.MODE_PRIVATE).edit().putString(id, message).apply()
        SpeedInstaller.changes.value = System.nanoTime()
    }
}
