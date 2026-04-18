package xyz.nextalone.cardtrainer.engine.mahjong

/**
 * Sichuan blood-battle (血战到底) hand checking.
 *
 * Core rules implemented here:
 *  - 108-tile wall (万/条/筒 only)
 *  - 缺一门: a winning hand MUST NOT contain tiles from the declared missing suit.
 *  - 胡牌 shapes supported: 4 melds + 1 pair (standard) or 七对 (seven distinct pairs).
 *  - Detects 听牌 (tiles that would complete the hand).
 *
 * Not modeling scoring multipliers (刮风下雨, 根, 大对子 bonus, 清一色 bonus) — those
 * are left to the AI coach and future scoring module.
 */
object HandCheck {

    fun isWinning(hand: List<Tile>, missing: Suit? = null): Boolean {
        if (missing != null && hand.any { it.suit == missing }) return false
        if (hand.size !in listOf(2, 5, 8, 11, 14)) return false
        val counts = toCounts(hand)
        return isSevenPairs(counts) || isStandardHu(counts)
    }

    /** Returns the list of tiles that, if drawn, would complete a winning hand. */
    fun waitingTiles(hand: List<Tile>, missing: Suit? = null): List<Tile> {
        if (hand.size !in listOf(1, 4, 7, 10, 13)) return emptyList()
        val result = mutableListOf<Tile>()
        for (candidate in Tiles.ALL) {
            if (missing != null && candidate.suit == missing) continue
            val test = hand + candidate
            if (isWinning(test, missing)) result += candidate
        }
        return result
    }

    /** True iff [hand] of size 13 is 听牌 under the given missing-suit constraint. */
    fun isTing(hand: List<Tile>, missing: Suit? = null): Boolean =
        waitingTiles(hand, missing).isNotEmpty()

    /**
     * 向听数 (shanten) — standard mahjong convention:
     *  - For a 14-tile hand: -1 = already winning, 0 = one discard from tenpai,
     *    1 = two discards away, ...
     *  - For a 13-tile hand: 0 = tenpai (one correct draw wins), 1 = ichishanten, ...
     *
     * Implementation: brute-force swap search with a small depth cap. A "swap" means
     * replacing one tile in the hand with a legal tile (respecting 缺 and ≤4 copies).
     * Because swaps preserve hand size, we model:
     *  - 14-tile input: success = isWinning(counts).
     *  - 13-tile input: success = waitingTiles non-empty (equivalent to: ∃ legal draw
     *    t such that hand+t is winning).
     *
     * Depth cap = 2 to keep interactive latency <~50ms; distinguishing 0 / 1 /
     * 2 shanten is what matters for discard decisions. Hands further from tenpai
     * simply report the cap (2) which is good enough for UI.
     */
    fun shanten(hand: List<Tile>, missing: Suit? = null): Int {
        if (isWinning(hand, missing)) return -1
        if (hand.size == 13) {
            // Shortcut: tenpai check before launching expensive search.
            if (isTing(hand, missing)) return 0
        }
        val maxDepth = 2
        val handCounts = toCounts(hand)
        val isThirteen = hand.size == 13
        return searchShanten(handCounts, missing, 0, maxDepth, isThirteen) ?: maxDepth
    }

    private fun searchShanten(
        counts: IntArray,
        missing: Suit?,
        depth: Int,
        maxDepth: Int,
        thirteenMode: Boolean,
    ): Int? {
        val hasMissingTile = missing != null && (0 until 9).any { counts[missing.ordinal * 9 + it] > 0 }
        val solved = !hasMissingTile && (
            if (thirteenMode) isTenpaiFromCounts(counts, missing)
            else isSevenPairs(counts) || isStandardHu(counts)
        )
        if (solved) return if (thirteenMode) depth else depth - 1
        if (depth == maxDepth) return null
        var best: Int? = null
        for (i in counts.indices) {
            if (counts[i] == 0) continue
            counts[i]--
            for (j in counts.indices) {
                if (counts[j] >= 4) continue
                val jSuit = indexSuit(j)
                if (missing != null && jSuit == missing) continue
                counts[j]++
                val r = searchShanten(counts, missing, depth + 1, maxDepth, thirteenMode)
                counts[j]--
                if (r != null && (best == null || r < best)) best = r
            }
            counts[i]++
        }
        return best
    }

    /** Check if the 13-tile state encoded in [counts] is tenpai. */
    private fun isTenpaiFromCounts(counts: IntArray, missing: Suit?): Boolean {
        for (i in counts.indices) {
            if (counts[i] >= 4) continue
            val iSuit = indexSuit(i)
            if (missing != null && iSuit == missing) continue
            counts[i]++
            val ok = isSevenPairs(counts) || isStandardHu(counts)
            counts[i]--
            if (ok) return true
        }
        return false
    }

    private fun isSevenPairs(counts: IntArray): Boolean {
        if (counts.sum() != 14) return false
        var pairs = 0
        for (c in counts) {
            if (c == 2 || c == 4) pairs += c / 2
            else if (c != 0) return false
        }
        return pairs == 7
    }

    /** Standard 4 melds + 1 pair check via recursive descent. */
    private fun isStandardHu(counts: IntArray): Boolean {
        val total = counts.sum()
        if (total !in listOf(2, 5, 8, 11, 14)) return false
        val neededMelds = (total - 2) / 3
        // Try each possible pair.
        val work = counts.copyOf()
        for (i in work.indices) {
            if (work[i] >= 2) {
                work[i] -= 2
                if (canFormMelds(work, neededMelds)) {
                    work[i] += 2
                    return true
                }
                work[i] += 2
            }
        }
        return false
    }

    private fun canFormMelds(counts: IntArray, melds: Int): Boolean {
        if (melds == 0) return counts.all { it == 0 }
        val i = counts.indexOfFirst { it > 0 }
        if (i < 0) return false
        // triplet
        if (counts[i] >= 3) {
            counts[i] -= 3
            if (canFormMelds(counts, melds - 1)) {
                counts[i] += 3
                return true
            }
            counts[i] += 3
        }
        // sequence (only within same suit; each suit has 9 consecutive indices)
        val suitStart = (i / 9) * 9
        val posInSuit = i - suitStart
        if (posInSuit <= 6 && counts[i + 1] > 0 && counts[i + 2] > 0) {
            counts[i]--; counts[i + 1]--; counts[i + 2]--
            if (canFormMelds(counts, melds - 1)) {
                counts[i]++; counts[i + 1]++; counts[i + 2]++
                return true
            }
            counts[i]++; counts[i + 1]++; counts[i + 2]++
        }
        return false
    }

    internal fun toCounts(hand: List<Tile>): IntArray {
        val counts = IntArray(27)
        for (t in hand) counts[tileIndex(t)]++
        return counts
    }

    internal fun tileIndex(t: Tile): Int = t.suit.ordinal * 9 + (t.number - 1)

    internal fun indexSuit(i: Int): Suit = Suit.entries[i / 9]

    internal fun indexTile(i: Int): Tile = Tile(indexSuit(i), (i % 9) + 1)
}
