package com.molagpt.app.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.model.LoreEntry
import com.molagpt.app.core.model.LoreIds
import com.molagpt.app.core.model.LoreKeywords
import com.molagpt.app.core.model.LoreScanScope
import com.molagpt.app.core.model.LoreSelectiveLogic
import com.molagpt.app.core.model.Lorebook
import com.molagpt.app.core.model.LorePosition
import com.molagpt.app.core.model.PersonaProfile

/**
 * 角色卡编辑区。导入的卡与自建的角色扮演角色共用这一套。
 *
 * 分两层：**常用字段摊开**，其余二十来个进「高级选项」，点开才出现。这么切是因为卡片规范的
 * 字段数量本来就大（V3 全规格），平铺一屏滑不到底；但砍掉它们又会让手机端只能用别人做好的卡，
 * 自己一条世界书都写不了。
 *
 * 不认识的字段照旧留在 [LoreEntry.cardData] 与未改动的属性里，保存时原样写回。
 *
 * @param sharedBooks 可引用的共享世界书（独立于卡片存在）。为空时不显示引用入口。
 */
@Composable
fun PersonaCardSections(
    profile: PersonaProfile,
    onChange: (PersonaProfile) -> Unit,
    sharedBooks: List<Lorebook> = emptyList(),
) {
    CardGroup(title = "人设", initiallyExpanded = true) {
        CardField("角色资料", profile.description, minLines = 3) { onChange(profile.copy(description = it)) }
        CardField("性格与表达", profile.personality, minLines = 2) { onChange(profile.copy(personality = it)) }
        CardField("场景", profile.scenario, minLines = 2) { onChange(profile.copy(scenario = it)) }
        CardField("角色称呼", profile.nickname, singleLine = true, placeholder = "留空沿用角色名称") {
            onChange(profile.copy(nickname = it))
        }
    }

    CardGroup(title = "我的身份") {
        SheetToggle("沿用个人资料称呼", profile.useProfileName) {
            onChange(profile.copy(useProfileName = it))
        }
        if (!profile.useProfileName) {
            CardField("你的称呼", profile.userName, singleLine = true) { onChange(profile.copy(userName = it)) }
        }
        CardField("你的身份", profile.userDescription, minLines = 2) { onChange(profile.copy(userDescription = it)) }
    }

    CardGroup(title = "开场与示例") {
        CardField("开场白", profile.greeting, minLines = 2) { onChange(profile.copy(greeting = it)) }
        if (profile.alternateGreetings.isNotEmpty()) {
            ReadOnlyNote("${profile.alternateGreetings.size} 条备选开场白，可在对话中切换")
        }
        CardField("对话示例", profile.exampleDialogue, minLines = 3, placeholder = "用 <START> 分隔多组示例") {
            onChange(profile.copy(exampleDialogue = it))
        }
    }

    CardGroup(title = "补充指令") {
        CardField("角色补充", profile.characterNote, minLines = 2) {
            onChange(profile.copy(characterNote = it))
        }
        ReadOnlyNote("${roleLabel(profile.characterNoteRole)}身份 · 距对话末尾 ${profile.characterNoteDepth} 条")
        CardField("后置指令", profile.postHistoryInstructions, minLines = 2, placeholder = "追加在对话历史之后") {
            onChange(profile.copy(postHistoryInstructions = it))
        }
    }

    LorebookGroup(profile = profile, sharedBooks = sharedBooks, onChange = onChange)

    val meta = listOfNotNull(
        profile.creator.takeIf { it.isNotBlank() }?.let { "作者 $it" },
        profile.characterVersion.takeIf { it.isNotBlank() }?.let { "版本 $it" },
        profile.tags.takeIf { it.isNotEmpty() }?.joinToString("、"),
        profile.cardSpec,
    )
    if (meta.isNotEmpty() || profile.importNotes.isNotEmpty()) {
        CardGroup(title = "卡片信息") {
            ReadOnlyNote(meta.joinToString(" · "))
            profile.importNotes.forEach { ReadOnlyNote(it) }
            if (profile.creatorNotes.isNotBlank()) ReadOnlyNote(profile.creatorNotes)
        }
    }
}

@Composable
private fun LorebookGroup(
    profile: PersonaProfile,
    sharedBooks: List<Lorebook>,
    onChange: (PersonaProfile) -> Unit,
) {
    val books = profile.lorebooks
    var editing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var picking by remember { mutableStateOf(false) }
    val referenced = profile.sharedLorebookIds.count { id -> sharedBooks.any { it.id == id } }

    CardGroup(
        title = "世界书",
        subtitle = if (books.isEmpty() && referenced == 0) "关键词触发的设定片段" else null,
        initiallyExpanded = books.isNotEmpty(),
    ) {
        books.forEachIndexed { bookIndex, book ->
            Text(
                text = "${book.name} · ${book.entries.size} 条",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
            )
            book.entries.forEachIndexed { entryIndex, entry ->
                if (entryIndex > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
                LoreEntryRow(
                    entry = entry,
                    onClick = { editing = bookIndex to entryIndex },
                    onToggle = { enabled ->
                        onChange(profile.replaceEntry(bookIndex, entryIndex) { it.copy(enabled = enabled) })
                    },
                )
            }
            AddRow("添加条目") {
                onChange(profile.appendEntry(bookIndex))
                editing = bookIndex to book.entries.size
            }
            BookAdvanced(book) { updated -> onChange(profile.replaceBook(bookIndex) { updated }) }
        }

        AddRow("新建世界书") {
            onChange(profile.copy(lorebooks = books + Lorebook(id = LoreIds.book())))
        }

        if (sharedBooks.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(
                modifier = Modifier.fillMaxWidth().clickable { picking = true }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("引用共享世界书", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (referenced == 0) "独立保存，可供多个角色引用" else "已引用 $referenced 本",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text("选择", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    editing?.let { (bookIndex, entryIndex) ->
        val entry = books.getOrNull(bookIndex)?.entries?.getOrNull(entryIndex)
        if (entry == null) {
            editing = null
        } else {
            LoreEntrySheet(
                entry = entry,
                onDismiss = { editing = null },
                onDelete = {
                    onChange(profile.removeEntry(bookIndex, entryIndex))
                    editing = null
                },
                onSave = { updated ->
                    onChange(profile.replaceEntry(bookIndex, entryIndex) { updated })
                    editing = null
                },
            )
        }
    }

    if (picking) {
        SharedLorebookPicker(
            books = sharedBooks,
            selected = profile.sharedLorebookIds.toSet(),
            onDismiss = { picking = false },
            onToggle = { id, on ->
                val next = if (on) profile.sharedLorebookIds + id else profile.sharedLorebookIds - id
                onChange(profile.copy(sharedLorebookIds = next.distinct()))
            },
        )
    }
}

/** 世界书自己的三项设置。整本书一级的东西，跟条目分开，默认收起。 */
@Composable
private fun BookAdvanced(book: Lorebook, onChange: (Lorebook) -> Unit) {
    AdvancedBlock(subtitle = "扫描深度、预算与递归", padded = false) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField(
                label = "扫描深度",
                value = book.scanDepth.toString(),
                modifier = Modifier.weight(1f),
            ) { onChange(book.copy(scanDepth = it.toIntOrNull()?.coerceIn(0, 200) ?: book.scanDepth)) }
            NumberField(
                label = "Token 预算",
                value = book.tokenBudget.toString(),
                maxDigits = 6,
                modifier = Modifier.weight(1f),
            ) { onChange(book.copy(tokenBudget = it.toIntOrNull()?.coerceIn(0, 999_999) ?: book.tokenBudget)) }
        }
        SheetToggle("递归扫描", book.recursiveScanning, "已命中条目的内容参与下一轮匹配") {
            onChange(book.copy(recursiveScanning = it))
        }
    }
}

@Composable
internal fun AddRow(label: String, onClick: () -> Unit) {
    Text(
        text = "＋ $label",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
    )
}

@Composable
internal fun LoreEntryRow(entry: LoreEntry, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(
                entry.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurface.copy(alpha = if (entry.enabled) 1f else 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                entry.summary(),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant.copy(alpha = if (entry.enabled) 1f else 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MolaSwitch(checked = entry.enabled, onChange = onToggle)
    }
}

/** 列表行只放一行摘要，让用户不用点进去也知道这条身上挂着什么。 */
internal fun LoreEntry.summary(): String = buildList {
    if (constant) add("常驻") else if (keywords.isNotEmpty()) add(keywords.joinToString("、").take(24))
    when (placement) {
        LorePosition.AT_DEPTH -> add("距末尾 $depth 条")
        LorePosition.BEFORE_CHARACTER -> add("角色资料前")
        LorePosition.OUTLET -> add("出口 $outletName")
        else -> Unit
    }
    if (probability in 1..99) add("概率 $probability%")
    if (group.isNotBlank()) add("分组 $group")
    if (sticky > 0) add("持续 $sticky 轮")
    if (selective && secondaryKeywords.isNotEmpty()) add("含次要关键词")
}.joinToString(" · ").ifBlank { "无关键词" }

/**
 * 条目编辑。常用五项直接摊开，其余按匹配 / 插入 / 取舍 / 分组 / 递归与节奏分组收在「高级选项」里。
 *
 * 没有出现在表单里的字段（以及 [LoreEntry.cardData] 里的原始 JSON）一律原样带回。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LoreEntrySheet(
    entry: LoreEntry,
    onDismiss: () -> Unit,
    onSave: (LoreEntry) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    // 一行一个关键词。原文存一份，保存时先比对——只改了名称或内容时，关键词列表原封不动地带回去。
    val keywordsEditable = LoreKeywords.isEditable(entry.keywords)
    val secondaryEditable = LoreKeywords.isEditable(entry.secondaryKeywords)

    var name by remember(entry.id) { mutableStateOf(entry.name) }
    var keywords by remember(entry.id) { mutableStateOf(LoreKeywords.toText(entry.keywords)) }
    var content by remember(entry.id) { mutableStateOf(entry.content) }
    var constant by remember(entry.id) { mutableStateOf(entry.constant) }
    // 位置只有这一个真相源：常用区的开关和高级区的下拉都读写它，不会互相打架。
    var position by remember(entry.id) { mutableStateOf(entry.placement) }
    var depth by remember(entry.id) { mutableStateOf(entry.depth.toString()) }

    var useRegex by remember(entry.id) { mutableStateOf(entry.useRegex) }
    var caseSensitive by remember(entry.id) { mutableStateOf(entry.caseSensitive) }
    var matchWholeWords by remember(entry.id) { mutableStateOf(entry.matchWholeWords) }
    var selective by remember(entry.id) { mutableStateOf(entry.selective) }
    var secondary by remember(entry.id) { mutableStateOf(LoreKeywords.toText(entry.secondaryKeywords)) }
    var selectiveLogic by remember(entry.id) { mutableStateOf(entry.selectiveLogic) }
    var scanDepth by remember(entry.id) { mutableStateOf(entry.scanDepth?.toString().orEmpty()) }
    var scanScope by remember(entry.id) { mutableStateOf(entry.scanScope) }
    var role by remember(entry.id) { mutableStateOf(entry.role) }
    var outletName by remember(entry.id) { mutableStateOf(entry.outletName) }
    var insertionOrder by remember(entry.id) { mutableStateOf(entry.insertionOrder?.toString().orEmpty()) }
    var budgetPriority by remember(entry.id) { mutableStateOf(entry.budgetPriority?.toString().orEmpty()) }
    var useProbability by remember(entry.id) { mutableStateOf(entry.useProbability) }
    var probability by remember(entry.id) { mutableStateOf(entry.probability.toString()) }
    var ignoreBudget by remember(entry.id) { mutableStateOf(entry.ignoreBudget) }
    var group by remember(entry.id) { mutableStateOf(entry.group) }
    var groupWeight by remember(entry.id) { mutableStateOf(entry.groupWeight.toString()) }
    var groupOverride by remember(entry.id) { mutableStateOf(entry.groupOverride) }
    var excludeRecursion by remember(entry.id) { mutableStateOf(entry.excludeRecursion) }
    var preventRecursion by remember(entry.id) { mutableStateOf(entry.preventRecursion) }
    var delayUntilRecursion by remember(entry.id) { mutableStateOf(entry.delayUntilRecursion.toString()) }
    var delay by remember(entry.id) { mutableStateOf(entry.delay.toString()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        ) {
            Text("世界书条目", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = keywords,
                onValueChange = { keywords = it },
                label = { Text("关键词") },
                placeholder = { Text("一行一个") },
                enabled = !constant && keywordsEditable,
                minLines = 2,
                supportingText = {
                    Text(
                        when {
                            !keywordsEditable -> "关键词含换行，暂不支持编辑"
                            constant -> "常驻条目不匹配关键词"
                            useRegex -> "一行一个，按 /pattern/flags 解析为正则"
                            else -> "一行一个，命中任一即触发"
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text("内容") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            )
            SheetToggle("常驻", constant, "每轮对话均加载，不匹配关键词") { constant = it }
            SheetToggle("按深度插入对话", position == LorePosition.AT_DEPTH) { on ->
                position = if (on) LorePosition.AT_DEPTH else LorePosition.AFTER_CHARACTER
            }
            AnimatedVisibility(visible = position == LorePosition.AT_DEPTH) {
                NumberField(
                    label = "距末尾条数",
                    value = depth,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) { depth = it }
            }

            AdvancedBlock(subtitle = "匹配、位置、预算与递归") {
                SubHeader("匹配")
                SheetToggle("正则匹配", useRegex, "关键词按 /pattern/flags 解析") { useRegex = it }
                SheetToggle("区分大小写", caseSensitive) { caseSensitive = it }
                SheetToggle("全词匹配", matchWholeWords) { matchWholeWords = it }
                SheetToggle("启用次要关键词", selective, "主关键词命中后，检查次要关键词") { selective = it }
                AnimatedVisibility(visible = selective) {
                    Column {
                        OutlinedTextField(
                            value = secondary,
                            onValueChange = { secondary = it },
                            label = { Text("次要关键词") },
                            placeholder = { Text("一行一个") },
                            enabled = secondaryEditable,
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                        )
                        PickerField(
                            label = "判定方式",
                            value = selectiveLogicLabel(selectiveLogic),
                            options = LoreSelectiveLogic.entries.map { it to selectiveLogicLabel(it) },
                            onPick = { selectiveLogic = it },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField(
                        label = "扫描深度",
                        value = scanDepth,
                        placeholder = "跟随世界书",
                        modifier = Modifier.weight(1f),
                    ) { scanDepth = it }
                    Box(modifier = Modifier.weight(1f)) {
                        PickerField(
                            label = "扫描范围",
                            value = scanScopeLabel(scanScope),
                            options = (listOf<LoreScanScope?>(null) + LoreScanScope.entries)
                                .filter { it != LoreScanScope.INHERIT }
                                .map { it to scanScopeLabel(it) },
                            onPick = { scanScope = it },
                        )
                    }
                }

                SubHeader("插入")
                PickerField(
                    label = "位置",
                    value = positionLabel(position),
                    options = LorePosition.entries.map { it to positionLabel(it) },
                    onPick = { position = it },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.weight(1f)) {
                        PickerField(
                            label = "注入身份",
                            value = roleLabel(role),
                            options = listOf("system", "user", "assistant").map { it to roleLabel(it) },
                            onPick = { role = it },
                        )
                    }
                    OutlinedTextField(
                        value = outletName,
                        onValueChange = { outletName = it },
                        label = { Text("出口名称") },
                        singleLine = true,
                        enabled = position == LorePosition.OUTLET,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                }

                SubHeader("排序与预算")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField(
                        label = "插入次序",
                        value = insertionOrder,
                        placeholder = "跟随优先级",
                        signed = true,
                        modifier = Modifier.weight(1f),
                    ) { insertionOrder = it }
                    NumberField(
                        label = "预算优先级",
                        value = budgetPriority,
                        placeholder = "跟随次序",
                        signed = true,
                        modifier = Modifier.weight(1f),
                    ) { budgetPriority = it }
                }
                SheetToggle("按概率触发", useProbability) { useProbability = it }
                AnimatedVisibility(visible = useProbability) {
                    NumberField(
                        label = "触发概率（%）",
                        value = probability,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    ) { probability = it }
                }
                SheetToggle("不计入 Token 预算", ignoreBudget, "预算不足时不裁剪此条目") { ignoreBudget = it }

                SubHeader("分组")
                OutlinedTextField(
                    value = group,
                    onValueChange = { group = it },
                    label = { Text("分组") },
                    placeholder = { Text("逗号分隔，同组每轮仅采用一条") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField(
                        label = "组内权重",
                        value = groupWeight,
                        maxDigits = 4,
                        modifier = Modifier.weight(1f),
                    ) { groupWeight = it }
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        SheetToggle("强制占位", groupOverride) { groupOverride = it }
                    }
                }

                SubHeader("递归与节奏")
                SheetToggle("不参与递归扫描", excludeRecursion) { excludeRecursion = it }
                SheetToggle("命中后阻止递归", preventRecursion) { preventRecursion = it }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField(
                        label = "延迟递归轮数",
                        value = delayUntilRecursion,
                        modifier = Modifier.weight(1f),
                    ) { delayUntilRecursion = it }
                    NumberField(
                        label = "生效所需对话条数",
                        value = delay,
                        maxDigits = 4,
                        modifier = Modifier.weight(1f),
                    ) { delay = it }
                }
                if (entry.sticky > 0 || entry.cooldown > 0) {
                    ReadOnlyNote("持续 ${entry.sticky} 轮、冷却 ${entry.cooldown} 轮：暂不支持")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        onSave(
                            entry.copy(
                                name = if (name == entry.name) entry.name else name.trim(),
                                keywords = LoreKeywords.resolve(entry.keywords, keywords, keywordsEditable),
                                secondaryKeywords =
                                    LoreKeywords.resolve(entry.secondaryKeywords, secondary, secondaryEditable),
                                content = content,
                                constant = constant,
                                position = if (position == entry.placement) entry.position else position,
                                depth = editedLoreInt(depth, entry.depth, 0..99) ?: entry.depth,
                                useRegex = useRegex,
                                caseSensitive = caseSensitive,
                                matchWholeWords = matchWholeWords,
                                selective = selective,
                                selectiveLogic = selectiveLogic,
                                scanDepth = editedLoreInt(scanDepth, entry.scanDepth, 0..200),
                                scanScope = scanScope,
                                role = role,
                                outletName = if (outletName == entry.outletName) entry.outletName else outletName.trim(),
                                insertionOrder = insertionOrder.toIntOrNull(),
                                budgetPriority = budgetPriority.toIntOrNull(),
                                useProbability = useProbability,
                                probability = editedLoreInt(probability, entry.probability, 0..100) ?: entry.probability,
                                ignoreBudget = ignoreBudget,
                                group = if (group == entry.group) entry.group else group.trim(),
                                groupWeight = editedLoreInt(groupWeight, entry.groupWeight, 0..9999) ?: entry.groupWeight,
                                groupOverride = groupOverride,
                                excludeRecursion = excludeRecursion,
                                preventRecursion = preventRecursion,
                                delayUntilRecursion = editedLoreInt(delayUntilRecursion, entry.delayUntilRecursion, 0..99)
                                    ?: entry.delayUntilRecursion,
                                delay = editedLoreInt(delay, entry.delay, 0..9999) ?: entry.delay,
                            ),
                        )
                    },
                ) { Text("保存") }
            }
        }
    }
}

/** 共享世界书多选。勾选写进 [PersonaProfile.sharedLorebookIds]，书本身不动。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharedLorebookPicker(
    books: List<Lorebook>,
    selected: Set<String>,
    onDismiss: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            Text("引用共享世界书", style = MaterialTheme.typography.titleMedium)
            Text(
                "在设置的世界书页面编辑内容",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
            )
            books.forEachIndexed { index, book ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
                val on = book.id in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(book.id, !on) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                        Text(book.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${book.entries.size} 条" + if (!book.enabled) " · 已停用" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    MolaSwitch(checked = on) { onToggle(book.id, it) }
                }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp)) {
                Spacer(Modifier.weight(1f))
                Button(onClick = onDismiss) { Text("完成") }
            }
        }
    }
}

// region 组件

/**
 * 「高级选项」折叠块。默认收起——点开之前，页面上只有真正常用的那几项。
 *
 * 与 [CardGroup] 的区别是它出现在表单内部（sheet 里、分组里），所以用描边而不是填充色，
 * 免得和外层卡片叠成两层底。
 */
@Composable
internal fun AdvancedBlock(
    subtitle: String,
    padded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(subtitle) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = if (padded) 16.dp else 8.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 13.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("高级选项", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 13.dp, end = 13.dp, bottom = 13.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun SubHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
}

@Composable
internal fun SheetToggle(
    label: String,
    checked: Boolean,
    subtitle: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        MolaSwitch(checked = checked, onChange = onChange)
    }
}

@Composable
internal fun MolaSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            uncheckedThumbColor = cs.onSurfaceVariant,
            checkedTrackColor = cs.primary,
            uncheckedTrackColor = cs.surfaceVariant,
            uncheckedBorderColor = cs.outline,
        ),
    )
}

/** 编辑范围只约束新输入，导入且未修改的值保持原样。 */
internal fun editedLoreInt(text: String, original: Int?, range: IntRange): Int? =
    if (text == original?.toString().orEmpty()) original else text.toIntOrNull()?.coerceIn(range)

/** 只收数字的输入框。留空即「不设置」，交给调用方解释成 null 或沿用原值。 */
@Composable
internal fun NumberField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    maxDigits: Int = 3,
    signed: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { raw ->
            val negative = signed && raw.startsWith("-")
            val digits = raw.filter(Char::isDigit).take(maxDigits)
            onChange(if (negative) "-$digits" else digits)
        },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        modifier = modifier,
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
    )
}

/** 枚举下拉。做成只读输入框 + 菜单，和旁边的文本框对齐，不用额外解释怎么点。 */
@Composable
private fun <T> PickerField(
    label: String,
    value: String,
    options: List<Pair<T, String>>,
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        // 只读框自己不接事件，盖一层透明点击区，免得为了可点把它改成 enabled 又能打字。
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (option, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onPick(option)
                        open = false
                    },
                )
            }
        }
    }
}

/** 可折叠分组。卡片字段一摊开就是十几个输入框，默认收起才有得用。 */
@Composable
internal fun CardGroup(
    title: String,
    subtitle: String? = null,
    initiallyExpanded: Boolean = false,
    // 标题会变的场合（比如标题就是可改的书名）必须另给一个稳定 key，
    // 否则改一个字就重置展开状态，卡片会在输入途中自己收起来。
    stateKey: String = title,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(stateKey) { mutableStateOf(initiallyExpanded) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    subtitle?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Text(
                    if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun CardField(
    label: String,
    value: String,
    minLines: Int = 1,
    singleLine: Boolean = false,
    placeholder: String? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(
            imeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
        ),
    )
}

@Composable
private fun ReadOnlyNote(text: String) {
    if (text.isBlank()) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// endregion

// region 文案

private fun roleLabel(role: String): String = when (role) {
    "user" -> "用户"
    "assistant" -> "角色"
    else -> "系统"
}

private fun positionLabel(position: LorePosition): String = when (position) {
    LorePosition.BEFORE_CHARACTER -> "角色资料之前"
    LorePosition.AFTER_CHARACTER -> "角色资料之后"
    LorePosition.BEFORE_NOTE -> "角色补充之前"
    LorePosition.AFTER_NOTE -> "角色补充之后"
    LorePosition.AT_DEPTH -> "按深度插入对话"
    LorePosition.BEFORE_EXAMPLES -> "对话示例之前"
    LorePosition.AFTER_EXAMPLES -> "对话示例之后"
    LorePosition.OUTLET -> "命名出口"
}

private fun selectiveLogicLabel(logic: LoreSelectiveLogic): String = when (logic) {
    LoreSelectiveLogic.AND_ANY -> "任一命中"
    LoreSelectiveLogic.NOT_ALL -> "不全命中"
    LoreSelectiveLogic.NOT_ANY -> "均不命中"
    LoreSelectiveLogic.AND_ALL -> "全部命中"
}

private fun scanScopeLabel(scope: LoreScanScope?): String = when (scope) {
    null, LoreScanScope.INHERIT -> "跟随世界书"
    LoreScanScope.RECENT -> "最近数条"
    LoreScanScope.ALL -> "全部历史"
    LoreScanScope.NONE -> "不扫描"
}

// endregion

// region profile 编辑辅助

private fun PersonaProfile.replaceBook(bookIndex: Int, transform: (Lorebook) -> Lorebook): PersonaProfile {
    val books = lorebooks.toMutableList()
    val book = books.getOrNull(bookIndex) ?: return this
    books[bookIndex] = transform(book)
    return copy(lorebooks = books)
}

private fun PersonaProfile.replaceEntry(
    bookIndex: Int,
    entryIndex: Int,
    transform: (LoreEntry) -> LoreEntry,
): PersonaProfile = replaceBook(bookIndex) { book ->
    val entries = book.entries.toMutableList()
    val entry = entries.getOrNull(entryIndex) ?: return@replaceBook book
    entries[entryIndex] = transform(entry)
    book.copy(entries = entries)
}

private fun PersonaProfile.appendEntry(bookIndex: Int): PersonaProfile = replaceBook(bookIndex) { book ->
    book.copy(entries = book.entries + LoreEntry(id = LoreIds.entry()))
}

private fun PersonaProfile.removeEntry(bookIndex: Int, entryIndex: Int): PersonaProfile =
    replaceBook(bookIndex) { book ->
        if (entryIndex !in book.entries.indices) book
        else book.copy(entries = book.entries.filterIndexed { index, _ -> index != entryIndex })
    }

// endregion

/** 分组之间的呼吸空间，和页面其余部分的节奏一致。 */
@Composable
fun PersonaCardSectionSpacer() {
    Spacer(Modifier.height(6.dp))
}
