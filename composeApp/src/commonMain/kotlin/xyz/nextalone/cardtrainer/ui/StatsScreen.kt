@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package xyz.nextalone.cardtrainer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.nextalone.cardtrainer.stats.MahjongStatsCalc
import xyz.nextalone.cardtrainer.stats.PokerStatsCalc
import xyz.nextalone.cardtrainer.stats.StatsRepository
import xyz.nextalone.cardtrainer.storage.AppSettings

private enum class StatsTab { POKER, MAHJONG }

@Composable
fun StatsScreen(settings: AppSettings, onBack: () -> Unit) {
    val repo = remember { StatsRepository(settings) }
    var tab by remember { mutableStateOf(StatsTab.POKER) }
    // Re-read on every compose so fresh events from the other screens show up
    // when the user navigates back here. Could switch to StateFlow later.
    val pokerEvents = remember(tab) { repo.loadPoker() }
    val mahjongEvents = remember(tab) { repo.loadMahjong() }

    xyz.nextalone.cardtrainer.ui.components.WithDeviceMode { mode ->
        val isPhone = mode == xyz.nextalone.cardtrainer.ui.components.DeviceMode.Phone
        val eyebrow = "STATS · HUD"
        val title = "训练统计"
        val topRight: @Composable xyz.nextalone.cardtrainer.ui.components.RowScope_.() -> Unit = {
            xyz.nextalone.cardtrainer.ui.components.BrandChip(
                "德州 · ${pokerEvents.size}",
                tone = if (tab == StatsTab.POKER) xyz.nextalone.cardtrainer.ui.components.ChipTone.Accent else xyz.nextalone.cardtrainer.ui.components.ChipTone.Outline,
                onClick = { tab = StatsTab.POKER },
            )
            xyz.nextalone.cardtrainer.ui.components.BrandChip(
                "麻将 · ${mahjongEvents.size}",
                tone = if (tab == StatsTab.MAHJONG) xyz.nextalone.cardtrainer.ui.components.ChipTone.Accent else xyz.nextalone.cardtrainer.ui.components.ChipTone.Outline,
                onClick = { tab = StatsTab.MAHJONG },
            )
        }
        val body: @Composable () -> Unit = {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = if (isPhone) 14.dp else 24.dp,
                        vertical = if (isPhone) 12.dp else 18.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(if (isPhone) 12.dp else 16.dp),
            ) {
                when (tab) {
                    StatsTab.POKER -> PokerStatsContent(
                        events = pokerEvents,
                        onClear = { repo.clearPoker() },
                    )
                    StatsTab.MAHJONG -> MahjongStatsContent(
                        events = mahjongEvents,
                        onClear = { repo.clearMahjong() },
                    )
                }
            }
        }
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
                windowLabel = "LLM Card Trainer · Stats",
                onBack = onBack,
                topRight = topRight,
                body = body,
            )
        }
    }
}

@Composable
private fun PokerStatsContent(
    events: List<xyz.nextalone.cardtrainer.stats.PokerDecisionEvent>,
    onClear: () -> Unit,
) {
    if (events.isEmpty()) {
        Text("暂无数据。提交几手牌后回来看统计。")
        return
    }
    val s = remember(events) { PokerStatsCalc.compute(events) }
    MetricsCard(
        title = "行为指标",
        rows = listOf(
            "决策总数" to "${s.totalDecisions}",
            "估计手数" to "${s.totalHands}",
            "VPIP (入池率)" to "${s.vpip}%",
            "PFR (翻前加注率)" to "${s.pfr}%",
            "AF (翻后攻击系数)" to format1(s.aggressionFactor),
            "翻前弃牌率" to "${s.preflopFoldRate}%",
            "翻后弃牌率" to "${s.postflopFoldRate}%",
            "偏离 RFI 基线率" to "${s.rfiDeviationRate}%",
            "平均下注 / 底池" to format1(s.avgBetSizePot),
        ),
    )

    MetricsCard(
        title = "胜负结算",
        rows = listOf(
            "胜率（含弃牌迫使）" to "${s.winRate}%",
            "平均蒙特卡洛胜率" to "${s.avgEquityPct}%",
            "我弃牌比例" to "${s.foldRate}%",
            "逼对手弃牌比例" to "${s.villainFoldRate}%",
            "走到摊牌比例" to "${s.showdownRate}%",
        ),
    )

    if (s.recentWindow.size >= 2) {
        TrendCard(
            title = "20 手滚动趋势：VPIP (蓝) · PFR (绿) · 胜率 (红)",
            series = listOf(
                s.recentWindow.map { it.vpip } to Color(0xFF1E5AA8),
                s.recentWindow.map { it.pfr } to Color(0xFF0E7C3A),
                s.recentWindow.map { it.winRate } to Color(0xFFB00020),
            ),
            yMax = 100.0,
        )
    }

    RecentEventsCard(
        title = "最近 10 条决策",
        lines = events.takeLast(10).reversed().map { pe ->
            val suffix = pe.villainResponse?.let { " → 对手 ${cnAction(it)}" } ?: ""
            "${pe.street} ${pe.position} ${pe.handLabel} · ${cnAction(pe.action)}${if (pe.amount > 0) " ${pe.amount}" else ""}$suffix"
        },
    )

    OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
        Text("清空德州统计")
    }
}

@Composable
private fun MahjongStatsContent(
    events: List<xyz.nextalone.cardtrainer.stats.MahjongDecisionEvent>,
    onClear: () -> Unit,
) {
    if (events.isEmpty()) {
        Text("暂无数据。打几张牌后回来看统计。")
        return
    }
    val s = remember(events) { MahjongStatsCalc.compute(events) }
    MetricsCard(
        title = "行为指标",
        rows = listOf(
            "决策总数" to "${s.totalDecisions}",
            "引擎 Top-1 命中率" to "${s.engineTop1MatchRate}%",
            "向听数下降比例" to "${s.shantenImprovementRate}%",
            "平均有效进张" to format1(s.avgLiveWaits),
            "平均弃牌时牌墙剩余" to format1(s.avgWallRemainingAtDiscard),
        ),
    )

    if (s.recentWindow.size >= 2) {
        TrendCard(
            title = "20 手滚动趋势：Top-1 命中率",
            series = listOf(
                s.recentWindow.map { it.top1Match } to Color(0xFF0E7C3A),
            ),
            yMax = 100.0,
        )
    }

    RecentEventsCard(
        title = "最近 10 条弃牌",
        lines = events.takeLast(10).reversed().map { me ->
            val matchTag = if (me.isEngineTop1) "✓" else "✗(引擎: ${me.engineTop1Label})"
            "缺${me.missingSuit} · 向听${me.shantenBefore}→${me.shantenAfter} · 打 ${me.tileDiscardedLabel} $matchTag"
        },
    )

    OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
        Text("清空麻将统计")
    }
}

@Composable
private fun MetricsCard(title: String, rows: List<Pair<String, String>>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            rows.forEach { (k, v) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(k, style = MaterialTheme.typography.bodyMedium)
                    Text(v, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun RecentEventsCard(title: String, lines: List<String>) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Minimal multi-series line chart drawn via Canvas — one colored line per series. */
@Composable
private fun TrendCard(
    title: String,
    series: List<Pair<List<Double>, Color>>,
    yMax: Double,
) {
    Card {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Canvas(
                modifier = Modifier.fillMaxWidth().height(120.dp),
            ) {
                val w = size.width
                val h = size.height
                series.forEach { (points, color) ->
                    if (points.size < 2) return@forEach
                    val path = Path()
                    val step = w / (points.size - 1).coerceAtLeast(1).toFloat()
                    points.forEachIndexed { i, v ->
                        val x = step * i
                        val y = h - (v / yMax).coerceIn(0.0, 1.0).toFloat() * h
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(
                        path = path,
                        color = color,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
                    )
                }
            }
        }
    }
}

private fun format1(v: Double): String = (kotlin.math.round(v * 10) / 10).toString()

private fun cnAction(raw: String): String = when (raw) {
    "FOLD" -> "弃"
    "CHECK" -> "过"
    "CALL" -> "跟"
    "BET" -> "下"
    "RAISE" -> "加"
    "ALL_IN" -> "全下"
    else -> raw
}
