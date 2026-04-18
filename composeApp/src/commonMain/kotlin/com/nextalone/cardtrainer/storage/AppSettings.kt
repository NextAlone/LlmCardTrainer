package com.nextalone.cardtrainer.storage

import com.russhwolf.settings.Settings

class AppSettings(private val settings: Settings) {

    var apiKey: String
        get() = settings.getString(KEY_API_KEY, "")
        set(value) = settings.putString(KEY_API_KEY, value)

    var model: String
        get() = settings.getString(KEY_MODEL, "claude-sonnet-4-6")
        set(value) = settings.putString(KEY_MODEL, value)

    companion object {
        private const val KEY_API_KEY = "anthropic_api_key"
        private const val KEY_MODEL = "anthropic_model"
    }
}

expect fun provideSettings(): Settings
