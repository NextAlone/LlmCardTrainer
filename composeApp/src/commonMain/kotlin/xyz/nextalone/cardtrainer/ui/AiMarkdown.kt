package xyz.nextalone.cardtrainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor

/**
 * Render markdown with GFM-style pipe tables.
 *
 * The third-party renderer's default TABLE component stacks cells vertically
 * — visually broken. We work around it by parsing pipe tables out of the
 * input first and rendering them with a Compose Row/Column grid; non-table
 * segments fall through to the library's standard markdown rendering.
 */
@Composable
fun AiMarkdown(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { splitIntoBlocks(text) }
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Text -> Markdown(
                    content = block.content,
                    flavour = GFMFlavourDescriptor(),
                    colors = com.mikepenz.markdown.m3.markdownColor(
                        text = MaterialTheme.colorScheme.onSurface,
                    ),
                    typography = com.mikepenz.markdown.m3.markdownTypography(
                        h1 = MaterialTheme.typography.titleLarge,
                        h2 = MaterialTheme.typography.titleMedium,
                        h3 = MaterialTheme.typography.titleSmall,
                        text = MaterialTheme.typography.bodyMedium,
                        paragraph = MaterialTheme.typography.bodyMedium,
                        list = MaterialTheme.typography.bodyMedium,
                        code = MaterialTheme.typography.bodySmall,
                    ),
                )
                is MarkdownBlock.Table -> TableBlock(block.headers, block.rows)
            }
        }
    }
}

@Composable
private fun TableBlock(headers: List<String>, rows: List<List<String>>) {
    val border = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .border(width = 1.dp, color = border, shape = RoundedCornerShape(6.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primaryContainer),
        ) {
            headers.forEach { h ->
                Text(
                    // Headers are already bold via fontWeight below; still
                    // parse inline markdown so `code` and italics render,
                    // and in case the model emits redundant **…** markers.
                    text = renderInlineMarkdown(h),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        rows.forEachIndexed { idx, row ->
            val cellCount = headers.size
            val padded = row + List((cellCount - row.size).coerceAtLeast(0)) { "" }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (idx % 2 == 0) MaterialTheme.colorScheme.surface
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
            ) {
                padded.take(cellCount).forEach { cell ->
                    Text(
                        text = renderInlineMarkdown(cell),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private sealed class MarkdownBlock {
    data class Text(val content: String) : MarkdownBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock()
}

private fun splitIntoBlocks(text: String): List<MarkdownBlock> {
    val lines = text.lines()
    val out = mutableListOf<MarkdownBlock>()
    val mdBuffer = StringBuilder()

    fun flush() {
        if (mdBuffer.isNotBlank()) {
            out += MarkdownBlock.Text(mdBuffer.toString().trimEnd('\n'))
        }
        mdBuffer.setLength(0)
    }

    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val nextLine = lines.getOrNull(i + 1) ?: ""
        if (isPipeRow(line) && isSeparatorRow(nextLine)) {
            flush()
            val headers = parseCells(line)
            val rows = mutableListOf<List<String>>()
            i += 2
            while (i < lines.size && isPipeRow(lines[i])) {
                rows += parseCells(lines[i])
                i++
            }
            out += MarkdownBlock.Table(headers, rows)
        } else {
            mdBuffer.append(line).append('\n')
            i++
        }
    }
    flush()
    return out
}

private fun isPipeRow(line: String): Boolean {
    val t = line.trim()
    if (!t.contains('|')) return false
    if (isSeparatorRow(line)) return false
    return t.count { it == '|' } >= 2
}

private fun isSeparatorRow(line: String): Boolean {
    val t = line.trim().trim('|').trim()
    if (t.isEmpty()) return false
    return t.split('|').all { cell ->
        val c = cell.trim()
        c.isNotEmpty() && c.contains('-') && c.all { it == '-' || it == ':' || it == ' ' }
    }
}

private fun parseCells(line: String): List<String> {
    return line.trim().trim('|').split('|').map { it.trim() }
}

/**
 * Tiny inline-markdown renderer for table cells. Recognizes
 *  - `**bold**` / `__bold__` → bold
 *  - `*italic*` / `_italic_` → italic (only when the marker isn't
 *    adjacent to a word-char — avoids eating `foo_bar_baz` identifiers)
 *  - `` `code` `` → monospace
 *
 * Unmatched markers render as literal characters so malformed input
 * never swallows surrounding text. Nested markers aren't supported —
 * table cells rarely need them and full GFM inline parsing would be a
 * separate dependency.
 */
private fun renderInlineMarkdown(src: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    val n = src.length
    while (i < n) {
        val c = src[i]
        when {
            c == '`' -> {
                val end = src.indexOf('`', startIndex = i + 1)
                if (end < 0) {
                    append(c)
                    i++
                } else {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                        append(src.substring(i + 1, end))
                    }
                    i = end + 1
                }
            }
            (c == '*' || c == '_') && i + 1 < n && src[i + 1] == c -> {
                // Bold: ** or __
                val marker = "$c$c"
                val end = src.indexOf(marker, startIndex = i + 2)
                if (end < 0) {
                    append(c)
                    i++
                } else {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(renderInlineMarkdown(src.substring(i + 2, end)))
                    }
                    i = end + 2
                }
            }
            c == '*' || c == '_' -> {
                // Italic: single * / _. Require the closing marker to be
                // non-adjacent to a word char on the trailing side for _
                // so we don't mangle snake_case tokens in cells.
                val end = findItalicEnd(src, i, c)
                if (end < 0) {
                    append(c)
                    i++
                } else {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(renderInlineMarkdown(src.substring(i + 1, end)))
                    }
                    i = end + 1
                }
            }
            else -> {
                append(c)
                i++
            }
        }
    }
}

private fun findItalicEnd(src: String, start: Int, marker: Char): Int {
    var j = start + 1
    while (j < src.length) {
        if (src[j] == marker) {
            // For underscore italics require word-boundary on trailing side
            // to avoid matching inside identifiers like foo_bar_baz.
            if (marker == '_') {
                val prev = src.getOrNull(j - 1)
                val next = src.getOrNull(j + 1)
                val prevWord = prev?.isLetterOrDigit() == true
                val nextWord = next?.isLetterOrDigit() == true
                if (prevWord && nextWord) {
                    j++
                    continue
                }
            }
            return j
        }
        j++
    }
    return -1
}
