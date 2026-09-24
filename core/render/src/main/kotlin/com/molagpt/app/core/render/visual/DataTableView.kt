package com.molagpt.app.core.render.visual

import android.widget.Toast
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.molagpt.app.core.markdown.visual.DataTableSpec
import com.molagpt.app.core.markdown.visual.TableAlign
import com.molagpt.app.core.markdown.visual.TableCell
import com.molagpt.app.core.markdown.visual.TableColumn
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale

/**
 * 内嵌数据表：点列头排序、搜索、翻页、复制到表格软件或导出 CSV。
 *
 * 翻页而不是表内滚动：表格吃掉竖向滑动会把读者困在里面。一次只有一页单元格，一千行的表
 * 和十行一样便宜。手机放不下的列横向滑动，首列固定，滑到哪一列都知道是哪一行。
 */
@Composable
internal fun DataTableView(spec: DataTableSpec, modifier: Modifier = Modifier) {
    val palette = rememberVisualPalette()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var query by rememberSaveable { mutableStateOf("") }
    var sortColumn by rememberSaveable { mutableIntStateOf(-1) }
    var descending by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    // 最后一页通常更短。高度只增不减，「下一页」就一直停在手指下。
    var bodyMin by remember { mutableIntStateOf(0) }

    val collator = remember { Collator.getInstance(Locale.getDefault()) }
    val needle = query.trim()
    val view = remember(spec, needle, sortColumn, descending) {
        var rows: List<List<TableCell>> = spec.rows
        if (needle.isNotEmpty()) {
            rows = rows.filter { row -> row.any { !it.isEmpty && it.text.contains(needle, ignoreCase = true) } }
        }
        if (sortColumn in spec.columns.indices) {
            val type = spec.columns[sortColumn].type
            rows = rows.sortedWith { a, b -> DataTableSpec.compare(a[sortColumn], b[sortColumn], type, descending, collator) }
        }
        rows
    }
    val pageCount = maxOf(1, (view.size + spec.pageSize - 1) / spec.pageSize)
    val current = page.coerceIn(0, pageCount - 1)
    val rows = view.drop(current * spec.pageSize).take(spec.pageSize)

    val count = if (needle.isNotEmpty()) "${view.size} / ${spec.rows.size} 行" else "${spec.rows.size} 行"
    VisualFrame(
        title = spec.title?.takeIf { it.isNotBlank() } ?: "表格",
        meta = count,
        imageName = "表格",
        modifier = modifier,
        menu = listOf(
            VisualMenuItem("复制表格") {
                clipboard.setText(AnnotatedString(tsv(spec, view)))
                Toast.makeText(context, "已复制 ${view.size} 行，可直接粘贴到表格软件", Toast.LENGTH_SHORT).show()
            },
            VisualMenuItem("导出 CSV") {
                scope.launch {
                    runCatching {
                        VisualExport.shareText(
                            context,
                            VisualExport.safeName(spec.title, "表格") + ".csv",
                            "text/csv",
                            csv(spec, view),
                            bom = true,
                        )
                    }.onFailure { Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show() }
                }
            },
        ),
    ) {
        // 搜索只在超过一页时才值得占位置。
        if (spec.rows.size > spec.pageSize) {
            SearchField(query, palette) {
                query = it
                page = 0
                bodyMin = 0
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .heightIn(min = with(LocalDensity.current) { bodyMin.toDp() })
                .onSizeChanged { if (it.height > bodyMin) bodyMin = it.height },
        ) {
            TableBody(spec, rows, needle, sortColumn, descending, palette) { column ->
                // 升序、降序、再回到模型写的顺序。
                when {
                    sortColumn != column -> {
                        sortColumn = column
                        descending = false
                    }
                    !descending -> descending = true
                    else -> sortColumn = -1
                }
            }
        }
        if (pageCount > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "第 ${current + 1} / $pageCount 页",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.muted,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { page = current - 1 },
                    enabled = current > 0,
                ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一页") }
                IconButton(
                    onClick = { page = current + 1 },
                    enabled = current < pageCount - 1,
                ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一页") }
            }
        } else {
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun SearchField(query: String, palette: VisualPalette, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 6.dp)
            .height(38.dp)
            .clip(shape)
            .border(1.dp, palette.border, shape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = palette.muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("搜索", style = MaterialTheme.typography.bodyMedium, color = palette.muted)
            }
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.text),
                cursorBrush = SolidColor(palette.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TableBody(
    spec: DataTableSpec,
    rows: List<List<TableCell>>,
    needle: String,
    sortColumn: Int,
    descending: Boolean,
    palette: VisualPalette,
    onSort: (Int) -> Unit,
) {
    val widths = remember(spec) { columnWidths(spec) }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val available = maxWidth - 2.dp
        val total = widths.fold(0.dp) { acc, w -> acc + w }
        // 放得下就按比例撑满；放不下且列够多时固定首列、其余横向滑动。
        val fitted = if (total < available) widths.map { it * (available / total) } else widths
        val pin = total > available && spec.columns.size >= 3
        val scroll = rememberScrollState()
        Column(modifier = Modifier.fillMaxWidth()) {
            TableRow(
                widths = fitted,
                pin = pin,
                scroll = scroll,
                header = true,
                palette = palette,
            ) { c ->
                HeaderCell(spec.columns[c], sortColumn == c, descending, palette) { onSort(c) }
            }
            if (rows.isEmpty()) {
                Text(
                    text = "没有匹配的行",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                )
            }
            rows.forEach { row ->
                TableRow(widths = fitted, pin = pin, scroll = scroll, header = false, palette = palette) { c ->
                    BodyCell(row[c], spec.columns[c], needle, palette)
                }
            }
        }
    }
}

@Composable
private fun TableRow(
    widths: List<Dp>,
    pin: Boolean,
    scroll: ScrollState,
    header: Boolean,
    palette: VisualPalette,
    cell: @Composable (Int) -> Unit,
) {
    val background = if (header) palette.grid.copy(alpha = 0.45f) else palette.surface
    Column(modifier = Modifier.fillMaxWidth().background(background)) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            val scrolled = if (pin) 1 else 0
            if (pin) {
                Box(modifier = Modifier.width(widths[0]).fillMaxHeight()) { cell(0) }
            }
            Row(modifier = Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
                for (c in scrolled until widths.size) {
                    Box(modifier = Modifier.width(widths[c]).fillMaxHeight()) { cell(c) }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.grid))
    }
}

@Composable
private fun HeaderCell(column: TableColumn, sorted: Boolean, descending: Boolean, palette: VisualPalette, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        horizontalArrangement = when (column.align) {
            TableAlign.END -> Arrangement.End
            TableAlign.CENTER -> Arrangement.Center
            TableAlign.START -> Arrangement.Start
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = column.label,
            style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp),
            color = palette.text,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (sorted) {
            Icon(
                if (descending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                contentDescription = if (descending) "降序" else "升序",
                tint = palette.accent,
                modifier = Modifier.padding(start = 2.dp).size(13.dp),
            )
        }
    }
}

@Composable
private fun BodyCell(cell: TableCell, column: TableColumn, needle: String, palette: VisualPalette) {
    val align = when (column.align) {
        TableAlign.END -> TextAlign.End
        TableAlign.CENTER -> TextAlign.Center
        TableAlign.START -> TextAlign.Start
    }
    val text = if (!cell.isEmpty && needle.isNotEmpty() && cell.text.contains(needle, ignoreCase = true)) {
        highlight(cell.text, needle, palette)
    } else {
        AnnotatedString(cell.text)
    }
    Text(
        text = text,
        style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
        color = if (cell.isEmpty) palette.muted else palette.text,
        textAlign = align,
        maxLines = 6,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

private fun highlight(text: String, needle: String, palette: VisualPalette): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    while (cursor < text.length) {
        val at = text.indexOf(needle, cursor, ignoreCase = true)
        if (at < 0) break
        append(text.substring(cursor, at))
        pushStyle(SpanStyle(background = palette.accent.copy(alpha = 0.24f)))
        append(text.substring(at, at + needle.length))
        pop()
        cursor = at + needle.length
    }
    if (cursor < text.length) append(text.substring(cursor))
}

/**
 * 按字符估列宽（中文一格、西文约半格），只看表头和前 200 行。宽度按整张表定下来，
 * 翻页、排序时列不跳宽。
 */
private fun columnWidths(spec: DataTableSpec): List<Dp> {
    val sample = spec.rows.take(200)
    return spec.columns.mapIndexed { c, column ->
        val header = estimate(column.label) * 12.5f + 26f
        val body = sample.maxOfOrNull { estimate(it[c].text) * 13f } ?: 0f
        maxOf(header, body + 22f).coerceIn(64f, 200f).dp
    }
}

private fun estimate(text: String): Float {
    var em = 0f
    for (ch in text) em += if (ch.code >= 0x2E80) 1f else if (ch.isUpperCase() || ch.isDigit()) 0.6f else 0.52f
    return em
}

/** 制表符分隔：粘贴进表格软件就按单元格拆开。按当前显示的顺序（筛选、排序后），所有页。 */
private fun tsv(spec: DataTableSpec, rows: List<List<TableCell>>): String = buildString {
    fun flatten(value: String) = value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')
    append(spec.columns.joinToString("\t") { flatten(it.label) }).append('\n')
    rows.forEach { row -> append(row.joinToString("\t") { if (it.isEmpty) "" else flatten(it.text) }).append('\n') }
}

private fun csv(spec: DataTableSpec, rows: List<List<TableCell>>): String = buildString {
    fun escape(value: String) =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
    append(spec.columns.joinToString(",") { escape(it.label) }).append("\r\n")
    rows.forEach { row -> append(row.joinToString(",") { if (it.isEmpty) "" else escape(it.text) }).append("\r\n") }
}
