package xyz.nextalone.cardtrainer.storage

import xyz.nextalone.cardtrainer.coach.ProviderConfig
import xyz.nextalone.cardtrainer.coach.ProviderKind
import com.russhwolf.settings.Settings

class AppSettings(private val settings: Settings) {

    var providerKind: ProviderKind
        get() = runCatching {
            ProviderKind.valueOf(settings.getString(KEY_PROVIDER, ProviderKind.ANTHROPIC.name))
        }.getOrDefault(ProviderKind.ANTHROPIC)
        set(value) = settings.putString(KEY_PROVIDER, value.name)

    fun apiKey(kind: ProviderKind): String = settings.getString(keyApi(kind), "")
    fun setApiKey(kind: ProviderKind, value: String) = settings.putString(keyApi(kind), value)

    fun baseUrl(kind: ProviderKind): String =
        settings.getString(keyBase(kind), kind.defaultBaseUrl)
    fun setBaseUrl(kind: ProviderKind, value: String) = settings.putString(keyBase(kind), value)

    fun model(kind: ProviderKind): String =
        settings.getString(keyModel(kind), kind.defaultModel)
    fun setModel(kind: ProviderKind, value: String) = settings.putString(keyModel(kind), value)

    /** Active provider config for use by coach. */
    fun activeConfig(): ProviderConfig {
        val k = providerKind
        return ProviderConfig(
            kind = k,
            apiKey = apiKey(k),
            baseUrl = baseUrl(k),
            model = model(k),
        )
    }

    /** Low-level string slot used by Snapshots helpers; `null` clears the key. */
    internal fun saveRaw(key: String, value: String?) {
        if (value == null) settings.remove(key) else settings.putString(key, value)
    }

    /** Low-level string read used by Snapshots helpers. Returns null if absent. */
    internal fun loadRaw(key: String): String? =
        if (settings.hasKey(key)) settings.getString(key, "") else null

    private fun keyApi(k: ProviderKind) = "${k.name}.api_key"
    private fun keyBase(k: ProviderKind) = "${k.name}.base_url"
    private fun keyModel(k: ProviderKind) = "${k.name}.model"

    companion object {
        private const val KEY_PROVIDER = "active_provider"
    }
}

expect fun provideSettings(): Settings

/**
 * Whether [provideSettings] currently persists to an OS-backed encrypted store
 * (Android Keystore / platform keychain). If false, values — including API keys —
 * land on disk in plaintext and the UI should warn the user.
 */
expect fun settingsEncrypted(): Boolean
