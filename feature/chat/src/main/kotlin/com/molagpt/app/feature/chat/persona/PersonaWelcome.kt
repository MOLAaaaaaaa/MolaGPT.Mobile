package com.molagpt.app.feature.chat.persona

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.model.Persona
import com.molagpt.app.core.render.MolaLogo
import com.molagpt.app.core.render.PersonaAvatar
import com.molagpt.app.core.render.PersonaIcons

/**
 * 新对话空白状态欢迎页。所有模型/模式都展示：logo + 欢迎语。
 * 仅 BYOK 模式额外显示当前角色选择入口（图中红框区域）。
 */
@Composable
fun PersonaWelcome(
    activePersona: Persona?,
    isByok: Boolean,
    /** 当前角色的卡片头像；没有就退回矢量图标。 */
    avatar: java.io.File? = null,
    onOpenPersonaPicker: () -> Unit,
    /** 替换欢迎语下的说明。 */
    subtitle: String? = null,
    /** 对话在哪儿运行，例如电脑名；显示为说明下方的一枚标签。 */
    location: String? = null,
    modifier: Modifier = Modifier,
    /** 欢迎区最下方的附加内容，如首页引导。 */
    footer: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MolaLogo(size = 96.dp, cornerRadius = 24.dp, contentDescription = "MolaGPT")
        Spacer(Modifier.height(20.dp))
        Text(
            text = "有什么可以帮助您？",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = subtitle ?: if (isByok) "可选择一个角色来定制回答风格" else "MolaGPT 会尽力提供帮助",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (location != null) {
            Spacer(Modifier.height(12.dp))
            LocationTag(location)
        }

        if (isByok) {
            Spacer(Modifier.height(32.dp))
            PersonaSelectorChip(
                persona = activePersona,
                avatar = avatar,
                onClick = onOpenPersonaPicker,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
        if (footer != null) {
            Spacer(Modifier.height(if (isByok) 16.dp else 28.dp))
            footer()
        }
    }
}

@Composable
private fun LocationTag(text: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(cs.primary))
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PersonaSelectorChip(
    avatar: java.io.File?,
    persona: Persona?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        PersonaAvatar(
            file = avatar,
            fallbackIcon = PersonaIcons.resolve(persona?.icon),
            size = 38.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = "当前角色",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
            )
            Text(
                text = persona?.name ?: "通用助手",
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Filled.ArrowDropDown,
            contentDescription = "选择角色",
            tint = cs.onSurfaceVariant,
        )
    }
}
