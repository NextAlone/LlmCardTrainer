package xyz.nextalone.cardtrainer.engine.mahjong

import kotlin.random.Random
import kotlinx.serialization.Serializable

data class DiscardSuggestion(
    val tile: Tile,
    val shantenAfter: Int,
    val waitSize: Int,
) {
    val score: Int get() = -(shantenAfter * 100) + waitSize
}

/**
 * Opponent state as persisted / shown to the hero. The hand itself is hidden
 * from the UI (that's the whole point of opponents), but we still persist it
 * in [opponentHand] so a restored session keeps consistent engine state.
 */
@Serializable
data class OpponentView(
    val discards: List<Tile>,
    val missing: Suit?,
    val handSize: Int,
    val opponentHand: List<Tile> = emptyList(),
)

@Serializable
data class SichuanSnapshot(
    val hand: List<Tile>,
    val discards: List<Tile>,
    val missing: Suit?,
    val wall: List<Tile>,
    val opponents: List<OpponentView> = emptyList(),
)

/**
 * 4-seat simulator: hero at seat 0, three bots at 1–3 (roughly 下家 / 对家 / 上家
 * in Sichuan mahjong terminology). Each hero discard triggers the three bots
 * to draw+discard in order, then draws a fresh tile for the hero. Bots follow
 * a simple policy: first shed their declared-missing suit, then greedy-minimise
 * shanten among distinct candidate discards.
 */
class SichuanTrainer(seed: Long? = null) {

    private val rng: Random = if (seed != null) Random(seed) else Random.Default

    private var wall: ArrayDeque<Tile> = Tiles.wall(seed)

    var hand: MutableList<Tile> = mutableListOf()
        private set
    var discards: MutableList<Tile> = mutableListOf()
        private set
    var missing: Suit? = null
        private set

    private data class Bot(
        val hand: MutableList<Tile> = mutableListOf(),
        val discards: MutableList<Tile> = mutableListOf(),
        var missing: Suit? = null,
    )

    private val bots: List<Bot> = List(3) { Bot() }

    fun dealInitial() {
        hand = wall.take(13).toMutableList().apply { sort() }
        repeat(13) { wall.removeFirst() }
        bots.forEach { bot ->
            bot.hand.clear(); bot.discards.clear()
            repeat(13) { bot.hand.add(wall.removeFirst()) }
            bot.hand.sort()
            // Bots auto-pick their missing: the suit with fewest tiles in hand.
            bot.missing = bot.hand.groupingBy { it.suit }.eachCount()
                .minByOrNull { it.value }?.key ?: Suit.WAN
        }
    }

    fun declareMissing(s: Suit) {
        missing = s
    }

    /** Convenience: deal 13, declare hero's missing, deal the 14th (hero's first draw). */
    fun deal(missingSuit: Suit) {
        dealInitial()
        declareMissing(missingSuit)
        drawTile()
    }

    fun drawTile(): Tile? {
        if (wall.isEmpty()) return null
        val t = wall.removeFirst()
        hand.add(t)
        hand.sort()
        return t
    }

    fun discard(tile: Tile) {
        require(hand.remove(tile)) { "tile $tile not in hand" }
        discards.add(tile)
    }

    /**
     * Called after hero's discard. Runs the three bots in seat order
     * (draw then discard) and finally draws a fresh tile for the hero so the
     * UI shows 14 tiles again. Returns false if the wall ran out mid-round
     * (黄庄 — no one hu'd).
     */
    fun runOpponentsAndDraw(): Boolean {
        for (bot in bots) {
            if (wall.isEmpty()) return false
            val drawn = wall.removeFirst()
            bot.hand.add(drawn); bot.hand.sort()
            val out = chooseBotDiscard(bot)
            bot.hand.remove(out)
            bot.discards.add(out)
        }
        if (wall.isEmpty()) return false
        drawTile()
        return true
    }

    private fun chooseBotDiscard(bot: Bot): Tile {
        // 1. Shed tiles of missing suit first (required to reach tenpai).
        bot.missing?.let { m ->
            bot.hand.firstOrNull { it.suit == m }?.let { return it }
        }
        // 2. Greedy: among distinct candidates, pick the one whose removal
        //    yields the best shanten; break ties by tile ordinality.
        val candidates = bot.hand.distinct()
        var best: Tile = candidates.first()
        var bestShanten: Int = Int.MAX_VALUE
        for (t in candidates) {
            val trial = bot.hand.toMutableList().also { it.remove(t) }
            val sh = HandCheck.shanten(trial, bot.missing)
            if (sh < bestShanten) {
                bestShanten = sh
                best = t
            }
        }
        return best
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

    /** All discards from the three opponents (for safety heuristics / UI). */
    fun opponentDiscards(): List<Tile> = bots.flatMap { it.discards }

    /** Per-seat opponent view — ordered seat 1, 2, 3 = 下家 / 对家 / 上家. */
    fun opponentViews(): List<OpponentView> = bots.map {
        OpponentView(
            discards = it.discards.toList(),
            missing = it.missing,
            handSize = it.hand.size,
        )
    }

    /**
     * Unseen copies (0..4) of every tile, from the hero's perspective.
     * Visible = hero's own hand + all discards (hero + opponents).
     */
    fun unseenByTile(): Map<Tile, Int> {
        val visible = HashMap<Tile, Int>()
        for (t in hand) visible.merge(t, 1, Int::plus)
        for (t in discards) visible.merge(t, 1, Int::plus)
        for (b in bots) for (t in b.discards) visible.merge(t, 1, Int::plus)
        return Tiles.ALL.associateWith { (4 - (visible[it] ?: 0)).coerceAtLeast(0) }
    }

    fun snapshot(): SichuanSnapshot = SichuanSnapshot(
        hand = hand.toList(),
        discards = discards.toList(),
        missing = missing,
        wall = wall.toList(),
        opponents = bots.map {
            OpponentView(
                discards = it.discards.toList(),
                missing = it.missing,
                handSize = it.hand.size,
                opponentHand = it.hand.toList(),
            )
        },
    )

    fun restore(s: SichuanSnapshot) {
        hand = s.hand.toMutableList()
        discards = s.discards.toMutableList()
        missing = s.missing
        wall = ArrayDeque(s.wall)
        bots.forEachIndexed { i, bot ->
            bot.hand.clear(); bot.discards.clear()
            s.opponents.getOrNull(i)?.let { v ->
                bot.discards.addAll(v.discards)
                bot.missing = v.missing
                bot.hand.addAll(v.opponentHand)
            }
        }
    }
}
