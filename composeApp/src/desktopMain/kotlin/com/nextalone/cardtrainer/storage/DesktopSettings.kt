package com.nextalone.cardtrainer.storage

import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import java.util.prefs.Preferences

actual fun provideSettings(): Settings {
    val prefs = Preferences.userRoot().node("com/nextalone/cardtrainer")
    return PreferencesSettings(prefs)
}
