@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package xyz.nextalone.cardtrainer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.nextalone.cardtrainer.coach.ChatTurn
import xyz.nextalone.cardtrainer.coach.LlmProviders
import xyz.nextalone.cardtrainer.coach.Prompts
import xyz.nextalone.cardtrainer.engine.holdem.Action
import xyz.nextalone.cardtrainer.engine.holdem.Card as PokerCard
import xyz.nextalone.cardtrainer.engine.holdem.Deck
import xyz.nextalone.cardtrainer.engine.holdem.Position
import xyz.nextalone.cardtrainer.engine.holdem.PreflopChart
import xyz.nextalone.cardtrainer.engine.holdem.Street
import xyz.nextalone.cardtrainer.stats.MultiwayDecisionEvent
import xyz.nextalone.cardtrainer.stats.StatsRepository
import xyz.nextalone.cardtrainer.engine.holdem.multiway.MultiwayEngine
import xyz.nextalone.cardtrainer.engine.holdem.multiway.MultiwayTable
import xyz.nextalone.cardtrainer.engine.holdem.multiway.Seat
import xyz.nextalone.cardtrainer.engine.holdem.multiway.SeatState
import xyz.nextalone.cardtrainer.engine.holdem.multiway.Showdown
import xyz.nextalone.cardtrainer.engine.holdem.multiway.ShowdownOutcome
import xyz.nextalone.cardtrainer.storage.AppSettings
import xyz.nextalone.cardtrainer.ui.components.BrandChip
import xyz.nextalone.cardtrainer.ui.components.BrandDivider
import xyz.nextalone.cardtrainer.ui.components.BrandSurface
import xyz.nextalone.cardtrainer.ui.components.CardSize
import xyz.nextalone.cardtrainer.ui.components.ChipTone
import xyz.nextalone.cardtrainer.ui.components.DeviceMode
import xyz.nextalone.cardtrainer.ui.components.Eyebrow
import xyz.nextalone.cardtrainer.ui.components.FeltSurface
import xyz.nextalone.cardtrainer.ui.components.PlayingCardView
import xyz.nextalone.cardtrainer.ui.components.SeatPip
import xyz.nextalone.cardtrainer.ui.components.StatReadout
import xyz.nextalone.cardtrainer.ui.components.WithDeviceMode
import xyz.nextalone.cardtrainer.ui.theme.BrandBodyFamily
import xyz.nextalone.cardtrainer.ui.theme.BrandMonoFamily
import xyz.nextalone.cardtrainer.ui.theme.BrandTheme
import xyz.nextalone.cardtrainer.util.nowEpochMs
import xyz.nextalone.cardtrainer.util.withRetry

/**
 * Multiway-engine screen — now dressed in the brand primitives so it reads
 * as a sibling of PokerScreen, not a plain-Material3 debug screen. Engine
 * behaviour and the three coach slots are unchanged from Phase 9; this pass
 * only swaps the chrome (shell, felt table, brand chips, playing cards, seat
 * pips, brand surfaces) so visual parity lands before the stats phase.
 */
@Composable
fun MultiwayPokerScreen(settings: AppSettings, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    var handSeed by remember { mutableStateOf(nowEpochMs()) }
    var deck by remember(handSeed) { mutableStateOf(Deck(seed = handSeed)) }
    var table by remember(handSeed) {
        mutableStateOf(
            MultiwayEngine.newHand(
                heroPosition = Position.entries.random(kotlin.random.Random(handSeed)),
                opponents = 3,
                deck = deck,
                rng = kotlin.random.Random(handSeed),
            ),
        )
    }
    var outcome by remember(handSeed) { mutableStateOf<ShowdownOutcome?>(null) }
    var userChoice by remember(handSeed) { mutableStateOf<Pair<Action, Int>?>(null) }

    var situationTurns by remember(handSeed) { mutableStateOf<List<ChatTurn>>(emptyList()) }
    var evaluationTurns by remember(handSeed) { mutableStateOf<List<ChatTurn>>(emptyList()) }
    var recapTurns by remember(handSeed) { mutableStateOf<List<ChatTurn>>(emptyList()) }
    var loadingSituation by remember(handSeed) { mutableStateOf(false) }
    var loadingEvaluation by remember(handSeed) { mutableStateOf(false) }
    var loadingRecap by remember(handSeed) { mutableStateOf(false) }
    var errorSituation by remember(handSeed) { mutableStateOf<String?>(null) }
    var errorEvaluation by remember(handSeed) { mutableStateOf<String?>(null) }
    var errorRecap by remember(handSeed) { mutableStateOf<String?>(null) }

    var situationFor by remember(handSeed) { mutableStateOf<Street?>(null) }
    var recapFor by remember(handSeed) { mutableStateOf<Street?>(null) }

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
                    equityPct = null,
                    preflopBaseline = null,
                    outs = null,
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
                    equityPct = null,
                    preflopBaseline = null,
                    outs = null,
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
                    equityPct = null,
                    preflopBaseline = null,
                    outs = null,
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
        scope.launch { runEvaluation(snapForEval, choice) }
        activeTab = 1
    }

    // Back-fill hand result onto every decision event for this hand once the
    // outcome is known. Guarded by resultRecordedFor so a recompose after the
    // update doesn't rewrite the same events.
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
        val body: @Composable () -> Unit = {
            val maxW = if (isPhone) Modifier.fillMaxWidth() else Modifier.widthIn(max = 860.dp)
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = if (isPhone) 14.dp else 28.dp,
                        vertical = if (isPhone) 12.dp else 20.dp,
                    ),
            ) {
                Column(maxW, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    FeltBlock(table = table, outcome = outcome)
                    HistoryBlock(table = table)
                    if (outcome != null) {
                        OutcomeBlock(outcome!!, table)
                        Button(
                            onClick = ::startNewHand,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("开始下一手") }
                    } else if (table.isHeroTurn) {
                        HeroActionBlock(table = table, onAction = ::submitAction)
                    } else {
                        BrandSurface {
                            Text(
                                "等待其他玩家行动…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = BrandTheme.colors.fgMuted,
                            )
                        }
                    }
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
                    )
                }
            }
        }
        val topRight: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
            FilledTonalButton(onClick = ::startNewHand) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
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
                body = body,
            )
        } else {
            xyz.nextalone.cardtrainer.ui.components.DesktopShell(
                eyebrow = eyebrow,
                title = title,
                windowLabel = "LLM Card Trainer · Multiway",
                onBack = onBack,
                topRight = topRight,
                body = body,
            )
        }
    }
}

@Composable
private fun FeltBlock(table: MultiwayTable, outcome: ShowdownOutcome?) {
    FeltSurface(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "MULTIWAY",
                        style = TextStyle(
                            fontFamily = BrandMonoFamily,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 1.4.sp,
                            color = androidx.compose.ui.graphics.Color(0xFFFFFAED).copy(alpha = 0.65f),
                        ),
                    )
                    Text(
                        streetLabel(table.street),
                        style = TextStyle(
                            fontFamily = BrandBodyFamily,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = androidx.compose.ui.graphics.Color(0xFFFFFAED),
                        ),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BrandChip("底池 ${table.pot}", tone = ChipTone.Felt)
                    if (table.isHeroTurn) {
                        BrandChip("你行动", tone = ChipTone.Accent)
                    } else {
                        val actorLabel = table.seats.getOrNull(table.toActIndex)?.position?.label ?: "—"
                        BrandChip("轮 $actorLabel", tone = ChipTone.Felt)
                    }
                }
            }

            // Board row
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val slots = 5
                val revealed = table.board
                for (i in 0 until slots) {
                    val c = revealed.getOrNull(i)
                    if (c != null) {
                        PlayingCardView(
                            rank = c.rank.label,
                            suit = c.suit.symbol,
                            size = CardSize.Default,
                        )
                    } else {
                        PlayingCardView(
                            rank = "",
                            suit = "",
                            size = CardSize.Default,
                            slot = true,
                        )
                    }
                }
            }

            // Seat row (compact)
            val seatedOrder = table.seats.filterIndexed { idx, seat ->
                seat.isHero || seat.state != SeatState.FOLDED || seat.totalContrib > 0 || idx == table.toActIndex
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                seatedOrder.forEach { seat ->
                    val idx = table.seats.indexOf(seat)
                    val active = idx == table.toActIndex
                    val label = seat.position.label
                    SeatPip(
                        label = label,
                        active = active,
                        folded = seat.state == SeatState.FOLDED,
                        bet = seat.contribThisStreet.takeIf { it > 0 },
                    )
                }
            }

            // Hero hand
            val hero = table.hero
            val heroCards = hero.cards
            Column(
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Eyebrow("你的手牌 · ${hero.position.label}")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (heroCards != null) {
                        heroCards.forEach { card ->
                            PlayingCardView(
                                rank = card.rank.label,
                                suit = card.suit.symbol,
                                size = CardSize.Large,
                            )
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatReadout(label = "栈", value = hero.stack.toString())
                    StatReadout(label = "跟注", value = if (table.heroToCall == 0) "—" else table.heroToCall.toString())
                    StatReadout(label = "已投", value = hero.totalContrib.toString())
                }
            }

            // Showdown reveal of other seats
            if (outcome != null) {
                BrandDivider()
                Eyebrow("所有摊牌")
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    table.seats.forEach { seat ->
                        if (!seat.isLive || seat.cards == null) return@forEach
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                seat.position.label + if (seat.isHero) "(你)" else "",
                                style = MaterialTheme.typography.labelMedium,
                                color = androidx.compose.ui.graphics.Color(0xFFFFFAED),
                                modifier = Modifier.size(60.dp, 20.dp),
                            )
                            seat.cards!!.forEach { card ->
                                PlayingCardView(
                                    rank = card.rank.label,
                                    suit = card.suit.symbol,
                                    size = CardSize.Small,
                                )
                            }
                        }
                    }
                }
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
    }
}

@Composable
private fun HeroActionBlock(table: MultiwayTable, onAction: (Action, Int) -> Unit) {
    val toCall = table.heroToCall
    val pot = table.pot
    val stack = table.hero.stack
    BrandSurface(tone = ChipTone.Accent) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Eyebrow(if (toCall == 0) "行动 · 无需跟注" else "行动 · 跟注 $toCall")
                Text(
                    "${table.hero.position.label} · 栈 $stack",
                    style = MaterialTheme.typography.titleSmall,
                    color = BrandTheme.colors.fg,
                )
            }
            BrandChip("底池 $pot", tone = ChipTone.Outline)
        }
        Spacer(Modifier.height(10.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (toCall == 0) {
                Button(onClick = { onAction(Action.CHECK, 0) }) { Text("过牌") }
                OutlinedButton(onClick = {
                    val to = (pot / 2).coerceAtLeast(table.currentBet + 1).coerceAtMost(stack)
                    onAction(Action.BET, to)
                }) { Text("下注 1/2p") }
                OutlinedButton(onClick = {
                    val to = pot.coerceAtLeast(table.currentBet + 1).coerceAtMost(stack)
                    onAction(Action.BET, to)
                }) { Text("下注 pot") }
            } else {
                OutlinedButton(onClick = { onAction(Action.FOLD, 0) }) { Text("弃牌") }
                Button(onClick = { onAction(Action.CALL, toCall) }) { Text("跟注 $toCall") }
                OutlinedButton(onClick = {
                    val to = (table.currentBet * 3)
                        .coerceAtLeast(table.currentBet + table.lastRaiseSize)
                        .coerceAtMost(table.hero.contribThisStreet + stack)
                    onAction(Action.RAISE, to)
                }) { Text("加注 3x") }
            }
            if (stack > 0) {
                OutlinedButton(onClick = { onAction(Action.ALL_IN, 0) }) { Text("全下") }
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
            0 -> CoachPane(
                turns = situationTurns,
                loading = loadingSituation,
                error = errorSituation,
                emptyHint = "等待轮到你，或本街情境分析加载中…",
            )
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
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
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

private fun streetLabel(street: Street): String = when (street) {
    Street.PREFLOP -> "翻前"
    Street.FLOP -> "翻牌"
    Street.TURN -> "转牌"
    Street.RIVER -> "河牌"
    Street.SHOWDOWN -> "摊牌"
}
