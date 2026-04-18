package xyz.nextalone.cardtrainer.coach

import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.statement.bodyAsText

sealed interface TestResult {
    data class Ok(val sample: String) : TestResult
    data class Fail(val reason: String, val detail: String?) : TestResult
}

/**
 * Fire a tiny coaching request through the configured provider and report a
 * human-readable success/failure reason. Used by the Settings "Test connection"
 * button so a broken API key / base URL / network shows a clear cause instead
 * of silently failing deep inside a trainer screen.
 */
suspend fun testConnection(cfg: ProviderConfig): TestResult {
    if (cfg.apiKey.isBlank()) {
        return TestResult.Fail("API Key 为空", "请在设置页填写 ${cfg.kind.label} 的 API Key。")
    }
    if (cfg.baseUrl.isBlank()) {
        return TestResult.Fail("Base URL 为空", "请填写完整 URL，例如 ${cfg.kind.defaultBaseUrl}。")
    }
    val provider = LlmProviders.create(cfg)
    return try {
        // 1024 tokens: enough headroom for reasoning models to finish a
        // <think> pass and emit the single-character final answer.
        val sample = provider.coach(
            systemPrompt = "你是一个 API 连通性测试。只用一个汉字回答。不要输出推理过程。",
            userPrompt = "ping",
            maxTokens = 1024,
        ).take(200)
        TestResult.Ok(
            sample.ifBlank {
                "(空响应：模型返回空。可能是 max_tokens 被推理过程耗尽，或代理未返回 content/reasoning_content)"
            },
        )
    } catch (e: HttpRequestTimeoutException) {
        TestResult.Fail("请求超时", "Base URL 是否可达？${cfg.baseUrl}")
    } catch (e: ClientRequestException) {
        TestResult.Fail(
            reason = "接口拒绝（${e.response.status.value}）",
            detail = sanitize(e.response.bodyAsText()),
        )
    } catch (e: ServerResponseException) {
        TestResult.Fail(
            reason = "服务端错误（${e.response.status.value}）",
            detail = sanitize(e.response.bodyAsText()),
        )
    } catch (e: ResponseException) {
        TestResult.Fail("HTTP 错误（${e.response.status.value}）", sanitize(e.response.bodyAsText()))
    } catch (t: Throwable) {
        TestResult.Fail(t::class.simpleName ?: "未知异常", t.message)
    } finally {
        provider.close()
    }
}

private fun sanitize(body: String): String = body.take(400)
