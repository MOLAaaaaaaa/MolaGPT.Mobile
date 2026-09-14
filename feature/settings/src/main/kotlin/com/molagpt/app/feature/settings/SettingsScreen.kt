package com.molagpt.app.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
    onOpenLorebooks: () -> Unit,
    onOpenByokMemory: () -> Unit,
    onOpenPostProcessing: () -> Unit,
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

            SectionTitle("模型与对话")
            ModelAndConversationCard(
                providerCount = byokProviders.size,
                modelCount = byokProviders.sumOf { it.models.size },
                memoryEnabled = s.byokMemoryMasterEnabled,
                postProcessingEnabled = s.responsePostProcessingEnabled,
                activeRules = s.responseRegexRules.count { it.enabled },
                toolsEnabledCount = listOf(
                    s.byokMcpServers.any { it.enabled },
                    s.visionProxyEnabled,
                    s.imageGenEnabled,
                ).count { it },
                onOpenByokProviders = onOpenByokProviders,
                onOpenByokTools = onOpenByokTools,
                onOpenByokMemory = onOpenByokMemory,
                onOpenPostProcessing = onOpenPostProcessing,
            )

            SectionTitle("角色扮演")
            RoleplayCard(
                onOpenPersonaManagement = onOpenPersonaManagement,
                onOpenLorebooks = onOpenLorebooks,
            )

            SectionTitle("外观与输入")
            SettingsGroup {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SegmentedRow(
                        label = "主题",
                        options = listOf("auto" to "跟随系统", "light" to "浅色", "dark" to "深色"),
                        selected = s.themeMode,
                        onSelect = viewModel::setThemeMode,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                    ToggleRow(
                        label = "Enter 键发送",
                        checked = s.enterToSend,
                        onChange = viewModel::setEnterToSend,
                        subtitle = "关闭后按 Enter 键换行",
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                    ToggleRow(
                        label = "Agent 控制快捷入口",
                        checked = s.showAgentControlShortcut,
                        onChange = viewModel::setShowAgentControlShortcut,
                        subtitle = "显示在对话页顶部",
                        leadingIcon = Icons.Outlined.DesktopWindows,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                    ToggleRow(
                        label = "图像工作台快捷入口",
                        checked = s.showImageWorkbenchShortcut,
                        onChange = viewModel::setShowImageWorkbenchShortcut,
                        subtitle = "显示在对话页顶部",
                        leadingIcon = Icons.Filled.Palette,
                    )
                }
            }

            SectionTitle("远程控制")
            SettingsGroup {
                SettingsEntryRow(
                    icon = Icons.Outlined.DesktopWindows,
                    title = "Agent 控制",
                    subtitle = "远程查看和控制桌面端 Agent 会话",
                    onClick = onOpenAgentControl,
                )
            }

            SectionTitle("关于")
            SettingsGroup {
                SettingsEntryRow(
                    icon = Icons.Outlined.Info,
                    title = "关于 MolaGPT",
                    subtitle = "版本、开源项目与许可证",
                    onClick = onOpenAbout,
                )
            }

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

@Composable
private fun ModelAndConversationCard(
    providerCount: Int,
    modelCount: Int,
    memoryEnabled: Boolean,
    postProcessingEnabled: Boolean,
    activeRules: Int,
    toolsEnabledCount: Int,
    onOpenByokProviders: () -> Unit,
    onOpenByokTools: () -> Unit,
    onOpenByokMemory: () -> Unit,
    onOpenPostProcessing: () -> Unit,
) {
    SettingsGroup {
        SettingsEntryRow(
            icon = Icons.Outlined.Cloud,
            title = "模型服务",
            subtitle = if (providerCount == 0) "尚未添加服务" else "$providerCount 个服务 · $modelCount 个模型",
            onClick = onOpenByokProviders,
            showDivider = true,
        )
        SettingsEntryRow(
            icon = Icons.Outlined.Extension,
            title = "模型工具",
            subtitle = if (toolsEnabledCount == 0) "联网搜索、MCP、视觉理解与图像生成" else "$toolsEnabledCount 项已开启",
            onClick = onOpenByokTools,
            showDivider = true,
        )
        SettingsEntryRow(
            icon = Icons.Outlined.Memory,
            title = "本地记忆",
            subtitle = if (memoryEnabled) "已开启" else "未开启",
            onClick = onOpenByokMemory,
            showDivider = true,
        )
        SettingsEntryRow(
            icon = Icons.Outlined.Tune,
            title = "回答后处理",
            subtitle = when {
                !postProcessingEnabled -> "替换回答内容 · 已关闭"
                activeRules == 0 -> "替换回答内容 · 未启用规则"
                else -> "替换回答内容 · $activeRules 条规则已启用"
            },
            onClick = onOpenPostProcessing,
        )
    }
}

@Composable
private fun RoleplayCard(
    onOpenPersonaManagement: () -> Unit,
    onOpenLorebooks: () -> Unit,
) {
    SettingsGroup {
        SettingsEntryRow(
            icon = Icons.Outlined.Face,
            title = "角色管理",
            subtitle = "角色设定与角色卡",
            onClick = onOpenPersonaManagement,
            showDivider = true,
        )
        SettingsEntryRow(
            icon = Icons.AutoMirrored.Outlined.MenuBook,
            title = "世界书",
            subtitle = "角色背景与世界设定",
            onClick = onOpenLorebooks,
        )
    }
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun SettingsEntryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    showDivider: Boolean = false,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 16.dp, top = 13.dp, end = 16.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsRowIcon(icon)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            ForwardChevron()
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 58.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
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
