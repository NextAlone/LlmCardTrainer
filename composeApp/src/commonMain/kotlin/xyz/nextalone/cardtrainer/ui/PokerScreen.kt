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
import xyz.nextalone.cardtrainer.coach.ChatTurn
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
import xyz.nextalone.cardtrainer.stats.PokerDecisionEvent
import xyz.nextalone.cardtrainer.stats.StatsRepository
import xyz.nextalone.cardtrainer.util.nowEpochMs
import xyz.nextalone.cardtrainer.util.withRetry

private typealias Phase = PokerSession.Phase

// Marker used by pre-retry builds when an AI call failed — stored directly in
// PokerSession.situationAnalysis / choiceEvaluation. We migrate such legacy
// strings into the new error-state slots at session-load time.
private const val ERROR_PREFIX = "请求失败："

/**
 * If the session has no turn list but does have a legacy success string,
 * synthesise an assistant-only turn so the old analysis stays visible. If
 * the legacy string was an error, we drop it (error goes to [legacyErrorOrNull]).
 */
private fun migrateLegacyTurns(
    turns: List<ChatTurn>,
    legacy: String?,
): List<ChatTurn> {
    if (turns.isNotEmpty()) return turns
    val clean = legacy?.takeUnless { it.startsWith(ERROR_PREFIX) } ?: return emptyList()
    // Synthetic initial-prompt placeholder so follow-up still has an index-0
    // hidden slot; the actual text is inconsequential, model will rely on the
    // system prompt + assistant reply for context.
    return listOf(
        ChatTurn(ChatTurn.Role.USER, "（初始分析请求）"),
        ChatTurn(ChatTurn.Role.ASSISTANT, clean),
    )
}

private fun legacyErrorOrNull(legacy: String?, turns: List<ChatTurn>): String? {
    if (turns.isNotEmpty()) return null
    return legacy?.takeIf { it.startsWith(ERROR_PREFIX) }?.removePrefix(ERROR_PREFIX)
}

private fun latestAssistant(turns: List<ChatTurn>): String? =
    turns.lastOrNull { it.role == ChatTurn.Role.ASSISTANT }?.content?.takeIf { it.isNotBlank() }

/**
 * Append the *other* coach card's latest reply as cross-reference context so
 * follow-up questions can explicitly reconcile disagreements between the two
 * viewpoints. Truncated to keep prompt size bounded on long conversations.
 */
private fun systemWithCross(base: String, crossLabel: String, crossContent: String?): String {
    if (crossContent.isNullOrBlank()) return base
    val snippet = crossContent.take(1500)
    return base + "\n\n【$crossLabel（仅供对照，可在回答中调和差异）】\n$snippet"
}

@Composable
fun PokerScreen(settings: AppSettings, onBack: () -> Unit) {
    val trainer = remember { HoldemTrainer() }

    // Restore prior session (if any) on first composition, else start fresh.
    // Discard legacy preflop sessions saved before PreflopScript existed —
    // those have empty history and a hard-coded toCall, so they no longer
    // reflect the realistic scripted scenarios. UTG with empty history is
    // legitimate (no seats act before UTG) so we keep those.
    val initial: PokerSession = remember {
        val saved = settings.loadPokerSession()
        val isLegacy = saved != null &&
            saved.table.street == Street.PREFLOP &&
            saved.table.history.isEmpty() &&
            saved.table.heroPosition != xyz.nextalone.cardtrainer.engine.holdem.Position.UTG
        if (saved != null && !isLegacy) {
            trainer.restoreFrom(saved.table)
            saved
        } else {
            PokerSession(table = trainer.newHand(), phase = PokerSession.Phase.DECIDING)
        }
    }

    var table by remember { mutableStateOf(initial.table) }
    var phase by remember { mutableStateOf(initial.phase) }

    // Algorithmic analysis — only revealed after submit.
    var equityPct by remember { mutableStateOf<Double?>(null) }
    var outs by remember { mutableStateOf<OutsReport?>(null) }
    // Showdown outcome — only set when hand resolved via reveal (not fold).
    var showdown by remember { mutableStateOf<xyz.nextalone.cardtrainer.engine.holdem.ShowdownResult?>(null) }
    // Multi-turn AI conversation state. Each is a list of ChatTurn: index 0
    // is always the structured initial user prompt (hidden from the UI); index
    // 1 is the first assistant reply; further indices alternate user follow-ups
    // and assistant replies.
    var situationTurns by remember {
        mutableStateOf(migrateLegacyTurns(initial.situationTurns, initial.situationAnalysis))
    }
    var situationError by remember {
        mutableStateOf(legacyErrorOrNull(initial.situationAnalysis, initial.situationTurns))
    }
    var loadingSituation by remember { mutableStateOf(false) }
    var evaluationTurns by remember {
        mutableStateOf(migrateLegacyTurns(initial.evaluationTurns, initial.choiceEvaluation))
    }
    var evaluationError by remember {
        mutableStateOf(legacyErrorOrNull(initial.choiceEvaluation, initial.evaluationTurns))
    }
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
    LaunchedEffect(table, phase, userChoice, situationTurns, evaluationTurns) {
        settings.savePokerSession(
            PokerSession(
                table = table,
                phase = phase,
                userChoiceAction = userChoice?.first,
                userChoiceAmount = userChoice?.second,
                situationTurns = situationTurns,
                evaluationTurns = evaluationTurns,
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
        val initialPrompt = Prompts.holdemUser(
            table = table,
            equityPct = equityPct,
            preflopBaseline = if (table.street == Street.PREFLOP) preflopBaseline else null,
            outs = outs,
            madeHand = if (table.board.size >= 3)
                HandEvaluator.evaluate(table.hero + table.board).category
            else null,
            draws = if (table.board.size in 3..4) Draws.detect(table.hero, table.board) else emptyList(),
            userChoice = null,
        )
        val seedTurns = listOf(ChatTurn(ChatTurn.Role.USER, initialPrompt))
        val coach = LlmProviders.create(cfg)
        try {
            val reply = withRetry {
                coach.coach(systemPrompt = Prompts.HOLDEM_SYSTEM, messages = seedTurns)
            }
            situationTurns = seedTurns + ChatTurn(ChatTurn.Role.ASSISTANT, reply)
            situationError = null
        } catch (t: Throwable) {
            situationError = t.message ?: t::class.simpleName ?: "未知错误"
        } finally {
            coach.close()
            loadingSituation = false
        }
    }

    // Follow-up on the situation analysis — appends the question + reply.
    // The system prompt is augmented with the evaluation card's latest
    // assistant turn (if any) as a cross-reference so the model can
    // explicitly reconcile when the two cards disagree.
    suspend fun followUpSituation(question: String) {
        val cfg = settings.activeConfig()
        if (cfg.apiKey.isBlank()) {
            situationError = "请先在『设置』中填写 ${cfg.kind.label} 的 API Key。"
            return
        }
        val priorTurns = situationTurns + ChatTurn(ChatTurn.Role.USER, question)
        loadingSituation = true
        situationError = null
        val coach = LlmProviders.create(cfg)
        try {
            val reply = withRetry {
                coach.coach(
                    systemPrompt = systemWithCross(
                        Prompts.HOLDEM_SYSTEM,
                        crossLabel = "另一位教练对用户决策的评价",
                        crossContent = latestAssistant(evaluationTurns),
                    ),
                    messages = priorTurns,
                )
            }
            situationTurns = priorTurns + ChatTurn(ChatTurn.Role.ASSISTANT, reply)
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

        // Skip the AI call if we already have a conversation for this exact
        // board (e.g. after restoring a session). Avoids duplicate billing.
        if (situationTurns.isNotEmpty()) return@LaunchedEffect
        runSituationAnalysis()
    }

    fun startNewHand() {
        table = trainer.newHand()
        phase = Phase.DECIDING
        userChoice = null
        situationTurns = emptyList()
        situationError = null
        evaluationTurns = emptyList()
        evaluationError = null
        equityPct = null
        outs = null
        showdown = null
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
        val initialPrompt = Prompts.holdemUser(
            table = table,
            equityPct = equityPct,
            preflopBaseline = if (table.street == Street.PREFLOP) preflopBaseline else null,
            outs = outs,
            madeHand = if (table.board.size >= 3)
                HandEvaluator.evaluate(table.hero + table.board).category
            else null,
            draws = if (table.board.size in 3..4) Draws.detect(table.hero, table.board) else emptyList(),
            userChoice = choice,
        )
        val seedTurns = listOf(ChatTurn(ChatTurn.Role.USER, initialPrompt))
        val coach = LlmProviders.create(cfg)
        try {
            val reply = withRetry {
                coach.coach(systemPrompt = Prompts.HOLDEM_SYSTEM, messages = seedTurns)
            }
            evaluationTurns = seedTurns + ChatTurn(ChatTurn.Role.ASSISTANT, reply)
            evaluationError = null
        } catch (t: Throwable) {
            evaluationError = t.message ?: t::class.simpleName ?: "未知错误"
        } finally {
            coach.close()
            loadingEvaluation = false
        }
    }

    suspend fun followUpEvaluation(question: String) {
        val cfg = settings.activeConfig()
        if (cfg.apiKey.isBlank()) {
            evaluationError = "请先在『设置』中填写 ${cfg.kind.label} 的 API Key。"
            return
        }
        val priorTurns = evaluationTurns + ChatTurn(ChatTurn.Role.USER, question)
        loadingEvaluation = true
        evaluationError = null
        val coach = LlmProviders.create(cfg)
        try {
            val reply = withRetry {
                coach.coach(
                    systemPrompt = systemWithCross(
                        Prompts.HOLDEM_SYSTEM,
                        crossLabel = "另一位教练的独立视角分析",
                        crossContent = latestAssistant(situationTurns),
                    ),
                    messages = priorTurns,
                )
            }
            evaluationTurns = priorTurns + ChatTurn(ChatTurn.Role.ASSISTANT, reply)
        } catch (t: Throwable) {
            evaluationError = t.message ?: t::class.simpleName ?: "未知错误"
        } finally {
            coach.close()
            loadingEvaluation = false
        }
    }

    val stats = remember { StatsRepository(settings) }

    fun submitDecision() {
        val (act, amt) = userChoice ?: return
        val potBeforeHero = table.pot
        val toCallBeforeHero = table.toCall
        val onRiver = table.street == Street.RIVER
        // Reflect the hero's action in the table state: pot, to-call, history
        // all update. Otherwise '发下一街' would deal a turn card with the pot
        // still at its pre-decision value.
        table = trainer.applyAction(table, act, amt)
        // If the hero bet / raised / shoved, run the villain's response
        // (fold or call). Folds end the hand; calls grow the pot by the
        // same amount for next street.
        var villainResp: xyz.nextalone.cardtrainer.engine.holdem.VillainResponse.Response? = null
        if (act == Action.BET || act == Action.RAISE || act == Action.ALL_IN) {
            val resp = xyz.nextalone.cardtrainer.engine.holdem.VillainResponse.react(
                table = table,
                heroAction = act,
                heroAmount = amt,
            )
            villainResp = resp
            table = table.copy(
                pot = table.pot + resp.addedToPot,
                toCall = resp.newToCall,
                history = table.history + resp.records,
                street = if (resp.folded) Street.SHOWDOWN else table.street,
            )
        }
        // On the river, if nobody folded and there's no pending bet, the
        // hand is now settled — reveal villain's cards and compare.
        val reachShowdown = onRiver && act != Action.FOLD &&
            villainResp?.folded != true && table.toCall == 0 && showdown == null
        if (reachShowdown) {
            val (newTable, result) = trainer.concludeToShowdown(table)
            table = newTable
            showdown = result
        }

        val resolution: String? = when {
            act == Action.FOLD -> "FOLD"
            villainResp?.folded == true -> "VILLAIN_FOLD"
            showdown != null -> "SHOWDOWN"
            else -> null
        }
        val heroWon: Boolean? = when {
            act == Action.FOLD -> false
            villainResp?.folded == true -> true
            showdown != null -> showdown!!.heroWon && !showdown!!.isTie
            else -> null
        }

        // Record the decision for long-term behavioural stats.
        stats.recordPoker(
            PokerDecisionEvent(
                timestampMs = nowEpochMs(),
                position = table.heroPosition.label,
                street = when (table.street) {
                    Street.SHOWDOWN -> when (table.board.size) {
                        0 -> "PREFLOP"; 3 -> "FLOP"; 4 -> "TURN"; else -> "RIVER"
                    }
                    else -> table.street.name
                },
                handLabel = PreflopChart.encode(table.hero),
                boardSize = table.board.size,
                potBefore = potBeforeHero,
                toCall = toCallBeforeHero,
                equityPct = equityPct,
                action = act.name,
                amount = amt,
                rfiBaseline = if (table.board.isEmpty()) preflopBaseline.name else null,
                villainResponse = villainResp?.let { if (it.folded) "FOLD" else "CALL" },
                potAfter = table.pot,
                handOver = table.street == Street.SHOWDOWN,
                heroWonHand = heroWon,
                handResolution = resolution,
            ),
        )
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
            // Pinned action row, always visible — no scrolling to reach it.
            //  - DECIDING:  提交决策 (disabled until the user picks a size) + 新牌局
            //  - SUBMITTED + can advance (PREFLOP/FLOP/TURN): 发下一街 + 新牌局
            //  - SUBMITTED on river (hand over): 新牌局 only
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (phase) {
                        Phase.DECIDING -> {
                            Button(
                                onClick = ::submitDecision,
                                enabled = userChoice != null,
                                modifier = Modifier.weight(1f),
                            ) { Text("提交决策") }
                            OutlinedButton(
                                onClick = ::startNewHand,
                                modifier = Modifier.weight(1f),
                            ) { Text("新牌局") }
                        }
                        Phase.SUBMITTED -> {
                            val canAdvance = table.street == Street.PREFLOP ||
                                table.street == Street.FLOP ||
                                table.street == Street.TURN
                            if (canAdvance) {
                                FilledTonalButton(
                                    onClick = {
                                        table = trainer.advanceStreet(table)
                                        phase = Phase.DECIDING
                                        userChoice = null
                                        evaluationTurns = emptyList()
                                        evaluationError = null
                                        situationTurns = emptyList()
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
                )
                Phase.SUBMITTED -> SubmittedBlock(
                    table = table,
                    userChoice = userChoice,
                    equityPct = equityPct,
                    outs = outs,
                    preflopBaseline = if (table.street == Street.PREFLOP) preflopBaseline.toString() else null,
                    showdown = showdown,
                    situationTurns = situationTurns,
                    situationError = situationError,
                    loadingSituation = loadingSituation,
                    onRetrySituation = { scope.launch { runSituationAnalysis() } },
                    onFollowUpSituation = { q -> scope.launch { followUpSituation(q) } },
                    evaluationTurns = evaluationTurns,
                    evaluationError = evaluationError,
                    loadingEvaluation = loadingEvaluation,
                    onRetryEvaluation = { scope.launch { runEvaluation() } },
                    onFollowUpEvaluation = { q -> scope.launch { followUpEvaluation(q) } },
                )
            }
        }
    }

    if (showGlossary) {
        GlossaryDialog(kind = GlossaryKind.POKER, onDismiss = { showGlossary = false })
    }
}

@Composable
private fun PreflopHistoryCard(table: HoldemTable) {
    // Position order in a 6-max game, UTG first.
    val order = listOf(
        xyz.nextalone.cardtrainer.engine.holdem.Position.UTG,
        xyz.nextalone.cardtrainer.engine.holdem.Position.MP,
        xyz.nextalone.cardtrainer.engine.holdem.Position.CO,
        xyz.nextalone.cardtrainer.engine.holdem.Position.BTN,
        xyz.nextalone.cardtrainer.engine.holdem.Position.SB,
        xyz.nextalone.cardtrainer.engine.holdem.Position.BB,
    )
    val seatsBeforeHero = order.takeWhile { it != table.heroPosition }
    val summary = table.history.zip(seatsBeforeHero).joinToString("  ·  ") { (rec, seat) ->
        when (rec.action) {
            Action.FOLD -> "${seat.label} 弃"
            Action.CALL -> if (rec.amount == 2) "${seat.label} 跛入" else "${seat.label} 跟 ${rec.amount}"
            Action.RAISE -> "${seat.label} 加到 ${rec.amount} (${rec.amount / 2.0}bb)"
            Action.BET -> "${seat.label} 下 ${rec.amount}"
            Action.CHECK -> "${seat.label} 过"
            Action.ALL_IN -> "${seat.label} 全下"
        }
    }
    val emptyMsg = if (table.heroPosition == xyz.nextalone.cardtrainer.engine.holdem.Position.UTG) {
        "你是 UTG，第一个行动 — 没有人在你之前下注。"
    } else {
        "（旧版本保存的牌局没有翻前脚本，点击「新牌局」生成新场景。）"
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("翻前动作（轮到你之前）", fontWeight = FontWeight.SemiBold)
            Text(summary.ifBlank { emptyMsg }, style = MaterialTheme.typography.bodyMedium)
            Text(
                "→ 现底池 ${table.pot} chips · 你${if (table.toCall == 0) "可过牌 / 开池" else "需跟注 ${table.toCall} chips"} · 当前对手 ${table.opponents} 人",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PostflopStreetCard(table: HoldemTable) {
    val streetLabel = when (table.street) {
        Street.FLOP -> "翻牌"
        Street.TURN -> "转牌"
        Street.RIVER -> "河牌"
        else -> table.street.name
    }
    val villainAction = table.history.lastOrNull { it.street == table.street }
    val villainLine = when (villainAction?.action) {
        Action.CHECK -> "对手（OOP） 过牌 check"
        Action.BET -> "对手（OOP） 下注 ${villainAction.amount} chips"
        Action.RAISE -> "对手（OOP） 加注到 ${villainAction.amount} chips"
        else -> "对手尚未行动"
    }
    val youLine = if (table.toCall == 0) {
        "你可过牌 / 下注（开池）"
    } else {
        "你需跟注 ${table.toCall} chips（底池赔率 ${kotlin.math.round(table.potOdds * 1000) / 10}%）"
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${streetLabel}动作", fontWeight = FontWeight.SemiBold)
            Text(villainLine, style = MaterialTheme.typography.bodyMedium)
            Text(
                "→ 现底池 ${table.pot} chips · $youLine",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DecidingBlock(
    table: HoldemTable,
    userChoice: Pair<Action, Int>?,
    onChoose: (Action, Int) -> Unit,
) {
    Text(
        "请先独立判断，选择你的动作。AI 与算法结果会在提交后显示。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // Street context: preflop shows what every seat did before the hero;
    // post-flop shows the OOP villain's opening action for this street.
    if (table.street == Street.PREFLOP) {
        PreflopHistoryCard(table = table)
    } else if (table.street != Street.SHOWDOWN) {
        PostflopStreetCard(table = table)
    }

    val presets = remember(table.pot, table.toCall, table.heroStack, table.board) {
        ActionPresets.forTable(table)
    }
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

    // Custom amount: lets the user commit any size the presets don't cover —
    // e.g. iso-raise 4.5bb preflop, or a 55% pot c-bet. The action defaults
    // to BET when there's nothing to call and RAISE otherwise.
    var customAmount by remember(table.pot, table.toCall, table.board) { mutableStateOf("") }
    val customAction = if (table.toCall == 0) Action.BET else Action.RAISE
    val customInt = customAmount.toIntOrNull()
    val customValid = customInt != null &&
        customInt > 0 &&
        customInt <= table.heroStack &&
        (table.toCall == 0 || customInt > table.toCall)
    val isCustomChosen = userChoice?.first == customAction && customInt != null && userChoice.second == customInt
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.material3.OutlinedTextField(
            value = customAmount,
            onValueChange = { new -> customAmount = new.filter { it.isDigit() }.take(5) },
            label = {
                Text(
                    if (table.toCall == 0) "自定义下注 (chips)"
                    else "自定义加注到 (chips)",
                )
            },
            singleLine = true,
            supportingText = {
                val bbHint = customInt?.let { " ≈ ${it / 2.0}bb" } ?: ""
                Text("筹码值${bbHint}")
            },
            modifier = Modifier.weight(1f),
        )
        FilterChip(
            selected = isCustomChosen,
            enabled = customValid,
            onClick = { if (customValid) onChoose(customAction, customInt!!) },
            label = { Text("选用") },
        )
    }
    // 提交决策 / 新牌局 are in the pinned Scaffold.bottomBar above — kept out
    // of the scrolling content so the user never has to scroll to submit.
}

@Composable
private fun SubmittedBlock(
    table: HoldemTable,
    userChoice: Pair<Action, Int>?,
    equityPct: Double?,
    outs: OutsReport?,
    preflopBaseline: String?,
    showdown: xyz.nextalone.cardtrainer.engine.holdem.ShowdownResult?,
    situationTurns: List<ChatTurn>,
    situationError: String?,
    loadingSituation: Boolean,
    onRetrySituation: () -> Unit,
    onFollowUpSituation: (String) -> Unit,
    evaluationTurns: List<ChatTurn>,
    evaluationError: String?,
    loadingEvaluation: Boolean,
    onRetryEvaluation: () -> Unit,
    onFollowUpEvaluation: (String) -> Unit,
) {
    userChoice?.let { (a, amt) ->
        val tag = if (amt > 0) " $amt" else ""
        Text(
            "你的决策：${a.label}$tag",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        // Villain's response line: if hero bet/raised/shoved, the last
        // history record is villain's FOLD or CALL reaction.
        val heroBet = a == Action.BET || a == Action.RAISE || a == Action.ALL_IN
        val last = if (heroBet) table.history.lastOrNull() else null
        when (last?.action) {
            Action.FOLD -> Text(
                "对手弃牌 — 你赢得底池 ${table.pot} chips",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Action.CALL -> Text(
                "对手跟注 ${last.amount} chips — 底池变 ${table.pot}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> { /* villain didn't need to respond (hero checked/called/folded) */ }
        }
    }

    // Showdown reveal — appears on river after both players settled without
    // folding.
    showdown?.let { sd ->
        val winnerText = when {
            sd.isTie -> "平分底池 ${table.pot} chips"
            sd.heroWon -> "你赢得底池 ${table.pot} chips"
            else -> "对手赢得底池 ${table.pot} chips"
        }
        val winnerColor = when {
            sd.isTie -> MaterialTheme.colorScheme.tertiary
            sd.heroWon -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.error
        }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("摊牌", fontWeight = FontWeight.SemiBold)
                Text(
                    "你 ${table.hero.joinToString(" ") { it.label }}（${sd.heroCategory.displayName}）",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "对手 ${sd.villainCards.joinToString(" ") { it.label }}（${sd.villainCategory.displayName}）",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    winnerText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = winnerColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
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

    if (situationTurns.isNotEmpty() || situationError != null || loadingSituation) {
        AiConversation(
            title = "AI 牌局分析（独立视角）",
            turns = situationTurns,
            loading = loadingSituation,
            error = situationError,
            onRetry = onRetrySituation,
            onFollowUp = onFollowUpSituation,
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            onContainerColor = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }

    AiConversation(
        title = "AI 对你决策的评价 + 原因推断",
        turns = evaluationTurns,
        loading = loadingEvaluation,
        error = evaluationError,
        onRetry = onRetryEvaluation,
        onFollowUp = onFollowUpEvaluation,
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        onContainerColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
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
