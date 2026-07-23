package com.molagpt.app.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.model.ThinkingConfig
import com.molagpt.app.core.model.ThinkingKinds
import com.molagpt.app.core.render.MolaMotion
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Composer「推理」胶囊 + 推理强度弹层。
 *
 * 交互约定：
 *  - 有档位的模型：胶囊显示「推理 · 高」，点按弹出强度面板；关闭态点按 = 开启并回默认档、随即弹层。
 *  - 无档位的 kind（KIMI / 遗留开关）：胶囊退化为纯开关，无 chevron、不弹层。
 *  - alwaysOn（如 Kimi K3）：无「关」停靠点，滑杆仅档位。
 *  - 弹层滑杆停靠点 =（可选「关」）+ 档位；「关」即 useThinking=false。
 *  - 预算类 kind 在技术细节折叠区展示映射 token；主界面只用中文档位。
 */
@Composable
internal fun ReasoningChip(
    checked: Boolean,
    effortLabel: String?,
    hasLevels: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    val containerColor by animateColorAsState(
        targetValue = if (checked) colorScheme.primary.copy(alpha = 0.14f) else colorScheme.surfaceVariant.copy(alpha = 0.72f),
        label = "reasonChipContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            !enabled -> colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
            checked -> colorScheme.primary
            else -> colorScheme.onSurfaceVariant
        },
        label = "reasonChipContent",
    )
    val borderColor = if (checked) colorScheme.primary.copy(alpha = 0.32f) else colorScheme.outline.copy(alpha = 0.12f)

    Row(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(shape)
            .background(containerColor)
            .border(1.dp, borderColor, shape)
            .clickable(
                enabled = enabled,
                role = if (hasLevels) Role.Button else Role.Checkbox,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
            ) { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = ReasoningBulbs.Outline,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = if (effortLabel != null) "推理 · $effortLabel" else "推理",
            color = contentColor,
            // 与 ToolChip（联网搜索 / 网页拉取）统一为 labelMedium。
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Medium,
        )
        if (hasLevels) {
            Icon(
                imageVector = ReasoningBulbs.Chevron,
                contentDescription = null,
                tint = contentColor.copy(alpha = 0.75f),
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/** null 档位值 = 「关」停靠点；alwaysOn 时 stops 不含 null。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReasoningSheet(
    config: ThinkingConfig,
    useThinking: Boolean,
    reasoningEffort: String,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
    /** 聚合网关 baseUrl：用于判断预算是否应展示为强度。空字符串 = 直连语义。 */
    baseUrl: String = "",
    /** 非空时展示「推理参数设置」入口（通常仅 BYOK 可编辑模型推理配置）。 */
    onOpenModelSettings: (() -> Unit)? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    val levels = remember(config) { ThinkingKinds.resolveEffortLevels(config) }
    val alwaysOn = config.alwaysOn
    val stops: List<String?> = remember(levels, alwaysOn) {
        if (alwaysOn) levels.map { it as String? } else listOf<String?>(null) + levels
    }
    val n = stops.size
    val selectedIndex = when {
        alwaysOn -> levels.indexOf(reasoningEffort).coerceAtLeast(0)
        !useThinking -> 0
        else -> (levels.indexOf(reasoningEffort) + 1).coerceAtLeast(1)
    }
    var previewIndex by remember(stops) { mutableStateOf<Int?>(null) }
    val displayedIndex = previewIndex ?: selectedIndex
    val displayedStop = stops.getOrNull(displayedIndex)
    val displayedOn = alwaysOn || displayedStop != null
    val displayedEffort = displayedStop ?: reasoningEffort
    val defaultEffort = remember(config) { ThinkingKinds.resolveDefaultEffort(config) }
    val isBudget = ThinkingKinds.showAsBudget(config, baseUrl)
    val wireKind = ThinkingKinds.wireKind(config.kind, baseUrl)
    val wireParam = ThinkingKinds.wireParamName(wireKind)
    var showTech by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        contentWindowInsets = { WindowInsets(0) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 26.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("推理强度", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "强度越高，思考越深入；回答更慢、更耗用量。",
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
            )

            val ratio = when {
                !displayedOn -> 0f
                alwaysOn -> displayedIndex / (n - 1).coerceAtLeast(1).toFloat()
                else -> (displayedIndex - 1) / (n - 2).coerceAtLeast(1).toFloat()
            }
            val bulb = when {
                !displayedOn -> ReasoningBulbs.Off
                ratio < 0.34f -> ReasoningBulbs.Low
                ratio < 0.75f -> ReasoningBulbs.Mid
                else -> ReasoningBulbs.High
            }
            val bulbTint by animateColorAsState(
                targetValue = if (displayedOn) colorScheme.primary else colorScheme.onSurfaceVariant,
                label = "reasonBulbTint",
            )
            // 固定占位：Crossfade 过渡帧若高度变化，会牵动 ModalBottomSheet 整页重测 → 抽屉抖一下。
            Box(
                modifier = Modifier.size(34.dp),
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(targetState = bulb, animationSpec = MolaMotion.standard(MolaMotion.Short), label = "reasonBulb") { icon ->
                    Icon(icon, contentDescription = null, tint = bulbTint, modifier = Modifier.size(34.dp))
                }
            }
            Text(
                text = if (displayedOn) ThinkingKinds.effortLabel(displayedEffort) else "已关闭",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .padding(top = 7.dp)
                    .heightIn(min = 28.dp),
            )
            Text(
                text = when {
                    !displayedOn -> "本次对话不进行推理"
                    alwaysOn -> "该模型常开推理，拖动滑杆调整强度"
                    else -> "拖动滑杆或点击档位调整强度"
                },
                style = MaterialTheme.typography.labelMedium,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 2.dp, bottom = 18.dp)
                    .heightIn(min = 20.dp),
            )

            ReasoningSlider(
                stopCount = n,
                selectedIndex = selectedIndex,
                onPreview = { previewIndex = it },
                onSnap = { idx ->
                    previewIndex = null
                    onPick(stops[idx])
                },
            )

            Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                stops.forEachIndexed { i, stop ->
                    val sel = i == displayedIndex
                    val tickColor by animateColorAsState(
                        targetValue = if (sel) colorScheme.primary else colorScheme.outlineVariant,
                        label = "reasonTick",
                    )
                    val tickWidth by animateDpAsState(if (sel) 20.dp else 16.dp, MolaMotion.emphasized(MolaMotion.Short), label = "reasonTickW")
                    val tickHeight by animateDpAsState(if (sel) 6.dp else 4.dp, MolaMotion.emphasized(MolaMotion.Short), label = "reasonTickH")
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(),
                            ) {
                                previewIndex = null
                                onPick(stop)
                            }
                            .padding(top = 8.dp, bottom = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // 外层固定 20×6：选中态只在框内缩放，避免整行高度跳动牵动抽屉。
                        Box(
                            modifier = Modifier.size(width = 20.dp, height = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = tickWidth, height = tickHeight)
                                    .clip(RoundedCornerShape(99.dp))
                                    .background(tickColor),
                            )
                        }
                        Text(
                            text = stop?.let(ThinkingKinds::effortLabel) ?: "关",
                            style = MaterialTheme.typography.labelSmall,
                            // 字重变化可能微变行高；固定字重 + 固定行高，选中态只改颜色。
                            fontWeight = FontWeight.SemiBold,
                            color = if (sel) colorScheme.primary else colorScheme.onSurfaceVariant,
                            modifier = Modifier.height(16.dp),
                        )
                    }
                }
            }

            // 恢复默认 + 技术细节：紧凑成组，避免与上方档位之间出现大片空白。
            val atDefault = displayedOn && displayedEffort == defaultEffort
            val followAlpha by animateFloatAsState(if (atDefault) 0f else 1f, MolaMotion.standard(MolaMotion.Short), label = "reasonFollow")
            // 固定紧凑高度 + alpha 动画：拖动跨过默认档时不改变抽屉高度（AnimatedVisibility 会引起抖动）。
            Box(
                modifier = Modifier
                    .padding(top = 14.dp)
                    .height(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "恢复默认（${ThinkingKinds.effortLabel(defaultEffort)}）",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.primary,
                    modifier = Modifier
                        .alpha(followAlpha)
                        .clip(RoundedCornerShape(50))
                        .clickable(
                            enabled = !atDefault,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(),
                        ) {
                            previewIndex = null
                            onPick(defaultEffort)
                        }
                        .padding(horizontal = 16.dp, vertical = 5.dp),
                )
            }

            // 技术细节折叠：wire 参数 / 预算 token 仅 power user 查看。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (showTech) "收起技术细节" else "使用的推理参数",
                    style = MaterialTheme.typography.labelMedium,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(),
                        ) { showTech = !showTech }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                AnimatedVisibility(
                    visible = showTech,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    val wireText = when {
                        !displayedOn -> "不发送推理参数"
                        isBudget -> {
                            val tokens = ThinkingKinds.budgetFor(config.kind, displayedEffort)
                            "$wireParam ≈ ${"%,d".format(tokens)} tokens"
                        }
                        ThinkingKinds.isAggregatingGateway(baseUrl) ->
                            "reasoning: { effort: \"$displayedEffort\" }"
                        else -> "$wireParam=$displayedEffort"
                    }
                    Text(
                        text = wireText,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )
                }
            }

            if (onOpenModelSettings != null) {
                HorizontalDivider(
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                    color = colorScheme.outline.copy(alpha = 0.12f),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(),
                            role = Role.Button,
                        ) {
                            onDismiss()
                            onOpenModelSettings()
                        }
                        .padding(horizontal = 4.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "推理参数设置",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colorScheme.onSurface,
                        )
                        Text(
                            text = "调整当前模型的推理设置",
                            style = MaterialTheme.typography.labelSmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

/**
 * 离散滑杆：交给 Material3 Slider 处理点击、拖动、触摸命中、手势竞争与无障碍。
 *
 * 关键点：不传 `steps`，让滑块位置在拖动期间保持连续、严格跟手——
 * 一旦传了 `steps`，Material3 会在拖动过程中把渲染位置实时吸附到最近档位，
 * 手指连续移动但滑块离散跳动，才会出现「发抖」且档位间无动画的问题。
 * 档位吸附只在松手（或外部切换档位）时通过 [Animatable] 平滑过渡完成。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReasoningSlider(
    stopCount: Int,
    selectedIndex: Int,
    onPreview: (Int?) -> Unit,
    onSnap: (Int) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val maxIndex = (stopCount - 1).coerceAtLeast(1)
    val position = remember(stopCount) {
        Animatable(selectedIndex.coerceIn(0, maxIndex).toFloat())
    }
    var dragging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val settleSpec = remember { MolaMotion.emphasized<Float>(MolaMotion.Short) }

    // 点下方档位、点「恢复默认」或外部切换模型时，平滑动画过去；拖动中不允许外部重组抢回位置。
    LaunchedEffect(selectedIndex, stopCount) {
        if (!dragging) position.animateTo(selectedIndex.coerceIn(0, maxIndex).toFloat(), settleSpec)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // 下方档位使用等宽单元格；把 Slider 端点缩进到首尾单元格中心，使滑块与文字严格对齐。
        // Material3 自身还会为 thumb 预留约 10.dp，因此在半单元格宽度上扣除该值。
        val endpointPadding = maxOf(0.dp, maxWidth / (stopCount * 2) - 10.dp)
        Slider(
            value = position.value,
            onValueChange = { raw ->
                dragging = true
                scope.launch { position.snapTo(raw.coerceIn(0f, maxIndex.toFloat())) }
                onPreview(raw.roundToInt().coerceIn(0, stopCount - 1))
            },
            onValueChangeFinished = {
                val snapped = position.value.roundToInt().coerceIn(0, stopCount - 1)
                dragging = false
                scope.launch { position.animateTo(snapped.toFloat(), settleSpec) }
                onSnap(snapped)
                onPreview(null)
            },
            enabled = stopCount > 1,
            valueRange = 0f..maxIndex.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = endpointPadding)
                .height(48.dp),
            thumb = {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(colorScheme.onPrimary),
                    )
                }
            },
            track = { state ->
                val fraction = (state.value / maxIndex.toFloat()).coerceIn(0f, 1f)
                Canvas(modifier = Modifier.fillMaxWidth().height(6.dp)) {
                    val radius = size.height / 2f
                    drawRoundRect(
                        color = colorScheme.surfaceVariant,
                        topLeft = Offset.Zero,
                        size = size,
                        cornerRadius = CornerRadius(radius),
                    )
                    if (fraction > 0f) {
                        drawRoundRect(
                            color = colorScheme.primary,
                            topLeft = Offset.Zero,
                            size = Size(size.width * fraction, size.height),
                            cornerRadius = CornerRadius(radius),
                        )
                    }
                }
            },
        )
    }
}

/**
 * 灯泡四态图标：Off 带斜杠、Low 小灯芯、Mid 大灯芯、High 满亮带光芒。
 */
private object ReasoningBulbs {
    private const val BULB = "M12 2a7 7 0 0 0-4 12.7c.6.5 1 1.3 1 2.1h6c0-.8.4-1.6 1-2.1A7 7 0 0 0 12 2z"
    private const val BASE = "M9 18h6M10 21h4"

    private fun build(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = "Reasoning$name",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply {
            stroked(BULB)
            stroked(BASE)
            block()
        }.build()

    private fun ImageVector.Builder.stroked(pathData: String, width: Float = 1.8f) {
        addPath(
            pathData = addPathNodes(pathData),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }

    private fun ImageVector.Builder.filled(pathData: String, alpha: Float = 1f) {
        addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black), fillAlpha = alpha)
    }

    private fun dot(r: Float) = "M12 ${9 - r}a$r $r 0 1 1 0 ${2 * r}a$r $r 0 1 1 0 ${-2 * r}z"

    val Outline: ImageVector by lazy { build("Outline") {} }
    val Off: ImageVector by lazy { build("Off") { stroked("M4 4l16 16") } }
    val Low: ImageVector by lazy { build("Low") { filled(dot(1.6f)) } }
    val Mid: ImageVector by lazy { build("Mid") { filled(dot(3f)) } }
    val High: ImageVector by lazy {
        build("High") {
            filled(BULB, alpha = 0.28f)
            filled(dot(3f))
            stroked("M12 0.6v1.6M4.5 3.5l1.2 1.2M19.5 3.5l-1.2 1.2M1.8 9.5h1.6M20.6 9.5h1.6", width = 1.6f)
        }
    }
    val Chevron: ImageVector by lazy {
        ImageVector.Builder(
            name = "ReasoningChevron",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply { stroked("M6 9l6 6 6-6", width = 2f) }.build()
    }
}
