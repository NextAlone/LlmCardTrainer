package xyz.nextalone.cardtrainer.coach

/**
 * User-chosen reasoning shape. AUTO falls back to a name-based guess so
 * that out-of-the-box experience works for canonical model ids, but the
 * user can force CHAT (plain Chat Completions / Messages API) or
 * REASONING (OpenAI max_completion_tokens + reasoning_effort, or Claude
 * extended thinking block) if the guess is wrong for a proxy / rename.
 */
enum class ReasoningMode(val label: String) {
    AUTO("自动检测"),
    CHAT("标准对话"),
    REASONING("推理模型"),
}

enum class ProviderKind(
    val label: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val defaultMaxTokens: Int,
) {
    ANTHROPIC(
        label = "Anthropic (Claude)",
        defaultBaseUrl = "https://api.anthropic.com",
        defaultModel = "claude-sonnet-4-6",
        // Claude 4 extended thinking eats a lot of budget; 16384 covers both
        // the thinking block and a long structured poker recap.
        defaultMaxTokens = 16_384,
    ),
    OPENAI_COMPAT(
        label = "OpenAI / 兼容",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        // 8k is enough for non-reasoning models and a generous cushion for
        // the reasoning-via-compat-proxy variants (DeepSeek-R1, Qwen-QwQ).
        defaultMaxTokens = 8_192,
    );
}

data class ProviderConfig(
    val kind: ProviderKind,
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val maxTokens: Int = kind.defaultMaxTokens,
    val reasoningMode: ReasoningMode = ReasoningMode.AUTO,
)

/**
 * A single coach response, including any reasoning / thinking trace the
 * provider chose to surface. UI folds [reasoning] into a collapsed
 * disclosure so users can audit chain-of-thought without it crowding
 * the main answer. `null` means the provider didn't expose any.
 */
data class CoachReply(val content: String, val reasoning: String? = null)

interface LlmProvider {
    /**
     * Budget used by [coach] when callers don't override [maxTokens]. Set by
     * the ProviderConfig at construction time — ConnectionTest can still
     * pass an explicit, smaller budget for the one-shot sanity check.
     */
    val defaultMaxTokens: Int

    /**
     * Send a multi-turn conversation to the model and return the next
     * assistant reply as plain text. Callers should append the returned
     * string to their own turn list as an ASSISTANT turn, then pass the
     * extended list back on the next follow-up call.
     *
     * Reasoning models (DeepSeek-R1 / Qwen-QwQ / Claude extended thinking)
     * routinely spend 1-2k+ tokens on an internal thinking block before
     * emitting the final answer. [defaultMaxTokens] is seeded from user
     * settings so that budget can be raised without code changes.
     */
    suspend fun coach(
        systemPrompt: String,
        messages: List<ChatTurn>,
        maxTokens: Int = defaultMaxTokens,
    ): String = coachVerbose(systemPrompt, messages, maxTokens).content

    /**
     * Verbose variant that also returns the provider's internal reasoning /
     * thinking trace when one was produced. Default impl delegates to
     * [coach] and leaves reasoning null — Provider impls that can surface
     * a thinking block should override this directly and let [coach] fall
     * through to the default bridge.
     */
    suspend fun coachVerbose(
        systemPrompt: String,
        messages: List<ChatTurn>,
        maxTokens: Int = defaultMaxTokens,
    ): CoachReply

    /** Convenience: one-shot single-user-turn call. */
    suspend fun coach(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = defaultMaxTokens,
    ): String = coach(
        systemPrompt = systemPrompt,
        messages = listOf(ChatTurn(ChatTurn.Role.USER, userPrompt)),
        maxTokens = maxTokens,
    )

    fun close()
}

object LlmProviders {
    fun create(cfg: ProviderConfig): LlmProvider = when (cfg.kind) {
        ProviderKind.ANTHROPIC -> AnthropicProvider(
            apiKey = cfg.apiKey,
            baseUrl = cfg.baseUrl,
            model = cfg.model,
            defaultMaxTokens = cfg.maxTokens,
            reasoningMode = cfg.reasoningMode,
        )
        ProviderKind.OPENAI_COMPAT -> OpenAiProvider(
            apiKey = cfg.apiKey,
            baseUrl = cfg.baseUrl,
            model = cfg.model,
            defaultMaxTokens = cfg.maxTokens,
            reasoningMode = cfg.reasoningMode,
        )
    }
}
