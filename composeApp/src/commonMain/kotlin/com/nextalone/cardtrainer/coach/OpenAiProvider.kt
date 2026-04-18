package com.nextalone.cardtrainer.coach

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * OpenAI Chat Completions-compatible client.
 *
 * Auth: `Authorization: Bearer <apiKey>`.
 * Base URL default: `https://api.openai.com/v1`; also works with any OpenAI-compatible
 * endpoint (DeepSeek, Moonshot, Together, OpenRouter, local vLLM/Ollama bridge, ...).
 * Endpoint called: POST {baseUrl}/chat/completions
 */
class OpenAiProvider(
    private val apiKey: String,
    baseUrl: String,
    private val model: String,
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

    override suspend fun coach(systemPrompt: String, userPrompt: String, maxTokens: Int): String {
        val request = ChatRequest(
            model = model,
            maxTokens = maxTokens,
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userPrompt),
            ),
        )
        val resp: ChatResponse = client.post(endpoint) {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()
        return resp.choices.firstOrNull()?.message?.content.orEmpty()
    }

    override fun close() = client.close()
}

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("max_tokens") val maxTokens: Int,
    val temperature: Double = 0.4,
)

@Serializable
private data class ChatMessage(val role: String, val content: String)

@Serializable
private data class ChatResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<ChatChoice> = emptyList(),
)

@Serializable
private data class ChatChoice(
    val index: Int = 0,
    val message: ChatMessage,
    @SerialName("finish_reason") val finishReason: String? = null,
)
