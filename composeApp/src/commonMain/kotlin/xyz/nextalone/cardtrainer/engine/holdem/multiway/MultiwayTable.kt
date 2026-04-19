package xyz.nextalone.cardtrainer.engine.holdem.multiway

import kotlinx.serialization.Serializable
import xyz.nextalone.cardtrainer.engine.holdem.ActionRecord
import xyz.nextalone.cardtrainer.engine.holdem.Card
import xyz.nextalone.cardtrainer.engine.holdem.Street

@Serializable
data class MultiwayTable(
    val seats: List<Seat>,
    val heroIndex: Int,
    val street: Street,
    val board: List<Card>,
    val toActIndex: Int,
    val currentBet: Int,
    val lastRaiseSize: Int,
    val history: List<ActionRecord>,
) {
    val hero: Seat get() = seats[heroIndex]
    val pot: Int get() = seats.sumOf { it.totalContrib }
    val heroToCall: Int get() = (currentBet - hero.contribThisStreet).coerceAtLeast(0)
    val liveSeats: List<Seat> get() = seats.filter { it.isLive }
    val isHeroTurn: Boolean get() = toActIndex == heroIndex
    val isStreetClosed: Boolean get() = toActIndex < 0
}
