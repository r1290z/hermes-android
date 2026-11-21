package ru.evgen.hermes

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.StrikethroughSpan
import android.text.style.TypefaceSpan
import android.view.View
import android.widget.TextView

/**
 * Just enough Markdown for a chat transcript, without a library.
 *
 * Markwon would drag in a renderer, an image loader and its own theme; what the
 * transcript actually needs is: fenced code, headings, bullet and numbered
 * lists, quotes, bold/italic/strike, inline code and links. That is a linear
 * scan, and keeping it in-house means the code blocks can be lifted out into
 * their own copyable card instead of being buried inside one giant TextView.
 */
object Markdown {

    sealed class Block {
        data class Paragraph(val text: String) : Block()
        data class Heading(val level: Int, val text: String) : Block()
        /** `level`: 0 top, 1 nested once, 2 nested twice; `marker` is "•" for
         *  bullets or the literal number ("3.") for ordered items. */
        data class BulletItem(val level: Int, val marker: String, val text: String)
        data class Bullets(val items: List<BulletItem>, val ordered: Boolean) : Block()
        data class Quote(val text: String) : Block()
        data class Code(val language: String, val code: String) : Block()
        data class Table(val headers: List<String>, val rows: List<List<String>>) : Block()
        data class Rule(val dummy: Int = 0) : Block()
    }

    fun parse(source: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val lines = source.replace("\r\n", "\n").split('\n')
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            // Fenced code first: everything inside is literal.
            val fence = fenceOf(line)
            if (fence != null) {
                val language = line.trim().removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && fenceOf(lines[i]) == null) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                i++ // closing fence
                blocks += Block.Code(language, code.toString().trimEnd('\n'))
                continue
            }

            if (line.isBlank()) { i++; continue }

            // Table: header row of pipes plus a |---|---| separator under it,
            // or an ASCII box table (+----+ borders, cells padded with spaces).
            val asciiTable = i + 1 < lines.size && line.trim().matches(Regex("^\\+[-=+:\\s]+\\+$")) &&
                lines[i + 1].count { it == '|' } >= 2
            if (asciiTable) {
                val rows = mutableListOf<List<String>>()
                var j = i
                while (j < lines.size) {
                    val l = lines[j].trim()
                    if (l.matches(Regex("^\\+[-=+:\\s]+\\+$"))) { j++; continue }
                    if (l.count { it == '|' } >= 2) { rows += splitRow(l); j++; continue }
                    break
                }
                if (rows.isNotEmpty()) {
                    blocks += Block.Table(rows.first(), rows.drop(1))
                    i = j
                    continue
                }
            }
            if (i + 1 < lines.size && line.count { it == '|' } >= 2 &&
                tableSeparator(lines[i + 1]) != null
            ) {
                val headers = splitRow(line)
                val rows = mutableListOf<List<String>>()
                var j = i + 2
                while (j < lines.size && lines[j].count { it == '|' } >= 2) {
                    rows += splitRow(lines[j])
                    j++
                }
                if (headers.isNotEmpty()) {
                    blocks += Block.Table(headers, rows)
                    i = j
                    continue
                }
            }

            if (line.trim().matches(Regex("^(-{3,}|\\*{3,}|_{3,})$"))) {
                blocks += Block.Rule()
                i++
                continue
            }

            val heading = Regex("^(#{1,6})\\s+(.*)$").find(line)
            if (heading != null) {
                blocks += Block.Heading(heading.groupValues[1].length, heading.groupValues[2])
                i++
                continue
            }

            val bullet = Regex("^\\s*([-*+]|\\d+[.)])\\s+(.*)$").find(line)
            if (bullet != null) {
                val ordered = bullet.groupValues[1].first().isDigit()
                val items = mutableListOf<Block.BulletItem>()
                var number = 0
                while (i < lines.size) {
                    val m = Regex("^\\s*([-*+]|\\d+[.)])\\s+(.*)$").find(lines[i]) ?: break
                    if (m.groupValues[1].first().isDigit() != ordered) break
                    val indent = lines[i].takeWhile { it == ' ' }.length
                    // Two levels: 0-1 spaces = top, deeper (3+) = nested. A list
                    // where every item sits on the same indent stays flat.
                    val level = if (indent >= 3) 1 else 0
                    if (m.groupValues[1].first().isDigit()) number++ else number = 0
                    val marker = if (ordered) "$number." else
                        if (level == 1) "◦" else "•"
                    var text = m.groupValues[2]
                    // A wrapped continuation line belongs to the item above it.
                    var j = i + 1
                    while (j < lines.size && lines[j].startsWith("  ") &&
                        lines[j].trim().isNotEmpty() &&
                        Regex("^\\s*([-*+]|\\d+[.)])\\s+").find(lines[j]) == null
                    ) {
                        text += " " + lines[j].trim()
                        j++
                    }
                    items += Block.BulletItem(level, marker, text)
                    i = j
                }
                blocks += Block.Bullets(items, ordered)
                continue
            }

            if (line.trimStart().startsWith(">")) {
                val quote = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    quote.append(lines[i].trimStart().removePrefix(">").trim()).append(' ')
                    i++
                }
                blocks += Block.Quote(quote.toString().trim())
                continue
            }

            // Paragraph: consume until a blank line or the start of another block.
            val para = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank() &&
                fenceOf(lines[i]) == null &&
                !lines[i].trimStart().startsWith(">") &&
                Regex("^#{1,6}\\s").find(lines[i]) == null &&
                Regex("^\\s*([-*+]|\\d+[.)])\\s+").find(lines[i]) == null &&
                lines[i].count { it == '|' } < 2
            ) {
                if (para.isNotEmpty()) para.append('\n')
                para.append(lines[i])
                i++
            }
            if (para.isNotEmpty()) blocks += Block.Paragraph(para.toString())
        }
        return blocks
    }

    private fun fenceOf(line: String): String? {
        val trimmed = line.trimStart()
        return if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) trimmed.take(3) else null
    }

    /** `| --- | :---: |` or an ASCII border `+----+----+` → true; else null. */
    private fun tableSeparator(line: String): Boolean? {
        val t = line.trim()
        if (t.matches(Regex("^\\+[-=+:\\s]+\\+$"))) return true
        if (t.count { it == '|' } < 2) return null
        val cells = splitRow(t)
        if (cells.isEmpty()) return null
        return cells.all { it.matches(Regex(":?-{2,}:?")) }
    }

    /** `| a | b |` → [a, b]. Leading/trailing pipes are optional. */
    private fun splitRow(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|")) t = t.substring(0, t.length - 1)
        return t.split('|').map { it.trim() }
    }

    /**
     * Inline markup into spans. `onLink` lets the caller open a URL, so this
     * file never has to know about Intents.
     */
    fun inline(
        text: String,
        boldColor: Int = 0,
        codeBackground: Int = 0,
        linkColor: Int = 0,
        onLink: ((String) -> Unit)? = null
    ): CharSequence {
        val out = SpannableStringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                // `code`
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        val start = out.length
                        out.append(text, i + 1, end)
                        if (codeBackground != 0) {
                            out.setSpan(BackgroundColorSpan(codeBackground), start, out.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        out.setSpan(TypefaceSpan("monospace"), start, out.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(RelativeSizeSpan(0.94f), start, out.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 1
                    } else {
                        out.append(c); i++
                    }
                }
                // **bold** and __bold__
                text.startsWith("**", i) || text.startsWith("__", i) -> {
                    val marker = text.substring(i, i + 2)
                    val end = text.indexOf(marker, i + 2)
                    if (end > i) {
                        val start = out.length
                        out.append(inline(text.substring(i + 2, end), boldColor, codeBackground, linkColor, onLink))
                        out.setSpan(StyleSpan(Typeface.BOLD), start, out.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 2
                    } else {
                        out.append(marker); i += 2
                    }
                }
                // ~~strike~~
                text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end > i) {
                        val start = out.length
                        out.append(text, i + 2, end)
                        out.setSpan(StrikethroughSpan(), start, out.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 2
                    } else {
                        out.append("~~"); i += 2
                    }
                }
                // *italic* — single star, not part of a bullet
                c == '*' && i + 1 < text.length && text[i + 1] != ' ' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i + 1) {
                        val start = out.length
                        out.append(inline(text.substring(i + 1, end), boldColor, codeBackground, linkColor, onLink))
                        out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 1
                    } else {
                        out.append(c); i++
                    }
                }
                // [label](url)
                c == '[' -> {
                    val close = text.indexOf(']', i)
                    val open = if (close > i && close + 1 < text.length && text[close + 1] == '(')
                        text.indexOf(')', close) else -1
                    if (close > i && open > close) {
                        val label = text.substring(i + 1, close)
                        val url = text.substring(close + 2, open)
                        val start = out.length
                        out.append(label)
                        if (linkColor != 0) {
                            out.setSpan(ForegroundColorSpan(linkColor), start, out.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        out.setSpan(object : ClickableSpan() {
                            override fun onClick(widget: View) { onLink?.invoke(url) }
                        }, start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = open + 1
                    } else {
                        out.append(c); i++
                    }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out
    }

    /** Bullets get a real marker; a TextView cannot draw one by itself. */
    fun bullets(items: List<Block.BulletItem>, ordered: Boolean): CharSequence {
        val out = SpannableStringBuilder()
        items.forEachIndexed { index, item ->
            if (index > 0) out.append('\n')
            repeat(item.level) { out.append("   ") }
            out.append(item.marker).append(' ')
            out.append(inline(item.text))
        }
        return out
    }
}
