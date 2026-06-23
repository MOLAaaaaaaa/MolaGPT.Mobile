package com.molagpt.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.molagpt.app.core.model.ByokProvider
import com.molagpt.app.core.model.ByokProviderType
import java.util.UUID

/**
 * BYOK 提供商列表页（独立子页面，从设置页进入）。
 * 卡片展示每个自定义服务，点击进详情；FAB 打开预设选择底部弹层，选中即落库并跳详情。
 * 借鉴 rikkahub 的「列表 → 详情」结构，设计语言沿用 MolaGPT（玫瑰粉、圆角卡片、M3）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ByokProvidersScreen(
    viewModel: SettingsViewModel,
    onOpenDetail: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val providers by viewModel.byokProviderList.collectAsStateWithLifecycle()
    val status by viewModel.byokStatus.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showPresetSheet by remember { mutableStateOf(false) }

    LaunchedEffect(status) {
        status?.let {
            snackbar.showSnackbar(it)
            viewModel.clearByokStatus()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text("自定义 API 模型") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showPresetSheet = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.navigationBarsPadding(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "添加服务")
            }
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle("已添加的服务")
            if (providers.isEmpty()) {
                EmptyProvidersHint()
            } else {
                providers.forEach { provider ->
                    ProviderCard(
                        provider = provider,
                        onClick = { onOpenDetail(provider.id) },
                        onToggle = { enabled ->
                            viewModel.saveByokProvider(provider.copy(enabled = enabled))
                        },
                    )
                }
            }

            SectionTitle("说明")
            Surface(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
            ) {
                Text(
                    "支持任意 OpenAI 兼容接口（DeepSeek、Qwen、Moonshot、OneAPI 等）以及 Anthropic、" +
                        "Google Gemini 原生格式。添加并启用后，可在对话界面的模型选择器中切换使用，密钥仅保存在本机。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
    }

    if (showPresetSheet) {
        ModalBottomSheet(
            onDismissRequest = { showPresetSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            // 自带 inset 置 0，底部留白只由内容的 navigationBarsPadding 处理一次（否则全屏展开时底部多一条白条把内容顶出屏幕）。
            contentWindowInsets = { WindowInsets(0) },
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("添加服务", style = MaterialTheme.typography.titleLarge)
                Text(
                    "选择一个预设快速开始，或选「自定义」从空白创建。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                val presets = viewModel.byokPresets
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PresetGroup("OpenAI 格式 API", presets.filter {
                        it.purpose == com.molagpt.app.core.model.ByokPurpose.CHAT &&
                            (it.type == ByokProviderType.OPENAI_COMPAT || it.type == ByokProviderType.OPENAI_RESPONSE)
                    }) { preset ->
                        showPresetSheet = false
                        val id = "byok-${preset.id}-" + UUID.randomUUID().toString().replace("-", "").take(6)
                        viewModel.saveByokProvider(preset.copy(id = id))
                        onOpenDetail(id)
                    }
                    PresetGroup("Claude", presets.filter { it.type == ByokProviderType.ANTHROPIC }) { preset ->
                        showPresetSheet = false
                        val id = "byok-${preset.id}-" + UUID.randomUUID().toString().replace("-", "").take(6)
                        viewModel.saveByokProvider(preset.copy(id = id))
                        onOpenDetail(id)
                    }
                    PresetGroup("Gemini", presets.filter {
                        it.purpose == com.molagpt.app.core.model.ByokPurpose.CHAT && it.type == ByokProviderType.GEMINI
                    }) { preset ->
                        showPresetSheet = false
                        val id = "byok-${preset.id}-" + UUID.randomUUID().toString().replace("-", "").take(6)
                        viewModel.saveByokProvider(preset.copy(id = id))
                        onOpenDetail(id)
                    }
                    PresetGroup("图像服务", presets.filter {
                        it.purpose == com.molagpt.app.core.model.ByokPurpose.IMAGE
                    }) { preset ->
                        showPresetSheet = false
                        val id = "byok-${preset.id}-" + UUID.randomUUID().toString().replace("-", "").take(6)
                        viewModel.saveByokProvider(preset.copy(id = id))
                        onOpenDetail(id)
                    }
                    Text(
                        "自定义",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                    )
                    PresetRow(
                        ByokProvider(
                            id = "custom",
                            name = "自定义",
                            type = ByokProviderType.OPENAI_COMPAT,
                            baseUrl = "",
                        ),
                    ) {
                        showPresetSheet = false
                        val id = "byok-custom-" + UUID.randomUUID().toString().replace("-", "").take(8)
                        viewModel.saveByokProvider(
                            ByokProvider(id = id, name = "新服务", type = ByokProviderType.OPENAI_COMPAT, baseUrl = ""),
                        )
                        onOpenDetail(id)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(
    provider: ByokProvider,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 14.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderAvatar(provider)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        provider.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Box(
                        modifier = Modifier
                            .padding(start = 7.dp)
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                if (provider.enabled) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            ),
                    )
                }
                Text(
                    "${provider.models.size} 个模型 · ${protocolLabel(provider.type)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Switch(
                checked = provider.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                ),
            )
            ForwardChevron(modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun PresetGroup(
    title: String,
    presets: List<ByokProvider>,
    onSelect: (ByokProvider) -> Unit,
) {
    if (presets.isEmpty()) return
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
    presets.forEach { preset -> PresetRow(preset) { onSelect(preset) } }
}

@Composable
private fun PresetRow(preset: ByokProvider, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderAvatar(preset)
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(preset.name, style = MaterialTheme.typography.titleMedium)
            Text(
                preset.baseUrl.ifBlank { "从空白配置创建" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        ForwardChevron()
    }
}

@Composable
private fun ProviderAvatar(provider: ByokProvider) {
    val color = protocolColor(provider.type)
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            provider.name.firstOrNull()?.uppercase() ?: "?",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun EmptyProvidersHint() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("还没有自定义服务", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "点击右下角 + 接入你自己的 API",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

internal fun protocolLabel(type: ByokProviderType): String = when (type) {
    ByokProviderType.OPENAI_COMPAT -> "OpenAI 兼容"
    ByokProviderType.OPENAI_RESPONSE -> "OpenAI Response"
    ByokProviderType.ANTHROPIC -> "Anthropic"
    ByokProviderType.GEMINI -> "Gemini"
}

internal fun protocolColor(type: ByokProviderType): Color = when (type) {
    ByokProviderType.OPENAI_COMPAT -> Color(0xFF10A37F)
    ByokProviderType.OPENAI_RESPONSE -> Color(0xFF10A37F)
    ByokProviderType.ANTHROPIC -> Color(0xFFD4763B)
    ByokProviderType.GEMINI -> Color(0xFF4285F4)
}
