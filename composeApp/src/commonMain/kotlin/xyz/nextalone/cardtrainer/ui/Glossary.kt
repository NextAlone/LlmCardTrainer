package xyz.nextalone.cardtrainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class GlossaryKind { POKER, MAHJONG }

private val POKER_TERMS: List<Pair<String, String>> = listOf(
    // Positions
    "UTG (Under the Gun)" to "枪口位 — 翻前第一个行动，位置最差。RFI 范围最窄（约 12–15%），只玩强牌。",
    "MP (Middle Position)" to "中位 — UTG 之后。RFI 略宽（约 17–19%），仍需偏紧。",
    "HJ / CO (Hijack / Cutoff)" to "庄前一位 / 庄前位。位置开始转好，CO 是偷盲机会位，RFI 可达 28%+。",
    "BTN (Button / 庄位)" to "按钮位 — 翻后永远最后行动，位置最好。RFI 可达 45%+，是拿最多利润的位置。",
    "SB (Small Blind / 小盲)" to "已下半个大盲的强制盲注。翻前在庄后，翻后位置最差（永远 OOP）。需偏紧且偏 3-bet。",
    "BB (Big Blind / 大盲)" to "已下一个大盲的强制盲注。翻前最后行动，有折扣跟注；翻后 OOP。",
    "IP / OOP" to "In-Position / Out-of-Position。翻后先 / 后手。IP 看得到对手动作再决策，价值巨大。",

    // Streets & flow
    "Street / 街" to "一轮下注。顺序：Preflop → Flop（翻牌/3 张公共）→ Turn（转牌/1 张）→ River（河牌/1 张）→ Showdown（摊牌）。",
    "Preflop / 翻前" to "发完底牌、翻牌前的下注轮。决策基于起手牌 + 位置 + 对手行动。",
    "Flop / 翻牌" to "翻 3 张公共牌后的下注轮。此时有 71% 信息，c-bet 频率是核心话题。",
    "Turn / 转牌" to "翻 4 张公共牌后的下注轮。底池通常已变大，错误成本高。",
    "River / 河牌" to "最后 1 张公共牌、最后一轮下注。无后续街，只剩价值/诈唬二分决策。",
    "Showdown / 摊牌" to "河牌跟注后亮牌比大小。牌面强弱按 5 张最佳组合判定。",

    // Odds & equity
    "RFI (Raise-First-In)" to "翻前在你之前没人加注时你主动开池的起手牌范围。位置越靠后 RFI 范围越宽。",
    "Pot Odds / 底池赔率" to "跟注额 ÷ (底池 + 跟注额)。胜率高于这个比例才值得跟注。",
    "Implied Odds / 隐含赔率" to "考虑后续街可能从对手身上再多赢多少，用来放宽当前跟注的胜率要求。",
    "Equity / 胜率" to "你当前手牌对抗对手范围到河牌摊牌时的胜出概率（含平分）。",
    "Range / 范围" to "对手可能持有的所有手牌集合，不是单一手牌。高手永远在想 range 而非具体牌。",
    "Blocker / 阻断牌" to "你手里有的关键张，使对手范围中强牌减少——用于诈唬或 hero call 的理由。",
    "Outs" to "后续街能让你变成最强牌的张数。",
    "Rule of 2 / Rule of 4" to "翻牌后：outs ×2 ≈ 下一张命中率；outs ×4 ≈ 两张命中率。粗略心算。",
    "EV (Expected Value)" to "该决策的长期平均收益（正 EV = 赚钱）。我们追求每个决策都是 EV 最大或相当。",

    // Actions
    "3-bet / 4-bet / 5-bet" to "开局加注 = 2-bet，再加注 = 3-bet，再再加注 = 4-bet。5-bet 通常等于全下。",
    "C-bet (Continuation bet)" to "翻前加注人在翻牌圈继续下注。职业常用的主动控池动作。",
    "Check-raise / 过牌加注" to "先 check 诱导对手下注后再加注。强度极高，对手面对极难处理。",
    "Float / 浮动" to "翻牌弱跟注，计划后续街接管底池的偷池打法。",
    "Squeeze / 挤压" to "有人开池、有人跟注后你加注。利用多人低折扣，目标拿死钱。",
    "Isolation / 单挑加注" to "对付跛入者（limper）的加注，把他们单挑到你的位置上。",
    "Limp / 跛入" to "翻前仅跟大盲不加注。在现代 NLH 里是弱势打法（除了 SB vs BB 的部分策略）。",
    "Pot-sized bet" to "下注等于当前底池大小。让对手需要 33% 胜率才划算跟注。",
    "All-in / 全下" to "推全部筹码。正确场景：牌极强要最大化价值，或 SPR 低时保护。",
    "SPR (Stack-to-Pot Ratio)" to "当前剩余筹码 / 底池。SPR 低时对子即可全下，SPR 高时需要两对以上。",
    "GTO" to "Game-Theory Optimal，博弈论最优、不可被剥削的策略基线。实战中常对业余玩家做剥削性偏离。",

    // Hand rankings (quick reference)
    "牌型大小（从大到小）" to "皇家同花顺 > 同花顺 > 四条 > 葫芦 > 同花 > 顺子 > 三条 > 两对 > 一对 > 高牌。",
)

private val MAHJONG_TERMS: List<Pair<String, String>> = listOf(
    // Core rules
    "血战到底" to "四川玩法：一人胡牌后游戏继续，直到 3 人胡或牌墙用尽，未胡者查叫 / 查花猪。",
    "定缺 / 缺一门" to "开局宣告一门不要（万/条/筒之一）；胡牌时手上不能有这门牌。",
    "万 / 条 / 筒" to "四川麻将只用三门花色各 1–9 各 4 张，共 108 张。无字牌、花牌。",

    // Hand states
    "听牌" to "再摸进一张就能胡牌的状态。听越多张越容易胡。",
    "向听数（shanten）" to "距离听牌还差几次有效换牌。0 = 已听牌；1 = 一向听；2 = 两向听。-1 = 已胡。",
    "有效进张 / 进张" to "能让你向听数减少（更接近听牌/胡牌）的牌。",
    "摸牌" to "从牌墙抓一张进手。本应用里打牌后自动摸下一张，手牌始终保持 14 张进行决策。",
    "弃牌 / 打牌" to "手中选一张打出。本应用点击该牌即可，系统自动摸下一张。",

    // Visibility concepts
    "现张" to "已被打出或吃碰杠过的牌，桌面可见。越多的现张越不危险。",
    "生张" to "至今没有出现过的牌。打生张有潜在风险。",
    "安全度" to "评估一张牌打出后被他人胡的风险。现张最安全，中张（4/5/6）相对危险。",

    // Winning types
    "平胡" to "4 副面子（顺子或刻子）+ 1 对将。最基础的胡牌型。",
    "对对胡" to "4 副刻子 + 1 对，无顺子。又叫大对子。",
    "七对" to "7 个完全不同的对子组成 14 张手牌。（不能有 4 张一样，不然就升级为龙七对）",
    "龙七对" to "七对中有一组 4 张相同（暗杠级别），分数翻倍。",
    "清一色" to "整副手牌全部来自同一花色。大番。",
    "根" to "手中每有 4 张完全相同（或暗杠明杠）记 1 根，每根翻 1 倍分。",
    "杠上花" to "开杠后摸的那张完成胡牌，额外加番。",
    "抢杠胡" to "别人补杠时你正好听这张，可直接胡走对方这张牌。",

    // Gangs / scoring
    "明杠 / 暗杠 / 补杠" to "明杠=别人打的牌杠下，暗杠=自己摸到 4 张，补杠=已碰的牌再摸到第 4 张。得分不同。",
    "刮风下雨" to "血战到底计分：别人放杠给你 = 刮风，自己暗杠/补杠 = 下雨。贡献分数。",

    // End-game
    "花猪" to "游戏结束时手里还有缺门牌的玩家，要向其他人赔分。",
    "查叫" to "游戏结束时未胡者如果听牌，少赔；如果没听牌（黄庄），要赔给听牌者。",
    "黄庄" to "牌墙用尽没人胡。未听牌者赔给听牌者。",
)

@Composable
fun GlossaryDialog(kind: GlossaryKind, onDismiss: () -> Unit) {
    val (title, terms) = when (kind) {
        GlossaryKind.POKER -> "德州扑克 术语速查" to POKER_TERMS
        GlossaryKind.MAHJONG -> "四川麻将 术语速查" to MAHJONG_TERMS
    }
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, terms) {
        if (query.isBlank()) terms
        else {
            val q = query.trim().lowercase()
            terms.filter { (term, explain) ->
                term.lowercase().contains(q) || explain.lowercase().contains(q)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("搜索术语 / 解释…") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (filtered.isEmpty()) {
                    Text(
                        "未命中，换个关键词？",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        filtered.forEach { (term, explain) ->
                            Column(Modifier.padding(vertical = 2.dp)) {
                                Text(
                                    term,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.padding(top = 2.dp))
                                Text(
                                    explain,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
