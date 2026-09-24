package com.molagpt.app.core.render.visual

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molagpt.app.core.markdown.MdBlock
import com.molagpt.app.core.markdown.visual.CardGridSpec
import com.molagpt.app.core.markdown.visual.ChartSpec
import com.molagpt.app.core.markdown.visual.DataTableSpec
import com.molagpt.app.core.markdown.visual.FunctionPlotSpec
import com.molagpt.app.core.markdown.visual.MolaUiParsed
import com.molagpt.app.core.markdown.visual.MolaUiParser
import com.molagpt.app.core.markdown.visual.StatGridSpec

/**
 * 一个 `mola-ui` 围栏：JSON 还在流式写时是占位，解析成功是组件，用不了时是原因加可展开的源码。
 *
 * 占位一开始就按组件的真实高度预留，图出来时下面的正文不跳。不做淡入：组件不该在
 * 半透明的中间状态停留。
 *
 * @param streaming 这个围栏所在的回答还在生成。回答已结束但模型没写结束标记时，按写完处理。
 */
@Composable
fun MolaUiBlockView(block: MdBlock.MolaUi, streaming: Boolean, modifier: Modifier = Modifier) {
    val parsed = if (!block.closed && !streaming && block.parsed is MolaUiParsed.Incomplete) {
        remember(block.source) { MolaUiParser.parseCached(block.source, closed = true) }
    } else {
        block.parsed
    }
    DisableSelection {
        when (parsed) {
            is MolaUiParsed.Incomplete -> Placeholder(parsed.component, modifier)
            is MolaUiParsed.Invalid -> Fallback(parsed.reason, block.source, modifier)
            is MolaUiParsed.Ready -> when (val spec = parsed.spec) {
                is FunctionPlotSpec -> FunctionPlotView(spec, modifier)
                is ChartSpec -> ChartView(spec, modifier)
                is DataTableSpec -> DataTableView(spec, modifier)
                is StatGridSpec -> StatGridView(spec, modifier)
                is CardGridSpec -> CardGridView(spec, modifier)
            }
        }
    }
}

@Composable
private fun Placeholder(component: String?, modifier: Modifier) {
    val palette = rememberVisualPalette()
    val (height: Dp, label) = when (component) {
        "function-plot", "plot", "function" -> PLOT_HEIGHT + 90.dp to "正在绘制函数图像…"
        "chart" -> CHART_HEIGHT + 80.dp to "正在生成图表…"
        "data-table", "table" -> 420.dp to "正在生成表格…"
        "stat-grid", "stats", "metrics" -> 170.dp to "正在生成指标…"
        "card-grid", "cards" -> 260.dp to "正在生成卡片…"
        else -> 96.dp to "正在生成组件…"
    }
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .border(1.dp, palette.border, shape),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = palette.muted)
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = palette.muted)
        }
    }
}

/** 哪里不对说一句，源码点一下就能看——不留空白，也不悄悄换成别的东西。 */
@Composable
private fun Fallback(reason: String, source: String, modifier: Modifier) {
    val palette = rememberVisualPalette()
    var open by rememberSaveable { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, palette.border, shape),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = palette.muted, modifier = Modifier.size(16.dp))
            Text(
                text = "组件无法显示：$reason",
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            TextButton(onClick = { open = !open }) { Text(if (open) "收起" else "源码") }
        }
        if (open) {
            Text(
                text = source.trim(),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = palette.text,
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            )
        }
    }
}
