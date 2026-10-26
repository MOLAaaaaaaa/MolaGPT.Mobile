package com.molagpt.app.feature.chat

import androidx.compose.runtime.compositionLocalOf

/**
 * 工具卡上「查看子 Agent」的去处，只有远控电脑的对话页提供。
 * [available] 是电脑上还在的子 Agent；不在里面的（电脑重启过）不显示入口。
 */
class SubagentLinks(val available: Set<String>, val open: (String) -> Unit)

val LocalSubagentLinks = compositionLocalOf<SubagentLinks?> { null }
