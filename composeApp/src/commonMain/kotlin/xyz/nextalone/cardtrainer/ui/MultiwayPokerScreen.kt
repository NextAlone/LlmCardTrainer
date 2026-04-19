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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.nextalone.cardtrainer.coach.ChatTurn
import xyz.nextalone.cardtrainer.coach.LlmProviders
import xyz.nextalone.cardtrainer.coach.Prompts
import xyz.nextalone.cardtrainer.engine.holdem.Action
import xyz.nextalone.cardtrainer.engine.holdem.Card as PokerCard
import xyz.nextalone.cardtrainer.engine.holdem.Deck
import xyz.nextalone.cardtrainer.engine.holdem.Draws
import xyz.nextalone.cardtrainer.engine.holdem.Equity
import xyz.nextalone.cardtrainer.engine.holdem.HandEvaluator
import xyz.nextalone.cardtrainer.engine.holdem.Outs
import xyz.nextalone.cardtrainer.engine.holdem.OutsReport
import xyz.nextalone.cardtrainer.engine.holdem.Position
import xyz.nextalone.cardtrainer.engine.holdem.PreflopChart
import xyz.nextalone.cardtrainer.engine.holdem.Street
import xyz.nextalone.cardtrainer.engine.holdem.multiway.MultiwayEngine
import xyz.nextalone.cardtrainer.engine.holdem.multiway.MultiwayTable
import xyz.nextalone.cardtrainer.engine.holdem.multiway.Seat
import xyz.nextalone.cardtrainer.engine.holdem.multiway.SeatState
import xyz.nextalone.cardtrainer.engine.holdem.multiway.Showdown
import xyz.nextalone.cardtrainer.engine.holdem.multiway.ShowdownOutcome
import xyz.nextalone.cardtrainer.stats.MultiwayDecisionEvent
import xyz.nextalone.cardtrainer.stats.StatsRepository
import xyz.nextalone.cardtrainer.storage.AppSettings
import xyz.nextalone.cardtrainer.ui.components.BrandChip
import xyz.nextalone.cardtrainer.ui.components.BrandDivider
import xyz.nextalone.cardtrainer.ui.components.BrandSurface
import xyz.nextalone.cardtrainer.ui.components.CardSize
import xyz.nextalone.cardtrainer.ui.components.ChipTone
import xyz.nextalone.cardtrainer.ui.components.DeviceMode
import xyz.nextalone.cardtrainer.ui.components.Eyebrow
import xyz.nextalone.cardtrainer.ui.components.FeltSurface
import xyz.nextalone.cardtrainer.ui.components.PinnedActionBar
import xyz.nextalone.cardtrainer.ui.components.PlayingCardView
import xyz.nextalone.cardtrainer.ui.components.SeatPip
import xyz.nextalone.cardtrainer.ui.components.WithDeviceMode
import xyz.nextalone.cardtrainer.ui.theme.BrandDisplayFamily
import xyz.nextalone.cardtrainer.ui.theme.BrandMonoFamily
import xyz.nextalone.cardtrainer.ui.theme.BrandTheme
import xyz.nextalone.cardtrainer.util.nowEpochMs
import xyz.nextalone.cardtrainer.util.withRetry

@Composable
fun MultiwayPokerScreen(settings: AppSettings, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    var handSeed by remember { mutableStateOf(nowEpochMs()) }
    var deck by remember(handSeed) { mutableStateOf(Deck(seed = handSeed)) }
    val configuredOpponents = settings.multiwayOpponents
    var table by remember(handSeed) {
        mutableStateOf(
            MultiwayEngine.newHand(
                heroPosition = Position.entries.random(kotlin.random.Random(handSeed)),
                opponents = configuredOpponents,
                deck = deck,
                rng = kotlin.random.Random(handSeed),
            ),
        )
    }
    var outcome by remember(handSeed) { mutableStateOf<ShowdownOutcome?>(null) }
    var userChoice by remember(handSeed) { mutableStateOf<Pair<Action, Int>?>(null) }

    var equityPct by remember(handSeed) { mutableStateOf<Double?>(null) }
    var outs by remember(handSeed) { mutableStateOf<OutsReport?>(null) }

    // Per-street caches for A/B/C so switching streets reveals the cached
    // content instead of re-running the coach. Hand-scoped; wiped on new
    // hand via remember(handSeed).
    var situationByStreet by remember(handSeed) {
        mutableStateOf<Map<Street, List<ChatTurn>>>(emptyMap())
    }
    var evaluationByStreet by remember(handSeed) {
        mutableStateOf<Map<Street, List<ChatTurn>>>(emptyMap())
    }
    var recapByStreet by remember(handSeed) {
        mutableStateOf<Map<Street, List<ChatTurn>>>(emptyMap())
    }
    var situationLoadingFor by remember(handSeed) { mutableStateOf<Set<Street>>(emptySet()) }
    var evaluationLoadingFor by remember(handSeed) { mutableStateOf<Set<Street>>(emptySet()) }
    var recapLoadingFor by remember(handSeed) { mutableStateOf<Set<Street>>(emptySet()) }
    var situationErrorByStreet by remember(handSeed) {
        mutableStateOf<Map<Street, String>>(emptyMap())
    }
    var evaluationErrorByStreet by remember(handSeed) {
        mutableStateOf<Map<Street, String>>(emptyMap())
    }
    var recapErrorByStreet by remember(handSeed) {
        mutableStateOf<Map<Street, String>>(emptyMap())
    }

    var handRecapTurns by remember(handSeed) { mutableStateOf<List<ChatTurn>>(emptyList()) }
    var loadingHandRecap by remember(handSeed) { mutableStateOf(false) }
    var errorHandRecap by remember(handSeed) { mutableStateOf<String?>(null) }
    var handRecapFired by remember(handSeed) { mutableStateOf(false) }

    // The street the user is currently viewing in the coach panes. Tracks
    // table.street automatically so fresh streets auto-focus; clicking a
    // score badge overrides to let the user time-travel back to an earlier
    // street on the same hand.
    var selectedStreet by remember(handSeed) { mutableStateOf(Street.PREFLOP) }
    LaunchedEffect(table.street, handSeed) {
        selectedStreet = table.street
    }

    // Per-street gating so each coach slot fires at most once.
    var situationFor by remember(handSeed) { mutableStateOf<Street?>(null) }
    var recapFor by remember(handSeed) { mutableStateOf<Street?>(null) }
    // Streets whose batched evaluation has already been sent.
    var evaluationFor by remember(handSeed) { mutableStateOf<Set<Street>>(emptySet()) }
    // Every hero submission on a given street, captured at submit-time so we
    // can replay them to the coach when the street closes.
    var heroSubmissionsByStreet by remember(handSeed) {
        mutableStateOf<Map<Street, List<Prompts.HeroStreetAction>>>(emptyMap())
    }
    // The street whose A-slot has been unlocked for display. A runs in the
    // background before submit, but the user doesn't see it until they've
    // submitted their own action for the same street.
    var revealedFor by remember(handSeed) { mutableStateOf<Street?>(null) }

    var activeTab by remember(handSeed) { mutableStateOf(0) }

    // Per-street B-scores. If the hero acts multiple times on the same
    // street (3-bet wars, check-raise, etc.) every submission contributes a
    // separate score; the bottom badge shows the mean and the count so a
    // single bad decision doesn't get hidden by one good one.
    var scoreByStreet by remember(handSeed) { mutableStateOf<Map<Street, List<Double>>>(emptyMap()) }

    val statsRepo = remember { StatsRepository(settings) }
    var resultRecordedFor by remember { mutableStateOf<Long?>(null) }

    suspend fun runSituation(forTable: MultiwayTable) {
        val street = forTable.street
        val cfg = settings.activeConfig()
        if (cfg.apiKey.isBlank()) {
            situationErrorByStreet = situationErrorByStreet +
                (street to "请先在『设置』中填写 ${cfg.kind.label} 的 API Key。")
            return
        }
        situationLoadingFor = situationLoadingFor + street
        situationErrorByStreet = situationErrorByStreet - street
        val seed = listOf(
            ChatTurn(
                ChatTurn.Role.USER,
                Prompts.holdemUser(
                    table = forTable,
                    equityPct = equityPct,
                    preflopBaseline = null,
                    outs = outs,
                    madeHand = if (forTable.board.size >= 3)
                        HandEvaluator.evaluate((forTable.hero.cards ?: emptyList()) + forTable.board).category
                    else null,
                    draws = if (forTable.board.size in 3..4)
                        Draws.detect(forTable.hero.cards ?: emptyList(), forTable.board)
                    else emptyList(),
                    userChoice = null,
                    mode = Prompts.MultiwayAnalysisMode.SITUATION,
                ),
            ),
        )
        val coach = LlmProviders.create(cfg)
        try {
            val reply = withRetry {
                coach.coach(systemPrompt = Prompts.HOLDEM_SYSTEM, messages = seed)
            }
            situationByStreet = situationByStreet +
                (street to (seed + ChatTurn(ChatTurn.Role.ASSISTANT, reply)))
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            situationErrorByStreet = situationErrorByStreet +
                (street to (t.message ?: t::class.simpleName ?: "未知错误"))
        } finally {
            coach.close()
            situationLoadingFor = situationLoadingFor - street
        }
    }

    suspend fun runStreetEvaluation(
        snapshot: MultiwayTable,
        submissions: List<Prompts.HeroStreetAction>,
        forStreet: Street,
    ) {
        val cfg = settings.activeConfig()
        if (cfg.apiKey.isBlank()) {
            evaluationErrorByStreet = evaluationErrorByStreet +
                (forStreet to "请先在『设置』中填写 ${cfg.kind.label} 的 API Key。")
            return
        }
        evaluationLoadingFor = evaluationLoadingFor + forStreet
        evaluationErrorByStreet = evaluationErrorByStreet - forStreet
        val seed = listOf(
            ChatTurn(
                ChatTurn.Role.USER,
                Prompts.holdemUserEvaluateStreet(snapshot, submissions),
            ),
        )
        val coach = LlmProviders.create(cfg)
        try {
            val reply = withRetry {
                coach.coach(systemPrompt = Prompts.HOLDEM_SYSTEM, messages = seed)
            }
            evaluationByStreet = evaluationByStreet +
                (forStreet to (seed + ChatTurn(ChatTurn.Role.ASSISTANT, reply)))
            val parsed = parseMultiScores(reply, submissions.size)
            if (parsed.isNotEmpty()) {
                scoreByStreet = scoreByStreet + (forStreet to parsed)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            evaluationErrorByStreet = evaluationErrorByStreet +
                (forStreet to (t.message ?: t::class.simpleName ?: "未知错误"))
        } finally {
            coach.close()
            evaluationLoadingFor = evaluationLoadingFor - forStreet
        }
    }

    suspend fun runHandRecap(snapshot: MultiwayTable) {
        val cfg = settings.activeConfig()
        if (cfg.apiKey.isBlank()) {
            errorHandRecap = "请先在『设置』中填写 ${cfg.kind.label} 的 API Key。"
            return
        }
        loadingHandRecap = true
        errorHandRecap = null
        val seed = listOf(
            ChatTurn(
                ChatTurn.Role.USER,
                Prompts.holdemUser(
                    table = snapshot,
                    equityPct = equityPct,
                    preflopBaseline = null,
                    outs = outs,
                    userChoice = userChoice,
                    mode = Prompts.MultiwayAnalysisMode.HAND_RECAP,
                ),
            ),
        )
        val coach = LlmProviders.create(cfg)
        try {
            val reply = withRetry {
                coach.coach(systemPrompt = Prompts.HOLDEM_SYSTEM, messages = seed)
            }
            handRecapTurns = seed + ChatTurn(ChatTurn.Role.ASSISTANT, reply)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            errorHandRecap = t.message ?: t::class.simpleName ?: "未知错误"
        } finally {
            coach.close()
            loadingHandRecap = false
        }
    }

    suspend fun runRecap(snapshot: MultiwayTable) {
        val street = snapshot.street
        val cfg = settings.activeConfig()
        if (cfg.apiKey.isBlank()) {
            recapErrorByStreet = recapErrorByStreet +
                (street to "请先在『设置』中填写 ${cfg.kind.label} 的 API Key。")
            return
        }
        recapLoadingFor = recapLoadingFor + street
        recapErrorByStreet = recapErrorByStreet - street
        val seed = listOf(
            ChatTurn(
                ChatTurn.Role.USER,
                Prompts.holdemUser(
                    table = snapshot,
                    equityPct = equityPct,
                    preflopBaseline = null,
                    outs = outs,
                    userChoice = userChoice,
                    mode = Prompts.MultiwayAnalysisMode.STREET_RECAP,
                ),
            ),
        )
        val coach = LlmProviders.create(cfg)
        try {
            val reply = withRetry {
                coach.coach(systemPrompt = Prompts.HOLDEM_SYSTEM, messages = seed)
            }
            recapByStreet = recapByStreet +
                (street to (seed + ChatTurn(ChatTurn.Role.ASSISTANT, reply)))
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            recapErrorByStreet = recapErrorByStreet +
                (street to (t.message ?: t::class.simpleName ?: "未知错误"))
        } finally {
            coach.close()
            recapLoadingFor = recapLoadingFor - street
        }
    }

    // Auto-pilot — villain seats step until hero acts or the street closes.
    // Street transitions are NOT automatic: once the street is closed the
    // screen waits for the user to press '发下一街' so they have time to read
    // the A/B/C coach panes without them getting overwritten by the next
    // street's fresh analyses.
    //
    // On street-close we also fire the batched hero evaluation: every
    // hero submission captured during the street is replayed to the coach
    // in a single request so the model can produce per-decision scores.
    LaunchedEffect(table, handSeed) {
        val current = table
        if (MultiwayEngine.isHandOver(current)) {
            if (outcome == null) {
                outcome = Showdown.run(current)
            }
            if (recapFor != current.street) {
                recapFor = current.street
                val snap = current
                scope.launch { runRecap(snap) }
            }
            val submissions = heroSubmissionsByStreet[current.street].orEmpty()
            if (submissions.isNotEmpty() && current.street !in evaluationFor) {
                evaluationFor = evaluationFor + current.street
                val snap = current
                val st = current.street
                scope.launch { runStreetEvaluation(snap, submissions, st) }
            }
            return@LaunchedEffect
        }
        if (current.isHeroTurn) return@LaunchedEffect
        if (current.isStreetClosed) {
            if (recapFor != current.street) {
                recapFor = current.street
                val snap = current
                scope.launch { runRecap(snap) }
            }
            val submissions = heroSubmissionsByStreet[current.street].orEmpty()
            if (submissions.isNotEmpty() && current.street !in evaluationFor) {
                evaluationFor = evaluationFor + current.street
                val snap = current
                val st = current.street
                scope.launch { runStreetEvaluation(snap, submissions, st) }
            }
            return@LaunchedEffect
        }
        delay(300)
        table = MultiwayEngine.stepUntilHero(current, rng = kotlin.random.Random.Default)
    }

    fun advanceStreet() {
        val current = table
        if (!current.isStreetClosed || MultiwayEngine.isHandOver(current)) return
        if (current.street == Street.RIVER) {
            val atShowdown = current.copy(street = Street.SHOWDOWN)
            table = atShowdown
            outcome = Showdown.run(atShowdown)
        } else {
            table = MultiwayEngine.advanceStreet(current, deck)
        }
    }

    // Equity + outs recompute on street / board / hero change. Multiway Equity
    // factors in the live opponent count.
    LaunchedEffect(table.street, table.board, handSeed) {
        val heroCards = table.hero.cards ?: return@LaunchedEffect
        val liveOpps = table.seats.count { !it.isHero && it.isLive }.coerceAtLeast(1)
        equityPct = withContext(Dispatchers.Default) {
            Equity.monteCarlo(
                hero = heroCards,
                board = table.board,
                opponents = liveOpps,
                trials = 1200,
            ).combinedPct
        }
        outs = if (table.board.size in 3..4) {
            withContext(Dispatchers.Default) { Outs.count(heroCards, table.board) }
        } else null
    }

    // Background Situation runs on hero's first turn of the street, but the
    // result stays hidden in the A tab until the hero submits.
    LaunchedEffect(table.isHeroTurn, table.street, handSeed) {
        if (table.isHeroTurn && situationFor != table.street) {
            situationFor = table.street
            runSituation(table)
        }
    }

    fun startNewHand() {
        scope.coroutineContext.cancelChildren()
        handSeed = nowEpochMs()
        deck = Deck(seed = handSeed)
        outcome = null
    }

    fun submitAction(action: Action, amount: Int) {
        val choice = action to amount
        userChoice = choice
        val snapForEval = table
        val potBefore = snapForEval.pot
        val toCallBefore = snapForEval.heroToCall
        val currentBetBefore = snapForEval.currentBet
        val liveOpps = snapForEval.seats.count { !it.isHero && it.isLive }
        val handLabel = snapForEval.hero.cards?.let { PreflopChart.encode(it) } ?: ""
        val streetName = snapForEval.street.name
        val boardSize = snapForEval.board.size
        val priorHistoryLine = snapForEval.history
            .filter { it.street == snapForEval.street }
            .joinToString(" · ") { rec ->
                val actor = rec.actor?.label ?: "?"
                val amt = if (rec.amount > 0) " ${rec.amount}" else ""
                "$actor ${rec.action.label}$amt"
            }
        val submission = Prompts.HeroStreetAction(
            potBefore = potBefore,
            toCall = toCallBefore,
            currentBet = currentBetBefore,
            stack = snapForEval.hero.stack,
            priorHistoryLine = priorHistoryLine,
            action = action,
            amount = amount,
        )
        heroSubmissionsByStreet = heroSubmissionsByStreet +
            (snapForEval.street to (heroSubmissionsByStreet[snapForEval.street].orEmpty() + submission))
        table = MultiwayEngine.applyHeroAction(snapForEval, action, amount)
        statsRepo.recordMultiway(
            MultiwayDecisionEvent(
                timestampMs = nowEpochMs(),
                handId = handSeed,
                position = snapForEval.hero.position.label,
                street = streetName,
                handLabel = handLabel,
                boardSize = boardSize,
                potBefore = potBefore,
                toCall = toCallBefore,
                currentBet = currentBetBefore,
                liveOpponents = liveOpps,
                action = action.name,
                amount = amount,
                potAfter = table.pot,
                heroStackAfter = table.hero.stack,
            ),
        )
        revealedFor = snapForEval.street
        activeTab = 1
    }

    // Fire the whole-hand recap exactly once, right after the hand settles.
    // Distinct from runRecap (per-street) so tabs can show both side-by-side.
    LaunchedEffect(outcome, handSeed) {
        outcome ?: return@LaunchedEffect
        if (!handRecapFired) {
            handRecapFired = true
            val snap = table
            scope.launch { runHandRecap(snap) }
        }
    }

    LaunchedEffect(outcome, handSeed) {
        val o = outcome ?: return@LaunchedEffect
        if (resultRecordedFor == handSeed) return@LaunchedEffect
        val heroIdx = table.heroIndex
        val heroWon = o.awards.any { heroIdx in it.winnerSeats }
        val heroLastAction = table.history
            .lastOrNull { it.actor == table.hero.position }
            ?.action
        val resolution = when {
            heroLastAction == Action.FOLD -> "FOLD"
            table.seats.count { it.isLive } <= 1 -> "UNCONTESTED"
            else -> "SHOWDOWN"
        }
        statsRepo.updateMultiwayHandResult(handSeed, heroWon, resolution)
        resultRecordedFor = handSeed
    }

    WithDeviceMode { mode ->
        val isPhone = mode == DeviceMode.Phone

        val bottomBar: @Composable () -> Unit = {
            MultiwayBottomBar(
                table = table,
                outcome = outcome,
                scoreByStreet = scoreByStreet,
                selectedStreet = selectedStreet,
                onSelectStreet = { selectedStreet = it },
                onSubmit = ::submitAction,
                onNewHand = ::startNewHand,
                onAdvanceStreet = ::advanceStreet,
            )
        }

        val body: @Composable () -> Unit = {
            val maxW = if (isPhone) Modifier.fillMaxWidth() else Modifier.widthIn(max = 900.dp)
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = if (isPhone) 14.dp else 24.dp,
                        vertical = if (isPhone) 12.dp else 18.dp,
                    ),
            ) {
                Column(maxW, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TableStrip(
                        table = table,
                        equityPct = equityPct,
                        outs = outs,
                        // Equity / made-hand / outs unlock only once the
                        // street is fully resolved (all villains done) or
                        // the hand is over. Unlocking on hero's own submit
                        // let the user peek before opponents responded,
                        // which masked how the action line actually shaped
                        // their equity.
                        revealed = table.isStreetClosed || outcome != null,
                    )
                    if (outcome != null) {
                        OutcomeBlock(outcome!!, table)
                    }
                    HandProgressionCard(
                        table = table,
                        outcome = outcome,
                    )
                    CoachTabsBlock(
                        activeTab = activeTab,
                        onTab = { activeTab = it },
                        selectedStreet = selectedStreet,
                        currentStreet = table.street,
                        situationTurns = situationByStreet[selectedStreet].orEmpty(),
                        evaluationTurns = evaluationByStreet[selectedStreet].orEmpty(),
                        recapTurns = recapByStreet[selectedStreet].orEmpty(),
                        handRecapTurns = handRecapTurns,
                        loadingSituation = selectedStreet in situationLoadingFor,
                        loadingEvaluation = selectedStreet in evaluationLoadingFor,
                        loadingRecap = selectedStreet in recapLoadingFor,
                        loadingHandRecap = loadingHandRecap,
                        errorSituation = situationErrorByStreet[selectedStreet],
                        errorEvaluation = evaluationErrorByStreet[selectedStreet],
                        errorRecap = recapErrorByStreet[selectedStreet],
                        errorHandRecap = errorHandRecap,
                        situationRevealed = (revealedFor != null && selectedStreet == revealedFor) ||
                            (selectedStreet != table.street) ||
                            outcome != null,
                        handSettled = outcome != null,
                    )
                    // Spacer so the last card isn't hidden under the pinned bar.
                    Spacer(Modifier.height(72.dp))
                }
            }
        }
        val topRight: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
            if (situationLoadingFor.isNotEmpty()) BrandChip("AI 预载", tone = ChipTone.Accent)
            // Always-available escape hatch. Bottom bar only mounts a
            // '新牌局' button in the hand-settled state, so the top bar
            // covers bailing mid-hand.
            FilledTonalButton(onClick = ::startNewHand) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text("新牌局")
            }
        }
        val eyebrow = "HOLD'EM MULTIWAY · ${table.hero.position.label} · ${streetLabel(table.street)}"
        val title = "多人训练（实验引擎）"
        if (isPhone) {
            xyz.nextalone.cardtrainer.ui.components.PhoneShell(
                eyebrow = eyebrow,
                title = title,
                onBack = onBack,
                topRight = topRight,
                bottomBar = bottomBar,
                body = body,
            )
        } else {
            xyz.nextalone.cardtrainer.ui.components.DesktopShell(
                eyebrow = eyebrow,
                title = title,
                windowLabel = "LLM Card Trainer · Multiway",
                onBack = onBack,
                topRight = topRight,
                bottomBar = bottomBar,
                body = body,
            )
        }
    }
}

/**
 * Dense felt strip: board + hero + stat row (pot / to-call / equity / pot
 * odds / made hand / outs). Modeled on PokerScreen.TableCardStrip so the
 * multiway screen feels like its sibling.
 */
@Composable
private fun TableStrip(
    table: MultiwayTable,
    equityPct: Double?,
    outs: OutsReport?,
    revealed: Boolean,
) {
    val c = BrandTheme.colors
    val ivory = Color(0xFFFFFAED)
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
    val heroCards = table.hero.cards ?: emptyList()
    val madeHand = if (table.board.size >= 3 && heroCards.size == 2)
        HandEvaluator.evaluate(heroCards + table.board).category.displayName
    else null
    val potOddsPct = if (table.heroToCall > 0)
        kotlin.math.round(table.heroToCall.toDouble() / (table.pot + table.heroToCall) * 1000) / 10
    else null

    Column(
        Modifier
            .fillMaxWidth()
            .clip(outer)
            .background(ink)
            .border(1.dp, Color.White.copy(alpha = 0.10f), outer)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
            val liveOpps = table.seats.count { !it.isHero && it.isLive }
            BrandChip("${table.hero.position.label} · $liveOpps 对手", tone = ChipTone.Felt)
        }
        // Board + hero in a felt panel
        Row(
            Modifier
                .fillMaxWidth()
                .clip(inner)
                .background(
                    Brush.verticalGradient(0f to c.table, 1f to c.tableDeep),
                )
                .border(1.dp, Color.White.copy(alpha = 0.08f), inner)
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
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
            Box(
                Modifier
                    .size(width = 1.dp, height = 66.dp)
                    .background(Color.White.copy(alpha = 0.12f)),
            )
            Column(
                Modifier.weight(2f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FeltEyebrow("HERO", c.accentBright)
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    heroCards.forEach {
                        PlayingCardView(
                            rank = it.rank.label,
                            suit = it.suit.symbol,
                            size = CardSize.Small,
                        )
                    }
                }
            }
        }
        // Stat row. Revealed-only stats (胜率 / 赔率 / 牌型 / outs) stay
        // hidden until the hero has submitted on this street — otherwise the
        // 'analyse before deciding' loop degenerates into reading the
        // Monte-Carlo number off the table.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FeltStat("底池", table.pot.toString(), ivory)
            FeltStat("跟注", if (table.heroToCall == 0) "—" else table.heroToCall.toString(), ivory)
            FeltStat("栈", table.hero.stack.toString(), ivory)
            if (revealed) {
                if (equityPct != null) {
                    val r = kotlin.math.round(equityPct * 10) / 10
                    FeltStat("胜率", "$r%", c.accentBright, big = true)
                }
                if (potOddsPct != null) {
                    FeltStat("赔率阈值", "$potOddsPct%", ivory)
                }
                if (madeHand != null) {
                    FeltStat("现牌型", madeHand, ivory)
                }
                if (outs != null) {
                    FeltStat(
                        "Outs",
                        "${outs.outs} (${kotlin.math.round(outs.turnAndRiverPct * 10) / 10}%)",
                        ivory,
                    )
                }
            } else {
                FeltStat("胜率", "—", ivory)
                FeltStat("牌型", "提交后揭晓", ivory)
            }
        }
    }
}

@Composable
private fun FeltEyebrow(text: String, color: Color) {
    Text(
        text,
        style = TextStyle(
            fontFamily = BrandMonoFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
            letterSpacing = 1.6.sp,
        ),
    )
}

@Composable
private fun FeltStat(label: String, value: String, valueColor: Color, big: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = TextStyle(
                fontFamily = BrandMonoFamily,
                fontSize = 9.5.sp,
                color = Color.White.copy(alpha = 0.55f),
                letterSpacing = 1.4.sp,
            ),
        )
        Text(
            value,
            style = TextStyle(
                fontFamily = BrandMonoFamily,
                fontSize = if (big) 20.sp else 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = valueColor,
            ),
        )
    }
}

/**
 * Compact contribution strip. Column order matches the progression grid
 * below (preflop action order UTG→BB) and each cell uses the same weight(1f)
 * layout, so the position labels line up with the action cells directly
 * beneath them. '累计投入' sits as an eyebrow above the row.
 */
@Composable
private fun ContributionStrip(table: MultiwayTable) {
    val c = BrandTheme.colors
    val order = listOf(
        Position.UTG, Position.MP, Position.CO, Position.BTN, Position.SB, Position.BB,
    )
    val columns = order.mapNotNull { pos ->
        val seat = table.seats.firstOrNull {
            it.position == pos && (it.cards != null || it.isHero)
        } ?: return@mapNotNull null
        pos to seat
    }
    if (columns.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "累计投入",
            style = TextStyle(
                fontFamily = BrandMonoFamily,
                fontSize = 10.sp,
                color = c.fgSubtle,
                letterSpacing = 1.2.sp,
            ),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            columns.forEach { (_, seat) ->
                val idx = table.seats.indexOf(seat)
                val active = idx == table.toActIndex
                val isFolded = seat.state == SeatState.FOLDED
                val isAllIn = seat.state == SeatState.ALL_IN
                val posColor = when {
                    seat.isHero -> c.accent
                    active -> c.accent
                    isFolded -> c.fgSubtle
                    else -> c.fgMuted
                }
                val chipColor = when {
                    isAllIn -> c.bad
                    seat.totalContrib > 0 -> c.fg
                    else -> c.fgSubtle
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            if (isFolded) "—" else seat.position.label,
                            style = TextStyle(
                                fontFamily = BrandMonoFamily,
                                fontSize = 11.sp,
                                fontWeight = if (seat.isHero || active) FontWeight.SemiBold else FontWeight.Medium,
                                color = posColor,
                            ),
                        )
                        if (seat.isHero) {
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
                    Text(
                        seat.totalContrib.toString(),
                        style = TextStyle(
                            fontFamily = BrandMonoFamily,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = chipColor,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * Multiway analogue of PokerScreen.HandProgressionCard. Same visual idiom —
 * one row per street, sub-rows per action round, every seat gets its own
 * column — but driven off [ActionRecord.actor] so multi-seat lines like
 * "UTG raise / MP fold / CO cold-call / BTN fold / SB fold / BB call" render
 * in one grid instead of a flat list.
 */
@Composable
private fun HandProgressionCard(
    table: MultiwayTable,
    outcome: ShowdownOutcome?,
) {
    val c = BrandTheme.colors
    val streets = buildList {
        add(Street.PREFLOP)
        if (table.board.size >= 3) add(Street.FLOP)
        if (table.board.size >= 4) add(Street.TURN)
        if (table.board.size >= 5) add(Street.RIVER)
    }
    val byStreet = streets.associateWith { s -> table.history.filter { it.street == s } }
    val handOver = outcome != null

    BrandSurface {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Eyebrow("牌局进程")
            val heroHand = table.hero.cards?.joinToString("") { it.rank.label } ?: ""
            Text(
                "英雄 ${table.hero.position.label} · $heroHand",
                style = TextStyle(
                    fontFamily = BrandMonoFamily,
                    fontSize = 11.sp,
                    color = c.fgMuted,
                ),
            )
        }
        Spacer(Modifier.height(8.dp))
        ContributionStrip(table = table)
        Spacer(Modifier.height(8.dp))
        BrandDivider()
        Spacer(Modifier.height(4.dp))
        streets.forEachIndexed { i, street ->
            MultiwayStreetRow(
                street = street,
                current = street == table.street,
                table = table,
                records = byStreet[street].orEmpty(),
                showPending = street == table.street && !handOver && table.isHeroTurn,
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
private fun MultiwayStreetRow(
    street: Street,
    current: Boolean,
    table: MultiwayTable,
    records: List<xyz.nextalone.cardtrainer.engine.holdem.ActionRecord>,
    showPending: Boolean,
) {
    val c = BrandTheme.colors
    val potAtStreet = records.firstOrNull()?.potBefore ?: table.pot
    val boardSlice: List<PokerCard> = when (street) {
        Street.PREFLOP -> emptyList()
        Street.FLOP -> table.board.take(3)
        Street.TURN -> if (table.board.size >= 4) listOf(table.board[3]) else emptyList()
        Street.RIVER -> if (table.board.size >= 5) listOf(table.board[4]) else emptyList()
        Street.SHOWDOWN -> table.board
    }
    // Unified column order across all streets (preflop UTG→BB) so the
    // ContributionStrip header aligns with every street's grid cells below.
    // Action order inside each row still reads left-to-right as the
    // engine recorded it, but visual columns stay fixed.
    val seatColumns: List<Position> = listOf(
        Position.UTG, Position.MP, Position.CO, Position.BTN, Position.SB, Position.BB,
    ).filter { pos ->
        table.seats.any { it.position == pos && (it.cards != null || it.isHero) }
    }
    val heroPos = table.hero.position
    val seated: List<Pair<Position, xyz.nextalone.cardtrainer.engine.holdem.ActionRecord>> =
        records.mapNotNull { rec ->
            val actor = rec.actor ?: return@mapNotNull null
            actor to rec
        }
    val rounds = splitIntoRoundsByActor(seated)
    val lastRoundHasHero = rounds.lastOrNull()?.any { it.first == heroPos } == true
    val pendingOnLast = showPending && rounds.isNotEmpty() && !lastRoundHasHero
    val pendingOnNew = showPending && (rounds.isEmpty() || lastRoundHasHero)

    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
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
        rounds.forEachIndexed { idx, round ->
            val isLast = idx == rounds.lastIndex
            MultiwaySeatGridRow(
                seats = seatColumns,
                heroPos = heroPos,
                records = round.toMap(),
                pendingSeat = if (isLast && pendingOnLast) heroPos else null,
            )
        }
        if (pendingOnNew) {
            MultiwaySeatGridRow(
                seats = seatColumns,
                heroPos = heroPos,
                records = emptyMap(),
                pendingSeat = heroPos,
            )
        }
    }
}

private fun splitIntoRoundsByActor(
    seated: List<Pair<Position, xyz.nextalone.cardtrainer.engine.holdem.ActionRecord>>,
): List<List<Pair<Position, xyz.nextalone.cardtrainer.engine.holdem.ActionRecord>>> {
    if (seated.isEmpty()) return emptyList()
    val rounds = mutableListOf<MutableList<Pair<Position, xyz.nextalone.cardtrainer.engine.holdem.ActionRecord>>>()
    var current = mutableListOf<Pair<Position, xyz.nextalone.cardtrainer.engine.holdem.ActionRecord>>()
    val seen = mutableSetOf<Position>()
    for (item in seated) {
        if (item.first in seen) {
            rounds.add(current)
            current = mutableListOf()
            seen.clear()
        }
        current.add(item)
        seen.add(item.first)
    }
    if (current.isNotEmpty()) rounds.add(current)
    return rounds
}

@Composable
private fun MultiwaySeatGridRow(
    seats: List<Position>,
    heroPos: Position,
    records: Map<Position, xyz.nextalone.cardtrainer.engine.holdem.ActionRecord>,
    pendingSeat: Position?,
) {
    if (seats.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        seats.forEach { pos ->
            MultiwaySeatGridCell(
                pos = pos,
                isHero = pos == heroPos,
                record = records[pos],
                isPending = pendingSeat != null && pos == pendingSeat,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MultiwaySeatGridCell(
    pos: Position,
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
                pos.label,
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
                val (label, color) = multiwayActionLabelAndColor(record, c)
                Text(
                    label,
                    style = TextStyle(
                        fontFamily = xyz.nextalone.cardtrainer.ui.theme.BrandBodyFamily,
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
                        fontFamily = xyz.nextalone.cardtrainer.ui.theme.BrandBodyFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.accent,
                    ),
                )
            }
            else -> Text(
                "—",
                style = TextStyle(
                    fontFamily = xyz.nextalone.cardtrainer.ui.theme.BrandBodyFamily,
                    fontSize = 11.sp,
                    color = c.fgSubtle.copy(alpha = 0.35f),
                ),
            )
        }
    }
}

private fun multiwayActionLabelAndColor(
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
private fun OutcomeBlock(outcome: ShowdownOutcome, table: MultiwayTable) {
    val heroIdx = table.heroIndex
    val heroWon = outcome.awards.any { heroIdx in it.winnerSeats }
    BrandSurface(tone = if (heroWon) ChipTone.Accent else ChipTone.Default) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Eyebrow("本手结果")
                Text(
                    if (heroWon) "你赢了" else "未赢得池",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = BrandTheme.colors.fg,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BrandChip("底池 ${table.pot}", tone = ChipTone.Outline)
                BrandChip("池数 ${outcome.awards.size}", tone = ChipTone.Outline)
            }
        }
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            outcome.awards.forEachIndexed { idx, award ->
                val winnerNames = award.winnerSeats.joinToString("、") {
                    val seat = table.seats[it]
                    seat.position.label + if (seat.isHero) "(你)" else ""
                }.ifBlank { "无人领取" }
                val potLabel = if (idx == 0) "主池" else "边池 $idx"
                Text(
                    "$potLabel ${award.potAmount} → $winnerNames (每人 ${award.perWinner})",
                    style = MaterialTheme.typography.bodySmall,
                    color = BrandTheme.colors.fg,
                )
            }
        }
        // Reveal all remaining seats' hands
        val revealed = table.seats.filter { it.isLive && it.cards != null }
        if (revealed.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            BrandDivider()
            Spacer(Modifier.height(6.dp))
            Eyebrow("所有摊牌")
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                revealed.forEach { seat ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            seat.position.label + if (seat.isHero) "(你)" else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = BrandTheme.colors.fg,
                            modifier = Modifier.width(56.dp),
                        )
                        seat.cards!!.forEach { card ->
                            PlayingCardView(
                                rank = card.rank.label,
                                suit = card.suit.symbol,
                                size = CardSize.XS,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoachTabsBlock(
    activeTab: Int,
    onTab: (Int) -> Unit,
    selectedStreet: Street,
    currentStreet: Street,
    situationTurns: List<ChatTurn>,
    evaluationTurns: List<ChatTurn>,
    recapTurns: List<ChatTurn>,
    handRecapTurns: List<ChatTurn>,
    loadingSituation: Boolean,
    loadingEvaluation: Boolean,
    loadingRecap: Boolean,
    loadingHandRecap: Boolean,
    errorSituation: String?,
    errorEvaluation: String?,
    errorRecap: String?,
    errorHandRecap: String?,
    situationRevealed: Boolean,
    handSettled: Boolean,
) {
    BrandSurface {
        val eyebrowLabel = if (selectedStreet != currentStreet) {
            "AI 教练 · ${streetLabel(selectedStreet)}（回看）"
        } else {
            "AI 教练 · ${streetLabel(selectedStreet)}"
        }
        Eyebrow(eyebrowLabel)
        Spacer(Modifier.height(10.dp))
        TabRow(selectedTabIndex = activeTab) {
            Tab(selected = activeTab == 0, onClick = { onTab(0) }, text = { Text("A 情境") })
            Tab(selected = activeTab == 1, onClick = { onTab(1) }, text = { Text("B 评分") })
            Tab(selected = activeTab == 2, onClick = { onTab(2) }, text = { Text("C 街总结") })
            Tab(selected = activeTab == 3, onClick = { onTab(3) }, text = { Text("D 整手") })
        }
        Spacer(Modifier.height(10.dp))
        when (activeTab) {
            0 -> if (!situationRevealed) {
                Text(
                    if (loadingSituation) "AI 情境已开始预载 · 提交本街动作后揭晓。"
                    else "提交本街动作后揭晓 AI 情境分析。",
                    style = MaterialTheme.typography.bodySmall,
                    color = BrandTheme.colors.fgMuted,
                )
            } else {
                CoachPane(
                    turns = situationTurns,
                    loading = loadingSituation,
                    error = errorSituation,
                    emptyHint = "本街情境加载中…",
                )
            }
            1 -> CoachPane(
                turns = evaluationTurns,
                loading = loadingEvaluation,
                error = errorEvaluation,
                emptyHint = "本街结束时一次性给每次决策打分。",
                stripScore = false,
            )
            2 -> CoachPane(
                turns = recapTurns,
                loading = loadingRecap,
                error = errorRecap,
                emptyHint = "每街结束给全桌回顾。",
            )
            else -> if (!handSettled) {
                Text(
                    "牌局结束后给整手总结（含 showdown 与关键转折点）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = BrandTheme.colors.fgMuted,
                )
            } else {
                CoachPane(
                    turns = handRecapTurns,
                    loading = loadingHandRecap,
                    error = errorHandRecap,
                    emptyHint = "整手总结加载中…",
                )
            }
        }
    }
}

@Composable
private fun CoachPane(
    turns: List<ChatTurn>,
    loading: Boolean,
    error: String?,
    emptyHint: String,
    stripScore: Boolean = false,
) {
    val raw = turns.lastOrNull { it.role == ChatTurn.Role.ASSISTANT }?.content
    val assistant = if (raw != null && stripScore) stripLeadingScore(raw) else raw
    when {
        error != null -> Text(
            "⚠ $error",
            color = BrandTheme.colors.bad,
            style = MaterialTheme.typography.bodySmall,
        )
        loading -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("分析中…", style = MaterialTheme.typography.bodySmall, color = BrandTheme.colors.fgMuted)
        }
        assistant != null -> AiMarkdown(assistant)
        else -> Text(
            emptyHint,
            style = MaterialTheme.typography.bodySmall,
            color = BrandTheme.colors.fgMuted,
        )
    }
}

// Legacy single-score pattern, kept for older tab-B replies produced before
// the street-batched evaluation. The new prompt requires numbered scores
// (【评分 N：X.X / 5】), which [multiwayNumberedScorePattern] captures.
private val multiwayScorePattern = Regex("""【评分[:：]\s*([0-9]+(?:\.[0-9]+)?)\s*/\s*5】""")
private fun stripLeadingScore(text: String): String =
    text.replaceFirst(multiwayScorePattern, "").trimStart('\n', ' ').trimEnd()

/**
 * Parses `【评分 1：X.X / 5】` .. `【评分 N：X.X / 5】` out of a reply. Falls
 * back to the single [multiwayScorePattern] if the model ignored the
 * numbered format, and finally returns an empty list if nothing matched.
 * Truncates to [expected] so one badge per submission.
 */
private val multiwayNumberedScorePattern =
    Regex("""【评分\s*\d+\s*[:：]\s*([0-9]+(?:\.[0-9]+)?)\s*/\s*5】""")

fun parseMultiScores(reply: String, expected: Int): List<Double> {
    val numbered = multiwayNumberedScorePattern.findAll(reply)
        .mapNotNull {
            it.groupValues.getOrNull(1)?.toDoubleOrNull()?.coerceIn(0.0, 5.0)
        }
        .toList()
    if (numbered.isNotEmpty()) return numbered.take(expected)
    val single = multiwayScorePattern.find(reply)
        ?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.coerceIn(0.0, 5.0)
    return if (single != null) listOf(single) else emptyList()
}

/**
 * Bottom chrome. On hero's turn an action sheet with defensive buttons +
 * sizing chips sits above a pinned action bar ("新牌局"). When the hand is
 * resolved we just show a prominent "开始下一手". Otherwise the bar carries
 * a pinned "新牌局" as a quick escape hatch.
 */
@Composable
private fun MultiwayBottomBar(
    table: MultiwayTable,
    outcome: ShowdownOutcome?,
    scoreByStreet: Map<Street, List<Double>>,
    selectedStreet: Street,
    onSelectStreet: (Street) -> Unit,
    onSubmit: (Action, Int) -> Unit,
    onNewHand: () -> Unit,
    onAdvanceStreet: () -> Unit,
) {
    val handOver = outcome != null
    val streetClosed = !handOver && table.isStreetClosed
    val heroTurn = !handOver && !streetClosed && table.isHeroTurn
    Column(Modifier.fillMaxWidth()) {
        MultiwayScoreRow(
            scoreByStreet = scoreByStreet,
            current = table.street,
            selected = selectedStreet,
            onSelect = onSelectStreet,
        )
        // Single bottom surface that switches mode by state:
        //  - heroTurn: show ActionSheet only, no extra bar.
        //  - streetClosed: full-width '发下一街' / '摊牌' button.
        //  - handOver: full-width '开始下一手' button.
        //  - waiting on villain: a subtle status line.
        when {
            heroTurn -> MultiwayActionSheet(table = table, onSubmit = onSubmit)
            streetClosed -> PinnedActionBar {
                Text(
                    "本街结束 · 查看分析后继续",
                    color = BrandTheme.colors.fgMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.weight(1f))
                Button(onClick = onAdvanceStreet) {
                    Text(if (table.street == Street.RIVER) "摊牌" else "发下一街")
                }
            }
            handOver -> PinnedActionBar {
                Spacer(Modifier.weight(1f))
                Button(onClick = onNewHand) { Text("开始下一手") }
            }
            else -> PinnedActionBar {
                Text(
                    "等待 ${table.seats.getOrNull(table.toActIndex)?.position?.label ?: "…"} 行动…",
                    color = BrandTheme.colors.fgMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MultiwayActionSheet(
    table: MultiwayTable,
    onSubmit: (Action, Int) -> Unit,
) {
    val c = BrandTheme.colors
    val toCall = table.heroToCall
    val pot = table.pot
    val stack = table.hero.stack
    val isPreflop = table.board.isEmpty()
    val contribThisStreet = table.hero.contribThisStreet

    data class Preset(val action: Action, val amount: Int, val label: String)

    val defensive = buildList {
        if (toCall == 0) {
            add(Preset(Action.CHECK, 0, "过牌"))
        } else {
            add(Preset(Action.FOLD, 0, "弃牌"))
            add(Preset(Action.CALL, toCall, "跟注 $toCall"))
        }
        if (stack > 0) add(Preset(Action.ALL_IN, 0, "全下 $stack"))
    }
    val sizings = buildList<Preset> {
        val minRaiseTo = table.currentBet + table.lastRaiseSize.coerceAtLeast(2)
        if (toCall == 0) {
            if (isPreflop) {
                // 2.5bb / 3bb / 4bb
                add(Preset(Action.BET, 5.coerceAtMost(stack), "2.5bb"))
                add(Preset(Action.BET, 6.coerceAtMost(stack), "3bb"))
                add(Preset(Action.BET, 8.coerceAtMost(stack), "4bb"))
            } else {
                add(Preset(Action.BET, (pot / 3).coerceAtLeast(1).coerceAtMost(stack), "1/3p"))
                add(Preset(Action.BET, (pot / 2).coerceAtLeast(1).coerceAtMost(stack), "1/2p"))
                add(Preset(Action.BET, (pot * 2 / 3).coerceAtLeast(1).coerceAtMost(stack), "2/3p"))
                add(Preset(Action.BET, pot.coerceAtLeast(1).coerceAtMost(stack), "pot"))
            }
        } else {
            val contribPlusStack = contribThisStreet + stack
            add(
                Preset(
                    Action.RAISE,
                    (table.currentBet * 2).coerceAtLeast(minRaiseTo).coerceAtMost(contribPlusStack),
                    "2x",
                ),
            )
            add(
                Preset(
                    Action.RAISE,
                    ((table.currentBet * 5 + 1) / 2).coerceAtLeast(minRaiseTo).coerceAtMost(contribPlusStack),
                    "2.5x",
                ),
            )
            add(
                Preset(
                    Action.RAISE,
                    (table.currentBet * 3).coerceAtLeast(minRaiseTo).coerceAtMost(contribPlusStack),
                    "3x",
                ),
            )
            add(
                Preset(
                    Action.RAISE,
                    ((pot + toCall) + toCall).coerceAtLeast(minRaiseTo).coerceAtMost(contribPlusStack),
                    "pot",
                ),
            )
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(c.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            defensive.forEach { p ->
                SheetButton(
                    label = p.label,
                    selected = false,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val amt = if (p.action == Action.ALL_IN) 0 else p.amount
                        onSubmit(p.action, amt)
                    },
                )
            }
        }
        if (sizings.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    if (toCall == 0) "下注" else "加注",
                    modifier = Modifier.width(36.dp),
                    style = TextStyle(
                        fontFamily = BrandDisplayFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = c.fgMuted,
                    ),
                )
                sizings.forEach { p ->
                    SheetChip(
                        label = p.label,
                        selected = false,
                        modifier = Modifier.weight(1f),
                        onClick = { onSubmit(p.action, p.amount) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = BrandTheme.colors
    val shape = RoundedCornerShape(10.dp)
    val bg = if (selected) {
        Brush.verticalGradient(0f to c.accentBright, 1f to c.accent)
    } else {
        SolidColor(c.fg.copy(alpha = 0.06f))
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

@Composable
private fun SheetChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = BrandTheme.colors
    val shape = RoundedCornerShape(999.dp)
    val bg = if (selected) c.accent.copy(alpha = 0.18f) else c.fg.copy(alpha = 0.06f)
    val borderColor = if (selected) c.accent else c.border
    val labelColor = if (selected) c.fg else c.fgMuted
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
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = labelColor,
            ),
            maxLines = 1,
        )
    }
}

private fun streetLabel(street: Street): String = when (street) {
    Street.PREFLOP -> "翻前"
    Street.FLOP -> "翻牌"
    Street.TURN -> "转牌"
    Street.RIVER -> "河牌"
    Street.SHOWDOWN -> "摊牌"
}

/**
 * Four street-scoped B-score badges pinned above the action sheet. Badge
 * colour follows the single-villain PokerScoreBadge tint ladder; the street
 * currently being played gets a thicker border so the user can see where
 * the next score will land. Empty slots render as '—'.
 */
@Composable
private fun MultiwayScoreRow(
    scoreByStreet: Map<Street, List<Double>>,
    current: Street,
    selected: Street,
    onSelect: (Street) -> Unit,
) {
    val streets = listOf(Street.PREFLOP, Street.FLOP, Street.TURN, Street.RIVER)
    Row(
        Modifier
            .fillMaxWidth()
            .background(BrandTheme.colors.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        streets.forEach { s ->
            MultiwayScoreBadge(
                label = streetLabel(s),
                scores = scoreByStreet[s].orEmpty(),
                highlighted = s == current,
                isSelected = s == selected,
                onClick = { onSelect(s) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MultiwayScoreBadge(
    label: String,
    scores: List<Double>,
    highlighted: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = BrandTheme.colors
    val avg = scores.takeIf { it.isNotEmpty() }?.average()
    val tint = when {
        avg == null -> c.fgSubtle
        avg >= 4.0 -> c.good
        avg >= 3.0 -> c.accent
        avg >= 2.0 -> c.warn
        else -> c.bad
    }
    val shape = RoundedCornerShape(999.dp)
    val borderColor = when {
        isSelected -> c.accentBright
        highlighted -> c.accent.copy(alpha = 0.6f)
        else -> tint.copy(alpha = 0.5f)
    }
    val borderWidth = when {
        isSelected -> 2.dp
        highlighted -> 1.5.dp
        else -> 1.dp
    }
    Row(
        modifier
            .clip(shape)
            .background(tint.copy(alpha = if (avg != null) 0.12f else 0.04f))
            .border(width = borderWidth, color = borderColor, shape = shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            label,
            style = TextStyle(
                fontFamily = BrandMonoFamily,
                fontSize = 10.sp,
                color = c.fgMuted,
                letterSpacing = 1.2.sp,
            ),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            avg?.let { (kotlin.math.round(it * 10) / 10).toString() } ?: "—",
            style = TextStyle(
                fontFamily = BrandMonoFamily,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = tint,
            ),
        )
        if (avg != null && scores.size > 1) {
            Text(
                "×${scores.size}",
                modifier = Modifier.padding(start = 4.dp),
                style = TextStyle(
                    fontFamily = BrandMonoFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.fgMuted,
                ),
            )
        }
    }
}
