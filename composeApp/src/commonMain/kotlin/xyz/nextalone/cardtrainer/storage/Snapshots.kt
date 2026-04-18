package xyz.nextalone.cardtrainer.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.nextalone.cardtrainer.engine.holdem.Action
import xyz.nextalone.cardtrainer.engine.holdem.HoldemTable
import xyz.nextalone.cardtrainer.engine.mahjong.SichuanSnapshot
import xyz.nextalone.cardtrainer.engine.mahjong.Suit as MjSuit

/**
 * Persistable poker session — everything needed to resume a training hand in
 * the exact state the user left it.
 */
@Serializable
data class PokerSession(
    val table: HoldemTable,
    val phase: Phase,
    val userChoiceAction: Action? = null,
    val userChoiceAmount: Int? = null,
    val situationAnalysis: String? = null,
    val choiceEvaluation: String? = null,
) {
    @Serializable enum class Phase { DECIDING, SUBMITTED }
}

/**
 * Persistable mahjong session. Wraps the engine's own snapshot and the UI step
 * so we can resume mid-dealing / mid-choosing-que as well as mid-play.
 */
@Serializable
data class MahjongSession(
    val step: Step,
    val pendingQue: MjSuit,
    val snapshot: SichuanSnapshot,
    val advice: String? = null,
) {
    @Serializable enum class Step { NOT_DEALT, CHOOSING_QUE, PLAYING }
}

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

private const val KEY_POKER = "session.poker"
private const val KEY_MAHJONG = "session.mahjong"

fun AppSettings.savePokerSession(session: PokerSession?) {
    saveRaw(KEY_POKER, session?.let { json.encodeToString(it) })
}

fun AppSettings.loadPokerSession(): PokerSession? =
    loadRaw(KEY_POKER)?.let {
        runCatching { json.decodeFromString<PokerSession>(it) }.getOrNull()
    }

fun AppSettings.saveMahjongSession(session: MahjongSession?) {
    saveRaw(KEY_MAHJONG, session?.let { json.encodeToString(it) })
}

fun AppSettings.loadMahjongSession(): MahjongSession? =
    loadRaw(KEY_MAHJONG)?.let {
        runCatching { json.decodeFromString<MahjongSession>(it) }.getOrNull()
    }
