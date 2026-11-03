package com.forest.android.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** 启动时完整展示四格漫画，横竖屏均不裁切；点击或返回可提前进入首页。 */
@Composable
fun StartupComic(@DrawableRes imageRes: Int, onFinished: () -> Unit) {
    val finish = rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
        delay(3_000)
        finish.value()
    }
    BackHandler { finish.value() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(onClickLabel = "跳过启动漫画") { finish.value() }
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(imageRes),
            contentDescription = "肥宅雕四格漫画：任务太多、点击执行、自动完成、轻松休息",
            modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 48.dp),
            contentScale = ContentScale.Fit
        )
        Text(
            text = "点击跳过",
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
