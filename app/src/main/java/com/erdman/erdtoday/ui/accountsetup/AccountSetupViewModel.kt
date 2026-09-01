package com.erdman.erdtoday.ui.accountsetup

import androidx.lifecycle.ViewModel
import com.erdman.erdtoday.data.credentials.CredentialsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Backs the first-run Vikunja account setup form (server URL + API token). */
class AccountSetupViewModel(
    private val credentialsManager: CredentialsManager,
) : ViewModel() {

    private val _baseUrl = MutableStateFlow("")
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _apiToken = MutableStateFlow("")
    val apiToken: StateFlow<String> = _apiToken.asStateFlow()

    fun setBaseUrl(value: String) {
        _baseUrl.value = value
    }

    fun setApiToken(value: String) {
        _apiToken.value = value
    }

    /** Persists the entered credentials; the app shell reacts to [CredentialsManager.credentials]. */
    fun connect() {
        credentialsManager.save(_baseUrl.value, _apiToken.value)
    }
}
