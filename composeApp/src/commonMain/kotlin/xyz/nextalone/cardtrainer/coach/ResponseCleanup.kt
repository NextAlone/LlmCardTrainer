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
        return s.trim()
    }
}
