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

    fun recordMultiway(event: MultiwayDecisionEvent) {
        val updated = (loadMultiway() + event).takeLast(MAX_EVENTS)
        settings.saveRaw(KEY_MULTIWAY, json.encodeToString(updated))
    }

    fun loadMultiway(): List<MultiwayDecisionEvent> {
        val raw = settings.loadRaw(KEY_MULTIWAY) ?: return emptyList()
        return runCatching { json.decodeFromString<List<MultiwayDecisionEvent>>(raw) }
            .getOrDefault(emptyList())
    }

    /**
     * Back-fill the terminal result onto every event that shares [handId].
     * Called once from the multiway screen when a showdown / fold finishes
     * the hand; events written during the hand had heroWonHand = null.
     */
    fun updateMultiwayHandResult(handId: Long, heroWon: Boolean, resolution: String) {
        val all = loadMultiway()
        if (all.none { it.handId == handId }) return
        val patched = all.map {
            if (it.handId == handId) {
                it.copy(handOver = true, heroWonHand = heroWon, handResolution = resolution)
            } else {
                it
            }
        }
        settings.saveRaw(KEY_MULTIWAY, json.encodeToString(patched))
    }

    fun clearPoker() = settings.saveRaw(KEY_POKER, null)
    fun clearMahjong() = settings.saveRaw(KEY_MAHJONG, null)
    fun clearMultiway() = settings.saveRaw(KEY_MULTIWAY, null)

    companion object {
        const val MAX_EVENTS = 500
        private const val KEY_POKER = "stats.poker.events"
        private const val KEY_MAHJONG = "stats.mahjong.events"
        private const val KEY_MULTIWAY = "stats.poker.multiway.events"
    }
}
