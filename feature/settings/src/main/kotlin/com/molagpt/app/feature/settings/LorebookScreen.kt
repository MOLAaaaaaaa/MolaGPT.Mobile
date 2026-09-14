package com.molagpt.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.molagpt.app.core.model.LoreEntry
import com.molagpt.app.core.model.LoreIds
import com.molagpt.app.core.model.Lorebook
import com.molagpt.app.core.render.ImeDismissBackHandler
import com.molagpt.app.core.storage.LorebookRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 设置 → 世界书。
 *
 * 这里的世界书**不属于任何一张角色卡**：建一次，可以被多个角色同时引用（角色编辑页里勾选）。
 * 角色卡自带的那本仍然跟着卡走，在角色编辑页里改，不出现在这张列表上。
 *
 * 条目编辑复用角色卡那一套（[LoreEntrySheet]），所以两边的字段、默认值和「高级选项」完全一致，
 * 不会出现同一个概念在两个入口下表现不同。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LorebookScreen(
    repository: LorebookRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val books by repository.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    var editing by remember { mutableStateOf<Pair<String, LoreEntry>?>(null) }
    var deleting by remember { mutableStateOf<Lorebook?>(null) }

    fun update(id: String, change: (Lorebook) -> Lorebook) {
        scope.launch { repository.update(id, change) }
    }

    ImeDismissBackHandler()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text("世界书") },
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
                .padding(horizontal = 16.dp)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            Text(
                "在角色设定中引用，可供多个角色共用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            if (books.isEmpty()) {
                Text(
                    "暂无世界书",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                )
            }

            // 按 id 绑定：删掉中间一本时，后面几本的展开状态与输入框不会跟着错位。
            books.forEach { book ->
                key(book.id) {
                    BookCard(
                        book = book,
                        onChange = { change -> update(book.id, change) },
                        onEditEntry = { entryId -> editing = book.id to book.entries.first { it.id == entryId } },
                        onAddEntry = {
                            val entry = LoreEntry(id = LoreIds.entry())
                            editing = book.id to entry
                        },
                        onDelete = { deleting = book },
                    )
                }
            }

            AddRow("新建世界书") {
                scope.launch { repository.save(Lorebook(id = LoreIds.book(), name = "世界书 ${books.size + 1}")) }
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    editing?.let { (bookId, entry) ->
        val entryId = entry.id
        LoreEntrySheet(
            entry = entry,
            onDismiss = { editing = null },
            onDelete = {
                update(bookId) { book -> book.copy(entries = book.entries.filterNot { it.id == entryId }) }
                editing = null
            },
            onSave = { updated ->
                update(bookId) { book ->
                    book.copy(entries = if (book.entries.any { it.id == entryId }) {
                        book.entries.map { if (it.id == entryId) updated else it }
                    } else book.entries + updated)
                }
                editing = null
            },
        )
    }

    deleting?.let { book ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除世界书") },
            text = { Text("删除「${book.name}」及全部 ${book.entries.size} 个条目？引用此书的角色将不再加载这些内容。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { repository.delete(book.id) }
                        deleting = null
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun BookCard(
    book: Lorebook,
    onChange: ((Lorebook) -> Lorebook) -> Unit,
    onEditEntry: (String) -> Unit,
    onAddEntry: () -> Unit,
    onDelete: () -> Unit,
) {
    CardGroup(
        title = book.name.ifBlank { "未命名世界书" },
        subtitle = "${book.entries.size} 条" + if (book.enabled) "" else " · 已停用",
        stateKey = book.id,
    ) {
        CommitField(label = "名称", value = book.name) { name -> onChange { it.copy(name = name) } }
        SheetToggle("启用", book.enabled) {
            val enabled = it
            onChange { current -> current.copy(enabled = enabled) }
        }

        book.entries.forEachIndexed { index, entry ->
            if (index > 0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
            LoreEntryRow(
                entry = entry,
                onClick = { onEditEntry(entry.id) },
                onToggle = { enabled ->
                    onChange { current ->
                        current.copy(
                            entries = current.entries.map {
                                if (it.id == entry.id) it.copy(enabled = enabled) else it
                            },
                        )
                    }
                },
            )
        }
        AddRow("添加条目", onAddEntry)

        AdvancedBlock(subtitle = "扫描深度、预算与递归", padded = false) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CommitField(
                    label = "扫描深度",
                    value = book.scanDepth.toString(),
                    digitsOnly = true,
                    maxDigits = 3,
                    modifier = Modifier.weight(1f),
                ) { text -> onChange { it.copy(scanDepth = text.toIntOrNull()?.coerceIn(0, 200) ?: it.scanDepth) } }
                CommitField(
                    label = "Token 预算",
                    value = book.tokenBudget.toString(),
                    digitsOnly = true,
                    maxDigits = 6,
                    modifier = Modifier.weight(1f),
                ) { text -> onChange { it.copy(tokenBudget = text.toIntOrNull()?.coerceIn(0, 999_999) ?: it.tokenBudget) } }
            }
            SheetToggle("递归扫描", book.recursiveScanning, "已命中条目的内容参与下一轮匹配") {
                val recursive = it
                onChange { current -> current.copy(recursiveScanning = recursive) }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDelete) {
                Text("删除世界书", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * 输入时只改本地，停顿或失焦才落库。
 *
 * 这一页的数据直接来自 Room 的 Flow，每敲一个字就写一次库会让文本框跟着流回来的值抖，
 * 顺带把整本书重新序列化一遍。角色编辑页没这个问题——那边改的是内存里的草稿，点保存才写。
 */
@Composable
private fun CommitField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    digitsOnly: Boolean = false,
    maxDigits: Int = 6,
    onCommit: (String) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }

    LaunchedEffect(text) {
        if (text == value) return@LaunchedEffect
        delay(COMMIT_DEBOUNCE_MS)
        onCommit(text)
    }

    OutlinedTextField(
        value = text,
        onValueChange = { raw -> text = if (digitsOnly) raw.filter(Char::isDigit).take(maxDigits) else raw },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                // 失焦时立刻落一次，用户点完就走也不丢。
                if (focused && !state.isFocused && text != value) onCommit(text)
                focused = state.isFocused
            },
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    )
}

private const val COMMIT_DEBOUNCE_MS = 500L
