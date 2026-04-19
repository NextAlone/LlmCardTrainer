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
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.unit.sp
import xyz.nextalone.cardtrainer.coach.ChatTurn
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
import xyz.nextalone.cardtrainer.ui.components.BrandChip
import xyz.nextalone.cardtrainer.ui.components.BrandSurface
import xyz.nextalone.cardtrainer.ui.components.ChipTone
import xyz.nextalone.cardtrainer.ui.components.Eyebrow
import xyz.nextalone.cardtrainer.ui.components.MahjongTileView
import xyz.nextalone.cardtrainer.ui.components.MjSuit
import xyz.nextalone.cardtrainer.ui.components.SectionHeader
import xyz.nextalone.cardtrainer.ui.components.TileSize
import xyz.nextalone.cardtrainer.ui.theme.BrandDisplayFamily
import xyz.nextalone.cardtrainer.ui.theme.BrandMonoFamily
import xyz.nextalone.cardtrainer.ui.theme.BrandTheme
import xyz.nextalone.cardtrainer.util.withRetry
import androidx.compose.runtime.LaunchedEffect
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

private typealias MjStep = MahjongSession.Step

// Marker for failure strings saved by pre-retry builds into MahjongSession.advice.
private const val ERROR_PREFIX = "请求失败："

/** If the session has no turn list but holds a legacy success string, wrap it
 *  into an assistant-only turn pair so follow-up stays available. */
private fun migrateLegacyAdvice(turns: List<ChatTurn>, legacy: String?): List<ChatTurn> {
    if (turns.isNotEmpty()) return turns
    val clean = legacy?.takeUnless { it.startsWith(ERROR_PREFIX) } ?: return emptyList()
    return listOf(
        ChatTurn(ChatTurn.Role.USER, "（初始分析请求）"),
        ChatTurn(ChatTurn.Role.ASSISTANT, clean),
    )
}

private fun legacyAdviceError(legacy: String?, turns: List<ChatTurn>): String? {
    if (turns.isNotEmpty()) return null
    return legacy?.takeIf { it.startsWith(ERROR_PREFIX) }?.removePrefix(ERROR_PREFIX)
}

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
    // Multi-turn coach chat. Index 0 = structured initial prompt (hidden from
    // UI); subsequent turns alternate user follow-ups and assistant replies.
    var adviceTurns by remember {
        mutableStateOf(migrateLegacyAdvice(savedSession?.adviceTurns ?: emptyList(), savedSession?.advice))
    }
    var adviceError by remember {
        mutableStateOf(legacyAdviceError(savedSession?.advice, savedSession?.adviceTurns ?: emptyList()))
    }
    var loading by remember { mutableStateOf(false) }
    var showGlossary by remember { mutableStateOf(false) }
    val stats = remember { xyz.nextalone.cardtrainer.stats.StatsRepository(settings) }
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
                adviceTurns = adviceTurns,
            ),
        )
    }

    LaunchedEffect(step, pendingQue, hand, adviceTurns) { persist() }

    fun buildInitialPrompt(): String {
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
            opponentDiscards = trainer.opponentDiscards(),
        )
        val typeReport = if (hand.size in listOf(2, 5, 8, 11, 14) && HandCheck.isWinning(hand, trainer.missing)) {
            HandType.classify(hand)
        } else null
        return Prompts.mahjongUser(
            hand = hand,
            missing = trainer.missing!!,
            discards = trainer.discards.toList(),
            suggestions = suggestions,
            wallRemaining = trainer.wallRemaining(),
            liveWaits = liveWaits,
            safety = safety,
            handType = typeReport,
        )
    }

    fun askCoach() {
        val cfg = settings.activeConfig()
        if (cfg.apiKey.isBlank()) {
            adviceError = "请先在『设置』中填写 ${cfg.kind.label} 的 API Key。"
            return
        }
        val seedTurns = listOf(ChatTurn(ChatTurn.Role.USER, buildInitialPrompt()))
        loading = true
        adviceError = null
        scope.launch {
            val coach = LlmProviders.create(cfg)
            try {
                val reply = withRetry {
                    coach.coach(systemPrompt = Prompts.MAHJONG_SYSTEM, messages = seedTurns)
                }
                adviceTurns = seedTurns + ChatTurn(ChatTurn.Role.ASSISTANT, reply)
                adviceError = null
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                adviceError = t.message ?: t::class.simpleName ?: "未知错误"
            } finally {
                coach.close()
                loading = false
            }
        }
    }

    fun followUpAdvice(question: String) {
        val cfg = settings.activeConfig()
        if (cfg.apiKey.isBlank()) {
            adviceError = "请先在『设置』中填写 ${cfg.kind.label} 的 API Key。"
            return
        }
        val priorTurns = adviceTurns + ChatTurn(ChatTurn.Role.USER, question)
        loading = true
        adviceError = null
        scope.launch {
            val coach = LlmProviders.create(cfg)
            try {
                val reply = withRetry {
                    coach.coach(systemPrompt = Prompts.MAHJONG_SYSTEM, messages = priorTurns)
                }
                adviceTurns = priorTurns + ChatTurn(ChatTurn.Role.ASSISTANT, reply)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                adviceError = t.message ?: t::class.simpleName ?: "未知错误"
            } finally {
                coach.close()
                loading = false
            }
        }
    }

    fun resetGame() {
        trainer = SichuanTrainer()
        hand = emptyList()
        adviceTurns = emptyList()
        adviceError = null
        step = MjStep.NOT_DEALT
    }

    val onDiscardHero: (Tile) -> Unit = { tile ->
        val priorShanten = HandCheck.shanten(trainer.hand, trainer.missing)
        val engineTop1 = if (trainer.hand.size == 14) {
            trainer.rankDiscards(limit = 1).firstOrNull()
        } else null
        trainer.discard(tile)
        trainer.runOpponentsAndDraw()
        refreshHand()
        val afterShanten = HandCheck.shanten(trainer.hand, trainer.missing)
        val waits = UkeIre.waitingWithCounts(
            trainer.hand,
            trainer.discards.toList(),
            trainer.missing,
        ).sumOf { it.remaining }
        stats.recordMahjong(
            xyz.nextalone.cardtrainer.stats.MahjongDecisionEvent(
                timestampMs = xyz.nextalone.cardtrainer.util.nowEpochMs(),
                missingSuit = trainer.missing!!.cn,
                shantenBefore = priorShanten,
                shantenAfter = afterShanten,
                tileDiscardedLabel = tile.label,
                engineTop1Label = engineTop1?.tile?.label ?: "",
                isEngineTop1 = engineTop1?.tile == tile,
                liveWaitsAfter = waits,
                wallRemaining = trainer.wallRemaining(),
            ),
        )
        adviceTurns = emptyList()
        adviceError = null
    }

    val onDeal: () -> Unit = {
        trainer = SichuanTrainer()
        trainer.dealInitial()
        refreshHand()
        pendingQue = DingQue.recommend(trainer.hand.toList()).first().suit
        step = MjStep.CHOOSING_QUE
    }

    val onConfirmQue: () -> Unit = {
        trainer.declareMissing(pendingQue)
        trainer.drawTile()
        refreshHand()
        step = MjStep.PLAYING
        adviceTurns = emptyList()
        adviceError = null
    }

    xyz.nextalone.cardtrainer.ui.components.WithDeviceMode { mode ->
        val isPhone = mode == xyz.nextalone.cardtrainer.ui.components.DeviceMode.Phone
        val shellTitle = when (step) {
            MjStep.NOT_DEALT -> "准备发牌"
            MjStep.CHOOSING_QUE -> "定缺"
            MjStep.PLAYING -> run {
                val sh = HandCheck.shanten(trainer.hand, trainer.missing)
                val win = HandCheck.isWinning(trainer.hand, trainer.missing)
                when {
                    win -> "已胡"
                    sh <= 0 -> "听牌 · 牌墙 ${trainer.wallRemaining()}"
                    else -> "向听 $sh · 牌墙 ${trainer.wallRemaining()}"
                }
            }
        }
        val eyebrow = when (step) {
            MjStep.NOT_DEALT -> "SICHUAN · 血战到底"
            MjStep.CHOOSING_QUE -> "SICHUAN · 选择缺门"
            MjStep.PLAYING -> "SICHUAN · 缺${trainer.missing?.cn ?: "?"} · 血战到底"
        }
        val body: @Composable () -> Unit = {
            when (step) {
                MjStep.NOT_DEALT -> MahjongIntroContent(isPhone = isPhone, onDeal = onDeal)
                MjStep.CHOOSING_QUE -> MahjongChooseQueContent(
                    isPhone = isPhone,
                    hand = hand,
                    pendingQue = pendingQue,
                    onPickQue = { pendingQue = it },
                    onConfirm = onConfirmQue,
                )
                MjStep.PLAYING -> {
                    if (isPhone) {
                        PlayingContentPhone(
                            trainer = trainer,
                            hand = hand,
                            missing = trainer.missing!!,
                            adviceTurns = adviceTurns,
                            adviceError = adviceError,
                            loadingAdvice = loading,
                            onRetryAdvice = ::askCoach,
                            onFollowUpAdvice = ::followUpAdvice,
                            onDiscard = onDiscardHero,
                        )
                    } else {
                        PlayingContentDesktop(
                            trainer = trainer,
                            hand = hand,
                            missing = trainer.missing!!,
                            adviceTurns = adviceTurns,
                            adviceError = adviceError,
                            loadingAdvice = loading,
                            onRetryAdvice = ::askCoach,
                            onFollowUpAdvice = ::followUpAdvice,
                            onDiscard = onDiscardHero,
                        )
                    }
                }
            }
        }
        val bottomBar: @Composable () -> Unit = {
            if (step == MjStep.PLAYING) {
                xyz.nextalone.cardtrainer.ui.components.PinnedActionBar {
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = ::resetGame) { Text("重新开局") }
                    Button(onClick = ::askCoach, enabled = !loading) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = BrandTheme.colors.fg)
                            Spacer(Modifier.width(6.dp))
                            Text("思考中")
                        } else Text("AI 教练分析")
                    }
                }
            }
        }

        if (isPhone) {
            xyz.nextalone.cardtrainer.ui.components.PhoneShell(
                eyebrow = eyebrow,
                title = shellTitle,
                onBack = onBack,
                topRight = {
                    BrandChip("术语", tone = ChipTone.Outline, onClick = { showGlossary = true })
                },
                bottomBar = bottomBar,
                body = body,
            )
        } else {
            xyz.nextalone.cardtrainer.ui.components.DesktopShell(
                eyebrow = eyebrow,
                title = shellTitle,
                windowLabel = "LLM Card Trainer · Mahjong",
                onBack = onBack,
                topRight = {
                    BrandChip("术语", tone = ChipTone.Outline, onClick = { showGlossary = true })
                    BrandChip("血战到底", tone = ChipTone.Outline)
                    if (step == MjStep.PLAYING) {
                        val sh = HandCheck.shanten(trainer.hand, trainer.missing)
                        BrandChip(
                            if (sh <= 0) "听 / 胡" else "向听 $sh",
                            tone = if (sh <= 0) ChipTone.Good else ChipTone.Accent,
                        )
                    }
                },
                bottomBar = bottomBar,
                body = body,
            )
        }
    }

    if (showGlossary) {
        GlossaryDialog(kind = GlossaryKind.MAHJONG, onDismiss = { showGlossary = false })
    }
}

@Composable
private fun MahjongIntroContent(isPhone: Boolean, onDeal: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (isPhone) 14.dp else 24.dp, vertical = if (isPhone) 12.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BrandSurface {
            Eyebrow("血战到底 · 108 张")
            Spacer(Modifier.height(6.dp))
            Text(
                "万 / 条 / 筒 各 4 张 · 共 108 张。点击发牌，引擎会给出 定缺 建议。",
                style = MaterialTheme.typography.bodyMedium,
                color = BrandTheme.colors.fg,
            )
        }
        Button(onClick = onDeal, modifier = Modifier.fillMaxWidth()) { Text("发 13 张") }
    }
}

@Composable
private fun MahjongChooseQueContent(
    isPhone: Boolean,
    hand: List<Tile>,
    pendingQue: Suit,
    onPickQue: (Suit) -> Unit,
    onConfirm: () -> Unit,
) {
    val advisories = remember(hand) { DingQue.recommend(hand) }
    val content: @Composable () -> Unit = {
        BrandSurface {
            Eyebrow("已发 13 张手牌")
            Spacer(Modifier.height(10.dp))
            HandBySuit(hand = hand, onDiscard = {})
        }
        BrandSurface {
            Eyebrow("引擎定缺推荐 · 弃后向听排序")
            Spacer(Modifier.height(8.dp))
            advisories.forEach {
                Text(
                    "缺${it.suit.cn}：保留分 ${it.score}（越低越适合缺），该门 ${it.countInSuit} 张",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BrandTheme.colors.fg,
                )
            }
        }
        Text("选择定缺", style = MaterialTheme.typography.titleMedium, color = BrandTheme.colors.fg)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Suit.entries.forEach { s ->
                FilterChip(
                    selected = pendingQue == s,
                    onClick = { onPickQue(s) },
                    label = { Text("缺${s.cn}") },
                )
            }
        }
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) { Text("确认定缺并摸牌") }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (isPhone) 14.dp else 24.dp, vertical = if (isPhone) 12.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

/**
 * Off-main shanten + ukeire computation. `trainer.rankDiscards()` calls the
 * shanten solver 14 times and `UkeIre.waitingWithCounts` calls it 27 times —
 * together ~40 solves that block the first frame when landing on the Mahjong
 * screen. Moving to `Dispatchers.Default` lets the UI paint the hand surface
 * immediately and fill the suggestions / wait chips when ready.
 */
@Composable
private fun asyncShantenAnalysis(
    trainer: SichuanTrainer,
    hand: List<Tile>,
    missing: Suit,
): Pair<List<xyz.nextalone.cardtrainer.engine.mahjong.DiscardSuggestion>, List<xyz.nextalone.cardtrainer.engine.mahjong.LiveWait>> {
    val suggestions by androidx.compose.runtime.produceState(
        initialValue = emptyList<xyz.nextalone.cardtrainer.engine.mahjong.DiscardSuggestion>(),
        key1 = hand,
    ) {
        value = if (hand.size == 14) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                trainer.rankDiscards()
            }
        } else emptyList()
    }
    val liveWaits by androidx.compose.runtime.produceState(
        initialValue = emptyList<xyz.nextalone.cardtrainer.engine.mahjong.LiveWait>(),
        key1 = hand,
        key2 = suggestions,
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val base = if (hand.size == 14 && suggestions.isNotEmpty())
                hand.toMutableList().also { it.remove(suggestions.first().tile) }
            else hand
            UkeIre.waitingWithCounts(base, trainer.discards.toList(), missing)
        }
    }
    return suggestions to liveWaits
}

/** Shared hand + state surface used by both Phone and Desktop PLAYING views. */
@Composable
private fun HandSurface(
    trainer: SichuanTrainer,
    hand: List<Tile>,
    missing: Suit,
    onDiscard: (Tile) -> Unit,
) {
    val shanten = remember(hand) { HandCheck.shanten(hand, missing) }
    val winning = remember(hand) { HandCheck.isWinning(hand, missing) }
    val typeReport = remember(hand) {
        if (hand.size in listOf(2, 5, 8, 11, 14) && HandCheck.isWinning(hand, missing))
            HandType.classify(hand)
        else null
    }
    BrandSurface {
        SectionHeader(
            eyebrow = "定缺 ${missing.cn} · 牌墙 ${trainer.wallRemaining()}",
            title = when {
                winning -> "已胡！" + (typeReport?.labels?.joinToString("、")?.let { " ($it)" } ?: "")
                shanten <= 0 -> "听牌"
                else -> "向听 $shanten · 听牌前 $shanten 步"
            },
            right = {
                if (winning) BrandChip("胡", tone = ChipTone.Good)
                else if (shanten <= 0) BrandChip("听", tone = ChipTone.Good)
                else BrandChip("向听 $shanten", tone = ChipTone.Outline)
            },
        )
        Spacer(Modifier.height(14.dp))
        Text(
            if (hand.size == 14) "点击手牌打出 → 自动摸下一张"
            else "等待摸牌…",
            style = MaterialTheme.typography.bodySmall,
            color = BrandTheme.colors.fgMuted,
        )
        Spacer(Modifier.height(10.dp))
        HandBySuit(
            hand = hand,
            missing = missing,
            onDiscard = { if (hand.size == 14) onDiscard(it) },
        )
    }
}

@Composable
private fun SuggestionsPanel(suggestions: List<xyz.nextalone.cardtrainer.engine.mahjong.DiscardSuggestion>) {
    if (suggestions.isEmpty()) return
    BrandSurface {
        Eyebrow("引擎候选弃牌")
        Spacer(Modifier.height(10.dp))
        suggestions.forEachIndexed { i, s ->
            val tag = when {
                s.shantenAfter < 0 -> "打出即胡"
                s.shantenAfter == 0 -> "听 ${s.waitSize} 张"
                else -> "向听 ${s.shantenAfter}"
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "${i + 1}.",
                    style = androidx.compose.ui.text.TextStyle(
                        fontFamily = BrandMonoFamily,
                        fontSize = 12.sp,
                        color = BrandTheme.colors.fgSubtle,
                    ),
                    modifier = Modifier.width(20.dp),
                )
                TileView(s.tile, dim = false) { }
                Text(
                    "→ $tag",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BrandTheme.colors.fg,
                    fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal,
                )
                Spacer(Modifier.weight(1f))
                if (i == 0) BrandChip("最佳", tone = ChipTone.Accent)
            }
        }
    }
}

@Composable
private fun LiveWaitsPanel(liveWaits: List<xyz.nextalone.cardtrainer.engine.mahjong.LiveWait>) {
    if (liveWaits.isEmpty()) return
    BrandSurface {
        Eyebrow("有效进张 · 剩余")
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            liveWaits.forEach { w -> BrandChip("${w.tile.label}×${w.remaining}", tone = ChipTone.Outline) }
        }
    }
}

@Composable
private fun PlayingContentPhone(
    trainer: SichuanTrainer,
    hand: List<Tile>,
    missing: Suit,
    adviceTurns: List<ChatTurn>,
    adviceError: String?,
    loadingAdvice: Boolean,
    onRetryAdvice: () -> Unit,
    onFollowUpAdvice: (String) -> Unit,
    onDiscard: (Tile) -> Unit,
) {
    val (suggestions, liveWaits) = asyncShantenAnalysis(trainer, hand, missing)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HandSurface(trainer, hand, missing, onDiscard)
        SuggestionsPanel(suggestions)
        LiveWaitsPanel(liveWaits)
        OpponentDiscardsPanel(trainer = trainer)
        TilePoolPanel(unseen = trainer.unseenByTile())
        if (adviceTurns.isNotEmpty() || adviceError != null || loadingAdvice) {
            AiConversation(
                title = "AI 教练建议",
                turns = adviceTurns,
                loading = loadingAdvice,
                error = adviceError,
                onRetry = onRetryAdvice,
                onFollowUp = onFollowUpAdvice,
                accentTone = ChipTone.Accent,
            )
        }
    }
}

@Composable
private fun PlayingContentDesktop(
    trainer: SichuanTrainer,
    hand: List<Tile>,
    missing: Suit,
    adviceTurns: List<ChatTurn>,
    adviceError: String?,
    loadingAdvice: Boolean,
    onRetryAdvice: () -> Unit,
    onFollowUpAdvice: (String) -> Unit,
    onDiscard: (Tile) -> Unit,
) {
    val (suggestions, liveWaits) = asyncShantenAnalysis(trainer, hand, missing)
    Row(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // Left: hand, melds (opponent strip), tile pool
        Column(
            Modifier.weight(1.35f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OpponentDiscardsPanel(trainer = trainer)
            HandSurface(trainer, hand, missing, onDiscard)
            TilePoolPanel(unseen = trainer.unseenByTile())
        }
        // Right: suggestions, waits, AI coach
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SuggestionsPanel(suggestions)
            LiveWaitsPanel(liveWaits)
            if (adviceTurns.isNotEmpty() || adviceError != null || loadingAdvice) {
                AiConversation(
                    title = "AI 教练建议",
                    turns = adviceTurns,
                    loading = loadingAdvice,
                    error = adviceError,
                    onRetry = onRetryAdvice,
                    onFollowUp = onFollowUpAdvice,
                    accentTone = ChipTone.Accent,
                )
            } else {
                BrandSurface {
                    Eyebrow("AI 教练就绪")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "点击底部「AI 教练分析」让 LLM 结合向听、进张、安全度给建议。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = BrandTheme.colors.fgMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun OpponentDiscardsPanel(trainer: SichuanTrainer) {
    val labels = listOf("下家", "对家", "上家")
    val views = trainer.opponentViews()
    val c = BrandTheme.colors
    BrandSurface {
        Eyebrow("对家弃牌轨迹")
        Spacer(Modifier.height(10.dp))
        views.forEachIndexed { i, v ->
            // Crude danger tint based on number of discards — approximation
            // until a real safety score is wired in.
            val dangerTone = when {
                v.discards.size >= 7 -> c.bad
                v.discards.size >= 4 -> c.accentBright
                else -> c.good
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(48.dp)
                        .background(dangerTone, RoundedCornerShape(2.dp)),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            labels.getOrNull(i) ?: "P${i + 1}",
                            style = MaterialTheme.typography.titleSmall,
                            color = c.fg,
                            fontWeight = FontWeight.SemiBold,
                        )
                        v.missing?.let {
                            BrandChip("缺${it.cn}", tone = ChipTone.Outline)
                        }
                        BrandChip("弃 ${v.discards.size}", tone = ChipTone.Outline)
                    }
                    if (v.discards.isEmpty()) {
                        Text(
                            "尚未出牌",
                            style = MaterialTheme.typography.bodySmall,
                            color = c.fgSubtle,
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            v.discards.forEach { tile ->
                                MahjongTileView(
                                    suit = when (tile.suit) {
                                        Suit.WAN -> MjSuit.Wan
                                        Suit.TIAO -> MjSuit.Tiao
                                        Suit.TONG -> MjSuit.Tong
                                    },
                                    number = tile.number,
                                    size = TileSize.Small,
                                )
                            }
                        }
                    }
                }
            }
            if (i < views.lastIndex) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
            }
        }
    }
}

/**
 * 27-tile pool viewer: one row per suit, each tile shown with its remaining
 * unseen count (4 = untouched, 0 = all gone). Matches how players "count
 * tiles" in a real game — which tiles are still live in the wall / opponents.
 */
@Composable
private fun TilePoolPanel(unseen: Map<Tile, Int>) {
    val c = BrandTheme.colors
    BrandSurface {
        Eyebrow("牌池剩余 · 每张最多 4")
        Spacer(Modifier.height(10.dp))
        Suit.entries.forEach { s ->
            val suitColor = when (s) {
                Suit.WAN -> c.suitRed
                Suit.TIAO -> c.suitGreenTiao
                Suit.TONG -> c.suitBlueTong
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    s.cn,
                    modifier = Modifier.width(26.dp),
                    style = androidx.compose.ui.text.TextStyle(
                        fontFamily = BrandDisplayFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = suitColor,
                    ),
                )
                for (n in 1..9) {
                    val count = unseen[Tile(s, n)] ?: 0
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            n.toString(),
                            style = androidx.compose.ui.text.TextStyle(
                                fontFamily = BrandMonoFamily,
                                fontSize = 10.sp,
                                color = c.fgSubtle,
                            ),
                        )
                        Text(
                            count.toString(),
                            style = androidx.compose.ui.text.TextStyle(
                                fontFamily = BrandMonoFamily,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = when {
                                    count == 0 -> c.fgSubtle.copy(alpha = 0.35f)
                                    count <= 1 -> c.bad
                                    else -> c.fg
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Render the hand as three rows grouped by suit (万 / 条 / 筒) — far easier
 * to scan than a single FlowRow when the hand has 13–14 tiles.
 */
@Composable
private fun HandBySuit(hand: List<Tile>, missing: Suit? = null, onDiscard: (Tile) -> Unit) {
    val brand = BrandTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Suit.entries.forEach { s ->
            val rowTiles = hand.filter { it.suit == s }.sortedBy { it.number }
            if (rowTiles.isEmpty()) return@forEach
            val suitColor = when (s) {
                Suit.WAN -> brand.suitRed
                Suit.TIAO -> brand.suitGreenTiao
                Suit.TONG -> brand.suitBlueTong
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    if (missing == s) "${s.cn} (缺)" else s.cn,
                    modifier = Modifier.width(46.dp),
                    style = androidx.compose.ui.text.TextStyle(
                        fontFamily = BrandDisplayFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = suitColor,
                    ),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    rowTiles.forEach { tile ->
                        TileView(tile, dim = missing == tile.suit) { onDiscard(tile) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TileView(tile: Tile, dim: Boolean = false, onClick: () -> Unit) {
    MahjongTileView(
        suit = when (tile.suit) {
            Suit.WAN -> MjSuit.Wan
            Suit.TIAO -> MjSuit.Tiao
            Suit.TONG -> MjSuit.Tong
        },
        number = tile.number,
        size = TileSize.Default,
        dim = dim,
        onClick = onClick,
    )
}
