package com.molagpt.app.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.render.MolaScaffold
import com.molagpt.app.core.render.MolaTopBar
import com.molagpt.app.core.storage.SettingsStore
import com.molagpt.app.core.model.MembershipInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportScreen(
    settingsStore: SettingsStore,
    loggedIn: Boolean,
    membership: MembershipInfo?,
    openingMembership: Boolean,
    statusLoading: Boolean,
    statusError: String?,
    onOpenMembership: (String?) -> Unit,
    onRefreshMembership: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onRefreshMembership() }

    var snapshot by remember { mutableStateOf<SponsorsSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var reloadNonce by remember { mutableIntStateOf(0) }
    var products by remember { mutableStateOf<List<MembershipProduct>?>(null) }
    var productsError by remember { mutableStateOf(false) }
    var productsReloadNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        runCatching { settingsStore.addSeenPromoId(BuiltInPromos.AfdianSupportV1.id) }
    }

    LaunchedEffect(productsReloadNonce) {
        productsError = false
        fetchMembershipProducts().onSuccess { products = it }.onFailure { productsError = true }
    }

    LaunchedEffect(reloadNonce) {
        loading = true
        errorMessage = null
        fetchSponsors()
            .onSuccess { snapshot = it }
            .onFailure { errorMessage = it.message ?: "无法加载感谢名单" }
        loading = false
    }

    fun openExternal(url: String) {
        runCatching { uriHandler.openUri(url) }
            .onFailure { scope.launch { snackbarHostState.showSnackbar("无法打开浏览器") } }
    }

    MolaScaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            MolaTopBar(
                title = { Text("支持") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(inner)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "如果 MolaGPT 对你有帮助，欢迎支持项目的服务器、模型 API 与后续开发。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 2.dp),
            )

            SectionTitle("我的支持")
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = when {
                            !loggedIn -> "登录后查看支持者状态"
                            statusLoading -> "正在加载支持者状态"
                            statusError != null -> "支持者状态暂时无法获取"
                            membership != null -> membership.summary()
                            else -> "暂无支持者权益"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = {
                            if (loggedIn && statusError != null) onRefreshMembership()
                            else onOpenMembership(null)
                        },
                        enabled = !openingMembership && !statusLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            when {
                                openingMembership -> "正在打开…"
                                loggedIn && statusError != null -> "重试获取状态"
                                loggedIn -> "查看支持者状态"
                                else -> "登录并查看"
                            },
                        )
                    }
                }
            }

            SectionTitle("支持方案")

            val availableProducts = products
            if (availableProducts == null || availableProducts.isEmpty()) {
                Text(
                    text = if (productsError) "无法加载支持方案，请稍后重试" else if (availableProducts == null) "正在加载支持方案…" else "暂无支持方案",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (productsError) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { productsReloadNonce++ }) { Text("重试") }
                        OutlinedButton(onClick = { onOpenMembership(null) }) { Text("打开支持中心") }
                    }
                }
            } else {
                availableProducts.forEach { product ->
                    SupportPlanOption(
                        title = "${product.name} · ¥${product.price} / ${product.durationDays} 天",
                        subtitle = "享有对应可用模型和使用额度。",
                        badge = null,
                        highlighted = false,
                        onClick = { onOpenMembership(product.id) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = { openExternal(CommunityUrls.AfdianHomeUrl) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                    Text("支持主页", modifier = Modifier.padding(start = 7.dp))
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            runCatching { settingsStore.addSeenPromoId(BuiltInPromos.GitHubStarV1.id) }
                        }
                        openExternal(CommunityUrls.MobileGitHubUrl)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                ) {
                    GitHubGlyph(modifier = Modifier.size(17.dp))
                    Text("GitHub Star", modifier = Modifier.padding(start = 7.dp))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "感谢名单",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                val count = snapshot?.totalCount ?: 0
                if (count > 0) {
                    Text(
                        text = "已有 $count 位朋友支持项目",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(8.dp).size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(
                        onClick = { reloadNonce++ },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "刷新感谢名单",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            val current = snapshot
            when {
                current != null && current.items.isNotEmpty() -> {
                    SponsorListSurface(items = current.items)
                }
                loading -> {
                    Text(
                        text = "正在加载感谢名单…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                    )
                }
                errorMessage != null -> {
                    Text(
                        text = "无法加载感谢名单：$errorMessage",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                    )
                }
                else -> {
                    Text(
                        text = "暂无支持记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SupportPlanOption(
    title: String,
    subtitle: String,
    badge: String?,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = cs.surfaceVariant.copy(alpha = 0.34f),
        border = if (highlighted) BorderStroke(1.dp, cs.primary.copy(alpha = 0.40f)) else null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (badge != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = cs.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(cs.primaryContainer.copy(alpha = 0.7f))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = if (highlighted) cs.primary else cs.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SponsorListSurface(items: List<SponsorEntry>) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f),
    ) {
        Column {
            items.forEachIndexed { index, item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "支持日期：${item.donateDateText}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Text(
                        text = item.planName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (index != items.lastIndex) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                    )
                }
            }
        }
    }
}
