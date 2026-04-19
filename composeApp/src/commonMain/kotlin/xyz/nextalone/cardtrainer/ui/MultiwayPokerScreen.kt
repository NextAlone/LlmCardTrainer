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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
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

/**
 * Multiway-engine screen — MVP. Drives [MultiwayEngine] end-to-end:
 *  - starts a new hand, auto-steps non-hero seats until hero acts,
 *  - renders seat states, board, hero hole cards, and history,
 *  - at showdown, runs [Showdown.run] and displays per-pot awards.
 *
 * No coach / equity / outs integration yet; that lands in a follow-up
 * once the engine baseline is validated.
 */
@Composable
fun MultiwayPokerScreen(settings: AppSettings, onBack: () -> Unit) {
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

    // Single-step the non-hero pipeline. Each state change recomposes and
    // re-runs this effect, which advances exactly one phase: run villain
    // seats, close a street, or compute the showdown.
    LaunchedEffect(table, handSeed) {
        val current = table
        if (MultiwayEngine.isHandOver(current)) {
            if (outcome == null) outcome = Showdown.run(current)
            return@LaunchedEffect
        }
        if (current.isHeroTurn) return@LaunchedEffect
        if (current.isStreetClosed) {
            delay(200)
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
                    IconButton(onClick = {
                        handSeed = nowEpochMs()
                        deck = Deck(seed = handSeed)
                        outcome = null
                    }) {
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
                    onClick = {
                        handSeed = nowEpochMs()
                        deck = Deck(seed = handSeed)
                        outcome = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("开始下一手")
                }
            } else if (table.isHeroTurn) {
                HeroActionPanel(
                    table = table,
                    onAction = { action, amount ->
                        table = MultiwayEngine.applyHeroAction(table, action, amount)
                    },
                )
            } else {
                Text(
                    "等待其他玩家行动…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
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
            LabelValue("轮到", if (table.isHeroTurn) "你" else table.seats.getOrNull(table.toActIndex)?.position?.label ?: "—")
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

private fun streetLabel(street: Street): String = when (street) {
    Street.PREFLOP -> "翻前"
    Street.FLOP -> "翻牌"
    Street.TURN -> "转牌"
    Street.RIVER -> "河牌"
    Street.SHOWDOWN -> "摊牌"
}
