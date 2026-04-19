package xyz.nextalone.cardtrainer.engine.holdem.multiway

import kotlin.random.Random
import xyz.nextalone.cardtrainer.engine.holdem.Action
import xyz.nextalone.cardtrainer.engine.holdem.Position
import xyz.nextalone.cardtrainer.engine.holdem.PreflopAction
import xyz.nextalone.cardtrainer.engine.holdem.PreflopChart
import xyz.nextalone.cardtrainer.engine.holdem.Street

/**
 * Range-based decision model for non-hero seats.
 *
 * Preflop uses [PreflopChart] plus heuristic ranges for facing open-raise and
 * facing 3-bet. Postflop is a texture-agnostic size/pot-odds model — we don't
 * model villain hole cards at all, so the user trains on reading sizings and
 * action lines, not specific hand reads.
 *
 * Returns a single legal action + amount. The engine is responsible for
 * clamping amounts against stack and minimum-raise rules one more time.
 */
object RangeModel {

    data class Decision(val action: Action, val amount: Int)

    fun decide(
        table: MultiwayTable,
        seatIndex: Int,
        rng: Random = Random.Default,
    ): Decision {
        val seat = table.seats[seatIndex]
        val toCall = (table.currentBet - seat.contribThisStreet).coerceAtLeast(0)
        return if (table.street == Street.PREFLOP) {
            preflop(table, seatIndex, toCall, rng)
        } else {
            postflop(table, seat.stack, toCall, table.pot, table.currentBet, table.lastRaiseSize, rng)
        }
    }

    // -------- Preflop --------

    private fun preflop(
        table: MultiwayTable,
        seatIndex: Int,
        toCall: Int,
        rng: Random,
    ): Decision {
        val seat = table.seats[seatIndex]
        val cards = requireNotNull(seat.cards) { "preflop seat must have hole cards" }
        val code = PreflopChart.encode(cards)
        val maxContrib = table.seats.maxOf { it.contribThisStreet }
        // BB baseline = 2. An open-raise lifts maxContrib above 2.
        val raiseCount = table.history.count { rec ->
            rec.street == Street.PREFLOP && rec.action == Action.RAISE
        }

        return when (raiseCount) {
            0 -> openDecision(seat.position, cards, code, seat.stack, rng)
            1 -> vsOpenDecision(code, toCall, seat.stack, maxContrib, rng)
            else -> vsThreeBetDecision(code, toCall, seat.stack, rng)
        }
    }

    private fun openDecision(
        position: Position,
        cards: List<xyz.nextalone.cardtrainer.engine.holdem.Card>,
        code: String,
        stack: Int,
        rng: Random,
    ): Decision {
        val rfi = PreflopChart.rfiAction(position, cards)
        return when (rfi) {
            PreflopAction.RAISE -> {
                val size = if (rng.nextBoolean()) 5 else 6  // 2.5–3bb
                Decision(Action.RAISE, size.coerceAtMost(stack))
            }
            PreflopAction.CALL -> Decision(Action.CALL, 0)  // unused by chart today
            PreflopAction.FOLD -> {
                // Small SB limp freq when unraised — approximated via tiny random
                if (position == Position.SB && rng.nextDouble() < 0.15) {
                    Decision(Action.CALL, 0)
                } else {
                    Decision(Action.FOLD, 0)
                }
            }
        }
    }

    private fun vsOpenDecision(
        code: String,
        toCall: Int,
        stack: Int,
        currentBet: Int,
        rng: Random,
    ): Decision {
        val r = rng.nextDouble()
        return when {
            code in PREMIUM_3BET -> {
                val threeBetTo = (currentBet * 3).coerceAtMost(stack)
                Decision(Action.RAISE, threeBetTo)
            }
            code in COLD_CALL_RANGE -> {
                if (r < 0.85) Decision(Action.CALL, toCall.coerceAtMost(stack))
                else Decision(Action.FOLD, 0)
            }
            else -> Decision(Action.FOLD, 0)
        }
    }

    private fun vsThreeBetDecision(code: String, toCall: Int, stack: Int, rng: Random): Decision {
        return when {
            code in FOUR_BET_ONLY -> Decision(Action.RAISE, stack) // jam / large 4-bet
            code in CALL_THREE_BET -> Decision(Action.CALL, toCall.coerceAtMost(stack))
            else -> Decision(Action.FOLD, 0)
        }
    }

    // -------- Postflop --------

    private fun postflop(
        table: MultiwayTable,
        stack: Int,
        toCall: Int,
        pot: Int,
        currentBet: Int,
        lastRaise: Int,
        rng: Random,
    ): Decision {
        val r = rng.nextDouble()
        if (toCall == 0) {
            // No bet to face — check or lead out
            return when {
                r < 0.55 -> Decision(Action.CHECK, 0)
                r < 0.80 -> Decision(Action.BET, betSize(pot, 0.5, stack))
                r < 0.93 -> Decision(Action.BET, betSize(pot, 0.67, stack))
                else -> Decision(Action.BET, betSize(pot, 1.0, stack))
            }
        }
        // Facing a bet. Pot odds = toCall / (pot + toCall).
        val potOdds = toCall.toDouble() / (pot + toCall).coerceAtLeast(1)
        val foldProb = when {
            potOdds <= 0.20 -> 0.15   // <= 1/3 pot bet
            potOdds <= 0.28 -> 0.30   // ~ 1/2 pot
            potOdds <= 0.34 -> 0.50   // ~ 2/3 pot
            potOdds <= 0.40 -> 0.65   // pot-sized
            else -> 0.80              // overbet
        }
        val raiseProb = when {
            potOdds <= 0.25 -> 0.08
            potOdds <= 0.34 -> 0.06
            else -> 0.03
        }
        return when {
            r < foldProb -> Decision(Action.FOLD, 0)
            r < foldProb + raiseProb -> {
                val minRaiseTo = currentBet + lastRaise.coerceAtLeast(toCall)
                val target = ((pot + toCall) * 2.5).toInt()
                Decision(Action.RAISE, target.coerceAtLeast(minRaiseTo).coerceAtMost(stack))
            }
            else -> Decision(Action.CALL, toCall.coerceAtMost(stack))
        }
    }

    private fun betSize(pot: Int, frac: Double, stack: Int): Int {
        val raw = (pot * frac).toInt().coerceAtLeast(1)
        return raw.coerceAtMost(stack)
    }

    // Rough facing-raise ranges — kept compact on purpose. Coaches can refine
    // these later without touching the decision flow.
    private val PREMIUM_3BET = setOf("AA", "KK", "QQ", "AKs", "AKo")
    private val COLD_CALL_RANGE = setOf(
        "JJ", "TT", "99", "88", "77",
        "AQs", "AJs", "ATs", "KQs", "KJs", "QJs", "JTs", "T9s",
        "AQo",
    )
    private val FOUR_BET_ONLY = setOf("AA", "KK", "AKs")
    private val CALL_THREE_BET = setOf("QQ", "JJ", "AKo", "AQs")
}
