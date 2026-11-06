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

/** 竖屏上下排列，横屏并排展示漫画和入口。 */
@Composable
fun StartupComic(@DrawableRes imageRes: Int, onFinished: () -> Unit) {
    val finish = rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) { delay(3_000); finish.value() }
    BackHandler { finish.value() }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        val comic = painterResource(imageRes)
        val description = "四格漫画：任务太多、录制操作、自动完成、轻松休息"
        if (maxWidth > maxHeight) {
            Row(
                modifier = Modifier.align(Alignment.Center).widthIn(max = 900.dp).fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(comic, contentDescription = description,
                    modifier = Modifier.weight(1.1f).fillMaxHeight(), contentScale = ContentScale.Fit)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    StartupHeading(landscape = true)
                    PrimaryAction("进入任务空间") { finish.value() }
                }
            }
        } else {
            val comicHeight = (maxHeight - 250.dp).coerceIn(160.dp, 540.dp)
            Column(
                modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 560.dp).fillMaxSize()
                    .verticalScroll(rememberScrollState()).padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                StartupHeading(landscape = false)
                Image(comic, contentDescription = description,
                    modifier = Modifier.fillMaxWidth().height(comicHeight).padding(vertical = 12.dp),
                    contentScale = ContentScale.Fit)
                PrimaryAction("进入任务空间") { finish.value() }
            }
        }
    }
}

@Composable
private fun StartupHeading(landscape: Boolean) {
    Text("雕雕助手 / FOUR PANELS", Modifier.fillMaxWidth(), fontSize = 10.sp, letterSpacing = 1.sp,
        color = Color(AppPalette.ON_PRIMARY_CONTAINER))
    Text(
        if (landscape) "忙碌的事，交给雕雕。" else "忙碌的事，\n交给雕雕。",
        Modifier.fillMaxWidth().padding(top = if (landscape) 10.dp else 18.dp, bottom = 8.dp),
        style = if (landscape) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge
    )
    Text("从重复点击到轻松完成，只差一个任务。", Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
