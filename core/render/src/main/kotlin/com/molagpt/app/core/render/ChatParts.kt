package com.molagpt.app.core.render

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * 聊天页与画图工作台共用的外观组件。尺寸、圆角、透明度以聊天页为准，
 * 两边任何一处要改，改这里。
 */

/** 输入区外壳：圆角描边卡片，无阴影。 */
@Composable
fun ComposerSurface(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(22.dp),
        color = colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.34f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/**
 * 输入区工具行的胶囊。[checked] 表示开关态；只当按钮用（弹菜单、开面板）时传 false、
 * 用 [trailingIcon] 标出下拉。
 */
@Composable
fun ToolChip(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    role: Role = Role.Checkbox,
) {
    val colorScheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    val containerColor by animateColorAsState(
        targetValue = when {
            checked -> colorScheme.primary.copy(alpha = 0.14f)
            else -> colorScheme.surfaceVariant.copy(alpha = 0.72f)
        },
        label = "toolChipContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            !enabled -> colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
            checked -> colorScheme.primary
            else -> colorScheme.onSurfaceVariant
        },
        label = "toolChipContent",
    )
    val borderColor = if (checked) {
        colorScheme.primary.copy(alpha = 0.32f)
    } else {
        colorScheme.outline.copy(alpha = 0.12f)
    }

    Row(
        modifier = modifier
            .heightIn(min = 32.dp)
            .clip(shape)
            .background(containerColor)
            .border(1.dp, borderColor, shape)
            .clickable(
                enabled = enabled,
                role = role,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            )
            .padding(
                start = if (leadingIcon != null) 9.dp else 11.dp,
                end = if (trailingIcon != null) 7.dp else 11.dp,
                top = 7.dp,
                bottom = 7.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(leadingIcon, contentDescription = null, tint = contentColor, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = label,
            color = contentColor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
        )
        if (trailingIcon != null) {
            Spacer(Modifier.width(2.dp))
            Icon(trailingIcon, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
        }
    }
}

/** 输入区底部的圆形按钮（附件、发送、停止）。 */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color? = null,
    contentColor: Color? = null,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val targetContainer = containerColor ?: if (selected) {
        colorScheme.primary.copy(alpha = 0.14f)
    } else {
        colorScheme.surfaceVariant.copy(alpha = 0.72f)
    }
    val targetContent = contentColor ?: if (selected) {
        colorScheme.primary
    } else {
        colorScheme.onSurfaceVariant
    }
    val animatedContainer by animateColorAsState(targetContainer, label = "roundButtonContainer")
    val animatedContent by animateColorAsState(
        if (enabled) targetContent else targetContent.copy(alpha = 0.48f),
        label = "roundButtonContent",
    )

    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(animatedContainer)
            .border(1.dp, colorScheme.outline.copy(alpha = 0.12f), CircleShape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(18.dp),
            tint = animatedContent,
        )
    }
}

/** 用户气泡的底：淡品牌底色、细边框、右上角收尖。 */
@Composable
fun UserBubble(
    modifier: Modifier = Modifier,
    maxWidth: Dp = 320.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomEnd = 16.dp, bottomStart = 16.dp)
    Box(modifier = modifier, contentAlignment = Alignment.CenterEnd) {
        Column(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .clip(shape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            content = content,
        )
    }
}

/** 消息下方的文字操作（复制、重新生成……）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActionChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.4f),
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** 版本切换栏：‹ n/m ›。 */
@Composable
fun RetryBar(
    current: Int,
    total: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RetryArrow("‹", enabled = current > 0, onClick = onPrev)
        Text(
            text = "${current + 1}/$total",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RetryArrow("›", enabled = current < total - 1, onClick = onNext)
    }
}

@Composable
private fun RetryArrow(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = glyph,
        style = MaterialTheme.typography.titleMedium,
        color = if (enabled) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        },
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
