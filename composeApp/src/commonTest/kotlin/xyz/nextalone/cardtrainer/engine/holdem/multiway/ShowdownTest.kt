package xyz.nextalone.cardtrainer.engine.holdem.multiway

import xyz.nextalone.cardtrainer.engine.holdem.Card
import xyz.nextalone.cardtrainer.engine.holdem.Position
import xyz.nextalone.cardtrainer.engine.holdem.Rank
import xyz.nextalone.cardtrainer.engine.holdem.Street
import xyz.nextalone.cardtrainer.engine.holdem.Suit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShowdownTest {

    private fun c(r: Rank, s: Suit) = Card(r, s)

    private fun seat(
        pos: Position,
        contrib: Int,
        cards: List<Card>?,
        folded: Boolean = false,
    ) = Seat(
        position = pos,
        stack = 0,
        cards = cards,
        contribThisStreet = 0,
        totalContrib = contrib,
        state = if (folded) SeatState.FOLDED else SeatState.ALL_IN,
        isHero = false,
    )

    @Test
    fun uncontested_fold_end_does_not_require_full_board() {
        // Only one live seat. Board has 0 cards (folded preflop).
        val seats = listOf(
            seat(Position.UTG, 2, null, folded = true),
            seat(Position.BTN, 5, listOf(c(Rank.ACE, Suit.SPADES), c(Rank.KING, Suit.SPADES))),
            seat(Position.BB, 3, null, folded = true),
        )
        val table = MultiwayTable(
            seats = seats,
            heroIndex = 1,
            street = Street.PREFLOP,
            board = emptyList(),
            toActIndex = -1,
            currentBet = 5,
            lastRaiseSize = 3,
            history = emptyList(),
        )
        val outcome = Showdown.run(table)
        assertEquals(1, outcome.awards.size)
        assertEquals(10, outcome.awards[0].potAmount)
        assertEquals(listOf(1), outcome.awards[0].winnerSeats)
        assertEquals(10, outcome.awards[0].perWinner)
    }

    @Test
    fun three_way_showdown_picks_best_five_card_hand() {
        // Board: As Ks Qs Jd 3h
        // Seat 0: Ts Qh → has flush draw no, but pair of Q
        // Seat 1: 2s 2h → pair of 2 → weakest
        // Seat 2: Ah Ad (wait, As on board, so use Ac, Ad) → trip aces
        val board = listOf(
            c(Rank.ACE, Suit.SPADES), c(Rank.KING, Suit.SPADES), c(Rank.QUEEN, Suit.SPADES),
            c(Rank.JACK, Suit.DIAMONDS), c(Rank.THREE, Suit.HEARTS),
        )
        val seats = listOf(
            seat(Position.UTG, 20, listOf(c(Rank.TEN, Suit.SPADES), c(Rank.QUEEN, Suit.HEARTS))),
            seat(Position.BTN, 20, listOf(c(Rank.TWO, Suit.SPADES), c(Rank.TWO, Suit.HEARTS))),
            seat(Position.BB, 20, listOf(c(Rank.ACE, Suit.CLUBS), c(Rank.ACE, Suit.DIAMONDS))),
        )
        val table = MultiwayTable(
            seats = seats,
            heroIndex = 0,
            street = Street.SHOWDOWN,
            board = board,
            toActIndex = -1,
            currentBet = 0,
            lastRaiseSize = 0,
            history = emptyList(),
        )
        val outcome = Showdown.run(table)
        // Seat 0 (Ts Qh) actually completes a royal flush with board As Ks Qs Js… wait,
        // Js is diamond here, so straight flush needs five spades. Seat 0 has Ts with
        // board As Ks Qs → only four spades. Hand: AKQT + kicker from board → straight
        // (A-K-Q-J-T with Jd not spade, so broadway straight, non-flush).
        // Seat 2 (AcAd + board As) → three aces. Straight beats trips → seat 0 wins.
        assertEquals(1, outcome.awards.size)
        assertEquals(listOf(0), outcome.awards[0].winnerSeats)
        assertTrue(outcome.seatRanks[0] != null)
        assertTrue(outcome.seatRanks[1] != null)
        assertTrue(outcome.seatRanks[2] != null)
    }
}
