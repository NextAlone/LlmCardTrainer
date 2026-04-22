package xyz.nextalone.cardtrainer.coach

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Anthropic Messages API client (POST {baseUrl}/v1/messages).
 *
 * Auth: `x-api-key` header.
 * System prompt is marked `cache_control: ephemeral` so repeated coaching calls
 * reuse the cached prefix at ~10% of input cost.
 */
class AnthropicProvider(
    private val apiKey: String,
    baseUrl: String,
    private val model: String,
    override val defaultMaxTokens: Int = 16_384,
    private val reasoningMode: ReasoningMode = ReasoningMode.AUTO,
) : LlmProvider {

    private val endpoint = baseUrl.trimEnd('/') + "/v1/messages"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private val client = HttpClient {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 60_000
            socketTimeoutMillis = 60_000
        }
        expectSuccess = true
    }

    override suspend fun coachVerbose(
        systemPrompt: String,
        messages: List<ChatTurn>,
        maxTokens: Int,
    ): CoachReply {
        val thinkingEnabled = useExtendedThinking()
        val thinking = if (thinkingEnabled) {
            // budget_tokens must be < max_tokens and ≥ 1024. Reserve 1/3 of
            // the budget for thinking up to 10k, leaving room for the
            // actual answer block even on a tight overall budget.
            val budget = (maxTokens / 3).coerceIn(1024, 10_000)
                .coerceAtMost(maxTokens - 256)
            if (budget >= 1024) Thinking(budgetTokens = budget) else null
        } else null
        val request = MessageRequest(
            model = model,
            maxTokens = maxTokens,
            system = listOf(
                SystemBlock(text = systemPrompt, cacheControl = CacheControl("ephemeral")),
            ),
            messages = messages.map {
                Message(
                    role = when (it.role) {
                        ChatTurn.Role.USER -> "user"
                        ChatTurn.Role.ASSISTANT -> "assistant"
                    },
                    content = it.content,
                )
            },
            thinking = thinking,
            // Extended thinking requires temperature == 1. Leave it null on
            // the non-thinking path so Anthropic uses its service default.
            temperature = if (thinking != null) 1.0 else null,
        )
        val resp: MessageResponse = client.post(endpoint) {
            header("x-api-key", apiKey)
            header("anthropic-version", API_VERSION)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
        val answer = resp.content
            .filter { it.type == "text" }
            .joinToString("\n") { it.text.orEmpty() }
        val reasoning = resp.content
            .filter { it.type == "thinking" }
            .mapNotNull { it.thinking?.takeUnless { s -> s.isBlank() } }
            .joinToString("\n\n")
            .ifBlank { null }
        return CoachReply(
            content = ResponseCleanup.cleanOrRaw(answer),
            reasoning = reasoning,
        )
    }

    private fun useExtendedThinking(): Boolean = when (reasoningMode) {
        ReasoningMode.CHAT -> false
        ReasoningMode.REASONING -> true
        ReasoningMode.AUTO -> {
            // Claude extended thinking is supported on 3.7 Sonnet, Claude 4
            // Opus / Sonnet / Haiku families. Safe default when the model id
            // matches one of these prefixes.
            val lower = model.lowercase()
            lower.startsWith("claude-opus-4") ||
                lower.startsWith("claude-sonnet-4") ||
                lower.startsWith("claude-haiku-4") ||
                lower.startsWith("claude-3-7")
        }
    }

    override fun streamCoach(
        systemPrompt: String,
        messages: List<ChatTurn>,
        maxTokens: Int,
    ): Flow<CoachDelta> = flow {
        val thinkingEnabled = useExtendedThinking()
        val thinking = if (thinkingEnabled) {
            val budget = (maxTokens / 3).coerceIn(1024, 10_000)
                .coerceAtMost(maxTokens - 256)
            if (budget >= 1024) Thinking(budgetTokens = budget) else null
        } else null
        val request = MessageRequest(
            model = model,
            maxTokens = maxTokens,
            system = listOf(
                SystemBlock(text = systemPrompt, cacheControl = CacheControl("ephemeral")),
            ),
            messages = messages.map {
                Message(
                    role = when (it.role) {
                        ChatTurn.Role.USER -> "user"
                        ChatTurn.Role.ASSISTANT -> "assistant"
                    },
                    content = it.content,
                )
            },
            thinking = thinking,
            temperature = if (thinking != null) 1.0 else null,
            stream = true,
        )
        client.preparePost(endpoint) {
            header("x-api-key", apiKey)
            header("anthropic-version", API_VERSION)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.execute { response ->
            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                // content_block_delta with delta.type == text_delta / thinking_delta
                val obj = runCatching { json.parseToJsonElement(payload).jsonObject }
                    .getOrNull() ?: continue
                val type = obj["type"]?.jsonPrimitive?.content
                if (type != "content_block_delta") continue
                val delta = obj["delta"]?.jsonObject ?: continue
                when (delta["type"]?.jsonPrimitive?.content) {
                    "text_delta" -> delta["text"]?.jsonPrimitive?.content
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { emit(CoachDelta.Content(it)) }
                    "thinking_delta" -> delta["thinking"]?.jsonPrimitive?.content
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { emit(CoachDelta.Reasoning(it)) }
                }
            }
        }
        emit(CoachDelta.Done)
    }

    override fun close() = client.close()

    companion object { private const val API_VERSION = "2023-06-01" }
}

@Serializable
private data class MessageRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: List<SystemBlock>,
    val messages: List<Message>,
    val thinking: Thinking? = null,
    val temperature: Double? = null,
    val stream: Boolean = false,
)

@Serializable
private data class Thinking(
    val type: String = "enabled",
    @SerialName("budget_tokens") val budgetTokens: Int,
)

@Serializable
private data class SystemBlock(
    val type: String = "text",
    val text: String,
    @SerialName("cache_control") val cacheControl: CacheControl? = null,
)

@Serializable
private data class CacheControl(val type: String)

@Serializable
private data class Message(val role: String, val content: String)

@Serializable
private data class MessageResponse(
    val id: String? = null,
    val role: String? = null,
    val model: String? = null,
    val content: List<ContentBlock> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null,
    val usage: Usage? = null,
)

@Serializable
private data class ContentBlock(
    val type: String,
    val text: String? = null,
    val thinking: String? = null,
)

@Serializable
private data class Usage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    @SerialName("cache_creation_input_tokens") val cacheCreation: Int = 0,
    @SerialName("cache_read_input_tokens") val cacheRead: Int = 0,
)
