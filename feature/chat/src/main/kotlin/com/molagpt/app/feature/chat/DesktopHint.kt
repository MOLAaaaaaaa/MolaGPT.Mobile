package com.molagpt.app.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** 首页引导：还没在手机上配对过电脑时显示，点开进电脑页，关掉后不再出现。 */
@Composable
internal fun DesktopHint(
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onOpen)
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentMonitorIcon(cs.primary, Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = "在手机上继续电脑上的对话",
            style = MaterialTheme.typography.labelLarge,
            color = cs.onSurfaceVariant,
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "不再提示",
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
