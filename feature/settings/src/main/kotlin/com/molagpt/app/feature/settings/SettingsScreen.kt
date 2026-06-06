package com.molagpt.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.molagpt.app.core.model.AccountStatus
import com.molagpt.app.core.model.QuotaItem
import com.molagpt.app.core.render.shimmer

/**
 * 设置页 = 个人中心 + 偏好。账户区登录态由 app 层注入（[loggedIn]/[username]），配额/userType 来自 VM 拉取的 status。
 * 仅用 MaterialTheme 令牌着色——整体配色由主题（Theme.kt）统一控制（前端样式优化批次）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    loggedIn: Boolean,
    username: String?,
    onOpenLogin: () -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit,
    onOpenPersonalization: () -> Unit,
    buildLabel: String,
    modifier: Modifier = Modifier,
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val statusLoading by viewModel.statusLoading.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()

    // 系统返回由 NavHost 统一处理，本页只提供顶栏返回入口。
    // 顶栏返回箭头仍调 onBack。
    // 登录态变化后重拉配额（游客/登录可用额度不同）。
    LaunchedEffect(loggedIn) { viewModel.refreshStatus() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // 底部系统栏由滚动内容消费，页面背景保持铺满。
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
                .padding(16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AccountHero(
                loggedIn = loggedIn,
                username = username,
                status = status,
                onOpenLogin = onOpenLogin,
                onLogout = onLogout,
            )

            SectionTitle("配额用量 · 今日")
            QuotaSection(status = status, loading = statusLoading, onRefresh = viewModel::refreshStatus)

            // 云同步与个性化记忆依赖账号，游客态隐藏。
            if (loggedIn) {
                SectionTitle("云同步")
                ToggleRow(
                    label = "自动云同步",
                    checked = s.cloudSyncEnabled,
                    onChange = viewModel::setCloudSync,
                    enabled = !syncing,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (s.lastSyncAt > 0L) "上次同步：${relativeTime(s.lastSyncAt)}" else "尚未同步",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (syncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
                TextButton(
                    onClick = viewModel::syncNow,
                    enabled = s.cloudSyncEnabled && !syncing,
                ) {
                    Text(if (syncing) "同步中…" else "立即同步")
                }

                SectionTitle("个性化记忆 · MolaGPT Tracks")
                ToggleRow("启用个性化记忆", s.tracksEnabled, viewModel::setTracks)
                NavRow(
                    title = "管理个性化回答",
                    subtitle = "用户画像、对话风格与隐私",
                    onClick = onOpenPersonalization,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("对话")
            ToggleRow("代码执行", s.toolCode, onChange = { viewModel.setTools(s.toolNetwork, s.toolSteel, it) })

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("外观与输入")
            SegmentedRow(
                label = "主题",
                options = listOf("auto" to "跟随系统", "light" to "浅色", "dark" to "深色"),
                selected = s.themeMode,
                onSelect = viewModel::setThemeMode,
            )
            ToggleRow("Enter 发送（关闭则换行）", s.enterToSend, viewModel::setEnterToSend)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                text = buildLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AccountHero(
    loggedIn: Boolean,
    username: String?,
    status: AccountStatus?,
    onOpenLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    val registered = loggedIn && (status?.isRegistered ?: true)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            PersonGlyph(
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(30.dp),
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                text = if (loggedIn) (username ?: "已登录账号") else "游客",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = when {
                    registered -> "注册用户 · 已解锁更多模型"
                    loggedIn -> "已登录"
                    else -> "登录后可跨设备同步、解锁更多模型"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (loggedIn) {
            TextButton(onClick = onLogout) { Text("退出") }
        } else {
            TextButton(onClick = onOpenLogin) { Text("登录") }
        }
    }
}

@Composable
private fun QuotaSection(status: AccountStatus?, loading: Boolean, onRefresh: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = when {
                status == null && loading -> "正在获取配额…"
                status == null -> "未获取到配额"
                status.quotas.isEmpty() -> "暂无配额信息"
                else -> "额度每日重置"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = onRefresh) { Text("刷新") }
        }
    }
    // 首次加载(还没拿到配额)时显示骨架微光占位。
    if (status == null && loading) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .shimmer(),
            )
        }
    }
    status?.quotas?.forEach { q -> QuotaRow(q) }
}

@Composable
private fun QuotaRow(q: QuotaItem) {
    val valueText = when {
        q.used != null && q.limit != null -> "${q.used} / ${q.limit}"
        q.used != null && q.unlimited -> "${q.used} / 无限"
        !q.available -> "不可用"
        q.unlimited -> "无限"
        else -> "剩余 ${q.remaining}"
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(q.displayName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodySmall,
                color = if (!q.available) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 仅在上限/已用均已知时画进度条。
        q.usedFraction?.let { f ->
            LinearProgressIndicator(
                progress = { f },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun SegmentedRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
        com.molagpt.app.core.render.SegmentedControl(
            options = options,
            selected = selected,
            onSelect = onSelect,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
}

/** 可点击的导航行（进入下钻页）。前进箭头复用 ArrowBack 旋转 180°（material-icons-core 无 ArrowForward）。 */
@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp).rotate(180f),
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            // 圆点统一白/暗白(关态用 onSurfaceVariant，暗色下为浅灰，避免默认 outline 看不清)。
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

/** 简易人头剪影(Canvas 绘制,不依赖图标库)：头 + 肩。用于账户头像。 */
@Composable
private fun PersonGlyph(color: Color, modifier: Modifier = Modifier) {
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
            size = Size(bodyW, bodyW),
        )
    }
}

/** 粗粒度相对时间（避免引入日期库）。注意：与 Date 无关，仅用当前毫秒差。 */
private fun relativeTime(ms: Long): String {
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        else -> "${diff / 86_400_000} 天前"
    }
}
