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
 * OpenAI Chat Completions-compatible client.
 *
 * Auth: `Authorization: Bearer <apiKey>`.
 * Base URL default: `https://api.openai.com/v1`; also works with any OpenAI-compatible
 * endpoint (DeepSeek, Moonshot, Together, OpenRouter, local vLLM/Ollama bridge, ...).
 * Endpoint called: POST {baseUrl}/chat/completions
 *
 * Response extraction prefers `choices[0].message.content`, but transparently
 * falls back to `reasoning_content` / `thinking` — some reasoning endpoints
 * (DeepSeek-R1-style proxies) put the usable text there when they don't emit
 * a separate final-answer field.
 */
class OpenAiProvider(
    private val apiKey: String,
    baseUrl: String,
    private val model: String,
    override val defaultMaxTokens: Int = 8_192,
    private val reasoningMode: ReasoningMode = ReasoningMode.AUTO,
) : LlmProvider {

    private val endpoint = baseUrl.trimEnd('/') + "/chat/completions"

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
        val shape = modelShape(model)
        val requestMessages = buildList {
            add(RequestMessage(role = "system", content = systemPrompt))
            messages.forEach {
                add(
                    RequestMessage(
                        role = when (it.role) {
                            ChatTurn.Role.USER -> "user"
                            ChatTurn.Role.ASSISTANT -> "assistant"
                        },
                        content = it.content,
                    ),
                )
            }
        }
        val body: ChatRequest = when (shape) {
            ModelShape.OPENAI_REASONING -> ChatRequest(
                model = model,
                messages = requestMessages,
                // o-series uses max_completion_tokens; temperature is locked
                // to 1 server-side, so we omit the field entirely.
                maxCompletionTokens = maxTokens,
                reasoningEffort = "medium",
                temperature = null,
            )
            ModelShape.CHAT -> ChatRequest(
                model = model,
                messages = requestMessages,
                maxTokens = maxTokens,
                temperature = 0.4,
            )
        }
        val resp: ChatResponse = client.post(endpoint) {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
        val msg = resp.choices.firstOrNull()?.message
        val content = msg?.content?.takeUnless { it.isBlank() }
        val reasoning = msg?.reasoningContent?.takeUnless { it.isBlank() }
            ?: msg?.thinking?.takeUnless { it.isBlank() }
        val finalContent = content
            ?: reasoning
            // If the endpoint returned only reasoning and no separate final
            // text, fall back to it so the card isn't blank — that's the
            // shape DeepSeek-R1 proxies use when they don't emit a split.
            ?: ""
        return CoachReply(
            content = ResponseCleanup.cleanOrRaw(finalContent),
            // Only report reasoning as a separate trace when the final
            // content is a distinct, non-empty answer — otherwise we'd
            // duplicate the same text into both fields.
            reasoning = if (content != null) reasoning else null,
        )
    }

    private enum class ModelShape { OPENAI_REASONING, CHAT }

    private fun modelShape(id: String): ModelShape = when (reasoningMode) {
        ReasoningMode.CHAT -> ModelShape.CHAT
        ReasoningMode.REASONING -> ModelShape.OPENAI_REASONING
        ReasoningMode.AUTO -> {
            val lower = id.lowercase()
            val openAiReasoningPrefixes = listOf("o1", "o3", "o4", "gpt-5")
            if (openAiReasoningPrefixes.any { lower.startsWith(it) }) {
                ModelShape.OPENAI_REASONING
            } else {
                ModelShape.CHAT
            }
        }
    }

    override fun streamCoach(
        systemPrompt: String,
        messages: List<ChatTurn>,
        maxTokens: Int,
    ): Flow<CoachDelta> = flow {
        val shape = modelShape(model)
        val requestMessages = buildList {
            add(RequestMessage(role = "system", content = systemPrompt))
            messages.forEach {
                add(
                    RequestMessage(
                        role = when (it.role) {
                            ChatTurn.Role.USER -> "user"
                            ChatTurn.Role.ASSISTANT -> "assistant"
                        },
                        content = it.content,
                    ),
                )
            }
        }
        val body: ChatRequest = when (shape) {
            ModelShape.OPENAI_REASONING -> ChatRequest(
                model = model,
                messages = requestMessages,
                maxCompletionTokens = maxTokens,
                reasoningEffort = "medium",
                temperature = null,
                stream = true,
            )
            ModelShape.CHAT -> ChatRequest(
                model = model,
                messages = requestMessages,
                maxTokens = maxTokens,
                temperature = 0.4,
                stream = true,
            )
        }
        client.preparePost(endpoint) {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(body)
        }.execute { response ->
            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty()) continue
                if (payload == "[DONE]") break
                val delta = runCatching { json.parseToJsonElement(payload).jsonObject }
                    .getOrNull()
                    ?.get("choices")
                    ?.let { it as? kotlinx.serialization.json.JsonArray }
                    ?.firstOrNull()
                    ?.jsonObject
                    ?.get("delta")
                    ?.jsonObject
                    ?: continue
                delta["content"]?.jsonPrimitive?.content
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { emit(CoachDelta.Content(it)) }
                // DeepSeek-R1 / Qwen-QwQ OpenAI-compat proxies stream a
                // 'reasoning_content' delta alongside or before 'content'.
                delta["reasoning_content"]?.jsonPrimitive?.content
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { emit(CoachDelta.Reasoning(it)) }
                delta["thinking"]?.jsonPrimitive?.content
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { emit(CoachDelta.Reasoning(it)) }
            }
        }
        emit(CoachDelta.Done)
    }

    override fun close() = client.close()
}

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<RequestMessage>,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    val temperature: Double? = null,
    val stream: Boolean = false,
)

@Serializable
private data class RequestMessage(val role: String, val content: String)

@Serializable
private data class ChatResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<ChatChoice> = emptyList(),
)

@Serializable
private data class ChatChoice(
    val index: Int = 0,
    val message: ResponseMessage,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
private data class ResponseMessage(
    val role: String? = null,
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    val thinking: String? = null,
)
