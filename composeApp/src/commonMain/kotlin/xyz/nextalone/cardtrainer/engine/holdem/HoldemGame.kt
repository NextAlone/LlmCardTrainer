package xyz.nextalone.cardtrainer.engine.holdem

import kotlinx.serialization.Serializable

@Serializable
enum class Street { PREFLOP, FLOP, TURN, RIVER, SHOWDOWN }

@Serializable
enum class Position(val label: String) {
    SB("SB"), BB("BB"), UTG("UTG"), MP("MP"), CO("CO"), BTN("BTN"),
}

@Serializable
enum class Action(val label: String) {
    FOLD("弃牌"), CHECK("过牌"), CALL("跟注"), BET("下注"), RAISE("加注"), ALL_IN("全下"),
}

@Serializable
data class ActionRecord(
    val street: Street,
    val action: Action,
    val amount: Int,
    val potBefore: Int,
    val toCall: Int,
)

@Serializable
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

class HoldemTrainer(private val baseSeed: Long? = null) {

    // A fresh, shuffled deck per hand so long trainer sessions never exhaust it.
    private var handCounter: Long = 0
    private var deck: Deck = Deck(baseSeed)

    fun newHand(opponents: Int = 1, heroPosition: Position = randomPosition()): HoldemTable {
        handCounter++
        deck = Deck(baseSeed?.let { it + handCounter })
        val hero = deck.dealN(2)
        return HoldemTable(
            heroPosition = heroPosition,
            opponents = opponents,
            heroStack = 100,
            villainStack = 100,
            pot = 3,
            toCall = when (heroPosition) {
                Position.SB -> 1
                Position.BB -> 0
                else -> 2
            },
            street = Street.PREFLOP,
            hero = hero,
            board = emptyList(),
            history = emptyList(),
        )
    }

    /**
     * Rebuild internal deck state from a persisted table: a fresh shuffled deck
     * minus every card that's already on the table (hero + board). Future
     * `advanceStreet` deals from this pruned deck.
     */
    fun restoreFrom(table: HoldemTable) {
        deck = Deck(baseSeed)
        deck.removeSpecific(table.hero + table.board)
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

    /**
     * Apply the hero's action: record it in the table history, update pot / to-call
     * / hero stack. Folding sets street to SHOWDOWN (hand ends).
     */
    fun applyAction(table: HoldemTable, action: Action, amount: Int): HoldemTable {
        val record = ActionRecord(
            street = table.street,
            action = action,
            amount = amount,
            potBefore = table.pot,
            toCall = table.toCall,
        )
        return when (action) {
            Action.FOLD -> table.copy(
                history = table.history + record,
                street = Street.SHOWDOWN,
            )
            Action.CHECK -> table.copy(
                history = table.history + record,
                toCall = 0,
            )
            Action.CALL -> {
                val paid = table.toCall.coerceAtMost(table.heroStack)
                table.copy(
                    history = table.history + record,
                    pot = table.pot + paid,
                    heroStack = table.heroStack - paid,
                    toCall = 0,
                )
            }
            Action.BET, Action.RAISE -> {
                val paid = amount.coerceAtMost(table.heroStack)
                table.copy(
                    history = table.history + record,
                    pot = table.pot + paid,
                    heroStack = table.heroStack - paid,
                    // Assume villain calls for training purposes: toCall goes back to 0
                    // so we can smoothly advance to the next street.
                    toCall = 0,
                )
            }
            Action.ALL_IN -> {
                val paid = table.heroStack
                table.copy(
                    history = table.history + record,
                    pot = table.pot + paid,
                    heroStack = 0,
                    toCall = 0,
                )
            }
        }
    }

    private fun randomPosition(): Position = Position.entries.random()
}

/**
 * A preset action a user can pick from the UI.
 */
data class ActionPreset(val action: Action, val amount: Int, val label: String)

object ActionPresets {
    /** Build preset buttons appropriate for the current table state. */
    fun forTable(table: HoldemTable): List<ActionPreset> = buildList {
        val pot = table.pot
        val toCall = table.toCall
        val stack = table.heroStack
        if (toCall == 0) {
            add(ActionPreset(Action.CHECK, 0, "过牌"))
            add(ActionPreset(Action.BET, (pot / 2).coerceAtLeast(1), "下注 ½ 底池"))
            add(ActionPreset(Action.BET, pot.coerceAtLeast(1), "下注 1 底池"))
        } else {
            add(ActionPreset(Action.FOLD, 0, "弃牌"))
            add(ActionPreset(Action.CALL, toCall, "跟注 $toCall"))
            add(ActionPreset(Action.RAISE, (toCall * 3).coerceAtMost(stack), "加注 3x"))
            add(ActionPreset(Action.RAISE, ((pot + toCall) + toCall).coerceAtMost(stack), "加注 pot"))
        }
        if (stack > 0) add(ActionPreset(Action.ALL_IN, stack, "全下 $stack"))
    }
}
