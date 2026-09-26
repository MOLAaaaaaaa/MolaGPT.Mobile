package com.molagpt.app.feature.file

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.molagpt.app.core.render.decodeImageModel

/**
 * 页面级的图片预览宿主：共享元素过渡的 scope、预览目标、全屏 overlay 一次接好。
 *
 * 包在 Scaffold 外面，overlay 才能盖住顶栏和输入框。[content] 里任何 [RemoteImage] /
 * [PreviewableImage] 点开都会从原位展开到全屏，关闭时收回。预览打开时返回键先关预览。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ImagePreviewHost(
    modifier: Modifier = Modifier,
    extraAction: ImagePreviewAction? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    SharedTransitionLayout(modifier = modifier.fillMaxSize()) {
        val sharedScope = this
        val holder = rememberPreviewUrlHolder()
        CompositionLocalProvider(
            LocalSharedTransitionScope provides sharedScope,
            LocalImagePreviewUrl provides holder,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                content()

                val previewUrl = holder.current
                AnimatedVisibility(
                    visible = previewUrl != null,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@AnimatedVisibility) {
                        previewUrl?.let { url ->
                            sharedScope.ImagePreviewOverlay(
                                url = url,
                                gallery = holder.gallery,
                                extraAction = extraAction?.let { action ->
                                    ImagePreviewAction(action.label) { current ->
                                        holder.request(null)
                                        action.onClick(current)
                                    }
                                },
                                onDismiss = { holder.request(null) },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberPreviewUrlHolder(): ImagePreviewUrlHolder {
    var url by remember { mutableStateOf<String?>(null) }
    var group by remember { mutableStateOf<List<String>>(emptyList()) }
    BackHandler(enabled = url != null) { url = null }
    return remember(url, group) {
        object : ImagePreviewUrlHolder {
            override val current: String? get() = url
            override val gallery: List<String> get() = group
            override fun request(url: String?) {
                if (url == null) group = emptyList()
                setUrl(url)
            }

            override fun request(url: String, gallery: List<String>) {
                group = gallery
                setUrl(url)
            }

            private fun setUrl(value: String?) {
                url = value
            }
        }
    }
}

/**
 * 可点开预览的图片，尺寸和裁切由调用方决定（网格缩略图、定宽卡片）。
 * 点开时把 [gallery] 一起交给预览，可以左右切换。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PreviewableImage(
    url: String,
    modifier: Modifier = Modifier,
    gallery: List<String> = emptyList(),
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = null,
    onLoaded: (Boolean) -> Unit = {},
) {
    val model = remember(url) { decodeImageModel(url) }
    val holder = LocalImagePreviewUrl.current
    val sts = LocalSharedTransitionScope.current
    val isPreviewing = holder?.current == url
    val open = { if (gallery.size > 1) holder?.request(url, gallery) else holder?.request(url) }
    val onState: (AsyncImagePainter.State) -> Unit = { state ->
        when (state) {
            is AsyncImagePainter.State.Success -> onLoaded(true)
            is AsyncImagePainter.State.Error -> onLoaded(false)
            else -> Unit
        }
    }
    Box(modifier = modifier) {
        if (sts != null) {
            with(sts) {
                AnimatedVisibility(visible = !isPreviewing, enter = fadeIn(), exit = fadeOut()) {
                    AsyncImage(
                        model = model,
                        contentDescription = contentDescription,
                        contentScale = contentScale,
                        onState = onState,
                        modifier = Modifier
                            .sharedElement(
                                sharedContentState = rememberSharedContentState(key = "img-$url"),
                                animatedVisibilityScope = this@AnimatedVisibility,
                                boundsTransform = { _, _ -> tween(320, easing = FastOutSlowInEasing) },
                            )
                            .fillMaxSize()
                            .clickable { open() },
                    )
                }
            }
        } else {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                contentScale = contentScale,
                onState = onState,
                modifier = Modifier.fillMaxSize().clickable { open() },
            )
        }
    }
}
