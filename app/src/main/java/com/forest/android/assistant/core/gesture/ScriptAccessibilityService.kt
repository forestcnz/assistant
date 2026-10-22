package com.forest.android.assistant.core.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.forest.android.assistant.core.engine.ScriptRunner

/**
 * 无障碍服务：仅用于手势派发（坐标点击/滑动/手势），不解析控件树。
 * 开启后通过 [ScriptAccessibilityService.instance] 全局引用供 GestureClient 调用。
 */
class ScriptAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "手势无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 手势模式无需监听界面事件，保留空实现
    }

    override fun onInterrupt() {
        // 系统要求中断反馈属于**常规回调**，不代表服务被关闭：只记日志，
        // 绝不能提示用户「掉线了、去设置重开」（旧实现会在此误报）。
        Log.i(TAG, "手势无障碍服务中断（系统常规回调，非掉线）")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        // 解绑常常是瞬时重绑（实测系统约 1s 后自动重连），uiautomator 等第三方
        // 无障碍连接建立时也会触发。这里只解除实例引用并留日志；
        // 是否算「掉线」交给 AccessibilityGuard 按宽限期统一判定，避免误报刷屏。
        instance = null
        Log.i(TAG, "手势无障碍服务已解绑（等待系统重连）")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScriptA11y"

        /** 全局单例引用；服务未开启时为 null */
        @Volatile
        var instance: ScriptAccessibilityService? = null
            private set
    }
}
