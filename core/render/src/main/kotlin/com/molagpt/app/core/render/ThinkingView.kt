package com.molagpt.app.core.render

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
fun ThinkingView(
    text: String,
    initiallyCollapsed: Boolean = true,
    durationMs: Long? = null,
    modifier: Modifier = Modifier,
) {
    var collapsed by remember { mutableStateOf(initiallyCollapsed) }
    LaunchedEffect(initiallyCollapsed) {
        collapsed = initiallyCollapsed
    }
    val title = buildString {
        append("思考过程")
        durationMs?.let { append(" · ").append(it / 1000).append("s") }
    }
    val chevColor = MaterialTheme.colorScheme.onSurfaceVariant
    val rotation by animateFloatAsState(
        targetValue = if (collapsed) 0f else 180f,
        animationSpec = MolaMotion.emphasized(),
        label = "chevron",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { collapsed = !collapsed }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Canvas(modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = rotation }) {
                val w = size.width
                val h = size.height
                val sw = w * 0.12f
                drawLine(chevColor, Offset(w * 0.28f, h * 0.42f), Offset(w * 0.5f, h * 0.62f), strokeWidth = sw, cap = StrokeCap.Round)
                drawLine(chevColor, Offset(w * 0.72f, h * 0.42f), Offset(w * 0.5f, h * 0.62f), strokeWidth = sw, cap = StrokeCap.Round)
            }
        }
        AnimatedVisibility(
            visible = !collapsed,
            enter = expandVertically(MolaMotion.emphasized()) + fadeIn(MolaMotion.standard(MolaMotion.Medium)),
            exit = shrinkVertically(MolaMotion.emphasized()) + fadeOut(MolaMotion.standard()),
        ) {
            if (text.isNotBlank()) {
                // 思考内容也走 Markdown 渲染，支持加粗、列表、代码和公式。
                // 用 LocalContentColor 压暗，保持思考块比正文更弱的视觉层级。
                CompositionLocalProvider(
                    LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    StreamingMarkdownView(
                        markdown = text,
                        textScale = 0.78f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 10.dp),
                    )
                }
            }
        }
    }
}
