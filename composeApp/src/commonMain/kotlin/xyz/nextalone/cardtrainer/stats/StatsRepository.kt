package xyz.nextalone.cardtrainer.stats

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.nextalone.cardtrainer.storage.AppSettings

/**
 * Persists decision events so the Stats screen can compute running behavioural
 * metrics across sessions. Capped at [MAX_EVENTS] per game to keep the settings
 * blob bounded — old events are dropped FIFO when the cap is hit.
 *
 * Storage is the same multiplatform-settings backend as everything else
 * (EncryptedSharedPreferences on Android, java.util.prefs on desktop), so no
 * extra filesystem plumbing needed.
 */
class StatsRepository(private val settings: AppSettings) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun recordPoker(event: PokerDecisionEvent) {
        val updated = (loadPoker() + event).takeLast(MAX_EVENTS)
        settings.saveRaw(KEY_POKER, json.encodeToString(updated))
    }

    fun loadPoker(): List<PokerDecisionEvent> {
        val raw = settings.loadRaw(KEY_POKER) ?: return emptyList()
        return runCatching { json.decodeFromString<List<PokerDecisionEvent>>(raw) }
            .getOrDefault(emptyList())
    }

    fun recordMahjong(event: MahjongDecisionEvent) {
        val updated = (loadMahjong() + event).takeLast(MAX_EVENTS)
        settings.saveRaw(KEY_MAHJONG, json.encodeToString(updated))
    }

    fun loadMahjong(): List<MahjongDecisionEvent> {
        val raw = settings.loadRaw(KEY_MAHJONG) ?: return emptyList()
        return runCatching { json.decodeFromString<List<MahjongDecisionEvent>>(raw) }
            .getOrDefault(emptyList())
    }

    fun clearPoker() = settings.saveRaw(KEY_POKER, null)
    fun clearMahjong() = settings.saveRaw(KEY_MAHJONG, null)

    companion object {
        const val MAX_EVENTS = 500
        private const val KEY_POKER = "stats.poker.events"
        private const val KEY_MAHJONG = "stats.mahjong.events"
    }
}
