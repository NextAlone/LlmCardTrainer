package xyz.nextalone.cardtrainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.nextalone.cardtrainer.coach.ChatTurn

/**
 * Reusable AI conversation card: renders the multi-turn chat, plus a
 * 追问 input box that lets the user keep asking with full prior context.
 *
 * [hiddenLeadingTurns] skips rendering the first N entries of [turns] so the
 * big structured initial prompt (built from table state) doesn't clutter the
 * screen. Follow-up user messages after that are shown as question bubbles.
 */
@Composable
fun AiConversation(
    title: String,
    turns: List<ChatTurn>,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onFollowUp: (String) -> Unit,
    containerColor: Color,
    onContainerColor: Color = MaterialTheme.colorScheme.onSurface,
    hiddenLeadingTurns: Int = 1,
    emptyPlaceholder: String = "（暂无，请检查 API Key / 网络）",
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, fontWeight = FontWeight.SemiBold, color = onContainerColor)

            turns.forEachIndexed { index, turn ->
                if (index < hiddenLeadingTurns) return@forEachIndexed
                when (turn.role) {
                    ChatTurn.Role.USER -> UserBubble(turn.content)
                    ChatTurn.Role.ASSISTANT -> AiMarkdown(turn.content)
                }
            }

            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("思考中…（自动重试）", color = onContainerColor)
                }
                error != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "请求失败：$error",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    FilledTonalButton(onClick = onRetry) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("重试")
                    }
                }
                turns.size <= hiddenLeadingTurns -> Text(
                    emptyPlaceholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Follow-up is offered only once we have at least one assistant
            // reply (i.e. we've gotten past the empty placeholder state).
            val hasAssistantReply = turns.any { it.role == ChatTurn.Role.ASSISTANT }
            if (hasAssistantReply) {
                FollowUpInput(enabled = !loading, onSend = onFollowUp)
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(
            "Q: $text",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun FollowUpInput(enabled: Boolean, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("追问…") },
            singleLine = false,
            maxLines = 3,
            modifier = Modifier.weight(1f),
            enabled = enabled,
        )
        IconButton(
            onClick = {
                val q = text.trim()
                if (q.isNotEmpty()) {
                    onSend(q)
                    text = ""
                }
            },
            enabled = enabled && text.isNotBlank(),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送追问")
        }
    }
}
