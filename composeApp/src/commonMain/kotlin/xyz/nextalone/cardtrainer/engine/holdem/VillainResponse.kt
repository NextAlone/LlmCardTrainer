package xyz.nextalone.cardtrainer.engine.holdem

import kotlin.random.Random

/**
 * Second villain action: the hero just bet / raised / went all-in, and the
 * villain has to fold or call.
 *
 * Fold probability scales with bet ratio (fraction of pot the hero staked).
 * Small bets get called a lot; overbets get folded through. Re-raises are
 * intentionally NOT modelled in this pass — that would require a full
 * multi-turn single-street UI loop, which is a larger refactor.
 *
 *   ratio ≤ 0.5     →  fold 20%, call 80%
 *   ratio ≤ 1.0     →  fold 40%, call 60%
 *   ratio ≤ 1.5     →  fold 55%, call 45%
 *   ratio >  1.5    →  fold 70%, call 30%
 *
 * 'ratio' is the hero's bet size divided by the pot BEFORE the hero's bet.
 */
internal object VillainResponse {

    data class Response(
        val records: List<ActionRecord>,
        val addedToPot: Int,
        val folded: Boolean,
        val newToCall: Int,
    )

    fun react(
        table: HoldemTable,
        heroAction: Action,
        heroAmount: Int,
        rng: Random = Random.Default,
    ): Response {
        if (heroAction !in listOf(Action.BET, Action.RAISE, Action.ALL_IN)) {
            return Response(emptyList(), 0, folded = false, newToCall = 0)
        }
        val potBeforeHero = (table.pot - heroAmount).coerceAtLeast(1)
        val ratio = heroAmount.toDouble() / potBeforeHero
        val foldProb = when {
            ratio <= 0.5 -> 0.20
            ratio <= 1.0 -> 0.40
            ratio <= 1.5 -> 0.55
            else -> 0.70
        }
        val willFold = rng.nextDouble() < foldProb
        return if (willFold) {
            Response(
                records = listOf(
                    ActionRecord(
                        street = table.street,
                        action = Action.FOLD,
                        amount = 0,
                        potBefore = table.pot,
                        toCall = heroAmount,
                    ),
                ),
                addedToPot = 0,
                folded = true,
                newToCall = 0,
            )
        } else {
            Response(
                records = listOf(
                    ActionRecord(
                        street = table.street,
                        action = Action.CALL,
                        amount = heroAmount,
                        potBefore = table.pot,
                        toCall = heroAmount,
                    ),
                ),
                addedToPot = heroAmount,
                folded = false,
                newToCall = 0,
            )
        }
    }
}
