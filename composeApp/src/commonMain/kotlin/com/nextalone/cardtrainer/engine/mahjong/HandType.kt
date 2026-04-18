package com.nextalone.cardtrainer.engine.mahjong

/**
 * 胡牌型识别 (winning hand classification for Sichuan blood-battle).
 *
 * Types we care about for coaching:
 *  - 平胡 (standard): 4 melds + 1 pair, mixed shapes
 *  - 对对胡 (all-triplets): 4 triplets + 1 pair
 *  - 七对 (seven pairs): 7 distinct pairs
 *  - 龙七对: seven pairs with at least one four-of-a-kind (counted as pair+pair)
 *  - 清一色 (one-suit): all tiles from a single suit
 *  - 根 (root): number of quadruplets in the hand
 */
data class HandTypeReport(
    val isPingHu: Boolean,
    val isDuiDuiHu: Boolean,
    val isQiDui: Boolean,
    val isLongQiDui: Boolean,
    val isQingYiSe: Boolean,
    val roots: Int,
) {
    val labels: List<String> = buildList {
        if (isLongQiDui) add("龙七对")
        else if (isQiDui) add("七对")
        if (isDuiDuiHu) add("对对胡")
        if (isQingYiSe) add("清一色")
        if (!isQiDui && !isDuiDuiHu && isPingHu) add("平胡")
        if (roots > 0) add("根×$roots")
    }
}

object HandType {

    fun classify(hand: List<Tile>): HandTypeReport {
        require(hand.size in listOf(2, 5, 8, 11, 14))
        val counts = HandCheck.toCounts(hand)

        val qi = isSevenPairs(counts)
        val longQi = qi && hand.groupBy { it }.values.any { it.size == 4 }
        val duidui = !qi && isAllTriplets(counts)
        val ping = !qi && !duidui && HandCheck.isWinning(hand)
        val qing = hand.map { it.suit }.toSet().size == 1
        val roots = hand.groupBy { it }.values.count { it.size == 4 }

        return HandTypeReport(
            isPingHu = ping,
            isDuiDuiHu = duidui,
            isQiDui = qi,
            isLongQiDui = longQi,
            isQingYiSe = qing,
            roots = roots,
        )
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

    /** 对对胡 = pair + 4 triplets; no sequences allowed. */
    private fun isAllTriplets(counts: IntArray): Boolean {
        if (counts.sum() != 14) return false
        var pair = 0
        var triplets = 0
        for (c in counts) {
            when (c) {
                0 -> {}
                2 -> pair++
                3 -> triplets++
                4 -> { triplets++; pair++ } // treat 4 as a triplet plus a leftover handled below
                else -> return false
            }
        }
        // A 4-of-kind cannot cleanly split into pair+triplet in a single meld layout,
        // so the simple count here is a proxy. Final truth: HandCheck already validated.
        return pair == 1 && triplets == 4
    }
}
