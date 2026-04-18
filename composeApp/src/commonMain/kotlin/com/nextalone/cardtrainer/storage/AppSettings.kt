package com.nextalone.cardtrainer.storage

import com.nextalone.cardtrainer.coach.ProviderConfig
import com.nextalone.cardtrainer.coach.ProviderKind
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

    private fun keyApi(k: ProviderKind) = "${k.name}.api_key"
    private fun keyBase(k: ProviderKind) = "${k.name}.base_url"
    private fun keyModel(k: ProviderKind) = "${k.name}.model"

    companion object {
        private const val KEY_PROVIDER = "active_provider"
    }
}

expect fun provideSettings(): Settings
