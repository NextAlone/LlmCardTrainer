package xyz.nextalone.cardtrainer.engine.holdem.multiway

import kotlin.random.Random
import xyz.nextalone.cardtrainer.engine.holdem.Action
import xyz.nextalone.cardtrainer.engine.holdem.ActionRecord
import xyz.nextalone.cardtrainer.engine.holdem.Deck
import xyz.nextalone.cardtrainer.engine.holdem.Position
import xyz.nextalone.cardtrainer.engine.holdem.Street

/**
 * Multiway holdem engine. Drives every non-hero seat via [RangeModel];
 * [stepUntilHero] recurses until the hero must act, the street closes, or
 * the hand is over. Streets advance with [advanceStreet]; showdown happens
 * in [Showdown] (Phase 4).
 *
 * Seat acting order is 6-max:
 *  - preflop: UTG → MP → CO → BTN → SB → BB
 *  - postflop: SB → BB → UTG → MP → CO → BTN
 * Seats that are not dealt in (opponent count < 5) are marked FOLDED at
 * hand start and skipped by the actor iterator.
 */
object MultiwayEngine {

    private val PREFLOP_ORDER = listOf(
        Position.UTG, Position.MP, Position.CO, Position.BTN, Position.SB, Position.BB,
    )
    private val POSTFLOP_ORDER = listOf(
        Position.SB, Position.BB, Position.UTG, Position.MP, Position.CO, Position.BTN,
    )

    private const val SMALL_BLIND = 1
    private const val BIG_BLIND = 2

    fun newHand(
        heroPosition: Position,
        opponents: Int,
        baseStack: Int = 100,
        deck: Deck,
        rng: Random = Random.Default,
    ): MultiwayTable {
        require(opponents in 1..5) { "opponents must be 1..5" }
        // Blinds must be posted to justify currentBet=2, so BB (and SB if we have
        // room) are mandatory occupants. Remaining seats are picked randomly from
        // non-blind positions so the user sees different table shapes per hand.
        val mandatory = buildList {
            if (heroPosition != Position.BB) add(Position.BB)
            if (opponents >= 2 && heroPosition != Position.SB && Position.SB !in this) add(Position.SB)
        }.take(opponents)
        val pool = (Position.entries - heroPosition - mandatory.toSet()).shuffled(rng)
        val opponentPositions = (mandatory + pool.take(opponents - mandatory.size))
        val playing = (listOf(heroPosition) + opponentPositions).toSet()

        val seats = PREFLOP_ORDER.map { pos ->
            val seated = pos in playing
            Seat(
                position = pos,
                stack = if (seated) baseStack else 0,
                cards = if (seated) deck.dealN(2) else null,
                state = if (seated) SeatState.IN_HAND else SeatState.FOLDED,
                isHero = pos == heroPosition,
            )
        }
        val withBlinds = seats.map { seat ->
            when {
                seat.position == Position.SB && seat.state == SeatState.IN_HAND -> seat.copy(
                    stack = seat.stack - SMALL_BLIND,
                    contribThisStreet = SMALL_BLIND,
                    totalContrib = SMALL_BLIND,
                )
                seat.position == Position.BB && seat.state == SeatState.IN_HAND -> seat.copy(
                    stack = seat.stack - BIG_BLIND,
                    contribThisStreet = BIG_BLIND,
                    totalContrib = BIG_BLIND,
                )
                else -> seat
            }
        }
        val heroIdx = withBlinds.indexOfFirst { it.isHero }
        val toAct = firstActor(withBlinds, Street.PREFLOP, fromIdx = -1)
        return MultiwayTable(
            seats = withBlinds,
            heroIndex = heroIdx,
            street = Street.PREFLOP,
            board = emptyList(),
            toActIndex = toAct,
            currentBet = BIG_BLIND,
            lastRaiseSize = BIG_BLIND,
            history = emptyList(),
        )
    }

    fun stepUntilHero(
        table: MultiwayTable,
        rng: Random = Random.Default,
        style: VillainStyle = VillainStyle.STANDARD,
    ): MultiwayTable {
        var state = table
        var guard = 0
        while (!state.isHeroTurn && !state.isStreetClosed && !isHandOver(state) && guard < 100) {
            guard++
            val decision = RangeModel.decide(state, state.toActIndex, rng, style)
            state = applySeatDecision(state, state.toActIndex, decision)
        }
        return state
    }

    fun applyHeroAction(table: MultiwayTable, action: Action, rawAmount: Int): MultiwayTable {
        require(table.isHeroTurn) { "not hero's turn" }
        return applySeatDecision(table, table.heroIndex, RangeModel.Decision(action, rawAmount))
    }

    fun isHandOver(table: MultiwayTable): Boolean =
        table.seats.count { it.isLive } <= 1 || table.street == Street.SHOWDOWN

    // TODO(run-it-twice): when every remaining seat is ALL_IN before the
    // river, poker clients often let users request a second runout so the
    // pot splits across two boards (halves equity variance). To extend:
    //  1. Add a `runouts: Int = 1` parameter and a board-per-runout field
    //     on MultiwayTable (List<List<Card>>).
    //  2. Stream each runout's board in advanceStreet until SHOWDOWN.
    //  3. In Showdown.run, evaluate each runout independently and merge
    //     the awards (per-pot, per-runout) — odd chip distribution stays
    //     BTN-left ordered within each runout.
    // Out of scope for the current pass; training sessions only do a
    // single runout.
    fun advanceStreet(table: MultiwayTable, deck: Deck): MultiwayTable {
        val cardsToAdd = when (table.street) {
            Street.PREFLOP -> 3
            Street.FLOP -> 1
            Street.TURN -> 1
            else -> 0
        }
        val nextStreet = when (table.street) {
            Street.PREFLOP -> Street.FLOP
            Street.FLOP -> Street.TURN
            Street.TURN -> Street.RIVER
            Street.RIVER -> Street.SHOWDOWN
            Street.SHOWDOWN -> Street.SHOWDOWN
        }
        val newBoard = if (cardsToAdd > 0) table.board + deck.dealN(cardsToAdd) else table.board
        val resetSeats = table.seats.map {
            it.copy(contribThisStreet = 0, actedThisStreet = false)
        }
        val newToAct = if (nextStreet == Street.SHOWDOWN || isHandOver(table.copy(seats = resetSeats, street = nextStreet))) {
            -1
        } else {
            firstActor(resetSeats, nextStreet, fromIdx = -1)
        }
        return table.copy(
            seats = resetSeats,
            street = nextStreet,
            board = newBoard,
            toActIndex = newToAct,
            currentBet = 0,
            lastRaiseSize = 0,
        )
    }

    // ---- internal ----

    private fun applySeatDecision(
        table: MultiwayTable,
        seatIndex: Int,
        decision: RangeModel.Decision,
    ): MultiwayTable {
        val seat = table.seats[seatIndex]
        val toCall = (table.currentBet - seat.contribThisStreet).coerceAtLeast(0)
        val prevCurrentBet = table.currentBet

        // Coerce CHECK when toCall>0 → CALL; coerce CALL when toCall==0 → CHECK.
        // RangeModel can produce either in edge cases; engine normalizes.
        val normalized = when {
            decision.action == Action.CHECK && toCall > 0 ->
                RangeModel.Decision(Action.CALL, toCall)
            decision.action == Action.CALL && toCall == 0 ->
                RangeModel.Decision(Action.CHECK, 0)
            else -> decision
        }

        val (newSeat, newCurrentBet, newLastRaise, raised) = applyOne(seat, normalized, table.currentBet, table.lastRaiseSize)

        val record = ActionRecord(
            street = table.street,
            action = normalized.action,
            amount = when (normalized.action) {
                Action.CALL -> (newSeat.contribThisStreet - seat.contribThisStreet)
                Action.BET, Action.RAISE -> newSeat.contribThisStreet
                Action.ALL_IN -> newSeat.contribThisStreet
                else -> 0
            },
            potBefore = table.pot,
            toCall = toCall,
            actor = seat.position,
        )

        val updatedSeats = table.seats.toMutableList().also { it[seatIndex] = newSeat }
        if (raised) {
            // Every other seat that can still act must respond to the new size.
            for (i in updatedSeats.indices) {
                if (i == seatIndex) continue
                val s = updatedSeats[i]
                if (s.canAct) updatedSeats[i] = s.copy(actedThisStreet = false)
            }
        }

        val stepTable = table.copy(
            seats = updatedSeats,
            currentBet = newCurrentBet,
            lastRaiseSize = newLastRaise,
            history = table.history + record,
        )
        val nextAct = computeNextActor(stepTable, seatIndex)
        return stepTable.copy(toActIndex = nextAct)
    }

    private data class OneApplied(
        val seat: Seat,
        val currentBet: Int,
        val lastRaise: Int,
        val raised: Boolean,
    )

    private fun applyOne(
        seat: Seat,
        decision: RangeModel.Decision,
        currentBet: Int,
        lastRaise: Int,
    ): OneApplied {
        return when (decision.action) {
            Action.FOLD -> OneApplied(
                seat = seat.copy(state = SeatState.FOLDED, actedThisStreet = true),
                currentBet = currentBet,
                lastRaise = lastRaise,
                raised = false,
            )
            Action.CHECK -> OneApplied(
                seat = seat.copy(actedThisStreet = true),
                currentBet = currentBet,
                lastRaise = lastRaise,
                raised = false,
            )
            Action.CALL -> {
                val paid = (currentBet - seat.contribThisStreet).coerceAtLeast(0).coerceAtMost(seat.stack)
                val newStack = seat.stack - paid
                OneApplied(
                    seat = seat.copy(
                        stack = newStack,
                        contribThisStreet = seat.contribThisStreet + paid,
                        totalContrib = seat.totalContrib + paid,
                        state = if (newStack == 0) SeatState.ALL_IN else seat.state,
                        actedThisStreet = true,
                    ),
                    currentBet = currentBet,
                    lastRaise = lastRaise,
                    raised = false,
                )
            }
            Action.BET, Action.RAISE, Action.ALL_IN -> {
                // amount is intended raise-to (new contribThisStreet total for this seat)
                val intended = if (decision.action == Action.ALL_IN) {
                    seat.contribThisStreet + seat.stack
                } else {
                    val minRaiseTo = (currentBet + lastRaise).coerceAtLeast(currentBet + 1)
                    // Treat BET/RAISE.amount as "raise-to" total chips staked this street
                    // (matches RangeModel's convention). Clamp to min-raise on the low end
                    // and stack cap on the high end.
                    val raiseTo = decision.amount.coerceAtLeast(minRaiseTo)
                    raiseTo.coerceAtMost(seat.contribThisStreet + seat.stack)
                }
                val paid = (intended - seat.contribThisStreet).coerceAtLeast(0).coerceAtMost(seat.stack)
                val newContrib = seat.contribThisStreet + paid
                val newStack = seat.stack - paid
                val wentUp = newContrib > currentBet
                // NLHE: a short all-in whose increment is below the last full raise
                // does NOT reopen action for players who already acted this street.
                val fullRaise = wentUp && (newContrib - currentBet) >= lastRaise.coerceAtLeast(1)
                val newCurrent = if (wentUp) newContrib else currentBet
                val newLastRaise = if (fullRaise) (newContrib - currentBet) else lastRaise
                OneApplied(
                    seat = seat.copy(
                        stack = newStack,
                        contribThisStreet = newContrib,
                        totalContrib = seat.totalContrib + paid,
                        state = if (newStack == 0) SeatState.ALL_IN else seat.state,
                        actedThisStreet = true,
                    ),
                    currentBet = newCurrent,
                    lastRaise = newLastRaise,
                    raised = fullRaise,
                )
            }
        }
    }

    private fun computeNextActor(table: MultiwayTable, justActedIdx: Int): Int {
        val live = table.seats.count { it.isLive }
        if (live <= 1) return -1
        val canActSeats = table.seats.withIndex().filter { it.value.canAct }
        if (canActSeats.isEmpty()) return -1
        val streetClosed = canActSeats.all { (_, s) ->
            s.actedThisStreet && s.contribThisStreet == table.currentBet
        }
        if (streetClosed) return -1

        val order = if (table.street == Street.PREFLOP) PREFLOP_ORDER else POSTFLOP_ORDER
        val fromPos = table.seats[justActedIdx].position
        val start = order.indexOf(fromPos)
        for (i in 1..order.size) {
            val pos = order[(start + i) % order.size]
            val idx = table.seats.indexOfFirst { it.position == pos }
            if (idx < 0) continue
            val s = table.seats[idx]
            if (!s.canAct) continue
            val needs = !s.actedThisStreet || s.contribThisStreet < table.currentBet
            if (needs) return idx
        }
        return -1
    }

    private fun firstActor(seats: List<Seat>, street: Street, fromIdx: Int): Int {
        val order = if (street == Street.PREFLOP) PREFLOP_ORDER else POSTFLOP_ORDER
        for (pos in order) {
            val idx = seats.indexOfFirst { it.position == pos }
            if (idx < 0) continue
            if (seats[idx].canAct) return idx
        }
        return -1
    }
}
