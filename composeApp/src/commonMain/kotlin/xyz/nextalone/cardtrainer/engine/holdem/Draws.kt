package xyz.nextalone.cardtrainer.engine.holdem

/**
 * Detect **draws** on the flop/turn — hands that haven't yet formed a made
 * category but are close to doing so. Most useful coaching feedback beyond
 * the flat "current best hand" category.
 *
 * Covered draws (single hero hand + 3–4 board cards):
 *  - Flush draw (4 to one suit → 9 outs)
 *  - Backdoor flush draw (3 to one suit on flop only → 2-runner ~4.2%)
 *  - Open-ended straight draw (4 consecutive ranks, 2 open ends → 8 outs)
 *  - Gutshot (4 of 5 for a straight with one gap → 4 outs)
 *  - Overcards (both hero cards strictly higher than every board rank →
 *    6 outs to top-pair)
 */
data class DrawSummary(val tag: String, val outs: Int)

object Draws {

    fun detect(hero: List<Card>, board: List<Card>): List<DrawSummary> {
        require(hero.size == 2)
        if (board.size !in 3..4) return emptyList()

        val cards = hero + board
        val best = HandEvaluator.evaluate(cards)

        // If we already have a straight/flush/fullhouse etc., draws are
        // subsumed by the made hand and there's nothing actionable to surface.
        if (best.category.ordinal >= HandCategory.STRAIGHT.ordinal) return emptyList()

        val draws = mutableListOf<DrawSummary>()

        detectFlushDraw(cards, boardSize = board.size)?.let { draws += it }
        detectStraightDraw(cards)?.let { draws += it }

        if (best.category == HandCategory.HIGH_CARD) {
            detectOvercards(hero, board)?.let { draws += it }
        }

        return draws
    }

    private fun detectFlushDraw(cards: List<Card>, boardSize: Int): DrawSummary? {
        val bySuit = cards.groupBy { it.suit }.mapValues { it.value.size }
        val maxSame = bySuit.values.max()
        return when {
            maxSame >= 4 -> DrawSummary("同花听牌", outs = 9)
            maxSame == 3 && boardSize == 3 -> DrawSummary("后门同花听牌", outs = 3)
            else -> null
        }
    }

    /** Ace counts as both 1 and 14 for wheel (A-2-3-4-5). */
    private fun detectStraightDraw(cards: List<Card>): DrawSummary? {
        val ranks = cards.map { it.rank.value }.toMutableSet()
        if (ranks.contains(14)) ranks += 1 // wheel support
        if (ranks.size < 4) return null

        // Scan every 5-length rank window [low, low+4] and count how many of
        // the five required ranks we have. 4 of 5 = a draw.
        var bestType: String? = null
        var bestOuts = 0
        for (low in 1..10) {
            val window = (low..low + 4).toList()
            val hits = window.count { it in ranks }
            if (hits != 4) continue
            val missing = window.single { it !in ranks }
            val isOpenEnded = (missing == low || missing == low + 4) &&
                // open-ended only counts if the 4 hits form a true run;
                // the inner 3 ranks must all be present.
                (low + 1..low + 3).all { it in ranks } &&
                // Can't be "open-ended" if one end is the deck-edge (A/ace-high
                // counts as open via wheel; straight to A is not open since
                // nothing beyond A).
                missing !in listOf(0, 15)
            val (type, outs) = when {
                // A-high (T-J-Q-K-A) and wheel (A-2-3-4-5) are one-sided → gutshot-style 4 outs
                low == 10 && missing == 14 -> "卡顺听牌" to 4
                low == 1 && missing == 1 -> "卡顺听牌（轮子）" to 4
                isOpenEnded -> "开放式顺子听牌" to 8
                else -> "卡顺听牌" to 4
            }
            // Prefer the biggest draw we find across all windows.
            if (outs > bestOuts) {
                bestOuts = outs
                bestType = type
            }
        }
        return bestType?.let { DrawSummary(it, bestOuts) }
    }

    private fun detectOvercards(hero: List<Card>, board: List<Card>): DrawSummary? {
        val topBoard = board.maxOf { it.rank.value }
        val both = hero.all { it.rank.value > topBoard }
        return if (both) DrawSummary("双超张", outs = 6) else null
    }
}
