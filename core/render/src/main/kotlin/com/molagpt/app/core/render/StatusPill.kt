package com.molagpt.app.core.render

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 状态行要显示的内容；null 即不显示。 */
data class StatusModel(
    val text: String,
    val error: Boolean = false,
    val progress: Boolean = false,
    val action: String? = null,
)


@Composable
fun StatusPill(
    model: StatusModel,
    onAction: () -> Unit = {},
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .molaGlass(RoundedCornerShape(50), elevation = 2.dp)
            .heightIn(min = 34.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 12.dp, end = if (model.action != null) 4.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (model.progress) {
            CircularProgressIndicator(strokeWidth = 1.5.dp, modifier = Modifier.size(12.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
        } else {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (model.error) cs.error else cs.onSurfaceVariant.copy(alpha = 0.5f)),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            model.text,
            style = MaterialTheme.typography.labelMedium,
            color = if (model.error) cs.error else cs.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        model.action?.let { label ->
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (model.error) cs.onSurfaceVariant else cs.primary,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

