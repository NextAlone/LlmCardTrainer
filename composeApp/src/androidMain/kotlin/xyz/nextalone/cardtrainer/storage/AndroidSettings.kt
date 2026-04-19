package xyz.nextalone.cardtrainer.storage

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

@SuppressLint("StaticFieldLeak")
object AppContextHolder {
    lateinit var context: Context
}

@Volatile private var encryptedBackend: Boolean = false

/**
 * Persist API keys / base URLs to Android's EncryptedSharedPreferences backed by
 * the Android Keystore, so the on-disk bytes are AES-GCM encrypted. Falls back to
 * plain SharedPreferences if EncryptedSharedPreferences initialization fails (rare
 * — usually only on broken/emulator keystores). When the fallback kicks in,
 * [settingsEncrypted] returns false so the UI can surface a warning.
 */
actual fun provideSettings(): Settings {
    val ctx = AppContextHolder.context
    val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val encrypted = EncryptedSharedPreferences.create(
            ctx,
            "card_trainer_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        encryptedBackend = true
        encrypted
    } catch (t: Throwable) {
        Log.w("AppSettings", "EncryptedSharedPreferences failed, falling back to plain", t)
        encryptedBackend = false
        ctx.getSharedPreferences("card_trainer_settings", Context.MODE_PRIVATE)
    }
    return SharedPreferencesSettings(prefs)
}

actual fun settingsEncrypted(): Boolean = encryptedBackend
