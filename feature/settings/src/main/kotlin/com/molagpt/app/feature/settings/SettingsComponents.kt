package com.molagpt.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 设置相关屏幕共享组件。原先这些是 SettingsScreen 的 private 组件，BYOK 独立页面（列表/详情）
 * 与图像工作台都要复用，故提取为 internal，集中此处统一样式（玫瑰粉主题、M3 令牌着色）。
 */

/** 区块标题：主色（玫瑰粉）小标题，置于卡片分组上方。 */
@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
}

/** 开关行：标题 + 可选副标题 + Switch（统一白/暗白圆点配色）。 */
@Composable
internal fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

/** 胶囊选择 chip（圆角描边）。选中时主色描边+淡主色填充。 */
@Composable
internal fun SelectPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.20f),
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                selected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

internal enum class TracksIconKind { Sparkles, Info, Persona }

/** Canvas 绘制的 30dp 主色调圆角图标块。用于设置/BYOK 入口卡片的前导图标。 */
@Composable
internal fun TracksRowIcon(kind: TracksIconKind) {
    val tint = MaterialTheme.colorScheme.primary
    val container = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(19.dp)) {
            val stroke = 2.dp.toPx()
            when (kind) {
                TracksIconKind.Sparkles -> {
                    val cx = size.width * 0.43f
                    val cy = size.height * 0.43f
                    drawLine(tint, Offset(cx, size.height * 0.02f), Offset(cx, size.height * 0.84f), stroke)
                    drawLine(tint, Offset(size.width * 0.05f, cy), Offset(size.width * 0.82f, cy), stroke)
                    drawLine(tint, Offset(size.width * 0.18f, size.height * 0.18f), Offset(size.width * 0.68f, size.height * 0.68f), stroke)
                    drawLine(tint, Offset(size.width * 0.68f, size.height * 0.18f), Offset(size.width * 0.18f, size.height * 0.68f), stroke)
                    drawCircle(tint, radius = size.minDimension * 0.08f, center = Offset(size.width * 0.80f, size.height * 0.80f))
                }
                TracksIconKind.Info -> {
                    drawCircle(tint, radius = size.minDimension * 0.42f, center = center, style = Stroke(stroke))
                    drawCircle(tint, radius = size.minDimension * 0.045f, center = Offset(center.x, size.height * 0.31f))
                    drawLine(tint, Offset(center.x, size.height * 0.45f), Offset(center.x, size.height * 0.70f), stroke)
                }
                TracksIconKind.Persona -> {
                    drawCircle(tint, radius = size.minDimension * 0.16f, center = Offset(center.x, size.height * 0.30f), style = Stroke(stroke))
                    drawArc(
                        tint,
                        startAngle = 180f,
                        sweepAngle = 180f,
                        useCenter = false,
                        topLeft = Offset(size.width * 0.12f, size.height * 0.48f),
                        size = androidx.compose.ui.geometry.Size(size.width * 0.76f, size.height * 0.50f),
                        style = Stroke(stroke),
                    )
                }
            }
        }
    }
}

/** 前向（右向）箭头：复用返回箭头旋转 180°，用于「进入子页面」入口卡。 */
@Composable
internal fun ForwardChevron(modifier: Modifier = Modifier) {
    Icon(
        Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(20.dp).rotate(180f),
    )
}

/**
 * 简易人头剪影（Canvas 绘制，不依赖图标库）：头 + 肩。用于账户头像。
 * 设置页的账户入口行与账户页的 AccountHero 共用，故置于此。
 */
@Composable
internal fun PersonGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val headR = w * 0.20f
        drawCircle(color = color, radius = headR, center = Offset(w / 2f, h * 0.33f))
        val bodyW = w * 0.66f
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = Offset((w - bodyW) / 2f, h * 0.58f),
            size = androidx.compose.ui.geometry.Size(bodyW, bodyW),
        )
    }
}
