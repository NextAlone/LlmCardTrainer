package xyz.nextalone.cardtrainer.engine.holdem.multiway

import kotlinx.serialization.Serializable
import xyz.nextalone.cardtrainer.engine.holdem.HandCategory
import xyz.nextalone.cardtrainer.engine.holdem.HandEvaluator
import xyz.nextalone.cardtrainer.engine.holdem.HandStrength
import xyz.nextalone.cardtrainer.engine.holdem.Position

@Serializable
data class SeatRank(
    val seatIndex: Int,
    val category: HandCategory,
    val score: Long,
) : Comparable<SeatRank> {
    override fun compareTo(other: SeatRank): Int = score.compareTo(other.score)
}

@Serializable
data class PotAward(
    val potAmount: Int,
    val eligibleSeats: List<Int>,
    val winnerSeats: List<Int>,
    val perWinner: Int,
    val remainder: Int,
    // Seats that also receive the odd chip(s), in order starting from
    // BTN-left (SB side). Size equals [remainder]; each gets one extra chip
    // on top of [perWinner]. Empty when remainder is 0 or no winners.
    val oddChipSeats: List<Int> = emptyList(),
)

@Serializable
data class ShowdownOutcome(
    val awards: List<PotAward>,
    val seatRanks: List<SeatRank?>,
) {
    val heroWon: Boolean get() = awards.any { it.winnerSeats.isNotEmpty() && it.potAmount > 0 }
}

object Showdown {

    // Odd-chip distribution order: starting from BTN-left (SB) clockwise.
    // Matches the standard high-hand poker convention: the seat closest to
    // the button's left that belongs to the winner set receives the leftover.
    private val ODD_CHIP_ORDER = listOf(
        Position.SB, Position.BB, Position.UTG, Position.MP, Position.CO, Position.BTN,
    )

    fun run(table: MultiwayTable): ShowdownOutcome {
        val liveIdxs = table.seats.withIndex().filter { it.value.isLive }.map { it.index }
        val pots = SidePotBuilder.build(table.seats)

        // Uncontested — everyone folded to one seat. Board may be incomplete
        // so we cannot evaluate hand strengths; sole live seat scoops every
        // pot they're eligible for.
        if (liveIdxs.size <= 1) {
            val awards = pots.map { pot ->
                val winners = pot.eligibleSeats.filter { it in liveIdxs }
                val per = if (winners.isNotEmpty()) pot.amount / winners.size else 0
                val rem = pot.amount - per * winners.size
                PotAward(
                    pot.amount, pot.eligibleSeats, winners, per, rem,
                    oddChipSeats = pickOddChipSeats(table.seats, winners, rem),
                )
            }
            return ShowdownOutcome(awards, List(table.seats.size) { null })
        }

        // Contested showdown expects a complete board.
        require(table.board.size == 5) {
            "Contested showdown requires a full 5-card board, got ${table.board.size}"
        }
        val strengths: List<HandStrength?> = table.seats.map { s ->
            if (!s.isLive || s.cards == null) null
            else HandEvaluator.evaluate(s.cards + table.board)
        }
        val seatRanks = strengths.mapIndexed { idx, st ->
            st?.let { SeatRank(idx, it.category, it.score) }
        }
        val awards = pots.map { pot ->
            val ranked = pot.eligibleSeats.mapNotNull { idx ->
                val st = strengths[idx] ?: return@mapNotNull null
                idx to st.score
            }
            if (ranked.isEmpty()) {
                PotAward(pot.amount, pot.eligibleSeats, emptyList(), 0, pot.amount)
            } else {
                val topScore = ranked.maxOf { it.second }
                val winners = ranked.filter { it.second == topScore }.map { it.first }
                val per = pot.amount / winners.size
                val rem = pot.amount - per * winners.size
                PotAward(
                    pot.amount, pot.eligibleSeats, winners, per, rem,
                    oddChipSeats = pickOddChipSeats(table.seats, winners, rem),
                )
            }
        }
        return ShowdownOutcome(awards = awards, seatRanks = seatRanks)
    }

    private fun pickOddChipSeats(
        seats: List<Seat>,
        winners: List<Int>,
        remainder: Int,
    ): List<Int> {
        if (remainder <= 0 || winners.isEmpty()) return emptyList()
        val ordered = ODD_CHIP_ORDER.mapNotNull { pos ->
            val idx = seats.indexOfFirst { it.position == pos }
            if (idx >= 0 && idx in winners) idx else null
        }
        return ordered.take(remainder)
    }
}
