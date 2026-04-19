package xyz.nextalone.cardtrainer.stats

import kotlinx.serialization.Serializable

/**
 * A single hero poker decision, captured at submit time. Persisted so the
 * stats screen can compute rolling VPIP / PFR / aggression factor / rfi-
 * deviation over many sessions. Timestamps are epoch-millis (from Clock.System
 * via Instant — platform-neutral).
 */
@Serializable
data class PokerDecisionEvent(
    val timestampMs: Long,
    val position: String,         // e.g. "BB"
    val street: String,           // "PREFLOP" / "FLOP" / "TURN" / "RIVER"
    val handLabel: String,        // e.g. "JTo" / "AKs" / "77"
    val boardSize: Int,
    val potBefore: Int,
    val toCall: Int,
    val equityPct: Double? = null,
    val action: String,           // "FOLD" / "CHECK" / "CALL" / "BET" / "RAISE" / "ALL_IN"
    val amount: Int,
    val rfiBaseline: String? = null,   // for preflop-only: "RAISE" / "FOLD" / null
    val villainResponse: String? = null, // "FOLD" / "CALL" / null
    val potAfter: Int,
    val handOver: Boolean,
)

/**
 * A single hero mahjong discard, captured each time the user plays a tile.
 * Enough state to compute top-1 match with the engine, per-session
 * shanten progression, discard-danger profile, etc.
 */
@Serializable
data class MahjongDecisionEvent(
    val timestampMs: Long,
    val missingSuit: String,       // "万" / "条" / "筒"
    val shantenBefore: Int,
    val shantenAfter: Int,
    val tileDiscardedLabel: String,
    val engineTop1Label: String,
    val isEngineTop1: Boolean,
    val liveWaitsAfter: Int,
    val wallRemaining: Int,
)
