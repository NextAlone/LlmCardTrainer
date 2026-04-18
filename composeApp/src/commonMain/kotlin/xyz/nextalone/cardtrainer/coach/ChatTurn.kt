package xyz.nextalone.cardtrainer.coach

import kotlinx.serialization.Serializable

/**
 * A single message in an ongoing coaching conversation. The provider receives
 * the whole list and produces the next assistant turn; we append it and
 * optionally let the user ask further follow-ups by appending another USER
 * turn and calling again.
 */
@Serializable
data class ChatTurn(val role: Role, val content: String) {
    @Serializable
    enum class Role { USER, ASSISTANT }
}
