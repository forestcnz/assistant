package com.forest.android.assistant.core.gesture

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import com.forest.android.assistant.core.engine.ScriptRunner

/**
 * 无障碍掉线守护：周期性检测手势服务连接状态，**确认真掉线**时提示并引导重开。
 *
 * 说明：Android 出于安全不允许应用自行开启无障碍服务，因此“自动拉起”实现为
 * 「自动打开无障碍设置页引导用户重开」；用户重开后 [ScriptAccessibilityService]
 * 会重新回调 onServiceConnected，坐标能力自动恢复，无需重启应用或脚本。
 *
 * 判定策略（避免误报刷屏，这是历史缺陷）：
 * - 只有**本进程内曾连接成功过**（或当前有任务在跑）才判定为掉线，避免启动瞬间误报；
 * - 断连后有 [GRACE_MS] **宽限期**：瞬时重绑（实测系统约 1s 自动重连）完全不提示；
 * - 宽限期后仍断开时，再区分两种情况：
 *   · 设置里**已关闭**该服务 → 提示"已关闭，需重新开启"，并在有任务运行时引导打开设置页；
 *   · 设置里**仍开启**（系统正在重连/被第三方无障碍连接挤掉）→ 只写警告日志，不弹"去设置"，
 *     因为打开设置页解决不了问题，重连通常会自动完成；
 * - 同类提示有 [NOTIFY_COOLDOWN_MS] 冷却，绝不连续弹 Toast。
 *
 * 对应设计 docs/README.md 第 3.4 节「无障碍守护与自动恢复」。
 */
object AccessibilityGuard {

    private const val TAG = "ScriptJs"
    private const val INTERVAL_MS = 2000L
    private const val COOLDOWN_MS = 30_000L

    /** 断连宽限期：小于该时长视为瞬时重绑，不提示、不引导 */
    private const val GRACE_MS = 6_000L

    /** 同类提示的最短间隔，防止刷屏 */
    private const val NOTIFY_COOLDOWN_MS = 60_000L

    /**
     * 「设置里已启用但系统长时间未绑定」的升级阈值：
     * 该状态下系统通常不会自己重连（实测进程被回收后 AMS 未重绑），
     * 超过此时长就升级为可操作提示 + 引导用户重开服务。
     */
    private const val RECONNECT_ESCALATE_MS = 30_000L

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    /** 本进程内是否成功连接过（用于区分“从未连上”与“掉线”） */
    @Volatile
    private var everConnected = false

    @Volatile
    private var lastKnown = false

    /** 本次断连起始时间（0 表示当前未断开） */
    @Volatile
    private var offlineSince = 0L

    /** 上次提示时间（同类提示冷却用） */
    @Volatile
    private var lastNotifyAt = 0L

    /** 上次引导打开设置页的时间 */
    @Volatile
    private var lastRelaunchAt = 0L

    /** 已重试引导次数 */
    @Volatile
    private var retryCount = 0

    /** 本次掉线是否已提示过（避免同一次掉线重复刷屏） */
    @Volatile
    private var dropNotified = false

    /**
     * 本次掉线是否已**升级**为可操作提示。
     * 单独标记：6s 时的软提示（系统正在重连）不能把 30s 的升级提示吞掉，
     * 否则「设置里启用但系统不重绑」这一状态永远拿不到可操作指引（历史缺陷）。
     */
    @Volatile
    private var escalated = false

    private val checker = object : Runnable {
        override fun run() {
            val connected = GestureClient.available()
            val now = System.currentTimeMillis()
            if (connected) {
                val outage = if (offlineSince > 0L) now - offlineSince else 0L
                offlineSince = 0L
                if (!lastKnown) {
                    lastKnown = true
                    when {
                        // 瞬时重绑（宽限期内）：完全静默，连日志都不写，避免刷屏
                        outage in 1 until GRACE_MS -> Log.d(TAG, "无障碍服务瞬时重绑 ${outage}ms（忽略）")
                        everConnected -> onRestored(outage)
                        else -> {
                            everConnected = true
                            // 首次连接也复位掉线标志：否则本次进程内的「升级提示」标志会残留，
                            // 导致后续真实掉线不再提示
                            dropNotified = false
                            escalated = false
                            Log.i(TAG, "无障碍服务已连接")
                        }
                    }
                }
            } else {
                lastKnown = false
                if (offlineSince == 0L) offlineSince = now
                val offlineFor = now - offlineSince
                if (offlineFor >= GRACE_MS) {
                    val running = ScriptRunner.state.value.running
                    val enabled = isServiceEnabled(appContext)
                    when {
                        // 设置里已关闭：需要用户操作（曾连过或有任务在跑时才提示，避免打扰）
                        !enabled -> if (everConnected || running) onDisabled(running)
                        // 设置里启用却长时间不绑定：系统不会自愈，**即使本次进程从未连上也要提示**
                        // （典型场景：进程被回收后 AMS 未重绑，用户以为无障碍是开着的）
                        offlineFor >= RECONNECT_ESCALATE_MS -> onRebindStuck(running, offlineFor)
                        // 设置里启用、刚断开不久：可能自愈，只给一条温和提示
                        else -> if (everConnected || running) onReconnecting(running)
                    }
                }
            }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    /** 应用启动时开始守护 */
    fun start(context: Context) {
        appContext = context.applicationContext
        lastKnown = GestureClient.available()
        offlineSince = 0L
        if (lastKnown) everConnected = true
        handler.removeCallbacks(checker)
        handler.postDelayed(checker, INTERVAL_MS)
        Log.i(TAG, "无障碍掉线守护已启动（当前连接=$lastKnown）")
    }

    fun stop() {
        handler.removeCallbacks(checker)
    }

    /**
     * 服务是否仍列在系统无障碍启用列表中。
     * 用于区分「用户关掉了服务」与「服务只是暂时没连上」。
     */
    private fun isServiceEnabled(context: Context?): Boolean {
        val ctx = context ?: return false
        val target = ComponentName(ctx, ScriptAccessibilityService::class.java)
        try {
            val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            val fromManager = am?.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )?.any { info ->
                val si = info.resolveInfo?.serviceInfo ?: return@any false
                ComponentName(si.packageName, si.name) == target
            } ?: false
            if (fromManager) return true
        } catch (e: Exception) {
            Log.w(TAG, "查询无障碍启用列表失败: ${e.message}")
        }
        // 兜底：读 Secure 设置（部分 ROM 的 AMS 列表不完整）
        return try {
            val enabled = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabled.contains(target.flattenToString()) ||
                    enabled.contains(target.flattenToShortString())
        } catch (_: Exception) {
            false
        }
    }

    /** 服务在启用列表中但连不上：系统重连中，只记日志（打开设置页无济于事） */
    private fun onReconnecting(running: Boolean) {
        if (!dropNotified && canNotify()) {
            dropNotified = true
            val msg = if (running) {
                "无障碍服务连接中断（任务运行中），系统正在重连；坐标操作可能短暂失败"
            } else {
                "无障碍服务连接中断，系统正在重连…"
            }
            Log.w(TAG, msg)
            ScriptRunner.appendLog(msg, 'W')
            // 有意不弹 Toast：多数情况 1~2 秒内自动恢复，提示只会造成困扰
        }
    }

    /** 服务在设置里被关闭：这是需要用户操作的，提示并（有任务时）引导去开启 */
    private fun onDisabled(running: Boolean) {
        if (!dropNotified && canNotify()) {
            dropNotified = true
            val msg = "无障碍服务已关闭，坐标点击不可用；请在「设置 → 无障碍」开启本应用服务"
            Log.e(TAG, msg)
            ScriptRunner.appendLog(msg, 'E')
            toast(msg)
        }
        guideToSettings(running)
    }

    /**
     * 服务在启用列表中、但系统长时间未重绑（实测进程被回收后 AMS 不会自己重连）。
     * 这一状态不会自愈，因此升级为可操作提示 + 引导重开，而不是一直说「正在重连」。
     */
    private fun onRebindStuck(running: Boolean, offlineFor: Long) {
        // 用独立标志：即使 6s 时已发过「正在重连」软提示，也要升级为可操作提示
        if (!escalated && canNotify()) {
            escalated = true
            dropNotified = true
            val msg = "无障碍服务已启用但系统未绑定（已 ${offlineFor / 1000}s），坐标点击不可用；" +
                    "请到「设置 → 无障碍」关闭再开启本应用服务"
            Log.e(TAG, msg)
            ScriptRunner.appendLog(msg, 'E')
            toast(msg)
        }
        guideToSettings(running)
    }

    /** 有任务在跑时自动打开无障碍设置页引导重开（30s 冷却，重开即自动恢复） */
    private fun guideToSettings(running: Boolean) {
        if (!running) return

        val now = System.currentTimeMillis()
        val elapsed = now - lastRelaunchAt
        if (elapsed < COOLDOWN_MS) {
            if (!dropNotified) {
                ScriptRunner.appendLog("已引导过一次，${(COOLDOWN_MS - elapsed) / 1000}s 后自动重试", 'W')
            }
            return
        }
        val context = appContext ?: return
        if (openAccessibilitySettings(context)) {
            lastRelaunchAt = now
            retryCount++
            ScriptRunner.appendLog(
                "已自动打开无障碍设置页（第 $retryCount 次引导），请重新开启本应用服务；" +
                        "重开后坐标能力自动恢复",
                'W'
            )
        }
    }

    private fun canNotify(): Boolean =
        System.currentTimeMillis() - lastNotifyAt >= NOTIFY_COOLDOWN_MS

    /** 手动打开无障碍设置（供界面「重试开启无障碍」入口复用） */
    fun openAccessibilitySettings(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: Exception) {
        Log.w(TAG, "打开无障碍设置失败: ${e.message}")
        false
    }

    private fun onRestored(outageMs: Long) {
        retryCount = 0
        dropNotified = false
        escalated = false
        lastNotifyAt = 0L
        val msg = "无障碍服务已恢复（中断 ${outageMs / 1000}s），坐标操作可用"
        Log.i(TAG, msg)
        ScriptRunner.appendLog(msg, 'I')
    }

    private fun toast(msg: String) {
        lastNotifyAt = System.currentTimeMillis()
        try {
            handler.post {
                appContext?.let { Toast.makeText(it, msg, Toast.LENGTH_LONG).show() }
            }
        } catch (_: Exception) {
        }
    }
}
