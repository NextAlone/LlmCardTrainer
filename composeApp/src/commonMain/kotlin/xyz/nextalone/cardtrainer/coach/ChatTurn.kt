package xyz.nextalone.cardtrainer.coach

import kotlinx.serialization.Serializable

/**
 * A single message in an ongoing coaching conversation. The provider receives
 * the whole list and produces the next assistant turn; we append it and
 * optionally let the user ask further follow-ups by appending another USER
 * turn and calling again.
 *
 * [reasoning] carries the model's internal thinking text when the provider
 * surfaces one (Claude extended thinking, DeepSeek-R1 reasoning_content,
 * OpenAI o-series reasoning summary). UI renders it as a collapsed "思考
 * 过程" disclosure so users can inspect the chain of thought without the
 * main answer getting buried.
 */
@Serializable
data class ChatTurn(
    val role: Role,
    val content: String,
    val reasoning: String? = null,
) {
    @Serializable
    enum class Role { USER, ASSISTANT }
}
