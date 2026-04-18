package xyz.nextalone.cardtrainer.storage

import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import java.util.prefs.Preferences

/**
 * Desktop settings are persisted in `java.util.prefs.Preferences` (backed by a
 * user-scoped file such as `~/Library/Preferences/.../prefs.xml` on macOS).
 * NOTE: this is **plaintext on disk**. A production build should integrate the
 * macOS Keychain (e.g. via `security` CLI or a JNI bridge) for API keys.
 * Tracked as a follow-up; keeping this simple for a personal tool.
 */
actual fun provideSettings(): Settings {
    val prefs = Preferences.userRoot().node("com/nextalone/cardtrainer")
    return PreferencesSettings(prefs)
}
