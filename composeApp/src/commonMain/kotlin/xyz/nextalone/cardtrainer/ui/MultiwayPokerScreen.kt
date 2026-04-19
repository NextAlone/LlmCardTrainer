@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
import xyz.nextalone.cardtrainer.engine.holdem.Street
import xyz.nextalone.cardtrainer.engine.holdem.multiway.MultiwayEngine
import xyz.nextalone.cardtrainer.engine.holdem.multiway.MultiwayTable
import xyz.nextalone.cardtrainer.engine.holdem.multiway.Seat
import xyz.nextalone.cardtrainer.engine.holdem.multiway.SeatState
import xyz.nextalone.cardtrainer.engine.holdem.multiway.Showdown
import xyz.nextalone.cardtrainer.engine.holdem.multiway.ShowdownOutcome
import xyz.nextalone.cardtrainer.storage.AppSettings
import xyz.nextalone.cardtrainer.util.nowEpochMs
import xyz.nextalone.cardtrainer.util.withRetry

/**
 * Multiway-engine screen — MVP. Drives [MultiwayEngine] end-to-end and runs
 * three independent coach analyses per street, shown in a Tab panel:
 *  - A 情境：hero 行动前，基于前置位线给推荐；
 *  - B 评分：hero 提交后，评估该选择；
 *  - C 街总结：街闭合后，回顾整桌行动。
 *
 * No coach / equity integration pieces from PokerScreen are reused; the goal
 * is feature parity with the single-villain flow for training value, not
 * code-level convergence. UI polish and stats split land in follow-up phases.
 */
@Composable
fun MultiwayPokerScreen(settings: AppSettings, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    // Engine state
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

    // Three coach slots
    var situationTurns by remember(handSeed) { mutableStateOf<List<ChatTurn>>(emptyList()) }
    var evaluationTurns by remember(handSeed) { mutableStateOf<List<ChatTurn>>(emptyList()) }
    var recapTurns by remember(handSeed) { mutableStateOf<List<ChatTurn>>(emptyList()) }
    var loadingSituation by remember(handSeed) { mutableStateOf(false) }
    var loadingEvaluation by remember(handSeed) { mutableStateOf(false) }
    var loadingRecap by remember(handSeed) { mutableStateOf(false) }
    var errorSituation by remember(handSeed) { mutableStateOf<String?>(null) }
    var errorEvaluation by remember(handSeed) { mutableStateOf<String?>(null) }
    var errorRecap by remember(handSeed) { mutableStateOf<String?>(null) }

    // Trigger gating — each analysis fires at most once per (hand, street).
    var situationFor by remember(handSeed) { mutableStateOf<Street?>(null) }
    var recapFor by remember(handSeed) { mutableStateOf<Street?>(null) }

    // Tab: 0=A situation, 1=B evaluation, 2=C recap
    var activeTab by remember(handSeed) { mutableStateOf(0) }

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

    // Auto-step villain seats / advance streets / resolve showdown.
    // When a street closes (or the hand ends), kick off Recap before moving
    // on so the snapshot captures per-street contribs before they reset.
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

    // Situation trigger: runs once per (hand, street) as soon as it becomes
    // hero's turn on a fresh street. A new street resets situationFor via
    // the handSeed-keyed remember + an equality compare below.
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
        table = MultiwayEngine.applyHeroAction(table, action, amount)
        // Evaluation is computed against the pre-action table snapshot so the
        // prompt reflects the state the user was facing, not the post-action
        // state where pot/toCall have already moved.
        scope.launch { runEvaluation(snapForEval, choice) }
        activeTab = 1
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("多人德扑 (新引擎)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = ::startNewHand) {
                        Icon(Icons.Filled.Refresh, contentDescription = "新牌局")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PotAndStreetCard(table)
            BoardCard(board = table.board)
            SeatsCard(table = table, outcome = outcome)
            HistoryCard(table = table)

            if (outcome != null) {
                OutcomeCard(outcome!!, table)
                Button(
                    onClick = ::startNewHand,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("开始下一手") }
            } else if (table.isHeroTurn) {
                HeroActionPanel(
                    table = table,
                    onAction = ::submitAction,
                )
            } else {
                Text(
                    "等待其他玩家行动…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            CoachTabs(
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

@Composable
private fun PotAndStreetCard(table: MultiwayTable) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LabelValue("街道", streetLabel(table.street))
            LabelValue("底池", table.pot.toString())
            LabelValue(
                "轮到",
                if (table.isHeroTurn) "你"
                else table.seats.getOrNull(table.toActIndex)?.position?.label ?: "—",
            )
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BoardCard(board: List<PokerCard>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("公共牌", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (board.isEmpty()) {
                    Text("（翻前）", style = MaterialTheme.typography.bodyMedium)
                } else {
                    board.forEach { card -> MiniCard(card) }
                }
            }
        }
    }
}

@Composable
private fun SeatsCard(table: MultiwayTable, outcome: ShowdownOutcome?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("座位", style = MaterialTheme.typography.labelMedium)
            table.seats.forEachIndexed { idx, seat ->
                if (seat.state == SeatState.FOLDED && seat.totalContrib == 0 && !seat.isHero) return@forEachIndexed
                SeatRow(
                    seat = seat,
                    isToAct = idx == table.toActIndex,
                    revealCards = outcome != null && seat.isLive,
                )
            }
        }
    }
}

@Composable
private fun SeatRow(seat: Seat, isToAct: Boolean, revealCards: Boolean) {
    val bg = when {
        seat.isHero -> MaterialTheme.colorScheme.secondaryContainer
        isToAct -> MaterialTheme.colorScheme.tertiaryContainer
        seat.state == SeatState.FOLDED -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            seat.position.label + if (seat.isHero) "(你)" else "",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.size(60.dp, 24.dp),
        )
        Text("栈 ${seat.stack}", style = MaterialTheme.typography.bodySmall)
        Text(
            "投 ${seat.totalContrib}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 4.dp),
        )
        val stateLabel = when (seat.state) {
            SeatState.FOLDED -> "弃"
            SeatState.ALL_IN -> "全下"
            SeatState.IN_HAND -> if (isToAct) "▶" else "·"
        }
        Text(stateLabel, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.weight(1f))
        if ((seat.isHero || revealCards) && seat.cards != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                seat.cards!!.forEach { MiniCard(it) }
            }
        }
    }
}

@Composable
private fun MiniCard(card: PokerCard) {
    val color = if (card.isRed) Color(0xFFE53935) else Color(0xFF202020)
    Box(
        modifier = Modifier
            .size(34.dp, 44.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White)
            .border(1.dp, Color(0xFF444444), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(card.label, color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HistoryCard(table: MultiwayTable) {
    if (table.history.isEmpty()) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("行动历史", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            table.history.forEach { rec ->
                val actor = rec.actor?.label ?: "?"
                val amt = if (rec.amount > 0) " ${rec.amount}" else ""
                Text(
                    "${streetLabel(rec.street)} · $actor ${rec.action.label}$amt",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun OutcomeCard(outcome: ShowdownOutcome, table: MultiwayTable) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("本手结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            outcome.awards.forEachIndexed { idx, award ->
                val winnerNames = award.winnerSeats.joinToString("、") {
                    val seat = table.seats[it]
                    seat.position.label + if (seat.isHero) "(你)" else ""
                }.ifBlank { "无人领取" }
                val potLabel = if (idx == 0) "主池" else "边池 $idx"
                Text(
                    "$potLabel ${award.potAmount} → $winnerNames (每人 ${award.perWinner})",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val heroIdx = table.heroIndex
            val heroWon = outcome.awards.any { heroIdx in it.winnerSeats }
            Text(
                if (heroWon) "你赢了 ✓" else "你未赢得池",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun HeroActionPanel(table: MultiwayTable, onAction: (Action, Int) -> Unit) {
    val toCall = table.heroToCall
    val pot = table.pot
    val stack = table.hero.stack
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (toCall == 0) "你的回合（无需跟注）" else "你的回合（跟注 $toCall）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
}

@Composable
private fun CoachTabs(
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            TabRow(selectedTabIndex = activeTab) {
                Tab(
                    selected = activeTab == 0,
                    onClick = { onTab(0) },
                    text = { Text("A 情境") },
                )
                Tab(
                    selected = activeTab == 1,
                    onClick = { onTab(1) },
                    text = { Text("B 评分") },
                )
                Tab(
                    selected = activeTab == 2,
                    onClick = { onTab(2) },
                    text = { Text("C 街总结") },
                )
            }
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
}

@Composable
private fun CoachPane(
    turns: List<ChatTurn>,
    loading: Boolean,
    error: String?,
    emptyHint: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val assistant = turns.lastOrNull { it.role == ChatTurn.Role.ASSISTANT }?.content
        when {
            error != null -> Text(
                "⚠ $error",
                color = MaterialTheme.colorScheme.error,
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
                Text("分析中…", style = MaterialTheme.typography.bodySmall)
            }
            assistant != null -> Text(assistant, style = MaterialTheme.typography.bodyMedium)
            else -> Text(
                emptyHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun streetLabel(street: Street): String = when (street) {
    Street.PREFLOP -> "翻前"
    Street.FLOP -> "翻牌"
    Street.TURN -> "转牌"
    Street.RIVER -> "河牌"
    Street.SHOWDOWN -> "摊牌"
}
