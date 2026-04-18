package xyz.nextalone.cardtrainer.engine.holdem

import kotlin.random.Random

data class EquityResult(val winPct: Double, val tiePct: Double, val trials: Int) {
    val combinedPct: Double get() = winPct + tiePct / 2.0
}

object Equity {

    fun monteCarlo(
        hero: List<Card>,
        board: List<Card>,
        opponents: Int = 1,
        trials: Int = 2000,
        seed: Long? = null,
    ): EquityResult {
        require(hero.size == 2)
        require(opponents in 1..9)
        require(board.size in 0..5)

        val rng = if (seed != null) Random(seed) else Random.Default
        val used = (hero + board).toHashSet()
        val allCards = buildList {
            for (s in Suit.entries) for (r in Rank.entries) add(Card(r, s))
        }
        val remaining = allCards.filter { it !in used }

        var wins = 0
        var ties = 0
        repeat(trials) {
            val deck = remaining.toMutableList()
            shuffle(deck, rng)
            var idx = 0
            val villains = List(opponents) {
                val c1 = deck[idx++]
                val c2 = deck[idx++]
                listOf(c1, c2)
            }
            val fill = 5 - board.size
            val runout = board + (0 until fill).map { deck[idx++] }

            val heroScore = HandEvaluator.evaluate(hero + runout)
            val villainScores = villains.map { HandEvaluator.evaluate(it + runout) }
            val bestVillain = villainScores.max()

            val cmp = heroScore.compareTo(bestVillain)
            when {
                cmp > 0 -> wins++
                cmp == 0 -> ties++
            }
        }
        return EquityResult(
            winPct = wins * 100.0 / trials,
            tiePct = ties * 100.0 / trials,
            trials = trials,
        )
    }

    private fun <T> shuffle(list: MutableList<T>, rng: Random) {
        for (i in list.size - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = list[i]; list[i] = list[j]; list[j] = tmp
        }
    }
}
