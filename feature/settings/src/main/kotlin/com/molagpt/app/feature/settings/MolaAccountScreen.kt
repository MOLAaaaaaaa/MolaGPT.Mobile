package com.molagpt.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.molagpt.app.core.model.AccountStatus
import com.molagpt.app.core.model.QuotaItem
import com.molagpt.app.core.render.shimmer

/**
 * MolaGPT 账户页（二级页）。
 *
 * 本 App 的发展方向是 **BYOK 优先**（对齐 CherryStudio / RikkaHub），账户体系退居幕后：
 * 设置根页只保留一行入口，账户域的全部内容集中到这里——账户信息、配额、云同步、
 * 个性化记忆、MolaGPT 账户工具。
 *
 * 但账户**不能真的隐藏**：Agent 控制（`AgentControlService` 用登录 JWT 鉴权）、云同步、
 * 个性化记忆、MolaGPT 模型、图片上传都以它为前置条件。所以游客态下入口行照常显示，
 * 本页顶部给出「登录能解锁什么」的说明，而不是把入口藏起来。
 *
 * 配额对**游客同样可见**（`status.php` 走短 token，未登录也返回游客额度），不要因为
 * 「账户页」这个名字就把它塞进 `loggedIn` 分支里——那会让游客失去查看剩余额度的地方。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MolaAccountScreen(
    viewModel: SettingsViewModel,
    loggedIn: Boolean,
    username: String?,
    onOpenLogin: () -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit,
    onOpenPersonalization: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val statusLoading by viewModel.statusLoading.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()

    // 配额只在真正打开本页时才确保拉取（命中容器级缓存则不重拉）。
    // 改版前这行挂在设置页上，等于每次进设置都可能触发一次 status.php。
    LaunchedEffect(loggedIn) { viewModel.ensureStatus() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text("MolaGPT 账户") },
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

            if (!loggedIn) GuestBenefitsCard(onOpenLogin = onOpenLogin)

            SectionTitle("配额用量 · 今日")
            QuotaSection(status = status, loading = statusLoading, onRefresh = viewModel::refreshStatus)

            // 云同步与个性化记忆依赖账号，游客态隐藏。
            if (loggedIn) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionTitle("云同步")
                ToggleRow(
                    label = "使用云同步",
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

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionTitle("个性化记忆 · MolaGPT Tracks")
                TracksCard(
                    enabled = s.tracksEnabled,
                    onEnabledChange = viewModel::setTracks,
                    onOpenPersonalization = onOpenPersonalization,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("账户工具")
            MolaGptToolsSection(
                network = s.toolNetwork,
                steel = s.toolSteel,
                code = s.toolCode,
                onChange = viewModel::setTools,
            )
        }
    }
}

/**
 * 游客态说明卡：把「登录能解锁什么」讲清楚，同时点明不登录也能用 BYOK。
 */
@Composable
private fun GuestBenefitsCard(onOpenLogin: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("登录 MolaGPT 后可解锁", style = MaterialTheme.typography.bodyLarge)
            Column(
                modifier = Modifier.padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BenefitLine("Agent 控制", "在手机上接管桌面端会话")
                BenefitLine("云同步", "会话跨设备增量同步")
                BenefitLine("个性化记忆", "用户画像与回答风格")
                BenefitLine("MolaGPT 模型", "平台内置额度模型")
            }
            TextButton(
                onClick = onOpenLogin,
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Text("登录 MolaGPT")
            }
        }
    }
}

@Composable
private fun BenefitLine(title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 10.dp),
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
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
private fun TracksCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onOpenPersonalization: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, end = 14.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TracksRowIcon(kind = TracksIconKind.Sparkles)
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("启用个性化记忆", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "基于历史对话提供更连贯的个性化回答",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(start = 58.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenPersonalization)
                    .padding(start = 16.dp, top = 13.dp, end = 16.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TracksRowIcon(kind = TracksIconKind.Info)
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("管理个性化回答", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "用户画像、对话风格与隐私",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                ForwardChevron()
            }
        }
    }
}

/**
 * MolaGPT 账户工具卡片：联网搜索 / 网页拉取 / 代码执行——由 MolaGPT 服务端执行，
 * 仅对 MolaGPT 账户模型生效。BYOK 的对应能力在设置页「对话工具 › BYOK 自定义工具」。
 */
@Composable
private fun MolaGptToolsSection(
    network: Boolean,
    steel: Boolean,
    code: Boolean,
    onChange: (network: Boolean, steel: Boolean, code: Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TracksRowIcon(kind = TracksIconKind.Info)
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("MolaGPT 账户工具", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "对 MolaGPT 账户模型生效",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            ToggleRow("联网搜索", network, onChange = { onChange(it, steel, code) })
            ToggleRow("网页拉取", steel, onChange = { onChange(network, it, code) })
            ToggleRow("代码执行", code, onChange = { onChange(network, steel, it) })
        }
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
