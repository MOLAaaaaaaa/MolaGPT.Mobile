package com.molagpt.app.core.network

/** Separates inline reasoning without consuming tag literals in Markdown or comments. */
class InlineThinkSplitter {
    data class Segment(val text: String, val thinking: Boolean)

    private var thinking = false
    private var pending = ""
    private var comment = false
    private var inlineTicks = 0
    private var fence = ' '
    private var fenceSize = 0
    private var indent = 0
    private var escaped = false

    fun feed(text: String): List<Segment> = scan(pending + text, eof = false)
    fun flush(): List<Segment> = scan(pending, eof = true)

    private fun scan(text: String, eof: Boolean): List<Segment> {
        val segments = ArrayList<Segment>()
        val output = StringBuilder()
        fun emit() {
            if (output.isNotEmpty()) {
                segments.add(Segment(output.toString(), thinking))
                output.setLength(0)
            }
        }
        fun copy(from: Int, to: Int) {
            output.append(text, from, to)
            for (i in from until to) {
                val c = text[i]
                indent = when {
                    c == '\n' || c == '\r' -> 0
                    c == ' ' && indent >= 0 -> indent + 1
                    else -> -1
                }
                escaped = c == '\\' && !escaped
            }
        }
        fun partial(at: Int, token: String): Boolean =
            !eof && text.length - at < token.length &&
                text.regionMatches(at, token, 0, text.length - at, ignoreCase = true)

        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (comment) {
                if (partial(i, "-->")) break
                if (text.startsWith("-->", i)) {
                    copy(i, i + 3)
                    i += 3
                    comment = false
                    continue
                }
            } else if ((c == BACKTICK || c == '~') && (!escaped || fenceSize > 0 || inlineTicks > 0)) {
                var end = i + 1
                while (end < text.length && text[end] == c) end++
                if (end == text.length && !eof) break
                val count = end - i
                val atMargin = indent in 0..3
                if (fenceSize > 0) {
                    if (atMargin && c == fence && count >= fenceSize) {
                        var after = end
                        while (after < text.length && text[after] in " \t") after++
                        if (after == text.length && !eof) break
                        if (after == text.length || text[after] in "\r\n") fenceSize = 0
                    }
                } else if (inlineTicks > 0) {
                    if (c == BACKTICK && count == inlineTicks) inlineTicks = 0
                } else if (atMargin && count >= 3) {
                    fence = c
                    fenceSize = count
                } else if (c == BACKTICK) {
                    inlineTicks = count
                }
                copy(i, end)
                i = end
                continue
            } else if (c == '<' && !escaped && fenceSize == 0 && inlineTicks == 0) {
                val tag = if (thinking) "</think" else "<think"
                if (partial(i, "<!--") || partial(i, tag)) break
                if (text.startsWith("<!--", i)) {
                    copy(i, i + 4)
                    i += 4
                    comment = true
                    continue
                }
                if (text.regionMatches(i, tag, 0, tag.length, ignoreCase = true)) {
                    val start = i + tag.length
                    if (start == text.length && !eof) break
                    if (start < text.length && (text[start] == '>' || text[start] in " \t\r\n")) {
                        var end = start
                        var quote = '\u0000'
                        while (end < text.length && end - start < 4096) {
                            val ch = text[end]
                            if (quote == '\u0000' && ch in "<>") break
                            if (quote == ch) quote = '\u0000'
                            else if (quote == '\u0000' && ch in "\"'") quote = ch
                            end++
                        }
                        if (end == text.length && !eof && end - start < 4096) break
                        if (end < text.length && text[end] == '>' &&
                            (if (thinking) text.substring(start, end).isBlank()
                            else attributes.matches(text.substring(start, end)))
                        ) {
                            emit()
                            thinking = !thinking
                            indent = -1
                            escaped = false
                            i = end + 1
                            continue
                        }
                    }
                }
            }
            copy(i, i + 1)
            i++
        }
        pending = text.substring(i)
        emit()
        return segments
    }

    companion object {
        private val attributes = Regex("""(?:\s+[A-Za-z_:][-A-Za-z0-9_:.]*(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s"'=<>\x60]+))?)*\s*""")
        private const val BACKTICK = 96.toChar()

        fun visibleText(text: String): String {
            val splitter = InlineThinkSplitter()
            return (splitter.feed(text) + splitter.flush()).filterNot { it.thinking }.joinToString("") { it.text }
        }
    }
}
