package com.erdman.erdtoday

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.erdman.erdtoday.di.appContainer
import com.erdman.erdtoday.reminder.EXTRA_TASK_ID
import com.erdman.erdtoday.ui.accountsetup.AccountSetupScreen
import com.erdman.erdtoday.ui.nav.AppShell
import com.erdman.erdtoday.ui.theme.TodayTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /** Set when launched from a reminder notification; the shell consumes it to open the to-do. */
    private val deepLinkTaskId = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeDeepLink(intent)
        setContent {
            TodayTheme {
                // No Fastmail account yet: show the setup form instead of the normal app shell.
                val credentials by appContainer().credentialsManager.credentials.collectAsState()
                if (credentials == null) {
                    AccountSetupScreen()
                } else {
                    AppShell(deepLinkTaskId)
                }
            }
        }
    }

    // launchMode=singleTop: a notification tap while the app is open arrives here, not onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeDeepLink(intent)
    }

    private fun consumeDeepLink(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_TASK_ID, -1L) ?: -1L
        if (id > 0L) {
            deepLinkTaskId.value = id
            // One-shot: drop the extra so a recreation (config change) doesn't re-navigate.
            intent?.removeExtra(EXTRA_TASK_ID)
        }
    }
}
