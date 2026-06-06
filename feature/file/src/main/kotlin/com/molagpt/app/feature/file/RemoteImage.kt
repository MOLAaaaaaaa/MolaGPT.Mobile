package com.molagpt.app.feature.file

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
import com.molagpt.app.core.render.fadeScaleIn
import com.molagpt.app.core.render.shimmer

/** 远程图片（Coil 3）。加载中显示骨架微光占位，成功后淡入 + 轻微放大。用于生成图片 / 多模态图片展示。 */
@Composable
fun RemoteImage(url: String, modifier: Modifier = Modifier, contentDescription: String? = null) {
    var loaded by remember(url) { mutableStateOf(false) }
    Box(modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))) {
        AsyncImage(
            model = url,
            contentDescription = contentDescription,
            contentScale = ContentScale.FillWidth,
            onState = { state -> if (state is AsyncImagePainter.State.Success) loaded = true },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .fadeScaleIn(loaded),
        )
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
