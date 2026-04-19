package xyz.nextalone.cardtrainer.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.nextalone.cardtrainer.coach.ChatTurn
import xyz.nextalone.cardtrainer.engine.holdem.Action
import xyz.nextalone.cardtrainer.engine.holdem.HoldemTable
import xyz.nextalone.cardtrainer.engine.holdem.multiway.MultiwayTable
import xyz.nextalone.cardtrainer.engine.holdem.multiway.ShowdownOutcome
import xyz.nextalone.cardtrainer.engine.mahjong.SichuanSnapshot
import xyz.nextalone.cardtrainer.engine.mahjong.Suit as MjSuit

/**
 * Persistable poker session — everything needed to resume a training hand in
 * the exact state the user left it.
 *
 * Conversation fields:
 *  - situationTurns / evaluationTurns hold the multi-turn coach chat so
 *    follow-up questions can be asked with full prior context.
 *  - situationAnalysis / choiceEvaluation are pre-conversation legacy fields
 *    kept only so old saved sessions decode cleanly; at load time the screens
 *    migrate any non-error legacy string into a synthetic assistant turn.
 */
@Serializable
data class PokerSession(
    val table: HoldemTable,
    val phase: Phase,
    val userChoiceAction: Action? = null,
    val userChoiceAmount: Int? = null,
    val situationTurns: List<ChatTurn> = emptyList(),
    val evaluationTurns: List<ChatTurn> = emptyList(),
    val situationAnalysis: String? = null,
    val choiceEvaluation: String? = null,
) {
    @Serializable enum class Phase { DECIDING, SUBMITTED }
}

/**
 * Persistable mahjong session. Wraps the engine's own snapshot and the UI step
 * so we can resume mid-dealing / mid-choosing-que as well as mid-play.
 *
 * adviceTurns supersedes the legacy `advice` single-string field; see
 * PokerSession for the migration strategy.
 */
@Serializable
data class MahjongSession(
    val step: Step,
    val pendingQue: MjSuit,
    val snapshot: SichuanSnapshot,
    val adviceTurns: List<ChatTurn> = emptyList(),
    val advice: String? = null,
) {
    @Serializable enum class Step { NOT_DEALT, CHOOSING_QUE, PLAYING }
}

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

/**
 * Persistable multiway poker session. Parallel to [PokerSession]; only one of
 * the two is populated depending on which engine is active at save time.
 *
 * [schemaVersion] is written on every save and read on every load; mismatches
 * trigger a drop-and-reset instead of decoding into a stale shape. Bump it
 * when the seat/table wire format changes incompatibly.
 */
@Serializable
data class MultiwayPokerSession(
    val schemaVersion: Int = SCHEMA_VERSION,
    val table: MultiwayTable,
    val phase: PokerSession.Phase,
    val userChoiceAction: Action? = null,
    val userChoiceAmount: Int? = null,
    val situationTurns: List<ChatTurn> = emptyList(),
    val evaluationTurns: List<ChatTurn> = emptyList(),
    val showdown: ShowdownOutcome? = null,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

private const val KEY_POKER = "session.poker"
private const val KEY_POKER_MULTIWAY = "session.poker.multiway"
private const val KEY_MAHJONG = "session.mahjong"

fun AppSettings.savePokerSession(session: PokerSession?) {
    saveRaw(KEY_POKER, session?.let { json.encodeToString(it) })
}

fun AppSettings.loadPokerSession(): PokerSession? =
    loadRaw(KEY_POKER)?.let {
        runCatching { json.decodeFromString<PokerSession>(it) }.getOrNull()
    }

fun AppSettings.saveMultiwayPokerSession(session: MultiwayPokerSession?) {
    saveRaw(KEY_POKER_MULTIWAY, session?.let { json.encodeToString(it) })
}

fun AppSettings.loadMultiwayPokerSession(): MultiwayPokerSession? {
    val raw = loadRaw(KEY_POKER_MULTIWAY) ?: return null
    val decoded = runCatching { json.decodeFromString<MultiwayPokerSession>(raw) }.getOrNull()
    // Drop sessions from an incompatible older schema rather than surfacing a
    // half-decoded object. A future version bump would migrate here.
    return decoded?.takeIf { it.schemaVersion == MultiwayPokerSession.SCHEMA_VERSION }
}

fun AppSettings.saveMahjongSession(session: MahjongSession?) {
    saveRaw(KEY_MAHJONG, session?.let { json.encodeToString(it) })
}

fun AppSettings.loadMahjongSession(): MahjongSession? =
    loadRaw(KEY_MAHJONG)?.let {
        runCatching { json.decodeFromString<MahjongSession>(it) }.getOrNull()
    }
