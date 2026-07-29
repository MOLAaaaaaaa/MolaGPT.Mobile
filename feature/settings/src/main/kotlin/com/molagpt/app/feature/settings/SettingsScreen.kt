package com.molagpt.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.molagpt.app.core.common.Logger
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.molagpt.app.core.model.ByokMcpServer
import com.molagpt.app.core.model.ByokProvider
import com.molagpt.app.core.model.ByokProviderType
import com.molagpt.app.core.model.ProviderKind
import com.molagpt.app.core.model.ProviderModel

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
    onBack: () -> Unit,
    onOpenMolaAccount: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenImageWorkbench: () -> Unit,
    onOpenAgentControl: () -> Unit,
    onOpenByokProviders: () -> Unit,
    onOpenByokTools: () -> Unit,
    onOpenPersonaManagement: () -> Unit,
    buildLabel: String,
    modifier: Modifier = Modifier,
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val byokProviders by viewModel.byokProviderList.collectAsStateWithLifecycle()
    val byokStatus by viewModel.byokStatus.collectAsStateWithLifecycle()

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
            // 账户入口置顶但只占一行：位置保留（Agent 控制硬依赖登录，入口要好找），
            // 体量收敛（原先是 52dp 头像 + 逐模型一行的配额表，吃掉整个首屏）。
            // 详情见 MolaAccountScreen。
            MolaAccountEntryCard(
                loggedIn = loggedIn,
                username = username,
                onClick = onOpenMolaAccount,
            )

            SectionTitle("自定义模型")
            ModelServiceCard(
                providerCount = byokProviders.size,
                modelCount = byokProviders.sumOf { it.models.size },
                onOpenByokProviders = onOpenByokProviders,
                onOpenPersonaManagement = onOpenPersonaManagement,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("对话工具")
            ByokToolsEntryCard(
                mcp = s.byokMcpServers.any { it.enabled },
                vision = s.visionProxyEnabled,
                image = s.imageGenEnabled,
                servers = s.byokMcpServers,
                onClick = onOpenByokTools,
            )

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
            SectionTitle("远程控制")
            AgentControlEntryCard(onOpenAgentControl = onOpenAgentControl)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("关于")
            AboutEntryCard(onOpenAbout = onOpenAbout)

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
/**
 * MolaGPT 账户入口卡：单行，副标题承担登录态显示。
 *
 * 本 App 走 BYOK 优先路线，账户域（账户信息 / 配额 / 云同步 / 个性化记忆 / 账户工具）
 * 整体收进 [MolaAccountScreen]。这里保留置顶位置但只占一行——**不要因为「降低存在感」
 * 就把入口藏起来或隐藏游客态**：Agent 控制以登录 JWT 为前置条件，游客必须找得到登录路径。
 */
@Composable
private fun MolaAccountEntryCard(
    loggedIn: Boolean,
    username: String?,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 16.dp, top = 13.dp, end = 16.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                PersonGlyph(
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("MolaGPT 账户", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (loggedIn) {
                        "${username ?: "已登录"} · 配额、云同步与个性化记忆"
                    } else {
                        "登录后解锁云同步与 Agent 控制"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            ForwardChevron()
        }
    }
}

/**
 * 「自定义模型」域卡片：把 BYOK 模型的「来源」与「行为」收进同一张卡，用分隔线分面。
 * - 上行 `自定义 API 模型`：模型从哪来（provider / 模型清单）。
 * - 下行 `角色管理`：这些模型怎么说话（系统提示 / 角色，仅 BYOK 生效）。
 * 结构对齐 `MolaAccountScreen` 里 TracksCard（开关/入口两行同卡）的既有范式。
 */
@Composable
private fun ModelServiceCard(
    providerCount: Int,
    modelCount: Int,
    onOpenByokProviders: () -> Unit,
    onOpenPersonaManagement: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenByokProviders)
                    .padding(start = 16.dp, top = 13.dp, end = 16.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TracksRowIcon(kind = TracksIconKind.Sparkles)
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("自定义 API 模型", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (providerCount == 0) "接入你自己的 OpenAI / Claude / Gemini 服务"
                        else "$providerCount 个服务 · $modelCount 个模型",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                ForwardChevron()
            }

            HorizontalDivider(
                modifier = Modifier.padding(start = 58.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenPersonaManagement)
                    .padding(start = 16.dp, top = 13.dp, end = 16.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TracksRowIcon(kind = TracksIconKind.Persona)
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("角色管理", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "管理自定义模型使用的系统提示词",
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

@Composable
private fun AgentControlEntryCard(onOpenAgentControl: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    Logger.d("AgentControl", "Agent control entry card clicked!")
                    onOpenAgentControl()
                }
                .padding(start = 16.dp, top = 13.dp, end = 16.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TracksRowIcon(kind = TracksIconKind.Info)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("Agent 控制", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "远程查看与控制电脑上的 Claude Code / Codex 会话",
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
}

@Composable
private fun AboutEntryCard(onOpenAbout: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenAbout)
                .padding(start = 16.dp, top = 13.dp, end = 16.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TracksRowIcon(kind = TracksIconKind.Info)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("关于 MolaGPT", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "版本、开源项目与许可证",
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
}

/**
 * BYOK 自定义工具入口卡：进入独立的 BYOK 自定义工具页（网络搜索、MCP、视觉理解、图像生成）。
 * MolaGPT 账户侧的对应能力（联网/网页/代码执行）已随账户域迁至 `MolaAccountScreen`，
 * 因此本页「对话工具」区现在只服务 BYOK。
 */
@Composable
private fun ByokToolsEntryCard(
    mcp: Boolean,
    vision: Boolean,
    image: Boolean,
    servers: List<ByokMcpServer>,
    onClick: () -> Unit,
) {
    val enabledCount = listOf(mcp, vision, image).count { it }
    val subtitle = buildString {
        append("对自定义模型生效")
        if (servers.isNotEmpty()) append(" · ${servers.count { it.enabled }} 个 MCP 已启用")
        if (enabledCount > 0) append(" · $enabledCount 项已开启")
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 16.dp, top = 13.dp, end = 16.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TracksRowIcon(kind = TracksIconKind.Sparkles)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("BYOK 自定义工具", style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            ForwardChevron()
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
