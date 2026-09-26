package com.molagpt.app.feature.file

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.molagpt.app.core.render.decodeImageModel
import coil3.ImageLoader
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState

/**
 * 图片预览的共享元素过渡 scope 下发。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope =
    staticCompositionLocalOf<SharedTransitionScope?> { null }

/** overlay 的 AnimatedVisibility scope；缩略图与全屏图共用，驱动过渡进度。 */
val LocalAnimatedVisibilityScope =
    staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** 当前预览目标 url（null = 关闭）。由顶层持有，缩略图点击写入，overlay 观察渲染。 */
val LocalImagePreviewUrl = staticCompositionLocalOf<ImagePreviewUrlHolder?> { null }

/** 缩略图点击时调用 [request]；overlay 渲染 [current] 指定的 url。 */
interface ImagePreviewUrlHolder {
    val current: String?
    fun request(url: String?)

    /** 同组的其它图片，预览里可以左右切换。 */
    val gallery: List<String> get() = emptyList()

    fun request(url: String, gallery: List<String>) = request(url)
}

/**
 * 全屏图片预览 overlay（非 Dialog，挂在 SharedTransitionLayout 子树内以支持共享元素过渡）。
 *
 * - 沉浸式全屏黑底（透到状态栏后方，覆盖顶栏/输入框）；
 * - ZoomableAsyncImage 双指/双击缩放；
 * - [gallery] 多于一张时左右滑动切换，顶部显示序号；
 * - 单指下滑关闭：未放大时下拉拖动，超过 15% 阈值则非线性动画收起到原位；未达阈值弹回；
 * - 顶栏「返回」+「保存到相册」，可再挂一个 [extraAction]（如画廊里的「查看任务」）。
 *
 * 共享元素只挂在打开时的那一张上：划到别的图再关，就只淡出，不去找另一张缩略图的位置。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.ImagePreviewOverlay(
    url: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    gallery: List<String> = emptyList(),
    extraAction: ImagePreviewAction? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val avScope = LocalAnimatedVisibilityScope.current
    var saving by remember { mutableStateOf(false) }

    val pages = remember(url, gallery) { if (gallery.size > 1 && url in gallery) gallery else listOf(url) }
    val initialPage = remember(pages, url) { pages.indexOf(url).coerceAtLeast(0) }
    val pagerState = rememberPagerState(initialPage = initialPage) { pages.size }
    val currentUrl = pages.getOrElse(pagerState.currentPage) { url }

    // 每页各自的缩放态；下滑关闭只看当前页有没有放大。
    val zoomStates = remember(pages) { mutableMapOf<Int, ZoomableState>() }

    // ── 下滑关闭 ──
    // dismissProgress ∈ [0,1]：0=原位，1=完全移出屏幕。拖动时 snapTo；松手后 animateTo。
    val dismissProgress = remember { Animatable(0f) }
    var isDismissing by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .graphicsLayer {
                val p = dismissProgress.value
                alpha = 1f - p * 0.6f
                translationY = p * size.height
            }
            .pointerInput(pages) {
                detectVerticalDragGestures(
                    onVerticalDrag = { _, dragAmount ->
                        if (isDismissing) return@detectVerticalDragGestures
                        // 仅在未放大时下拉关闭；放大后由 telephoto 内部平移。
                        val zoom = zoomStates[pagerState.currentPage]
                        val atBaseZoom = (zoom?.zoomFraction ?: 0f) <= 0.01f
                        if (atBaseZoom && dragAmount > 0f) {
                            scope.launch {
                                dismissProgress.snapTo(
                                    (dismissProgress.value + dragAmount / size.height).coerceIn(0f, 1f),
                                )
                            }
                        }
                    },
                    onDragEnd = {
                        if (isDismissing) return@detectVerticalDragGestures
                        scope.launch {
                            if (dismissProgress.value > 0.15f) {
                                isDismissing = true
                                dismissProgress.animateTo(1f, tween(250, easing = FastOutSlowInEasing))
                                onDismiss()
                            } else {
                                dismissProgress.animateTo(0f, spring())
                            }
                        }
                    },
                )
            },
    ) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = pages.size > 1,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val pageUrl = pages[page]
            val zoomState = rememberZoomableState()
            zoomStates[page] = zoomState
            val imageState = rememberZoomableImageState(zoomState)
            val shared = avScope != null && page == initialPage && pagerState.currentPage == initialPage
            val sharedModifier = if (shared) {
                Modifier.sharedElement(
                    sharedContentState = rememberSharedContentState(key = "img-$pageUrl"),
                    animatedVisibilityScope = avScope!!,
                    boundsTransform = { _, _ ->
                        tween(durationMillis = 320, easing = FastOutSlowInEasing)
                    },
                )
            } else {
                Modifier
            }
            ZoomableAsyncImage(
                model = decodeImageModel(pageUrl),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                state = imageState,
                modifier = sharedModifier.then(Modifier.fillMaxSize()),
            )
        }

        if (!isDismissing) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "关闭", tint = Color.White)
                }
                if (pages.size > 1) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${pages.size}",
                        color = Color.White.copy(alpha = 0.85f),
                        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                    )
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    if (extraAction != null) {
                        TextButton(onClick = { extraAction.onClick(currentUrl) }) {
                            Text(extraAction.label, color = Color.White)
                        }
                    }
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp).padding(end = 12.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        TextButton(onClick = {
                            if (!saving) {
                                saving = true
                                val target = currentUrl
                                scope.launch {
                                    val ok = runCatching {
                                        withContext(Dispatchers.IO) { saveImageToGallery(context, target) }
                                    }.getOrDefault(false)
                                    saving = false
                                    Toast.makeText(
                                        context,
                                        if (ok) "已保存到相册" else "保存失败",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        }) { Text("保存", color = Color.White) }
                    }
                }
            }
        }
    }
}

/** 预览顶栏上的附加操作，拿到的是当前正在看的那一张。 */
class ImagePreviewAction(val label: String, val onClick: (url: String) -> Unit)

/** Coil 解码图片 → Bitmap → MediaStore。 */
private suspend fun saveImageToGallery(context: Context, url: String): Boolean {
    val model = decodeImageModel(url)
    val loader: ImageLoader = context.imageLoader
    val request = ImageRequest.Builder(context).data(model).crossfade(false).build()
    val result = runCatching { loader.execute(request) }.getOrNull() ?: return false
    val image = result.image ?: return false
    val bitmap = runCatching { image.asDrawable(context.resources).toBitmap() }.getOrNull() ?: return false
    return writeBitmapToMediaStore(context, bitmap)
}

private fun writeBitmapToMediaStore(context: Context, bitmap: Bitmap): Boolean {
    val name = "MolaGPT_${System.currentTimeMillis()}.jpg"
    val resolver: ContentResolver = context.contentResolver
    val (collection, pending) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to true
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI to false
    }
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MolaGPT")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        } else {
            @Suppress("DEPRECATION")
            put(
                MediaStore.Images.Media.DATA,
                "${Environment.getExternalStorageDirectory()}/${Environment.DIRECTORY_PICTURES}/MolaGPT/$name",
            )
        }
    }
    val uri: Uri = resolver.insert(collection, values) ?: return false
    return runCatching {
        resolver.openOutputStream(uri)?.use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out) }
            ?: return false
        if (pending) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        true
    }.getOrElse {
        runCatching { resolver.delete(uri, null, null) }
        false
    }
}
