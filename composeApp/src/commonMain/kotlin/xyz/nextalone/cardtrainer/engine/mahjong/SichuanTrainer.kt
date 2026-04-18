package xyz.nextalone.cardtrainer.engine.mahjong

import kotlinx.serialization.Serializable

data class DiscardSuggestion(
    val tile: Tile,
    val shantenAfter: Int,
    val waitSize: Int,
) {
    val score: Int get() = -(shantenAfter * 100) + waitSize
}

@Serializable
data class SichuanSnapshot(
    val hand: List<Tile>,
    val discards: List<Tile>,
    val missing: Suit?,
    val wall: List<Tile>,
)

class SichuanTrainer(seed: Long? = null) {

    private var wall: ArrayDeque<Tile> = Tiles.wall(seed)
    var hand: MutableList<Tile> = mutableListOf()
        private set
    var discards: MutableList<Tile> = mutableListOf()
        private set
    var missing: Suit? = null
        private set

    fun dealInitial() {
        hand = wall.take(13).toMutableList().apply { sort() }
        repeat(13) { wall.removeFirst() }
    }

    fun declareMissing(s: Suit) {
        missing = s
    }

    /** Convenience: deal 13 and immediately declare missing (used by tests / direct start). */
    fun deal(missingSuit: Suit) {
        dealInitial()
        declareMissing(missingSuit)
    }

    fun drawTile(): Tile {
        val t = wall.removeFirst()
        hand.add(t)
        hand.sort()
        return t
    }

    fun discard(tile: Tile) {
        require(hand.remove(tile)) { "tile $tile not in hand" }
        discards.add(tile)
    }

    fun isWinning(): Boolean = HandCheck.isWinning(hand, missing)

    fun currentShanten(): Int = HandCheck.shanten(hand, missing)

    /**
     * Rank candidate discards by (shanten after, number of waits).
     * Expects a 14-tile hand (i.e. just drew).
     */
    fun rankDiscards(limit: Int = 5): List<DiscardSuggestion> {
        require(hand.size == 14) { "rankDiscards expects 14 tiles, got ${hand.size}" }
        val results = mutableListOf<DiscardSuggestion>()
        for (t in hand.distinct()) {
            val trial = hand.toMutableList().also { it.remove(t) }
            val sh = HandCheck.shanten(trial, missing)
            val waits = if (sh <= 0) HandCheck.waitingTiles(trial, missing).size else 0
            results += DiscardSuggestion(t, sh, waits)
        }
        return results.sortedByDescending { it.score }.take(limit)
    }

    fun wallRemaining(): Int = wall.size

    fun snapshot(): SichuanSnapshot = SichuanSnapshot(
        hand = hand.toList(),
        discards = discards.toList(),
        missing = missing,
        wall = wall.toList(),
    )

    fun restore(s: SichuanSnapshot) {
        hand = s.hand.toMutableList()
        discards = s.discards.toMutableList()
        missing = s.missing
        wall = ArrayDeque(s.wall)
    }
}
