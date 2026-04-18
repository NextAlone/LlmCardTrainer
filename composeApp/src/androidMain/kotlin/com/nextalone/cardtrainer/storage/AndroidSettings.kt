package com.nextalone.cardtrainer.storage

import android.annotation.SuppressLint
import android.content.Context
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

@SuppressLint("StaticFieldLeak")
object AppContextHolder {
    lateinit var context: Context
}

actual fun provideSettings(): Settings {
    val prefs = AppContextHolder.context.getSharedPreferences(
        "card_trainer_settings",
        Context.MODE_PRIVATE,
    )
    return SharedPreferencesSettings(prefs)
}
