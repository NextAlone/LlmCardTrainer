package com.nextalone.cardtrainer.engine.mahjong

/**
 * 定缺推荐 (recommend which suit to declare as 缺).
 *
 * Metric: shanten of the full 13-tile hand under the hypothetical missing-suit
 * constraint. Declaring 缺X does not instantly remove tiles from your hand;
 * you must discard them over the coming turns. The shanten calculator, when
 * given `missing = X`, already refuses to build melds/pairs out of X-suit
 * tiles — those tiles become "dead weight" that cost turns to shed, so the
 * resulting shanten naturally penalises suits where you hold many tiles.
 *
 * Ties are broken by fewer tiles already in that suit (cheaper to clear).
 */
data class DingQueAdvice(val suit: Suit, val shantenIfMissing: Int, val countInSuit: Int)

object DingQue {

    fun recommend(thirteen: List<Tile>): List<DingQueAdvice> {
        require(thirteen.size == 13)
        return Suit.entries.map { s ->
            val sh = HandCheck.shanten(thirteen, missing = s)
            val cnt = thirteen.count { it.suit == s }
            DingQueAdvice(s, sh, cnt)
        }.sortedWith(
            compareBy<DingQueAdvice> { it.shantenIfMissing }.thenBy { it.countInSuit },
        )
    }
}
