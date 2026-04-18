@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package xyz.nextalone.cardtrainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.nextalone.cardtrainer.coach.LlmProviders
import xyz.nextalone.cardtrainer.coach.Prompts
import xyz.nextalone.cardtrainer.engine.holdem.Action
import xyz.nextalone.cardtrainer.engine.holdem.ActionPresets
import xyz.nextalone.cardtrainer.engine.holdem.Card as PokerCard
import xyz.nextalone.cardtrainer.engine.holdem.Equity
import xyz.nextalone.cardtrainer.engine.holdem.Draws
import xyz.nextalone.cardtrainer.engine.holdem.HandEvaluator
import xyz.nextalone.cardtrainer.engine.holdem.HoldemTable
import xyz.nextalone.cardtrainer.engine.holdem.HoldemTrainer
import xyz.nextalone.cardtrainer.engine.holdem.Outs
import xyz.nextalone.cardtrainer.engine.holdem.OutsReport
import xyz.nextalone.cardtrainer.engine.holdem.PreflopChart
import xyz.nextalone.cardtrainer.engine.holdem.Street
import xyz.nextalone.cardtrainer.storage.AppSettings
import xyz.nextalone.cardtrainer.storage.PokerSession
import xyz.nextalone.cardtrainer.storage.loadPokerSession
import xyz.nextalone.cardtrainer.storage.savePokerSession
import xyz.nextalone.cardtrainer.util.withRetry

private typealias Phase = PokerSession.Phase

// Marker used by pre-retry builds when an AI call failed — stored directly in
// PokerSession.situationAnalysis / choiceEvaluation. We migrate such legacy
// strings into the new error-state slots at session-load time.
private const val ERROR_PREFIX = "请求失败："

@Composable
fun PokerScreen(settings: AppSettings, onBack: () -> Unit) {
    val trainer = remember { HoldemTrainer() }

    // Restore prior session (if any) on first composition, else start fresh.
    val initial: PokerSession = remember {
        settings.loadPokerSession()?.also { trainer.restoreFrom(it.table) }
            ?: PokerSession(table = trainer.newHand(), phase = PokerSession.Phase.DECIDING)
    }

    var table by remember { mutableStateOf(initial.table) }
    var phase by remember { mutableStateOf(initial.phase) }

    // Algorithmic analysis — only revealed after submit.
    var equityPct by remember { mutableStateOf<Double?>(null) }
    var outs by remember { mutableStateOf<OutsReport?>(null) }
    // AI-provided situation analysis, pre-computed in background during DECIDING.
    // Pre-retry builds stored error strings directly in *Analysis/*Evaluation;
    // partition those out to the error field at load time so the retry button
    // appears for legacy sessions.
    var situationAnalysis by remember { mutableStateOf(initial.situationAnalysis?.takeUnless { it.startsWith(ERROR_PREFIX) }) }
    var situationError by remember { mutableStateOf(initial.situationAnalysis?.takeIf { it.startsWith(ERROR_PREFIX) }?.removePrefix(ERROR_PREFIX)) }
    var loadingSituation by remember { mutableStateOf(false) }
    // AI-provided choice evaluation, computed after submit.
    var choiceEvaluation by remember { mutableStateOf(initial.choiceEvaluation?.takeUnless { it.startsWith(ERROR_PREFIX) }) }
    var evaluationError by remember { mutableStateOf(initial.choiceEvaluation?.takeIf { it.startsWith(ERROR_PREFIX) }?.removePrefix(ERROR_PREFIX)) }
    var loadingEvaluation by remember { mutableStateOf(false) }

    var userChoice by remember {
        mutableStateOf(
            initial.userChoiceAction?.let { act -> act to (initial.userChoiceAmount ?: 0) },
        )
    }
    var showGlossary by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val preflopBaseline = remember(table.hero, table.heroPosition) {
        PreflopChart.rfiAction(table.heroPosition, table.hero)
    }

    // Persist the session on every meaningful state change so relaunching the
    // app resumes exactly where the user left off.
    LaunchedEffect(table, phase, userChoice, situationAnalysis, choiceEvaluation) {
        settings.savePokerSession(
            PokerSession(
                table = table,
                phase = phase,
                userChoiceAction = userChoice?.first,
                userChoiceAmount = userChoice?.second,
                situationAnalysis = situationAnalysis,
                choiceEvaluation = choiceEvaluation,
            ),
        )
    }

    // Background situation-analysis: called on street change and retry.
    suspend fun runSituationAnalysis() {
        val cfg = settings.activeConfig()
        if (cfg.apiKey.isBlank()) {
            situationError = "请先在『设置』中填写 ${cfg.kind.label} 的 API Key。"
            return
        }
        loadingSituation = true
        situationError = null
        val coach = LlmProviders.create(cfg)
        try {
            val result = withRetry {
                coach.coach(
                    systemPrompt = Prompts.HOLDEM_SYSTEM,
                    userPrompt = Prompts.holdemUser(
                        table = table,
                        equityPct = equityPct,
                        preflopBaseline = if (table.street == Street.PREFLOP) preflopBaseline else null,
                        outs = outs,
                        userChoice = null,
                    ),
                )
            }
            situationAnalysis = result
            situationError = null
        } catch (t: Throwable) {
            situationError = t.message ?: t::class.simpleName ?: "未知错误"
        } finally {
            coach.close()
            loadingSituation = false
        }
    }

    // On every street change / new hand: recompute equity+outs off-main-thread,
    // and kick off a background situation-analysis AI call (hidden until submit).
    LaunchedEffect(table.street, table.hero, table.board) {
        equityPct = withContext(Dispatchers.Default) {
            Equity.monteCarlo(
                hero = table.hero,
                board = table.board,
                opponents = table.opponents,
                trials = 1500,
            ).combinedPct
        }
        outs = if (table.board.size in 3..4) {
            withContext(Dispatchers.Default) { Outs.count(table.hero, table.board) }
        } else null

        // Skip the AI call if we already have analysis for this exact board
        // (e.g. after restoring a session). Avoids duplicate API billing.
        if (situationAnalysis != null) return@LaunchedEffect
        runSituationAnalysis()
    }

    fun startNewHand() {
        table = trainer.newHand()
        phase = Phase.DECIDING
        userChoice = null
        situationAnalysis = null
        situationError = null
        choiceEvaluation = null
        evaluationError = null
        equityPct = null
        outs = null
    }

    suspend fun runEvaluation() {
        val choice = userChoice ?: return
        val cfg = settings.activeConfig()
        if (cfg.apiKey.isBlank()) {
            evaluationError = "请先在『设置』中填写 ${cfg.kind.label} 的 API Key。"
            return
        }
        loadingEvaluation = true
        evaluationError = null
        choiceEvaluation = null
        val coach = LlmProviders.create(cfg)
        try {
            val result = withRetry {
                coach.coach(
                    systemPrompt = Prompts.HOLDEM_SYSTEM,
                    userPrompt = Prompts.holdemUser(
                        table = table,
                        equityPct = equityPct,
                        preflopBaseline = if (table.street == Street.PREFLOP) preflopBaseline else null,
                        outs = outs,
                        userChoice = choice,
                    ),
                )
            }
            choiceEvaluation = result
            evaluationError = null
        } catch (t: Throwable) {
            evaluationError = t.message ?: t::class.simpleName ?: "未知错误"
        } finally {
            coach.close()
            loadingEvaluation = false
        }
    }

    fun submitDecision() {
        userChoice ?: return
        phase = Phase.SUBMITTED
        scope.launch { runEvaluation() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("德州扑克训练") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showGlossary = true }) {
                        Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "术语")
                    }
                },
            )
        },
        bottomBar = {
            // Pinned navigation: 发下一街 + 新牌局 only appear after the user has
            // submitted their decision; before that, DecidingBlock shows its own
            // 提交 / 新牌局 buttons. At RIVER (all 5 board cards dealt) we hide
            // 发下一街 — real Hold'em has no betting round after the river
            // decision, showdown is just comparing cards.
            if (phase == Phase.SUBMITTED) {
                val canAdvance = table.street == Street.PREFLOP ||
                    table.street == Street.FLOP ||
                    table.street == Street.TURN
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (canAdvance) {
                            FilledTonalButton(
                                onClick = {
                                    table = trainer.advanceStreet(table)
                                    phase = Phase.DECIDING
                                    userChoice = null
                                    choiceEvaluation = null
                                    evaluationError = null
                                    situationAnalysis = null
                                    situationError = null
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("发下一街") }
                        }
                        OutlinedButton(
                            onClick = ::startNewHand,
                            modifier = Modifier.weight(1f),
                        ) { Text("新牌局") }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TableCard(
                table = table,
                equityPct = equityPct,
                showAnalysis = phase == Phase.SUBMITTED,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text("位置 ${table.heroPosition.label}") })
                AssistChip(onClick = {}, label = { Text("${table.opponents} 对手") })
                AssistChip(onClick = {}, label = { Text("街 ${table.street.name}") })
                if (phase == Phase.DECIDING && loadingSituation) {
                    AssistChip(
                        onClick = {},
                        label = { Text("AI 分析中…") },
                    )
                }
            }

            when (phase) {
                Phase.DECIDING -> DecidingBlock(
                    table = table,
                    userChoice = userChoice,
                    onChoose = { act, amt -> userChoice = act to amt },
                    onSubmit = ::submitDecision,
                    onNewHand = ::startNewHand,
                )
                Phase.SUBMITTED -> SubmittedBlock(
                    table = table,
                    userChoice = userChoice,
                    equityPct = equityPct,
                    outs = outs,
                    preflopBaseline = if (table.street == Street.PREFLOP) preflopBaseline.toString() else null,
                    situationAnalysis = situationAnalysis,
                    situationError = situationError,
                    loadingSituation = loadingSituation,
                    onRetrySituation = { scope.launch { runSituationAnalysis() } },
                    choiceEvaluation = choiceEvaluation,
                    evaluationError = evaluationError,
                    loadingEvaluation = loadingEvaluation,
                    onRetryEvaluation = { scope.launch { runEvaluation() } },
                )
            }
        }
    }

    if (showGlossary) {
        GlossaryDialog(kind = GlossaryKind.POKER, onDismiss = { showGlossary = false })
    }
}

@Composable
private fun DecidingBlock(
    table: HoldemTable,
    userChoice: Pair<Action, Int>?,
    onChoose: (Action, Int) -> Unit,
    onSubmit: () -> Unit,
    onNewHand: () -> Unit,
) {
    Text(
        "请先独立判断，选择你的动作。AI 与算法结果会在提交后显示。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val presets = remember(table.pot, table.toCall, table.heroStack) { ActionPresets.forTable(table) }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { p ->
            val isChosen = userChoice?.first == p.action && userChoice.second == p.amount
            FilterChip(
                selected = isChosen,
                onClick = { onChoose(p.action, p.amount) },
                label = { Text(p.label) },
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onSubmit,
            enabled = userChoice != null,
            modifier = Modifier.weight(1f),
        ) { Text("提交决策") }
        OutlinedButton(
            onClick = onNewHand,
            modifier = Modifier.weight(1f),
        ) { Text("新牌局") }
    }
}

@Composable
private fun SubmittedBlock(
    table: HoldemTable,
    userChoice: Pair<Action, Int>?,
    equityPct: Double?,
    outs: OutsReport?,
    preflopBaseline: String?,
    situationAnalysis: String?,
    situationError: String?,
    loadingSituation: Boolean,
    onRetrySituation: () -> Unit,
    choiceEvaluation: String?,
    evaluationError: String?,
    loadingEvaluation: Boolean,
    onRetryEvaluation: () -> Unit,
) {
    userChoice?.let { (a, amt) ->
        val tag = if (amt > 0) " $amt" else ""
        Text(
            "你的决策：${a.label}$tag",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("算法结果", fontWeight = FontWeight.SemiBold)
            val parts = buildList {
                equityPct?.let { add("胜率 ≈ ${kotlin.math.round(it * 10) / 10}%") }
                outs?.let { add("Outs ${it.outs} (${it.turnPct}%/${it.turnAndRiverPct}%)") }
                preflopBaseline?.let { add("RFI: $it") }
                if (table.toCall > 0) {
                    val o = kotlin.math.round(table.potOdds * 1000) / 10
                    add("赔率 $o%")
                }
                if (table.board.size >= 3) {
                    val cat = HandEvaluator.evaluate(table.hero + table.board).category
                    val prefix = if (table.street == Street.RIVER) "最终牌型" else "当前最佳"
                    add("$prefix: ${cat.displayName}")
                }
                if (table.board.size in 3..4) {
                    val drawTags = Draws.detect(table.hero, table.board)
                        .joinToString("+") { "${it.tag}(${it.outs})" }
                    if (drawTags.isNotEmpty()) add("听: $drawTags")
                }
            }
            if (parts.isNotEmpty()) {
                Text(
                    parts.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    if (situationAnalysis != null || situationError != null || loadingSituation) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    "AI 牌局分析（独立视角）",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                when {
                    loadingSituation -> Row(
                        Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("分析中…（自动重试）")
                    }
                    situationAnalysis != null -> AiMarkdown(situationAnalysis)
                    situationError != null -> ErrorWithRetry(
                        message = situationError,
                        onRetry = onRetrySituation,
                    )
                }
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "AI 对你决策的评价 + 原因推断",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            when {
                loadingEvaluation -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("AI 评价生成中…（自动重试）")
                }
                choiceEvaluation != null -> AiMarkdown(choiceEvaluation)
                evaluationError != null -> ErrorWithRetry(
                    message = evaluationError,
                    onRetry = onRetryEvaluation,
                )
                else -> Text(
                    "（暂无，请检查 API Key / 网络）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Reusable error row: message + manual retry affordance. */
@Composable
private fun ErrorWithRetry(message: String, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "请求失败：$message",
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
}

@Composable
private fun TableCard(table: HoldemTable, equityPct: Double?, showAnalysis: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0E7C3A)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("公共牌", color = Color.White, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (table.board.isEmpty()) {
                    repeat(5) { CardSlot() }
                } else {
                    table.board.forEach { CardView(it) }
                    repeat(5 - table.board.size) { CardSlot() }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("我的手牌", color = Color.White, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                table.hero.forEach { CardView(it) }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("底池 ${table.pot}", color = Color.White)
                Text("跟注 ${table.toCall}", color = Color.White)
                if (showAnalysis) {
                    equityPct?.let {
                        val r = kotlin.math.round(it * 10) / 10
                        Text("胜率 ≈ $r%", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun CardView(card: PokerCard) {
    Box(
        modifier = Modifier
            .size(width = 42.dp, height = 58.dp)
            .background(Color.White, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            card.label,
            color = if (card.isRed) Color(0xFFB00020) else Color.Black,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun CardSlot() {
    Box(
        modifier = Modifier
            .size(width = 42.dp, height = 58.dp)
            .background(Color(0x33FFFFFF), RoundedCornerShape(6.dp)),
    )
}
