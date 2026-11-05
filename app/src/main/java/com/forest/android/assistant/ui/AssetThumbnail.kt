package com.forest.android.assistant.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.forest.android.assistant.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 小尺寸异步解码；文件更新后重新读取，避免在列表中加载原图。 */
@Composable
fun AssetThumbnail(file: File, modified: Long, bytes: Long) {
    val bitmap by produceState<Bitmap?>(null, file.absolutePath, modified, bytes) {
        value = withContext(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 192) sample *= 2
                BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
        }
    }
    val decoded = bitmap
    if (decoded == null) IconTile(R.drawable.ic_tool_shot, blue = true)
    else Image(decoded.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
        modifier = Modifier.size(50.dp).clip(RoundedCornerShape(10.dp)))
}
