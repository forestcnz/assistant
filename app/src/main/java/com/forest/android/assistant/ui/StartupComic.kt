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

/**
 * 启动页：遵循「雾蓝暖白」设计稿的品牌页。
 * 保留 3 秒自动进入；品牌形象、三步说明与底部入口与设计稿一致。
 * imageRes 保留为参数，便于后续替换启动插画。
 * 当前恢复原来的四格漫画，直接显示 imageRes，保留自动进入和手动进入。
 */
@Composable
fun StartupComic(@DrawableRes imageRes: Int, onFinished: () -> Unit) {
    val finish = rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) { delay(3_000); finish.value() }
    BackHandler { finish.value() }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        val comic = painterResource(imageRes)
        val description = "四格漫画：任务太多、录制操作、自动完成、轻松休息"
        if (maxWidth > maxHeight) {
            // 横屏：文案与品牌形象并排，保证入口始终可见。
            // 插画恢复为原来的完整四格漫画，保持原始比例。
            Row(
                modifier = Modifier.align(Alignment.Center).widthIn(max = 900.dp).fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = comic,
                    contentDescription = description,
                    modifier = Modifier.weight(1.1f).fillMaxHeight(),
                    contentScale = ContentScale.Fit
                )
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
                // 文案区可滚动，入口按钮固定在底部，与设计稿一致。
                // 恢复原版布局：文案、完整漫画和进入按钮依次展示，小屏整体滚动。
                StartupHeading(landscape = false)
                Image(comic, contentDescription = description,
                    modifier = Modifier.fillMaxWidth().height(comicHeight).padding(vertical = 12.dp),
                    contentScale = ContentScale.Fit)
                PrimaryAction("进入任务空间") { finish.value() }
            }
        }
    }
}

/** 启动页文案与三步流程；横屏使用更紧凑的字号。 */
// 三步图标已恢复为四格漫画，此处只保留原版的标题和说明。
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
    // 录下操作 → 自动执行 → 完成，与设计稿的三步图标一致。
    // 原有四格漫画承载录制、自动执行与完成的画面，不再额外显示三步图标。
}
