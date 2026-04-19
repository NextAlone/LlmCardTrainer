package xyz.nextalone.cardtrainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import xyz.nextalone.cardtrainer.ui.theme.BrandMonoFamily
import xyz.nextalone.cardtrainer.ui.theme.BrandTheme

/** `【评分：3.5 / 5】` → `3.5`. Tolerant of half-width/full-width colon. */
private val scorePattern = Regex("""【评分[:：]\s*([0-9]+(?:\.[0-9]+)?)\s*/\s*5】""")

fun parsePokerScore(text: String?): Double? {
    if (text.isNullOrBlank()) return null
    return scorePattern.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        ?.coerceIn(0.0, 5.0)
}

/** Strip the score line so the body Markdown doesn't show it twice. */
private fun stripScoreLine(text: String): String =
    text.replaceFirst(scorePattern, "").trimStart('\n', ' ').trimEnd()

private enum class CoachTab(val label: String) {
    INDEPENDENT("独立分析"),
    EVALUATION("评价你的决策"),
}

/**
 * Merged AI coach card with 独立分析 / 评价 tabs.
 *
 *  - "独立分析" shows the assistant's no-context analysis + follow-up input.
 *  - "评价" shows the assistant's evaluation of the user's chosen action,
 *    with a 0-5 score bar at top parsed from `【评分：X.X / 5】` marker.
 *
 * The evaluation tab is disabled until the user has submitted — no eval
 * turns exist prior. Follow-up messages append to the active tab's thread.
 */
@Composable
fun PokerCoachMerged(
    // Independent analysis
    indepTurns: List<ChatTurn>,
    indepError: String?,
    indepLoading: Boolean,
    onIndepRetry: () -> Unit,
    onIndepFollowUp: (String) -> Unit,
    // Evaluation (only populated after user submits their decision)
    evalTurns: List<ChatTurn>,
    evalError: String?,
    evalLoading: Boolean,
    onEvalRetry: () -> Unit,
    onEvalFollowUp: (String) -> Unit,
    evalEnabled: Boolean,
) {
    val c = BrandTheme.colors
    val shape = RoundedCornerShape(14.dp)
    // Default to 独立分析 — when the response first streams in the user still
    // has the option to self-assess before peeking at the evaluation.
    var tab by remember { mutableStateOf(CoachTab.INDEPENDENT) }
    val evalLatest = evalTurns.lastOrNull { it.role == ChatTurn.Role.ASSISTANT }?.content
    val score = remember(evalLatest) { parsePokerScore(evalLatest) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.border, shape),
    ) {
        CoachHeader(
            accentTone = if (tab == CoachTab.EVALUATION) ChipTone.Good else ChipTone.Accent,
            loading = if (tab == CoachTab.EVALUATION) evalLoading else indepLoading,
            error = if (tab == CoachTab.EVALUATION) evalError != null else indepError != null,
        )
        BrandDivider()
        CoachTabs(
            active = tab,
            onSelect = { tab = it },
            evalEnabled = evalEnabled,
        )
        if (tab == CoachTab.EVALUATION && score != null) {
            ScoreBar(score = score)
            BrandDivider()
        }
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val activeTurns = if (tab == CoachTab.EVALUATION) evalTurns else indepTurns
            val activeError = if (tab == CoachTab.EVALUATION) evalError else indepError
            val activeLoading = if (tab == CoachTab.EVALUATION) evalLoading else indepLoading
            val onRetry = if (tab == CoachTab.EVALUATION) onEvalRetry else onIndepRetry
            val onFollowUp = if (tab == CoachTab.EVALUATION) onEvalFollowUp else onIndepFollowUp

            activeTurns.forEachIndexed { index, turn ->
                if (index < 1) return@forEachIndexed // hide the structured initial prompt
                when (turn.role) {
                    ChatTurn.Role.USER -> UserBubble(turn.content)
                    ChatTurn.Role.ASSISTANT -> {
                        val body = if (tab == CoachTab.EVALUATION) stripScoreLine(turn.content) else turn.content
                        AiMarkdown(body)
                    }
                }
            }

            when {
                tab == CoachTab.EVALUATION && !evalEnabled -> Text(
                    "提交决策后，AI 会对比基线给出评分与具体偏差。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.fgMuted,
                )
                activeLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.accent)
                    Spacer(Modifier.width(8.dp))
                    Text("思考中…（自动重试）", color = c.fgMuted, style = MaterialTheme.typography.bodyMedium)
                }
                activeError != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("请求失败：$activeError", style = MaterialTheme.typography.bodySmall, color = c.bad)
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
                activeTurns.size <= 1 -> Text(
                    "（暂无，请检查 API Key / 网络）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.fgMuted,
                )
            }

            val hasAssistantReply = activeTurns.any { it.role == ChatTurn.Role.ASSISTANT }
            if (hasAssistantReply) {
                FollowUpInput(enabled = !activeLoading, onSend = onFollowUp)
            }
        }
    }
}

@Composable
private fun CoachHeader(accentTone: ChipTone, loading: Boolean, error: Boolean) {
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
                "独立分析 + 决策评价",
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
private fun CoachTabs(
    active: CoachTab,
    onSelect: (CoachTab) -> Unit,
    evalEnabled: Boolean,
) {
    val c = BrandTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoachTab.entries.forEach { t ->
            val isActive = t == active
            val enabled = t != CoachTab.EVALUATION || evalEnabled
            val shape = RoundedCornerShape(999.dp)
            Box(
                Modifier
                    .clip(shape)
                    .background(
                        if (isActive) c.fg.copy(alpha = 0.9f) else Color.Transparent,
                    )
                    .border(
                        1.dp,
                        if (isActive) Color.Transparent else c.border,
                        shape,
                    )
                    .let { if (enabled) it.clickable { onSelect(t) } else it }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    t.label,
                    style = TextStyle(
                        fontFamily = BrandDisplayFamily,
                        fontSize = 13.sp,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                        color = when {
                            isActive -> c.bg
                            !enabled -> c.fgSubtle
                            else -> c.fgMuted
                        },
                    ),
                )
            }
        }
    }
}

/** 0-5 horizontal score bar with brass fill. Displays the numeric score left,
 *  fills the bar right. */
@Composable
private fun ScoreBar(score: Double) {
    val c = BrandTheme.colors
    val pct = (score / 5.0).coerceIn(0.0, 1.0).toFloat()
    val scoreColor = when {
        score >= 4.0 -> c.good
        score >= 3.0 -> c.accent
        score >= 2.0 -> c.warn
        else -> c.bad
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Eyebrow("整体评分")
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "${kotlin.math.round(score * 10) / 10}",
                    style = TextStyle(
                        fontFamily = BrandMonoFamily,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = scoreColor,
                    ),
                )
                Text(
                    "/5",
                    style = TextStyle(
                        fontFamily = BrandMonoFamily,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = c.fgSubtle,
                    ),
                    modifier = Modifier.padding(start = 2.dp, bottom = 4.dp),
                )
            }
        }
        Box(
            Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(c.fg.copy(alpha = 0.08f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(pct)
                    .height(10.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        Brush.horizontalGradient(listOf(scoreColor.copy(alpha = 0.85f), scoreColor)),
                    ),
            )
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
        Text("Q: $text", style = MaterialTheme.typography.bodyMedium, color = c.fg)
    }
}

@Composable
private fun FollowUpInput(enabled: Boolean, onSend: (String) -> Unit) {
    val c = BrandTheme.colors
    var text by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth(),
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
                if (q.isNotEmpty()) { onSend(q); text = "" }
            },
            enabled = enabled && text.isNotBlank(),
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送追问", tint = c.accent)
        }
    }
}
