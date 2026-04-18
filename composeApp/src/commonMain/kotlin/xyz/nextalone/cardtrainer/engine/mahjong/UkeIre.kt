package xyz.nextalone.cardtrainer.engine.mahjong

/**
 * 有效进张统计 (uke-ire) with live wall accounting.
 *
 * Given the visible tiles (hand + own discards + visible opponents' discards),
 * computes the waiting tiles and how many copies of each remain unseen.
 */
data class LiveWait(val tile: Tile, val remaining: Int)

object UkeIre {

    fun waitingWithCounts(
        hand: List<Tile>,
        visible: List<Tile>,
        missing: Suit?,
    ): List<LiveWait> {
        val waits = HandCheck.waitingTiles(hand, missing)
        if (waits.isEmpty()) return emptyList()

        val visibleCounts = HashMap<Tile, Int>()
        for (t in hand + visible) visibleCounts[t] = (visibleCounts[t] ?: 0) + 1

        return waits
            .map { LiveWait(it, (4 - (visibleCounts[it] ?: 0)).coerceAtLeast(0)) }
            .filter { it.remaining > 0 }
            .sortedByDescending { it.remaining }
    }

    /** Effective draws for a 14-tile hand if it discards [candidate]. */
    fun drawsAfterDiscard(
        hand14: List<Tile>,
        candidate: Tile,
        visible: List<Tile>,
        missing: Suit?,
    ): Int {
        val trial = hand14.toMutableList().also { it.remove(candidate) }
        return waitingWithCounts(trial, visible, missing).sumOf { it.remaining }
    }
}
