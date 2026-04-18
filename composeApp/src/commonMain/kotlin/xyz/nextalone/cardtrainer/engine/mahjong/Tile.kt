package xyz.nextalone.cardtrainer.engine.mahjong

enum class Suit(val cn: String, val code: Char) {
    WAN("万", 'm'),
    TIAO("条", 's'),
    TONG("筒", 'p'),
}

data class Tile(val suit: Suit, val number: Int) : Comparable<Tile> {
    init {
        require(number in 1..9)
    }
    val label: String get() = "$number${suit.cn}"
    val code: String get() = "$number${suit.code}"

    override fun compareTo(other: Tile): Int =
        compareValuesBy(this, other, { it.suit.ordinal }, { it.number })
}

object Tiles {
    val ALL: List<Tile> = Suit.entries.flatMap { s -> (1..9).map { Tile(s, it) } }

    /** Full 108-tile wall (Sichuan blood-battle uses 万条筒, 4 copies each). */
    fun wall(seed: Long? = null): ArrayDeque<Tile> {
        val rng = if (seed != null) kotlin.random.Random(seed) else kotlin.random.Random.Default
        val all = ALL.flatMap { listOf(it, it, it, it) }.shuffled(rng)
        return ArrayDeque(all)
    }

    fun parse(code: String): Tile {
        require(code.length == 2)
        val num = code[0].digitToInt()
        val suit = when (code[1]) {
            'm' -> Suit.WAN
            's' -> Suit.TIAO
            'p' -> Suit.TONG
            else -> error("bad code: $code")
        }
        return Tile(suit, num)
    }
}
