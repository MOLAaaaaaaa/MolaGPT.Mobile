package com.molagpt.app.feature.file

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * .docx → Markdown。零第三方依赖：docx 本质是个 ZIP，正文在 `word/document.xml`，
 * 用 SDK 自带的 [ZipInputStream] + [XmlPullParser] 就能解，不必为此引入 POI 那种量级的库。
 *
 * 只还原对模型有用的结构——标题、列表、表格、段落。run 级的粗体/斜体刻意不处理：
 * 对理解内容几乎没有帮助，却要额外一倍的解析分支。
 */
object DocxParser {

    fun parse(file: File): String = runCatching {
        file.inputStream().use { fileStream ->
            ZipInputStream(fileStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") return@runCatching parseDocument(zip)
                    entry = zip.nextEntry
                }
                ""
            }
        }
    }.getOrDefault("")

    private fun parseDocument(input: InputStream): String {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(input, "UTF-8")

        val out = StringBuilder()
        var inBody = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "body" -> inBody = true
                    "p" -> if (inBody) out.appendParagraph(readParagraph(parser))
                    "tbl" -> if (inBody) out.appendTable(readTable(parser))
                }

                XmlPullParser.END_TAG -> if (parser.name == "body") inBody = false
            }
            parser.next()
        }
        return out.toString().trim()
    }

    private data class Paragraph(val text: String, val headingLevel: Int, val listLevel: Int, val numbered: Boolean)

    private fun StringBuilder.appendParagraph(p: Paragraph) {
        if (p.text.isBlank()) return
        when {
            p.headingLevel > 0 -> append("#".repeat(p.headingLevel.coerceAtMost(6))).append(' ').append(p.text).append("\n\n")
            p.listLevel >= 0 -> {
                append("  ".repeat(p.listLevel))
                append(if (p.numbered) "1. " else "- ")
                append(p.text).append('\n')
            }

            else -> append(p.text).append("\n\n")
        }
    }

    private fun StringBuilder.appendTable(rows: List<List<String>>) {
        if (rows.isEmpty()) return
        val columns = rows.maxOf { it.size }
        rows.forEachIndexed { index, row ->
            append("| ")
            for (col in 0 until columns) append(row.getOrElse(col) { "" }).append(" | ")
            append('\n')
            if (index == 0) {
                append("| ")
                repeat(columns) { append("--- | ") }
                append('\n')
            }
        }
        append('\n')
    }

    /** 读完一个 `<w:p>`：文本 + 段落属性（标题级别 / 列表层级）。 */
    private fun readParagraph(parser: XmlPullParser): Paragraph {
        val depth = parser.depth
        val text = StringBuilder()
        var headingLevel = 0
        var listLevel = -1
        var numbered = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "t" -> {
                        parser.next()
                        if (parser.eventType == XmlPullParser.TEXT) text.append(parser.text.orEmpty())
                    }

                    "tab" -> text.append('\t')
                    "br" -> text.append('\n')
                    "pPr" -> readParagraphProperties(parser).let {
                        headingLevel = it.first
                        listLevel = it.second
                        numbered = it.third
                    }
                }

                XmlPullParser.END_TAG -> if (parser.name == "p" && parser.depth == depth) break
            }
        }
        return Paragraph(text.toString().trim(), headingLevel, listLevel, numbered)
    }

    /** @return (标题级别, 列表层级或 -1, 是否有序) */
    private fun readParagraphProperties(parser: XmlPullParser): Triple<Int, Int, Boolean> {
        val depth = parser.depth
        var heading = 0
        var listLevel = -1
        var numbered = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "pStyle" -> {
                        val style = parser.getAttributeValue(null, "val").orEmpty()
                        if (style.startsWith("Heading", ignoreCase = true)) {
                            heading = style.lastOrNull()?.digitToIntOrNull() ?: 1
                        }
                    }

                    "numPr" -> {
                        if (listLevel < 0) listLevel = 0
                        val numPrDepth = parser.depth
                        while (parser.next() != XmlPullParser.END_DOCUMENT) {
                            when (parser.eventType) {
                                XmlPullParser.START_TAG -> when (parser.name) {
                                    "ilvl" -> listLevel = parser.getAttributeValue(null, "val")?.toIntOrNull() ?: 0
                                    "numId" -> numbered = parser.getAttributeValue(null, "val") != null
                                }

                                XmlPullParser.END_TAG ->
                                    if (parser.name == "numPr" && parser.depth == numPrDepth) break
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> if (parser.name == "pPr" && parser.depth == depth) break
            }
        }
        return Triple(heading, listLevel, numbered)
    }

    private fun readTable(parser: XmlPullParser): List<List<String>> {
        val depth = parser.depth
        val rows = mutableListOf<List<String>>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> if (parser.name == "tr") {
                    readRow(parser).takeIf { it.isNotEmpty() }?.let(rows::add)
                }

                XmlPullParser.END_TAG -> if (parser.name == "tbl" && parser.depth == depth) break
            }
        }
        return rows
    }

    private fun readRow(parser: XmlPullParser): List<String> {
        val depth = parser.depth
        val cells = mutableListOf<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> if (parser.name == "tc") cells.add(readCell(parser))
                XmlPullParser.END_TAG -> if (parser.name == "tr" && parser.depth == depth) break
            }
        }
        return cells
    }

    /** 单元格里可能有多段，用空格连起来——Markdown 表格不能换行。 */
    private fun readCell(parser: XmlPullParser): String {
        val depth = parser.depth
        val parts = mutableListOf<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> if (parser.name == "p") {
                    readParagraph(parser).text.takeIf { it.isNotBlank() }?.let(parts::add)
                }

                XmlPullParser.END_TAG -> if (parser.name == "tc" && parser.depth == depth) break
            }
        }
        return parts.joinToString(" ").replace('|', '/')
    }
}
