package xyz.nextalone.cardtrainer.engine.holdem

import kotlin.random.Random

/**
 * Scripted villain action at the START of a post-flop street.
 *
 * Villain is treated as heads-up and always OOP — they act first on every
 * post-flop street. Distribution is coarse but plausible:
 *  - ~55% check
 *  - ~20% bet ½ pot
 *  - ~15% bet ⅔ pot
 *  - ~10% bet pot (scary overbet)
 *
 * When villain checks, the hero enters the street with toCall=0 and can
 * c-bet / check back. When villain leads, the hero faces an actual bet and
 * picks from the facing-bet preset set (fold / call / raise).
 *
 * This is intentionally independent of hand strength — we don't model
 * villain's hole cards — so the user is training to read sizes and lines,
 * not specific hands.
 */
internal object PostflopVillain {

    data class Action(
        val records: List<ActionRecord>,
        val pot: Int,
        val toCall: Int,
    )

    fun act(street: Street, potAtStreetStart: Int, rng: Random = Random.Default): Action {
        val r = rng.nextDouble()
        val records = mutableListOf<ActionRecord>()
        var pot = potAtStreetStart
        var toCall = 0

        val betSize = when {
            r < 0.55 -> null
            r < 0.75 -> (pot / 2).coerceAtLeast(1)
            r < 0.90 -> (pot * 2 / 3).coerceAtLeast(1)
            else -> pot.coerceAtLeast(1)
        }

        if (betSize == null) {
            records += ActionRecord(street, xyz.nextalone.cardtrainer.engine.holdem.Action.CHECK, 0, pot, 0)
        } else {
            records += ActionRecord(street, xyz.nextalone.cardtrainer.engine.holdem.Action.BET, betSize, pot, 0)
            pot += betSize
            toCall = betSize
        }
        return Action(records, pot, toCall)
    }
}
