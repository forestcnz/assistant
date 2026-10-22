package com.forest.android.assistant

import android.app.Application
import android.content.ComponentCallbacks
import android.content.res.Configuration
import com.forest.android.assistant.core.editor.ApiDocs
import com.forest.android.assistant.core.floaty.ConsoleOverlay
import com.forest.android.assistant.core.floaty.FloatyManager
import com.forest.android.assistant.core.gesture.AccessibilityGuard
import com.forest.android.assistant.core.pack.EmbeddedTask
import com.forest.android.assistant.core.engine.ScriptRunner

/**
 * 应用入口：初始化脚本运行器（悬浮停止回调）；
 * 生成 API 声明文件（.d.ts）供编辑器补全与外部查阅；
 * 注册配置变化回调，旋转/分屏后把悬浮球与控制台收回屏内。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ScriptRunner.attach(this)   // 初始化运行器：绑定悬浮球/停止回调与任务列表来源
        AccessibilityGuard.start(this)   // 无障碍掉线守护：掉线提示 + 自动引导重开
        ApiDocs.writeDts(this)      // 生成 files/api/script-js.d.ts

        // 旋转/分辨率变化后重算悬浮窗边界：否则浮层可能半出屏（旋转后按钮点不到）
        registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                FloatyManager.onScreenSizeChanged(this@App)
                ConsoleOverlay.onScreenSizeChanged(this@App)
            }

            override fun onLowMemory() {
            }
        })

        // 打包导出的 APK 自带内嵌任务：提取到任务目录，首次启动自动运行一次
        val embedded = EmbeddedTask.installIfPresent(this)
        if (embedded != null && EmbeddedTask.consumeFirstLaunch(this)) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                ScriptRunner.run(this, embedded)
            }, 1500)
        }
    }
}
