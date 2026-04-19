package xyz.nextalone.cardtrainer.engine.holdem.multiway

import kotlinx.serialization.Serializable
import xyz.nextalone.cardtrainer.engine.holdem.Card
import xyz.nextalone.cardtrainer.engine.holdem.Position

@Serializable
enum class SeatState { IN_HAND, FOLDED, ALL_IN }

@Serializable
data class Seat(
    val position: Position,
    val stack: Int,
    val cards: List<Card>? = null,
    val contribThisStreet: Int = 0,
    val totalContrib: Int = 0,
    val state: SeatState = SeatState.IN_HAND,
    val isHero: Boolean = false,
    val actedThisStreet: Boolean = false,
) {
    val isLive: Boolean get() = state != SeatState.FOLDED
    val canAct: Boolean get() = state == SeatState.IN_HAND && stack > 0
}
