package xyz.nextalone.cardtrainer.stats

/**
 * Derived mahjong discard behaviour metrics. The headline question is
 * "how often does the user's discard match the engine's top recommendation",
 * plus quality signals like how often shanten actually improved after the
 * discard and how full the wall of live waits is.
 */
data class MahjongStats(
    val totalDecisions: Int,
    val engineTop1MatchRate: Double,
    val shantenImprovementRate: Double,
    val avgLiveWaits: Double,
    val avgWallRemainingAtDiscard: Double,
    val recentWindow: List<WindowBucket>,
) {
    data class WindowBucket(val endIndex: Int, val top1Match: Double)
}

object MahjongStatsCalc {

    fun compute(events: List<MahjongDecisionEvent>): MahjongStats {
        if (events.isEmpty()) return empty()

        val top1 = events.count { it.isEngineTop1 }
        val improved = events.count { it.shantenAfter < it.shantenBefore }
        val top1Rate = pct(top1, events.size)
        val improveRate = pct(improved, events.size)
        val avgLive = events.map { it.liveWaitsAfter.toDouble() }.average()
        val avgWall = events.map { it.wallRemaining.toDouble() }.average()
        val buckets = rollingBuckets(events, size = 20)

        return MahjongStats(
            totalDecisions = events.size,
            engineTop1MatchRate = top1Rate,
            shantenImprovementRate = improveRate,
            avgLiveWaits = avgLive,
            avgWallRemainingAtDiscard = avgWall,
            recentWindow = buckets,
        )
    }

    private fun empty() = MahjongStats(
        totalDecisions = 0,
        engineTop1MatchRate = 0.0,
        shantenImprovementRate = 0.0,
        avgLiveWaits = 0.0,
        avgWallRemainingAtDiscard = 0.0,
        recentWindow = emptyList(),
    )

    private fun rollingBuckets(events: List<MahjongDecisionEvent>, size: Int): List<MahjongStats.WindowBucket> {
        if (events.size < size) return emptyList()
        val out = mutableListOf<MahjongStats.WindowBucket>()
        var i = size
        while (i <= events.size) {
            val window = events.subList(i - size, i)
            val match = pct(window.count { it.isEngineTop1 }, window.size)
            out += MahjongStats.WindowBucket(endIndex = i, top1Match = match)
            i += size
        }
        return out
    }

    private fun pct(num: Int, denom: Int): Double =
        if (denom == 0) 0.0 else (num * 1000.0 / denom).toInt() / 10.0
}
