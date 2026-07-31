package com.molagpt.app.feature.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.graphicsLayer
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
 * 交互约定（方案 A）：
 *  - 有档位：胶囊拆成「主体」+「▾」。主体 = 开/关（开启用默认档，不弹层）；▾ = 打开强度抽屉。
 *  - 无档位 kind（KIMI）：退化为纯开关，无 ▾。
 *  - alwaysOn：主体不关推理，点主体或 ▾ 都打开强度抽屉。
 *  - 抽屉：离散滑杆 + 档位刻度；BYOK 时标题栏右侧齿轮直达模型推理设置。
 */
@Composable
internal fun ReasoningChip(
    checked: Boolean,
    effortLabel: String?,
    hasLevels: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onOpenLevels: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    val containerColor by animateColorAsState(
        targetValue = if (checked) colorScheme.primary.copy(alpha = 0.14f) else colorScheme.surfaceVariant.copy(alpha = 0.72f),
        animationSpec = MolaMotion.standard(MolaMotion.Short),
        label = "reasonChipContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            !enabled -> colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
            checked -> colorScheme.primary
            else -> colorScheme.onSurfaceVariant
        },
        animationSpec = MolaMotion.standard(MolaMotion.Short),
        label = "reasonChipContent",
    )
    val borderColor by animateColorAsState(
        targetValue = if (checked) colorScheme.primary.copy(alpha = 0.32f) else colorScheme.outline.copy(alpha = 0.12f),
        animationSpec = MolaMotion.standard(MolaMotion.Short),
        label = "reasonChipBorder",
    )

    Row(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clip(shape)
            .background(containerColor)
            .border(1.dp, borderColor, shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clickable(
                    enabled = enabled,
                    role = Role.Checkbox,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(),
                ) { onToggle() }
                .padding(
                    start = 12.dp,
                    end = if (hasLevels) 8.dp else 12.dp,
                    top = 6.dp,
                    bottom = 6.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = ReasoningBulbs.Outline,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(15.dp),
            )
            AnimatedContent(
                targetState = if (effortLabel != null) "推理 · $effortLabel" else "推理",
                transitionSpec = {
                    (fadeIn(MolaMotion.standard(MolaMotion.Short)) + slideInVertically(MolaMotion.standard(MolaMotion.Short)) { it / 3 }) togetherWith
                        (fadeOut(MolaMotion.standard(MolaMotion.Short)) + slideOutVertically(MolaMotion.standard(MolaMotion.Short)) { -it / 3 }) using
                        SizeTransform(clip = false)
                },
                label = "reasonChipLabel",
            ) { label ->
                Text(
                    text = label,
                    color = contentColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
        if (hasLevels) {
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(16.dp)
                    .background(contentColor.copy(alpha = 0.18f)),
            )
            Box(
                modifier = Modifier
                    .clickable(
                        enabled = enabled,
                        role = Role.Button,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(),
                    ) { onOpenLevels() }
                    .padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = ReasoningBulbs.Chevron,
                    contentDescription = "调整推理强度",
                    tint = contentColor.copy(alpha = 0.75f),
                    modifier = Modifier.size(13.dp),
                )
            }
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
    /** 非空时标题栏右侧展示齿轮，直达当前模型推理设置（通常仅 BYOK）。 */
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
                .padding(horizontal = 20.dp)
                .padding(bottom = 26.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 标题行：居中标题 + 右侧齿轮（有设置入口时）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "推理强度",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (onOpenModelSettings != null) {
                    IconButton(
                        onClick = {
                            onDismiss()
                            onOpenModelSettings()
                        },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "模型推理设置",
                            tint = colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            Text(
                "强度越高，思考越深入；回答更慢、更耗用量。",
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
            )

            val statusLabel = if (displayedOn) ThinkingKinds.effortLabel(displayedEffort) else "已关闭"
            val statusSub = when {
                !displayedOn -> "本次对话不进行推理"
                alwaysOn -> "该模型常开推理，拖动滑杆调整强度"
                else -> "拖动滑杆或点击档位调整强度"
            }
            AnimatedContent(
                targetState = Triple(statusLabel, displayedOn, statusSub),
                transitionSpec = {
                    (fadeIn(MolaMotion.decelerate(MolaMotion.Short)) +
                        slideInVertically(MolaMotion.decelerate(MolaMotion.Short)) { it / 4 }) togetherWith
                        (fadeOut(MolaMotion.standard(MolaMotion.Short)) +
                            slideOutVertically(MolaMotion.standard(MolaMotion.Short)) { -it / 4 }) using
                        SizeTransform(clip = false)
                },
                label = "reasonStatus",
            ) { (label, on, sub) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (on) colorScheme.primary else colorScheme.onSurface,
                        modifier = Modifier.heightIn(min = 28.dp),
                    )
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.labelMedium,
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = 2.dp, bottom = 14.dp)
                            .heightIn(min = 20.dp),
                    )
                }
            }

            ReasoningSlider(
                stopCount = n,
                selectedIndex = selectedIndex,
                onPreview = { previewIndex = it },
                onSnap = { idx ->
                    previewIndex = null
                    onPick(stops[idx])
                },
            )

            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                stops.forEachIndexed { i, stop ->
                    val sel = i == displayedIndex
                    val tickColor by animateColorAsState(
                        targetValue = if (sel) colorScheme.primary else colorScheme.outlineVariant,
                        animationSpec = MolaMotion.decelerate(MolaMotion.Short),
                        label = "reasonTick",
                    )
                    val tickWidth by animateDpAsState(
                        if (sel) 20.dp else 14.dp,
                        MolaMotion.springy(),
                        label = "reasonTickW",
                    )
                    val tickHeight by animateDpAsState(
                        if (sel) 6.dp else 4.dp,
                        MolaMotion.springy(),
                        label = "reasonTickH",
                    )
                    val labelColor by animateColorAsState(
                        targetValue = if (sel) colorScheme.primary else colorScheme.onSurfaceVariant,
                        animationSpec = MolaMotion.standard(MolaMotion.Short),
                        label = "reasonTickLabel",
                    )
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
                            fontWeight = FontWeight.SemiBold,
                            color = labelColor,
                            modifier = Modifier.height(16.dp),
                        )
                    }
                }
            }

            val atDefault = displayedOn && displayedEffort == defaultEffort
            val followAlpha by animateFloatAsState(
                if (atDefault) 0f else 1f,
                MolaMotion.standard(MolaMotion.Short),
                label = "reasonFollow",
            )
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
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

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
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
        }
    }
}

/**
 * 离散滑杆：拖动期间连续跟手；松手 / 点档位后用减速曲线平滑吸附。
 * 拖动中拇指略放大，松手回弹，手感更跟手。
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
    val settleSpec = remember { MolaMotion.decelerate<Float>(MolaMotion.Medium) }
    val thumbScale by animateFloatAsState(
        targetValue = if (dragging) 1.16f else 1f,
        animationSpec = MolaMotion.springy(),
        label = "reasonThumbScale",
    )

    LaunchedEffect(selectedIndex, stopCount) {
        if (!dragging) position.animateTo(selectedIndex.coerceIn(0, maxIndex).toFloat(), settleSpec)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
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
                        .size(28.dp)
                        .graphicsLayer {
                            scaleX = thumbScale
                            scaleY = thumbScale
                        }
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
 * 灯泡四态图标：Outline 用于胶囊；Chevron 用于强度入口。
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

    val Outline: ImageVector by lazy { build("Outline") {} }
    val Chevron: ImageVector by lazy {
        ImageVector.Builder(
            name = "ReasoningChevron",
            defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f,
        ).apply { stroked("M6 9l6 6 6-6", width = 2f) }.build()
    }
}
