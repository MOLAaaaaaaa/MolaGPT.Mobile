package com.molagpt.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageWorkbenchScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val providerList by viewModel.byokProviderList.collectAsStateWithLifecycle()
    val imageState by viewModel.imageWorkbench.collectAsStateWithLifecycle()
    var selectedProviderId by rememberSaveable { mutableStateOf("") }
    var selectedModelId by rememberSaveable { mutableStateOf("") }
    val providers = providerList.filter { provider ->
        provider.enabled && provider.purpose == com.molagpt.app.core.model.ByokPurpose.IMAGE
    }
    val selectedProvider = providers.firstOrNull { it.id == selectedProviderId } ?: providers.firstOrNull()
    val providerModels = selectedProvider?.models.orEmpty().map { model ->
        model.copy(providerId = selectedProvider?.id.orEmpty(), providerName = selectedProvider?.name.orEmpty())
    }
    // image 用途 provider 的模型即为图像模型。
    val preferredImageModels = providerModels.filter { it.supportsImageGeneration }
    // OpenRouter 对话补全出图：image_config 参数在工作台本地调整（下方选择器）。
    val isChatImageFormat = selectedProvider?.imageFormat == com.molagpt.app.core.model.ByokImageFormat.OPENAI_CHAT_IMAGE
    var prompt by rememberSaveable { mutableStateOf("玻璃质感的 MolaGPT 标志，柔和自然光，白色背景") }
    var size by rememberSaveable { mutableStateOf("1024x1024") }
    var style by rememberSaveable { mutableStateOf("自然") }
    // image_config 出图参数（本地记住，跟随工作台而非模型）。
    var imageSize by rememberSaveable { mutableStateOf("1K") }
    var aspectRatio by rememberSaveable { mutableStateOf("1:1") }
    var reasoning by rememberSaveable { mutableStateOf(false) }
    var reasoningEffort by rememberSaveable { mutableStateOf("medium") }
    // 所选模型是否支持出图推理（GPT-5 Image / Gemini 3 Image 系列）。
    val supportsReasoning = selectedModelId.isNotBlank() &&
        com.molagpt.app.core.network.looksLikeByokImageReasoningModel(selectedModelId)

    LaunchedEffect(providers, selectedProviderId, selectedModelId) {
        if (selectedProvider == null) {
            selectedProviderId = ""
            selectedModelId = ""
            return@LaunchedEffect
        }
        if (selectedProviderId != selectedProvider.id) {
            selectedProviderId = selectedProvider.id
        }
        if (preferredImageModels.none { it.id == selectedModelId }) {
            selectedModelId = preferredImageModels.firstOrNull()?.id.orEmpty()
        }
    }

    Scaffold(
        modifier = modifier,
        // 底部手势条沉浸：内容延伸到导航栏后方，由滚动内容的 navigationBarsPadding 让出最后一屏，
        // 与其它二级页面一致（不给 bottom inset 填纯色）。
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text("图像绘制") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ImagePreviewPanel(
                imageUrl = imageState.result?.url,
                raw = imageState.result?.raw,
                loading = imageState.loading,
                error = imageState.error,
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("服务", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    if (providers.isEmpty()) {
                        Text(
                            "暂无图像模型",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        providers.forEach { provider ->
                            val providerImageModels = provider.models.filter { it.supportsImageGeneration }
                            SelectChip(
                                label = provider.name,
                                selected = provider.id == selectedProviderId,
                                onClick = {
                                    selectedProviderId = provider.id
                                    selectedModelId = providerImageModels.firstOrNull()?.id.orEmpty()
                                },
                            )
                        }
                    }
                    Text("模型", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        preferredImageModels
                            .forEach { model ->
                                SelectChip(
                                    label = model.displayName,
                                    selected = model.id == selectedModelId,
                                    onClick = { selectedModelId = model.id },
                                )
                            }
                    }
                    // 对话补全出图（OpenRouter）：image_config 参数在工作台直接调，本地记住。
                    if (isChatImageFormat) {
                        Text("尺寸", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            com.molagpt.app.core.model.ImageGenerationConfig.IMAGE_SIZES.forEach { sz ->
                                SelectChip(label = sz, selected = imageSize == sz, onClick = { imageSize = sz })
                            }
                        }
                        Text("宽高比", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            com.molagpt.app.core.model.ImageGenerationConfig.ASPECT_RATIOS.forEach { ar ->
                                SelectChip(label = ar, selected = aspectRatio == ar, onClick = { aspectRatio = ar })
                            }
                        }
                        if (supportsReasoning) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                androidx.compose.material3.Switch(checked = reasoning, onCheckedChange = { reasoning = it })
                                Text("推理强度", style = MaterialTheme.typography.bodyMedium)
                            }
                            if (reasoning) {
                                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    com.molagpt.app.core.model.ImageGenerationConfig.REASONING_EFFORTS.forEach { e ->
                                        SelectChip(label = e, selected = reasoningEffort == e, onClick = { reasoningEffort = e })
                                    }
                                }
                            }
                        }
                        OutlinedTextField(
                            value = style,
                            onValueChange = { style = it },
                            label = { Text("风格（可选）") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    } else {
                        // OPENAI_IMAGES 等传统出图：尺寸为 WxH 文本。
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = size,
                                onValueChange = { size = it },
                                label = { Text("尺寸") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = style,
                                onValueChange = { style = it },
                                label = { Text("风格") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        label = { Text("提示词") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        val statusText = imageState.error ?: imageState.status
                        statusText?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (imageState.error == null) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        } ?: Box(Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                viewModel.generateByokImage(
                                    providerId = selectedProviderId,
                                    modelId = selectedModelId,
                                    prompt = prompt,
                                    style = style,
                                    imageConfig = com.molagpt.app.core.model.ImageGenerationConfig(
                                        imageSize = imageSize,
                                        aspectRatio = aspectRatio,
                                        reasoning = reasoning && supportsReasoning,
                                        reasoningEffort = reasoningEffort,
                                    ),
                                )
                            },
                            enabled = prompt.isNotBlank() && selectedProviderId.isNotBlank() && selectedModelId.isNotBlank() && !imageState.loading,
                        ) {
                            Text("开始绘制", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImagePreviewPanel(
    imageUrl: String?,
    raw: String?,
    loading: Boolean,
    error: String?,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(188.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        cs.surface,
                        cs.primary.copy(alpha = 0.18f),
                        cs.secondaryContainer.copy(alpha = 0.72f),
                    ),
                ),
            ),
    ) {
        when {
            loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            !imageUrl.isNullOrBlank() -> {
                // Coil3 直接喂超大 data URI 渲染不稳——data:base64 先解码为 ByteArray，http(s) 仍直传。
                val model = remember(imageUrl) { decodeImageModel(imageUrl) }
                AsyncImage(
                    model = model,
                    contentDescription = "生成图片",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            !raw.isNullOrBlank() || !error.isNullOrBlank() -> Text(
                text = (error ?: raw).orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = if (error == null) cs.onSurfaceVariant else cs.error,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(18.dp),
            )
            else -> Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.55f)
                    .height(74.dp),
                shape = RoundedCornerShape(24.dp),
                color = cs.primary.copy(alpha = 0.18f),
                border = androidx.compose.foundation.BorderStroke(1.dp, cs.primary.copy(alpha = 0.20f)),
            ) {}
        }
    }
}

@Composable
private fun SelectChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.20f),
        ),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/**
 * 把出图结果转成 Coil 能稳定渲染的 model：
 * - `data:[mime];base64,xxx` → 解码为 ByteArray（Coil3 对 ByteArray 直出支持稳定，超大 data URI 字符串则常渲染失败）。
 * - http(s) URL / 其它 → 原样返回，交给 Coil 网络加载。
 */
private fun decodeImageModel(url: String): Any {
    if (!url.startsWith("data:")) return url
    val comma = url.indexOf(',')
    if (comma < 0) return url
    val meta = url.substring(0, comma)
    if (!meta.contains("base64", ignoreCase = true)) return url
    return runCatching {
        android.util.Base64.decode(url.substring(comma + 1), android.util.Base64.DEFAULT)
    }.getOrDefault(url)
}
