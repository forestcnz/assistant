package com.forest.android.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** 保留品牌漫画资源和自动进入行为，增加设计稿的标题与明确入口。 */
@Composable
fun StartupComic(@DrawableRes imageRes: Int, onFinished: () -> Unit) {
    val finish = rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) { delay(3_000); finish.value() }
    BackHandler { finish.value() }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        val comicHeight = (maxHeight - 285.dp).coerceIn(240.dp, 540.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("雕雕助手 / FOUR PANELS", Modifier.fillMaxWidth(), fontSize = 10.sp, letterSpacing = 1.sp,
                color = Color(AppPalette.ON_PRIMARY_CONTAINER))
            Text("忙碌的事，\n交给雕雕。", Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp),
                style = MaterialTheme.typography.headlineLarge)
            Text("从重复点击到轻松完成，只差一个任务。", Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Image(painterResource(imageRes), contentDescription = "四格漫画：任务太多、录制操作、自动完成、轻松休息",
                modifier = Modifier.fillMaxWidth().height(comicHeight).padding(vertical = 16.dp),
                contentScale = ContentScale.Fit)
            PrimaryAction("进入任务空间") { finish.value() }
            TextButton(onClick = { finish.value() }) { Text("点击跳过", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
