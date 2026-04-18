package xyz.nextalone.cardtrainer.engine.holdem

/**
 * Simple outs counter.
 *
 * Iterates every remaining card in the deck; counts how many improve the hero's
 * current best-5 to a strictly better category. This is a pragmatic, not
 * exhaustive, outs definition (matches how most trainers present "outs").
 */
data class OutsReport(val outs: Int, val samples: List<Card>) {
    val turnPct: Double get() = outs * 2.0 // rule-of-2
    val turnAndRiverPct: Double get() = outs * 4.0 // rule-of-4
}

object Outs {

    fun count(hero: List<Card>, board: List<Card>): OutsReport {
        require(hero.size == 2)
        require(board.size in 3..4)

        val used = (hero + board).toHashSet()
        val allCards = Suit.entries.flatMap { s -> Rank.entries.map { Card(it, s) } }
        val remaining = allCards.filter { it !in used }
        val currentCat = HandEvaluator.evaluate(hero + board).category

        val improved = remaining.filter { extra ->
            val cat = HandEvaluator.evaluate(hero + board + extra).category
            cat.ordinal > currentCat.ordinal
        }
        return OutsReport(improved.size, improved.take(10))
    }
}
