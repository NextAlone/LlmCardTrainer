package xyz.nextalone.cardtrainer.stats

/**
 * Derived behavioural metrics over a list of poker decisions. All percentages
 * are 0-100 (not 0-1) to match how they're shown in every poker-tracker in
 * the wild (VPIP 23% not 0.23).
 */
data class PokerStats(
    val totalDecisions: Int,
    val totalHands: Int,
    val vpip: Double,
    val pfr: Double,
    val aggressionFactor: Double,
    val preflopFoldRate: Double,
    val postflopFoldRate: Double,
    val rfiDeviationRate: Double,
    val avgBetSizePot: Double,
    val recentWindow: List<WindowBucket>,
) {
    /** 20-hand rolling buckets so the UI can draw a trend. */
    data class WindowBucket(val endIndex: Int, val vpip: Double, val pfr: Double)
}

object PokerStatsCalc {

    fun compute(events: List<PokerDecisionEvent>): PokerStats {
        if (events.isEmpty()) return empty()

        val decisionsByHand = events.groupBy { it.timestampMs / 1_000 }.size
            .coerceAtLeast(estimateHands(events))
        val preflop = events.filter { it.street == "PREFLOP" }
        val postflop = events.filter { it.street != "PREFLOP" }

        val preflopVoluntary = preflop.count { it.action !in FOLD_CHECK && it.toCall > 0 || it.action in AGGR }
        val preflopRaise = preflop.count { it.action in AGGR }

        val vpip = pct(preflopVoluntary, preflop.size)
        val pfr = pct(preflopRaise, preflop.size)

        val postflopBet = postflop.count { it.action == "BET" || it.action == "RAISE" }
        val postflopCall = postflop.count { it.action == "CALL" }
        val aggFactor = if (postflopCall > 0) postflopBet.toDouble() / postflopCall else postflopBet.toDouble()

        val preflopFold = pct(preflop.count { it.action == "FOLD" }, preflop.size)
        val postflopFold = pct(postflop.count { it.action == "FOLD" }, postflop.size)

        val rfiDeviation = preflop.count { it.rfiBaseline != null && it.action.notMatching(it.rfiBaseline) }
        val rfiTotal = preflop.count { it.rfiBaseline != null }
        val rfiDevRate = pct(rfiDeviation, rfiTotal)

        val betEvents = events.filter { it.action == "BET" || it.action == "RAISE" }
        val avgBetSizePot = if (betEvents.isEmpty()) 0.0 else
            betEvents.map { it.amount.toDouble() / it.potBefore.coerceAtLeast(1) }.average()

        val buckets = rollingBuckets(events, size = 20)

        return PokerStats(
            totalDecisions = events.size,
            totalHands = decisionsByHand,
            vpip = vpip,
            pfr = pfr,
            aggressionFactor = aggFactor,
            preflopFoldRate = preflopFold,
            postflopFoldRate = postflopFold,
            rfiDeviationRate = rfiDevRate,
            avgBetSizePot = avgBetSizePot,
            recentWindow = buckets,
        )
    }

    private fun empty(): PokerStats = PokerStats(
        totalDecisions = 0,
        totalHands = 0,
        vpip = 0.0,
        pfr = 0.0,
        aggressionFactor = 0.0,
        preflopFoldRate = 0.0,
        postflopFoldRate = 0.0,
        rfiDeviationRate = 0.0,
        avgBetSizePot = 0.0,
        recentWindow = emptyList(),
    )

    private fun estimateHands(events: List<PokerDecisionEvent>): Int {
        // Each hand resets to PREFLOP, so count 'PREFLOP' events as a lower-
        // bound estimate of distinct hands (if the user opens a new hand
        // without acting, it still shows up on street change).
        val preflopCount = events.count { it.street == "PREFLOP" }
        return preflopCount.coerceAtLeast(1)
    }

    private fun rollingBuckets(events: List<PokerDecisionEvent>, size: Int): List<PokerStats.WindowBucket> {
        if (events.size < size) return emptyList()
        val result = mutableListOf<PokerStats.WindowBucket>()
        var i = size
        while (i <= events.size) {
            val window = events.subList(i - size, i)
            val windowPreflop = window.filter { it.street == "PREFLOP" }
            if (windowPreflop.isNotEmpty()) {
                val vpip = pct(
                    windowPreflop.count { it.action !in FOLD_CHECK && it.toCall > 0 || it.action in AGGR },
                    windowPreflop.size,
                )
                val pfr = pct(windowPreflop.count { it.action in AGGR }, windowPreflop.size)
                result += PokerStats.WindowBucket(endIndex = i, vpip = vpip, pfr = pfr)
            }
            i += size
        }
        return result
    }

    private fun pct(num: Int, denom: Int): Double =
        if (denom == 0) 0.0 else (num * 1000.0 / denom).toInt() / 10.0

    private fun String.notMatching(baseline: String): Boolean {
        // Baseline RAISE vs hero anything-but-raise = deviation
        // Baseline FOLD vs hero anything-but-fold  = deviation
        return when (baseline) {
            "RAISE" -> this !in AGGR
            "FOLD" -> this != "FOLD"
            else -> false
        }
    }

    private val FOLD_CHECK = setOf("FOLD", "CHECK")
    private val AGGR = setOf("BET", "RAISE", "ALL_IN")
}
