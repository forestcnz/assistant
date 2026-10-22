package com.forest.android.assistant.core.image

import android.util.Log

/**
 * Shizuku UserService：由 Shizuku 以 **shell 身份** 在独立进程中运行，
 * 因此可以直接执行 `screencap` 等命令，实现免授权的静默截屏。
 *
 * 注意（Shizuku 13 起）：UserService **不是** Android Service，也不需要清单声明。
 * Shizuku 的 starter 会反射实例化本类，并要求实例本身是 [android.os.IBinder]，
 * 因此必须直接继承 AIDL 生成的 [IShellService.Stub]（Stub 即 Binder）。
 * 早期写成 `Service` 子类会报 `ClassCastException: ... cannot be cast to android.os.IBinder`。
 *
 * 由 [SilentCapture.captureViaShizuku] 绑定并调用。
 */
class ShellUserService : IShellService.Stub() {

    override fun screencap(path: String): Boolean = try {
        val process = Runtime.getRuntime().exec(arrayOf("screencap", "-p", path))
        val finished = process.waitFor()
        val ok = finished == 0
        if (!ok) Log.w(TAG, "screencap 退出码: $finished")
        ok
    } catch (e: Exception) {
        Log.w(TAG, "screencap 执行失败: ${e.message}")
        false
    }

    /** Shizuku 结束 UserService 时调用：让本进程退出（daemon=false 时由 Shizuku 请求销毁） */
    override fun destroy() {
        Log.i(TAG, "Shizuku UserService 收到销毁请求")
        System.exit(0)
    }

    companion object {
        private const val TAG = "ScriptJs"
    }
}
