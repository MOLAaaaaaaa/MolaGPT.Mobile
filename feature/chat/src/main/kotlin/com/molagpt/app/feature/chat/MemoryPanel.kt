package com.molagpt.app.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.model.ByokMemoryProjection
import com.molagpt.app.core.model.MemorySection

/**
 * 会话内记忆面板。只回答一个问题：**这轮到底带了什么出去**。
 *
 * 是否使用记忆由 composer 的记忆 chip 在首轮之前决定，这里不再重复给开关——
 * 面板是在回答结束后打开的，那时候改开关既改不了已经发出去的内容，也容易让人误以为能撤回。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryPanel(
    projection: ByokMemoryProjection,
    memoryEnabled: Boolean,
    onOpenMemorySettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("记忆", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onOpenMemorySettings) { Text("管理") }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 10.dp),
                color = cs.outlineVariant.copy(alpha = 0.5f),
            )

            when {
                !memoryEnabled -> PanelHint("当前对话未使用记忆")
                projection.injected.isEmpty() -> PanelHint("暂无可用记忆")
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "本次使用 ${projection.injected.size} 条记忆",
                            style = MaterialTheme.typography.labelMedium,
                            color = cs.onSurface,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "已用 ${(projection.usage * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    if (projection.skipped > 0) {
                        Text(
                            "另有 ${projection.skipped} 条暂未使用",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        MemorySection.entries.forEach { section ->
                            val items = projection.injected.filter { it.section == section }
                            if (items.isEmpty()) return@forEach
                            Text(
                                section.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = cs.primary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            items.forEach { entry ->
                                Text(
                                    entry.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurface,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(cs.surfaceVariant.copy(alpha = 0.4f))
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.padding(bottom = 20.dp))
        }
    }
}

@Composable
private fun PanelHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}
