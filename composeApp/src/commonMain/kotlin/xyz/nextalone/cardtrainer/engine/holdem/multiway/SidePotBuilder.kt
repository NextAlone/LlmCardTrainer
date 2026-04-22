package xyz.nextalone.cardtrainer.engine.holdem.multiway

/**
 * Builds side pots from a list of seats' total contributions.
 *
 * Levels are the distinct `totalContrib` values of **live** seats (sorted
 * ascending). Each level carves out a layer whose amount is the per-seat
 * delta times the count of any seat (live or folded) that reached at least
 * that level. Eligibility for a layer is limited to live seats with
 * `totalContrib >= level`.
 *
 * Folded seats contribute their chips but cannot win — any "dead money"
 * they contributed above the highest live level is added to the last
 * (deepest) pot as a conservative approximation. The strictly correct
 * NLHE rule is to refund an uncalled over-bet to the contributor; the
 * engine currently has no refund path, and routing dead money to the
 * main pot would let short all-in seats win chips they never had to
 * cover. Routing it to the deepest side pot at least restricts the
 * windfall to a seat that contested up to that level.
 */
object SidePotBuilder {

    fun build(seats: List<Seat>): List<SidePot> {
        val levels = seats.filter { it.isLive && it.totalContrib > 0 }
            .map { it.totalContrib }
            .distinct()
            .sorted()

        val pots = mutableListOf<SidePot>()
        var prev = 0
        for (level in levels) {
            val delta = level - prev
            if (delta <= 0) continue
            var amount = 0
            seats.forEach { seat ->
                val inLayer = (seat.totalContrib - prev).coerceAtLeast(0).coerceAtMost(delta)
                amount += inLayer
            }
            val eligible = seats.withIndex()
                .filter { (_, s) -> s.isLive && s.totalContrib >= level }
                .map { it.index }
            if (amount > 0 && eligible.isNotEmpty()) {
                pots += SidePot(amount = amount, eligibleSeats = eligible)
            }
            prev = level
        }

        val totalMoney = seats.sumOf { it.totalContrib }
        val dealt = pots.sumOf { it.amount }
        if (dealt < totalMoney && pots.isNotEmpty()) {
            // Dead money from folded seats above the highest live level goes
            // into the deepest pot. See class-level kdoc for why main-pot was
            // wrong and why a refund is the truly correct behavior.
            val deadMoney = totalMoney - dealt
            val last = pots.lastIndex
            pots[last] = pots[last].copy(amount = pots[last].amount + deadMoney)
        } else if (pots.isEmpty() && totalMoney > 0) {
            // Everyone folded except one live seat — return a single pot for
            // that seat. Callers can award without comparing.
            val soleLive = seats.withIndex().filter { it.value.isLive }.map { it.index }
            if (soleLive.isNotEmpty()) {
                pots += SidePot(amount = totalMoney, eligibleSeats = soleLive)
            }
        }
        return pots
    }
}
