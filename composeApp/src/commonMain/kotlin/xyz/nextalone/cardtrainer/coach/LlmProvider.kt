package xyz.nextalone.cardtrainer.coach

enum class ProviderKind(val label: String, val defaultBaseUrl: String, val defaultModel: String) {
    ANTHROPIC(
        label = "Anthropic (Claude)",
        defaultBaseUrl = "https://api.anthropic.com",
        defaultModel = "claude-sonnet-4-6",
    ),
    OPENAI_COMPAT(
        label = "OpenAI / 兼容",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
    );
}

data class ProviderConfig(
    val kind: ProviderKind,
    val apiKey: String,
    val baseUrl: String,
    val model: String,
)

interface LlmProvider {
    /**
     * Send a multi-turn conversation to the model and return the next
     * assistant reply as plain text. Callers should append the returned
     * string to their own turn list as an ASSISTANT turn, then pass the
     * extended list back on the next follow-up call.
     *
     * Default max_tokens is generous (1500) because some reasoning models
     * spend a large chunk of the budget inside `<think>` blocks before
     * emitting the actual final answer. Too small a budget and the final
     * answer gets truncated — the UI ends up showing a blank card.
     */
    suspend fun coach(
        systemPrompt: String,
        messages: List<ChatTurn>,
        maxTokens: Int = 1500,
    ): String

    /** Convenience: one-shot single-user-turn call. */
    suspend fun coach(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 1500,
    ): String = coach(
        systemPrompt = systemPrompt,
        messages = listOf(ChatTurn(ChatTurn.Role.USER, userPrompt)),
        maxTokens = maxTokens,
    )

    fun close()
}

object LlmProviders {
    fun create(cfg: ProviderConfig): LlmProvider = when (cfg.kind) {
        ProviderKind.ANTHROPIC -> AnthropicProvider(cfg.apiKey, cfg.baseUrl, cfg.model)
        ProviderKind.OPENAI_COMPAT -> OpenAiProvider(cfg.apiKey, cfg.baseUrl, cfg.model)
    }
}
