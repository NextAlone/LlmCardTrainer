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

    var situationTurns by remember(handSeed) { mutableStateOf<List<ChatTurn>>(emptyList()) }
    var evaluationTurns by remember(handSeed) { mutableStateOf<List<ChatTurn>>(emptyList()) }
    var recapTurns by remember(handSeed) { mutableStateOf<List<ChatTurn>>(emptyList()) }
    var loadingSituation by remember(handSeed) { mutableStateOf(false) }
    var loadingEvaluation by remember(handSeed) { mutableStateOf(false) }
    var loadingRecap by remember(handSeed) { mutableStateOf(false) }
    var errorSituation by remember(handSeed) { mutableStateOf<String?>(null) }
    var errorEvaluation by remember(handSeed) { mutableStateOf<String?>(null) }
    var errorRecap by remember(handSeed) { mutableStateOf<String?>(null) }

    // Per-street gating so each coach slot fires at most once.
    var situationFor by remember(handSeed) { mutableStateOf<Street?>(null) }
    var recapFor by remember(handSeed) { mutableStateOf<Street?>(null) }
    // The street whose A-slot has been unlocked for display. A runs in the
    // background before submit, but the user doesn't see it until they've
    // submitted their own action for the same street.
    var revealedFor by remember(handSeed) { mutableStateOf<Street?>(null) }

    var activeTab by remember(handSeed) { mutableStateOf(0) }

    val statsRepo = remember { StatsRepository(settings) }
    var resultRecordedFor by remember { mutableStateOf<Long?>(null) }

    suspend fun runSituation(forTable: MultiwayTable) {
        val cfg = settings.activeConfig()
        if (cfg.apiKey.isBlank()) {
            errorSituation = "请先在『设置』中填写 ${cfg.kind.label} 的 API Key。"
            return
        }
        loadingSituation = true
        errorSituation = null
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
            situationTurns = seed + ChatTurn(ChatTurn.Role.ASSISTANT, reply)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            errorSituation = t.message ?: t::class.simpleName ?: "未知错误"
        } finally {
            coach.close()
            loadingSituation = false
        }
    }

    suspend fun runEvaluation(forTable: MultiwayTable, choice: Pair<Action, Int>) {
        val cfg = settings.activeConfig()
        if (cfg.apiKey.isBlank()) {
            errorEvaluation = "请先在『设置』中填写 ${cfg.kind.label} 的 API Key。"
            return
        }
        loadingEvaluation = true
        errorEvaluation = null
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
                    userChoice = choice,
                    mode = Prompts.MultiwayAnalysisMode.EVALUATION,
                ),
            ),
        )
        val coach = LlmProviders.create(cfg)
        try {
            val reply = withRetry {
                coach.coach(systemPrompt = Prompts.HOLDEM_SYSTEM, messages = seed)
            }
            evaluationTurns = seed + ChatTurn(ChatTurn.Role.ASSISTANT, reply)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            errorEvaluation = t.message ?: t::class.simpleName ?: "未知错误"
        } finally {
            coach.close()
            loadingEvaluation = false
        }
    }

    suspend fun runRecap(snapshot: MultiwayTable) {
        val cfg = settings.activeConfig()
        if (cfg.apiKey.isBlank()) {
            errorRecap = "请先在『设置』中填写 ${cfg.kind.label} 的 API Key。"
            return
        }
        loadingRecap = true
        errorRecap = null
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
            recapTurns = seed + ChatTurn(ChatTurn.Role.ASSISTANT, reply)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            errorRecap = t.message ?: t::class.simpleName ?: "未知错误"
        } finally {
            coach.close()
            loadingRecap = false
        }
    }

    LaunchedEffect(table, handSeed) {
        val current = table
        if (MultiwayEngine.isHandOver(current)) {
            if (outcome == null) {
                outcome = Showdown.run(current)
                if (recapFor != current.street) {
                    recapFor = current.street
                    val snap = current
                    scope.launch { runRecap(snap) }
                }
            }
            return@LaunchedEffect
        }
        if (current.isHeroTurn) return@LaunchedEffect
        if (current.isStreetClosed) {
            delay(200)
            if (recapFor != current.street) {
                recapFor = current.street
                val snap = current
                scope.launch { runRecap(snap) }
            }
            if (current.street == Street.RIVER) {
                val atShowdown = current.copy(street = Street.SHOWDOWN)
                table = atShowdown
                outcome = Showdown.run(atShowdown)
                return@LaunchedEffect
            }
            table = MultiwayEngine.advanceStreet(current, deck)
            return@LaunchedEffect
        }
        delay(300)
        table = MultiwayEngine.stepUntilHero(current, rng = kotlin.random.Random.Default)
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
        scope.launch { runEvaluation(snapForEval, choice) }
        activeTab = 1
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
                onSubmit = ::submitAction,
                onNewHand = ::startNewHand,
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
                    )
                    SeatsStrip(table = table)
                    if (outcome != null) {
                        OutcomeBlock(outcome!!, table)
                    }
                    HistoryBlock(table = table)
                    CoachTabsBlock(
                        activeTab = activeTab,
                        onTab = { activeTab = it },
                        situationTurns = situationTurns,
                        evaluationTurns = evaluationTurns,
                        recapTurns = recapTurns,
                        loadingSituation = loadingSituation,
                        loadingEvaluation = loadingEvaluation,
                        loadingRecap = loadingRecap,
                        errorSituation = errorSituation,
                        errorEvaluation = errorEvaluation,
                        errorRecap = errorRecap,
                        situationRevealed = revealedFor == table.street || outcome != null,
                    )
                    // Spacer so the last card isn't hidden under the pinned bar.
                    Spacer(Modifier.height(72.dp))
                }
            }
        }
        val topRight: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
            BrandChip(
                "${configuredOpponents} 对手",
                tone = ChipTone.Outline,
            )
            if (loadingSituation) BrandChip("AI 预载", tone = ChipTone.Accent)
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
        // Stat row
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FeltStat("底池", table.pot.toString(), ivory)
            FeltStat("跟注", if (table.heroToCall == 0) "—" else table.heroToCall.toString(), ivory)
            FeltStat("栈", table.hero.stack.toString(), ivory)
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

@Composable
private fun SeatsStrip(table: MultiwayTable) {
    val sorted = table.seats.filter {
        it.isHero || it.state != SeatState.FOLDED || it.totalContrib > 0
    }
    if (sorted.isEmpty()) return
    BrandSurface {
        Eyebrow("座位")
        Spacer(Modifier.height(8.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            sorted.forEach { seat ->
                val idx = table.seats.indexOf(seat)
                SeatPip(
                    label = seat.position.label + if (seat.isHero) "·你" else "",
                    active = idx == table.toActIndex,
                    folded = seat.state == SeatState.FOLDED,
                    bet = seat.contribThisStreet.takeIf { it > 0 },
                )
            }
        }
    }
}

@Composable
private fun HistoryBlock(table: MultiwayTable) {
    if (table.history.isEmpty()) return
    BrandSurface {
        Eyebrow("行动历史")
        Spacer(Modifier.height(6.dp))
        table.history.forEach { rec ->
            val actor = rec.actor?.label ?: "?"
            val amt = if (rec.amount > 0) " ${rec.amount}" else ""
            Text(
                "${streetLabel(rec.street)} · $actor ${rec.action.label}$amt",
                style = MaterialTheme.typography.bodySmall,
                color = BrandTheme.colors.fgMuted,
            )
        }
    }
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
    situationTurns: List<ChatTurn>,
    evaluationTurns: List<ChatTurn>,
    recapTurns: List<ChatTurn>,
    loadingSituation: Boolean,
    loadingEvaluation: Boolean,
    loadingRecap: Boolean,
    errorSituation: String?,
    errorEvaluation: String?,
    errorRecap: String?,
    situationRevealed: Boolean,
) {
    BrandSurface {
        Eyebrow("AI 教练 · 三视角")
        Spacer(Modifier.height(10.dp))
        TabRow(selectedTabIndex = activeTab) {
            Tab(selected = activeTab == 0, onClick = { onTab(0) }, text = { Text("A 情境") })
            Tab(selected = activeTab == 1, onClick = { onTab(1) }, text = { Text("B 评分") })
            Tab(selected = activeTab == 2, onClick = { onTab(2) }, text = { Text("C 街总结") })
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
                emptyHint = "提交本街动作后，将评估你的选择。",
            )
            else -> CoachPane(
                turns = recapTurns,
                loading = loadingRecap,
                error = errorRecap,
                emptyHint = "街结束后给出全桌回顾。",
            )
        }
    }
}

@Composable
private fun CoachPane(
    turns: List<ChatTurn>,
    loading: Boolean,
    error: String?,
    emptyHint: String,
) {
    val assistant = turns.lastOrNull { it.role == ChatTurn.Role.ASSISTANT }?.content
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
        assistant != null -> Text(
            assistant,
            style = MaterialTheme.typography.bodyMedium,
            color = BrandTheme.colors.fg,
        )
        else -> Text(
            emptyHint,
            style = MaterialTheme.typography.bodySmall,
            color = BrandTheme.colors.fgMuted,
        )
    }
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
    onSubmit: (Action, Int) -> Unit,
    onNewHand: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        if (outcome == null && table.isHeroTurn) {
            MultiwayActionSheet(table = table, onSubmit = onSubmit)
        }
        PinnedActionBar {
            if (outcome != null) {
                Spacer(Modifier.weight(1f))
                Button(onClick = onNewHand) { Text("开始下一手") }
            } else {
                if (!table.isHeroTurn) {
                    Text(
                        "等待 ${table.seats.getOrNull(table.toActIndex)?.position?.label ?: "…"} 行动",
                        color = BrandTheme.colors.fgMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onNewHand) { Text("新牌局") }
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
