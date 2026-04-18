package xyz.nextalone.cardtrainer.engine.holdem

enum class HandCategory(val displayName: String) {
    HIGH_CARD("高牌"),
    ONE_PAIR("一对"),
    TWO_PAIR("两对"),
    THREE_OF_A_KIND("三条"),
    STRAIGHT("顺子"),
    FLUSH("同花"),
    FULL_HOUSE("葫芦"),
    FOUR_OF_A_KIND("四条"),
    STRAIGHT_FLUSH("同花顺"),
    ROYAL_FLUSH("皇家同花顺"),
}

data class HandStrength(val category: HandCategory, val score: Long) : Comparable<HandStrength> {
    override fun compareTo(other: HandStrength): Int = score.compareTo(other.score)
}

object HandEvaluator {

    fun evaluate(sevenOrLess: List<Card>): HandStrength {
        require(sevenOrLess.size in 5..7)
        if (sevenOrLess.size == 5) return scoreFive(sevenOrLess)
        var best: HandStrength? = null
        val combos = combinations(sevenOrLess, 5)
        for (c in combos) {
            val s = scoreFive(c)
            if (best == null || s > best) best = s
        }
        return best!!
    }

    private fun scoreFive(cards: List<Card>): HandStrength {
        val sorted = cards.sortedByDescending { it.rank.value }
        val values = sorted.map { it.rank.value }
        val flush = cards.groupBy { it.suit }.any { it.value.size == 5 }

        val distinct = values.toSortedSet().toList().sortedDescending()
        val straightHigh = straightHigh(distinct, values)

        val counts = values.groupingBy { it }.eachCount()
        val byCount = counts.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenByDescending { it.key })
        val countPattern = byCount.map { it.value }

        return when {
            flush && straightHigh == 14 -> HandStrength(HandCategory.ROYAL_FLUSH, score(HandCategory.ROYAL_FLUSH, listOf(14)))
            flush && straightHigh != null -> HandStrength(HandCategory.STRAIGHT_FLUSH, score(HandCategory.STRAIGHT_FLUSH, listOf(straightHigh)))
            countPattern.firstOrNull() == 4 -> {
                val quad = byCount[0].key
                val kicker = byCount[1].key
                HandStrength(HandCategory.FOUR_OF_A_KIND, score(HandCategory.FOUR_OF_A_KIND, listOf(quad, kicker)))
            }
            countPattern.take(2) == listOf(3, 2) -> {
                val trips = byCount[0].key
                val pair = byCount[1].key
                HandStrength(HandCategory.FULL_HOUSE, score(HandCategory.FULL_HOUSE, listOf(trips, pair)))
            }
            flush -> HandStrength(HandCategory.FLUSH, score(HandCategory.FLUSH, values))
            straightHigh != null -> HandStrength(HandCategory.STRAIGHT, score(HandCategory.STRAIGHT, listOf(straightHigh)))
            countPattern.firstOrNull() == 3 -> {
                val trips = byCount[0].key
                val kickers = byCount.drop(1).map { it.key }
                HandStrength(HandCategory.THREE_OF_A_KIND, score(HandCategory.THREE_OF_A_KIND, listOf(trips) + kickers))
            }
            countPattern.take(2) == listOf(2, 2) -> {
                val p1 = byCount[0].key
                val p2 = byCount[1].key
                val kicker = byCount[2].key
                HandStrength(HandCategory.TWO_PAIR, score(HandCategory.TWO_PAIR, listOf(p1, p2, kicker)))
            }
            countPattern.firstOrNull() == 2 -> {
                val pair = byCount[0].key
                val kickers = byCount.drop(1).map { it.key }
                HandStrength(HandCategory.ONE_PAIR, score(HandCategory.ONE_PAIR, listOf(pair) + kickers))
            }
            else -> HandStrength(HandCategory.HIGH_CARD, score(HandCategory.HIGH_CARD, values))
        }
    }

    private fun straightHigh(distinctDesc: List<Int>, allValues: List<Int>): Int? {
        if (distinctDesc.size < 5) return null
        for (i in 0..distinctDesc.size - 5) {
            if (distinctDesc[i] - distinctDesc[i + 4] == 4) return distinctDesc[i]
        }
        // wheel A-2-3-4-5
        if (allValues.toSet().containsAll(listOf(14, 2, 3, 4, 5))) return 5
        return null
    }

    private fun score(cat: HandCategory, tiebreakers: List<Int>): Long {
        var s = cat.ordinal.toLong()
        for (t in tiebreakers.take(5)) {
            s = s * 16L + t.toLong()
        }
        // pad to 5 tiebreakers for consistent magnitude
        for (i in tiebreakers.size until 5) s *= 16L
        return s
    }

    private fun <T> combinations(list: List<T>, k: Int): Sequence<List<T>> = sequence {
        val n = list.size
        if (k > n) return@sequence
        val indices = IntArray(k) { it }
        while (true) {
            yield(indices.map { list[it] })
            var i = k - 1
            while (i >= 0 && indices[i] == n - k + i) i--
            if (i < 0) break
            indices[i]++
            for (j in i + 1 until k) indices[j] = indices[j - 1] + 1
        }
    }
}
