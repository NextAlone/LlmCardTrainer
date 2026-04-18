package com.nextalone.cardtrainer.engine.mahjong

/**
 * 定缺推荐 (which suit to declare as missing).
 *
 * Heuristic: pick the suit whose tiles contribute the least to potential melds,
 * i.e. whose removal yields the lowest shanten on the remaining 13 tiles.
 * Ties broken by smaller count in that suit.
 */
data class DingQueAdvice(val suit: Suit, val shantenIfMissing: Int, val countInSuit: Int)

object DingQue {

    fun recommend(thirteen: List<Tile>): List<DingQueAdvice> {
        require(thirteen.size == 13)
        return Suit.entries.map { s ->
            val remaining = thirteen.filter { it.suit != s }
            val sh = if (remaining.isEmpty()) 0 else HandCheck.shanten(remaining, missing = s)
            DingQueAdvice(s, sh, thirteen.count { it.suit == s })
        }.sortedWith(
            compareBy<DingQueAdvice> { it.shantenIfMissing }.thenBy { it.countInSuit },
        )
    }
}
