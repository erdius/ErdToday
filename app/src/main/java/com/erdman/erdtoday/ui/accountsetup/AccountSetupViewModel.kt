package com.erdman.erdtoday.ui.accountsetup

import androidx.lifecycle.ViewModel
import com.erdman.erdtoday.data.credentials.CredentialsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Backs the first-run Fastmail account setup form (email + app-specific password). */
class AccountSetupViewModel(
    private val credentialsManager: CredentialsManager,
) : ViewModel() {

    private val _email = MutableStateFlow("")
    val email: StateFlow<String> = _email.asStateFlow()

    private val _appPassword = MutableStateFlow("")
    val appPassword: StateFlow<String> = _appPassword.asStateFlow()

    fun setEmail(value: String) {
        _email.value = value
    }

    fun setAppPassword(value: String) {
        _appPassword.value = value
    }

    /** Persists the entered credentials; the app shell reacts to [CredentialsManager.credentials]. */
    fun connect() {
        credentialsManager.save(_email.value, _appPassword.value)
    }
}
