package xyz.nextalone.cardtrainer.coach

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
)

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
    ): String

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
        ProviderKind.ANTHROPIC -> AnthropicProvider(cfg.apiKey, cfg.baseUrl, cfg.model, cfg.maxTokens)
        ProviderKind.OPENAI_COMPAT -> OpenAiProvider(cfg.apiKey, cfg.baseUrl, cfg.model, cfg.maxTokens)
    }
}
