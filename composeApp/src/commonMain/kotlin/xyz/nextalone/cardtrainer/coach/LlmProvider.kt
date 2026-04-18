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
     * Default max_tokens is generous (4096) because reasoning models
     * (DeepSeek-R1 / mimo-v2-pro / Qwen-QwQ …) routinely spend 1-2k
     * tokens inside `<think>` blocks before emitting the final answer.
     * Too small a budget gets consumed in reasoning and truncates the
     * visible answer — the UI ends up showing a blank or cut-off card.
     */
    suspend fun coach(
        systemPrompt: String,
        messages: List<ChatTurn>,
        maxTokens: Int = 4096,
    ): String

    /** Convenience: one-shot single-user-turn call. */
    suspend fun coach(
        systemPrompt: String,
        userPrompt: String,
        maxTokens: Int = 4096,
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
