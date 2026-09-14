package com.molagpt.app.core.render

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 角色卡头像。没有头像就退回矢量图标，视觉上与没导入过卡的角色一致。
 *
 * 两件事值得说明：
 * - **按目标尺寸降采样**。角色卡 PNG 动辄几百上千像素，为一个 42dp 的圆头像解全图是白烧内存。
 * - **解码结果缓存**。同一张头像会同时出现在列表、选择器和聊天气泡里，不缓存就要解好几遍。
 */
@Composable
fun PersonaAvatar(
    file: File?,
    fallbackIcon: ImageVector,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val targetPx = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, file?.path, file?.lastModified(), targetPx) {
        value = file?.let { withContext(Dispatchers.IO) { PersonaAvatarCache.load(it, targetPx) } }
    }
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(cs.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        } else {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(size * 0.56f),
            )
        }
    }
}

private object PersonaAvatarCache {
    /** 列表 + 选择器 + 气泡同屏最多也就这么多张不同头像。 */
    private val cache = LruCache<String, ImageBitmap>(12)

    fun load(file: File, targetPx: Int): ImageBitmap? {
        if (!file.isFile || targetPx <= 0) return null
        val key = "${file.path}:${file.lastModified()}:$targetPx"
        cache.get(key)?.let { return it }
        val decoded = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetPx)
            }
            BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
        }.getOrNull() ?: return null
        cache.put(key, decoded)
        return decoded
    }

    private fun sampleSize(width: Int, height: Int, targetPx: Int): Int {
        var sample = 1
        var smaller = minOf(width, height)
        while (smaller / 2 >= targetPx) {
            smaller /= 2
            sample *= 2
        }
        return sample
    }
}
