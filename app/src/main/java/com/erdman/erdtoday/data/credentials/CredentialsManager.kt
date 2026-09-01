package com.erdman.erdtoday.data.credentials

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VikunjaCredentials(
    val baseUrl: String,
    val apiToken: String,
)

/** Self-hosted Vikunja server URL + API token, in encrypted SharedPreferences. */
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
    val credentials: StateFlow<VikunjaCredentials?> = _credentials.asStateFlow()

    private fun readCredentials(): VikunjaCredentials? {
        val baseUrl = prefs.getString(KEY_BASE_URL, null) ?: return null
        val apiToken = prefs.getString(KEY_API_TOKEN, null) ?: return null
        return VikunjaCredentials(baseUrl, apiToken)
    }

    /** [baseUrl] is normalized here (trimmed, trailing slash stripped) so every caller gets a
     *  consistent, slash-free base to build request paths onto. */
    fun save(baseUrl: String, apiToken: String) {
        val normalizedUrl = baseUrl.trim().trimEnd('/')
        val trimmedToken = apiToken.trim()
        prefs.edit()
            .putString(KEY_BASE_URL, normalizedUrl)
            .putString(KEY_API_TOKEN, trimmedToken)
            .apply()
        _credentials.value = VikunjaCredentials(normalizedUrl, trimmedToken)
    }

    fun clear() {
        prefs.edit().clear().apply()
        _credentials.value = null
    }

    companion object {
        private const val PREFS_NAME = "erdtoday_credentials"
        private const val KEY_BASE_URL = "vikunja_base_url"
        private const val KEY_API_TOKEN = "vikunja_api_token"
    }
}
