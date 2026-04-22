package xyz.nextalone.cardtrainer.coach

import xyz.nextalone.cardtrainer.engine.holdem.Action
import xyz.nextalone.cardtrainer.engine.holdem.DrawSummary
import xyz.nextalone.cardtrainer.engine.holdem.HandCategory
import xyz.nextalone.cardtrainer.engine.holdem.HoldemTable
import xyz.nextalone.cardtrainer.engine.holdem.OutsReport
import xyz.nextalone.cardtrainer.engine.holdem.PreflopAction
import xyz.nextalone.cardtrainer.engine.holdem.multiway.MultiwayTable
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
    - 使用 Markdown 排版。所有对比必须用 GFM 管道表格，并严格按如下模板：
        * 表格前后各有一个空行；
        * 表头首尾都带 `|`；
        * 表头正下方必须紧跟一行"分隔行"，每列写 `---`（或 `:---`），列数与表头一致。
      **正确示例**（请逐字对照）：

          | 方案 | 尺度 | 估算 EV | 说明 |
          | --- | --- | --- | --- |
          | 过牌 | 0 | 基准 | 保留诱捕空间 |
          | 下注 | 50% pot | +0.3bb | 薄价值下注 |

      禁止把单元格内容各自换行成独立段落。禁止忽略分隔行。
    - 若引用了数字（胜率/底池/outs/向听）请使用上方【】里给出的值，不要自行臆造。
""".trimIndent()

object Prompts {

    val HOLDEM_SYSTEM = """
        你是一位高水平的德州扑克（No-Limit Hold'em）教练，语言为中文。
        请依据位置（position）、牌力、底池赔率（pot odds）、隐含赔率、对手范围（range）、
        以及阻断牌（blocker）给出分析。

        两种模式：
        (A) 若用户未做决策，给出【推荐动作】+【简短理由 ≤ 3 条】+【常见偏差提示】。
        (B) 若用户已选择了某个动作：
            **首行必须严格输出一行评分**（整行仅此内容），格式：
                `【评分：X.X / 5】`
            其中 X.X 为一位小数，取值 0.0–5.0，按如下档位给分：
              * 5.0   与基线完全一致或优于基线；
              * 4.0–4.9 方向正确，尺度略有出入；
              * 3.0–3.9 可接受，存在明显改进空间；
              * 2.0–2.9 偏离基线，EV 损失中等；
              * 1.0–1.9 较差，EV 损失明显；
              * 0.0–0.9 严重错误。
            然后依次输出：
              1. 【结论】优秀 / 可接受 / 不推荐（三档之一，应与评分档匹配）；
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

    /**
     * Analysis slots for the multiway screen. Each produces a different tail
     * instruction and is shown in its own tab:
     *  - SITUATION: no decision yet — give a recommendation;
     *  - EVALUATION: decision submitted — grade it;
     *  - STREET_RECAP: street closed — summarise the whole table's line.
     */
    enum class MultiwayAnalysisMode { SITUATION, EVALUATION, STREET_RECAP, HAND_RECAP }

    /**
     * One hero submission on a street. Snapshot state is captured at submit
     * time so the coach sees the real context the user faced — pot / toCall
     * / action-history-so-far — rather than the post-street aggregate.
     */
    data class HeroStreetAction(
        val potBefore: Int,
        val toCall: Int,
        val currentBet: Int,
        val stack: Int,
        val priorHistoryLine: String,
        val action: Action,
        val amount: Int,
    )

    /**
     * Street-end multi-decision evaluation. The hero may have acted 1..N times
     * on the street; we send every decision with its snapshot and require a
     * distinct `【评分 i：X.X / 5】` line per decision so the client can show
     * per-turn scores instead of a single aggregate.
     */
    fun holdemUserEvaluateStreet(
        table: MultiwayTable,
        actions: List<HeroStreetAction>,
    ): String = buildString {
        val hero = table.hero
        val heroCards = hero.cards ?: error("hero must have cards")
        append("【当前街】${table.street}\n")
        append("【位置】${hero.position.label}，存活对手数：${table.seats.count { !it.isHero && it.isLive } }\n")
        append("【手牌】${heroCards.joinToString(" ") { it.label }}\n")
        if (table.board.isNotEmpty()) {
            append("【公共牌】${table.board.joinToString(" ") { it.label }}\n")
        }
        append("【本街完整行动线】\n")
        for (h in table.history.filter { it.street == table.street }) {
            val who = h.actor?.label ?: "?"
            val amt = if (h.amount > 0) " ${h.amount}" else ""
            append("  - $who ${h.action.label}$amt\n")
        }
        append("【你的本街决策回放 · 共 ${actions.size} 次】\n")
        actions.forEachIndexed { i, a ->
            val n = i + 1
            val historyFragment = a.priorHistoryLine.takeIf { it.isNotBlank() } ?: "（你是首个行动）"
            val amtText = if (a.amount > 0) " ${a.amount}" else ""
            append("  $n. 底池 ${a.potBefore} · 跟注 ${a.toCall} · 对手下注档 ${a.currentBet} · 剩余栈 ${a.stack}；当时行动线：$historyFragment；你选择 ${a.action.label}$amtText\n")
        }
        append("\n请对本街每次决策分别独立打分。输出规范（严格遵守，顺序与编号对应）：\n")
        actions.indices.forEach { i ->
            val n = i + 1
            append("  【评分 $n：X.X / 5】（然后用 1-2 句中文评语说明该次决策的偏差或正确之处）\n")
        }
        append("评分档位与单次 EVALUATION 相同：5.0 与基线一致 / 4.x 方向正确 / 3.x 可接受 / 2.x EV 中度损失 / <2 严重错误。")
        append("评语中如对比替代方案，请给出具体尺度（pot 百分比 或 bb）。")
        append("本次不需要最终的总结 / 表格；每次决策单独一段即可。")
    }

    /**
     * Multiway overload. Differs from the heads-up prompt in how villain info
     * is framed: we enumerate every live opponent's position + remaining stack
     * and reconstruct the action line with per-seat actors so the coach can
     * reason about 3-bet squeezes, cold-calls, and multi-way equity loss
     * instead of pretending it's a single villain.
     */
    fun holdemUser(
        table: MultiwayTable,
        equityPct: Double?,
        preflopBaseline: PreflopAction?,
        outs: OutsReport?,
        madeHand: HandCategory? = null,
        draws: List<DrawSummary> = emptyList(),
        userChoice: Pair<Action, Int>? = null,
        mode: MultiwayAnalysisMode = if (userChoice != null) {
            MultiwayAnalysisMode.EVALUATION
        } else {
            MultiwayAnalysisMode.SITUATION
        },
    ): String = buildString {
        val hero = table.hero
        val heroCards = hero.cards
            ?: error("hero seat must have hole cards when building a prompt")
        val liveCount = table.seats.count { it.isLive }
        append("【当前街】${table.street}\n")
        // Flag early-terminated hands so the coach knows not to talk about
        // a showdown that never happened. MultiwayEngine marks street ==
        // SHOWDOWN when only one live seat remains, even if the hand folded
        // on flop with a 3-card board.
        if (table.street.name == "SHOWDOWN") {
            val ended = when {
                liveCount <= 1 -> "单独存活 → 提前结束（未摊牌）"
                table.board.size < 5 -> "提前结束，公共牌仅 ${table.board.size} 张（未摊牌）"
                else -> "走完 river，进入摊牌"
            }
            append("【本手状态】$ended\n")
        }
        append("【位置】${hero.position.label}，存活对手数：${liveCount - 1}\n")
        append("【手牌】${heroCards.joinToString(" ") { it.label }}\n")
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
        append("【底池】${table.pot}，【跟注额】${table.heroToCall}，【我的筹码】${hero.stack}\n")
        val otherLive = table.seats.filter { !it.isHero && it.isLive }
        if (otherLive.isNotEmpty()) {
            append("【对手筹码】")
            append(otherLive.joinToString("、") { "${it.position.label}:${it.stack}" })
            append('\n')
        }
        if (table.heroToCall > 0) {
            val potOdds = table.heroToCall.toDouble() / (table.pot + table.heroToCall)
            val odds = kotlin.math.round(potOdds * 1000) / 10
            append("【底池赔率】${odds}%（所需胜率阈值）\n")
        }
        if (equityPct != null) {
            val e = kotlin.math.round(equityPct * 10) / 10
            append("【蒙特卡洛胜率】${e}%\n")
        }
        if (preflopBaseline != null) {
            append("【翻前基线】位置 ${hero.position.label} 的 RFI 建议：$preflopBaseline\n")
        }
        if (outs != null) {
            append("【Outs】${outs.outs} 张（Turn ≈ ${outs.turnPct}%，Turn+River ≈ ${outs.turnAndRiverPct}%）\n")
        }
        if (table.history.isNotEmpty()) {
            append("【行动历史】\n")
            for (h in table.history) {
                val actor = h.actor?.label ?: "?"
                val amtText = if (h.amount > 0) " ${h.amount}" else ""
                append("  - ${h.street} $actor ${h.action.label}$amtText\n")
            }
        }
        if (userChoice != null) {
            val (act, amt) = userChoice
            val amtText = if (amt > 0) " $amt" else ""
            append("【用户做出的决策】${act.label}$amtText\n")
        }
        val tail = when (mode) {
            MultiwayAnalysisMode.SITUATION ->
                "请按模式 (A) 给出本街的推荐决策与理由。" +
                    "重点说明前置位动作如何收窄他们的 range、从而影响你的最佳反应。" +
                    "本次不输出首行的『【评分】』。"
            MultiwayAnalysisMode.EVALUATION ->
                "请按模式 (B) 评估该决策。"
            MultiwayAnalysisMode.STREET_RECAP ->
                "本街所有玩家的行动均已完成。请给出全桌回顾：\n" +
                    "1. 逐家简述其行动是否符合合理 range / 位置基线，指出明显偏差；\n" +
                    "2. 底池演化与 SPR 变化对后续街的影响；\n" +
                    "3. 你（hero）本街的整体贡献价值（EV 感）与可改进的地方。\n" +
                    "若牌局已结束，只回顾至此街；不要预测尚未翻出的公共牌。" +
                    "本次不输出首行的『【评分】』。"
            MultiwayAnalysisMode.HAND_RECAP ->
                "本手牌已彻底结束。请给出整手回顾：\n" +
                    "1. 关键转折点：哪些 action 决定了本手走势（不仅是 hero，也包括对手）；\n" +
                    "2. hero 的整体思路与实际 EV / 真实胜率的差距，是否有情绪 / 位置 / 底池控制方面的系统性倾向；\n" +
                    "3. 与基线相比的 1-2 个可以下次复盘时重点练习的点；\n" +
                    "4. 若【本手状态】为已摊牌，点评 showdown 牌型分布是否符合预期 range；" +
                    "若为提前结束，则评估 hero 推动/收下这手的施压是否恰当，不要虚构未出现的公共牌或对手底牌。\n" +
                    "语气教练式、具体、避免重复【牌局进程】已罗列的事实。" +
                    "本次不输出首行的『【评分】』。"
        }
        append(tail)
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
