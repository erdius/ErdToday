package com.erdman.erdtoday.data.credentials

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FastmailCredentials(
    val email: String,
    val appPassword: String,
)

/** Fastmail account email + app-specific password, in encrypted SharedPreferences. */
class CredentialsManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val _credentials = MutableStateFlow(readCredentials())
    val credentials: StateFlow<FastmailCredentials?> = _credentials.asStateFlow()

    private fun readCredentials(): FastmailCredentials? {
        val email = prefs.getString(KEY_EMAIL, null) ?: return null
        val appPassword = prefs.getString(KEY_APP_PASSWORD, null) ?: return null
        return FastmailCredentials(email, appPassword)
    }

    fun save(email: String, appPassword: String) {
        val trimmedEmail = email.trim()
        prefs.edit()
            .putString(KEY_EMAIL, trimmedEmail)
            .putString(KEY_APP_PASSWORD, appPassword)
            .apply()
        _credentials.value = FastmailCredentials(trimmedEmail, appPassword)
    }

    fun clear() {
        prefs.edit().clear().apply()
        _credentials.value = null
    }

    companion object {
        private const val PREFS_NAME = "erdtoday_credentials"
        private const val KEY_EMAIL = "fastmail_email"
        private const val KEY_APP_PASSWORD = "fastmail_app_password"
    }
}
