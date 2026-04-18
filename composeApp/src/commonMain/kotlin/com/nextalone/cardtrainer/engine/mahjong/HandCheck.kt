package com.nextalone.cardtrainer.engine.mahjong

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
     * 向听数 (shanten): 0 = 听牌, 1 = 一向听, ... -1 indicates already winning.
     * Simple upper bound based on standard form only.
     */
    fun shanten(hand: List<Tile>, missing: Suit? = null): Int {
        if (isWinning(hand, missing)) return -1
        // Brute-force: try every pair of tiles to discard and draw combinations.
        // For a 14- or 13-tile hand this is O(27) per depth so keep to depth 4.
        val maxDepth = 4
        val handCounts = toCounts(hand)
        return searchShanten(handCounts, missing, 0, maxDepth) ?: maxDepth
    }

    private fun searchShanten(
        counts: IntArray,
        missing: Suit?,
        depth: Int,
        maxDepth: Int,
    ): Int? {
        if (isSevenPairs(counts) || isStandardHu(counts)) return depth - 1
        if (depth == maxDepth) return null
        var best: Int? = null
        // Try swapping one tile at a time (remove one we have, add any valid one).
        for (i in counts.indices) {
            if (counts[i] == 0) continue
            counts[i]--
            for (j in counts.indices) {
                if (counts[j] >= 4) continue
                val jSuit = indexSuit(j)
                if (missing != null && jSuit == missing) continue
                counts[j]++
                val r = searchShanten(counts, missing, depth + 1, maxDepth)
                counts[j]--
                if (r != null && (best == null || r < best)) best = r
            }
            counts[i]++
        }
        return best
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
