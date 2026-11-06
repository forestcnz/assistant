package com.forest.android.assistant.core.engine

import android.content.Context
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 脚本运行时停止回归：验证 quickjs-kt 的 VM 级中断（interruptEvaluation）真正生效。
 * 无需截图或无障碍权限，只使用脚本引擎本身。
 *
 * 覆盖：
 * * 纯 JS 忙循环（本身不含任何原生调用）可被停止；
 * * 长 sleep 仍可通过绑定入口的检查点快速退出；
 * * 正常结束、exit()、语法错误的分类不被中断逻辑影响；
 * * 连续启停不崩溃（对应 quickjs-kt 1.0.7 修复的 native 生命周期问题）。
 */
object RuntimeStopChecks {

    fun run(context: Context) {
        val baseDir = File(context.cacheDir, "runtime-stop").apply { mkdirs() }

        // 1) 纯 JS 忙循环：没有 sleep、没有任何原生调用，只能靠 VM 中断结束
        expectStopWithin(baseDir, "busy-loop.js", "while (true) { }", 3000)

        // 2) 不含原生的空转 for 循环
        expectStopWithin(baseDir, "for-loop.js", "for (;;) { }", 3000)

        // 3) 长 sleep：由 sleep 内部的停止检查退出
        expectStopWithin(baseDir, "long-sleep.js", "sleep(60000);", 3000)

        // 4) 正常结束不受影响
        expectOutcome(baseDir, "normal.js", "log('done');", ScriptEngine.OutcomeKind.FINISHED)

        // 5) exit() 仍按退出处理
        expectOutcome(baseDir, "exit.js", "exit();", ScriptEngine.OutcomeKind.EXITED)

        // 6) 语法错误仍按出错处理，并带出行号
        val errorOutcome = expectOutcome(
            baseDir, "syntax-error.js", "var = ;", ScriptEngine.OutcomeKind.ERROR
        )
        check(errorOutcome.errorLine != null) {
            "语法错误未解析出行号: ${errorOutcome.message}"
        }

        // 7) 连续启停：覆盖 native 生命周期与锁，重复 5 次不应崩溃
        repeat(5) { index ->
            expectStopWithin(baseDir, "repeat-$index.js", "while (true) { }", 3000)
        }

        // 8) 停止后引擎仍可复用（activeSession 已清理）
        expectOutcome(baseDir, "after-stop.js", "var token = 1;", ScriptEngine.OutcomeKind.FINISHED)
    }

    /** 运行脚本并断言它在限定时间内以 STOPPED 结束 */
    private fun expectStopWithin(baseDir: File, name: String, script: String, timeoutMs: Long) {
        expectOutcome(
            baseDir = baseDir,
            name = name,
            script = script,
            expected = ScriptEngine.OutcomeKind.STOPPED,
            stopAfterMs = 150,
            timeoutMs = timeoutMs
        )
    }

    /**
     * 执行脚本并断言结束分类；[stopAfterMs] > 0 时在该延时后模拟用户停止。
     *
     * execute() 放在独立线程里跑，停止请求也由独立线程发出，
     * 以还原「主线程点停止、引擎线程执行脚本」的真实时序。
     */
    private fun expectOutcome(
        baseDir: File,
        name: String,
        script: String,
        expected: ScriptEngine.OutcomeKind,
        stopAfterMs: Long = 0,
        timeoutMs: Long = 30_000
    ): ScriptEngine.RunOutcome {
        val logs = mutableListOf<String>()
        val started = AtomicBoolean(false)
        val result = AtomicReference<ScriptEngine.RunOutcome?>()
        val failure = AtomicReference<Throwable?>()

        val env = ScriptEngine.ScriptEnv(
            scriptPath = File(baseDir, name).absolutePath,
            onLog = { _, message -> synchronized(logs) { logs.add(message) } },
            onToast = {},
            openAccessibilitySettings = {},
            requestScreenCapture = { false },
            screenCaptureReady = { false },
            captureScreen = { null }
        )

        val worker = Thread({
            started.set(true)
            try {
                result.set(runBlocking { ScriptEngine.execute(env, script) })
            } catch (e: Throwable) {
                failure.set(e)
            }
        }, "runtime-check-$name").apply { isDaemon = true }

        val stopper = if (stopAfterMs > 0) Thread({
            val deadline = System.currentTimeMillis() + 5_000
            while (!started.get() && System.currentTimeMillis() < deadline) Thread.sleep(5)
            Thread.sleep(stopAfterMs)
            ScriptEngine.cancel()
        }, "runtime-check-stop-$name").apply { isDaemon = true } else null

        try {
            stopper?.start()
            worker.start()
            worker.join(timeoutMs)
            check(!worker.isAlive) {
                "$name 未在 ${timeoutMs}ms 内结束（期望 $expected）"
            }
        } finally {
            if (worker.isAlive) {
                ScriptEngine.cancel()
                worker.join(3_000)
            }
            stopper?.join(2_000)
        }

        failure.get()?.let { throw AssertionError("$name 抛出异常", it) }
        val outcome = result.get() ?: error("$name 未取得运行结果")
        check(outcome.kind == expected) {
            "$name 期望 $expected，实际 ${outcome.kind}，" +
                    "消息=${outcome.message}，日志=${synchronized(logs) { logs.takeLast(8) }}"
        }
        return outcome
    }
}
