package com.molagpt.app.feature.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 固化格式的 Promo 卡：视觉头图 + 徽标 + 标题 + 说明 + 要点 + 单个主按钮。
 * 下期换内容只换数据，不换布局；布局要变另外起格式版本。
 */
data class PromoCard(
    val id: String,
    val icon: ImageVector,
    val badge: String,
    val kicker: String,
    val title: String,
    val body: String,
    val highlights: List<String>,
    val primaryLabel: String,
    val footerNote: String? = null,
)

object BuiltInPromos {
    /** 本期：角色卡与世界书上线说明。 */
    val RoleplayCardsV1 = PromoCard(
        id = "roleplay-cards-v1",
        icon = Icons.Filled.AutoStories,
        badge = "NEW",
        kicker = "角色扮演",
        title = "现已支持角色卡与世界书",
        body = "导入 SillyTavern 角色卡，用世界书还原完整人设与背景设定。",
        highlights = listOf(
            "支持 PNG、JSON、charX 角色卡，覆盖 V1 至 V3 规范",
            "会话列表直接显示当前使用的角色",
        ),
        primaryLabel = "立刻体验",
        footerNote = " ",
    )

    val all = listOf(RoleplayCardsV1)
}

/** Promo 卡底部弹窗：X / 手势 / 点遮罩关闭都算已读，由调用方落库。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromoCardSheet(
    card: PromoCard,
    onPrimary: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null,
        containerColor = cs.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(168.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(cs.primaryContainer, cs.secondaryContainer),
                        ),
                    ),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    drawCircle(
                        color = Color.White.copy(alpha = 0.35f),
                        radius = h * 0.62f,
                        center = Offset(w * 0.5f, h * 0.42f),
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.22f),
                        radius = h * 0.30f,
                        center = Offset(w * 0.82f, h * 0.30f),
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.28f),
                        radius = h * 0.10f,
                        center = Offset(w * 0.20f, h * 0.30f),
                    )
                    rotate(degrees = -18f) {
                        drawOval(
                            color = Color.White.copy(alpha = 0.55f),
                            topLeft = Offset(w * 0.18f, h * 0.18f),
                            size = Size(w * 0.64f, h * 0.52f),
                            style = Stroke(width = 3.dp.toPx()),
                        )
                    }
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(
                            Color.White.copy(alpha = 0.65f),
                        ),
                ) {
                    Icon(
                        card.icon,
                        contentDescription = null,
                        tint = cs.primary,
                        modifier = Modifier.size(38.dp),
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(cs.surface.copy(alpha = 0.75f)),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 16.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = card.badge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = cs.onPrimaryContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(cs.primaryContainer)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = card.kicker,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = cs.primary,
                    )
                }
                Text(
                    text = card.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = cs.onSurface,
                )
                Text(
                    text = card.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
                card.highlights.forEach { item ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = cs.primary,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = item,
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Button(
                    onClick = onPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                ) { Text(card.primaryLabel) }
                card.footerNote?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
