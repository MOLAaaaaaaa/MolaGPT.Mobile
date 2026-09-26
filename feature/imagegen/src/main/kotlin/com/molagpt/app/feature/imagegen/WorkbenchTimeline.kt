package com.molagpt.app.feature.imagegen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.molagpt.app.core.render.ActionChip
import com.molagpt.app.core.render.RetryBar
import com.molagpt.app.core.render.UserBubble
import com.molagpt.app.core.render.shimmer
import com.molagpt.app.core.storage.ImageOutputRecord
import com.molagpt.app.core.storage.ImageRun
import com.molagpt.app.core.storage.ImageRunKind
import com.molagpt.app.core.storage.ImageVersion
import com.molagpt.app.core.storage.ImageVersionStatus
import com.molagpt.app.feature.file.PreviewableImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 结果区的最大宽度，与用户气泡一致。 */
private val ContentMaxWidth = 320.dp
private val SingleMaxHeight = 360.dp

internal class TimelineActions(
    val urlOf: (String) -> String,
    val modelName: (providerId: String, modelId: String) -> String,
    val canUseAsBase: Boolean,
    val onRegenerate: (runId: String) -> Unit,
    val onRetry: (versionId: String) -> Unit,
    val onSwitchVersion: (runId: String, versionId: String) -> Unit,
    val onUseAsBase: (url: String) -> Unit,
)

@Composable
internal fun WorkbenchTimeline(
    taskId: String,
    runs: List<ImageRun>,
    actions: TimelineActions,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // 打开任务时停在最后一轮；新发一轮时滚到底。
    LaunchedEffect(taskId) {
        if (runs.isNotEmpty()) listState.scrollToItem(runs.lastIndex)
    }
    LaunchedEffect(runs.size) {
        if (runs.isNotEmpty()) listState.animateScrollToItem(runs.lastIndex)
    }
    // 最后一轮出结果（或失败）时滚到底，别让结果压在输入区下面。
    val last = runs.lastOrNull()
    LaunchedEffect(last?.id, last?.activeVersionId, last?.activeVersion?.status) {
        if (last != null && last.activeVersion?.status != ImageVersionStatus.RUNNING) {
            listState.animateScrollToItem(runs.lastIndex, Int.MAX_VALUE)
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        items(runs, key = { it.id }, contentType = { "run" }) { run ->
            RunTurn(run = run, actions = actions)
        }
    }
}

@Composable
private fun RunTurn(run: ImageRun, actions: TimelineActions) {
    val version = run.activeVersion
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        UserBubble(modifier = Modifier.fillMaxWidth()) {
            if (run.refs.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    run.refs.forEach { ref ->
                        Box {
                            PreviewableImage(
                                url = actions.urlOf(ref.src),
                                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)),
                            )
                            if (ref.fromOutput || ref.mask != null) {
                                Text(
                                    text = if (ref.mask != null) "涂抹" else "上一张",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = androidx.compose.ui.graphics.Color.White,
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(3.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
            }
            SelectionContainer {
                Text(
                    text = run.prompt,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "${actions.modelName(run.providerId, run.modelId)} · ${kindTitle(run.kind)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        when (version?.status) {
            null, ImageVersionStatus.RUNNING -> RunningBody(run, version)
            ImageVersionStatus.SUCCESS -> SuccessBody(run, version, actions)
            ImageVersionStatus.FAILED, ImageVersionStatus.CANCELED -> FailureBody(run, version, actions)
        }
    }
}

@Composable
private fun RunningBody(run: ImageRun, version: ImageVersion?) {
    val started = version?.startedAt ?: run.createdAt
    val elapsed by produceState(initialValue = elapsedSeconds(started), started) {
        while (true) {
            value = elapsedSeconds(started)
            delay(1_000)
        }
    }
    val slots = when (run.kind) {
        ImageRunKind.NEW -> run.count.coerceIn(1, ImageTaskManager.MAX_COUNT)
        ImageRunKind.PER_IMAGE -> run.refs.size.coerceAtLeast(1)
        else -> 1
    }
    ImageLayout(count = slots, aspect = sizeAspect(run.size)) { _, mod ->
        Box(mod.shimmer())
    }
    Row(modifier = Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            text = "${if (run.kind.isEdit) "编辑中" else "生成中"} · ${elapsed}s",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SuccessBody(run: ImageRun, version: ImageVersion, actions: TimelineActions) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val urls = remember(version.outputs) { version.outputs.map { actions.urlOf(it.src) } }
    val aspect = version.outputs.singleOrNull()?.let(::outputAspect) ?: sizeAspect(run.size)
    ImageLayout(count = urls.size, aspect = aspect) { index, mod ->
        LoadingImage(url = urls[index], gallery = urls, modifier = mod)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            ActionChip(if (urls.size > 1) "全部保存" else "保存", onClick = {
                scope.launch {
                    val saved = ImageActions.save(context, urls)
                    Toast.makeText(context, if (saved > 0) "已保存 $saved 张到相册" else "保存失败", Toast.LENGTH_SHORT).show()
                }
            })
            ActionChip("分享", onClick = { scope.launch { ImageActions.share(context, urls) } })
            if (actions.canUseAsBase && urls.size == 1) {
                ActionChip("以此为底图", onClick = { actions.onUseAsBase(urls.first()) })
            }
            ActionChip("再次生成", onClick = { actions.onRegenerate(run.id) })
        }
        VersionSwitcher(run, actions)
    }
    MetaLine(run, version)
}

@Composable
private fun FailureBody(run: ImageRun, version: ImageVersion, actions: TimelineActions) {
    val clipboard = LocalClipboardManager.current
    val canceled = version.status == ImageVersionStatus.CANCELED
    var showRaw by rememberSaveable(version.id) { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val tint = if (canceled) cs.onSurfaceVariant else cs.error
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .widthIn(max = ContentMaxWidth)
            .fillMaxWidth()
            .clip(shape)
            .background(tint.copy(alpha = 0.06f))
            .border(1.dp, tint.copy(alpha = 0.18f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (canceled) Icons.Filled.StopCircle else Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (canceled) "已停止" else "生成失败",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = tint,
            )
        }
        val message = version.error?.takeIf { !canceled }
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurface.copy(alpha = 0.85f),
                maxLines = 4,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (showRaw && version.raw != null) {
            SelectionContainer {
                Text(
                    text = version.raw.orEmpty(),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = cs.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(cs.surfaceVariant.copy(alpha = 0.6f))
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                )
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            ActionChip("重试", onClick = { actions.onRetry(version.id) })
            if (!canceled && version.error != null) {
                ActionChip("复制错误", onClick = {
                    clipboard.setText(AnnotatedString(listOfNotNull(version.error, version.raw).joinToString("\n\n")))
                })
            }
            if (!canceled && version.raw != null) {
                ActionChip(if (showRaw) "收起响应" else "原始响应", onClick = { showRaw = !showRaw })
            }
        }
        VersionSwitcher(run, actions)
    }
}

@Composable
private fun VersionSwitcher(run: ImageRun, actions: TimelineActions) {
    if (run.versions.size < 2) return
    val index = run.activeIndex
    RetryBar(
        current = index,
        total = run.versions.size,
        onPrev = { run.versions.getOrNull(index - 1)?.let { actions.onSwitchVersion(run.id, it.id) } },
        onNext = { run.versions.getOrNull(index + 1)?.let { actions.onSwitchVersion(run.id, it.id) } },
    )
}

@Composable
private fun MetaLine(run: ImageRun, version: ImageVersion) {
    val first = version.outputs.firstOrNull()
    val dims = first?.takeIf { it.width > 0 && it.height > 0 }?.let { "${it.width}×${it.height}" }
        ?: parseSize(run.size)?.let { (w, h) -> "$w×$h" }
    // 旧版搬过来的记录没有真实耗时，算出来是 0，不显示。
    val took = version.endedAt?.let { (it - version.startedAt) / 1000 }?.takeIf { it > 0 }
    val parts = listOfNotNull(
        dims,
        version.outputs.size.takeIf { it > 1 }?.let { "$it 张" },
        took?.let { "用时 ${it}s" },
        version.note,
    )
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        modifier = Modifier.padding(start = 10.dp, top = 2.dp),
    )
}

/**
 * 一张时按比例铺开（宽不超过气泡、高不超过 360dp）；多张时两列方格。
 * 占位和结果用同一套尺寸，生成完成时不跳动。
 */
@Composable
private fun ImageLayout(
    count: Int,
    aspect: Float,
    item: @Composable (index: Int, modifier: Modifier) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val avail = minOf(maxWidth, ContentMaxWidth)
        if (count <= 1) {
            val width: Dp = minOf(avail, SingleMaxHeight * aspect)
            item(
                0,
                Modifier
                    .width(width)
                    .aspectRatio(aspect)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        } else {
            val gap = 6.dp
            val cell = (avail - gap) / 2
            FlowRow(
                modifier = Modifier.width(avail),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(gap),
                maxItemsInEachRow = 2,
            ) {
                repeat(count) { index ->
                    item(
                        index,
                        Modifier
                            .size(cell)
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingImage(url: String, gallery: List<String>, modifier: Modifier) {
    var loaded by remember(url) { mutableStateOf(false) }
    Box(modifier = modifier) {
        if (!loaded) Box(Modifier.fillMaxSize().shimmer())
        PreviewableImage(
            url = url,
            gallery = gallery,
            modifier = Modifier.fillMaxSize(),
            onLoaded = { loaded = true },
        )
    }
}

private fun elapsedSeconds(since: Long): Long = ((System.currentTimeMillis() - since) / 1000).coerceAtLeast(0)

private fun sizeAspect(size: String): Float =
    parseSize(size)?.let { (w, h) -> (w.toFloat() / h).coerceIn(0.4f, 2.5f) } ?: 1f

private fun outputAspect(output: ImageOutputRecord): Float? =
    output.takeIf { it.width > 0 && it.height > 0 }?.let { (it.width.toFloat() / it.height).coerceIn(0.4f, 2.5f) }
