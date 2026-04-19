package xyz.nextalone.cardtrainer.engine.holdem.multiway

import kotlinx.serialization.Serializable

@Serializable
data class SidePot(
    val amount: Int,
    val eligibleSeats: List<Int>,
)
