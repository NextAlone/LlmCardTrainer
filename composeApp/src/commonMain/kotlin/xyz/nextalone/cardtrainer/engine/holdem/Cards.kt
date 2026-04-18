package xyz.nextalone.cardtrainer.engine.holdem

enum class Suit(val symbol: String) {
    SPADES("♠"),
    HEARTS("♥"),
    DIAMONDS("♦"),
    CLUBS("♣"),
}

enum class Rank(val value: Int, val label: String) {
    TWO(2, "2"),
    THREE(3, "3"),
    FOUR(4, "4"),
    FIVE(5, "5"),
    SIX(6, "6"),
    SEVEN(7, "7"),
    EIGHT(8, "8"),
    NINE(9, "9"),
    TEN(10, "T"),
    JACK(11, "J"),
    QUEEN(12, "Q"),
    KING(13, "K"),
    ACE(14, "A"),
}

data class Card(val rank: Rank, val suit: Suit) {
    val label: String get() = "${rank.label}${suit.symbol}"
    val isRed: Boolean get() = suit == Suit.HEARTS || suit == Suit.DIAMONDS
}

class Deck(seed: Long? = null) {
    private val rng = if (seed != null) kotlin.random.Random(seed) else kotlin.random.Random.Default
    private val cards: ArrayDeque<Card> = ArrayDeque(
        Suit.entries.flatMap { s -> Rank.entries.map { Card(it, s) } }.shuffled(rng)
    )

    fun deal(): Card = cards.removeFirst()
    fun dealN(n: Int): List<Card> = List(n) { deal() }
    fun remaining(): Int = cards.size

    fun removeSpecific(used: Collection<Card>) {
        cards.removeAll(used.toSet())
    }
}
