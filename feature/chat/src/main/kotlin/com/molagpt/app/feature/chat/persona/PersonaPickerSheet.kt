package com.molagpt.app.feature.chat.persona

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.model.Persona
import com.molagpt.app.core.render.ImeDismissBackHandler
import com.molagpt.app.core.render.PersonaIcons

/**
 * 角色选择 Sheet。欢迎页和 Composer 都通过它选择/管理角色。
 *
 * @param selectedPersona 当前选中角色；null 表示未显式选择，内部回退到「通用助手」。
 * @param onSelect 选择角色（id）。
 * @param onManage 进入管理页。
 * @param onDismiss 关闭 sheet。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaPickerSheet(
    personas: List<Persona>,
    selectedPersona: Persona?,
    onSelect: (Persona) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    var query by remember { mutableStateOf("") }
    val normalized = query.trim().lowercase()
    val filtered = remember(personas, normalized) {
        if (normalized.isEmpty()) personas.sortedWith(compareByDescending<Persona> { it.pinned }.thenBy { it.sortOrder }.thenBy { it.name })
        else personas.filter {
            it.name.contains(query, ignoreCase = true) || it.systemPrompt.contains(query, ignoreCase = true)
        }.sortedWith(compareByDescending<Persona> { it.name.startsWith(query, ignoreCase = true) }.thenBy { it.name })
    }
    val defaultPersona = personas.firstOrNull { it.id == Persona.BUILTIN_DEFAULT_ID }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
        contentWindowInsets = { androidx.compose.foundation.layout.WindowInsets(0) },
    ) {
        // 键盘弹着时返回先收键盘，不关弹层（搜索框在这里）。
        ImeDismissBackHandler()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(
                    text = "选择角色",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onManage(); onDismiss() }) {
                    Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("管理")
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索角色") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
            )

            Spacer(Modifier.height(12.dp))

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp)
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "未找到角色",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.id }) { persona ->
                        PersonaPickerItem(
                            persona = persona,
                            selected = selectedPersona?.id == persona.id ||
                                (selectedPersona == null && persona.id == Persona.BUILTIN_DEFAULT_ID),
                            onClick = { onSelect(persona); onDismiss() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonaPickerItem(
    persona: Persona,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) cs.primary.copy(alpha = 0.12f) else cs.surfaceVariant.copy(alpha = 0.4f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(cs.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = PersonaIcons.resolve(persona.icon),
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = persona.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (persona.isBuiltin) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "内置",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(cs.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                text = persona.preview,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
