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
    /**
     * Seat position that performed this action. Optional for backward
     * compatibility: the single-villain engine doesn't track per-seat actors
     * and leaves it null; the multiway engine fills it so prompt/UI can say
     * "UTG raised 6, CO 3-bet to 18".
     */
    val actor: Position? = null,
)

@Serializable
data class ShowdownResult(
    /** Villain's two hole cards, revealed. */
    val villainCards: List<Card>,
    val heroCategory: HandCategory,
    val villainCategory: HandCategory,
    val heroWon: Boolean,
    val isTie: Boolean,
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
        val script = PreflopScript.generate(heroPosition)
        // Count remaining live opponents from the script's fold/raise calls so
        // the equity calculation knows how many non-hero hands to deal against.
        val liveOpponents = script.livePlayers.coerceAtLeast(1)
        return HoldemTable(
            heroPosition = heroPosition,
            opponents = liveOpponents,
            heroStack = 100,
            villainStack = 100,
            pot = script.pot,
            toCall = script.toCallForHero(heroPosition),
            street = Street.PREFLOP,
            hero = hero,
            board = emptyList(),
            history = script.records,
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
        if (next == Street.SHOWDOWN) {
            return table.copy(street = next, board = newBoard, toCall = 0)
        }
        // Post-flop: villain (OOP) acts first on the new street. Generate a
        // plausible scripted line so the hero sees 'opponent checked' or
        // 'opponent bet ½ pot' before deciding.
        val villain = PostflopVillain.act(street = next, potAtStreetStart = table.pot)
        return table.copy(
            street = next,
            board = newBoard,
            pot = villain.pot,
            toCall = villain.toCall,
            history = table.history + villain.records,
        )
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

    /**
     * Reveal villain's two hole cards and compare best 5-card hands. Called
     * when the hero's final action on the river doesn't fold or force a
     * villain fold — both players are now settled and have to show down.
     * The board must be complete (5 cards dealt).
     */
    fun concludeToShowdown(table: HoldemTable): Pair<HoldemTable, ShowdownResult> {
        require(table.board.size == 5) {
            "concludeToShowdown expects a full board, got ${table.board.size} cards"
        }
        val villainCards = deck.dealN(2)
        val heroStr = HandEvaluator.evaluate(table.hero + table.board)
        val villainStr = HandEvaluator.evaluate(villainCards + table.board)
        val cmp = heroStr.compareTo(villainStr)
        val result = ShowdownResult(
            villainCards = villainCards,
            heroCategory = heroStr.category,
            villainCategory = villainStr.category,
            heroWon = cmp > 0,
            isTie = cmp == 0,
        )
        val newTable = table.copy(street = Street.SHOWDOWN)
        return newTable to result
    }

    private fun randomPosition(): Position = Position.entries.random()
}

/**
 * A preset action a user can pick from the UI.
 */
data class ActionPreset(val action: Action, val amount: Int, val label: String)

object ActionPresets {
    /**
     * Build preset buttons appropriate for the current table state.
     *
     * Sizing conventions:
     *  - **Preflop with no raise to face** (open / iso): in BB units
     *    (2.5bb / 3bb / 4bb / 5bb) — matches how players think pre-flop.
     *  - **Post-flop with no bet to face**: in pot fractions
     *    (⅓ / ½ / ⅔ / pot / 1.5× / 2× overbet).
     *  - **Facing a bet/raise**: raise sizes 2× / 2.5× / 3× the call amount
     *    plus a pot-sized raise.
     *  - All-in is always available as the last option.
     *
     * Callers can also pass a custom amount through the DecidingBlock's
     * 自定义 input; presets are a discoverability convenience, not the
     * only way to submit.
     */
    fun forTable(table: HoldemTable): List<ActionPreset> = buildList {
        val pot = table.pot
        val toCall = table.toCall
        val stack = table.heroStack
        val isPreflop = table.board.isEmpty()

        if (toCall == 0) {
            add(ActionPreset(Action.CHECK, 0, "过牌"))
            if (isPreflop) {
                // BB = 2 chips in this simplified table.
                add(ActionPreset(Action.BET, 5.coerceAtMost(stack), "加注 2.5bb"))
                add(ActionPreset(Action.BET, 6.coerceAtMost(stack), "加注 3bb"))
                add(ActionPreset(Action.BET, 8.coerceAtMost(stack), "加注 4bb"))
                add(ActionPreset(Action.BET, 10.coerceAtMost(stack), "加注 5bb"))
            } else {
                // ASCII fraction glyphs only — `⅓ / ½ / ⅔` may be missing in
                // the system fallback font and render as blank boxes on some
                // Android devices. Suffix `p` == pot; verbose "底池" gets
                // truncated in the narrow action-sheet chip row.
                add(ActionPreset(Action.BET, (pot / 3).coerceAtLeast(1), "下注 1/3p"))
                add(ActionPreset(Action.BET, (pot / 2).coerceAtLeast(1), "下注 1/2p"))
                add(ActionPreset(Action.BET, (pot * 2 / 3).coerceAtLeast(1), "下注 2/3p"))
                add(ActionPreset(Action.BET, pot.coerceAtLeast(1), "下注 1p"))
                add(ActionPreset(Action.BET, (pot * 3 / 2).coerceAtLeast(1), "下注 1.5p"))
                add(ActionPreset(Action.BET, (pot * 2).coerceAtLeast(1), "下注 2p"))
            }
        } else {
            add(ActionPreset(Action.FOLD, 0, "弃牌"))
            add(ActionPreset(Action.CALL, toCall, "跟注 $toCall"))
            val minLegalRaise = toCall + 1
            add(ActionPreset(Action.RAISE, (toCall * 2).coerceAtLeast(minLegalRaise).coerceAtMost(stack), "加注 2x"))
            add(ActionPreset(Action.RAISE, ((toCall * 5 + 1) / 2).coerceAtLeast(minLegalRaise).coerceAtMost(stack), "加注 2.5x"))
            add(ActionPreset(Action.RAISE, (toCall * 3).coerceAtLeast(minLegalRaise).coerceAtMost(stack), "加注 3x"))
            add(ActionPreset(Action.RAISE, ((pot + toCall) + toCall).coerceAtLeast(minLegalRaise).coerceAtMost(stack), "加注 pot"))
        }
        if (stack > 0) add(ActionPreset(Action.ALL_IN, stack, "全下 $stack"))
    }
}
