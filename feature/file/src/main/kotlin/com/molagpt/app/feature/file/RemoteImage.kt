package com.molagpt.app.feature.file

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.molagpt.app.core.render.decodeImageModel
import com.molagpt.app.core.render.fadeScaleIn
import com.molagpt.app.core.render.shimmer

/**
 * 远程图片（Coil 3）。加载中显示骨架微光占位，成功后淡入 + 轻微放大。用于生成图片 / 多模态图片展示。
 *
 * 点击打开全屏可缩放预览（共享元素过渡：从缩略图位置展开到全屏，关闭时收回）。
 * 实现：缩略图包在 [AnimatedVisibility] 内，当该 url 成为预览目标时缩略图退出、全屏 overlay 进入，
 * 两者用 key `img-$url` 配对，框架自动以非线性缓动在两端 bounds 间过渡。预览状态由顶层
 * [LocalImagePreviewUrl] 统一持有。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun RemoteImage(url: String, modifier: Modifier = Modifier, contentDescription: String? = null) {
    var loaded by remember(url) { mutableStateOf(false) }
    val model = remember(url) { decodeImageModel(url) }
    val previewHolder = LocalImagePreviewUrl.current
    val sts = LocalSharedTransitionScope.current
    // 当前 url 正被预览时，缩略图退出（让位给全屏 overlay，由 shared element 接管过渡）。
    val isPreviewing = previewHolder?.current == url

    Box(modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))) {
        if (sts != null) {
            with(sts) {
                AnimatedVisibility(
                    visible = !isPreviewing,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    AsyncImage(
                        model = model,
                        contentDescription = contentDescription,
                        contentScale = ContentScale.FillWidth,
                        onState = { state -> if (state is AsyncImagePainter.State.Success) loaded = true },
                        modifier = Modifier
                            .sharedElement(
                                sharedContentState = rememberSharedContentState(key = "img-$url"),
                                animatedVisibilityScope = this@AnimatedVisibility,
                                boundsTransform = { _, _ ->
                                    tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                },
                            )
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .fadeScaleIn(loaded)
                            .clickable { previewHolder?.request(url) },
                    )
                }
            }
        } else {
            // 无 SharedTransitionScope（如预览/单测）：退化为普通可点击图片。
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                contentScale = ContentScale.FillWidth,
                onState = { state -> if (state is AsyncImagePainter.State.Success) loaded = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .fadeScaleIn(loaded)
                    .clickable { previewHolder?.request(url) },
            )
        }
        if (!loaded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .shimmer(),
            )
        }
    }
}
