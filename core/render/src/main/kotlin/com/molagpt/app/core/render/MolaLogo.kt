package com.molagpt.app.core.render

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The product logo treatment used by the About page and empty-chat welcome page.
 */
@Composable
fun MolaLogo(
    modifier: Modifier = Modifier,
    size: Dp = 88.dp,
    cornerRadius: Dp = 22.dp,
    imageScale: Float = 1.3f,
    contentDescription: String? = null,
) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(primary.copy(alpha = 0.13f))
            .border(1.dp, primary.copy(alpha = 0.18f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.molagpt_logo),
            contentDescription = contentDescription,
            modifier = Modifier
                .size(size)
                .scale(imageScale),
        )
    }
}
