package com.molagpt.app.core.render.visual

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.markdown.MdBlock
import com.molagpt.app.core.markdown.visual.HtmlFence
import com.molagpt.app.core.render.CodeBlockView

/**
 * 回答里的 HTML 代码块，用户在两种形态间切换：卡片（是什么、多大、运行），或原本的代码块，
 * 标题栏多出「运行」和「收起为卡片」。
 *
 * 默认形态跟设置「网页以卡片显示」；某一处单独切换过就记在宿主里（按 [stateKey]），
 * 切回与设置一致的形态即忘掉，之后继续跟随设置。够不上「一页」的短片段始终是代码块，
 * 写完后标题栏同样能运行。
 *
 * @param streaming 所在回答还在生成。围栏没写完时卡片显示「正在生成」，不能运行。
 * @param stateKey 这一处在对话里的稳定身份；为 null 时切换只在本控件里记住。
 */
@Composable
fun HtmlFenceView(block: MdBlock.Code, streaming: Boolean, stateKey: String?, modifier: Modifier = Modifier) {
    val host = LocalVisualHost.current
    if (host == null) {
        CodeBlockView(language = block.language, code = block.code, modifier = modifier)
        return
    }
    val generating = !block.closed && streaming
    val page = HtmlFence.isPage(block.language, block.code, closed = !generating)
    val run = {
        host.runHtml(HtmlRunRequest(block.code, HtmlFence.title(block.code), HtmlFence.fileName(block.code)))
    }
    if (!page) {
        CodeBlockView(language = block.language, code = block.code, modifier = modifier) {
            if (!generating) RunIcon(host, run)
        }
        return
    }

    var local by remember { mutableStateOf<Boolean?>(null) }
    val default = host.htmlAsCard
    val card = (if (stateKey != null) host.htmlForm(stateKey) else local) ?: default
    val setCard: (Boolean) -> Unit = { value ->
        if (stateKey != null) host.setHtmlForm(stateKey, value) else local = value.takeIf { it != default }
    }

    if (card) {
        HtmlCard(
            title = HtmlFence.title(block.code),
            lines = HtmlFence.lineCount(block.code),
            generating = generating,
            onPrepare = host::prepareHtml,
            onRun = run,
            onShowCode = { setCard(false) },
            modifier = modifier,
        )
    } else {
        CodeBlockView(language = block.language ?: "html", code = block.code, modifier = modifier) {
            if (!generating) RunIcon(host, run)
            IconButton(onClick = { setCard(true) }) {
                Icon(
                    Icons.Outlined.ViewAgenda,
                    contentDescription = "收起为卡片",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RunIcon(host: VisualHost, run: () -> Unit) {
    IconButton(onClick = run, interactionSource = rememberPressAction(host::prepareHtml)) {
        Icon(Icons.Filled.PlayArrow, contentDescription = "运行", tint = MaterialTheme.colorScheme.primary)
    }
}

/** 按下（而不是抬手）时先准备网页引擎：从按下到抬手的这段时间正好够它启动。 */
@Composable
private fun rememberPressAction(action: () -> Unit): MutableInteractionSource {
    val source = remember { MutableInteractionSource() }
    val current by rememberUpdatedState(action)
    LaunchedEffect(source) {
        source.interactions.collect { if (it is PressInteraction.Press) current() }
    }
    return source
}

@Composable
private fun HtmlCard(
    title: String,
    lines: Int,
    generating: Boolean,
    onPrepare: () -> Unit,
    onRun: () -> Unit,
    onShowCode: () -> Unit,
    modifier: Modifier,
) {
    val palette = rememberVisualPalette()
    val shape = RoundedCornerShape(12.dp)
    val runNow by rememberUpdatedState(onRun)
    val prepare by rememberUpdatedState(onPrepare)
    DisableSelection {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .border(1.dp, palette.border, shape)
                .pointerInput(generating) {
                    // 按下就开始准备网页引擎，抬手时它多半已经就绪。
                    detectTapGestures(
                        onPress = { if (!generating) prepare() },
                        onTap = { if (!generating) runNow() },
                    )
                }
                .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                if (generating) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = palette.accent)
                } else {
                    Icon(Icons.Outlined.Language, contentDescription = null, tint = palette.accent, modifier = Modifier.size(22.dp))
                }
            }
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (generating) "正在生成 · $lines 行" else "网页 · $lines 行",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onShowCode) {
                Icon(Icons.Outlined.Code, contentDescription = "查看代码", tint = palette.muted)
            }
            Spacer(Modifier.width(2.dp))
            Button(
                onClick = onRun,
                enabled = !generating,
                contentPadding = PaddingValues(horizontal = 14.dp),
                interactionSource = rememberPressAction(onPrepare),
                modifier = Modifier.height(36.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("运行")
            }
        }
    }
}
