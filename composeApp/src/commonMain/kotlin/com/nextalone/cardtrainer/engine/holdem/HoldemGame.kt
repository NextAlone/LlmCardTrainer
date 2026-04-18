package com.nextalone.cardtrainer.engine.holdem

enum class Street { PREFLOP, FLOP, TURN, RIVER, SHOWDOWN }

enum class Position(val label: String) {
    SB("SB"), BB("BB"), UTG("UTG"), MP("MP"), CO("CO"), BTN("BTN"),
}

enum class Action(val label: String) {
    FOLD("弃牌"), CHECK("过牌"), CALL("跟注"), BET("下注"), RAISE("加注"), ALL_IN("全下"),
}

data class ActionRecord(
    val street: Street,
    val action: Action,
    val amount: Int,
    val potBefore: Int,
    val toCall: Int,
)

data class HoldemTable(
    val heroPosition: Position,
    val opponents: Int,
    val heroStack: Int,
    val villainStack: Int,
    val pot: Int,
    val toCall: Int,
    val street: Street,
    val hero: List<Card>,
    val board: List<Card>,
    val history: List<ActionRecord>,
) {
    val potOdds: Double get() = if (toCall <= 0) 0.0 else toCall.toDouble() / (pot + toCall)
}

class HoldemTrainer(seed: Long? = null) {

    private val deck = Deck(seed)

    fun newHand(opponents: Int = 1, heroPosition: Position = randomPosition()): HoldemTable {
        val hero = deck.dealN(2)
        return HoldemTable(
            heroPosition = heroPosition,
            opponents = opponents,
            heroStack = 100,
            villainStack = 100,
            pot = if (heroPosition == Position.SB) 3 else 3,
            toCall = if (heroPosition == Position.SB) 1 else if (heroPosition == Position.BB) 0 else 2,
            street = Street.PREFLOP,
            hero = hero,
            board = emptyList(),
            history = emptyList(),
        )
    }

    fun advanceStreet(table: HoldemTable): HoldemTable {
        val cardsToAdd = when (table.street) {
            Street.PREFLOP -> 3
            Street.FLOP -> 1
            Street.TURN -> 1
            else -> 0
        }
        val next = when (table.street) {
            Street.PREFLOP -> Street.FLOP
            Street.FLOP -> Street.TURN
            Street.TURN -> Street.RIVER
            Street.RIVER -> Street.SHOWDOWN
            Street.SHOWDOWN -> Street.SHOWDOWN
        }
        val newBoard = if (cardsToAdd > 0) table.board + deck.dealN(cardsToAdd) else table.board
        return table.copy(street = next, board = newBoard, toCall = 0)
    }

    private fun randomPosition(): Position = Position.entries.random()
}
