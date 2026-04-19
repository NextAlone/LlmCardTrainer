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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.nextalone.cardtrainer.Route
import xyz.nextalone.cardtrainer.engine.holdem.Street
import xyz.nextalone.cardtrainer.storage.AppSettings
import xyz.nextalone.cardtrainer.storage.MahjongSession
import xyz.nextalone.cardtrainer.storage.PokerSession
import xyz.nextalone.cardtrainer.storage.loadMahjongSession
import xyz.nextalone.cardtrainer.storage.loadPokerSession
import xyz.nextalone.cardtrainer.stats.MahjongStatsCalc
import xyz.nextalone.cardtrainer.stats.PokerStatsCalc
import xyz.nextalone.cardtrainer.stats.StatsRepository
import xyz.nextalone.cardtrainer.ui.components.BrandChip
import xyz.nextalone.cardtrainer.ui.components.BrandDivider
import xyz.nextalone.cardtrainer.ui.components.BrandSurface
import xyz.nextalone.cardtrainer.ui.components.CardSize
import xyz.nextalone.cardtrainer.ui.components.ChipTone
import xyz.nextalone.cardtrainer.ui.components.Eyebrow
import xyz.nextalone.cardtrainer.ui.components.FeltSurface
import xyz.nextalone.cardtrainer.ui.components.PlayingCardView
import xyz.nextalone.cardtrainer.ui.components.SectionHeader
import xyz.nextalone.cardtrainer.ui.components.StatReadout
import xyz.nextalone.cardtrainer.ui.theme.BrandMonoFamily
import xyz.nextalone.cardtrainer.ui.theme.BrandTheme

/**
 * Home = session dashboard (not a nav portal). Matches Redesign.html:
 *  - Masthead with resume hint
 *  - Resume card (felt background, last board/hand snapshot, KPI strip)
 *  - Weekly snapshot (5 KPI tiles from StatsRepository)
 *  - AI recommendations (placeholder until the recommendation engine exists)
 *  - Recent activity (last N events, read-only)
 *  - Bottom tab on phone mode
 */
@Composable
fun HomeScreen(
    settings: AppSettings,
    onNav: (Route) -> Unit,
) {
    val stats = remember { StatsRepository(settings) }
    val pokerEvents = remember { stats.loadPoker() }
    val mahjongEvents = remember { stats.loadMahjong() }
    val pokerStats = remember(pokerEvents) { PokerStatsCalc.compute(pokerEvents) }
    val mahjongStats = remember(mahjongEvents) { MahjongStatsCalc.compute(mahjongEvents) }
    val pokerSession = remember { settings.loadPokerSession() }
    val mahjongSession = remember { settings.loadMahjongSession() }

    xyz.nextalone.cardtrainer.ui.components.WithDeviceMode { mode ->
        val narrow = mode == xyz.nextalone.cardtrainer.ui.components.DeviceMode.Phone
        Column(
            Modifier
                .fillMaxSize()
                .background(BrandTheme.colors.bg),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .padding(
                        horizontal = if (narrow) 16.dp else 32.dp,
                        vertical = if (narrow) 16.dp else 24.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(if (narrow) 16.dp else 22.dp),
            ) {
                if (!narrow) {
                    // Desktop top nav — phone uses the bottom tab bar.
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Spacer(Modifier.weight(1f))
                        BrandChip("扑克", tone = ChipTone.Outline, onClick = { onNav(Route.Poker) })
                        BrandChip("麻将", tone = ChipTone.Outline, onClick = { onNav(Route.Mahjong) })
                        BrandChip("统计", tone = ChipTone.Outline, onClick = { onNav(Route.Stats) })
                        BrandChip("设置", tone = ChipTone.Outline, onClick = { onNav(Route.Settings) })
                    }
                }
                Masthead(narrow = narrow, pokerSession = pokerSession, mahjongSession = mahjongSession)
                ResumeCard(
                    narrow = narrow,
                    pokerSession = pokerSession,
                    mahjongSession = mahjongSession,
                    onResumePoker = { onNav(Route.Poker) },
                    onResumeMahjong = { onNav(Route.Mahjong) },
                )
                KpiStrip(narrow = narrow, pokerStats = pokerStats, mahjongStats = mahjongStats)
                RecommendationsSection(narrow = narrow, onOpenPoker = { onNav(Route.Poker) }, onOpenMahjong = { onNav(Route.Mahjong) })
                RecentActivitySection(
                    narrow = narrow,
                    pokerEvents = pokerEvents,
                    mahjongEvents = mahjongEvents,
                )
            }
            // Bottom tab bar is now rendered by App.kt globally so it persists
            // across sub-routes; no per-screen copy needed.
        }
    }
}

/* ============================== Masthead ================================== */

@Composable
private fun Masthead(narrow: Boolean, pokerSession: PokerSession?, mahjongSession: MahjongSession?) {
    val c = BrandTheme.colors
    val resumeLine = resumeHint(pokerSession, mahjongSession)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Eyebrow("CARD TRAINER · V2")
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "回到牌桌",
                style = MaterialTheme.typography.displaySmall,
                color = c.fg,
            )
            BrandChip(resumeLine.first, tone = ChipTone.Accent)
        }
        Text(
            resumeLine.second,
            style = MaterialTheme.typography.bodyMedium,
            color = c.fgMuted,
        )
    }
}

private fun resumeHint(p: PokerSession?, m: MahjongSession?): Pair<String, String> {
    if (p == null && m == null) return "新手" to "还没有训练记录，挑个模式开始吧。"
    val chips = buildList {
        if (p != null) {
            val street = when (p.table.street) {
                Street.PREFLOP -> "翻前"; Street.FLOP -> "翻牌"
                Street.TURN -> "转牌"; Street.RIVER -> "河牌"; Street.SHOWDOWN -> "摊牌"
            }
            add("上一局 ${street} · 决策 ${p.table.history.size + 1}")
        }
        if (m != null) {
            val label = when (m.step) {
                MahjongSession.Step.NOT_DEALT -> "未发牌"
                MahjongSession.Step.CHOOSING_QUE -> "定缺中"
                MahjongSession.Step.PLAYING -> "对局中"
            }
            add("麻将 $label")
        }
    }
    val headline = chips.first()
    val detail = if (chips.size > 1) chips.drop(1).joinToString(" · ") else "从上次停下的地方继续。"
    return headline to detail
}

/* ============================== Resume card =============================== */

@Composable
private fun ResumeCard(
    narrow: Boolean,
    pokerSession: PokerSession?,
    mahjongSession: MahjongSession?,
    onResumePoker: () -> Unit,
    onResumeMahjong: () -> Unit,
) {
    val c = BrandTheme.colors
    FeltSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Eyebrow("Resume")
                        Text("·", color = Color.White.copy(alpha = 0.4f))
                        Text(
                            if (pokerSession != null) "德州扑克" else if (mahjongSession != null) "四川麻将" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                    }
                    Text(
                        resumeTitle(pokerSession, mahjongSession),
                        style = MaterialTheme.typography.displaySmall,
                        color = Color(0xFFFFFAED),
                    )
                }
                ResumeCta(
                    pokerSession = pokerSession,
                    mahjongSession = mahjongSession,
                    onResumePoker = onResumePoker,
                    onResumeMahjong = onResumeMahjong,
                )
            }
            if (pokerSession != null) {
                ResumePokerBody(pokerSession)
            } else if (mahjongSession != null) {
                ResumeMahjongBody(mahjongSession)
            } else {
                Text(
                    "点击下方训练模块开始第一局。算法算胜率 / 向听 / 有效进张，AI 教练讲解思路。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
        }
    }
}

private fun resumeTitle(p: PokerSession?, m: MahjongSession?): String = when {
    p != null -> {
        val street = when (p.table.street) {
            Street.PREFLOP -> "翻前 RFI"
            Street.FLOP -> "翻牌 决策"
            Street.TURN -> "转牌 决策"
            Street.RIVER -> "河牌 决策"
            Street.SHOWDOWN -> "摊牌"
        }
        "$street · 底池 ${p.table.pot}"
    }
    m != null -> when (m.step) {
        MahjongSession.Step.NOT_DEALT -> "准备发牌"
        MahjongSession.Step.CHOOSING_QUE -> "选定缺门"
        MahjongSession.Step.PLAYING -> "对局进行中"
    }
    else -> "开始新局"
}

@Composable
private fun ResumeCta(
    pokerSession: PokerSession?,
    mahjongSession: MahjongSession?,
    onResumePoker: () -> Unit,
    onResumeMahjong: () -> Unit,
) {
    val action = when {
        pokerSession != null -> onResumePoker
        mahjongSession != null -> onResumeMahjong
        else -> onResumePoker
    }
    val label = if (pokerSession != null || mahjongSession != null) "继续训练" else "开始训练"
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(BrandTheme.colors.accentBright, BrandTheme.colors.accent),
                ),
            )
            .clickable(onClick = action)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = BrandTheme.colors.fg,
            fontWeight = FontWeight.SemiBold,
        )
        Icon(Icons.Default.ArrowForward, contentDescription = null, tint = BrandTheme.colors.fg)
    }
}

@Composable
private fun ResumePokerBody(s: PokerSession) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // Hero hole cards
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier,
                ) {
                    s.table.hero.forEach {
                        PlayingCardView(
                            rank = it.rank.label,
                            suit = it.suit.symbol,
                            size = CardSize.Small,
                        )
                    }
                }
                Text("手牌", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
            }
            BoardRow(s)
        }
        StatRow(
            entries = listOf(
                "底池" to s.table.pot.toString(),
                "跟注" to s.table.toCall.toString(),
                "位置" to s.table.heroPosition.label,
                "街" to s.table.street.name,
            ),
        )
    }
}

@Composable
private fun BoardRow(s: PokerSession) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            s.table.board.forEach {
                PlayingCardView(
                    rank = it.rank.label,
                    suit = it.suit.symbol,
                    size = CardSize.Small,
                )
            }
            repeat(5 - s.table.board.size) {
                PlayingCardView(rank = "", suit = "", size = CardSize.Small, slot = true)
            }
        }
        Text("公共牌", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.6f))
    }
}

@Composable
private fun ResumeMahjongBody(s: MahjongSession) {
    val label = when (s.step) {
        MahjongSession.Step.NOT_DEALT -> "点击继续发牌"
        MahjongSession.Step.CHOOSING_QUE -> "正在选 ${s.pendingQue}"
        MahjongSession.Step.PLAYING -> "牌墙剩 ${s.snapshot.wall.size} 张"
    }
    Text(
        label,
        style = MaterialTheme.typography.titleMedium,
        color = Color(0xFFFFFAED),
    )
}

@Composable
private fun StatRow(entries: List<Pair<String, String>>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entries.forEach { (label, value) ->
            Column {
                Text(
                    label.uppercase(),
                    style = TextStyle(
                        fontFamily = BrandMonoFamily,
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.6f),
                        letterSpacing = 1.2.sp,
                    ),
                )
                Text(
                    value,
                    style = TextStyle(
                        fontFamily = BrandMonoFamily,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFFFFAED),
                    ),
                )
            }
        }
    }
}

/* ============================== KPI strip ================================= */

@Composable
private fun KpiStrip(
    narrow: Boolean,
    pokerStats: xyz.nextalone.cardtrainer.stats.PokerStats,
    mahjongStats: xyz.nextalone.cardtrainer.stats.MahjongStats,
) {
    val kpis = listOf(
        Triple("VPIP", fmt1(pokerStats.vpip), "%"),
        Triple("PFR", fmt1(pokerStats.pfr), "%"),
        Triple("胜率", fmt1(pokerStats.winRate), "%"),
        Triple("麻将 TOP-1", fmt1(mahjongStats.engineTop1MatchRate), "%"),
        Triple("有效进张", fmt1(mahjongStats.avgLiveWaits), ""),
    )
    SectionHeader(eyebrow = "OVERALL SNAPSHOT", title = "累计指标")
    Spacer(Modifier.height(8.dp))
    if (narrow) {
        FlowRow(
            maxItemsInEachRow = 2,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            kpis.forEach { KpiTile(it.first, it.second, it.third, modifier = Modifier.weight(1f, fill = true)) }
        }
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            kpis.forEach { KpiTile(it.first, it.second, it.third, modifier = Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun KpiTile(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    BrandSurface(modifier = modifier.fillMaxWidth(), padding = PaddingValues(14.dp)) {
        StatReadout(label = label, value = value, unit = unit.takeIf { it.isNotEmpty() }, big = true)
    }
}

private fun fmt1(d: Double): String = ((d * 10).toInt() / 10.0).toString()

/* ============================== Recommendations =========================== */

@Composable
private fun RecommendationsSection(narrow: Boolean, onOpenPoker: () -> Unit, onOpenMahjong: () -> Unit) {
    val cards = listOf(
        RecommendationCard(
            eyebrow = "课题 · 扑克",
            title = "3bet pot OOP 的 c-bet 频率",
            hint = "在 BB vs BTN 3bet 底池，你的低频 c-bet 偏离教练模型 +14%。",
            action = "进入扑克",
            onClick = onOpenPoker,
        ),
        RecommendationCard(
            eyebrow = "课题 · 扑克",
            title = "Turn value raise 尺度",
            hint = "遇强听牌且你有顶级两对 +，试试 75% 池尺度而非 1/2。",
            action = "进入扑克",
            onClick = onOpenPoker,
        ),
        RecommendationCard(
            eyebrow = "课题 · 麻将",
            title = "立直前的安全牌管理",
            hint = "定缺阶段提前留 1 安全牌，听牌后减少危险弃牌 23%。",
            action = "进入麻将",
            onClick = onOpenMahjong,
        ),
    )
    SectionHeader(
        eyebrow = "AI RECOMMENDS",
        title = "AI 推荐练什么",
        right = { BrandChip("占位 · 待引擎接入", tone = ChipTone.Outline) },
    )
    Spacer(Modifier.height(8.dp))
    if (narrow) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            cards.forEach { it.Render(Modifier.fillMaxWidth()) }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            cards.forEach { it.Render(Modifier.weight(1f)) }
        }
    }
}

private data class RecommendationCard(
    val eyebrow: String,
    val title: String,
    val hint: String,
    val action: String,
    val onClick: () -> Unit,
) {
    @Composable
    fun Render(modifier: Modifier = Modifier) {
        BrandSurface(
            modifier = modifier.clickable(onClick = onClick),
            padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Eyebrow(eyebrow)
            Spacer(Modifier.height(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = BrandTheme.colors.fg,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = BrandTheme.colors.fgMuted,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    action,
                    style = MaterialTheme.typography.labelLarge,
                    color = BrandTheme.colors.accent,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    Icons.Default.ArrowForward,
                    contentDescription = null,
                    tint = BrandTheme.colors.accent,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/* ============================== Recent activity =========================== */

@Composable
private fun RecentActivitySection(
    narrow: Boolean,
    pokerEvents: List<xyz.nextalone.cardtrainer.stats.PokerDecisionEvent>,
    mahjongEvents: List<xyz.nextalone.cardtrainer.stats.MahjongDecisionEvent>,
) {
    SectionHeader(eyebrow = "LATEST", title = "最近活动")
    Spacer(Modifier.height(8.dp))
    BrandSurface(Modifier.fillMaxWidth(), padding = PaddingValues(0.dp)) {
        val entries = buildList {
            pokerEvents.takeLast(6).asReversed().forEach { ev ->
                add(
                    ActivityRow(
                        kind = "扑克",
                        detail = "${ev.street} · ${ev.handLabel} · ${ev.action} ${if (ev.amount > 0) ev.amount else ""}",
                        meta = "底池 ${ev.potBefore} → ${ev.potAfter}",
                    ),
                )
            }
            mahjongEvents.takeLast(4).asReversed().forEach { ev ->
                add(
                    ActivityRow(
                        kind = "麻将",
                        detail = "弃 ${ev.tileDiscardedLabel} · 向听 ${ev.shantenBefore}→${ev.shantenAfter}",
                        meta = "引擎 Top-1: ${if (ev.isEngineTop1) "命中" else ev.engineTop1Label}",
                    ),
                )
            }
        }
        if (entries.isEmpty()) {
            Text(
                "还没有记录。完成一局后这里会显示最近的决策。",
                style = MaterialTheme.typography.bodyMedium,
                color = BrandTheme.colors.fgMuted,
                modifier = Modifier.padding(18.dp),
            )
        } else {
            entries.forEachIndexed { idx, row ->
                ActivityRowView(row)
                if (idx != entries.lastIndex) BrandDivider()
            }
        }
    }
}

private data class ActivityRow(val kind: String, val detail: String, val meta: String)

@Composable
private fun ActivityRowView(row: ActivityRow) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BrandChip(row.kind, tone = if (row.kind == "扑克") ChipTone.Accent else ChipTone.Good)
        Text(
            row.detail,
            style = MaterialTheme.typography.bodyMedium,
            color = BrandTheme.colors.fg,
            modifier = Modifier.weight(1f),
        )
        Text(
            row.meta,
            style = TextStyle(
                fontFamily = BrandMonoFamily,
                fontSize = 12.sp,
                color = BrandTheme.colors.fgSubtle,
            ),
        )
    }
}

