package xyz.nextalone.cardtrainer.coach

/**
 * Strip internal-reasoning blocks some models (DeepSeek-R1, Qwen-QwQ, o1-ish
 * reasoning models behind OpenAI-compatible proxies, etc.) emit inline in the
 * message body. We've seen:
 *
 *   <think>…</think>    // closed
 *   <think>…            // unclosed if the model truncated before closing
 *   <thinking>…</thinking>
 *   ◁think▷…◁/think▷     // some Chinese-frontend wrappers
 *
 * The system prompt already asks the model to not emit these, but not every
 * backend obeys. Keep it as a defensive post-processor so the UI never shows
 * raw chain-of-thought to the user.
 */
object ResponseCleanup {

    private val THINK_BLOCKS = Regex(
        "(?is)(<think>|<thinking>|◁think▷).*?(</think>|</thinking>|◁/think▷)",
    )
    // If the closer is missing we drop everything from the opener to end-of-text.
    private val THINK_UNCLOSED = Regex(
        "(?is)(<think>|<thinking>|◁think▷).*",
    )

    fun clean(raw: String): String {
        var s = THINK_BLOCKS.replace(raw, "")
        s = THINK_UNCLOSED.replace(s, "")
        s = normalizeTables(s)
        return s.trim()
    }

    /**
     * GFM requires every pipe table to have a separator row (`| --- | --- |`)
     * directly under the header. Models often forget it, which makes the
     * renderer fall back to treating each cell as a separate paragraph — that
     * looks terrible on screen. Detect a pipe-row immediately followed by
     * another pipe-row (without an intervening separator) and inject a proper
     * separator between them. Also make sure tables start on a fresh line
     * (blank line before the header) so the GFM parser recognises the block.
     */
    private fun normalizeTables(input: String): String {
        val lines = input.lines().toMutableList()

        // 1) Insert missing separator rows.
        var i = 0
        while (i < lines.size - 1) {
            val cur = lines[i]
            val next = lines[i + 1]
            if (isPipeRow(cur) && isPipeRow(next) && !isSeparatorRow(next)) {
                lines.add(i + 1, buildSeparator(cur))
                i += 2 // skip the header + inserted separator
            } else {
                i++
            }
        }

        // 2) Ensure the header row is preceded by a blank line. Find every
        // header (followed directly by a separator row) and if the preceding
        // line is non-empty, insert a blank.
        i = 0
        while (i < lines.size - 1) {
            val cur = lines[i]
            val next = lines[i + 1]
            val isHeader = isPipeRow(cur) && isSeparatorRow(next)
            if (isHeader && i > 0 && lines[i - 1].isNotBlank()) {
                lines.add(i, "")
                i += 3 // blank + header + separator
            } else {
                i++
            }
        }
        return lines.joinToString("\n")
    }

    private fun isPipeRow(s: String): Boolean {
        val t = s.trim()
        // A table row starts and ends with `|` or contains `|` with multiple
        // cells. We use a pragmatic check: at least 2 pipe separators.
        if (!t.contains('|')) return false
        val pipes = t.count { it == '|' }
        return pipes >= 2 && !isSeparatorRow(s)
    }

    private fun isSeparatorRow(s: String): Boolean {
        // Matches | --- | :--- | :---: | ---: | style separator rows.
        val t = s.trim().trim('|').trim()
        if (t.isEmpty()) return false
        return t.split('|').all { cell ->
            val c = cell.trim()
            c.isNotEmpty() && c.all { it == '-' || it == ':' || it == ' ' } &&
                c.contains('-')
        }
    }

    private fun buildSeparator(headerRow: String): String {
        val stripped = headerRow.trim().trim('|').trim()
        val columns = stripped.split('|').size
        return "| " + List(columns) { "---" }.joinToString(" | ") + " |"
    }

    /**
     * Prefer the cleaned text, but fall back to the raw output when the
     * cleaner strips away everything (typical for a reasoning model that
     * blew its maxTokens budget inside a `<think>` block and never reached
     * the final answer). Users at least see SOMETHING — the raw
     * reasoning — rather than an empty card with no hint.
     */
    fun cleanOrRaw(raw: String): String {
        val cleaned = clean(raw)
        return cleaned.ifEmpty { raw.trim() }
    }
}
