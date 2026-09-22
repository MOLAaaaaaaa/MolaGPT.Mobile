package com.molagpt.app.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import com.molagpt.app.core.render.SegmentedControl
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.molagpt.app.core.model.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ByokPricingActions(viewModel: SettingsViewModel, snackbar: SnackbarHostState) {
    val state by viewModel.pricingRefresh.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showReviews by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ModelPriceReview?>(null) }
    var saving by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { viewModel.fetchAllModelPrices() }, enabled = !state.busy) {
            Text(if (state.busy) "获取中…" else "获取模型价格")
        }
        TextButton(onClick = { viewModel.fetchAllModelPrices(force = true) }, enabled = !state.busy) { Text("强制刷新") }
    }
    state.cachedAt?.let {
        Text("价目更新：${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(it))}", style = MaterialTheme.typography.bodySmall)
    }
    state.result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (state.reviews.isNotEmpty()) {
        TextButton(onClick = { showReviews = true }, enabled = !state.busy) { Text("完善价格（${state.reviews.size}）") }
    }
    if (showReviews) {
        ModalBottomSheet(
            onDismissRequest = { if (!saving) { showReviews = false; editing = null } },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            contentWindowInsets = { WindowInsets(0) },
        ) {
            val review = editing
            if (review == null) {
                Column(Modifier.fillMaxHeight(0.88f).imePadding()) {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("完善价格", style = MaterialTheme.typography.titleLarge)
                            Text("${state.reviews.size} 个模型待确认", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { showReviews = false }) { Icon(Icons.Default.Close, "关闭") }
                    }
                    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("搜索模型或提供商") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp))
                    val filtered = state.reviews.filter { item ->
                        query.isBlank() || listOf(item.model.displayName, item.model.id, item.providerName).any { it.contains(query.trim(), true) }
                    }
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                    ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (filtered.isEmpty()) item { Text("没有匹配的模型", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(filtered, key = { "${it.providerId}/${it.model.id}" }) { item ->
                            Surface(onClick = { editing = item }, shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                        PricingModelTitle(item.model, item.providerName)
                                        Text(if (item.candidates.isEmpty()) "填写价格" else "${item.candidates.size} 个来源价格",
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Icon(Icons.Default.ChevronRight, "选择价格", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 8.dp).size(20.dp))
                                }
                            }
                        }
                    }
                }
            } else {
                val sourceCoverage = review.candidates.associate { candidate ->
                    candidate.providerKey to state.reviews.count { item ->
                        item.providerId == review.providerId && item.candidates.any { it.providerKey == candidate.providerKey }
                    }
                }
                PricingEditorContent(review.model, onDismiss = { editing = null }, candidates = review.candidates,
                    preferredSource = review.preferredSource, providerName = review.providerName, allowClear = false, saving = saving,
                    sourceCoverage = sourceCoverage, onSave = { pricing, sourceProviderKey, applyToMatchingModels ->
                        scope.launch {
                            saving = true
                            try {
                                viewModel.saveReviewedPrice(
                                    review,
                                    requireNotNull(pricing),
                                    sourceProviderKey,
                                    applyToMatchingModels,
                                )
                                editing = null
                                if (viewModel.pricingRefresh.value.reviews.isEmpty()) showReviews = false
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                snackbar.showSnackbar("保存失败：${e.message}")
                            } finally { saving = false }
                        }
                    })
            }
        }
    }

}

@Composable
private fun PricingModelTitle(model: ProviderModel, providerName: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(model.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(providerName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 104.dp).padding(horizontal = 7.dp, vertical = 3.dp))
        }
    }
}

private fun priceText(value: Double?): String = value?.let { java.math.BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() } ?: "—"

@Composable
private fun PricingEditorContent(
    model: ProviderModel,
    onDismiss: () -> Unit,
    onSave: (ModelPricing?, String?, Boolean) -> Unit,
    candidates: List<ModelsDevPrice> = emptyList(),
    preferredSource: String? = null,
    providerName: String,
    allowClear: Boolean = true,
    saving: Boolean = false,
    sourceCoverage: Map<String, Int> = emptyMap(),
) {
    val ordered = remember(candidates, preferredSource) { candidates.sortedBy { if (it.providerKey == preferredSource) 0 else 1 } }
    val initial = remember(model.id) { ordered.firstOrNull() }
    var selected by remember(model.id) { mutableStateOf(initial) }
    var custom by remember(model.id) { mutableStateOf(candidates.isEmpty()) }
    var applyToMatchingModels by remember(model.id) { mutableStateOf(false) }
    var query by remember(model.id) { mutableStateOf("") }
    val price = initial?.pricing ?: model.pricing
    var input by remember(model.id) { mutableStateOf(price?.input?.let(::priceText).orEmpty()) }
    var output by remember(model.id) { mutableStateOf(price?.output?.let(::priceText).orEmpty()) }
    var read by remember(model.id) { mutableStateOf(price?.cacheRead?.let(::priceText).orEmpty()) }
    var write by remember(model.id) { mutableStateOf(price?.cacheWrite?.let(::priceText).orEmpty()) }
    val fields = listOf(input, output, read, write)
    val clear = allowClear && fields.all { it.isBlank() }
    val valid = if (!custom) selected != null else clear || (input.isNotBlank() && output.isNotBlank() && fields.all {
        it.isBlank() || it.toDoubleOrNull()?.let { value -> value.isFinite() && value >= 0 } == true
    })
    val matchingModels = selected?.let { sourceCoverage[it.providerKey] } ?: 1
    Column(Modifier.fillMaxHeight(0.88f).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss, enabled = !saving) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            PricingModelTitle(model, providerName, Modifier.weight(1f))
        }
        if (model.displayName != model.id) Text(model.id, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        if (candidates.isNotEmpty()) SegmentedControl(options = listOf("quotes" to "来源价格", "custom" to "自定义"),
            selected = if (custom) "custom" else "quotes", onSelect = { custom = it == "custom" }, enabled = !saving,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp))
        Text("USD / 1M tokens", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        if (custom) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                fields.chunked(2).forEachIndexed { row, values ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        values.forEachIndexed { column, value ->
                            val index = row * 2 + column
                            OutlinedTextField(value = value, onValueChange = {
                                when (index) { 0 -> input = it; 1 -> output = it; 2 -> read = it; 3 -> write = it }
                            }, label = { Text(listOf("输入", "输出", "缓存读取", "缓存写入")[index]) },
                                supportingText = if (index >= 2) ({ Text("可选") }) else null,
                                singleLine = true, enabled = !saving, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (!valid) Text("输入和输出需同时填写，价格必须为非负数。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        } else {
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("搜索价格来源") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp))
            Row(Modifier.fillMaxWidth().padding(start = 62.dp, end = 34.dp, top = 10.dp, bottom = 6.dp)) {
                Text("来源", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("输入", Modifier.width(70.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("输出", Modifier.width(70.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val visible = ordered.filter { it.providerName.contains(query.trim(), true) || it.providerKey.contains(query.trim(), true) }
            LazyColumn(Modifier.weight(1f).selectableGroup(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (visible.isEmpty()) item { Text("没有匹配的来源", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(visible, key = { it.providerKey }) { candidate ->
                    val chosen = selected == candidate
                    Surface(shape = RoundedCornerShape(12.dp),
                        color = if (chosen) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceContainerLow,
                        border = if (chosen) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null) {
                        Column(Modifier.fillMaxWidth().selectable(selected = chosen, enabled = !saving, role = Role.RadioButton, onClick = {
                            selected = candidate
                            input = priceText(candidate.pricing.input)
                            output = priceText(candidate.pricing.output)
                            read = candidate.pricing.cacheRead?.let(::priceText).orEmpty()
                            write = candidate.pricing.cacheWrite?.let(::priceText).orEmpty()
                        }).padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = chosen, onClick = null, enabled = !saving, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(candidate.providerName, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal)
                                Text(priceText(candidate.pricing.input), Modifier.width(70.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                    style = MaterialTheme.typography.bodyMedium)
                                Text(priceText(candidate.pricing.output), Modifier.width(70.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            if (chosen) Text("缓存读取 ${priceText(candidate.pricing.cacheRead)} · 写入 ${priceText(candidate.pricing.cacheWrite)}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 28.dp, top = 8.dp))
                        }
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!custom && matchingModels > 1) {
                    Row(
                        Modifier.fillMaxWidth().toggleable(
                            value = applyToMatchingModels,
                            enabled = !saving,
                            role = Role.Checkbox,
                            onValueChange = { applyToMatchingModels = it },
                        ).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = applyToMatchingModels, onCheckedChange = null, enabled = !saving)
                        Text(
                            "同时应用提供商 ${selected?.providerName} 的价格到其他 ${matchingModels - 1} 个模型",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                Button(enabled = valid && !saving, onClick = {
                    val pricing = if (!custom) selected!!.pricing else if (clear) null else
                        ModelPricing(input.toDouble(), output.toDouble(), read.toDoubleOrNull(), write.toDoubleOrNull(), "manual")
                    onSave(
                        pricing,
                        selected?.providerKey?.takeUnless { custom },
                        !custom && matchingModels > 1 && applyToMatchingModels,
                    )
                }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(when {
                        saving -> "保存中…"
                        custom -> "保存价格"
                        matchingModels > 1 && applyToMatchingModels -> "应用到 $matchingModels 个模型"
                        else -> "使用此价格"
                    })
                }
            }
        }
    }
}
