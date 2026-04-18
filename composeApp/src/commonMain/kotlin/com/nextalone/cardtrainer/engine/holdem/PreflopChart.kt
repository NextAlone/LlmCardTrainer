package com.nextalone.cardtrainer.engine.holdem

/**
 * Simplified 6-max cash GTO-ish preflop opening chart (RFI — raise-first-in).
 *
 * Not a solver; just a reasonable starting baseline drawn from publicly known
 * open charts (MIT Pokerbot-style, Upswing's free 6-max tables). The AI coach
 * still does the final recommendation; this gives the engine a baseline to
 * include in the prompt context.
 */
enum class PreflopAction { RAISE, CALL, FOLD }

object PreflopChart {

    /** Pair notation like "AKs", "AKo", "TT". */
    fun encode(hand: List<Card>): String {
        require(hand.size == 2)
        val (a, b) = hand.sortedByDescending { it.rank.value }
        if (a.rank == b.rank) return "${a.rank.label}${a.rank.label}"
        val suited = a.suit == b.suit
        return "${a.rank.label}${b.rank.label}${if (suited) "s" else "o"}"
    }

    fun rfiAction(position: Position, hand: List<Card>): PreflopAction {
        val code = encode(hand)
        val openRange = when (position) {
            Position.UTG -> UTG
            Position.MP -> MP
            Position.CO -> CO
            Position.BTN -> BTN
            Position.SB -> SB
            Position.BB -> BB
        }
        return if (code in openRange) PreflopAction.RAISE else PreflopAction.FOLD
    }

    /** Pocket pairs and suited aces across all positions. */
    private val ANCHOR = setOf(
        "AA", "KK", "QQ", "JJ", "TT", "99", "88", "77", "66", "55", "44", "33", "22",
        "AKs", "AQs", "AJs", "ATs", "A9s", "A8s", "A7s", "A6s", "A5s", "A4s", "A3s", "A2s",
        "AKo", "AQo", "AJo", "ATo",
    )

    // Rough public RFI ranges. Keep conservative; AI coach can widen with reads.
    private val UTG: Set<String> = ANCHOR + setOf(
        "KQs", "KJs", "KTs", "QJs", "QTs", "JTs", "T9s", "98s", "KQo",
    )

    private val MP: Set<String> = UTG + setOf(
        "K9s", "Q9s", "J9s", "87s", "76s", "65s", "KJo", "QJo",
    )

    private val CO: Set<String> = MP + setOf(
        "K8s", "K7s", "Q8s", "J8s", "T8s", "97s", "86s", "75s", "54s", "KTo", "QTo", "JTo",
    )

    private val BTN: Set<String> = CO + setOf(
        "K6s", "K5s", "K4s", "K3s", "K2s", "Q7s", "Q6s", "Q5s", "Q4s", "Q3s", "Q2s",
        "J7s", "J6s", "J5s", "T7s", "T6s", "96s", "85s", "74s", "64s", "53s", "43s",
        "A9o", "A8o", "A7o", "A6o", "A5o", "A4o", "A3o", "A2o",
        "K9o", "K8o", "K7o", "Q9o", "Q8o", "J9o", "J8o", "T9o", "98o",
    )

    private val SB: Set<String> = CO + setOf(
        "K9o", "Q9o", "J9o", "T9o", "A9o", "A8o", "A7o", "A6o", "A5o", "A4o", "A3o", "A2o",
    )

    // BB just defends vs. RFI in reality; the chart returns "any pair / broadway" as proxy.
    private val BB: Set<String> = BTN
}
