package com.molagpt.app.feature.agentcontrol.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.molagpt.app.core.model.DesktopConversation
import com.molagpt.app.core.render.MolaScaffold
import com.molagpt.app.core.render.MolaTopBar
import com.molagpt.app.feature.agentcontrol.MonitorIcon
import com.molagpt.app.feature.agentcontrol.Pill
import com.molagpt.app.feature.agentcontrol.agentPalette
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/** 「电脑」页：开启了远控的电脑与它们最近的对话；外部 Agent（Claude Code / Codex）在最下面。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopsScreen(
    vm: DesktopsViewModel,
    onOpenConversation: (machineId: String, conversationId: String) -> Unit,
    onOpenExternalAgents: () -> Unit,
    onExit: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var anonPairing by remember { mutableStateOf(false) }

    MolaScaffold(
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            MolaTopBar(
                title = { Text("电脑", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    if (state.refreshing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, contentDescription = "刷新") }
                },
            )
        },
    ) { inner ->
        val layoutDirection = LocalLayoutDirection.current
        // 顶栏留白放进 contentPadding，列表才能滚到玻璃顶栏下面。
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = inner.calculateStartPadding(layoutDirection), end = inner.calculateEndPadding(layoutDirection)),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = inner.calculateTopPadding() + 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            state.error?.let { message ->
                item(key = "error") { Hint(message, error = true) }
            }
            item(key = "anon-add") {
                val cs = MaterialTheme.colorScheme
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(cs.primary.copy(alpha = 0.08f))
                        .clickable { anonPairing = true }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("用配对码添加电脑", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "电脑端 设置 → 远程控制 → 配对新手机",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
            if (state.loaded && state.desktops.isEmpty()) {
                item(key = "empty") {
                    Hint("请在电脑端设置 → 远程控制中启用手机控制。")
                }
            }
            state.desktops.forEach { row ->
                item(key = "machine:" + row.info.machineId) {
                    MachineHeader(
                        row,
                        pairing = state.pairingMachineId == row.info.machineId,
                        canPair = state.pairingMachineId == null && row.online,
                        onPair = { vm.pairWithAccount(row.info.machineId) },
                        onPairWithCode = { anonPairing = true },
                        onForget = { if (row.anon) vm.forgetAnon(row.info.machineId) else vm.forget(row.info.machineId) },
                    )
                }
                // 未配对的电脑也列出对话：点开时先用账号配对，与侧边栏一致。
                item(key = "conversations:" + row.info.machineId) {
                    val conversations = row.info.conversations
                    if (conversations.isEmpty()) {
                        Hint("暂无 Work / BYOK 对话")
                    } else {
                        ConversationGroup(
                            conversations = conversations,
                            online = row.online,
                            enabled = state.pairingMachineId == null && (row.paired || row.online),
                        ) { conversation ->
                            vm.openConversation(row, conversation.id) { onOpenConversation(row.info.machineId, it) }
                        }
                    }
                }
            }
            // 首次加载完再放：否则列表开头只有它，电脑与对话插到它前面时，
            // LazyColumn 按 key 保持首个可见项，页面会停在最底部。
            if (state.loaded) item(key = "external") {
                Surface(
                    onClick = onOpenExternalAgents,
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).navigationBarsPadding(),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Claude Code / Codex", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "外部 Agent 会话（实验）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (anonPairing) {
        AnonPairDialog(
            onPair = { code, confirmSas, done -> vm.pairAnon(code, confirmSas, done) },
            onDismiss = { anonPairing = false },
        )
    }
}

private const val PAIR_CODE_LENGTH = 8

/** 只留配对码字母表里的字符，易混的 O、I、L 按 0、1 读，最多 8 位。 */
private fun pairCodeInput(input: String): String = buildString {
    for (raw in input.uppercase()) {
        val c = when (raw) {
            'O' -> '0'
            'I', 'L' -> '1'
            else -> raw
        }
        if (c in "0123456789ABCDEFGHJKMNPQRSTVWXYZ" && length < PAIR_CODE_LENGTH) append(c)
    }
}

/** 显示为 XXXX-XXXX；分隔符只在显示层，输入值始终是 8 位纯字符。 */
private object PairCodeTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw.length <= 4) return TransformedText(text, OffsetMapping.Identity)
        return TransformedText(
            AnnotatedString(raw.take(4) + "-" + raw.drop(4)),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = if (offset <= 4) offset else offset + 1
                override fun transformedToOriginal(offset: Int) = if (offset <= 4) offset else offset - 1
            },
        )
    }
}

/** 配对码配对：输入配对码 → 两端各显示一个校验码 → 手机和电脑都确认一致。 */
@Composable
private fun AnonPairDialog(
    onPair: suspend (code: String, confirmSas: suspend (String) -> Boolean, done: (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var sas by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    // 校验码显示期间等用户回答；回答后转为等待电脑端允许。
    var answer by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val scope = rememberCoroutineScope()
    val codeStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
    val canSubmit = !working && code.length == PAIR_CODE_LENGTH
    val submit: () -> Unit = {
        working = true
        error = null
        scope.launch {
            onPair(
                code,
                { shown ->
                    val question = CompletableDeferred<Boolean>()
                    sas = shown
                    answer = question
                    question.await()
                },
                { failure ->
                    answer = null
                    if (failure == null) onDismiss()
                    else {
                        sas = null
                        error = failure
                    }
                },
            )
            working = false
        }
    }
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(if (sas == null) "用配对码添加电脑" else "请核对校验码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (sas == null) {
                    Text(
                        "输入电脑端「配对新手机」显示的 8 位配对码",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = pairCodeInput(it); error = null },
                        singleLine = true,
                        enabled = !working,
                        placeholder = {
                            Text(
                                "XXXX-XXXX",
                                style = codeStyle,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        textStyle = codeStyle.copy(textAlign = TextAlign.Center),
                        visualTransformation = PairCodeTransformation,
                        // 密码类键盘让中文输入法停在英文字母，不进拼音。
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { if (canSubmit) submit() }),
                        isError = error != null,
                        supportingText = error?.let { { Text(it) } },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    val asking = answer != null
                    Text(
                        if (asking) "与电脑上显示的校验码一致吗？"
                        else "在电脑上点「允许」完成配对",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        sas!!.chunked(3).joinToString(" "),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    if (!asking) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            val pending = answer
            if (pending != null) {
                TextButton(onClick = { pending.complete(true); answer = null }) { Text("一致") }
            } else if (sas == null) {
                TextButton(
                    enabled = canSubmit,
                    onClick = submit,
                ) { Text(if (working) "配对中…" else "配对") }
            }
        },
        dismissButton = {
            val pending = answer
            if (pending != null) {
                TextButton(onClick = { pending.complete(false); answer = null }) { Text("不一致") }
            } else {
                TextButton(enabled = !working, onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun MachineHeader(
    row: DesktopRow,
    pairing: Boolean,
    canPair: Boolean,
    onPair: () -> Unit,
    onPairWithCode: () -> Unit,
    onForget: () -> Unit,
) {
    val p = agentPalette()
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonitorIcon(MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(row.info.machineName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (!row.online && row.info.lastSeenAtMs > 0) {
                Text(
                    "最近在线 · " + relativeTime(row.info.lastSeenAtMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Pill(
            if (row.online) "在线" else "离线",
            if (row.online) p.green else MaterialTheme.colorScheme.onSurfaceVariant,
            if (row.online) p.greenSoft else MaterialTheme.colorScheme.surfaceVariant,
        )
        if (!row.paired) {
            Spacer(Modifier.width(6.dp))
            val cs = MaterialTheme.colorScheme
            Text(
                if (pairing) "配对中…" else "一键配对",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (canPair || pairing) cs.primary else cs.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(cs.primary.copy(alpha = if (canPair || pairing) 0.12f else 0.05f))
                    .clickable(enabled = canPair, onClick = onPair)
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "更多") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (!row.paired) {
                    DropdownMenuItem(
                        text = { Text("使用配对码") },
                        onClick = {
                            menu = false
                            onPairWithCode()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(if (row.paired) "移除并取消配对" else "从列表移除") },
                    onClick = {
                        menu = false
                        onForget()
                    },
                )
            }
        }
    }
}

/** 一台电脑的对话，收在一张卡片里，行间细线分隔。 */
@Composable
private fun ConversationGroup(
    conversations: List<DesktopConversation>,
    online: Boolean,
    enabled: Boolean,
    onOpen: (DesktopConversation) -> Unit,
) {
    val p = agentPalette()
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = cs.surface,
        border = BorderStroke(1.dp, cs.outline.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            conversations.forEachIndexed { index, conversation ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = 14.dp), color = cs.outline.copy(alpha = 0.35f))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled) { onOpen(conversation) }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            conversation.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (online) cs.onSurface else cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(conversation.modelLabel, relativeTime(conversation.updatedAtMs)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    when {
                        online && conversation.pendingApprovals > 0 -> Pill("等待审批", p.amber, p.amberSoft)
                        online && conversation.busy -> Pill("生成中", p.blue, p.blueSoft)
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

internal fun relativeTime(ms: Long): String {
    if (ms <= 0) return ""
    val diff = (System.currentTimeMillis() - ms).coerceAtLeast(0)
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes} 分钟前"
        minutes < 24 * 60 -> "${minutes / 60} 小时前"
        minutes < 7 * 24 * 60 -> "${minutes / (24 * 60)} 天前"
        else -> java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA).format(java.util.Date(ms))
    }
}
