package com.molagpt.app.core.markdown.visual

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.text.Collator
import kotlin.math.roundToInt

// 排版类组件（表格、指标卡、条目卡）的 props。规则同 VisualSpecs：在这里校验，
// 报错点名字段；对模型实际会写的形态宽容（列写成纯字符串、行写成数组、该写字符串处写了数字）。

enum class TableColumnType { TEXT, NUMBER, DATE, BOOLEAN }

enum class TableAlign { START, CENTER, END }

data class TableColumn(val key: String, val label: String, val type: TableColumnType, val align: TableAlign)

/**
 * 一个单元格：显示的文字，加上按所在列类型排序用的键。读不成该类型的单元格没有键，
 * 升序降序都排在最后。[date] 是 yyyyMMddHHmm 形式的可比较整数（不依赖 java.time，
 * 最低支持的 Android 6 没有它）。
 */
data class TableCell(
    val text: String,
    val isEmpty: Boolean,
    val number: Double? = null,
    val date: Long? = null,
    val flag: Boolean? = null,
)

class DataTableSpec(
    val title: String?,
    val columns: List<TableColumn>,
    val rows: List<List<TableCell>>,
    val pageSize: Int,
) : VisualSpec {
    companion object {
        const val MAX_COLUMNS = 16
        const val MAX_ROWS = 1000
        const val DEFAULT_PAGE_SIZE = 10

        private val NUMBER_TEXT =
            Regex("""^\s*([+\-−]?)\s*[¥$€£]?\s*((?:\d{1,3}(?:[,，]\d{3})+|\d+)(?:\.\d+)?|\.\d+)\s*(%|‰)?\s*$""")
        private val DATE_TEXT =
            Regex("""^\s*(\d{4})\s*(?:[-/.]|年)\s*(\d{1,2})\s*(?:(?:[-/.]|月)\s*(\d{1,2})\s*日?)?\s*月?(?:[ T](\d{1,2}):(\d{2}))?""")

        fun parse(props: JsonObject): SpecResult<DataTableSpec> {
            val rowNodes = (props["rows"] as? JsonArray)?.take(MAX_ROWS)
                ?: return SpecResult.Error("rows 必须是数组")

            val columns = when (val read = readColumns(props, rowNodes)) {
                is SpecResult.Ok -> read.spec
                is SpecResult.Error -> return read
            }

            // 对象行按列 key 取值，数组行按列顺序取值。
            val raw = ArrayList<Array<JsonElement?>>(rowNodes.size)
            for (node in rowNodes) {
                val cells = arrayOfNulls<JsonElement>(columns.size)
                when (node) {
                    is JsonObject -> columns.forEachIndexed { c, column -> cells[c] = node[column.key] }
                    is JsonArray -> node.take(columns.size).forEachIndexed { c, value -> cells[c] = value }
                    else -> return SpecResult.Error("rows 的每一项应是对象（按列 key）或数组（按列顺序）")
                }
                raw += cells
            }
            if (raw.isEmpty()) return SpecResult.Error("rows 里没有数据")

            val typed = columns.mapIndexed { c, column ->
                val type = if (column.type == TableColumnType.TEXT && !column.typeDeclared) {
                    inferType(raw.map { it[c] })
                } else {
                    column.type
                }
                TableColumn(column.key, column.label, type, column.align ?: if (type == TableColumnType.NUMBER) TableAlign.END else TableAlign.START)
            }

            val rows = raw.map { cells -> cells.mapIndexed { c, value -> toCell(value, typed[c].type) } }
            val pageSize = VisualJson.number(props, "pageSize")?.roundToInt() ?: DEFAULT_PAGE_SIZE
            return SpecResult.Ok(DataTableSpec(VisualJson.string(props, "title"), typed, rows, pageSize.coerceIn(5, 50)))
        }

        private class ColumnDraft(
            val key: String,
            val label: String,
            val type: TableColumnType,
            val typeDeclared: Boolean,
            val align: TableAlign?,
        )

        private fun readColumns(props: JsonObject, rows: List<JsonElement>): SpecResult<List<ColumnDraft>> {
            val columns = ArrayList<ColumnDraft>()
            val node = props["columns"]
            if (node is JsonArray) {
                for ((index, item) in node.withIndex()) {
                    if (columns.size >= MAX_COLUMNS) break
                    when {
                        item is JsonPrimitive && item.isString ->
                            columns += ColumnDraft(item.content, item.content, TableColumnType.TEXT, false, null)
                        item is JsonObject -> {
                            val key = VisualJson.string(item, "key") ?: VisualJson.string(item, "field")
                                ?: VisualJson.string(item, "label")
                            if (key.isNullOrEmpty()) return SpecResult.Error("columns 第 ${index + 1} 项缺少 key")
                            val label = VisualJson.string(item, "label") ?: VisualJson.string(item, "title") ?: key
                            val typeText = VisualJson.string(item, "type")?.trim()?.lowercase()
                            val type = when (typeText) {
                                "number", "numeric", "int", "float", "currency", "percent" -> TableColumnType.NUMBER
                                "date", "datetime", "time" -> TableColumnType.DATE
                                "boolean", "bool" -> TableColumnType.BOOLEAN
                                else -> TableColumnType.TEXT
                            }
                            val align = when (VisualJson.string(item, "align")?.trim()?.lowercase()) {
                                "end", "right" -> TableAlign.END
                                "center", "centre" -> TableAlign.CENTER
                                "start", "left" -> TableAlign.START
                                else -> null
                            }
                            val declared = typeText == "string" || typeText == "text" || type != TableColumnType.TEXT
                            columns += ColumnDraft(key, label, type, declared, align)
                        }
                        else -> return SpecResult.Error("columns 的每一项应是字符串或 {key, label}")
                    }
                }
            } else {
                // 没写 columns：取第一个对象行的字段，按书写顺序。
                (rows.firstOrNull { it is JsonObject } as? JsonObject)?.keys?.take(MAX_COLUMNS)?.forEach { key ->
                    columns += ColumnDraft(key, key, TableColumnType.TEXT, false, null)
                }
            }

            if (columns.isEmpty()) return SpecResult.Error("columns 至少需要一列")
            if (columns.map { it.key }.distinct().size != columns.size) return SpecResult.Error("columns 的 key 不能重复")
            return SpecResult.Ok(columns)
        }

        /**
         * 一列里过半的非空单元格读得成数字（或日期、是否），这一列就按它排序。
         * 是「过半」不是「全部」：一列数字里夹一个「—」或「未公布」，不该让排序退化成字符串序。
         */
        private fun inferType(values: List<JsonElement?>): TableColumnType {
            var filled = 0
            var numbers = 0
            var dates = 0
            var flags = 0
            for (value in values) {
                if (VisualJson.isBlank(value)) continue
                filled++
                val p = value as? JsonPrimitive
                when {
                    VisualJson.isBoolean(value) -> flags++
                    VisualJson.isNumber(value) || (p != null && p.isString && parseNumber(p.content) != null) -> numbers++
                    p != null && p.isString && parseDate(p.content) != null -> dates++
                }
            }
            return when {
                filled == 0 -> TableColumnType.TEXT
                flags * 2 > filled -> TableColumnType.BOOLEAN
                numbers * 2 > filled -> TableColumnType.NUMBER
                dates * 2 > filled -> TableColumnType.DATE
                else -> TableColumnType.TEXT
            }
        }

        private fun toCell(value: JsonElement?, type: TableColumnType): TableCell {
            if (VisualJson.isBlank(value)) return TableCell("—", isEmpty = true)
            val p = value as? JsonPrimitive
            // 数字按模型写的原样显示：它写「0.10」或「2024」自有道理，把年份重排成「2,024」是经典错误。
            val text = when {
                VisualJson.isBoolean(value) -> if (p!!.booleanOrNull == true) "✓" else "✗"
                p != null -> p.content
                else -> value.toString()
            }
            return when (type) {
                TableColumnType.NUMBER -> TableCell(
                    text,
                    isEmpty = false,
                    number = if (VisualJson.isNumber(value)) p!!.content.toDoubleOrNull() else parseNumber(text),
                )
                TableColumnType.DATE -> TableCell(text, isEmpty = false, date = parseDate(text))
                TableColumnType.BOOLEAN -> TableCell(
                    text,
                    isEmpty = false,
                    flag = if (VisualJson.isBoolean(value)) p!!.booleanOrNull else parseFlag(text),
                )
                TableColumnType.TEXT -> TableCell(text, isEmpty = false)
            }
        }

        /** 表格里常见的数字装饰：正负号、货币符号、千分位、百分号。万、亿这类单位不读——提示词要求把单位写进列名。 */
        fun parseNumber(text: String?): Double? {
            if (text.isNullOrBlank()) return null
            val match = NUMBER_TEXT.matchEntire(text) ?: return null
            val digits = match.groupValues[2].replace(",", "").replace("，", "")
            val value = digits.toDoubleOrNull() ?: return null
            return if (match.groupValues[1] == "-" || match.groupValues[1] == "−") -value else value
        }

        fun parseDate(text: String?): Long? {
            if (text.isNullOrBlank()) return null
            val match = DATE_TEXT.find(text) ?: return null
            if (match.range.first != 0) return null
            val g = match.groupValues
            val year = g[1].toInt()
            val month = g[2].toInt()
            val day = g[3].ifEmpty { "1" }.toInt()
            val hour = g[4].ifEmpty { "0" }.toInt()
            val minute = g[5].ifEmpty { "0" }.toInt()
            if (year < 1 || month !in 1..12 || day < 1 || day > daysInMonth(year, month) || hour > 23 || minute > 59) return null
            return (((year * 100L + month) * 100 + day) * 100 + hour) * 100 + minute
        }

        private fun daysInMonth(year: Int, month: Int): Int = when (month) {
            2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }

        private fun parseFlag(text: String): Boolean? = when (text.trim().lowercase()) {
            "true", "yes", "y", "是", "有", "支持", "✓", "✔", "√" -> true
            "false", "no", "n", "否", "无", "不支持", "✗", "✘", "×" -> false
            else -> null
        }

        /**
         * 按列类型比较；没有排序键的单元格无论升降都排最后，翻转顺序不会把空值翻到顶上。
         * [collator] 由调用方给（中文环境下按拼音），它不是线程安全的，别跨线程共享。
         */
        fun compare(a: TableCell, b: TableCell, type: TableColumnType, descending: Boolean, collator: Collator): Int {
            val ka = key(a, type)
            val kb = key(b, type)
            if (ka == null) return if (kb == null) 0 else 1
            if (kb == null) return -1
            val order = when (ka) {
                is String -> collator.compare(ka, kb as String)
                is Double -> ka.compareTo(kb as Double)
                is Long -> ka.compareTo(kb as Long)
                is Boolean -> ka.compareTo(kb as Boolean)
                else -> 0
            }
            return if (descending) -order else order
        }

        private fun key(cell: TableCell, type: TableColumnType): Any? =
            if (cell.isEmpty) {
                null
            } else {
                when (type) {
                    TableColumnType.NUMBER -> cell.number
                    TableColumnType.DATE -> cell.date
                    TableColumnType.BOOLEAN -> cell.flag
                    TableColumnType.TEXT -> cell.text
                }
            }
    }
}

enum class StatTrend { NONE, UP, DOWN, FLAT }

/**
 * 这个变化是好消息还是坏消息。刻意和方向分开：成本上升是「涨」却是坏事；
 * 国内市场红涨绿跌、海外相反——按方向着色总会错一边。
 */
enum class StatTone { NEUTRAL, GOOD, BAD }

data class StatItem(
    val label: String,
    val value: String,
    val unit: String?,
    val delta: String?,
    val trend: StatTrend,
    val tone: StatTone,
    val note: String?,
    val history: List<Double>,
)

class StatGridSpec(val title: String?, val items: List<StatItem>, val periods: List<String>) : VisualSpec {
    companion object {
        const val MAX_ITEMS = 12
        const val MAX_HISTORY = 120

        fun parse(props: JsonObject): SpecResult<StatGridSpec> {
            val itemsNode = props["items"] as? JsonArray ?: return SpecResult.Error("items 必须是数组")
            val items = ArrayList<StatItem>()
            for ((index, item) in itemsNode.withIndex()) {
                if (items.size >= MAX_ITEMS) break
                if (item !is JsonObject) return SpecResult.Error("items 第 ${index + 1} 项应是对象")

                val label = VisualJson.string(item, "label") ?: VisualJson.string(item, "name") ?: VisualJson.string(item, "title")
                if (label.isNullOrBlank()) return SpecResult.Error("items 第 ${index + 1} 项缺少 label")

                val valueNode = item["value"]
                if (valueNode == null || valueNode is JsonNull) return SpecResult.Error("指标 $label 缺少 value")
                val value = when {
                    VisualJson.isBoolean(valueNode) -> if ((valueNode as JsonPrimitive).booleanOrNull == true) "是" else "否"
                    else -> VisualJson.text(valueNode)
                }

                val delta = text(item, "delta") ?: text(item, "change")
                val trend = when (VisualJson.string(item, "trend")?.trim()?.lowercase()) {
                    "up", "rise", "increase", "上升", "上涨" -> StatTrend.UP
                    "down", "fall", "decrease", "下降", "下跌" -> StatTrend.DOWN
                    "flat", "stable", "持平" -> StatTrend.FLAT
                    else -> trendOf(delta)
                }
                val tone = when (VisualJson.string(item, "tone")?.trim()?.lowercase()) {
                    "good", "positive", "好" -> StatTone.GOOD
                    "bad", "negative", "坏", "差" -> StatTone.BAD
                    else -> StatTone.NEUTRAL
                }

                val history = ArrayList<Double>()
                (item["history"] as? JsonArray)?.takeLast(MAX_HISTORY)?.forEach { point ->
                    val number = when {
                        VisualJson.isNumber(point) -> VisualJson.numberOf(point)
                        point is JsonPrimitive && point.isString -> DataTableSpec.parseNumber(point.content)
                        else -> null
                    }
                    if (number != null && number.isFinite()) history += number
                }

                items += StatItem(label.trim(), value, text(item, "unit"), delta, trend, tone, text(item, "note"), history)
            }
            if (items.isEmpty()) return SpecResult.Error("items 至少需要一项")

            val periods = (props["periods"] as? JsonArray)?.takeLast(MAX_HISTORY)?.map { VisualJson.text(it) }.orEmpty()
            return SpecResult.Ok(StatGridSpec(VisualJson.string(props, "title"), items, periods))
        }

        /** 只看方向，读模型已经写出的正负号。 */
        private fun trendOf(delta: String?): StatTrend {
            val text = delta?.trimStart()
            if (text.isNullOrEmpty()) return StatTrend.NONE
            return when (text[0]) {
                '+', '↑', '▲' -> StatTrend.UP
                '-', '−', '↓', '▼' -> StatTrend.DOWN
                else -> StatTrend.NONE
            }
        }

        private fun text(owner: JsonObject, name: String): String? {
            val node = VisualJson.primitive(owner[name]) ?: return null
            if (VisualJson.isBoolean(node)) return null
            return node.content.trim().ifEmpty { null }
        }
    }
}

data class CardItem(val title: String, val summary: String?, val tag: String?, val source: String?, val url: String?)

class CardGridSpec(val title: String?, val items: List<CardItem>, val tags: List<String>) : VisualSpec {
    companion object {
        const val MAX_ITEMS = 24

        fun parse(props: JsonObject): SpecResult<CardGridSpec> {
            val itemsNode = props["items"] as? JsonArray ?: return SpecResult.Error("items 必须是数组")
            val items = ArrayList<CardItem>()
            for ((index, item) in itemsNode.withIndex()) {
                if (items.size >= MAX_ITEMS) break
                if (item !is JsonObject) return SpecResult.Error("items 第 ${index + 1} 项应是对象")
                val title = clean(VisualJson.string(item, "title") ?: VisualJson.string(item, "name"))
                    ?: return SpecResult.Error("items 第 ${index + 1} 项缺少 title")
                items += CardItem(
                    title = title,
                    summary = clean(VisualJson.string(item, "summary") ?: VisualJson.string(item, "description")),
                    tag = clean(VisualJson.string(item, "tag")),
                    source = clean(VisualJson.string(item, "source")),
                    url = clean(VisualJson.string(item, "url") ?: VisualJson.string(item, "link")),
                )
            }
            if (items.isEmpty()) return SpecResult.Error("items 至少需要一项")
            // 筛选项取卡片上出现过的 tag，按首次出现顺序；模型不单独声明，就不会和卡片对不上。
            val tags = items.mapNotNull { it.tag }.distinct()
            return SpecResult.Ok(CardGridSpec(VisualJson.string(props, "title"), items, tags))
        }

        private fun clean(text: String?): String? = text?.trim()?.ifEmpty { null }
    }
}
