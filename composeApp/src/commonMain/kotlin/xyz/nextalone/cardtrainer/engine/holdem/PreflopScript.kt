package xyz.nextalone.cardtrainer.engine.holdem

import kotlin.random.Random

/**
 * Scripted preflop action for every seat that acts BEFORE the hero on this
 * hand. Without this, every 新牌局 handed the user an abstract pot=3 /
 * toCall=[0|1|2] state with no context — the user (and the AI coach) could
 * not tell whether the situation was 'SB limped to me', 'CO opened 3bb',
 * 'UTG raised, BTN cold-called', etc. Those scenarios demand completely
 * different actions.
 *
 * The script uses a coarse but realistic distribution:
 *  - ~65% fold (loose-aggressive 6-max baseline)
 *  - ~25% open-raise (2.5–3bb)
 *  - ~8% cold-call an existing raise
 *  - ~2% 3-bet vs an existing raise (≈ 3× the last raise-to)
 *  - SB has a small chance of limping when the pot is unraised
 *
 * SB=1 and BB=2 chips are already in the pot at the start.
 */
internal data class PreflopScript(
    val records: List<ActionRecord>,
    val pot: Int,
    val currentBet: Int,
    val livePlayers: Int,
) {
    /** toCall for hero = currentBet - whatever-hero-already-posted-as-a-blind. */
    fun toCallForHero(heroPosition: Position): Int {
        val posted = when (heroPosition) {
            Position.SB -> 1
            Position.BB -> 2
            else -> 0
        }
        return (currentBet - posted).coerceAtLeast(0)
    }

    companion object {
        fun generate(heroPosition: Position, rng: Random = Random.Default): PreflopScript {
            val seatsBeforeHero: List<Position> = when (heroPosition) {
                Position.UTG -> emptyList()
                Position.MP -> listOf(Position.UTG)
                Position.CO -> listOf(Position.UTG, Position.MP)
                Position.BTN -> listOf(Position.UTG, Position.MP, Position.CO)
                Position.SB -> listOf(Position.UTG, Position.MP, Position.CO, Position.BTN)
                Position.BB -> listOf(Position.UTG, Position.MP, Position.CO, Position.BTN, Position.SB)
            }

            val records = mutableListOf<ActionRecord>()
            var pot = 3 // SB(1) + BB(2) blinds
            var currentBet = 2 // BB
            // Hero + remaining non-folded seats. Hero counted separately.
            var liveOpponents = 0

            for (seat in seatsBeforeHero) {
                val unraised = currentBet == 2
                val potBefore = pot
                val toCallForThisSeat = when (seat) {
                    Position.SB -> currentBet - 1
                    Position.BB -> currentBet - 2
                    else -> currentBet
                }.coerceAtLeast(0)

                val r = rng.nextDouble()
                when {
                    // SB limp when pot is unraised
                    unraised && seat == Position.SB && r < 0.15 -> {
                        // SB completes to BB (pays 1 more, total in = 2)
                        pot += 1
                        records += ActionRecord(Street.PREFLOP, Action.CALL, 2, potBefore, toCallForThisSeat)
                        liveOpponents++
                    }
                    // Cold-call an existing raise (only if pot is raised)
                    !unraised && r < 0.10 -> {
                        pot += toCallForThisSeat
                        records += ActionRecord(Street.PREFLOP, Action.CALL, currentBet, potBefore, toCallForThisSeat)
                        liveOpponents++
                    }
                    // 3-bet over an existing raise
                    !unraised && r < 0.12 -> {
                        val raiseTo = (currentBet * 3).coerceAtMost(20)
                        val alreadyPosted = when (seat) {
                            Position.SB -> 1; Position.BB -> 2; else -> 0
                        }
                        pot += raiseTo - alreadyPosted
                        currentBet = raiseTo
                        records += ActionRecord(Street.PREFLOP, Action.RAISE, raiseTo, potBefore, toCallForThisSeat)
                        liveOpponents++
                    }
                    // Open-raise when unraised
                    unraised && r < 0.30 -> {
                        val raiseTo = if (rng.nextBoolean()) 5 else 6 // 2.5 or 3bb
                        val alreadyPosted = when (seat) {
                            Position.SB -> 1; Position.BB -> 2; else -> 0
                        }
                        pot += raiseTo - alreadyPosted
                        currentBet = raiseTo
                        records += ActionRecord(Street.PREFLOP, Action.RAISE, raiseTo, potBefore, toCallForThisSeat)
                        liveOpponents++
                    }
                    // Everything else folds
                    else -> {
                        records += ActionRecord(Street.PREFLOP, Action.FOLD, 0, potBefore, toCallForThisSeat)
                    }
                }
            }

            return PreflopScript(
                records = records,
                pot = pot,
                currentBet = currentBet,
                livePlayers = liveOpponents,
            )
        }
    }
}
