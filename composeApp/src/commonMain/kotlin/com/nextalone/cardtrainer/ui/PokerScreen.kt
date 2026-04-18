@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nextalone.cardtrainer.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nextalone.cardtrainer.coach.LlmProviders
import com.nextalone.cardtrainer.coach.Prompts
import com.nextalone.cardtrainer.engine.holdem.Card as PokerCard
import com.nextalone.cardtrainer.engine.holdem.Equity
import com.nextalone.cardtrainer.engine.holdem.HoldemTable
import com.nextalone.cardtrainer.engine.holdem.HoldemTrainer
import com.nextalone.cardtrainer.engine.holdem.Outs
import com.nextalone.cardtrainer.engine.holdem.OutsReport
import com.nextalone.cardtrainer.engine.holdem.PreflopChart
import com.nextalone.cardtrainer.engine.holdem.Street
import com.nextalone.cardtrainer.storage.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PokerScreen(settings: AppSettings, onBack: () -> Unit) {
    val trainer = remember { HoldemTrainer() }
    var table by remember { mutableStateOf(trainer.newHand()) }
    var equityPct by remember { mutableStateOf<Double?>(null) }
    var outs by remember { mutableStateOf<OutsReport?>(null) }
    var advice by remember { mutableStateOf<String?>(null) }
    var loadingAdvice by remember { mutableStateOf(false) }
    var loadingEquity by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val preflopBaseline = remember(table.hero, table.heroPosition) {
        PreflopChart.rfiAction(table.heroPosition, table.hero)
    }

    LaunchedEffect(table.street, table.hero, table.board) {
        loadingEquity = true
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
        loadingEquity = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("德州扑克训练") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TableCard(table = table, equityPct = equityPct, loadingEquity = loadingEquity)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text("位置 ${table.heroPosition.label}") })
                AssistChip(onClick = {}, label = { Text("${table.opponents} 对手") })
                AssistChip(onClick = {}, label = { Text("街 ${table.street.name}") })
            }

            if (table.street == Street.PREFLOP) {
                Text(
                    "翻前基线（按位置 RFI）：$preflopBaseline",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            outs?.let {
                Text(
                    "Outs ${it.outs}（Rule of 2/4：${it.turnPct}% / ${it.turnAndRiverPct}%）",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (table.street != Street.SHOWDOWN) {
                    FilledTonalButton(
                        onClick = { table = trainer.advanceStreet(table) },
                        modifier = Modifier.weight(1f),
                    ) { Text("发下一街") }
                }
                OutlinedButton(
                    onClick = {
                        table = trainer.newHand()
                        advice = null
                        equityPct = null
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("新牌局") }
            }

            Button(
                onClick = {
                    val cfg = settings.activeConfig()
                    if (cfg.apiKey.isBlank()) {
                        advice = "请先在『设置』中填写 ${cfg.kind.label} 的 API Key。"
                        return@Button
                    }
                    loadingAdvice = true
                    scope.launch {
                        val coach = LlmProviders.create(cfg)
                        try {
                            advice = coach.coach(
                                systemPrompt = Prompts.HOLDEM_SYSTEM,
                                userPrompt = Prompts.holdemUser(
                                    table = table,
                                    equityPct = equityPct,
                                    preflopBaseline = if (table.street == Street.PREFLOP) preflopBaseline else null,
                                    outs = outs,
                                ),
                            )
                        } catch (t: Throwable) {
                            advice = "请求失败：${t.message}"
                        } finally {
                            coach.close()
                            loadingAdvice = false
                        }
                    }
                },
                enabled = !loadingAdvice,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (loadingAdvice) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("AI 教练思考中…")
                } else {
                    Text("让 AI 教练分析")
                }
            }

            advice?.let {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Text(
                        it,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun TableCard(table: HoldemTable, equityPct: Double?, loadingEquity: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0E7C3A)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "公共牌",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (table.board.isEmpty()) {
                    repeat(5) { CardSlot() }
                } else {
                    table.board.forEach { CardView(it) }
                    repeat(5 - table.board.size) { CardSlot() }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "我的手牌",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                table.hero.forEach { CardView(it) }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("底池 ${table.pot}", color = Color.White)
                Text("跟注 ${table.toCall}", color = Color.White)
                if (loadingEquity) {
                    Text("胜率 …", color = Color.White)
                } else {
                    equityPct?.let {
                        val rounded = kotlin.math.round(it * 10) / 10
                        Text("胜率 ≈ $rounded%", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun CardView(card: PokerCard) {
    Box(
        modifier = Modifier
            .size(width = 42.dp, height = 58.dp)
            .background(Color.White, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            card.label,
            color = if (card.isRed) Color(0xFFB00020) else Color.Black,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun CardSlot() {
    Box(
        modifier = Modifier
            .size(width = 42.dp, height = 58.dp)
            .background(Color(0x33FFFFFF), RoundedCornerShape(6.dp)),
    )
}
