package com.forest.android.assistant.core.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.forest.android.assistant.core.editor.ApiDocs
import com.forest.android.assistant.core.engine.ScriptAdapter
import com.forest.android.assistant.core.engine.ScriptEngine
import kotlinx.coroutines.runBlocking
import java.io.File

/** 使用生成的文字图片验证 MLKit → Kotlin → QuickJS，无需截图或无障碍权限。 */
object OcrScriptChecks {
    fun run(context: Context) {
        val bitmap = Bitmap.createBitmap(1000, 600, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 54f
        }
        canvas.drawText("SALE: Buy - NOW!", 60f, 130f, paint)
        canvas.drawText("限时：立 即·购 买！", 60f, 300f, paint)
        val logs = mutableListOf<String>()
        ScriptAdapter.setRealSize(bitmap.width, bitmap.height)
        try {
            check(ApiDocs.writeDts(context) != null) { "API 声明导出失败" }
            val outcome = runBlocking {
                ScriptEngine.execute(
                    ScriptEngine.ScriptEnv(
                        scriptPath = File(context.cacheDir, "ocr-contains-check.js").absolutePath,
                        onLog = { _, message -> logs.add(message) },
                        onToast = {},
                        openAccessibilitySettings = {},
                        requestScreenCapture = { true },
                        screenCaptureReady = { true },
                        captureScreen = { bitmap.copy(Bitmap.Config.ARGB_8888, false) }
                    ),
                    """
                    function check(ok, message) { if (!ok) throw new Error(message); }
                    check(require("ocr") === ocr, "require OCR module");
                    adapter.setDesignSize(500, 300);
                    var image = captureScreen();
                    var options = { region: [15, 30, 450, 65] };
                    var found = ocr.findTextContains(image, "Ｂｕｙ　ＮＯＷ", options);
                    check(found !== null, "normalized English lookup");
                    check(found.text.indexOf("Buy") >= 0, "original text retained: " + found.text);
                    check(found.left >= 15 && found.right <= 465, "region X / design coordinates");
                    check(found.top >= 30 && found.bottom <= 95, "region Y / design coordinates");
                    check(found.bounds.left === found.left, "bounds serialization");
                    check(found.centerX() === Math.round((found.left + found.right) / 2), "centerX");
                    check(found.centerY() === Math.round((found.top + found.bottom) / 2), "centerY");
                    check(typeof found.confidence === "number", "confidence");
                    var chinese = ocr.findTextContains(image, "立即购买");
                    check(chinese !== null, "Chinese lookup without options");
                    check(ocr.findTextContains(image, "立即购买", options) === null, "region excludes Chinese line");
                    check(ocr.findTextContains(image, "missing") === null, "missing query");
                    check(ocr.findTextContains(image, " \t\n【】★") === null, "symbol-only query");
                    check(ocr.findTextContains(image, null) === null, "null query");
                    check(ocr.findTextContains(image) === null, "undefined query");
                    check(ocr.findText(image, found.text, options).text === found.text, "existing findText");
                    check(ocr.findAllText(image, found.text, options).length > 0, "existing findAllText");
                    images.release(image);
                    check(ocr.findTextContains(image, "Buy") === null, "released image");
                    log("OCR_CONTAINS_OK");
                    """.trimIndent()
                )
            }
            check(outcome.kind == ScriptEngine.OutcomeKind.FINISHED) {
                "OCR 脚本失败: $outcome\n${logs.joinToString("\n") }"
            }
            check("OCR_CONTAINS_OK" in logs)
        } finally {
            ScriptAdapter.clear()
            bitmap.recycle()
        }
    }
}
