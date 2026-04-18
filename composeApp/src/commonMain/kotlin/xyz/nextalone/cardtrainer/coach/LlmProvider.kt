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
    suspend fun coach(systemPrompt: String, userPrompt: String, maxTokens: Int = 600): String
    fun close()
}

object LlmProviders {
    fun create(cfg: ProviderConfig): LlmProvider = when (cfg.kind) {
        ProviderKind.ANTHROPIC -> AnthropicProvider(cfg.apiKey, cfg.baseUrl, cfg.model)
        ProviderKind.OPENAI_COMPAT -> OpenAiProvider(cfg.apiKey, cfg.baseUrl, cfg.model)
    }
}
