package xyz.nextalone.cardtrainer.engine.holdem.multiway

import xyz.nextalone.cardtrainer.engine.holdem.Position
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SidePotBuilderTest {

    private fun seat(pos: Position, contrib: Int, folded: Boolean = false, allIn: Boolean = false): Seat =
        Seat(
            position = pos,
            stack = if (allIn) 0 else 100,
            cards = null,
            contribThisStreet = 0,
            totalContrib = contrib,
            state = when {
                folded -> SeatState.FOLDED
                allIn -> SeatState.ALL_IN
                else -> SeatState.IN_HAND
            },
            isHero = false,
        )

    @Test
    fun single_layer_pot_when_everyone_contributed_equally() {
        val seats = listOf(
            seat(Position.UTG, 50),
            seat(Position.BTN, 50),
            seat(Position.BB, 50),
        )
        val pots = SidePotBuilder.build(seats)
        assertEquals(1, pots.size)
        assertEquals(150, pots[0].amount)
        assertEquals(listOf(0, 1, 2), pots[0].eligibleSeats)
    }

    @Test
    fun three_way_all_in_produces_three_layers() {
        // Seat A all-in 20, Seat B all-in 60, Seat C has 100.
        val seats = listOf(
            seat(Position.UTG, 20, allIn = true),
            seat(Position.BTN, 60, allIn = true),
            seat(Position.BB, 100, allIn = true),
        )
        val pots = SidePotBuilder.build(seats)
        assertEquals(3, pots.size)
        // Main pot: 3 * 20 = 60, eligible [0,1,2]
        assertEquals(60, pots[0].amount)
        assertEquals(listOf(0, 1, 2), pots[0].eligibleSeats)
        // Second layer: 2 * 40 = 80, eligible [1,2]
        assertEquals(80, pots[1].amount)
        assertEquals(listOf(1, 2), pots[1].eligibleSeats)
        // Third layer: 1 * 40 = 40, eligible [2]
        assertEquals(40, pots[2].amount)
        assertEquals(listOf(2), pots[2].eligibleSeats)
    }

    @Test
    fun folded_contribution_rolls_into_main_pot_as_dead_money() {
        // C folded with 80 committed; A and B live at 50 each.
        val seats = listOf(
            seat(Position.UTG, 50),
            seat(Position.BTN, 50),
            seat(Position.BB, 80, folded = true),
        )
        val pots = SidePotBuilder.build(seats)
        // Main pot: layer at 50 → 3 seats contributed 50 each = 150
        // Folded seat's excess (30) above highest live level (50) is dead money → into main pot
        val totalMoney = 50 + 50 + 80
        assertEquals(totalMoney, pots.sumOf { it.amount })
        assertTrue(pots.all { 0 in it.eligibleSeats && 1 in it.eligibleSeats })
        assertTrue(pots.none { 2 in it.eligibleSeats })
    }

    @Test
    fun dead_money_above_highest_live_level_lands_in_deepest_pot() {
        // Pathological over-bet scenario: BTN folded 150, UTG all-in 30,
        // BB all-in 50. Live levels = [30, 50]. Dead money (above 50) = 100
        // must attach to the deepest side pot (eligible only to BB), not to
        // the main pot (which would unfairly reward UTG's short all-in).
        val seats = listOf(
            seat(Position.UTG, 30, allIn = true),
            seat(Position.BTN, 150, folded = true),
            seat(Position.BB, 50, allIn = true),
        )
        val pots = SidePotBuilder.build(seats)
        assertEquals(2, pots.size)
        // Main pot: 3 * 30 = 90, eligible live seats at level 30 → [UTG(0), BB(2)].
        assertEquals(90, pots[0].amount)
        assertEquals(listOf(0, 2), pots[0].eligibleSeats)
        // Deepest pot: live layer 30→50 = (0 + 20 + 20) = 40, PLUS dead money 100 → 140.
        // Eligible only [BB(2)]; folded BTN never eligible anywhere.
        assertEquals(140, pots[1].amount)
        assertEquals(listOf(2), pots[1].eligibleSeats)
        // Sanity: total money conserved, folded seat never eligible.
        assertEquals(30 + 150 + 50, pots.sumOf { it.amount })
        assertTrue(pots.none { 1 in it.eligibleSeats })
    }

    @Test
    fun sole_survivor_gets_one_pot_even_without_showdown() {
        val seats = listOf(
            seat(Position.UTG, 10, folded = true),
            seat(Position.BTN, 5, folded = true),
            seat(Position.BB, 2), // sole live
        )
        val pots = SidePotBuilder.build(seats)
        assertEquals(1, pots.size)
        assertEquals(17, pots[0].amount)
        assertEquals(listOf(2), pots[0].eligibleSeats)
    }
}
