package xyz.nextalone.cardtrainer.coach

/**
 * Breakdown of a single coach call, for diagnosis. `cleaned` is what the UI
 * renders; `raw` is what the endpoint actually returned before we stripped
 * `<think>` blocks or picked fallback fields. `reasoningOnly` is set when the
 * provider only found content inside a reasoning/thinking wrapper (i.e. the
 * model didn't emit a final answer) — useful to warn users that maxTokens is
 * too small for this model.
 */
data class CoachDebug(
    val cleaned: String,
    val raw: String,
    val reasoningOnly: Boolean = false,
)
