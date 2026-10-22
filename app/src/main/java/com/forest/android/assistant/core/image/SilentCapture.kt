package com.forest.android.assistant.core.image

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.util.Log
import com.forest.android.assistant.core.shell.RootShell
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

/**
 * 静默截屏通道（增强，可选）：
 * - **Root**：`su -c screencap -p <file>`，无需任何授权弹窗；
 * - **Shizuku**：通过 Shizuku 以 shell 身份执行 `screencap`，同样免授权弹窗。
 *
 * 两者都不可用时由 [ScreenCapture] 降级到 MediaProjection（需用户授权）。
 * 对应设计 docs/README.md 第 3 章「增强」权限级别。
 */
object SilentCapture {

    private const val TAG = "ScriptJs"
    private const val TIMEOUT_SECONDS = 6L
    private const val PROBE_TIMEOUT_SECONDS = 2L

    /** Root 是否可用（复用 [RootShell] 的探测缓存） */
    fun rootAvailable(): Boolean = RootShell.available()

    /** Shizuku 是否可用（服务在运行且已授予权限） */
    fun shizukuAvailable(): Boolean = try {
        val binder = Shizuku.pingBinder()
        val granted = binder &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "Shizuku 探测: binder=$binder granted=$granted")
        granted
    } catch (e: Throwable) {
        Log.i(TAG, "Shizuku 探测: 不可用（${e.message}）")
        false
    }

    /** Shizuku 服务是否已运行（binder 存活；权限可能尚未授予） */
    fun shizukuBinderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    /** 申请 Shizuku 权限（需在有权请求时调用，通常由界面触发） */
    fun requestShizukuPermission(requestCode: Int): Boolean = try {
        if (!rikka.shizuku.Shizuku.pingBinder()) {
            false
        } else if (rikka.shizuku.Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            true
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            // 用户拒绝过：需在 Shizuku 应用内重新授权
            false
        } else {
            Shizuku.requestPermission(requestCode)
            true
        }
    } catch (e: Throwable) {
        Log.w(TAG, "请求 Shizuku 权限失败: ${e.message}")
        false
    }

    /** Root 通道截屏 */
    fun captureViaRoot(context: Context): Bitmap? {
        val file = tempFile(context)
        return screencap(arrayOf("su", "-c", "screencap -p ${file.absolutePath}"), file, "root")
    }

    /**
     * Shizuku 通道截屏：绑定 UserService（shell 身份）执行 screencap。
     * **串行化**：绑定/解绑共享同一个 UserService 参数与连接引用，且临时文件路径固定，
     * 并发调用会互相抢连接、覆盖截图（实测会出现「一边失败一边成功」）。
     */
    @Synchronized
    fun captureViaShizuku(context: Context): Bitmap? {
        val file = tempFile(context)
        return try {
            val service = bindShellService(context) ?: run {
                Log.w(TAG, "Shizuku UserService 绑定失败")
                // 绑定超时/失败时也要解绑并移除服务记录，否则 Shizuku 侧会留下一个常驻进程
                try {
                    Shizuku.unbindUserService(userServiceArgs(context), connection, true)
                } catch (_: Throwable) {
                }
                file.delete()
                return null
            }
            val ok = try {
                service.screencap(file.absolutePath)
            } finally {
                // 先让 UserService 进程自行退出，再解绑。
                // 实测仅 unbind(remove=true) 不会销毁该进程，会按次累积残留 root/shell 进程。
                try {
                    service.destroy()
                } catch (_: Throwable) {
                }
                try {
                    Shizuku.unbindUserService(userServiceArgs(context), connection, true)
                } catch (_: Throwable) {
                }
            }
            if (!ok || !file.isFile || file.length() == 0L) {
                file.delete()
                null
            } else {
                BitmapFactory.decodeFile(file.absolutePath).also {
                    Log.i(TAG, "shizuku 静默截屏成功: ${it?.width}x${it?.height}")
                }.also { file.delete() }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Shizuku 截屏失败: ${e.message}")
            file.delete()
            null
        }
    }

    /** 绑定 Shizuku UserService，等待连接建立 */
    private fun bindShellService(context: Context): IShellService? {
        val latch = CountDownLatch(1)
        val holder = java.util.concurrent.atomic.AtomicReference<IShellService?>(null)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                holder.set(IShellService.Stub.asInterface(binder))
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                latch.countDown()
            }
        }
        connection = conn
        Shizuku.bindUserService(userServiceArgs(context), conn)
        latch.await(5, TimeUnit.SECONDS)
        return holder.get()
    }

    /**
     * UserService 参数：**必须缓存同一个实例**。
     * Shizuku 以参数对象区分服务记录，绑定时新建、解绑时再新建会导致
     * unbind 匹配不上 → UserService 进程不销毁（表现为 `:shizuku` 进程越积越多）。
     */
    private var cachedArgs: Shizuku.UserServiceArgs? = null

    private fun userServiceArgs(context: Context): Shizuku.UserServiceArgs =
        cachedArgs ?: Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShellUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shizuku")
            .version(1)
            .also { cachedArgs = it }

    private var connection: ServiceConnection? = null

    private fun tempFile(context: Context): File {
        val dir = File(context.cacheDir, "silent").apply { mkdirs() }
        // 文件名唯一：并发/重入时不会互相覆盖同一张截图
        return File(dir, "cap_${Thread.currentThread().id}_${System.nanoTime()}.png")
    }

    private fun screencap(command: Array<String>, file: File, channel: String): Bitmap? = try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        awaitAndDecode(process, file, channel)
    } catch (e: Exception) {
        Log.w(TAG, "$channel 截屏失败: ${e.message}")
        file.delete()
        null
    }

    private fun awaitAndDecode(process: Process, file: File, channel: String): Bitmap? = try {
        val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            Log.w(TAG, "$channel 截屏超时")
            null
        } else if (process.exitValue() != 0) {
            Log.w(TAG, "$channel 截屏退出码 ${process.exitValue()}")
            null
        } else if (!file.isFile || file.length() == 0L) {
            Log.w(TAG, "$channel 截屏未生成文件")
            null
        } else {
            BitmapFactory.decodeFile(file.absolutePath).also {
                Log.i(TAG, "$channel 静默截屏成功: ${it?.width}x${it?.height}")
            }
        }
    } finally {
        if (file.exists()) file.delete()
    }
}
