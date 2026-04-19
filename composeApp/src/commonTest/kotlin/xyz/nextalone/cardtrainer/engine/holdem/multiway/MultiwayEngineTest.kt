package xyz.nextalone.cardtrainer.engine.holdem.multiway

import xyz.nextalone.cardtrainer.engine.holdem.Deck
import xyz.nextalone.cardtrainer.engine.holdem.Position
import xyz.nextalone.cardtrainer.engine.holdem.Street
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiwayEngineTest {

    @Test
    fun newHand_seats_hero_and_opponents_with_blinds_posted() {
        val deck = Deck(seed = 42L)
        val table = MultiwayEngine.newHand(
            heroPosition = Position.BTN,
            opponents = 3,
            deck = deck,
            rng = Random(42L),
        )
        assertEquals(6, table.seats.size)
        val live = table.seats.filter { it.isLive }
        assertEquals(4, live.size)
        assertEquals(Position.BTN, table.hero.position)
        assertTrue(table.hero.cards != null && table.hero.cards!!.size == 2)
        // Blinds posted: SB=1 BB=2 if both seated
        val sb = table.seats.first { it.position == Position.SB }
        val bb = table.seats.first { it.position == Position.BB }
        if (sb.state == SeatState.IN_HAND) assertEquals(1, sb.contribThisStreet)
        if (bb.state == SeatState.IN_HAND) assertEquals(2, bb.contribThisStreet)
        assertEquals(2, table.currentBet)
    }

    @Test
    fun stepUntilHero_advances_to_hero_or_closes_hand() {
        repeat(20) { attempt ->
            val seed = 100L + attempt
            val deck = Deck(seed = seed)
            val table = MultiwayEngine.newHand(
                heroPosition = Position.CO,
                opponents = 5,
                deck = deck,
                rng = Random(seed),
            )
            val after = MultiwayEngine.stepUntilHero(table, rng = Random(seed))
            val terminated = after.isHeroTurn ||
                MultiwayEngine.isHandOver(after) ||
                after.isStreetClosed
            assertTrue(terminated, "iteration $attempt: engine did not reach a terminal step")
        }
    }

    @Test
    fun fullHand_preflop_to_showdown_does_not_throw() {
        val seed = 7L
        val deck = Deck(seed = seed)
        var table = MultiwayEngine.newHand(
            heroPosition = Position.BTN,
            opponents = 2,
            deck = deck,
            rng = Random(seed),
        )
        var guard = 0
        while (!MultiwayEngine.isHandOver(table) && guard < 40) {
            guard++
            table = MultiwayEngine.stepUntilHero(table, rng = Random(seed + guard))
            if (table.isHeroTurn) {
                // Hero always checks or folds — pick the cheapest legal move.
                table = if (table.heroToCall == 0) {
                    MultiwayEngine.applyHeroAction(table, xyz.nextalone.cardtrainer.engine.holdem.Action.CHECK, 0)
                } else {
                    MultiwayEngine.applyHeroAction(table, xyz.nextalone.cardtrainer.engine.holdem.Action.FOLD, 0)
                }
                continue
            }
            if (table.isStreetClosed && table.street != Street.SHOWDOWN) {
                table = MultiwayEngine.advanceStreet(table, deck)
            }
        }
        assertTrue(guard < 40, "hand did not terminate within 40 iterations")
        // Showdown should not throw with whatever state we ended in.
        val outcome = Showdown.run(table)
        assertTrue(outcome.awards.isNotEmpty())
    }
}
