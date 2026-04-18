package xyz.nextalone.cardtrainer.coach

import xyz.nextalone.cardtrainer.engine.holdem.Action
import xyz.nextalone.cardtrainer.engine.holdem.DrawSummary
import xyz.nextalone.cardtrainer.engine.holdem.HandCategory
import xyz.nextalone.cardtrainer.engine.holdem.HoldemTable
import xyz.nextalone.cardtrainer.engine.holdem.OutsReport
import xyz.nextalone.cardtrainer.engine.holdem.PreflopAction
import xyz.nextalone.cardtrainer.engine.mahjong.DiscardSuggestion
import xyz.nextalone.cardtrainer.engine.mahjong.HandTypeReport
import xyz.nextalone.cardtrainer.engine.mahjong.LiveWait
import xyz.nextalone.cardtrainer.engine.mahjong.SafetyScore
import xyz.nextalone.cardtrainer.engine.mahjong.Suit
import xyz.nextalone.cardtrainer.engine.mahjong.Tile

private val OUTPUT_RULES = """

    输出约束（必须遵守）：
    - 全部使用简体中文。不得出现任何英文段落、推理过程（chain-of-thought）、或
      以 "We need to" / "Actually" / "Let me" 等开头的自语思考内容。
    - 禁止输出 <think>…</think> 或 <thinking>…</thinking> 块，也禁止把内部推理
      写进正文。直接给最终结论即可。
    - 使用 Markdown 排版；需要对比时使用 GFM 管道表格（| 列1 | 列2 |）而不是
      换行罗列。
    - 若引用了数字（胜率/底池/outs/向听）请使用上方【】里给出的值，不要自行臆造。
""".trimIndent()

object Prompts {

    val HOLDEM_SYSTEM = """
        你是一位高水平的德州扑克（No-Limit Hold'em）教练，语言为中文。
        请依据位置（position）、牌力、底池赔率（pot odds）、隐含赔率、对手范围（range）、
        以及阻断牌（blocker）给出分析。

        两种模式：
        (A) 若用户未做决策，给出【推荐动作】+【简短理由 ≤ 3 条】+【常见偏差提示】。
        (B) 若用户已选择了某个动作，则同时输出：
            1. 【结论】优秀 / 可接受 / 不推荐（三档之一）；
            2. 【对比】用 GFM 管道表格列出当前动作 vs 更优替代方案的尺度、EV 估算；
            3. 【用户思路推断】：基于其动作反推用户可能的思维 —— 牌力估计、对手范围、
               情绪偏差（如赌徒谬误、sunk cost、恐惧弃牌等）。精炼，1–2 条。

        所有输出都要给出明确下注尺度（big blinds 或 pot 百分比）。风格：精炼、可操作。
    """.trimIndent() + "\n" + OUTPUT_RULES

    fun holdemUser(
        table: HoldemTable,
        equityPct: Double?,
        preflopBaseline: PreflopAction?,
        outs: OutsReport?,
        madeHand: HandCategory? = null,
        draws: List<DrawSummary> = emptyList(),
        userChoice: Pair<Action, Int>? = null,
    ): String = buildString {
        append("【当前街】${table.street}\n")
        append("【位置】${table.heroPosition.label}，对手数：${table.opponents}\n")
        append("【手牌】${table.hero.joinToString(" ") { it.label }}\n")
        if (table.board.isNotEmpty()) {
            append("【公共牌】${table.board.joinToString(" ") { it.label }}\n")
        }
        if (madeHand != null && table.board.size >= 3) {
            val label = if (table.street.name == "RIVER") "最终牌型" else "当前已成牌型"
            append("【$label】${madeHand.displayName}\n")
        }
        if (draws.isNotEmpty() && table.board.size in 3..4) {
            append("【听牌】")
            append(draws.joinToString("、") { "${it.tag}(${it.outs} outs)" })
            append('\n')
        }
        append("【底池】${table.pot}，【跟注额】${table.toCall}，【我的筹码】${table.heroStack}，【对手筹码】${table.villainStack}\n")
        if (table.toCall > 0) {
            val odds = (table.potOdds * 100).let { kotlin.math.round(it * 10) / 10 }
            append("【底池赔率】${odds}%（所需胜率阈值）\n")
        }
        if (equityPct != null) {
            val e = kotlin.math.round(equityPct * 10) / 10
            append("【蒙特卡洛胜率】${e}%\n")
        }
        if (preflopBaseline != null) {
            append("【翻前基线】位置 ${table.heroPosition.label} 的 RFI 建议：$preflopBaseline\n")
        }
        if (outs != null) {
            append("【Outs（任意牌型升级）】${outs.outs} 张（Turn ≈ ${outs.turnPct}%，Turn+River ≈ ${outs.turnAndRiverPct}%）\n")
        }
        if (table.history.isNotEmpty()) {
            append("【行动历史】\n")
            for (h in table.history) {
                append("  - ${h.street} ${h.action.label} ${h.amount}\n")
            }
        }
        if (userChoice != null) {
            val (act, amt) = userChoice
            val amtText = if (amt > 0) " $amt" else ""
            append("【用户做出的决策】${act.label}$amtText\n")
            append("请按模式 (B) 评估该决策。")
        } else {
            append("请按模式 (A) 给出本街的推荐决策与理由。")
        }
    }

    val MAHJONG_SYSTEM = """
        你是一位高水平的四川麻将（血战到底）教练，语言为中文。规则要点：
        - 108 张牌（万/条/筒，各 4 张），不用字牌/花牌。
        - 开局必须“缺一门”，听牌/胡牌时手上不能有这门牌。
        - 胡法含 平胡、七对、清一色、对对胡、大对子（根） 等；杠牌有明杠/暗杠/补杠 区别。
        输出结构：
        1) 推荐打出的牌（若牌型已听，则指出听的牌与胡牌数）。
        2) 原因（向听数变化、进张数、牌型潜力、弃牌者安全度）。
        3) 注意事项（缺门一致性、防炮、杠上花/抢杠胡 等关键时机）。
        风格：简练、直给操作，不啰嗦。
    """.trimIndent() + "\n" + OUTPUT_RULES

    fun mahjongUser(
        hand: List<Tile>,
        missing: Suit,
        discards: List<Tile>,
        suggestions: List<DiscardSuggestion>,
        wallRemaining: Int,
        liveWaits: List<LiveWait>,
        safety: List<SafetyScore>,
        handType: HandTypeReport?,
    ): String = buildString {
        append("【缺门】${missing.cn}\n")
        append("【手牌】${hand.joinToString(" ") { it.label }}\n")
        if (discards.isNotEmpty()) {
            append("【我已弃】${discards.joinToString(" ") { it.label }}\n")
        }
        append("【剩余牌墙】${wallRemaining}\n")
        if (handType != null && handType.labels.isNotEmpty()) {
            append("【牌型】${handType.labels.joinToString("、")}\n")
        }
        if (liveWaits.isNotEmpty()) {
            append("【有效进张（剩余枚数）】")
            append(liveWaits.joinToString(" ") { "${it.tile.label}×${it.remaining}" })
            append('\n')
        }
        if (suggestions.isNotEmpty()) {
            append("【候选弃牌（综合评分）】\n")
            for (s in suggestions) {
                val tag = when {
                    s.shantenAfter < 0 -> "打出即胡"
                    s.shantenAfter == 0 -> "听${s.waitSize}张"
                    else -> "向听${s.shantenAfter}"
                }
                append("  - ${s.tile.label}  → $tag\n")
            }
        }
        if (safety.isNotEmpty()) {
            append("【弃牌安全度 Top】")
            append(safety.take(3).joinToString("；") { "${it.tile.label}(${it.score})" })
            append('\n')
        }
        append("请综合上面的向听、进张、安全度推荐本回合弃牌，并说明理由。")
    }
}
