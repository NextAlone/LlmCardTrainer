@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package xyz.nextalone.cardtrainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
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
import androidx.compose.ui.unit.sp
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
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
import xyz.nextalone.cardtrainer.ui.components.BrandChip
import xyz.nextalone.cardtrainer.ui.components.BrandDivider
import xyz.nextalone.cardtrainer.ui.components.BrandSurface
import xyz.nextalone.cardtrainer.ui.components.CardSize
import xyz.nextalone.cardtrainer.ui.components.ChipTone
import xyz.nextalone.cardtrainer.ui.components.Eyebrow
import xyz.nextalone.cardtrainer.ui.components.FeltSurface
import xyz.nextalone.cardtrainer.ui.components.Meter
import xyz.nextalone.cardtrainer.ui.components.PlayingCardView
import xyz.nextalone.cardtrainer.ui.theme.BrandBodyFamily
import xyz.nextalone.cardtrainer.ui.theme.BrandDisplayFamily
import xyz.nextalone.cardtrainer.ui.theme.BrandMonoFamily
import xyz.nextalone.cardtrainer.ui.theme.BrandTheme
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
        } catch (c: CancellationException) {
            throw c
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
        } catch (c: CancellationException) {
            throw c
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
        // Abort any in-flight analyse / evaluate / follow-up calls so their
        // eventual responses don't land on the fresh hand and corrupt the
        // score + recap.
        scope.coroutineContext.cancelChildren()
        table = trainer.newHand()
        phase = Phase.DECIDING
        userChoice = null
        situationTurns = emptyList()
        situationError = null
        loadingSituation = false
        evaluationTurns = emptyList()
        evaluationError = null
        loadingEvaluation = false
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
        } catch (c: CancellationException) {
            throw c
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
        } catch (c: CancellationException) {
            throw c
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

    val advanceStreet: () -> Unit = {
        // Same stale-response guard as startNewHand.
        scope.coroutineContext.cancelChildren()
        table = trainer.advanceStreet(table)
        phase = Phase.DECIDING
        userChoice = null
        evaluationTurns = emptyList()
        evaluationError = null
        loadingEvaluation = false
        situationTurns = emptyList()
        situationError = null
        loadingSituation = false
    }

    xyz.nextalone.cardtrainer.ui.components.WithDeviceMode { mode ->
        if (mode == xyz.nextalone.cardtrainer.ui.components.DeviceMode.Phone) {
            PokerPhone(
                table = table,
                phase = phase,
                userChoice = userChoice,
                equityPct = equityPct,
                outs = outs,
                preflopBaseline = preflopBaseline,
                showdown = showdown,
                situationTurns = situationTurns,
                situationError = situationError,
                loadingSituation = loadingSituation,
                evaluationTurns = evaluationTurns,
                evaluationError = evaluationError,
                loadingEvaluation = loadingEvaluation,
                onBack = onBack,
                onShowGlossary = { showGlossary = true },
                onChoose = { act, amt -> userChoice = act to amt },
                onSubmit = ::submitDecision,
                onNewHand = ::startNewHand,
                onAdvanceStreet = advanceStreet,
                onRetrySituation = { scope.launch { runSituationAnalysis() } },
                onFollowUpSituation = { q -> scope.launch { followUpSituation(q) } },
                onRetryEvaluation = { scope.launch { runEvaluation() } },
                onFollowUpEvaluation = { q -> scope.launch { followUpEvaluation(q) } },
            )
        } else {
            PokerDesktop(
                table = table,
                phase = phase,
                userChoice = userChoice,
                equityPct = equityPct,
                outs = outs,
                preflopBaseline = preflopBaseline,
                showdown = showdown,
                situationTurns = situationTurns,
                situationError = situationError,
                loadingSituation = loadingSituation,
                evaluationTurns = evaluationTurns,
                evaluationError = evaluationError,
                loadingEvaluation = loadingEvaluation,
                onBack = onBack,
                onShowGlossary = { showGlossary = true },
                onChoose = { act, amt -> userChoice = act to amt },
                onSubmit = ::submitDecision,
                onNewHand = ::startNewHand,
                onAdvanceStreet = advanceStreet,
                onRetrySituation = { scope.launch { runSituationAnalysis() } },
                onFollowUpSituation = { q -> scope.launch { followUpSituation(q) } },
                onRetryEvaluation = { scope.launch { runEvaluation() } },
                onFollowUpEvaluation = { q -> scope.launch { followUpEvaluation(q) } },
            )
        }
    }

    if (showGlossary) {
        GlossaryDialog(kind = GlossaryKind.POKER, onDismiss = { showGlossary = false })
    }
}

@Composable
private fun PokerPhone(
    table: HoldemTable,
    phase: Phase,
    userChoice: Pair<Action, Int>?,
    equityPct: Double?,
    outs: OutsReport?,
    preflopBaseline: xyz.nextalone.cardtrainer.engine.holdem.PreflopAction,
    showdown: xyz.nextalone.cardtrainer.engine.holdem.ShowdownResult?,
    situationTurns: List<ChatTurn>,
    situationError: String?,
    loadingSituation: Boolean,
    evaluationTurns: List<ChatTurn>,
    evaluationError: String?,
    loadingEvaluation: Boolean,
    onBack: () -> Unit,
    onShowGlossary: () -> Unit,
    onChoose: (Action, Int) -> Unit,
    onSubmit: () -> Unit,
    onNewHand: () -> Unit,
    onAdvanceStreet: () -> Unit,
    onRetrySituation: () -> Unit,
    onFollowUpSituation: (String) -> Unit,
    onRetryEvaluation: () -> Unit,
    onFollowUpEvaluation: (String) -> Unit,
) {
    xyz.nextalone.cardtrainer.ui.components.PhoneShell(
        eyebrow = "HOLD'EM · ${table.heroPosition.label} · ${streetLabel(table.street)}",
        title = "决策 ${table.history.size + 1}/4",
        onBack = onBack,
        topRight = {
            BrandChip("术语", tone = ChipTone.Outline, onClick = onShowGlossary)
            if (phase == Phase.DECIDING && loadingSituation) {
                BrandChip("AI 流", tone = ChipTone.Accent)
            }
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth()) {
                if (phase == Phase.DECIDING) {
                    PokerActionSheet(
                        table = table,
                        presets = ActionPresets.forTable(table),
                        userChoice = userChoice,
                        onChoose = onChoose,
                    )
                }
                PokerActionBar(
                    phase = phase,
                    canSubmit = userChoice != null,
                    canAdvance = table.street == Street.PREFLOP ||
                        table.street == Street.FLOP ||
                        table.street == Street.TURN,
                    evalScore = parsePokerScore(
                        evaluationTurns.lastOrNull { it.role == ChatTurn.Role.ASSISTANT }?.content,
                    ),
                    onSubmit = onSubmit,
                    onNewHand = onNewHand,
                    onAdvanceStreet = onAdvanceStreet,
                )
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TableCardStrip(table = table)
            HandProgressionCard(table = table, phase = phase)
            if (phase == Phase.SUBMITTED) {
                DecisionRecapCard(
                    table = table,
                    userChoice = userChoice,
                    equityPct = equityPct,
                    outs = outs,
                    preflopBaseline = if (table.street == Street.PREFLOP)
                        preflopBaseline.toString() else null,
                )
            }
            when (phase) {
                Phase.DECIDING -> DecidingBlock(
                    table = table,
                    userChoice = userChoice,
                    onChoose = onChoose,
                    showPresets = false,
                    showHistory = false,
                    showCustom = false,
                )
                Phase.SUBMITTED -> SubmittedBlock(
                    table = table,
                    showdown = showdown,
                    situationTurns = situationTurns,
                    situationError = situationError,
                    loadingSituation = loadingSituation,
                    onRetrySituation = onRetrySituation,
                    onFollowUpSituation = onFollowUpSituation,
                    evaluationTurns = evaluationTurns,
                    evaluationError = evaluationError,
                    loadingEvaluation = loadingEvaluation,
                    onRetryEvaluation = onRetryEvaluation,
                    onFollowUpEvaluation = onFollowUpEvaluation,
                )
            }
        }
    }
}

@Composable
private fun PokerDesktop(
    table: HoldemTable,
    phase: Phase,
    userChoice: Pair<Action, Int>?,
    equityPct: Double?,
    outs: OutsReport?,
    preflopBaseline: xyz.nextalone.cardtrainer.engine.holdem.PreflopAction,
    showdown: xyz.nextalone.cardtrainer.engine.holdem.ShowdownResult?,
    situationTurns: List<ChatTurn>,
    situationError: String?,
    loadingSituation: Boolean,
    evaluationTurns: List<ChatTurn>,
    evaluationError: String?,
    loadingEvaluation: Boolean,
    onBack: () -> Unit,
    onShowGlossary: () -> Unit,
    onChoose: (Action, Int) -> Unit,
    onSubmit: () -> Unit,
    onNewHand: () -> Unit,
    onAdvanceStreet: () -> Unit,
    onRetrySituation: () -> Unit,
    onFollowUpSituation: (String) -> Unit,
    onRetryEvaluation: () -> Unit,
    onFollowUpEvaluation: (String) -> Unit,
) {
    xyz.nextalone.cardtrainer.ui.components.DesktopShell(
        eyebrow = "HOLD'EM · ${table.heroPosition.label} · ${streetLabel(table.street)}",
        title = "决策 ${table.history.size + 1}/4",
        windowLabel = "LLM Card Trainer · Hold'em",
        onBack = onBack,
        topRight = {
            BrandChip("术语", tone = ChipTone.Outline, onClick = onShowGlossary)
            BrandChip("${table.opponents} 对手", tone = ChipTone.Outline)
            if (phase == Phase.DECIDING && loadingSituation) {
                BrandChip("AI 流", tone = ChipTone.Accent)
            }
        },
        bottomBar = {
            PokerActionBar(
                phase = phase,
                canSubmit = userChoice != null,
                canAdvance = table.street == Street.PREFLOP ||
                    table.street == Street.FLOP ||
                    table.street == Street.TURN,
                evalScore = parsePokerScore(
                    evaluationTurns.lastOrNull { it.role == ChatTurn.Role.ASSISTANT }?.content,
                ),
                onSubmit = onSubmit,
                onNewHand = onNewHand,
                onAdvanceStreet = onAdvanceStreet,
            )
        },
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // Left: table + decision
            Column(
                Modifier
                    .weight(1.25f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                TableCard(table = table, equityPct = equityPct, showAnalysis = phase == Phase.SUBMITTED)
                when (phase) {
                    Phase.DECIDING -> DecidingBlock(table, userChoice, onChoose)
                    Phase.SUBMITTED -> SubmittedLeftColumn(
                        table = table,
                        userChoice = userChoice,
                        equityPct = equityPct,
                        outs = outs,
                        preflopBaseline = if (table.street == Street.PREFLOP) preflopBaseline.toString() else null,
                        showdown = showdown,
                    )
                }
            }
            // Right: AI coaches
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PokerCoachMerged(
                    indepTurns = situationTurns,
                    indepError = situationError,
                    indepLoading = loadingSituation,
                    onIndepRetry = onRetrySituation,
                    onIndepFollowUp = onFollowUpSituation,
                    evalTurns = evaluationTurns,
                    evalError = evaluationError,
                    evalLoading = loadingEvaluation,
                    onEvalRetry = onRetryEvaluation,
                    onEvalFollowUp = onFollowUpEvaluation,
                    evalEnabled = phase == Phase.SUBMITTED,
                )
            }
        }
    }
}

/** Shared submit/advance/new-hand pinned action bar. */
@Composable
private fun PokerActionBar(
    phase: Phase,
    canSubmit: Boolean,
    canAdvance: Boolean,
    evalScore: Double?,
    onSubmit: () -> Unit,
    onNewHand: () -> Unit,
    onAdvanceStreet: () -> Unit,
) {
    xyz.nextalone.cardtrainer.ui.components.PinnedActionBar {
        if (phase == Phase.SUBMITTED && evalScore != null) {
            PokerScoreBadge(score = evalScore)
        }
        Spacer(Modifier.weight(1f))
        when (phase) {
            Phase.DECIDING -> {
                OutlinedButton(onClick = onNewHand) { Text("新牌局") }
                Button(onClick = onSubmit, enabled = canSubmit) { Text("提交决策") }
            }
            Phase.SUBMITTED -> {
                OutlinedButton(onClick = onNewHand) { Text("新牌局") }
                if (canAdvance) {
                    Button(onClick = onAdvanceStreet) { Text("发下一街") }
                }
            }
        }
    }
}

/**
 * Compact `N/5` chip parked on the left of the pinned action bar after the
 * evaluation returns — lets the user see the AI verdict without expanding
 * the coach card. Color tier mirrors the bigger [ScoreBar] inside the card.
 */
@Composable
private fun PokerScoreBadge(score: Double) {
    val c = BrandTheme.colors
    val tint = when {
        score >= 4.0 -> c.good
        score >= 3.0 -> c.accent
        score >= 2.0 -> c.warn
        else -> c.bad
    }
    val shape = RoundedCornerShape(999.dp)
    Row(
        Modifier
            .clip(shape)
            .background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.5f), shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "评分",
            style = TextStyle(
                fontFamily = BrandMonoFamily,
                fontSize = 10.sp,
                color = c.fgMuted,
                letterSpacing = 1.2.sp,
            ),
        )
        Text(
            "${kotlin.math.round(score * 10) / 10}",
            style = TextStyle(
                fontFamily = BrandMonoFamily,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = tint,
            ),
        )
        Text(
            "/5",
            style = TextStyle(
                fontFamily = BrandMonoFamily,
                fontSize = 11.sp,
                color = c.fgSubtle,
            ),
        )
    }
}

/**
 * Floating action sheet pinned above the global tab bar on phone.
 *  - Row 1: defensive presets (FOLD / CHECK / CALL) + ALL_IN merged into one
 *    equal-weight row so they are all reachable without vertical scanning.
 *  - Row 2: RAISE / BET presets collapsed into a single "加注" row — the
 *    size chips (2x / 2.5x / 3x / pot / ⅓ …) each take equal weight so they
 *    fill the row width without horizontal scrolling.
 *  - All buttons dark/neutral by default; only the selected one highlights.
 */
@Composable
private fun PokerActionSheet(
    table: HoldemTable,
    presets: List<xyz.nextalone.cardtrainer.engine.holdem.ActionPreset>,
    userChoice: Pair<Action, Int>?,
    onChoose: (Action, Int) -> Unit,
) {
    val c = BrandTheme.colors
    val defensive = presets.filter { it.action in listOf(Action.FOLD, Action.CHECK, Action.CALL) }
    val raises = presets.filter { it.action in listOf(Action.BET, Action.RAISE) }
    val allIn = presets.firstOrNull { it.action == Action.ALL_IN }
    val topRow = buildList {
        addAll(defensive)
        if (allIn != null) add(allIn)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (topRow.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                topRow.forEach { p ->
                    val selected = userChoice?.first == p.action && userChoice.second == p.amount
                    ActionSheetButton(
                        label = p.label,
                        selected = selected,
                        onClick = { onChoose(p.action, p.amount) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (raises.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "加注",
                    modifier = Modifier.width(36.dp),
                    style = TextStyle(
                        fontFamily = BrandDisplayFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = c.fgMuted,
                    ),
                )
                raises.forEach { p ->
                    val selected = userChoice?.first == p.action && userChoice.second == p.amount
                    val hint = p.label
                        .removePrefix("加注 ")
                        .removePrefix("下注 ")
                    ActionSheetChip(
                        label = hint,
                        selected = selected,
                        onClick = { onChoose(p.action, p.amount) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Full-width / half-width action button. Dark by default, brass when selected. */
@Composable
private fun ActionSheetButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = BrandTheme.colors
    val shape = RoundedCornerShape(10.dp)
    val bg: androidx.compose.ui.graphics.Brush = if (selected) {
        androidx.compose.ui.graphics.Brush.verticalGradient(
            0f to c.accentBright,
            1f to c.accent,
        )
    } else {
        androidx.compose.ui.graphics.SolidColor(c.fg.copy(alpha = 0.06f))
    }
    val borderColor = if (selected) c.accent.copy(alpha = 0.7f) else c.border
    val labelColor = if (selected) c.fg else c.fgMuted
    Box(
        modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = TextStyle(
                fontFamily = BrandDisplayFamily,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = labelColor,
            ),
            maxLines = 1,
        )
    }
}

/** Compact size-hint chip for the raise row (2x / 2.5x / 3x / pot). */
@Composable
private fun ActionSheetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = BrandTheme.colors
    val shape = RoundedCornerShape(999.dp)
    val bg = if (selected) {
        androidx.compose.ui.graphics.Brush.verticalGradient(0f to c.accentBright, 1f to c.accent)
    } else {
        androidx.compose.ui.graphics.SolidColor(c.fg.copy(alpha = 0.06f))
    }
    val borderColor = if (selected) c.accent.copy(alpha = 0.7f) else c.border
    val fgColor = if (selected) c.fg else c.fgMuted
    Box(
        modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = TextStyle(
                fontFamily = BrandMonoFamily,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = fgColor,
            ),
            maxLines = 1,
        )
    }
}

/** Street label as 翻前/翻牌/转牌/河牌 for header eyebrow. */
private fun streetLabel(s: Street): String = when (s) {
    Street.PREFLOP -> "PREFLOP"
    Street.FLOP -> "FLOP"
    Street.TURN -> "TURN"
    Street.RIVER -> "RIVER"
    Street.SHOWDOWN -> "SHOWDOWN"
}

/**
 * Desktop left-column post-submit: everything except the AI conversations
 * (which live in the right column on desktop). Mirrors [SubmittedBlock] but
 * drops the conversation cards.
 */
@Composable
private fun SubmittedLeftColumn(
    table: HoldemTable,
    userChoice: Pair<Action, Int>?,
    equityPct: Double?,
    outs: OutsReport?,
    preflopBaseline: String?,
    showdown: xyz.nextalone.cardtrainer.engine.holdem.ShowdownResult?,
) {
    userChoice?.let { (a, amt) ->
        val tag = if (amt > 0) " $amt" else ""
        Text(
            "你的决策：${a.label}$tag",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = BrandTheme.colors.fg,
        )
        val heroBet = a == Action.BET || a == Action.RAISE || a == Action.ALL_IN
        val last = if (heroBet) table.history.lastOrNull() else null
        when (last?.action) {
            Action.FOLD -> Text(
                "对手弃牌 — 你赢得底池 ${table.pot} chips",
                style = MaterialTheme.typography.bodyMedium,
                color = BrandTheme.colors.good,
            )
            Action.CALL -> Text(
                "对手跟注 ${last.amount} chips — 底池变 ${table.pot}",
                style = MaterialTheme.typography.bodyMedium,
                color = BrandTheme.colors.fgMuted,
            )
            else -> Unit
        }
    }

    showdown?.let { sd ->
        val winnerText = when {
            sd.isTie -> "平分底池 ${table.pot} chips"
            sd.heroWon -> "你赢得底池 ${table.pot} chips"
            else -> "对手赢得底池 ${table.pot} chips"
        }
        val winnerColor = when {
            sd.isTie -> BrandTheme.colors.accent
            sd.heroWon -> BrandTheme.colors.good
            else -> BrandTheme.colors.bad
        }
        BrandSurface {
            Eyebrow("摊牌")
            Spacer(Modifier.height(6.dp))
            Text(
                "你 ${table.hero.joinToString(" ") { it.label }}（${sd.heroCategory.displayName}）",
                style = MaterialTheme.typography.bodyMedium,
                color = BrandTheme.colors.fg,
            )
            Text(
                "对手 ${sd.villainCards.joinToString(" ") { it.label }}（${sd.villainCategory.displayName}）",
                style = MaterialTheme.typography.bodyMedium,
                color = BrandTheme.colors.fg,
            )
            Spacer(Modifier.height(4.dp))
            Text(winnerText, style = MaterialTheme.typography.titleMedium, color = winnerColor, fontWeight = FontWeight.SemiBold)
        }
    }

    BrandSurface {
        Eyebrow("算法结果")
        Spacer(Modifier.height(8.dp))
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
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                parts.forEachIndexed { i, p ->
                    BrandChip(p, tone = if (i == 0) ChipTone.Accent else ChipTone.Outline)
                }
            }
        }
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
    BrandSurface {
        Eyebrow("翻前动作 · 轮到你之前")
        Spacer(Modifier.height(6.dp))
        Text(
            summary.ifBlank { emptyMsg },
            style = MaterialTheme.typography.bodyMedium,
            color = BrandTheme.colors.fg,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "→ 现底池 ${table.pot} chips · 你${if (table.toCall == 0) "可过牌 / 开池" else "需跟注 ${table.toCall} chips"} · 当前对手 ${table.opponents} 人",
            style = MaterialTheme.typography.bodySmall,
            color = BrandTheme.colors.fgMuted,
        )
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
    BrandSurface {
        Eyebrow("${streetLabel} 动作")
        Spacer(Modifier.height(6.dp))
        Text(villainLine, style = MaterialTheme.typography.bodyMedium, color = BrandTheme.colors.fg)
        Spacer(Modifier.height(4.dp))
        Text(
            "→ 现底池 ${table.pot} chips · $youLine",
            style = MaterialTheme.typography.bodySmall,
            color = BrandTheme.colors.fgMuted,
        )
    }
}

@Composable
private fun DecidingBlock(
    table: HoldemTable,
    userChoice: Pair<Action, Int>?,
    onChoose: (Action, Int) -> Unit,
    showPresets: Boolean = true,
    showHistory: Boolean = true,
    showCustom: Boolean = true,
) {
    Text(
        "请先独立判断，选择你的动作。AI 与算法结果会在提交后显示。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // Street context: preflop shows what every seat did before the hero;
    // post-flop shows the OOP villain's opening action for this street.
    // When the parent already renders HandProgressionCard, skip this.
    if (showHistory) {
        if (table.street == Street.PREFLOP) {
            PreflopHistoryCard(table = table)
        } else if (table.street != Street.SHOWDOWN) {
            PostflopStreetCard(table = table)
        }
    }

    if (showPresets) {
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
    }

    if (showCustom) {
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
    }
    // 提交决策 / 新牌局 are in the pinned Scaffold.bottomBar above — kept out
    // of the scrolling content so the user never has to scroll to submit.
}

@Composable
private fun SubmittedBlock(
    table: HoldemTable,
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
    // Decision echo + villain response now live in DecisionRecapCard above.

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
        BrandSurface {
            Eyebrow("摊牌")
            Spacer(Modifier.height(6.dp))
            Text(
                "你 ${table.hero.joinToString(" ") { it.label }}（${sd.heroCategory.displayName}）",
                style = MaterialTheme.typography.bodyMedium,
                color = BrandTheme.colors.fg,
            )
            Text(
                "对手 ${sd.villainCards.joinToString(" ") { it.label }}（${sd.villainCategory.displayName}）",
                style = MaterialTheme.typography.bodyMedium,
                color = BrandTheme.colors.fg,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                winnerText,
                style = MaterialTheme.typography.titleMedium,
                color = winnerColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

    // The detailed 算法结果 card is rendered above in PokerPhone (AlgoDataCard
    // + HandSnapshotChips) so the SubmittedBlock only adds decision echo,
    // showdown and AI conversations.

    PokerCoachMerged(
        indepTurns = situationTurns,
        indepError = situationError,
        indepLoading = loadingSituation,
        onIndepRetry = onRetrySituation,
        onIndepFollowUp = onFollowUpSituation,
        evalTurns = evaluationTurns,
        evalError = evaluationError,
        evalLoading = loadingEvaluation,
        onEvalRetry = onRetryEvaluation,
        onEvalFollowUp = onFollowUpEvaluation,
        evalEnabled = true,
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
    val c = BrandTheme.colors
    val ivory = Color(0xFFFFFAED)
    val streetLabel = when (table.street) {
        Street.PREFLOP -> "PREFLOP"
        Street.FLOP -> "FLOP"
        Street.TURN -> "TURN"
        Street.RIVER -> "RIVER"
        Street.SHOWDOWN -> "SHOWDOWN"
    }
    FeltSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "POT ${table.pot}  ·  $streetLabel",
                        style = TextStyle(
                            fontFamily = BrandMonoFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                            color = c.accentBright,
                            letterSpacing = 1.5.sp,
                        ),
                    )
                }
                BrandChip("${table.heroPosition.label} · ${table.opponents} 对手", tone = ChipTone.Felt)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "公共牌",
                style = TextStyle(
                    fontFamily = BrandMonoFamily,
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.55f),
                    letterSpacing = 1.4.sp,
                ),
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                table.board.forEach {
                    PlayingCardView(
                        rank = it.rank.label,
                        suit = it.suit.symbol,
                        size = CardSize.Default,
                    )
                }
                repeat(5 - table.board.size) {
                    PlayingCardView(rank = "", suit = "", size = CardSize.Default, slot = true)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "我的手牌",
                style = TextStyle(
                    fontFamily = BrandMonoFamily,
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.55f),
                    letterSpacing = 1.4.sp,
                ),
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                table.hero.forEach {
                    PlayingCardView(
                        rank = it.rank.label,
                        suit = it.suit.symbol,
                        size = CardSize.Default,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FeltStat("底池", table.pot.toString(), ivory)
                FeltStat("跟注", if (table.toCall == 0) "—" else table.toCall.toString(), ivory)
                if (showAnalysis && equityPct != null) {
                    val r = kotlin.math.round(equityPct * 10) / 10
                    FeltStat("胜率", "$r%", c.accentBright, big = true)
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FeltStat(label: String, value: String, valueColor: Color, big: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label.uppercase(),
            style = TextStyle(
                fontFamily = BrandMonoFamily,
                fontSize = 9.sp,
                color = Color.White.copy(alpha = 0.55f),
                letterSpacing = 1.4.sp,
            ),
        )
        Text(
            value,
            style = TextStyle(
                fontFamily = BrandMonoFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (big) 24.sp else 18.sp,
                color = valueColor,
            ),
        )
    }
}

/**
 * Phone-only compact strip: ink-dark outer + felt inner with BOARD / HERO.
 * Analysis data (equity, odds, outs, made hand) is now shown in separate
 * chip row / data card below the strip — this keeps the cards readable.
 */
@Composable
private fun TableCardStrip(table: HoldemTable) {
    val c = BrandTheme.colors
    val ink = Color(0xFF0A0A0A)
    val outer = RoundedCornerShape(14.dp)
    val inner = RoundedCornerShape(10.dp)
    val streetLabel = when (table.street) {
        Street.PREFLOP -> "PREFLOP"
        Street.FLOP -> "FLOP"
        Street.TURN -> "TURN"
        Street.RIVER -> "RIVER"
        Street.SHOWDOWN -> "SHOWDOWN"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(outer)
            .background(ink)
            .border(1.dp, Color.White.copy(alpha = 0.10f), outer)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$streetLabel · POT ${table.pot}",
                style = TextStyle(
                    fontFamily = BrandMonoFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.accentBright,
                    letterSpacing = 1.8.sp,
                ),
            )
            Spacer(Modifier.weight(1f))
            val villain = inferVillainLabel(table)
            val matchupLabel = when {
                villain != null && villain != table.heroPosition.label ->
                    "${table.heroPosition.label} vs $villain"
                table.opponents == 1 -> "${table.heroPosition.label} 单挑"
                else -> "${table.heroPosition.label} · ${table.opponents} 对手"
            }
            BrandChip(matchupLabel, tone = ChipTone.Felt)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(inner)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        0f to c.table,
                        1f to c.tableDeep,
                    ),
                )
                .border(1.dp, Color.White.copy(alpha = 0.08f), inner)
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // BOARD
            Column(
                Modifier.weight(5f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FeltEyebrow("BOARD", c.accentBright)
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    table.board.forEach {
                        PlayingCardView(
                            rank = it.rank.label,
                            suit = it.suit.symbol,
                            size = CardSize.Small,
                        )
                    }
                    repeat(5 - table.board.size) {
                        PlayingCardView(rank = "", suit = "", size = CardSize.Small, slot = true)
                    }
                }
            }
            // Divider
            Box(
                Modifier
                    .size(width = 1.dp, height = 66.dp)
                    .background(Color.White.copy(alpha = 0.12f)),
            )
            // HERO
            Column(
                Modifier.weight(2f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FeltEyebrow("HERO", c.accentBright)
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    table.hero.forEach {
                        PlayingCardView(
                            rank = it.rank.label,
                            suit = it.suit.symbol,
                            size = CardSize.Small,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Consolidated post-submit recap — decision + villain response + equity +
 * odds + made-hand / outs / draw chips, all in one surface. Replaces the
 * previous standalone `HandSnapshotChips`, `AlgoDataCard` and the inline
 * decision-echo lines in SubmittedBlock.
 */
@Composable
private fun DecisionRecapCard(
    table: HoldemTable,
    userChoice: Pair<Action, Int>?,
    equityPct: Double?,
    outs: OutsReport?,
    preflopBaseline: String?,
) {
    val c = BrandTheme.colors
    val (choiceLabel, villainLine) = remember(table, userChoice) {
        choiceAndVillainLine(table, userChoice)
    }
    val madeHand = if (table.board.size >= 3)
        HandEvaluator.evaluate(table.hero + table.board).category.displayName
    else null
    val draws = if (table.board.size in 3..4) Draws.detect(table.hero, table.board) else emptyList()

    BrandSurface {
        Eyebrow("本局状态")
        Spacer(Modifier.height(8.dp))
        // Decision + villain response
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "你的决策",
                    style = TextStyle(
                        fontFamily = BrandMonoFamily,
                        fontSize = 10.sp,
                        color = c.fgSubtle,
                        letterSpacing = 1.2.sp,
                    ),
                )
                Text(
                    choiceLabel ?: "—",
                    style = TextStyle(
                        fontFamily = BrandDisplayFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        color = c.accent,
                    ),
                )
            }
            if (villainLine != null) {
                Text(
                    villainLine,
                    style = TextStyle(
                        fontFamily = BrandMonoFamily,
                        fontSize = 12.sp,
                        color = c.fgMuted,
                    ),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        BrandDivider()
        Spacer(Modifier.height(10.dp))
        // Equity + odds row
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Eyebrow("胜率")
                Text(
                    equityPct?.let { "${kotlin.math.round(it * 10) / 10}%" } ?: "—",
                    style = TextStyle(
                        fontFamily = BrandMonoFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp,
                        color = c.accent,
                    ),
                )
            }
            if (table.toCall > 0) {
                val odds = kotlin.math.round(table.potOdds * 1000) / 10
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Eyebrow("赔率")
                    Text(
                        "$odds%",
                        style = TextStyle(
                            fontFamily = BrandMonoFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 20.sp,
                            color = c.fg,
                        ),
                    )
                }
            }
        }
        // Snapshot + extras chips
        val chips = buildList {
            if (madeHand != null) add(madeHand to ChipTone.Accent)
            outs?.let { add("Outs ${it.outs}" to ChipTone.Outline) }
            draws.forEach { d -> add(d.tag to ChipTone.Outline) }
            outs?.let { add("Turn ${it.turnPct}% · T+R ${it.turnAndRiverPct}%" to ChipTone.Outline) }
            preflopBaseline?.let { add("RFI 基线: $it" to ChipTone.Outline) }
        }
        if (chips.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                chips.forEach { (label, tone) -> BrandChip(label, tone = tone) }
            }
        }
    }
}

/** Pulls the "你的决策" / "对手跟注 14 chips — 底池变 70" strings out of the
 *  state so DecisionRecapCard stays layout-only. */
private fun choiceAndVillainLine(
    table: HoldemTable,
    userChoice: Pair<Action, Int>?,
): Pair<String?, String?> {
    val (a, amt) = userChoice ?: return null to null
    val choiceLabel = a.label + if (amt > 0) " $amt" else ""
    val heroBet = a == Action.BET || a == Action.RAISE || a == Action.ALL_IN
    val last = if (heroBet) table.history.lastOrNull() else null
    val villain = when (last?.action) {
        Action.FOLD -> "对手弃牌 · 你赢 ${table.pot}"
        Action.CALL -> "对手跟注 ${last.amount} → 底池 ${table.pot}"
        else -> null
    }
    return choiceLabel to villain
}

/**
 * Hand progression timeline — one section per street with pot size, board
 * cards and the per-seat action list. Hero row gets a ★ marker; the current
 * pending slot renders a dashed "待决策" chip.
 */
@Composable
private fun HandProgressionCard(table: HoldemTable, phase: Phase) {
    val c = BrandTheme.colors
    val streets = buildList {
        add(Street.PREFLOP)
        if (table.board.size >= 3) add(Street.FLOP)
        if (table.board.size >= 4) add(Street.TURN)
        if (table.board.size >= 5) add(Street.RIVER)
    }
    val byStreet = streets.associateWith { s -> table.history.filter { it.street == s } }
    BrandSurface {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Eyebrow("牌局进程")
            Text(
                "英雄 ${table.heroPosition.label} · ${table.hero.joinToString("") { it.rank.label }}",
                style = TextStyle(
                    fontFamily = BrandMonoFamily,
                    fontSize = 11.sp,
                    color = c.fgMuted,
                ),
            )
        }
        Spacer(Modifier.height(10.dp))
        streets.forEachIndexed { i, street ->
            StreetProgressionRow(
                street = street,
                current = street == table.street,
                table = table,
                records = byStreet[street].orEmpty(),
                showPending = street == table.street && phase == Phase.DECIDING,
            )
            if (i != streets.lastIndex) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .padding(vertical = 4.dp)
                        .background(c.border.copy(alpha = 0.5f)),
                )
            }
        }
    }
}

@Composable
private fun StreetProgressionRow(
    street: Street,
    current: Boolean,
    table: HoldemTable,
    records: List<xyz.nextalone.cardtrainer.engine.holdem.ActionRecord>,
    showPending: Boolean,
) {
    val c = BrandTheme.colors
    val potAtStreet = records.firstOrNull()?.potBefore ?: table.pot
    val boardSlice: List<xyz.nextalone.cardtrainer.engine.holdem.Card> = when (street) {
        Street.PREFLOP -> emptyList()
        Street.FLOP -> table.board.take(3)
        Street.TURN -> if (table.board.size >= 4) listOf(table.board[3]) else emptyList()
        Street.RIVER -> if (table.board.size >= 5) listOf(table.board[4]) else emptyList()
        Street.SHOWDOWN -> table.board
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Row 1: street label + pot + board (inline)
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                streetLabel(street),
                style = TextStyle(
                    fontFamily = BrandMonoFamily,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (current) c.accent else c.fg,
                    letterSpacing = 1.2.sp,
                ),
            )
            Text(
                "底池 $potAtStreet",
                style = TextStyle(
                    fontFamily = BrandMonoFamily,
                    fontSize = 11.sp,
                    color = c.fgMuted,
                ),
            )
            if (boardSlice.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    boardSlice.forEach { card ->
                        PlayingCardView(
                            rank = card.rank.label,
                            suit = card.suit.symbol,
                            size = CardSize.XS,
                        )
                    }
                }
            }
        }
        // Row 2..N: per-round grid — preflop = 6 equal cells (UTG/MP/CO/BTN/
        // SB/BB), postflop = 2 (BB / hero). Empty cell when the seat didn't
        // act this round. Hero's pending turn renders as a 待决策 chip in
        // its own column on the next row.
        val seatedRecords = assignSeats(street = street, table = table, records = records)
        val rounds = splitIntoRounds(seatedRecords)
        val heroLabel = table.heroPosition.label
        val seatColumns = if (street == Street.PREFLOP) {
            listOf("UTG", "MP", "CO", "BTN", "SB", "BB")
        } else {
            listOf("BB", heroLabel).distinct()
        }
        // Decide where the pending marker goes. If hero hasn't acted in the
        // last round yet, its 待决策 belongs on THAT round's hero cell — no
        // extra row needed. Otherwise open a fresh round so 3-bet re-actions
        // still get their own line.
        val lastRoundHasHero = rounds.lastOrNull()?.any { it.first == heroLabel } == true
        val pendingOnLast = showPending && rounds.isNotEmpty() && !lastRoundHasHero
        val pendingOnNew = showPending && (rounds.isEmpty() || lastRoundHasHero)

        rounds.forEachIndexed { idx, round ->
            val isLast = idx == rounds.lastIndex
            SeatGridRow(
                seats = seatColumns,
                heroLabel = heroLabel,
                byeSeat = round.toMap(),
                pendingSeat = if (isLast && pendingOnLast) heroLabel else null,
            )
        }
        if (pendingOnNew) {
            SeatGridRow(
                seats = seatColumns,
                heroLabel = heroLabel,
                byeSeat = emptyMap(),
                pendingSeat = heroLabel,
            )
        }
    }
}

/** Break seated records into rounds — a new round starts when a seat we've
 *  already seen acts again (e.g., cold-call vs 3-bet response). */
private fun splitIntoRounds(
    seated: List<Pair<String, xyz.nextalone.cardtrainer.engine.holdem.ActionRecord>>,
): List<List<Pair<String, xyz.nextalone.cardtrainer.engine.holdem.ActionRecord>>> {
    if (seated.isEmpty()) return emptyList()
    val rounds = mutableListOf<MutableList<Pair<String, xyz.nextalone.cardtrainer.engine.holdem.ActionRecord>>>()
    var current = mutableListOf<Pair<String, xyz.nextalone.cardtrainer.engine.holdem.ActionRecord>>()
    val seenThisRound = mutableSetOf<String>()
    for (item in seated) {
        val seat = item.first
        if (seat in seenThisRound) {
            rounds.add(current)
            current = mutableListOf()
            seenThisRound.clear()
        }
        current.add(item)
        seenThisRound.add(seat)
    }
    if (current.isNotEmpty()) rounds.add(current)
    return rounds
}

/** Compute the seat columns for a street — preflop uses fixed UTG→BB order,
 *  postflop is heads-up (BB + hero). Empty seats are trimmed so narrow
 *  screens don't waste columns, but the remaining columns keep their
 *  cross-round alignment. */
/**
 * Per-round seat grid — N equal-weight cells, one per seat. Each cell stacks
 * seat label on top and action text below so 6 preflop columns fit in a
 * phone's width without truncating. Empty cell means the seat didn't act
 * this round (or folded earlier). Hero gets a ★ next to the label and a
 * dashed `待决策` chip on the pending row.
 */
@Composable
private fun SeatGridRow(
    seats: List<String>,
    heroLabel: String,
    byeSeat: Map<String, xyz.nextalone.cardtrainer.engine.holdem.ActionRecord>,
    pendingSeat: String?,
) {
    if (seats.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        seats.forEach { seat ->
            SeatGridCell(
                seat = seat,
                isHero = seat == heroLabel,
                record = byeSeat[seat],
                isPending = pendingSeat != null && seat == pendingSeat,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SeatGridCell(
    seat: String,
    isHero: Boolean,
    record: xyz.nextalone.cardtrainer.engine.holdem.ActionRecord?,
    isPending: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = BrandTheme.colors
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                seat,
                style = TextStyle(
                    fontFamily = BrandMonoFamily,
                    fontSize = 11.sp,
                    fontWeight = if (isHero) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isHero) c.accent else c.fgMuted,
                ),
            )
            if (isHero) {
                Text(
                    "★",
                    style = TextStyle(
                        fontFamily = BrandMonoFamily,
                        fontSize = 9.sp,
                        color = c.accent,
                    ),
                )
            }
        }
        when {
            record != null -> {
                val (label, color) = actionLabelAndColor(record, c)
                Text(
                    label,
                    style = TextStyle(
                        fontFamily = BrandBodyFamily,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = color,
                    ),
                    maxLines = 1,
                )
            }
            isPending -> Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, c.accent.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    "待决策",
                    style = TextStyle(
                        fontFamily = BrandBodyFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.accent,
                    ),
                )
            }
            else -> Text(
                "—",
                style = TextStyle(
                    fontFamily = BrandBodyFamily,
                    fontSize = 11.sp,
                    color = c.fgSubtle.copy(alpha = 0.35f),
                ),
            )
        }
    }
}

/** Best-effort villain label for the table strip header.
 *  Scans preflop records to find the last non-fold seat that isn't hero —
 *  that's the opponent still in the hand heads-up. Returns null if nothing
 *  can be inferred (e.g., fresh hand). */
private fun inferVillainLabel(table: HoldemTable): String? {
    val preflop = table.history.filter { it.street == Street.PREFLOP }
    val seated = assignSeats(Street.PREFLOP, table, preflop)
    val heroLabel = table.heroPosition.label
    return seated.lastOrNull { (seat, rec) ->
        seat != heroLabel && rec.action != Action.FOLD && seat != "?"
    }?.first
}

private fun assignSeats(
    street: Street,
    table: HoldemTable,
    records: List<xyz.nextalone.cardtrainer.engine.holdem.ActionRecord>,
): List<Pair<String, xyz.nextalone.cardtrainer.engine.holdem.ActionRecord>> {
    val heroLabel = table.heroPosition.label
    return if (street == Street.PREFLOP) {
        // Preflop order: UTG → MP → CO → BTN → SB → BB (blinds acted with dead chips).
        // Records are actions BEFORE hero + hero's own (if submitted) + villains'
        // responses after hero. We assign positions in preflop order.
        val preflopOrder = listOf(
            xyz.nextalone.cardtrainer.engine.holdem.Position.UTG,
            xyz.nextalone.cardtrainer.engine.holdem.Position.MP,
            xyz.nextalone.cardtrainer.engine.holdem.Position.CO,
            xyz.nextalone.cardtrainer.engine.holdem.Position.BTN,
            xyz.nextalone.cardtrainer.engine.holdem.Position.SB,
            xyz.nextalone.cardtrainer.engine.holdem.Position.BB,
        )
        records.mapIndexed { idx, rec ->
            val seat = preflopOrder.getOrNull(idx)?.label ?: "?"
            seat to rec
        }
    } else {
        // Post-flop 2-player: villain (BB) acts first, hero second. Alternate.
        records.mapIndexed { idx, rec ->
            val seat = if (idx % 2 == 0) "BB" else heroLabel
            seat to rec
        }
    }
}

private fun actionLabelAndColor(
    rec: xyz.nextalone.cardtrainer.engine.holdem.ActionRecord,
    c: xyz.nextalone.cardtrainer.ui.theme.BrandColors,
): Pair<String, Color> = when (rec.action) {
    Action.FOLD -> "弃牌" to c.fgSubtle
    Action.CHECK -> "过牌" to c.fgMuted
    Action.CALL -> "跟注 ${rec.amount}" to c.suitBlueTong
    Action.BET -> "下注 ${rec.amount}" to c.accent
    Action.RAISE -> "加注 ${rec.amount}" to c.accent
    Action.ALL_IN -> "全下 ${rec.amount}" to c.bad
}

@Composable
private fun FeltEyebrow(text: String, color: Color) {
    Text(
        text,
        style = TextStyle(
            fontFamily = BrandMonoFamily,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
            letterSpacing = 1.8.sp,
        ),
    )
}
