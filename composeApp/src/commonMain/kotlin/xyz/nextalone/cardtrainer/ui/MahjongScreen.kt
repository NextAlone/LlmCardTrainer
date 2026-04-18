@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package xyz.nextalone.cardtrainer.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
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
import xyz.nextalone.cardtrainer.coach.LlmProviders
import xyz.nextalone.cardtrainer.coach.Prompts
import xyz.nextalone.cardtrainer.engine.mahjong.DingQue
import xyz.nextalone.cardtrainer.engine.mahjong.HandCheck
import xyz.nextalone.cardtrainer.engine.mahjong.HandType
import xyz.nextalone.cardtrainer.engine.mahjong.Safety
import xyz.nextalone.cardtrainer.engine.mahjong.SichuanTrainer
import xyz.nextalone.cardtrainer.engine.mahjong.Suit
import xyz.nextalone.cardtrainer.engine.mahjong.Tile
import xyz.nextalone.cardtrainer.engine.mahjong.UkeIre
import xyz.nextalone.cardtrainer.storage.AppSettings
import xyz.nextalone.cardtrainer.storage.MahjongSession
import xyz.nextalone.cardtrainer.storage.loadMahjongSession
import xyz.nextalone.cardtrainer.storage.saveMahjongSession
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch

private typealias MjStep = MahjongSession.Step

@Composable
fun MahjongScreen(settings: AppSettings, onBack: () -> Unit) {
    // Restore prior session if any.
    val savedSession = remember { settings.loadMahjongSession() }
    var trainer by remember {
        mutableStateOf(
            SichuanTrainer().also { t -> savedSession?.let { t.restore(it.snapshot) } },
        )
    }
    var step by remember { mutableStateOf(savedSession?.step ?: MjStep.NOT_DEALT) }
    var pendingQue by remember { mutableStateOf(savedSession?.pendingQue ?: Suit.WAN) }
    var hand by remember { mutableStateOf(trainer.hand.toList()) }
    var advice by remember { mutableStateOf(savedSession?.advice) }
    var loading by remember { mutableStateOf(false) }
    var showGlossary by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refreshHand() {
        hand = trainer.hand.toList()
    }

    fun persist() {
        settings.saveMahjongSession(
            MahjongSession(
                step = step,
                pendingQue = pendingQue,
                snapshot = trainer.snapshot(),
                advice = advice,
            ),
        )
    }

    LaunchedEffect(step, pendingQue, hand, advice) { persist() }

    fun askCoach() {
        val cfg = settings.activeConfig()
        if (cfg.apiKey.isBlank()) {
            advice = "请先在『设置』中填写 ${cfg.kind.label} 的 API Key。"
            return
        }
        loading = true
        scope.launch {
            val coach = LlmProviders.create(cfg)
            try {
                val suggestions = if (hand.size == 14) trainer.rankDiscards() else emptyList()
                val liveWaits = UkeIre.waitingWithCounts(
                    hand = if (hand.size == 14 && suggestions.isNotEmpty())
                        hand.toMutableList().also { it.remove(suggestions.first().tile) }
                    else hand,
                    visible = trainer.discards.toList(),
                    missing = trainer.missing,
                )
                val candidates = suggestions.map { it.tile }
                val safety = Safety.rank(
                    candidates = candidates.ifEmpty { hand },
                    ownDiscards = trainer.discards.toList(),
                    opponentDiscards = emptyList(),
                )
                val typeReport = if (hand.size in listOf(2, 5, 8, 11, 14) && HandCheck.isWinning(hand, trainer.missing)) {
                    HandType.classify(hand)
                } else null
                advice = coach.coach(
                    systemPrompt = Prompts.MAHJONG_SYSTEM,
                    userPrompt = Prompts.mahjongUser(
                        hand = hand,
                        missing = trainer.missing!!,
                        discards = trainer.discards.toList(),
                        suggestions = suggestions,
                        wallRemaining = trainer.wallRemaining(),
                        liveWaits = liveWaits,
                        safety = safety,
                        handType = typeReport,
                    ),
                )
            } catch (t: Throwable) {
                advice = "请求失败：${t.message}"
            } finally {
                coach.close()
                loading = false
            }
        }
    }

    fun resetGame() {
        trainer = SichuanTrainer()
        hand = emptyList()
        advice = null
        step = MjStep.NOT_DEALT
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("四川麻将训练") },
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
            // Pin AI 分析 + 重新开局 once a hand is in play; they no longer scroll
            // out of reach when the AI response is long.
            if (step == MjStep.PLAYING) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = ::askCoach,
                            enabled = !loading,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("思考中…")
                            } else {
                                Text("AI 教练分析")
                            }
                        }
                        OutlinedButton(
                            onClick = ::resetGame,
                            modifier = Modifier.weight(1f),
                        ) { Text("重新开局") }
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
            when (step) {
                MjStep.NOT_DEALT -> {
                    Text(
                        "血战到底 108 张（万/条/筒，各 4 张）。点击发牌，引擎会给出 定缺 建议。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = {
                            trainer = SichuanTrainer()
                            trainer.dealInitial()
                            refreshHand()
                            pendingQue = DingQue.recommend(trainer.hand.toList()).first().suit
                            step = MjStep.CHOOSING_QUE
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("发 13 张") }
                }

                MjStep.CHOOSING_QUE -> {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            Text("已发 13 张手牌", fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            HandBySuit(hand = hand, onDiscard = {})
                        }
                    }
                    val advisories = remember(hand) { DingQue.recommend(hand) }
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "引擎定缺推荐（按弃后向听数排序）",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(6.dp))
                            advisories.forEach {
                                Text("缺${it.suit.cn}：保留分 ${it.score}（越低越适合缺），该门 ${it.countInSuit} 张")
                            }
                        }
                    }
                    Text("选择定缺：")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Suit.entries.forEach { s ->
                            FilterChip(
                                selected = pendingQue == s,
                                onClick = { pendingQue = s },
                                label = { Text("缺${s.cn}") },
                            )
                        }
                    }
                    Button(
                        onClick = {
                            trainer.declareMissing(pendingQue)
                            trainer.drawTile()
                            refreshHand()
                            step = MjStep.PLAYING
                            advice = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("确认定缺并摸牌") }
                }

                MjStep.PLAYING -> PlayingContent(
                    trainer = trainer,
                    hand = hand,
                    missing = trainer.missing!!,
                    advice = advice,
                    onDiscard = { tile ->
                        trainer.discard(tile)
                        if (trainer.wallRemaining() > 0) trainer.drawTile()
                        refreshHand()
                        advice = null
                    },
                )
            }
        }
    }

    if (showGlossary) {
        GlossaryDialog(kind = GlossaryKind.MAHJONG, onDismiss = { showGlossary = false })
    }
}

@Composable
private fun PlayingContent(
    trainer: SichuanTrainer,
    hand: List<Tile>,
    missing: Suit,
    advice: String?,
    onDiscard: (Tile) -> Unit,
) {
    val shanten = remember(hand) { HandCheck.shanten(hand, missing) }
    val winning = remember(hand) { HandCheck.isWinning(hand, missing) }
    val suggestions = remember(hand) {
        if (hand.size == 14) trainer.rankDiscards() else emptyList()
    }
    val liveWaits = remember(hand) {
        val base = if (hand.size == 14 && suggestions.isNotEmpty())
            hand.toMutableList().also { it.remove(suggestions.first().tile) }
        else hand
        UkeIre.waitingWithCounts(base, trainer.discards.toList(), missing)
    }
    val typeReport = remember(hand) {
        if (hand.size in listOf(2, 5, 8, 11, 14) && HandCheck.isWinning(hand, missing))
            HandType.classify(hand)
        else null
    }

    // One-time in-screen operation hint.
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Text(
            "点击手牌中任意一张 = 打出这张，引擎会自动从牌墙再摸一张进手。\n" +
                "目标：通过打 / 摸进张降低「向听数」直到听牌（0）→ 胡牌（-1）。\n" +
                "需要策略时点底部「AI 教练分析」让 LLM 结合向听、进张、安全度给建议。",
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }

    Card {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("缺${missing.cn}", fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        winning -> "已胡！" + (typeReport?.labels?.joinToString("、")?.let { " ($it)" } ?: "")
                        shanten <= 0 -> "听牌"
                        else -> "向听 $shanten"
                    },
                    color = if (winning || shanten <= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text("牌墙余 ${trainer.wallRemaining()}")
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (hand.size == 14) "手牌（点击打出 → 自动摸下一张）"
                else "手牌（等待摸牌）",
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(6.dp))
            HandBySuit(
                hand = hand,
                onDiscard = { if (hand.size == 14) onDiscard(it) },
            )
        }
    }

    if (suggestions.isNotEmpty()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("引擎候选弃牌", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                suggestions.forEachIndexed { i, s ->
                    val tag = when {
                        s.shantenAfter < 0 -> "打出即胡"
                        s.shantenAfter == 0 -> "听 ${s.waitSize} 张"
                        else -> "向听 ${s.shantenAfter}"
                    }
                    Text("${i + 1}. ${s.tile.label}  →  $tag")
                }
            }
        }
    }

    if (liveWaits.isNotEmpty()) {
        Card {
            Column(Modifier.padding(16.dp)) {
                Text("有效进张（剩余）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(liveWaits.joinToString("  ") { "${it.tile.label}×${it.remaining}" })
            }
        }
    }

    advice?.let {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            AiMarkdown(it)
        }
    }
}

/**
 * Render the hand as three rows grouped by suit (万 / 条 / 筒) — far easier
 * to scan than a single FlowRow when the hand has 13–14 tiles.
 */
@Composable
private fun HandBySuit(hand: List<Tile>, onDiscard: (Tile) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Suit.entries.forEach { s ->
            val rowTiles = hand.filter { it.suit == s }.sortedBy { it.number }
            if (rowTiles.isEmpty()) return@forEach
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    s.cn,
                    modifier = Modifier.width(22.dp),
                    color = when (s) {
                        Suit.WAN -> Color(0xFFB00020)
                        Suit.TIAO -> Color(0xFF0E7C3A)
                        Suit.TONG -> Color(0xFF1E5AA8)
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    rowTiles.forEach { tile -> TileView(tile) { onDiscard(tile) } }
                }
            }
        }
    }
}

@Composable
private fun TileView(tile: Tile, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 60.dp)
            .background(Color(0xFFFFFBEA), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                tile.number.toString(),
                fontWeight = FontWeight.Bold,
                color = Color(0xFF111111),
            )
            Text(
                tile.suit.cn,
                style = MaterialTheme.typography.bodySmall,
                color = when (tile.suit) {
                    Suit.WAN -> Color(0xFFB00020)
                    Suit.TIAO -> Color(0xFF0E7C3A)
                    Suit.TONG -> Color(0xFF1E5AA8)
                },
            )
        }
    }
}
