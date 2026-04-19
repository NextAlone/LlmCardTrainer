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
 * they contributed above the highest live level is added to the main
 * (first) pot, as is standard.
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
            // Dead money from folded seats above highest live level goes into
            // the main pot (first pot).
            val deadMoney = totalMoney - dealt
            pots[0] = pots[0].copy(amount = pots[0].amount + deadMoney)
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
