package com.forest.android.assistant.core.floaty

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.forest.android.assistant.ui.AppPalette

/** 原生悬浮工具的确认面板，可在其他应用上显示并通过系统返回取消。 */
internal object OverlayConfirm {
    fun show(context: Context, title: String, message: String, confirm: String, onConfirm: () -> Unit) {
        val themed = ContextThemeWrapper(context, android.R.style.Theme_Material_Light_Dialog_NoActionBar)
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val layout = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(23), dp(24), dp(23), dp(20))
            background = GradientDrawable().apply { setColor(AppPalette.BACKGROUND); cornerRadius = dp(25).toFloat() }
        }
        layout.addView(TextView(themed).apply { text = title; textSize = 19f; setTextColor(AppPalette.ON_SURFACE) })
        layout.addView(TextView(themed).apply {
            text = message; textSize = 12f; setTextColor(AppPalette.ON_SURFACE_VARIANT)
            setPadding(0, dp(14), 0, dp(18))
        })
        val dialog = AlertDialog.Builder(themed).setView(layout).create()
        val actions = LinearLayout(themed)
        actions.addView(Button(themed).apply {
            text = "取消"; isAllCaps = false; setTextColor(AppPalette.ON_SURFACE_VARIANT)
            backgroundTintList = android.content.res.ColorStateList.valueOf(AppPalette.SURFACE_CONTAINER)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(10) })
        actions.addView(Button(themed).apply {
            text = confirm; isAllCaps = false; setTextColor(AppPalette.ERROR)
            backgroundTintList = android.content.res.ColorStateList.valueOf(AppPalette.ERROR_CONTAINER)
            setOnClickListener { dialog.dismiss(); onConfirm() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        layout.addView(actions)
        dialog.window?.setType(overlayWindowType())
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(context.resources.displayMetrics.widthPixels - dp(20), -2)
        }
    }
}
