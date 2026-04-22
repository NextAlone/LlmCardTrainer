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
/**
 * Opponent tightness profile. STANDARD is the baseline (current behavior).
 * LOOSE widens cold-calls and reduces post-flop folds so hero gets more
 * multiway-to-flop spots; TIGHT tightens further for drill-style spots.
 */
enum class VillainStyle(val label: String) {
    TIGHT("紧"),
    STANDARD("标准"),
    LOOSE("松"),
}

object RangeModel {

    data class Decision(val action: Action, val amount: Int)

    fun decide(
        table: MultiwayTable,
        seatIndex: Int,
        rng: Random = Random.Default,
        style: VillainStyle = VillainStyle.STANDARD,
    ): Decision {
        val seat = table.seats[seatIndex]
        val toCall = (table.currentBet - seat.contribThisStreet).coerceAtLeast(0)
        return if (table.street == Street.PREFLOP) {
            preflop(table, seatIndex, toCall, rng, style)
        } else {
            postflop(
                table, seat.stack, toCall, table.pot,
                table.currentBet, table.lastRaiseSize, rng, style,
            )
        }
    }

    // -------- Preflop --------

    private fun preflop(
        table: MultiwayTable,
        seatIndex: Int,
        toCall: Int,
        rng: Random,
        style: VillainStyle,
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
            0 -> openDecision(seat.position, cards, code, seat.stack, rng, style)
            1 -> vsOpenDecision(code, toCall, seat.stack, maxContrib, rng, style)
            else -> vsThreeBetDecision(code, toCall, seat.stack, rng, style)
        }
    }

    private fun openDecision(
        position: Position,
        cards: List<xyz.nextalone.cardtrainer.engine.holdem.Card>,
        code: String,
        stack: Int,
        rng: Random,
        style: VillainStyle,
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
                val sbLimp = when (style) {
                    VillainStyle.TIGHT -> 0.05
                    VillainStyle.STANDARD -> 0.15
                    VillainStyle.LOOSE -> 0.30
                }
                val fishLimp = when (style) {
                    VillainStyle.TIGHT -> 0.0
                    VillainStyle.STANDARD -> 0.0
                    // Loose tables also see mid-position limps with speculative
                    // hands once in a while — wider ranges saw a limp-first
                    // spot into your flop decision.
                    VillainStyle.LOOSE -> if (code in LOOSE_LIMPY) 0.30 else 0.0
                }
                when {
                    position == Position.SB && rng.nextDouble() < sbLimp -> Decision(Action.CALL, 0)
                    fishLimp > 0 && rng.nextDouble() < fishLimp -> Decision(Action.CALL, 0)
                    else -> Decision(Action.FOLD, 0)
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
        style: VillainStyle,
    ): Decision {
        val r = rng.nextDouble()
        val coldCall = when (style) {
            VillainStyle.TIGHT -> COLD_CALL_RANGE
            VillainStyle.STANDARD -> COLD_CALL_RANGE
            VillainStyle.LOOSE -> COLD_CALL_RANGE + COLD_CALL_LOOSE_EXT
        }
        val threeBet = when (style) {
            VillainStyle.TIGHT -> PREMIUM_3BET
            VillainStyle.STANDARD -> PREMIUM_3BET
            VillainStyle.LOOSE -> PREMIUM_3BET + BLUFF_3BET_LOOSE
        }
        // Loose opponents fold cold-calls less often; tight opponents fold more.
        val callThreshold = when (style) {
            VillainStyle.TIGHT -> 0.70
            VillainStyle.STANDARD -> 0.85
            VillainStyle.LOOSE -> 0.95
        }
        return when {
            code in threeBet -> {
                val threeBetTo = (currentBet * 3).coerceAtMost(stack)
                Decision(Action.RAISE, threeBetTo)
            }
            code in coldCall -> {
                if (r < callThreshold) Decision(Action.CALL, toCall.coerceAtMost(stack))
                else Decision(Action.FOLD, 0)
            }
            else -> Decision(Action.FOLD, 0)
        }
    }

    private fun vsThreeBetDecision(
        code: String,
        toCall: Int,
        stack: Int,
        rng: Random,
        style: VillainStyle,
    ): Decision {
        // Loose adds JJ / AQs to the call-three-bet pool; tight keeps it.
        val callPool = when (style) {
            VillainStyle.TIGHT -> CALL_THREE_BET
            VillainStyle.STANDARD -> CALL_THREE_BET
            VillainStyle.LOOSE -> CALL_THREE_BET + setOf("TT", "99", "AJs", "KQs")
        }
        return when {
            code in FOUR_BET_ONLY -> Decision(Action.RAISE, stack) // jam / large 4-bet
            code in callPool -> Decision(Action.CALL, toCall.coerceAtMost(stack))
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
        style: VillainStyle,
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
        val foldBase = when {
            potOdds <= 0.20 -> 0.15   // <= 1/3 pot bet
            potOdds <= 0.28 -> 0.30   // ~ 1/2 pot
            potOdds <= 0.34 -> 0.50   // ~ 2/3 pot
            potOdds <= 0.40 -> 0.65   // pot-sized
            else -> 0.80              // overbet
        }
        // Style shifts fold probability uniformly across sizings.
        val foldProb = (foldBase + when (style) {
            VillainStyle.TIGHT -> 0.10
            VillainStyle.STANDARD -> 0.0
            VillainStyle.LOOSE -> -0.20
        }).coerceIn(0.05, 0.95)
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

    // Loose extensions — added onto the baseline ranges when the user picks
    // the 'Loose' style, giving hero more action than the baseline tight
    // chart would produce.
    private val COLD_CALL_LOOSE_EXT = setOf(
        "66", "55", "44", "33", "22",
        "A9s", "A8s", "A7s", "A6s", "A5s",
        "K9s", "KTo", "Q9s", "QTo", "J9s", "T8s", "98s", "87s", "76s", "65s",
        "KQo", "QJo",
    )
    private val BLUFF_3BET_LOOSE = setOf("AQo", "AJs", "A5s", "A4s", "T9s", "76s")
    private val LOOSE_LIMPY = setOf(
        "66", "55", "44", "33", "22",
        "T9s", "98s", "87s", "76s", "65s", "54s",
        "K9s", "Q9s", "J9s",
    )
}
