package com.nextalone.cardtrainer.engine.mahjong

/**
 * 弃牌安全度 (discard safety, simplified).
 *
 * Higher score = safer to discard. Factors:
 *  - 已现张: a tile previously discarded by any player is "现张"; discarding it
 *    into an opponent's 听 is impossible for 平胡 direct pon-only claims, so safer.
 *  - 边张/字牌比例: Sichuan has no honors; 1/9 terminals tend to be less useful
 *    in opponents' sequences — slight safety bonus.
 *  - 对手弃同一数字: if an opponent discarded 5-suit, 4/5/6-same-suit sequences
 *    are less likely for that opponent — mild safety bonus.
 *
 * This is heuristic; real danger requires tracking called melds and 听 reads.
 */
data class SafetyScore(val tile: Tile, val score: Double, val reasons: List<String>)

object Safety {

    fun rank(
        candidates: List<Tile>,
        ownDiscards: List<Tile>,
        opponentDiscards: List<Tile>,
    ): List<SafetyScore> {
        val opSet = opponentDiscards.toHashSet()
        val ownSet = ownDiscards.toHashSet()
        val allDiscards = opponentDiscards + ownDiscards

        return candidates.distinct().map { t ->
            var score = 0.0
            val reasons = mutableListOf<String>()

            if (t in opSet) { score += 3.0; reasons += "对手已弃同张（现张）" }
            if (t in ownSet) { score += 1.0; reasons += "我方已弃同张" }
            if (t.number == 1 || t.number == 9) { score += 0.5; reasons += "边张（1/9）" }

            val nearOpp = allDiscards.any { d -> d.suit == t.suit && kotlin.math.abs(d.number - t.number) <= 1 }
            if (nearOpp) { score += 0.5; reasons += "相邻张已现" }

            SafetyScore(t, score, reasons)
        }.sortedByDescending { it.score }
    }
}
