package xyz.nextalone.cardtrainer.engine.mahjong

/**
 * 定缺推荐 (recommend which suit to declare as 缺).
 *
 * Cheap per-suit usefulness heuristic — the previous shanten-based metric
 * froze the "发 13 张" flow because brute-force shanten at depth 4 over 3
 * suits was multi-second on the UI thread.
 *
 * Scoring: triplets ×3 + pairs ×2 + full runs ×3 + partial runs ×1.
 * The suit with the lowest score is the best 缺 candidate (hurts the hand
 * least). Ties broken by fewer tiles in that suit (cheaper to clear).
 */
data class DingQueAdvice(val suit: Suit, val score: Int, val countInSuit: Int)

object DingQue {

    fun recommend(thirteen: List<Tile>): List<DingQueAdvice> {
        require(thirteen.size == 13)
        return Suit.entries.map { s ->
            val slot = IntArray(9)
            for (t in thirteen) if (t.suit == s) slot[t.number - 1]++
            var useful = 0
            for (i in 0..8) useful += (slot[i] / 3) * 3           // triplets
            for (i in 0..8) useful += ((slot[i] % 3) / 2) * 2     // pairs
            for (i in 0..6) {                                     // runs
                val a = slot[i]; val b = slot[i + 1]; val c = slot[i + 2]
                if (a > 0 && b > 0 && c > 0) useful += 3
                else if ((a > 0 && b > 0) || (b > 0 && c > 0)) useful += 1
            }
            DingQueAdvice(suit = s, score = useful, countInSuit = slot.sum())
        }.sortedWith(
            compareBy<DingQueAdvice> { it.score }.thenBy { it.countInSuit },
        )
    }
}
