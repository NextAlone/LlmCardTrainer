package xyz.nextalone.cardtrainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.nextalone.cardtrainer.coach.ChatTurn
import xyz.nextalone.cardtrainer.ui.components.BrandChip
import xyz.nextalone.cardtrainer.ui.components.BrandDivider
import xyz.nextalone.cardtrainer.ui.components.ChipTone
import xyz.nextalone.cardtrainer.ui.components.Eyebrow
import xyz.nextalone.cardtrainer.ui.theme.BrandDisplayFamily
import xyz.nextalone.cardtrainer.ui.theme.BrandTheme

/**
 * AI coach card — matches Redesign.html `AiCoachCard`. Header strip with
 * brass sigil + eyebrow + subtitle, brand chip for state; body renders the
 * multi-turn chat and a 追问 input box. [accentTone] picks the header tint
 * (Accent = 独立分析; Good = 对你决策的评价).
 */
@Composable
fun AiConversation(
    title: String,
    turns: List<ChatTurn>,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onFollowUp: (String) -> Unit,
    accentTone: ChipTone = ChipTone.Accent,
    hiddenLeadingTurns: Int = 1,
    emptyPlaceholder: String = "（暂无，请检查 API Key / 网络）",
) {
    val c = BrandTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.border, shape),
    ) {
        CoachHeader(title = title, accentTone = accentTone, loading = loading, error = error != null)
        BrandDivider()
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            turns.forEachIndexed { index, turn ->
                if (index < hiddenLeadingTurns) return@forEachIndexed
                when (turn.role) {
                    ChatTurn.Role.USER -> UserBubble(turn.content)
                    ChatTurn.Role.ASSISTANT -> AiMarkdown(turn.content)
                }
            }

            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.accent)
                    Spacer(Modifier.width(8.dp))
                    Text("思考中…（自动重试）", color = c.fgMuted, style = MaterialTheme.typography.bodyMedium)
                }
                error != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "请求失败：$error",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.bad,
                    )
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(c.bad.copy(alpha = 0.12f))
                            .border(1.dp, c.bad.copy(alpha = 0.4f), RoundedCornerShape(999.dp)),
                    ) {
                        IconButton(onClick = onRetry) {
                            Icon(Icons.Default.Refresh, contentDescription = "重试", tint = c.bad)
                        }
                        Text(
                            "重试",
                            Modifier.align(Alignment.CenterVertically).padding(end = 12.dp),
                            color = c.bad,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                turns.size <= hiddenLeadingTurns -> Text(
                    emptyPlaceholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.fgMuted,
                )
            }

            val hasAssistantReply = turns.any { it.role == ChatTurn.Role.ASSISTANT }
            if (hasAssistantReply) {
                FollowUpInput(enabled = !loading, onSend = onFollowUp)
            }
        }
    }
}

@Composable
private fun CoachHeader(title: String, accentTone: ChipTone, loading: Boolean, error: Boolean) {
    val c = BrandTheme.colors
    val headerTint = when (accentTone) {
        ChipTone.Good -> c.good.copy(alpha = 0.12f)
        else -> c.accent.copy(alpha = 0.12f)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(headerTint, Color.Transparent)))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(c.accentBright, c.accent))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "C",
                style = TextStyle(
                    fontFamily = BrandDisplayFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = c.fg,
                ),
            )
        }
        Column(Modifier.weight(1f)) {
            Eyebrow("AI 教练")
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = c.fg,
                fontWeight = FontWeight.SemiBold,
            )
        }
        when {
            error -> BrandChip("离线", tone = ChipTone.Bad)
            loading -> BrandChip("流 · 分析中", tone = ChipTone.Outline)
            else -> BrandChip("在线", tone = accentTone)
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    val c = BrandTheme.colors
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .clip(shape)
            .background(c.accent.copy(alpha = 0.14f))
            .border(1.dp, c.accent.copy(alpha = 0.3f), shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            "Q: $text",
            style = MaterialTheme.typography.bodyMedium,
            color = c.fg,
        )
    }
}

@Composable
private fun FollowUpInput(enabled: Boolean, onSend: (String) -> Unit) {
    val c = BrandTheme.colors
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
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送追问", tint = c.accent)
        }
    }
}
