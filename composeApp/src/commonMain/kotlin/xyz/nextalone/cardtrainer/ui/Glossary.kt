package xyz.nextalone.cardtrainer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class GlossaryKind { POKER, MAHJONG }

private val POKER_TERMS: List<Pair<String, String>> = listOf(
    "Position / 位置" to "UTG 枪口最前、MP 中位、CO 庄前、BTN 按钮、SB 小盲、BB 大盲。越靠后越有利。",
    "RFI (Raise-First-In)" to "翻前在你之前没人加注时你主动开池的起手牌范围。位置越靠后 RFI 范围越宽。",
    "Pot Odds / 底池赔率" to "跟注额 ÷ (底池 + 跟注额)。胜率高于这个比例才值得跟注。",
    "Implied Odds / 隐含赔率" to "考虑后续街可能从对手身上再多赢多少，用来放宽当前跟注的胜率要求。",
    "Equity / 胜率" to "你当前手牌对抗对手范围到河牌摊牌时的胜出概率（含平分）。",
    "Range / 范围" to "对手可能持有的所有手牌集合，不是单一手牌。高手永远在想 range 而非具体牌。",
    "Blocker / 阻断牌" to "你手里有的关键张，使对手范围中强牌减少——用于诈唬或 hero call 的理由。",
    "Outs" to "后续街能让你变成最强牌的张数。",
    "Rule of 2 / Rule of 4" to "翻牌后：outs ×2 ≈ 下一张命中率；outs ×4 ≈ 两张命中率。粗略心算。",
    "EV (Expected Value)" to "该决策的长期平均收益（正 EV = 赚钱）。我们追求每个决策都是 EV 最大或相当。",
    "3-bet / 4-bet" to "开局加注 = 2-bet，再加注 = 3-bet，再再加注 = 4-bet。",
    "Pot-sized bet" to "下注等于当前底池大小。让对手需要 33% 胜率才划算跟注。",
    "All-in / 全下" to "推全部筹码。正确场景：牌极强要最大化价值，或 SPR 低时保护。",
    "SPR (Stack-to-Pot Ratio)" to "当前剩余筹码 / 底池。SPR 低时对子即可全下，SPR 高时需要两对以上。",
    "GTO" to "Game-Theory Optimal，博弈论最优、不可被剥削的策略基线。实战中常对业余玩家做剥削性偏离。",
)

private val MAHJONG_TERMS: List<Pair<String, String>> = listOf(
    "定缺 / 缺一门" to "开局宣告一门不要（万/条/筒之一）；胡牌时手上不能有这门牌。",
    "听牌" to "再摸进一张就能胡牌的状态。听越多张越容易胡。",
    "向听数（shanten）" to "距离听牌还差几次有效换牌。0 = 已听牌；1 = 一向听；2 = 两向听。",
    "有效进张 / 进张" to "能让你向听数减少（更接近听牌/胡牌）的牌。",
    "现张" to "已被打出或吃碰杠过的牌，桌面可见。越多的现张越不危险。",
    "平胡" to "4 副面子（顺子或刻子）+ 1 对将。最基础的胡牌型。",
    "对对胡" to "4 副刻子 + 1 对，无顺子。又叫大对子。",
    "七对" to "7 个完全不同的对子组成 14 张手牌。（不能有 4 张一样，不然就升级为…）",
    "龙七对" to "七对中有一组 4 张相同（暗杠级别），分数翻倍。",
    "清一色" to "整副手牌全部来自同一花色。大番。",
    "根" to "手中每有 4 张完全相同（或暗杠明杠）记 1 根，每根翻 1 倍分。",
    "刮风下雨" to "血战到底计分：别人放杠给你 = 刮风，自己暗杠/补杠 = 下雨。贡献分数。",
    "血战到底" to "一人胡牌后游戏继续，直到 3 人胡或牌墙用尽，未胡者查叫/查花猪。",
    "花猪" to "游戏结束时手里还有缺门牌的玩家，要向其他人赔分。",
    "查叫" to "游戏结束时未胡者如果听牌，少赔；如果没听牌（黄庄），要赔给听牌者。",
    "明杠 / 暗杠 / 补杠" to "明杠=别人打的牌杠下，暗杠=自己摸到 4 张，补杠=已碰的牌再摸到第 4 张。得分不同。",
    "安全度" to "评估一张牌打出后被他人胡的风险。现张最安全，中张（4/5/6）相对危险。",
)

@Composable
fun GlossaryDialog(kind: GlossaryKind, onDismiss: () -> Unit) {
    val (title, terms) = when (kind) {
        GlossaryKind.POKER -> "德州扑克 术语速查" to POKER_TERMS
        GlossaryKind.MAHJONG -> "四川麻将 术语速查" to MAHJONG_TERMS
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                terms.forEach { (term, explain) ->
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
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("明白了") }
        },
    )
}
